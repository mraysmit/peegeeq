package dev.mars.peegeeq.db.fanout;

import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.cleanup.DeadConsumerGroupCleanup;
import dev.mars.peegeeq.db.cleanup.DeadConsumerGroupCleanup.CleanupResult;
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
import io.vertx.sqlclient.RowSet;
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
 * Integration tests for {@link DeadConsumerGroupCleanup}.
 *
 * <p>Tests validate that when a consumer group is marked DEAD, the cleanup
 * correctly decrements {@code required_consumer_groups}, removes orphaned
 * tracking rows, and auto-completes messages where all remaining consumers
 * have finished processing.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-03-01
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
@Execution(ExecutionMode.SAME_THREAD)
public class DeadConsumerGroupCleanupIntegrationTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(DeadConsumerGroupCleanupIntegrationTest.class);
    private static final String SERVICE_ID = "peegeeq-main";

    private PgConnectionManager connectionManager;
    private TopicConfigService topicConfigService;
    private SubscriptionManager subscriptionManager;
    private DeadConsumerGroupCleanup cleanup;

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

        topicConfigService = new TopicConfigService(connectionManager, SERVICE_ID);
        subscriptionManager = new SubscriptionManager(connectionManager, SERVICE_ID);
        cleanup = new DeadConsumerGroupCleanup(connectionManager, SERVICE_ID);

        logger.info("Test setup complete");
    }

    /**
     * Test basic cleanup: 2 consumer groups, one dies, its blocked messages get decremented.
     *
     * Scenario:
     * - Topic with 2 subscribers (group-a, group-b)
     * - 3 messages inserted (required_consumer_groups = 2 via trigger)
     * - group-a completes all 3 messages (completed_consumer_groups = 1)
     * - group-b is marked DEAD
     * - Cleanup decrements required_consumer_groups to 1
     * - Since completed (1) >= required (1), all 3 messages auto-complete
     */
    @Test
    void testCleanupDecrementsAndAutoCompletes() throws Exception {
        String topic = uniqueTopic("cleanup-basic");

        // Create topic + subscribe 2 groups
        createPubSubTopic(topic);
        subscribe(topic, "group-a");
        subscribe(topic, "group-b");

        // Insert 3 messages (trigger sets required_consumer_groups = 2)
        List<Long> messageIds = insertMessages(topic, 3);

        // group-a completes all 3 messages
        for (Long msgId : messageIds) {
            completeMessage(msgId, "group-a");
        }

        // Verify: required=2, completed=1 on each message
        for (Long msgId : messageIds) {
            assertMessageState(msgId, "PENDING", 2, 1);
        }

        // Mark group-b as DEAD
        markSubscriptionDead(topic, "group-b");

        // Run cleanup
        CleanupResult result = cleanup.cleanupDeadGroup(topic, "group-b")
                .toCompletionStage().toCompletableFuture().get();

        // Verify cleanup counts
        assertEquals(3, result.messagesDecremented(), "Should decrement 3 messages");
        assertTrue(result.messagesAutoCompleted() >= 3,
                "Should auto-complete 3 messages (completed 1 >= required 1)");
        assertTrue(result.hadWork(), "Should have had work");
        assertEquals(topic, result.topic());
        assertEquals("group-b", result.groupName());

        // Verify final state: all messages completed
        for (Long msgId : messageIds) {
            assertMessageState(msgId, "COMPLETED", 1, 1);
        }

        logger.info("✅ Basic cleanup with auto-complete verified");
    }

    /**
     * Test that cleanup is idempotent — running it twice doesn't double-decrement.
     */
    @Test
    void testCleanupIsIdempotent() throws Exception {
        String topic = uniqueTopic("cleanup-idempotent");

        createPubSubTopic(topic);
        subscribe(topic, "group-a");
        subscribe(topic, "group-b");

        List<Long> messageIds = insertMessages(topic, 2);

        // group-a completes both
        for (Long msgId : messageIds) {
            completeMessage(msgId, "group-a");
        }

        markSubscriptionDead(topic, "group-b");

        // Run cleanup twice
        CleanupResult first = cleanup.cleanupDeadGroup(topic, "group-b")
                .toCompletionStage().toCompletableFuture().get();
        CleanupResult second = cleanup.cleanupDeadGroup(topic, "group-b")
                .toCompletionStage().toCompletableFuture().get();

        // First run should have work
        assertTrue(first.hadWork(), "First run should have work");
        assertEquals(2, first.messagesDecremented());

        // Second run should be a no-op (messages already COMPLETED, nothing to decrement)
        assertEquals(0, second.messagesDecremented(), "Second run should have nothing to decrement");
        assertEquals(0, second.messagesAutoCompleted(), "Second run should have nothing to auto-complete");

        logger.info("✅ Idempotency verified");
    }

    /**
     * Test that already-completed groups are not affected by cleanup.
     *
     * Scenario: 3 groups. Group-c dies. Group-a and group-b have completed.
     * Required goes from 3 to 2, and since completed=2 >= required=2, messages auto-complete.
     */
    @Test
    void testDoesNotAffectCompletedGroups() throws Exception {
        String topic = uniqueTopic("cleanup-completed");

        createPubSubTopic(topic);
        subscribe(topic, "group-a");
        subscribe(topic, "group-b");
        subscribe(topic, "group-c");

        List<Long> messageIds = insertMessages(topic, 2);

        // group-a and group-b complete all messages
        for (Long msgId : messageIds) {
            completeMessage(msgId, "group-a");
            completeMessage(msgId, "group-b");
        }

        // Verify: required=3, completed=2
        for (Long msgId : messageIds) {
            assertMessageState(msgId, "PENDING", 3, 2);
        }

        markSubscriptionDead(topic, "group-c");

        CleanupResult result = cleanup.cleanupDeadGroup(topic, "group-c")
                .toCompletionStage().toCompletableFuture().get();

        assertEquals(2, result.messagesDecremented());
        assertTrue(result.messagesAutoCompleted() >= 2,
                "completed(2) >= required(2) so should auto-complete");

        // Verify final state: completed, required now 2
        for (Long msgId : messageIds) {
            assertMessageState(msgId, "COMPLETED", 2, 2);
        }

        // Verify group-a and group-b tracking rows still exist with COMPLETED status
        for (Long msgId : messageIds) {
            String statusA = getConsumerGroupStatus(msgId, "group-a");
            assertEquals("COMPLETED", statusA, "group-a tracking row should be preserved");
            String statusB = getConsumerGroupStatus(msgId, "group-b");
            assertEquals("COMPLETED", statusB, "group-b tracking row should be preserved");
        }

        logger.info("✅ Completed group preservation verified");
    }

    /**
     * Test cleanup when the dead group had partially processed messages.
     *
     * Scenario: group-b completed 1 of 3 messages before dying.
     * - Message 1: group-b completed (shouldn't decrement, NOT EXISTS fails)
     * - Messages 2,3: group-b has no tracking row (should decrement)
     */
    @Test
    void testPartiallyProcessedDeadGroup() throws Exception {
        String topic = uniqueTopic("cleanup-partial");

        createPubSubTopic(topic);
        subscribe(topic, "group-a");
        subscribe(topic, "group-b");

        List<Long> messageIds = insertMessages(topic, 3);

        // group-a completes all 3
        for (Long msgId : messageIds) {
            completeMessage(msgId, "group-a");
        }

        // group-b completed only message 0 before dying
        completeMessage(messageIds.get(0), "group-b");

        // Message 0: required=2, completed=2, should already be COMPLETED
        assertMessageState(messageIds.get(0), "COMPLETED", 2, 2);
        // Messages 1,2: required=2, completed=1, still PENDING
        assertMessageState(messageIds.get(1), "PENDING", 2, 1);
        assertMessageState(messageIds.get(2), "PENDING", 2, 1);

        markSubscriptionDead(topic, "group-b");

        CleanupResult result = cleanup.cleanupDeadGroup(topic, "group-b")
                .toCompletionStage().toCompletableFuture().get();

        // Only messages 1 and 2 should be decremented (message 0 already completed by group-b)
        assertEquals(2, result.messagesDecremented(),
                "Should only decrement the 2 messages group-b hadn't completed");
        assertTrue(result.messagesAutoCompleted() >= 2,
                "Messages 1,2 should auto-complete (completed 1 >= required 1)");

        // Message 0 was already COMPLETED — shouldn't be touched
        assertMessageState(messageIds.get(0), "COMPLETED", 2, 2);

        logger.info("✅ Partially processed dead group verified");
    }

    /**
     * Test cleanup when no messages exist for the dead group (no-op).
     */
    @Test
    void testCleanupWithNoMessages() throws Exception {
        String topic = uniqueTopic("cleanup-empty");

        createPubSubTopic(topic);
        subscribe(topic, "group-a");

        markSubscriptionDead(topic, "group-a");

        CleanupResult result = cleanup.cleanupDeadGroup(topic, "group-a")
                .toCompletionStage().toCompletableFuture().get();

        assertEquals(0, result.messagesDecremented());
        assertEquals(0, result.orphanRowsRemoved());
        assertEquals(0, result.messagesAutoCompleted());
        assertFalse(result.hadWork());

        logger.info("✅ Empty cleanup verified");
    }

    /**
     * Test orphaned tracking row removal.
     *
     * Scenario: group-b has PENDING tracking rows (e.g., created by backfill)
     * but then dies. Cleanup should remove those orphaned rows.
     */
    @Test
    void testRemovesOrphanedTrackingRows() throws Exception {
        String topic = uniqueTopic("cleanup-orphans");

        createPubSubTopic(topic);
        subscribe(topic, "group-a");
        subscribe(topic, "group-b");

        List<Long> messageIds = insertMessages(topic, 2);

        // Create PENDING tracking rows for group-b (simulating backfill or partial processing)
        for (Long msgId : messageIds) {
            insertConsumerGroupRow(msgId, "group-b", "PENDING");
        }

        markSubscriptionDead(topic, "group-b");

        CleanupResult result = cleanup.cleanupDeadGroup(topic, "group-b")
                .toCompletionStage().toCompletableFuture().get();

        assertEquals(2, result.messagesDecremented());
        assertEquals(2, result.orphanRowsRemoved(), "Should remove 2 orphaned PENDING tracking rows");

        // Verify tracking rows are gone
        for (Long msgId : messageIds) {
            assertFalse(consumerGroupRowExists(msgId, "group-b"),
                    "Orphaned tracking row should be removed for message " + msgId);
        }

        logger.info("✅ Orphaned tracking row removal verified");
    }

    /**
     * Test cleanupAllDeadGroups discovers and cleans multiple dead groups.
     */
    @Test
    void testCleanupAllDeadGroups() throws Exception {
        String topic = uniqueTopic("cleanup-all");

        createPubSubTopic(topic);
        subscribe(topic, "group-a");
        subscribe(topic, "group-b");
        subscribe(topic, "group-c");

        List<Long> messageIds = insertMessages(topic, 2);

        // group-a completes everything
        for (Long msgId : messageIds) {
            completeMessage(msgId, "group-a");
        }

        // Mark both group-b and group-c as DEAD
        markSubscriptionDead(topic, "group-b");
        markSubscriptionDead(topic, "group-c");

        // Run cleanup for all dead groups
        List<CleanupResult> results = cleanup.cleanupAllDeadGroups()
                .toCompletionStage().toCompletableFuture().get();

        // Filter to our topic's results for specific assertions
        // (other parallel test classes may contribute additional dead groups)
        List<CleanupResult> ourResults = results.stream()
                .filter(r -> r.topic().equals(topic))
                .toList();

        assertTrue(results.size() >= 2, "Should have results for at least 2 dead groups, got " + results.size());
        assertEquals(2, ourResults.size(), "Should have results for our 2 dead groups on topic " + topic);

        int totalDecremented = ourResults.stream().mapToInt(CleanupResult::messagesDecremented).sum();
        int totalAutoCompleted = ourResults.stream().mapToInt(CleanupResult::messagesAutoCompleted).sum();

        // Each dead group decrements 2 messages: first group decrement (3→2), second (2→1)
        assertEquals(4, totalDecremented, "Total decrements: 2 messages × 2 dead groups");
        // After both groups cleaned up: required=1, completed=1, all auto-complete
        assertTrue(totalAutoCompleted >= 2, "Both messages should eventually auto-complete");

        // Final state: all messages completed
        for (Long msgId : messageIds) {
            Row state = getMessageRow(msgId);
            assertEquals("COMPLETED", state.getString("status"));
            assertEquals(1, state.getInteger("required_consumer_groups"),
                    "required should be decremented from 3 to 1");
        }

        logger.info("✅ cleanupAllDeadGroups verified");
    }

    /**
     * Test that cleanup doesn't decrement below zero.
     */
    @Test
    void testDoesNotDecrementBelowZero() throws Exception {
        String topic = uniqueTopic("cleanup-zero-guard");

        createPubSubTopic(topic);
        subscribe(topic, "group-a");

        List<Long> messageIds = insertMessages(topic, 1);

        // Force required_consumer_groups to 0 (edge case)
        setRequiredConsumerGroups(messageIds.get(0), 0);

        markSubscriptionDead(topic, "group-a");

        CleanupResult result = cleanup.cleanupDeadGroup(topic, "group-a")
                .toCompletionStage().toCompletableFuture().get();

        assertEquals(0, result.messagesDecremented(),
                "Should not decrement when required_consumer_groups is already 0");

        // Verify it didn't go negative
        Row state = getMessageRow(messageIds.get(0));
        assertTrue(state.getInteger("required_consumer_groups") >= 0,
                "required_consumer_groups should never be negative");

        logger.info("✅ Zero-guard verified");
    }

        /**
         * Explicit decrement logic proof:
         * when required_consumer_groups is decremented from 1 to 0, message should
         * be auto-completed instead of being left PENDING/PROCESSING.
         */
        @Test
        void testDecrementToZeroAutoCompletesMessage() throws Exception {
        String topic = uniqueTopic("cleanup-to-zero");

        createPubSubTopic(topic);
        subscribe(topic, "group-dead");

        List<Long> messageIds = insertMessages(topic, 2);
        markSubscriptionDead(topic, "group-dead");

        CleanupResult result = cleanup.cleanupDeadGroup(topic, "group-dead")
            .toCompletionStage().toCompletableFuture().get();

        assertEquals(2, result.messagesDecremented(),
            "Both messages should be decremented from required=1 to required=0");
        assertTrue(result.messagesAutoCompleted() >= 2,
            "Messages should be auto-completed when completed(0) >= required(0)");

        for (Long msgId : messageIds) {
            Row state = getMessageRow(msgId);
            assertEquals("COMPLETED", state.getString("status"),
                "Message should be auto-completed after required reaches 0");
            assertEquals(0, state.getInteger("required_consumer_groups"),
                "required_consumer_groups should be decremented to 0");
            assertEquals(0, state.getInteger("completed_consumer_groups"),
                "completed_consumer_groups remains 0 (no consumer completion rows)");
        }

        logger.info("✅ Decrement-to-zero auto-complete verified");
        }

        /**
         * Explicit decrement logic proof:
         * cleanup must NOT decrement historical messages that were created before the
         * dead group subscribed (the dead group was never part of required quorum).
         */
        @Test
        void testDoesNotDecrementMessagesCreatedBeforeDeadGroupSubscribed() throws Exception {
        String topic = uniqueTopic("cleanup-late-subscriber");

        createPubSubTopic(topic);
        subscribe(topic, "group-a");

        // Insert messages while only group-a is subscribed, so required=1.
        List<Long> messageIds = insertMessages(topic, 2);

        // Ensure group-b subscription timestamp is after message creation.
        Thread.sleep(20);
        subscribe(topic, "group-b");

        markSubscriptionDead(topic, "group-b");

        CleanupResult result = cleanup.cleanupDeadGroup(topic, "group-b")
            .toCompletionStage().toCompletableFuture().get();

        assertEquals(0, result.messagesDecremented(),
            "Late-subscribed dead group should not decrement older messages");
        assertEquals(0, result.messagesAutoCompleted(),
            "No auto-completion expected because required should remain unchanged");

        for (Long msgId : messageIds) {
            Row state = getMessageRow(msgId);
            assertEquals("PENDING", state.getString("status"),
                "Message should remain pending (no completion yet)");
            assertEquals(1, state.getInteger("required_consumer_groups"),
                "required_consumer_groups should stay 1; group-b was never required");
            assertEquals(0, state.getInteger("completed_consumer_groups"));
        }

        logger.info("✅ Late-subscriber no-decrement behavior verified");
        }

    // ========================================================================
    // Helper methods
    // ========================================================================

    private String uniqueTopic(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private void createPubSubTopic(String topic) throws Exception {
        topicConfigService.createTopic(TopicConfig.builder()
                        .topic(topic)
                        .semantics(TopicSemantics.PUB_SUB)
                        .messageRetentionHours(24)
                        .build())
                .toCompletionStage().toCompletableFuture().get();
    }

    private void subscribe(String topic, String groupName) throws Exception {
        SubscriptionOptions options = SubscriptionOptions.builder()
                .heartbeatIntervalSeconds(60)
                .heartbeatTimeoutSeconds(300)
                .build();
        subscriptionManager.subscribe(topic, groupName, options)
                .toCompletionStage().toCompletableFuture().get();
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

    /**
     * Simulates group-a completing a message: creates COMPLETED tracking row
     * and increments completed_consumer_groups.
     */
    private void completeMessage(Long messageId, String groupName) throws Exception {
        connectionManager.withTransaction(SERVICE_ID, connection -> {
            // Insert or update tracking row
            String insertSql = """
                INSERT INTO outbox_consumer_groups (message_id, group_name, status, processed_at)
                VALUES ($1, $2, 'COMPLETED', NOW())
                ON CONFLICT (message_id, group_name)
                DO UPDATE SET status = 'COMPLETED', processed_at = NOW()
                """;
            return connection.preparedQuery(insertSql)
                    .execute(Tuple.of(messageId, groupName))
                    .compose(v -> {
                        // Increment completed count and auto-complete if needed
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

    private void markSubscriptionDead(String topic, String groupName) throws Exception {
        connectionManager.withConnection(SERVICE_ID, connection -> {
            String sql = """
                UPDATE outbox_topic_subscriptions
                SET subscription_status = 'DEAD'
                WHERE topic = $1 AND group_name = $2
                """;
            return connection.preparedQuery(sql)
                    .execute(Tuple.of(topic, groupName))
                    .mapEmpty();
        }).toCompletionStage().toCompletableFuture().get();
    }

    private void insertConsumerGroupRow(Long messageId, String groupName, String status) throws Exception {
        connectionManager.withConnection(SERVICE_ID, connection -> {
            String sql = """
                INSERT INTO outbox_consumer_groups (message_id, group_name, status)
                VALUES ($1, $2, $3)
                ON CONFLICT (message_id, group_name) DO NOTHING
                """;
            return connection.preparedQuery(sql)
                    .execute(Tuple.of(messageId, groupName, status))
                    .mapEmpty();
        }).toCompletionStage().toCompletableFuture().get();
    }

    private void setRequiredConsumerGroups(Long messageId, int required) throws Exception {
        connectionManager.withConnection(SERVICE_ID, connection -> {
            String sql = "UPDATE outbox SET required_consumer_groups = $1 WHERE id = $2";
            return connection.preparedQuery(sql)
                    .execute(Tuple.of(required, messageId))
                    .mapEmpty();
        }).toCompletionStage().toCompletableFuture().get();
    }

    private void assertMessageState(Long messageId, String expectedStatus,
                                     int expectedRequired, int expectedCompleted) throws Exception {
        Row row = getMessageRow(messageId);
        assertEquals(expectedStatus, row.getString("status"),
                "Message " + messageId + " status mismatch");
        assertEquals(expectedRequired, row.getInteger("required_consumer_groups"),
                "Message " + messageId + " required_consumer_groups mismatch");
        assertEquals(expectedCompleted, row.getInteger("completed_consumer_groups"),
                "Message " + messageId + " completed_consumer_groups mismatch");
    }

    private Row getMessageRow(Long messageId) throws Exception {
        return connectionManager.withConnection(SERVICE_ID, connection -> {
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

    private String getConsumerGroupStatus(Long messageId, String groupName) throws Exception {
        return connectionManager.withConnection(SERVICE_ID, connection -> {
            String sql = """
                SELECT status FROM outbox_consumer_groups
                WHERE message_id = $1 AND group_name = $2
                """;
            return connection.preparedQuery(sql)
                    .execute(Tuple.of(messageId, groupName))
                    .map(rows -> {
                        if (rows.size() == 0) {
                            return null;
                        }
                        return rows.iterator().next().getString("status");
                    });
        }).toCompletionStage().toCompletableFuture().get();
    }

    private boolean consumerGroupRowExists(Long messageId, String groupName) throws Exception {
        return connectionManager.withConnection(SERVICE_ID, connection -> {
            String sql = """
                SELECT COUNT(*) as cnt FROM outbox_consumer_groups
                WHERE message_id = $1 AND group_name = $2
                """;
            return connection.preparedQuery(sql)
                    .execute(Tuple.of(messageId, groupName))
                    .map(rows -> rows.iterator().next().getInteger("cnt") > 0);
        }).toCompletionStage().toCompletableFuture().get();
    }

    // ========================================================================
    // Test: Cleanup error resilience — one group failure doesn't break others
    // ========================================================================

    /**
     * Tests that {@code cleanupAllDeadGroups()} continues processing remaining
     * dead groups when cleanup for one group fails.
     *
     * <p>Uses a test subclass that overrides {@code cleanupDeadGroup()} to
     * inject a failure for a specific topic. This follows the project's
     * established subclass-override pattern (e.g. FakeDeadLetterQueueManager).
     * {@code cleanupDeadGroup()} is already public, so no production code
     * changes are required.</p>
     *
     * <p>Validates the {@code .recover()} block in {@code cleanupAllDeadGroups()}
     * which catches per-group failures, logs them, and adds a zero-result
     * {@code CleanupResult} so the batch can continue.</p>
     */
    @Test
    void testCleanupContinuesAfterOneGroupFails() throws Exception {
        String topicOk = uniqueTopic("resilience-ok");
        String topicFail = uniqueTopic("resilience-fail");

        // Set up topic that will be cleaned successfully
        createPubSubTopic(topicOk);
        subscribe(topicOk, "group-alive");
        subscribe(topicOk, "group-dead-ok");
        List<Long> okMessageIds = insertMessages(topicOk, 2);
        for (Long msgId : okMessageIds) {
            completeMessage(msgId, "group-alive");
        }
        markSubscriptionDead(topicOk, "group-dead-ok");

        // Set up topic whose cleanup will fail
        createPubSubTopic(topicFail);
        subscribe(topicFail, "group-alive");
        subscribe(topicFail, "group-dead-fail");
        List<Long> failMessageIds = insertMessages(topicFail, 2);
        for (Long msgId : failMessageIds) {
            completeMessage(msgId, "group-alive");
        }
        markSubscriptionDead(topicFail, "group-dead-fail");

        // Create a test subclass that injects a failure for topicFail
        DeadConsumerGroupCleanup failingCleanup = new DeadConsumerGroupCleanup(
                connectionManager, SERVICE_ID) {
            @Override
            public Future<CleanupResult> cleanupDeadGroup(String topic, String groupName) {
                if (topic.equals(topicFail)) {
                    return Future.failedFuture(
                            new RuntimeException("Simulated DB failure for topic=" + topic));
                }
                return super.cleanupDeadGroup(topic, groupName);
            }
        };

        // Run cleanup — should NOT throw, should process both groups
        List<CleanupResult> results = failingCleanup.cleanupAllDeadGroups()
                .toCompletionStage().toCompletableFuture().get();

        // Filter to our results (other parallel tests may contribute dead groups)
        var okResult = results.stream()
                .filter(r -> r.topic().equals(topicOk) && r.groupName().equals("group-dead-ok"))
                .findFirst();
        var failResult = results.stream()
                .filter(r -> r.topic().equals(topicFail) && r.groupName().equals("group-dead-fail"))
                .findFirst();

        // Verify: the good cleanup succeeded
        assertTrue(okResult.isPresent(), "Should have result for successfully cleaned group");
        assertTrue(okResult.get().hadWork(), "Good group should have been cleaned");
        assertEquals(2, okResult.get().messagesDecremented(),
                "Good group should have decremented 2 messages");

        // Verify: the failed cleanup produced a zero-result from .recover()
        assertTrue(failResult.isPresent(),
                "Failed group should still have a result (from .recover() block)");
        assertFalse(failResult.get().hadWork(),
                ".recover() should produce a zero-work CleanupResult");
        assertEquals(0, failResult.get().messagesDecremented(),
                "Failed group should have 0 decremented");
        assertEquals(0, failResult.get().orphanRowsRemoved(),
                "Failed group should have 0 orphans removed");
        assertEquals(0, failResult.get().messagesAutoCompleted(),
                "Failed group should have 0 auto-completed");

        // Verify: messages on the good topic were actually cleaned up
        for (Long msgId : okMessageIds) {
            Row state = getMessageRow(msgId);
            assertEquals("COMPLETED", state.getString("status"),
                    "Good topic messages should be auto-completed");
            assertEquals(1, state.getInteger("required_consumer_groups"),
                    "required_consumer_groups should be decremented from 2 to 1");
        }

        // Verify: messages on the failed topic were NOT cleaned up
        for (Long msgId : failMessageIds) {
            Row state = getMessageRow(msgId);
            assertEquals("PENDING", state.getString("status"),
                    "Failed topic messages should still be PENDING");
            assertEquals(2, state.getInteger("required_consumer_groups"),
                    "Failed topic required_consumer_groups should still be 2");
        }

        logger.info("✅ Cleanup error resilience verified: good group cleaned, failed group produced zero-result");
    }
}
