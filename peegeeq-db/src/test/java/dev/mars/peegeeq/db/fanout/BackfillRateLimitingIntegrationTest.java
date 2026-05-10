package dev.mars.peegeeq.db.fanout;

import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.api.messaging.BackfillScope;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.db.subscription.BackfillService;
import dev.mars.peegeeq.db.subscription.BackfillService.BackfillResult;
import dev.mars.peegeeq.db.subscription.SubscriptionManager;
import dev.mars.peegeeq.db.subscription.TopicConfig;
import dev.mars.peegeeq.db.subscription.TopicConfigService;
import dev.mars.peegeeq.db.subscription.TopicSemantics;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link BackfillService} adaptive rate-limiting feature.
 *
 * <p>Tests validate that inter-batch delays slow backfill throughput and that
 * cancellation still works correctly during delayed batches.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-04-04
 */
@Tag(TestCategories.INTEGRATION)
@Execution(ExecutionMode.SAME_THREAD)
public class BackfillRateLimitingIntegrationTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(BackfillRateLimitingIntegrationTest.class);
    private static final String SERVICE_ID = "peegeeq-main";

    private PgConnectionManager connectionManager;
    private TopicConfigService topicConfigService;
    private SubscriptionManager subscriptionManager;
    private BackfillService backfillService;
    private Vertx vertx;

    @BeforeEach
    void setUp() {
        vertx = manager.getVertx();
        connectionManager = new PgConnectionManager(vertx, null);

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

        topicConfigService = new TopicConfigService(connectionManager, SERVICE_ID);
        subscriptionManager = new SubscriptionManager(connectionManager, SERVICE_ID);
        backfillService = new BackfillService(connectionManager, SERVICE_ID, vertx);

        logger.info("Test setup complete");
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        if (connectionManager != null) {
            connectionManager.close().onSuccess(v -> testContext.completeNow()).onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }
    }

    // =========================================================================
    // Rate-limited backfill delay slows throughput
    // =========================================================================

    /**
     * Verifies that a backfill with inter-batch delay takes measurably longer
     * than a backfill without delay on the same data volume.
     *
     * Scenario:
     * - Insert 30 messages on a PUB_SUB topic with an initial subscriber
     * - Run backfill with batchSize=10, no delay, measure elapsed time
     * - Run backfill with batchSize=10, 100ms delay, measure elapsed time
     * - Delayed backfill should take at least 200ms longer (2 inter-batch pauses)
     */
    @Test
    void testBatchDelaySlowsThroughput(VertxTestContext testContext) {
        String topicBase = "test-rate-limit-" + UUID.randomUUID().toString().substring(0, 8);
        String topicNoDelay = topicBase + "-nodelay";
        String topicWithDelay = topicBase + "-delay";
        String groupNoDelay = "group-nodelay";
        String groupDelay = "group-delay";
        int messageCount = 30;
        int batchSize = 10; // 3 batches → 2 inter-batch pauses
        long delayMs = 100L;

        // Setup both topics with initial subscribers + messages
        Future<Void> setup = createTopicWithSubscriberAndMessages(topicNoDelay, messageCount)
                .compose(v -> createTopicWithSubscriberAndMessages(topicWithDelay, messageCount));

        setup
            // Subscribe both late-joining groups
            .compose(v -> subscriptionManager.subscribe(topicNoDelay, groupNoDelay, SubscriptionOptions.fromBeginning()))
            .compose(v -> subscriptionManager.subscribe(topicWithDelay, groupDelay, SubscriptionOptions.fromBeginning()))
            // No-delay backfill
            .compose(v -> {
                long start = System.currentTimeMillis();
                return backfillService.startBackfill(topicNoDelay, groupNoDelay, batchSize, 0, 0L)
                        .map(result -> {
                            long elapsed = System.currentTimeMillis() - start;
                            logger.info("No-delay backfill: {}ms, processed={}", elapsed, result.processedMessages());
                            return elapsed;
                        });
            })
            // With-delay backfill
            .compose(noDelayMs -> {
                long start = System.currentTimeMillis();
                return backfillService.startBackfill(topicWithDelay, groupDelay, batchSize, 0, delayMs)
                        .map(result -> {
                            long elapsed = System.currentTimeMillis() - start;
                            logger.info("With-delay backfill: {}ms, processed={}", elapsed, result.processedMessages());
                            return new long[]{noDelayMs, elapsed};
                        });
            })
            .onComplete(testContext.succeeding(times -> testContext.verify(() -> {
                long noDelayElapsed = times[0];
                long delayElapsed = times[1];

                // With 3 batches and 100ms delay between batches, delayed should be
                // at least 150ms longer (conservative to avoid flakiness 2 delays × 75ms margin)
                long minimumDifference = 150L;
                long actualDifference = delayElapsed - noDelayElapsed;

                logger.info("Throughput comparison: no-delay={}ms, delay={}ms, diff={}ms (min expected={}ms)",
                        noDelayElapsed, delayElapsed, actualDifference, minimumDifference);

                assertTrue(actualDifference >= minimumDifference,
                        String.format("Delayed backfill should be at least %dms slower. " +
                                "no-delay=%dms, delay=%dms, diff=%dms",
                                minimumDifference, noDelayElapsed, delayElapsed, actualDifference));

                testContext.completeNow();
            })));

    }

    /**
     * Verifies that zero-delay backfill completes identically to legacy (no-delay) behavior.
     */
    @Test
    void testZeroDelayCompletesNormally(VertxTestContext testContext) {
        String topic = "test-zero-delay-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group-zero-delay";
        int messageCount = 10;

        createTopicWithSubscriberAndMessages(topic, messageCount)
            .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.fromBeginning()))
            .compose(v -> backfillService.startBackfill(topic, groupName, 5, 0, 0L))
            .onComplete(testContext.succeeding(result -> testContext.verify(() -> {
                assertEquals(BackfillResult.Status.COMPLETED, result.status());
                assertEquals(messageCount, result.processedMessages());
                testContext.completeNow();
            })));

    }

    /**
     * Verifies that cancellation works correctly during a rate-limited backfill.
     * The delay between batches should not prevent cancellation from being detected.
     */
    @Test
    void testCancellationDuringDelayedBackfill(VertxTestContext testContext) {
        String topic = "test-cancel-delay-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group-cancel-delay";
        int messageCount = 100;
        int batchSize = 10; // 10 batches
        long delayMs = 200L; // 200ms between batches total ~1.8s of delays

        createTopicWithSubscriberAndMessages(topic, messageCount)
            .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.fromBeginning()))
            .compose(v -> {
                // Start backfill (it will run asynchronously due to delays)
                Future<BackfillResult> backfillFuture = backfillService.startBackfill(
                        topic, groupName, batchSize, 0, delayMs);

                // Cancel after a short delay (enough for ~1-2 batches)
                vertx.setTimer(350L, timerId -> {
                    backfillService.cancelBackfill(topic, groupName)
                            .onSuccess(v2 -> logger.info("Backfill cancellation recorded"))
                            .onFailure(err -> logger.warn("Cancel request failed: {}", err.getMessage()));
                });

                return backfillFuture;
            })
            .onComplete(testContext.succeeding(result -> testContext.verify(() -> {
                assertEquals(BackfillResult.Status.CANCELLED, result.status(),
                        "Backfill should be cancelled");
                assertTrue(result.processedMessages() > 0,
                        "Should have processed some messages before cancellation");
                assertTrue(result.processedMessages() < messageCount,
                        "Should not have processed all messages (cancelled early)");
                logger.info("Cancelled after processing {} of {} messages",
                        result.processedMessages(), messageCount);
                testContext.completeNow();
            })));

    }

    /**
     * Verifies that the legacy startBackfill overloads (without delay parameter)
     * still work correctly via the Vertx-aware constructor.
     */
    @Test
    void testLegacyOverloadsStillWork(VertxTestContext testContext) {
        String topic = "test-legacy-compat-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group-legacy";
        int messageCount = 5;

        createTopicWithSubscriberAndMessages(topic, messageCount)
            .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.fromBeginning()))
            .compose(v -> backfillService.startBackfill(topic, groupName, 100, 0))
            .onComplete(testContext.succeeding(result -> testContext.verify(() -> {
                assertEquals(BackfillResult.Status.COMPLETED, result.status());
                assertEquals(messageCount, result.processedMessages());
                testContext.completeNow();
            })));

    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private String uniqueTopic() {
        return "test-backfill-rl-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private Future<Void> createTopicWithSubscriberAndMessages(String topic, int messageCount) {
        return topicConfigService.createTopic(TopicConfig.builder()
                        .topic(topic)
                        .semantics(TopicSemantics.PUB_SUB)
                        .messageRetentionHours(24)
                        .build())
                .compose(v -> subscriptionManager.subscribe(topic, "initial-group", SubscriptionOptions.defaults()))
                .compose(v -> insertMessages(topic, messageCount));
    }

    private Future<Void> insertMessages(String topic, int count) {
        Future<Void> chain = Future.succeededFuture();
        for (int i = 0; i < count; i++) {
            final int idx = i;
            chain = chain.compose(v -> connectionManager.withConnection(SERVICE_ID, conn -> {
                String sql = """
                    INSERT INTO outbox (topic, payload, status, created_at)
                    VALUES ($1, $2, 'PENDING', $3)
                    """;
                JsonObject payload = new JsonObject().put("index", idx).put("data", "test-" + idx);
                return conn.preparedQuery(sql)
                        .execute(Tuple.of(topic, payload, OffsetDateTime.now(ZoneOffset.UTC)))
                        .mapEmpty();
            }));
        }
        return chain;
    }
}
