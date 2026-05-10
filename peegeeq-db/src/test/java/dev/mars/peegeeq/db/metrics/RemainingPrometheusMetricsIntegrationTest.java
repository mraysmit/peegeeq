package dev.mars.peegeeq.db.metrics;

import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.cleanup.DeadConsumerDetectionJob;
import dev.mars.peegeeq.db.cleanup.DeadConsumerDetector;
import dev.mars.peegeeq.db.cleanup.DeadConsumerGroupCleanup;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.consumer.CompletionTracker;
import dev.mars.peegeeq.db.subscription.SubscriptionManager;
import dev.mars.peegeeq.db.subscription.TopicConfig;
import dev.mars.peegeeq.db.subscription.TopicConfigService;
import dev.mars.peegeeq.db.subscription.TopicSemantics;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Tuple;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Task L10: Remaining Prometheus Metrics.
 *
 * <p>Tests 3 consumer-group-specific metrics:</p>
 * <ol>
 *   <li>{@code peegeeq.completions.total} - counter per topic+group in CompletionTracker</li>
 *   <li>{@code peegeeq.blocked.messages} - gauge per topic+group from blocked message stats</li>
 *   <li>{@code peegeeq.detection.run.duration.seconds} - gauge from detection job stats</li>
 * </ol>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-04-07
 */
