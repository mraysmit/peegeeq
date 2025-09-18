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

import dev.mars.peegeeq.db.metrics.PeeGeeQMetrics;
import dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory.PerformanceProfile;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for PerformanceMetricsCollector.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-18
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
class PerformanceMetricsCollectorTest {
    private static final Logger logger = LoggerFactory.getLogger(PerformanceMetricsCollectorTest.class);
    
    @Mock
    private Pool mockPool;
    
    private PeeGeeQMetrics baseMetrics;
    private SimpleMeterRegistry meterRegistry;
    private PerformanceMetricsCollector collector;
    private String testInstanceId;
    
    @BeforeEach
    void setUp() {
        System.err.println("=== PerformanceMetricsCollectorTest setUp() started ===");
        
        testInstanceId = "test-collector-" + System.currentTimeMillis();
        meterRegistry = new SimpleMeterRegistry();
        baseMetrics = new PeeGeeQMetrics(mockPool, testInstanceId);
        baseMetrics.bindTo(meterRegistry);
        
        collector = new PerformanceMetricsCollector(baseMetrics, testInstanceId);
        
        logger.info("Set up PerformanceMetricsCollector test with instance: {}", testInstanceId);
        System.err.println("=== PerformanceMetricsCollectorTest setUp() completed ===");
    }
    
    @Test
    void testBasicMetricsCollection() {
        System.err.println("=== TEST METHOD STARTED: testBasicMetricsCollection ===");
        logger.info("Testing basic metrics collection");
        
        String testName = "basicMetricsTest";
        PerformanceProfile profile = PerformanceProfile.STANDARD;
        
        // Start test
        collector.startTest(testName, profile);
        
        // Simulate some work
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Record some metrics during the test
        collector.recordTestTimer("operation.time", Duration.ofMillis(25), profile, Map.of("operation", "test"));
        collector.recordTestCounter("operations", profile, Map.of("type", "success"));
        collector.recordTestGauge("memory.usage", 1024.0, profile, Map.of("unit", "MB"));
        
        // End test
        Map<String, Object> additionalMetrics = Map.of(
            "operations", 100L,
            "throughput", 2000.0
        );
        PerformanceSnapshot snapshot = collector.endTest(testName, profile, true, additionalMetrics);
        
        // Verify snapshot
        assertNotNull(snapshot);
        assertEquals(testName, snapshot.getTestName());
        assertEquals(profile, snapshot.getProfile());
        assertTrue(snapshot.isSuccess());
        assertTrue(snapshot.getDurationMs() >= 50);
        assertEquals(100L, snapshot.getAdditionalMetricAsLong("operations", 0L));
        assertEquals(2000.0, snapshot.getAdditionalMetricAsDouble("throughput", 0.0));
        
        logger.info("✓ Basic metrics collection test passed");
        System.err.println("=== TEST METHOD COMPLETED: testBasicMetricsCollection ===");
    }
    
    @Test
    void testPerformanceComparison() {
        System.err.println("=== TEST METHOD STARTED: testPerformanceComparison ===");
        logger.info("Testing performance comparison between profiles");
        
        String testName = "comparisonTest";
        
        // Test with BASIC profile
        collector.startTest(testName, PerformanceProfile.BASIC);
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        collector.endTest(testName, PerformanceProfile.BASIC, true, Map.of("throughput", 1000.0));
        
        // Test with HIGH_PERFORMANCE profile
        collector.startTest(testName, PerformanceProfile.HIGH_PERFORMANCE);
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        collector.endTest(testName, PerformanceProfile.HIGH_PERFORMANCE, true, Map.of("throughput", 2000.0));
        
        // Get comparisons
        Map<String, PerformanceComparison> comparisons = collector.getProfileComparisons();
        assertFalse(comparisons.isEmpty());
        
        // Verify comparison exists
        String comparisonKey = "BASIC_vs_HIGH_PERFORMANCE";
        assertTrue(comparisons.containsKey(comparisonKey) || 
                  comparisons.containsKey("HIGH_PERFORMANCE_vs_BASIC"));
        
        PerformanceComparison comparison = comparisons.values().iterator().next();
        assertNotNull(comparison);
        assertNotNull(comparison.getBaseline());
        assertNotNull(comparison.getComparison());
        assertNotNull(comparison.getMetrics());
        
        logger.info("✓ Performance comparison test passed");
        System.err.println("=== TEST METHOD COMPLETED: testPerformanceComparison ===");
    }
    
