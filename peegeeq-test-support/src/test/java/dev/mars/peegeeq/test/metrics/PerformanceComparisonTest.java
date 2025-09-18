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

import dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory.PerformanceProfile;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for PerformanceComparison.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-18
 * @version 1.0
 */
class PerformanceComparisonTest {
    private static final Logger logger = LoggerFactory.getLogger(PerformanceComparisonTest.class);
    
    @Test
    void testBasicComparison() {
        System.err.println("=== TEST METHOD STARTED: testBasicComparison ===");
        logger.info("Testing basic performance comparison");
        
        Instant baseTime = Instant.now();
        
        // Baseline: slower performance
        PerformanceSnapshot baseline = new PerformanceSnapshot(
            "comparisonTest", PerformanceProfile.BASIC,
            baseTime, baseTime.plusMillis(200), Duration.ofMillis(200),
            true, Map.of("throughput", 1000.0)
        );
        
        // Comparison: faster performance
        PerformanceSnapshot comparison = new PerformanceSnapshot(
            "comparisonTest", PerformanceProfile.HIGH_PERFORMANCE,
            baseTime, baseTime.plusMillis(100), Duration.ofMillis(100),
            true, Map.of("throughput", 2000.0)
        );
        
        PerformanceComparison comp = new PerformanceComparison(baseline, comparison);
        
        assertEquals(baseline, comp.getBaseline());
        assertEquals(comparison, comp.getComparison());
        assertNotNull(comp.getMetrics());
        
        // Duration improvement: (200-100)/200 * 100 = 50%
        assertEquals(50.0, comp.getMetrics().getDurationImprovementPercent(), 0.1);
        
        // Throughput improvement: (2000-1000)/1000 * 100 = 100%
        assertEquals(100.0, comp.getMetrics().getThroughputImprovementPercent(), 0.1);
        
        assertTrue(comp.isImprovement());
        assertFalse(comp.isRegression(10.0));
        assertTrue(comp.isSignificantDifference(25.0));
        
        logger.info("✓ Basic comparison test passed");
        System.err.println("=== TEST METHOD COMPLETED: testBasicComparison ===");
    }
    
    @Test
    void testRegressionDetection() {
        System.err.println("=== TEST METHOD STARTED: testRegressionDetection ===");
        logger.info("Testing regression detection");
        
        Instant baseTime = Instant.now();
        
        // Baseline: faster performance
        PerformanceSnapshot baseline = new PerformanceSnapshot(
            "regressionTest", PerformanceProfile.HIGH_PERFORMANCE,
            baseTime, baseTime.plusMillis(50), Duration.ofMillis(50),
            true, Map.of("throughput", 4000.0)
        );
        
        // Comparison: slower performance (regression)
        PerformanceSnapshot comparison = new PerformanceSnapshot(
            "regressionTest", PerformanceProfile.BASIC,
            baseTime, baseTime.plusMillis(150), Duration.ofMillis(150),
            true, Map.of("throughput", 1000.0)
        );
        
        PerformanceComparison comp = new PerformanceComparison(baseline, comparison);
        
        // Duration regression: (50-150)/50 * 100 = -200%
        assertTrue(comp.getMetrics().getDurationImprovementPercent() < 0);
        
        // Throughput regression: (1000-4000)/4000 * 100 = -75%
        assertTrue(comp.getMetrics().getThroughputImprovementPercent() < 0);
        
        assertFalse(comp.isImprovement());
        assertTrue(comp.isRegression(10.0));
        assertTrue(comp.isRegression(50.0));
        assertTrue(comp.isSignificantDifference(25.0));
        
        logger.info("✓ Regression detection test passed");
        System.err.println("=== TEST METHOD COMPLETED: testRegressionDetection ===");
    }
    
    @Test
    void testAdditionalMetricsComparison() {
        System.err.println("=== TEST METHOD STARTED: testAdditionalMetricsComparison ===");
        logger.info("Testing additional metrics comparison");
        
        Instant baseTime = Instant.now();
        
        Map<String, Object> baselineMetrics = Map.of(
            "memory_usage", 1000.0,
            "cpu_usage", 50.0,
            "error_rate", 5.0
        );
        
        Map<String, Object> comparisonMetrics = Map.of(
            "memory_usage", 800.0,  // 20% improvement
            "cpu_usage", 60.0,      // 20% regression
            "error_rate", 2.0       // 60% improvement
        );
        
        PerformanceSnapshot baseline = new PerformanceSnapshot(
            "additionalMetricsTest", PerformanceProfile.STANDARD,
            baseTime, baseTime.plusMillis(100), Duration.ofMillis(100),
            true, baselineMetrics
        );
        
        PerformanceSnapshot comparison = new PerformanceSnapshot(
            "additionalMetricsTest", PerformanceProfile.HIGH_PERFORMANCE,
            baseTime, baseTime.plusMillis(100), Duration.ofMillis(100),
            true, comparisonMetrics
        );
        
        PerformanceComparison comp = new PerformanceComparison(baseline, comparison);
        PerformanceComparison.ComparisonMetrics metrics = comp.getMetrics();
        
        Map<String, Double> additionalComparisons = metrics.getAdditionalComparisons();
        assertFalse(additionalComparisons.isEmpty());
        
        // Memory usage improvement: (800-1000)/1000 * 100 = -20%
        assertEquals(-20.0, additionalComparisons.get("memory_usage"), 0.1);
        
        // CPU usage regression: (60-50)/50 * 100 = 20%
        assertEquals(20.0, additionalComparisons.get("cpu_usage"), 0.1);
        
        // Error rate improvement: (2-5)/5 * 100 = -60%
        assertEquals(-60.0, additionalComparisons.get("error_rate"), 0.1);
        
        // Test specific metric queries
        assertFalse(metrics.isMetricImproved("memory_usage")); // Lower is better, but shows as negative
        assertTrue(metrics.isMetricImproved("cpu_usage"));     // Higher value, but might not be better
        assertFalse(metrics.isMetricImproved("error_rate"));   // Lower is better, but shows as negative
        
        logger.info("✓ Additional metrics comparison test passed");
        System.err.println("=== TEST METHOD COMPLETED: testAdditionalMetricsComparison ===");
    }
    
