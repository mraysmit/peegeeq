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

import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.ConsumerGroup;
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

import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests verifying that filter exceptions are propagated to the framework
 * retry and dead-letter mechanism rather than being swallowed.
 *
 * <p>Regression tests for the fix to {@code OutboxConsumerGroupMember.acceptsMessage()}
 * which previously caught filter exceptions and returned {@code false}, causing an
 * infinite PENDING loop (retry_count never incremented).</p>
 *
 * <ul>
 *   <li>TC-1: filter exception → retry_count increments (not stuck at 0)</li>
 *   <li>TC-2: filter exception exhausts max retries → dead_letter_queue entry, outbox DEAD_LETTER</li>
 *   <li>TC-3: circuit breaker OPEN → retry_count stops incrementing (CB protection intact)</li>
 *   <li>TC-6: DLQ write is atomic — exactly one DLQ entry, outbox status consistent</li>
 * </ul>
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
@DisplayName("Filter exception propagation to framework retry / DLQ")
public class FilterExceptionFrameworkRetryIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(FilterExceptionFrameworkRetryIntegrationTest.class);

    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private OutboxFactory outboxFactory;
    private MessageProducer<String> producer;
    private ConsumerGroup<String> consumerGroup;
    private String testTopic;

    @BeforeEach
    void setUp() {
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);
        testTopic = "filter-exc-retry-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Starts the manager and creates the shared factory, producer, and consumer group.
     * Each test calls this with its required max-retries value.
     */
    private Future<Void> startManager(String maxRetries) {
        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .property("peegeeq.queue.max-retries", maxRetries)
                .property("peegeeq.queue.polling-interval", "PT0.1S")
                .build();

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        return manager.start().map(v -> {
            DatabaseService databaseService = new PgDatabaseService(manager);
            outboxFactory = new OutboxFactory(databaseService, config);
            producer = outboxFactory.createProducer(testTopic, String.class);
            consumerGroup = outboxFactory.createConsumerGroup("filter-exc-group", testTopic, String.class);
            return null;
        });
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws Exception {
        if (consumerGroup != null) {
            consumerGroup.stop()
                    .compose(v -> consumerGroup.close())
                    .onFailure(e -> logger.warn("consumerGroup stop/close failed in tearDown", e));
        }
        if (producer != null) {
            producer.close();
        }
        if (outboxFactory != null) {
            outboxFactory.close();
        }
        if (manager != null) {
            manager.closeReactive()
                    .onSuccess(v -> testContext.completeNow())
                    .onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }
        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS));
    }

    // =========================================================================
    // TC-1: Filter exception → retry_count increments
    // =========================================================================

    @Test
    @DisplayName("TC-1: filter exception propagates to retry handler — retry_count increments")
    void tc1_filterExceptionIncrementsRetryCount(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== TC-1: filter exception propagates to retry handler ===");

        startManager("10")
                .compose(v -> {
                    consumerGroup.addConsumer("member-tc1",
                            message -> Future.succeededFuture(),
                            msg -> { throw new RuntimeException("TC-1 EXPECTED FILTER EXCEPTION"); });
                    return consumerGroup.start();
                })
                .compose(v -> {
                    logger.info("ERROR ===== INTENTIONAL ERROR TEST ===== " +
                                "The next ERROR log ('TC-1 EXPECTED FILTER EXCEPTION') is EXPECTED");
                    return producer.send("tc1-message");
                })
                .compose(v -> vertx.timer(300))
                .compose(v -> awaitDatabaseCondition(vertx,
                        () -> queryRetryCountAsync(testTopic).map(rc -> rc > 0),
                        10_000,
                        "retry_count should have incremented after filter exception — " +
                        "if still 0 the exception was swallowed (old bug)"))
                .compose(v -> queryRetryCountAsync(testTopic))
                .onSuccess(retryCount -> testContext.verify(() -> {
                    assertTrue(retryCount > 0,
                            "retry_count should be > 0 after filter exception, was " + retryCount +
                            " — exception was swallowed (old bug present)");
                    logger.info("TC-1 PASSED: retry_count={}, outbox has been retried", retryCount);
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(20, TimeUnit.SECONDS));
    }

    // =========================================================================
    // TC-2: Filter exception exhausts retries → dead_letter_queue + DEAD_LETTER
    // =========================================================================

    @Test
    @DisplayName("TC-2: filter exception exhausts retries — message moves to dead_letter_queue")
    void tc2_filterExceptionExhaustsRetries_movesToDLQ(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== TC-2: filter exception exhausts retries → DLQ ===");

        startManager("2")
                .compose(v -> {
                    consumerGroup.addConsumer("member-tc2",
                            message -> Future.succeededFuture(),
                            msg -> { throw new RuntimeException("TC-2 EXPECTED FILTER EXCEPTION"); });
                    return consumerGroup.start();
                })
                .compose(v -> {
                    logger.info("ERROR ===== INTENTIONAL ERROR TEST ===== " +
                                "The next ERROR log ('TC-2 EXPECTED FILTER EXCEPTION') is EXPECTED");
                    return producer.send("tc2-message");
                })
                .compose(v -> awaitDatabaseCondition(vertx,
                        () -> queryStatusAsync(testTopic).map("DEAD_LETTER"::equals),
                        20_000,
                        "outbox status should be DEAD_LETTER after exhausting retries — " +
                        "if still PENDING the exception was swallowed (old bug)"))
                .compose(v -> queryRetryCountAsync(testTopic))
                .compose(retryCount -> {
                    testContext.verify(() -> assertEquals(2, retryCount,
                            "outbox.retry_count should equal max-retries (2), was " + retryCount));
                    return queryDLQCountAsync(testTopic);
                })
                .onSuccess(dlqCount -> testContext.verify(() -> {
                    assertEquals(1, dlqCount,
                            "dead_letter_queue should have exactly 1 entry, had " + dlqCount);
                    logger.info("TC-2 PASSED: retry_count=2, status=DEAD_LETTER, dlq_count=1");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    // =========================================================================
    // TC-3: Circuit breaker OPEN → retry_count stops incrementing
    // =========================================================================

    @Test
    @DisplayName("TC-3: circuit breaker OPEN — retry_count does not increment further")
    void tc3_circuitBreakerOpen_retryCountStopsIncrementing(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== TC-3: circuit breaker OPEN — retry_count stops incrementing ===");

        // max-retries=50: the message must survive long enough for CB to open.
        // Default CB: failureThreshold=5, minimumRequests=10.
        // With polling-interval=100ms and all attempts failing, CB opens after ~10 cycles (1s).
        // After CB opens: acceptsMessage() returns false (no exception) → MessageFilteredException
        // → resetFilteredMessageToPending() → retry_count stays the same.
        AtomicInteger retryCountAtCbOpen = new AtomicInteger(-1);

        startManager("50")
                .compose(v -> {
                    consumerGroup.addConsumer("member-tc3",
                            message -> Future.succeededFuture(),
                            msg -> { throw new RuntimeException("TC-3 EXPECTED FILTER EXCEPTION"); });
                    return consumerGroup.start();
                })
                .compose(v -> {
                    logger.info("ERROR ===== INTENTIONAL ERROR TEST ===== " +
                                "The next ERROR log ('TC-3 EXPECTED FILTER EXCEPTION') is EXPECTED");
                    return producer.send("tc3-message");
                })
                // Wait until retry_count >= 10 (CB has opened: minimumRequests=10 all failed)
                .compose(v -> awaitDatabaseCondition(vertx,
                        () -> queryRetryCountAsync(testTopic).map(rc -> {
                            if (rc >= 10) {
                                retryCountAtCbOpen.compareAndSet(-1, rc);
                                return true;
                            }
                            return false;
                        }),
                        15_000,
                        "retry_count should reach 10 — takes ~1s with polling-interval=100ms"))
                // Wait 2 more seconds (≈20 additional polling cycles) — CB is OPEN, no increments expected
                .compose(v -> vertx.timer(2000))
                .compose(v -> queryRetryCountAsync(testTopic))
                .onSuccess(finalRetryCount -> testContext.verify(() -> {
                    int capturedAtOpen = retryCountAtCbOpen.get();
                    logger.info("TC-3: retry_count at CB open={}, retry_count after 2s wait={}",
                            capturedAtOpen, finalRetryCount);

                    // CB OPEN means no further filter invocations, so retry_count should not grow.
                    // Allow a margin of +2 for any in-flight cycles at the moment CB opened.
                    assertTrue(finalRetryCount <= capturedAtOpen + 2,
                            "retry_count should stop incrementing when CB is OPEN — " +
                            "was " + capturedAtOpen + " at CB open, is " + finalRetryCount +
                            " after 2s (20 polling cycles). CB protection appears broken.");

                    logger.info("TC-3 PASSED: retry_count stopped at {} (CB open at {})",
                            finalRetryCount, capturedAtOpen);
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    // =========================================================================
    // TC-6: DLQ write is atomic — outbox + dead_letter_queue are consistent
    // =========================================================================

    @Test
    @DisplayName("TC-6: DLQ write is atomic — exactly one DLQ entry, outbox status DEAD_LETTER consistent")
    void tc6_dlqWriteIsAtomic(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== TC-6: DLQ write atomicity ===");

        startManager("1")
                .compose(v -> {
                    consumerGroup.addConsumer("member-tc6",
                            message -> Future.succeededFuture(),
                            msg -> { throw new RuntimeException("TC-6 EXPECTED FILTER EXCEPTION"); });
                    return consumerGroup.start();
                })
                .compose(v -> {
                    logger.info("ERROR ===== INTENTIONAL ERROR TEST ===== " +
                                "The next ERROR log ('TC-6 EXPECTED FILTER EXCEPTION') is EXPECTED");
                    return producer.send("tc6-message");
                })
                .compose(v -> awaitDatabaseCondition(vertx,
                        () -> queryStatusAsync(testTopic).map("DEAD_LETTER"::equals),
                        15_000,
                        "outbox status should reach DEAD_LETTER"))
                // Extra wait to confirm no duplicate DLQ writes race
                .compose(v -> vertx.timer(1000))
                .compose(v -> queryDLQCountAsync(testTopic))
                .compose(dlqCount -> {
                    testContext.verify(() -> assertEquals(1, dlqCount,
                            "dead_letter_queue must have exactly ONE entry (atomic write), had " + dlqCount +
                            ". Multiple entries would indicate a non-atomic write bug."));
                    return queryStatusAsync(testTopic);
                })
                .onSuccess(status -> testContext.verify(() -> {
                    assertEquals("DEAD_LETTER", status,
                            "outbox.status must be DEAD_LETTER for atomic consistency, was " + status);
                    logger.info("TC-6 PASSED: dlq_count=1, outbox status=DEAD_LETTER");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(25, TimeUnit.SECONDS));
    }

    // =========================================================================
    // Async DB helpers — all return Future<T>, no blocking
    // =========================================================================

    private Future<Integer> queryRetryCountAsync(String topic) {
        return manager.getPool()
                .preparedQuery("SELECT retry_count FROM outbox WHERE topic = $1 LIMIT 1")
                .execute(Tuple.of(topic))
                .map(rows -> {
                    var iterator = rows.iterator();
                    return iterator.hasNext() ? iterator.next().getInteger("retry_count") : 0;
                });
    }

    private Future<String> queryStatusAsync(String topic) {
        return manager.getPool()
                .preparedQuery("SELECT status FROM outbox WHERE topic = $1 LIMIT 1")
                .execute(Tuple.of(topic))
                .map(rows -> {
                    var iterator = rows.iterator();
                    return iterator.hasNext() ? iterator.next().getString("status") : "";
                });
    }

    private Future<Integer> queryDLQCountAsync(String topic) {
        return manager.getPool()
                .preparedQuery("SELECT COUNT(*) as cnt FROM dead_letter_queue WHERE topic = $1")
                .execute(Tuple.of(topic))
                .map(rows -> rows.iterator().next().getInteger("cnt"));
    }

    // =========================================================================
    // Async polling — Supplier<Future<Boolean>> variant, no event-loop blocking
    // =========================================================================

    /**
     * Polls {@code condition} every 100 ms using Vert.x timers until it returns
     * {@code true} or the deadline elapses.  The condition supplier is called on
     * the event loop without blocking, so it must return a Future rather than a
     * synchronous boolean.
     */
    private Future<Void> awaitDatabaseCondition(Vertx vertx, Supplier<Future<Boolean>> condition,
                                                 long timeoutMillis, String failureMessage) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        return pollCondition(vertx, condition, deadline, failureMessage);
    }

    private Future<Void> pollCondition(Vertx vertx, Supplier<Future<Boolean>> condition,
                                       long deadline, String failureMessage) {
        return condition.get()
                .transform(ar -> {
                    if (ar.succeeded() && Boolean.TRUE.equals(ar.result())) {
                        return Future.succeededFuture();
                    }
                    if (System.currentTimeMillis() > deadline) {
                        String reason = ar.failed()
                                ? " — condition threw: " + ar.cause().getMessage()
                                : " (timed out)";
                        return Future.failedFuture(new AssertionError(failureMessage + reason));
                    }
                    return vertx.timer(100)
                            .compose(id -> pollCondition(vertx, condition, deadline, failureMessage));
                });
    }
}
