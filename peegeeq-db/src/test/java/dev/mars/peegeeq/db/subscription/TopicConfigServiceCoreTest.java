package dev.mars.peegeeq.db.subscription;

import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CORE tests for TopicConfigService using TestContainers.
 *
 * <p>These tests are tagged as CORE because they:
 * <ul>
 *   <li>Run fast (each test completes in <1 second)</li>
 *   <li>Are isolated (each test focuses on a single method)</li>
 *   <li>Test one component at a time (TopicConfigService only)</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-27
 * @version 1.0
 */
@Tag(TestCategories.CORE)
public class TopicConfigServiceCoreTest extends BaseIntegrationTest {

    private PgConnectionManager connectionManager;
    private TopicConfigService topicConfigService;

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

        // Create service
        topicConfigService = new TopicConfigService(connectionManager, "peegeeq-main");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connectionManager != null) {
            connectionManager.close();
        }
    }

    @Test
    void testCreateTopicWithQueueSemantics() throws Exception {
        String topic = "test-topic-queue";

        TopicConfig config = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.QUEUE)
            .messageRetentionHours(24)
            .zeroSubscriptionRetentionHours(1)
            .blockWritesOnZeroSubscriptions(false)
            .completionTrackingMode("REFERENCE_COUNTING")
            .build();

        topicConfigService.createTopic(config)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        // Verify topic was created
        TopicConfig retrieved = topicConfigService.getTopic(topic)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        assertNotNull(retrieved);
        assertEquals(topic, retrieved.getTopic());
        assertEquals(TopicSemantics.QUEUE, retrieved.getSemantics());
        assertEquals(24, retrieved.getMessageRetentionHours());
        assertEquals(1, retrieved.getZeroSubscriptionRetentionHours());
        assertFalse(retrieved.isBlockWritesOnZeroSubscriptions());
        assertEquals("REFERENCE_COUNTING", retrieved.getCompletionTrackingMode());
        assertNotNull(retrieved.getCreatedAt());
        assertNotNull(retrieved.getUpdatedAt());
    }

    @Test
    void testCreateTopicWithPubSubSemantics() throws Exception {
        String topic = "test-topic-pubsub";

        TopicConfig config = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .messageRetentionHours(48)
            .zeroSubscriptionRetentionHours(2)
            .blockWritesOnZeroSubscriptions(true)
            .completionTrackingMode("OFFSET_WATERMARK")
            .build();

        topicConfigService.createTopic(config)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        // Verify topic was created
        TopicConfig retrieved = topicConfigService.getTopic(topic)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        assertNotNull(retrieved);
        assertEquals(topic, retrieved.getTopic());
        assertEquals(TopicSemantics.PUB_SUB, retrieved.getSemantics());
        assertEquals(48, retrieved.getMessageRetentionHours());
        assertEquals(2, retrieved.getZeroSubscriptionRetentionHours());
        assertTrue(retrieved.isBlockWritesOnZeroSubscriptions());
        assertEquals("OFFSET_WATERMARK", retrieved.getCompletionTrackingMode());
    }

    @Test
    void testCreateTopicUpsert() throws Exception {
        String topic = "test-topic-upsert";

        // Create initial topic
        TopicConfig config1 = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.QUEUE)
            .messageRetentionHours(24)
            .build();

        topicConfigService.createTopic(config1)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        // Create again with different settings (should upsert)
        TopicConfig config2 = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .messageRetentionHours(48)
            .build();

        topicConfigService.createTopic(config2)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        // Verify topic was updated
        TopicConfig retrieved = topicConfigService.getTopic(topic)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        assertNotNull(retrieved);
        assertEquals(TopicSemantics.PUB_SUB, retrieved.getSemantics());
        assertEquals(48, retrieved.getMessageRetentionHours());
    }

    @Test
    void testUpdateTopic() throws Exception {
        String topic = "test-topic-update";

        // Create initial topic
        TopicConfig config = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.QUEUE)
            .messageRetentionHours(24)
            .build();

        topicConfigService.createTopic(config)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        // Update topic
        TopicConfig updatedConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .messageRetentionHours(72)
            .zeroSubscriptionRetentionHours(3)
            .blockWritesOnZeroSubscriptions(true)
            .completionTrackingMode("OFFSET_WATERMARK")
            .build();

        topicConfigService.updateTopic(updatedConfig)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        // Verify update
        TopicConfig retrieved = topicConfigService.getTopic(topic)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        assertNotNull(retrieved);
        assertEquals(TopicSemantics.PUB_SUB, retrieved.getSemantics());
        assertEquals(72, retrieved.getMessageRetentionHours());
        assertEquals(3, retrieved.getZeroSubscriptionRetentionHours());
        assertTrue(retrieved.isBlockWritesOnZeroSubscriptions());
        assertEquals("OFFSET_WATERMARK", retrieved.getCompletionTrackingMode());
    }

    @Test
    void testUpdateNonExistentTopic() throws Exception {
        String topic = "test-topic-nonexistent";

        TopicConfig config = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.QUEUE)
            .build();

        // Should fail because topic doesn't exist
        Exception exception = assertThrows(Exception.class, () -> {
            topicConfigService.updateTopic(config)
                .toCompletionStage()
                .toCompletableFuture()
                .get();
        });

        assertTrue(exception.getCause() instanceof IllegalStateException);
        assertTrue(exception.getCause().getMessage().contains("not found"));
    }

    @Test
    void testGetNonExistentTopic() throws Exception {
        String topic = "test-topic-does-not-exist";

        TopicConfig retrieved = topicConfigService.getTopic(topic)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        assertNull(retrieved);
    }

    @Test
    void testListTopics() throws Exception {
        // Create multiple topics
        TopicConfig config1 = TopicConfig.builder()
            .topic("test-topic-list-1")
            .semantics(TopicSemantics.QUEUE)
            .build();

        TopicConfig config2 = TopicConfig.builder()
            .topic("test-topic-list-2")
            .semantics(TopicSemantics.PUB_SUB)
            .build();

        topicConfigService.createTopic(config1)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        topicConfigService.createTopic(config2)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        // List all topics
        List<TopicConfig> topics = topicConfigService.listTopics()
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        assertNotNull(topics);
        assertTrue(topics.size() >= 2, "Should have at least 2 topics");

        // Verify our topics are present
        boolean hasTopic1 = topics.stream()
            .anyMatch(t -> t.getTopic().equals("test-topic-list-1"));
        boolean hasTopic2 = topics.stream()
            .anyMatch(t -> t.getTopic().equals("test-topic-list-2"));

        assertTrue(hasTopic1, "Should contain test-topic-list-1");
        assertTrue(hasTopic2, "Should contain test-topic-list-2");
    }

    @Test
    void testDeleteTopic() throws Exception {
        String topic = "test-topic-delete";

        // Create topic
        TopicConfig config = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.QUEUE)
            .build();

        topicConfigService.createTopic(config)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        // Verify it exists
        TopicConfig retrieved = topicConfigService.getTopic(topic)
            .toCompletionStage()
            .toCompletableFuture()
            .get();
        assertNotNull(retrieved);

        // Delete topic
        topicConfigService.deleteTopic(topic)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        // Verify it's gone
        TopicConfig afterDelete = topicConfigService.getTopic(topic)
            .toCompletionStage()
            .toCompletableFuture()
            .get();
        assertNull(afterDelete);
    }

    @Test
    void testDeleteNonExistentTopic() throws Exception {
        String topic = "test-topic-delete-nonexistent";

        // Should not throw exception, just log warning
        topicConfigService.deleteTopic(topic)
            .toCompletionStage()
            .toCompletableFuture()
            .get();
    }

    @Test
    void testTopicExists() throws Exception {
        String topic = "test-topic-exists";

        // Should not exist initially
        Boolean existsBefore = topicConfigService.topicExists(topic)
            .toCompletionStage()
            .toCompletableFuture()
            .get();
        assertFalse(existsBefore);

        // Create topic
        TopicConfig config = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.QUEUE)
            .build();

        topicConfigService.createTopic(config)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        // Should exist now
        Boolean existsAfter = topicConfigService.topicExists(topic)
            .toCompletionStage()
            .toCompletableFuture()
            .get();
        assertTrue(existsAfter);
    }

    @Test
    void testCreateTopicRequiresNonNullConfig() {
        assertThrows(NullPointerException.class, () -> {
            topicConfigService.createTopic(null);
        });
    }

    @Test
    void testUpdateTopicRequiresNonNullConfig() {
        assertThrows(NullPointerException.class, () -> {
            topicConfigService.updateTopic(null);
        });
    }

    @Test
    void testGetTopicRequiresNonNullTopic() {
        assertThrows(NullPointerException.class, () -> {
            topicConfigService.getTopic(null);
        });
    }

    @Test
    void testDeleteTopicRequiresNonNullTopic() {
        assertThrows(NullPointerException.class, () -> {
            topicConfigService.deleteTopic(null);
        });
    }

    @Test
    void testTopicExistsRequiresNonNullTopic() {
        assertThrows(NullPointerException.class, () -> {
            topicConfigService.topicExists(null);
        });
    }
}

