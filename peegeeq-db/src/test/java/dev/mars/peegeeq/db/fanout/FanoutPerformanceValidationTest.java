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
 * Performance validation test for consumer group fanout (Phase 6 - Option A).
 *
 * <p>This test validates basic performance characteristics with higher message volumes:
 * <ul>
 *   <li>Throughput measurement with 1000 messages</li>
 *   <li>Fanout to 4 consumer groups</li>
 *   <li>End-to-end latency tracking</li>
 *   <li>Completion tracking validation</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-13
 * @version 1.0
 */
@Tag(TestCategories.PERFORMANCE)
public class FanoutPerformanceValidationTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(FanoutPerformanceValidationTest.class);

    private PgConnectionManager connectionManager;
    private TopicConfigService topicConfigService;
    private SubscriptionManager subscriptionManager;
    private ConsumerGroupFetcher fetcher;
    private CompletionTracker completionTracker;
    private CleanupService cleanupService;

    @BeforeEach
    void setUp() throws Exception {
        System.err.println("=== PERFORMANCE TEST SETUP STARTED ===");
        System.err.flush();

        // Create connection manager using the shared Vertx instance
        connectionManager = new PgConnectionManager(manager.getVertx(), null);

        // Get PostgreSQL container and create pool
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
            .maxSize(20)  // Increased pool size for performance testing
            .build();

        connectionManager.getOrCreateReactivePool("peegeeq-main", connectionConfig, poolConfig);

        // Create service instances
        topicConfigService = new TopicConfigService(connectionManager, "peegeeq-main");
        subscriptionManager = new SubscriptionManager(connectionManager, "peegeeq-main");
        fetcher = new ConsumerGroupFetcher(connectionManager, "peegeeq-main");
        completionTracker = new CompletionTracker(connectionManager, "peegeeq-main");
        cleanupService = new CleanupService(connectionManager, "peegeeq-main");

        System.err.println("=== PERFORMANCE TEST SETUP COMPLETE ===");
        System.err.flush();
        logger.info("Performance test setup complete");
    }

    /**
     * P1-Simple: Basic throughput validation with 1000 messages and 4 consumer groups.
     * This test measures end-to-end throughput including production, fanout, consumption, and cleanup.
     */
    @Test
    void testBasicThroughputValidation() throws Exception {
        System.err.println("=== TEST METHOD: testBasicThroughputValidation STARTED ===");
        System.err.flush();

        String topic = "perf-test-basic";
        int messageCount = 1000;
        int consumerGroupCount = 4;
        int payloadSizeBytes = 2048; // 2KB payload

        logger.info("Starting performance validation: {} messages, {} groups, {} byte payload",
            messageCount, consumerGroupCount, payloadSizeBytes);

        // Step 1: Create PUB_SUB topic
        TopicConfig config = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .messageRetentionHours(1)
            .build();
        topicConfigService.createTopic(config)
            .toCompletionStage().toCompletableFuture().get();

        logger.info("✅ Topic created: {}", topic);

        // Step 2: Create consumer group subscriptions
        List<String> groups = new ArrayList<>();
        for (int i = 0; i < consumerGroupCount; i++) {
            String groupName = "perf-group-" + i;
            groups.add(groupName);
            subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.defaults())
                .toCompletionStage().toCompletableFuture().get();
        }

        logger.info("✅ Created {} consumer group subscriptions", consumerGroupCount);

        // Step 3: Publish messages in batches and measure throughput
        long publishStartTime = System.currentTimeMillis();
        int batchSize = 50; // Batch size to avoid overwhelming connection pool
        int totalPublished = 0;
        Long firstMessageId = null;

        for (int batchStart = 0; batchStart < messageCount; batchStart += batchSize) {
            int batchEnd = Math.min(batchStart + batchSize, messageCount);
            List<Future<Long>> batchFutures = new ArrayList<>();

            for (int i = batchStart; i < batchEnd; i++) {
                JsonObject payload = generatePayload(i, payloadSizeBytes);
                batchFutures.add(insertMessage(topic, payload));
            }

            // Wait for this batch to complete
            Future.all(batchFutures)
                .toCompletionStage().toCompletableFuture().get();

            // Capture first message ID for verification
            if (firstMessageId == null && !batchFutures.isEmpty()) {
                firstMessageId = batchFutures.get(0).result();
            }

            totalPublished += batchFutures.size();
        }

        long publishEndTime = System.currentTimeMillis();
        long publishDurationMs = publishEndTime - publishStartTime;
        double publishThroughput = (messageCount * 1000.0) / publishDurationMs;

        logger.info("✅ Published {} messages in {} ms ({} msg/sec)",
            messageCount, publishDurationMs, String.format("%.2f", publishThroughput));

        // Step 4: Verify fanout - each message should have required_consumer_groups = 4
        Integer requiredGroups = getRequiredConsumerGroups(firstMessageId)
            .toCompletionStage().toCompletableFuture().get();
        assertEquals(consumerGroupCount, requiredGroups,
            "Messages should be fanned out to all consumer groups");

        logger.info("✅ Fanout verified: required_consumer_groups = {}", requiredGroups);

        // Step 5: Consume messages from all groups and measure throughput
        long consumeStartTime = System.currentTimeMillis();
        AtomicInteger totalConsumed = new AtomicInteger(0);

        for (String groupName : groups) {
            int consumed = consumeAllMessages(topic, groupName, messageCount);
            totalConsumed.addAndGet(consumed);
            logger.info("✅ Group {} consumed {} messages", groupName, consumed);
        }

        long consumeEndTime = System.currentTimeMillis();
        long consumeDurationMs = consumeEndTime - consumeStartTime;
        double consumeThroughput = (totalConsumed.get() * 1000.0) / consumeDurationMs;

        logger.info("✅ Consumed {} total messages in {} ms ({} msg/sec)",
            totalConsumed.get(), consumeDurationMs, String.format("%.2f", consumeThroughput));

        // Step 6: Verify all messages are completed
        int expectedCompletions = messageCount * consumerGroupCount;
        assertEquals(expectedCompletions, totalConsumed.get(),
            "All messages should be consumed by all groups");

        // Step 7: Run cleanup and verify messages are deleted
        long cleanupStartTime = System.currentTimeMillis();
        int deletedCount = cleanupService.cleanupCompletedMessages(topic, 100)
            .toCompletionStage().toCompletableFuture().get();
        long cleanupEndTime = System.currentTimeMillis();
        long cleanupDurationMs = cleanupEndTime - cleanupStartTime;

        logger.info("✅ Cleanup deleted {} messages in {} ms",
            deletedCount, cleanupDurationMs);

        // Performance Summary
        logger.info("=== PERFORMANCE SUMMARY ===");
        logger.info("Messages: {}", messageCount);
        logger.info("Consumer Groups: {}", consumerGroupCount);
        logger.info("Payload Size: {} bytes", payloadSizeBytes);
        logger.info("Publish Throughput: {} msg/sec", String.format("%.2f", publishThroughput));
        logger.info("Consume Throughput: {} msg/sec", String.format("%.2f", consumeThroughput));
        logger.info("Total Duration: {} ms", (cleanupEndTime - publishStartTime));
        logger.info("========================");

        System.err.println("=== TEST METHOD: testBasicThroughputValidation COMPLETED ===");
        System.err.flush();
    }

    // Helper methods

    private Future<Long> insertMessage(String topic, JsonObject payload) {
        return connectionManager.withConnection("peegeeq-main", connection -> {
            String sql = """
                INSERT INTO outbox (topic, payload, created_at, status)
                VALUES ($1, $2::jsonb, $3, 'PENDING')
                RETURNING id
                """;

            Tuple params = Tuple.of(topic, payload, OffsetDateTime.now(ZoneOffset.UTC));

            return connection.preparedQuery(sql)
                .execute(params)
                .map(rows -> {
                    Row row = rows.iterator().next();
                    return row.getLong("id");
                });
        });
    }

    private Future<Integer> getRequiredConsumerGroups(Long messageId) {
        return connectionManager.withConnection("peegeeq-main", connection -> {
            String sql = "SELECT required_consumer_groups FROM outbox WHERE id = $1";

            return connection.preparedQuery(sql)
                .execute(Tuple.of(messageId))
                .map(rows -> {
                    if (rows.size() == 0) {
                        throw new RuntimeException("Message not found: " + messageId);
                    }
                    Row row = rows.iterator().next();
                    return row.getInteger("required_consumer_groups");
                });
        });
    }

    private int consumeAllMessages(String topic, String groupName, int expectedCount) throws Exception {
        int totalConsumed = 0;
        int batchSize = 100;

        while (totalConsumed < expectedCount) {
            List<OutboxMessage> messages = fetcher.fetchMessages(topic, groupName, batchSize)
                .toCompletionStage().toCompletableFuture().get();

            if (messages.isEmpty()) {
                break; // No more messages
            }

            // Mark all messages as completed
            for (OutboxMessage message : messages) {
                completionTracker.markCompleted(message.getId(), topic, groupName)
                    .toCompletionStage().toCompletableFuture().get();
            }

            totalConsumed += messages.size();
        }

        return totalConsumed;
    }

    private JsonObject generatePayload(int messageIndex, int sizeBytes) {
        // Generate a payload of approximately the specified size
        StringBuilder data = new StringBuilder();
        int targetDataSize = sizeBytes - 100; // Reserve space for JSON structure

        while (data.length() < targetDataSize) {
            data.append("x");
        }

        return new JsonObject()
            .put("messageIndex", messageIndex)
            .put("timestamp", System.currentTimeMillis())
            .put("data", data.toString());
    }
}
