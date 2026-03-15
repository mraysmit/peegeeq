package dev.mars.peegeeq.db.cleanup;

import dev.mars.peegeeq.api.subscription.SubscriptionState;
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
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import io.vertx.junit5.VertxTestContext;

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
@Execution(ExecutionMode.SAME_THREAD)
public class DeadConsumerDetectorIntegrationTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(DeadConsumerDetectorIntegrationTest.class);

    private PgConnectionManager connectionManager;
    private DeadConsumerDetector detector;
    private TopicConfigService topicConfigService;
    private SubscriptionManager subscriptionManager;

    @BeforeEach
    public void setUp(VertxTestContext testContext) throws Exception {
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
    public void testDetectDeadSubscription(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testDetectDeadSubscription STARTED ===");

        String topic = "test-dead-detection-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";

        // Create topic
        TopicConfig topicConfig = TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.PUB_SUB)
                .build();
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
                    assertEquals(SubscriptionState.DEAD, subscription.state(),
                            "Subscription should be marked as DEAD");
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        logger.info("=== TEST: testDetectDeadSubscription PASSED ===");
    }

    @Test
    public void testDoesNotMarkActiveSubscription(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testDoesNotMarkActiveSubscription STARTED ===");

        String topic = "test-active-subscription-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";

        // Create topic
        TopicConfig topicConfig = TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.PUB_SUB)
                .build();
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
                    assertEquals(SubscriptionState.ACTIVE, subscription.state(),
                            "Subscription should remain ACTIVE");
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
        logger.info("=== TEST: testDoesNotMarkActiveSubscription PASSED ===");
    }

    @Test
    public void testDetectAllDeadSubscriptions(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testDetectAllDeadSubscriptions STARTED ===");

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String topic1 = "test-dead-all-1-" + suffix;
        String topic2 = "test-dead-all-2-" + suffix;
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
                .compose(markedDead -> {
                    logger.info("Marked {} subscriptions as DEAD across all topics", markedDead);
                    // Don't assert exact count — other parallel tests may contribute or steal detections
                    assertTrue(markedDead >= 0, "markedDead should be non-negative");
                    // Verify our specific subscriptions ended up DEAD
                    return subscriptionManager.getSubscription(topic1, group1);
                })
                .compose(sub1 -> {
                    assertEquals(SubscriptionState.DEAD, sub1.state(),
                            topic1 + "/" + group1 + " should be DEAD");
                    return subscriptionManager.getSubscription(topic2, group2);
                })
                .onSuccess(sub2 -> {
                    assertEquals(SubscriptionState.DEAD, sub2.state(),
                            topic2 + "/" + group2 + " should be DEAD");
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        logger.info("=== TEST: testDetectAllDeadSubscriptions PASSED ===");
    }

    @Test
    public void testCountDeadSubscriptions(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testCountDeadSubscriptions STARTED ===");

        String topic = "test-count-dead-" + UUID.randomUUID().toString().substring(0, 8);
        String group1 = "group1";
        String group2 = "group2";

        // Create topic
        TopicConfig topicConfig = TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.PUB_SUB)
                .build();
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
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
        logger.info("=== TEST: testCountDeadSubscriptions PASSED ===");
    }
}

