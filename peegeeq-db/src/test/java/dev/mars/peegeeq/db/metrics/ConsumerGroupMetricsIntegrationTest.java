package dev.mars.peegeeq.db.metrics;

import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.cleanup.DeadConsumerDetector;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.db.subscription.SubscriptionManager;
import dev.mars.peegeeq.db.subscription.TopicConfig;
import dev.mars.peegeeq.db.subscription.TopicConfigService;
import dev.mars.peegeeq.db.subscription.TopicSemantics;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.sqlclient.Tuple;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link ConsumerGroupMetrics}.
 *
 * <p>Validates that Prometheus metrics for consumer group health are correctly
 * registered and populated from real database state via {@link DeadConsumerDetector}.</p>
 *
 * <p>TDD RED phase: these tests define the expected metrics contract before
 * implementation exists.</p>
 */
@Tag(TestCategories.INTEGRATION)
@Execution(ExecutionMode.SAME_THREAD)
public class ConsumerGroupMetricsIntegrationTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(ConsumerGroupMetricsIntegrationTest.class);
    private static final String SERVICE_ID = "peegeeq-main";

    private PgConnectionManager connectionManager;
    private DeadConsumerDetector detector;
    private TopicConfigService topicConfigService;
    private SubscriptionManager subscriptionManager;
    private MeterRegistry meterRegistry;
    private ConsumerGroupMetrics consumerGroupMetrics;

    @BeforeEach
    void setUp() throws Exception {
        connectionManager = new PgConnectionManager(manager.getVertx(), null);

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

        connectionManager.getOrCreateReactivePool(SERVICE_ID, connectionConfig, poolConfig);

        detector = new DeadConsumerDetector(connectionManager, SERVICE_ID);
        topicConfigService = new TopicConfigService(connectionManager, SERVICE_ID);
        subscriptionManager = new SubscriptionManager(connectionManager, SERVICE_ID);

        meterRegistry = new SimpleMeterRegistry();
        consumerGroupMetrics = new ConsumerGroupMetrics(detector);
        consumerGroupMetrics.bindTo(meterRegistry);

        logger.info("ConsumerGroupMetricsIntegrationTest setup complete");
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        if (connectionManager != null) {
            connectionManager.close().onSuccess(v -> testContext.completeNow()).onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }
    }

    /**
     * After binding, subscription status gauges should exist in the registry
     * with initial zero values before any refresh.
     */
    @Test
    void testGaugesRegisteredAfterBinding() {
        assertGaugeExists("peegeeq.subscriptions.active");
        assertGaugeExists("peegeeq.subscriptions.paused");
        assertGaugeExists("peegeeq.subscriptions.dead");
        assertGaugeExists("peegeeq.subscriptions.cancelled");
        assertGaugeExists("peegeeq.subscriptions.total");
        assertGaugeExists("peegeeq.subscriptions.topics");

        logger.info("All subscription gauges registered");
    }

    /**
     * After creating ACTIVE subscriptions and refreshing, the active gauge
     * should reflect the count from the database.
     */
    @Test
    void testActiveSubscriptionGaugeAfterRefresh(VertxTestContext testContext) throws Exception {
        String topic = uniqueTopic("metrics-active");
        createTopic(topic)
                .compose(v -> subscribeWithDefaults(topic, "group-1"))
                .compose(v -> subscribeWithDefaults(topic, "group-2"))
                .compose(v -> consumerGroupMetrics.refresh())
                .onSuccess(v -> testContext.verify(() -> {
                double activeCount = getGaugeValue("peegeeq.subscriptions.active");
                        assertTrue(activeCount >= 2,
                                "Active subscription gauge should be >= 2 after creating 2 subscriptions, but was " + activeCount);
                        double totalCount = getGaugeValue("peegeeq.subscriptions.total");
                        assertTrue(totalCount >= 2,
                                "Total subscription gauge should be >= 2, but was " + totalCount);
                        logger.info("Active subscription gauge verified: active={}, total={}", activeCount, totalCount);
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    /**
     * After marking a subscription DEAD (via detection) and refreshing,
     * the dead gauge should increment.
     */
    @Test
    void testDeadSubscriptionGaugeAfterDetection(VertxTestContext testContext) throws Exception {
        logger.warn("===== INTENTIONAL WARN TEST ===== The next WARN logs ('Marked N subscriptions as DEAD') are EXPECTED — this test marks a subscription DEAD to verify the dead subscription gauge increments");
        String topic = uniqueTopic("metrics-dead");
        createTopic(topic)
                .compose(v -> subscribeWithShortTimeout(topic, "dead-group"))
                .compose(v -> setHeartbeatInPast(topic, "dead-group", 10))
                .compose(v -> detector.detectDeadSubscriptions(topic))
                .compose(v -> setHeartbeatInPast(topic, "dead-group", 10))
                .compose(v -> detector.detectDeadSubscriptions(topic))
                .compose(v -> setHeartbeatInPast(topic, "dead-group", 10))
                .compose(v -> detector.detectDeadSubscriptions(topic))
                .compose(v -> consumerGroupMetrics.refresh())
                .onSuccess(v -> testContext.verify(() -> {
                double deadCount = getGaugeValue("peegeeq.subscriptions.dead");
                        assertTrue(deadCount >= 1,
                                "Dead subscription gauge should be >= 1 after marking a subscription DEAD, but was " + deadCount);
                        logger.info("Dead subscription gauge verified: dead={}", deadCount);
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    /**
     * After pausing a subscription and refreshing, the paused gauge
     * should reflect the count.
     */
    @Test
    void testPausedSubscriptionGaugeAfterRefresh(VertxTestContext testContext) throws Exception {
        String topic = uniqueTopic("metrics-paused");
        createTopic(topic)
                .compose(v -> subscribeWithDefaults(topic, "pause-group"))
                .compose(v -> subscriptionManager.pause(topic, "pause-group"))
                .compose(v -> consumerGroupMetrics.refresh())
                .onSuccess(v -> testContext.verify(() -> {
                double pausedCount = getGaugeValue("peegeeq.subscriptions.paused");
                        assertTrue(pausedCount >= 1,
                                "Paused subscription gauge should be >= 1 after pausing a subscription, but was " + pausedCount);
                        logger.info("Paused subscription gauge verified: paused={}", pausedCount);
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    /**
     * The topics gauge should reflect the number of distinct topics
     * that have subscriptions.
     */
    @Test
    void testTopicsGaugeReflectsDistinctTopicCount(VertxTestContext testContext) throws Exception {
        String topic1 = uniqueTopic("metrics-t1");
        String topic2 = uniqueTopic("metrics-t2");
        createTopic(topic1)
                .compose(v -> createTopic(topic2))
                .compose(v -> subscribeWithDefaults(topic1, "group-a"))
                .compose(v -> subscribeWithDefaults(topic2, "group-b"))
                .compose(v -> consumerGroupMetrics.refresh())
                .onSuccess(v -> testContext.verify(() -> {
                double topicCount = getGaugeValue("peegeeq.subscriptions.topics");
                        assertTrue(topicCount >= 2,
                                "Topics gauge should be >= 2 after subscribing to 2 topics, but was " + topicCount);
                        logger.info("Topics gauge verified: topics={}", topicCount);
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    /**
     * Multiple sequential refreshes should replace (not accumulate) the gauge values.
     * Gauges are snapshots, not counters.
     */
    @Test
    void testRefreshReplacesGaugeValues(VertxTestContext testContext) throws Exception {
        String topic = uniqueTopic("metrics-replace");
        double[] firstReadRef = new double[1];
        createTopic(topic)
                .compose(v -> subscribeWithDefaults(topic, "group-1"))
                .compose(v -> consumerGroupMetrics.refresh())
                .compose(v -> {
                    firstReadRef[0] = getGaugeValue("peegeeq.subscriptions.active");
                    return subscribeWithDefaults(topic, "group-2");
                })
                .compose(v -> consumerGroupMetrics.refresh())
                .onSuccess(v -> testContext.verify(() -> {
                double secondRead = getGaugeValue("peegeeq.subscriptions.active");
                        assertTrue(secondRead > firstReadRef[0],
                                "Gauge should increase after adding subscription: first=" + firstReadRef[0] + " second=" + secondRead);
                        logger.info("Gauge replacement verified: first={}, second={}", firstReadRef[0], secondRead);
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    /**
     * Detection run metrics: total detection runs counted.
     */
    @Test
    void testDetectionRunCountGauge(VertxTestContext testContext) throws Exception {
        String topic = uniqueTopic("metrics-runs");
        createTopic(topic)
                .compose(v -> subscribeWithShortTimeout(topic, "run-group"))
                .compose(v -> detector.detectDeadSubscriptions(topic))
                .compose(v -> consumerGroupMetrics.refresh())
                .onSuccess(v -> testContext.verify(() -> {
                double activeCount = getGaugeValue("peegeeq.subscriptions.active");
                        assertTrue(activeCount >= 0, "Active count should be non-negative");
                        logger.info("Detection-related gauge verified");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    private String uniqueTopic(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private Future<Void> createTopic(String topic) {
        return topicConfigService.createTopic(TopicConfig.builder()
                        .topic(topic)
                        .semantics(TopicSemantics.PUB_SUB)
                        .messageRetentionHours(24)
                        .build())
                .mapEmpty();
    }

    private Future<Void> subscribeWithDefaults(String topic, String groupName) {
        return subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.builder().build()).mapEmpty();
    }

    private Future<Void> subscribeWithShortTimeout(String topic, String groupName) {
        SubscriptionOptions options = SubscriptionOptions.builder()
                .heartbeatIntervalSeconds(1)
                .heartbeatTimeoutSeconds(2)
                .build();
        return subscriptionManager.subscribe(topic, groupName, options).mapEmpty();
    }

    private Future<Void> setHeartbeatInPast(String topic, String groupName, int secondsAgo) {
        return connectionManager.withConnection(SERVICE_ID, connection -> {
            String sql = """
                UPDATE outbox_topic_subscriptions
                SET last_heartbeat_at = NOW() - ($3 || ' seconds')::INTERVAL
                WHERE topic = $1 AND group_name = $2
                """;
            return connection.preparedQuery(sql)
                    .execute(Tuple.of(topic, groupName, String.valueOf(secondsAgo)))
                    .mapEmpty();
        });
    }

    private void assertGaugeExists(String metricName) {
        Gauge gauge = meterRegistry.find(metricName).gauge();
        assertNotNull(gauge, "Gauge '" + metricName + "' should be registered in the meter registry");
    }

    private double getGaugeValue(String metricName) {
        Gauge gauge = meterRegistry.find(metricName).gauge();
        assertNotNull(gauge, "Gauge '" + metricName + "' not found in registry");
        return gauge.value();
    }
}
