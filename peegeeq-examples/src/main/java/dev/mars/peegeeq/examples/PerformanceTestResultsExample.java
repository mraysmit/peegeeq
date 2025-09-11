package dev.mars.peegeeq.examples;

import dev.mars.peegeeq.db.performance.PerformanceTestResultsGenerator;
import dev.mars.peegeeq.db.performance.SystemInfoCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Example demonstrating how to use the PerformanceTestResultsGenerator
 * to create standardized performance test reports with system information.
 * 
 * This example shows how to:
 * - Collect system information automatically
 * - Generate standardized performance test reports
 * - Include test results with metrics
 * - Save reports to files
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-11
 * @version 1.0
 */
public class PerformanceTestResultsExample {
    private static final Logger logger = LoggerFactory.getLogger(PerformanceTestResultsExample.class);
    
    public static void main(String[] args) {
        logger.info("=== Performance Test Results Generator Example ===");
        
        // Example 1: Simple report generation
        generateSimpleReport();
        
        // Example 2: Detailed report with metrics
        generateDetailedReport();
        
        // Example 3: System info collection only
        demonstrateSystemInfoCollection();
        
        logger.info("=== Example completed ===");
    }
    
    /**
     * Demonstrates simple report generation.
     */
    private static void generateSimpleReport() {
        logger.info("Generating simple performance test report...");
        
        PerformanceTestResultsGenerator generator = new PerformanceTestResultsGenerator.Builder(
            "PeeGeeQ Simple Performance Tests",
            "Windows 11, Docker Desktop, PostgreSQL 16"
        )
        .addTest("Native Queue Test", "PASSED", "27.63 seconds")
        .addTest("Outbox Pattern Test", "PASSED", "12.87 seconds")
        .addTest("Bitemporal Test", "PARTIAL", "45.21 seconds")
        .addInfo("Test Configuration", "Standard performance settings")
        .addInfo("Database", "PostgreSQL 16 with TestContainers")
        .build();
        
        String report = generator.generateReport();
        System.out.println("\n=== SIMPLE REPORT ===");
        System.out.println(report);
        
        // Save to file
        generator.saveToFile("docs/PerformanceTestResults_Simple_Example.md");
    }
    
    /**
     * Demonstrates detailed report generation with metrics.
     */
    private static void generateDetailedReport() {
        logger.info("Generating detailed performance test report with metrics...");
        
        // Create detailed metrics for each test
        Map<String, Object> nativeQueueMetrics = new LinkedHashMap<>();
        nativeQueueMetrics.put("throughput", "10,000+ msg/sec");
        nativeQueueMetrics.put("latency", "<10ms end-to-end");
        nativeQueueMetrics.put("consumer_modes", "LISTEN_NOTIFY_ONLY, POLLING_ONLY, HYBRID");
        nativeQueueMetrics.put("notes", "Real-time LISTEN/NOTIFY, <10ms latency");
        
        Map<String, Object> outboxMetrics = new LinkedHashMap<>();
        outboxMetrics.put("throughput", "5,000+ msg/sec");
        outboxMetrics.put("implementation", "JDBC vs Reactive");
        outboxMetrics.put("safety", "Full ACID compliance");
        outboxMetrics.put("notes", "Transactional safety, JDBC vs Reactive");
        
        Map<String, Object> bitemporalMetrics = new LinkedHashMap<>();
        bitemporalMetrics.put("throughput", "956 msg/sec");
        bitemporalMetrics.put("events", "1000+ events processed");
        bitemporalMetrics.put("optimization", "Vert.x 5.x optimizations");
        bitemporalMetrics.put("improvement", "+517% improvement");
        bitemporalMetrics.put("notes", "Event sourcing + Vert.x 5.x optimizations");
        
        PerformanceTestResultsGenerator generator = new PerformanceTestResultsGenerator.Builder(
            "PeeGeeQ Comprehensive Performance Tests",
            "Windows 11, Docker Desktop, PostgreSQL 16"
        )
        .addTestWithMetrics("Native Queue Performance Test", "PASSED", "27.63 seconds", nativeQueueMetrics)
        .addTestWithMetrics("Outbox Pattern Performance Test", "PASSED", "12.87 seconds", outboxMetrics)
        .addTestWithMetrics("Bitemporal Event Store Performance Test", "PARTIAL", "45.21 seconds", bitemporalMetrics)
        .addInfo("Vert.x Version", "5.x with optimizations applied")
        .addInfo("Test Configuration", "High-performance configuration with pipelining")
        .addInfo("Database Configuration", "PostgreSQL 16 with optimized connection pool")
        .addInfo("Pool Configuration", "100 connections, 1000 wait queue")
        .addInfo("Pipelining", "Enabled (1024 limit)")
        .build();
        
        String report = generator.generateReport();
        System.out.println("\n=== DETAILED REPORT ===");
        System.out.println(report);
        
        // Save to file
        generator.saveToFile("docs/PerformanceTestResults_Detailed_Example.md");
    }
    
    /**
     * Demonstrates system information collection.
     */
    private static void demonstrateSystemInfoCollection() {
        logger.info("Demonstrating system information collection...");
        
        // Collect system information
        Map<String, Object> systemInfo = SystemInfoCollector.collectSystemInfo();
        
        System.out.println("\n=== SYSTEM INFORMATION ===");
        System.out.println("Timestamp: " + systemInfo.get("timestamp"));
        
        // Display as markdown
        System.out.println("\n=== MARKDOWN FORMAT ===");
        System.out.println(SystemInfoCollector.formatAsMarkdown());
        
        // Display as summary
        System.out.println("\n=== SUMMARY FORMAT ===");
        System.out.println(SystemInfoCollector.formatAsSummary());
    }
    
    /**
     * Example of how to integrate with actual test results.
     * This method shows how you would typically use the generator
     * in a real test scenario.
     */
    public static PerformanceTestResultsGenerator createReportFromTestResults(
            String testSuiteName,
            String testEnvironment,
            Map<String, TestExecutionResult> testResults) {
        
        PerformanceTestResultsGenerator.Builder builder = 
            new PerformanceTestResultsGenerator.Builder(testSuiteName, testEnvironment);
        
        for (Map.Entry<String, TestExecutionResult> entry : testResults.entrySet()) {
            TestExecutionResult result = entry.getValue();
            
            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("throughput", result.throughput + " msg/sec");
            metrics.put("latency", result.averageLatency + "ms");
            metrics.put("events", result.eventCount + " events");
            
            if (result.notes != null) {
                metrics.put("notes", result.notes);
            }
            
            builder.addTestWithMetrics(
                entry.getKey(),
                result.passed ? "PASSED" : "FAILED",
                result.duration + " seconds",
                metrics
            );
        }
        
        return builder.build();
    }
    
    /**
     * Simple data structure for test execution results.
     */
    public static class TestExecutionResult {
        public final boolean passed;
        public final double duration;
        public final double throughput;
        public final double averageLatency;
        public final int eventCount;
        public final String notes;
        
        public TestExecutionResult(boolean passed, double duration, double throughput, 
                                 double averageLatency, int eventCount, String notes) {
            this.passed = passed;
            this.duration = duration;
            this.throughput = throughput;
            this.averageLatency = averageLatency;
            this.eventCount = eventCount;
            this.notes = notes;
        }
    }
}
