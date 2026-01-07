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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CORE tests for SubscriptionManager using TestContainers.
 * 
 * <p>These tests are fast, focused, and isolated - testing individual methods
 * of SubscriptionManager with a real database. Each test validates a single
 * responsibility and runs in <1 second.</p>
 * 
 * <p>Tagged as CORE because they are:
 * <ul>
 *   <li>Fast: Each test completes in <1 second</li>
 *   <li>Isolated: Each test focuses on a single method/behavior</li>
 *   <li>Focused: Tests one component (SubscriptionManager) not integration scenarios</li>
 * </ul>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-27
 * @version 1.0
 */
@Tag(TestCategories.CORE)
public class SubscriptionManagerCoreTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(SubscriptionManagerCoreTest.class);

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

        logger.info("SubscriptionManagerCoreTest setup complete");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connectionManager != null) {
            connectionManager.close();
        }
    }

    @Test
    void testSubscribeWithDefaultOptions() throws Exception {
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

        assertNotNull(subscription);
        assertEquals(topic, subscription.topic());
        assertEquals(groupName, subscription.groupName());
        assertEquals(SubscriptionState.ACTIVE, subscription.state());
        assertNotNull(subscription.subscribedAt());
        assertNotNull(subscription.lastHeartbeatAt());
    }

    @Test
    void testSubscribeWithFromBeginning() throws Exception {
        String topic = "test-topic-beginning";
        String groupName = "test-group-2";

        // Create topic
        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.QUEUE)
            .build();

        topicConfigService.createTopic(topicConfig)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        // Subscribe from beginning
        SubscriptionOptions options = SubscriptionOptions.builder()
            .startPosition(StartPosition.FROM_BEGINNING)
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
        assertEquals(1L, subscription.startFromMessageId());
    }

    @Test
    void testSubscribeWithFromNow() throws Exception {
        String topic = "test-topic-now";
        String groupName = "test-group-3";

        // Create topic
        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .build();

        topicConfigService.createTopic(topicConfig)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        // Subscribe from now
        SubscriptionOptions options = SubscriptionOptions.builder()
            .startPosition(StartPosition.FROM_NOW)
            .build();

        subscriptionManager.subscribe(topic, groupName, options)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        // Verify subscription - should start from next message (1 since no messages exist)
        SubscriptionInfo subscription = subscriptionManager.getSubscription(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        assertNotNull(subscription);
        assertNotNull(subscription.startFromMessageId());
        assertTrue(subscription.startFromMessageId() >= 1);
    }

    @Test
    void testSubscribeWithFromMessageId() throws Exception {
        String topic = "test-topic-msgid";
        String groupName = "test-group-4";

        // Create topic
        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.QUEUE)
            .build();

        topicConfigService.createTopic(topicConfig)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        // Subscribe from specific message ID
        SubscriptionOptions options = SubscriptionOptions.builder()
            .startPosition(StartPosition.FROM_MESSAGE_ID)
            .startFromMessageId(100L)
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
        assertEquals(100L, subscription.startFromMessageId());
    }

    @Test
    void testSubscribeWithFromTimestamp() throws Exception {
        String topic = "test-topic-timestamp";
        String groupName = "test-group-5";

        // Create topic
        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .build();

        topicConfigService.createTopic(topicConfig)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        // Subscribe from timestamp
        Instant startTime = Instant.now().minusSeconds(3600); // 1 hour ago
        SubscriptionOptions options = SubscriptionOptions.builder()
            .startPosition(StartPosition.FROM_TIMESTAMP)
            .startFromTimestamp(startTime)
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
        assertNotNull(subscription.startFromTimestamp());
        // PostgreSQL stores timestamps with microsecond precision, so truncate to micros for comparison
        Instant expectedTruncated = startTime.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        Instant actualTruncated = subscription.startFromTimestamp().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        assertEquals(expectedTruncated, actualTruncated);
    }

    @Test
    void testPauseSubscription() throws Exception {
        String topic = "test-topic-pause";
        String groupName = "test-group-6";

        // Create topic and subscribe
        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.QUEUE)
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

        // Verify status
        SubscriptionInfo subscription = subscriptionManager.getSubscription(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        assertNotNull(subscription);
        assertEquals(SubscriptionState.PAUSED, subscription.state());
    }

    @Test
    void testResumeSubscription() throws Exception {
        String topic = "test-topic-resume";
        String groupName = "test-group-7";

        // Create topic, subscribe, and pause
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

        subscriptionManager.pause(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        // Resume subscription
        subscriptionManager.resume(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        // Verify status
        SubscriptionInfo subscription = subscriptionManager.getSubscription(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        assertNotNull(subscription);
        assertEquals(SubscriptionState.ACTIVE, subscription.state());
    }

    @Test
    void testCancelSubscription() throws Exception {
        String topic = "test-topic-cancel";
        String groupName = "test-group-8";

        // Create topic and subscribe
        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.QUEUE)
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

        // Verify status
        SubscriptionInfo subscription = subscriptionManager.getSubscription(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        assertNotNull(subscription);
        assertEquals(SubscriptionState.CANCELLED, subscription.state());
    }

    @Test
    void testUpdateHeartbeat() throws Exception {
        String topic = "test-topic-heartbeat";
        String groupName = "test-group-9";

        // Create topic and subscribe
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

        // Get initial heartbeat
        SubscriptionInfo before = subscriptionManager.getSubscription(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        Instant initialHeartbeat = before.lastHeartbeatAt();

        // Wait a bit to ensure timestamp changes
        Thread.sleep(100);

        // Update heartbeat
        subscriptionManager.updateHeartbeat(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        // Verify heartbeat was updated
        SubscriptionInfo after = subscriptionManager.getSubscription(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        assertNotNull(after.lastHeartbeatAt());
        assertTrue(after.lastHeartbeatAt().isAfter(initialHeartbeat),
            "Heartbeat should be updated to a later time");
    }

    @Test
    void testListSubscriptionsForTopic() throws Exception {
        // Use unique topic name to avoid conflicts in parallel test execution
        String topic = "test-topic-list-" + UUID.randomUUID().toString().substring(0, 8);

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
        subscriptionManager.subscribe(topic, "group-1")
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        subscriptionManager.subscribe(topic, "group-2")
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        subscriptionManager.subscribe(topic, "group-3")
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        // List subscriptions
        List<SubscriptionInfo> subscriptions = subscriptionManager.listSubscriptions(topic)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        assertNotNull(subscriptions);
        assertEquals(3, subscriptions.size());

        // Verify all groups are present
        List<String> groupNames = subscriptions.stream()
            .map(SubscriptionInfo::groupName)
            .sorted()
            .toList();

        assertEquals(List.of("group-1", "group-2", "group-3"), groupNames);
    }

    @Test
    void testGetNonExistentSubscription() throws Exception {
        SubscriptionInfo subscription = subscriptionManager.getSubscription("non-existent-topic", "non-existent-group")
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        assertNull(subscription, "Should return null for non-existent subscription");
    }

    @Test
    void testSubscribeRequiresNonNullTopic() {
        assertThrows(NullPointerException.class, () -> {
            subscriptionManager.subscribe(null, "group-name")
                .toCompletionStage()
                .toCompletableFuture()
                .get();
        });
    }

    @Test
    void testSubscribeRequiresNonNullGroupName() {
        assertThrows(NullPointerException.class, () -> {
            subscriptionManager.subscribe("topic", null)
                .toCompletionStage()
                .toCompletableFuture()
                .get();
        });
    }

    @Test
    void testSubscribeRequiresNonNullOptions() {
        assertThrows(NullPointerException.class, () -> {
            subscriptionManager.subscribe("topic", "group", null)
                .toCompletionStage()
                .toCompletableFuture()
                .get();
        });
    }
}

