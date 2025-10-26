package dev.mars.peegeeq.test.base;

import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory.PerformanceProfile;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for PeeGeeQTestBase functionality.
 * 
 * This test validates that the base test class correctly:
 * - Sets up TestContainers with different performance profiles
 * - Integrates with the metrics system
 * - Provides consistent database connectivity
 * - Handles cleanup properly
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-18
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
class PeeGeeQTestBaseTest extends PeeGeeQTestBase {
    
    private static final Logger logger = LoggerFactory.getLogger(PeeGeeQTestBaseTest.class);
    
    @Override
    protected PerformanceProfile getPerformanceProfile() {
        return PerformanceProfile.STANDARD;
    }
    
    @Test
    void testBasicSetup() {
        System.err.println("=== TEST METHOD STARTED: testBasicSetup ===");
        System.err.flush();
        
        logger.info("Testing basic PeeGeeQTestBase setup");
        
        // Verify container is running
        assertNotNull(getContainer(), "Container should not be null");
        assertTrue(getContainer().isRunning(), "Container should be running");
        
        // Verify database connectivity
        assertNotNull(getJdbcUrl(), "JDBC URL should not be null");
        assertNotNull(getHost(), "Host should not be null");
        assertNotNull(getPort(), "Port should not be null");
        
        // Verify metrics are set up
        assertNotNull(getMetrics(), "Metrics should not be null");
        assertNotNull(getMeterRegistry(), "MeterRegistry should not be null");
        
        // Verify test instance ID
        assertNotNull(getTestInstanceId(), "Test instance ID should not be null");
        assertTrue(getTestInstanceId().startsWith("test-"), "Test instance ID should start with 'test-'");
        
        // Verify performance profile
        assertEquals(PerformanceProfile.STANDARD, getCurrentProfile(), "Profile should be STANDARD");
        
        logger.info("✅ Basic setup test passed");
        
        System.err.println("=== TEST METHOD COMPLETED: testBasicSetup ===");
        System.err.flush();
    }
    
    @Test
    void testDatabaseConnectivity() throws Exception {
        System.err.println("=== TEST METHOD STARTED: testDatabaseConnectivity ===");
        System.err.flush();
        
        logger.info("Testing database connectivity");
        
        String jdbcUrl = getJdbcUrl();
        String username = getContainer().getUsername();
        String password = getContainer().getPassword();
        
        // Test direct JDBC connection
        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password)) {
            assertNotNull(conn, "Connection should not be null");
            assertFalse(conn.isClosed(), "Connection should be open");
            
            // Test basic query
            try (PreparedStatement stmt = conn.prepareStatement("SELECT 1 as test_value");
                 ResultSet rs = stmt.executeQuery()) {
                
                assertTrue(rs.next(), "Result set should have at least one row");
                assertEquals(1, rs.getInt("test_value"), "Test value should be 1");
            }
        }
        
        logger.info("✅ Database connectivity test passed");
        
        System.err.println("=== TEST METHOD COMPLETED: testDatabaseConnectivity ===");
        System.err.flush();
    }
    
    @Test
    void testMetricsRecording() {
        System.err.println("=== TEST METHOD STARTED: testMetricsRecording ===");
        System.err.flush();
        
        logger.info("Testing metrics recording");
        
        // Record some test metrics
        recordTestMetric("test.metric.gauge", 42.0, Map.of("test_tag", "test_value"));
        recordTestTimer("test.metric.timer", 100L, Map.of("operation", "test_operation"));
        
        // Verify metrics were recorded (basic validation)
        assertNotNull(getMeterRegistry(), "MeterRegistry should be available");
        
        logger.info("✅ Metrics recording test passed");
        
        System.err.println("=== TEST METHOD COMPLETED: testMetricsRecording ===");
        System.err.flush();
    }
    
    @Test
    void testSystemProperties() {
        System.err.println("=== TEST METHOD STARTED: testSystemProperties ===");
        System.err.flush();
        
        logger.info("Testing system properties setup");
        
        // Verify system properties are set
        assertNotNull(System.getProperty("test.database.host"), "Database host property should be set");
        assertNotNull(System.getProperty("test.database.port"), "Database port property should be set");
        assertNotNull(System.getProperty("test.database.name"), "Database name property should be set");
        assertNotNull(System.getProperty("test.database.username"), "Database username property should be set");
        assertNotNull(System.getProperty("test.database.password"), "Database password property should be set");
        assertNotNull(System.getProperty("test.database.url"), "Database URL property should be set");
        
        // Verify properties match container values
        assertEquals(getHost(), System.getProperty("test.database.host"), "Host property should match container");
        assertEquals(getPort().toString(), System.getProperty("test.database.port"), "Port property should match container");
        assertEquals(getJdbcUrl(), System.getProperty("test.database.url"), "URL property should match container");
        
        logger.info("✅ System properties test passed");
        
        System.err.println("=== TEST METHOD COMPLETED: testSystemProperties ===");
        System.err.flush();
    }
}

