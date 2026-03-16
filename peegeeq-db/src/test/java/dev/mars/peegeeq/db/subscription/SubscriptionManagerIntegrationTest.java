package dev.mars.peegeeq.db.subscription;

import dev.mars.peegeeq.api.messaging.StartPosition;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.api.subscription.SubscriptionInfo;
import dev.mars.peegeeq.api.subscription.SubscriptionState;
import dev.mars.peegeeq.db.BaseIntegrationTest;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for SubscriptionManager.
 *
 * <p>This test validates the subscription management API including:
 * <ul>
 *   <li>Creating subscriptions with different start positions</li>
 *   <li>Pausing and resuming subscriptions</li>
 *   <li>Cancelling subscriptions</li>
 *   <li>Updating heartbeats</li>
 *   <li>Querying subscription status</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-12
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
public class SubscriptionManagerIntegrationTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(SubscriptionManagerIntegrationTest.class);

    private SubscriptionManager subscriptionManager;
    private TopicConfigService topicConfigService;
    private PgConnectionManager connectionManager;

    @BeforeEach
    void setUp() throws Exception {
        // Create connection manager using the shared Vertx instance
        connectionManager = new PgConnectionManager(manager.getVertx(), null);

        // Get PostgreSQL container and create pool
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

        logger.info("SubscriptionManager test setup complete");
    }
    
    @Test
    void testSubscribeWithDefaultOptions() throws Exception {
        logger.info("=== Testing subscribe with default options ===");
        
        String topic = "test-topic-default";
        String groupName = "test-group-1";
        
        // Create topic configuration first
        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .build();
        
        topicConfigService.createTopic(topicConfig)
            .toCompletionStage()
            .toCompletableFuture()
            .get();
        
        // Subscribe with default options
        subscriptionManager.subscribe(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();
        
        // Verify subscription was created
        SubscriptionInfo subscription = subscriptionManager.getSubscription(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        assertNotNull(subscription, "Subscription should be created");
        assertEquals(topic, subscription.topic());
        assertEquals(groupName, subscription.groupName());
        assertEquals(SubscriptionState.ACTIVE, subscription.state());
        assertNotNull(subscription.subscribedAt());
        assertNotNull(subscription.lastHeartbeatAt());

        logger.info("Subscribe with default options test passed");
    }

    @Test
    void testSubscribeWithCustomOptions() throws Exception {
        logger.info("=== Testing subscribe with custom options ===");

        String topic = "test-topic-custom";
        String groupName = "test-group-2";

        // Create topic configuration
        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .build();

        topicConfigService.createTopic(topicConfig)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        // Subscribe with custom options
        SubscriptionOptions options = SubscriptionOptions.builder()
            .startPosition(StartPosition.FROM_BEGINNING)
            .heartbeatIntervalSeconds(30)
            .heartbeatTimeoutSeconds(120)
            .build();

        subscriptionManager.subscribe(topic, groupName, options)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        // Verify subscription
        SubscriptionInfo subscription = subscriptionManager.getSubscription(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        assertNotNull(subscription);
        assertEquals(30, subscription.heartbeatIntervalSeconds());
        assertEquals(120, subscription.heartbeatTimeoutSeconds());

        logger.info("Subscribe with custom options test passed");
    }
    
    @Test
    void testPauseAndResumeSubscription() throws Exception {
        logger.info("=== Testing pause and resume subscription ===");
        
        String topic = "test-topic-pause";
        String groupName = "test-group-3";
        
        // Create topic and subscription
        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .build();
        
        topicConfigService.createTopic(topicConfig)
            .toCompletionStage()
            .toCompletableFuture()
            .get();
        
        subscriptionManager.subscribe(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();
        
        // Pause subscription
        subscriptionManager.pause(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        SubscriptionInfo pausedSubscription = subscriptionManager.getSubscription(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        assertEquals(SubscriptionState.PAUSED, pausedSubscription.state());
        assertFalse(pausedSubscription.isActive());

        // Resume subscription
        subscriptionManager.resume(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        SubscriptionInfo resumedSubscription = subscriptionManager.getSubscription(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        assertEquals(SubscriptionState.ACTIVE, resumedSubscription.state());
        assertTrue(resumedSubscription.isActive());

        logger.info("Pause and resume subscription test passed");
    }
    
    @Test
    void testCancelSubscription() throws Exception {
        logger.info("=== Testing cancel subscription ===");
        
        String topic = "test-topic-cancel";
        String groupName = "test-group-4";
        
        // Create topic and subscription
        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .build();
        
        topicConfigService.createTopic(topicConfig)
            .toCompletionStage()
            .toCompletableFuture()
            .get();
        
        subscriptionManager.subscribe(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();
        
        // Cancel subscription
        subscriptionManager.cancel(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        SubscriptionInfo cancelledSubscription = subscriptionManager.getSubscription(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        assertEquals(SubscriptionState.CANCELLED, cancelledSubscription.state());
        assertFalse(cancelledSubscription.isActive());

        logger.info("Cancel subscription test passed");
    }

    @Test
    void testUpdateHeartbeat() throws Exception {
        logger.info("=== Testing update heartbeat ===");

        String topic = "test-topic-heartbeat";
        String groupName = "test-group-5";

        // Create topic and subscription
        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .build();

        topicConfigService.createTopic(topicConfig)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        subscriptionManager.subscribe(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        SubscriptionInfo initialSubscription = subscriptionManager.getSubscription(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        Instant initialHeartbeat = initialSubscription.lastHeartbeatAt();

        // Wait a bit to ensure timestamp difference
        manager.getVertx().timer(100).toCompletionStage().toCompletableFuture().join();

        // Update heartbeat
        subscriptionManager.updateHeartbeat(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        SubscriptionInfo updatedSubscription = subscriptionManager.getSubscription(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        Instant updatedHeartbeat = updatedSubscription.lastHeartbeatAt();
        
        assertTrue(updatedHeartbeat.isAfter(initialHeartbeat), 
                  "Heartbeat timestamp should be updated");
        
        logger.info("Update heartbeat test passed");
    }
    
    @Test
    void testListSubscriptions() throws Exception {
        logger.info("=== Testing list subscriptions ===");
        
        String topic = "test-topic-list";
        
        // Create topic
        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .build();
        
        topicConfigService.createTopic(topicConfig)
            .toCompletionStage()
            .toCompletableFuture()
            .get();
        
        // Create multiple subscriptions
        subscriptionManager.subscribe(topic, "group-a")
            .toCompletionStage()
            .toCompletableFuture()
            .get();
        
        subscriptionManager.subscribe(topic, "group-b")
            .toCompletionStage()
            .toCompletableFuture()
            .get();
        
        subscriptionManager.subscribe(topic, "group-c")
            .toCompletionStage()
            .toCompletableFuture()
            .get();
        
        // List all subscriptions for topic
        List<SubscriptionInfo> subscriptions = subscriptionManager.listSubscriptions(topic)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        assertEquals(3, subscriptions.size(), "Should have 3 subscriptions");

        // Verify all groups are present
        assertTrue(subscriptions.stream().anyMatch(s -> s.groupName().equals("group-a")));
        assertTrue(subscriptions.stream().anyMatch(s -> s.groupName().equals("group-b")));
        assertTrue(subscriptions.stream().anyMatch(s -> s.groupName().equals("group-c")));

        logger.info("List subscriptions test passed");
    }

    @Test
    void testHeartbeatResurrectsDeadSubscription() throws Exception {
        logger.info("=== Testing heartbeat auto-resurrects DEAD subscription ===");

        String topic = "test-topic-resurrect";
        String groupName = "test-group-resurrect";

        // Create topic and subscription
        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .build();

        topicConfigService.createTopic(topicConfig)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        subscriptionManager.subscribe(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        // Verify initially ACTIVE
        SubscriptionInfo initial = subscriptionManager.getSubscription(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();
        assertEquals(SubscriptionState.ACTIVE, initial.state(), "Should start as ACTIVE");

        // Mark as DEAD via direct SQL (simulates what DeadConsumerDetector does)
        setSubscriptionStatus(topic, groupName, "DEAD");

        // Verify it's actually DEAD
        SubscriptionInfo dead = subscriptionManager.getSubscription(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();
        assertEquals(SubscriptionState.DEAD, dead.state(), "Should be DEAD after manual update");

        Instant deadHeartbeat = dead.lastHeartbeatAt();

        // Wait a bit for timestamp difference
        manager.getVertx().timer(100).toCompletionStage().toCompletableFuture().join();

        // Send heartbeat — should auto-resurrect DEAD → ACTIVE
        subscriptionManager.updateHeartbeat(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        // Verify resurrected to ACTIVE
        SubscriptionInfo resurrected = subscriptionManager.getSubscription(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();
        assertEquals(SubscriptionState.ACTIVE, resurrected.state(), 
                    "Should be ACTIVE after heartbeat resurrects DEAD subscription");
        assertTrue(resurrected.lastHeartbeatAt().isAfter(deadHeartbeat),
                  "Heartbeat timestamp should be updated");

        logger.info("Heartbeat auto-resurrection test passed");
    }

    @Test
    void testHeartbeatDoesNotResurrectCancelledSubscription() throws Exception {
        logger.info("=== Testing heartbeat does NOT resurrect CANCELLED subscription ===");

        String topic = "test-topic-no-resurrect";
        String groupName = "test-group-no-resurrect";

        // Create topic and subscription
        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .build();

        topicConfigService.createTopic(topicConfig)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        subscriptionManager.subscribe(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        // Cancel the subscription
        subscriptionManager.cancel(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        // Verify CANCELLED
        SubscriptionInfo cancelled = subscriptionManager.getSubscription(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();
        assertEquals(SubscriptionState.CANCELLED, cancelled.state(), "Should be CANCELLED");

        // Send heartbeat — should NOT change status (CANCELLED is terminal)
        subscriptionManager.updateHeartbeat(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        // Verify still CANCELLED
        SubscriptionInfo stillCancelled = subscriptionManager.getSubscription(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();
        assertEquals(SubscriptionState.CANCELLED, stillCancelled.state(),
                    "CANCELLED subscription should NOT be resurrected by heartbeat");

        logger.info("Heartbeat does not resurrect CANCELLED test passed");
    }

    @Test
    void testHeartbeatKeepsPausedSubscriptionPaused() throws Exception {
        logger.info("=== Testing heartbeat keeps PAUSED subscription PAUSED ===");

        String topic = "test-topic-paused-hb";
        String groupName = "test-group-paused-hb";

        // Create topic and subscription
        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .build();

        topicConfigService.createTopic(topicConfig)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        subscriptionManager.subscribe(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        // Pause the subscription
        subscriptionManager.pause(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        // Verify PAUSED
        SubscriptionInfo paused = subscriptionManager.getSubscription(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();
        assertEquals(SubscriptionState.PAUSED, paused.state(), "Should be PAUSED");

        // Send heartbeat — should NOT change status
        subscriptionManager.updateHeartbeat(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        // Verify still PAUSED
        SubscriptionInfo stillPaused = subscriptionManager.getSubscription(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();
        assertEquals(SubscriptionState.PAUSED, stillPaused.state(),
                    "PAUSED subscription should remain PAUSED after heartbeat");

        logger.info("Heartbeat keeps PAUSED subscription PAUSED test passed");
    }
    // --- Backfill Lifecycle Integration Tests (H2) ---

    @Test
    void testSubscribeFromBeginningAutoTriggersBackfill() throws Exception {
        logger.info("=== Testing subscribe FROM_BEGINNING auto-triggers backfill ===");

        String topic = "test-backfill-auto-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "backfill-auto-group";

        // Create PUB_SUB topic
        topicConfigService.createTopic(TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.PUB_SUB)
                .messageRetentionHours(24)
                .build())
            .toCompletionStage().toCompletableFuture().get();

        // Subscribe an initial group so messages get required_consumer_groups = 1
        subscriptionManager.subscribe(topic, "initial-group", SubscriptionOptions.defaults())
            .toCompletionStage().toCompletableFuture().get();

        // Insert some messages before the late-joining group subscribes
        int messageCount = 5;
        for (int i = 0; i < messageCount; i++) {
            insertMessage(topic, new JsonObject().put("index", i))
                .toCompletionStage().toCompletableFuture().get();
        }

        // Configure BackfillService on SubscriptionManager
        BackfillService backfillService = new BackfillService(connectionManager, "peegeeq-main");
        subscriptionManager.setBackfillService(backfillService);

        // Subscribe with FROM_BEGINNING \u2014 should auto-trigger backfill
        subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.fromBeginning())
            .toCompletionStage().toCompletableFuture().get();

        // Verify backfill ran by checking backfill_status is COMPLETED
        SubscriptionInfo info = subscriptionManager.getSubscription(topic, groupName)
            .toCompletionStage().toCompletableFuture().get();

        assertNotNull(info, "Subscription should exist");
        assertEquals(SubscriptionState.ACTIVE, info.state(), "Subscription should be ACTIVE");
        assertEquals("COMPLETED", info.backfillStatus(),
                    "Backfill should have been auto-triggered and completed");
        assertEquals(messageCount, info.backfillProcessedMessages(),
                    "All " + messageCount + " messages should have been backfilled");

        // Verify required_consumer_groups was incremented
        Long incrementedCount = countMessagesWithRequiredGroups(topic, 2)
            .toCompletionStage().toCompletableFuture().get();
        assertTrue(incrementedCount > 0,
                  "Messages should have required_consumer_groups incremented to 2");

        logger.info("\u2705 Subscribe FROM_BEGINNING auto-triggers backfill test passed");
    }

    @Test
    void testSubscribeFromNowDoesNotTriggerBackfill() throws Exception {
        logger.info("=== Testing subscribe FROM_NOW does NOT trigger backfill ===");

        String topic = "test-no-backfill-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "no-backfill-group";

        // Create PUB_SUB topic
        topicConfigService.createTopic(TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.PUB_SUB)
                .messageRetentionHours(24)
                .build())
            .toCompletionStage().toCompletableFuture().get();

        // Insert messages before subscribing
        for (int i = 0; i < 3; i++) {
            insertMessage(topic, new JsonObject().put("index", i))
                .toCompletionStage().toCompletableFuture().get();
        }

        // Configure BackfillService
        BackfillService backfillService = new BackfillService(connectionManager, "peegeeq-main");
        subscriptionManager.setBackfillService(backfillService);

        // Subscribe with FROM_NOW \u2014 should NOT trigger backfill
        subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.defaults())
            .toCompletionStage().toCompletableFuture().get();

        // Verify no backfill was triggered
        SubscriptionInfo info = subscriptionManager.getSubscription(topic, groupName)
            .toCompletionStage().toCompletableFuture().get();

        assertNotNull(info, "Subscription should exist");
        assertEquals(SubscriptionState.ACTIVE, info.state(), "Subscription should be ACTIVE");
        // backfill_status should be NONE or null (not COMPLETED)
        assertTrue(info.backfillStatus() == null || "NONE".equals(info.backfillStatus()),
                  "Backfill should NOT have been triggered for FROM_NOW, got: " + info.backfillStatus());

        logger.info("\u2705 Subscribe FROM_NOW does not trigger backfill test passed");
    }

    @Test
    void testSubscribeFromBeginningWithoutBackfillServiceStillWorks() throws Exception {
        logger.info("=== Testing subscribe FROM_BEGINNING without BackfillService ===");

        String topic = "test-no-svc-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "no-svc-group";

        // Create PUB_SUB topic
        topicConfigService.createTopic(TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.PUB_SUB)
                .build())
            .toCompletionStage().toCompletableFuture().get();

        // Do NOT set BackfillService on the SubscriptionManager
        // Subscribe with FROM_BEGINNING \u2014 should succeed without triggering backfill
        subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.fromBeginning())
            .toCompletionStage().toCompletableFuture().get();

        // Verify subscription was created normally
        SubscriptionInfo info = subscriptionManager.getSubscription(topic, groupName)
            .toCompletionStage().toCompletableFuture().get();

        assertNotNull(info, "Subscription should exist");
        assertEquals(SubscriptionState.ACTIVE, info.state(), "Subscription should be ACTIVE");
        assertEquals(1L, info.startFromMessageId(), "start_from_message_id should be 1 for FROM_BEGINNING");

        logger.info("\u2705 Subscribe FROM_BEGINNING without BackfillService test passed");
    }
    // --- Helper methods ---

    /**
     * Directly sets subscription status via SQL (for test setup).
     */
    private void setSubscriptionStatus(String topic, String groupName, String status) throws Exception {
        connectionManager.withConnection("peegeeq-main", connection -> {
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

    /**
     * Inserts a test message into the outbox.
     */
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

    /**
     * Counts messages with a specific required_consumer_groups value.
     */
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
}

