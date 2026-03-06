package dev.mars.peegeeq.db.cleanup;

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

import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.cleanup.DeadConsumerDetector.BlockedMessageStats;
import dev.mars.peegeeq.db.cleanup.DeadConsumerDetector.DetectionResult;
import dev.mars.peegeeq.db.cleanup.DeadConsumerDetector.SubscriptionSummary;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.db.subscription.SubscriptionManager;
import dev.mars.peegeeq.db.subscription.TopicConfig;
import dev.mars.peegeeq.db.subscription.TopicConfigService;
import dev.mars.peegeeq.db.subscription.TopicSemantics;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive integration tests for {@link DeadConsumerDetector}.
 *
 * <p>Fills coverage gaps identified in the existing test suite:</p>
 * <ul>
 *   <li>Validates ALL dead consumers are picked up (no false negatives)</li>
 *   <li>Tests PAUSED consumers are detected when heartbeat expires</li>
 *   <li>Tests already-DEAD consumers are NOT re-detected</li>
 *   <li>Tests CANCELLED consumers are NOT detected</li>
 *   <li>Tests mixed-state scenarios on a single topic</li>
 *   <li>Tests boundary condition near heartbeat timeout edge</li>
 *   <li>Tests structured {@link DetectionResult} fields</li>
 *   <li>Tests {@link DeadConsumerDetector#getBlockedMessageStats()}</li>
 *   <li>Tests {@link DeadConsumerDetector#getSubscriptionSummary()}</li>
 *   <li>Tests {@link DeadConsumerDetector#countEligibleForDeadDetection(String)} with non-zero results</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-03-01
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
@Execution(ExecutionMode.SAME_THREAD) // Required: testDetectAllWithDetailedResults uses global detection
public class DeadConsumerDetectorComprehensiveTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(DeadConsumerDetectorComprehensiveTest.class);
    private static final String SERVICE_ID = "peegeeq-main";

    private PgConnectionManager connectionManager;
    private DeadConsumerDetector detector;
    private TopicConfigService topicConfigService;
    private SubscriptionManager subscriptionManager;

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

        connectionManager.getOrCreateReactivePool(SERVICE_ID, connectionConfig, poolConfig);

        detector = new DeadConsumerDetector(connectionManager, SERVICE_ID);
        topicConfigService = new TopicConfigService(connectionManager, SERVICE_ID);
        subscriptionManager = new SubscriptionManager(connectionManager, SERVICE_ID);

        logger.info("Comprehensive test setup complete");
    }

    // ========================================================================
    // Test 1: All dead consumers are found — no false negatives
    // ========================================================================

    /**
     * Creates 5 consumers with expired heartbeats and 3 with fresh heartbeats
     * on the same topic. Asserts exactly 5 are detected — no more, no fewer.
     */
    @Test
    void testAllDeadConsumersFoundNoFalseNegatives() throws Exception {
        String topic = uniqueTopic("all-dead-found");
        createTopic(topic);

        // 5 dead consumers (heartbeat expired well past 60s timeout)
        for (int i = 1; i <= 5; i++) {
            subscribe(topic, "dead-" + i, 60);
            setHeartbeatInPast(topic, "dead-" + i, 120);
        }

        // 3 healthy consumers (fresh heartbeat, long timeout)
        for (int i = 1; i <= 3; i++) {
            subscribe(topic, "healthy-" + i, 300);
        }

        // Detect on this specific topic
        Integer markedDead = detector.detectDeadSubscriptions(topic)
                .toCompletionStage().toCompletableFuture().get();

        assertEquals(5, markedDead, "Should detect exactly 5 dead consumers");

        // Verify each dead consumer is DEAD
        for (int i = 1; i <= 5; i++) {
            assertEquals("DEAD", getStatus(topic, "dead-" + i),
                    "dead-" + i + " should be marked DEAD");
        }

        // Verify each healthy consumer is still ACTIVE
        for (int i = 1; i <= 3; i++) {
            assertEquals("ACTIVE", getStatus(topic, "healthy-" + i),
                    "healthy-" + i + " should remain ACTIVE");
        }

        logger.info("✅ All 5 dead consumers found, 3 healthy preserved");
    }

    // ========================================================================
    // Test 2: PAUSED consumer with expired heartbeat is detected
    // ========================================================================

    /**
     * The detection SQL covers {@code subscription_status IN ('ACTIVE', 'PAUSED')}.
     * This test verifies that a PAUSED consumer whose heartbeat has expired
     * is correctly marked as DEAD.
     */
    @Test
    void testPausedConsumerWithExpiredHeartbeatDetected() throws Exception {
        String topic = uniqueTopic("paused-detected");
        createTopic(topic);

        subscribe(topic, "paused-group", 60);
        subscriptionManager.pause(topic, "paused-group")
                .toCompletionStage().toCompletableFuture().get();

        // Verify it's PAUSED before we expire its heartbeat
        assertEquals("PAUSED", getStatus(topic, "paused-group"));

        // Expire heartbeat well past the 60s timeout
        setHeartbeatInPast(topic, "paused-group", 120);

        // Detect
        Integer markedDead = detector.detectDeadSubscriptions(topic)
                .toCompletionStage().toCompletableFuture().get();

        assertEquals(1, markedDead, "PAUSED consumer with expired heartbeat should be detected");
        assertEquals("DEAD", getStatus(topic, "paused-group"),
                "PAUSED consumer should be marked DEAD");

        logger.info("✅ Paused consumer with expired heartbeat detected as DEAD");
    }

    // ========================================================================
    // Test 3: Already-DEAD consumers are NOT re-detected
    // ========================================================================

    /**
     * Running detection twice: the first run marks the consumer as DEAD,
     * the second run should find 0 new dead consumers since it filters
     * {@code WHERE subscription_status IN ('ACTIVE', 'PAUSED')}.
     */
    @Test
    void testAlreadyDeadNotReDetected() throws Exception {
        String topic = uniqueTopic("already-dead");
        createTopic(topic);

        subscribe(topic, "dead-once", 60);
        setHeartbeatInPast(topic, "dead-once", 120);

        // First detection — should mark DEAD
        Integer firstRun = detector.detectDeadSubscriptions(topic)
                .toCompletionStage().toCompletableFuture().get();
        assertEquals(1, firstRun, "First run should detect 1 dead consumer");
        assertEquals("DEAD", getStatus(topic, "dead-once"));

        // Second detection — already DEAD, should NOT be re-detected
        Integer secondRun = detector.detectDeadSubscriptions(topic)
                .toCompletionStage().toCompletableFuture().get();
        assertEquals(0, secondRun,
                "Second run should detect 0 — already DEAD consumers are excluded");
        assertEquals("DEAD", getStatus(topic, "dead-once"),
                "Status should still be DEAD");

        logger.info("✅ Already-DEAD consumer not re-detected on second run");
    }

    // ========================================================================
    // Test 4: CANCELLED consumers are NOT detected
    // ========================================================================

    /**
     * A CANCELLED consumer with an expired heartbeat should NOT be detected
     * because the SQL only checks {@code IN ('ACTIVE', 'PAUSED')}.
     */
    @Test
    void testCancelledConsumerNotDetected() throws Exception {
        String topic = uniqueTopic("cancelled-exempt");
        createTopic(topic);

        subscribe(topic, "cancelled-group", 60);
        subscriptionManager.cancel(topic, "cancelled-group")
                .toCompletionStage().toCompletableFuture().get();

        // Verify it's CANCELLED
        assertEquals("CANCELLED", getStatus(topic, "cancelled-group"));

        // Expire heartbeat
        setHeartbeatInPast(topic, "cancelled-group", 120);

        // Detect — should NOT pick up CANCELLED consumers
        Integer markedDead = detector.detectDeadSubscriptions(topic)
                .toCompletionStage().toCompletableFuture().get();

        assertEquals(0, markedDead, "CANCELLED consumer should NOT be detected");
        assertEquals("CANCELLED", getStatus(topic, "cancelled-group"),
                "Status should remain CANCELLED");

        logger.info("✅ Cancelled consumer correctly excluded from detection");
    }

    // ========================================================================
    // Test 5: Mixed states — only eligible consumers are detected
    // ========================================================================

    /**
     * Creates 5 consumers on one topic with a mix of states:
     * <ol>
     *   <li>ACTIVE + expired heartbeat → should be detected (DEAD)</li>
     *   <li>PAUSED + expired heartbeat → should be detected (DEAD)</li>
     *   <li>ACTIVE + fresh heartbeat → should NOT be detected</li>
     *   <li>Already DEAD (set directly via SQL) → should NOT be re-detected</li>
     *   <li>CANCELLED → should NOT be detected</li>
     * </ol>
     * Exactly 2 of the 5 should be detected.
     */
    @Test
    void testMixedStatesDetectsOnlyEligible() throws Exception {
        String topic = uniqueTopic("mixed-states");
        createTopic(topic);

        // 1. ACTIVE + expired → should be detected
        subscribe(topic, "active-expired", 60);
        setHeartbeatInPast(topic, "active-expired", 120);

        // 2. PAUSED + expired → should be detected
        subscribe(topic, "paused-expired", 60);
        subscriptionManager.pause(topic, "paused-expired")
                .toCompletionStage().toCompletableFuture().get();
        setHeartbeatInPast(topic, "paused-expired", 120);

        // 3. ACTIVE + fresh heartbeat → NOT detected
        subscribe(topic, "active-healthy", 300);

        // 4. Already DEAD (set directly) → NOT re-detected
        subscribe(topic, "already-dead", 60);
        setHeartbeatInPast(topic, "already-dead", 120);
        setStatus(topic, "already-dead", "DEAD");

        // 5. CANCELLED → NOT detected
        subscribe(topic, "cancelled", 60);
        subscriptionManager.cancel(topic, "cancelled")
                .toCompletionStage().toCompletableFuture().get();
        setHeartbeatInPast(topic, "cancelled", 120);

        // Detect
        Integer markedDead = detector.detectDeadSubscriptions(topic)
                .toCompletionStage().toCompletableFuture().get();

        assertEquals(2, markedDead,
                "Should detect exactly 2: active-expired + paused-expired");

        // Verify final states
        assertEquals("DEAD", getStatus(topic, "active-expired"));
        assertEquals("DEAD", getStatus(topic, "paused-expired"));
        assertEquals("ACTIVE", getStatus(topic, "active-healthy"));
        assertEquals("DEAD", getStatus(topic, "already-dead")); // still DEAD, not re-processed
        assertEquals("CANCELLED", getStatus(topic, "cancelled"));

        logger.info("✅ Mixed states: exactly 2 of 5 detected");
    }

    // ========================================================================
    // Test 6: detectAllDeadSubscriptionsWithDetails returns structured result
    // ========================================================================

    /**
     * Validates that {@link DetectionResult} contains correct structured fields:
     * deadCount, totalActiveChecked, totalTopicsAffected, detectionTimeMs,
     * and each {@code DeadSubscriptionInfo} record.
     *
     * <p>Uses topic-scoped detection first to guarantee our consumer is DEAD,
     * then validates the global API structure. Resilient to parallel tests
     * that may also call global detection.</p>
     */
    @Test
    void testDetectAllWithDetailedResults() throws Exception {
        String topic = uniqueTopic("detailed-result");
        createTopic(topic);

        subscribe(topic, "detail-dead", 60);
        subscribe(topic, "detail-alive", 300);
        setHeartbeatInPast(topic, "detail-dead", 120);

        // Use topic-scoped detection first to guarantee our consumer is detected
        // (protects against parallel tests stealing via global detection)
        Integer topicDead = detector.detectDeadSubscriptions(topic)
                .toCompletionStage().toCompletableFuture().get();

        // Verify our consumer is DEAD regardless of who detected it
        assertEquals("DEAD", getStatus(topic, "detail-dead"),
                "detail-dead should be marked DEAD");
        assertEquals("ACTIVE", getStatus(topic, "detail-alive"),
                "detail-alive should remain ACTIVE");

        // Now create a FRESH dead consumer for the global detailed API test
        subscribe(topic, "detail-dead-2", 60);
        setHeartbeatInPast(topic, "detail-dead-2", 120);

        DetectionResult result = detector.detectAllDeadSubscriptionsWithDetails()
                .toCompletionStage().toCompletableFuture().get();

        // Validate structure — our detail-dead-2 should be in the results
        assertNotNull(result);
        assertTrue(result.deadCount() >= 1,
                "Should detect at least 1 dead consumer (detail-dead-2), got " + result.deadCount());
        assertTrue(result.hasDeadSubscriptions(), "hasDeadSubscriptions() should be true");
        assertTrue(result.totalActiveChecked() >= 0,
                "totalActiveChecked should be non-negative");
        assertTrue(result.detectionTimeMs() >= 0,
                "detectionTimeMs should be non-negative");

        // Find our specific dead consumer in the results
        var ourDead = result.deadSubscriptions().stream()
                .filter(d -> d.topic().equals(topic) && d.groupName().equals("detail-dead-2"))
                .findFirst();

        if (ourDead.isPresent()) {
            // Best case: verify all structured fields
            var info = ourDead.get();
            assertEquals(topic, info.topic());
            assertEquals("detail-dead-2", info.groupName());
            assertEquals(60, info.timeoutSeconds());
            assertNotNull(info.lastHeartbeat(), "lastHeartbeat should not be null");
            assertNotNull(info.overdueDuration(), "overdueDuration should not be null");
            assertTrue(info.overdueDuration().getSeconds() >= 50,
                    "Should be at least 50s overdue, got " + info.overdueDuration().getSeconds() + "s");
            assertTrue(info.silenceDuration().getSeconds() >= 100,
                    "Silent for at least 100s, got " + info.silenceDuration().getSeconds() + "s");
            assertTrue(result.affectedTopics().contains(topic),
                    "Our topic should be in affectedTopics");
        } else {
            // Consumer was stolen by a parallel test's global detection — verify it's DEAD
            assertEquals("DEAD", getStatus(topic, "detail-dead-2"),
                    "detail-dead-2 should be DEAD even if detected by another test");
            // Verify at least the general field structure on whatever was returned
            var anyInfo = result.deadSubscriptions().get(0);
            assertNotNull(anyInfo.topic());
            assertNotNull(anyInfo.groupName());
            assertNotNull(anyInfo.lastHeartbeat());
            assertTrue(anyInfo.timeoutSeconds() > 0);
            assertNotNull(anyInfo.overdueDuration());
        }

        logger.info("✅ DetectionResult structured fields validated: {} dead, {}ms",
                result.deadCount(), result.detectionTimeMs());
    }

    // ========================================================================
    // Test 7: getBlockedMessageStats returns blocked message counts
    // ========================================================================

    /**
     * Inserts messages for a topic with 2 consumer groups, marks one as DEAD,
     * then verifies that {@link DeadConsumerDetector#getBlockedMessageStats()}
     * reports the correct blocked message counts.
     */
    @Test
    void testGetBlockedMessageStats() throws Exception {
        String topic = uniqueTopic("blocked-stats");
        createTopic(topic);

        // Subscribe 2 groups (trigger sets required_consumer_groups = 2 on insert)
        subscribe(topic, "stats-alive", 300);
        subscribe(topic, "stats-dead", 60);

        // Insert 3 messages
        List<Long> messageIds = insertMessages(topic, 3);
        assertEquals(3, messageIds.size());

        // Expire stats-dead heartbeat and detect
        setHeartbeatInPast(topic, "stats-dead", 120);
        Integer dead = detector.detectDeadSubscriptions(topic)
                .toCompletionStage().toCompletableFuture().get();
        assertEquals(1, dead);

        // Get blocked message stats
        List<BlockedMessageStats> statsList = detector.getBlockedMessageStats()
                .toCompletionStage().toCompletableFuture().get();

        // Find stats for our dead group
        var ourStats = statsList.stream()
                .filter(s -> s.topic().equals(topic) && s.groupName().equals("stats-dead"))
                .findFirst();
        assertTrue(ourStats.isPresent(),
                "Should have blocked stats for our dead consumer group");

        BlockedMessageStats stats = ourStats.get();
        assertEquals(3, stats.totalBlocked(),
                "Dead group should be blocking 3 messages");
        assertEquals(3, stats.blockedPending(),
                "All blocked messages should be PENDING");
        assertEquals(0, stats.blockedProcessing(),
                "No messages should be PROCESSING");
        assertNotNull(stats.oldestBlockedAge(),
                "Oldest blocked age should not be null");

        logger.info("✅ Blocked message stats validated: {} blocked for group '{}'",
                stats.totalBlocked(), stats.groupName());
    }

    // ========================================================================
    // Test 8: getSubscriptionSummary counts all status categories
    // ========================================================================

    /**
     * Creates subscriptions in each status category (ACTIVE, PAUSED, CANCELLED, DEAD)
     * and validates that {@link DeadConsumerDetector#getSubscriptionSummary()} reflects
     * the correct deltas from the baseline.
     */
    @Test
    void testGetSubscriptionSummary() throws Exception {
        String topic = uniqueTopic("summary");
        createTopic(topic);

        // Capture baseline (shared DB may have subscriptions from other tests)
        SubscriptionSummary before = detector.getSubscriptionSummary()
                .toCompletionStage().toCompletableFuture().get();

        // Create 4 subscriptions in different states
        subscribe(topic, "sum-active", 300);     // stays ACTIVE
        subscribe(topic, "sum-paused", 300);     // will be PAUSED
        subscribe(topic, "sum-cancelled", 300);  // will be CANCELLED
        subscribe(topic, "sum-dead", 60);        // will be DEAD

        subscriptionManager.pause(topic, "sum-paused")
                .toCompletionStage().toCompletableFuture().get();
        subscriptionManager.cancel(topic, "sum-cancelled")
                .toCompletionStage().toCompletableFuture().get();
        setHeartbeatInPast(topic, "sum-dead", 120);
        detector.detectDeadSubscriptions(topic)
                .toCompletionStage().toCompletableFuture().get();

        // Get updated summary
        SubscriptionSummary after = detector.getSubscriptionSummary()
                .toCompletionStage().toCompletableFuture().get();

        // Assert deltas (using >= to handle potential parallel test interference)
        assertTrue(after.activeCount() >= before.activeCount() + 1,
                "Active count should increase by at least 1, was "
                        + before.activeCount() + " → " + after.activeCount());
        assertTrue(after.pausedCount() >= before.pausedCount() + 1,
                "Paused count should increase by at least 1, was "
                        + before.pausedCount() + " → " + after.pausedCount());
        assertTrue(after.cancelledCount() >= before.cancelledCount() + 1,
                "Cancelled count should increase by at least 1, was "
                        + before.cancelledCount() + " → " + after.cancelledCount());
        assertTrue(after.deadCount() >= before.deadCount() + 1,
                "Dead count should increase by at least 1, was "
                        + before.deadCount() + " → " + after.deadCount());
        assertTrue(after.totalCount() >= before.totalCount() + 4,
                "Total count should increase by at least 4, was "
                        + before.totalCount() + " → " + after.totalCount());
        assertTrue(after.hasDeadSubscriptions(), "Should report dead subscriptions present");

        logger.info("✅ Subscription summary validated: active={}, paused={}, dead={}, cancelled={}",
                after.activeCount(), after.pausedCount(), after.deadCount(), after.cancelledCount());
    }

    // ========================================================================
    // Test 9: countEligibleForDeadDetection with actual eligible consumers
    // ========================================================================

    /**
     * Validates {@code countEligibleForDeadDetection()} returns non-zero when
     * consumers have expired heartbeats but haven't been detected yet, then
     * returns zero after detection marks them as DEAD.
     */
    @Test
    void testCountEligibleWithActualEligible() throws Exception {
        String topic = uniqueTopic("count-eligible");
        createTopic(topic);

        subscribe(topic, "eligible-1", 60);
        subscribe(topic, "eligible-2", 60);
        subscribe(topic, "not-eligible", 300); // fresh heartbeat, long timeout

        // Expire 2 heartbeats
        setHeartbeatInPast(topic, "eligible-1", 120);
        setHeartbeatInPast(topic, "eligible-2", 120);

        // Count eligible BEFORE detection
        Long eligibleBefore = detector.countEligibleForDeadDetection(topic)
                .toCompletionStage().toCompletableFuture().get();
        assertEquals(2L, eligibleBefore,
                "Should find 2 eligible consumers before detection");

        // Run detection — marks both as DEAD
        Integer markedDead = detector.detectDeadSubscriptions(topic)
                .toCompletionStage().toCompletableFuture().get();
        assertEquals(2, markedDead);

        // Count eligible AFTER detection — should be 0 now (both are DEAD)
        Long eligibleAfter = detector.countEligibleForDeadDetection(topic)
                .toCompletionStage().toCompletableFuture().get();
        assertEquals(0L, eligibleAfter,
                "Should find 0 eligible after detection (all marked DEAD)");

        logger.info("✅ countEligibleForDeadDetection: before={}, after={}",
                eligibleBefore, eligibleAfter);
    }

    // ========================================================================
    // Test 10: Boundary — heartbeat within timeout not detected
    // ========================================================================

    /**
     * Tests the boundary condition: a consumer whose heartbeat is close to
     * but still within the timeout window should NOT be detected. The detection
     * SQL uses strict less-than ({@code <}), not less-than-or-equal.
     *
     * <p>Scenario:</p>
     * <ul>
     *   <li>Subscribe with 60s timeout</li>
     *   <li>Set heartbeat to 55s ago (within timeout) → NOT detected</li>
     *   <li>Set heartbeat to 65s ago (past timeout) → detected</li>
     * </ul>
     */
    @Test
    void testBoundaryWithinTimeoutNotDetected() throws Exception {
        String topic = uniqueTopic("boundary");
        createTopic(topic);

        // Subscribe with 60s timeout
        subscribe(topic, "boundary-group", 60);

        // Set heartbeat to 55s ago — 5 seconds WITHIN the 60s timeout
        setHeartbeatInPast(topic, "boundary-group", 55);

        // Should NOT be detected (55s < 60s timeout → deadline is 5s in the future)
        Integer detected = detector.detectDeadSubscriptions(topic)
                .toCompletionStage().toCompletableFuture().get();
        assertEquals(0, detected,
                "Consumer within timeout window (55s < 60s) should NOT be detected");
        assertEquals("ACTIVE", getStatus(topic, "boundary-group"),
                "Should remain ACTIVE");

        // Now expire — set heartbeat to 65s ago (5 seconds PAST the 60s timeout)
        setHeartbeatInPast(topic, "boundary-group", 65);

        detected = detector.detectDeadSubscriptions(topic)
                .toCompletionStage().toCompletableFuture().get();
        assertEquals(1, detected,
                "Consumer past timeout (65s > 60s) should be detected");
        assertEquals("DEAD", getStatus(topic, "boundary-group"),
                "Should be marked DEAD");

        logger.info("✅ Boundary: within timeout preserved, past timeout detected");
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    private String uniqueTopic(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private void createTopic(String topic) throws Exception {
        topicConfigService.createTopic(TopicConfig.builder()
                        .topic(topic)
                        .semantics(TopicSemantics.PUB_SUB)
                        .messageRetentionHours(24)
                        .build())
                .toCompletionStage().toCompletableFuture().get();
    }

    private void subscribe(String topic, String groupName, int timeoutSeconds) throws Exception {
        SubscriptionOptions options = SubscriptionOptions.builder()
                .heartbeatIntervalSeconds(Math.max(1, timeoutSeconds / 2))
                .heartbeatTimeoutSeconds(timeoutSeconds)
                .build();
        subscriptionManager.subscribe(topic, groupName, options)
                .toCompletionStage().toCompletableFuture().get();
    }

    private void setHeartbeatInPast(String topic, String groupName, int secondsAgo) throws Exception {
        OffsetDateTime pastTime = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(secondsAgo);
        connectionManager.withConnection(SERVICE_ID, connection -> {
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

    private String getStatus(String topic, String groupName) throws Exception {
        return connectionManager.withConnection(SERVICE_ID, connection -> {
            String sql = "SELECT subscription_status FROM outbox_topic_subscriptions WHERE topic = $1 AND group_name = $2";
            return connection.preparedQuery(sql)
                    .execute(Tuple.of(topic, groupName))
                    .map(rows -> {
                        if (rows.size() == 0) {
                            throw new RuntimeException("Subscription not found: " + topic + "/" + groupName);
                        }
                        return rows.iterator().next().getString("subscription_status");
                    });
        }).toCompletionStage().toCompletableFuture().get();
    }

    private void setStatus(String topic, String groupName, String status) throws Exception {
        connectionManager.withConnection(SERVICE_ID, connection -> {
            String sql = """
                UPDATE outbox_topic_subscriptions
                SET subscription_status = $1
                WHERE topic = $2 AND group_name = $3
                """;
            return connection.preparedQuery(sql)
                    .execute(Tuple.of(status, topic, groupName))
                    .mapEmpty();
        }).toCompletionStage().toCompletableFuture().get();
    }

    private List<Long> insertMessages(String topic, int count) throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            final int index = i;
            Long id = connectionManager.withConnection(SERVICE_ID, connection -> {
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
}