/**
 * Test class for ParameterizedPerformanceTestBase functionality.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
class ParameterizedPerformanceTestBaseTest extends ParameterizedPerformanceTestBase {
    
    private static final Logger logger = LoggerFactory.getLogger(ParameterizedPerformanceTestBaseTest.class);
    
    @ParameterizedTest
    @EnumSource(value = PerformanceProfile.class, names = {"BASIC", "STANDARD", "HIGH_PERFORMANCE"})
    void testPerformanceAcrossProfiles(PerformanceProfile profile) {
        System.err.println("=== TEST METHOD STARTED: testPerformanceAcrossProfiles(" + profile + ") ===");
        System.err.flush();
        
        logger.info("Testing performance with profile: {}", profile.getDisplayName());
        
        PerformanceTestResult result = runTestWithProfile(profile, () -> {
            // Simulate some work
            Thread.sleep(50);
            
            // Return some mock performance metrics
            return createPerformanceMetrics(
                1000.0,  // throughput: 1000 ops/sec
                50.0,    // average latency: 50ms
                75.0,    // p95 latency: 75ms
                0.0      // error rate: 0%
            );
        });
        
        // Validate result
        assertNotNull(result, "Result should not be null");
        assertEquals(profile, result.getProfile(), "Profile should match");
        assertTrue(result.isSuccess(), "Test should be successful");
        assertNotNull(result.getThroughput(), "Throughput should be recorded");
        assertEquals(1000.0, result.getThroughput(), 0.1, "Throughput should match expected value");
        
        // Validate performance thresholds
        validatePerformanceThresholds(result, profile);
        
        logger.info("✅ Performance test passed for profile: {} (duration: {}ms, throughput: {} ops/sec)", 
                   profile.getDisplayName(), result.getDurationMs(), result.getThroughput());
        
        System.err.println("=== TEST METHOD COMPLETED: testPerformanceAcrossProfiles(" + profile + ") ===");
        System.err.flush();
    }
    
    @Test
    void testPerformanceMetricsCreation() {
        System.err.println("=== TEST METHOD STARTED: testPerformanceMetricsCreation ===");
        System.err.flush();
        
        logger.info("Testing performance metrics creation");
        
        Map<String, Object> metrics = createPerformanceMetrics(500.0, 25.0, 40.0, 1.5);
        
        assertNotNull(metrics, "Metrics should not be null");
        assertEquals(500.0, metrics.get("throughput"), "Throughput should match");
        assertEquals(25.0, metrics.get("average_latency"), "Average latency should match");
        assertEquals(40.0, metrics.get("p95_latency"), "P95 latency should match");
        assertEquals(1.5, metrics.get("error_rate"), "Error rate should match");
        
        logger.info("✅ Performance metrics creation test passed");
        
        System.err.println("=== TEST METHOD COMPLETED: testPerformanceMetricsCreation ===");
        System.err.flush();
    }
}
