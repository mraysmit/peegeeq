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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P4: Backfill vs OLTP Test - Validates that backfill operations don't degrade OLTP performance.
 *
 * <p>This test simulates:
 * <ul>
 *   <li>A backfill consumer catching up on historical messages (large batch, old messages)</li>
 *   <li>OLTP consumers processing recent messages (small batches, new messages)</li>
 *   <li>Both running concurrently without interference</li>
 *   <li>OLTP latency should remain low even during backfill</li>
 * </ul>
 *
 * <p>Performance Target: OLTP p95 latency < 300ms even during backfill
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-13
 * @version 1.0
 */
@Tag(TestCategories.PERFORMANCE)
public class P4_BackfillVsOLTPTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(P4_BackfillVsOLTPTest.class);

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

        logger.info("P4 Backfill vs OLTP Test setup complete");
    }

    /**
     * Test backfill consumer catching up while OLTP consumers process new messages.
     * Validates that OLTP performance is not degraded by backfill operations.
     */
    @Test
    void testBackfillVsOLTP() throws Exception {
        logger.info("=== P4: BACKFILL VS OLTP TEST ===");

        // Use unique topic name to avoid conflicts in parallel test execution
        String topic = "perf-test-backfill-" + UUID.randomUUID().toString().substring(0, 8);
        int historicalMessageCount = 1000;  // Backfill workload
        int oltpMessageCount = 200;         // OLTP workload
        int payloadSizeBytes = 2048;

        logger.info("=== TEST CONFIGURATION ===");
        logger.info("Topic: {}", topic);
        logger.info("Historical Messages (backfill): {}", historicalMessageCount);
        logger.info("OLTP Messages: {}", oltpMessageCount);
        logger.info("Payload Size: {} bytes", payloadSizeBytes);

        // Step 1: Create PUB_SUB topic with 2 consumer groups
        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .messageRetentionHours(1)
            .build();
        topicConfigService.createTopic(topicConfig)
            .toCompletionStage().toCompletableFuture().get();

        // Backfill consumer (subscribes from beginning - using defaults for simplicity)
        String backfillGroup = "backfill-consumer";
        subscriptionManager.subscribe(topic, backfillGroup, SubscriptionOptions.defaults())
            .toCompletionStage().toCompletableFuture().get();

        // OLTP consumer (subscribes from now)
        String oltpGroup = "oltp-consumer";
        subscriptionManager.subscribe(topic, oltpGroup, SubscriptionOptions.defaults())
            .toCompletionStage().toCompletableFuture().get();

        // Step 2: Publish historical messages (simulating backlog)
        logger.info("Publishing {} historical messages...", historicalMessageCount);
        long historicalPublishStart = System.currentTimeMillis();

        publishMessagesInBatches(topic, historicalMessageCount, payloadSizeBytes);

        long historicalPublishEnd = System.currentTimeMillis();
        logger.info("Historical messages published in {} ms", historicalPublishEnd - historicalPublishStart);

        // Step 3: Start backfill consumer (large batches, catching up)
        logger.info("Starting backfill consumer...");
        long backfillStart = System.currentTimeMillis();
        AtomicInteger backfillConsumed = new AtomicInteger(0);

        // Backfill in background (simulated by consuming in large batches)
        Thread backfillThread = new Thread(() -> {
            try {
                while (backfillConsumed.get() < historicalMessageCount) {
                    List<OutboxMessage> messages = fetcher.fetchMessages(topic, backfillGroup, 100)
                        .toCompletionStage().toCompletableFuture().get();

                    if (messages.isEmpty()) {
                        break;
                    }

                    for (OutboxMessage message : messages) {
                        completionTracker.markCompleted(message.getId(), backfillGroup, topic)
                            .toCompletionStage().toCompletableFuture().get();
                        backfillConsumed.incrementAndGet();
                    }
                }
                logger.info("Backfill consumer finished: {} messages", backfillConsumed.get());
            } catch (Exception e) {
                logger.error("Backfill consumer error", e);
            }
        });
        backfillThread.start();

        // Step 4: Publish OLTP messages while backfill is running
        Thread.sleep(100);  // Give backfill a head start
        logger.info("Publishing {} OLTP messages while backfill is running...", oltpMessageCount);
        long oltpPublishStart = System.currentTimeMillis();

        publishMessagesInBatches(topic, oltpMessageCount, payloadSizeBytes);

        long oltpPublishEnd = System.currentTimeMillis();
        long oltpPublishDuration = oltpPublishEnd - oltpPublishStart;

        // Step 5: Consume OLTP messages (should be fast even during backfill)
        logger.info("OLTP consumer processing new messages...");
        long oltpConsumeStart = System.currentTimeMillis();
        AtomicInteger oltpConsumed = new AtomicInteger(0);
        List<Long> oltpLatencies = new ArrayList<>();

        while (oltpConsumed.get() < oltpMessageCount) {
            List<OutboxMessage> messages = fetcher.fetchMessages(topic, oltpGroup, 20)
                .toCompletionStage().toCompletableFuture().get();

            if (messages.isEmpty()) {
                break;
            }

            for (OutboxMessage message : messages) {
                long processStart = System.currentTimeMillis();
                completionTracker.markCompleted(message.getId(), oltpGroup, topic)
                    .toCompletionStage().toCompletableFuture().get();
                long processEnd = System.currentTimeMillis();

                oltpLatencies.add(processEnd - processStart);
                oltpConsumed.incrementAndGet();
            }
        }

        long oltpConsumeEnd = System.currentTimeMillis();
        long oltpConsumeDuration = oltpConsumeEnd - oltpConsumeStart;

        // Wait for backfill to complete
        backfillThread.join(30000);  // 30 second timeout
        long backfillEnd = System.currentTimeMillis();
        long backfillDuration = backfillEnd - backfillStart;

        // Step 6: Calculate OLTP latency percentiles
        oltpLatencies.sort(Long::compareTo);
        long p50 = oltpLatencies.get(oltpLatencies.size() / 2);
        long p95 = oltpLatencies.get((int) (oltpLatencies.size() * 0.95));
        long p99 = oltpLatencies.get((int) (oltpLatencies.size() * 0.99));

        // Step 7: Validate performance
        assertEquals(historicalMessageCount, backfillConsumed.get(),
            "Backfill consumer should process all historical messages");
        assertEquals(oltpMessageCount, oltpConsumed.get(),
            "OLTP consumer should process all new messages");

        // Performance assertion: OLTP p95 latency should be < 300ms even during backfill
        assertTrue(p95 < 300, "OLTP p95 latency should be < 300ms, but was " + p95 + "ms");

        // Step 8: Cleanup
        int deletedCount = cleanupService.cleanupCompletedMessages(topic, 100)
            .toCompletionStage().toCompletableFuture().get();

        // Log performance summary
        logger.info("=== PERFORMANCE SUMMARY ===");
        logger.info("Historical Messages Published: {} in {} ms", historicalMessageCount, historicalPublishEnd - historicalPublishStart);
        logger.info("OLTP Messages Published: {} in {} ms", oltpMessageCount, oltpPublishDuration);
        logger.info("Backfill Duration: {} ms ({} msg/sec)", backfillDuration, (backfillConsumed.get() * 1000.0) / backfillDuration);
        logger.info("OLTP Consume Duration: {} ms ({} msg/sec)", oltpConsumeDuration, (oltpConsumed.get() * 1000.0) / oltpConsumeDuration);
        logger.info("OLTP Latency - p50: {} ms, p95: {} ms, p99: {} ms", p50, p95, p99);
        logger.info("Messages Cleaned: {}", deletedCount);
        logger.info("=== P4: BACKFILL VS OLTP TEST COMPLETE ===");
    }

    private void publishMessagesInBatches(String topic, int count, int payloadSizeBytes) throws Exception {
        int batchSize = 50;

        for (int batchStart = 0; batchStart < count; batchStart += batchSize) {
            int batchEnd = Math.min(batchStart + batchSize, count);
            List<Future<Long>> batchFutures = new ArrayList<>();

            for (int i = batchStart; i < batchEnd; i++) {
                JsonObject payload = generatePayload(i, payloadSizeBytes);
                batchFutures.add(insertMessage(topic, payload));
            }

            Future.all(batchFutures)
                .toCompletionStage().toCompletableFuture().get();
        }
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

        int currentSize = payload.encode().length();
        if (currentSize < sizeBytes) {
            int paddingSize = sizeBytes - currentSize - 20;
            payload.put("padding", "x".repeat(Math.max(0, paddingSize)));
        }

        return payload;
    }
}

