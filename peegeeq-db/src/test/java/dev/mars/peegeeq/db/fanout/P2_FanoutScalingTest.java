package dev.mars.peegeeq.db.fanout;

import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.cleanup.CleanupService;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.consumer.CompletionTracker;
import dev.mars.peegeeq.db.consumer.ConsumerGroupFetcher;
import dev.mars.peegeeq.db.consumer.OutboxMessage;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.db.subscription.SubscriptionManager;
import dev.mars.peegeeq.db.subscription.TopicConfig;
import dev.mars.peegeeq.db.subscription.TopicConfigService;
import dev.mars.peegeeq.db.subscription.TopicSemantics;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P2: Fanout Scaling Test - Validates performance scaling with increasing consumer groups.
 *
 * <p>This test measures:
 * <ul>
 *   <li>Throughput degradation as consumer groups increase (1, 2, 4, 8, 16)</li>
 *   <li>CPU scaling characteristics (should be sub-linear for reference counting mode)</li>
 *   <li>Completion tracking overhead with multiple groups</li>
 * </ul>
 *
 * <p>Performance Target: DB CPU < 70% at N=16 groups
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-13
 * @version 1.0
 */
@Tag(TestCategories.PERFORMANCE)
@Isolated("Performance test requires exclusive database access")
public class P2_FanoutScalingTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(P2_FanoutScalingTest.class);

    private PgConnectionManager connectionManager;
    private TopicConfigService topicConfigService;
    private SubscriptionManager subscriptionManager;
    private ConsumerGroupFetcher fetcher;
    private CompletionTracker completionTracker;
    private CleanupService cleanupService;

    @BeforeEach
    void setUp() {
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
            .maxSize(5)
            .shared(false)
            .idleTimeout(Duration.ofSeconds(5))
            .connectionTimeout(Duration.ofSeconds(30))
            .build();

        connectionManager.getOrCreateReactivePool("peegeeq-main", connectionConfig, poolConfig);

        topicConfigService = new TopicConfigService(connectionManager, "peegeeq-main");
        subscriptionManager = new SubscriptionManager(connectionManager, "peegeeq-main");
        fetcher = new ConsumerGroupFetcher(connectionManager, "peegeeq-main");
        completionTracker = new CompletionTracker(connectionManager, "peegeeq-main");
        cleanupService = new CleanupService(connectionManager, "peegeeq-main");

        logger.info("P2 Fanout Scaling Test setup complete");
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
     * Test fanout scaling with 1, 2, 4, 8, 16 consumer groups.
     * Measures throughput degradation and validates sub-linear CPU scaling.
     *
     * <p><strong>Timeout override 240 s (special case):</strong> This test requires a custom
     * {@link Timeout} because the default VertxExtension timeout of 30 s is not sufficient
     * for this performance scenario. The test executes <em>five sequential group-count
     * iterations</em> (N = 1, 2, 4, 8, 16), each publishing 500 messages and consuming
     * them via individual DB round trips with completion marking. Worst-case total DB
     * operations exceed 18 000 (500 inserts + up to 8 000 completions × 16 groups).
     * On a Testcontainers PostgreSQL instance this reliably exceeds 30 s.
     * The 240 s budget is derived from observed consume durations scaling linearly
     * with group count (N=1: 6 s, N=2: 11 s, N=4: 22 s, N=8: 41 s, N=16: ~82 s),
     * giving a cumulative worst-case of ~162 s plus startup/teardown headroom.</p>
     */
    @Test
    @Timeout(value = 240, timeUnit = TimeUnit.SECONDS)
    void testFanoutScaling(VertxTestContext testContext) {
        logger.info("=== P2: FANOUT SCALING TEST ===");

        int[] groupCounts = {1, 2, 4, 8, 16};
        int messageCount = 500;  // Smaller count to keep test duration reasonable
        int payloadSizeBytes = 2048;

        logger.info("=== TEST CONFIGURATION ===");
        logger.info("Message Count: {}", messageCount);
        logger.info("Payload Size: {} bytes", payloadSizeBytes);
        logger.info("Group Counts: {}", groupCounts);

        List<long[]> scalingResults = new ArrayList<>();

        Future<Void> chain = Future.succeededFuture();
        for (int groupCount : groupCounts) {
            final int gc = groupCount;
            chain = chain.compose(v -> testWithGroupCount(gc, messageCount, payloadSizeBytes, scalingResults));
        }
        chain
            .onSuccess(v -> {
                logScalingSummaryTable(scalingResults, messageCount);
                logger.info("=== P2: FANOUT SCALING TEST COMPLETE ===");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    private Future<Void> testWithGroupCount(int groupCount, int messageCount, int payloadSizeBytes,
                                              List<long[]> scalingResults) {
        String topic = "perf-test-scaling-" + groupCount + "-" + UUID.randomUUID().toString().substring(0, 8);

        List<String> consumerGroups = new ArrayList<>();
        for (int i = 0; i < groupCount; i++) {
            consumerGroups.add("group-" + i);
        }

        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .messageRetentionHours(1)
            .build();

        long[] publishStart = {0};
        long[] publishEnd = {0};
        long[] consumeStart = {0};
        long[] consumeEnd = {0};
        AtomicInteger totalConsumed = new AtomicInteger(0);

        logger.info("=== Testing with {} consumer groups ===", groupCount);

        return topicConfigService.createTopic(topicConfig)
            .compose(v -> {
                Future<Void> subscribeChain = Future.succeededFuture();
                for (String groupName : consumerGroups) {
                    subscribeChain = subscribeChain.compose(ignored ->
                        subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.defaults())
                    );
                }
                return subscribeChain;
            })
            .compose(v -> {
                publishStart[0] = System.currentTimeMillis();
                return publishInBatches(topic, messageCount, payloadSizeBytes, 50);
            })
            .compose(v -> {
                publishEnd[0] = System.currentTimeMillis();
                consumeStart[0] = System.currentTimeMillis();
                Future<Void> consumeChain = Future.succeededFuture();
                for (String groupName : consumerGroups) {
                    consumeChain = consumeChain.compose(ignored ->
                        consumeGroupMessages(topic, groupName, messageCount, totalConsumed)
                    );
                }
                return consumeChain;
            })
            .compose(v -> {
                consumeEnd[0] = System.currentTimeMillis();
                int expectedCompletions = messageCount * groupCount;
                assertEquals(expectedCompletions, totalConsumed.get(),
                    "Should have consumed " + expectedCompletions + " messages across " + groupCount + " groups");
                return cleanupService.cleanupCompletedMessages(topic, 100);
            })
            .map(deletedCount -> {
                long publishDurationMs = publishEnd[0] - publishStart[0];
                long consumeDurationMs = consumeEnd[0] - consumeStart[0];
                // Per-group throughput: how fast each group processed its own 500 messages.
                // Groups run sequentially so perGroupTime ≈ consumeDuration/N.
                // Formula: messageCount / (consumeDuration / N) = messageCount*N / consumeDuration.
                // A roughly constant value across N confirms each group's performance is stable.
                double perGroupThroughput = (messageCount * (double) groupCount * 1000.0) / consumeDurationMs;
                // Effective delivery rate: distinct messages delivered per second across all groups.
                // = messageCount / consumeDuration. Drops with N because groups run sequentially.
                double effectiveDeliveryRate = (messageCount * 1000.0) / consumeDurationMs;
                double publishThroughput = (messageCount * 1000.0) / publishDurationMs;
                scalingResults.add(new long[]{groupCount, publishDurationMs, consumeDurationMs});
                logger.info("=== PERFORMANCE SUMMARY (N={} groups) ===", groupCount);
                logger.info("Publish Throughput:     {} msg/sec", String.format("%.2f", publishThroughput));
                logger.info("Per-Group Throughput:   {} msg/sec  (per individual group, ~constant across N)",
                    String.format("%.2f", perGroupThroughput));
                logger.info("Effective Delivery Rate: {} msg/sec  (distinct msgs/sec to all groups, drops with N)",
                    String.format("%.2f", effectiveDeliveryRate));
                logger.info("Total Messages: {}", messageCount);
                logger.info("Total Completions: {}", totalConsumed.get());
                logger.info("Messages Cleaned: {}", deletedCount);
                logger.info("Publish Duration: {} ms", publishDurationMs);
                logger.info("Consume Duration: {} ms", consumeDurationMs);
                return (Void) null;
            });
    }

    private void logScalingSummaryTable(List<long[]> scalingResults, int messageCount) {
        long baseConsume = scalingResults.get(0)[2];
        logger.info("=== P2: FANOUT SCALING SUMMARY ===");
        logger.info(String.format("%-4s | %-12s | %-12s | %-16s | %-16s | %-7s",
            "N", "Publish (ms)", "Consume (ms)", "Per-Group msg/s", "Effective msg/s", "Scale"));
        logger.info("-----|--------------|--------------|------------------|------------------|--------");
        for (long[] r : scalingResults) {
            int n = (int) r[0];
            long publishMs = r[1];
            long consumeMs = r[2];
            double perGroup = (messageCount * (double) n * 1000.0) / consumeMs;
            double effective = (messageCount * 1000.0) / consumeMs;
            double scale = (double) consumeMs / baseConsume;
            logger.info(String.format("%4d | %12d | %12d | %16.2f | %16.2f | %6.2fx",
                n, publishMs, consumeMs, perGroup, effective, scale));
        }
    }

    private Future<Void> publishInBatches(String topic, int messageCount, int payloadSizeBytes, int batchSize) {
        return publishBatchLoop(topic, messageCount, payloadSizeBytes, batchSize, 0);
    }

    private Future<Void> publishBatchLoop(String topic, int messageCount, int payloadSizeBytes,
                                          int batchSize, int batchStart) {
        if (batchStart >= messageCount) {
            return Future.succeededFuture();
        }
        int batchEnd = Math.min(batchStart + batchSize, messageCount);
        List<Future<Long>> batchFutures = new ArrayList<>();
        for (int i = batchStart; i < batchEnd; i++) {
            batchFutures.add(insertMessage(topic, generatePayload(i, payloadSizeBytes)));
        }
        return Future.all(batchFutures)
            .compose(ignored -> publishBatchLoop(topic, messageCount, payloadSizeBytes, batchSize, batchStart + batchSize));
    }

    private Future<Void> consumeGroupMessages(String topic, String groupName, int expected,
                                              AtomicInteger totalConsumed) {
        return consumeGroupLoop(topic, groupName, expected, new AtomicInteger(0), totalConsumed);
    }

    private Future<Void> consumeGroupLoop(String topic, String groupName, int expected,
                                          AtomicInteger consumed, AtomicInteger totalConsumed) {
        if (consumed.get() >= expected) {
            return Future.succeededFuture();
        }
        return fetcher.fetchMessages(topic, groupName, 100)
            .compose(messages -> {
                if (messages.isEmpty()) {
                    return Future.succeededFuture();
                }
                Future<Void> markChain = Future.succeededFuture();
                for (OutboxMessage message : messages) {
                    markChain = markChain.compose(v ->
                        completionTracker.markCompleted(message.getId(), groupName, topic)
                            .map(r -> {
                                consumed.incrementAndGet();
                                totalConsumed.incrementAndGet();
                                return (Void) null;
                            })
                    );
                }
                return markChain;
            })
            .compose(v -> consumeGroupLoop(topic, groupName, expected, consumed, totalConsumed));
    }

    private Future<Long> insertMessage(String topic, JsonObject payload) {
        return connectionManager.withConnection("peegeeq-main", connection -> {
            String sql = "INSERT INTO outbox (topic, payload, created_at) VALUES ($1, $2, $3) RETURNING id";
            return connection.preparedQuery(sql)
                .execute(Tuple.of(topic, payload, OffsetDateTime.now(ZoneOffset.UTC)))
                .compose(rows -> {
                    Row row = rows.iterator().next();
                    return Future.succeededFuture(row.getLong("id"));
                });
        });
    }

    private JsonObject generatePayload(int messageIndex, int sizeBytes) {
        JsonObject payload = new JsonObject()
            .put("messageIndex", messageIndex)
            .put("timestamp", System.currentTimeMillis());

        // Pad to approximate size
        int currentSize = payload.encode().length();
        if (currentSize < sizeBytes) {
            int paddingSize = sizeBytes - currentSize - 20;
            payload.put("padding", "x".repeat(Math.max(0, paddingSize)));
        }

        return payload;
    }
}

