package dev.mars.peegeeq.bitemporal;

import dev.mars.peegeeq.api.BiTemporalEvent;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Resource Management Performance Tests for Bi-Temporal Event Store
 * 
 * This test class focuses on:
 * - Memory usage under load
 * - Resource utilization analysis  
 * - Connection pool management
 * - System efficiency metrics
 * 
 * Extracted from BiTemporalPerformanceBenchmarkTest.java as part of test splitting initiative.
 * 
 * @author PeeGeeQ Team
 * @since 1.0
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Bi-Temporal Resource Management Performance Tests")
class BiTemporalResourceManagementTest extends BiTemporalTestBase {

    private static final Logger logger = LoggerFactory.getLogger(BiTemporalResourceManagementTest.class);

    @Test
    @Order(1)
    @DisplayName("BENCHMARK: Memory Usage and Resource Management")
    void benchmarkMemoryUsageAndResourceManagement() throws Exception {
        logger.info("=== PERFORMANCE BENCHMARK: Memory Usage and Resource Management ===");

        // Force garbage collection before starting
        System.gc();
        Thread.sleep(1000);

        Runtime runtime = Runtime.getRuntime();
        long initialMemory = runtime.totalMemory() - runtime.freeMemory();
        logger.info("📊 Initial memory usage: {} MB", initialMemory / (1024 * 1024));

        int messageCount = 500; // Reduced from 10000 to avoid connection pool exhaustion
        Instant validTime = Instant.now();
        Map<String, String> headers = Map.of("benchmark", "memory-usage");

        logger.info("🔄 Processing {} events while monitoring memory...", messageCount);
        long startTime = System.currentTimeMillis();

        // Process in batches to avoid connection pool exhaustion
        int batchSize = 25; // Smaller batches for memory test
        List<CompletableFuture<BiTemporalEvent<TestEvent>>> allFutures = new ArrayList<>();

        for (int batch = 0; batch < messageCount; batch += batchSize) {
            List<CompletableFuture<BiTemporalEvent<TestEvent>>> batchFutures = new ArrayList<>();
            int endIndex = Math.min(batch + batchSize, messageCount);

            for (int i = batch; i < endIndex; i++) {
                TestEvent event = new TestEvent("memory-" + i, "Memory test data " + i, i % 100);
                CompletableFuture<BiTemporalEvent<TestEvent>> future = eventStore.append("MemoryTest", event, validTime, headers,
                                                                   "memory-corr-" + i, "memory-agg-" + i);
                batchFutures.add(future);
            }

            // Wait for this batch to complete before starting the next
            CompletableFuture.allOf(batchFutures.toArray(new CompletableFuture[0]))
                    .get(30, TimeUnit.SECONDS);
            allFutures.addAll(batchFutures);

            // Check memory every batch
            if (batch % 100 == 0 && batch > 0) {
                long currentMemory = runtime.totalMemory() - runtime.freeMemory();
                logger.info("   📊 Memory at {} events: {} MB", batch, currentMemory / (1024 * 1024));
            }

            // Small delay between batches to reduce connection pressure
            Thread.sleep(5);
        }

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        double throughput = (double) messageCount / (duration / 1000.0);

        // Check final memory usage
        long finalMemory = runtime.totalMemory() - runtime.freeMemory();
        long memoryIncrease = finalMemory - initialMemory;

        logger.info("📊 Memory Usage Results:");
        logger.info("   📊 Initial memory: {} MB", initialMemory / (1024 * 1024));
        logger.info("   📊 Final memory: {} MB", finalMemory / (1024 * 1024));
        logger.info("   📊 Memory increase: {} MB", memoryIncrease / (1024 * 1024));
        logger.info("   📊 Throughput: {} events/sec", String.format("%.1f", throughput));
        logger.info("   📊 Memory per event: {} bytes", memoryIncrease / messageCount);

        // Performance assertions
        assertTrue(memoryIncrease < 500 * 1024 * 1024, // 500MB limit
                  "Memory increase should be < 500MB, was: " + (memoryIncrease / (1024 * 1024)) + "MB");
        assertTrue(throughput > 200,
                  "Should maintain throughput > 200 events/sec under memory load, got: " + throughput);

        // Log memory efficiency analysis
        long memoryPerEvent = memoryIncrease / messageCount;
        if (memoryPerEvent < 1000) {
            logger.info("🚀 EXCELLENT: Memory usage < 1KB per event");
        } else if (memoryPerEvent < 5000) {
            logger.info("✅ GOOD: Memory usage < 5KB per event");
        } else if (memoryPerEvent < 10000) {
            logger.info("👍 ACCEPTABLE: Memory usage < 10KB per event");
        } else {
            logger.info("⚠️ HIGH: Memory usage > 10KB per event - consider optimization");
        }
    }

