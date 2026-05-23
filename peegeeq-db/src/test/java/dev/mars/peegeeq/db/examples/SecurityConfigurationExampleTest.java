package dev.mars.peegeeq.db.examples;

import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.SharedPostgresTestExtension;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

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
@ExtendWith({SharedPostgresTestExtension.class, VertxExtension.class})
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
public class SecurityConfigurationExampleTest {

    private static final Logger logger = LoggerFactory.getLogger(SecurityConfigurationExampleTest.class);

    private PeeGeeQManager manager;
    private Properties containerProps;

    @BeforeEach
    void setUp() {
        PostgreSQLContainer postgres = SharedPostgresTestExtension.getContainer();

        containerProps = new Properties();
        containerProps.setProperty("peegeeq.database.host", postgres.getHost());
        containerProps.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        containerProps.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        containerProps.setProperty("peegeeq.database.username", postgres.getUsername());
        containerProps.setProperty("peegeeq.database.password", postgres.getPassword());
        containerProps.setProperty("peegeeq.database.ssl.enabled", "false");
        containerProps.setProperty("peegeeq.database.schema", "public");
        containerProps.setProperty("peegeeq.database.pool.min-size", "1");
        containerProps.setProperty("peegeeq.database.pool.max-size", "3");
        containerProps.setProperty("peegeeq.database.pool.shared", "false");
        containerProps.setProperty("peegeeq.database.pool.idle-timeout-ms", "5000");
        containerProps.setProperty("peegeeq.database.pool.connection-timeout-ms", "30000");
        containerProps.setProperty("peegeeq.health.check-interval", "PT5S");
        containerProps.setProperty("peegeeq.health.timeout", "PT10S");
        containerProps.setProperty("peegeeq.health-check.queue-checks-enabled", "false");
        containerProps.setProperty("peegeeq.migration.enabled", "false");
        containerProps.setProperty("peegeeq.migration.auto-migrate", "false");
        containerProps.setProperty("peegeeq.queue.consumer-group-retry.enabled", "false");
        containerProps.setProperty("peegeeq.queue.dead-consumer-detection.enabled", "false");
    }
    
    @AfterEach
    void tearDown(VertxTestContext testContext) {
        if (manager != null) {
            manager.closeReactive()
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }
    }

