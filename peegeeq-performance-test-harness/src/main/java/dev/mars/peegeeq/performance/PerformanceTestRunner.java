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
import dev.mars.peegeeq.performance.report.PerformanceReportGenerator;
import dev.mars.peegeeq.performance.suite.PerformanceTestSuite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;

/**
 * Main entry point for running PeeGeeQ performance tests.
 * 
 * This class orchestrates the execution of comprehensive performance tests
 * across all PeeGeeQ modules and generates detailed performance reports.
 * 
 * Usage:
 * - Run all tests: java -cp ... dev.mars.peegeeq.performance.PerformanceTestRunner
 * - Run specific suite: java -cp ... dev.mars.peegeeq.performance.PerformanceTestRunner --suite=bitemporal
 * - Custom configuration: java -cp ... dev.mars.peegeeq.performance.PerformanceTestRunner --config=custom.properties
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-13
 * @version 1.0
 */
public class PerformanceTestRunner {
    
    private static final Logger logger = LoggerFactory.getLogger(PerformanceTestRunner.class);
    
    public static void main(String[] args) {
        logger.info("🚀 Starting PeeGeeQ Performance Test Harness");
        
        try {
            // Parse command line arguments
            PerformanceTestConfig config = parseArguments(args);
            
            // Initialize performance test harness
            PerformanceTestHarness harness = new PerformanceTestHarness(config);
            
            // Execute performance test suite
            Instant startTime = Instant.now();
            PerformanceTestSuite.Results results = harness.runAllTests().join();
            Duration totalDuration = Duration.between(startTime, Instant.now());
            
            // Generate performance report
            PerformanceReportGenerator reportGenerator = new PerformanceReportGenerator();
            String reportPath = reportGenerator.generateReport(results, totalDuration);
            
            // Print summary
            printSummary(results, totalDuration, reportPath);
            
            // Exit with appropriate code
            System.exit(results.hasFailures() ? 1 : 0);
            
        } catch (Exception e) {
            logger.error("❌ Performance test execution failed", e);
            System.exit(1);
        }
    }
    
    private static PerformanceTestConfig parseArguments(String[] args) {
        PerformanceTestConfig.Builder configBuilder = PerformanceTestConfig.builder();
        
        for (String arg : args) {
            if (arg.startsWith("--suite=")) {
                String suite = arg.substring("--suite=".length());
                configBuilder.testSuite(suite);
            } else if (arg.startsWith("--config=")) {
                String configFile = arg.substring("--config=".length());
                configBuilder.configFile(configFile);
            } else if (arg.startsWith("--duration=")) {
                int duration = Integer.parseInt(arg.substring("--duration=".length()));
                configBuilder.testDuration(Duration.ofSeconds(duration));
            } else if (arg.startsWith("--threads=")) {
                int threads = Integer.parseInt(arg.substring("--threads=".length()));
                configBuilder.concurrentThreads(threads);
            } else if (arg.startsWith("--output=")) {
                String outputDir = arg.substring("--output=".length());
                configBuilder.outputDirectory(outputDir);
            } else if ("--help".equals(arg) || "-h".equals(arg)) {
                printUsage();
                System.exit(0);
            }
        }
        
        return configBuilder.build();
    }
    
    private static void printUsage() {
        System.out.println("PeeGeeQ Performance Test Harness");
        System.out.println("Usage: java -cp ... dev.mars.peegeeq.performance.PerformanceTestRunner [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --suite=<name>      Run specific test suite (bitemporal, outbox, native, db, all)");
        System.out.println("  --config=<file>     Use custom configuration file");
        System.out.println("  --duration=<sec>    Test duration in seconds (default: 300)");
        System.out.println("  --threads=<num>     Number of concurrent threads (default: 10)");
        System.out.println("  --output=<dir>      Output directory for reports (default: target/performance-reports)");
        System.out.println("  --help, -h          Show this help message");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  # Run all performance tests");
        System.out.println("  java -cp ... dev.mars.peegeeq.performance.PerformanceTestRunner");
        System.out.println();
        System.out.println("  # Run bi-temporal tests only");
        System.out.println("  java -cp ... dev.mars.peegeeq.performance.PerformanceTestRunner --suite=bitemporal");
        System.out.println();
        System.out.println("  # Run stress test with 100 threads for 1 hour");
        System.out.println("  java -cp ... dev.mars.peegeeq.performance.PerformanceTestRunner --threads=100 --duration=3600");
    }
    
    private static void printSummary(PerformanceTestSuite.Results results, Duration totalDuration, String reportPath) {
        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println("🎯 PEEGEEQ PERFORMANCE TEST SUMMARY");
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println();
        
        System.out.printf("📊 Total Tests Executed: %d%n", results.getTotalTests());
        System.out.printf("✅ Successful Tests: %d%n", results.getSuccessfulTests());
        System.out.printf("❌ Failed Tests: %d%n", results.getFailedTests());
        System.out.printf("⏱️  Total Duration: %s%n", formatDuration(totalDuration));
        System.out.println();
        
        // Print key performance metrics
        if (results.getBitemporalResults() != null) {
            System.out.println("🔄 Bi-temporal Performance:");
            System.out.printf("   Query Performance: %.0f events/sec%n", results.getBitemporalResults().getQueryThroughput());
            System.out.printf("   Append Performance: %.0f events/sec%n", results.getBitemporalResults().getAppendThroughput());
        }
        
        if (results.getOutboxResults() != null) {
            System.out.println("📤 Outbox Performance:");
            System.out.printf("   Send Throughput: %.0f msg/sec%n", results.getOutboxResults().getSendThroughput());
            System.out.printf("   Total Throughput: %.0f msg/sec%n", results.getOutboxResults().getTotalThroughput());
        }
        
        if (results.getNativeResults() != null) {
            System.out.println("⚡ Native Queue Performance:");
            System.out.printf("   Message Processing: %.2f ms avg latency%n", results.getNativeResults().getAverageLatency());
            System.out.printf("   Throughput: %.0f msg/sec%n", results.getNativeResults().getThroughput());
        }
        
        if (results.getDatabaseResults() != null) {
            System.out.println("🗄️  Database Performance:");
            System.out.printf("   Query Performance: %.0f queries/sec%n", results.getDatabaseResults().getQueryThroughput());
            System.out.printf("   Average Latency: %.2f ms%n", results.getDatabaseResults().getAverageLatency());
        }
        
        System.out.println();
        System.out.printf("📋 Detailed Report: %s%n", reportPath);
        System.out.println("═══════════════════════════════════════════════════════════════");
        
        if (results.hasFailures()) {
            System.out.println("⚠️  Some tests failed. Check the detailed report for more information.");
        } else {
            System.out.println("🎉 All performance tests completed successfully!");
        }
    }
    
    private static String formatDuration(Duration duration) {
        long seconds = duration.getSeconds();
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        
        if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes, secs);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, secs);
        } else {
            return String.format("%ds", secs);
        }
    }
}
