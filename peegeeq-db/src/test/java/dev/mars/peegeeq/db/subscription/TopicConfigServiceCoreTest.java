package dev.mars.peegeeq.db.subscription;

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
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger logger = LoggerFactory.getLogger(TopicConfigServiceCoreTest.class);

    private PgConnectionManager connectionManager;
    private TopicConfigService topicConfigService;

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
            .maxSize(3)
            .shared(false)
            .idleTimeout(Duration.ofSeconds(2))
            .connectionTimeout(Duration.ofSeconds(5))
            .build();

        connectionManager.getOrCreateReactivePool("peegeeq-main", connectionConfig, poolConfig);

        // Create service
        topicConfigService = new TopicConfigService(connectionManager, "peegeeq-main");
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
            .compose(v -> topicConfigService.getTopic(topic))
            .onComplete(testContext.succeeding(retrieved -> testContext.verify(() -> {
                assertNotNull(retrieved);
                assertEquals(topic, retrieved.getTopic());
                assertEquals(TopicSemantics.QUEUE, retrieved.getSemantics());
                assertEquals(24, retrieved.getMessageRetentionHours());
                assertEquals(1, retrieved.getZeroSubscriptionRetentionHours());
                assertFalse(retrieved.isBlockWritesOnZeroSubscriptions());
                assertEquals("REFERENCE_COUNTING", retrieved.getCompletionTrackingMode());
                assertNotNull(retrieved.getCreatedAt());
                assertNotNull(retrieved.getUpdatedAt());
                testContext.completeNow();
            })));
    }

    @Test
    void testCreateTopicWithPubSubSemantics(VertxTestContext testContext) {
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
            .compose(v -> topicConfigService.getTopic(topic))
            .onComplete(testContext.succeeding(retrieved -> testContext.verify(() -> {
                assertNotNull(retrieved);
                assertEquals(topic, retrieved.getTopic());
                assertEquals(TopicSemantics.PUB_SUB, retrieved.getSemantics());
                assertEquals(48, retrieved.getMessageRetentionHours());
                assertEquals(2, retrieved.getZeroSubscriptionRetentionHours());
                assertTrue(retrieved.isBlockWritesOnZeroSubscriptions());
                assertEquals("OFFSET_WATERMARK", retrieved.getCompletionTrackingMode());
                testContext.completeNow();
            })));
    }

    @Test
    void testCreateTopicUpsert(VertxTestContext testContext) {
        String topic = "test-topic-upsert";

        TopicConfig config1 = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.QUEUE)
            .messageRetentionHours(24)
            .build();

        TopicConfig config2 = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .messageRetentionHours(48)
            .build();

        topicConfigService.createTopic(config1)
            .compose(v -> topicConfigService.createTopic(config2))
            .compose(v -> topicConfigService.getTopic(topic))
            .onComplete(testContext.succeeding(retrieved -> testContext.verify(() -> {
                assertNotNull(retrieved);
                assertEquals(TopicSemantics.PUB_SUB, retrieved.getSemantics());
                assertEquals(48, retrieved.getMessageRetentionHours());
                testContext.completeNow();
            })));
    }

    @Test
    void testUpdateTopic(VertxTestContext testContext) {
        String topic = "test-topic-update";

        TopicConfig initialConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.QUEUE)
            .messageRetentionHours(24)
            .build();

        TopicConfig updatedConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .messageRetentionHours(72)
            .zeroSubscriptionRetentionHours(3)
            .blockWritesOnZeroSubscriptions(true)
            .completionTrackingMode("OFFSET_WATERMARK")
            .build();

        topicConfigService.createTopic(initialConfig)
            .compose(v -> topicConfigService.updateTopic(updatedConfig))
            .compose(v -> topicConfigService.getTopic(topic))
            .onComplete(testContext.succeeding(retrieved -> testContext.verify(() -> {
                assertNotNull(retrieved);
                assertEquals(TopicSemantics.PUB_SUB, retrieved.getSemantics());
                assertEquals(72, retrieved.getMessageRetentionHours());
                assertEquals(3, retrieved.getZeroSubscriptionRetentionHours());
                assertTrue(retrieved.isBlockWritesOnZeroSubscriptions());
                assertEquals("OFFSET_WATERMARK", retrieved.getCompletionTrackingMode());
                testContext.completeNow();
            })));
    }

    /**
     * Verifies that {@link TopicConfigService#updateTopic(TopicConfig)} returns a failed
     * {@code Future} with {@link IllegalStateException} when the topic does not exist,
     * logging an ERROR.
     *
     * <p><strong>INTENTIONAL ERROR TEST:</strong> The next ERROR log
     * ('Topic configuration not found: \'test-topic-nonexistent\'') is EXPECTED —
     * this test deliberately updates a topic that was never created to verify the not-found error path.
     */
    @Test
    void testUpdateNonExistentTopic(VertxTestContext testContext) {
        String topic = "test-topic-nonexistent";
        logger.error("===== INTENTIONAL ERROR TEST ===== The next ERROR log ('Topic configuration not found: test-topic-nonexistent') is EXPECTED this test deliberately updates a topic that was never created to verify the not-found error path");

        TopicConfig config = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.QUEUE)
            .build();

        topicConfigService.updateTopic(config)
            .onSuccess(v -> testContext.failNow(new AssertionError("Expected failure but update succeeded")))
            .onFailure(t -> testContext.verify(() -> {
                assertInstanceOf(IllegalStateException.class, t);
                assertTrue(t.getMessage().contains("not found"));
                testContext.completeNow();
            }));
    }

    @Test
    void testGetNonExistentTopic(VertxTestContext testContext) {
        String topic = "test-topic-does-not-exist";

        topicConfigService.getTopic(topic)
            .onComplete(testContext.succeeding(retrieved -> testContext.verify(() -> {
                assertNull(retrieved);
                testContext.completeNow();
            })));
    }

    @Test
    void testListTopics(VertxTestContext testContext) {
        TopicConfig config1 = TopicConfig.builder()
            .topic("test-topic-list-1")
            .semantics(TopicSemantics.QUEUE)
            .build();

        TopicConfig config2 = TopicConfig.builder()
            .topic("test-topic-list-2")
            .semantics(TopicSemantics.PUB_SUB)
            .build();

        topicConfigService.createTopic(config1)
            .compose(v -> topicConfigService.createTopic(config2))
            .compose(v -> topicConfigService.listTopics())
            .onComplete(testContext.succeeding(topics -> testContext.verify(() -> {
                assertNotNull(topics);
                assertTrue(topics.size() >= 2, "Should have at least 2 topics");
                assertTrue(topics.stream().anyMatch(tc -> tc.getTopic().equals("test-topic-list-1")),
                    "Should contain test-topic-list-1");
                assertTrue(topics.stream().anyMatch(tc -> tc.getTopic().equals("test-topic-list-2")),
                    "Should contain test-topic-list-2");
                testContext.completeNow();
            })));
    }

    @Test
    void testDeleteTopic(VertxTestContext testContext) {
        String topic = "test-topic-delete";

        TopicConfig config = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.QUEUE)
            .build();

        topicConfigService.createTopic(config)
            .compose(v -> topicConfigService.getTopic(topic))
            .compose(retrieved -> {
                assertNotNull(retrieved);
                return topicConfigService.deleteTopic(topic);
            })
            .compose(v -> topicConfigService.getTopic(topic))
            .onComplete(testContext.succeeding(afterDelete -> testContext.verify(() -> {
                assertNull(afterDelete);
                testContext.completeNow();
            })));
    }

    /**
     * Verifies that {@link TopicConfigService#deleteTopic(String)} succeeds silently
     * (no error thrown) when the topic does not exist, while logging a WARN.
     *
     * <p><strong>INTENTIONAL WARN TEST:</strong> The next WARN log
     * ('Topic configuration not found for deletion: \'test-topic-delete-nonexistent\'') is EXPECTED —
     * this test deliberately deletes a non-existent topic to verify idempotent delete behaviour.
     */
    @Test
    void testDeleteNonExistentTopic(VertxTestContext testContext) {
        String topic = "test-topic-delete-nonexistent";
        logger.warn("===== INTENTIONAL WARN TEST ===== The next WARN log ('Topic configuration not found for deletion: test-topic-delete-nonexistent') is EXPECTED this test deliberately deletes a non-existent topic to verify idempotent delete handling");

        topicConfigService.deleteTopic(topic)
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testTopicExists(VertxTestContext testContext) {
        String topic = "test-topic-exists";

        TopicConfig config = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.QUEUE)
            .build();

        topicConfigService.topicExists(topic)
            .compose(existsBefore -> {
                assertFalse(existsBefore);
                return topicConfigService.createTopic(config);
            })
            .compose(v -> topicConfigService.topicExists(topic))
            .onComplete(testContext.succeeding(existsAfter -> testContext.verify(() -> {
                assertTrue(existsAfter);
                testContext.completeNow();
            })));
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

