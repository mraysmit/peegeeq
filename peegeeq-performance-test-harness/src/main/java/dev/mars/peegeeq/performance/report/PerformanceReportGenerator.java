package dev.mars.peegeeq.performance.report;

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

import dev.mars.peegeeq.performance.suite.PerformanceTestSuite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * Generates comprehensive performance test reports in multiple formats.
 * 
 * This class creates detailed reports including:
 * - Executive summary with key metrics
 * - Detailed test results by module
 * - Performance comparisons and trends
 * - Recommendations and analysis
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-13
 * @version 1.0
 */
public class PerformanceReportGenerator {
    
    private static final Logger logger = LoggerFactory.getLogger(PerformanceReportGenerator.class);
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
            .withZone(java.time.ZoneId.systemDefault());

    /**
     * Generate a comprehensive performance report.
     */
    public String generateReport(PerformanceTestSuite.Results results, Duration totalDuration) {
        try {
            String timestamp = TIMESTAMP_FORMATTER.format(Instant.now());
            String reportFileName = String.format("peegeeq-performance-report_%s.md", timestamp);
            Path reportPath = Paths.get("target", "performance-reports", reportFileName);
            
            // Ensure directory exists
            Files.createDirectories(reportPath.getParent());
            
            // Generate report content
            String reportContent = generateReportContent(results, totalDuration, timestamp);
            
            // Write report to file
            Files.writeString(reportPath, reportContent);
            
            logger.info("📋 Performance report generated: {}", reportPath.toAbsolutePath());
            return reportPath.toAbsolutePath().toString();
            
        } catch (IOException e) {
            logger.error("❌ Failed to generate performance report", e);
            return "Error generating report: " + e.getMessage();
        }
    }
    
