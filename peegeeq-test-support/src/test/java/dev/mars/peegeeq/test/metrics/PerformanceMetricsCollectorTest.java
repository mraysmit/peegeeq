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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
@Tag(TestCategories.CORE)
@ExtendWith(VertxExtension.class)
class PerformanceMetricsCollectorTest {
    private static final Logger logger = LoggerFactory.getLogger(PerformanceMetricsCollectorTest.class);
    
    private SimpleMeterRegistry meterRegistry;
    private PerformanceMetricsCollector collector;
    private String testInstanceId;
    private Vertx vertx;
    
    @BeforeEach
    void setUp(Vertx vertx) {
        logger.info("=== PerformanceMetricsCollectorTest setUp() started ===");
        
        testInstanceId = "test-collector-" + System.currentTimeMillis();
        meterRegistry = new SimpleMeterRegistry();

        collector = new PerformanceMetricsCollector(meterRegistry, testInstanceId);
        this.vertx = vertx;
        
        logger.info("Set up PerformanceMetricsCollector test with instance: {}", testInstanceId);
        logger.info("=== PerformanceMetricsCollectorTest setUp() completed ===");
    }
    
    @Test
    void testBasicMetricsCollection(VertxTestContext ctx) {
        logger.info("=== TEST METHOD STARTED: testBasicMetricsCollection ===");
        logger.info("Testing basic metrics collection");

        String testName = "basicMetricsTest";
        PerformanceProfile profile = PerformanceProfile.STANDARD;

        // Start test
        collector.startTest(testName, profile);

        // Simulate some work via a non-blocking timer
        vertx.timer(50).onComplete(ctx.succeeding(v -> ctx.verify(() -> {
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

            logger.info("Basic metrics collection test passed");
            logger.info("=== TEST METHOD COMPLETED: testBasicMetricsCollection ===");
            ctx.completeNow();
        })));
    }
    
    @Test
    void testPerformanceComparison(VertxTestContext ctx) {
        logger.info("=== TEST METHOD STARTED: testPerformanceComparison ===");
        logger.info("Testing performance comparison between profiles");

        String testName = "comparisonTest";

        // Test with BASIC profile
        collector.startTest(testName, PerformanceProfile.BASIC);
        vertx.timer(100)
            .compose(v -> {
                collector.endTest(testName, PerformanceProfile.BASIC, true, Map.of("throughput", 1000.0));
                // Test with HIGH_PERFORMANCE profile
                collector.startTest(testName, PerformanceProfile.HIGH_PERFORMANCE);
                return vertx.timer(50);
            })
            .onComplete(ctx.succeeding(v -> ctx.verify(() -> {
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

                logger.info("Performance comparison test passed");
                logger.info("=== TEST METHOD COMPLETED: testPerformanceComparison ===");
                ctx.completeNow();
            })));
    }
    
    @Test
    void testMetricsReset() {
        logger.info("=== TEST METHOD STARTED: testMetricsReset ===");
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
        
        logger.info("Metrics reset test passed");
        logger.info("=== TEST METHOD COMPLETED: testMetricsReset ===");
    }
    
    @Test
    void testThroughputCalculation(VertxTestContext ctx) {
        logger.info("=== TEST METHOD STARTED: testThroughputCalculation ===");
        logger.info("Testing throughput calculation");

        String testName = "throughputTest";
        PerformanceProfile profile = PerformanceProfile.HIGH_PERFORMANCE;

        collector.startTest(testName, profile);

        // Simulate work for a known duration via a non-blocking timer
        vertx.timer(100).onComplete(ctx.succeeding(v -> ctx.verify(() -> {
            // End test with operations count
            Map<String, Object> metrics = Map.of("operations", 1000L);
            PerformanceSnapshot snapshot = collector.endTest(testName, profile, true, metrics);

            // Verify throughput calculation
            double throughput = snapshot.getThroughput();
            assertTrue(throughput > 0, "Throughput should be calculated from operations and duration");

            // Validate throughput against the measured test duration (stable across slower/faster hosts).
            long operations = 1000L;
            long durationMs = snapshot.getDurationMs();
            assertTrue(durationMs > 0, "Duration must be > 0ms to compute throughput");

            double expectedThroughput = (operations * 1000.0) / durationMs;
            assertEquals(expectedThroughput, throughput, 0.0001,
                "Throughput should match operations/duration calculation");

            logger.info("Throughput calculation test passed (throughput: {} ops/sec)", throughput);
            logger.info("=== TEST METHOD COMPLETED: testThroughputCalculation ===");
            ctx.completeNow();
        })));
    }
    
    @Test
    void testFailedTestHandling() {
        logger.info("=== TEST METHOD STARTED: testFailedTestHandling ===");
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
        
        logger.info("Failed test handling test passed");
        logger.info("=== TEST METHOD COMPLETED: testFailedTestHandling ===");
    }
    
    @Test
    void testMultipleProfileSnapshots(VertxTestContext ctx) {
        logger.info("=== TEST METHOD STARTED: testMultipleProfileSnapshots ===");
        logger.info("Testing multiple profile snapshots");

        String testName = "multiProfileTest";

        // Test with different profiles
        PerformanceProfile[] profiles = {
            PerformanceProfile.BASIC,
            PerformanceProfile.STANDARD,
            PerformanceProfile.HIGH_PERFORMANCE
        };

        // Sequentially exercise each profile by composing reactive futures.
        Future<Void> chain = Future.succeededFuture();
        for (PerformanceProfile profile : profiles) {
            final PerformanceProfile p = profile;
            chain = chain.compose(v -> {
                collector.startTest(testName, p);
                return vertx.timer(10).map(t -> null);
            }).compose(v -> {
                collector.endTest(testName, p, true, Map.of("profile_test", p.name()));
                return Future.succeededFuture();
            });
        }

        chain.onComplete(ctx.succeeding(v -> ctx.verify(() -> {
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

            logger.info("Multiple profile snapshots test passed");
            logger.info("=== TEST METHOD COMPLETED: testMultipleProfileSnapshots ===");
            ctx.completeNow();
        })));
    }
}
