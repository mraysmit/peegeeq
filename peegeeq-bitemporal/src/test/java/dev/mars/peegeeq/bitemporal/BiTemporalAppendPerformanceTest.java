package dev.mars.peegeeq.bitemporal;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import dev.mars.peegeeq.api.BiTemporalEvent;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance tests for bi-temporal event append operations.
 * 
 * This test class focuses specifically on event append performance patterns:
 * - Sequential vs Concurrent append operations
 * - Batch vs Individual append operations
 * - Throughput and latency measurements
 * 
 * Split from the original BiTemporalPerformanceBenchmarkTest following PGQ coding principles:
 * - Investigate before implementing: Analyzed existing test patterns
 * - Follow established conventions: Uses same base infrastructure
 * - Validate incrementally: Each test can be run independently
 * - Fix root causes: Focused testing for specific performance aspects
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-11
 * @version 1.0
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class BiTemporalAppendPerformanceTest extends BiTemporalTestBase {

    private static final Logger logger = LoggerFactory.getLogger(BiTemporalAppendPerformanceTest.class);

    @Test
    @Order(1)
    @DisplayName("BENCHMARK: Sequential vs Concurrent Event Appends")
    void benchmarkSequentialVsConcurrentAppends() throws Exception {
        logger.info("=== PERFORMANCE BENCHMARK: Sequential vs Concurrent Appends ===");
        
        int messageCount = 100; // Optimized count for test stability
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

        logger.info("✅ Sequential Approach: {} events in {} ms ({} events/sec)",
                   messageCount, sequentialDuration, String.format("%.1f", sequentialThroughput));

        // Benchmark Concurrent approach
        logger.info("🔄 Benchmarking Concurrent appends with {} events...", messageCount);
        long concurrentStartTime = System.currentTimeMillis();

        List<CompletableFuture<BiTemporalEvent<TestEvent>>> concurrentFutures = new ArrayList<>();
        for (int i = 0; i < messageCount; i++) {
            TestEvent event = new TestEvent("conc-" + i, "Concurrent test data " + i, i);
            CompletableFuture<BiTemporalEvent<TestEvent>> future = eventStore.append("ConcurrentTest", event, validTime, headers,
                                                                   "conc-correlation-" + i, "conc-aggregate-" + i);
            concurrentFutures.add(future);
        }

        // Wait for all concurrent operations to complete
        CompletableFuture.allOf(concurrentFutures.toArray(new CompletableFuture[0]))
                .get(60, TimeUnit.SECONDS);

        long concurrentEndTime = System.currentTimeMillis();
        long concurrentDuration = concurrentEndTime - concurrentStartTime;
        double concurrentThroughput = (double) messageCount / (concurrentDuration / 1000.0);

        logger.info("✅ Concurrent Approach: {} events in {} ms ({} events/sec)",
                   messageCount, concurrentDuration, String.format("%.1f", concurrentThroughput));

        // Calculate performance improvement
        double improvementPercentage = ((concurrentThroughput - sequentialThroughput) / sequentialThroughput) * 100;
        logger.info("🚀 Performance Improvement: {}% faster with concurrent approach",
                   String.format("%.1f", improvementPercentage));

        // Assertions
        assertTrue(concurrentThroughput > sequentialThroughput, 
                  "Concurrent approach should be faster than sequential");
        assertTrue(improvementPercentage > 50, 
                  "Concurrent approach should be at least 50% faster");
        assertTrue(concurrentThroughput > 200, 
                  "Concurrent throughput should exceed 200 events/sec");
    }

    @Test
    @Order(2)
    @DisplayName("BENCHMARK: Batch vs Individual Operations")
    void benchmarkBatchVsIndividualOperations() throws Exception {
        logger.info("=== BENCHMARK: Batch vs Individual Operations ===");

        int messageCount = 100; // Optimized count for test stability
        Instant validTime = Instant.now();
        Map<String, String> headers = Map.of("benchmark", "true", "test-type", "batch-comparison");

        // Benchmark Individual operations (sequential)
        logger.info("🔄 Benchmarking Individual operations with {} events...", messageCount);
        long individualStartTime = System.currentTimeMillis();

        for (int i = 0; i < messageCount; i++) {
            TestEvent event = new TestEvent("individual-" + i, "Individual test data " + i, i);
            eventStore.append("IndividualTest", event, validTime, headers,
                             "individual-corr-" + i, "individual-agg-" + i)
                     .get(5, TimeUnit.SECONDS);
        }

        long individualEndTime = System.currentTimeMillis();
        long individualDuration = individualEndTime - individualStartTime;
        double individualThroughput = (double) messageCount / (individualDuration / 1000.0);

        logger.info("✅ Individual Operations: {} events in {} ms ({} events/sec)",
                   messageCount, individualDuration, String.format("%.1f", individualThroughput));

        // Benchmark Batch operations (concurrent)
        logger.info("🔄 Benchmarking Batch operations with {} events...", messageCount);
        long batchStartTime = System.currentTimeMillis();

        List<CompletableFuture<BiTemporalEvent<TestEvent>>> batchFutures = new ArrayList<>();
        for (int i = 0; i < messageCount; i++) {
            TestEvent event = new TestEvent("batch-" + i, "Batch test data " + i, i);
            CompletableFuture<BiTemporalEvent<TestEvent>> future = eventStore.append("BatchTest", event, validTime, headers,
                                                               "batch-corr-" + i, "batch-agg-" + i);
            batchFutures.add(future);
        }

        // Wait for all batch operations to complete
        CompletableFuture.allOf(batchFutures.toArray(new CompletableFuture[0]))
                .get(60, TimeUnit.SECONDS);

        long batchEndTime = System.currentTimeMillis();
        long batchDuration = batchEndTime - batchStartTime;
        double batchThroughput = (double) messageCount / (batchDuration / 1000.0);

        logger.info("✅ Batch Operations: {} events in {} ms ({} events/sec)",
                   messageCount, batchDuration, String.format("%.1f", batchThroughput));

        // Calculate performance improvement
        double improvementPercentage = ((batchThroughput - individualThroughput) / individualThroughput) * 100;
        logger.info("🚀 Batch Performance Improvement: {}% faster than individual operations",
                   String.format("%.1f", improvementPercentage));

        // Assertions
        assertTrue(batchThroughput > individualThroughput, 
                  "Batch operations should be faster than individual operations");
        assertTrue(improvementPercentage > 30, 
                  "Batch operations should be at least 30% faster");
        assertTrue(batchThroughput > 150, 
                  "Batch throughput should exceed 150 events/sec");
    }
}
