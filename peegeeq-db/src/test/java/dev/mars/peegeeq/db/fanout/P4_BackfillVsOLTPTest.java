package dev.mars.peegeeq.db.fanout;

import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.cleanup.CleanupService;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.consumer.CompletionTracker;
import dev.mars.peegeeq.db.consumer.ConsumerGroupFetcher;
import dev.mars.peegeeq.db.consumer.OutboxMessage;
import dev.mars.peegeeq.api.messaging.BackfillScope;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.db.subscription.BackfillService;
import dev.mars.peegeeq.db.subscription.BackfillService.BackfillResult;
import dev.mars.peegeeq.db.subscription.SubscriptionManager;
import dev.mars.peegeeq.db.subscription.TopicConfig;
import dev.mars.peegeeq.db.subscription.TopicConfigService;
import dev.mars.peegeeq.db.subscription.TopicSemantics;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.util.concurrent.CompletableFuture;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
    private BackfillService backfillService;
    private ConsumerGroupFetcher fetcher;
    private CompletionTracker completionTracker;
    private CleanupService cleanupService;

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
            .maxSize(20)
            .build();

        connectionManager.getOrCreateReactivePool("peegeeq-main", connectionConfig, poolConfig);

        topicConfigService = new TopicConfigService(connectionManager, "peegeeq-main");
        subscriptionManager = new SubscriptionManager(connectionManager, "peegeeq-main");
        backfillService = new BackfillService(connectionManager, "peegeeq-main");
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
        CountDownLatch setupLatch = new CountDownLatch(1);
        topicConfigService.createTopic(topicConfig)
            .onComplete(ar -> setupLatch.countDown());
        assertTrue(setupLatch.await(10, TimeUnit.SECONDS));

        // Backfill consumer (subscribes from beginning - using defaults for simplicity)
        String backfillGroup = "backfill-consumer";
        CountDownLatch backfillSubLatch = new CountDownLatch(1);
        subscriptionManager.subscribe(topic, backfillGroup, SubscriptionOptions.defaults())
            .onComplete(ar -> backfillSubLatch.countDown());
        assertTrue(backfillSubLatch.await(10, TimeUnit.SECONDS));

        // OLTP consumer (subscribes from now)
        String oltpGroup = "oltp-consumer";
        CountDownLatch oltpSubLatch = new CountDownLatch(1);
        subscriptionManager.subscribe(topic, oltpGroup, SubscriptionOptions.defaults())
            .onComplete(ar -> oltpSubLatch.countDown());
        assertTrue(oltpSubLatch.await(10, TimeUnit.SECONDS));

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
                    CountDownLatch fetchLatch = new CountDownLatch(1);
                    AtomicReference<List<OutboxMessage>> fetchResult = new AtomicReference<>();
                    fetcher.fetchMessages(topic, backfillGroup, 100)
                        .onSuccess(fetchResult::set)
                        .onComplete(ar -> fetchLatch.countDown());
                    fetchLatch.await(30, TimeUnit.SECONDS);

                    List<OutboxMessage> messages = fetchResult.get();
                    if (messages == null || messages.isEmpty()) {
                        break;
                    }

                    for (OutboxMessage message : messages) {
                        CountDownLatch markLatch = new CountDownLatch(1);
                        completionTracker.markCompleted(message.getId(), backfillGroup, topic)
                            .onComplete(ar -> markLatch.countDown());
                        markLatch.await(10, TimeUnit.SECONDS);
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
        CountDownLatch headStartLatch = new CountDownLatch(1);
        manager.getVertx().setTimer(100, id -> headStartLatch.countDown());
        headStartLatch.await(5, TimeUnit.SECONDS);  // Give backfill a head start
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

    /**
     * Validates that a regularly running ALL_RETAINED backfill does not materially impact
     * normal OLTP throughput/latency while new messages are continuously published.
     *
     * <p>Workload model:
     * <ul>
     *   <li>Seed historical backlog (includes COMPLETED messages)</li>
     *   <li>Start periodic backfill cycles in background (every 250ms)</li>
     *   <li>Concurrently publish and consume OLTP messages for a fixed duration</li>
     *   <li>Assert OLTP p95 latency and throughput targets</li>
     * </ul>
     */
    @Test
    void testRegularBackfillDoesNotImpactNormalOLTPThroughput() throws Exception {
        logger.info("=== P4: REGULAR BACKFILL VS NORMAL OLTP THROUGHPUT ===");

        String topic = "perf-regular-backfill-" + UUID.randomUUID().toString().substring(0, 8);
        String backfillGroup = "backfill-group";
        String oltpGroup = "oltp-group";

        int historicalMessageCount = 50_000;
        int completedHistoricalCount = 20_000;
        int oltpPayloadSizeBytes = 1024;
        long runDurationMs = 12_000;
        long backfillIntervalMs = 250;

        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .messageRetentionHours(24)
            .build();
        topicConfigService.createTopic(topicConfig)
            .toCompletionStage().toCompletableFuture().get();

        // Seed historical backlog and mark a large subset as COMPLETED to exercise ALL_RETAINED path.
        seedHistoricalMessages(topic, historicalMessageCount)
            .toCompletionStage().toCompletableFuture().get();
        markOldestMessagesCompleted(topic, completedHistoricalCount)
            .toCompletionStage().toCompletableFuture().get();

        // Backfill group starts from beginning to process retained history.
        subscriptionManager.subscribe(topic, backfillGroup, SubscriptionOptions.fromBeginning())
            .toCompletionStage().toCompletableFuture().get();
        // OLTP group starts from now to represent normal live operations.
        subscriptionManager.subscribe(topic, oltpGroup, SubscriptionOptions.defaults())
            .toCompletionStage().toCompletableFuture().get();

        AtomicInteger published = new AtomicInteger(0);
        AtomicInteger consumed = new AtomicInteger(0);
        AtomicInteger backfillRuns = new AtomicInteger(0);
        List<Long> oltpLatencies = new CopyOnWriteArrayList<>();

        AtomicReference<Throwable> backfillError = new AtomicReference<>();
        AtomicReference<Throwable> publisherError = new AtomicReference<>();
        AtomicReference<Throwable> consumerError = new AtomicReference<>();

        long testStart = System.currentTimeMillis();
        long testEnd = testStart + runDurationMs;

        CompletableFuture<Void> backfillLoop = CompletableFuture.runAsync(() -> {
            while (System.currentTimeMillis() < testEnd) {
                try {
                    BackfillResult result = backfillService
                        .startBackfill(topic, backfillGroup, 2_000, 0, BackfillScope.ALL_RETAINED)
                        .toCompletionStage().toCompletableFuture().get();
                    backfillRuns.incrementAndGet();
                    if (result.status() != BackfillResult.Status.COMPLETED
                        && result.status() != BackfillResult.Status.ALREADY_COMPLETED
                        && result.status() != BackfillResult.Status.SKIPPED) {
                        throw new IllegalStateException("Unexpected backfill status: " + result.status());
                    }
                    manager.getVertx().timer(backfillIntervalMs).toCompletionStage().toCompletableFuture().join();
                } catch (Throwable t) {
                    backfillError.compareAndSet(null, t);
                    break;
                }
            }
        });

        CompletableFuture<Void> publisherLoop = CompletableFuture.runAsync(() -> {
            while (System.currentTimeMillis() < testEnd) {
                try {
                    JsonObject payload = generatePayload(published.get(), oltpPayloadSizeBytes);
                    insertMessage(topic, payload)
                        .toCompletionStage().toCompletableFuture().get();
                    published.incrementAndGet();
                    manager.getVertx().timer(5).toCompletionStage().toCompletableFuture().join();
                } catch (Throwable t) {
                    publisherError.compareAndSet(null, t);
                    break;
                }
            }
        });

        CompletableFuture<Void> consumerLoop = CompletableFuture.runAsync(() -> {
            while (System.currentTimeMillis() < testEnd || consumed.get() < published.get()) {
                try {
                    List<OutboxMessage> messages = fetcher.fetchMessages(topic, oltpGroup, 50)
                        .toCompletionStage().toCompletableFuture().get();

                    if (messages.isEmpty()) {
                        manager.getVertx().timer(10).toCompletionStage().toCompletableFuture().join();
                        continue;
                    }

                    for (OutboxMessage message : messages) {
                        long processStart = System.currentTimeMillis();
                        completionTracker.markCompleted(message.getId(), oltpGroup, topic)
                            .toCompletionStage().toCompletableFuture().get();
                        long processEnd = System.currentTimeMillis();
                        oltpLatencies.add(processEnd - processStart);
                        consumed.incrementAndGet();
                    }
                } catch (Throwable t) {
                    consumerError.compareAndSet(null, t);
                    break;
                }
            }
        });

        CompletableFuture.allOf(backfillLoop, publisherLoop, consumerLoop).get();

        assertNull(backfillError.get(), "Backfill loop should not fail");
        assertNull(publisherError.get(), "Publisher loop should not fail");
        assertNull(consumerError.get(), "Consumer loop should not fail");

        assertTrue(backfillRuns.get() >= 10,
            "Regular backfill should run repeatedly during OLTP window");
        assertTrue(published.get() >= 1_000,
            "Should publish a meaningful OLTP load, got " + published.get());
        assertEquals(published.get(), consumed.get(),
            "OLTP consumer should keep up and complete all published messages");

        assertFalse(oltpLatencies.isEmpty(), "OLTP latencies should be captured");
        List<Long> sortedLatencies = new ArrayList<>(oltpLatencies);
        sortedLatencies.sort(Long::compareTo);
        long p50 = sortedLatencies.get(sortedLatencies.size() / 2);
        long p95 = sortedLatencies.get(Math.min(sortedLatencies.size() - 1, (int) (sortedLatencies.size() * 0.95)));
        long p99 = sortedLatencies.get(Math.min(sortedLatencies.size() - 1, (int) (sortedLatencies.size() * 0.99)));

        long elapsedMs = System.currentTimeMillis() - testStart;
        double oltpThroughput = consumed.get() * 1000.0 / elapsedMs;

        // Targets aligned with existing P4 goal and practical throughput floor for this sustained workload.
        assertTrue(p95 < 300,
            "OLTP p95 latency should remain < 300ms during regular backfill, got " + p95 + "ms");
        assertTrue(oltpThroughput >= 100,
            "OLTP throughput should remain >= 100 msg/s during regular backfill, got "
                + String.format("%.1f", oltpThroughput));

        int deletedCount = cleanupService.cleanupCompletedMessages(topic, 5_000)
            .toCompletionStage().toCompletableFuture().get();

        logger.info("=== REGULAR BACKFILL VS OLTP SUMMARY ===");
        logger.info("Backfill runs: {}", backfillRuns.get());
        logger.info("Published: {}", published.get());
        logger.info("Consumed: {}", consumed.get());
        logger.info("OLTP Throughput: {} msg/s", String.format("%.1f", oltpThroughput));
        logger.info("OLTP Latency p50={}ms p95={}ms p99={}ms", p50, p95, p99);
        logger.info("Messages Cleaned: {}", deletedCount);
        logger.info("=== P4: REGULAR BACKFILL VS NORMAL OLTP COMPLETE ===");
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

    private Future<Void> seedHistoricalMessages(String topic, int count) {
        return connectionManager.withConnection("peegeeq-main", connection -> {
            String sql = """
                INSERT INTO outbox (topic, payload, created_at, status)
                SELECT $1, ('{"index": ' || generate_series || '}')::jsonb, $2, 'PENDING'
                FROM generate_series(1, $3)
                """;

            return connection.preparedQuery(sql)
                .execute(Tuple.of(topic, OffsetDateTime.now(ZoneOffset.UTC), count))
                .mapEmpty();
        });
    }

    private Future<Void> markOldestMessagesCompleted(String topic, int limit) {
        return connectionManager.withConnection("peegeeq-main", connection -> {
            String sql = """
                WITH to_complete AS (
                    SELECT id
                    FROM outbox
                    WHERE topic = $1 AND status = 'PENDING'
                    ORDER BY id ASC
                    LIMIT $2
                )
                UPDATE outbox o
                SET status = 'COMPLETED'
                FROM to_complete tc
                WHERE o.id = tc.id
                """;

            return connection.preparedQuery(sql)
                .execute(Tuple.of(topic, limit))
                .mapEmpty();
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

