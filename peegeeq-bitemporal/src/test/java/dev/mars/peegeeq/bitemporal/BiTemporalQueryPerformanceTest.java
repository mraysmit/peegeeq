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
import dev.mars.peegeeq.api.EventQuery;
import dev.mars.peegeeq.api.TemporalRange;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance tests for bi-temporal event query operations.
 * 
 * This test class focuses specifically on query performance patterns:
 * - Query all events performance
 * - Query by event type performance  
 * - Query by time range performance
 * - Large dataset query optimization
 * 
 * Split from the original BiTemporalPerformanceBenchmarkTest following PGQ coding principles:
 * - Investigate before implementing: Analyzed existing query patterns
 * - Follow established conventions: Uses same base infrastructure
 * - Validate incrementally: Each test can be run independently
 * - Fix root causes: Focused testing for specific query performance aspects
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-11
 * @version 1.0
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class BiTemporalQueryPerformanceTest extends BiTemporalTestBase {

    private static final Logger logger = LoggerFactory.getLogger(BiTemporalQueryPerformanceTest.class);

    @Test
    @Order(1)
    @DisplayName("BENCHMARK: Query Performance with Large Dataset")
    void benchmarkQueryPerformance() throws Exception {
        logger.info("=== BENCHMARK: Query Performance ===");
        
        // First, populate with test data (reduced size for test stability)
        int datasetSize = 1000; // Reduced from 5000 for faster, more stable tests
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
        benchmarkQueryAllEvents(datasetSize);
        benchmarkQueryByEventType(datasetSize);
        benchmarkQueryByTimeRange();
    }

    private void benchmarkQueryAllEvents(int expectedSize) throws Exception {
        logger.info("🔄 Benchmarking queryAll performance...");
        long startTime = System.currentTimeMillis();

        // Use a higher limit to retrieve all events
        List<BiTemporalEvent<TestEvent>> allEvents = eventStore.query(
            EventQuery.builder().limit(2000).build()
        ).get(30, TimeUnit.SECONDS);

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        double throughput = (double) allEvents.size() / (duration / 1000.0);

        logger.info("✅ QueryAll: {} events retrieved in {} ms ({} events/sec)",
                   allEvents.size(), duration, String.format("%.1f", throughput));

        // Assertions
        assertTrue(allEvents.size() >= expectedSize, 
                  "Should retrieve all populated events, got: " + allEvents.size());
        assertTrue(throughput > 500, 
                  "Query throughput should exceed 500 events/sec, got: " + String.format("%.1f", throughput));
        assertTrue(duration < 5000, 
                  "Query should complete within 5 seconds, took: " + duration + "ms");
    }

    private void benchmarkQueryByEventType(int expectedSize) throws Exception {
        logger.info("🔄 Benchmarking queryByEventType performance...");
        long startTime = System.currentTimeMillis();

        // Use higher limit to retrieve all events of this type
        List<BiTemporalEvent<TestEvent>> typeEvents = eventStore.query(
            EventQuery.builder()
                .eventType("QueryTest")
                .limit(2000)
                .build()
        ).get(30, TimeUnit.SECONDS);

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        double throughput = (double) typeEvents.size() / (duration / 1000.0);

        logger.info("✅ QueryByEventType: {} events retrieved in {} ms ({} events/sec)",
                   typeEvents.size(), duration, String.format("%.1f", throughput));

        // Assertions
        assertTrue(typeEvents.size() == expectedSize, 
                  "Should retrieve exactly " + expectedSize + " QueryTest events, got: " + typeEvents.size());
        assertTrue(throughput > 500, 
                  "Query throughput should exceed 500 events/sec, got: " + String.format("%.1f", throughput));
        assertTrue(duration < 5000, 
                  "Query should complete within 5 seconds, took: " + duration + "ms");
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
        double throughput = (double) rangeEvents.size() / (duration / 1000.0);

        logger.info("✅ QueryByTimeRange: {} events retrieved in {} ms ({} events/sec)",
                   rangeEvents.size(), duration, String.format("%.1f", throughput));

        // Assertions
        assertTrue(rangeEvents.size() > 0, 
                  "Should retrieve events in the time range");
        assertTrue(rangeEvents.size() <= 1800, 
                  "Should not exceed expected range size, got: " + rangeEvents.size());
        assertTrue(duration < 5000, 
                  "Query should complete within 5 seconds, took: " + duration + "ms");
        
        // Verify all events are within the time range
        for (BiTemporalEvent<TestEvent> event : rangeEvents) {
            assertTrue(event.getValidTime().isAfter(startTime.minusSeconds(1)) && 
                      event.getValidTime().isBefore(endTime.plusSeconds(1)),
                      "Event should be within time range: " + event.getValidTime());
        }
    }

    @Test
    @Order(2)
    @DisplayName("BENCHMARK: Query Optimization Patterns")
    void benchmarkQueryOptimizationPatterns() throws Exception {
        logger.info("=== BENCHMARK: Query Optimization Patterns ===");
        
        // Populate with structured test data for optimization testing
        int datasetSize = 500; // Smaller dataset for optimization testing
        logger.info("🔄 Populating structured dataset with {} events...", datasetSize);
        
        Instant baseTime = Instant.now().minusSeconds(1800); // 30 minutes ago
        List<CompletableFuture<BiTemporalEvent<TestEvent>>> populationFutures = new ArrayList<>();

        // Create events with different types and patterns
        for (int i = 0; i < datasetSize; i++) {
            String eventType = "OptimizationTest" + (i % 5); // 5 different event types
            TestEvent event = new TestEvent("opt-test-" + i, "Optimization test data " + i, i);
            Instant validTime = baseTime.plusSeconds(i * 2); // 2-second intervals
            CompletableFuture<BiTemporalEvent<TestEvent>> future = eventStore.append(eventType, event, validTime);
            populationFutures.add(future);
        }
        
        CompletableFuture.allOf(populationFutures.toArray(new CompletableFuture[0]))
                .get(60, TimeUnit.SECONDS);
        
        logger.info("✅ Structured dataset populated successfully");

        // Test different optimization patterns
        benchmarkLimitedQuery();
        benchmarkSpecificEventTypeQuery();
        benchmarkNarrowTimeRangeQuery();
    }

    private void benchmarkLimitedQuery() throws Exception {
        logger.info("🔄 Benchmarking limited query performance...");
        long startTime = System.currentTimeMillis();

        List<BiTemporalEvent<TestEvent>> limitedEvents = eventStore.query(
            EventQuery.builder().limit(50).build()
        ).get(30, TimeUnit.SECONDS);

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        double throughput = (double) limitedEvents.size() / (duration / 1000.0);

        logger.info("✅ Limited Query: {} events retrieved in {} ms ({} events/sec)",
                   limitedEvents.size(), duration, String.format("%.1f", throughput));

        // Assertions for limited query
        assertEquals(50, limitedEvents.size(), "Should retrieve exactly 50 events");
        assertTrue(duration < 1000, "Limited query should be very fast, took: " + duration + "ms");
        assertTrue(throughput > 100, "Limited query throughput should exceed 100 events/sec");
    }

    private void benchmarkSpecificEventTypeQuery() throws Exception {
        logger.info("🔄 Benchmarking specific event type query performance...");
        long startTime = System.currentTimeMillis();

        List<BiTemporalEvent<TestEvent>> specificTypeEvents = eventStore.query(
            EventQuery.builder()
                .eventType("OptimizationTest0")
                .limit(200)
                .build()
        ).get(30, TimeUnit.SECONDS);

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        double throughput = (double) specificTypeEvents.size() / (duration / 1000.0);

        logger.info("✅ Specific Type Query: {} events retrieved in {} ms ({} events/sec)",
                   specificTypeEvents.size(), duration, String.format("%.1f", throughput));

        // Assertions for specific type query
        assertTrue(specificTypeEvents.size() == 100, 
                  "Should retrieve exactly 100 OptimizationTest0 events, got: " + specificTypeEvents.size());
        assertTrue(duration < 2000, "Specific type query should be fast, took: " + duration + "ms");
        
        // Verify all events are of the correct type
        for (BiTemporalEvent<TestEvent> event : specificTypeEvents) {
            assertEquals("OptimizationTest0", event.getEventType(), 
                        "All events should be of type OptimizationTest0");
        }
    }

    private void benchmarkNarrowTimeRangeQuery() throws Exception {
        logger.info("🔄 Benchmarking narrow time range query performance...");
        
        Instant rangeStart = Instant.now().minusSeconds(1800); // 30 minutes ago
        Instant rangeEnd = rangeStart.plusSeconds(200); // 200-second window
        
        long startTime = System.currentTimeMillis();

        List<BiTemporalEvent<TestEvent>> narrowRangeEvents = eventStore.query(
            EventQuery.builder()
                .validTimeRange(new TemporalRange(rangeStart, rangeEnd))
                .build()
        ).get(30, TimeUnit.SECONDS);

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        double throughput = (double) narrowRangeEvents.size() / (duration / 1000.0);

        logger.info("✅ Narrow Range Query: {} events retrieved in {} ms ({} events/sec)",
                   narrowRangeEvents.size(), duration, String.format("%.1f", throughput));

        // Assertions for narrow range query
        assertTrue(narrowRangeEvents.size() > 0, "Should retrieve events in narrow range");
        assertTrue(narrowRangeEvents.size() <= 100, 
                  "Should not exceed expected narrow range size, got: " + narrowRangeEvents.size());
        assertTrue(duration < 2000, "Narrow range query should be fast, took: " + duration + "ms");
        
        // Verify all events are within the narrow time range
        for (BiTemporalEvent<TestEvent> event : narrowRangeEvents) {
            assertTrue(event.getValidTime().isAfter(rangeStart.minusSeconds(1)) && 
                      event.getValidTime().isBefore(rangeEnd.plusSeconds(1)),
                      "Event should be within narrow time range: " + event.getValidTime());
        }
    }
}
