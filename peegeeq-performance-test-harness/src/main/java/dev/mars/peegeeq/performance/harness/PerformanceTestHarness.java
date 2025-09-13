package dev.mars.peegeeq.performance.harness;

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
import dev.mars.peegeeq.performance.suite.PerformanceTestSuite;
import dev.mars.peegeeq.performance.suite.BitemporalPerformanceTestSuite;
import dev.mars.peegeeq.performance.suite.OutboxPerformanceTestSuite;
import dev.mars.peegeeq.performance.suite.NativeQueuePerformanceTestSuite;
import dev.mars.peegeeq.performance.suite.DatabasePerformanceTestSuite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Main performance test harness that orchestrates the execution of all performance test suites.
 * 
 * This class manages the lifecycle of performance tests, including:
 * - Test suite selection and configuration
 * - Parallel test execution
 * - Resource management and cleanup
 * - Result aggregation and reporting
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-13
 * @version 1.0
 */
public class PerformanceTestHarness {
    
    private static final Logger logger = LoggerFactory.getLogger(PerformanceTestHarness.class);
    
    private final PerformanceTestConfig config;
    private final ExecutorService executorService;
    private final List<PerformanceTestSuite> testSuites;
    
    public PerformanceTestHarness(PerformanceTestConfig config) {
        this.config = config;
        this.executorService = Executors.newFixedThreadPool(config.getConcurrentThreads());
        this.testSuites = createTestSuites();
        
        logger.info("Initialized PerformanceTestHarness with {} test suites", testSuites.size());
    }
    
    /**
     * Run all configured performance test suites.
     */
    public CompletableFuture<PerformanceTestSuite.Results> runAllTests() {
        logger.info("🚀 Starting performance test execution for suite: {}", config.getTestSuite());
        
        return CompletableFuture.supplyAsync(() -> {
            PerformanceTestSuite.Results aggregatedResults = new PerformanceTestSuite.Results();
            
            try {
                // Run warmup if configured
                if (config.getWarmupIterations() > 0) {
                    logger.info("🔥 Running warmup phase with {} iterations", config.getWarmupIterations());
                    runWarmup();
                }
                
                // Execute test suites
                for (PerformanceTestSuite suite : testSuites) {
                    logger.info("📊 Executing test suite: {}", suite.getName());
                    
                    try {
                        PerformanceTestSuite.Results suiteResults = suite.execute(config).join();
                        aggregatedResults.merge(suiteResults);
                        
                        logger.info("✅ Completed test suite: {} (Success: {}, Failures: {})", 
                                   suite.getName(), suiteResults.getSuccessfulTests(), suiteResults.getFailedTests());
                        
                    } catch (Exception e) {
                        logger.error("❌ Test suite {} failed with error", suite.getName(), e);
                        aggregatedResults.addFailure(suite.getName(), e);
                    }
                }
                
                logger.info("🎯 Performance test execution completed. Total tests: {}, Successful: {}, Failed: {}", 
                           aggregatedResults.getTotalTests(), 
                           aggregatedResults.getSuccessfulTests(), 
                           aggregatedResults.getFailedTests());
                
                return aggregatedResults;
                
            } catch (Exception e) {
                logger.error("❌ Performance test execution failed", e);
                aggregatedResults.addFailure("TestHarness", e);
                return aggregatedResults;
            } finally {
                cleanup();
            }
        }, executorService);
    }
    
    /**
     * Run a specific test suite by name.
     */
    public CompletableFuture<PerformanceTestSuite.Results> runTestSuite(String suiteName) {
        logger.info("🎯 Running specific test suite: {}", suiteName);

        return testSuites.stream()
                .filter(suite -> matchesSuiteName(suite.getName(), suiteName))
                .findFirst()
                .map(suite -> suite.execute(config))
                .orElseGet(() -> {
                    logger.error("❌ Test suite not found: {}", suiteName);
                    PerformanceTestSuite.Results results = new PerformanceTestSuite.Results();
                    results.addFailure(suiteName, new IllegalArgumentException("Test suite not found: " + suiteName));
                    return CompletableFuture.completedFuture(results);
                });
    }

