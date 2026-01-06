package dev.mars.peegeeq.bitemporal;

import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.junit5.VertxExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance tests for PgBiTemporalEventStore.
 * Tests batch operations, concurrent access, and subscription throughput.
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
class PgBiTemporalEventStorePerformanceTest {
    private static final Logger logger = LoggerFactory.getLogger(PgBiTemporalEventStorePerformanceTest.class);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
            .withDatabaseName("peegeeq_perf_test")
            .withUsername("peegeeq_test")
            .withPassword("peegeeq_test")
            .withSharedMemorySize(256 * 1024 * 1024L)
            .withReuse(false);

    private PeeGeeQManager peeGeeQManager;
    private PgBiTemporalEventStore<Map<String, Object>> eventStore;
    private static final int BATCH_SIZE = 100;
    private static final int CONCURRENT_THREADS = 10;
    private static final int EVENTS_PER_THREAD = 10;

    @BeforeEach
    void setUp() throws Exception {
        logger.info("Setting up performance test...");
        configureSystemPropertiesForContainer(postgres);
        createTestEventsTable();
        
        peeGeeQManager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());
        peeGeeQManager.start();
        
        @SuppressWarnings("unchecked")
        Class<Map<String, Object>> mapClass = (Class<Map<String, Object>>) (Class<?>) Map.class;
        eventStore = new PgBiTemporalEventStore<>(peeGeeQManager, mapClass, "perf_test_events", new ObjectMapper());
        