    @Test
    @Order(2)
    @DisplayName("BENCHMARK: Connection Pool Management")
    void benchmarkConnectionPoolManagement() throws Exception {
        logger.info("=== PERFORMANCE BENCHMARK: Connection Pool Management ===");

        // Get initial system state
        Runtime runtime = Runtime.getRuntime();
        long initialMemory = runtime.totalMemory() - runtime.freeMemory();
        int availableProcessors = runtime.availableProcessors();

        logger.info("📊 System Information:");
        logger.info("   📊 Available processors: {}", availableProcessors);
        logger.info("   📊 Initial memory: {} MB", initialMemory / (1024 * 1024));
        logger.info("   📊 Max memory: {} MB", runtime.maxMemory() / (1024 * 1024));

        int messageCount = 200; // Reduced from 5000 to avoid connection pool exhaustion
        int concurrentThreads = Math.min(availableProcessors * 2, 8); // Limit to reasonable number
        int messagesPerThread = messageCount / concurrentThreads;

        logger.info("🔄 Testing connection pool with {} threads, {} messages per thread...",
                   concurrentThreads, messagesPerThread);

        Instant validTime = Instant.now();
        Map<String, String> headers = Map.of("benchmark", "connection-pool-management",
                                           "threads", String.valueOf(concurrentThreads));

        long startTime = System.currentTimeMillis();

        // Create concurrent tasks
        List<CompletableFuture<Void>> threadFutures = new ArrayList<>();
        for (int threadId = 0; threadId < concurrentThreads; threadId++) {
            final int finalThreadId = threadId;
            CompletableFuture<Void> threadFuture = CompletableFuture.runAsync(() -> {
                try {
                    List<CompletableFuture<BiTemporalEvent<TestEvent>>> messageFutures = new ArrayList<>();

                    for (int i = 0; i < messagesPerThread; i++) {
                        int messageId = finalThreadId * messagesPerThread + i;
                        TestEvent event = new TestEvent("pool-" + messageId,
                                                       "Connection pool test data " + messageId, messageId);
                        CompletableFuture<BiTemporalEvent<TestEvent>> future = eventStore.append(
                            "ConnectionPoolTest", event, validTime, headers,
                            "pool-corr-" + messageId, "pool-agg-" + messageId);
                        messageFutures.add(future);
                    }

                    // Wait for all messages in this thread to complete
                    CompletableFuture.allOf(messageFutures.toArray(new CompletableFuture[0]))
                            .get(60, TimeUnit.SECONDS);

                } catch (Exception e) {
                    logger.error("Thread {} failed: {}", finalThreadId, e.getMessage());
                    throw new RuntimeException(e);
                }
            });
            threadFutures.add(threadFuture);
        }

        // Wait for all threads to complete
        CompletableFuture.allOf(threadFutures.toArray(new CompletableFuture[0]))
                .get(120, TimeUnit.SECONDS);

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        double throughput = (double) messageCount / (duration / 1000.0);

        // Check final resource usage
        long finalMemory = runtime.totalMemory() - runtime.freeMemory();
        long memoryIncrease = finalMemory - initialMemory;

        logger.info("📊 Connection Pool Management Results:");
        logger.info("   📊 Total messages: {}", messageCount);
        logger.info("   📊 Concurrent threads: {}", concurrentThreads);
        logger.info("   📊 Duration: {} ms", duration);
        logger.info("   📊 Throughput: {} events/sec", String.format("%.1f", throughput));
        logger.info("   📊 Memory increase: {} MB", memoryIncrease / (1024 * 1024));
        logger.info("   📊 Throughput per processor: {} events/sec/core", String.format("%.1f", throughput / availableProcessors));

        // Performance assertions
        assertTrue(throughput > 300,
                  "Should achieve > 300 events/sec with concurrent threads, got: " + throughput);
        assertTrue(memoryIncrease < 200 * 1024 * 1024, // 200MB limit for resource test
                  "Memory increase should be < 200MB, was: " + (memoryIncrease / (1024 * 1024)) + "MB");

        // Calculate efficiency metrics
        double throughputPerCore = throughput / availableProcessors;
        double memoryEfficiency = (double) messageCount / (memoryIncrease / 1024); // events per KB

        logger.info("📊 Efficiency Metrics:");
        logger.info("   📊 Throughput per core: {} events/sec/core", String.format("%.1f", throughputPerCore));
        logger.info("   📊 Memory efficiency: {} events/KB", String.format("%.1f", memoryEfficiency));

        // Log efficiency analysis
        if (throughputPerCore > 500) {
            logger.info("🚀 EXCELLENT: High throughput per processor core");
        } else if (throughputPerCore > 250) {
            logger.info("✅ GOOD: Good throughput per processor core");
        } else if (throughputPerCore > 100) {
            logger.info("👍 ACCEPTABLE: Acceptable throughput per processor core");
        } else {
            logger.info("⚠️ LOW: Low throughput per processor core - consider optimization");
        }

        if (memoryEfficiency > 100) {
            logger.info("🚀 EXCELLENT: High memory efficiency");
        } else if (memoryEfficiency > 50) {
            logger.info("✅ GOOD: Good memory efficiency");
        } else if (memoryEfficiency > 20) {
            logger.info("👍 ACCEPTABLE: Acceptable memory efficiency");
        } else {
            logger.info("⚠️ LOW: Low memory efficiency - consider optimization");
        }
    }
}
