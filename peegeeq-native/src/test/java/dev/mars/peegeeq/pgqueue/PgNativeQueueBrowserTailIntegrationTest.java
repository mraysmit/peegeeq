package dev.mars.peegeeq.pgqueue;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for {@link dev.mars.peegeeq.api.messaging.QueueBrowser#tail} on the native
 * queue — the NON-DESTRUCTIVE live observe capability (Phase 12, §7.12).
 *
 * <p>The core guarantee: observing new messages via {@code tail} must never consume them — a
 * message pushed to the tail must still be browsable afterwards, exactly like a {@code SELECT}
 * that does not delete its rows.
 *
 * <p>Written test-first: it fails against the current code (the {@code QueueBrowser.tail}
 * default returns {@code UnsupportedOperationException}) and passes once
 * {@code PgNativeQueueBrowser} implements {@code tail} via LISTEN/NOTIFY → browse → push.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-06-17
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
class PgNativeQueueBrowserTailIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(PgNativeQueueBrowserTailIntegrationTest.class);

    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private PgDatabaseService databaseService;
    private PgNativeQueueFactory factory;

    @BeforeEach
    void setUp(VertxTestContext ctx) {
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA,
                SchemaComponent.NATIVE_QUEUE, SchemaComponent.OUTBOX, SchemaComponent.DEAD_LETTER_QUEUE);

        Properties testProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .build();

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("native-tail-test", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start()
                .onSuccess(v -> {
                    databaseService = new PgDatabaseService(manager);
                    factory = new PgNativeQueueFactory(databaseService, config);
                    ctx.completeNow();
                })
                .onFailure(ctx::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws InterruptedException {
        (factory != null ? factory.close() : Future.<Void>succeededFuture())
                .compose(v -> manager != null ? manager.closeReactive() : Future.succeededFuture())
                .onSuccess(v -> testContext.completeNow())
                .onFailure(err -> {
                    logger.warn("Error during teardown: {}", err.getMessage());
                    testContext.completeNow();
                });
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    @Timeout(value = 15, timeUnit = TimeUnit.SECONDS)
    void tail_observesNewMessage_andLeavesItBrowsable(VertxTestContext ctx) {
        String topic = "tail-nondestructive-" + System.currentTimeMillis();
        Promise<Message<String>> observed = Promise.promise();
        Promise<Message<String>> consumedByRealConsumer = Promise.promise();

        factory.<String>createBrowser(topic, String.class)
                .compose(browser ->
                        // Establish the non-destructive live tail.
                        browser.tail(message -> {
                                    observed.tryComplete(message);
                                    return Future.succeededFuture();
                                })
                                // Once subscribed, publish a message — the tail must observe it.
                                .compose(v -> {
                                    MessageProducer<String> producer = factory.createProducer(topic, String.class);
                                    return producer.send("hello-tail");
                                })
                                .compose(v -> observed.future())
                                .compose(msg -> {
                                    ctx.verify(() -> assertEquals("hello-tail", msg.getPayload(),
                                            "tail must observe the published message"));
                                    // Proof 1 — still browsable: a non-destructive read still sees it.
                                    return browser.browse(50, 0);
                                })
                                .compose(messages -> {
                                    ctx.verify(() -> assertTrue(
                                            messages.stream().anyMatch(m -> "hello-tail".equals(m.getPayload())),
                                            "observing via tail must NOT consume — the message must remain browsable"));
                                    // Proof 2 (the decisive one) — a REAL consumer must STILL receive it.
                                    // Observing must not steal the message from the application's real
                                    // consumers; a "SELECT that also deletes" would fail this assertion.
                                    MessageConsumer<String> consumer = factory.createConsumer(topic, String.class);
                                    return consumer.subscribe(m -> {
                                                consumedByRealConsumer.tryComplete(m);
                                                return Future.succeededFuture();
                                            })
                                            .compose(v -> consumedByRealConsumer.future())
                                            .eventually(() -> {
                                                try { consumer.close(); } catch (Exception ignored) { }
                                                return Future.succeededFuture();
                                            });
                                })
                                .eventually(() -> {
                                    try { browser.close(); } catch (Exception ignored) { }
                                    return Future.succeededFuture();
                                }))
                .onSuccess(consumedMsg -> ctx.verify(() -> {
                    assertEquals("hello-tail", consumedMsg.getPayload(),
                            "a real consumer must still receive the observed message — observe did not consume it");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    // ============================================================
    // Fail-fast guards — every tail() setup fault must surface as a failed Future,
    // never a silent 30s VertxTestContext timeout. These lock in the contract that
    // protects against the "errors don't surface → hang" deviation. A regression that
    // removes a guard (or swallows the failure) turns these from PASS into a timeout.
    // ============================================================

    @Test
    @Timeout(value = 10, timeUnit = TimeUnit.SECONDS)
    void tail_onClosedBrowser_failsFastWithoutHanging(VertxTestContext ctx) {
        factory.<String>createBrowser("tail-closed-" + System.currentTimeMillis(), String.class)
                .compose(browser -> {
                    browser.close();
                    // tail on a closed browser must reject immediately, not hang.
                    return browser.tail(message -> Future.succeededFuture());
                })
                .onSuccess(v -> ctx.failNow("tail on a closed browser must fail, not succeed"))
                .onFailure(err -> ctx.verify(() -> {
                    assertInstanceOf(IllegalStateException.class, err,
                            "closed browser must surface IllegalStateException");
                    ctx.completeNow();
                }));
    }

    @Test
    @Timeout(value = 10, timeUnit = TimeUnit.SECONDS)
    void tail_withNullHandler_failsFastWithoutHanging(VertxTestContext ctx) {
        factory.<String>createBrowser("tail-null-" + System.currentTimeMillis(), String.class)
                .compose(browser -> browser.tail(null)
                        .eventually(() -> {
                            try { browser.close(); } catch (Exception ignored) { }
                            return Future.succeededFuture();
                        }))
                .onSuccess(v -> ctx.failNow("tail(null) must fail, not succeed"))
                .onFailure(err -> ctx.verify(() -> {
                    assertInstanceOf(IllegalArgumentException.class, err,
                            "null handler must surface IllegalArgumentException");
                    ctx.completeNow();
                }));
    }

    @Test
    @Timeout(value = 10, timeUnit = TimeUnit.SECONDS)
    void tail_calledTwice_secondFailsFastWithoutHanging(VertxTestContext ctx) {
        String topic = "tail-twice-" + System.currentTimeMillis();
        factory.<String>createBrowser(topic, String.class)
                .compose(browser ->
                        // First tail establishes the live observe; once it resolves a second
                        // tail on the same browser must reject (one LISTEN connection per browser).
                        browser.tail(message -> Future.succeededFuture())
                                .compose(v -> browser.tail(message -> Future.succeededFuture()))
                                .eventually(() -> {
                                    try { browser.close(); } catch (Exception ignored) { }
                                    return Future.succeededFuture();
                                }))
                .onSuccess(v -> ctx.failNow("second tail() on the same browser must fail, not succeed"))
                .onFailure(err -> ctx.verify(() -> {
                    assertInstanceOf(IllegalStateException.class, err,
                            "a second concurrent tail must surface IllegalStateException");
                    ctx.completeNow();
                }));
    }

    // ============================================================
    // Resilience — the live tail must survive a dropped LISTEN connection and catch up.
    //
    // This is the signature guarantee of the watermark-drain observer design
    // (docs-design/dev/non-destructive-queue-observer-design-18-Jun-2026.md): a NOTIFY
    // delivered while the observer is disconnected is LOST, so on reconnect the observer must
    // re-read everything above its high-water mark (a plain non-destructive SELECT) rather than
    // trusting per-NOTIFY ids. A read-by-single-id tail with no reconnect cannot satisfy this —
    // the gap message is never observed and the test times out.
    // ============================================================

    @Test
    @Timeout(value = 10, timeUnit = TimeUnit.SECONDS)
    void tail_reconnectsAfterListenConnectionDropped_andObservesGapMessage(VertxTestContext ctx) {
        String topic = "tail-reconnect-" + System.currentTimeMillis();
        // Same package as NativeQueueChannels — compute the exact channel the observer LISTENs on.
        String channel = NativeQueueChannels.channelFor(PostgreSQLTestConstants.TEST_SCHEMA, topic);
        MessageProducer<String> producer = factory.createProducer(topic, String.class);

        Promise<Message<String>> beforeObserved = Promise.promise();
        Promise<Message<String>> afterObserved = Promise.promise();

        factory.<String>createBrowser(topic, String.class)
                .compose(browser -> browser
                        .tail(msg -> {
                            // First delivery proves the tail is live; the second is the gap message.
                            if (!beforeObserved.tryComplete(msg)) {
                                afterObserved.tryComplete(msg);
                            }
                            return Future.succeededFuture();
                        })
                        // 1. Publish and confirm the tail observes it (subscription is live).
                        .compose(v -> producer.send("before-drop"))
                        .compose(v -> beforeObserved.future())
                        // 2. Kill ONLY the observer's dedicated LISTEN backend connection.
                        .compose(before -> killListenBackend(channel))
                        // 3. Publish during the outage — its NOTIFY is lost while disconnected.
                        .compose(v -> producer.send("after-drop"))
                        // 4. The observer must reconnect and catch up to observe the gap message.
                        .compose(v -> afterObserved.future())
                        .eventually(() -> {
                            try { browser.close(); }
                            catch (Exception e) { logger.warn("Error closing browser: {}", e.getMessage()); }
                            return Future.succeededFuture();
                        }))
                .onSuccess(after -> ctx.verify(() -> {
                    assertEquals("after-drop", after.getPayload(),
                            "tail must reconnect after its LISTEN connection drops and observe the "
                                    + "message published during the outage");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    /**
     * Terminates the observer's dedicated LISTEN backend by matching its exact {@code LISTEN}
     * statement in {@code pg_stat_activity} (it sits idle after subscribing). Targeted so the
     * shared pool is left intact — this exercises the observer's reconnect, not the pool's.
     */
    private Future<Void> killListenBackend(String channel) {
        return databaseService.getPool().preparedQuery(
                        "SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                                + "WHERE datname = current_database() AND state = 'idle' AND query = $1")
                .execute(Tuple.of("LISTEN \"" + channel + "\""))
                .compose(rows -> {
                    int terminated = rows.size();
                    logger.info("Terminated {} LISTEN backend(s) for channel {}", terminated, channel);
                    if (terminated < 1) {
                        return Future.failedFuture(new AssertionError(
                                "Expected to terminate the observer's LISTEN backend for channel "
                                        + channel + " but matched none"));
                    }
                    return Future.succeededFuture();
                });
    }
}