    private String generateReportContent(PerformanceTestSuite.Results results, Duration totalDuration, String timestamp) {
        StringBuilder report = new StringBuilder();
        
        // Header
        report.append("# PeeGeeQ Performance Test Report\n\n");
        report.append("**Generated:** ").append(timestamp.replace("_", " ")).append("\n");
        report.append("**Total Duration:** ").append(formatDuration(totalDuration)).append("\n");
        report.append("**Test Status:** ").append(results.hasFailures() ? "❌ FAILED" : "✅ PASSED").append("\n\n");
        
        // Executive Summary
        report.append("## Executive Summary\n\n");
        report.append("| Metric | Value |\n");
        report.append("|--------|-------|\n");
        report.append("| Total Tests | ").append(results.getTotalTests()).append(" |\n");
        report.append("| Successful Tests | ").append(results.getSuccessfulTests()).append(" |\n");
        report.append("| Failed Tests | ").append(results.getFailedTests()).append(" |\n");
        report.append("| Success Rate | ").append(String.format("%.1f%%", 
            (double) results.getSuccessfulTests() / results.getTotalTests() * 100)).append(" |\n");
        report.append("| Total Duration | ").append(formatDuration(totalDuration)).append(" |\n\n");
        
        // Performance Highlights
        report.append("## Performance Highlights\n\n");
        
        if (results.getBitemporalResults() != null) {
            var bt = results.getBitemporalResults();
            report.append("### 🔄 Bi-temporal Event Store\n");
            report.append("- **Query Performance:** ").append(String.format("%.0f events/sec", bt.getQueryThroughput())).append("\n");
            report.append("- **Append Performance:** ").append(String.format("%.0f events/sec", bt.getAppendThroughput())).append("\n");
            report.append("- **Query Latency:** ").append(String.format("%.2f ms", bt.getAverageQueryLatency())).append("\n");
            report.append("- **Append Latency:** ").append(String.format("%.2f ms", bt.getAverageAppendLatency())).append("\n\n");
        }
        
        if (results.getOutboxResults() != null) {
            var ob = results.getOutboxResults();
            report.append("### 📤 Outbox Pattern\n");
            report.append("- **Send Throughput:** ").append(String.format("%.0f msg/sec", ob.getSendThroughput())).append("\n");
            report.append("- **Total Throughput:** ").append(String.format("%.0f msg/sec", ob.getTotalThroughput())).append("\n");
            report.append("- **Average Latency:** ").append(String.format("%.2f ms", ob.getAverageLatency())).append("\n");
            report.append("- **Total Messages:** ").append(ob.getTotalMessages()).append("\n\n");
        }
        
        if (results.getNativeResults() != null) {
            var nq = results.getNativeResults();
            report.append("### ⚡ Native Queue\n");
            report.append("- **Throughput:** ").append(String.format("%.0f msg/sec", nq.getThroughput())).append("\n");
            report.append("- **Average Latency:** ").append(String.format("%.2f ms", nq.getAverageLatency())).append("\n");
            report.append("- **Max Latency:** ").append(String.format("%.2f ms", nq.getMaxLatency())).append("\n");
            report.append("- **Min Latency:** ").append(String.format("%.2f ms", nq.getMinLatency())).append("\n\n");
        }
        
        if (results.getDatabaseResults() != null) {
            var db = results.getDatabaseResults();
            report.append("### 🗄️ Database Core\n");
            report.append("- **Query Throughput:** ").append(String.format("%.0f queries/sec", db.getQueryThroughput())).append("\n");
            report.append("- **Average Latency:** ").append(String.format("%.2f ms", db.getAverageLatency())).append("\n");
            report.append("- **Pool Utilization:** ").append(String.format("%.1f%%", db.getConnectionPoolUtilization())).append("\n");
            report.append("- **Total Queries:** ").append(db.getTotalQueries()).append("\n\n");
        }
        
        // Detailed Metrics
        report.append("## Detailed Metrics\n\n");
        if (!results.getMetrics().isEmpty()) {
            report.append("| Metric | Value |\n");
            report.append("|--------|-------|\n");
            results.getMetrics().forEach((key, value) -> {
                report.append("| ").append(formatMetricName(key)).append(" | ");
                if (value instanceof Number) {
                    report.append(String.format("%.2f", ((Number) value).doubleValue()));
                } else {
                    report.append(value.toString());
                }
                report.append(" |\n");
            });
            report.append("\n");
        }
        
        // Failures (if any)
        if (results.hasFailures()) {
            report.append("## ❌ Test Failures\n\n");
            for (String failure : results.getFailures()) {
                report.append("- ").append(failure).append("\n");
            }
            report.append("\n");
        }
        
        // Performance Analysis
        report.append("## Performance Analysis\n\n");
        report.append("### Key Findings\n\n");
        
        if (results.getBitemporalResults() != null) {
            var bt = results.getBitemporalResults();
            if (bt.getQueryThroughput() > 10000) {
                report.append("✅ **Excellent Query Performance:** Bi-temporal queries exceed 10,000 events/sec, meeting enterprise requirements.\n\n");
            }
            if (bt.getAppendThroughput() > 500) {
                report.append("✅ **Strong Append Performance:** Event append rate exceeds 500 events/sec, suitable for high-volume applications.\n\n");
            }
        }
        
        if (results.getOutboxResults() != null) {
            var ob = results.getOutboxResults();
            if (ob.getSendThroughput() > 3000) {
                report.append("✅ **High Message Throughput:** Outbox pattern achieves >3,000 msg/sec, suitable for high-volume transactional messaging.\n\n");
            }
            if (ob.getAverageLatency() < 100) {
                report.append("✅ **Low Latency Messaging:** Average latency under 100ms provides responsive user experience.\n\n");
            }
        }
        
        // Recommendations
        report.append("### Recommendations\n\n");
        report.append("1. **Production Deployment:** Performance metrics indicate system readiness for production deployment.\n");
        report.append("2. **Scaling Strategy:** Consider horizontal scaling for workloads exceeding current throughput limits.\n");
        report.append("3. **Monitoring:** Implement continuous performance monitoring to track degradation over time.\n");
        report.append("4. **Optimization:** Focus optimization efforts on the lowest-performing components identified in this report.\n\n");
        
        // System Information
        report.append("## System Information\n\n");
        report.append("- **OS:** ").append(System.getProperty("os.name")).append(" ").append(System.getProperty("os.version")).append("\n");
        report.append("- **Java:** ").append(System.getProperty("java.version")).append(" (").append(System.getProperty("java.vendor")).append(")\n");
        report.append("- **CPU Cores:** ").append(Runtime.getRuntime().availableProcessors()).append("\n");
        report.append("- **Max Memory:** ").append(Runtime.getRuntime().maxMemory() / 1024 / 1024).append(" MB\n");
        report.append("- **Test Timestamp:** ").append(timestamp.replace("_", " ")).append("\n\n");
        
        // Footer
        report.append("---\n");
        report.append("*Report generated by PeeGeeQ Performance Test Harness*\n");
        
        return report.toString();
    }
    
    private String formatDuration(Duration duration) {
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
    
    private String formatMetricName(String key) {
        String[] words = key.replace("_", " ").split(" ");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (!word.isEmpty()) {
                result.append(Character.toUpperCase(word.charAt(0)))
                      .append(word.substring(1).toLowerCase())
                      .append(" ");
            }
        }
        return result.toString().trim();
    }
}
