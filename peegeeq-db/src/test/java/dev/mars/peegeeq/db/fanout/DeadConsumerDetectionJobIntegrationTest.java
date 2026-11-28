package dev.mars.peegeeq.db.fanout;

import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.db.cleanup.DeadConsumerDetector.DetectionResult;
import dev.mars.peegeeq.db.cleanup.DeadConsumerDetector.SubscriptionSummary;

import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.cleanup.DeadConsumerDetector;
import dev.mars.peegeeq.db.cleanup.DeadConsumerDetectionJob;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.api.subscription.SubscriptionStore;
import dev.mars.peegeeq.db.subscription.SubscriptionManager;
import dev.mars.peegeeq.db.subscription.TopicConfig;
import dev.mars.peegeeq.db.subscription.TopicConfigService;
import dev.mars.peegeeq.db.subscription.TopicSemantics;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import dev.mars.peegeeq.test.vertx.TestCleanup;

/**
 * Integration tests for {@link DeadConsumerDetectionJob}.
 *
 * <p>Tests validate that the scheduled job correctly detects subscriptions
 * whose heartbeat has timed out and marks them as DEAD.</p>
 *
 * <p>The class holds the {@code dead-consumer-detection} lock. Its jobs run global detection,
 * which would otherwise mark the expired groups of other classes while those classes assert
 * their own detection counts.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-12-01
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock(value = "dead-consumer-detection", mode = ResourceAccessMode.READ_WRITE)
public class DeadConsumerDetectionJobIntegrationTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(DeadConsumerDetectionJobIntegrationTest.class);

    private PgConnectionManager connectionManager;
    private TopicConfigService topicConfigService;
    private SubscriptionManager subscriptionManager;
    private DeadConsumerDetector detector;
    private DeadConsumerDetectionJob job;

    @BeforeEach
    void setUp() throws Exception {
        connectionManager = new PgConnectionManager(manager.getVertx(), null);

        PostgreSQLContainer postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .database(postgres.getDatabaseName())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
                .maxSize(3)
                .shared(false)
                .idleTimeout(Duration.ofSeconds(2))
                .connectionTimeout(Duration.ofSeconds(5))
                .build();

        connectionManager.getOrCreateReactivePool("peegeeq-main", connectionConfig, poolConfig);

        topicConfigService = new TopicConfigService(connectionManager, "peegeeq-main");
        subscriptionManager = new SubscriptionManager(connectionManager, "peegeeq-main");
        detector = new DeadConsumerDetector(connectionManager, "peegeeq-main");

        logger.info("Test setup complete");
    }

    @AfterEach
    void cleanUp(VertxTestContext testContext) {
        TestCleanup.run(
                        () -> (job != null && job.isRunning()) ? job.stop() : Future.succeededFuture(),
                        () -> connectionManager != null ? connectionManager.close() : Future.succeededFuture())
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }
    /**
     * Test that a subscription with an expired heartbeat is detected and marked as DEAD.
     */
    @Test
    void testDetectsExpiredHeartbeat(VertxTestContext ctx) {
        logger.warn("===== INTENTIONAL WARN TEST ===== The next WARN logs ('Marked N subscriptions as DEAD', detection run summary) are EXPECTED  this test deliberately expires a heartbeat to verify dead consumer detection job");
        String topic = "test-dead-detect-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "dead-group-1";

        topicConfigService.createTopic(TopicConfig.builder()
                        .topic(topic)
                        .semantics(TopicSemantics.PUB_SUB)
                        .messageRetentionHours(24)
                        .build())
                .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionStore.OUTBOX, SubscriptionOptions.builder()
                        .heartbeatIntervalSeconds(1)
                        .heartbeatTimeoutSeconds(2)
                        .build()))
                .compose(v -> setHeartbeatInPast(topic, groupName, 10))
                .compose(v -> detector.detectDeadSubscriptions(topic))
                .compose(v -> setHeartbeatInPast(topic, groupName, 10))
                .compose(v -> detector.detectDeadSubscriptions(topic))
                .compose(v -> setHeartbeatInPast(topic, groupName, 10))
                .compose(v -> detector.detectDeadSubscriptions(topic))
                .compose(markedDead -> {
                    assertEquals(1, markedDead, "Should detect 1 dead subscription");
                    return getSubscriptionStatus(topic, groupName);
                })
                .onComplete(ctx.succeeding(status -> ctx.verify(() -> {
                    assertEquals("DEAD", status, "Subscription should be marked as DEAD");
                    logger.info("Dead consumer detection verified");
                    ctx.completeNow();
                })));
    }

    /**
     * Test that healthy subscriptions (recent heartbeat) are NOT marked as DEAD.
     */
    @Test
    void testDoesNotMarkHealthySubscriptions(VertxTestContext ctx) {
        String topic = "test-healthy-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "healthy-group-1";

        topicConfigService.createTopic(TopicConfig.builder()
                        .topic(topic)
                        .semantics(TopicSemantics.PUB_SUB)
                        .messageRetentionHours(24)
                        .build())
                .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionStore.OUTBOX, SubscriptionOptions.builder()
                        .heartbeatIntervalSeconds(60)
                        .heartbeatTimeoutSeconds(300)
                        .build()))
                .compose(v -> detector.detectDeadSubscriptions(topic))
                .compose(markedDead -> {
                    assertEquals(0, markedDead, "Should not detect any dead subscriptions");
                    return getSubscriptionStatus(topic, groupName);
                })
                .onComplete(ctx.succeeding(status -> ctx.verify(() -> {
                    assertEquals("ACTIVE", status, "Subscription should remain ACTIVE");
                    logger.info("Healthy subscription preserved");
                    ctx.completeNow();
                })));
    }

    /**
     * Test that detect-all-dead works across multiple topics.
     */
    @Test
    void testDetectAllDeadAcrossTopics(VertxTestContext ctx) {
        logger.warn("===== INTENTIONAL WARN TEST ===== The next WARN logs ('Marked N subscriptions as DEAD') are EXPECTED  this test deliberately expires heartbeats across multiple topics");
        String topic1 = "test-dead-all-1-" + UUID.randomUUID().toString().substring(0, 8);
        String topic2 = "test-dead-all-2-" + UUID.randomUUID().toString().substring(0, 8);

        topicConfigService.createTopic(TopicConfig.builder()
                        .topic(topic1)
                        .semantics(TopicSemantics.PUB_SUB)
                        .messageRetentionHours(24)
                        .build())
                .compose(v -> topicConfigService.createTopic(TopicConfig.builder()
                        .topic(topic2)
                        .semantics(TopicSemantics.PUB_SUB)
                        .messageRetentionHours(24)
                        .build()))
                .compose(v -> subscriptionManager.subscribe(topic1, "dead-group", SubscriptionStore.OUTBOX, SubscriptionOptions.builder()
                        .heartbeatIntervalSeconds(1)
                        .heartbeatTimeoutSeconds(2)
                        .build()))
                .compose(v -> subscriptionManager.subscribe(topic2, "dead-group", SubscriptionStore.OUTBOX, SubscriptionOptions.builder()
                        .heartbeatIntervalSeconds(1)
                        .heartbeatTimeoutSeconds(2)
                        .build()))
                .compose(v -> runDetectionCycles(topic1, topic2, "dead-group", 3))
                .compose(v -> getSubscriptionStatus(topic1, "dead-group"))
                .compose(status1 -> {
                    assertEquals("DEAD", status1, topic1 + "/dead-group should be DEAD");
                    return getSubscriptionStatus(topic2, "dead-group");
                })
                .onComplete(ctx.succeeding(status2 -> ctx.verify(() -> {
                    assertEquals("DEAD", status2, topic2 + "/dead-group should be DEAD");
                    logger.info("Cross-topic dead detection verified");
                    ctx.completeNow();
                })));
    }

    /**
     * Test that the scheduled job starts and stops correctly.
     */
    @Test
    void testJobStartStop(VertxTestContext ctx) {
        job = new DeadConsumerDetectionJob(manager.getVertx(), detector, 500);

        assertFalse(job.isRunning(), "Job should not be running initially");

        job.start();
        assertTrue(job.isRunning(), "Job should be running after start");

        // Two runs prove the periodic timer fired: the first run happens in start().
        pollUntilRuns(2, System.currentTimeMillis() + 10_000)
                .compose(v -> job.stop())
                .onComplete(ctx.succeeding(v -> ctx.verify(() -> {
                    assertFalse(job.isRunning(), "Job should not be running after stop");
                    logger.info("Job start/stop lifecycle verified");
                    ctx.completeNow();
                })));
    }

    /**
     * Test that the job cannot be started twice.
     */
    @Test
    void testDoubleStartThrows(VertxTestContext ctx) {
        job = new DeadConsumerDetectionJob(manager.getVertx(), detector, 1000);
        job.start();

        try {
            job.start();
            ctx.failNow("Starting an already-running job should throw IllegalStateException");
        } catch (IllegalStateException e) {
            job.stop()
                .onSuccess(v -> {
                    logger.info("Double-start prevention verified");
                    ctx.completeNow();
                })
                .onFailure(ctx::failNow);
        }
    }

    /**
     * Test that runDetectionOnce works independently of the periodic schedule.
     */
    @Test
    void testRunDetectionOnce(VertxTestContext ctx) {
        logger.warn("===== INTENTIONAL WARN TEST ===== The next WARN log ('Marked N subscriptions as DEAD') is EXPECTED  this test deliberately runs manual dead detection");
        String topic = "test-manual-detect-" + UUID.randomUUID().toString().substring(0, 8);

        topicConfigService.createTopic(TopicConfig.builder()
                        .topic(topic)
                        .semantics(TopicSemantics.PUB_SUB)
                        .messageRetentionHours(24)
                        .build())
                .compose(v -> subscriptionManager.subscribe(topic, "manual-group", SubscriptionStore.OUTBOX, SubscriptionOptions.builder()
                        .heartbeatIntervalSeconds(1)
                        .heartbeatTimeoutSeconds(2)
                        .build()))
                .compose(v -> {
                    // Pre-run 2 detection cycles to reach just below flapping threshold
                    return runDetectionCycles(topic, "manual-group", 2)
                            .compose(u -> setHeartbeatInPast(topic, "manual-group", 10));
                })
                .compose(v -> {
                    job = new DeadConsumerDetectionJob(manager.getVertx(), detector);
                    return job.runDetectionOnce();
                })
                .onComplete(ctx.succeeding(result -> ctx.verify(() -> {
                    assertTrue(result >= 1, "Should detect at least 1 dead subscription");
                    logger.info("Manual detection run verified");
                    ctx.completeNow();
                })));
    }

    /**
     * Test that stopping the job fences future runs, even if timer callbacks are queued.
     */
    @Test
    void testStopPreventsFurtherRuns(VertxTestContext ctx) {
        job = new DeadConsumerDetectionJob(manager.getVertx(), detector, 5);
        job.start();

        // Three runs prove the periodic timer fires before the stop is tested.
        pollUntilRuns(3, System.currentTimeMillis() + 10_000)
                .compose(v -> job.stop())
                .compose(stopped -> {
                    assertFalse(job.isRunning(), "Job should report stopped");
                    long afterStopRuns = job.getTotalRunCount();
                    // Long enough for 50 more ticks of the 5 ms timer if it were still running.
                    return manager.getVertx().timer(250).map(v2 -> afterStopRuns);
                })
                .onComplete(ctx.succeeding(afterStopRuns -> ctx.verify(() -> {
                    assertEquals(afterStopRuns, job.getTotalRunCount(),
                            "Run count should not increase after stop");
                    logger.info("Stop fencing verified: runCount stayed at {}", afterStopRuns);
                    ctx.completeNow();
                })));
    }

    /**
     * A manual detection run that marks a group DEAD leaves the messages of the topic unchanged:
     * the dead group's unread messages stay PENDING with their required group count (R-1).
     */
    @Test
    void testRunDetectionOnceWithDetailsLeavesMessagesUnchanged(VertxTestContext ctx) {
        String topic = "test-manual-pipeline-" + UUID.randomUUID().toString().substring(0, 8);

        topicConfigService.createTopic(TopicConfig.builder()
                        .topic(topic)
                        .semantics(TopicSemantics.PUB_SUB)
                        .messageRetentionHours(24)
                        .build())
                .compose(v -> subscriptionManager.subscribe(topic, "group-a", SubscriptionStore.OUTBOX, SubscriptionOptions.builder()
                        .heartbeatIntervalSeconds(60)
                        .heartbeatTimeoutSeconds(300)
                        .build()))
                .compose(v -> subscriptionManager.subscribe(topic, "group-b", SubscriptionStore.OUTBOX, SubscriptionOptions.builder()
                        .heartbeatIntervalSeconds(1)
                        .heartbeatTimeoutSeconds(2)
                        .deadAfterMisses(1)
                        .build()))
                .compose(v -> insertMessages(topic, 2))
                .compose(messageIds -> completeMessagesAndReturnIds(topic, "group-a", messageIds))
                .compose(messageIds -> setHeartbeatInPast(topic, "group-b", 10)
                        .map(v -> messageIds))
                .compose(messageIds -> {
                    job = new DeadConsumerDetectionJob(manager.getVertx(), detector);
                    return job.runDetectionOnceWithDetails()
                            .compose(details -> {
                                assertTrue(details.deadCount() >= 1,
                                        "Manual detailed run should detect at least one dead subscription");
                                return verifyUnchangedMessagesAndReturn(messageIds);
                            });
                })
                .onSuccess(v -> {
                    logger.info("Manual detailed detection run left the messages unchanged");
                    ctx.completeNow();
                })
                .onFailure(ctx::failNow);
    }

    /**
     * The scheduled job marks a group DEAD and leaves the messages of the topic unchanged:
     * the dead group's unread messages stay PENDING with their required group count (R-1).
     */
    @Test
    void testScheduledDetectionLeavesMessagesUnchanged(VertxTestContext ctx) {
        String topic = "test-pipeline-" + UUID.randomUUID().toString().substring(0, 8);

        topicConfigService.createTopic(TopicConfig.builder()
                        .topic(topic)
                        .semantics(TopicSemantics.PUB_SUB)
                        .messageRetentionHours(24)
                        .build())
                .compose(v -> subscriptionManager.subscribe(topic, "group-a", SubscriptionStore.OUTBOX, SubscriptionOptions.builder()
                        .heartbeatIntervalSeconds(60)
                        .heartbeatTimeoutSeconds(300)
                        .build()))
                .compose(v -> subscriptionManager.subscribe(topic, "group-b", SubscriptionStore.OUTBOX, SubscriptionOptions.builder()
                        .heartbeatIntervalSeconds(1)
                        .heartbeatTimeoutSeconds(2)
                        .build()))
                .compose(v -> insertMessages(topic, 3))
                .compose(messageIds -> completeMessagesAndReturnIds(topic, "group-a", messageIds))
                .compose(messageIds -> verifyPendingMessagesAndReturn(topic, messageIds))
                .compose(messageIds -> setHeartbeatInPast(topic, "group-b", 10)
                        .map(v -> messageIds))
                .compose(messageIds -> {
                    job = new DeadConsumerDetectionJob(manager.getVertx(), detector, 200);
                    job.start();
                    return waitForDetectionAndReturn(topic, messageIds);
                })
                .compose(messageIds -> job.stop().compose(v -> {
                    assertTrue(job.getTotalDeadDetected() >= 1,
                            "Job should have detected at least 1 dead consumer");
                    assertTrue(job.getTotalRunCount() >= 1,
                            "Job should have completed at least 1 run");
                    assertEquals(0, job.getTotalFailures(),
                            "Job should have no failures");
                    return getSubscriptionStatus(topic, "group-b")
                            .map(ignored -> messageIds);
                }))
                .compose(messageIds -> verifyUnchangedMessagesAndReturn(messageIds))
                .onSuccess(v -> {
                    logger.info("Scheduled detection marked the group DEAD and left the messages unchanged");
                    ctx.completeNow();
                })
                .onFailure(ctx::failNow);
    }

    /**
     * Tests that the {@code detectionInProgress} guard prevents overlapping detection runs.
     */
    @Test
    void testConcurrentDetectionGuardPreventsOverlap(VertxTestContext ctx) {
        AtomicInteger detectionCalls = new AtomicInteger();
        Promise<DetectionResult> firstRunGate = Promise.promise();

        DeadConsumerDetector controlledDetector = new DeadConsumerDetector(
                connectionManager, "peegeeq-main") {
            @Override
            public Future<DetectionResult> detectAllDeadSubscriptionsWithDetails() {
                detectionCalls.incrementAndGet();
                return firstRunGate.future();
            }

            @Override
            public Future<SubscriptionSummary> getSubscriptionSummary() {
                return Future.succeededFuture(new SubscriptionSummary(0, 0, 0, 0, 0, 0));
            }
        };

        job = new DeadConsumerDetectionJob(manager.getVertx(), controlledDetector, 5);
        job.start();

        manager.getVertx().timer(100)
                .compose(v -> {
                    int callsWhileFirstRunBlocked = detectionCalls.intValue();
                    firstRunGate.complete(new DetectionResult(List.of(), 0, 0, 0));
                    return job.stop().map(callsWhileFirstRunBlocked);
                })
                .onComplete(ctx.succeeding(callsWhileFirstRunBlocked -> ctx.verify(() -> {
                    assertEquals(1, callsWhileFirstRunBlocked,
                            "Periodic timer callbacks must not enter detection while the first run is blocked");
                    assertEquals(0, job.getTotalFailures(),
                            "The controlled detection run should complete without failures");
                    logger.info("Concurrent detection guard verified with a blocked in-flight run");
                    ctx.completeNow();
                })));
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    /**
     * Re-checks the job every 50 ms until it has completed {@code minRuns} runs, and fails at
     * the deadline with the counts it saw.
     */
    private Future<Void> pollUntilRuns(long minRuns, long deadline) {
        if (job.getTotalRunCount() >= minRuns) {
            return Future.succeededFuture();
        }
        if (System.currentTimeMillis() >= deadline) {
            return Future.failedFuture(new AssertionError("DeadConsumerDetectionJob did not complete "
                    + minRuns + " runs before the deadline: runs=" + job.getTotalRunCount()
                    + ", failures=" + job.getTotalFailures()));
        }
        return manager.getVertx().timer(50L).compose(t -> pollUntilRuns(minRuns, deadline));
    }

    private Future<Void> setHeartbeatInPast(String topic, String groupName, int secondsAgo) {
        OffsetDateTime pastTime = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(secondsAgo);
        return connectionManager.withConnection("peegeeq-main", connection -> {
            String sql = """
                UPDATE outbox_topic_subscriptions
                SET last_heartbeat_at = $1
                WHERE topic = $2 AND group_name = $3
                """;
            return connection.preparedQuery(sql)
                    .execute(Tuple.of(pastTime, topic, groupName))
                    .mapEmpty();
        });
    }

    private Future<String> getSubscriptionStatus(String topic, String groupName) {
        return connectionManager.withConnection("peegeeq-main", connection -> {
            String sql = "SELECT subscription_status FROM outbox_topic_subscriptions WHERE topic = $1 AND group_name = $2";
            return connection.preparedQuery(sql)
                    .execute(Tuple.of(topic, groupName))
                    .map(rows -> {
                        if (rows.size() == 0) {
                            throw new RuntimeException("Subscription not found: " + topic + "/" + groupName);
                        }
                        return rows.iterator().next().getString("subscription_status");
                    });
        });
    }

    private Future<Void> runDetectionCycles(String topic1, String topic2, String groupName, int cycles) {
        Future<Void> future = Future.succeededFuture();
        for (int i = 0; i < cycles; i++) {
            future = future
                    .compose(v -> setHeartbeatInPast(topic1, groupName, 10))
                    .compose(v -> setHeartbeatInPast(topic2, groupName, 10))
                    .compose(v -> detector.detectAllDeadSubscriptions())
                    .mapEmpty();
        }
        return future;
    }

    private Future<Void> runDetectionCycles(String topic, String groupName, int cycles) {
        Future<Void> future = Future.succeededFuture();
        for (int i = 0; i < cycles; i++) {
            future = future
                    .compose(v -> setHeartbeatInPast(topic, groupName, 10))
                    .compose(v -> detector.detectAllDeadSubscriptions())
                    .mapEmpty();
        }
        return future;
    }

    private Future<List<Long>> insertMessages(String topic, int count) {
        List<Long> ids = new ArrayList<>();
        Future<Void> future = Future.succeededFuture();
        for (int i = 0; i < count; i++) {
            final int index = i;
            future = future.compose(v -> connectionManager.withConnection("peegeeq-main", connection -> {
                String sql = """
                    INSERT INTO outbox (topic, payload, created_at, status)
                    VALUES ($1, $2::jsonb, $3, 'PENDING')
                    RETURNING id
                    """;
                JsonObject payload = new JsonObject().put("test", true).put("index", index);
                return connection.preparedQuery(sql)
                        .execute(Tuple.of(topic, payload.encode(), OffsetDateTime.now(ZoneOffset.UTC)))
                        .map(rows -> rows.iterator().next().getLong("id"))
                        .map(id -> {
                            ids.add(id);
                            return null;
                        });
            }));
        }
        return future.map(v -> ids);
    }

    private Future<List<Long>> completeMessagesAndReturnIds(String topic, String groupName, List<Long> messageIds) {
        Future<Void> future = Future.succeededFuture();
        for (Long msgId : messageIds) {
            future = future.compose(v -> connectionManager.withTransaction("peegeeq-main", connection -> {
                String insertSql = """
                    INSERT INTO outbox_consumer_groups (message_id, group_name, status, processed_at)
                    VALUES ($1, $2, 'COMPLETED', NOW())
                    ON CONFLICT (message_id, group_name)
                    DO UPDATE SET status = 'COMPLETED', processed_at = NOW()
                    """;
                return connection.preparedQuery(insertSql)
                        .execute(Tuple.of(msgId, groupName))
                        .compose(u -> {
                            String updateSql = """
                                UPDATE outbox
                                SET completed_consumer_groups = completed_consumer_groups + 1,
                                    status = CASE
                                        WHEN completed_consumer_groups + 1 >= required_consumer_groups
                                            THEN 'COMPLETED'
                                        ELSE status
                                    END,
                                    processed_at = CASE
                                        WHEN completed_consumer_groups + 1 >= required_consumer_groups
                                            THEN NOW()
                                        ELSE processed_at
                                    END
                                WHERE id = $1
                                  AND completed_consumer_groups < required_consumer_groups
                                """;
                            return connection.preparedQuery(updateSql)
                                    .execute(Tuple.of(msgId))
                                    .mapEmpty();
                        });
            }));
        }
        return future.map(v -> messageIds);
    }

    private Future<List<Long>> verifyPendingMessagesAndReturn(String topic, List<Long> messageIds) {
        Future<Void> future = Future.succeededFuture();
        for (Long msgId : messageIds) {
            future = future.compose(v -> getMessageRow(msgId)
                    .map(state -> {
                        assertEquals("PENDING", state.getString("status"),
                                "Message " + msgId + " should be PENDING before pipeline");
                        assertEquals(2, state.getInteger("required_consumer_groups"));
                        assertEquals(1, state.getInteger("completed_consumer_groups"));
                        return null;
                    }));
        }
        return future.map(v -> messageIds);
    }

    /**
     * Each message is as group-a left it: completed by group-a, unread by the dead group-b,
     * PENDING, and still required by both groups.
     */
    private Future<List<Long>> verifyUnchangedMessagesAndReturn(List<Long> messageIds) {
        Future<Void> future = Future.succeededFuture();
        for (Long msgId : messageIds) {
            future = future.compose(v -> getMessageRow(msgId)
                    .map(state -> {
                        assertEquals("PENDING", state.getString("status"),
                                "Detection must not mark message " + msgId + " processed");
                        assertEquals(2, state.getInteger("required_consumer_groups"),
                                "Detection must not change the required group count of message " + msgId);
                        assertEquals(1, state.getInteger("completed_consumer_groups"),
                                "Detection must not change the completed group count of message " + msgId);
                        return null;
                    }));
        }
        return future.map(v -> messageIds);
    }

    private Future<List<Long>> waitForDetectionAndReturn(String topic, List<Long> messageIds) {
        return waitForDetectionCycles(topic, 0, messageIds);
    }

    private Future<List<Long>> waitForDetectionCycles(String topic, int cycle, List<Long> messageIds) {
        if (cycle >= 25) {
            return Future.failedFuture(new RuntimeException(
                    "group-b on topic '" + topic + "' was not detected as DEAD within " + (25 * 200) + "ms"));
        }
        return getSubscriptionStatus(topic, "group-b")
                .compose(status -> {
                    if ("DEAD".equals(status)) {
                        // group-b is DEAD  the detection query has marked it dead.
                        // Stop the job; stop() awaits inFlightDetection which covers the
                        // full detection chain, so callers can safely verify
                        // message states without racing against detection writes.
                        return job.stop().map(v -> messageIds);
                    }
                    return manager.getVertx().timer(200)
                            .compose(v -> waitForDetectionCycles(topic, cycle + 1, messageIds));
                });
    }

    private Future<Row> getMessageRow(Long messageId) {
        return connectionManager.withConnection("peegeeq-main", connection -> {
            String sql = "SELECT status, required_consumer_groups, completed_consumer_groups FROM outbox WHERE id = $1";
            return connection.preparedQuery(sql)
                    .execute(Tuple.of(messageId))
                    .map(rows -> {
                        if (rows.size() == 0) {
                            throw new RuntimeException("Message not found: " + messageId);
                        }
                        return rows.iterator().next();
                    });
        });
    }
}
