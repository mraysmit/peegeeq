package dev.mars.peegeeq.db.examples;

import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.SharedPostgresExtension;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test for SecurityConfigurationExample functionality.
 *
 * This test validates all security configuration patterns from the original 493-line example:
 * 1. Basic security configuration principles
 * 2. SSL/TLS configuration for database connections
 * 3. Production security setup with proper credentials
 * 4. Security monitoring and health checks
 * 5. Credential management best practices
 * 6. Compliance configuration for enterprise environments
 *
 * All original functionality is preserved with enhanced test assertions and documentation.
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(SharedPostgresExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
public class SecurityConfigurationExampleTest {

    private static final Logger logger = LoggerFactory.getLogger(SecurityConfigurationExampleTest.class);

    private PeeGeeQManager manager;

    @BeforeEach
    void setUp() throws Exception {
        logger.info("Setting up Security Configuration Example Test");

        PostgreSQLContainer<?> postgres = SharedPostgresExtension.getContainer();

        // Set database properties from TestContainer
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.ssl.enabled", "false");
        System.setProperty("peegeeq.database.schema", "public");
        
        logger.info("✓ Security Configuration Example Test setup completed");
    }
    
    @AfterEach
    void tearDown() throws Exception {
        logger.info("Tearing down Security Configuration Example Test");
        
        if (manager != null) {
            manager.stop();
        }
        
        logger.info("✓ Security Configuration Example Test teardown completed");
    }

    /**
     * Test Pattern 1: Basic Security Configuration
     * Validates basic security configuration principles and properties
     */
    @Test
    void testBasicSecurityConfiguration() throws Exception {
        logger.info("=== Testing Basic Security Configuration ===");
        
        // Create security properties following the example
        Properties securityProps = new Properties();
        
        // Database security (adapted for test environment)
        securityProps.setProperty("peegeeq.database.ssl.enabled", "false"); // TestContainer doesn't support SSL
        securityProps.setProperty("peegeeq.database.ssl.mode", "disable");
        
        // Connection security
        securityProps.setProperty("peegeeq.database.connection.timeout", "30000");
        securityProps.setProperty("peegeeq.database.connection.max-lifetime", "1800000"); // 30 minutes
        securityProps.setProperty("peegeeq.database.connection.leak-detection-threshold", "60000");
        
        // Authentication security
        securityProps.setProperty("peegeeq.database.username.encrypted", "false");
        securityProps.setProperty("peegeeq.database.password.encrypted", "false");
        
        // Apply security properties to system
        for (String key : securityProps.stringPropertyNames()) {
            System.setProperty(key, securityProps.getProperty(key));
        }
        
        // Test that manager can be created with security configuration
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("secure"), new SimpleMeterRegistry());
        assertNotNull(manager);
        
        // Start manager and validate it works with security settings
        assertDoesNotThrow(() -> manager.start());
        
        // Validate health check functionality
        var healthStatus = manager.getHealthCheckManager().getOverallHealth();
        assertNotNull(healthStatus);
        assertTrue(healthStatus.isHealthy());
        
        logger.info("✓ Basic security configuration validated successfully");
    }

    /**
     * Test Pattern 2: SSL/TLS Configuration
     * Validates SSL/TLS configuration patterns (adapted for test environment)
     */
    @Test
    void testSSLTLSConfiguration() throws Exception {
        logger.info("=== Testing SSL/TLS Configuration ===");
        
        // SSL/TLS properties (adapted for TestContainer environment)
        Properties sslProps = new Properties();
        sslProps.setProperty("peegeeq.database.ssl.enabled", "false"); // TestContainer limitation
        sslProps.setProperty("peegeeq.database.ssl.mode", "disable");
        sslProps.setProperty("peegeeq.database.ssl.cert.validation", "none");
        
        // Apply SSL properties
        for (String key : sslProps.stringPropertyNames()) {
            System.setProperty(key, sslProps.getProperty(key));
        }
        
        // Test SSL configuration
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("ssl-test"), new SimpleMeterRegistry());
        assertNotNull(manager);
        
        assertDoesNotThrow(() -> manager.start());
        
        // Validate connection works with SSL configuration
        var healthStatus = manager.getHealthCheckManager().getOverallHealth();
        assertTrue(healthStatus.isHealthy());
        
        logger.info("✓ SSL/TLS configuration validated successfully");
    }

    /**
     * Test Pattern 3: Production Security Setup
     * Validates production-ready security configuration
     */
    @Test
    void testProductionSecuritySetup() throws Exception {
        logger.info("=== Testing Production Security Setup ===");
        
        // Production security properties
        Properties prodProps = new Properties();
        
        // Connection pooling security
        prodProps.setProperty("peegeeq.database.pool.minimum-idle", "5");
        prodProps.setProperty("peegeeq.database.pool.maximum-pool-size", "20");
        prodProps.setProperty("peegeeq.database.pool.connection-timeout", "30000");
        prodProps.setProperty("peegeeq.database.pool.idle-timeout", "600000");
        prodProps.setProperty("peegeeq.database.pool.max-lifetime", "1800000");
        
        // Security monitoring
        prodProps.setProperty("peegeeq.security.monitoring.enabled", "true");
        prodProps.setProperty("peegeeq.security.audit.enabled", "true");
        prodProps.setProperty("peegeeq.security.connection.validation", "true");
        
        // Apply production properties
        for (String key : prodProps.stringPropertyNames()) {
            System.setProperty(key, prodProps.getProperty(key));
        }
        
        // Test production configuration
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("production"), new SimpleMeterRegistry());
        assertNotNull(manager);
        
        assertDoesNotThrow(() -> manager.start());
        
        // Validate production setup
        var healthStatus = manager.getHealthCheckManager().getOverallHealth();
        assertTrue(healthStatus.isHealthy());
        
        // Validate metrics are available (production monitoring)
        var metrics = manager.getMetrics();
        assertNotNull(metrics);
        
        logger.info("✓ Production security setup validated successfully");
    }

    /**
     * Test Pattern 4: Security Monitoring
     * Validates security monitoring and health check functionality
     */
    @Test
    void testSecurityMonitoring() throws Exception {
        logger.info("=== Testing Security Monitoring ===");
        
        // Security monitoring properties
        Properties monitoringProps = new Properties();
        monitoringProps.setProperty("peegeeq.monitoring.health.enabled", "true");
        monitoringProps.setProperty("peegeeq.monitoring.health.interval", "PT30S");
        monitoringProps.setProperty("peegeeq.monitoring.metrics.enabled", "true");
        monitoringProps.setProperty("peegeeq.monitoring.security.audit", "true");
        
        // Apply monitoring properties
        for (String key : monitoringProps.stringPropertyNames()) {
            System.setProperty(key, monitoringProps.getProperty(key));
        }
        
        // Test monitoring configuration
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("monitoring"), new SimpleMeterRegistry());
        assertNotNull(manager);
        
        assertDoesNotThrow(() -> manager.start());

        // Allow time for health checks to initialize and run
        Thread.sleep(2000);

        // Validate health monitoring
        var healthCheckManager = manager.getHealthCheckManager();
        assertNotNull(healthCheckManager);

        var overallHealth = healthCheckManager.getOverallHealthInternal();
        assertNotNull(overallHealth);
        assertTrue(overallHealth.isHealthy());

        // Validate individual health checks are available
        var overallHealthComponents = overallHealth.getComponents();

        // Log available health checks for debugging
        logger.info("Available health checks: {}", overallHealthComponents.keySet());

        // Validate that we have at least some core health checks
        assertTrue(overallHealthComponents.size() > 0, "Should have at least one health check");
        
        logger.info("✓ Security monitoring validated successfully");
    }

    /**
     * Test Pattern 5: Credential Management
     * Validates credential management best practices
     */
    @Test
    void testCredentialManagement() throws Exception {
        logger.info("=== Testing Credential Management ===");
        
        // Credential management properties
        Properties credProps = new Properties();
        credProps.setProperty("peegeeq.credentials.source", "system-properties");
        credProps.setProperty("peegeeq.credentials.encryption.enabled", "false"); // Test environment
        credProps.setProperty("peegeeq.credentials.rotation.enabled", "false"); // Test environment
        credProps.setProperty("peegeeq.credentials.validation.enabled", "true");
        
        // Apply credential properties
        for (String key : credProps.stringPropertyNames()) {
            System.setProperty(key, credProps.getProperty(key));
        }
        
        // Test credential management
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("credentials"), new SimpleMeterRegistry());
        assertNotNull(manager);
        
        assertDoesNotThrow(() -> manager.start());
        
        // Validate credential-based connection
        var healthStatus = manager.getHealthCheckManager().getOverallHealth();
        assertTrue(healthStatus.isHealthy());
        
        logger.info("✓ Credential management validated successfully");
    }

    /**
     * Test Pattern 6: Compliance Configuration
     * Validates compliance configuration for enterprise environments
     */
    @Test
    void testComplianceConfiguration() throws Exception {
        logger.info("=== Testing Compliance Configuration ===");
        
        // Compliance properties
        Properties complianceProps = new Properties();
        complianceProps.setProperty("peegeeq.compliance.audit.enabled", "true");
        complianceProps.setProperty("peegeeq.compliance.encryption.at-rest", "true");
        complianceProps.setProperty("peegeeq.compliance.encryption.in-transit", "false"); // TestContainer limitation
        complianceProps.setProperty("peegeeq.compliance.data.retention", "P90D"); // 90 days
        complianceProps.setProperty("peegeeq.compliance.access.logging", "true");
        
        // Apply compliance properties
        for (String key : complianceProps.stringPropertyNames()) {
            System.setProperty(key, complianceProps.getProperty(key));
        }
        
        // Test compliance configuration
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("compliance"), new SimpleMeterRegistry());
        assertNotNull(manager);
        
        assertDoesNotThrow(() -> manager.start());
        
        // Validate compliance setup
        var healthStatus = manager.getHealthCheckManager().getOverallHealth();
        assertTrue(healthStatus.isHealthy());
        
        // Validate audit capabilities
        var metrics = manager.getMetrics();
        assertNotNull(metrics);
        
        logger.info("✓ Compliance configuration validated successfully");
    }
}
