package dev.mars.peegeeq.bitemporal;

import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Promise;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
    @SuppressWarnings("resource") // Managed by Testcontainers framework
    static PostgreSQLContainer postgres = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE)
            .withDatabaseName("peegeeq_perf_test")
            .withUsername("peegeeq_test")
            .withPassword("peegeeq_test")
            .withSharedMemorySize(256 * 1024 * 1024L)
            .withReuse(false);

    private PeeGeeQManager peeGeeQManager;
    private PgBiTemporalEventStore<Map<String, Object>> eventStore;
    private final Map<String, String> originalProperties = new HashMap<>();
    private static final int BATCH_SIZE = 100;
    private static final int CONCURRENT_THREADS = 10;
    private static final int EVENTS_PER_THREAD = 10;

    @SuppressWarnings("unchecked")
    private static Class<Map<String, Object>> mapClass() {
        return (Class<Map<String, Object>>) (Class<?>) Map.class;
    }

    @BeforeEach
    void setUp(VertxTestContext testContext) throws Exception {
        logger.info("Setting up performance test...");
        configureSystemPropertiesForContainer(postgres);
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.ALL);
        createTestEventsTable();

        peeGeeQManager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());
        peeGeeQManager.start()
            .onSuccess(v -> {
                eventStore = new PgBiTemporalEventStore<>(peeGeeQManager, mapClass(), "perf_test_events", new ObjectMapper());
                logger.info("Performance test setup completed");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        if (eventStore != null) {
            eventStore.close();
        }
        Future<Void> closeFuture = (peeGeeQManager != null)
            ? peeGeeQManager.closeReactive().recover(err -> {
                logger.warn("Error during cleanup: {}", err.getMessage());
                return Future.succeededFuture();
            })
            : Future.succeededFuture();
        closeFuture.onSuccess(v -> {
            PgBiTemporalEventStore.clearCachedPools();
            restoreTestProperties();
            testContext.completeNow();
        }).onFailure(testContext::failNow);
    }

    private void configureSystemPropertiesForContainer(PostgreSQLContainer postgres) {
        setTestProperty("peegeeq.database.host", postgres.getHost());
        setTestProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        setTestProperty("peegeeq.database.name", postgres.getDatabaseName());
        setTestProperty("peegeeq.database.username", postgres.getUsername());
        setTestProperty("peegeeq.database.password", postgres.getPassword());
        setTestProperty("peegeeq.database.schema", "public");
        setTestProperty("peegeeq.database.ssl.enabled", "false");
        // Performance tests fire many concurrent appends; ensure the pool can queue them
        setTestProperty("peegeeq.database.pool.max-wait-queue-size", "256");
    }

    private void setTestProperty(String key, String value) {
        originalProperties.putIfAbsent(key, System.getProperty(key));
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private void restoreTestProperties() {
        for (Map.Entry<String, String> entry : originalProperties.entrySet()) {
            if (entry.getValue() == null) {
                System.clearProperty(entry.getKey());
            } else {
                System.setProperty(entry.getKey(), entry.getValue());
            }
        }
        originalProperties.clear();
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
                    causation_id VARCHAR(255),
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
            
            // Add notification trigger with schema-qualified channel names (matching V013 migration)
            stmt.execute("""
                CREATE OR REPLACE FUNCTION notify_perf_test_events() RETURNS TRIGGER AS $$
                DECLARE
                    schema_name TEXT;
                    table_name TEXT;
                    channel_prefix TEXT;
                    type_channel_name TEXT;
                    type_base_name TEXT;
                    hash_suffix TEXT;
                BEGIN
                    -- Get schema and table from trigger context
                    schema_name := TG_TABLE_SCHEMA;
                    table_name := TG_TABLE_NAME;
                    
                    -- Build channel prefix: {schema}_bitemporal_events_{table}
                    channel_prefix := schema_name || '_bitemporal_events_' || table_name;
                    
                    -- Send general notification
                    PERFORM pg_notify(channel_prefix, json_build_object(
                        'event_id', NEW.event_id, 'event_type', NEW.event_type,
                        'aggregate_id', NEW.aggregate_id, 'correlation_id', NEW.correlation_id,
                        'causation_id', NEW.causation_id, 'is_correction', NEW.is_correction,
                        'transaction_time', extract(epoch from NEW.transaction_time))::text);
                    
                    -- Build type-specific channel with truncation support
                    type_base_name := channel_prefix || '_' || replace(NEW.event_type, '.', '_');
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
    void testBatchAppendVsSingleAppend(VertxTestContext testContext) {
        logger.info("=== Test: Batch Append vs Single Append Performance ===");
        AtomicLong singleDurationRef = new AtomicLong();
        AtomicLong batchDurationRef = new AtomicLong();

        // Warm up
        eventStore.appendBuilder().eventType("warmup").payload(Map.of("data", "warmup"))
            .validTime(Instant.now()).execute()
            .compose(warmup -> {
                // Single append timing — sequential chain
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
            .onSuccess(batchResults -> testContext.verify(() -> {
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
            }))
            .onFailure(testContext::failNow);
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
        .onSuccess(v -> testContext.verify(() -> {
            logger.info("THROUGHPUT RESULTS:");
            logger.info("  Received {} events", receivedEvents.size());
            logger.info("  Duplicates detected: {}", duplicateCount.get());

            assertEquals(eventCount, receivedEvents.size(),
                "Should receive exactly " + eventCount + " unique events");
            assertEquals(0, duplicateCount.get(), "Should not receive duplicate events");
            testContext.completeNow();
        }))
        .onFailure(testContext::failNow);
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
            .onSuccess(cf -> testContext.verify(() -> {
                long duration = System.currentTimeMillis() - start;
                double throughput = totalEvents * 1000.0 / duration;
                logger.info("CONCURRENT RESULTS:");
                logger.info("  {} threads x {} events = {} total events", CONCURRENT_THREADS, EVENTS_PER_THREAD, totalEvents);
                logger.info("  Completed in {}ms = {} events/sec", duration, String.format("%.1f", throughput));

                assertEquals(totalEvents, allFutures.size(), "All events should be submitted");
                long successCount = allFutures.stream().filter(f -> !f.failed()).count();
                assertEquals(totalEvents, successCount, "All events should complete successfully");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    /**
     * Test 4: Wildcard vs exact match subscription performance.
     * Exact match should not be slower than wildcard (validates channel optimization).
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
        .onSuccess(v -> testContext.verify(() -> {
            long exactTotalTime = exactDurationRef.get();
            long wildcardTotalTime = System.currentTimeMillis() - wildcardStartRef.get();

            logger.info("SUBSCRIPTION PERFORMANCE RESULTS:");
            logger.info("  Exact match:  {} events received in {}ms", exactLatencies.size(), exactTotalTime);
            logger.info("  Wildcard:     {} events received in {}ms", wildcardLatencies.size(), wildcardTotalTime);

            assertEquals(eventCount, exactLatencies.size(), "Exact match should receive all events");
            assertEquals(eventCount, wildcardLatencies.size(), "Wildcard should receive all events");

            assertTrue(exactTotalTime <= wildcardTotalTime * 2,
                "Exact match should not be significantly slower than wildcard");
            testContext.completeNow();
        }))
        .onFailure(testContext::failNow);
    }
}



