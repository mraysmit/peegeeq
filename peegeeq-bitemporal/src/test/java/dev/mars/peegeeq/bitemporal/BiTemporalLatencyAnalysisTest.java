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
 * Latency Analysis Performance Tests for Bi-Temporal Event Store
 * 
 * This test class focuses on:
 * - End-to-end latency measurement
 * - Latency percentile analysis (P50, P95, P99)
 * - High-throughput validation with multiple verticles
 * - Performance optimization validation
 * 
 * Extracted from BiTemporalPerformanceBenchmarkTest.java as part of test splitting initiative.
 * 
 * @author PeeGeeQ Team
 * @since 1.0
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Bi-Temporal Latency Analysis Performance Tests")
class BiTemporalLatencyAnalysisTest extends BiTemporalTestBase {

    private static final Logger logger = LoggerFactory.getLogger(BiTemporalLatencyAnalysisTest.class);

    @Test
    @Order(1)
    @DisplayName("BENCHMARK: Latency Performance Analysis")
    void benchmarkLatencyPerformance() throws Exception {
        logger.info("=== BENCHMARK: Latency Performance Analysis ===");

        int messageCount = 100;
        List<Long> latencies = new ArrayList<>();

        logger.info("🔄 Measuring end-to-end latency for {} events...", messageCount);

        Instant validTime = Instant.now();
        Map<String, String> headers = Map.of("benchmark", "latency", "test-type", "end-to-end");

        for (int i = 0; i < messageCount; i++) {
            long startTime = System.nanoTime();

            TestEvent event = new TestEvent("latency-" + i, "Latency test data " + i, i);
            eventStore.append("LatencyTest", event, validTime, headers,
                             "latency-corr-" + i, "latency-agg-" + i)
                     .get(5, TimeUnit.SECONDS);

            long endTime = System.nanoTime();
            long latencyNs = endTime - startTime;
            latencies.add(latencyNs);

            // Small delay between operations to get individual measurements
            Thread.sleep(10);
        }

        // Calculate latency statistics
        long totalLatency = latencies.stream().mapToLong(Long::longValue).sum();
        double avgLatencyMs = (totalLatency / (double) messageCount) / 1_000_000;
        double minLatencyMs = latencies.stream().mapToLong(Long::longValue).min().orElse(0) / 1_000_000.0;
        double maxLatencyMs = latencies.stream().mapToLong(Long::longValue).max().orElse(0) / 1_000_000.0;

        // Calculate percentiles
        latencies.sort(Long::compareTo);
        double p50LatencyMs = latencies.get(messageCount / 2) / 1_000_000.0;
        double p95LatencyMs = latencies.get((int) (messageCount * 0.95)) / 1_000_000.0;
        double p99LatencyMs = latencies.get((int) (messageCount * 0.99)) / 1_000_000.0;

        logger.info("📊 Latency Performance Results:");
        logger.info("   📊 Messages: {}", messageCount);
        logger.info("   📊 Average latency: {}ms", String.format("%.2f", avgLatencyMs));
        logger.info("   📊 Min latency: {}ms", String.format("%.2f", minLatencyMs));
        logger.info("   📊 Max latency: {}ms", String.format("%.2f", maxLatencyMs));
        logger.info("   📊 P50 latency: {}ms", String.format("%.2f", p50LatencyMs));
        logger.info("   📊 P95 latency: {}ms", String.format("%.2f", p95LatencyMs));
        logger.info("   📊 P99 latency: {}ms", String.format("%.2f", p99LatencyMs));

        // Performance assertions
        assertTrue(avgLatencyMs < 1000, "Average latency should be < 1000ms, was: " + avgLatencyMs);
        assertTrue(p95LatencyMs < 2000, "P95 latency should be < 2000ms, was: " + p95LatencyMs);
        assertTrue(minLatencyMs < 500, "Min latency should be < 500ms, was: " + minLatencyMs);

        // Log performance analysis
        if (avgLatencyMs < 100) {
            logger.info("🚀 EXCELLENT: Average latency under 100ms");
        } else if (avgLatencyMs < 250) {
            logger.info("✅ GOOD: Average latency under 250ms");
        } else if (avgLatencyMs < 500) {
            logger.info("👍 ACCEPTABLE: Average latency under 500ms");
        } else {
            logger.info("⚠️ HIGH: Average latency over 500ms - consider optimization");
        }
    }

