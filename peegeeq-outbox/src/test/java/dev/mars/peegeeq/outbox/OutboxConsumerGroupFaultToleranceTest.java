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

import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.ConsumerGroup;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
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
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
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
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory.PerformanceProfile.BASIC;
import static org.junit.jupiter.api.Assertions.*;
import dev.mars.peegeeq.test.vertx.TestCleanup;

/**
 * Fault tolerance integration tests for {@link OutboxConsumerGroup}.
 *
 * <p>These tests exercise failure paths that can only be tested with a real database:
 * <ul>
 *   <li>F2 DB connection loss during polling  poll loop self-heals</li>
 *   <li>F4 Handler fails mid-batch  remaining messages still processed</li>
 *   <li>F3 Hung handler blocks poll loop  documented blocking behavior</li>
 *   <li>F5 stop() called while handler is in-flight  clean shutdown</li>
 *   <li>F6 All consumers fail  retry/DLQ pipeline engaged</li>
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

    /** The consumers' polling interval; the windows of the negative assertions are derived from it. */
    private static final String POLLING_INTERVAL = "PT0.2S";
    /** A window that spans five polls: a group that still polled would claim a pending row in it. */
    private long negativeWindowMs;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext testContext) {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.QUEUE_ALL);

        testTopic = "fault-test-" + UUID.randomUUID().toString().substring(0, 8);

        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.queue.polling-interval", POLLING_INTERVAL)
                .build();
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        negativeWindowMs = config.getQueueConfig().getPollingInterval().toMillis() * 5;
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());

        // Verification pool independent connection for asserting DB state
        verificationConnectionManager = new PgConnectionManager(vertx);
        PgConnectionConfig connConfig = new PgConnectionConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .database(postgres.getDatabaseName())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .build();
        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
                .maxSize(2)
                .shared(false)
                .build();
        verificationPool = verificationConnectionManager.getOrCreateReactivePool(
                "verification", connConfig, poolConfig);

        manager.start()
                .onSuccess(v -> {
                    DatabaseService databaseService = new PgDatabaseService(manager);
                    outboxFactory = new OutboxFactory(databaseService, config);
                    producer = outboxFactory.createProducer(testTopic, String.class);
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext tearDownContext) throws Exception {
        logger.info("Tearing down: closing resources and manager");
        Future.<Void>succeededFuture()
                .transform(TestCleanup.cleanup(() -> outboxFactory != null ? outboxFactory.close() : Future.succeededFuture()))
                .transform(TestCleanup.cleanup(() -> manager != null ? manager.closeReactive() : Future.succeededFuture()))
                .transform(TestCleanup.cleanup(() -> verificationConnectionManager != null ? verificationConnectionManager.close() : Future.<Void>succeededFuture()))
                .onSuccess(v -> tearDownContext.completeNow())
                .onFailure(tearDownContext::failNow);
        assertTrue(tearDownContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    // ========================================================================
    // F2 DB connection loss during consumer group polling
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
            // start() completes once the group polls; the messages wait as rows until claimed.
            consumerGroup.start()
                .compose(v -> {
                    Future<Void> sends = Future.succeededFuture();
                    for (int i = 0; i < preKillMessages; i++) {
                        int idx = i;
                        sends = sends.compose(x -> producer.send("pre-kill-" + idx).mapEmpty());
                    }
                    return sends;
                })
                .compose(v -> pollUntil(
                        vertx,
                        () -> Future.succeededFuture(received.size() == preKillMessages),
                        10_000,
                        "Pre-kill messages should be consumed before terminating connections"))
                .compose(v -> killPeeGeeQConnections())
                .compose(killedPids -> {
                    logger.info("Killed PeeGeeQ connections {}; polling loop should recover", killedPids);
                    assertFalse(killedPids.isEmpty(),
                            "At least one PeeGeeQ connection should have been terminated");
                    // The sends below need the killed backends gone and a live connection in the
                    // application pool; both are polled instead of a fixed pause.
                    return pollUntil(vertx, () -> backendsGone(killedPids), 10_000,
                                    "The terminated backends should exit")
                            .compose(gone -> pollUntil(vertx, () -> applicationPoolServes(), 10_000,
                                    "The application pool should serve a live connection after the kill"));
                })
                .compose(v -> {
                    // Phase 2: send post-kill messages group must recover and process these
                    Future<Void> sends = Future.succeededFuture();
                    for (int i = 0; i < postKillMessages; i++) {
                        int idx = i;
                        sends = sends.compose(x -> producer.send("post-kill-" + idx).mapEmpty());
                    }
                    return sends;
                })
                .compose(v -> pollUntil(
                        vertx,
                        () -> Future.succeededFuture(received.size() == preKillMessages + postKillMessages),
                        15_000,
                        "Post-kill messages should be consumed after polling recovery"))
                .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                    assertEquals(preKillMessages + postKillMessages, received.size(),
                            "All messages should be received after recovery");
                    testContext.completeNow();
                })));

            assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS),
                    "Group should recover and process all messages after connection loss");
        }
    }

    // ========================================================================
    // F4 Handler fails mid-batch  remaining messages still processed
    // ========================================================================

    @Nested
    @DisplayName("F4: Mid-batch handler failure")
    class MidBatchFailure {

        @Test
        @DisplayName("batch continues after individual handler failure")
        @io.vertx.junit5.Timeout(value = 30, timeUnit = TimeUnit.SECONDS)
        void batchContinuesAfterHandlerFailure(VertxTestContext testContext) {
            int totalMessages = 10;
            Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();
            List<String> successfullyProcessed = Collections.synchronizedList(new ArrayList<>());

            // Half succeed on the first pass; the failed ones are retried. The per-message
            // attempt counts are checked once every message has succeeded.
            Promise<Void> allDone = Promise.promise();

            consumerGroup = outboxFactory.createConsumerGroup("mid-batch-group", testTopic, String.class);
            consumerGroup.addConsumer("c1", message -> {
                String payload = message.getPayload();
                int attempt = attempts.computeIfAbsent(payload, ignored -> new AtomicInteger())
                        .incrementAndGet();
                // Fail on odd-numbered messages the first time only
                if (payload.contains("odd") && attempt == 1) {
                    return Future.failedFuture(new RuntimeException("Simulated mid-batch failure for " + payload));
                }
                successfullyProcessed.add(payload);
                if (successfullyProcessed.size() == totalMessages) {
                    allDone.tryComplete();
                }
                return Future.succeededFuture();
            });
            // Wait for group to start, then send mix of messages
            consumerGroup.start()
                .compose(v -> {
                    Future<Void> sends = Future.succeededFuture();
                    for (int i = 0; i < totalMessages; i++) {
                        String tag = (i % 2 == 0) ? "even" : "odd";
                        int idx = i;
                        sends = sends.compose(x -> producer.send(tag + "-" + idx).mapEmpty());
                    }
                    return sends;
                })
                .compose(sent -> allDone.future())
                .onSuccess(done -> testContext.verify(() -> {
                    assertEquals(totalMessages, successfullyProcessed.size(),
                            "All messages should be successfully processed after retries");
                    for (int i = 0; i < totalMessages; i++) {
                        String payload = (i % 2 == 0 ? "even" : "odd") + "-" + i;
                        assertNotNull(attempts.get(payload), "Every message must reach the handler");
                        assertEquals(i % 2 == 0 ? 1 : 2, attempts.get(payload).intValue(),
                                "Only the first attempt of each odd message should fail: " + payload);
                    }
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
        }
    }

    // ========================================================================
    // F5 stop() while handler is in-flight
    // ========================================================================

    @Nested
    @DisplayName("F5: Stop during active processing")
    class StopDuringProcessing {

        @Test
        @DisplayName("stop() waits for an active handler and completes cleanly")
        void stopDuringActiveHandlerCompletesCleanly(Vertx vertx, VertxTestContext testContext) throws Exception {
            Promise<Void> handlerGate = Promise.promise();
            Promise<Void> handlerEntered = Promise.promise();

            consumerGroup = outboxFactory.createConsumerGroup("stop-active-group", testTopic, String.class);
            consumerGroup.addConsumer("c1", message -> {
                handlerEntered.tryComplete();
                logger.info("Handler entered for message: {}", message.getPayload());
                // Hold the handler in flight until the test verifies stop is waiting.
                return handlerGate.future();
            });
            // Start the group, then send a message whose handler waits on the gate.
            consumerGroup.start()
                .compose(v -> producer.send("slow-message").mapEmpty())
                .compose(v -> handlerEntered.future())
                .compose(v -> {
                    logger.info("Calling stopGracefully() while handler is in-flight");
                    Future<Void> stopFuture = consumerGroup.stopGracefully();
                    try {
                        testContext.verify(() -> assertFalse(stopFuture.isComplete(),
                                "stopGracefully() must wait for the active handler"));
                    } finally {
                        handlerGate.tryComplete();
                    }
                    return stopFuture;
                })
                .compose(v -> {
                    // Verify the group is no longer active
                    assertFalse(consumerGroup.isActive(), "Group should not be active after stopGracefully()");

                    // Send another message it should NOT be processed (group is stopped)
                    AtomicInteger postStopReceived = new AtomicInteger();
                    consumerGroup.addConsumer("c2", msg -> {
                        postStopReceived.incrementAndGet();
                        return Future.succeededFuture();
                    });

                    // A negative assertion needs a window: five polls of a group that still ran.
                    // The row must still be PENDING after it, which shows nothing claimed it.
                    return producer.send("post-stop-message").mapEmpty()
                        .compose(x -> vertx.timer(negativeWindowMs).mapEmpty())
                        .compose(x -> verificationPool.preparedQuery(
                                "SELECT status FROM outbox WHERE topic = $1 AND payload ->> 'value' = $2")
                            .execute(Tuple.of(testTopic, "post-stop-message")))
                        .map(rows -> {
                            assertEquals(0, postStopReceived.intValue(),
                                    "No messages should be processed after stop()");
                            assertEquals(1, rows.size(), "The post-stop message should be stored once");
                            assertEquals("PENDING", rows.iterator().next().getString("status"),
                                    "No consumer of the stopped group may claim the post-stop message");
                            return (Void) null;
                        });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

            assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS),
                    "Test should complete within timeout");
        }

        @Test
        @DisplayName("factory close waits for an active handler and persists completion")
        void factoryCloseWaitsForActiveHandlerAndPersistsCompletion(
                VertxTestContext testContext) throws Exception {
            Promise<Void> handlerGate = Promise.promise();
            Promise<Void> handlerEntered = Promise.promise();
            MessageConsumer<String> directConsumer = outboxFactory.createConsumer(testTopic, String.class);

            directConsumer.subscribe(message -> {
                handlerEntered.tryComplete();
                return handlerGate.future();
            })
                .compose(v -> producer.send("factory-close-message").mapEmpty())
                .compose(v -> handlerEntered.future())
                .compose(v -> {
                    Future<Void> factoryClose = outboxFactory.close();
                    try {
                        testContext.verify(() -> assertFalse(factoryClose.isComplete(),
                                "OutboxFactory.close() must wait for the active handler"));
                    } finally {
                        handlerGate.tryComplete();
                    }
                    return factoryClose;
                })
                .compose(v -> verificationPool.preparedQuery(
                        "SELECT status FROM outbox WHERE topic = $1")
                        .execute(Tuple.of(testTopic)))
                .onSuccess(rows -> testContext.verify(() -> {
                    assertEquals(1, rows.size(), "The sent message should remain queryable");
                    assertEquals("COMPLETED", rows.iterator().next().getString("status"),
                            "Close must allow the in-flight completion update to finish");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

            assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS),
                    "Factory close should complete after the handler is released");
        }
    }

    // ========================================================================
    // F3 Hung handler blocks poll loop
    // ========================================================================

    @Nested
    @DisplayName("F3: Hung handler blocks processing")
    class HungHandlerBlocking {

        @Test
        @DisplayName("hung handler blocks poll loop no further messages processed")
        void hungHandlerBlocksPollLoop(Vertx vertx, VertxTestContext testContext) throws Exception {
            AtomicInteger handlerCalls = new AtomicInteger();
            Promise<Void> handlerGate = Promise.promise();
            int totalMessages = 15;

            consumerGroup = outboxFactory.createConsumerGroup("hung-group", testTopic, String.class);
            consumerGroup.addConsumer("c1", message -> {
                handlerCalls.incrementAndGet();
                logger.info("Handler entered for message: {}", message.getPayload());
                return handlerGate.future();
            });
            // Start the group and send the messages; the first handler hangs on the gate.
            consumerGroup.start()
                .compose(v -> {
                    Future<Void> sends = Future.succeededFuture();
                    for (int i = 0; i < totalMessages; i++) {
                        int idx = i;
                        sends = sends.compose(x -> producer.send("hung-msg-" + idx).mapEmpty());
                    }
                    return sends;
                })
                // Positive precondition first: the handler is entered and its batch is claimed.
                .compose(v -> pollUntil(vertx, () -> Future.succeededFuture(handlerCalls.get() >= 1)
                                .compose(entered -> !entered ? Future.succeededFuture(false)
                                        : countProcessing().map(processing -> processing > 0)),
                        10_000, "The first handler should be entered with its batch in PROCESSING"))
                // Then a window of five polls in which an unblocked loop would claim more rows.
                .compose(v -> vertx.timer(negativeWindowMs).mapEmpty())
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
                    int totalCalls = handlerCalls.intValue();
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
                .transform(TestCleanup.cleanup(() -> {
                    handlerGate.tryComplete();
                    return Future.succeededFuture();
                }))
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

            assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS),
                    "Test should complete within timeout");
        }
    }

    // ========================================================================
    // F6 All consumers fail  retry/DLQ pipeline
    // ========================================================================

    @Nested
    @DisplayName("F6: Handler failure  retry/DLQ pipeline")
    class RetryAndDlqPipeline {

        @Test
        @DisplayName("always-failing handler exhausts retries and message reaches FAILED/DLQ")
        void alwaysFailingHandlerExhaustsRetries(Vertx vertx, VertxTestContext testContext) throws Exception {
            Properties retryProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                    .property("peegeeq.queue.max-retries", "2")
                    .property("peegeeq.queue.polling-interval", POLLING_INTERVAL)
                    .build();

            AtomicInteger attemptCount = new AtomicInteger();
            PeeGeeQConfiguration newConfig = new PeeGeeQConfiguration("default", retryProps);

            // Recreate config + factory with the new max-retries, then run the test chain
            (manager != null ? manager.closeReactive() : Future.<Void>succeededFuture())
                .compose(v -> {
                    manager = new PeeGeeQManager(newConfig, new SimpleMeterRegistry());
                    DatabaseService databaseService = new PgDatabaseService(manager);
                    outboxFactory = new OutboxFactory(databaseService, newConfig);
                    producer = outboxFactory.createProducer(testTopic, String.class);
                    return manager.start();
                })
                .compose(v -> {
                    consumerGroup = outboxFactory.createConsumerGroup("retry-group", testTopic, String.class);
                    consumerGroup.addConsumer("c1", message -> {
                        attemptCount.incrementAndGet();
                        logger.info("Handler attempt {} for message {}", attemptCount.intValue(), message.getPayload());
                        return Future.failedFuture(new RuntimeException("Permanent failure: " + message.getPayload()));
                    });
                    return consumerGroup.start();
                })
                // The group has started; send one doomed message
                .compose(v -> producer.send("doomed-message").mapEmpty())
                .compose(v -> {
                    // Poll until message reaches terminal state
                    Promise<Void> terminalReached = Promise.promise();
                    vertx.setPeriodic(500, timerId -> {
                        verificationPool.preparedQuery(
                                "SELECT status, retry_count FROM outbox WHERE topic = $1")
                            .execute(Tuple.of(testTopic))
                            .map(rows -> {
                                if (rows.size() > 0) {
                                    var row = rows.iterator().next();
                                    String status = row.getString("status");
                                    int retryCount = row.getInteger("retry_count") != null
                                            ? row.getInteger("retry_count") : 0;
                                    logger.info("Message status={}, retry_count={}", status, retryCount);
                                    if ("FAILED".equals(status) || "DEAD_LETTER".equals(status)) {
                                        vertx.cancelTimer(timerId);
                                        terminalReached.tryComplete();
                                    }
                                }
                                return (Void) null;
                            })
                            .onFailure(error -> {
                                vertx.cancelTimer(timerId);
                                terminalReached.tryFail(error);
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
                            assertTrue(attemptCount.intValue() >= 3,
                                    "Handler should have been invoked at least 3 times (initial + 2 retries), was: "
                                            + attemptCount.intValue());
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
    private Future<List<Integer>> killPeeGeeQConnections() {
        return verificationPool.preparedQuery(
                "SELECT pid, pg_terminate_backend(pid) AS terminated FROM pg_stat_activity " +
                "WHERE pid != pg_backend_pid() AND datname = current_database() AND state = 'idle'")
            .execute()
            .map(rows -> {
                List<Integer> killed = new ArrayList<>();
                rows.forEach(row -> {
                    if (Boolean.TRUE.equals(row.getBoolean("terminated"))) {
                        killed.add(row.getInteger("pid"));
                    }
                });
                logger.info("Terminated {} PeeGeeQ connections", killed.size());
                return killed;
            });
    }

    /** True once none of {@code pids} is a server backend any more. */
    private Future<Boolean> backendsGone(List<Integer> pids) {
        return verificationPool.preparedQuery(
                "SELECT count(*) AS remaining FROM pg_stat_activity WHERE pid = ANY($1)")
            .execute(Tuple.of(pids.toArray(new Integer[0])))
            .map(rows -> rows.iterator().next().getLong("remaining") == 0L);
    }

    /**
     * True once the application pool runs a query. A query on a connection whose backend was
     * terminated fails; that failure is the state being polled, so it maps to false.
     */
    private Future<Boolean> applicationPoolServes() {
        return manager.getPool().query("SELECT 1").execute()
            .map(rows -> true)
            .transform(outcome -> {
                if (outcome.failed()) {
                    logger.debug("Application pool not serving yet: {}", outcome.cause().getMessage());
                    return Future.succeededFuture(false);
                }
                return Future.succeededFuture(true);
            });
    }

    private Future<Integer> countProcessing() {
        return verificationPool.preparedQuery(
                "SELECT count(*) AS processing FROM outbox WHERE topic = $1 AND status = 'PROCESSING'")
            .execute(Tuple.of(testTopic))
            .map(rows -> rows.iterator().next().getLong("processing").intValue());
    }

    /**
     * Re-runs {@code check} every 100 ms until it yields true, and fails with {@code timeoutMessage}
     * after {@code timeoutMs}. A failed check fails the poll.
     */
    private Future<Void> pollUntil(Vertx vertx, Supplier<Future<Boolean>> check,
                                   long timeoutMs, String timeoutMessage) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        Promise<Void> done = Promise.promise();
        pollOnce(vertx, check, deadline, timeoutMessage, done);
        return done.future();
    }

    private void pollOnce(Vertx vertx, Supplier<Future<Boolean>> check, long deadline,
                          String timeoutMessage, Promise<Void> done) {
        check.get()
            .onSuccess(reached -> {
                if (reached) {
                    done.tryComplete();
                } else if (System.nanoTime() >= deadline) {
                    done.tryFail(new AssertionError(timeoutMessage));
                } else {
                    vertx.setTimer(100, id -> pollOnce(vertx, check, deadline, timeoutMessage, done));
                }
            })
            .onFailure(done::tryFail);
    }
}
