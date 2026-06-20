package dev.mars.peegeeq.outbox;

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
 * Integration test for {@link dev.mars.peegeeq.api.messaging.QueueBrowser#tail} on the OUTBOX
 * queue — the NON-DESTRUCTIVE live observe capability (Phase 12.3, §7.12).
 *
 * <p>The outbox has no insert {@code NOTIFY} (it is a poll-based queue: {@code OutboxConsumer}
 * uses {@code vertx.setPeriodic}, not LISTEN), so the outbox tail is a non-destructive
 * <em>browse-poll</em> — same watermark semantics as the native observer, but driven by a periodic
 * timer instead of NOTIFY. The core guarantee is identical: observing a message via {@code tail}
 * must never consume it — a message pushed to the tail must still be browsable afterwards.
 *
 * <p>Written test-first: it fails against the current code (the {@code QueueBrowser.tail} default
 * throws {@code UnsupportedOperationException} — {@code OutboxQueueBrowser} does not yet override
 * it) and passes once the poll-based observer is implemented.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-06-18
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
class OutboxQueueBrowserTailIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxQueueBrowserTailIntegrationTest.class);

    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private PgDatabaseService databaseService;
    private OutboxFactory factory;

    @BeforeEach
    void setUp(VertxTestContext ctx) {
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA,
                SchemaComponent.OUTBOX, SchemaComponent.NATIVE_QUEUE, SchemaComponent.DEAD_LETTER_QUEUE);

        Properties testProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .build();

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("outbox-tail-test", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start()
                .onSuccess(v -> {
                    databaseService = new PgDatabaseService(manager);
                    factory = new OutboxFactory(databaseService, config);
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
        String topic = "outbox-tail-nondestructive-" + System.currentTimeMillis();
        Promise<Message<String>> observed = Promise.promise();

        Promise<Message<String>> consumedByRealConsumer = Promise.promise();

        factory.<String>createBrowser(topic, String.class)
                .compose(browser ->
                        // Establish the non-destructive live tail (poll-based for the outbox).
                        browser.tail(message -> {
                                    observed.tryComplete(message);
                                    return Future.succeededFuture();
                                })
                                // Once subscribed, publish a message — the tail must observe it.
                                .compose(v -> {
                                    MessageProducer<String> producer = factory.createProducer(topic, String.class);
                                    return producer.send("hello-outbox-tail");
                                })
                                .compose(v -> observed.future())
                                .compose(msg -> {
                                    ctx.verify(() -> assertEquals("hello-outbox-tail", msg.getPayload(),
                                            "tail must observe the published message"));
                                    // Proof 1 — still browsable: a non-destructive read still sees it.
                                    return browser.browse(50, 0);
                                })
                                .compose(messages -> {
                                    ctx.verify(() -> assertTrue(
                                            messages.stream().anyMatch(m -> "hello-outbox-tail".equals(m.getPayload())),
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
                                                try { consumer.close(); }
                                                catch (Exception e) { logger.warn("Error closing consumer: {}", e.getMessage()); }
                                                return Future.succeededFuture();
                                            });
                                })
                                .eventually(() -> {
                                    try { browser.close(); }
                                    catch (Exception e) { logger.warn("Error closing browser: {}", e.getMessage()); }
                                    return Future.succeededFuture();
                                }))
                .onSuccess(consumedMsg -> ctx.verify(() -> {
                    assertEquals("hello-outbox-tail", consumedMsg.getPayload(),
                            "a real consumer must still receive the observed message — observe did not consume it");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }
}