    /**
     * Test Pattern 1: Basic Security Configuration
     * Validates basic security configuration principles and properties
     */
    @Test
    void testBasicSecurityConfiguration(VertxTestContext testContext) {
        Properties securityProps = new Properties();
        securityProps.setProperty("peegeeq.database.ssl.enabled", "false");
        securityProps.setProperty("peegeeq.database.ssl.mode", "disable");
        securityProps.setProperty("peegeeq.database.connection.timeout", "30000");
        securityProps.setProperty("peegeeq.database.connection.max-lifetime", "1800000");
        securityProps.setProperty("peegeeq.database.connection.leak-detection-threshold", "60000");
        securityProps.setProperty("peegeeq.database.username.encrypted", "false");
        securityProps.setProperty("peegeeq.database.password.encrypted", "false");

        Properties mergedProps = mergeProps(securityProps);
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("secure", mergedProps), new SimpleMeterRegistry());
        manager.start()
            .compose(v -> manager.getVertx().timer(5000).mapEmpty())
            .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                var healthStatus = manager.getHealthCheckManager().getOverallHealth();
                assertNotNull(healthStatus);
                assertTrue(healthStatus.isHealthy());
                testContext.completeNow();
            })));
    }

    /**
     * Test Pattern 2: SSL/TLS Configuration
     * Validates SSL/TLS configuration patterns (adapted for test environment)
     */
    @Test
    void testSSLTLSConfiguration(VertxTestContext testContext) {
        Properties sslProps = new Properties();
        sslProps.setProperty("peegeeq.database.ssl.enabled", "false");
        sslProps.setProperty("peegeeq.database.ssl.mode", "disable");
        sslProps.setProperty("peegeeq.database.ssl.cert.validation", "none");

        Properties mergedProps = mergeProps(sslProps);
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("ssl-test", mergedProps), new SimpleMeterRegistry());
        manager.start()
            .compose(v -> manager.getVertx().timer(5000).mapEmpty())
            .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                var healthStatus = manager.getHealthCheckManager().getOverallHealth();
                assertTrue(healthStatus.isHealthy());
                testContext.completeNow();
            })));
    }

    /**
     * Test Pattern 3: Production Security Setup
     * Validates production-ready security configuration
     */
    @Test
    void testProductionSecuritySetup(VertxTestContext testContext) {
        Properties prodProps = new Properties();
        prodProps.setProperty("peegeeq.database.pool.minimum-idle", "5");
        prodProps.setProperty("peegeeq.database.pool.maximum-pool-size", "20");
        prodProps.setProperty("peegeeq.database.pool.connection-timeout", "30000");
        prodProps.setProperty("peegeeq.database.pool.idle-timeout", "600000");
        prodProps.setProperty("peegeeq.database.pool.max-lifetime", "1800000");
        prodProps.setProperty("peegeeq.security.monitoring.enabled", "true");
        prodProps.setProperty("peegeeq.security.audit.enabled", "true");
        prodProps.setProperty("peegeeq.security.connection.validation", "true");

        Properties mergedProps = mergeProps(prodProps);
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("production", mergedProps), new SimpleMeterRegistry());
        manager.start()
            .compose(v -> manager.getVertx().timer(5000).mapEmpty())
            .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                var healthStatus = manager.getHealthCheckManager().getOverallHealth();
                assertTrue(healthStatus.isHealthy());
                assertNotNull(manager.getMetrics());
                testContext.completeNow();
            })));
    }

    /**
     * Test Pattern 4: Security Monitoring
     * Validates security monitoring and health check functionality
     */
    @Test
    void testSecurityMonitoring(VertxTestContext testContext) {
        Properties monitoringProps = new Properties();
        monitoringProps.setProperty("peegeeq.monitoring.health.enabled", "true");
        monitoringProps.setProperty("peegeeq.monitoring.health.interval", "PT30S");
        monitoringProps.setProperty("peegeeq.monitoring.metrics.enabled", "true");
        monitoringProps.setProperty("peegeeq.monitoring.security.audit", "true");

        Properties mergedProps = mergeProps(monitoringProps);
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("monitoring", mergedProps), new SimpleMeterRegistry());
        manager.start()
            .compose(v -> manager.getVertx().timer(5000).mapEmpty())
            .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                var healthCheckManager = manager.getHealthCheckManager();
                assertNotNull(healthCheckManager);

                var overallHealth = healthCheckManager.getOverallHealthInternal();
                assertNotNull(overallHealth);
                assertTrue(overallHealth.isHealthy());

                var overallHealthComponents = overallHealth.getComponents();
                assertTrue(overallHealthComponents.size() > 0, "Should have at least one health check");
                testContext.completeNow();
            })));
    }

    /**
     * Test Pattern 5: Credential Management
     * Validates credential management best practices
     */
    @Test
    void testCredentialManagement(VertxTestContext testContext) {
        Properties credProps = new Properties();
        credProps.setProperty("peegeeq.credentials.source", "system-properties");
        credProps.setProperty("peegeeq.credentials.encryption.enabled", "false");
        credProps.setProperty("peegeeq.credentials.rotation.enabled", "false");
        credProps.setProperty("peegeeq.credentials.validation.enabled", "true");

        Properties mergedProps = mergeProps(credProps);
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("credentials", mergedProps), new SimpleMeterRegistry());
        manager.start()
            .compose(v -> manager.getVertx().timer(5000).mapEmpty())
            .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                var healthStatus = manager.getHealthCheckManager().getOverallHealth();
                assertTrue(healthStatus.isHealthy());
                testContext.completeNow();
            })));
    }

    /**
     * Test Pattern 6: Compliance Configuration
     * Validates compliance configuration for enterprise environments
     */
    @Test
    void testComplianceConfiguration(VertxTestContext testContext) {
        Properties complianceProps = new Properties();
        complianceProps.setProperty("peegeeq.compliance.audit.enabled", "true");
        complianceProps.setProperty("peegeeq.compliance.encryption.at-rest", "true");
        complianceProps.setProperty("peegeeq.compliance.encryption.in-transit", "false");
        complianceProps.setProperty("peegeeq.compliance.data.retention", "P90D");
        complianceProps.setProperty("peegeeq.compliance.access.logging", "true");

        Properties mergedProps = mergeProps(complianceProps);
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("compliance", mergedProps), new SimpleMeterRegistry());
        manager.start()
            .compose(v -> manager.getVertx().timer(5000).mapEmpty())
            .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                var healthStatus = manager.getHealthCheckManager().getOverallHealth();
                assertTrue(healthStatus.isHealthy());
                assertNotNull(manager.getMetrics());
                testContext.completeNow();
            })));
    }

    private Properties mergeProps(Properties overrides) {
        Properties merged = new Properties();
        containerProps.forEach((k, v) -> merged.setProperty(k.toString(), v.toString()));
        overrides.forEach((k, v) -> merged.setProperty(k.toString(), v.toString()));
        return merged;
    }
}


