package dev.mars.peegeeq.db.performance;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for SystemInfoCollector to validate system information collection.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-11
 * @version 1.0
 */
@org.junit.jupiter.api.parallel.ResourceLock("system-properties")
class SystemInfoCollectorTest {
    private static final Logger logger = LoggerFactory.getLogger(SystemInfoCollectorTest.class);
    
    @Test
    void testCollectSystemInfo() {
        logger.info("Testing system information collection...");
        
        Map<String, Object> systemInfo = SystemInfoCollector.collectSystemInfo();
        
        // Verify basic structure
        assertNotNull(systemInfo, "System info should not be null");
        assertTrue(systemInfo.containsKey("timestamp"), "Should contain timestamp");
        assertTrue(systemInfo.containsKey("systemConfiguration"), "Should contain system configuration");
        assertTrue(systemInfo.containsKey("databaseConfiguration"), "Should contain database configuration");
        assertTrue(systemInfo.containsKey("peeGeeQConfiguration"), "Should contain PeeGeeQ configuration");
        
        logger.info("✅ System info structure validation passed");
    }
    
    @Test
    @SuppressWarnings("unchecked")
    void testSystemConfiguration() {
        logger.info("Testing system configuration collection...");
        
        Map<String, Object> systemInfo = SystemInfoCollector.collectSystemInfo();
        Map<String, String> sysConfig = (Map<String, String>) systemInfo.get("systemConfiguration");
        
        assertNotNull(sysConfig, "System configuration should not be null");
        
        // Verify essential system information is collected
        assertTrue(sysConfig.containsKey("OS"), "Should contain OS information");
        assertTrue(sysConfig.containsKey("CPU Cores"), "Should contain CPU core information");
        assertTrue(sysConfig.containsKey("Total Memory"), "Should contain memory information");
        assertTrue(sysConfig.containsKey("Java Version"), "Should contain Java version");
        assertTrue(sysConfig.containsKey("JVM"), "Should contain JVM information");
        
        // Log the collected information
        logger.info("OS: {}", sysConfig.get("OS"));
        logger.info("CPU Cores: {}", sysConfig.get("CPU Cores"));
        logger.info("Total Memory: {}", sysConfig.get("Total Memory"));
        logger.info("Java Version: {}", sysConfig.get("Java Version"));
        logger.info("JVM: {}", sysConfig.get("JVM"));
        
        logger.info("✅ System configuration validation passed");
    }
    
    @Test
    @SuppressWarnings("unchecked")
    void testDatabaseConfiguration() {
        logger.info("Testing database configuration collection...");
        
        Map<String, Object> systemInfo = SystemInfoCollector.collectSystemInfo();
        Map<String, String> dbConfig = (Map<String, String>) systemInfo.get("databaseConfiguration");
        
        assertNotNull(dbConfig, "Database configuration should not be null");
        
        // Verify database configuration is collected
        assertTrue(dbConfig.containsKey("Database"), "Should contain database information");
        assertTrue(dbConfig.containsKey("Connection Status"), "Should contain connection status");
        assertTrue(dbConfig.containsKey("Pool Configuration"), "Should contain pool configuration");
        assertTrue(dbConfig.containsKey("Pipelining"), "Should contain pipelining information");
        
        // Log the collected information
        logger.info("Database: {}", dbConfig.get("Database"));
        logger.info("Connection Status: {}", dbConfig.get("Connection Status"));
        logger.info("Pool Configuration: {}", dbConfig.get("Pool Configuration"));
        logger.info("Pipelining: {}", dbConfig.get("Pipelining"));
        
        logger.info("✅ Database configuration validation passed");
    }
    
    @Test
    void testFormatAsMarkdown() {
        logger.info("Testing markdown formatting...");
        
        String markdown = SystemInfoCollector.formatAsMarkdown();
        
        assertNotNull(markdown, "Markdown should not be null");
        assertFalse(markdown.trim().isEmpty(), "Markdown should not be empty");
        
        // Verify markdown structure
        assertTrue(markdown.contains("## System Configuration"), "Should contain system configuration section");
        assertTrue(markdown.contains("## Database Configuration"), "Should contain database configuration section");
        assertTrue(markdown.contains("**OS:**"), "Should contain OS information");
        assertTrue(markdown.contains("**Database:**"), "Should contain database information");
        
        logger.info("Generated markdown:\n{}", markdown);
        logger.info("✅ Markdown formatting validation passed");
    }
    
    @Test
    void testFormatAsSummary() {
        logger.info("Testing summary formatting...");
        
        String summary = SystemInfoCollector.formatAsSummary();
        
        assertNotNull(summary, "Summary should not be null");
        assertFalse(summary.trim().isEmpty(), "Summary should not be empty");
        
        // Verify summary contains key information
        assertTrue(summary.contains("System:"), "Should contain system information");
        assertTrue(summary.contains("CPU:"), "Should contain CPU information");
        assertTrue(summary.contains("Memory:"), "Should contain memory information");
        assertTrue(summary.contains("Java:"), "Should contain Java information");
        
        logger.info("Generated summary: {}", summary);
        logger.info("✅ Summary formatting validation passed");
    }
    
    @Test
    void testPerformanceTestResultsGenerator() {
        logger.info("Testing PerformanceTestResultsGenerator...");
        
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
        assertTrue(report.contains("## 📊 Executive Summary"), "Should contain executive summary");
        assertTrue(report.contains("## System Configuration"), "Should contain system configuration");
        assertTrue(report.contains("## 🎯 Detailed Test Results"), "Should contain detailed results");
        assertTrue(report.contains("Sample Test"), "Should contain test name");
        
        logger.info("Generated report preview (first 500 chars):\n{}", 
            report.length() > 500 ? report.substring(0, 500) + "..." : report);
        logger.info("✅ PerformanceTestResultsGenerator validation passed");
    }
    
    @Test
    void testSystemInfoWithCustomProperties() {
        logger.info("Testing system info with custom PeeGeeQ properties...");
        
        // Set some test properties
        System.setProperty("peegeeq.test.property", "test-value");
        System.setProperty("peegeeq.database.pool.max-size", "100");
        System.setProperty("peegeeq.database.pipelining.limit", "1024");
        
        try {
            Map<String, Object> systemInfo = SystemInfoCollector.collectSystemInfo();
            
            @SuppressWarnings("unchecked")
            Map<String, String> peeGeeQConfig = (Map<String, String>) systemInfo.get("peeGeeQConfiguration");
            
            assertNotNull(peeGeeQConfig, "PeeGeeQ configuration should not be null");
            assertTrue(peeGeeQConfig.containsKey("peegeeq.test.property"), "Should contain test property");
            assertEquals("test-value", peeGeeQConfig.get("peegeeq.test.property"), "Should have correct test value");
            
            logger.info("PeeGeeQ properties found: {}", peeGeeQConfig.size());
            for (Map.Entry<String, String> entry : peeGeeQConfig.entrySet()) {
                logger.info("  {}: {}", entry.getKey(), entry.getValue());
            }
            
            logger.info("✅ Custom properties validation passed");

        } finally {
            // Clean up test properties
            System.clearProperty("peegeeq.test.property");
            System.clearProperty("peegeeq.database.pool.max-size");
            System.clearProperty("peegeeq.database.pipelining.limit");

            logger.info("Cleaned up test properties");
        }
    }
}