@Tag(TestCategories.INTEGRATION)
@Execution(ExecutionMode.SAME_THREAD)
public class RemainingPrometheusMetricsIntegrationTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(RemainingPrometheusMetricsIntegrationTest.class);
    private static final String SERVICE_ID = "peegeeq-main";

    private PgConnectionManager connectionManager;
    private DeadConsumerDetector detector;
    private TopicConfigService topicConfigService;
    private SubscriptionManager subscriptionManager;
    private MeterRegistry meterRegistry;

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

        logger.info("RemainingPrometheusMetricsIntegrationTest setup complete");
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        if (connectionManager != null) {
            connectionManager.close()
                    .onSuccess(v -> testContext.completeNow())
                    .onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }
    }

    // ========================================================================
    // Metric 1: peegeeq.completions.total (counter per topic+group)
    // ========================================================================

    @Test
    void testCompletionCounterIncrementsOnMarkCompleted(VertxTestContext testContext) throws Exception {
        logger.info("=== Testing completion counter increments on markCompleted ===");

        String topic = "test-completion-counter-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "counter-group";
        CompletionTracker tracker = new CompletionTracker(connectionManager, SERVICE_ID, meterRegistry);

        topicConfigService.createTopic(TopicConfig.builder()
                        .topic(topic)
                        .semantics(TopicSemantics.PUB_SUB)
                        .build())
            .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.defaults()))
            .compose(v -> insertMessage(topic, new JsonObject().put("test", true)))
            .compose(messageId -> tracker.markCompleted(messageId, groupName, topic))
            .onSuccess(v -> {
                testContext.verify(() -> {
                    Counter counter = meterRegistry.find("peegeeq.completions.total")
                            .tag("topic", topic)
                            .tag("group", groupName)
                            .counter();
                    assertNotNull(counter, "Completion counter should be registered");
                    assertEquals(1.0, counter.count(), "Counter should be 1 after one completion");
                    logger.info("Completion counter test passed: count={}", counter.count());
                    testContext.completeNow();
                });
            })
            .onFailure(testContext::failNow);
    }

    @Test
    void testCompletionCounterWithoutRegistryStillWorks(VertxTestContext testContext) throws Exception {
        logger.info("=== Testing completion tracker works without meter registry ===");

        String topic = "test-no-registry-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "no-registry-group";
        CompletionTracker tracker = new CompletionTracker(connectionManager, SERVICE_ID);

        topicConfigService.createTopic(TopicConfig.builder()
                        .topic(topic)
                        .semantics(TopicSemantics.PUB_SUB)
                        .build())
            .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.defaults()))
            .compose(v -> insertMessage(topic, new JsonObject().put("test", true)))
            .compose(messageId -> tracker.markCompleted(messageId, groupName, topic))
            .onSuccess(v -> {
                logger.info("Completion tracker without registry test passed");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    // ========================================================================
    // Metric 2: peegeeq.blocked.messages (gauge from blocked message stats)
    // ========================================================================

    @Test
    void testBlockedMessagesGaugeAfterRefresh(VertxTestContext testContext) throws Exception {
        logger.info("=== Testing blocked messages gauge after refresh ===");

        String topic = "test-blocked-gauge-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "blocked-gauge-group";
        ConsumerGroupMetrics metrics = new ConsumerGroupMetrics(detector);
        metrics.bindTo(meterRegistry);

        topicConfigService.createTopic(TopicConfig.builder()
                        .topic(topic)
                        .semantics(TopicSemantics.PUB_SUB)
                        .messageRetentionHours(24)
                        .build())
            .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.defaults()))
            .compose(v -> {
                Future<Void> chain = Future.succeededFuture();
                for (int i = 0; i < 3; i++) {
                    final int idx = i;
                    chain = chain.compose(ignored -> insertMessage(topic, new JsonObject().put("index", idx)).mapEmpty());
                }
                return chain;
            })
            .compose(v -> setSubscriptionDead(topic, groupName))
            .compose(v -> metrics.refresh())
            .onSuccess(v -> {
                testContext.verify(() -> {
                    Gauge blockedGauge = meterRegistry.find("peegeeq.blocked.messages")
                            .tag("topic", topic)
                            .tag("group", groupName)
                            .gauge();
                    assertNotNull(blockedGauge, "Blocked messages gauge should exist for dead group");
                    assertTrue(blockedGauge.value() >= 3,
                            "Blocked messages gauge should be >= 3, actual: " + blockedGauge.value());
                    logger.info("Blocked messages gauge test passed: value={}", blockedGauge.value());
                    testContext.completeNow();
                });
            })
            .onFailure(testContext::failNow);
    }

    // ========================================================================
    // Metric 3: peegeeq.detection.run.duration.seconds (gauge)
    // ========================================================================

    @Test
    void testDetectionRunDurationGaugeAfterRefresh(VertxTestContext testContext) throws Exception {
        logger.info("=== Testing detection run duration gauge ===");

        DeadConsumerGroupCleanup cleanup = new DeadConsumerGroupCleanup(connectionManager, SERVICE_ID);
        DeadConsumerDetectionJob job = new DeadConsumerDetectionJob(
                manager.getVertx(), detector, cleanup, 60_000);

        ConsumerGroupMetrics metrics = new ConsumerGroupMetrics(detector);
        metrics.setDetectionJob(job);
        metrics.bindTo(meterRegistry);

        job.start();

        job.runDetectionOnce()
            .compose(v -> metrics.refresh())
            .onSuccess(v -> {
                testContext.verify(() -> {
                    Gauge durationGauge = meterRegistry.find("peegeeq.detection.run.duration.seconds")
                            .gauge();
                    assertNotNull(durationGauge, "Detection run duration gauge should exist");
                    assertTrue(durationGauge.value() >= 0,
                            "Detection duration should be non-negative, actual: " + durationGauge.value());

                    Gauge runCountGauge = meterRegistry.find("peegeeq.detection.runs.total")
                            .gauge();
                    assertNotNull(runCountGauge, "Detection run count gauge should exist");
                    assertTrue(runCountGauge.value() >= 1,
                            "Detection run count should be >= 1, actual: " + runCountGauge.value());

                    job.stop();
                    logger.info("Detection run duration gauge test passed: duration={}s, runs={}",
                            durationGauge.value(), runCountGauge.value());
                    
                    testContext.completeNow();
                });
            })
            .onFailure(testContext::failNow);
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private Future<Long> insertMessage(String topic, JsonObject payload) {
        return connectionManager.withConnection(SERVICE_ID, connection -> {
            String sql = """
                INSERT INTO outbox (topic, payload, created_at, status)
                VALUES ($1, $2::jsonb, $3, 'PENDING')
                RETURNING id
                """;
            Tuple params = Tuple.of(topic, payload, OffsetDateTime.now(ZoneOffset.UTC));
            return connection.preparedQuery(sql)
                    .execute(params)
                    .map(rows -> rows.iterator().next().getLong("id"));
        });
    }

    private Future<Void> setSubscriptionDead(String topic, String groupName) {
        return connectionManager.withConnection(SERVICE_ID, connection -> {
            String sql = """
                UPDATE outbox_topic_subscriptions
                SET subscription_status = 'DEAD'
                WHERE topic = $1 AND group_name = $2
                """;
            return connection.preparedQuery(sql)
                    .execute(Tuple.of(topic, groupName))
                    .mapEmpty();
        });
    }
}