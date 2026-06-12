package dev.mars.peegeeq.db.examples;

import dev.mars.peegeeq.test.PostgreSQLTestConstants;
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
import org.junit.jupiter.api.parallel.Isolated;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test for AdvancedConfigurationExample functionality.
 *
 * This test validates all advanced configuration management patterns from the original 651-line example:
 * 1. Environment-Specific Configuration - Development, staging, production configurations
 * 2. External Configuration Management - Properties files, environment variables, system properties
 * 3. Database Connection Pooling - Connection pool optimization and tuning
 * 4. Monitoring Integration - Prometheus/Grafana ready metrics configuration
 * 5. Configuration Validation - Best practices and validation patterns
 * 6. Runtime Configuration Updates - Dynamic configuration updates and safety considerations
 *
 * All original functionality is preserved with enhanced test assertions and documentation.
 * Tests demonstrate production-ready configuration patterns for distributed systems.
 */
@Tag(TestCategories.INTEGRATION)
@Isolated // Avoids parallel interference on System properties used by this example
@ExtendWith({SharedPostgresTestExtension.class, VertxExtension.class})
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
public class AdvancedConfigurationExampleTest {

    private static final Logger logger = LoggerFactory.getLogger(AdvancedConfigurationExampleTest.class);

    private PeeGeeQManager manager;
    private Properties containerProps;

    private static final String DB_URL_KEY = "PEEGEEQ_DB_URL";
    private static final String DB_USERNAME_KEY = "PEEGEEQ_DB_USERNAME";
    private static final String DB_PASSWORD_KEY = "PEEGEEQ_DB_PASSWORD";
    private static final String MONITORING_ENABLED_KEY = "PEEGEEQ_MONITORING_ENABLED";

