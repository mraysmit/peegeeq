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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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
import java.util.concurrent.atomic.AtomicReference;

import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxTestContext;

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
 * @version 2.0 (Vert.x 5.x Reactive-Only)
 */
@Tag(TestCategories.PERFORMANCE)
@Tag(TestCategories.INTEGRATION)
@Isolated("Performance test requires exclusive database access")
public class BackfillServiceConcurrencyTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(BackfillServiceConcurrencyTest.class);

    private PgConnectionManager connectionManager;
    private TopicConfigService topicConfigService;
    private SubscriptionManager subscriptionManager;
    private BackfillService backfillService;

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

        // Larger pool for concurrency testing
        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
                .maxSize(5)
                .shared(false)
                .idleTimeout(Duration.ofSeconds(5))
                .connectionTimeout(Duration.ofSeconds(30))
                .build();

        connectionManager.getOrCreateReactivePool("peegeeq-main", connectionConfig, poolConfig);

        topicConfigService = new TopicConfigService(connectionManager, "peegeeq-main");
        subscriptionManager = new SubscriptionManager(connectionManager, "peegeeq-main");
        backfillService = new BackfillService(connectionManager, "peegeeq-main");

        logger.info("Concurrency test setup complete with pool size 50");
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

    /**
     * Test concurrent backfill attempts on the SAME subscription should be safely serialized
     * by the FOR UPDATE row lock.
     */
    @Test
    @Timeout(60)
    void testConcurrentBackfillSameSubscription_PreventsDuplicateProcessing(VertxTestContext testContext) {
        String topic = "test-concurrent-same-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "concurrent-group-1";
        int concurrentAttempts = 5;
        Checkpoint latch = testContext.checkpoint(concurrentAttempts);

        // Setup phase
        setupTopicAndMessages(topic, 1000)
                .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.fromBeginning()))
                .compose(v -> {
                    // Launch 5 concurrent backfill attempts
                    List<Future<BackfillResult>> futures = new ArrayList<>();
                    for (int i = 0; i < concurrentAttempts; i++) {
                        futures.add(backfillService.startBackfill(topic, groupName, 100, 0)
                                .onSuccess(result -> {
                                    logger.info("Backfill attempt completed with status: {}, processed: {}",
                                            result.status(), result.processedMessages());
                                    latch.flag();
                                })
                                .onFailure(err -> {
                                    logger.warn("Backfill attempt failed: {}", err.getMessage());
                                    latch.flag();
                                }));
                    }
                    return Future.all(futures).mapEmpty();
                })
                .compose(v -> {
                    // Verify database state: no duplicate tracking rows
                    return countTrackingRows(topic, groupName)
                            .map(count -> {
                                assertEquals(1000L, count,
                                        "Should have exactly 1000 tracking rows (no duplicates)");
                                return null;
                            });
                })
                .compose(v -> backfillService.getBackfillProgress(topic, groupName)
                        .map(progressOpt -> {
                            progressOpt.ifPresent(progress -> {
                                assertEquals("COMPLETED", progress.status());
                                assertEquals(1000L, progress.processedMessages());
                            });
                            return null;
                        }))
                .onSuccess(v -> {
                    logger.info("Concurrent same-subscription test passed: race condition prevented by FOR UPDATE");
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
    }

    /**
     * Test backfilling multiple different subscriptions concurrently (independent workloads).
     */
    @Test
    @Timeout(90)
    void testConcurrentBackfillDifferentSubscriptions(VertxTestContext testContext) {
        String baseTopic = "test-concurrent-diff-" + UUID.randomUUID().toString().substring(0, 8);
        int numSubscriptions = 10;
        int messagesPerTopic = 500;
        List<String> topics = new ArrayList<>();
        List<String> groups = new ArrayList<>();

        // Prepare topic and group lists
        for (int i = 0; i < numSubscriptions; i++) {
            topics.add(baseTopic + "-" + i);
            groups.add("group-" + i);
        }

        // Setup all topics sequentially, then launch backfills concurrently
        setupAllTopicsSequential(topics, groups, messagesPerTopic)
                .compose(v -> {
                    logger.info("Testing {} concurrent independent backfills", numSubscriptions);
                    long startTime = System.currentTimeMillis();
                    
                    // Launch all backfills concurrently
                    List<Future<BackfillResult>> backfillFutures = new ArrayList<>();
                    for (int i = 0; i < numSubscriptions; i++) {
                        backfillFutures.add(
                                backfillService.startBackfill(topics.get(i), groups.get(i), 100, 0)
                        );
                    }
                    
                    return Future.all(backfillFutures)
                            .map(list -> {
                                long elapsed = System.currentTimeMillis() - startTime;
                                logger.info("All {} concurrent backfills completed in {} ms", numSubscriptions, elapsed);
                                
                                // Verify all completed successfully
                                List<?> results = list.list();
                                for (int i = 0; i < numSubscriptions; i++) {
                                    BackfillResult result = (BackfillResult) results.get(i);
                                    assertEquals(BackfillResult.Status.COMPLETED, result.status(),
                                            "Backfill " + i + " should complete");
                                    assertEquals(messagesPerTopic, result.processedMessages(),
                                            "Backfill " + i + " should process all messages");
                                }
                                return null;
                            });
                })
                .onSuccess(v -> {
                    logger.info("Concurrent different-subscriptions test passed");
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
    }

    /**
     * Test heavy load: backfill 100,000 messages in batches.
     * Validates performance and batch processing efficiency.
     */
    @Test
    @Timeout(300)
    void testBackfillHeavyLoad_100kMessages(VertxTestContext testContext) {
        String topic = "test-heavy-load-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "heavy-load-group";
        int messageCount = 100_000;
        int batchSize = 10_000;
        AtomicReference<Long> setupStartRef = new AtomicReference<>();
        AtomicReference<Long> backfillStartRef = new AtomicReference<>();

        setupStartRef.set(System.currentTimeMillis());
        
        setupTopicAndMessages(topic, messageCount)
                .compose(v -> {
                    long setupTime = System.currentTimeMillis() - setupStartRef.get();
                    logger.info("Setup completed in {} ms ({} msgs/sec)",
                            setupTime, (long) (messageCount * 1000.0 / setupTime));
                    return subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.fromBeginning());
                })
                .compose(v -> {
                    logger.info("Starting backfill of {} messages with batch size {}", messageCount, batchSize);
                    backfillStartRef.set(System.currentTimeMillis());
                    return backfillService.startBackfill(topic, groupName, batchSize, 0);
                })
                .compose(result -> {
                    long backfillTime = System.currentTimeMillis() - backfillStartRef.get();
                    double throughput = messageCount * 1000.0 / backfillTime;

                    logger.info("Backfill Results:");
                    logger.info("  Status: {}", result.status());
                    logger.info("  Processed: {} messages", result.processedMessages());
                    logger.info("  Time: {} ms", backfillTime);
                    logger.info("  Throughput: {} msgs/sec", String.format("%.1f", throughput));
                    logger.info("  Batches: ~{}", messageCount / batchSize);

                    assertEquals(BackfillResult.Status.COMPLETED, result.status());
                    assertEquals(messageCount, result.processedMessages());
                    assertTrue(throughput >= 1000,
                            "Throughput should be >= 1000 msgs/sec, got " + String.format("%.1f", throughput));

                    // Verify database state
                    return countTrackingRows(topic, groupName)
                            .map(trackingRows -> {
                                assertEquals(messageCount, trackingRows, "Should have tracking rows for all messages");
                                logger.info("Heavy load test passed: {} messages backfilled at {} msgs/sec",
                                        messageCount, String.format("%.1f", throughput));
                                return null;
                            });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    /**
     * Test that row-level locking prevents concurrent batch processing of the same subscription.
     * This is a stress test of the FOR UPDATE locking.
     */
    @Test
    @Timeout(60)
    void testRowLevelLocking_PreventsConcurrentBatches(VertxTestContext testContext) {
        String topic = "test-row-lock-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "row-lock-group";
        int batchSize = 100;
        int concurrentWorkers = 3;

        setupTopicAndMessages(topic, 2000)
                .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.fromBeginning()))
                .compose(v -> {
                    // Launch 3 concurrent backfills with small batch size
                    List<Future<BackfillResult>> futures = new ArrayList<>();
                    for (int i = 0; i < concurrentWorkers; i++) {
                        futures.add(backfillService.startBackfill(topic, groupName, batchSize, 0));
                    }
                    return Future.all(futures).mapEmpty();
                })
                .compose(v -> countTrackingRows(topic, groupName)
                        .map(trackingRows -> {
                            assertEquals(2000L, trackingRows,
                                    "Should have exactly 2000 tracking rows (no duplicates from concurrent batches)");
                            return null;
                        }))
                .compose(v -> backfillService.getBackfillProgress(topic, groupName)
                        .map(progressOpt -> {
                            progressOpt.ifPresent(progress -> {
                                assertEquals("COMPLETED", progress.status());
                                assertEquals(2000L, progress.processedMessages());
                            });
                            return null;
                        }))
                .onSuccess(v -> {
                    logger.info("Row-level locking test passed: no duplicate processing detected");
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
    }

    /**
     * Test backfill resumability under simulated failure (cancel mid-batch and resume).
     */
    @Test
    @Timeout(60)
    void testResumabilityUnderLoad(VertxTestContext testContext) {
        String topic = "test-resume-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "resume-group";

        setupTopicAndMessages(topic, 5000)
                .compose(v -> subscriptionManager.subscribe(topic, groupName, SubscriptionOptions.fromBeginning()))
                .compose(v -> {
                    // Start backfill, then pause with timer, then cancel
                    Future<BackfillResult> backfillFuture = backfillService.startBackfill(topic, groupName, 500, 0);
                    
                    // Wait 500ms for it to process some batches, then cancel
                    return manager.getVertx().timer(500)
                            .compose(v2 -> backfillService.cancelBackfill(topic, groupName))
                            .compose(v2 -> {
                                // Give cancellation time to take effect
                                return manager.getVertx().timer(100);
                            })
                            .map(v2 -> null);
                })
                .compose(v -> backfillService.getBackfillProgress(topic, groupName)
                        .map(progressOpt -> {
                            progressOpt.ifPresent(progress ->
                                    logger.info("Progress after cancel: status={}, processed={}",
                                            progress.status(), progress.processedMessages()));
                            return null;
                        }))
                .compose(v -> {
                    // Resume backfill
                    return backfillService.startBackfill(topic, groupName, 500, 0)
                            .map(result -> {
                                assertTrue(
                                        result.status() == BackfillResult.Status.COMPLETED ||
                                                result.status() == BackfillResult.Status.ALREADY_COMPLETED,
                                        "Expected COMPLETED or ALREADY_COMPLETED but was: " + result.status());
                                return null;
                            });
                })
                .compose(v -> backfillService.getBackfillProgress(topic, groupName)
                        .map(progressOpt -> {
                            progressOpt.ifPresent(progress -> {
                                assertEquals("COMPLETED", progress.status());
                                assertEquals(5000L, progress.processedMessages());
                            });
                            return null;
                        }))
                .compose(v -> countTrackingRows(topic, groupName)
                        .map(trackingRows -> {
                            assertEquals(5000L, trackingRows,
                                    "Should have no duplicate tracking rows after resume");
                            return null;
                        }))
                .onSuccess(v -> {
                    logger.info("Resumability test passed");
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    /**
     * Set up a topic with initial message group subscription and bulk insert messages.
     * Returns Future<Void> - fully async, no blocking.
     */
    private Future<Void> setupTopicAndMessages(String topic, int messageCount) {
        return topicConfigService.createTopic(TopicConfig.builder()
                        .topic(topic)
                        .semantics(TopicSemantics.PUB_SUB)
                        .messageRetentionHours(24)
                        .build())
                .compose(v -> subscriptionManager.subscribe(
                        topic,
                        "initial-group-" + UUID.randomUUID().toString().substring(0, 4),
                        SubscriptionOptions.defaults()))
                .compose(v -> insertMessagesBulk(topic, messageCount));
    }

    /**
     * Set up multiple topics sequentially, then subscribe each with its group.
     */
    private Future<Void> setupAllTopicsSequential(List<String> topics, List<String> groups, int messagesPerTopic) {
        if (topics.isEmpty()) {
            return Future.succeededFuture();
        }

        // Chain all setups sequentially using recursion/iteration
        Future<Void> chain = Future.succeededFuture();
        for (int i = 0; i < topics.size(); i++) {
            final int idx = i;
            String topic = topics.get(i);
            String group = groups.get(i);
            
            chain = chain.compose(v -> setupTopicAndMessages(topic, messagesPerTopic))
                    .compose(v -> subscriptionManager.subscribe(topic, group, SubscriptionOptions.fromBeginning()));
        }
        return chain;
    }

    /**
     * Bulk insert messages into the outbox table.
     * Returns Future<Void> - fully async, no blocking.
     */
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

    /**
     * Count tracking rows for a topic and group.
     * Returns Future<Long> - fully async, no blocking.
     */
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