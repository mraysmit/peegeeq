package dev.mars.peegeeq.bitemporal;

import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.db.EventStoreTestTables;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.HashMap;
import java.util.Properties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import dev.mars.peegeeq.test.vertx.TestCleanup;

/**
 * Performance tests for PgBiTemporalEventStore.
 * Tests batch operations, concurrent access, and subscription throughput.
 */
@Tag(TestCategories.PERFORMANCE)
@ExtendWith(VertxExtension.class)
@Testcontainers
class PgBiTemporalEventStorePerformanceTest {
    private static final Logger logger = LoggerFactory.getLogger(PgBiTemporalEventStorePerformanceTest.class);

    @Container
    @SuppressWarnings("resource") // Managed by Testcontainers framework
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private Vertx vertx;
    private PeeGeeQManager peeGeeQManager;
    private PgBiTemporalEventStore<Map<String, Object>> eventStore;
    private static final int BATCH_SIZE = 100;
    private static final int CONCURRENT_THREADS = 10;
    private static final int EVENTS_PER_THREAD = 10;
    private static final long SUBSCRIPTION_PERFORMANCE_LIMIT_MS = 15_000;

    @SuppressWarnings("unchecked")
    private static Class<Map<String, Object>> mapClass() {
        return (Class<Map<String, Object>>) (Class<?>) Map.class;
    }

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext testContext) throws Exception {
        this.vertx = vertx;
        logger.info("Setting up performance test...");

        Properties testProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.database.ssl.enabled", "false")
                .property("peegeeq.metrics.enabled", "false")
                // The largest test burst submits 100 operations concurrently. Keep the
                // three-connection test pool small while allowing that bounded burst.
                .property("peegeeq.database.pool.wait-queue-multiplier", "64")
                .build();

        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.ALL);

        // The perf_test_events store table comes from the production event-store templates.
        EventStoreTestTables.createIfMissing(vertx, postgres, PostgreSQLTestConstants.TEST_SCHEMA, "perf_test_events")
            .compose(created -> {
                peeGeeQManager = new PeeGeeQManager(new PeeGeeQConfiguration("default", testProps), new SimpleMeterRegistry());
                return peeGeeQManager.start();
            })
            .onSuccess(v -> {
                eventStore = new PgBiTemporalEventStore<>(vertx, peeGeeQManager, mapClass(), "perf_test_events", new ObjectMapper());
                logger.info("Performance test setup completed");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws Exception {
        Future<Void> closeFuture = eventStore != null
            ? eventStore.close()
            : Future.succeededFuture();
        PeeGeeQManager managerToClose = peeGeeQManager;
        eventStore = null;
        peeGeeQManager = null;

        closeFuture
            .transform(TestCleanup.cleanup(() -> managerToClose != null
                ? managerToClose.closeReactive()
                : Future.succeededFuture()))
            .transform(result -> {
                PgBiTemporalEventStore.clearCachedPools();
                return result.succeeded()
                    ? Future.<Void>succeededFuture()
                    : Future.<Void>failedFuture(result.cause());
            })
            .onSuccess(v -> {
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);

        awaitSuccess(testContext, 30);
    }

    private void awaitSuccess(VertxTestContext testContext, long timeoutSeconds) throws Exception {
        boolean completed = testContext.awaitCompletion(timeoutSeconds, TimeUnit.SECONDS);
        assertTrue(completed, "Asynchronous lifecycle operation timed out");
        if (testContext.failed()) {
            throw new AssertionError("Asynchronous lifecycle operation failed", testContext.causeOfFailure());
        }
    }




    /**
     * Test 1: Compare batch append vs single append performance.
     * Batch should be significantly faster due to reduced round-trips.
     */
    @Test
    void testBatchAppendVsSingleAppend(VertxTestContext testContext) {
        logger.info("=== Test: Batch Append vs Single Append Performance ===");
        AtomicLong singleDurationRef = new AtomicLong();
        AtomicLong batchDurationRef = new AtomicLong();

        // Warm up
        eventStore.appendBuilder().eventType("warmup").payload(Map.of("data", "warmup"))
            .validTime(Instant.now()).execute()
            .compose(warmup -> {
                // Single append timing sequential chain
                long singleStart = System.currentTimeMillis();
                Future<Void> chain = Future.succeededFuture();
                for (int i = 0; i < BATCH_SIZE; i++) {
                    final int idx = i;
                    chain = chain.compose(v -> eventStore.appendBuilder()
                        .eventType("single.event").payload(Map.of("index", idx))
                        .validTime(Instant.now()).execute().mapEmpty());
                }
                return chain.map(v -> {
                    singleDurationRef.set(System.currentTimeMillis() - singleStart);
                    return (Void) null;
                });
            })
            .compose(v -> {
                // Batch append timing
                List<PgBiTemporalEventStore.BatchEventData<Map<String, Object>>> batchEvents = new ArrayList<>();
                for (int i = 0; i < BATCH_SIZE; i++) {
                    batchEvents.add(new PgBiTemporalEventStore.BatchEventData<>(
                        "batch.event", Map.of("index", i), Instant.now(), Map.of(), null, null));
                }
                long batchStart = System.currentTimeMillis();
                return eventStore.appendBatch(batchEvents).map(results -> {
                    batchDurationRef.set(System.currentTimeMillis() - batchStart);
                    return results;
                });
            })
            .onComplete(testContext.succeeding(batchResults -> testContext.verify(() -> {
                long singleDuration = singleDurationRef.get();
                long batchDuration = batchDurationRef.get();
                double singleThroughput = BATCH_SIZE * 1000.0 / singleDuration;
                double batchThroughput = BATCH_SIZE * 1000.0 / batchDuration;

                logger.info("PERFORMANCE RESULTS:");
                logger.info("  Single append: {} events in {}ms = {} events/sec", BATCH_SIZE, singleDuration,
                    String.format("%.1f", singleThroughput));
                logger.info("  Batch append:  {} events in {}ms = {} events/sec", BATCH_SIZE, batchDuration,
                    String.format("%.1f", batchThroughput));
                logger.info("  Speedup: {}x", String.format("%.1f", batchThroughput / singleThroughput));

                assertEquals(BATCH_SIZE, batchResults.size(), "Batch should return all events");
                assertTrue(batchDuration < singleDuration, "Batch should be faster than single appends");
                testContext.completeNow();
            })));
    }

    /**
     * Test 2: Subscription throughput under load.
     * Verify subscriber can keep up with rapid event publishing.
     */
    @Test
    void testSubscriptionThroughputUnderLoad(VertxTestContext testContext) {
        logger.info("=== Test: Subscription Throughput Under Load ===");

        int eventCount = 50;
        List<BiTemporalEvent<Map<String, Object>>> receivedEvents = new CopyOnWriteArrayList<>();
        AtomicInteger duplicateCount = new AtomicInteger(0);
        Promise<Void> allExpectedNotifications = Promise.promise();

        // Subscribe before publishing
        eventStore.subscribe("throughput.test", message -> {
            BiTemporalEvent<Map<String, Object>> event = message.getPayload();
            if (receivedEvents.stream().anyMatch(e -> e.getEventId().equals(event.getEventId()))) {
                duplicateCount.incrementAndGet();
                allExpectedNotifications.tryFail("Duplicate notification for eventId=" + event.getEventId());
            } else {
                receivedEvents.add(event);
                if (receivedEvents.size() == eventCount) {
                    allExpectedNotifications.tryComplete();
                } else if (receivedEvents.size() > eventCount) {
                    allExpectedNotifications.tryFail("Received more notifications than expected");
                }
            }
            return Future.<Void>succeededFuture();
        })
        .compose(v -> {
            // Rapid-fire publish events
            List<Future<BiTemporalEvent<Map<String, Object>>>> futures = new ArrayList<>();
            for (int i = 0; i < eventCount; i++) {
                futures.add(eventStore.appendBuilder()
                    .eventType("throughput.test").payload(Map.of("index", i))
                    .validTime(Instant.now()).execute());
            }
            return Future.all(new ArrayList<>(futures));
        })
        .compose(cf -> allExpectedNotifications.future())
        .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
            logger.info("THROUGHPUT RESULTS:");
            logger.info("  Received {} events", receivedEvents.size());
            logger.info("  Duplicates detected: {}", duplicateCount.get());

            assertEquals(eventCount, receivedEvents.size(),
                "Should receive exactly " + eventCount + " unique events");
            assertEquals(0, duplicateCount.get(), "Should not receive duplicate events");
            testContext.completeNow();
        })));
    }

    /**
     * Test 3: Concurrent append throughput.
     * Verify connection pool handles concurrent writes efficiently.
     */
    @Test
    void testConcurrentAppendThroughput(VertxTestContext testContext) {
        logger.info("=== Test: Concurrent Append Throughput ===");

        int totalEvents = CONCURRENT_THREADS * EVENTS_PER_THREAD;
        List<Future<BiTemporalEvent<Map<String, Object>>>> allFutures = new ArrayList<>();

        // Fire all appends concurrently (Vert.x pipelines them)
        long start = System.currentTimeMillis();
        for (int t = 0; t < CONCURRENT_THREADS; t++) {
            for (int i = 0; i < EVENTS_PER_THREAD; i++) {
                allFutures.add(eventStore.appendBuilder()
                    .eventType("concurrent.event")
                    .payload(Map.of("thread", t, "index", i))
                    .validTime(Instant.now()).execute());
            }
        }

        Future.all(new ArrayList<>(allFutures))
            .onComplete(testContext.succeeding(cf -> testContext.verify(() -> {
                long duration = System.currentTimeMillis() - start;
                double throughput = totalEvents * 1000.0 / duration;
                logger.info("CONCURRENT RESULTS:");
                logger.info("  {} threads x {} events = {} total events", CONCURRENT_THREADS, EVENTS_PER_THREAD, totalEvents);
                logger.info("  Completed in {}ms = {} events/sec", duration, String.format("%.1f", throughput));

                assertEquals(totalEvents, allFutures.size(), "All events should be submitted");
                long successCount = allFutures.stream().filter(f -> !f.failed()).count();
                assertEquals(totalEvents, successCount, "All events should complete successfully");
                testContext.completeNow();
            })));
    }

    /**
     * Test 4: Wildcard vs exact match subscription performance.
     * Both subscription paths must remain within a fixed performance limit. Their
     * relative timings are observational because short runs are sensitive to CI scheduling.
     */
    @Test
    void testWildcardVsExactMatchSubscriptionPerformance(VertxTestContext testContext) {
        logger.info("=== Test: Wildcard vs Exact Match Subscription Performance ===");

        int eventCount = 30;
        List<Long> exactLatencies = new CopyOnWriteArrayList<>();
        List<Long> wildcardLatencies = new CopyOnWriteArrayList<>();
        AtomicLong exactDurationRef = new AtomicLong();
        AtomicLong wildcardStartRef = new AtomicLong();
        Promise<Void> exactNotificationsDone = Promise.promise();
        Promise<Void> wildcardNotificationsDone = Promise.promise();
        AtomicInteger exactNotifications = new AtomicInteger(0);
        AtomicInteger wildcardNotifications = new AtomicInteger(0);

        // Exact match subscription
        eventStore.subscribe("perf.exact.event", message -> {
            exactLatencies.add(System.currentTimeMillis());
            int current = exactNotifications.incrementAndGet();
            if (current == eventCount) {
                exactNotificationsDone.tryComplete();
            } else if (current > eventCount) {
                exactNotificationsDone.tryFail("Exact subscriber received too many notifications");
            }
            return Future.<Void>succeededFuture();
        })
        .compose(v -> {
            long exactStart = System.currentTimeMillis();
            Future<Void> chain = Future.succeededFuture();
            for (int i = 0; i < eventCount; i++) {
                final int idx = i;
                chain = chain.compose(x -> eventStore.appendBuilder()
                    .eventType("perf.exact.event").payload(Map.of("index", idx))
                    .validTime(Instant.now()).execute().mapEmpty());
            }
            return chain.compose(v2 -> exactNotificationsDone.future())
                .map(v2 -> {
                    exactDurationRef.set(System.currentTimeMillis() - exactStart);
                    return (Void) null;
                });
        })
        // Wildcard subscription
        .compose(v -> eventStore.subscribe("perf.wildcard.*", message -> {
            wildcardLatencies.add(System.currentTimeMillis());
            int current = wildcardNotifications.incrementAndGet();
            if (current == eventCount) {
                wildcardNotificationsDone.tryComplete();
            } else if (current > eventCount) {
                wildcardNotificationsDone.tryFail("Wildcard subscriber received too many notifications");
            }
            return Future.<Void>succeededFuture();
        }))
        .compose(v -> {
            wildcardStartRef.set(System.currentTimeMillis());
            Future<Void> chain = Future.succeededFuture();
            for (int i = 0; i < eventCount; i++) {
                final int idx = i;
                chain = chain.compose(x -> eventStore.appendBuilder()
                    .eventType("perf.wildcard.event").payload(Map.of("index", idx))
                    .validTime(Instant.now()).execute().mapEmpty());
            }
            return chain.compose(v2 -> wildcardNotificationsDone.future());
        })
        .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
            long exactTotalTime = exactDurationRef.get();
            long wildcardTotalTime = System.currentTimeMillis() - wildcardStartRef.get();

            logger.info("SUBSCRIPTION PERFORMANCE RESULTS:");
            logger.info("  Exact match:  {} events received in {}ms", exactLatencies.size(), exactTotalTime);
            logger.info("  Wildcard:     {} events received in {}ms", wildcardLatencies.size(), wildcardTotalTime);

            assertEquals(eventCount, exactLatencies.size(), "Exact match should receive all events");
            assertEquals(eventCount, wildcardLatencies.size(), "Wildcard should receive all events");

            assertTrue(exactTotalTime < SUBSCRIPTION_PERFORMANCE_LIMIT_MS,
                "Exact-match subscription exceeded the performance limit");
            assertTrue(wildcardTotalTime < SUBSCRIPTION_PERFORMANCE_LIMIT_MS,
                "Wildcard subscription exceeded the performance limit");
            testContext.completeNow();
        })));
    }
}
