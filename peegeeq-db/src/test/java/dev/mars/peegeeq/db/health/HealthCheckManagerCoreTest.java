package dev.mars.peegeeq.db.health;

import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CORE tests for HealthCheckManager using TestContainers.
 *
 * <p>These tests are tagged as CORE because they:
 * <ul>
 *   <li>Run fast (each test completes in <1 second)</li>
 *   <li>Are isolated (each test focuses on a single method)</li>
 *   <li>Test one component at a time (HealthCheckManager only)</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-27
 * @version 1.0
 */
@Tag(TestCategories.CORE)
@Execution(ExecutionMode.SAME_THREAD)
public class HealthCheckManagerCoreTest extends BaseIntegrationTest {

    private PgConnectionManager connectionManager;
    private Pool reactivePool;
    private HealthCheckManager healthCheckManager;

    @BeforeEach
    void setUp() throws Exception {
        // Create connection manager using the shared Vertx instance
        connectionManager = new PgConnectionManager(manager.getVertx(), null);

        // Get PostgreSQL container and create pool
        PostgreSQLContainer<?> postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema("public")
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
            .maxSize(10)
            .build();

        reactivePool = connectionManager.getOrCreateReactivePool("peegeeq-main", connectionConfig, poolConfig);

        // Create health check manager with queue health checks disabled
        // (queue tables don't exist in the test schema)
        healthCheckManager = new HealthCheckManager(
            reactivePool,
            Duration.ofSeconds(5),
            Duration.ofSeconds(2),
            false  // Disable queue health checks
        );
    }

    @AfterEach
    void tearDown() throws Exception {
        if (healthCheckManager != null && healthCheckManager.isRunning()) {
            healthCheckManager.stop();
        }
        if (connectionManager != null) {
            connectionManager.close();
        }
    }

    @Test
    void testHealthCheckManagerCreation() {
        assertNotNull(healthCheckManager);
        assertFalse(healthCheckManager.isRunning());
    }

    @Test
    void testStartAndStop() throws Exception {
        // Start health check manager
        healthCheckManager.startReactive()
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        assertTrue(healthCheckManager.isRunning());

        // Stop health check manager
        healthCheckManager.stop();
        assertFalse(healthCheckManager.isRunning());
    }

    @Test
    void testDatabaseHealthCheck() throws Exception {
        // Start health check manager
        healthCheckManager.startReactive()
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        // Wait for health checks to run
        Thread.sleep(500);

        // Get database health status
        HealthStatus dbHealth = healthCheckManager.getHealthStatus("database");
        assertNotNull(dbHealth);
        assertEquals("database", dbHealth.getComponent());
        assertTrue(dbHealth.isHealthy(), "Database should be healthy");
    }

    @Test
    void testMemoryHealthCheck() throws Exception {
        // Start health check manager
        healthCheckManager.startReactive()
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        // Wait for health checks to run
        Thread.sleep(500);

        // Get memory health status
        HealthStatus memoryHealth = healthCheckManager.getHealthStatus("memory");
        assertNotNull(memoryHealth);
        assertEquals("memory", memoryHealth.getComponent());
        // Memory should be healthy in test environment
        assertTrue(memoryHealth.isHealthy() || memoryHealth.isDegraded());
        assertNotNull(memoryHealth.getDetails());
        assertTrue(memoryHealth.getDetails().containsKey("max_memory_mb"));
        assertTrue(memoryHealth.getDetails().containsKey("used_memory_mb"));
        assertTrue(memoryHealth.getDetails().containsKey("memory_usage_percent"));
    }

    @Test
    void testDiskSpaceHealthCheck() throws Exception {
        // Start health check manager
        healthCheckManager.startReactive()
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        // Wait for health checks to run
        Thread.sleep(500);

        // Get disk space health status
        HealthStatus diskHealth = healthCheckManager.getHealthStatus("disk-space");
        assertNotNull(diskHealth);
        assertEquals("disk-space", diskHealth.getComponent());
        // Disk space should be healthy in test environment
        assertTrue(diskHealth.isHealthy() || diskHealth.isDegraded());
        assertNotNull(diskHealth.getDetails());
        assertTrue(diskHealth.getDetails().containsKey("total_space_gb"));
        assertTrue(diskHealth.getDetails().containsKey("free_space_gb"));
        assertTrue(diskHealth.getDetails().containsKey("disk_usage_percent"));
    }

