package dev.mars.peegeeq.db.subscription;

import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.UUID;

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
        PostgreSQLContainer postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema(PostgreSQLTestConstants.TEST_SCHEMA)
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
            .maxSize(3)
            .shared(false)
            .idleTimeout(Duration.ofSeconds(2))
            .connectionTimeout(Duration.ofSeconds(5))
            .build();

        connectionManager.getOrCreateReactivePool("peegeeq-main", connectionConfig, poolConfig);

        topicConfigService = new TopicConfigService(connectionManager, "peegeeq-main");

        logger.info("TopicConfigService test setup complete");
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        if (connectionManager != null) {
            connectionManager.close().onSuccess(v -> testContext.completeNow()).onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }
    }
    
    @Test
    void testCreateTopicWithQueueSemantics(VertxTestContext testContext) {
        String topicName = "test-queue-topic-" + UUID.randomUUID().toString().substring(0, 8);

        TopicConfig config = TopicConfig.builder()
            .topic(topicName)
            .semantics(TopicSemantics.QUEUE)
            .messageRetentionHours(48)
            .build();

        topicConfigService.createTopic(config)
            .compose(v -> topicConfigService.getTopic(topicName))
            .onComplete(testContext.succeeding(retrieved -> testContext.verify(() -> {
                assertNotNull(retrieved, "Topic should be created");
                assertEquals(topicName, retrieved.getTopic());
                assertEquals(TopicSemantics.QUEUE, retrieved.getSemantics());
                assertEquals(48, retrieved.getMessageRetentionHours());
                assertTrue(retrieved.isQueue());
                assertFalse(retrieved.isPubSub());
                testContext.completeNow();
            })));
    }
    
    @Test
    void testCreateTopicWithPubSubSemantics(VertxTestContext testContext) {
        String topicName = "test-pubsub-topic-" + UUID.randomUUID().toString().substring(0, 8);

        TopicConfig config = TopicConfig.builder()
            .topic(topicName)
            .semantics(TopicSemantics.PUB_SUB)
            .messageRetentionHours(24)
            .zeroSubscriptionRetentionHours(12)
            .blockWritesOnZeroSubscriptions(true)
            .build();

        topicConfigService.createTopic(config)
            .compose(v -> topicConfigService.getTopic(topicName))
            .onComplete(testContext.succeeding(retrieved -> testContext.verify(() -> {
                assertNotNull(retrieved);
                assertEquals(topicName, retrieved.getTopic());
                assertEquals(TopicSemantics.PUB_SUB, retrieved.getSemantics());
                assertEquals(24, retrieved.getMessageRetentionHours());
                assertEquals(12, retrieved.getZeroSubscriptionRetentionHours());
                assertTrue(retrieved.isBlockWritesOnZeroSubscriptions());
                assertTrue(retrieved.isPubSub());
                assertFalse(retrieved.isQueue());
                testContext.completeNow();
            })));
    }
    
    @Test
    void testUpdateTopicConfiguration(VertxTestContext testContext) {
        String topicName = "test-update-topic-" + UUID.randomUUID().toString().substring(0, 8);

        TopicConfig initialConfig = TopicConfig.builder()
            .topic(topicName)
            .semantics(TopicSemantics.QUEUE)
            .messageRetentionHours(24)
            .build();

        TopicConfig updatedConfig = TopicConfig.builder()
            .topic(topicName)
            .semantics(TopicSemantics.PUB_SUB)
            .messageRetentionHours(72)
            .zeroSubscriptionRetentionHours(6)
            .build();

        topicConfigService.createTopic(initialConfig)
            .compose(v -> topicConfigService.updateTopic(updatedConfig))
            .compose(v -> topicConfigService.getTopic(topicName))
            .onComplete(testContext.succeeding(retrieved -> testContext.verify(() -> {
                assertEquals(TopicSemantics.PUB_SUB, retrieved.getSemantics());
                assertEquals(72, retrieved.getMessageRetentionHours());
                assertEquals(6, retrieved.getZeroSubscriptionRetentionHours());
                testContext.completeNow();
            })));
    }
    
    @Test
    void testListTopics(VertxTestContext testContext) {
        topicConfigService.createTopic(TopicConfig.builder()
                .topic("list-topic-1").semantics(TopicSemantics.QUEUE).build())
            .compose(v -> topicConfigService.createTopic(TopicConfig.builder()
                .topic("list-topic-2").semantics(TopicSemantics.PUB_SUB).build()))
            .compose(v -> topicConfigService.createTopic(TopicConfig.builder()
                .topic("list-topic-3").semantics(TopicSemantics.QUEUE).build()))
            .compose(v -> topicConfigService.listTopics())
            .onComplete(testContext.succeeding(topics -> testContext.verify(() -> {
                assertTrue(topics.size() >= 3, "Should have at least 3 topics");
                assertTrue(topics.stream().anyMatch(tc -> tc.getTopic().equals("list-topic-1")));
                assertTrue(topics.stream().anyMatch(tc -> tc.getTopic().equals("list-topic-2")));
                assertTrue(topics.stream().anyMatch(tc -> tc.getTopic().equals("list-topic-3")));
                testContext.completeNow();
            })));
    }
    
    @Test
    void testDeleteTopic(VertxTestContext testContext) {
        String topicName = "test-delete-topic-" + UUID.randomUUID().toString().substring(0, 8);

        TopicConfig config = TopicConfig.builder()
            .topic(topicName)
            .semantics(TopicSemantics.QUEUE)
            .build();

        topicConfigService.createTopic(config)
            .compose(v -> topicConfigService.topicExists(topicName))
            .compose(exists -> {
                assertTrue(exists, "Topic should exist after creation");
                return topicConfigService.deleteTopic(topicName);
            })
            .compose(v -> topicConfigService.topicExists(topicName))
            .compose(existsAfterDelete -> {
                assertFalse(existsAfterDelete, "Topic should not exist after deletion");
                return topicConfigService.getTopic(topicName);
            })
            .onComplete(testContext.succeeding(retrieved -> testContext.verify(() -> {
                assertNull(retrieved, "Topic should be deleted");
                testContext.completeNow();
            })));
    }
    
    @Test
    void testTopicExists(VertxTestContext testContext) {
        String existingTopic = "test-exists-topic";
        String nonExistingTopic = "test-nonexisting-topic";

        TopicConfig config = TopicConfig.builder()
            .topic(existingTopic)
            .semantics(TopicSemantics.QUEUE)
            .build();

        topicConfigService.createTopic(config)
            .compose(v -> topicConfigService.topicExists(existingTopic))
            .compose(existsForCreated -> {
                assertTrue(existsForCreated, "Existing topic should return true");
                return topicConfigService.topicExists(nonExistingTopic);
            })
            .onComplete(testContext.succeeding(existsForNonExisting -> testContext.verify(() -> {
                assertFalse(existsForNonExisting, "Non-existing topic should return false");
                testContext.completeNow();
            })));
    }
    
    @Test
    void testCreateTopicIdempotent(VertxTestContext testContext) {
        String topicName = "test-idempotent-topic-" + UUID.randomUUID().toString().substring(0, 8);

        TopicConfig initialConfig = TopicConfig.builder()
            .topic(topicName)
            .semantics(TopicSemantics.QUEUE)
            .messageRetentionHours(24)
            .build();

        TopicConfig updatedConfig = TopicConfig.builder()
            .topic(topicName)
            .semantics(TopicSemantics.PUB_SUB)
            .messageRetentionHours(48)
            .build();

        topicConfigService.createTopic(initialConfig)
            .compose(v -> topicConfigService.createTopic(updatedConfig))
            .compose(v -> topicConfigService.getTopic(topicName))
            .onComplete(testContext.succeeding(retrieved -> testContext.verify(() -> {
                assertEquals(TopicSemantics.PUB_SUB, retrieved.getSemantics());
                assertEquals(48, retrieved.getMessageRetentionHours());
                testContext.completeNow();
            })));
    }
}

