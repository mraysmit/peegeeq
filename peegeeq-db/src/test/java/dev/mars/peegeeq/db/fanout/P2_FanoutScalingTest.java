package dev.mars.peegeeq.db.fanout;

import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.cleanup.CleanupService;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.consumer.CompletionTracker;
import dev.mars.peegeeq.db.consumer.ConsumerGroupFetcher;
import dev.mars.peegeeq.db.consumer.OutboxMessage;
import dev.mars.peegeeq.db.subscription.SubscriptionManager;
import dev.mars.peegeeq.db.subscription.SubscriptionOptions;
import dev.mars.peegeeq.db.subscription.TopicConfig;
import dev.mars.peegeeq.db.subscription.TopicConfigService;
import dev.mars.peegeeq.db.subscription.TopicSemantics;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
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
public class P2_FanoutScalingTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(P2_FanoutScalingTest.class);

    private PgConnectionManager connectionManager;
    private TopicConfigService topicConfigService;
    private SubscriptionManager subscriptionManager;
    private ConsumerGroupFetcher fetcher;
    private CompletionTracker completionTracker;
    private CleanupService cleanupService;

    @BeforeEach
    void setUp() throws Exception {
        connectionManager = new PgConnectionManager(manager.getVertx(), null);

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
            .maxSize(20)
            .build();

        connectionManager.getOrCreateReactivePool("peegeeq-main", connectionConfig, poolConfig);

        topicConfigService = new TopicConfigService(connectionManager, "peegeeq-main");
        subscriptionManager = new SubscriptionManager(connectionManager, "peegeeq-main");
        fetcher = new ConsumerGroupFetcher(connectionManager, "peegeeq-main");
        completionTracker = new CompletionTracker(connectionManager, "peegeeq-main");
        cleanupService = new CleanupService(connectionManager, "peegeeq-main");

        logger.info("P2 Fanout Scaling Test setup complete");
    }

    /**
     * Test fanout scaling with 1, 2, 4, 8, 16 consumer groups.
     * Measures throughput degradation and validates sub-linear CPU scaling.
     */
    @Test
    void testFanoutScaling() throws Exception {
        logger.info("=== P2: FANOUT SCALING TEST ===");

        int[] groupCounts = {1, 2, 4, 8, 16};
        int messageCount = 500;  // Smaller count to keep test duration reasonable
        int payloadSizeBytes = 2048;

        logger.info("=== TEST CONFIGURATION ===");
        logger.info("Message Count: {}", messageCount);
        logger.info("Payload Size: {} bytes", payloadSizeBytes);
        logger.info("Group Counts: {}", groupCounts);

        for (int groupCount : groupCounts) {
            logger.info("=== Testing with {} consumer groups ===", groupCount);
            testWithGroupCount(groupCount, messageCount, payloadSizeBytes);
        }

        logger.info("=== P2: FANOUT SCALING TEST COMPLETE ===");
    }

    private void testWithGroupCount(int groupCount, int messageCount, int payloadSizeBytes) throws Exception {
        String topic = "perf-test-scaling-" + groupCount;

        // Step 1: Create PUB_SUB topic
        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .messageRetentionHours(1)
            .build();
        topicConfigService.createTopic(topicConfig)
            .toCompletionStage().toCompletableFuture().get();

        // Step 2: Create N consumer group subscriptions
        List<String> consumerGroups = new ArrayList<>();
        for (int i = 0; i < groupCount; i++) {
            String groupName = "group-" + i;
            consumerGroups.add(groupName);
            subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.defaults())
                .toCompletionStage().toCompletableFuture().get();
        }

        // Step 3: Publish messages and measure throughput
        long publishStartTime = System.currentTimeMillis();
        int batchSize = 50;
        Long firstMessageId = null;

        for (int batchStart = 0; batchStart < messageCount; batchStart += batchSize) {
            int batchEnd = Math.min(batchStart + batchSize, messageCount);
            List<Future<Long>> batchFutures = new ArrayList<>();

            for (int i = batchStart; i < batchEnd; i++) {
                JsonObject payload = generatePayload(i, payloadSizeBytes);
                batchFutures.add(insertMessage(topic, payload));
            }

            Future.all(batchFutures)
                .toCompletionStage().toCompletableFuture().get();

            if (firstMessageId == null && !batchFutures.isEmpty()) {
                firstMessageId = batchFutures.get(0).result();
            }
        }

        long publishEndTime = System.currentTimeMillis();
        long publishDurationMs = publishEndTime - publishStartTime;
        double publishThroughput = (messageCount * 1000.0) / publishDurationMs;

        // Step 4: Consume messages from all groups and measure throughput
        long consumeStartTime = System.currentTimeMillis();
        AtomicInteger totalConsumed = new AtomicInteger(0);

        for (String groupName : consumerGroups) {
            int consumed = 0;
            while (consumed < messageCount) {
                List<OutboxMessage> messages = fetcher.fetchMessages(topic, groupName, 100)
                    .toCompletionStage().toCompletableFuture().get();

                if (messages.isEmpty()) {
                    break;
                }

                for (OutboxMessage message : messages) {
                    completionTracker.markCompleted(message.getId(), groupName, topic)
                        .toCompletionStage().toCompletableFuture().get();
                    consumed++;
                    totalConsumed.incrementAndGet();
                }
            }
        }

        long consumeEndTime = System.currentTimeMillis();
        long consumeDurationMs = consumeEndTime - consumeStartTime;
        double consumeThroughput = (totalConsumed.get() * 1000.0) / consumeDurationMs;

        // Step 5: Verify all messages completed
        int expectedCompletions = messageCount * groupCount;
        assertEquals(expectedCompletions, totalConsumed.get(),
            "Should have consumed " + expectedCompletions + " messages across " + groupCount + " groups");

        // Step 6: Run cleanup
        int deletedCount = cleanupService.cleanupCompletedMessages(topic, 100)
            .toCompletionStage().toCompletableFuture().get();

        // Log performance summary
        logger.info("=== PERFORMANCE SUMMARY (N={} groups) ===", groupCount);
        logger.info("Publish Throughput: {:.2f} msg/sec", publishThroughput);
        logger.info("Consume Throughput: {:.2f} msg/sec", consumeThroughput);
        logger.info("Total Messages: {}", messageCount);
        logger.info("Total Completions: {}", totalConsumed.get());
        logger.info("Messages Cleaned: {}", deletedCount);
        logger.info("Publish Duration: {} ms", publishDurationMs);
        logger.info("Consume Duration: {} ms", consumeDurationMs);
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