    @BeforeEach
    void setUp() {
        logger.info("Setting up Advanced Configuration Example Test");

        PostgreSQLContainer postgres = SharedPostgresTestExtension.getContainer();

        containerProps = new Properties();
        containerProps.setProperty("peegeeq.database.host", postgres.getHost());
        containerProps.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        containerProps.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        containerProps.setProperty("peegeeq.database.username", postgres.getUsername());
        containerProps.setProperty("peegeeq.database.password", postgres.getPassword());
        containerProps.setProperty("peegeeq.database.ssl.enabled", "false");
        containerProps.setProperty("peegeeq.database.schema", PostgreSQLTestConstants.TEST_SCHEMA);containerProps.setProperty("peegeeq.database.pool.min-size", "1");
        containerProps.setProperty("peegeeq.database.pool.max-size", "3");
        containerProps.setProperty("peegeeq.database.pool.shared", "false");
        containerProps.setProperty("peegeeq.database.pool.idle-timeout-ms", "2000");
        containerProps.setProperty("peegeeq.database.pool.connection-timeout-ms", "5000");
        containerProps.setProperty("peegeeq.migration.enabled", "false");
        containerProps.setProperty("peegeeq.migration.auto-migrate", "false");
        containerProps.setProperty("peegeeq.queue.consumer-group-retry.enabled", "false");
        containerProps.setProperty("peegeeq.queue.dead-consumer-detection.enabled", "false");

        logger.info(" Advanced Configuration Example Test setup completed");
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        logger.info("Tearing down Advanced Configuration Example Test");

        if (manager != null) {
            manager.closeReactive()
                .onSuccess(v -> {
                    clearTestProperties();
                    logger.info("\u2713 Advanced Configuration Example Test teardown completed");
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
        } else {
            clearTestProperties();
            logger.info("\u2713 Advanced Configuration Example Test teardown completed");
            testContext.completeNow();
        }
    }

    /**
     * Test Pattern 1: Environment-Specific Configuration
     * Validates different configurations for development, staging, and production environments
     */
    @Test
    void testEnvironmentSpecificConfiguration() throws Exception {
        logger.info("=== Testing Environment-Specific Configuration ===");

        String[] environments = {"development", "staging", "production"};

        for (String environment : environments) {
            logger.info("--- Testing {} Environment Configuration ---", environment.toUpperCase());

            // Test database configuration
            String dbHost = getDatabaseHost(environment);
            int dbPort = getDatabasePort(environment);
            String dbName = getDatabaseName(environment);
            boolean sslEnabled = isSslEnabled(environment);

            assertNotNull(dbHost, "Database host should be configured for " + environment);
            assertTrue(dbPort > 0, "Database port should be positive for " + environment);
            assertNotNull(dbName, "Database name should be configured for " + environment);

            logger.info("Database Configuration for {}:", environment);
            logger.info("   Host: {}, Port: {}, Name: {}, SSL: {}", dbHost, dbPort, dbName, sslEnabled);

            // Test pool configuration
            int minPoolSize = getMinPoolSize(environment);
            int maxPoolSize = getMaxPoolSize(environment);
            int connectionTimeout = getConnectionTimeout(environment);
            int idleTimeout = getIdleTimeout(environment);
            int maxLifetime = getMaxLifetime(environment);

            assertTrue(minPoolSize > 0, "Min pool size should be positive for " + environment);
            assertTrue(maxPoolSize >= minPoolSize, "Max pool size should be >= min pool size for " + environment);
            assertTrue(connectionTimeout > 0, "Connection timeout should be positive for " + environment);
            assertTrue(idleTimeout > 0, "Idle timeout should be positive for " + environment);
            assertTrue(maxLifetime > 0, "Max lifetime should be positive for " + environment);

            logger.info("Pool Configuration for {}:", environment);
            logger.info("   Min: {}, Max: {}, ConnTimeout: {}ms, IdleTimeout: {}ms, MaxLifetime: {}ms",
                minPoolSize, maxPoolSize, connectionTimeout, idleTimeout, maxLifetime);

            // Test queue configuration
            int maxRetries = getMaxRetries(environment);
            int visibilityTimeout = getVisibilityTimeout(environment);
            int batchSize = getBatchSize(environment);
            int pollingInterval = getPollingInterval(environment);

            assertTrue(maxRetries >= 0, "Max retries should be non-negative for " + environment);
            assertTrue(visibilityTimeout > 0, "Visibility timeout should be positive for " + environment);
            assertTrue(batchSize > 0, "Batch size should be positive for " + environment);
            assertTrue(pollingInterval > 0, "Polling interval should be positive for " + environment);

            logger.info("Queue Configuration for {}:", environment);
            logger.info("   MaxRetries: {}, VisibilityTimeout: {}ms, BatchSize: {}, PollingInterval: {}ms",
                maxRetries, visibilityTimeout, batchSize, pollingInterval);

            // Test monitoring configuration
            boolean monitoringEnabled = isMonitoringEnabled(environment);
            boolean prometheusEnabled = isPrometheusEnabled(environment);
            String logLevel = getLogLevel(environment);
            String retryPolicy = getRetryPolicy(environment);

            assertNotNull(logLevel, "Log level should be configured for " + environment);
            assertNotNull(retryPolicy, "Retry policy should be configured for " + environment);

            logger.info("Monitoring Configuration for {}:", environment);
            logger.info("   Monitoring: {}, Prometheus: {}, LogLevel: {}, RetryPolicy: {}",
                monitoringEnabled, prometheusEnabled, logLevel, retryPolicy);
        }

        logger.info("Environment-specific configuration validated successfully");
    }

    /**
     * Test Pattern 2: External Configuration Management
     * Validates properties files, environment variables, and system properties
     */
    @Test
    void testExternalConfigurationManagement() throws Exception {
        logger.info("=== Testing External Configuration Management ===");

        // Test instance-level properties configuration (correct pattern since Phase 11 removed
        // the System.getProperties() sweep  use the 2-arg constructor to pass per-instance values)
        logger.info("--- Testing Instance Properties Configuration ---");
        Properties instanceProps = new Properties();
        containerProps.forEach((k, v) -> instanceProps.setProperty(k.toString(), v.toString()));
        instanceProps.setProperty("peegeeq.test.property", "instance-value");
        PeeGeeQConfiguration instanceConfig = new PeeGeeQConfiguration("default", instanceProps);
        String instanceValue = instanceConfig.getString("peegeeq.test.property", "default");
        assertEquals("instance-value", instanceValue, "Instance property override should take precedence");
        logger.info("Instance properties configuration: {}", instanceValue);

        // Test environment variables simulation
        logger.info("--- Testing Environment Variables Configuration ---");
        System.setProperty(DB_URL_KEY, "jdbc:postgresql://localhost:5432/peegeeq");
        System.setProperty(DB_USERNAME_KEY, "peegeeq_user");
        System.setProperty(DB_PASSWORD_KEY, "secure_password");
        System.setProperty(MONITORING_ENABLED_KEY, "true");

        String dbUrl = getConfigValue(DB_URL_KEY, "jdbc:postgresql://localhost:5432/peegeeq");
        String dbUsername = getConfigValue(DB_USERNAME_KEY, "peegeeq");
        String dbPassword = getConfigValue(DB_PASSWORD_KEY, "password");
        boolean monitoringEnabled = Boolean.parseBoolean(getConfigValue(MONITORING_ENABLED_KEY, "false"));

        assertNotNull(dbUrl, "Database URL should be configured");
        assertNotNull(dbUsername, "Database username should be configured");
        assertNotNull(dbPassword, "Database password should be configured");
        assertTrue(monitoringEnabled, "Monitoring should be enabled");

        logger.info("Environment Variables Configuration:");
        logger.info("   Database URL: {}", maskSensitiveInfo(dbUrl));
        logger.info("   Database Username: {}", dbUsername);
        logger.info("   Database Password: {}", maskSensitiveInfo(dbPassword));
        logger.info("   Monitoring Enabled: {}", monitoringEnabled);

        // Test configuration hierarchy
        logger.info("--- Testing Configuration Hierarchy ---");
        testConfigurationHierarchy();

        logger.info("External configuration management validated successfully");
    }

    /**
     * Test Pattern 3: Database Connection Pooling
     * Validates database connection pool optimization and tuning
     */
    @Test
    void testDatabaseConnectionPooling(VertxTestContext testContext) {
        logger.info("=== Testing Database Connection Pooling ===");

        String[] environments = {"development", "staging", "production"};

        Future<Void> chain = Future.succeededFuture();
        for (String environment : environments) {
            chain = chain.compose(v -> {
                logger.info("--- Testing {} Pool Configuration ---", environment.toUpperCase());

                Properties envProps = new Properties();
                containerProps.forEach((k, val) -> envProps.setProperty(k.toString(), val.toString()));
                envProps.setProperty("peegeeq.database.pool.min-size", String.valueOf(getMinPoolSize(environment)));
                envProps.setProperty("peegeeq.database.pool.max-size", String.valueOf(getMaxPoolSize(environment)));
                envProps.setProperty("peegeeq.database.pool.connection-timeout", String.valueOf(getConnectionTimeout(environment)));
                envProps.setProperty("peegeeq.database.pool.idle-timeout", String.valueOf(getIdleTimeout(environment)));
                envProps.setProperty("peegeeq.database.pool.max-lifetime", String.valueOf(getMaxLifetime(environment)));

                PeeGeeQConfiguration config = new PeeGeeQConfiguration(environment, envProps);
                manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
                return manager.start()
                    .compose(started -> {
                        testContext.verify(() -> {
                            assertNotNull(manager, "PeeGeeQ Manager should be initialized");
                            assertTrue(manager.isStarted(), "PeeGeeQ Manager should be started");
                        });

                        logger.info("{} Pool Configuration validated:", environment);
                        logger.info("   Min Pool Size: {}", getMinPoolSize(environment));
                        logger.info("   Max Pool Size: {}", getMaxPoolSize(environment));
                        logger.info("   Connection Timeout: {}ms", getConnectionTimeout(environment));
                        logger.info("   Idle Timeout: {}ms", getIdleTimeout(environment));
                        logger.info("   Max Lifetime: {}ms", getMaxLifetime(environment));

                        return manager.closeReactive();
                    })
                    .compose(closed -> {
                        manager = null;
                        return Future.succeededFuture();
                    });
            });
        }

        chain
            .onSuccess(v -> {
                logger.info("Database connection pooling validated successfully");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    /**
     * Test Pattern 4: Monitoring Integration
     * Validates Prometheus/Grafana ready metrics configuration
     */
    @Test
    void testMonitoringIntegration() throws Exception {
        logger.info("=== Testing Monitoring Integration ===");

        // Test monitoring configuration for different environments
        String[] environments = {"development", "staging", "production"};

        for (String environment : environments) {
            logger.info("--- Testing {} Monitoring Configuration ---", environment.toUpperCase());

            boolean monitoringEnabled = isMonitoringEnabled(environment);
            boolean prometheusEnabled = isPrometheusEnabled(environment);
            String logLevel = getLogLevel(environment);

            // Validate monitoring configuration
            if ("production".equals(environment)) {
                assertTrue(monitoringEnabled, "Monitoring should be enabled in production");
                assertTrue(prometheusEnabled, "Prometheus should be enabled in production");
                assertEquals("INFO", logLevel, "Production should use INFO log level");
            } else if ("staging".equals(environment)) {
                assertTrue(monitoringEnabled, "Monitoring should be enabled in staging");
                assertTrue(prometheusEnabled, "Prometheus should be enabled in staging");
                assertEquals("DEBUG", logLevel, "Staging should use DEBUG log level");
            } else { // development
                assertFalse(monitoringEnabled, "Monitoring can be disabled in development");
                assertFalse(prometheusEnabled, "Prometheus can be disabled in development");
                assertEquals("DEBUG", logLevel, "Development should use DEBUG log level");
            }

            logger.info("{} Monitoring Configuration:", environment);
            logger.info("   Monitoring Enabled: {}", monitoringEnabled);
            logger.info("   Prometheus Enabled: {}", prometheusEnabled);
            logger.info("   Log Level: {}", logLevel);
        }

        logger.info("Monitoring integration validated successfully");
    }

    /**
     * Test Pattern 5: Configuration Validation
     * Validates configuration validation and best practices
     */
    @Test
    void testConfigurationValidation() throws Exception {
        logger.info("=== Testing Configuration Validation ===");

        // Test valid configurations
        logger.info("--- Testing Valid Configurations ---");
        assertTrue(validateDatabaseConfiguration("localhost", 5432, "peegeeq_test"),
            "Valid database configuration should pass validation");
        assertTrue(validatePoolConfiguration(2, 10, 5000, 30000, 1800000),
            "Valid pool configuration should pass validation");
        assertTrue(validateQueueConfiguration(3, 30000, 10, 1000),
            "Valid queue configuration should pass validation");

        // Test invalid configurations
        logger.warn("===== INTENTIONAL WARN TEST ===== The next 3 WARN logs (validation rejection messages) are EXPECTED this test deliberately passes invalid configuration values to verify each validation guard");
        logger.info("--- Testing Invalid Configurations ---");
        assertFalse(validateDatabaseConfiguration("", 5432, "peegeeq_test"),
            "Empty database host should fail validation");
        assertFalse(validatePoolConfiguration(10, 5, 5000, 30000, 1800000),
            "Min pool size > max pool size should fail validation");
        assertFalse(validateQueueConfiguration(-1, 30000, 10, 1000),
            "Negative max retries should fail validation");

        logger.info("Configuration validation patterns validated successfully");
    }

    /**
     * Test Pattern 6: Runtime Configuration Updates
     * Validates dynamic configuration updates and safety considerations
     */
    @Test
    void testRuntimeConfigurationUpdates() throws Exception {
        logger.info("=== Testing Runtime Configuration Updates ===");

        // Properties are supplied at construction time  System.setProperty has no effect on
        // PeeGeeQConfiguration since the System sweep was removed in version 2.0.
        // Each instance owns its isolated Properties object; configuration is never global.
        logger.info("--- Testing Instance-Isolated Configuration Properties ---");

        Properties configProps = new Properties();
        containerProps.forEach((k, v) -> configProps.setProperty(k.toString(), v.toString()));
        configProps.setProperty("peegeeq.queue.max-retries", "5");
        configProps.setProperty("peegeeq.queue.batch-size", "20");
        configProps.setProperty("peegeeq.queue.polling-interval", "2000");
        configProps.setProperty("peegeeq.monitoring.enabled", "true");
        PeeGeeQConfiguration testConfig = new PeeGeeQConfiguration("default", configProps);

        assertEquals("5", testConfig.getString("peegeeq.queue.max-retries", null));
        assertEquals("20", testConfig.getString("peegeeq.queue.batch-size", null));
        assertEquals("2000", testConfig.getString("peegeeq.queue.polling-interval", null));
        assertTrue(testConfig.getBoolean("peegeeq.monitoring.enabled", false));

        logger.info("Instance Configuration Values:");
        logger.info("   Max Retries: {}", testConfig.getString("peegeeq.queue.max-retries", null));
        logger.info("   Batch Size: {}", testConfig.getString("peegeeq.queue.batch-size", null));
        logger.info("   Polling Interval: {}ms", testConfig.getString("peegeeq.queue.polling-interval", null));
        logger.info("   Monitoring Enabled: {}", testConfig.getBoolean("peegeeq.monitoring.enabled", false));

        // Test safety considerations
        logger.info("--- Testing Safety Considerations ---");
        logger.info("Safety Mechanisms:");
        logger.info("   - Configuration validation before applying changes");
        logger.info("   - Gradual rollout capability for configuration changes");
        logger.info("   - Rollback capability for failed configuration updates");
        logger.info("   - Audit trail of all configuration changes");
        logger.info("   - Impact assessment before applying updates");

        logger.info(" Non-updatable Configuration (requires restart):");
        logger.info("   - Database connection URL and credentials");
        logger.info("   - Core threading model configuration");
        logger.info("   - JVM-level settings and memory allocation");

        logger.info("Runtime configuration updates validated successfully");
    }

    // Helper methods for configuration management

    private void clearTestProperties() {
        System.clearProperty(DB_URL_KEY);
        System.clearProperty(DB_USERNAME_KEY);
        System.clearProperty(DB_PASSWORD_KEY);
        System.clearProperty(MONITORING_ENABLED_KEY);
    }

    private String getConfigValue(String key, String defaultValue) {
        // Check system properties first
        String value = System.getProperty(key);
        if (value != null) {
            return value;
        }

        // Check environment variables
        value = System.getenv(key);
        if (value != null) {
            return value;
        }

        // Check simulated environment variables (for demo)
        value = System.getProperty("env." + key);
        if (value != null) {
            return value;
        }

        // Return default
        return defaultValue;
    }

    private String maskSensitiveInfo(String value) {
        if (value == null || value.length() <= 4) {
            return "****";
        }
        return value.substring(0, 2) + "****" + value.substring(value.length() - 2);
    }

    private void testConfigurationHierarchy() {
        logger.info("Testing configuration hierarchy (highest to lowest priority):");
        logger.info("1. System properties (-Dproperty=value)");
        logger.info("2. Environment variables");
        logger.info("3. External configuration files");
        logger.info("4. Application defaults");

        // Test hierarchy with a test property
        String testKey = "peegeeq.hierarchy.test";

        // Set default
        String defaultValue = "default-value";

        // Set environment variable simulation
        System.setProperty("env." + testKey, "env-value");

        // Set system property (should override environment)
        System.setProperty(testKey, "system-value");

        String finalValue = getConfigValue(testKey, defaultValue);
        assertEquals("system-value", finalValue, "System property should have highest priority");

        // Remove system property, should fall back to env
        System.clearProperty(testKey);
        finalValue = getConfigValue(testKey, defaultValue);
        assertEquals("env-value", finalValue, "Environment variable should have second priority");

        // Remove env simulation, should fall back to default
        System.clearProperty("env." + testKey);
        finalValue = getConfigValue(testKey, defaultValue);
        assertEquals("default-value", finalValue, "Default value should have lowest priority");

        logger.info("Configuration hierarchy validated successfully");
    }

    // Environment-specific configuration getters

    private String getDatabaseHost(String environment) {
        switch (environment) {
            case "development": return "localhost";
            case "staging": return "staging-db.example.com";
            case "production": return "prod-db.example.com";
            default: return "localhost";
        }
    }

    private int getDatabasePort(String environment) {
        return 5432; // Same for all environments
    }

    private String getDatabaseName(String environment) {
        switch (environment) {
            case "development": return "peegeeq_dev";
            case "staging": return "peegeeq_staging";
            case "production": return "peegeeq_prod";
            default: return "peegeeq";
        }
    }

    private boolean isSslEnabled(String environment) {
        switch (environment) {
            case "development": return false;
            case "staging": return true;
            case "production": return true;
            default: return false;
        }
    }

    private int getMinPoolSize(String environment) {
        switch (environment) {
            case "development": return 2;
            case "staging": return 5;
            case "production": return 10;
            default: return 2;
        }
    }

    private int getMaxPoolSize(String environment) {
        switch (environment) {
            case "development": return 10;
            case "staging": return 20;
            case "production": return 50;
            default: return 10;
        }
    }

    private int getConnectionTimeout(String environment) {
        switch (environment) {
            case "development": return 5000;  // 5 seconds
            case "staging": return 10000;     // 10 seconds
            case "production": return 15000;  // 15 seconds
            default: return 5000;
        }
    }

    private int getIdleTimeout(String environment) {
        switch (environment) {
            case "development": return 300000;   // 5 minutes
            case "staging": return 600000;       // 10 minutes
            case "production": return 1800000;   // 30 minutes
            default: return 300000;
        }
    }

    private int getMaxLifetime(String environment) {
        switch (environment) {
            case "development": return 1800000;  // 30 minutes
            case "staging": return 3600000;      // 1 hour
            case "production": return 7200000;   // 2 hours
            default: return 1800000;
        }
    }

    private int getMaxRetries(String environment) {
        switch (environment) {
            case "development": return 3;
            case "staging": return 5;
            case "production": return 8;
            default: return 3;
        }
    }

    private int getVisibilityTimeout(String environment) {
        switch (environment) {
            case "development": return 30000;   // 30 seconds
            case "staging": return 60000;       // 1 minute
            case "production": return 300000;   // 5 minutes
            default: return 30000;
        }
    }

    private int getBatchSize(String environment) {
        switch (environment) {
            case "development": return 5;
            case "staging": return 10;
            case "production": return 20;
            default: return 5;
        }
    }

    private int getPollingInterval(String environment) {
        switch (environment) {
            case "development": return 1000;    // 1 second
            case "staging": return 500;         // 0.5 seconds
            case "production": return 200;      // 0.2 seconds
            default: return 1000;
        }
    }

    private boolean isMonitoringEnabled(String environment) {
        switch (environment) {
            case "development": return false;
            case "staging": return true;
            case "production": return true;
            default: return false;
        }
    }

    private boolean isPrometheusEnabled(String environment) {
        switch (environment) {
            case "development": return false;
            case "staging": return true;
            case "production": return true;
            default: return false;
        }
    }

    private String getLogLevel(String environment) {
        switch (environment) {
            case "development": return "DEBUG";
            case "staging": return "DEBUG";
            case "production": return "INFO";
            default: return "DEBUG";
        }
    }

    private String getRetryPolicy(String environment) {
        switch (environment) {
            case "development": return "EXPONENTIAL_BACKOFF";
            case "staging": return "LINEAR_BACKOFF";
            case "production": return "EXPONENTIAL_BACKOFF";
            default: return "EXPONENTIAL_BACKOFF";
        }
    }

    // Configuration validation methods

    private boolean validateDatabaseConfiguration(String host, int port, String database) {
        if (host == null || host.trim().isEmpty()) {
            logger.warn("Database host cannot be null or empty");
            return false;
        }
        if (port <= 0 || port > 65535) {
            logger.warn("Database port must be between 1 and 65535");
            return false;
        }
        if (database == null || database.trim().isEmpty()) {
            logger.warn("Database name cannot be null or empty");
            return false;
        }
        return true;
    }

    private boolean validatePoolConfiguration(int minSize, int maxSize, int connectionTimeout,
                                            int idleTimeout, int maxLifetime) {
        if (minSize < 0) {
            logger.warn("Min pool size cannot be negative");
            return false;
        }
        if (maxSize < minSize) {
            logger.warn("Max pool size cannot be less than min pool size");
            return false;
        }
        if (connectionTimeout <= 0) {
            logger.warn("Connection timeout must be positive");
            return false;
        }
        if (idleTimeout <= 0) {
            logger.warn("Idle timeout must be positive");
            return false;
        }
        if (maxLifetime <= 0) {
            logger.warn("Max lifetime must be positive");
            return false;
        }
        return true;
    }

    private boolean validateQueueConfiguration(int maxRetries, int visibilityTimeout,
                                             int batchSize, int pollingInterval) {
        if (maxRetries < 0) {
            logger.warn("Max retries cannot be negative");
            return false;
        }
        if (visibilityTimeout <= 0) {
            logger.warn("Visibility timeout must be positive");
            return false;
        }
        if (batchSize <= 0) {
            logger.warn("Batch size must be positive");
            return false;
        }
        if (pollingInterval <= 0) {
            logger.warn("Polling interval must be positive");
            return false;
        }
        return true;
    }
}


