package dev.mars.peegeeq.db.config;

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


import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.logging.ExpectedErrorLog;

import java.time.Duration;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for {@link PeeGeeQConfiguration}.
 *
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
@Tag(TestCategories.CORE)
@ResourceLock("system-properties")
public class PeeGeeQConfigurationTest {

    private static final String TEST_PROFILE = "test";
    private Properties savedProperties;

    @BeforeEach
    void setUp() {
        // Save ALL peegeeq.* system properties before each test
        savedProperties = new Properties();
        System.getProperties().entrySet().stream()
            .filter(entry -> entry.getKey().toString().startsWith("peegeeq."))
            .forEach(entry -> savedProperties.put(entry.getKey(), entry.getValue()));

        // Clear all peegeeq.* properties to start fresh
        System.getProperties().entrySet().removeIf(entry ->
            entry.getKey().toString().startsWith("peegeeq."));
    }

    @AfterEach
    void tearDown() {
        // Clear all peegeeq.* properties
        System.getProperties().entrySet().removeIf(entry ->
            entry.getKey().toString().startsWith("peegeeq."));

        // Restore saved properties
        savedProperties.forEach((key, value) ->
            System.setProperty(key.toString(), value.toString()));
    }

    @Test
    void testConstructorLoadsProfile() {
        PeeGeeQConfiguration config = new PeeGeeQConfiguration(TEST_PROFILE, new Properties());

        assertEquals(TEST_PROFILE, config.getProfile());
        assertNotNull(config.getProperties());
    }

