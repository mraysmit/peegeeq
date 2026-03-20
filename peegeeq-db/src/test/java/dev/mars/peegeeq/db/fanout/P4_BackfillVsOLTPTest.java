package dev.mars.peegeeq.db.fanout;

import dev.mars.peegeeq.api.messaging.BackfillScope;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.cleanup.CleanupService;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.consumer.CompletionTracker;
import dev.mars.peegeeq.db.consumer.ConsumerGroupFetcher;
import dev.mars.peegeeq.db.consumer.OutboxMessage;
import dev.mars.peegeeq.db.subscription.BackfillService;
import dev.mars.peegeeq.db.subscription.BackfillService.BackfillResult;
import dev.mars.peegeeq.db.subscription.SubscriptionManager;
import dev.mars.peegeeq.db.subscription.TopicConfig;
import dev.mars.peegeeq.db.subscription.TopicConfigService;
import dev.mars.peegeeq.db.subscription.TopicSemantics;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * <p>Performance Target: OLTP p95 latency < 300ms even during backfill.
 *
 * <p><b>Implementation notes (reactive migration):</b>
 * <ul>
 *   <li>Publishing uses batches of 10 with a 5ms inter-batch delay instead of one-at-a-time
 *       recursive {@code .compose()} chains, which caused stack-depth exhaustion and hangs
 *       (~12,000 recursive levels over a 12s run).</li>
 *   <li>Message completion ({@code markCompleted}) uses {@code Future.all()} for concurrent
 *       batch processing instead of serial recursive {@code .compose()} per message, which
 *       was too slow to keep consumers draining ahead of publishers.</li>
 *   <li>Idle/stall detection limits are kept moderate (1000–2000 rounds) so failures surface
 *       quickly rather than silently waiting 30–60s before reporting a stalled loop.</li>
 *   <li>In the second test, the OLTP consumer group is subscribed <em>after</em> historical
 *       messages are seeded so it only sees newly-published messages (subscribing before
 *       seeding caused consumed &gt; published assertion mismatches).</li>
 * </ul>
 */
