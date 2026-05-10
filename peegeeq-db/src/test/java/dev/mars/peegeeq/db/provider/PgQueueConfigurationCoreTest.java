package dev.mars.peegeeq.db.provider;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 */

import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CORE tests for PgQueueConfiguration.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-27
 * @version 1.0
 */
@Tag(TestCategories.CORE)
@Execution(ExecutionMode.SAME_THREAD)
public class PgQueueConfigurationCoreTest {

    private PeeGeeQConfiguration peeGeeQConfig;

    @BeforeEach
    void setUp() {
        Properties testProps = new Properties();
        testProps.setProperty("peegeeq.database.host", "localhost");
        testProps.setProperty("peegeeq.database.port", "5432");
        testProps.setProperty("peegeeq.database.name", "testdb");
        testProps.setProperty("peegeeq.database.username", "testuser");
        testProps.setProperty("peegeeq.database.password", "testpass");
        testProps.setProperty("peegeeq.database.pool.max-size", "32");
        testProps.setProperty("peegeeq.database.pool.connection-timeout-ms", "30000");
        testProps.setProperty("peegeeq.database.pool.idle-timeout-ms", "600000");
        testProps.setProperty("peegeeq.metrics.instance-id", "test-instance");
        testProps.setProperty("peegeeq.metrics.enabled", "true");
        testProps.setProperty("peegeeq.health.enabled", "true");
        testProps.setProperty("peegeeq.health.check-interval", "PT1M");
        testProps.setProperty("peegeeq.migration.enabled", "false");

        peeGeeQConfig = new PeeGeeQConfiguration("test-pgqueue-config", testProps);
    }

    @AfterEach
    void tearDown() {
        // No System properties to clean up
    }

    @Test
    void testPgQueueConfigurationCreation() {
        PgQueueConfiguration config = new PgQueueConfiguration(peeGeeQConfig);
        assertNotNull(config);
    }

    @Test
    void testPgQueueConfigurationCreationWithAdditionalProperties() {
        Map<String, Object> additionalProps = new HashMap<>();
        additionalProps.put("custom.key", "custom.value");

        PgQueueConfiguration config = new PgQueueConfiguration(peeGeeQConfig, additionalProps);
        assertNotNull(config);
        assertEquals("custom.value", config.getProperty("custom.key"));
    }

    @Test
    void testGetDatabaseUrl() {
        PgQueueConfiguration config = new PgQueueConfiguration(peeGeeQConfig);
        String url = config.getDatabaseUrl();
        assertNotNull(url);
        assertTrue(url.contains("jdbc:postgresql://"));
    }

    @Test
    void testGetUsername() {
        PgQueueConfiguration config = new PgQueueConfiguration(peeGeeQConfig);
        assertEquals("testuser", config.getUsername());
    }

    @Test
    void testGetPassword() {
        PgQueueConfiguration config = new PgQueueConfiguration(peeGeeQConfig);
        assertEquals("testpass", config.getPassword());
    }

    @Test
    void testGetMaxPoolSize() {
        PgQueueConfiguration config = new PgQueueConfiguration(peeGeeQConfig);
        // The default max pool size is 32
        assertEquals(32, config.getMaxPoolSize());
    }

    @Test
    void testGetMinPoolSize() {
        PgQueueConfiguration config = new PgQueueConfiguration(peeGeeQConfig);
        // Vert.x reactive pools do not use minimum idle
        assertEquals(0, config.getMinPoolSize());
    }

    @Test
    void testGetConnectionTimeout() {
        PgQueueConfiguration config = new PgQueueConfiguration(peeGeeQConfig);
        assertEquals(Duration.ofSeconds(30), config.getConnectionTimeout());
    }

    @Test
    void testGetIdleTimeout() {
        PgQueueConfiguration config = new PgQueueConfiguration(peeGeeQConfig);
        assertEquals(Duration.ofMinutes(10), config.getIdleTimeout());
    }

    @Test
    void testGetMaxLifetime() {
        PgQueueConfiguration config = new PgQueueConfiguration(peeGeeQConfig);
        // Not applicable in Vert.x reactive pools
        assertEquals(Duration.ZERO, config.getMaxLifetime());
    }

    @Test
    void testGetInstanceId() {
        PgQueueConfiguration config = new PgQueueConfiguration(peeGeeQConfig);
        assertEquals("test-instance", config.getInstanceId());
    }

    @Test
    void testIsMetricsEnabled() {
        PgQueueConfiguration config = new PgQueueConfiguration(peeGeeQConfig);
        assertTrue(config.isMetricsEnabled());
    }

    @Test
    void testIsHealthChecksEnabled() {
        PgQueueConfiguration config = new PgQueueConfiguration(peeGeeQConfig);
        assertTrue(config.isHealthChecksEnabled());
    }

    @Test
    void testGetHealthCheckInterval() {
        PgQueueConfiguration config = new PgQueueConfiguration(peeGeeQConfig);
        assertEquals(Duration.ofMinutes(1), config.getHealthCheckInterval());
    }

    @Test
    void testIsAutoMigrationEnabled() {
        PgQueueConfiguration config = new PgQueueConfiguration(peeGeeQConfig);
        assertFalse(config.isAutoMigrationEnabled());
    }

    @Test
    void testGetAdditionalProperties() {
        Map<String, Object> additionalProps = new HashMap<>();
        additionalProps.put("key1", "value1");
        additionalProps.put("key2", 42);

        PgQueueConfiguration config = new PgQueueConfiguration(peeGeeQConfig, additionalProps);
        Map<String, Object> retrieved = config.getAdditionalProperties();

        assertEquals(2, retrieved.size());
        assertEquals("value1", retrieved.get("key1"));
        assertEquals(42, retrieved.get("key2"));
    }

    @Test
    void testGetProperty() {
        Map<String, Object> additionalProps = new HashMap<>();
        additionalProps.put("test.key", "test.value");

        PgQueueConfiguration config = new PgQueueConfiguration(peeGeeQConfig, additionalProps);
        assertEquals("test.value", config.getProperty("test.key"));
    }

    @Test
    void testSetProperty() {
        PgQueueConfiguration config = new PgQueueConfiguration(peeGeeQConfig);
        config.setProperty("new.key", "new.value");
        assertEquals("new.value", config.getProperty("new.key"));
    }

    @Test
    void testGetUnderlyingConfiguration() {
        PgQueueConfiguration config = new PgQueueConfiguration(peeGeeQConfig);
        assertSame(peeGeeQConfig, config.getUnderlyingConfiguration());
    }
}