        logger.info("Performance test setup completed");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (eventStore != null) {
            eventStore.close();
        }
        if (peeGeeQManager != null) {
            peeGeeQManager.stop();
        }
        PgBiTemporalEventStore.clearCachedPools();
    }

    private void configureSystemPropertiesForContainer(PostgreSQLContainer<?> postgres) {
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.schema", "public");
        System.setProperty("peegeeq.database.ssl.enabled", "false");
    }

    private void createTestEventsTable() throws Exception {
        logger.info("Creating perf_test_events table...");
        try (var conn = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS perf_test_events (
                    event_id VARCHAR(255) PRIMARY KEY,
                    event_type VARCHAR(255) NOT NULL,
                    aggregate_id VARCHAR(255),
                    correlation_id VARCHAR(255),
                    valid_time TIMESTAMPTZ NOT NULL,
                    transaction_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    payload JSONB NOT NULL,
                    headers JSONB NOT NULL DEFAULT '{}',
                    version BIGINT NOT NULL DEFAULT 1,
                    previous_version_id VARCHAR(255),
                    is_correction BOOLEAN NOT NULL DEFAULT FALSE,
                    correction_reason TEXT,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
            
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_perf_test_events_event_type ON perf_test_events(event_type)");
            
            // Add notification trigger with MD5 truncation for long channel names
            stmt.execute("""
                CREATE OR REPLACE FUNCTION notify_perf_test_events() RETURNS TRIGGER AS $$
                DECLARE
                    type_channel_name TEXT;
                    type_base_name TEXT;
                    hash_suffix TEXT;
                BEGIN
                    PERFORM pg_notify('bitemporal_events', json_build_object(
                        'event_id', NEW.event_id, 'event_type', NEW.event_type,
                        'aggregate_id', NEW.aggregate_id, 'correlation_id', NEW.correlation_id,
                        'causation_id', NEW.causation_id, 'is_correction', NEW.is_correction,
                        'transaction_time', extract(epoch from NEW.transaction_time))::text);
                    
                    -- Build type-specific channel with truncation support
                    type_base_name := 'bitemporal_events_' || replace(NEW.event_type, '.', '_');
                    IF length(type_base_name) > 63 THEN
                        hash_suffix := '_' || substr(md5(type_base_name), 1, 8);
                        type_channel_name := substr(type_base_name, 1, 63 - length(hash_suffix)) || hash_suffix;
                    ELSE
                        type_channel_name := type_base_name;
                    END IF;
                    
                    PERFORM pg_notify(type_channel_name,
                        json_build_object('event_id', NEW.event_id, 'event_type', NEW.event_type,
                        'aggregate_id', NEW.aggregate_id, 'correlation_id', NEW.correlation_id,
                        'causation_id', NEW.causation_id, 'is_correction', NEW.is_correction,
                        'transaction_time', extract(epoch from NEW.transaction_time))::text);
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql;
                """);
            stmt.execute("""
                DROP TRIGGER IF EXISTS trigger_notify_perf_test_events ON perf_test_events;
                CREATE TRIGGER trigger_notify_perf_test_events AFTER INSERT ON perf_test_events
                    FOR EACH ROW EXECUTE FUNCTION notify_perf_test_events();
                """);
        }
    }

    /**
     * Test 1: Compare batch append vs single append performance.
     * Batch should be significantly faster due to reduced round-trips.
     */
    @Test
    void testBatchAppendVsSingleAppend() throws Exception {
        logger.info("=== Test: Batch Append vs Single Append Performance ===");

        // Warm up
        eventStore.append("warmup", Map.of("data", "warmup"), Instant.now()).get(5, TimeUnit.SECONDS);

        // Single append timing
        long singleStart = System.currentTimeMillis();
        for (int i = 0; i < BATCH_SIZE; i++) {
            eventStore.append("single.event", Map.of("index", i), Instant.now()).get(5, TimeUnit.SECONDS);
        }
        long singleDuration = System.currentTimeMillis() - singleStart;
        double singleThroughput = BATCH_SIZE * 1000.0 / singleDuration;

        // Batch append timing
        List<PgBiTemporalEventStore.BatchEventData<Map<String, Object>>> batchEvents = new ArrayList<>();
        for (int i = 0; i < BATCH_SIZE; i++) {
            batchEvents.add(new PgBiTemporalEventStore.BatchEventData<>("batch.event", Map.of("index", i), Instant.now(),
                Map.of(), null, null));
        }

        long batchStart = System.currentTimeMillis();
        List<BiTemporalEvent<Map<String, Object>>> batchResults = eventStore.appendBatch(batchEvents)
            .get(30, TimeUnit.SECONDS);
        long batchDuration = System.currentTimeMillis() - batchStart;
        double batchThroughput = BATCH_SIZE * 1000.0 / batchDuration;

        logger.info("PERFORMANCE RESULTS:");
        logger.info("  Single append: {} events in {}ms = {:.1f} events/sec", BATCH_SIZE, singleDuration, singleThroughput);
        logger.info("  Batch append:  {} events in {}ms = {:.1f} events/sec", BATCH_SIZE, batchDuration, batchThroughput);
        logger.info("  Speedup: {:.1f}x", batchThroughput / singleThroughput);

        assertEquals(BATCH_SIZE, batchResults.size(), "Batch should return all events");
        assertTrue(batchDuration < singleDuration, "Batch should be faster than single appends");
    }

    /**
     * Test 2: Subscription throughput under load.
     * Verify subscriber can keep up with rapid event publishing.
     */
    @Test
    void testSubscriptionThroughputUnderLoad() throws Exception {
        logger.info("=== Test: Subscription Throughput Under Load ===");

        int eventCount = 50;
        CountDownLatch receivedLatch = new CountDownLatch(eventCount);
        List<BiTemporalEvent<Map<String, Object>>> receivedEvents = new CopyOnWriteArrayList<>();
        AtomicInteger duplicateCount = new AtomicInteger(0);

        // Subscribe before publishing
        eventStore.subscribe("throughput.test", message -> {
            BiTemporalEvent<Map<String, Object>> event = message.getPayload();
            if (receivedEvents.stream().anyMatch(e -> e.getEventId().equals(event.getEventId()))) {
                duplicateCount.incrementAndGet();
            } else {
                receivedEvents.add(event);
            }
            receivedLatch.countDown();
            return CompletableFuture.completedFuture(null);
        }).get(10, TimeUnit.SECONDS);

        Thread.sleep(500); // Allow subscription to stabilize

        // Rapid-fire publish events
        long publishStart = System.currentTimeMillis();
        List<CompletableFuture<BiTemporalEvent<Map<String, Object>>>> futures = new ArrayList<>();
        for (int i = 0; i < eventCount; i++) {
            futures.add(eventStore.append("throughput.test", Map.of("index", i), Instant.now()));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(30, TimeUnit.SECONDS);
        long publishDuration = System.currentTimeMillis() - publishStart;

        // Wait for all notifications
        boolean allReceived = receivedLatch.await(30, TimeUnit.SECONDS);
        long totalDuration = System.currentTimeMillis() - publishStart;

        logger.info("THROUGHPUT RESULTS:");
        logger.info("  Published {} events in {}ms", eventCount, publishDuration);
        logger.info("  Received {} events in {}ms", receivedEvents.size(), totalDuration);
        logger.info("  Duplicates detected: {}", duplicateCount.get());

        assertTrue(allReceived, "Should receive all events within timeout");
        assertEquals(eventCount, receivedEvents.size(), "Should receive exactly " + eventCount + " unique events");
        assertEquals(0, duplicateCount.get(), "Should not receive duplicate events");
    }

    /**
     * Test 3: Concurrent append throughput.
     * Verify connection pool handles concurrent writes efficiently.
     */
    @Test
    void testConcurrentAppendThroughput() throws Exception {
        logger.info("=== Test: Concurrent Append Throughput ===");

        int totalEvents = CONCURRENT_THREADS * EVENTS_PER_THREAD;
        List<CompletableFuture<BiTemporalEvent<Map<String, Object>>>> allFutures = new CopyOnWriteArrayList<>();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(CONCURRENT_THREADS);

        // Create concurrent threads
        for (int t = 0; t < CONCURRENT_THREADS; t++) {
            final int threadId = t;
            new Thread(() -> {
                try {
                    startLatch.await(); // Wait for signal to start
                    for (int i = 0; i < EVENTS_PER_THREAD; i++) {
                        CompletableFuture<BiTemporalEvent<Map<String, Object>>> future =
                            eventStore.append("concurrent.event", Map.of("thread", threadId, "index", i), Instant.now());
                        allFutures.add(future);
                    }
                } catch (Exception e) {
                    logger.error("Thread {} failed: {}", threadId, e.getMessage());
                } finally {
                    completeLatch.countDown();
                }
            }).start();
        }

        // Start all threads simultaneously
        long start = System.currentTimeMillis();
        startLatch.countDown();

        // Wait for all threads to submit
        completeLatch.await(10, TimeUnit.SECONDS);

        // Wait for all futures to complete
        CompletableFuture.allOf(allFutures.toArray(new CompletableFuture[0])).get(60, TimeUnit.SECONDS);
        long duration = System.currentTimeMillis() - start;

        double throughput = totalEvents * 1000.0 / duration;
        logger.info("CONCURRENT RESULTS:");
        logger.info("  {} threads x {} events = {} total events", CONCURRENT_THREADS, EVENTS_PER_THREAD, totalEvents);
        logger.info("  Completed in {}ms = {:.1f} events/sec", duration, throughput);

        assertEquals(totalEvents, allFutures.size(), "All events should be submitted");
        long successCount = allFutures.stream().filter(f -> !f.isCompletedExceptionally()).count();
        assertEquals(totalEvents, successCount, "All events should complete successfully");
    }

    /**
     * Test 4: Wildcard vs exact match subscription performance.
     * Exact match should not be slower than wildcard (validates channel optimization).
     */
    @Test
    void testWildcardVsExactMatchSubscriptionPerformance() throws Exception {
        logger.info("=== Test: Wildcard vs Exact Match Subscription Performance ===");

        int eventCount = 30;

        // Test exact match subscription
        CountDownLatch exactLatch = new CountDownLatch(eventCount);
        List<Long> exactLatencies = new CopyOnWriteArrayList<>();

        eventStore.subscribe("perf.exact.event", message -> {
            exactLatencies.add(System.currentTimeMillis());
            exactLatch.countDown();
            return CompletableFuture.completedFuture(null);
        }).get(10, TimeUnit.SECONDS);

        Thread.sleep(500);

        long exactPublishStart = System.currentTimeMillis();
        for (int i = 0; i < eventCount; i++) {
            eventStore.append("perf.exact.event", Map.of("index", i), Instant.now()).get(5, TimeUnit.SECONDS);
        }
        exactLatch.await(30, TimeUnit.SECONDS);
        long exactTotalTime = System.currentTimeMillis() - exactPublishStart;

        // Test wildcard subscription
        CountDownLatch wildcardLatch = new CountDownLatch(eventCount);
        List<Long> wildcardLatencies = new CopyOnWriteArrayList<>();

        eventStore.subscribe("perf.wildcard.*", message -> {
            wildcardLatencies.add(System.currentTimeMillis());
            wildcardLatch.countDown();
            return CompletableFuture.completedFuture(null);
        }).get(10, TimeUnit.SECONDS);

        Thread.sleep(500);

        long wildcardPublishStart = System.currentTimeMillis();
        for (int i = 0; i < eventCount; i++) {
            eventStore.append("perf.wildcard.event", Map.of("index", i), Instant.now()).get(5, TimeUnit.SECONDS);
        }
        wildcardLatch.await(30, TimeUnit.SECONDS);
        long wildcardTotalTime = System.currentTimeMillis() - wildcardPublishStart;

        logger.info("SUBSCRIPTION PERFORMANCE RESULTS:");
        logger.info("  Exact match:  {} events received in {}ms", exactLatencies.size(), exactTotalTime);
        logger.info("  Wildcard:     {} events received in {}ms", wildcardLatencies.size(), wildcardTotalTime);

        assertEquals(eventCount, exactLatencies.size(), "Exact match should receive all events");
        assertEquals(eventCount, wildcardLatencies.size(), "Wildcard should receive all events");

        // Exact match should not be significantly slower than wildcard (within 2x)
        assertTrue(exactTotalTime <= wildcardTotalTime * 2,
            "Exact match should not be significantly slower than wildcard");
    }
}

