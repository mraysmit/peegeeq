package dev.mars.peegeeq.pgqueue;

import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.performance.SystemInfoCollector;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.pgqueue.ConsumerConfig;
import dev.mars.peegeeq.pgqueue.ConsumerMode;
import dev.mars.peegeeq.pgqueue.PgNativeFactoryRegistrar;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance comparison tests for different consumer modes.
 * Tests throughput, latency, and resource usage across LISTEN_NOTIFY_ONLY, POLLING_ONLY, and HYBRID modes.
 */
@Testcontainers
public class ConsumerModePerformanceTest {
    private static final Logger logger = LoggerFactory.getLogger(ConsumerModePerformanceTest.class);

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass");

    private PeeGeeQManager manager;
    private QueueFactory factory;

    @BeforeAll
    static void logSystemInfo() {
        logger.info("=== CONSUMER MODE PERFORMANCE TEST SUITE ===");
        logger.info("System Information:");
        logger.info(SystemInfoCollector.formatAsSummary());
        logger.info("=== Starting Performance Tests ===");
    }

    @BeforeEach
    void setUp() throws Exception {
        logger.info("🔧 Setting up ConsumerModePerformanceTest");
        
        // Clear any existing system properties
        System.clearProperty("peegeeq.queue.polling-interval");
        System.clearProperty("peegeeq.queue.visibility-timeout");
        System.clearProperty("peegeeq.queue.batch-size");
        System.clearProperty("peegeeq.consumer.threads");

        initializeManagerAndFactory();
        logger.info("✅ ConsumerModePerformanceTest setup completed");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (factory != null) {
            factory.close();
        }
        if (manager != null) {
            manager.stop();
        }
        logger.info("🧹 ConsumerModePerformanceTest teardown completed");
    }

    private void initializeManagerAndFactory() throws Exception {
        // Configure test properties using TestContainer pattern (following established patterns)
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.ssl.enabled", "false");
        System.setProperty("peegeeq.queue.polling-interval", "PT0.1S"); // Fast polling for performance tests
        System.setProperty("peegeeq.queue.visibility-timeout", "PT30S");
        System.setProperty("peegeeq.metrics.enabled", "true");
        System.setProperty("peegeeq.circuit-breaker.enabled", "true");

        // Initialize PeeGeeQ with test configuration
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        // Create factory using the proper pattern
        PgDatabaseService databaseService = new PgDatabaseService(manager);
        PgQueueFactoryProvider provider = new PgQueueFactoryProvider();

        // Register native factory implementation
        PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);

