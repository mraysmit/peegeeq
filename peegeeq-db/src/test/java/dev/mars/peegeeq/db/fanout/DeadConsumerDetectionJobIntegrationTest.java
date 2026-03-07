package dev.mars.peegeeq.db.fanout;

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

        PostgreSQLContainer<?> postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .database(postgres.getDatabaseName())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .schema("public")
                .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
                .maxSize(10)
                .build();

        connectionManager.getOrCreateReactivePool("peegeeq-main", connectionConfig, poolConfig);

        topicConfigService = new TopicConfigService(connectionManager, "peegeeq-main");
        subscriptionManager = new SubscriptionManager(connectionManager, "peegeeq-main");
        detector = new DeadConsumerDetector(connectionManager, "peegeeq-main");
        cleanup = new DeadConsumerGroupCleanup(connectionManager, "peegeeq-main");

        logger.info("Test setup complete");
    }

    @AfterEach
    void cleanUp() {
        if (job != null && job.isRunning()) {
            job.stop();
        }
    }

    /**
     * Test that a subscription with an expired heartbeat is detected and marked as DEAD.
     */
    @Test
    void testDetectsExpiredHeartbeat() throws Exception {
        String topic = "test-dead-detect-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "dead-group-1";

        // Create topic
        topicConfigService.createTopic(TopicConfig.builder()
                        .topic(topic)
                        .semantics(TopicSemantics.PUB_SUB)
                        .messageRetentionHours(24)
                        .build())
                .toCompletionStage().toCompletableFuture().get();

        // Subscribe with very short heartbeat timeout (2 seconds, interval 1)
        SubscriptionOptions options = SubscriptionOptions.builder()
                .heartbeatIntervalSeconds(1)
                .heartbeatTimeoutSeconds(2)
                .build();
        subscriptionManager.subscribe(topic, groupName, options)
                .toCompletionStage().toCompletableFuture().get();

        // Set heartbeat to the past to simulate timeout
        setHeartbeatInPast(topic, groupName, 10);

        // Run detection
        Integer markedDead = detector.detectDeadSubscriptions(topic)
                .toCompletionStage().toCompletableFuture().get();

        assertEquals(1, markedDead, "Should detect 1 dead subscription");

        // Verify subscription is now DEAD
        String status = getSubscriptionStatus(topic, groupName)
                .toCompletionStage().toCompletableFuture().get();
        assertEquals("DEAD", status, "Subscription should be marked as DEAD");

        logger.info("✅ Dead consumer detection verified");
    }

    /**
     * Test that healthy subscriptions (recent heartbeat) are NOT marked as DEAD.
     */
    @Test
    void testDoesNotMarkHealthySubscriptions() throws Exception {
        String topic = "test-healthy-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "healthy-group-1";

        topicConfigService.createTopic(TopicConfig.builder()
                        .topic(topic)
                        .semantics(TopicSemantics.PUB_SUB)
                        .messageRetentionHours(24)
                        .build())
                .toCompletionStage().toCompletableFuture().get();

        // Subscribe with long timeout
        SubscriptionOptions options = SubscriptionOptions.builder()
                .heartbeatIntervalSeconds(60)
                .heartbeatTimeoutSeconds(300)
                .build();
        subscriptionManager.subscribe(topic, groupName, options)
                .toCompletionStage().toCompletableFuture().get();

        // Run detection (heartbeat is fresh)
        Integer markedDead = detector.detectDeadSubscriptions(topic)
                .toCompletionStage().toCompletableFuture().get();

        assertEquals(0, markedDead, "Should not detect any dead subscriptions");

        // Verify subscription is still ACTIVE
        String status = getSubscriptionStatus(topic, groupName)
                .toCompletionStage().toCompletableFuture().get();
        assertEquals("ACTIVE", status, "Subscription should remain ACTIVE");

        logger.info("✅ Healthy subscription preserved");
    }

    /**
     * Test that detect-all-dead works across multiple topics.
     */
    @Test
    void testDetectAllDeadAcrossTopics() throws Exception {
        String topic1 = "test-dead-all-1-" + UUID.randomUUID().toString().substring(0, 8);
        String topic2 = "test-dead-all-2-" + UUID.randomUUID().toString().substring(0, 8);

        // Create both topics
        for (String topic : new String[]{topic1, topic2}) {
            topicConfigService.createTopic(TopicConfig.builder()
                            .topic(topic)
                            .semantics(TopicSemantics.PUB_SUB)
                            .messageRetentionHours(24)
                            .build())
                    .toCompletionStage().toCompletableFuture().get();
        }

        // Subscribe with short timeout
        SubscriptionOptions shortTimeout = SubscriptionOptions.builder()
                .heartbeatIntervalSeconds(1)
                .heartbeatTimeoutSeconds(2)
                .build();

        subscriptionManager.subscribe(topic1, "dead-group", shortTimeout)
                .toCompletionStage().toCompletableFuture().get();
        subscriptionManager.subscribe(topic2, "dead-group", shortTimeout)
                .toCompletionStage().toCompletableFuture().get();

        // Expire both heartbeats
        setHeartbeatInPast(topic1, "dead-group", 10);
        setHeartbeatInPast(topic2, "dead-group", 10);

        // Run detection across all topics
        Integer markedDead = detector.detectAllDeadSubscriptions()
                .toCompletionStage().toCompletableFuture().get();

        // Don't assert exact count — other parallel test classes may have already detected ours
        // Instead, verify our specific subscriptions ended up DEAD
        String status1 = getSubscriptionStatus(topic1, "dead-group")
                .toCompletionStage().toCompletableFuture().get();
        String status2 = getSubscriptionStatus(topic2, "dead-group")
                .toCompletionStage().toCompletableFuture().get();

        assertEquals("DEAD", status1, topic1 + "/dead-group should be DEAD");
        assertEquals("DEAD", status2, topic2 + "/dead-group should be DEAD");

        logger.info("✅ Cross-topic dead detection verified (markedDead={})", markedDead);
    }

    /**
     * Test that the scheduled job starts and stops correctly.
     */
    @Test
    void testJobStartStop() throws Exception {
        job = new DeadConsumerDetectionJob(manager.getVertx(), detector, cleanup, 500);

        assertFalse(job.isRunning(), "Job should not be running initially");

        job.start();
        assertTrue(job.isRunning(), "Job should be running after start");

        // Let it run a couple cycles
        Thread.sleep(1200);

        job.stop();
        assertFalse(job.isRunning(), "Job should not be running after stop");

        logger.info("✅ Job start/stop lifecycle verified");
    }

    /**
     * Test that the job cannot be started twice.
     */
    @Test
    void testDoubleStartThrows() {
        job = new DeadConsumerDetectionJob(manager.getVertx(), detector, cleanup, 1000);
        job.start();

        assertThrows(IllegalStateException.class, () -> job.start(),
                "Starting an already-running job should throw IllegalStateException");

        job.stop();
        logger.info("✅ Double-start prevention verified");
    }

    /**
     * Test that runDetectionOnce works independently of the periodic schedule.
     */
    @Test
    void testRunDetectionOnce() throws Exception {
        String topic = "test-manual-detect-" + UUID.randomUUID().toString().substring(0, 8);

        topicConfigService.createTopic(TopicConfig.builder()
                        .topic(topic)
                        .semantics(TopicSemantics.PUB_SUB)
                        .messageRetentionHours(24)
                        .build())
                .toCompletionStage().toCompletableFuture().get();

        SubscriptionOptions shortTimeout = SubscriptionOptions.builder()
                .heartbeatIntervalSeconds(1)
                .heartbeatTimeoutSeconds(2)
                .build();
        subscriptionManager.subscribe(topic, "manual-group", shortTimeout)
                .toCompletionStage().toCompletableFuture().get();

        setHeartbeatInPast(topic, "manual-group", 10);

        // Use job's runDetectionOnce without starting periodic schedule
        job = new DeadConsumerDetectionJob(manager.getVertx(), detector, cleanup);
        Integer result = job.runDetectionOnce()
                .toCompletionStage().toCompletableFuture().get();

        assertTrue(result >= 1, "Should detect at least 1 dead subscription");

        logger.info("✅ Manual detection run verified");
    }

    /**
     * Test that stopping the job fences future runs, even if timer callbacks are queued.
     */
    @Test
    void testStopPreventsFurtherRuns() throws Exception {
        job = new DeadConsumerDetectionJob(manager.getVertx(), detector, cleanup, 5);
        job.start();

        Thread.sleep(250);
        long beforeStopRuns = job.getTotalRunCount();

        job.stop();
        assertFalse(job.isRunning(), "Job should report stopped");

        // Wait long enough that many timer ticks would have happened if not fenced.
        Thread.sleep(250);
        long afterStopRuns = job.getTotalRunCount();

        assertEquals(beforeStopRuns, afterStopRuns,
                "Run count should not increase after stop");

        logger.info("✅ Stop fencing verified: runCount stayed at {}", afterStopRuns);
    }

    /**
     * Test that manual runDetectionOnceWithDetails executes detection + cleanup pipeline.
     */
    @Test
    void testRunDetectionOnceWithDetailsPerformsCleanupPipeline() throws Exception {
        String topic = "test-manual-pipeline-" + UUID.randomUUID().toString().substring(0, 8);

        topicConfigService.createTopic(TopicConfig.builder()
                        .topic(topic)
                        .semantics(TopicSemantics.PUB_SUB)
                        .messageRetentionHours(24)
                        .build())
                .toCompletionStage().toCompletableFuture().get();

        SubscriptionOptions groupAOptions = SubscriptionOptions.builder()
                .heartbeatIntervalSeconds(60)
                .heartbeatTimeoutSeconds(300)
                .build();
        subscriptionManager.subscribe(topic, "group-a", groupAOptions)
                .toCompletionStage().toCompletableFuture().get();

        SubscriptionOptions groupBOptions = SubscriptionOptions.builder()
                .heartbeatIntervalSeconds(1)
                .heartbeatTimeoutSeconds(2)
                .build();
        subscriptionManager.subscribe(topic, "group-b", groupBOptions)
                .toCompletionStage().toCompletableFuture().get();

        List<Long> messageIds = insertMessages(topic, 2);
        for (Long msgId : messageIds) {
            completeMessage(msgId, "group-a");
        }

        // Expire group-b so detector marks it DEAD.
        setHeartbeatInPast(topic, "group-b", 10);

        job = new DeadConsumerDetectionJob(manager.getVertx(), detector, cleanup);
        var details = job.runDetectionOnceWithDetails()
                .toCompletionStage().toCompletableFuture().get();

        assertTrue(details.deadCount() >= 1,
                "Manual detailed run should detect at least one dead subscription");

        // Cleanup side effect: required_consumer_groups should be decremented for pending messages.
        for (Long msgId : messageIds) {
            Row state = getMessageRow(msgId);
            assertEquals(1, state.getInteger("required_consumer_groups"),
                    "Manual detailed run should execute cleanup pipeline");
        }

        logger.info("✅ Manual detailed detection pipeline verified");
    }

    // Helper methods

    private void setHeartbeatInPast(String topic, String groupName, int secondsAgo) throws Exception {
        OffsetDateTime pastTime = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(secondsAgo);
        connectionManager.withConnection("peegeeq-main", connection -> {
            String sql = """
                UPDATE outbox_topic_subscriptions
                SET last_heartbeat_at = $1
                WHERE topic = $2 AND group_name = $3
                """;
            return connection.preparedQuery(sql)
                    .execute(Tuple.of(pastTime, topic, groupName))
                    .mapEmpty();
        }).toCompletionStage().toCompletableFuture().get();
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

    // ========================================================================
    // End-to-end pipeline test: detection → cleanup → auto-complete
    // ========================================================================

    /**
     * Tests the full end-to-end pipeline through the scheduled job:
     * detection → cleanup → messages unblocked.
     *
     * <p>Scenario:</p>
     * <ul>
     *   <li>Topic with 2 consumer groups (group-a with long timeout, group-b with short timeout)</li>
     *   <li>3 messages inserted (required_consumer_groups = 2 via trigger)</li>
     *   <li>group-a completes all 3 messages (completed_consumer_groups = 1)</li>
     *   <li>group-b heartbeat expires</li>
     *   <li>Job runs periodically, detects group-b as DEAD, runs cleanup</li>
     *   <li>Cleanup decrements required_consumer_groups (2 → 1)</li>
     *   <li>Since completed (1) >= required (1), all messages auto-complete</li>
     * </ul>
     */
    @Test
    void testEndToEndDetectCleanupPipeline() throws Exception {
        String topic = "test-pipeline-" + UUID.randomUUID().toString().substring(0, 8);

        // Create topic
        topicConfigService.createTopic(TopicConfig.builder()
                        .topic(topic)
                        .semantics(TopicSemantics.PUB_SUB)
                        .messageRetentionHours(24)
                        .build())
                .toCompletionStage().toCompletableFuture().get();

        // Subscribe group-a with long timeout (won't expire during test)
        SubscriptionOptions groupAOptions = SubscriptionOptions.builder()
                .heartbeatIntervalSeconds(60)
                .heartbeatTimeoutSeconds(300)
                .build();
        subscriptionManager.subscribe(topic, "group-a", groupAOptions)
                .toCompletionStage().toCompletableFuture().get();

        // Subscribe group-b with very short timeout (will expire)
        SubscriptionOptions groupBOptions = SubscriptionOptions.builder()
                .heartbeatIntervalSeconds(1)
                .heartbeatTimeoutSeconds(2)
                .build();
        subscriptionManager.subscribe(topic, "group-b", groupBOptions)
                .toCompletionStage().toCompletableFuture().get();

        // Insert 3 messages (trigger sets required_consumer_groups = 2)
        List<Long> messageIds = insertMessages(topic, 3);

        // group-a completes all 3 messages
        for (Long msgId : messageIds) {
            completeMessage(msgId, "group-a");
        }

        // Verify pre-condition: messages are PENDING with required=2, completed=1
        for (Long msgId : messageIds) {
            Row state = getMessageRow(msgId);
            assertEquals("PENDING", state.getString("status"),
                    "Message " + msgId + " should be PENDING before pipeline");
            assertEquals(2, state.getInteger("required_consumer_groups"));
            assertEquals(1, state.getInteger("completed_consumer_groups"));
        }

        // Expire group-b heartbeat
        setHeartbeatInPast(topic, "group-b", 10);

        // Start job with very short interval
        job = new DeadConsumerDetectionJob(manager.getVertx(), detector, cleanup, 200);
        job.start();

        // Wait for the job to detect and clean up (generous 5-second timeout)
        for (int i = 0; i < 25; i++) {
            Thread.sleep(200);
            if (job.getTotalDeadDetected() >= 1) {
                // Give cleanup time to complete after detection
                Thread.sleep(500);
                break;
            }
        }

        job.stop();

        // Verify: job detected dead consumers
        assertTrue(job.getTotalDeadDetected() >= 1,
                "Job should have detected at least 1 dead consumer");
        assertTrue(job.getTotalRunCount() >= 1,
                "Job should have completed at least 1 run");
        assertEquals(0, job.getTotalFailures(),
                "Job should have no failures");

        // Verify: group-b is DEAD
        String statusB = getSubscriptionStatus(topic, "group-b")
                .toCompletionStage().toCompletableFuture().get();
        assertEquals("DEAD", statusB, "group-b should be marked DEAD");

        // Verify: all messages auto-completed by the cleanup pipeline
        for (Long msgId : messageIds) {
            Row state = getMessageRow(msgId);
            assertEquals("COMPLETED", state.getString("status"),
                    "Message " + msgId + " should be auto-completed by cleanup pipeline");
            assertEquals(1, state.getInteger("required_consumer_groups"),
                    "required_consumer_groups should be decremented from 2 to 1");
        }

        logger.info("✅ End-to-end pipeline verified: detect → cleanup → auto-complete");
    }

    // ========================================================================
    // Test: Concurrent detection guard prevents overlapping runs
    // ========================================================================

    /**
     * Tests that the {@code detectionInProgress} guard prevents overlapping detection runs.
     *
     * <p>Strategy: start the job with a very short timer interval (10ms) so the timer fires
     * far more frequently than a detection run can complete (each run executes real DB queries
     * that take tens of milliseconds). If the guard were absent, we'd see either concurrent
     * mutation errors or a run count approaching the number of timer ticks. With the guard,
     * overlapping invocations are skipped and the run count stays low.</p>
     *
     * <p>This is a pure integration test — zero production code changes required.</p>
     */
    @Test
    void testConcurrentDetectionGuardPreventsOverlap() throws Exception {
        // Create multiple subscriptions so the detection query does real work
        for (int i = 0; i < 10; i++) {
            String topic = "test-guard-" + UUID.randomUUID().toString().substring(0, 8);
            topicConfigService.createTopic(TopicConfig.builder()
                            .topic(topic)
                            .semantics(TopicSemantics.PUB_SUB)
                            .messageRetentionHours(24)
                            .build())
                    .toCompletionStage().toCompletableFuture().get();

            SubscriptionOptions opts = SubscriptionOptions.builder()
                    .heartbeatIntervalSeconds(60)
                    .heartbeatTimeoutSeconds(300)
                    .build();
            subscriptionManager.subscribe(topic, "group-" + i, opts)
                    .toCompletionStage().toCompletableFuture().get();
        }

        // Start job with 5ms interval — timer fires ~400 times in 2 seconds
        // Each real detection run takes 10-20ms (DB query + getSubscriptionSummary)
        // so the guard should skip most timer ticks
        job = new DeadConsumerDetectionJob(manager.getVertx(), detector, cleanup, 5);
        job.start();

        // Let the timer fire many times
        Thread.sleep(2000);
        job.stop();

        long runCount = job.getTotalRunCount();
        long failures = job.getTotalFailures();

        // ~400 timer ticks in 2 seconds at 5ms interval
        long expectedTicks = 400;
        logger.info("Guard test: {} runs completed out of ~{} timer ticks, {} failures",
                runCount, expectedTicks, failures);

        // With guard: runs should be a fraction of the timer ticks
        // because overlapping invocations are skipped while detection is in progress.
        // Each detection run takes real DB time (10-20ms), so with a 5ms timer,
        // at least half the ticks should be skipped. Assert runs < 75% of ticks.
        assertTrue(runCount >= 1, "Should have completed at least 1 run");
        assertTrue(runCount < expectedTicks * 3 / 4,
                "Guard should skip overlapping runs. Expected <" + (expectedTicks * 3 / 4) +
                        " but got " + runCount + " out of ~" + expectedTicks + " timer ticks");
        assertEquals(0, failures,
                "No failures should occur — guard prevents concurrent DB access");

        logger.info("✅ Concurrent detection guard verified: {} runs (not ~100)", runCount);
    }

    // ========================================================================
    // Additional helper methods for pipeline test
    // ========================================================================

    private List<Long> insertMessages(String topic, int count) throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            final int index = i;
            Long id = connectionManager.withConnection("peegeeq-main", connection -> {
                String sql = """
                    INSERT INTO outbox (topic, payload, created_at, status)
                    VALUES ($1, $2::jsonb, $3, 'PENDING')
                    RETURNING id
                    """;
                JsonObject payload = new JsonObject().put("test", true).put("index", index);
                return connection.preparedQuery(sql)
                        .execute(Tuple.of(topic, payload.encode(), OffsetDateTime.now(ZoneOffset.UTC)))
                        .map(rows -> rows.iterator().next().getLong("id"));
            }).toCompletionStage().toCompletableFuture().get();
            ids.add(id);
        }
        return ids;
    }

    private void completeMessage(Long messageId, String groupName) throws Exception {
        connectionManager.withTransaction("peegeeq-main", connection -> {
            String insertSql = """
                INSERT INTO outbox_consumer_groups (message_id, group_name, status, processed_at)
                VALUES ($1, $2, 'COMPLETED', NOW())
                ON CONFLICT (message_id, group_name)
                DO UPDATE SET status = 'COMPLETED', processed_at = NOW()
                """;
            return connection.preparedQuery(insertSql)
                    .execute(Tuple.of(messageId, groupName))
                    .compose(v -> {
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
                                .execute(Tuple.of(messageId))
                                .mapEmpty();
                    });
        }).toCompletionStage().toCompletableFuture().get();
    }

    private Row getMessageRow(Long messageId) throws Exception {
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
        }).toCompletionStage().toCompletableFuture().get();
    }
}