@Tag(TestCategories.PERFORMANCE)
@ExtendWith(VertxExtension.class)
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

    @Test
    void testBackfillVsOLTP(VertxTestContext testContext) throws Exception {
        logger.info("=== P4: BACKFILL VS OLTP TEST ===");

        String topic = "perf-test-backfill-" + UUID.randomUUID().toString().substring(0, 8);
        int historicalMessageCount = 1000;
        int oltpMessageCount = 200;
        int payloadSizeBytes = 2048;

        String backfillGroup = "backfill-consumer";
        String oltpGroup = "oltp-consumer";

        AtomicInteger backfillConsumed = new AtomicInteger(0);
        AtomicInteger oltpConsumed = new AtomicInteger(0);
        List<Long> oltpLatencies = new ArrayList<>();

        AtomicLong historicalPublishStart = new AtomicLong();
        AtomicLong historicalPublishEnd = new AtomicLong();
        AtomicLong backfillStart = new AtomicLong();
        AtomicLong backfillEnd = new AtomicLong();
        AtomicLong oltpPublishStart = new AtomicLong();
        AtomicLong oltpPublishEnd = new AtomicLong();
        AtomicLong oltpConsumeStart = new AtomicLong();
        AtomicLong oltpConsumeEnd = new AtomicLong();

        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .messageRetentionHours(1)
            .build();

        backfillStart.set(System.currentTimeMillis());

        Future<Void> backfillFuture = createTopicAndSubscriptions(topic, topicConfig, backfillGroup, oltpGroup)
            .compose(v -> {
                historicalPublishStart.set(System.currentTimeMillis());
                return publishMessagesInBatches(topic, historicalMessageCount, payloadSizeBytes);
            })
            .compose(v -> {
                historicalPublishEnd.set(System.currentTimeMillis());
                return runBackfillLoop(topic, backfillGroup, 100, historicalMessageCount, backfillConsumed);
            })
            .onSuccess(v -> {
                backfillEnd.set(System.currentTimeMillis());
                logger.info("Backfill consumer finished: {} messages", backfillConsumed.get());
            });

        Future<Void> oltpFuture = delay(100)
            .compose(v -> {
                oltpPublishStart.set(System.currentTimeMillis());
                return publishMessagesInBatches(topic, oltpMessageCount, payloadSizeBytes);
            })
            .compose(v -> {
                oltpPublishEnd.set(System.currentTimeMillis());
                oltpConsumeStart.set(System.currentTimeMillis());
                return consumeWithLatency(topic, oltpGroup, 20, oltpMessageCount, oltpConsumed, oltpLatencies, 0);
            })
            .onSuccess(v -> oltpConsumeEnd.set(System.currentTimeMillis()));

        Future.all(backfillFuture, oltpFuture)
            .compose(v -> cleanupService.cleanupCompletedMessages(topic, 100).map(deleted -> {
                List<Long> sortedLatencies = new ArrayList<>(oltpLatencies);
                sortedLatencies.sort(Long::compareTo);

                long p50 = sortedLatencies.get(sortedLatencies.size() / 2);
                long p95 = sortedLatencies.get(Math.min(sortedLatencies.size() - 1, (int) (sortedLatencies.size() * 0.95)));
                long p99 = sortedLatencies.get(Math.min(sortedLatencies.size() - 1, (int) (sortedLatencies.size() * 0.99)));

                long historicalPublishDuration = historicalPublishEnd.get() - historicalPublishStart.get();
                long oltpPublishDuration = oltpPublishEnd.get() - oltpPublishStart.get();
                long backfillDuration = backfillEnd.get() - backfillStart.get();
                long oltpConsumeDuration = oltpConsumeEnd.get() - oltpConsumeStart.get();

                testContext.verify(() -> {
                    assertEquals(historicalMessageCount, backfillConsumed.get(),
                        "Backfill consumer should process all historical messages");
                    assertEquals(oltpMessageCount, oltpConsumed.get(),
                        "OLTP consumer should process all new messages");
                    assertFalse(sortedLatencies.isEmpty(), "OLTP latencies should be captured");
                    assertTrue(p95 < 300, "OLTP p95 latency should be < 300ms, but was " + p95 + "ms");
                });

                logger.info("=== PERFORMANCE SUMMARY ===");
                logger.info("Historical Messages Published: {} in {} ms", historicalMessageCount, historicalPublishDuration);
                logger.info("OLTP Messages Published: {} in {} ms", oltpMessageCount, oltpPublishDuration);
                logger.info("Backfill Duration: {} ms ({} msg/sec)", backfillDuration,
                    (backfillConsumed.get() * 1000.0) / Math.max(1, backfillDuration));
                logger.info("OLTP Consume Duration: {} ms ({} msg/sec)", oltpConsumeDuration,
                    (oltpConsumed.get() * 1000.0) / Math.max(1, oltpConsumeDuration));
                logger.info("OLTP Latency - p50: {} ms, p95: {} ms, p99: {} ms", p50, p95, p99);
                logger.info("Messages Cleaned: {}", deleted);
                logger.info("=== P4: BACKFILL VS OLTP TEST COMPLETE ===");

                return deleted;
            }))
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(180, TimeUnit.SECONDS));
        if (testContext.failed()) {
            Throwable cause = testContext.causeOfFailure();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new RuntimeException(cause);
        }
    }

    @Test
    void testRegularBackfillDoesNotImpactNormalOLTPThroughput(VertxTestContext testContext) throws Exception {
        logger.info("=== P4: REGULAR BACKFILL VS NORMAL OLTP THROUGHPUT ===");

        String topic = "perf-regular-backfill-" + UUID.randomUUID().toString().substring(0, 8);
        String backfillGroup = "backfill-group";
        String oltpGroup = "oltp-group";

        int historicalMessageCount = 50_000;
        int completedHistoricalCount = 20_000;
        int oltpPayloadSizeBytes = 1024;
        long runDurationMs = 12_000;
        long backfillIntervalMs = 250;

        AtomicInteger published = new AtomicInteger(0);
        AtomicInteger consumed = new AtomicInteger(0);
        AtomicInteger backfillRuns = new AtomicInteger(0);
        List<Long> oltpLatencies = new CopyOnWriteArrayList<>();

        AtomicReference<Throwable> backfillError = new AtomicReference<>();
        AtomicReference<Throwable> publisherError = new AtomicReference<>();
        AtomicReference<Throwable> consumerError = new AtomicReference<>();

        AtomicLong testStartRef = new AtomicLong();

        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .messageRetentionHours(24)
            .build();

        topicConfigService.createTopic(topicConfig)
            .compose(v -> seedHistoricalMessages(topic, historicalMessageCount))
            .compose(v -> markOldestMessagesCompleted(topic, completedHistoricalCount))
            .compose(v -> subscriptionManager.subscribe(topic, backfillGroup, SubscriptionOptions.fromBeginning()).mapEmpty())
            .compose(v -> subscriptionManager.subscribe(topic, oltpGroup, SubscriptionOptions.defaults()).mapEmpty())
            .compose(v -> {
                long testStart = System.currentTimeMillis();
                testStartRef.set(testStart);
                long testEnd = testStart + runDurationMs;

                Future<Void> backfillLoop = runRegularBackfillUntil(testEnd, topic, backfillGroup, backfillIntervalMs, backfillRuns)
                    .recover(err -> {
                        backfillError.compareAndSet(null, err);
                        return Future.failedFuture(err);
                    });

                Future<Void> publisherLoop = publishUntil(testEnd, topic, oltpPayloadSizeBytes, published)
                    .recover(err -> {
                        publisherError.compareAndSet(null, err);
                        return Future.failedFuture(err);
                    });

                Future<Void> consumerLoop = consumeUntilDrained(testEnd, topic, oltpGroup, 50, published, consumed, oltpLatencies, 0)
                    .recover(err -> {
                        consumerError.compareAndSet(null, err);
                        return Future.failedFuture(err);
                    });

                return Future.all(backfillLoop, publisherLoop, consumerLoop).mapEmpty();
            })
            .compose(v -> cleanupService.cleanupCompletedMessages(topic, 5_000).map(deletedCount -> {
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

                long elapsedMs = System.currentTimeMillis() - testStartRef.get();
                double oltpThroughput = consumed.get() * 1000.0 / Math.max(1, elapsedMs);

                assertTrue(p95 < 300,
                    "OLTP p95 latency should remain < 300ms during regular backfill, got " + p95 + "ms");
                assertTrue(oltpThroughput >= 100,
                    "OLTP throughput should remain >= 100 msg/s during regular backfill, got "
                        + String.format("%.1f", oltpThroughput));

                logger.info("=== REGULAR BACKFILL VS OLTP SUMMARY ===");
                logger.info("Backfill runs: {}", backfillRuns.get());
                logger.info("Published: {}", published.get());
                logger.info("Consumed: {}", consumed.get());
                logger.info("OLTP Throughput: {} msg/s", String.format("%.1f", oltpThroughput));
                logger.info("OLTP Latency p50={}ms p95={}ms p99={}ms", p50, p95, p99);
                logger.info("Messages Cleaned: {}", deletedCount);
                logger.info("=== P4: REGULAR BACKFILL VS NORMAL OLTP COMPLETE ===");

                return deletedCount;
            }))
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(240, TimeUnit.SECONDS));
        if (testContext.failed()) {
            Throwable cause = testContext.causeOfFailure();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new RuntimeException(cause);
        }
    }

    private Future<Void> createTopicAndSubscriptions(
        String topic,
        TopicConfig topicConfig,
        String backfillGroup,
        String oltpGroup
    ) {
        return topicConfigService.createTopic(topicConfig)
            .compose(v -> subscriptionManager.subscribe(topic, backfillGroup, SubscriptionOptions.defaults()).mapEmpty())
            .compose(v -> subscriptionManager.subscribe(topic, oltpGroup, SubscriptionOptions.defaults()).mapEmpty());
    }

    private Future<Void> runBackfillLoop(
        String topic,
        String group,
        int batchSize,
        int targetCount,
        AtomicInteger consumedCount
    ) {
        if (consumedCount.get() >= targetCount) {
            return Future.succeededFuture();
        }

        return fetcher.fetchMessages(topic, group, batchSize)
            .compose(messages -> {
                if (messages.isEmpty()) {
                    return delay(10).compose(v -> runBackfillLoop(topic, group, batchSize, targetCount, consumedCount));
                }

                return markCompletedSequentially(messages, group, topic, consumedCount)
                    .compose(v -> runBackfillLoop(topic, group, batchSize, targetCount, consumedCount));
            });
    }

    private Future<Void> consumeWithLatency(
        String topic,
        String group,
        int batchSize,
        int targetCount,
        AtomicInteger consumedCount,
        List<Long> latencies,
        int emptyPolls
    ) {
        if (consumedCount.get() >= targetCount) {
            return Future.succeededFuture();
        }
        if (emptyPolls > 1000) {
            return Future.failedFuture("OLTP consume loop stalled before reaching target count");
        }

        return fetcher.fetchMessages(topic, group, batchSize)
            .compose(messages -> {
                if (messages.isEmpty()) {
                    return delay(10)
                        .compose(v -> consumeWithLatency(topic, group, batchSize, targetCount, consumedCount, latencies, emptyPolls + 1));
                }

                return markCompletedWithLatency(messages, group, topic, consumedCount, latencies)
                    .compose(v -> consumeWithLatency(topic, group, batchSize, targetCount, consumedCount, latencies, 0));
            });
    }

    private Future<Void> runRegularBackfillUntil(
        long testEndEpochMs,
        String topic,
        String backfillGroup,
        long intervalMs,
        AtomicInteger backfillRuns
    ) {
        if (System.currentTimeMillis() >= testEndEpochMs) {
            return Future.succeededFuture();
        }

        return backfillService.startBackfill(topic, backfillGroup, 2_000, 0, BackfillScope.ALL_RETAINED)
            .compose(result -> {
                if (result.status() != BackfillResult.Status.COMPLETED
                    && result.status() != BackfillResult.Status.ALREADY_COMPLETED
                    && result.status() != BackfillResult.Status.SKIPPED) {
                    return Future.failedFuture("Unexpected backfill status: " + result.status());
                }
                backfillRuns.incrementAndGet();
                return delay(intervalMs).compose(v -> runRegularBackfillUntil(testEndEpochMs, topic, backfillGroup, intervalMs, backfillRuns));
            });
    }

    private Future<Void> publishUntil(
        long testEndEpochMs,
        String topic,
        int payloadSizeBytes,
        AtomicInteger published
    ) {
        if (System.currentTimeMillis() >= testEndEpochMs) {
            return Future.succeededFuture();
        }

        int batchSize = 10;
        List<Future<?>> batchFutures = new ArrayList<>();
        for (int i = 0; i < batchSize; i++) {
            JsonObject payload = generatePayload(published.get() + i, payloadSizeBytes);
            batchFutures.add(insertMessage(topic, payload).map(id -> {
                published.incrementAndGet();
                return null;
            }));
        }

        return Future.all(batchFutures)
            .mapEmpty()
            .compose(v -> delay(5).compose(t -> publishUntil(testEndEpochMs, topic, payloadSizeBytes, published)));
    }

    private Future<Void> consumeUntilDrained(
        long testEndEpochMs,
        String topic,
        String group,
        int batchSize,
        AtomicInteger published,
        AtomicInteger consumed,
        List<Long> latencies,
        int idleRounds
    ) {
        if (System.currentTimeMillis() >= testEndEpochMs && consumed.get() >= published.get()) {
            return Future.succeededFuture();
        }
        if (idleRounds > 2000) {
            return Future.failedFuture("OLTP consumer stalled while draining published workload");
        }

        return fetcher.fetchMessages(topic, group, batchSize)
            .compose(messages -> {
                if (messages.isEmpty()) {
                    return delay(10)
                        .compose(v -> consumeUntilDrained(testEndEpochMs, topic, group, batchSize, published, consumed, latencies, idleRounds + 1));
                }

                return markCompletedWithLatency(messages, group, topic, consumed, latencies)
                    .compose(v -> consumeUntilDrained(testEndEpochMs, topic, group, batchSize, published, consumed, latencies, 0));
            });
    }

    private Future<Void> markCompletedSequentially(
        List<OutboxMessage> messages,
        String group,
        String topic,
        AtomicInteger consumedCount
    ) {
        List<Future<?>> futures = new ArrayList<>();
        for (OutboxMessage message : messages) {
            futures.add(completionTracker.markCompleted(message.getId(), group, topic)
                .map(v -> {
                    consumedCount.incrementAndGet();
                    return null;
                }));
        }
        return Future.all(futures).mapEmpty();
    }

    private Future<Void> markCompletedWithLatency(
        List<OutboxMessage> messages,
        String group,
        String topic,
        AtomicInteger consumedCount,
        List<Long> latencies
    ) {
        List<Future<?>> futures = new ArrayList<>();
        for (OutboxMessage message : messages) {
            long processStart = System.currentTimeMillis();
            futures.add(completionTracker.markCompleted(message.getId(), group, topic)
                .map(v -> {
                    latencies.add(System.currentTimeMillis() - processStart);
                    consumedCount.incrementAndGet();
                    return null;
                }));
        }
        return Future.all(futures).mapEmpty();
    }

    private Future<Void> publishMessagesInBatches(String topic, int count, int payloadSizeBytes) {
        return publishMessagesInBatches(topic, count, payloadSizeBytes, 0, 50);
    }

    private Future<Void> publishMessagesInBatches(
        String topic,
        int count,
        int payloadSizeBytes,
        int batchStart,
        int batchSize
    ) {
        if (batchStart >= count) {
            return Future.succeededFuture();
        }

        int batchEnd = Math.min(batchStart + batchSize, count);
        List<Future<?>> batchFutures = new ArrayList<>();

        for (int i = batchStart; i < batchEnd; i++) {
            JsonObject payload = generatePayload(i, payloadSizeBytes);
            batchFutures.add(insertMessage(topic, payload));
        }

        return Future.all(batchFutures)
            .mapEmpty()
            .compose(v -> publishMessagesInBatches(topic, count, payloadSizeBytes, batchEnd, batchSize));
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
                SELECT $1, ('{\"index\": ' || generate_series || '}')::jsonb, $2, 'PENDING'
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

    private Future<Void> delay(long millis) {
        Promise<Void> promise = Promise.promise();
        manager.getVertx().setTimer(millis, id -> promise.complete());
        return promise.future();
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