        factory = provider.createFactory("native", databaseService);
    }

    @Test
    void testThroughputComparison() throws Exception {
        logger.info("🧪 Testing throughput comparison across consumer modes");

        String topicName = "test-throughput-comparison";
        int messageCount = 100; // Reasonable number for CI environment
        int warmupMessages = 10;

        // Test results storage
        List<PerformanceResult> results = new ArrayList<>();

        // Test each consumer mode
        ConsumerMode[] modes = {ConsumerMode.LISTEN_NOTIFY_ONLY, ConsumerMode.POLLING_ONLY, ConsumerMode.HYBRID};
        
        for (ConsumerMode mode : modes) {
            logger.info("📊 Testing throughput for mode: {}", mode);
            
            PerformanceResult result = measureThroughput(topicName + "-" + mode.name().toLowerCase(), 
                mode, messageCount, warmupMessages);
            results.add(result);
            
            logger.info("📈 {} - Throughput: {:.2f} msg/sec, Avg Latency: {:.2f}ms", 
                mode, result.throughput, result.averageLatency);
        }

        // Validate results
        assertTrue(results.size() == 3, "Should have results for all 3 consumer modes");
        
        // All modes should have reasonable throughput (> 5 msg/sec in test environment)
        for (PerformanceResult result : results) {
            assertTrue(result.throughput > 5.0,
                String.format("Mode %s should have throughput > 5 msg/sec, got %.2f",
                    result.mode, result.throughput));
            assertTrue(result.averageLatency < 10000, // 10 seconds max average latency for CI
                String.format("Mode %s should have average latency < 10000ms, got %.2f",
                    result.mode, result.averageLatency));
        }

        // HYBRID mode should generally perform well (not necessarily the fastest due to test environment variability)
        PerformanceResult hybridResult = results.stream()
            .filter(r -> r.mode == ConsumerMode.HYBRID)
            .findFirst()
            .orElseThrow();

        assertTrue(hybridResult.throughput > 7.0,
            "HYBRID mode should have good throughput in test environment");

        logger.info("✅ Throughput comparison test completed successfully");
    }

    @Test
    void testLatencyComparison() throws Exception {
        logger.info("🧪 Testing latency comparison across consumer modes");

        String topicName = "test-latency-comparison";
        int messageCount = 50; // Smaller count for latency precision

        List<LatencyResult> results = new ArrayList<>();
        ConsumerMode[] modes = {ConsumerMode.LISTEN_NOTIFY_ONLY, ConsumerMode.POLLING_ONLY, ConsumerMode.HYBRID};

        for (ConsumerMode mode : modes) {
            logger.info("⏱️ Testing latency for mode: {}", mode);
            
            LatencyResult result = measureLatency(topicName + "-" + mode.name().toLowerCase(), mode, messageCount);
            results.add(result);
            
            logger.info("📊 {} - Min: {:.2f}ms, Max: {:.2f}ms, Avg: {:.2f}ms, P95: {:.2f}ms", 
                mode, result.minLatency, result.maxLatency, result.averageLatency, result.p95Latency);
        }

        // Validate latency results
        for (LatencyResult result : results) {
            assertTrue(result.minLatency >= 0, "Min latency should be non-negative");
            assertTrue(result.maxLatency >= result.minLatency, "Max latency should be >= min latency");
            assertTrue(result.averageLatency >= result.minLatency, "Average latency should be >= min latency");
            assertTrue(result.p95Latency >= result.averageLatency, "P95 latency should be >= average latency");
            
            // Reasonable bounds for test environment
            assertTrue(result.averageLatency < 5000, // 5 seconds max average for CI
                String.format("Mode %s average latency should be reasonable, got %.2f ms",
                    result.mode, result.averageLatency));
        }

        logger.info("✅ Latency comparison test completed successfully");
    }

    private PerformanceResult measureThroughput(String topicName, ConsumerMode mode, 
                                              int messageCount, int warmupMessages) throws Exception {
        AtomicInteger processedCount = new AtomicInteger(0);
        AtomicLong totalLatency = new AtomicLong(0);
        CountDownLatch latch = new CountDownLatch(messageCount);
        
        // Create consumer with specific mode
        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder()
                .mode(mode)
                .pollingInterval(Duration.ofMillis(100)) // Fast polling
                .build());

        long[] messageSentTimes = new long[messageCount + warmupMessages];
        
        // Subscribe to messages
        consumer.subscribe(message -> {
            long receiveTime = System.currentTimeMillis();
            int index = processedCount.incrementAndGet();
            
            // Skip warmup messages in calculations
            if (index > warmupMessages) {
                long sendTime = messageSentTimes[index - 1];
                long latency = receiveTime - sendTime;
                totalLatency.addAndGet(latency);
                latch.countDown();
            }
            
            return CompletableFuture.completedFuture(null);
        });

        // Wait for consumer setup
        Thread.sleep(1000);

        // Send messages
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);
        
        long startTime = System.currentTimeMillis();
        
        // Send warmup + test messages
        for (int i = 0; i < messageCount + warmupMessages; i++) {
            messageSentTimes[i] = System.currentTimeMillis();
            producer.send("Performance test message " + i);
        }
        
        // Wait for all test messages to be processed (excluding warmup)
        boolean completed = latch.await(30, TimeUnit.SECONDS);
        long endTime = System.currentTimeMillis();
        
        producer.close();
        consumer.close();
        
        assertTrue(completed, "All messages should be processed within timeout");
        
        double durationSeconds = (endTime - startTime) / 1000.0;
        double throughput = messageCount / durationSeconds;
        double averageLatency = totalLatency.get() / (double) messageCount;
        
        return new PerformanceResult(mode, throughput, averageLatency, messageCount);
    }

    private LatencyResult measureLatency(String topicName, ConsumerMode mode, int messageCount) throws Exception {
        List<Long> latencies = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(messageCount);
        
        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder()
                .mode(mode)
                .pollingInterval(Duration.ofMillis(100))
                .build());

        long[] messageSentTimes = new long[messageCount];
        AtomicInteger processedCount = new AtomicInteger(0);
        
        consumer.subscribe(message -> {
            long receiveTime = System.currentTimeMillis();
            int index = processedCount.getAndIncrement();
            
            if (index < messageCount) {
                long sendTime = messageSentTimes[index];
                long latency = receiveTime - sendTime;
                synchronized (latencies) {
                    latencies.add(latency);
                }
                latch.countDown();
            }
            
            return CompletableFuture.completedFuture(null);
        });

        Thread.sleep(1000); // Consumer setup time

        MessageProducer<String> producer = factory.createProducer(topicName, String.class);
        
        for (int i = 0; i < messageCount; i++) {
            messageSentTimes[i] = System.currentTimeMillis();
            producer.send("Latency test message " + i);
        }
        
        boolean completed = latch.await(20, TimeUnit.SECONDS);
        
        producer.close();
        consumer.close();
        
        assertTrue(completed, "All messages should be processed for latency measurement");
        
        // Calculate latency statistics
        latencies.sort(Long::compareTo);
        
        double minLatency = latencies.get(0);
        double maxLatency = latencies.get(latencies.size() - 1);
        double averageLatency = latencies.stream().mapToLong(Long::longValue).average().orElse(0.0);
        double p95Latency = latencies.get((int) (latencies.size() * 0.95));
        
        return new LatencyResult(mode, minLatency, maxLatency, averageLatency, p95Latency);
    }

    // Performance result classes
    private static class PerformanceResult {
        final ConsumerMode mode;
        final double throughput;
        final double averageLatency;
        final int messageCount;
        
        PerformanceResult(ConsumerMode mode, double throughput, double averageLatency, int messageCount) {
            this.mode = mode;
            this.throughput = throughput;
            this.averageLatency = averageLatency;
            this.messageCount = messageCount;
        }
    }
    
    private static class LatencyResult {
        final ConsumerMode mode;
        final double minLatency;
        final double maxLatency;
        final double averageLatency;
        final double p95Latency;
        
        LatencyResult(ConsumerMode mode, double minLatency, double maxLatency, 
                     double averageLatency, double p95Latency) {
            this.mode = mode;
            this.minLatency = minLatency;
            this.maxLatency = maxLatency;
            this.averageLatency = averageLatency;
            this.p95Latency = p95Latency;
        }
    }
}
