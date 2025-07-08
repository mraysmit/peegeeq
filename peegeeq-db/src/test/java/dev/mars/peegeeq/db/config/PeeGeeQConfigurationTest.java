package dev.mars.peegeeq.db.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for {@link PeeGeeQConfiguration}.
 * Tests all major functionality including:
 * - Profile loading
 * - Property loading from different sources
 * - Configuration validation
 * - Type-specific getters
 * - Configuration builders
 */
public class PeeGeeQConfigurationTest {

    private static final String TEST_PROFILE = "test";
    private String originalProfileProperty;
    private String originalProfileEnv;

    @BeforeEach
    void setUp() {
        // Save original system properties and environment variables
        originalProfileProperty = System.getProperty("peegeeq.profile");
        originalProfileEnv = System.getenv("PEEGEEQ_PROFILE");
    }

    @AfterEach
    void tearDown() {
        // Restore original system properties
        if (originalProfileProperty != null) {
            System.setProperty("peegeeq.profile", originalProfileProperty);
        } else {
            System.clearProperty("peegeeq.profile");
        }

        // Note: We can't restore environment variables in Java
    }

    @Test
    void testDefaultConstructor() {
        // Set system property for testing
        System.setProperty("peegeeq.profile", TEST_PROFILE);

        PeeGeeQConfiguration config = new PeeGeeQConfiguration();

        assertEquals(TEST_PROFILE, config.getProfile());
        assertNotNull(config.getProperties());
    }

    @Test
    void testProfileConstructor() {
        PeeGeeQConfiguration config = new PeeGeeQConfiguration(TEST_PROFILE);

        assertEquals(TEST_PROFILE, config.getProfile());
        assertNotNull(config.getProperties());
    }

    @Test
    void testGetString() {
        PeeGeeQConfiguration config = new PeeGeeQConfiguration(TEST_PROFILE);

        // Test with existing property
        assertEquals("test-host", config.getString("peegeeq.database.host", "default-host"));

        // Test with default value
        assertEquals("default-value", config.getString("non.existent.property", "default-value"));

        // Test without default (should return the value)
        assertEquals("test-host", config.getString("peegeeq.database.host"));

        // Test without default (should throw exception for non-existent property)
        assertThrows(IllegalArgumentException.class, () -> config.getString("non.existent.property"));
    }

    @Test
    void testGetInt() {
        PeeGeeQConfiguration config = new PeeGeeQConfiguration(TEST_PROFILE);

        // Test with existing property
        assertEquals(5433, config.getInt("peegeeq.database.port", 5432));

        // Test with default value
        assertEquals(9999, config.getInt("non.existent.property", 9999));

        // Test with invalid value (should return default)
        assertEquals(1000, config.getInt("peegeeq.test.invalid.int", 1000));
    }

    @Test
    void testGetLong() {
        PeeGeeQConfiguration config = new PeeGeeQConfiguration(TEST_PROFILE);

        // Test with existing property
        assertEquals(20000L, config.getLong("peegeeq.database.pool.connection-timeout-ms", 30000L));

        // Test with default value
        assertEquals(9999L, config.getLong("non.existent.property", 9999L));

        // Test with invalid value (should return default)
        assertEquals(1000L, config.getLong("peegeeq.test.invalid.long", 1000L));
    }

    @Test
    void testGetBoolean() {
        PeeGeeQConfiguration config = new PeeGeeQConfiguration(TEST_PROFILE);

        // Test with existing property
        assertTrue(config.getBoolean("peegeeq.database.ssl.enabled", false));

        // Test with default value
        assertTrue(config.getBoolean("non.existent.property", true));

        // Test with "false" string
        assertFalse(config.getBoolean("peegeeq.database.pool.auto-commit", true));
    }

    @Test
    void testGetDuration() {
        PeeGeeQConfiguration config = new PeeGeeQConfiguration(TEST_PROFILE);

        // Test with existing property
        assertEquals(Duration.ofSeconds(45), config.getDuration("peegeeq.queue.visibility-timeout", Duration.ofSeconds(30)));

        // Test with default value
        Duration defaultDuration = Duration.ofMinutes(5);
        assertEquals(defaultDuration, config.getDuration("non.existent.property", defaultDuration));

        // Test with invalid value (should return default)
        assertEquals(defaultDuration, config.getDuration("peegeeq.test.invalid.duration", defaultDuration));
    }

    // Note: getDouble is private, so we test it indirectly through CircuitBreakerConfig
    @Test
    void testDoubleValueHandling() {
        PeeGeeQConfiguration config = new PeeGeeQConfiguration(TEST_PROFILE);

        // Test with existing property through CircuitBreakerConfig
        PeeGeeQConfiguration.CircuitBreakerConfig cbConfig = config.getCircuitBreakerConfig();
        assertEquals(40.0, cbConfig.getFailureRateThreshold(), 0.001);

        // Set system property with invalid double value
        System.setProperty("peegeeq.circuit-breaker.failure-rate-threshold", "not-a-double");

        // Create new config with the invalid property
        PeeGeeQConfiguration configWithInvalidDouble = new PeeGeeQConfiguration(TEST_PROFILE);
        PeeGeeQConfiguration.CircuitBreakerConfig cbConfigWithInvalid = configWithInvalidDouble.getCircuitBreakerConfig();

        // Should use default value from the implementation (50.0)
        assertEquals(50.0, cbConfigWithInvalid.getFailureRateThreshold(), 0.001);

        // Clean up
        System.clearProperty("peegeeq.circuit-breaker.failure-rate-threshold");
    }

