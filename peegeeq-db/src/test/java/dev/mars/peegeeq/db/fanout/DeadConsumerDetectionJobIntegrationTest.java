package dev.mars.peegeeq.db.fanout;

import dev.mars.peegeeq.db.cleanup.DeadConsumerDetector.DetectionResult;

import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.cleanup.DeadConsumerDetector;
import dev.mars.peegeeq.db.cleanup.DeadConsumerDetectionJob;
import dev.mars.peegeeq.db.cleanup.DeadConsumerGroupCleanup;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.db.subscription.SubscriptionManager;
import dev.mars.peegeeq.db.subscription.TopicConfig;
import dev.mars.peegeeq.db.subscription.TopicConfigService;
import dev.mars.peegeeq.db.subscription.TopicSemantics;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link DeadConsumerDetectionJob}.
 *
 * <p>Tests validate that the scheduled job correctly detects subscriptions
 * whose heartbeat has timed out and marks them as DEAD.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-12-01
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
@Execution(ExecutionMode.SAME_THREAD)
public class DeadConsumerDetectionJobIntegrationTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(DeadConsumerDetectionJobIntegrationTest.class);

    private PgConnectionManager connectionManager;
    private TopicConfigService topicConfigService;
    private SubscriptionManager subscriptionManager;
    private DeadConsumerDetector detector;
    private DeadConsumerGroupCleanup cleanup;
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
                .schema("public")
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
        cleanup = new DeadConsumerGroupCleanup(connectionManager, "peegeeq-main");

        logger.info("Test setup complete");
    }

    @AfterEach
    void cleanUp(VertxTestContext testContext) {
        if (connectionManager != null) {
            Future<Void> stopJob = (job != null && job.isRunning()) ? job.stop() : Future.succeededFuture();
            stopJob
                    .compose(v -> cleanup.cleanupAllDeadGroups())
                    .eventually(() -> connectionManager.close())
                    .onSuccess(v -> testContext.completeNow())
                    .onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }
    }
    /**
     * Test that a subscription with an expired heartbeat is detected and marked as DEAD.
     */
    @Test
    void testDetectsExpiredHeartbeat(VertxTestContext ctx) {
        logger.warn("===== INTENTIONAL WARN TEST ===== The next WARN logs ('Marked N subscriptions as DEAD', detection run summary) are EXPECTED ÔÇö this test deliberately expires a heartbeat to verify dead consumer detection job");
        String topic = "test-dead-detect-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "dead-group-1";

        topicConfigService.createTopic(TopicConfig.builder()
                        .topic(topic)
                        .semantics(TopicSemantics.PUB_SUB)
                        .messageRetentionHours(24)
                        .build())
                .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.builder()
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
                .onSuccess(status -> {
                    assertEquals("DEAD", status, "Subscription should be marked as DEAD");
                    logger.info("Dead consumer detection verified");
                    ctx.completeNow();
                })
                .onFailure(ctx::failNow);
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
                .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.builder()
                        .heartbeatIntervalSeconds(60)
                        .heartbeatTimeoutSeconds(300)
                        .build()))
                .compose(v -> detector.detectDeadSubscriptions(topic))
                .compose(markedDead -> {
                    assertEquals(0, markedDead, "Should not detect any dead subscriptions");
                    return getSubscriptionStatus(topic, groupName);
                })
                .onSuccess(status -> {
                    assertEquals("ACTIVE", status, "Subscription should remain ACTIVE");
                    logger.info("Healthy subscription preserved");
                    ctx.completeNow();
                })
                .onFailure(ctx::failNow);
    }

    /**
     * Test that detect-all-dead works across multiple topics.
     */
    @Test
    void testDetectAllDeadAcrossTopics(VertxTestContext ctx) {
        logger.warn("===== INTENTIONAL WARN TEST ===== The next WARN logs ('Marked N subscriptions as DEAD') are EXPECTED ÔÇö this test deliberately expires heartbeats across multiple topics");
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
                .compose(v -> subscriptionManager.subscribe(topic1, "dead-group", SubscriptionOptions.builder()
                        .heartbeatIntervalSeconds(1)
                        .heartbeatTimeoutSeconds(2)
                        .build()))
                .compose(v -> subscriptionManager.subscribe(topic2, "dead-group", SubscriptionOptions.builder()
                        .heartbeatIntervalSeconds(1)
                        .heartbeatTimeoutSeconds(2)
                        .build()))
                .compose(v -> runDetectionCycles(topic1, topic2, "dead-group", 3))
                .compose(v -> getSubscriptionStatus(topic1, "dead-group"))
                .compose(status1 -> {
                    assertEquals("DEAD", status1, topic1 + "/dead-group should be DEAD");
                    return getSubscriptionStatus(topic2, "dead-group");
                })
                .onSuccess(status2 -> {
                    assertEquals("DEAD", status2, topic2 + "/dead-group should be DEAD");
                    logger.info("Cross-topic dead detection verified");
                    ctx.completeNow();
                })
                .onFailure(ctx::failNow);
    }

    /**
     * Test that the scheduled job starts and stops correctly.
     */
    @Test
    void testJobStartStop(VertxTestContext ctx) {
        job = new DeadConsumerDetectionJob(manager.getVertx(), detector, cleanup, 500);

        assertFalse(job.isRunning(), "Job should not be running initially");

        job.start();
        assertTrue(job.isRunning(), "Job should be running after start");

        manager.getVertx().timer(1200)
                .onSuccess(v -> {
                    job.stop();
                    assertFalse(job.isRunning(), "Job should not be running after stop");
                    logger.info("Job start/stop lifecycle verified");
                    ctx.completeNow();
                })
                .onFailure(ctx::failNow);
    }

    /**
     * Test that the job cannot be started twice.
     */
    @Test
    void testDoubleStartThrows(VertxTestContext ctx) {
        job = new DeadConsumerDetectionJob(manager.getVertx(), detector, cleanup, 1000);
        job.start();

        try {
            job.start();
            ctx.failNow("Starting an already-running job should throw IllegalStateException");
        } catch (IllegalStateException e) {
            job.stop();
            logger.info("Double-start prevention verified");
            ctx.completeNow();
        }
    }

    /**
     * Test that runDetectionOnce works independently of the periodic schedule.
     */
    @Test
    void testRunDetectionOnce(VertxTestContext ctx) {
        logger.warn("===== INTENTIONAL WARN TEST ===== The next WARN log ('Marked N subscriptions as DEAD') is EXPECTED ÔÇö this test deliberately runs manual dead detection");
        String topic = "test-manual-detect-" + UUID.randomUUID().toString().substring(0, 8);

        topicConfigService.createTopic(TopicConfig.builder()
                        .topic(topic)
                        .semantics(TopicSemantics.PUB_SUB)
                        .messageRetentionHours(24)
                        .build())
                .compose(v -> subscriptionManager.subscribe(topic, "manual-group", SubscriptionOptions.builder()
                        .heartbeatIntervalSeconds(1)
                        .heartbeatTimeoutSeconds(2)
                        .build()))
                .compose(v -> {
                    // Pre-run 2 detection cycles to reach just below flapping threshold
                    return runDetectionCycles(topic, "manual-group", 2)
                            .compose(u -> setHeartbeatInPast(topic, "manual-group", 10));
                })
                .compose(v -> {
                    job = new DeadConsumerDetectionJob(manager.getVertx(), detector, cleanup);
                    return job.runDetectionOnce();
                })
                .onSuccess(result -> {
                    assertTrue(result >= 1, "Should detect at least 1 dead subscription");
                    logger.info("Manual detection run verified");
                    ctx.completeNow();
                })
                .onFailure(ctx::failNow);
    }

    /**
     * Test that stopping the job fences future runs, even if timer callbacks are queued.
     */
    @Test
    void testStopPreventsFurtherRuns(VertxTestContext ctx) {
        job = new DeadConsumerDetectionJob(manager.getVertx(), detector, cleanup, 5);
        job.start();

        manager.getVertx().timer(250)
                .onSuccess(v -> {
                    long beforeStopRuns = job.getTotalRunCount();
                    job.stop();
                    assertFalse(job.isRunning(), "Job should report stopped");

                    manager.getVertx().timer(250)
                            .onSuccess(v2 -> {
                                long afterStopRuns = job.getTotalRunCount();
                                assertEquals(beforeStopRuns, afterStopRuns,
                                        "Run count should not increase after stop");
                                logger.info("Stop fencing verified: runCount stayed at {}", afterStopRuns);
                                ctx.completeNow();
                            })
                            .onFailure(ctx::failNow);
                })
                .onFailure(ctx::failNow);
    }

    /**
     * Test that manual runDetectionOnceWithDetails executes detection + cleanup pipeline.
     */
    @Test
    void testRunDetectionOnceWithDetailsPerformsCleanupPipeline(VertxTestContext ctx) {
        String topic = "test-manual-pipeline-" + UUID.randomUUID().toString().substring(0, 8);

        topicConfigService.createTopic(TopicConfig.builder()
                        .topic(topic)
                        .semantics(TopicSemantics.PUB_SUB)
                        .messageRetentionHours(24)
                        .build())
                .compose(v -> subscriptionManager.subscribe(topic, "group-a", SubscriptionOptions.builder()
                        .heartbeatIntervalSeconds(60)
                        .heartbeatTimeoutSeconds(300)
                        .build()))
                .compose(v -> subscriptionManager.subscribe(topic, "group-b", SubscriptionOptions.builder()
                        .heartbeatIntervalSeconds(1)
                        .heartbeatTimeoutSeconds(2)
                        .deadAfterMisses(1)
                        .build()))
                .compose(v -> insertMessages(topic, 2))
                .compose(messageIds -> completeMessagesAndReturnIds(topic, "group-a", messageIds))
                .compose(messageIds -> setHeartbeatInPast(topic, "group-b", 10)
                        .map(v -> messageIds))
                .compose(messageIds -> {
                    job = new DeadConsumerDetectionJob(manager.getVertx(), detector, cleanup);
                    return job.runDetectionOnceWithDetails()
                            .compose(details -> verifyCleanupMessagesAndReturn(messageIds, details));
                })
                .onSuccess(v -> {
                    logger.info("Manual detailed detection pipeline verified");
                    ctx.completeNow();
                })
                .onFailure(ctx::failNow);
    }

    /**
     * Tests the full end-to-end pipeline through the scheduled job:
     * detection ÔåÆ cleanup ÔåÆ messages unblocked.
     */
    @Test
    void testEndToEndDetectCleanupPipeline(VertxTestContext ctx) {
        String topic = "test-pipeline-" + UUID.randomUUID().toString().substring(0, 8);

        topicConfigService.createTopic(TopicConfig.builder()
                        .topic(topic)
                        .semantics(TopicSemantics.PUB_SUB)
                        .messageRetentionHours(24)
                        .build())
                .compose(v -> subscriptionManager.subscribe(topic, "group-a", SubscriptionOptions.builder()
                        .heartbeatIntervalSeconds(60)
                        .heartbeatTimeoutSeconds(300)
                        .build()))
                .compose(v -> subscriptionManager.subscribe(topic, "group-b", SubscriptionOptions.builder()
                        .heartbeatIntervalSeconds(1)
                        .heartbeatTimeoutSeconds(2)
                        .build()))
                .compose(v -> insertMessages(topic, 3))
                .compose(messageIds -> completeMessagesAndReturnIds(topic, "group-a", messageIds))
                .compose(messageIds -> verifyPendingMessagesAndReturn(topic, messageIds))
                .compose(messageIds -> setHeartbeatInPast(topic, "group-b", 10)
                        .map(v -> messageIds))
                .compose(messageIds -> {
                    job = new DeadConsumerDetectionJob(manager.getVertx(), detector, cleanup, 200);
                    job.start();
                    return waitForDetectionAndReturn(messageIds);
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
                .compose(messageIds -> verifyCompletedMessagesAndReturn(topic, messageIds))
                .onSuccess(v -> {
                    logger.info("End-to-end pipeline verified: detect ÔåÆ cleanup ÔåÆ auto-complete");
                    ctx.completeNow();
                })
                .onFailure(ctx::failNow);
    }

    /**
     * Tests that the {@code detectionInProgress} guard prevents overlapping detection runs.
     */
    @Test
    void testConcurrentDetectionGuardPreventsOverlap(VertxTestContext ctx) {
        Future<Void> setupFuture = Future.succeededFuture();

        // Create multiple subscriptions so the detection query does real work
        for (int i = 0; i < 10; i++) {
            final int index = i;
            setupFuture = setupFuture.compose(v -> {
                String topic = "test-guard-" + UUID.randomUUID().toString().substring(0, 8);
                return topicConfigService.createTopic(TopicConfig.builder()
                        .topic(topic)
                        .semantics(TopicSemantics.PUB_SUB)
                        .messageRetentionHours(24)
                        .build())
                        .compose(u -> subscriptionManager.subscribe(topic, "group-" + index, SubscriptionOptions.builder()
                                .heartbeatIntervalSeconds(60)
                                .heartbeatTimeoutSeconds(300)
                                .build()));
            });
        }

        setupFuture
                .compose(v -> {
                    job = new DeadConsumerDetectionJob(manager.getVertx(), detector, cleanup, 5);
                    job.start();
                    return manager.getVertx().timer(2000);
                })
                .onSuccess(v -> {
                    job.stop();
                    long runCount = job.getTotalRunCount();
                    long failures = job.getTotalFailures();
                    long expectedTicks = 400;

                    logger.info("Guard test: {} runs completed out of ~{} timer ticks, {} failures",
                            runCount, expectedTicks, failures);

                    assertTrue(runCount >= 1, "Should have completed at least 1 run");
                    assertTrue(runCount < expectedTicks * 3 / 4,
                            "Guard should skip overlapping runs. Expected <" + (expectedTicks * 3 / 4) +
                                    " but got " + runCount + " out of ~" + expectedTicks + " timer ticks");
                    assertEquals(0, failures,
                            "No failures should occur ÔÇö guard prevents concurrent DB access");

                    logger.info("Concurrent detection guard verified: {} runs (not ~100)", runCount);
                    ctx.completeNow();
                })
                .onFailure(ctx::failNow);
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

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

    private Future<List<Long>> verifyCompletedMessagesAndReturn(String topic, List<Long> messageIds) {
        Future<Void> future = Future.succeededFuture();
        for (Long msgId : messageIds) {
            future = future.compose(v -> getMessageRow(msgId)
                    .map(state -> {
                        assertEquals("COMPLETED", state.getString("status"),
                                "Message " + msgId + " should be auto-completed by cleanup pipeline");
                        assertEquals(1, state.getInteger("required_consumer_groups"),
                                "required_consumer_groups should be decremented from 2 to 1");
                        return null;
                    }));
        }
        return future.map(v -> messageIds);
    }

    private Future<Void> verifyCleanupMessagesAndReturn(List<Long> messageIds, DetectionResult details) {
        assertTrue(details.deadCount() >= 1,
                "Manual detailed run should detect at least one dead subscription");
        Future<Void> future = Future.succeededFuture();
        for (Long msgId : messageIds) {
            future = future.compose(v -> getMessageRow(msgId)
                    .map(state -> {
                        assertEquals(1, state.getInteger("required_consumer_groups"),
                                "Manual detailed run should execute cleanup pipeline");
                        return null;
                    }));
        }
        return future;
    }

    private Future<List<Long>> waitForDetectionAndReturn(List<Long> messageIds) {
        return waitForDetectionCycles(0, messageIds);
    }

    private Future<List<Long>> waitForDetectionCycles(int cycle, List<Long> messageIds) {
        if (cycle >= 25) {
            return Future.failedFuture(new RuntimeException("Detection did not complete within timeout"));
        }

        if (job.getTotalDeadDetected() >= 1) {
            return manager.getVertx().timer(500)
                    .map(v -> messageIds);
        }

        return manager.getVertx().timer(200)
                .compose(v -> waitForDetectionCycles(cycle + 1, messageIds));
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