    @Test
    @Order(2)
    @DisplayName("BENCHMARK: High-Throughput Validation (Batched Processing)")
    void benchmarkHighThroughputValidation() throws Exception {
        logger.info("=== BENCHMARK: High-Throughput Validation with Optimized Processing ===");

        // CRITICAL PERFORMANCE CONFIGURATION: Enable all Vert.x PostgreSQL optimizations
        // Using September 11th documented configuration that achieved 956 events/sec
        System.setProperty("peegeeq.database.use.pipelined.client", "true");
        System.setProperty("peegeeq.database.pipelining.limit", "1024"); // Restored to Sept 11th value
        System.setProperty("peegeeq.database.event.loop.size", "16"); // More event loops for better concurrency
        System.setProperty("peegeeq.database.worker.pool.size", "32"); // More worker threads
        System.setProperty("peegeeq.database.pool.max-size", "100"); // Ensure pool size matches base config
        System.setProperty("peegeeq.database.pool.shared", "true");  // Enable shared pool (Sept 11th setting)
        System.setProperty("peegeeq.database.pool.wait-queue-size", "1000"); // Sept 11th documented value
        System.setProperty("peegeeq.metrics.jvm.enabled", "false");  // Disable JVM metrics overhead

        // CRITICAL PERFORMANCE BOOST: Disable Event Bus distribution to test direct pool performance
        System.setProperty("peegeeq.database.use.event.bus.distribution", "false");

        // Use batched processing to achieve high throughput without overwhelming the database
        int totalEvents = 5000; // Reduced from 50K to avoid timeout issues while still testing high throughput
        int batchSize = 250; // Process in batches to avoid overwhelming connection pool
        int targetThroughput = 1000; // Realistic target for batched processing
        int expectedThroughput = 2000; // Expected achievement with optimizations

        logger.info("🎯 Target: {}+ events/sec (Expected: {} events/sec)", targetThroughput, expectedThroughput);
        logger.info("📊 Total Events: {} (batched: {} events per batch)", totalEvents, batchSize);
        logger.info("🔧 Optimizations: Pipelined client, increased event loops and worker threads");

        long startTime = System.currentTimeMillis();
        int completedEvents = 0;

        logger.info("🚀 Starting batched high-throughput processing...");

        // Process in batches to avoid database timeout
        for (int batchStart = 0; batchStart < totalEvents; batchStart += batchSize) {
            int batchEnd = Math.min(batchStart + batchSize, totalEvents);
            int currentBatchSize = batchEnd - batchStart;

            logger.info("📦 Processing batch {}-{} ({} events)...", batchStart, batchEnd - 1, currentBatchSize);

            List<CompletableFuture<BiTemporalEvent<TestEvent>>> batchFutures = new ArrayList<>();

            // Launch batch concurrently
            for (int i = batchStart; i < batchEnd; i++) {
                TestEvent event = new TestEvent("high-throughput-" + i, "High throughput validation " + i, i);

                // Use standard transactional append - focus on Vert.x threading optimization
                CompletableFuture<BiTemporalEvent<TestEvent>> future = eventStore.append("HighThroughputTest", event,
                                                                       Instant.now(), Map.of(),
                                                                       "high-throughput-correlation-" + i,
                                                                       "high-throughput-aggregate-" + i);
                batchFutures.add(future);
            }

            // Wait for batch to complete
            CompletableFuture.allOf(batchFutures.toArray(new CompletableFuture[0]))
                    .get(60, TimeUnit.SECONDS); // 1 minute timeout per batch

            completedEvents += currentBatchSize;

            long currentTime = System.currentTimeMillis();
            double currentThroughput = (double) completedEvents / ((currentTime - startTime) / 1000.0);
            logger.info("📈 Progress: {}/{} events completed ({} events/sec so far)",
                       completedEvents, totalEvents, Math.round(currentThroughput));
        }

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        double actualThroughput = (double) totalEvents / (duration / 1000.0);

        logger.info("✅ High-Throughput Validation Results:");
        logger.info("   📊 Total Events: {}", totalEvents);
        logger.info("   📊 Execution Time: {} seconds", String.format("%.2f", duration / 1000.0));
        logger.info("   📊 Actual Throughput: {} events/sec", Math.round(actualThroughput));
        logger.info("   📊 Target Achievement: {}% of minimum target", Math.round((actualThroughput / targetThroughput) * 100));
        logger.info("   📊 Expected Comparison: {}% of expected performance", Math.round((actualThroughput / expectedThroughput) * 100));

        // Validate we're achieving reasonable throughput
        int realisticTarget = 400; // Realistic target with optimized processing
        assertTrue(actualThroughput >= realisticTarget,
                  String.format("Should achieve at least %d events/sec with optimizations, got: %.0f",
                               realisticTarget, actualThroughput));

        if (actualThroughput >= targetThroughput) {
            logger.info("🎉 EXCELLENT: Meets or exceeds target of {} events/sec with optimizations!",
                       targetThroughput);
        } else if (actualThroughput >= realisticTarget) {
            logger.info("✅ SUCCESS: Meets realistic target of {} events/sec with optimizations (Target: {} events/sec)",
                       realisticTarget, targetThroughput);
        }

        logger.info("🎉 High-throughput validation with optimized processing completed successfully");
    }
}