    @Test
    void testMetricsReset() {
        System.err.println("=== TEST METHOD STARTED: testMetricsReset ===");
        logger.info("Testing metrics reset functionality");
        
        // Record some metrics
        collector.startTest("resetTest", PerformanceProfile.STANDARD);
        collector.recordTestCounter("test.counter", PerformanceProfile.STANDARD, Map.of());
        collector.endTest("resetTest", PerformanceProfile.STANDARD, true, Map.of());
        
        // Verify metrics exist
        assertFalse(collector.getPerformanceSnapshots().isEmpty());
        
        // Reset
        collector.reset();
        
        // Verify metrics are cleared
        assertTrue(collector.getPerformanceSnapshots().isEmpty());
        assertTrue(collector.getProfileComparisons().isEmpty());
        
        logger.info("✓ Metrics reset test passed");
        System.err.println("=== TEST METHOD COMPLETED: testMetricsReset ===");
    }
    
    @Test
    void testThroughputCalculation() {
        System.err.println("=== TEST METHOD STARTED: testThroughputCalculation ===");
        logger.info("Testing throughput calculation");
        
        String testName = "throughputTest";
        PerformanceProfile profile = PerformanceProfile.HIGH_PERFORMANCE;
        
        collector.startTest(testName, profile);
        
        // Simulate work for a known duration
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // End test with operations count
        Map<String, Object> metrics = Map.of("operations", 1000L);
        PerformanceSnapshot snapshot = collector.endTest(testName, profile, true, metrics);
        
        // Verify throughput calculation
        double throughput = snapshot.getThroughput();
        assertTrue(throughput > 0, "Throughput should be calculated from operations and duration");
        
        // Should be approximately 10,000 ops/sec (1000 ops in ~100ms)
        assertTrue(throughput > 5000 && throughput < 15000, 
                  "Throughput should be reasonable: " + throughput);
        
        logger.info("✓ Throughput calculation test passed (throughput: {} ops/sec)", throughput);
        System.err.println("=== TEST METHOD COMPLETED: testThroughputCalculation ===");
    }
    
    @Test
    void testFailedTestHandling() {
        System.err.println("=== TEST METHOD STARTED: testFailedTestHandling ===");
        logger.info("Testing failed test handling");
        
        String testName = "failedTest";
        PerformanceProfile profile = PerformanceProfile.BASIC;
        
        collector.startTest(testName, profile);
        
        // Simulate a failed test
        PerformanceSnapshot snapshot = collector.endTest(testName, profile, false, Map.of());
        
        // Verify failure is recorded
        assertFalse(snapshot.isSuccess());
        assertEquals(testName, snapshot.getTestName());
        assertEquals(profile, snapshot.getProfile());
        
        logger.info("✓ Failed test handling test passed");
        System.err.println("=== TEST METHOD COMPLETED: testFailedTestHandling ===");
    }
    
    @Test
    void testMultipleProfileSnapshots() {
        System.err.println("=== TEST METHOD STARTED: testMultipleProfileSnapshots ===");
        logger.info("Testing multiple profile snapshots");
        
        String testName = "multiProfileTest";
        
        // Test with different profiles
        PerformanceProfile[] profiles = {
            PerformanceProfile.BASIC,
            PerformanceProfile.STANDARD,
            PerformanceProfile.HIGH_PERFORMANCE
        };
        
        for (PerformanceProfile profile : profiles) {
            collector.startTest(testName, profile);
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            collector.endTest(testName, profile, true, Map.of("profile_test", profile.name()));
        }
        
        // Verify all snapshots are stored
        Map<PerformanceProfile, PerformanceSnapshot> snapshots = collector.getPerformanceSnapshots();
        assertEquals(profiles.length, snapshots.size());
        
        for (PerformanceProfile profile : profiles) {
            assertTrue(snapshots.containsKey(profile));
            PerformanceSnapshot snapshot = snapshots.get(profile);
            assertEquals(testName, snapshot.getTestName());
            assertEquals(profile, snapshot.getProfile());
            assertTrue(snapshot.isSuccess());
        }
        
        logger.info("✓ Multiple profile snapshots test passed");
        System.err.println("=== TEST METHOD COMPLETED: testMultipleProfileSnapshots ===");
    }
}
