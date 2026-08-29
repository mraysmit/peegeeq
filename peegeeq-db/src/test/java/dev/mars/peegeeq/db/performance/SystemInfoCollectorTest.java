package dev.mars.peegeeq.db.performance;

import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for SystemInfoCollector to validate system information collection.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-11
 * @version 1.0
 */
@Tag(TestCategories.CORE)
class SystemInfoCollectorTest {
    @Test
    void testCollectSystemInfo() {
        SystemInfoCollector.SystemInfoSnapshot systemInfo = SystemInfoCollector.collectSystemInfo();
        
        // Verify basic structure
        assertNotNull(systemInfo, "System info should not be null");
        assertNotNull(systemInfo.timestamp(), "Should contain timestamp");
        assertNotNull(systemInfo.systemConfiguration(), "Should contain system configuration");
        assertNotNull(systemInfo.databaseConfiguration(), "Should contain database configuration");
        assertNotNull(systemInfo.peeGeeQConfiguration(), "Should contain PeeGeeQ configuration");
    }
    
    @Test
    void testSystemConfiguration() {
        SystemInfoCollector.SystemInfoSnapshot systemInfo = SystemInfoCollector.collectSystemInfo();
        Map<String, String> sysConfig = systemInfo.systemConfiguration();
        
        assertNotNull(sysConfig, "System configuration should not be null");
        
        // Verify essential system information is collected
        assertTrue(sysConfig.containsKey("OS"), "Should contain OS information");
        assertTrue(sysConfig.containsKey("CPU Cores"), "Should contain CPU core information");
        assertTrue(sysConfig.containsKey("Total Memory"), "Should contain memory information");
        assertTrue(sysConfig.containsKey("Java Version"), "Should contain Java version");
        assertTrue(sysConfig.containsKey("JVM"), "Should contain JVM information");
    }
    
    @Test
    void testDatabaseConfiguration() {
        SystemInfoCollector.SystemInfoSnapshot systemInfo = SystemInfoCollector.collectSystemInfo();
        Map<String, String> dbConfig = systemInfo.databaseConfiguration();
        
        assertNotNull(dbConfig, "Database configuration should not be null");
        
        // Verify database configuration is collected
        assertTrue(dbConfig.containsKey("Database"), "Should contain database information");
        assertTrue(dbConfig.containsKey("Connection Status"), "Should contain connection status");
        assertTrue(dbConfig.containsKey("Pool Configuration"), "Should contain pool configuration");
        assertTrue(dbConfig.containsKey("Pipelining"), "Should contain pipelining information");
    }
    
    @Test
    void testFormatAsMarkdown() {
        String markdown = SystemInfoCollector.formatAsMarkdown();
        
        assertNotNull(markdown, "Markdown should not be null");
        assertFalse(markdown.trim().isEmpty(), "Markdown should not be empty");
        
        // Verify markdown structure
        assertTrue(markdown.contains("## System Configuration"), "Should contain system configuration section");
        assertTrue(markdown.contains("## Database Configuration"), "Should contain database configuration section");
        assertTrue(markdown.contains("**OS:**"), "Should contain OS information");
        assertTrue(markdown.contains("**Database:**"), "Should contain database information");
    }
    
    @Test
    void testFormatAsSummary() {
        String summary = SystemInfoCollector.formatAsSummary();
        
        assertNotNull(summary, "Summary should not be null");
        assertFalse(summary.trim().isEmpty(), "Summary should not be empty");
        
        // Verify summary contains key information
        assertTrue(summary.contains("System:"), "Should contain system information");
        assertTrue(summary.contains("CPU:"), "Should contain CPU information");
        assertTrue(summary.contains("Memory:"), "Should contain memory information");
        assertTrue(summary.contains("Java:"), "Should contain Java information");
    }
    
    @Test
    void testPerformanceTestResultsGenerator() {
        PerformanceTestResultsGenerator generator = new PerformanceTestResultsGenerator.Builder(
            "Test Suite",
            "Test Environment"
        )
        .addTest("Sample Test", "PASSED", "10.5 seconds")
        .addInfo("Test Info", "Sample information")
        .build();
        
        String report = generator.generateReport();
        
        assertNotNull(report, "Report should not be null");
        assertFalse(report.trim().isEmpty(), "Report should not be empty");
        
        // Verify report structure
        assertTrue(report.contains("# Test Suite Performance Test Results"), "Should contain title");
        assertTrue(report.contains("##  Executive Summary"), "Should contain executive summary");
        assertTrue(report.contains("## System Configuration"), "Should contain system configuration");
        assertTrue(report.contains("##  Detailed Test Results"), "Should contain detailed results");
        assertTrue(report.contains("Sample Test"), "Should contain test name");
    }
    
