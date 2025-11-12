package dev.mars.peegeeq.db.cleanup;

import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.subscription.SubscriptionManager;
import dev.mars.peegeeq.db.subscription.SubscriptionOptions;
import dev.mars.peegeeq.db.subscription.SubscriptionStatus;
import dev.mars.peegeeq.db.subscription.TopicConfig;
import dev.mars.peegeeq.db.subscription.TopicConfigService;
import dev.mars.peegeeq.db.subscription.TopicSemantics;
import dev.mars.peegeeq.test.categories.TestCategories;
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
 * Integration tests for DeadConsumerDetector.
 * 
 * <p>Tests the dead consumer detection logic including:
 * <ul>
 *   <li>Detecting subscriptions that exceeded heartbeat timeout</li>
 *   <li>Marking subscriptions as DEAD</li>
 *   <li>Detecting across all topics</li>
 *   <li>Counting dead subscriptions</li>
 * </ul>
 * </p>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-12
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
public class DeadConsumerDetectorIntegrationTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(DeadConsumerDetectorIntegrationTest.class);

    private PgConnectionManager connectionManager;
    private DeadConsumerDetector detector;
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

        detector = new DeadConsumerDetector(connectionManager, "peegeeq-main");
        topicConfigService = new TopicConfigService(connectionManager, "peegeeq-main");
        subscriptionManager = new SubscriptionManager(connectionManager, "peegeeq-main");

        logger.info("DeadConsumerDetector test setup complete");
    }

    @Test
    public void testDetectDeadSubscription() throws Exception {
        logger.info("=== TEST: testDetectDeadSubscription STARTED ===");

        String topic = "test-dead-detection";
        String groupName = "group1";

        // Create topic
        TopicConfig topicConfig = TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.PUB_SUB)
                .build();

        CountDownLatch latch = new CountDownLatch(1);

        topicConfigService.createTopic(topicConfig)
                .compose(v -> {
                    // Subscribe with 60 second timeout
                    SubscriptionOptions options = SubscriptionOptions.builder()
                            .heartbeatIntervalSeconds(30)
                            .heartbeatTimeoutSeconds(60)
                            .build();
                    return subscriptionManager.subscribe(topic, groupName, options);
                })
                .compose(v -> {
                    // Manually set last_heartbeat_at to 2 minutes ago (exceeds 60s timeout)
                    String sql = """
                        UPDATE outbox_topic_subscriptions
                        SET last_heartbeat_at = $1
                        WHERE topic = $2 AND group_name = $3
                        """;
                    OffsetDateTime twoMinutesAgo = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(2);
                    Tuple params = Tuple.of(twoMinutesAgo, topic, groupName);

                    return connectionManager.withConnection("peegeeq-main", connection ->
                            connection.preparedQuery(sql).execute(params)
                    );
                })
                .compose(result -> {
                    logger.info("Set last_heartbeat_at to 2 minutes ago");
                    // Run dead detection
                    return detector.detectDeadSubscriptions(topic);
                })
                .compose(markedDead -> {
                    logger.info("Marked {} subscriptions as DEAD", markedDead);
                    assertEquals(1, markedDead, "Should mark 1 subscription as DEAD");
                    // Verify subscription status
                    return subscriptionManager.getSubscription(topic, groupName);
                })
                .onSuccess(subscription -> {
                    assertNotNull(subscription, "Subscription should exist");
                    assertEquals(SubscriptionStatus.DEAD, subscription.getStatus(),
                            "Subscription should be marked as DEAD");
                    latch.countDown();
                })
                .onFailure(error -> {
                    logger.error("Test failed", error);
                    fail("Test failed: " + error.getMessage());
                    latch.countDown();
                });

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Test should complete within 10 seconds");
        logger.info("=== TEST: testDetectDeadSubscription PASSED ===");
    }

    @Test
    public void testDoesNotMarkActiveSubscription() throws Exception {
        logger.info("=== TEST: testDoesNotMarkActiveSubscription STARTED ===");

        String topic = "test-active-subscription";
        String groupName = "group1";

        // Create topic
        TopicConfig topicConfig = TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.PUB_SUB)
                .build();

        CountDownLatch latch = new CountDownLatch(1);

        topicConfigService.createTopic(topicConfig)
                .compose(v -> {
                    // Subscribe with 60 second timeout
                    SubscriptionOptions options = SubscriptionOptions.builder()
                            .heartbeatIntervalSeconds(30)
                            .heartbeatTimeoutSeconds(60)
                            .build();
                    return subscriptionManager.subscribe(topic, groupName, options);
                })
                .compose(v -> {
                    // Update heartbeat to now (within timeout)
                    return subscriptionManager.updateHeartbeat(topic, groupName);
                })
                .compose(v -> {
                    logger.info("Updated heartbeat to now");
                    // Run dead detection
                    return detector.detectDeadSubscriptions(topic);
                })
                .compose(markedDead -> {
                    logger.info("Marked {} subscriptions as DEAD", markedDead);
                    assertEquals(0, markedDead, "Should NOT mark active subscription as DEAD");
                    // Verify subscription status
                    return subscriptionManager.getSubscription(topic, groupName);
                })
                .onSuccess(subscription -> {
                    assertNotNull(subscription, "Subscription should exist");
                    assertEquals(SubscriptionStatus.ACTIVE, subscription.getStatus(),
                            "Subscription should remain ACTIVE");
                    latch.countDown();
                })
                .onFailure(error -> {
                    logger.error("Test failed", error);
                    fail("Test failed: " + error.getMessage());
                    latch.countDown();
                });

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Test should complete within 10 seconds");
        logger.info("=== TEST: testDoesNotMarkActiveSubscription PASSED ===");
    }

    @Test
    public void testDetectAllDeadSubscriptions() throws Exception {
        logger.info("=== TEST: testDetectAllDeadSubscriptions STARTED ===");

        String topic1 = "test-dead-all-1";
        String topic2 = "test-dead-all-2";
        String group1 = "group1";
        String group2 = "group2";

        // Create two topics
        TopicConfig topicConfig1 = TopicConfig.builder()
                .topic(topic1)
                .semantics(TopicSemantics.PUB_SUB)
                .build();

        TopicConfig topicConfig2 = TopicConfig.builder()
                .topic(topic2)
                .semantics(TopicSemantics.PUB_SUB)
                .build();

        CountDownLatch latch = new CountDownLatch(1);

        topicConfigService.createTopic(topicConfig1)
                .compose(v -> topicConfigService.createTopic(topicConfig2))
                .compose(v -> {
                    // Subscribe to both topics
                    SubscriptionOptions options = SubscriptionOptions.builder()
                            .heartbeatIntervalSeconds(30)
                            .heartbeatTimeoutSeconds(60)
                            .build();
                    return subscriptionManager.subscribe(topic1, group1, options);
                })
                .compose(v -> {
                    SubscriptionOptions options = SubscriptionOptions.builder()
                            .heartbeatIntervalSeconds(30)
                            .heartbeatTimeoutSeconds(60)
                            .build();
                    return subscriptionManager.subscribe(topic2, group2, options);
                })
                .compose(v -> {
                    // Set both heartbeats to 2 minutes ago
                    String sql = """
                        UPDATE outbox_topic_subscriptions
                        SET last_heartbeat_at = $1
                        WHERE (topic = $2 AND group_name = $3) OR (topic = $4 AND group_name = $5)
                        """;
                    OffsetDateTime twoMinutesAgo = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(2);
                    Tuple params = Tuple.of(twoMinutesAgo, topic1, group1, topic2, group2);

                    return connectionManager.withConnection("peegeeq-main", connection ->
                            connection.preparedQuery(sql).execute(params)
                    );
                })
                .compose(result -> {
                    logger.info("Set both heartbeats to 2 minutes ago");
                    // Run dead detection for all topics
                    return detector.detectAllDeadSubscriptions();
                })
                .onSuccess(markedDead -> {
                    logger.info("Marked {} subscriptions as DEAD across all topics", markedDead);
                    assertEquals(2, markedDead, "Should mark 2 subscriptions as DEAD");
                    latch.countDown();
                })
                .onFailure(error -> {
                    logger.error("Test failed", error);
                    fail("Test failed: " + error.getMessage());
                    latch.countDown();
                });

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Test should complete within 10 seconds");
        logger.info("=== TEST: testDetectAllDeadSubscriptions PASSED ===");
    }

    @Test
    public void testCountDeadSubscriptions() throws Exception {
        logger.info("=== TEST: testCountDeadSubscriptions STARTED ===");

        String topic = "test-count-dead";
        String group1 = "group1";
        String group2 = "group2";

        // Create topic
        TopicConfig topicConfig = TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.PUB_SUB)
                .build();

        CountDownLatch latch = new CountDownLatch(1);

        topicConfigService.createTopic(topicConfig)
                .compose(v -> {
                    // Subscribe two groups
                    SubscriptionOptions options = SubscriptionOptions.builder()
                            .heartbeatIntervalSeconds(30)
                            .heartbeatTimeoutSeconds(60)
                            .build();
                    return subscriptionManager.subscribe(topic, group1, options);
                })
                .compose(v -> {
                    SubscriptionOptions options = SubscriptionOptions.builder()
                            .heartbeatIntervalSeconds(30)
                            .heartbeatTimeoutSeconds(60)
                            .build();
                    return subscriptionManager.subscribe(topic, group2, options);
                })
                .compose(v -> {
                    // Set group1 heartbeat to 2 minutes ago
                    String sql = """
                        UPDATE outbox_topic_subscriptions
                        SET last_heartbeat_at = $1
                        WHERE topic = $2 AND group_name = $3
                        """;
                    OffsetDateTime twoMinutesAgo = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(2);
                    Tuple params = Tuple.of(twoMinutesAgo, topic, group1);

                    return connectionManager.withConnection("peegeeq-main", connection ->
                            connection.preparedQuery(sql).execute(params)
                    );
                })
                .compose(result -> {
                    logger.info("Set group1 heartbeat to 2 minutes ago");
                    // Run dead detection
                    return detector.detectDeadSubscriptions(topic);
                })
                .compose(markedDead -> {
                    logger.info("Marked {} subscriptions as DEAD", markedDead);
                    // Count dead subscriptions
                    return detector.countDeadSubscriptions(topic);
                })
                .onSuccess(count -> {
                    logger.info("Found {} DEAD subscriptions", count);
                    assertEquals(1L, count, "Should find 1 DEAD subscription");
                    latch.countDown();
                })
                .onFailure(error -> {
                    logger.error("Test failed", error);
                    fail("Test failed: " + error.getMessage());
                    latch.countDown();
                });

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Test should complete within 10 seconds");
        logger.info("=== TEST: testCountDeadSubscriptions PASSED ===");
    }
}

