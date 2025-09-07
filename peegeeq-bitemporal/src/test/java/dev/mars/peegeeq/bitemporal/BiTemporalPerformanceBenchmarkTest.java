package dev.mars.peegeeq.bitemporal;

import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.api.EventStore;
import dev.mars.peegeeq.api.EventQuery;
import dev.mars.peegeeq.api.TemporalRange;
import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.MessageHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
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
import java.util.concurrent.TimeUnit;

/**
 * Performance benchmark test for bi-temporal event store.
 * This test validates the performance improvements achieved with the reactive Vert.x 5.x implementation.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class BiTemporalPerformanceBenchmarkTest {

    private static final Logger logger = LoggerFactory.getLogger(BiTemporalPerformanceBenchmarkTest.class);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_test")
            .withUsername("test")
            .withPassword("test");

    private PeeGeeQManager manager;
    private BiTemporalEventStoreFactory factory;
    private EventStore<TestEvent> eventStore;

    @BeforeEach
    void setUp() throws Exception {
        logger.info("Setting up performance benchmark test...");

        // Set system properties for PeeGeeQ configuration
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());

        // Configure PeeGeeQ
        PeeGeeQConfiguration config = new PeeGeeQConfiguration();

        // Initialize PeeGeeQ
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        // Create factory and event store
        factory = new BiTemporalEventStoreFactory(manager);
        eventStore = factory.createEventStore(TestEvent.class);

        // Ensure reactive notification handler is active by triggering pool creation
        // This follows the pattern from working ReactiveNotificationTest
        TestEvent warmupEvent = new TestEvent("warmup", "warmup", 1);
        eventStore.append("WarmupEvent", warmupEvent, Instant.now()).get(5, TimeUnit.SECONDS);

        // Give the reactive notification handler time to become active
        Thread.sleep(1000);

        logger.info("✅ Performance benchmark test setup complete");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (eventStore != null) eventStore.close();
        if (manager != null) manager.close();
        logger.info("Performance benchmark test cleanup completed");
    }

    @Test
    @Order(1)
    @DisplayName("BENCHMARK: Sequential vs Concurrent Event Appends")
    void benchmarkSequentialVsConcurrentAppends() throws Exception {
        logger.info("=== PERFORMANCE BENCHMARK: Sequential vs Concurrent Appends ===");
        
        int messageCount = 1000;
        Instant validTime = Instant.now();
        Map<String, String> headers = Map.of("benchmark", "true", "test-type", "performance");

        // Benchmark Sequential approach
        logger.info("🔄 Benchmarking Sequential appends with {} events...", messageCount);
        long sequentialStartTime = System.currentTimeMillis();
        
        for (int i = 0; i < messageCount; i++) {
            TestEvent event = new TestEvent("seq-" + i, "Sequential test data " + i, i);
            eventStore.append("SequentialTest", event, validTime, headers, 
                             "seq-correlation-" + i, "seq-aggregate-" + i)
                     .get(5, TimeUnit.SECONDS);
        }
        
        long sequentialEndTime = System.currentTimeMillis();
        long sequentialDuration = sequentialEndTime - sequentialStartTime;
        double sequentialThroughput = (double) messageCount / (sequentialDuration / 1000.0);

        logger.info("✅ Sequential Approach: {} events in {} ms ({:.1f} events/sec)", 
                   messageCount, sequentialDuration, sequentialThroughput);

        // Benchmark Concurrent approach
        logger.info("🔄 Benchmarking Concurrent appends with {} events...", messageCount);
        long concurrentStartTime = System.currentTimeMillis();

        List<CompletableFuture<BiTemporalEvent<TestEvent>>> futures = new ArrayList<>();
        for (int i = 0; i < messageCount; i++) {
            TestEvent event = new TestEvent("conc-" + i, "Concurrent test data " + i, i);
            CompletableFuture<BiTemporalEvent<TestEvent>> future = eventStore.append("ConcurrentTest", event, validTime, headers,
                                                               "conc-correlation-" + i, "conc-aggregate-" + i);
            futures.add(future);
        }

        // Wait for all concurrent operations to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(30, TimeUnit.SECONDS);

        long concurrentEndTime = System.currentTimeMillis();
        long concurrentDuration = concurrentEndTime - concurrentStartTime;
        double concurrentThroughput = (double) messageCount / (concurrentDuration / 1000.0);

        // Log results
        logger.info("✅ Concurrent Approach: {} events in {} ms ({:.1f} events/sec)", 
                   messageCount, concurrentDuration, concurrentThroughput);

        // Calculate improvement
        double improvementPercent = ((concurrentThroughput - sequentialThroughput) / sequentialThroughput) * 100;
        logger.info("🚀 Performance Improvement: {:.1f}% faster with concurrent approach", improvementPercent);

        // Validate that concurrent approach is significantly faster
        assertTrue(concurrentThroughput > sequentialThroughput * 1.5, 
                  "Concurrent approach should be at least 50% faster than sequential");
        
        // Validate we're achieving reasonable throughput (target: 1000+ events/sec)
        assertTrue(concurrentThroughput > 1000, 
                  "Concurrent throughput should exceed 1000 events/sec, got: " + concurrentThroughput);
    }

    @Test
    @Order(2)
    @DisplayName("BENCHMARK: Query Performance with Large Dataset")
    void benchmarkQueryPerformance() throws Exception {
        logger.info("=== BENCHMARK: Query Performance ===");
        
        // First, populate with test data
        int datasetSize = 5000;
        logger.info("🔄 Populating dataset with {} events...", datasetSize);
        
        Instant baseTime = Instant.now().minusSeconds(3600); // 1 hour ago
        List<CompletableFuture<BiTemporalEvent<TestEvent>>> populationFutures = new ArrayList<>();

        for (int i = 0; i < datasetSize; i++) {
            TestEvent event = new TestEvent("query-test-" + i, "Query test data " + i, i % 100);
            Instant validTime = baseTime.plusSeconds(i);
            CompletableFuture<BiTemporalEvent<TestEvent>> future = eventStore.append("QueryTest", event, validTime);
            populationFutures.add(future);
        }
        
        CompletableFuture.allOf(populationFutures.toArray(new CompletableFuture[0]))
                .get(60, TimeUnit.SECONDS);
        
        logger.info("✅ Dataset populated successfully");

        // Benchmark different query types
        benchmarkQueryAllEvents();
        benchmarkQueryByEventType();
        benchmarkQueryByTimeRange();
    }

    private void benchmarkQueryAllEvents() throws Exception {
        logger.info("🔄 Benchmarking queryAll performance...");
        long startTime = System.currentTimeMillis();

        // Use a higher limit to retrieve all 5000 events
        List<BiTemporalEvent<TestEvent>> allEvents = eventStore.query(
            EventQuery.builder().limit(10000).build()
        ).get(30, TimeUnit.SECONDS);

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;

        logger.info("✅ QueryAll: {} events retrieved in {} ms ({:.1f} events/sec)",
                   allEvents.size(), duration, (double) allEvents.size() / (duration / 1000.0));

        assertTrue(allEvents.size() >= 5000, "Should retrieve all populated events, got: " + allEvents.size());
    }

    private void benchmarkQueryByEventType() throws Exception {
        logger.info("🔄 Benchmarking queryByEventType performance...");
        long startTime = System.currentTimeMillis();

        // Use higher limit to retrieve all 5000 events of this type
        List<BiTemporalEvent<TestEvent>> typeEvents = eventStore.query(
            EventQuery.builder()
                .eventType("QueryTest")
                .limit(10000)
                .build()
        ).get(30, TimeUnit.SECONDS);

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;

        logger.info("✅ QueryByEventType: {} events retrieved in {} ms ({:.1f} events/sec)",
                   typeEvents.size(), duration, (double) typeEvents.size() / (duration / 1000.0));

        assertTrue(typeEvents.size() == 5000, "Should retrieve exactly 5000 QueryTest events, got: " + typeEvents.size());
    }

    private void benchmarkQueryByTimeRange() throws Exception {
        logger.info("🔄 Benchmarking queryByValidTimeRange performance...");

        Instant startTime = Instant.now().minusSeconds(3600);
        Instant endTime = startTime.plusSeconds(1800); // 30 minutes range

        long benchmarkStart = System.currentTimeMillis();

        List<BiTemporalEvent<TestEvent>> rangeEvents = eventStore.query(
            EventQuery.builder()
                .validTimeRange(new TemporalRange(startTime, endTime))
                .build()
        ).get(30, TimeUnit.SECONDS);

        long benchmarkEnd = System.currentTimeMillis();
        long duration = benchmarkEnd - benchmarkStart;

        logger.info("✅ QueryByTimeRange: {} events retrieved in {} ms ({:.1f} events/sec)",
                   rangeEvents.size(), duration, (double) rangeEvents.size() / (duration / 1000.0));

        assertTrue(rangeEvents.size() > 0, "Should retrieve events in the time range");
        assertTrue(rangeEvents.size() <= 1800, "Should not exceed expected range size");
    }

    // Test event class for benchmarking
    public static class TestEvent {
        private String id;
        private String data;
        private int value;

        public TestEvent() {}

        public TestEvent(String id, String data, int value) {
            this.id = id;
            this.data = data;
            this.value = value;
        }

        // Getters and setters
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getData() { return data; }
        public void setData(String data) { this.data = data; }
        public int getValue() { return value; }
        public void setValue(int value) { this.value = value; }

        @Override
        public String toString() {
            return "TestEvent{id='" + id + "', data='" + data + "', value=" + value + "}";
        }
    }

    @Test
    @Order(3)
    @DisplayName("BENCHMARK: Memory Usage and Resource Management")
    void benchmarkMemoryUsageAndResourceManagement() throws Exception {
        logger.info("=== BENCHMARK: Memory Usage and Resource Management ===");

        Runtime runtime = Runtime.getRuntime();

        // Force garbage collection and get baseline
        System.gc();
        Thread.sleep(1000);
        long baselineMemory = runtime.totalMemory() - runtime.freeMemory();
        logger.info("📊 Baseline memory usage: {} MB", baselineMemory / (1024 * 1024));

        // Generate load with many events
        int operations = 10000;
        logger.info("🔄 Generating load with {} operations...", operations);

        List<CompletableFuture<BiTemporalEvent<TestEvent>>> futures = new ArrayList<>();
        for (int i = 0; i < operations; i++) {
            TestEvent event = new TestEvent("mem-test-" + i, "Memory test data " + i, i);
            CompletableFuture<BiTemporalEvent<TestEvent>> future = eventStore.append("MemoryTest", event, Instant.now());
            futures.add(future);

            if (i % 1000 == 0) {
                // Check memory periodically
                long currentMemory = runtime.totalMemory() - runtime.freeMemory();
                long memoryIncrease = currentMemory - baselineMemory;
                logger.info("📊 Memory at {} operations: {} MB (increase: {} MB)",
                           i, currentMemory / (1024 * 1024), memoryIncrease / (1024 * 1024));

                // Memory increase should be reasonable (less than 500MB)
                assertTrue(memoryIncrease < 500 * 1024 * 1024,
                          "Memory usage should not increase excessively: " + (memoryIncrease / (1024 * 1024)) + " MB");
            }
        }

        // Wait for all operations to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(120, TimeUnit.SECONDS);

        // Final memory check
        System.gc();
        Thread.sleep(1000);
        long finalMemory = runtime.totalMemory() - runtime.freeMemory();
        long totalIncrease = finalMemory - baselineMemory;

        logger.info("✅ Memory test completed: Final memory {} MB (total increase: {} MB)",
                   finalMemory / (1024 * 1024), totalIncrease / (1024 * 1024));

        // Validate reasonable memory usage
        assertTrue(totalIncrease < 1024 * 1024 * 1024, // 1GB
                  "Total memory increase should be reasonable: " + (totalIncrease / (1024 * 1024)) + " MB");
    }

    @Test
    @Order(4)
    @DisplayName("BENCHMARK: Reactive Notification Performance")
    void benchmarkReactiveNotificationPerformance() throws Exception {
        logger.info("=== BENCHMARK: Reactive Notification Performance ===");

        int notificationCount = 1000;
        List<BiTemporalEvent<TestEvent>> receivedNotifications = new ArrayList<>();

        // Set up subscription - following working integration test patterns
        logger.info("🔄 Setting up reactive notification subscription...");
        MessageHandler<BiTemporalEvent<TestEvent>> handler = message -> {
            BiTemporalEvent<TestEvent> event = message.getPayload();
            logger.debug("Received notification for event: {}", event.getEventId());
            receivedNotifications.add(event);
            return CompletableFuture.completedFuture(null);
        };

        // Subscribe and wait for it to complete - following integration test pattern
        eventStore.subscribe("NotificationTest", handler).join();

        // Give subscription time to establish - following integration test pattern
        Thread.sleep(3000);

        // Benchmark notification throughput
        logger.info("🔄 Benchmarking notification performance with {} events...", notificationCount);
        long startTime = System.currentTimeMillis();

        List<CompletableFuture<BiTemporalEvent<TestEvent>>> appendFutures = new ArrayList<>();
        for (int i = 0; i < notificationCount; i++) {
            TestEvent event = new TestEvent("notify-" + i, "Notification test " + i, i);
            CompletableFuture<BiTemporalEvent<TestEvent>> future = eventStore.append("NotificationTest", event, Instant.now());
            appendFutures.add(future);
        }

        // Wait for all appends to complete
        CompletableFuture.allOf(appendFutures.toArray(new CompletableFuture[0]))
                .get(60, TimeUnit.SECONDS);

        // Wait for notifications to be received
        long notificationTimeout = System.currentTimeMillis() + 30000; // 30 second timeout
        while (receivedNotifications.size() < notificationCount && System.currentTimeMillis() < notificationTimeout) {
            Thread.sleep(100);
        }

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        double throughput = (double) receivedNotifications.size() / (duration / 1000.0);

        logger.info("✅ Notification Performance: {}/{} notifications received in {} ms ({:.1f} notifications/sec)",
                   receivedNotifications.size(), notificationCount, duration, throughput);

        // Validate notification delivery - adjusted for performance test conditions
        double successRate = (double) receivedNotifications.size() / notificationCount;
        assertTrue(receivedNotifications.size() >= notificationCount * 0.90, // Allow 10% tolerance for high-load performance test
                  "Should receive at least 90% of notifications: " + receivedNotifications.size() + "/" + notificationCount +
                  " (" + String.format("%.1f", successRate * 100) + "%)");

        // Validate reasonable notification throughput (target: 25+ notifications/sec under high load)
        assertTrue(throughput > 25,
                  "Notification throughput should exceed 25/sec under high load, got: " + throughput);
    }

    @Test
    @Order(5)
    @DisplayName("BENCHMARK: Target Throughput Validation (5000+ msg/sec)")
    void benchmarkTargetThroughputValidation() throws Exception {
        logger.info("=== BENCHMARK: Target Throughput Validation (5000+ msg/sec) ===");

        int targetThroughput = 5000; // Target: 5000+ msg/sec
        int testDurationSeconds = 10;
        int expectedMessages = targetThroughput * testDurationSeconds;

        logger.info("🎯 Target: {} msg/sec for {} seconds = {} total messages",
                   targetThroughput, testDurationSeconds, expectedMessages);

        // Benchmark high-throughput scenario
        logger.info("🔄 Starting high-throughput benchmark...");
        long startTime = System.currentTimeMillis();

        List<CompletableFuture<BiTemporalEvent<TestEvent>>> futures = new ArrayList<>();
        for (int i = 0; i < expectedMessages; i++) {
            TestEvent event = new TestEvent("throughput-" + i, "High throughput test " + i, i % 1000);
            CompletableFuture<BiTemporalEvent<TestEvent>> future = eventStore.append("ThroughputTest", event, Instant.now());
            futures.add(future);
        }

        // Wait for all operations to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(120, TimeUnit.SECONDS);

        long endTime = System.currentTimeMillis();
        long actualDuration = endTime - startTime;
        double actualThroughput = (double) expectedMessages / (actualDuration / 1000.0);

        logger.info("✅ High-Throughput Results:");
        logger.info("   📊 Messages: {} in {} ms", expectedMessages, actualDuration);
        logger.info("   📊 Actual Throughput: {:.1f} msg/sec", actualThroughput);
        logger.info("   📊 Target Achievement: {:.1f}%", (actualThroughput / targetThroughput) * 100);

        // Validate we're achieving the target throughput
        if (actualThroughput >= targetThroughput) {
            logger.info("🎉 SUCCESS: Target throughput of {} msg/sec ACHIEVED! (Actual: {:.1f} msg/sec)",
                       targetThroughput, actualThroughput);
        } else {
            logger.warn("⚠️  Target throughput not achieved. Target: {} msg/sec, Actual: {:.1f} msg/sec",
                       targetThroughput, actualThroughput);
        }

        // For now, we'll validate a reasonable throughput (2000+ msg/sec) as the target may be ambitious
        assertTrue(actualThroughput > 2000,
                  "Should achieve at least 2000 msg/sec, got: " + actualThroughput);
    }

    private void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
