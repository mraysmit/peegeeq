package dev.mars.peegeeq.performance;

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

import dev.mars.peegeeq.performance.config.PerformanceTestConfig;
import dev.mars.peegeeq.performance.harness.PerformanceTestHarness;
import dev.mars.peegeeq.performance.suite.PerformanceTestSuite;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for the PeeGeeQ Performance Test Harness.
 * 
 * This test validates the complete performance testing workflow including:
 * - Configuration loading and validation
 * - Test suite execution and orchestration
 * - Result aggregation and reporting
 * - Error handling and cleanup
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-13
 * @version 1.0
 */
@EnabledIfSystemProperty(named = "peegeeq.performance.tests", matches = "true")
class PerformanceTestHarnessIntegrationTest {
    
    private static final Logger logger = LoggerFactory.getLogger(PerformanceTestHarnessIntegrationTest.class);
    
    @Test
    void testCompletePerformanceTestExecution() {
        logger.info("🚀 Starting complete performance test harness integration test");
        
        // Create test configuration
        PerformanceTestConfig config = PerformanceTestConfig.builder()
                .testSuite("all")
                .testDuration(Duration.ofSeconds(30))
                .concurrentThreads(5)
                .warmupIterations(10)
                .testIterations(100)
                .outputDirectory("target/test-performance-reports")
                .enableDetailedLogging(true)
                .generateGraphs(false) // Disable for test
                .build();
        
        // Initialize performance test harness
        PerformanceTestHarness harness = new PerformanceTestHarness(config);
        
        // Execute all performance tests
        CompletableFuture<PerformanceTestSuite.Results> resultsFuture = harness.runAllTests();
        
        // Wait for completion and validate results
        PerformanceTestSuite.Results results = resultsFuture.join();
        
        // Validate test execution
        assertNotNull(results, "Results should not be null");
        assertTrue(results.getTotalTests() > 0, "Should have executed some tests");
        assertEquals(results.getTotalTests(), results.getSuccessfulTests() + results.getFailedTests(),
                    "Total tests should equal successful + failed tests");
        
        // Validate specific module results
        assertNotNull(results.getBitemporalResults(), "Bi-temporal results should be present");
        assertNotNull(results.getOutboxResults(), "Outbox results should be present");
        assertNotNull(results.getNativeResults(), "Native queue results should be present");
        assertNotNull(results.getDatabaseResults(), "Database results should be present");
        
        // Validate performance metrics
        validateBitemporalResults(results.getBitemporalResults());
        validateOutboxResults(results.getOutboxResults());
        validateNativeResults(results.getNativeResults());
        validateDatabaseResults(results.getDatabaseResults());
        
        // Validate metrics collection
        assertFalse(results.getMetrics().isEmpty(), "Should have collected metrics");
        
        logger.info("✅ Performance test harness integration test completed successfully");
        logger.info("📊 Test Summary: {} total, {} successful, {} failed", 
                   results.getTotalTests(), results.getSuccessfulTests(), results.getFailedTests());
    }
    
    @Test
    void testSpecificTestSuiteExecution() {
        logger.info("🎯 Testing specific test suite execution");
        
        PerformanceTestConfig config = PerformanceTestConfig.builder()
                .testSuite("bitemporal")
                .testDuration(Duration.ofSeconds(15))
                .concurrentThreads(3)
                .testIterations(50)
                .build();
        
        PerformanceTestHarness harness = new PerformanceTestHarness(config);
        
        // Execute specific test suite
        CompletableFuture<PerformanceTestSuite.Results> resultsFuture = harness.runTestSuite("bitemporal");
        PerformanceTestSuite.Results results = resultsFuture.join();
        
        // Validate results
        assertNotNull(results, "Results should not be null");
        assertTrue(results.getTotalTests() > 0, "Should have executed bi-temporal tests");
        assertNotNull(results.getBitemporalResults(), "Bi-temporal results should be present");
        
        // Other module results should be null for specific suite execution
        assertNull(results.getOutboxResults(), "Outbox results should be null for bi-temporal only test");
        
        logger.info("✅ Specific test suite execution completed successfully");
    }
    
