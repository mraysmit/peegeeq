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
import java.util.concurrent.TimeUnit;

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

    @BeforeEach
    void setUp() {
        PostgreSQLContainer postgres = SharedPostgresTestExtension.getContainer();

        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.ssl.enabled", "false");
        System.setProperty("peegeeq.database.schema", "public");
    }
    
    @AfterEach
    void tearDown(VertxTestContext testContext) throws InterruptedException {
        if (manager != null) {
            manager.closeReactive()
                .recover(t -> Future.succeededFuture())
                .onComplete(v -> {
                    System.getProperties().entrySet().removeIf(e ->
                        e.getKey().toString().startsWith("peegeeq."));
                    testContext.completeNow();
                });
        } else {
            System.getProperties().entrySet().removeIf(e ->
                e.getKey().toString().startsWith("peegeeq."));
            testContext.completeNow();
        }
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    /**
     * Test Pattern 1: Basic Security Configuration
     * Validates basic security configuration principles and properties
     */
    @Test
    void testBasicSecurityConfiguration(VertxTestContext testContext) throws InterruptedException {
        Properties securityProps = new Properties();
        securityProps.setProperty("peegeeq.database.ssl.enabled", "false");
        securityProps.setProperty("peegeeq.database.ssl.mode", "disable");
        securityProps.setProperty("peegeeq.database.connection.timeout", "30000");
        securityProps.setProperty("peegeeq.database.connection.max-lifetime", "1800000");
        securityProps.setProperty("peegeeq.database.connection.leak-detection-threshold", "60000");
        securityProps.setProperty("peegeeq.database.username.encrypted", "false");
        securityProps.setProperty("peegeeq.database.password.encrypted", "false");

        for (String key : securityProps.stringPropertyNames()) {
            System.setProperty(key, securityProps.getProperty(key));
        }

        manager = new PeeGeeQManager(new PeeGeeQConfiguration("secure"), new SimpleMeterRegistry());
        manager.start()
            .compose(v -> manager.getVertx().timer(1000).mapEmpty())
            .onSuccess(v -> testContext.verify(() -> {
                var healthStatus = manager.getHealthCheckManager().getOverallHealth();
                assertNotNull(healthStatus);
                assertTrue(healthStatus.isHealthy());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    /**
     * Test Pattern 2: SSL/TLS Configuration
     * Validates SSL/TLS configuration patterns (adapted for test environment)
     */
    @Test
    void testSSLTLSConfiguration(VertxTestContext testContext) throws InterruptedException {
        Properties sslProps = new Properties();
        sslProps.setProperty("peegeeq.database.ssl.enabled", "false");
        sslProps.setProperty("peegeeq.database.ssl.mode", "disable");
        sslProps.setProperty("peegeeq.database.ssl.cert.validation", "none");

        for (String key : sslProps.stringPropertyNames()) {
            System.setProperty(key, sslProps.getProperty(key));
        }

        manager = new PeeGeeQManager(new PeeGeeQConfiguration("ssl-test"), new SimpleMeterRegistry());
        manager.start()
            .compose(v -> manager.getVertx().timer(1000).mapEmpty())
            .onSuccess(v -> testContext.verify(() -> {
                var healthStatus = manager.getHealthCheckManager().getOverallHealth();
                assertTrue(healthStatus.isHealthy());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    /**
     * Test Pattern 3: Production Security Setup
     * Validates production-ready security configuration
     */
    @Test
    void testProductionSecuritySetup(VertxTestContext testContext) throws InterruptedException {
        Properties prodProps = new Properties();
        prodProps.setProperty("peegeeq.database.pool.minimum-idle", "5");
        prodProps.setProperty("peegeeq.database.pool.maximum-pool-size", "20");
        prodProps.setProperty("peegeeq.database.pool.connection-timeout", "30000");
        prodProps.setProperty("peegeeq.database.pool.idle-timeout", "600000");
        prodProps.setProperty("peegeeq.database.pool.max-lifetime", "1800000");
        prodProps.setProperty("peegeeq.security.monitoring.enabled", "true");
        prodProps.setProperty("peegeeq.security.audit.enabled", "true");
        prodProps.setProperty("peegeeq.security.connection.validation", "true");

        for (String key : prodProps.stringPropertyNames()) {
            System.setProperty(key, prodProps.getProperty(key));
        }

        manager = new PeeGeeQManager(new PeeGeeQConfiguration("production"), new SimpleMeterRegistry());
        manager.start()
            .compose(v -> manager.getVertx().timer(1000).mapEmpty())
            .onSuccess(v -> testContext.verify(() -> {
                var healthStatus = manager.getHealthCheckManager().getOverallHealth();
                assertTrue(healthStatus.isHealthy());
                assertNotNull(manager.getMetrics());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    /**
     * Test Pattern 4: Security Monitoring
     * Validates security monitoring and health check functionality
     */
    @Test
    void testSecurityMonitoring(VertxTestContext testContext) throws InterruptedException {
        Properties monitoringProps = new Properties();
        monitoringProps.setProperty("peegeeq.monitoring.health.enabled", "true");
        monitoringProps.setProperty("peegeeq.monitoring.health.interval", "PT30S");
        monitoringProps.setProperty("peegeeq.monitoring.metrics.enabled", "true");
        monitoringProps.setProperty("peegeeq.monitoring.security.audit", "true");

        for (String key : monitoringProps.stringPropertyNames()) {
            System.setProperty(key, monitoringProps.getProperty(key));
        }

        manager = new PeeGeeQManager(new PeeGeeQConfiguration("monitoring"), new SimpleMeterRegistry());
        manager.start()
            .compose(v -> manager.getVertx().timer(2000).mapEmpty())
            .onSuccess(v -> testContext.verify(() -> {
                var healthCheckManager = manager.getHealthCheckManager();
                assertNotNull(healthCheckManager);

                var overallHealth = healthCheckManager.getOverallHealthInternal();
                assertNotNull(overallHealth);
                assertTrue(overallHealth.isHealthy());

                var overallHealthComponents = overallHealth.getComponents();
                assertTrue(overallHealthComponents.size() > 0, "Should have at least one health check");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    /**
     * Test Pattern 5: Credential Management
     * Validates credential management best practices
     */
    @Test
    void testCredentialManagement(VertxTestContext testContext) throws InterruptedException {
        Properties credProps = new Properties();
        credProps.setProperty("peegeeq.credentials.source", "system-properties");
        credProps.setProperty("peegeeq.credentials.encryption.enabled", "false");
        credProps.setProperty("peegeeq.credentials.rotation.enabled", "false");
        credProps.setProperty("peegeeq.credentials.validation.enabled", "true");

        for (String key : credProps.stringPropertyNames()) {
            System.setProperty(key, credProps.getProperty(key));
        }

        manager = new PeeGeeQManager(new PeeGeeQConfiguration("credentials"), new SimpleMeterRegistry());
        manager.start()
            .compose(v -> manager.getVertx().timer(1000).mapEmpty())
            .onSuccess(v -> testContext.verify(() -> {
                var healthStatus = manager.getHealthCheckManager().getOverallHealth();
                assertTrue(healthStatus.isHealthy());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    /**
     * Test Pattern 6: Compliance Configuration
     * Validates compliance configuration for enterprise environments
     */
    @Test
    void testComplianceConfiguration(VertxTestContext testContext) throws InterruptedException {
        Properties complianceProps = new Properties();
        complianceProps.setProperty("peegeeq.compliance.audit.enabled", "true");
        complianceProps.setProperty("peegeeq.compliance.encryption.at-rest", "true");
        complianceProps.setProperty("peegeeq.compliance.encryption.in-transit", "false");
        complianceProps.setProperty("peegeeq.compliance.data.retention", "P90D");
        complianceProps.setProperty("peegeeq.compliance.access.logging", "true");

        for (String key : complianceProps.stringPropertyNames()) {
            System.setProperty(key, complianceProps.getProperty(key));
        }

        manager = new PeeGeeQManager(new PeeGeeQConfiguration("compliance"), new SimpleMeterRegistry());
        manager.start()
            .compose(v -> manager.getVertx().timer(1000).mapEmpty())
            .onSuccess(v -> testContext.verify(() -> {
                var healthStatus = manager.getHealthCheckManager().getOverallHealth();
                assertTrue(healthStatus.isHealthy());
                assertNotNull(manager.getMetrics());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }
}


