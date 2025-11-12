package dev.mars.peegeeq.db.subscription;

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

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for TopicConfigService.
 *
 * <p>This test validates the topic configuration management API including:
 * <ul>
 *   <li>Creating topic configurations</li>
 *   <li>Updating topic configurations</li>
 *   <li>Querying topic configurations</li>
 *   <li>Listing all topics</li>
 *   <li>Deleting topic configurations</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-12
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
public class TopicConfigServiceIntegrationTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(TopicConfigServiceIntegrationTest.class);

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

        topicConfigService = new TopicConfigService(connectionManager, "peegeeq-main");

        logger.info("TopicConfigService test setup complete");
    }
    
    @Test
    void testCreateTopicWithQueueSemantics() throws Exception {
        logger.info("=== Testing create topic with QUEUE semantics ===");
        
        String topicName = "test-queue-topic";
        
        TopicConfig config = TopicConfig.builder()
            .topic(topicName)
            .semantics(TopicSemantics.QUEUE)
            .messageRetentionHours(48)
            .build();
        
        topicConfigService.createTopic(config)
            .toCompletionStage()
            .toCompletableFuture()
            .get();
        
        // Verify topic was created
        TopicConfig retrieved = topicConfigService.getTopic(topicName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();
        
        assertNotNull(retrieved, "Topic should be created");
        assertEquals(topicName, retrieved.getTopic());
        assertEquals(TopicSemantics.QUEUE, retrieved.getSemantics());
        assertEquals(48, retrieved.getMessageRetentionHours());
        assertTrue(retrieved.isQueue());
        assertFalse(retrieved.isPubSub());
        
        logger.info("✅ Create topic with QUEUE semantics test passed");
    }
    
    @Test
    void testCreateTopicWithPubSubSemantics() throws Exception {
        logger.info("=== Testing create topic with PUB_SUB semantics ===");
        
        String topicName = "test-pubsub-topic";
        
        TopicConfig config = TopicConfig.builder()
            .topic(topicName)
            .semantics(TopicSemantics.PUB_SUB)
            .messageRetentionHours(24)
            .zeroSubscriptionRetentionHours(12)
            .blockWritesOnZeroSubscriptions(true)
            .build();
        
        topicConfigService.createTopic(config)
            .toCompletionStage()
            .toCompletableFuture()
            .get();
        
        // Verify topic was created
        TopicConfig retrieved = topicConfigService.getTopic(topicName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();
        
        assertNotNull(retrieved);
        assertEquals(topicName, retrieved.getTopic());
        assertEquals(TopicSemantics.PUB_SUB, retrieved.getSemantics());
        assertEquals(24, retrieved.getMessageRetentionHours());
        assertEquals(12, retrieved.getZeroSubscriptionRetentionHours());
        assertTrue(retrieved.isBlockWritesOnZeroSubscriptions());
        assertTrue(retrieved.isPubSub());
        assertFalse(retrieved.isQueue());
        
        logger.info("✅ Create topic with PUB_SUB semantics test passed");
    }
    
    @Test
    void testUpdateTopicConfiguration() throws Exception {
        logger.info("=== Testing update topic configuration ===");
        
        String topicName = "test-update-topic";
        
        // Create initial topic
        TopicConfig initialConfig = TopicConfig.builder()
            .topic(topicName)
            .semantics(TopicSemantics.QUEUE)
            .messageRetentionHours(24)
            .build();
        
        topicConfigService.createTopic(initialConfig)
            .toCompletionStage()
            .toCompletableFuture()
            .get();
        
        // Update topic configuration
        TopicConfig updatedConfig = TopicConfig.builder()
            .topic(topicName)
            .semantics(TopicSemantics.PUB_SUB)
            .messageRetentionHours(72)
            .zeroSubscriptionRetentionHours(6)
            .build();
        
        topicConfigService.updateTopic(updatedConfig)
            .toCompletionStage()
            .toCompletableFuture()
            .get();
        
        // Verify update
        TopicConfig retrieved = topicConfigService.getTopic(topicName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();
        
        assertEquals(TopicSemantics.PUB_SUB, retrieved.getSemantics());
        assertEquals(72, retrieved.getMessageRetentionHours());
        assertEquals(6, retrieved.getZeroSubscriptionRetentionHours());
        
        logger.info("✅ Update topic configuration test passed");
    }
    
    @Test
    void testListTopics() throws Exception {
        logger.info("=== Testing list topics ===");
        
        // Create multiple topics
        topicConfigService.createTopic(TopicConfig.builder()
            .topic("list-topic-1")
            .semantics(TopicSemantics.QUEUE)
            .build())
            .toCompletionStage()
            .toCompletableFuture()
            .get();
        
        topicConfigService.createTopic(TopicConfig.builder()
            .topic("list-topic-2")
            .semantics(TopicSemantics.PUB_SUB)
            .build())
            .toCompletionStage()
            .toCompletableFuture()
            .get();
        
        topicConfigService.createTopic(TopicConfig.builder()
            .topic("list-topic-3")
            .semantics(TopicSemantics.QUEUE)
            .build())
            .toCompletionStage()
            .toCompletableFuture()
            .get();
        
        // List all topics
        List<TopicConfig> topics = topicConfigService.listTopics()
            .toCompletionStage()
            .toCompletableFuture()
            .get();
        
        // Should have at least the 3 we just created (may have more from other tests)
        assertTrue(topics.size() >= 3, "Should have at least 3 topics");
        
        // Verify our topics are present
        assertTrue(topics.stream().anyMatch(t -> t.getTopic().equals("list-topic-1")));
        assertTrue(topics.stream().anyMatch(t -> t.getTopic().equals("list-topic-2")));
        assertTrue(topics.stream().anyMatch(t -> t.getTopic().equals("list-topic-3")));
        
        logger.info("✅ List topics test passed");
    }
    
    @Test
    void testDeleteTopic() throws Exception {
        logger.info("=== Testing delete topic ===");
        
        String topicName = "test-delete-topic";
        
        // Create topic
        TopicConfig config = TopicConfig.builder()
            .topic(topicName)
            .semantics(TopicSemantics.QUEUE)
            .build();
        
        topicConfigService.createTopic(config)
            .toCompletionStage()
            .toCompletableFuture()
            .get();
        
        // Verify it exists
        assertTrue(topicConfigService.topicExists(topicName)
            .toCompletionStage()
            .toCompletableFuture()
            .get());
        
        // Delete topic
        topicConfigService.deleteTopic(topicName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();
        
        // Verify it's deleted
        assertFalse(topicConfigService.topicExists(topicName)
            .toCompletionStage()
            .toCompletableFuture()
            .get());
        
        TopicConfig retrieved = topicConfigService.getTopic(topicName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();
        
        assertNull(retrieved, "Topic should be deleted");
        
        logger.info("✅ Delete topic test passed");
    }
    
    @Test
    void testTopicExists() throws Exception {
        logger.info("=== Testing topic exists ===");
        
        String existingTopic = "test-exists-topic";
        String nonExistingTopic = "test-nonexisting-topic";
        
        // Create one topic
        TopicConfig config = TopicConfig.builder()
            .topic(existingTopic)
            .semantics(TopicSemantics.QUEUE)
            .build();
        
        topicConfigService.createTopic(config)
            .toCompletionStage()
            .toCompletableFuture()
            .get();
        
        // Check existence
        assertTrue(topicConfigService.topicExists(existingTopic)
            .toCompletionStage()
            .toCompletableFuture()
            .get(), "Existing topic should return true");
        
        assertFalse(topicConfigService.topicExists(nonExistingTopic)
            .toCompletionStage()
            .toCompletableFuture()
            .get(), "Non-existing topic should return false");
        
        logger.info("✅ Topic exists test passed");
    }
    
    @Test
    void testCreateTopicIdempotent() throws Exception {
        logger.info("=== Testing create topic is idempotent ===");
        
        String topicName = "test-idempotent-topic";
        
        TopicConfig config = TopicConfig.builder()
            .topic(topicName)
            .semantics(TopicSemantics.QUEUE)
            .messageRetentionHours(24)
            .build();
        
        // Create topic first time
        topicConfigService.createTopic(config)
            .toCompletionStage()
            .toCompletableFuture()
            .get();
        
        // Create same topic again (should update, not fail)
        TopicConfig updatedConfig = TopicConfig.builder()
            .topic(topicName)
            .semantics(TopicSemantics.PUB_SUB)
            .messageRetentionHours(48)
            .build();
        
        topicConfigService.createTopic(updatedConfig)
            .toCompletionStage()
            .toCompletableFuture()
            .get();
        
        // Verify it was updated
        TopicConfig retrieved = topicConfigService.getTopic(topicName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();
        
        assertEquals(TopicSemantics.PUB_SUB, retrieved.getSemantics());
        assertEquals(48, retrieved.getMessageRetentionHours());
        
        logger.info("✅ Create topic idempotent test passed");
    }
}

