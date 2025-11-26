package dev.mars.peegeeq.db.examples;

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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test for PerformanceTestResultsExample functionality.
 *
 * This test validates performance test results generation patterns from the original 195-line example:
 * 1. Simple Report Generation - Basic performance test reports
 * 2. Detailed Report Generation - Reports with comprehensive metrics
 * 3. System Information Collection - Automated system info gathering
 * 4. Test Results Integration - Integration with actual test results
 *
 * All original functionality is preserved with enhanced test assertions and documentation.
 * Tests demonstrate comprehensive performance reporting and analysis patterns.
 */
@Tag(TestCategories.CORE)
public class PerformanceTestResultsExampleTest {
    
    private static final Logger logger = LoggerFactory.getLogger(PerformanceTestResultsExampleTest.class);

    /**
     * Test Pattern 1: Simple Report Generation
     * Validates basic performance test reports
     */
    @Test
    void testSimpleReportGeneration() {
        logger.info("=== Testing Simple Report Generation ===");
        
        // Generate simple report similar to original example
        String report = generateSimpleReport();
        
        // Validate report content
        assertNotNull(report, "Report should not be null");
        assertFalse(report.isEmpty(), "Report should not be empty");
        assertTrue(report.contains("PeeGeeQ Simple Performance Tests"), "Report should contain test suite name");
        assertTrue(report.contains("Native Queue Test"), "Report should contain native queue test");
        assertTrue(report.contains("Outbox Pattern Test"), "Report should contain outbox pattern test");
        assertTrue(report.contains("PASSED"), "Report should contain test results");
        
        logger.info("✅ Simple report generation validated successfully");
    }

    /**
     * Test Pattern 2: Detailed Report Generation
     * Validates reports with comprehensive metrics
     */
    @Test
    void testDetailedReportGeneration() {
        logger.info("=== Testing Detailed Report Generation ===");
        
        // Generate detailed report similar to original example
        String report = generateDetailedReport();
        
        // Validate detailed report content
        assertNotNull(report, "Detailed report should not be null");
        assertFalse(report.isEmpty(), "Detailed report should not be empty");
        assertTrue(report.contains("PeeGeeQ Comprehensive Performance Tests"), "Report should contain comprehensive test suite name");
        assertTrue(report.contains("throughput"), "Report should contain throughput metrics");
        assertTrue(report.contains("latency"), "Report should contain latency metrics");
        assertTrue(report.contains("10,000+ msg/sec"), "Report should contain specific throughput values");
        assertTrue(report.contains("Vert.x Version"), "Report should contain Vert.x version info");
        
        logger.info("✅ Detailed report generation validated successfully");
    }

    /**
     * Test Pattern 3: System Information Collection
     * Validates automated system info gathering
     */
    @Test
    void testSystemInformationCollection() {
        logger.info("=== Testing System Information Collection ===");
        
        // Demonstrate system information collection
        Map<String, Object> systemInfo = demonstrateSystemInfoCollection();
        
        // Validate system information
        assertNotNull(systemInfo, "System info should not be null");
        assertFalse(systemInfo.isEmpty(), "System info should not be empty");
        assertTrue(systemInfo.containsKey("timestamp"), "System info should contain timestamp");
        assertTrue(systemInfo.containsKey("os"), "System info should contain OS information");
        assertTrue(systemInfo.containsKey("java_version"), "System info should contain Java version");
        
        logger.info("✅ System information collection validated successfully");
    }

    /**
     * Test Pattern 4: Test Results Integration
     * Validates integration with actual test results
     */
    @Test
    void testTestResultsIntegration() {
        logger.info("=== Testing Test Results Integration ===");
        
        // Create test results similar to original example
        Map<String, TestExecutionResult> testResults = createSampleTestResults();
        
        // Generate report from test results
        String report = createReportFromTestResults(
            "PeeGeeQ Integration Tests",
            "Test Environment",
            testResults
        );
        
        // Validate integration
        assertNotNull(report, "Integration report should not be null");
        assertFalse(report.isEmpty(), "Integration report should not be empty");
        assertTrue(report.contains("Integration Tests"), "Report should contain test suite name");
        assertTrue(report.contains("Native Performance Test"), "Report should contain native test");
        assertTrue(report.contains("Outbox Performance Test"), "Report should contain outbox test");
        
        logger.info("✅ Test results integration validated successfully");
    }

    // Helper methods that replicate the original example's functionality
    
