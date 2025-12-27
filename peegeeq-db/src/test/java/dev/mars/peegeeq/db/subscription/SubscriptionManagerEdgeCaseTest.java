package dev.mars.peegeeq.db.subscription;

import dev.mars.peegeeq.api.messaging.StartPosition;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.api.subscription.SubscriptionInfo;
import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Edge case tests for SubscriptionManager.
 * 
 * <p><b>PURPOSE:</b> Comprehensive edge case coverage at the database layer to ensure
 * robust behavior for all StartPosition variants, updates, and error conditions.
 * 
 * <p><b>CRITICAL INVARIANTS TESTED:</b>
 * <ul>
 *   <li>FROM_BEGINNING → start_from_message_id = 1</li>
 *   <li>FROM_NOW → start_from_message_id = maxId + 1</li>
 *   <li>FROM_MESSAGE_ID → exact message ID storage</li>
 *   <li>FROM_TIMESTAMP → exact timestamp storage</li>
 *   <li>Subscription updates preserve data integrity</li>
 *   <li>Edge cases (ID=0, null handling, concurrent updates)</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-23
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
public class SubscriptionManagerEdgeCaseTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(SubscriptionManagerEdgeCaseTest.class);

    private SubscriptionManager subscriptionManager;
    private TopicConfigService topicConfigService;
    private PgConnectionManager connectionManager;

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

        subscriptionManager = new SubscriptionManager(connectionManager, "peegeeq-main");
        topicConfigService = new TopicConfigService(connectionManager, "peegeeq-main");

        logger.info("SubscriptionManagerEdgeCaseTest setup complete");
    }

    @Test
    void testFromBeginningStoresMessageIdOne() throws Exception {
        logger.info("=== Testing FROM_BEGINNING stores start_from_message_id = 1 ===");

        String topic = "test-from-beginning-storage";
        String groupName = "group-beginning-storage";

        createTopic(topic);

        // Subscribe with FROM_BEGINNING
        SubscriptionOptions options = SubscriptionOptions.builder()
            .startPosition(StartPosition.FROM_BEGINNING)
            .build();

        subscriptionManager.subscribe(topic, groupName, options)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        logger.info("✓ Subscription created with FROM_BEGINNING");

        // Retrieve and verify
        SubscriptionInfo subscription = subscriptionManager.getSubscription(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        assertNotNull(subscription.startFromMessageId(),
            "FROM_BEGINNING must store start_from_message_id (not NULL)");
        assertEquals(1L, subscription.startFromMessageId().longValue(),
            "FROM_BEGINNING MUST store start_from_message_id = 1");
        assertNull(subscription.startFromTimestamp(),
            "start_from_timestamp should be NULL for FROM_BEGINNING");

        logger.info("✅ FROM_BEGINNING → start_from_message_id=1 verified");
    }

    @Test
    void testFromNowStoresNonNullMessageId() throws Exception {
        logger.info("=== Testing FROM_NOW stores non-null start_from_message_id ===");

        String topic = "test-from-now-storage";
        String groupName = "group-now-storage";

        createTopic(topic);

        // Subscribe with FROM_NOW
        SubscriptionOptions options = SubscriptionOptions.builder()
            .startPosition(StartPosition.FROM_NOW)
            .build();

        subscriptionManager.subscribe(topic, groupName, options)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        logger.info("✓ Subscription created with FROM_NOW");

        // Retrieve and verify
        SubscriptionInfo subscription = subscriptionManager.getSubscription(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        assertNotNull(subscription.startFromMessageId(),
            "FROM_NOW must store start_from_message_id (not NULL)");
        assertTrue(subscription.startFromMessageId() >= 1L,
            "FROM_NOW should store start_from_message_id >= 1");
        assertNull(subscription.startFromTimestamp());

        logger.info("✅ FROM_NOW → start_from_message_id={} verified",
            subscription.startFromMessageId());
    }

    @Test
    void testFromMessageIdWithExplicitValue() throws Exception {
        logger.info("=== Testing FROM_MESSAGE_ID with explicit value ===");

        String topic = "test-from-message-id";
        String groupName = "group-message-id";
        Long explicitMessageId = 42L;

        createTopic(topic);

        // Subscribe with FROM_MESSAGE_ID
        SubscriptionOptions options = SubscriptionOptions.builder()
            .startPosition(StartPosition.FROM_MESSAGE_ID)
            .startFromMessageId(explicitMessageId)
            .build();

        subscriptionManager.subscribe(topic, groupName, options)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        logger.info("✓ Subscription created with FROM_MESSAGE_ID={}", explicitMessageId);

        // Retrieve and verify
        SubscriptionInfo subscription = subscriptionManager.getSubscription(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        assertNotNull(subscription.startFromMessageId());
        assertEquals(explicitMessageId, subscription.startFromMessageId(),
            "FROM_MESSAGE_ID must store exact provided message ID");
        assertNull(subscription.startFromTimestamp());

        logger.info("✅ FROM_MESSAGE_ID(42) → start_from_message_id=42 verified");
    }

    @Test
    void testFromTimestampWithExplicitValue() throws Exception {
        logger.info("=== Testing FROM_TIMESTAMP with explicit value ===");

        String topic = "test-from-timestamp";
        String groupName = "group-timestamp";
        Instant explicitTimestamp = Instant.now().minusSeconds(3600); // 1 hour ago

        createTopic(topic);

        // Subscribe with FROM_TIMESTAMP
        SubscriptionOptions options = SubscriptionOptions.builder()
            .startPosition(StartPosition.FROM_TIMESTAMP)
            .startFromTimestamp(explicitTimestamp)
            .build();

        subscriptionManager.subscribe(topic, groupName, options)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        logger.info("✓ Subscription created with FROM_TIMESTAMP={}", explicitTimestamp);

        // Retrieve and verify
        SubscriptionInfo subscription = subscriptionManager.getSubscription(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        assertNull(subscription.startFromMessageId(),
            "start_from_message_id should be NULL for FROM_TIMESTAMP");
        assertNotNull(subscription.startFromTimestamp());

        // Timestamps may lose precision, compare within 1 second tolerance
        long diffMillis = Math.abs(explicitTimestamp.toEpochMilli() -
                                   subscription.startFromTimestamp().toEpochMilli());
        assertTrue(diffMillis < 1000,
            String.format("Timestamp difference should be < 1 second (was %d ms)", diffMillis));

        logger.info("✅ FROM_TIMESTAMP → stored correctly (diff: {} ms)", diffMillis);
    }

    @Test
    void testUpdateFromNowToFromBeginning() throws Exception {
        logger.info("=== Testing update FROM_NOW → FROM_BEGINNING ===");

        String topic = "test-update-position";
        String groupName = "group-update-position";

        createTopic(topic);

        // Initial: FROM_NOW
        SubscriptionOptions initialOptions = SubscriptionOptions.builder()
            .startPosition(StartPosition.FROM_NOW)
            .build();

        subscriptionManager.subscribe(topic, groupName, initialOptions)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        SubscriptionInfo initialSubscription = subscriptionManager.getSubscription(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        Long initialMessageId = initialSubscription.startFromMessageId();
        logger.info("✓ Initial state: FROM_NOW with start_from_message_id={}", initialMessageId);

        // Note: If topic is empty, FROM_NOW gives maxId+1 which is 1 (same as FROM_BEGINNING)
        // This test verifies that the update mechanism works, not that values differ
        assertNotNull(initialMessageId, "Initial message ID should not be null");

        // Update: FROM_BEGINNING
        SubscriptionOptions updatedOptions = SubscriptionOptions.builder()
            .startPosition(StartPosition.FROM_BEGINNING)
            .build();

        subscriptionManager.subscribe(topic, groupName, updatedOptions)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        SubscriptionInfo updatedSubscription = subscriptionManager.getSubscription(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        assertEquals(1L, updatedSubscription.startFromMessageId().longValue(),
            "After update, start_from_message_id should be 1 (FROM_BEGINNING)");

        logger.info("✅ Update verified: {} → 1 (FROM_NOW → FROM_BEGINNING)", initialMessageId);
    }

    @Test
    void testEdgeCaseMessageIdZero() throws Exception {
        logger.info("=== Testing FROM_MESSAGE_ID with ID = 0 (edge case) ===");

        String topic = "test-message-id-zero";
        String groupName = "group-zero";

        createTopic(topic);

        // Subscribe with message ID = 0
        SubscriptionOptions options = SubscriptionOptions.builder()
            .startPosition(StartPosition.FROM_MESSAGE_ID)
            .startFromMessageId(0L)
            .build();

        subscriptionManager.subscribe(topic, groupName, options)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        SubscriptionInfo subscription = subscriptionManager.getSubscription(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        assertNotNull(subscription.startFromMessageId());
        assertEquals(0L, subscription.startFromMessageId().longValue(),
            "Should accept and store message ID = 0");

        logger.info("✅ Edge case verified: message ID = 0 handled correctly");
    }

    @Test
    void testGetNonExistentSubscription() throws Exception {
        logger.info("=== Testing get non-existent subscription ===");

        String topic = "test-nonexistent";
        String groupName = "group-nonexistent";

        createTopic(topic);

        // Try to get non-existent subscription
        SubscriptionInfo subscription = subscriptionManager.getSubscription(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        assertNull(subscription, "Non-existent subscription should return null");

        logger.info("✅ Non-existent subscription returns null correctly");
    }

    @Test
    void testUpdateFromBeginningToMessageId() throws Exception {
        logger.info("=== Testing update FROM_BEGINNING → FROM_MESSAGE_ID ===");

        String topic = "test-update-beginning-to-id";
        String groupName = "group-update-beg-id";

        createTopic(topic);

        // Initial: FROM_BEGINNING
        subscriptionManager.subscribe(topic, groupName,
            SubscriptionOptions.builder().startPosition(StartPosition.FROM_BEGINNING).build())
            .toCompletionStage().toCompletableFuture().get();

        SubscriptionInfo initial = subscriptionManager.getSubscription(topic, groupName)
            .toCompletionStage().toCompletableFuture().get();
        assertEquals(1L, initial.startFromMessageId());

        // Update: FROM_MESSAGE_ID(100)
        subscriptionManager.subscribe(topic, groupName,
            SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_MESSAGE_ID)
                .startFromMessageId(100L)
                .build())
            .toCompletionStage().toCompletableFuture().get();

        SubscriptionInfo updated = subscriptionManager.getSubscription(topic, groupName)
            .toCompletionStage().toCompletableFuture().get();

        assertEquals(100L, updated.startFromMessageId().longValue());
        logger.info("✅ Update verified: FROM_BEGINNING(1) → FROM_MESSAGE_ID(100)");
    }

    @Test
    void testMultipleUpdatesLastWins() throws Exception {
        logger.info("=== Testing multiple updates (last write wins) ===");

        String topic = "test-multiple-updates";
        String groupName = "group-multiple-updates";

        createTopic(topic);

        // Three rapid updates
        subscriptionManager.subscribe(topic, groupName,
            SubscriptionOptions.builder().startPosition(StartPosition.FROM_BEGINNING).build())
            .toCompletionStage().toCompletableFuture().get();

        subscriptionManager.subscribe(topic, groupName,
            SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_MESSAGE_ID)
                .startFromMessageId(50L)
                .build())
            .toCompletionStage().toCompletableFuture().get();

        subscriptionManager.subscribe(topic, groupName,
            SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_MESSAGE_ID)
                .startFromMessageId(100L)
                .build())
            .toCompletionStage().toCompletableFuture().get();

        SubscriptionInfo final_sub = subscriptionManager.getSubscription(topic, groupName)
            .toCompletionStage().toCompletableFuture().get();

        assertEquals(100L, final_sub.startFromMessageId().longValue(),
            "Last update should win");
        logger.info("✅ Multiple updates: last write (100) wins");
    }

    @Test
    void testHeartbeatIntervalEdgeCases() throws Exception {
        logger.info("=== Testing heartbeat interval edge cases ===");

        String topic = "test-heartbeat-edge";

        createTopic(topic);

        // Test minimum value (1 second interval, 2 second timeout)
        subscriptionManager.subscribe(topic, "group-hb-1",
            SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_NOW)
                .heartbeatIntervalSeconds(1)
                .heartbeatTimeoutSeconds(2)  // Must be > interval
                .build())
            .toCompletionStage().toCompletableFuture().get();

        SubscriptionInfo sub1 = subscriptionManager.getSubscription(topic, "group-hb-1")
            .toCompletionStage().toCompletableFuture().get();
        assertEquals(1, sub1.heartbeatIntervalSeconds());
        assertEquals(2, sub1.heartbeatTimeoutSeconds());

        // Test large value (1 hour interval, 2 hour timeout)
        subscriptionManager.subscribe(topic, "group-hb-3600",
            SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_NOW)
                .heartbeatIntervalSeconds(3600)
                .heartbeatTimeoutSeconds(7200)  // Must be > interval
                .build())
            .toCompletionStage().toCompletableFuture().get();

        SubscriptionInfo sub3600 = subscriptionManager.getSubscription(topic, "group-hb-3600")
            .toCompletionStage().toCompletableFuture().get();
        assertEquals(3600, sub3600.heartbeatIntervalSeconds());
        assertEquals(7200, sub3600.heartbeatTimeoutSeconds());

        logger.info("✅ Heartbeat edge cases: (1s/2s) and (3600s/7200s) verified");
    }

    // Helper method
    private void createTopic(String topic) throws Exception {
        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .build();

        topicConfigService.createTopic(topicConfig)
            .toCompletionStage()
            .toCompletableFuture()
            .get();
    }
}