    @Test
    void testSystemInfoWithCustomProperties() {
        // Build a configuration with custom properties using the 2-arg constructor
        Properties props = new Properties();
        props.setProperty("peegeeq.test.property", "test-value");
        props.setProperty("peegeeq.database.pool.max-size", "100");
        props.setProperty("peegeeq.database.pipelining.limit", "1024");
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test", props);

        SystemInfoCollector.SystemInfoSnapshot systemInfo = SystemInfoCollector.collectSystemInfo(config);

        Map<String, String> peeGeeQConfig = systemInfo.peeGeeQConfiguration();

        assertNotNull(peeGeeQConfig, "PeeGeeQ configuration should not be null");
        assertTrue(peeGeeQConfig.containsKey("peegeeq.test.property"), "Should contain test property");
        assertEquals("test-value", peeGeeQConfig.get("peegeeq.test.property"), "Should have correct test value");

    }

    @Test
    @ResourceLock("system-properties")
    void diagnosticsAreInstanceScopedAndNoConfigurationIsTruthful() {
        Properties firstProperties = new Properties();
        firstProperties.setProperty("peegeeq.database.pool.max-size", "7");
        firstProperties.setProperty("peegeeq.database.pool.wait-queue-multiplier", "3");
        firstProperties.setProperty("peegeeq.database.pipelining.limit", "11");
        firstProperties.setProperty("peegeeq.diagnostics.instance-marker", "first");

        Properties secondProperties = new Properties();
        secondProperties.setProperty("peegeeq.database.pool.max-size", "13");
        secondProperties.setProperty("peegeeq.database.pool.wait-queue-multiplier", "2");
        secondProperties.setProperty("peegeeq.database.pipelining.limit", "17");
        secondProperties.setProperty("peegeeq.diagnostics.instance-marker", "second");

        PeeGeeQConfiguration first = new PeeGeeQConfiguration("test", firstProperties);
        PeeGeeQConfiguration second = new PeeGeeQConfiguration("test", secondProperties);

        String[] ambientKeys = {
            "peegeeq.database.url",
            "peegeeq.database.pool.max-size",
            "peegeeq.database.pool.wait-queue-multiplier",
            "peegeeq.database.pipelining.limit",
            "peegeeq.diagnostics.instance-marker"
        };
        Properties previousValues = new Properties();
        for (String key : ambientKeys) {
            String previousValue = System.getProperty(key);
            if (previousValue != null) {
                previousValues.setProperty(key, previousValue);
            }
        }

        try {
            System.setProperty("peegeeq.database.url", "jdbc:postgresql://ambient-host:5439/ambient");
            System.setProperty("peegeeq.database.pool.max-size", "91");
            System.setProperty("peegeeq.database.pool.wait-queue-multiplier", "9");
            System.setProperty("peegeeq.database.pipelining.limit", "909");
            System.setProperty("peegeeq.diagnostics.instance-marker", "ambient");

            SystemInfoCollector.SystemInfoSnapshot firstSnapshot =
                SystemInfoCollector.collectSystemInfo(first);
            SystemInfoCollector.SystemInfoSnapshot secondSnapshot =
                SystemInfoCollector.collectSystemInfo(second);
            SystemInfoCollector.SystemInfoSnapshot unconfiguredSnapshot =
                SystemInfoCollector.collectSystemInfo();

            assertAll(
                () -> assertEquals("Optimized (7 connections, 21 wait queue)",
                    firstSnapshot.databaseConfiguration().get("Pool Configuration")),
                () -> assertEquals("Enabled (11 limit)",
                    firstSnapshot.databaseConfiguration().get("Pipelining")),
                () -> assertEquals("first",
                    firstSnapshot.peeGeeQConfiguration().get("peegeeq.diagnostics.instance-marker")),
                () -> assertEquals("Optimized (13 connections, 26 wait queue)",
                    secondSnapshot.databaseConfiguration().get("Pool Configuration")),
                () -> assertEquals("Enabled (17 limit)",
                    secondSnapshot.databaseConfiguration().get("Pipelining")),
                () -> assertEquals("second",
                    secondSnapshot.peeGeeQConfiguration().get("peegeeq.diagnostics.instance-marker")),
                () -> assertEquals("Configuration not supplied",
                    unconfiguredSnapshot.databaseConfiguration().get("Database")),
                () -> assertEquals("Configuration not supplied",
                    unconfiguredSnapshot.databaseConfiguration().get("Pool Configuration")),
                () -> assertEquals("Configuration not supplied",
                    unconfiguredSnapshot.databaseConfiguration().get("Pipelining")),
                () -> assertEquals("Not supplied",
                    unconfiguredSnapshot.peeGeeQConfiguration().get("Configuration")),
                () -> assertFalse(unconfiguredSnapshot.peeGeeQConfiguration()
                    .containsKey("peegeeq.diagnostics.instance-marker"))
            );
        } finally {
            for (String key : ambientKeys) {
                String previousValue = previousValues.getProperty(key);
                if (previousValue == null) {
                    System.clearProperty(key);
                } else {
                    System.setProperty(key, previousValue);
                }
            }
        }
    }
}