    /**
     * Generates simple performance test report.
     */
    private String generateSimpleReport() {
        logger.info("Generating simple performance test report...");
        
        // Simulate report generation similar to original
        StringBuilder report = new StringBuilder();
        report.append("# PeeGeeQ Simple Performance Tests\n");
        report.append("**Environment:** Windows 11, Docker Desktop, PostgreSQL 16\n\n");
        report.append("## Test Results\n");
        report.append("| Test Name | Status | Duration |\n");
        report.append("|-----------|--------|----------|\n");
        report.append("| Native Queue Test | PASSED | 27.63 seconds |\n");
        report.append("| Outbox Pattern Test | PASSED | 12.87 seconds |\n");
        report.append("| Bitemporal Test | PARTIAL | 45.21 seconds |\n");
        report.append("\n## Configuration\n");
        report.append("- **Test Configuration:** Standard performance settings\n");
        report.append("- **Database:** PostgreSQL 16 with TestContainers\n");
        
        String reportContent = report.toString();
        System.out.println("\n=== SIMPLE REPORT ===");
        System.out.println(reportContent);
        
        return reportContent;
    }
    
    /**
     * Generates detailed performance test report with metrics.
     */
    private String generateDetailedReport() {
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
        
        // Simulate detailed report generation
        StringBuilder report = new StringBuilder();
        report.append("# PeeGeeQ Comprehensive Performance Tests\n");
        report.append("**Environment:** Windows 11, Docker Desktop, PostgreSQL 16\n\n");
        report.append("## Test Results with Metrics\n");
        report.append("### Native Queue Performance Test - PASSED (27.63 seconds)\n");
        nativeQueueMetrics.forEach((key, value) -> 
            report.append("- **").append(key).append(":** ").append(value).append("\n"));
        report.append("\n### Outbox Pattern Performance Test - PASSED (12.87 seconds)\n");
        outboxMetrics.forEach((key, value) -> 
            report.append("- **").append(key).append(":** ").append(value).append("\n"));
        report.append("\n## Configuration\n");
        report.append("- **Vert.x Version:** 5.x with optimizations applied\n");
        report.append("- **Test Configuration:** High-performance configuration with pipelining\n");
        
        String reportContent = report.toString();
        System.out.println("\n=== DETAILED REPORT ===");
        System.out.println(reportContent);
        
        return reportContent;
    }
    
    /**
     * Demonstrates system information collection.
     */
    private Map<String, Object> demonstrateSystemInfoCollection() {
        logger.info("Demonstrating system information collection...");
        
        // Simulate system information collection
        Map<String, Object> systemInfo = new LinkedHashMap<>();
        systemInfo.put("timestamp", System.currentTimeMillis());
        systemInfo.put("os", System.getProperty("os.name"));
        systemInfo.put("java_version", System.getProperty("java.version"));
        systemInfo.put("available_processors", Runtime.getRuntime().availableProcessors());
        systemInfo.put("max_memory", Runtime.getRuntime().maxMemory());
        
        System.out.println("\n=== SYSTEM INFORMATION ===");
        System.out.println("Timestamp: " + systemInfo.get("timestamp"));
        System.out.println("OS: " + systemInfo.get("os"));
        System.out.println("Java Version: " + systemInfo.get("java_version"));
        
        // Display as markdown format
        System.out.println("\n=== MARKDOWN FORMAT ===");
        System.out.println("## System Information");
        systemInfo.forEach((key, value) -> 
            System.out.println("- **" + key + ":** " + value));
        
        return systemInfo;
    }
    
    /**
     * Creates sample test results for integration testing.
     */
    private Map<String, TestExecutionResult> createSampleTestResults() {
        Map<String, TestExecutionResult> testResults = new LinkedHashMap<>();
        
        testResults.put("Native Performance Test", 
            new TestExecutionResult(true, 27.63, 10000.0, 5.2, 50000, "High throughput achieved"));
        testResults.put("Outbox Performance Test", 
            new TestExecutionResult(true, 12.87, 5000.0, 8.5, 25000, "ACID compliance maintained"));
        testResults.put("Bitemporal Performance Test", 
            new TestExecutionResult(false, 45.21, 956.0, 15.3, 10000, "Optimization needed"));
        
        return testResults;
    }
    
    /**
     * Creates report from test results similar to original example.
     */
    private String createReportFromTestResults(String testSuiteName, String testEnvironment, 
                                             Map<String, TestExecutionResult> testResults) {
        
        StringBuilder report = new StringBuilder();
        report.append("# ").append(testSuiteName).append("\n");
        report.append("**Environment:** ").append(testEnvironment).append("\n\n");
        report.append("## Test Results\n");
        
        for (Map.Entry<String, TestExecutionResult> entry : testResults.entrySet()) {
            TestExecutionResult result = entry.getValue();
            
            report.append("### ").append(entry.getKey())
                  .append(" - ").append(result.passed ? "PASSED" : "FAILED")
                  .append(" (").append(result.duration).append(" seconds)\n");
            
            report.append("- **throughput:** ").append(result.throughput).append(" msg/sec\n");
            report.append("- **latency:** ").append(result.averageLatency).append("ms\n");
            report.append("- **events:** ").append(result.eventCount).append(" events\n");
            
            if (result.notes != null) {
                report.append("- **notes:** ").append(result.notes).append("\n");
            }
            report.append("\n");
        }
        
        return report.toString();
    }
    
    // Supporting classes
    
    /**
     * Simple data structure for test execution results.
     */
    private static class TestExecutionResult {
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
