package dev.mars.peegeeq.db.subscription;

import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.api.subscription.SubscriptionInfo;
import dev.mars.peegeeq.api.subscription.SubscriptionState;
import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.cleanup.DeadConsumerGroupCleanup;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Task H6: CANCELLED Subscription Orphan Cleanup.
 *
 * <p>When a subscription is cancelled via {@code cancel()}, its PENDING/PROCESSING
 * rows in {@code outbox_consumer_groups} should be removed and
 * {@code required_consumer_groups} decremented so messages are not left blocked.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-04-07
 */
@Tag(TestCategories.INTEGRATION)
@Execution(ExecutionMode.SAME_THREAD)
public class CancelCleanupIntegrationTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(CancelCleanupIntegrationTest.class);

    private SubscriptionManager subscriptionManager;
    private TopicConfigService topicConfigService;
    private PgConnectionManager connectionManager;

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

        subscriptionManager = new SubscriptionManager(connectionManager, "peegeeq-main");
        topicConfigService = new TopicConfigService(connectionManager, "peegeeq-main");

        logger.info("CancelCleanupIntegrationTest setup complete");
    }

    @Test
    void testCancelCleansUpOrphanedRowsAndDecrementsRequiredGroups() throws Exception {
        logger.info("=== Testing cancel() cleans up messages ===");

        String topic = "test-cancel-cleanup-" + UUID.randomUUID().toString().substring(0, 8);
        String groupA = "group-a";
        String groupB = "group-b";

        // Create PUB_SUB topic
        topicConfigService.createTopic(TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.PUB_SUB)
                .messageRetentionHours(24)
                .build())
            .toCompletionStage().toCompletableFuture().get();

        // Subscribe both groups
        subscriptionManager.subscribe(topic, groupA, SubscriptionOptions.defaults())
            .toCompletionStage().toCompletableFuture().get();
        subscriptionManager.subscribe(topic, groupB, SubscriptionOptions.defaults())
            .toCompletionStage().toCompletableFuture().get();

        // Configure cleanup
        DeadConsumerGroupCleanup cleanup = new DeadConsumerGroupCleanup(connectionManager, "peegeeq-main");
        subscriptionManager.setDeadConsumerGroupCleanup(cleanup);

        // Publish messages — trigger sets required_consumer_groups = 2 (2 active subscriptions)
        int messageCount = 5;
        java.util.List<Long> messageIds = new java.util.ArrayList<>();
        for (int i = 0; i < messageCount; i++) {
            long id = insertMessage(topic, new JsonObject().put("index", i))
                .toCompletionStage().toCompletableFuture().get();
            messageIds.add(id);
        }

        // Verify messages require 2 groups
        long twoGroupMsgs = countMessagesWithRequiredGroups(topic, 2)
            .toCompletionStage().toCompletableFuture().get();
        assertEquals(messageCount, twoGroupMsgs, "Messages should require 2 consumer groups");

        // Create PENDING tracking rows for groupB (simulates consumer fetching but not yet completing)
        for (long id : messageIds) {
            insertConsumerGroupRow(id, groupB, "PENDING")
                .toCompletionStage().toCompletableFuture().get();
        }

        // Verify outbox_consumer_groups rows exist for groupB
        long groupBRows = countConsumerGroupRows(topic, groupB)
            .toCompletionStage().toCompletableFuture().get();
        assertEquals(messageCount, groupBRows, "groupB should have tracking rows in outbox_consumer_groups");

        // Cancel groupB — should clean up its tracking rows and decrement required_consumer_groups
        subscriptionManager.cancel(topic, groupB)
            .toCompletionStage().toCompletableFuture().get();

        // Verify CANCELLED state
        SubscriptionInfo cancelled = subscriptionManager.getSubscription(topic, groupB)
            .toCompletionStage().toCompletableFuture().get();
        assertEquals(SubscriptionState.CANCELLED, cancelled.state());

        // Verify required_consumer_groups decremented to 1
        long oneGroupMsgs = countMessagesWithRequiredGroups(topic, 1)
            .toCompletionStage().toCompletableFuture().get();
        assertEquals(messageCount, oneGroupMsgs,
                    "After cancel, messages should require 1 consumer group (decremented from 2)");

        // Verify orphaned outbox_consumer_groups rows removed for groupB
        long groupBRowsAfter = countConsumerGroupRows(topic, groupB)
            .toCompletionStage().toCompletableFuture().get();
        assertEquals(0, groupBRowsAfter,
                    "Cancelled group's outbox_consumer_groups rows should be removed");

        logger.info("Cancel cleanup test passed");
    }

    @Test
    void testCancelWithoutCleanupServiceStillSucceeds() throws Exception {
        logger.info("=== Testing cancel without DeadConsumerGroupCleanup still works ===");

        String topic = "test-cancel-nocleanup-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "no-cleanup-group";

        // Create topic and subscribe
        topicConfigService.createTopic(TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.PUB_SUB)
                .build())
            .toCompletionStage().toCompletableFuture().get();

        subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.defaults())
            .toCompletionStage().toCompletableFuture().get();

        // Do NOT configure DeadConsumerGroupCleanup

        // Cancel should still succeed
        subscriptionManager.cancel(topic, groupName)
            .toCompletionStage().toCompletableFuture().get();

        SubscriptionInfo cancelled = subscriptionManager.getSubscription(topic, groupName)
            .toCompletionStage().toCompletableFuture().get();
        assertEquals(SubscriptionState.CANCELLED, cancelled.state(),
                    "Cancel should succeed even without cleanup service configured");

        logger.info("Cancel without cleanup service test passed");
    }

    @Test
    void testCancelCleanupFailureDoesNotFailCancel() throws Exception {
        logger.info("=== Testing cancel cleanup failure doesn't fail cancel ===");

        String topic = "test-cancel-fail-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "cancel-fail-group";

        // Create topic and subscribe
        topicConfigService.createTopic(TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.PUB_SUB)
                .build())
            .toCompletionStage().toCompletableFuture().get();

        subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.defaults())
            .toCompletionStage().toCompletableFuture().get();

        // Configure cleanup with a broken connection manager — cleanup will fail
        PgConnectionManager brokenConnectionManager = new PgConnectionManager(manager.getVertx(), null);
        DeadConsumerGroupCleanup brokenCleanup = new DeadConsumerGroupCleanup(brokenConnectionManager, "no-such-pool");
        subscriptionManager.setDeadConsumerGroupCleanup(brokenCleanup);

        // Cancel should still succeed even though cleanup fails
        subscriptionManager.cancel(topic, groupName)
            .toCompletionStage().toCompletableFuture().get();

        SubscriptionInfo cancelled = subscriptionManager.getSubscription(topic, groupName)
            .toCompletionStage().toCompletableFuture().get();
        assertEquals(SubscriptionState.CANCELLED, cancelled.state(),
                    "Cancel must succeed even when cleanup fails");

        logger.info("Cancel cleanup failure resilience test passed");
    }

    @Test
    void testForceRemoveBehaviourUnchanged() throws Exception {
        logger.info("=== Testing forceRemoveConsumerGroup still works correctly ===");

        String topic = "test-forcerm-unchanged-" + UUID.randomUUID().toString().substring(0, 8);
        String groupA = "group-a";
        String groupB = "group-b";

        // Create topic, subscribe both groups
        topicConfigService.createTopic(TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.PUB_SUB)
                .messageRetentionHours(24)
                .build())
            .toCompletionStage().toCompletableFuture().get();

        subscriptionManager.subscribe(topic, groupA, SubscriptionOptions.defaults())
            .toCompletionStage().toCompletableFuture().get();
        subscriptionManager.subscribe(topic, groupB, SubscriptionOptions.defaults())
            .toCompletionStage().toCompletableFuture().get();

        DeadConsumerGroupCleanup cleanup = new DeadConsumerGroupCleanup(connectionManager, "peegeeq-main");
        subscriptionManager.setDeadConsumerGroupCleanup(cleanup);

        // Publish messages
        int messageCount = 3;
        for (int i = 0; i < messageCount; i++) {
            insertMessage(topic, new JsonObject().put("index", i))
                .toCompletionStage().toCompletableFuture().get();
        }

        // forceRemove groupB — existing behaviour should still work
        subscriptionManager.forceRemoveConsumerGroup(topic, groupB)
            .toCompletionStage().toCompletableFuture().get();

        // Verify CANCELLED
        SubscriptionInfo removed = subscriptionManager.getSubscription(topic, groupB)
            .toCompletionStage().toCompletableFuture().get();
        assertEquals(SubscriptionState.CANCELLED, removed.state());

        // Verify messages decremented to 1
        long oneGroupMsgs = countMessagesWithRequiredGroups(topic, 1)
            .toCompletionStage().toCompletableFuture().get();
        assertEquals(messageCount, oneGroupMsgs,
                    "forceRemove should still decrement required_consumer_groups");

        logger.info("forceRemove behaviour unchanged test passed");
    }

    // --- Helper methods ---

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

    private Future<Long> countConsumerGroupRows(String topic, String groupName) {
        return connectionManager.withConnection("peegeeq-main", connection -> {
            String sql = """
                SELECT COUNT(*) AS cnt FROM outbox_consumer_groups ocg
                JOIN outbox o ON o.id = ocg.message_id
                WHERE o.topic = $1 AND ocg.group_name = $2
                """;
            return connection.preparedQuery(sql)
                    .execute(Tuple.of(topic, groupName))
                    .map(rows -> rows.iterator().next().getLong("cnt"));
        });
    }

    private Future<Void> insertConsumerGroupRow(long messageId, String groupName, String status) {
        return connectionManager.withConnection("peegeeq-main", connection -> {
            String sql = """
                INSERT INTO outbox_consumer_groups (message_id, group_name, status)
                VALUES ($1, $2, $3)
                ON CONFLICT (message_id, group_name) DO NOTHING
                """;
            return connection.preparedQuery(sql)
                    .execute(Tuple.of(messageId, groupName, status))
                    .mapEmpty();
        });
    }
}