    @Test
    void testConfigurationFromSystemProperties() {
        logger.info("⚙️ Testing configuration loading from system properties");
        
        // Set system properties
        System.setProperty("peegeeq.performance.suite", "outbox");
        System.setProperty("peegeeq.performance.duration", "20");
        System.setProperty("peegeeq.performance.threads", "8");
        
        try {
            // Load configuration from system properties
            PerformanceTestConfig config = PerformanceTestConfig.fromSystemProperties();
            
            // Validate configuration
            assertEquals("outbox", config.getTestSuite());
            assertEquals(Duration.ofSeconds(20), config.getTestDuration());
            assertEquals(8, config.getConcurrentThreads());
            
            logger.info("✅ Configuration loading from system properties successful");
            
        } finally {
            // Clean up system properties
            System.clearProperty("peegeeq.performance.suite");
            System.clearProperty("peegeeq.performance.duration");
            System.clearProperty("peegeeq.performance.threads");
        }
    }
    
    @Test
    void testAvailableTestSuites() {
        logger.info("📋 Testing available test suites enumeration");
        
        PerformanceTestConfig config = PerformanceTestConfig.builder().build();
        PerformanceTestHarness harness = new PerformanceTestHarness(config);
        
        var availableSuites = harness.getAvailableTestSuites();
        
        assertNotNull(availableSuites, "Available test suites should not be null");
        assertFalse(availableSuites.isEmpty(), "Should have available test suites");
        assertTrue(availableSuites.contains("Bi-temporal Event Store"), "Should include bi-temporal suite");
        assertTrue(availableSuites.contains("Outbox Pattern"), "Should include outbox suite");
        assertTrue(availableSuites.contains("Native Queue"), "Should include native queue suite");
        assertTrue(availableSuites.contains("Database Core"), "Should include database suite");
        
        logger.info("✅ Available test suites: {}", availableSuites);
    }
    
    private void validateBitemporalResults(PerformanceTestSuite.BitemporalResults results) {
        assertNotNull(results, "Bi-temporal results should not be null");
        assertTrue(results.getQueryThroughput() > 0, "Query throughput should be positive");
        assertTrue(results.getAppendThroughput() > 0, "Append throughput should be positive");
        assertTrue(results.getAverageQueryLatency() > 0, "Average query latency should be positive");
        assertTrue(results.getAverageAppendLatency() > 0, "Average append latency should be positive");
        assertTrue(results.getTotalEvents() > 0, "Total events should be positive");
        
        logger.debug("Bi-temporal Results: Query={} events/sec, Append={} events/sec", 
                    results.getQueryThroughput(), results.getAppendThroughput());
    }
    
    private void validateOutboxResults(PerformanceTestSuite.OutboxResults results) {
        assertNotNull(results, "Outbox results should not be null");
        assertTrue(results.getSendThroughput() > 0, "Send throughput should be positive");
        assertTrue(results.getTotalThroughput() > 0, "Total throughput should be positive");
        assertTrue(results.getAverageLatency() > 0, "Average latency should be positive");
        assertTrue(results.getTotalMessages() > 0, "Total messages should be positive");
        
        logger.debug("Outbox Results: Send={} msg/sec, Total={} msg/sec, Latency={} ms", 
                    results.getSendThroughput(), results.getTotalThroughput(), results.getAverageLatency());
    }
    
    private void validateNativeResults(PerformanceTestSuite.NativeResults results) {
        assertNotNull(results, "Native queue results should not be null");
        assertTrue(results.getThroughput() > 0, "Throughput should be positive");
        assertTrue(results.getAverageLatency() > 0, "Average latency should be positive");
        assertTrue(results.getMaxLatency() >= results.getAverageLatency(), "Max latency should be >= average");
        assertTrue(results.getMinLatency() <= results.getAverageLatency(), "Min latency should be <= average");
        assertTrue(results.getTotalMessages() > 0, "Total messages should be positive");
        
        logger.debug("Native Queue Results: Throughput={} msg/sec, Avg Latency={} ms", 
                    results.getThroughput(), results.getAverageLatency());
    }
    
    private void validateDatabaseResults(PerformanceTestSuite.DatabaseResults results) {
        assertNotNull(results, "Database results should not be null");
        assertTrue(results.getQueryThroughput() > 0, "Query throughput should be positive");
        assertTrue(results.getAverageLatency() > 0, "Average latency should be positive");
        assertTrue(results.getConnectionPoolUtilization() >= 0, "Pool utilization should be non-negative");
        assertTrue(results.getConnectionPoolUtilization() <= 100, "Pool utilization should be <= 100%");
        assertTrue(results.getTotalQueries() > 0, "Total queries should be positive");
        
        logger.debug("Database Results: Query Throughput={} queries/sec, Pool Utilization={}%", 
                    results.getQueryThroughput(), results.getConnectionPoolUtilization());
    }
}