    @Test
    void testGetDatabaseConfig() {
        PeeGeeQConfiguration config = new PeeGeeQConfiguration(TEST_PROFILE);
        PgConnectionConfig dbConfig = config.getDatabaseConfig();

        assertNotNull(dbConfig);
        assertEquals("test-host", dbConfig.getHost());
        assertEquals(5433, dbConfig.getPort());
        assertEquals("test-db", dbConfig.getDatabase());
        assertEquals("test-user", dbConfig.getUsername());
        assertEquals("test-password", dbConfig.getPassword());
        assertEquals("test-schema", dbConfig.getSchema());
        assertTrue(dbConfig.isSslEnabled());
    }

    @Test
    void testGetPoolConfig() {
        PeeGeeQConfiguration config = new PeeGeeQConfiguration(TEST_PROFILE);
        PgPoolConfig poolConfig = config.getPoolConfig();

        assertNotNull(poolConfig);
        assertEquals(3, poolConfig.getMinimumIdle());
        assertEquals(8, poolConfig.getMaximumPoolSize());
        assertEquals(20000L, poolConfig.getConnectionTimeout());
        assertEquals(300000L, poolConfig.getIdleTimeout());
        assertEquals(900000L, poolConfig.getMaxLifetime());
        assertFalse(poolConfig.isAutoCommit());
    }

    @Test
    void testGetQueueConfig() {
        PeeGeeQConfiguration config = new PeeGeeQConfiguration(TEST_PROFILE);
        PeeGeeQConfiguration.QueueConfig queueConfig = config.getQueueConfig();

        assertNotNull(queueConfig);
        assertEquals(5, queueConfig.getMaxRetries());
        assertEquals(Duration.ofSeconds(45), queueConfig.getVisibilityTimeout());
        assertEquals(20, queueConfig.getBatchSize());
        assertEquals(Duration.ofSeconds(2), queueConfig.getPollingInterval());
        assertTrue(queueConfig.isDeadLetterEnabled());
        assertEquals(3, queueConfig.getDefaultPriority());
    }

    @Test
    void testGetMetricsConfig() {
        PeeGeeQConfiguration config = new PeeGeeQConfiguration(TEST_PROFILE);
        PeeGeeQConfiguration.MetricsConfig metricsConfig = config.getMetricsConfig();

        assertNotNull(metricsConfig);
        assertTrue(metricsConfig.isEnabled());
        assertEquals(Duration.ofSeconds(30), metricsConfig.getReportingInterval());
        assertTrue(metricsConfig.isJvmMetricsEnabled());
        assertTrue(metricsConfig.isDatabaseMetricsEnabled());
        assertEquals("test-instance", metricsConfig.getInstanceId());
    }

    @Test
    void testGetCircuitBreakerConfig() {
        PeeGeeQConfiguration config = new PeeGeeQConfiguration(TEST_PROFILE);
        PeeGeeQConfiguration.CircuitBreakerConfig cbConfig = config.getCircuitBreakerConfig();

        assertNotNull(cbConfig);
        assertTrue(cbConfig.isEnabled());
        assertEquals(10, cbConfig.getFailureThreshold());
        assertEquals(Duration.ofMinutes(2), cbConfig.getWaitDuration());
        assertEquals(50, cbConfig.getRingBufferSize());
        assertEquals(40.0, cbConfig.getFailureRateThreshold(), 0.001);
    }

    @Test
    void testValidationSuccess() {
        // This should not throw an exception with valid test properties
        assertDoesNotThrow(() -> new PeeGeeQConfiguration(TEST_PROFILE));
    }

    @Test
    void testValidationFailure() {
        // Create a temporary properties file with invalid configuration
        Properties invalidProps = new Properties();
        invalidProps.setProperty("peegeeq.database.host", ""); // Empty host (invalid)
        invalidProps.setProperty("peegeeq.database.port", "70000"); // Invalid port range
        invalidProps.setProperty("peegeeq.database.pool.min-size", "0"); // Invalid min pool size
        invalidProps.setProperty("peegeeq.database.pool.max-size", "2"); // Max < min (5)

        // Set system properties to use our invalid properties
        for (String key : invalidProps.stringPropertyNames()) {
            System.setProperty(key, invalidProps.getProperty(key));
        }

        // This should throw an IllegalStateException due to validation failures
        Exception exception = assertThrows(IllegalStateException.class, 
            () -> new PeeGeeQConfiguration(TEST_PROFILE));

        // Verify the exception message contains expected validation errors
        String exceptionMessage = exception.getMessage();
        assertTrue(exceptionMessage.contains("Database host is required"));
        assertTrue(exceptionMessage.contains("Database port must be between 1 and 65535"));
        assertTrue(exceptionMessage.contains("Minimum pool size must be at least 1"));

        // Clean up system properties
        for (String key : invalidProps.stringPropertyNames()) {
            System.clearProperty(key);
        }
    }

    @Test
    void testSystemPropertyOverride() {
        // Set a system property to override a value in the properties file
        System.setProperty("peegeeq.database.host", "system-override-host");

        PeeGeeQConfiguration config = new PeeGeeQConfiguration(TEST_PROFILE);

        // Verify the system property override worked
        assertEquals("system-override-host", config.getString("peegeeq.database.host"));

        // Clean up
        System.clearProperty("peegeeq.database.host");
    }

    @Test
    void testEnvironmentVariableHandling() {
        // We can't set environment variables in Java, so we'll test the method that processes them
        // This is a more limited test that verifies the property key transformation logic

        // Create a configuration and verify a property that would come from an environment variable
        // For example, if PEEGEEQ_DATABASE_HOST were set, it would be transformed to peegeeq.database.host

        // We can only verify this indirectly by checking that the default property was loaded
        PeeGeeQConfiguration config = new PeeGeeQConfiguration(TEST_PROFILE);
        assertEquals("test-host", config.getString("peegeeq.database.host"));
    }
}
