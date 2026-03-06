package dev.mars.peegeeq.db.fanout;

import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.db.subscription.BackfillService;
import dev.mars.peegeeq.db.subscription.BackfillService.BackfillProgress;
import dev.mars.peegeeq.db.subscription.BackfillService.BackfillResult;
import dev.mars.peegeeq.db.subscription.SubscriptionManager;
import dev.mars.peegeeq.db.subscription.TopicConfig;
import dev.mars.peegeeq.db.subscription.TopicConfigService;
import dev.mars.peegeeq.db.subscription.TopicSemantics;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrency and performance tests for {@link BackfillService}.
 * 
 * <p>These tests validate that the BackfillService changes handle:
 * <ul>
 *   <li>High concurrency - multiple backfills running simultaneously</li>
 *   <li>Race conditions - concurrent attempts to backfill same subscription</li>
 *   <li>Heavy load - large message volumes (100k+ messages)</li>
 *   <li>Performance degradation - batch processing efficiency at scale</li>
 * </ul>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-03-02
 * @version 1.0
 */
@Tag(TestCategories.PERFORMANCE)
@Tag(TestCategories.INTEGRATION)
public class BackfillServiceConcurrencyTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(BackfillServiceConcurrencyTest.class);

    private PgConnectionManager connectionManager;
    private TopicConfigService topicConfigService;
    private SubscriptionManager subscriptionManager;
    private BackfillService backfillService;

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

        // Larger pool for concurrency testing
        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
                .maxSize(50)
                .build();

        connectionManager.getOrCreateReactivePool("peegeeq-main", connectionConfig, poolConfig);

        topicConfigService = new TopicConfigService(connectionManager, "peegeeq-main");
        subscriptionManager = new SubscriptionManager(connectionManager, "peegeeq-main");
        backfillService = new BackfillService(connectionManager, "peegeeq-main");

        logger.info("Concurrency test setup complete with pool size 50");
    }

    /**
     * Test concurrent backfill attempts on the SAME subscription should be safely serialized
     * by the FOR UPDATE row lock.
     */
    @Test
    @Timeout(60)
    void testConcurrentBackfillSameSubscription_PreventsDuplicateProcessing() throws Exception {
        String topic = "test-concurrent-same-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "concurrent-group-1";

        // Setup
        setupTopicAndMessages(topic, 1000);
        subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.fromBeginning())
                .toCompletionStage().toCompletableFuture().get();

        // Launch 5 concurrent backfill attempts on same subscription
        int concurrentAttempts = 5;
        CountDownLatch latch = new CountDownLatch(concurrentAttempts);
        AtomicInteger completedCount = new AtomicInteger(0);
        AtomicInteger failedCount = new AtomicInteger(0);
        List<Long> processedCounts = new ArrayList<>();

        ExecutorService executor = Executors.newFixedThreadPool(concurrentAttempts);
        try {
            for (int i = 0; i < concurrentAttempts; i++) {
                final int attemptNum = i;
                executor.submit(() -> {
                    try {
                        logger.info("Attempt {} starting backfill", attemptNum);
                        BackfillResult result = backfillService.startBackfill(topic, groupName, 100, 0)
                                .toCompletionStage().toCompletableFuture().get();
                        
                        synchronized (processedCounts) {
                            processedCounts.add(result.processedMessages());
                        }
                        
                        if (result.status() == BackfillResult.Status.COMPLETED) {
                            completedCount.incrementAndGet();
                            logger.info("Attempt {} completed: {} messages", attemptNum, result.processedMessages());
                        }
                    } catch (Exception e) {
                        failedCount.incrementAndGet();
                        logger.warn("Attempt {} failed: {}", attemptNum, e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(45, TimeUnit.SECONDS), "All backfill attempts should complete");

            // Validate: Only ONE should have done the work, others should see ALREADY_COMPLETED
            logger.info("Results: completed={}, failed={}, processedCounts={}", 
                    completedCount.get(), failedCount.get(), processedCounts);

            // Should have at least one completion
            assertTrue(completedCount.get() >= 1, "At least one backfill should complete");
            
            // All successful attempts should report the same count (1000 messages)
            long expectedCount = 1000;
            for (Long count : processedCounts) {
                if (count > 0) {
                    assertEquals(expectedCount, count, 
                            "Each completed backfill should process all " + expectedCount + " messages");
                }
            }

            // Verify database state: messages should be processed exactly once
            Long totalTracking = countTrackingRows(topic, groupName)
                    .toCompletionStage().toCompletableFuture().get();
            assertEquals(expectedCount, totalTracking, 
                    "Should have exactly " + expectedCount + " tracking rows (no duplicates)");

            BackfillProgress progress = backfillService.getBackfillProgress(topic, groupName)
                    .toCompletionStage().toCompletableFuture().get().orElseThrow();
            assertEquals("COMPLETED", progress.status());
            assertEquals(expectedCount, progress.processedMessages());

            logger.info("✅ Concurrent same-subscription test passed: race condition prevented by FOR UPDATE");

        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Test backfilling multiple different subscriptions concurrently (independent workloads).
     */
    @Test
    @Timeout(90)
    void testConcurrentBackfillDifferentSubscriptions() throws Exception {
        String baseTopic = "test-concurrent-diff-" + UUID.randomUUID().toString().substring(0, 8);
        int numSubscriptions = 10;
        int messagesPerTopic = 500;

        // Setup multiple topics with messages
        List<String> topics = new ArrayList<>();
        List<String> groups = new ArrayList<>();
        
        for (int i = 0; i < numSubscriptions; i++) {
            String topic = baseTopic + "-" + i;
            String group = "group-" + i;
            topics.add(topic);
            groups.add(group);
            
            setupTopicAndMessages(topic, messagesPerTopic);
            subscriptionManager.subscribe(topic, group, SubscriptionOptions.fromBeginning())
                    .toCompletionStage().toCompletableFuture().get();
        }

        logger.info("Testing {} concurrent independent backfills", numSubscriptions);

        // Launch all backfills concurrently
        List<CompletableFuture<BackfillResult>> futures = new ArrayList<>();
        long startTime = System.currentTimeMillis();

        for (int i = 0; i < numSubscriptions; i++) {
            String topic = topics.get(i);
            String group = groups.get(i);
            futures.add(backfillService.startBackfill(topic, group, 100, 0)
                    .toCompletionStage().toCompletableFuture());
        }

        // Wait for all to complete
        CompletableFuture<Void> allOf = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0]));
        allOf.get(60, TimeUnit.SECONDS);

        long elapsed = System.currentTimeMillis() - startTime;
        logger.info("All {} concurrent backfills completed in {} ms", numSubscriptions, elapsed);

        // Verify all completed successfully
        for (int i = 0; i < numSubscriptions; i++) {
            BackfillResult result = futures.get(i).get();
            assertEquals(BackfillResult.Status.COMPLETED, result.status(),
                    "Backfill " + i + " should complete");
            assertEquals(messagesPerTopic, result.processedMessages(),
                    "Backfill " + i + " should process all messages");
        }

        logger.info("✅ Concurrent different-subscriptions test passed");
    }

    /**
     * Test heavy load: backfill 100,000 messages in batches.
     * Validates performance and batch processing efficiency.
     */
    @Test
    @Timeout(300)
    void testBackfillHeavyLoad_100kMessages() throws Exception {
        String topic = "test-heavy-load-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "heavy-load-group";

        // This is the big one - 100k messages
        int messageCount = 100_000;
        int batchSize = 10_000;

        logger.info("Setting up {} messages for heavy load test", messageCount);
        long setupStart = System.currentTimeMillis();
        
        setupTopicAndMessages(topic, messageCount);
        
        long setupTime = System.currentTimeMillis() - setupStart;
        logger.info("Setup completed in {} ms ({} msgs/sec)", 
                setupTime, (long)(messageCount * 1000.0 / setupTime));

        subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.fromBeginning())
                .toCompletionStage().toCompletableFuture().get();

        // Run backfill and measure performance
        logger.info("Starting backfill of {} messages with batch size {}", messageCount, batchSize);
        long backfillStart = System.currentTimeMillis();
        
        BackfillResult result = backfillService.startBackfill(topic, groupName, batchSize, 0)
                .toCompletionStage().toCompletableFuture().get();
        
        long backfillTime = System.currentTimeMillis() - backfillStart;
        double throughput = messageCount * 1000.0 / backfillTime;

        logger.info("Backfill Results:");
        logger.info("  Status: {}", result.status());
        logger.info("  Processed: {} messages", result.processedMessages());
        logger.info("  Time: {} ms", backfillTime);
        logger.info("  Throughput: {} msgs/sec", String.format("%.1f", throughput));
        logger.info("  Batches: ~{}", messageCount / batchSize);

        assertEquals(BackfillResult.Status.COMPLETED, result.status());
        assertEquals(messageCount, result.processedMessages());
        
        // Performance assertion: should handle at least 1,000 msgs/sec
        assertTrue(throughput >= 1000, 
                "Throughput should be >= 1000 msgs/sec, got " + String.format("%.1f", throughput));

        // Verify database state
        Long trackingRows = countTrackingRows(topic, groupName)
                .toCompletionStage().toCompletableFuture().get();
        assertEquals(messageCount, trackingRows, "Should have tracking rows for all messages");

        logger.info("✅ Heavy load test passed: {} messages backfilled at {} msgs/sec",
        messageCount, String.format("%.1f", throughput));
    }

    /**
     * Test that row-level locking prevents concurrent batch processing of the same subscription.
     * This is a stress test of the FOR UPDATE locking.
     */
    @Test
    @Timeout(60)
    void testRowLevelLocking_PreventsConcurrentBatches() throws Exception {
        String topic = "test-row-lock-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "row-lock-group";

        setupTopicAndMessages(topic, 2000);
        subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.fromBeginning())
                .toCompletionStage().toCompletableFuture().get();

        // Use small batch size to create many batches
        int batchSize = 100;  // Will create 20 batches

        // Launch 3 concurrent backfills
        int concurrentWorkers = 3;
        List<Future<BackfillResult>> futures = new ArrayList<>();
        
        for (int i = 0; i < concurrentWorkers; i++) {
            futures.add(backfillService.startBackfill(topic, groupName, batchSize, 0));
        }

        // Wait for all
        Future.all(futures)
                .toCompletionStage().toCompletableFuture().get(45, TimeUnit.SECONDS);

        // Verify: No duplicate processing
        Long trackingRows = countTrackingRows(topic, groupName)
                .toCompletionStage().toCompletableFuture().get();
        assertEquals(2000L, trackingRows, 
                "Should have exactly 2000 tracking rows (no duplicates from concurrent batches)");

        BackfillProgress progress = backfillService.getBackfillProgress(topic, groupName)
                .toCompletionStage().toCompletableFuture().get().orElseThrow();
        assertEquals("COMPLETED", progress.status());
        assertEquals(2000L, progress.processedMessages());

        logger.info("✅ Row-level locking test passed: no duplicate processing detected");
    }

    /**
     * Test backfill resumability under simulated failure (cancel mid-batch and resume).
     */
    @Test
    @Timeout(60)
    void testResumabilityUnderLoad() throws Exception {
        String topic = "test-resume-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "resume-group";

        setupTopicAndMessages(topic, 5000);
        subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.fromBeginning())
                .toCompletionStage().toCompletableFuture().get();

        // Start backfill with small batches
        Future<BackfillResult> future1 = backfillService.startBackfill(topic, groupName, 500, 0);
        
        // Give it time to process some batches
        Thread.sleep(500);
        
        // Cancel mid-flight
        backfillService.cancelBackfill(topic, groupName)
                .toCompletionStage().toCompletableFuture().get();

        // Wait for cancellation to take effect
        try {
            future1.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // May fail or cancel, that's fine
        }

        // Check partial progress
        BackfillProgress midProgress = backfillService.getBackfillProgress(topic, groupName)
                .toCompletionStage().toCompletableFuture().get().orElseThrow();
        
        logger.info("Progress after cancel: status={}, processed={}", 
                midProgress.status(), midProgress.processedMessages());

        // Resume backfill - either the cancel landed (CANCELLED → resume → COMPLETED)
        // or the first run finished before the cancel arrived (COMPLETED → ALREADY_COMPLETED).
        // Both are valid outcomes; what matters is all 5000 messages are processed.
        BackfillResult result = backfillService.startBackfill(topic, groupName, 500, 0)
                .toCompletionStage().toCompletableFuture().get();

        assertTrue(
                result.status() == BackfillResult.Status.COMPLETED ||
                result.status() == BackfillResult.Status.ALREADY_COMPLETED,
                "Expected COMPLETED or ALREADY_COMPLETED but was: " + result.status());

        // Verify final state via progress (not via BackfillResult, which may carry stale counts)
        BackfillProgress finalProgress = backfillService.getBackfillProgress(topic, groupName)
                .toCompletionStage().toCompletableFuture().get().orElseThrow();
        assertEquals("COMPLETED", finalProgress.status());
        assertEquals(5000L, finalProgress.processedMessages());

        // Verify no duplicates
        Long trackingRows = countTrackingRows(topic, groupName)
                .toCompletionStage().toCompletableFuture().get();
        assertEquals(5000L, trackingRows, "Should have no duplicate tracking rows after resume");

        logger.info("✅ Resumability test passed");
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private void setupTopicAndMessages(String topic, int messageCount) throws Exception {
        topicConfigService.createTopic(TopicConfig.builder()
                        .topic(topic)
                        .semantics(TopicSemantics.PUB_SUB)
                        .messageRetentionHours(24)
                        .build())
                .toCompletionStage().toCompletableFuture().get();

        // Subscribe an initial group so messages get required_consumer_groups = 1
        subscriptionManager.subscribe(topic, "initial-group-" + UUID.randomUUID().toString().substring(0, 4), 
                        SubscriptionOptions.defaults())
                .toCompletionStage().toCompletableFuture().get();

        // Bulk insert messages
        insertMessagesBulk(topic, messageCount).toCompletionStage().toCompletableFuture().get();
    }

    private Future<Void> insertMessagesBulk(String topic, int count) {
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

    private Future<Long> countTrackingRows(String topic, String groupName) {
        return connectionManager.withConnection("peegeeq-main", connection -> {
            String sql = """
                SELECT COUNT(*) AS cnt
                FROM outbox o
                JOIN outbox_consumer_groups cg ON o.id = cg.message_id
                WHERE o.topic = $1 AND cg.group_name = $2
                """;
            return connection.preparedQuery(sql)
                    .execute(Tuple.of(topic, groupName))
                    .map(rows -> rows.iterator().next().getLong("cnt"));
        });
    }
}