    @Test
    void testBlankSchemaRejected() {
        // PeeGeeQ has no default schema: a blank schema must fail validation at
        // construction, not flow silently into connection configuration
        Properties overrides = new Properties();
        overrides.setProperty("peegeeq.database.schema", "   ");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new PeeGeeQConfiguration(TEST_PROFILE, overrides),
                "A blank peegeeq.database.schema must fail configuration validation");
        assertTrue(ex.getMessage().contains("schema"),
                "The validation error must name the schema, got: " + ex.getMessage());
    }

    @Test
    void testExplicitSchemaAccepted() {
        Properties overrides = new Properties();
        overrides.setProperty("peegeeq.database.schema", "peegeeq_test");

        PeeGeeQConfiguration config = new PeeGeeQConfiguration(TEST_PROFILE, overrides);

        assertEquals("peegeeq_test", config.getDatabaseConfig().getSchema());
    }

    @Test
    void testUnresolvedPlaceholderWithoutDefaultThrows() {
        // ${VAR} with no default and no env var must fail loudly at construction —
        // passing the literal "${VAR}" string downstream is silent corruption
        Properties overrides = new Properties();
        overrides.setProperty("peegeeq.database.schema", "peegeeq_test");
        overrides.setProperty("peegeeq.database.name", "${PEEGEEQ_TEST_UNSET_VAR_XYZ}");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new PeeGeeQConfiguration(TEST_PROFILE, overrides),
                "An unresolvable ${VAR} placeholder must fail configuration, not flow as a literal");
        assertTrue(ex.getMessage().contains("PEEGEEQ_TEST_UNSET_VAR_XYZ"),
                "The error must name the missing variable, got: " + ex.getMessage());
    }

    @Test
    void testPlaceholderDefaultStillApplies() {
        Properties overrides = new Properties();
        overrides.setProperty("peegeeq.database.schema", "peegeeq_test");
        overrides.setProperty("peegeeq.database.name", "${PEEGEEQ_TEST_UNSET_VAR_XYZ:fallback_db}");

        PeeGeeQConfiguration config = new PeeGeeQConfiguration(TEST_PROFILE, overrides);

        assertEquals("fallback_db", config.getString("peegeeq.database.name"));
    }

    @Test
    void testConstructorAppliesOverrides() {
        Properties overrides = new Properties();
        overrides.setProperty("peegeeq.database.host", "override-host");

        PeeGeeQConfiguration config = new PeeGeeQConfiguration(TEST_PROFILE, overrides);

        assertEquals(TEST_PROFILE, config.getProfile());
        assertNotNull(config.getProperties());
        assertEquals("override-host", config.getString("peegeeq.database.host"));
    }

    @Test
    void testGetString() {
        PeeGeeQConfiguration config = new PeeGeeQConfiguration(TEST_PROFILE, new Properties());

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
        PeeGeeQConfiguration config = new PeeGeeQConfiguration(TEST_PROFILE, new Properties());

        // Test with existing property
        assertEquals(5433, config.getInt("peegeeq.database.port", 5432));

        // Test with default value
        assertEquals(9999, config.getInt("non.existent.property", 9999));

        // Test with invalid value (should return default)
        assertEquals(1000, config.getInt("peegeeq.test.invalid.int", 1000));
    }

    @Test
    void testGetLong() {
        PeeGeeQConfiguration config = new PeeGeeQConfiguration(TEST_PROFILE, new Properties());

        // Test with existing property
        assertEquals(5000L, config.getLong("peegeeq.database.pool.connection-timeout-ms", 30000L));

        // Test with default value
        assertEquals(9999L, config.getLong("non.existent.property", 9999L));

        // Test with invalid value (should return default)
        assertEquals(1000L, config.getLong("peegeeq.test.invalid.long", 1000L));
    }

    @Test
    void testGetBoolean() {
        PeeGeeQConfiguration config = new PeeGeeQConfiguration(TEST_PROFILE, new Properties());

        // Test with existing property
        assertTrue(config.getBoolean("peegeeq.database.ssl.enabled", false));

        // Test with default value
        assertTrue(config.getBoolean("non.existent.property", true));

        // Test with "false" string
        assertFalse(config.getBoolean("peegeeq.database.pool.auto-commit", true));
    }

    @Test
    @ExpectedErrorLog(
            logger = "dev.mars.peegeeq.db.config.PeeGeeQConfiguration",
            message = "Invalid duration value for peegeeq.test.invalid.duration: not-a-duration, using default: PT5M",
            throwable = ExpectedErrorLog.ThrowablePolicy.NONE)
    void testGetDuration() {
        PeeGeeQConfiguration config = new PeeGeeQConfiguration(TEST_PROFILE, new Properties());

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
        PeeGeeQConfiguration config = new PeeGeeQConfiguration(TEST_PROFILE, new Properties());

        // Test with existing property through CircuitBreakerConfig
        PeeGeeQConfiguration.CircuitBreakerConfig cbConfig = config.getCircuitBreakerConfig();
        assertEquals(40.0, cbConfig.getFailureRateThreshold(), 0.001);

        // Pass invalid double via 2-arg constructor (System properties are no longer swept)
        Properties invalidDoubleProps = new Properties();
        invalidDoubleProps.setProperty("peegeeq.circuit-breaker.failure-rate-threshold", "not-a-double");

        PeeGeeQConfiguration configWithInvalidDouble = new PeeGeeQConfiguration(TEST_PROFILE, invalidDoubleProps);
        PeeGeeQConfiguration.CircuitBreakerConfig cbConfigWithInvalid = configWithInvalidDouble.getCircuitBreakerConfig();

        // Should use default value from the implementation (50.0) because the override is not a valid double
        assertEquals(50.0, cbConfigWithInvalid.getFailureRateThreshold(), 0.001);
    }

    @Test
    void testGetDatabaseConfig() {
        PeeGeeQConfiguration config = new PeeGeeQConfiguration(TEST_PROFILE, new Properties());
        PgConnectionConfig dbConfig = config.getDatabaseConfig();

        assertNotNull(dbConfig);
        assertEquals("test-host", dbConfig.getHost());
        assertEquals(5433, dbConfig.getPort());
        assertEquals("test-db", dbConfig.getDatabase());
        assertEquals("test-user", dbConfig.getUsername());
        assertEquals("test-password", dbConfig.getPassword());
        assertEquals("peegeeq_test", dbConfig.getSchema());
        assertTrue(dbConfig.isSslEnabled());
    }

    @Test
    void testGetPoolConfig() {
        PeeGeeQConfiguration config = new PeeGeeQConfiguration(TEST_PROFILE, new Properties());
        PgPoolConfig poolConfig = config.getPoolConfig();

        assertNotNull(poolConfig);
        assertEquals(3, poolConfig.getMaxSize());
        assertEquals(java.time.Duration.ofMillis(5000), poolConfig.getConnectionTimeout());
        assertEquals(java.time.Duration.ofMillis(2000), poolConfig.getIdleTimeout());
    }

    @Test
    void testGetQueueConfig() {
        PeeGeeQConfiguration config = new PeeGeeQConfiguration(TEST_PROFILE, new Properties());
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
        PeeGeeQConfiguration config = new PeeGeeQConfiguration(TEST_PROFILE, new Properties());
        PeeGeeQConfiguration.MetricsConfig metricsConfig = config.getMetricsConfig();

        assertNotNull(metricsConfig);
        assertTrue(metricsConfig.isEnabled());
        assertEquals(Duration.ofSeconds(30), metricsConfig.getReportingInterval());
        assertEquals("test-instance", metricsConfig.getInstanceId());
    }

    @Test
    void testGetCircuitBreakerConfig() {
        PeeGeeQConfiguration config = new PeeGeeQConfiguration(TEST_PROFILE, new Properties());
        PeeGeeQConfiguration.CircuitBreakerConfig cbConfig = config.getCircuitBreakerConfig();

        assertNotNull(cbConfig);
        assertTrue(cbConfig.isEnabled());
        assertEquals(10, cbConfig.getFailureThreshold());
        assertEquals(Duration.ofMinutes(2), cbConfig.getWaitDuration());
        assertEquals(50, cbConfig.getRingBufferSize());
        assertEquals(40.0, cbConfig.getFailureRateThreshold(), 0.001);
    }

    @Test
    void canonicalCircuitBreakerPropertiesTakePrecedenceOverHistoricalAliases() {
        Properties overrides = new Properties();
        overrides.setProperty("peegeeq.circuit-breaker.minimum-number-of-calls", "7");
        overrides.setProperty("peegeeq.circuit-breaker.failure-threshold", "2");
        overrides.setProperty("peegeeq.circuit-breaker.sliding-window-size", "41");
        overrides.setProperty("peegeeq.circuit-breaker.ring-buffer-size", "11");
        overrides.setProperty("peegeeq.circuit-breaker.wait-duration-in-open-state", "PT17S");
        overrides.setProperty("peegeeq.circuit-breaker.wait-duration", "PT3S");

        PeeGeeQConfiguration.CircuitBreakerConfig cbConfig =
            new PeeGeeQConfiguration(TEST_PROFILE, overrides).getCircuitBreakerConfig();

        assertEquals(7, cbConfig.getFailureThreshold());
        assertEquals(41, cbConfig.getRingBufferSize());
        assertEquals(Duration.ofSeconds(17), cbConfig.getWaitDuration());
    }

    @Test
    void canonicalHealthPropertiesTakePrecedenceOverHistoricalAliases() {
        Properties overrides = new Properties();
        overrides.setProperty("peegeeq.health.enabled", "false");
        overrides.setProperty("peegeeq.health-check.enabled", "true");
        overrides.setProperty("peegeeq.health.queue-checks-enabled", "false");
        overrides.setProperty("peegeeq.health-check.queue-checks-enabled", "true");
        overrides.setProperty("peegeeq.health.check-interval", "PT17S");
        overrides.setProperty("peegeeq.health-check.interval", "PT3S");
        overrides.setProperty("peegeeq.health.timeout", "PT4S");
        overrides.setProperty("peegeeq.health-check.timeout", "PT1S");

        PeeGeeQConfiguration.HealthCheckConfig healthConfig =
            new PeeGeeQConfiguration(TEST_PROFILE, overrides).getHealthCheckConfig();

        assertFalse(healthConfig.isEnabled());
        assertFalse(healthConfig.isQueueChecksEnabled());
        assertEquals(Duration.ofSeconds(17), healthConfig.getInterval());
        assertEquals(Duration.ofSeconds(4), healthConfig.getTimeout());
    }

    @Test
    void testValidationSuccess() {
        // This should not throw an exception with valid test properties
        assertDoesNotThrow(() -> new PeeGeeQConfiguration(TEST_PROFILE, new Properties()));
    }

    @Test
    void testValidationFailure() {
        // Create invalid configuration and pass directly via 2-arg constructor
        // (System properties are no longer swept; overrides must be explicit)
        Properties invalidProps = new Properties();
        invalidProps.setProperty("peegeeq.database.host", ""); // Empty host (invalid)
        invalidProps.setProperty("peegeeq.database.port", "70000"); // Invalid port range
        invalidProps.setProperty("peegeeq.database.name", ""); // Empty name (invalid)
        invalidProps.setProperty("peegeeq.database.username", ""); // Empty username (invalid)
        // This should throw an IllegalStateException due to validation failures
        Exception exception = assertThrows(IllegalStateException.class,
            () -> new PeeGeeQConfiguration(TEST_PROFILE, invalidProps));

        // Verify the exception message contains expected validation errors
        String exceptionMessage = exception.getMessage();
        assertTrue(exceptionMessage.contains("Database host is required") ||
                   exceptionMessage.contains("Database name is required") ||
                   exceptionMessage.contains("Database username is required") ||
                   exceptionMessage.contains("Database port must be between 1 and 65535"),
                   "Should contain at least one validation error");
    }

    @Test
    void testValidationFailureForPoolTimeouts() {
        // Pass invalid pool timeouts via 2-arg constructor (System properties are no longer swept)
        Properties timeoutProps = new Properties();
        timeoutProps.setProperty("peegeeq.database.pool.connection-timeout-ms", "0");
        timeoutProps.setProperty("peegeeq.database.pool.idle-timeout-ms", "-1");

        Exception exception = assertThrows(IllegalStateException.class,
            () -> new PeeGeeQConfiguration(TEST_PROFILE, timeoutProps));

        String exceptionMessage = exception.getMessage();
        assertTrue(exceptionMessage.contains("Connection timeout must be greater than 0ms"));
        assertTrue(exceptionMessage.contains("Idle timeout must be greater than or equal to 0ms"));
    }

    @Test
    void testSystemPropertyNotPickedUpByConstructor() {
        // Phase 11: the System.getProperties() sweep has been removed from loadProperties().
        // Setting a peegeeq.* System property must NOT affect a newly constructed instance.
        System.setProperty("peegeeq.database.host", "system-override-host");
        try {
            PeeGeeQConfiguration config = new PeeGeeQConfiguration(TEST_PROFILE, new Properties());

            // Must return the value from the test profile file, not the System property
            assertEquals("test-host", config.getString("peegeeq.database.host"),
                "System property must not contaminate PeeGeeQConfiguration instances");
        } finally {
            System.clearProperty("peegeeq.database.host");
        }
    }

    @Test
    void testEnvironmentVariableHandling() {
        // We can't set environment variables in Java, so we'll test the method that processes them
        // This is a more limited test that verifies the property key transformation logic

        // Create a configuration and verify a property that would come from an environment variable
        // For example, if PEEGEEQ_DATABASE_HOST were set, it would be transformed to peegeeq.database.host

        // We can only verify this indirectly by checking that the default property was loaded
        PeeGeeQConfiguration config = new PeeGeeQConfiguration(TEST_PROFILE, new Properties());
        assertEquals("test-host", config.getString("peegeeq.database.host"));
    }

    @Test
    void testPlaceholderResolutionWithDefault() {
        // ${UNSET_VAR:my-default} should resolve to "my-default" when the env var is not set
        Properties overrides = new Properties();
        overrides.setProperty("peegeeq.database.host",     "${PEEGEEQ_TEST_UNSET_HOST:placeholder-host}");
        overrides.setProperty("peegeeq.database.port",     "5433");
        overrides.setProperty("peegeeq.database.name",     "${PEEGEEQ_TEST_UNSET_DB:placeholder-db}");
        overrides.setProperty("peegeeq.database.username", "${PEEGEEQ_TEST_UNSET_USER:placeholder-user}");
        overrides.setProperty("peegeeq.database.password", "${PEEGEEQ_TEST_UNSET_PWD:s3cr3t}");
        overrides.setProperty("peegeeq.database.schema",   "${PEEGEEQ_TEST_UNSET_SCHEMA:placeholder-schema}");

        PeeGeeQConfiguration config = new PeeGeeQConfiguration(TEST_PROFILE, overrides);

        assertEquals("placeholder-host",   config.getString("peegeeq.database.host"));
        assertEquals("placeholder-db",     config.getString("peegeeq.database.name"));
        assertEquals("placeholder-user",   config.getString("peegeeq.database.username"));
        assertEquals("s3cr3t",             config.getString("peegeeq.database.password"));
        assertEquals("placeholder-schema", config.getString("peegeeq.database.schema"));
    }

    @Test
    void testPlaceholderResolutionFailsForUnknownVarWithoutDefault() {
        // Inverted contract: ${UNSET_VAR} with no default must FAIL construction —
        // the previous behavior (keep the literal string and warn) was silent corruption
        Properties overrides = new Properties();
        overrides.setProperty("peegeeq.database.host",     "test-host"); // keep valid
        overrides.setProperty("peegeeq.test.placeholder",  "${PEEGEEQ_TEST_UNSET_NO_DEFAULT}");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> new PeeGeeQConfiguration(TEST_PROFILE, overrides));
        assertTrue(ex.getMessage().contains("PEEGEEQ_TEST_UNSET_NO_DEFAULT"),
            "The error must name the missing variable, got: " + ex.getMessage());
    }
}