    @Test
    void testOverallHealthStatus() throws Exception {
        // Start health check manager
        healthCheckManager.startReactive()
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        // Wait for health checks to run
        Thread.sleep(500);

        // Get overall health status
        OverallHealthStatus overallHealth = healthCheckManager.getOverallHealth();
        assertNotNull(overallHealth);
        assertEquals("UP", overallHealth.getStatus());
        assertNotNull(overallHealth.getComponents());
        assertTrue(overallHealth.getComponents().containsKey("database"));
        assertTrue(overallHealth.getComponents().containsKey("memory"));
        assertTrue(overallHealth.getComponents().containsKey("disk-space"));
        assertNotNull(overallHealth.getTimestamp());
    }

    @Test
    void testIsHealthy() throws Exception {
        // Start health check manager
        healthCheckManager.startReactive()
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        // Wait for health checks to run
        Thread.sleep(500);

        // Check if overall system is healthy
        assertTrue(healthCheckManager.isHealthy());
    }

    @Test
    void testRegisterCustomHealthCheck() throws Exception {
        // Register a custom health check
        HealthCheck customCheck = () -> HealthStatus.healthy("custom-check");
        healthCheckManager.registerHealthCheck("custom-check", customCheck);

        // Start health check manager
        healthCheckManager.startReactive()
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        // Wait for health checks to run
        Thread.sleep(500);

        // Verify custom health check is registered and running
        HealthStatus customHealth = healthCheckManager.getHealthStatus("custom-check");
        assertNotNull(customHealth);
        assertEquals("custom-check", customHealth.getComponent());
        assertTrue(customHealth.isHealthy());
    }

    @Test
    void testQueueHealthChecksDisabled() throws Exception {
        // Start health check manager (queue health checks disabled in setUp)
        healthCheckManager.startReactive()
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        // Wait for health checks to run
        Thread.sleep(500);

        // Verify queue health checks are NOT registered
        assertNull(healthCheckManager.getHealthStatus("outbox-queue"));
        assertNull(healthCheckManager.getHealthStatus("native-queue"));
        assertNull(healthCheckManager.getHealthStatus("dead-letter-queue"));
    }

    @Test
    void testQueueHealthChecksEnabled() throws Exception {
        // Create a new health check manager with queue health checks enabled
        HealthCheckManager queueEnabledManager = new HealthCheckManager(
            reactivePool,
            Duration.ofSeconds(5),
            Duration.ofSeconds(2),
            true  // Enable queue health checks
        );

        try {
            // Start health check manager
            queueEnabledManager.startReactive()
                .toCompletionStage()
                .toCompletableFuture()
                .get();

            // Wait for health checks to run
            Thread.sleep(500);

            // Verify queue health checks ARE registered
            HealthStatus outboxHealth = queueEnabledManager.getHealthStatus("outbox-queue");
            HealthStatus nativeHealth = queueEnabledManager.getHealthStatus("native-queue");
            HealthStatus dlqHealth = queueEnabledManager.getHealthStatus("dead-letter-queue");

            assertNotNull(outboxHealth);
            assertNotNull(nativeHealth);
            assertNotNull(dlqHealth);

            // These should be healthy because the tables exist in the shared PostgreSQL container
            assertTrue(outboxHealth.isHealthy(), "Outbox queue should be healthy: " + outboxHealth.getMessage());
            assertTrue(nativeHealth.isHealthy(), "Native queue should be healthy: " + nativeHealth.getMessage());
            assertTrue(dlqHealth.isHealthy(), "Dead letter queue should be healthy: " + dlqHealth.getMessage());

        } finally {
            if (queueEnabledManager.isRunning()) {
                queueEnabledManager.stop();
            }
        }
    }

    @Test
    void testStartWhenAlreadyRunning() throws Exception {
        // Start health check manager
        healthCheckManager.startReactive()
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        assertTrue(healthCheckManager.isRunning());

        // Try to start again - should log warning but not fail
        healthCheckManager.startReactive()
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        assertTrue(healthCheckManager.isRunning());
    }

    @Test
    void testStopWhenNotRunning() {
        // Stop when not running - should not throw exception
        assertDoesNotThrow(() -> healthCheckManager.stop());
        assertFalse(healthCheckManager.isRunning());
    }
}