    /**
     * Check if a suite name matches the requested name (flexible matching).
     */
    private boolean matchesSuiteName(String suiteName, String requestedName) {
        // Exact match
        if (suiteName.equalsIgnoreCase(requestedName)) {
            return true;
        }

        // Check if requested name matches key words in suite name
        String lowerSuiteName = suiteName.toLowerCase();
        String lowerRequestedName = requestedName.toLowerCase();

        return switch (lowerRequestedName) {
            case "bitemporal", "bi-temporal" -> lowerSuiteName.contains("bi-temporal");
            case "outbox" -> lowerSuiteName.contains("outbox");
            case "native", "queue" -> lowerSuiteName.contains("native");
            case "database", "db" -> lowerSuiteName.contains("database");
            default -> lowerSuiteName.contains(lowerRequestedName);
        };
    }
    
    /**
     * Get list of available test suite names.
     */
    public List<String> getAvailableTestSuites() {
        return testSuites.stream()
                .map(PerformanceTestSuite::getName)
                .toList();
    }
    
    /**
     * Create test suites based on configuration.
     */
    private List<PerformanceTestSuite> createTestSuites() {
        List<PerformanceTestSuite> suites = new ArrayList<>();
        
        String testSuite = config.getTestSuite().toLowerCase();
        
        if ("all".equals(testSuite) || "bitemporal".equals(testSuite)) {
            suites.add(new BitemporalPerformanceTestSuite());
        }
        
        if ("all".equals(testSuite) || "outbox".equals(testSuite)) {
            suites.add(new OutboxPerformanceTestSuite());
        }
        
        if ("all".equals(testSuite) || "native".equals(testSuite)) {
            suites.add(new NativeQueuePerformanceTestSuite());
        }
        
        if ("all".equals(testSuite) || "database".equals(testSuite) || "db".equals(testSuite)) {
            suites.add(new DatabasePerformanceTestSuite());
        }
        
        if (suites.isEmpty()) {
            logger.warn("⚠️ No test suites matched configuration: {}. Adding all suites.", testSuite);
            suites.add(new BitemporalPerformanceTestSuite());
            suites.add(new OutboxPerformanceTestSuite());
            suites.add(new NativeQueuePerformanceTestSuite());
            suites.add(new DatabasePerformanceTestSuite());
        }
        
        return suites;
    }
    
    /**
     * Run warmup iterations to prepare the system for performance testing.
     */
    private void runWarmup() {
        try {
            // Simple warmup - create and destroy some test objects
            for (int i = 0; i < config.getWarmupIterations(); i++) {
                // Warmup JVM, connection pools, etc.
                Thread.sleep(1);
                
                if (i % 100 == 0) {
                    logger.debug("Warmup progress: {}/{}", i, config.getWarmupIterations());
                }
            }
            
            // Force garbage collection after warmup
            System.gc();
            Thread.sleep(100);
            
            logger.info("✅ Warmup phase completed");
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("⚠️ Warmup phase interrupted", e);
        }
    }
    
    /**
     * Clean up resources.
     */
    private void cleanup() {
        logger.info("🧹 Cleaning up performance test harness resources");
        
        try {
            // Shutdown test suites
            for (PerformanceTestSuite suite : testSuites) {
                try {
                    suite.cleanup();
                } catch (Exception e) {
                    logger.warn("⚠️ Error cleaning up test suite: {}", suite.getName(), e);
                }
            }
            
            // Shutdown executor service
            executorService.shutdown();
            
            logger.info("✅ Performance test harness cleanup completed");
            
        } catch (Exception e) {
            logger.error("❌ Error during cleanup", e);
        }
    }
    
    /**
     * Get performance test configuration.
     */
    public PerformanceTestConfig getConfig() {
        return config;
    }
}