    @Test
    void testZeroDurationHandling() {
        System.err.println("=== TEST METHOD STARTED: testZeroDurationHandling ===");
        logger.info("Testing zero duration handling");
        
        Instant baseTime = Instant.now();
        
        // Baseline with zero duration
        PerformanceSnapshot baseline = new PerformanceSnapshot(
            "zeroDurationTest", PerformanceProfile.BASIC,
            baseTime, baseTime, Duration.ZERO,
            true, Map.of("throughput", 0.0)
        );
        
        // Comparison with normal duration
        PerformanceSnapshot comparison = new PerformanceSnapshot(
            "zeroDurationTest", PerformanceProfile.STANDARD,
            baseTime, baseTime.plusMillis(100), Duration.ofMillis(100),
            true, Map.of("throughput", 1000.0)
        );
        
        PerformanceComparison comp = new PerformanceComparison(baseline, comparison);
        PerformanceComparison.ComparisonMetrics metrics = comp.getMetrics();
        
        // Should handle zero duration gracefully
        assertNotNull(metrics);
        // Duration improvement should be 0 when baseline is 0
        assertEquals(0.0, metrics.getDurationImprovementPercent(), 0.1);
        
        logger.info("✓ Zero duration handling test passed");
        System.err.println("=== TEST METHOD COMPLETED: testZeroDurationHandling ===");
    }
    
    @Test
    void testComparisonSummary() {
        System.err.println("=== TEST METHOD STARTED: testComparisonSummary ===");
        logger.info("Testing comparison summary");
        
        Instant baseTime = Instant.now();
        
        PerformanceSnapshot baseline = new PerformanceSnapshot(
            "summaryTest", PerformanceProfile.BASIC,
            baseTime, baseTime.plusMillis(200), Duration.ofMillis(200),
            true, Map.of("throughput", 1000.0)
        );
        
        PerformanceSnapshot comparison = new PerformanceSnapshot(
            "summaryTest", PerformanceProfile.HIGH_PERFORMANCE,
            baseTime, baseTime.plusMillis(100), Duration.ofMillis(100),
            true, Map.of("throughput", 2000.0)
        );
        
        PerformanceComparison comp = new PerformanceComparison(baseline, comparison);
        
        String summary = comp.getSummary();
        assertNotNull(summary);
        assertTrue(summary.contains("Basic Testing"));
        assertTrue(summary.contains("High Performance"));
        assertTrue(summary.contains("durationChange"));
        assertTrue(summary.contains("throughputChange"));
        assertTrue(summary.contains("improvement=true"));
        
        logger.info("✓ Comparison summary test passed: {}", summary);
        System.err.println("=== TEST METHOD COMPLETED: testComparisonSummary ===");
    }
    
    @Test
    void testComparisonEquality() {
        System.err.println("=== TEST METHOD STARTED: testComparisonEquality ===");
        logger.info("Testing comparison equality");
        
        Instant baseTime = Instant.now();
        
        PerformanceSnapshot baseline = new PerformanceSnapshot(
            "equalityTest", PerformanceProfile.BASIC,
            baseTime, baseTime.plusMillis(100), Duration.ofMillis(100),
            true, Map.of()
        );
        
        PerformanceSnapshot comparison = new PerformanceSnapshot(
            "equalityTest", PerformanceProfile.STANDARD,
            baseTime, baseTime.plusMillis(50), Duration.ofMillis(50),
            true, Map.of()
        );
        
        PerformanceComparison comp1 = new PerformanceComparison(baseline, comparison);
        PerformanceComparison comp2 = new PerformanceComparison(baseline, comparison);
        
        assertEquals(comp1, comp2);
        assertEquals(comp1.hashCode(), comp2.hashCode());
        
        logger.info("✓ Comparison equality test passed");
        System.err.println("=== TEST METHOD COMPLETED: testComparisonEquality ===");
    }
    
    @Test
    void testNullHandling() {
        System.err.println("=== TEST METHOD STARTED: testNullHandling ===");
        logger.info("Testing null handling");
        
        Instant baseTime = Instant.now();
        
        PerformanceSnapshot validSnapshot = new PerformanceSnapshot(
            "nullTest", PerformanceProfile.BASIC,
            baseTime, baseTime.plusMillis(100), Duration.ofMillis(100),
            true, Map.of()
        );
        
        // Test null parameter validation
        assertThrows(NullPointerException.class, () -> 
            new PerformanceComparison(null, validSnapshot)
        );
        
        assertThrows(NullPointerException.class, () -> 
            new PerformanceComparison(validSnapshot, null)
        );
        
        logger.info("✓ Null handling test passed");
        System.err.println("=== TEST METHOD COMPLETED: testNullHandling ===");
    }
}
