package dev.mars.peegeeq.db.subscription;

import dev.mars.peegeeq.api.messaging.StartPosition;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Instant;
import java.util.List;

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
        Subscription subscription = subscriptionManager.getSubscription(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();
        
        assertNotNull(subscription, "Subscription should be created");
        assertEquals(topic, subscription.getTopic());
        assertEquals(groupName, subscription.getGroupName());
        assertEquals(SubscriptionStatus.ACTIVE, subscription.getStatus());
        assertNotNull(subscription.getSubscribedAt());
        assertNotNull(subscription.getLastHeartbeatAt());
        
        logger.info("✅ Subscribe with default options test passed");
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
        Subscription subscription = subscriptionManager.getSubscription(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();
        
        assertNotNull(subscription);
        assertEquals(30, subscription.getHeartbeatIntervalSeconds());
        assertEquals(120, subscription.getHeartbeatTimeoutSeconds());
        
        logger.info("✅ Subscribe with custom options test passed");
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
        
        Subscription pausedSubscription = subscriptionManager.getSubscription(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();
        
        assertEquals(SubscriptionStatus.PAUSED, pausedSubscription.getStatus());
        assertFalse(pausedSubscription.isActive());
        
        // Resume subscription
        subscriptionManager.resume(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();
        
        Subscription resumedSubscription = subscriptionManager.getSubscription(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();
        
        assertEquals(SubscriptionStatus.ACTIVE, resumedSubscription.getStatus());
        assertTrue(resumedSubscription.isActive());
        
        logger.info("✅ Pause and resume subscription test passed");
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
        
        Subscription cancelledSubscription = subscriptionManager.getSubscription(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();
        
        assertEquals(SubscriptionStatus.CANCELLED, cancelledSubscription.getStatus());
        assertFalse(cancelledSubscription.isActive());
        
        logger.info("✅ Cancel subscription test passed");
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
        
        Subscription initialSubscription = subscriptionManager.getSubscription(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();
        
        Instant initialHeartbeat = initialSubscription.getLastHeartbeatAt();
        
        // Wait a bit to ensure timestamp difference
        Thread.sleep(100);
        
        // Update heartbeat
        subscriptionManager.updateHeartbeat(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();
        
        Subscription updatedSubscription = subscriptionManager.getSubscription(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();
        
        Instant updatedHeartbeat = updatedSubscription.getLastHeartbeatAt();
        
        assertTrue(updatedHeartbeat.isAfter(initialHeartbeat), 
                  "Heartbeat timestamp should be updated");
        
        logger.info("✅ Update heartbeat test passed");
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
        List<Subscription> subscriptions = subscriptionManager.listSubscriptions(topic)
            .toCompletionStage()
            .toCompletableFuture()
            .get();
        
        assertEquals(3, subscriptions.size(), "Should have 3 subscriptions");
        
        // Verify all groups are present
        assertTrue(subscriptions.stream().anyMatch(s -> s.getGroupName().equals("group-a")));
        assertTrue(subscriptions.stream().anyMatch(s -> s.getGroupName().equals("group-b")));
        assertTrue(subscriptions.stream().anyMatch(s -> s.getGroupName().equals("group-c")));
        
        logger.info("✅ List subscriptions test passed");
    }
}

