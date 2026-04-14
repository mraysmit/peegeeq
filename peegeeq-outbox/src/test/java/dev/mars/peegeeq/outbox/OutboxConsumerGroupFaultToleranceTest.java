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
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory.PerformanceProfile.BASIC;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Fault tolerance integration tests for {@link OutboxConsumerGroup}.
 *
 * <p>These tests exercise failure paths that can only be tested with a real database:
 * <ul>
 *   <li>F2 — DB connection loss during polling → poll loop self-heals</li>
 *   <li>F4 — Handler fails mid-batch → remaining messages still processed</li>
 *   <li>F3 — Hung handler blocks poll loop → documented blocking behavior</li>
 *   <li>F5 — stop() called while handler is in-flight → clean shutdown</li>
 *   <li>F6 — All consumers fail → retry/DLQ pipeline engaged</li>
 * </ul>
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
@DisplayName("OutboxConsumerGroup fault tolerance")
class OutboxConsumerGroupFaultToleranceTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxConsumerGroupFaultToleranceTest.class);

    @Container
    static final PostgreSQLContainer postgres =
            PeeGeeQTestContainerFactory.createContainer(BASIC);

    private PeeGeeQManager manager;
    private OutboxFactory outboxFactory;
    private MessageProducer<String> producer;
    private ConsumerGroup<String> consumerGroup;
    private String testTopic;

    // Separate pool for verification queries (not affected by pg_terminate_backend)
    private PgConnectionManager verificationConnectionManager;
    private Pool verificationPool;

    @BeforeEach
    void setUp(Vertx vertx) {
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);

        testTopic = "fault-test-" + UUID.randomUUID().toString().substring(0, 8);

        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("fault-test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        DatabaseService databaseService = new PgDatabaseService(manager);
        outboxFactory = new OutboxFactory(databaseService, config);
        producer = outboxFactory.createProducer(testTopic, String.class);

        // Verification pool — independent connection for asserting DB state
        verificationConnectionManager = new PgConnectionManager(vertx);
        PgConnectionConfig connConfig = new PgConnectionConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .database(postgres.getDatabaseName())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .build();
        PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(2).build();
        verificationPool = verificationConnectionManager.getOrCreateReactivePool(
                "verification", connConfig, poolConfig);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (consumerGroup != null) {
            consumerGroup.stop();
            consumerGroup.close();
        }
        if (producer != null) {
            producer.close();
        }
        if (outboxFactory != null) {
            outboxFactory.close();
        }
        if (manager != null) {
            manager.closeReactive().await();
        }
        if (verificationConnectionManager != null) {
            verificationConnectionManager.close();
        }
        System.clearProperty("peegeeq.database.host");
        System.clearProperty("peegeeq.database.port");
        System.clearProperty("peegeeq.database.name");
        System.clearProperty("peegeeq.database.username");
        System.clearProperty("peegeeq.database.password");
        System.clearProperty("peegeeq.queue.max-retries");
    }

    // ========================================================================
    // F2 — DB connection loss during consumer group polling
    // ========================================================================

    @Nested
    @DisplayName("F2: DB connection loss recovery")
    class ConnectionLossRecovery {

        @Test
        @DisplayName("group recovers after pg_terminate_backend kills connections")
        void groupRecoversAfterConnectionKill(Vertx vertx, VertxTestContext testContext) throws Exception {
            int preKillMessages = 5;
            int postKillMessages = 5;
            List<String> received = Collections.synchronizedList(new ArrayList<>());

            consumerGroup = outboxFactory.createConsumerGroup("conn-loss-group", testTopic, String.class);
            consumerGroup.addConsumer("c1", message -> {
                received.add(message.getPayload());
                return Future.succeededFuture();
            });
            consumerGroup.start();

            // Wait for group to start polling, then send pre-kill messages
            vertx.timer(500).mapEmpty()
                .compose(v -> {
                    Future<Void> sends = Future.succeededFuture();
                    for (int i = 0; i < preKillMessages; i++) {
                        int idx = i;
                        sends = sends.compose(x -> producer.send("pre-kill-" + idx).mapEmpty());
                    }
                    return sends;
                })
                .compose(v -> waitUntil(
                        vertx,
                        () -> received.size() == preKillMessages,
                        10_000,
                        "Pre-kill messages should be consumed before terminating connections"))
                .compose(v -> killPeeGeeQConnections())
                .compose(killCount -> {
                    logger.info("Killed PeeGeeQ connections — polling loop should recover");
                    assertTrue(killCount > 0,
                            "At least one PeeGeeQ connection should have been terminated");
                    // Brief pause for the pool to notice the dead connections
                    return vertx.timer(2000).mapEmpty();
                })
                .compose(v -> {
                    // Phase 2: send post-kill messages — group must recover and process these
                    Future<Void> sends = Future.succeededFuture();
                    for (int i = 0; i < postKillMessages; i++) {
                        int idx = i;
                        sends = sends.compose(x -> producer.send("post-kill-" + idx).mapEmpty());
                    }
                    return sends;
                })
                .compose(v -> waitUntil(
                        vertx,
                        () -> received.size() == preKillMessages + postKillMessages,
                        15_000,
                        "Post-kill messages should be consumed after polling recovery"))
                .onSuccess(v -> {
                    assertEquals(preKillMessages + postKillMessages, received.size(),
                            "All messages should be received after recovery");
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);

            assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS),
                    "Group should recover and process all messages after connection loss");
        }
    }

    // ========================================================================
    // F4 — Handler fails mid-batch → remaining messages still processed
    // ========================================================================

    @Nested
    @DisplayName("F4: Mid-batch handler failure")
    class MidBatchFailure {

        @Test
        @DisplayName("batch continues after individual handler failure")
        void batchContinuesAfterHandlerFailure(Vertx vertx, VertxTestContext testContext) throws Exception {
            int totalMessages = 10;
            AtomicInteger failCount = new AtomicInteger();
            List<String> successfullyProcessed = Collections.synchronizedList(new ArrayList<>());

            // Expect ~half to succeed on first pass; failed ones get retried
            Checkpoint allDone = testContext.checkpoint(totalMessages);

            consumerGroup = outboxFactory.createConsumerGroup("mid-batch-group", testTopic, String.class);
            consumerGroup.addConsumer("c1", message -> {
                String payload = message.getPayload();
                // Fail on odd-numbered messages the first time only
                if (payload.contains("odd") && failCount.incrementAndGet() <= 5) {
                    return Future.failedFuture(new RuntimeException("Simulated mid-batch failure for " + payload));
                }
                successfullyProcessed.add(payload);
                allDone.flag();
                return Future.succeededFuture();
            });
            consumerGroup.start();

            // Wait for group to start, then send mix of messages
            vertx.timer(500).mapEmpty()
                .compose(v -> {
                    Future<Void> sends = Future.succeededFuture();
                    for (int i = 0; i < totalMessages; i++) {
                        String tag = (i % 2 == 0) ? "even" : "odd";
                        int idx = i;
                        sends = sends.compose(x -> producer.send(tag + "-" + idx).mapEmpty());
                    }
                    return sends;
                })
                .onFailure(testContext::failNow);

            assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS),
                    "All messages should eventually be processed (failures retried)");

            assertEquals(totalMessages, successfullyProcessed.size(),
                    "All messages should be successfully processed after retries");
        }
    }

    // ========================================================================
    // F5 — stop() while handler is in-flight
    // ========================================================================

    @Nested
    @DisplayName("F5: Stop during active processing")
    class StopDuringProcessing {

        @Test
        @DisplayName("stop() during active handler completes cleanly")
        void stopDuringActiveHandlerCompletesCleanly(Vertx vertx, VertxTestContext testContext) throws Exception {
            Promise<Void> handlerGate = Promise.promise();
            AtomicInteger handlerEntryCount = new AtomicInteger();

            consumerGroup = outboxFactory.createConsumerGroup("stop-active-group", testTopic, String.class);
            consumerGroup.addConsumer("c1", message -> {
                handlerEntryCount.incrementAndGet();
                logger.info("Handler entered for message: {}", message.getPayload());
                // Block until gate is released — simulates slow processing
                return handlerGate.future();
            });
            consumerGroup.start();

            // Wait for group to start, then send a message — handler will hang on the gate
            vertx.timer(500).mapEmpty()
                .compose(v -> producer.send("slow-message").mapEmpty())
                .compose(v -> {
                    // Poll until handler is entered
                    Promise<Void> entered = Promise.promise();
                    vertx.setPeriodic(100, id -> {
                        if (handlerEntryCount.get() > 0) {
                            vertx.cancelTimer(id);
                            entered.complete();
                        }
                    });
                    return entered.future();
                })
                .compose(v -> {
                    // Now stop the group gracefully while handler is in-flight
                    logger.info("Calling stopGracefully() while handler is in-flight");
                    return consumerGroup.stopGracefully();
                })
                .compose(v -> {
                    // Verify the group is no longer active
                    assertFalse(consumerGroup.isActive(), "Group should not be active after stopGracefully()");

                    // Release the handler gate so the in-flight future completes
                    handlerGate.complete();

                    // Send another message — it should NOT be processed (group is stopped)
                    AtomicInteger postStopReceived = new AtomicInteger();
                    consumerGroup.addConsumer("c2", msg -> {
                        postStopReceived.incrementAndGet();
                        return Future.succeededFuture();
                    });

                    return producer.send("post-stop-message").mapEmpty()
                        .compose(x -> vertx.timer(2000).mapEmpty())
                        .map(x -> {
                            assertEquals(0, postStopReceived.get(),
                                    "No messages should be processed after stop()");
                            return (Void) null;
                        });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

            assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS),
                    "Test should complete within timeout");
        }
    }

    // ========================================================================
    // F3 — Hung handler blocks poll loop
    // ========================================================================

    @Nested
    @DisplayName("F3: Hung handler blocks processing")
    class HungHandlerBlocking {

        @Test
        @DisplayName("hung handler blocks poll loop — no further messages processed")
        void hungHandlerBlocksPollLoop(Vertx vertx, VertxTestContext testContext) throws Exception {
            AtomicInteger handlerCalls = new AtomicInteger();
            int totalMessages = 15;
            int initialBatchSize = 10;

            consumerGroup = outboxFactory.createConsumerGroup("hung-group", testTopic, String.class);
            consumerGroup.addConsumer("c1", message -> {
                handlerCalls.incrementAndGet();
                logger.info("Handler entered for message: {}", message.getPayload());
                // Return a Future that never completes — simulates permanently hung handler
                return Promise.<Void>promise().future();
            });
            consumerGroup.start();

            // Wait for group to start, send multiple messages, then wait
            vertx.timer(500).mapEmpty()
                .compose(v -> {
                    Future<Void> sends = Future.succeededFuture();
                    for (int i = 0; i < totalMessages; i++) {
                        int idx = i;
                        sends = sends.compose(x -> producer.send("hung-msg-" + idx).mapEmpty());
                    }
                    return sends;
                })
                // Wait long enough for multiple poll cycles
                .compose(v -> vertx.timer(10000).mapEmpty())
                .compose(v -> verificationPool.preparedQuery(
                    """
                    SELECT
                        COUNT(*) FILTER (WHERE status = 'PROCESSING') AS processing_count,
                        COUNT(*) FILTER (WHERE status = 'PENDING') AS pending_count,
                        COUNT(*) FILTER (WHERE status IN ('FAILED', 'DEAD_LETTER', 'COMPLETED')) AS terminal_count,
                        COALESCE(MAX(retry_count), 0) AS max_retry
                    FROM outbox
                    WHERE topic = $1
                    """)
                    .execute(Tuple.of(testTopic))
                    .map(rows -> rows.iterator().next()))
                .map(row -> {
                    int totalCalls = handlerCalls.get();
                    int processingCount = row.getInteger("processing_count");
                    int pendingCount = row.getInteger("pending_count");
                    int terminalCount = row.getInteger("terminal_count");
                    int maxRetry = row.getInteger("max_retry");
                    logger.info("F3: handlerCalls={} after waiting for poll cycles", totalCalls);
                    logger.info("F3: processingCount={}, pendingCount={}, terminalCount={}, maxRetry={}",
                        processingCount, pendingCount, terminalCount, maxRetry);

                    // OutboxConsumer claims a whole batch into PROCESSING before dispatching sequentially.
                    // A hung first handler should therefore freeze the initial claimed batch and prevent
                    // later polls from claiming the remaining pending rows.
                    assertEquals(1, totalCalls,
                        "Hung handler should only be entered once");
                    assertTrue(processingCount > 0,
                        "At least one claimed message should remain stuck in PROCESSING");
                    assertEquals(totalMessages, processingCount + pendingCount,
                        "All queued messages should remain non-terminal while the first handler is hung");
                    assertEquals(0, terminalCount,
                        "No messages should reach terminal states while the handler is hung");
                    assertTrue(maxRetry >= 0,
                        "Retry metadata should remain queryable while processing is blocked");

                    logger.info("F3 PASSED: hung handler blocks poll loop (documented behavior)");
                    return (Void) null;
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

            assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS),
                    "Test should complete within timeout");
        }
    }

    // ========================================================================
    // F6 — All consumers fail → retry/DLQ pipeline
    // ========================================================================

    @Nested
    @DisplayName("F6: Handler failure → retry/DLQ pipeline")
    class RetryAndDlqPipeline {

        @Test
        @DisplayName("always-failing handler exhausts retries and message reaches FAILED/DLQ")
        void alwaysFailingHandlerExhaustsRetries(Vertx vertx, VertxTestContext testContext) throws Exception {
            // Set max retries low for faster test
            System.setProperty("peegeeq.queue.max-retries", "2");

            // Recreate config + factory with the new max-retries
            if (manager != null) {
                manager.closeReactive().await();
            }
            PeeGeeQConfiguration config = new PeeGeeQConfiguration("retry-test");
            manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
            manager.start();
            DatabaseService databaseService = new PgDatabaseService(manager);
            outboxFactory = new OutboxFactory(databaseService, config);
            producer = outboxFactory.createProducer(testTopic, String.class);

            AtomicInteger attemptCount = new AtomicInteger();

            consumerGroup = outboxFactory.createConsumerGroup("retry-group", testTopic, String.class);
            consumerGroup.addConsumer("c1", message -> {
                attemptCount.incrementAndGet();
                logger.info("Handler attempt {} for message {}", attemptCount.get(), message.getPayload());
                return Future.failedFuture(new RuntimeException("Permanent failure: " + message.getPayload()));
            });
            consumerGroup.start();

            // Wait for group to start, then send one doomed message
            vertx.timer(500).mapEmpty()
                .compose(v -> producer.send("doomed-message").mapEmpty())
                .compose(v -> {
                    // Poll until message reaches terminal state
                    Promise<Void> terminalReached = Promise.promise();
                    vertx.setPeriodic(500, timerId -> {
                        verificationPool.preparedQuery(
                                "SELECT status, retry_count FROM outbox WHERE topic = $1")
                            .execute(Tuple.of(testTopic))
                            .onSuccess(rows -> {
                                if (rows.size() > 0) {
                                    var row = rows.iterator().next();
                                    String status = row.getString("status");
                                    int retryCount = row.getInteger("retry_count") != null
                                            ? row.getInteger("retry_count") : 0;
                                    logger.info("Message status={}, retry_count={}", status, retryCount);
                                    if ("FAILED".equals(status) || "DEAD_LETTER".equals(status)) {
                                        vertx.cancelTimer(timerId);
                                        terminalReached.complete();
                                    }
                                }
                            });
                    });
                    return terminalReached.future();
                })
                .compose(v -> {
                    // Verify final DB state
                    return verificationPool.preparedQuery(
                            "SELECT status, retry_count FROM outbox WHERE topic = $1")
                        .execute(Tuple.of(testTopic))
                        .map(rows -> {
                            assertTrue(rows.size() > 0, "Message should exist in outbox");
                            var row = rows.iterator().next();
                            String status = row.getString("status");
                            int retryCount = row.getInteger("retry_count") != null
                                    ? row.getInteger("retry_count") : 0;
                            logger.info("Final: status={}, retry_count={}", status, retryCount);
                            assertTrue("FAILED".equals(status) || "DEAD_LETTER".equals(status),
                                    "Message should be FAILED or DEAD_LETTER, was: " + status);
                            assertTrue(retryCount >= 2,
                                    "Retry count should be >= 2, was: " + retryCount);
                            assertTrue(attemptCount.get() >= 3,
                                    "Handler should have been invoked at least 3 times (initial + 2 retries), was: "
                                            + attemptCount.get());
                            return (Void) null;
                        });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

            assertTrue(testContext.awaitCompletion(45, TimeUnit.SECONDS),
                    "Message should reach FAILED/DEAD_LETTER state after retries exhausted");
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /**
     * Kills all PeeGeeQ application connections using the verification pool.
     * The verification pool is not affected because it uses a different application name.
     *
     * @return the number of connections terminated
     */
    private Future<Integer> killPeeGeeQConnections() {
        return verificationPool.preparedQuery(
                "SELECT pg_terminate_backend(pid) FROM pg_stat_activity " +
                "WHERE pid != pg_backend_pid() AND datname = current_database() AND state = 'idle'")
            .execute()
            .map(rows -> {
                int count = rows.size();
                logger.info("Terminated {} PeeGeeQ connections", count);
                return count;
            });
    }

    private Future<Void> waitUntil(Vertx vertx, BooleanSupplier condition, long timeoutMs, String timeoutMessage) {
        if (condition.getAsBoolean()) {
            return Future.succeededFuture();
        }

        Promise<Void> promise = Promise.promise();
        long timeoutId = vertx.setTimer(timeoutMs, id -> {
            if (!promise.future().isComplete()) {
                promise.fail(new AssertionError(timeoutMessage));
            }
        });

        long pollId = vertx.setPeriodic(100, id -> {
            if (condition.getAsBoolean() && !promise.future().isComplete()) {
                vertx.cancelTimer(timeoutId);
                vertx.cancelTimer(id);
                promise.complete();
            }
        });

        return promise.future().eventually(() -> {
            vertx.cancelTimer(timeoutId);
            vertx.cancelTimer(pollId);
            return Future.succeededFuture();
        });
    }
}
