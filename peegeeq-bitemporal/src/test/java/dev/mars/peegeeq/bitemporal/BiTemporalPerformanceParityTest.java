package dev.mars.peegeeq.bitemporal;

import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.api.EventQuery;
import dev.mars.peegeeq.api.TemporalRange;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused parity tests that preserve benchmark functionality from the deprecated benchmark class.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
class BiTemporalPerformanceParityTest {

    private static final Logger logger = LoggerFactory.getLogger(BiTemporalPerformanceParityTest.class);

    @Container
    @SuppressWarnings("resource") // Managed by Testcontainers framework
    static PostgreSQLContainer postgres = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE)
        .withDatabaseName("peegeeq_perf_parity_test")
        .withUsername("test")
        .withPassword("test")
        .withSharedMemorySize(256 * 1024 * 1024L)
        .withReuse(false);

    private PeeGeeQManager manager;
    private PgBiTemporalEventStore<Map<String, Object>> eventStore;
    private Vertx vertx;
    private final Map<String, String> originalProperties = new HashMap<>();

    @SuppressWarnings("unchecked")
    private static Class<Map<String, Object>> mapClass() {
        return (Class<Map<String, Object>>) (Class<?>) Map.class;
    }

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext testContext) throws Exception {
        this.vertx = vertx;

        setTestProperty("peegeeq.database.host", postgres.getHost());
        setTestProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        setTestProperty("peegeeq.database.name", postgres.getDatabaseName());
        setTestProperty("peegeeq.database.username", postgres.getUsername());
        setTestProperty("peegeeq.database.password", postgres.getPassword());
        setTestProperty("peegeeq.health-check.queue-checks-enabled", "false");

        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.BITEMPORAL);

        manager = new PeeGeeQManager(new PeeGeeQConfiguration(), new SimpleMeterRegistry());

        cleanupDatabase()
            .compose(v -> manager.start())
            .compose(v -> {
                BiTemporalEventStoreFactory factory = new BiTemporalEventStoreFactory(manager);
                eventStore = (PgBiTemporalEventStore<Map<String, Object>>) factory.createEventStore(mapClass(), "bitemporal_event_log");
                return Future.succeededFuture();
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);

        awaitSuccess(testContext, 30);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws Exception {
        Future<Void> closeFuture = Future.succeededFuture();
        if (eventStore != null) {
            closeFuture = eventStore.closeFuture();
            eventStore = null;
        }

        closeFuture
            .compose(v -> {
                if (manager != null) {
                    PeeGeeQManager m = manager;
                    manager = null;
                    return m.closeReactive();
                }
                return Future.succeededFuture();
            })
            .recover(t -> Future.succeededFuture())
            .compose(v -> cleanupDatabase())
            .onSuccess(v -> {
                restoreTestProperties();
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);

        awaitSuccess(testContext, 30);
    }

    @Test
    @DisplayName("PARITY: Query Performance with Large Dataset")
    void parityQueryPerformanceWithLargeDataset(VertxTestContext testContext) throws Exception {
        int datasetSize = 1000;
        Instant baseTime = Instant.now().minusSeconds(3600);

        appendMany("QueryTest", "query", datasetSize, baseTime)
            .compose(v -> eventStore.query(EventQuery.builder().limit(5000).build()))
            .compose(allEvents -> {
                testContext.verify(() -> assertTrue(allEvents.size() >= datasetSize, "Should retrieve all populated events"));
                return eventStore.query(EventQuery.builder().eventType("QueryTest").limit(5000).build());
            })
            .compose(typeEvents -> {
                testContext.verify(() -> assertEquals(datasetSize, typeEvents.size(), "Should retrieve exact QueryTest count"));
                Instant rangeStart = baseTime.plusSeconds(200);
                Instant rangeEnd = baseTime.plusSeconds(700);
                return eventStore.query(EventQuery.builder().validTimeRange(new TemporalRange(rangeStart, rangeEnd)).build());
            })
            .onSuccess(rangeEvents -> testContext.verify(() -> {
                assertTrue(rangeEvents.size() > 0, "Time range query should return events");
                assertTrue(rangeEvents.size() <= datasetSize, "Time range query should stay bounded");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        awaitSuccess(testContext, 60);
    }

    @Test
    @DisplayName("PARITY: Memory Usage and Resource Management")
    void parityMemoryUsageAndResourceManagement(VertxTestContext testContext) throws Exception {
        Runtime runtime = Runtime.getRuntime();
        System.gc();

        delayMillis(600)
            .compose(v -> {
                long baseline = runtime.totalMemory() - runtime.freeMemory();
                int operations = 600;
                return appendMany("MemoryResourceTest", "mem-resource", operations, Instant.now())
                    .compose(v2 -> {
                        long current = runtime.totalMemory() - runtime.freeMemory();
                        long increase = current - baseline;
                        testContext.verify(() -> assertTrue(increase < 350L * 1024 * 1024,
                            "Memory increase should remain bounded"));
                        System.gc();
                        return delayMillis(600).map(v3 -> baseline);
                    });
            })
            .onSuccess(baseline -> testContext.verify(() -> {
                long finalMemory = runtime.totalMemory() - runtime.freeMemory();
                long totalIncrease = finalMemory - baseline;
                assertTrue(totalIncrease < 450L * 1024 * 1024, "Final memory increase should remain reasonable");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        awaitSuccess(testContext, 90);
    }

    @Test
    @DisplayName("PARITY: Target Throughput Validation")
    void parityTargetThroughputValidation(VertxTestContext testContext) throws Exception {
        int totalMessages = 2000;
        int batchSize = 100;
        Instant validTime = Instant.now();
        long start = System.currentTimeMillis();

        Future<Void> chain = Future.succeededFuture();
        for (int batchStart = 0; batchStart < totalMessages; batchStart += batchSize) {
            int from = batchStart;
            int end = Math.min(batchStart + batchSize, totalMessages);
            chain = chain.compose(v -> {
                List<Future<BiTemporalEvent<Map<String, Object>>>> futures = new ArrayList<>();
                for (int i = from; i < end; i++) {
                    futures.add(eventStore.appendBuilder()
                        .eventType("ThroughputParity")
                        .payload(Map.of("index", i))
                        .validTime(validTime)
                        .execute());
                }
                return Future.all(new ArrayList<>(futures)).mapEmpty();
            });
        }

        chain
            .onSuccess(v -> testContext.verify(() -> {
                long duration = System.currentTimeMillis() - start;
                double throughput = totalMessages * 1000.0 / Math.max(duration, 1);
                assertTrue(throughput > 100, "Throughput should exceed 100 msg/sec");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        awaitSuccess(testContext, 90);
    }

    @Test
    @DisplayName("PARITY: Latency Performance Analysis")
    void parityLatencyPerformanceAnalysis(VertxTestContext testContext) throws Exception {
        int messageCount = 80;
        List<Long> latenciesNs = new ArrayList<>();

        appendWithLatency(0, messageCount, latenciesNs)
            .onSuccess(v -> testContext.verify(() -> {
                List<Long> sorted = new ArrayList<>(latenciesNs);
                Collections.sort(sorted);

                double avgMs = sorted.stream().mapToLong(Long::longValue).average().orElse(0.0) / 1_000_000.0;
                double p95Ms = sorted.get((int) (sorted.size() * 0.95)) / 1_000_000.0;
                double p99Ms = sorted.get((int) (sorted.size() * 0.99)) / 1_000_000.0;

                assertTrue(avgMs < 1200, "Average latency should remain bounded");
                assertTrue(p95Ms < 2000, "P95 latency should remain bounded");
                assertTrue(p99Ms < 3000, "P99 latency should remain bounded");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        awaitSuccess(testContext, 90);
    }

    @Test
    @DisplayName("PARITY: Memory Usage Under Load")
    void parityMemoryUsageUnderLoad(VertxTestContext testContext) throws Exception {
        Runtime runtime = Runtime.getRuntime();
        System.gc();

        delayMillis(500)
            .compose(v -> {
                long initialMemory = runtime.totalMemory() - runtime.freeMemory();
                long start = System.currentTimeMillis();
                return appendMany("MemoryLoadParity", "mem-load", 800, Instant.now())
                    .map(done -> {
                        long duration = System.currentTimeMillis() - start;
                        long finalMemory = runtime.totalMemory() - runtime.freeMemory();
                        long memoryIncrease = finalMemory - initialMemory;
                        double throughput = 800 * 1000.0 / Math.max(duration, 1);

                        testContext.verify(() -> {
                            assertTrue(memoryIncrease < 500L * 1024 * 1024, "Memory increase should stay under 500MB");
                            assertTrue(throughput > 150, "Throughput under load should exceed 150 events/sec");
                        });
                        return done;
                    });
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);

        awaitSuccess(testContext, 90);
    }

    @Test
    @DisplayName("PARITY: Resource Utilization Analysis")
    void parityResourceUtilizationAnalysis(VertxTestContext testContext) throws Exception {
        Runtime runtime = Runtime.getRuntime();
        int processors = runtime.availableProcessors();
        int concurrentThreads = Math.min(processors * 2, 8);
        int totalMessages = concurrentThreads * 40;
        long initialMemory = runtime.totalMemory() - runtime.freeMemory();
        long start = System.currentTimeMillis();

        appendMany("ResourceParity", "resource", totalMessages, Instant.now())
            .onSuccess(v -> testContext.verify(() -> {
                long duration = System.currentTimeMillis() - start;
                long finalMemory = runtime.totalMemory() - runtime.freeMemory();
                long memoryIncrease = finalMemory - initialMemory;
                double throughput = totalMessages * 1000.0 / Math.max(duration, 1);
                double throughputPerCore = throughput / Math.max(processors, 1);

                assertTrue(throughput > 150, "Concurrent throughput should exceed 150 events/sec");
                assertTrue(throughputPerCore > 20, "Throughput per core should exceed 20 events/sec/core");
                assertTrue(memoryIncrease < 300L * 1024 * 1024, "Memory increase should stay under 300MB");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        awaitSuccess(testContext, 90);
    }

    @Test
    @DisplayName("PARITY: High-Throughput Validation (Batched Processing)")
    void parityHighThroughputValidationBatched(VertxTestContext testContext) throws Exception {
        int totalEvents = 5000;
        int batchSize = 250;
        long start = System.currentTimeMillis();

        appendManyBatched("HighThroughputParity", totalEvents, batchSize, Instant.now())
            .onSuccess(v -> testContext.verify(() -> {
                long duration = System.currentTimeMillis() - start;
                double throughput = totalEvents * 1000.0 / Math.max(duration, 1);
                logger.info("High-throughput parity: {} events in {} ms ({} events/sec)",
                    totalEvents, duration, String.format("%.1f", throughput));
                assertTrue(throughput > 250, "Batched throughput should exceed 250 events/sec");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        awaitSuccess(testContext, 120);
    }

    private Future<Void> appendMany(String eventType, String keyPrefix, int count, Instant baseValidTime) {
        int batchSize = 100;
        Future<Void> chain = Future.succeededFuture();
        for (int start = 0; start < count; start += batchSize) {
            int from = start;
            int end = Math.min(start + batchSize, count);
            chain = chain.compose(v -> {
                List<Future<BiTemporalEvent<Map<String, Object>>>> futures = new ArrayList<>();
                for (int i = from; i < end; i++) {
                    futures.add(eventStore.appendBuilder()
                        .eventType(eventType)
                        .payload(Map.of("id", keyPrefix + "-" + i, "value", i))
                        .validTime(baseValidTime.plusSeconds(i))
                        .execute());
                }
                return Future.all(new ArrayList<>(futures)).mapEmpty();
            });
        }
        return chain;
    }

    private Future<Void> appendManyBatched(String eventType, int totalEvents, int batchSize, Instant baseValidTime) {
        Future<Void> chain = Future.succeededFuture();
        for (int start = 0; start < totalEvents; start += batchSize) {
            int from = start;
            int end = Math.min(start + batchSize, totalEvents);
            chain = chain.compose(v -> {
                List<PgBiTemporalEventStore.BatchEventData<Map<String, Object>>> batch = new ArrayList<>();
                for (int i = from; i < end; i++) {
                    batch.add(new PgBiTemporalEventStore.BatchEventData<>(
                        eventType,
                        Map.of("id", "high-throughput-" + i, "value", i),
                        baseValidTime.plusSeconds(i),
                        Map.of("batch", "true"),
                        "corr-" + i,
                        "agg-" + i
                    ));
                }
                return eventStore.appendBatch(batch).mapEmpty();
            });
        }
        return chain;
    }

    private Future<Void> appendWithLatency(int index, int count, List<Long> latenciesNs) {
        if (index >= count) {
            return Future.succeededFuture();
        }

        long startNs = System.nanoTime();
        return eventStore.appendBuilder()
            .eventType("LatencyParity")
            .payload(Map.of("index", index))
            .validTime(Instant.now())
            .execute()
            .compose(v -> {
                latenciesNs.add(System.nanoTime() - startNs);
                return delayMillis(5);
            })
            .compose(v -> appendWithLatency(index + 1, count, latenciesNs));
    }

    private Future<Void> cleanupDatabase() {
        PgConnectOptions connectOptions = new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getFirstMappedPort())
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword());

        Pool cleanupPool = PgBuilder.pool().connectingTo(connectOptions).using(vertx).build();
        return cleanupPool.withConnection(conn ->
            conn.query("SELECT to_regclass('public.bitemporal_event_log') AS table_name").execute()
                .compose(rows -> {
                    if (!rows.iterator().hasNext() || rows.iterator().next().getValue("table_name") == null) {
                        return Future.succeededFuture();
                    }
                    return conn.query("TRUNCATE TABLE bitemporal_event_log").execute().mapEmpty();
                })
        ).compose(v -> cleanupPool.close());
    }

    private Future<Void> delayMillis(long delayMs) {
        Promise<Void> promise = Promise.promise();
        vertx.setTimer(delayMs, id -> promise.complete());
        return promise.future();
    }

    private void awaitSuccess(VertxTestContext testContext, long timeoutSeconds) {
        boolean completed;
        try {
            completed = testContext.awaitCompletion(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while awaiting asynchronous test completion", e);
        }
        assertTrue(completed, "Test timed out after " + timeoutSeconds + " seconds");
        if (testContext.failed()) {
            throw new AssertionError("Asynchronous test flow failed", testContext.causeOfFailure());
        }
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
}
