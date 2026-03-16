package dev.mars.peegeeq.db.fanout;

import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.api.messaging.BackfillScope;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.db.subscription.BackfillService;
import dev.mars.peegeeq.db.subscription.BackfillService.BackfillProgress;
import dev.mars.peegeeq.db.subscription.BackfillService.BackfillResult;
import dev.mars.peegeeq.db.subscription.SubscriptionManager;
import dev.mars.peegeeq.db.subscription.TopicConfig;
import dev.mars.peegeeq.db.subscription.TopicConfigService;
import dev.mars.peegeeq.db.subscription.TopicSemantics;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link BackfillService}.
 *
 * <p>Tests validate the complete backfill lifecycle including:
 * starting, batched processing, checkpoint-based resumability,
 * cancellation, and idempotent completion.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-12-01
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
public class BackfillServiceIntegrationTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(BackfillServiceIntegrationTest.class);

    private PgConnectionManager connectionManager;
    private TopicConfigService topicConfigService;
    private SubscriptionManager subscriptionManager;
    private BackfillService backfillService;

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
                .maxSize(10)
                .build();

        connectionManager.getOrCreateReactivePool("peegeeq-main", connectionConfig, poolConfig);

        topicConfigService = new TopicConfigService(connectionManager, "peegeeq-main");
        subscriptionManager = new SubscriptionManager(connectionManager, "peegeeq-main");
        backfillService = new BackfillService(connectionManager, "peegeeq-main");

        logger.info("Test setup complete");
    }

    /**
     * Test backfill of a small number of messages end-to-end.
     */
    @Test
    void testBackfillSmallBatch() throws Exception {
        String topic = "test-backfill-small-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "backfill-group-1";

        // Create PUB_SUB topic with initial subscription
        topicConfigService.createTopic(TopicConfig.builder()
                        .topic(topic)
                        .semantics(TopicSemantics.PUB_SUB)
                        .messageRetentionHours(24)
                        .build())
                .toCompletionStage().toCompletableFuture().get();

        // Subscribe initial group so messages get required_consumer_groups = 1
        subscriptionManager.subscribe(topic, "initial-group", SubscriptionOptions.defaults())
                .toCompletionStage().toCompletableFuture().get();

        // Insert some messages
        int messageCount = 5;
        for (int i = 0; i < messageCount; i++) {
            insertMessage(topic, new JsonObject().put("index", i))
                    .toCompletionStage().toCompletableFuture().get();
        }

        // Late-joining group subscribes from beginning
        subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.fromBeginning())
                .toCompletionStage().toCompletableFuture().get();

        // Run backfill
        BackfillResult result = backfillService.startBackfill(topic, groupName, 100, 0)
                .toCompletionStage().toCompletableFuture().get();

        assertEquals(BackfillResult.Status.COMPLETED, result.status(),
                "Backfill should complete successfully");
        assertEquals(messageCount, result.processedMessages(),
                "Should process all " + messageCount + " messages");

        // Verify backfill progress shows completed
        BackfillProgress progress = backfillService.getBackfillProgress(topic, groupName)
                .toCompletionStage().toCompletableFuture().get().orElseThrow();
        assertEquals("COMPLETED", progress.status());
        assertEquals(messageCount, progress.processedMessages());
        assertNotNull(progress.completedAt());

        // Verify required_consumer_groups was incremented on PENDING messages
        Long incrementedCount = countMessagesWithRequiredGroups(topic, 2)
                .toCompletionStage().toCompletableFuture().get();
        assertTrue(incrementedCount > 0,
                "At least some messages should have required_consumer_groups incremented to 2");

        logger.info("Small batch backfill verified: {} messages processed", result.processedMessages());
    }

    /**
     * Test backfill with multiple batches (batch size < total messages).
     */
    @Test
    void testBackfillMultipleBatches() throws Exception {
        String topic = "test-backfill-multi-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "backfill-batch-group";

        topicConfigService.createTopic(TopicConfig.builder()
                        .topic(topic)
                        .semantics(TopicSemantics.PUB_SUB)
                        .messageRetentionHours(24)
                        .build())
                .toCompletionStage().toCompletableFuture().get();

        subscriptionManager.subscribe(topic, "initial-group", SubscriptionOptions.defaults())
                .toCompletionStage().toCompletableFuture().get();

        // Insert 25 messages
        int messageCount = 25;
        for (int i = 0; i < messageCount; i++) {
            insertMessage(topic, new JsonObject().put("index", i))
                    .toCompletionStage().toCompletableFuture().get();
        }

        subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.fromBeginning())
                .toCompletionStage().toCompletableFuture().get();

        // Run backfill with small batch size (10) to force multiple batches
        BackfillResult result = backfillService.startBackfill(topic, groupName, 10, 0)
                .toCompletionStage().toCompletableFuture().get();

        assertEquals(BackfillResult.Status.COMPLETED, result.status());
        assertEquals(messageCount, result.processedMessages(),
                "Should process all messages across multiple batches");

        logger.info("Multi-batch backfill verified: {} messages in batches of 10", result.processedMessages());
    }

    /**
     * Test backfill with max messages limit.
     */
    @Test
    void testBackfillWithMaxLimit() throws Exception {
        String topic = "test-backfill-max-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "backfill-max-group";

        topicConfigService.createTopic(TopicConfig.builder()
                        .topic(topic)
                        .semantics(TopicSemantics.PUB_SUB)
                        .messageRetentionHours(24)
                        .build())
                .toCompletionStage().toCompletableFuture().get();

        subscriptionManager.subscribe(topic, "initial-group", SubscriptionOptions.defaults())
                .toCompletionStage().toCompletableFuture().get();

        // Insert 20 messages
        for (int i = 0; i < 20; i++) {
            insertMessage(topic, new JsonObject().put("index", i))
                    .toCompletionStage().toCompletableFuture().get();
        }

        subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.fromBeginning())
                .toCompletionStage().toCompletableFuture().get();

        // Run backfill with max limit of 10
        BackfillResult result = backfillService.startBackfill(topic, groupName, 5, 10)
                .toCompletionStage().toCompletableFuture().get();

        assertEquals(BackfillResult.Status.COMPLETED, result.status());
        assertEquals(10, result.processedMessages(),
                "Should stop after processing maxMessages (10)");

        logger.info("Max-limited backfill verified: processed {} of 20", result.processedMessages());
    }

    /**
     * Test that backfill of an already-completed subscription returns immediately.
     */
    @Test
    void testBackfillAlreadyCompleted() throws Exception {
        String topic = "test-backfill-done-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "backfill-done-group";

        topicConfigService.createTopic(TopicConfig.builder()
                        .topic(topic)
                        .semantics(TopicSemantics.PUB_SUB)
                        .messageRetentionHours(24)
                        .build())
                .toCompletionStage().toCompletableFuture().get();

        subscriptionManager.subscribe(topic, "initial-group", SubscriptionOptions.defaults())
                .toCompletionStage().toCompletableFuture().get();

        insertMessage(topic, new JsonObject().put("test", 1))
                .toCompletionStage().toCompletableFuture().get();

        subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.fromBeginning())
                .toCompletionStage().toCompletableFuture().get();

        // Run backfill first time
        backfillService.startBackfill(topic, groupName)
                .toCompletionStage().toCompletableFuture().get();

        // Run backfill second time - should return ALREADY_COMPLETED
        BackfillResult result = backfillService.startBackfill(topic, groupName)
                .toCompletionStage().toCompletableFuture().get();

        assertEquals(BackfillResult.Status.ALREADY_COMPLETED, result.status(),
                "Second backfill should return ALREADY_COMPLETED");

        logger.info("Idempotent backfill verified");
    }

    /**
     * Test backfill with no messages to process.
     */
    @Test
    void testBackfillNoMessages() throws Exception {
        String topic = "test-backfill-empty-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "backfill-empty-group";

        topicConfigService.createTopic(TopicConfig.builder()
                        .topic(topic)
                        .semantics(TopicSemantics.PUB_SUB)
                        .messageRetentionHours(24)
                        .build())
                .toCompletionStage().toCompletableFuture().get();

        // Subscribe from beginning but no messages exist
        subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.fromBeginning())
                .toCompletionStage().toCompletableFuture().get();

        BackfillResult result = backfillService.startBackfill(topic, groupName)
                .toCompletionStage().toCompletableFuture().get();

        assertEquals(BackfillResult.Status.COMPLETED, result.status());
        assertEquals(0, result.processedMessages(), "Should process 0 messages");

        logger.info("Empty backfill verified");
    }

    /**
     * Test backfill cancellation.
     */
    @Test
    void testBackfillCancellation() throws Exception {
        String topic = "test-backfill-cancel-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "backfill-cancel-group";

        topicConfigService.createTopic(TopicConfig.builder()
                        .topic(topic)
                        .semantics(TopicSemantics.PUB_SUB)
                        .messageRetentionHours(24)
                        .build())
                .toCompletionStage().toCompletableFuture().get();

        subscriptionManager.subscribe(topic, "initial-group", SubscriptionOptions.defaults())
                .toCompletionStage().toCompletableFuture().get();

        // Insert messages
        for (int i = 0; i < 10; i++) {
            insertMessage(topic, new JsonObject().put("index", i))
                    .toCompletionStage().toCompletableFuture().get();
        }

        subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.fromBeginning())
                .toCompletionStage().toCompletableFuture().get();

        // Set backfill status to IN_PROGRESS, then cancel before processing
        connectionManager.withConnection("peegeeq-main", connection -> {
            String sql = """
                UPDATE outbox_topic_subscriptions
                SET backfill_status = 'IN_PROGRESS'
                WHERE topic = $1 AND group_name = $2
                """;
            return connection.preparedQuery(sql)
                    .execute(Tuple.of(topic, groupName))
                    .mapEmpty();
        }).toCompletionStage().toCompletableFuture().get();

        // Cancel the backfill
        backfillService.cancelBackfill(topic, groupName)
                .toCompletionStage().toCompletableFuture().get();

        // Verify status is CANCELLED
        BackfillProgress progress = backfillService.getBackfillProgress(topic, groupName)
                .toCompletionStage().toCompletableFuture().get()
                .orElseThrow();

        assertEquals("CANCELLED", progress.status(),
                "Backfill should be cancelled");

        logger.info("Backfill cancellation verified");
    }

    /**
     * Test that backfill fails for non-ACTIVE subscriptions.
     */
    @Test
    void testBackfillFailsForNonActiveSubscription() throws Exception {
        String topic = "test-backfill-paused-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "backfill-paused-group";

        topicConfigService.createTopic(TopicConfig.builder()
                        .topic(topic)
                        .semantics(TopicSemantics.PUB_SUB)
                        .messageRetentionHours(24)
                        .build())
                .toCompletionStage().toCompletableFuture().get();

        subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.fromBeginning())
                .toCompletionStage().toCompletableFuture().get();

        // Pause the subscription
        subscriptionManager.pause(topic, groupName)
                .toCompletionStage().toCompletableFuture().get();

        // Attempt backfill - should fail
        try {
            backfillService.startBackfill(topic, groupName)
                    .toCompletionStage().toCompletableFuture().get();
            fail("Backfill should fail for PAUSED subscription");
        } catch (Exception e) {
            assertTrue(e.getCause().getMessage().contains("ACTIVE"),
                    "Error should mention ACTIVE requirement");
        }

        logger.info("Backfill validation for non-ACTIVE subscription verified");
    }

    /**
     * Test that backfill fails for non-existent subscription.
     */
    @Test
    void testBackfillFailsForMissingSubscription() {
        try {
            backfillService.startBackfill("nonexistent-topic", "nonexistent-group")
                    .toCompletionStage().toCompletableFuture().get();
            fail("Backfill should fail for non-existent subscription");
        } catch (Exception e) {
            assertTrue(e.getCause().getMessage().contains("not found"),
                    "Error should mention subscription not found");
        }

        logger.info("Backfill validation for missing subscription verified");
    }

    /**
     * Test backfill progress tracking.
     */
    @Test
    void testBackfillProgressTracking() throws Exception {
        String topic = "test-backfill-progress-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "backfill-progress-group";

        topicConfigService.createTopic(TopicConfig.builder()
                        .topic(topic)
                        .semantics(TopicSemantics.PUB_SUB)
                        .messageRetentionHours(24)
                        .build())
                .toCompletionStage().toCompletableFuture().get();

        subscriptionManager.subscribe(topic, "initial-group", SubscriptionOptions.defaults())
                .toCompletionStage().toCompletableFuture().get();

        for (int i = 0; i < 15; i++) {
            insertMessage(topic, new JsonObject().put("index", i))
                    .toCompletionStage().toCompletableFuture().get();
        }

        subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.fromBeginning())
                .toCompletionStage().toCompletableFuture().get();

        // Check progress before backfill
        BackfillProgress before = backfillService.getBackfillProgress(topic, groupName)
                .toCompletionStage().toCompletableFuture().get().orElseThrow();
        assertEquals("NONE", before.status());

        // Run backfill
        backfillService.startBackfill(topic, groupName, 5, 0)
                .toCompletionStage().toCompletableFuture().get();

        // Check progress after backfill
        BackfillProgress after = backfillService.getBackfillProgress(topic, groupName)
                .toCompletionStage().toCompletableFuture().get().orElseThrow();
        assertEquals("COMPLETED", after.status());
        assertEquals(15, after.processedMessages());
        assertNotNull(after.startedAt());
        assertNotNull(after.completedAt());
        assertEquals(100.0, after.percentComplete(), 0.1);

        logger.info("Backfill progress tracking verified");
    }

    /**
     * Test that backfill scope changes which message statuses are included.
     */
    @Test
    void testBackfillScopePendingOnlyVsAllRetained() throws Exception {
        String topic = "test-backfill-scope-" + UUID.randomUUID().toString().substring(0, 8);
        String pendingOnlyGroup = "scope-pending-only";
        String allRetainedGroup = "scope-all-retained";

        topicConfigService.createTopic(TopicConfig.builder()
                        .topic(topic)
                        .semantics(TopicSemantics.PUB_SUB)
                        .messageRetentionHours(24)
                        .build())
                .toCompletionStage().toCompletableFuture().get();

        subscriptionManager.subscribe(topic, "initial-group", SubscriptionOptions.defaults())
                .toCompletionStage().toCompletableFuture().get();

        int totalMessages = 10;
        int completedMessages = 4;
        for (int i = 0; i < totalMessages; i++) {
            insertMessage(topic, new JsonObject().put("index", i))
                    .toCompletionStage().toCompletableFuture().get();
        }
        markOldestMessagesCompleted(topic, completedMessages)
                .toCompletionStage().toCompletableFuture().get();

        subscriptionManager.subscribe(topic, pendingOnlyGroup,
                        SubscriptionOptions.fromBeginning(BackfillScope.PENDING_ONLY))
                .toCompletionStage().toCompletableFuture().get();
        subscriptionManager.subscribe(topic, allRetainedGroup,
                        SubscriptionOptions.fromBeginning(BackfillScope.ALL_RETAINED))
                .toCompletionStage().toCompletableFuture().get();

        BackfillResult pendingOnlyResult = backfillService
                .startBackfill(topic, pendingOnlyGroup, 100, 0, BackfillScope.PENDING_ONLY)
                .toCompletionStage().toCompletableFuture().get();
        BackfillResult allRetainedResult = backfillService
                .startBackfill(topic, allRetainedGroup, 100, 0, BackfillScope.ALL_RETAINED)
                .toCompletionStage().toCompletableFuture().get();

        int expectedPendingOnly = totalMessages - completedMessages;
        assertEquals(BackfillResult.Status.COMPLETED, pendingOnlyResult.status());
        assertEquals(expectedPendingOnly, pendingOnlyResult.processedMessages(),
                "PENDING_ONLY should exclude COMPLETED rows");

        assertEquals(BackfillResult.Status.COMPLETED, allRetainedResult.status());
        assertEquals(totalMessages, allRetainedResult.processedMessages(),
                "ALL_RETAINED should include COMPLETED rows");

        assertTrue(allRetainedResult.processedMessages() > pendingOnlyResult.processedMessages(),
                "ALL_RETAINED should process more messages than PENDING_ONLY on mixed-status data");

        logger.info("Backfill scope verified: PENDING_ONLY={}, ALL_RETAINED={}",
                pendingOnlyResult.processedMessages(), allRetainedResult.processedMessages());
    }

    // Helper methods

    private Future<Long> insertMessage(String topic, JsonObject payload) {
        return connectionManager.withConnection("peegeeq-main", connection -> {
            String sql = """
                INSERT INTO outbox (topic, payload, created_at, status)
                VALUES ($1, $2::jsonb, $3, 'PENDING')
                RETURNING id
                """;

            Tuple params = Tuple.of(topic, payload, OffsetDateTime.now(ZoneOffset.UTC));

            return connection.preparedQuery(sql)
                    .execute(params)
                    .map(rows -> rows.iterator().next().getLong("id"));
        });
    }

    private Future<Long> countMessagesWithRequiredGroups(String topic, int requiredGroups) {
        return connectionManager.withConnection("peegeeq-main", connection -> {
            String sql = """
                SELECT COUNT(*) AS cnt FROM outbox
                WHERE topic = $1 AND required_consumer_groups = $2
                """;
            return connection.preparedQuery(sql)
                    .execute(Tuple.of(topic, requiredGroups))
                    .map(rows -> rows.iterator().next().getLong("cnt"));
        });
    }

        private Future<Void> markOldestMessagesCompleted(String topic, int limit) {
                return connectionManager.withConnection("peegeeq-main", connection -> {
                        String sql = """
                                WITH to_complete AS (
                                        SELECT id
                                        FROM outbox
                                        WHERE topic = $1 AND status = 'PENDING'
                                        ORDER BY id ASC
                                        LIMIT $2
                                )
                                UPDATE outbox o
                                SET status = 'COMPLETED'
                                FROM to_complete tc
                                WHERE o.id = tc.id
                                """;

                        return connection.preparedQuery(sql)
                                        .execute(Tuple.of(topic, limit))
                                        .mapEmpty();
                });
        }
}
