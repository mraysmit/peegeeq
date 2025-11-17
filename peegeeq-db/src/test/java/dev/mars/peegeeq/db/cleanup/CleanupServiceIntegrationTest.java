package dev.mars.peegeeq.db.cleanup;

import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
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

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for CleanupService.
 * 
 * <p>Tests the cleanup logic for completed messages including:
 * <ul>
 *   <li>Cleanup of completed messages for QUEUE topics</li>
 *   <li>Cleanup of completed messages for PUB_SUB topics</li>
 *   <li>Cleanup respecting message retention hours</li>
 *   <li>Cleanup of zero-subscription messages</li>
 *   <li>Batch cleanup across all topics</li>
 *   <li>Counting eligible messages</li>
 * </ul>
 * </p>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-12
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
public class CleanupServiceIntegrationTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(CleanupServiceIntegrationTest.class);

    private PgConnectionManager connectionManager;
    private CleanupService cleanupService;
    private TopicConfigService topicConfigService;
    private SubscriptionManager subscriptionManager;

    @BeforeEach
    public void setUp() throws Exception {
        super.setUpBaseIntegration();

        // Create connection manager using the shared Vertx instance
        connectionManager = new PgConnectionManager(manager.getVertx(), null);

        // Get PostgreSQL container and create pool
        var postgres = getPostgres();
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

        cleanupService = new CleanupService(connectionManager, "peegeeq-main");
        topicConfigService = new TopicConfigService(connectionManager, "peegeeq-main");
        subscriptionManager = new SubscriptionManager(connectionManager, "peegeeq-main");

        logger.info("CleanupService test setup complete");
    }

    @Test
    public void testCleanupCompletedQueueMessages() throws Exception {
        logger.info("=== TEST: testCleanupCompletedQueueMessages STARTED ===");

        String topic = "test-cleanup-queue";

        // Create topic with QUEUE semantics
        TopicConfig topicConfig = TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.QUEUE)
                .messageRetentionHours(1) // 1 hour retention for testing
                .build();

        CountDownLatch latch = new CountDownLatch(1);

        topicConfigService.createTopic(topicConfig)
                .compose(v -> {
                    // Insert a completed message (trigger will set required_consumer_groups=1 for QUEUE)
                    String sql = """
                        INSERT INTO outbox (topic, payload, status, created_at, processed_at)
                        VALUES ($1, $2, 'COMPLETED', $3, $4)
                        RETURNING id
                        """;
                    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
                    OffsetDateTime past = now.minusHours(2); // 2 hours ago
                    Tuple params = Tuple.of(topic, new JsonObject().put("test", "data"), past, past);

                    return connectionManager.withConnection("peegeeq-main", connection ->
                            connection.preparedQuery(sql).execute(params)
                    );
                })
                .compose(v -> {
                    // Run cleanup
                    return cleanupService.cleanupCompletedMessages(topic, 100);
                })
                .compose(deletedCount -> {
                    assertEquals(1, deletedCount, "Should delete 1 completed message");
                    return Future.succeededFuture();
                })
                .onSuccess(v -> latch.countDown())
                .onFailure(error -> {
                    logger.error("Test failed", error);
                    latch.countDown();
                    fail("Test failed: " + error.getMessage());
                });

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Test should complete within 10 seconds");
        logger.info("=== TEST: testCleanupCompletedQueueMessages PASSED ===");
    }

    @Test
    public void testCleanupCompletedPubSubMessages() throws Exception {
        logger.info("=== TEST: testCleanupCompletedPubSubMessages STARTED ===");

        String topic = "test-cleanup-pubsub";
        String group1 = "group1";
        String group2 = "group2";

        // Create topic with PUB_SUB semantics
        TopicConfig topicConfig = TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.PUB_SUB)
                .messageRetentionHours(1) // 1 hour retention for testing
                .build();

        CountDownLatch latch = new CountDownLatch(1);

        topicConfigService.createTopic(topicConfig)
                .compose(v -> {
                    // Subscribe two groups
                    SubscriptionOptions options = SubscriptionOptions.builder().build();
                    return subscriptionManager.subscribe(topic, group1, options);
                })
                .compose(v -> {
                    SubscriptionOptions options = SubscriptionOptions.builder().build();
                    return subscriptionManager.subscribe(topic, group2, options);
                })
                .compose(v -> {
                    // Insert a message (trigger will set required_consumer_groups=2 for PUB_SUB with 2 subscriptions)
                    String sql = """
                        INSERT INTO outbox (topic, payload, status, created_at, processed_at)
                        VALUES ($1, $2, 'COMPLETED', $3, $4)
                        RETURNING id
                        """;
                    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
                    OffsetDateTime past = now.minusHours(2); // 2 hours ago
                    Tuple params = Tuple.of(topic, new JsonObject().put("test", "data"), past, past);

                    return connectionManager.withConnection("peegeeq-main", connection ->
                            connection.preparedQuery(sql).execute(params)
                    );
                })
                .compose(result -> {
                    // Mark both groups as completed
                    String sql = """
                        UPDATE outbox
                        SET completed_consumer_groups = 2
                        WHERE topic = $1
                        """;
                    return connectionManager.withConnection("peegeeq-main", connection ->
                            connection.preparedQuery(sql).execute(Tuple.of(topic))
                    );
                })
                .compose(result -> {
                    // Run cleanup
                    return cleanupService.cleanupCompletedMessages(topic, 100);
                })
                .compose(deletedCount -> {
                    assertEquals(1, deletedCount, "Should delete 1 completed message");
                    return Future.succeededFuture();
                })
                .onSuccess(v -> latch.countDown())
                .onFailure(error -> {
                    logger.error("Test failed", error);
                    latch.countDown();
                    fail("Test failed: " + error.getMessage());
                });

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Test should complete within 10 seconds");
        logger.info("=== TEST: testCleanupCompletedPubSubMessages PASSED ===");
    }

    @Test
    public void testCleanupRespectsRetentionHours() throws Exception {
        logger.info("=== TEST: testCleanupRespectsRetentionHours STARTED ===");

        String topic = "test-cleanup-retention";

        // Create topic with 24 hour retention
        TopicConfig topicConfig = TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.QUEUE)
                .messageRetentionHours(24)
                .build();

        CountDownLatch latch = new CountDownLatch(1);

        topicConfigService.createTopic(topicConfig)
                .compose(v -> {
                    // Insert a completed message processed 1 hour ago (should NOT be deleted)
                    String sql = """
                        INSERT INTO outbox (topic, payload, status, created_at, processed_at)
                        VALUES ($1, $2, 'COMPLETED', $3, $4)
                        RETURNING id
                        """;
                    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
                    OffsetDateTime oneHourAgo = now.minusHours(1);
                    Tuple params = Tuple.of(topic, new JsonObject().put("test", "data"), oneHourAgo, oneHourAgo);

                    return connectionManager.withConnection("peegeeq-main", connection ->
                            connection.preparedQuery(sql).execute(params)
                    );
                })
                .compose(result -> {
                    // Run cleanup
                    return cleanupService.cleanupCompletedMessages(topic, 100);
                })
                .compose(deletedCount -> {
                    assertEquals(0, deletedCount, "Should NOT delete message within retention period");
                    return Future.succeededFuture();
                })
                .onSuccess(v -> latch.countDown())
                .onFailure(error -> {
                    logger.error("Test failed", error);
                    latch.countDown();
                    fail("Test failed: " + error.getMessage());
                });

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Test should complete within 10 seconds");
        logger.info("=== TEST: testCleanupRespectsRetentionHours PASSED ===");
    }

    @Test
    public void testCleanupDoesNotDeleteIncompleteMessages() throws Exception {
        logger.info("=== TEST: testCleanupDoesNotDeleteIncompleteMessages STARTED ===");

        String topic = "test-cleanup-incomplete";
        String group1 = "group1";
        String group2 = "group2";

        // Create topic with PUB_SUB semantics
        TopicConfig topicConfig = TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.PUB_SUB)
                .messageRetentionHours(1) // 1 hour retention for testing
                .build();

        CountDownLatch latch = new CountDownLatch(1);

        topicConfigService.createTopic(topicConfig)
                .compose(v -> {
                    // Subscribe two groups
                    SubscriptionOptions options = SubscriptionOptions.builder().build();
                    return subscriptionManager.subscribe(topic, group1, options);
                })
                .compose(v -> {
                    SubscriptionOptions options = SubscriptionOptions.builder().build();
                    return subscriptionManager.subscribe(topic, group2, options);
                })
                .compose(v -> {
                    // Insert a message with PENDING status (trigger will set required_consumer_groups=2)
                    String sql = """
                        INSERT INTO outbox (topic, payload, status, created_at)
                        VALUES ($1, $2, 'PENDING', $3)
                        RETURNING id
                        """;
                    OffsetDateTime past = OffsetDateTime.now(ZoneOffset.UTC).minusHours(2);
                    Tuple params = Tuple.of(topic, new JsonObject().put("test", "data"), past);

                    return connectionManager.withConnection("peegeeq-main", connection ->
                            connection.preparedQuery(sql).execute(params)
                    );
                })
                .compose(result -> {
                    // Mark only 1 of 2 groups as completed
                    String sql = """
                        UPDATE outbox
                        SET completed_consumer_groups = 1
                        WHERE topic = $1
                        """;
                    return connectionManager.withConnection("peegeeq-main", connection ->
                            connection.preparedQuery(sql).execute(Tuple.of(topic))
                    );
                })
                .compose(result -> {
                    // Run cleanup
                    return cleanupService.cleanupCompletedMessages(topic, 100);
                })
                .compose(deletedCount -> {
                    assertEquals(0, deletedCount, "Should NOT delete incomplete message");
                    return Future.succeededFuture();
                })
                .onSuccess(v -> latch.countDown())
                .onFailure(error -> {
                    logger.error("Test failed", error);
                    latch.countDown();
                    fail("Test failed: " + error.getMessage());
                });

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Test should complete within 10 seconds");
        logger.info("=== TEST: testCleanupDoesNotDeleteIncompleteMessages PASSED ===");
    }

    @Test
    public void testCleanupAllTopics() throws Exception {
        logger.info("=== TEST: testCleanupAllTopics STARTED ===");

        String topic1 = "test-cleanup-all-1";
        String topic2 = "test-cleanup-all-2";

        // Create two topics
        TopicConfig topicConfig1 = TopicConfig.builder()
                .topic(topic1)
                .semantics(TopicSemantics.QUEUE)
                .messageRetentionHours(1)
                .build();

        TopicConfig topicConfig2 = TopicConfig.builder()
                .topic(topic2)
                .semantics(TopicSemantics.QUEUE)
                .messageRetentionHours(1)
                .build();

        CountDownLatch latch = new CountDownLatch(1);

        topicConfigService.createTopic(topicConfig1)
                .compose(v -> topicConfigService.createTopic(topicConfig2))
                .compose(v -> {
                    // Insert completed messages for both topics (trigger will set required_consumer_groups=1)
                    String sql = """
                        INSERT INTO outbox (topic, payload, status, created_at, processed_at)
                        VALUES ($1, $2, 'COMPLETED', $3, $4)
                        """;
                    OffsetDateTime past = OffsetDateTime.now(ZoneOffset.UTC).minusHours(2);

                    return connectionManager.withConnection("peegeeq-main", connection ->
                            connection.preparedQuery(sql)
                                    .execute(Tuple.of(topic1, new JsonObject().put("test", "data1"), past, past))
                                    .compose(r -> connection.preparedQuery(sql)
                                            .execute(Tuple.of(topic2, new JsonObject().put("test", "data2"), past, past)))
                    );
                })
                .compose(result -> {
                    // Run cleanup for all topics
                    return cleanupService.cleanupAllTopics(100);
                })
                .compose(totalDeleted -> {
                    assertEquals(2, totalDeleted, "Should delete 2 messages (1 per topic)");
                    return Future.succeededFuture();
                })
                .onSuccess(v -> latch.countDown())
                .onFailure(error -> {
                    logger.error("Test failed", error);
                    latch.countDown();
                    fail("Test failed: " + error.getMessage());
                });

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Test should complete within 10 seconds");
        logger.info("=== TEST: testCleanupAllTopics PASSED ===");
    }

    @Test
    public void testCountEligibleForCleanup() throws Exception {
        logger.info("=== TEST: testCountEligibleForCleanup STARTED ===");

        String topic = "test-cleanup-count";

        // Create topic
        TopicConfig topicConfig = TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.QUEUE)
                .messageRetentionHours(1)
                .build();

        CountDownLatch latch = new CountDownLatch(1);

        topicConfigService.createTopic(topicConfig)
                .compose(v -> {
                    // Insert 3 completed messages (trigger will set required_consumer_groups=1)
                    String sql = """
                        INSERT INTO outbox (topic, payload, status, created_at, processed_at)
                        VALUES ($1, $2, 'COMPLETED', $3, $4)
                        """;
                    OffsetDateTime past = OffsetDateTime.now(ZoneOffset.UTC).minusHours(2);

                    return connectionManager.withConnection("peegeeq-main", connection ->
                            connection.preparedQuery(sql)
                                    .execute(Tuple.of(topic, new JsonObject().put("test", "data1"), past, past))
                                    .compose(r -> connection.preparedQuery(sql)
                                            .execute(Tuple.of(topic, new JsonObject().put("test", "data2"), past, past)))
                                    .compose(r -> connection.preparedQuery(sql)
                                            .execute(Tuple.of(topic, new JsonObject().put("test", "data3"), past, past)))
                    );
                })
                .compose(result -> {
                    // Count eligible messages
                    return cleanupService.countEligibleForCleanup(topic);
                })
                .compose(count -> {
                    assertEquals(3L, count, "Should find 3 messages eligible for cleanup");
                    return Future.succeededFuture();
                })
                .onSuccess(v -> latch.countDown())
                .onFailure(error -> {
                    logger.error("Test failed", error);
                    latch.countDown();
                    fail("Test failed: " + error.getMessage());
                });

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Test should complete within 10 seconds");
        logger.info("=== TEST: testCountEligibleForCleanup PASSED ===");
    }
}

