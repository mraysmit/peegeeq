package dev.mars.peegeeq.test.metrics;

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

import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory.PerformanceProfile;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for PerformanceSnapshot.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-18
 * @version 1.0
 */
@Tag(TestCategories.CORE)
class PerformanceSnapshotTest {
    private static final Logger logger = LoggerFactory.getLogger(PerformanceSnapshotTest.class);
    
    @Test
    void testBasicSnapshotCreation() {
        System.err.println("=== TEST METHOD STARTED: testBasicSnapshotCreation ===");
        logger.info("Testing basic snapshot creation");
        
        String testName = "basicTest";
        PerformanceProfile profile = PerformanceProfile.STANDARD;
        Instant startTime = Instant.now();
        Instant endTime = startTime.plusMillis(100);
        Duration duration = Duration.between(startTime, endTime);
        boolean success = true;
        Map<String, Object> additionalMetrics = Map.of(
            "operations", 1000L,
            "throughput", 10000.0
        );
        
        PerformanceSnapshot snapshot = new PerformanceSnapshot(
            testName, profile, startTime, endTime, duration, success, additionalMetrics
        );
        
        assertEquals(testName, snapshot.getTestName());
        assertEquals(profile, snapshot.getProfile());
        assertEquals(startTime, snapshot.getStartTime());
        assertEquals(endTime, snapshot.getEndTime());
        assertEquals(duration, snapshot.getDuration());
        assertEquals(100L, snapshot.getDurationMs());
        assertTrue(snapshot.isSuccess());
        assertEquals(1000L, snapshot.getAdditionalMetricAsLong("operations", 0L));
        assertEquals(10000.0, snapshot.getAdditionalMetricAsDouble("throughput", 0.0));
        
        logger.info("✓ Basic snapshot creation test passed");
        System.err.println("=== TEST METHOD COMPLETED: testBasicSnapshotCreation ===");
    }
    
    @Test
    void testThroughputCalculation() {
        System.err.println("=== TEST METHOD STARTED: testThroughputCalculation ===");
        logger.info("Testing throughput calculation");
        
        Instant startTime = Instant.now();
        Instant endTime = startTime.plusMillis(1000); // 1 second
        Duration duration = Duration.between(startTime, endTime);
        
        // Test with operations count
        Map<String, Object> metricsWithOps = Map.of("operations", 5000L);
        PerformanceSnapshot snapshot1 = new PerformanceSnapshot(
            "throughputTest1", PerformanceProfile.HIGH_PERFORMANCE, 
            startTime, endTime, duration, true, metricsWithOps
        );
        
        double throughput1 = snapshot1.getThroughput();
        assertEquals(5000.0, throughput1, 0.1); // 5000 ops in 1 second = 5000 ops/sec
        
        // Test with direct throughput
        Map<String, Object> metricsWithThroughput = Map.of("throughput", 7500.0);
        PerformanceSnapshot snapshot2 = new PerformanceSnapshot(
            "throughputTest2", PerformanceProfile.HIGH_PERFORMANCE, 
            startTime, endTime, duration, true, metricsWithThroughput
        );
        
        double throughput2 = snapshot2.getThroughput();
        assertEquals(7500.0, throughput2, 0.1);
        
        // Test calculate throughput method
        double calculatedThroughput = snapshot1.calculateThroughput(10000L);
        assertEquals(10000.0, calculatedThroughput, 0.1); // 10000 ops in 1 second
        
        logger.info("✓ Throughput calculation test passed");
        System.err.println("=== TEST METHOD COMPLETED: testThroughputCalculation ===");
    }
    
    @Test
    void testLatencyMetrics() {
        System.err.println("=== TEST METHOD STARTED: testLatencyMetrics ===");
        logger.info("Testing latency metrics");
        
        Map<String, Double> latencyData = Map.of(
            "p50", 10.5,
            "p95", 25.0,
            "p99", 50.0
        );
        
        Map<String, Object> additionalMetrics = Map.of("latency", latencyData);
        
        PerformanceSnapshot snapshot = new PerformanceSnapshot(
            "latencyTest", PerformanceProfile.HIGH_PERFORMANCE,
            Instant.now(), Instant.now().plusMillis(100), Duration.ofMillis(100),
            true, additionalMetrics
        );
        
        Map<String, Double> retrievedLatency = snapshot.getLatencyMetrics();
        assertFalse(retrievedLatency.isEmpty());
        assertEquals(10.5, retrievedLatency.get("p50"));
        assertEquals(25.0, retrievedLatency.get("p95"));
        assertEquals(50.0, retrievedLatency.get("p99"));
        
        logger.info("✓ Latency metrics test passed");
        System.err.println("=== TEST METHOD COMPLETED: testLatencyMetrics ===");
    }
    
    @Test
    void testSnapshotSummary() {
        System.err.println("=== TEST METHOD STARTED: testSnapshotSummary ===");
        logger.info("Testing snapshot summary");
        
        Map<String, Object> additionalMetrics = Map.of(
            "throughput", 5000.0,
            "latency", Map.of("p50", 10.0, "p95", 25.0)
        );
        
        PerformanceSnapshot snapshot = new PerformanceSnapshot(
            "summaryTest", PerformanceProfile.STANDARD,
            Instant.now(), Instant.now().plusMillis(200), Duration.ofMillis(200),
            true, additionalMetrics
        );
        
        String summary = snapshot.getSummary();
        assertNotNull(summary);
        assertTrue(summary.contains("summaryTest"));
        assertTrue(summary.contains("Standard Performance"));
        assertTrue(summary.contains("200ms"));
        assertTrue(summary.contains("success=true"));
        assertTrue(summary.contains("throughput=5000.00"));
        
        logger.info("✓ Snapshot summary test passed: {}", summary);
        System.err.println("=== TEST METHOD COMPLETED: testSnapshotSummary ===");
    }
    
    @Test
    void testSnapshotEquality() {
        System.err.println("=== TEST METHOD STARTED: testSnapshotEquality ===");
        logger.info("Testing snapshot equality");
        
        Instant startTime = Instant.now();
        Instant endTime = startTime.plusMillis(100);
        Duration duration = Duration.between(startTime, endTime);
        Map<String, Object> metrics = Map.of("operations", 1000L);
        
        PerformanceSnapshot snapshot1 = new PerformanceSnapshot(
            "equalityTest", PerformanceProfile.BASIC,
            startTime, endTime, duration, true, metrics
        );
        
        PerformanceSnapshot snapshot2 = new PerformanceSnapshot(
            "equalityTest", PerformanceProfile.BASIC,
            startTime, endTime, duration, true, metrics
        );
        
        PerformanceSnapshot snapshot3 = new PerformanceSnapshot(
            "differentTest", PerformanceProfile.BASIC,
            startTime, endTime, duration, true, metrics
        );
        
        assertEquals(snapshot1, snapshot2);
        assertEquals(snapshot1.hashCode(), snapshot2.hashCode());
        assertNotEquals(snapshot1, snapshot3);
        assertNotEquals(snapshot1.hashCode(), snapshot3.hashCode());
        
        logger.info("✓ Snapshot equality test passed");
        System.err.println("=== TEST METHOD COMPLETED: testSnapshotEquality ===");
    }
    
    @Test
    void testNullHandling() {
        System.err.println("=== TEST METHOD STARTED: testNullHandling ===");
        logger.info("Testing null handling");
        
        Instant startTime = Instant.now();
        Instant endTime = startTime.plusMillis(100);
        Duration duration = Duration.between(startTime, endTime);
        
        // Test with null additional metrics
        PerformanceSnapshot snapshot = new PerformanceSnapshot(
            "nullTest", PerformanceProfile.BASIC,
            startTime, endTime, duration, true, null
        );
        
        assertNotNull(snapshot.getAdditionalMetrics());
        assertTrue(snapshot.getAdditionalMetrics().isEmpty());
        assertEquals(0.0, snapshot.getThroughput());
        assertTrue(snapshot.getLatencyMetrics().isEmpty());
        
        // Test null parameter validation
        assertThrows(NullPointerException.class, () -> 
            new PerformanceSnapshot(null, PerformanceProfile.BASIC, startTime, endTime, duration, true, null)
        );
        
        assertThrows(NullPointerException.class, () -> 
            new PerformanceSnapshot("test", null, startTime, endTime, duration, true, null)
        );
        
        logger.info("✓ Null handling test passed");
        System.err.println("=== TEST METHOD COMPLETED: testNullHandling ===");
    }
    
    @Test
    void testMetricTypeConversion() {
        System.err.println("=== TEST METHOD STARTED: testMetricTypeConversion ===");
        logger.info("Testing metric type conversion");
        
        Map<String, Object> additionalMetrics = Map.of(
            "intValue", 42,
            "longValue", 1000L,
            "doubleValue", 3.14,
            "stringValue", "not a number"
        );
        
        PerformanceSnapshot snapshot = new PerformanceSnapshot(
            "conversionTest", PerformanceProfile.STANDARD,
            Instant.now(), Instant.now().plusMillis(100), Duration.ofMillis(100),
            true, additionalMetrics
        );
        
        // Test successful conversions
        assertEquals(42L, snapshot.getAdditionalMetricAsLong("intValue", 0L));
        assertEquals(1000L, snapshot.getAdditionalMetricAsLong("longValue", 0L));
        assertEquals(3.14, snapshot.getAdditionalMetricAsDouble("doubleValue", 0.0), 0.001);
        assertEquals(42.0, snapshot.getAdditionalMetricAsDouble("intValue", 0.0), 0.001);
        
        // Test default values for non-numeric or missing values
        assertEquals(999L, snapshot.getAdditionalMetricAsLong("stringValue", 999L));
        assertEquals(888.0, snapshot.getAdditionalMetricAsDouble("missingValue", 888.0), 0.001);
        
        logger.info("✓ Metric type conversion test passed");
        System.err.println("=== TEST METHOD COMPLETED: testMetricTypeConversion ===");
    }
}
