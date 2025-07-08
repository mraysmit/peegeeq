package dev.mars.peegeeq.db.health;

import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.migration.SchemaMigrationManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for HealthCheckManager and related health check components.
 */
@Testcontainers
class HealthCheckManagerTest {

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("health_test")
            .withUsername("test_user")
            .withPassword("test_pass");

    private PgConnectionManager connectionManager;
    private DataSource dataSource;
    private HealthCheckManager healthCheckManager;

    @BeforeEach
    void setUp() throws SQLException {
        connectionManager = new PgConnectionManager();
        
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .database(postgres.getDatabaseName())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
                .minimumIdle(2)
                .maximumPoolSize(5)
                .build();

        dataSource = connectionManager.getOrCreateDataSource("test", connectionConfig, poolConfig);
        
        // Apply migrations to create necessary tables
        SchemaMigrationManager migrationManager = new SchemaMigrationManager(dataSource);
        migrationManager.migrate();
        
        healthCheckManager = new HealthCheckManager(dataSource, Duration.ofSeconds(5), Duration.ofSeconds(3));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (healthCheckManager != null) {
            healthCheckManager.stop();
        }
        if (connectionManager != null) {
            connectionManager.close();
        }
    }

    @Test
    void testHealthCheckManagerInitialization() {
        assertNotNull(healthCheckManager);
        assertFalse(healthCheckManager.isHealthy()); // No checks run yet
    }

    @Test
    void testHealthCheckManagerStartStop() {
        assertDoesNotThrow(() -> healthCheckManager.start());
        
        // Wait a moment for health checks to run
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        assertTrue(healthCheckManager.isHealthy());
        
        assertDoesNotThrow(() -> healthCheckManager.stop());
    }

    @Test
    void testOverallHealthStatus() {
        healthCheckManager.start();
        
        // Wait for health checks to run
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        OverallHealthStatus status = healthCheckManager.getOverallHealth();
        assertNotNull(status);
        assertEquals("UP", status.getStatus());
        assertTrue(status.isHealthy());
        assertFalse(status.getComponents().isEmpty());
        
        // Verify specific health checks are present
        assertTrue(status.getComponents().containsKey("database"));
        assertTrue(status.getComponents().containsKey("outbox-queue"));
        assertTrue(status.getComponents().containsKey("native-queue"));
        assertTrue(status.getComponents().containsKey("dead-letter-queue"));
        assertTrue(status.getComponents().containsKey("memory"));
        assertTrue(status.getComponents().containsKey("disk-space"));
    }

    @Test
    void testDatabaseHealthCheck() {
        healthCheckManager.start();
        
        // Wait for health checks to run
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        HealthStatus dbHealth = healthCheckManager.getHealthStatus("database");
        assertNotNull(dbHealth);
        assertTrue(dbHealth.isHealthy());
        assertEquals("database", dbHealth.getComponent());
        assertEquals(HealthStatus.Status.HEALTHY, dbHealth.getStatus());
    }

    @Test
    void testQueueHealthChecks() throws SQLException {
        // Insert test data for queue health checks
        insertTestData();
        
        healthCheckManager.start();
        
        // Wait for health checks to run
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Test outbox queue health
        HealthStatus outboxHealth = healthCheckManager.getHealthStatus("outbox-queue");
        assertNotNull(outboxHealth);
        assertTrue(outboxHealth.isHealthy());
        assertNotNull(outboxHealth.getDetails());
        assertTrue(outboxHealth.getDetails().containsKey("pending_messages"));
        
        // Test native queue health
        HealthStatus nativeHealth = healthCheckManager.getHealthStatus("native-queue");
        assertNotNull(nativeHealth);
        assertTrue(nativeHealth.isHealthy());
        assertNotNull(nativeHealth.getDetails());
        assertTrue(nativeHealth.getDetails().containsKey("available_messages"));
        
        // Test dead letter queue health
        HealthStatus dlqHealth = healthCheckManager.getHealthStatus("dead-letter-queue");
        assertNotNull(dlqHealth);
        assertTrue(dlqHealth.isHealthy());
        assertNotNull(dlqHealth.getDetails());
        assertTrue(dlqHealth.getDetails().containsKey("recent_failures"));
    }

    @Test
    void testMemoryHealthCheck() {
        healthCheckManager.start();
        
        // Wait for health checks to run
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        HealthStatus memoryHealth = healthCheckManager.getHealthStatus("memory");
        assertNotNull(memoryHealth);
        assertTrue(memoryHealth.isHealthy() || memoryHealth.isDegraded()); // Could be degraded under load
        assertNotNull(memoryHealth.getDetails());
        assertTrue(memoryHealth.getDetails().containsKey("max_memory_mb"));
        assertTrue(memoryHealth.getDetails().containsKey("used_memory_mb"));
        assertTrue(memoryHealth.getDetails().containsKey("memory_usage_percent"));
    }

    @Test
    void testDiskSpaceHealthCheck() {
        healthCheckManager.start();
        
        // Wait for health checks to run
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        HealthStatus diskHealth = healthCheckManager.getHealthStatus("disk-space");
        assertNotNull(diskHealth);
        assertTrue(diskHealth.isHealthy() || diskHealth.isDegraded()); // Could be degraded if disk is full
        assertNotNull(diskHealth.getDetails());
        assertTrue(diskHealth.getDetails().containsKey("total_space_gb"));
        assertTrue(diskHealth.getDetails().containsKey("free_space_gb"));
        assertTrue(diskHealth.getDetails().containsKey("disk_usage_percent"));
    }

    @Test
    void testCustomHealthCheck() {
        AtomicBoolean customCheckCalled = new AtomicBoolean(false);
        
        HealthCheck customCheck = () -> {
            customCheckCalled.set(true);
            return HealthStatus.healthy("custom-check");
        };
        
        healthCheckManager.registerHealthCheck("custom", customCheck);
        healthCheckManager.start();
        
        // Wait for health checks to run
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        assertTrue(customCheckCalled.get());
        
        HealthStatus customHealth = healthCheckManager.getHealthStatus("custom");
        assertNotNull(customHealth);
        assertTrue(customHealth.isHealthy());
        assertEquals("custom-check", customHealth.getComponent());
    }

    @Test
    void testFailingHealthCheck() {
        HealthCheck failingCheck = () -> {
            throw new RuntimeException("Simulated failure");
        };
        
        healthCheckManager.registerHealthCheck("failing", failingCheck);
        healthCheckManager.start();
        
        // Wait for health checks to run
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        HealthStatus failingHealth = healthCheckManager.getHealthStatus("failing");
        assertNotNull(failingHealth);
        assertFalse(failingHealth.isHealthy());
        assertTrue(failingHealth.isUnhealthy());
        assertNotNull(failingHealth.getMessage());
        assertTrue(failingHealth.getMessage().contains("Health check threw exception"));
    }

    @Test
    void testHealthCheckTimeout() {
        HealthCheck slowCheck = () -> {
            try {
                Thread.sleep(5000); // Longer than timeout
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return HealthStatus.healthy("slow-check");
        };
        
        healthCheckManager.registerHealthCheck("slow", slowCheck);
        healthCheckManager.start();
        
        // Wait for health checks to run and timeout
        try {
            Thread.sleep(4000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        HealthStatus slowHealth = healthCheckManager.getHealthStatus("slow");
        assertNotNull(slowHealth);
        assertFalse(slowHealth.isHealthy());
        assertTrue(slowHealth.getMessage().contains("timed out"));
    }

    @Test
    void testHealthCheckWithDatabaseFailure() throws Exception {
        healthCheckManager.start();
        
        // Wait for initial healthy state
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        assertTrue(healthCheckManager.isHealthy());
        
        // Close database connection to simulate failure
        connectionManager.close();
        
        // Wait for health checks to detect failure
        try {
            Thread.sleep(6000); // Wait longer than check interval
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        assertFalse(healthCheckManager.isHealthy());
        
        HealthStatus dbHealth = healthCheckManager.getHealthStatus("database");
        assertNotNull(dbHealth);
        assertFalse(dbHealth.isHealthy());
    }

    @Test
    void testConcurrentHealthChecks() throws InterruptedException {
        AtomicReference<Exception> exception = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(5);
        
        // Register multiple health checks that run concurrently
        for (int i = 0; i < 5; i++) {
            final int checkId = i;
            healthCheckManager.registerHealthCheck("concurrent-" + i, () -> {
                try {
                    Thread.sleep(100); // Simulate some work
                    latch.countDown();
                    return HealthStatus.healthy("concurrent-" + checkId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    exception.set(e);
                    return HealthStatus.unhealthy("concurrent-" + checkId, "Interrupted");
                }
            });
        }
        
        healthCheckManager.start();
        
        // Wait for all health checks to complete
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertNull(exception.get());
        
        // Verify all health checks are healthy
        for (int i = 0; i < 5; i++) {
            HealthStatus health = healthCheckManager.getHealthStatus("concurrent-" + i);
            assertNotNull(health);
            assertTrue(health.isHealthy());
        }
    }

    @Test
    void testHealthStatusEquality() {
        HealthStatus status1 = HealthStatus.healthy("test");
        HealthStatus status2 = HealthStatus.healthy("test");
        HealthStatus status3 = HealthStatus.unhealthy("test", "error");
        
        assertEquals(status1, status2);
        assertNotEquals(status1, status3);
        assertEquals(status1.hashCode(), status2.hashCode());
        assertNotEquals(status1.hashCode(), status3.hashCode());
    }

    @Test
    void testHealthStatusToString() {
        HealthStatus healthyStatus = HealthStatus.healthy("test");
        HealthStatus unhealthyStatus = HealthStatus.unhealthy("test", "error message");
        HealthStatus degradedStatus = HealthStatus.degraded("test", "warning message");
        
        String healthyString = healthyStatus.toString();
        String unhealthyString = unhealthyStatus.toString();
        String degradedString = degradedStatus.toString();
        
        assertTrue(healthyString.contains("test"));
        assertTrue(healthyString.contains("HEALTHY"));
        
        assertTrue(unhealthyString.contains("test"));
        assertTrue(unhealthyString.contains("UNHEALTHY"));
        assertTrue(unhealthyString.contains("error message"));
        
        assertTrue(degradedString.contains("test"));
        assertTrue(degradedString.contains("DEGRADED"));
        assertTrue(degradedString.contains("warning message"));
    }

    @Test
    void testOverallHealthStatusCounts() throws SQLException {
        // Insert data that might cause some health checks to be degraded
        insertLargeAmountOfTestData();
        
        healthCheckManager.start();
        
        // Wait for health checks to run
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        OverallHealthStatus status = healthCheckManager.getOverallHealth();
        
        long totalComponents = status.getHealthyCount() + status.getDegradedCount() + status.getUnhealthyCount();
        assertTrue(totalComponents > 0);
        assertEquals(status.getComponents().size(), totalComponents);
    }

    private void insertTestData() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            // Insert outbox message
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO outbox (topic, payload, status) VALUES (?, ?::jsonb, ?)")) {
                stmt.setString(1, "test-topic");
                stmt.setString(2, "{\"test\": \"data\"}");
                stmt.setString(3, "PENDING");
                stmt.executeUpdate();
            }
            
            // Insert queue message
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO queue_messages (topic, payload, status) VALUES (?, ?::jsonb, ?)")) {
                stmt.setString(1, "test-topic");
                stmt.setString(2, "{\"test\": \"data\"}");
                stmt.setString(3, "AVAILABLE");
                stmt.executeUpdate();
            }
            
            // Insert dead letter message
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO dead_letter_queue (original_table, original_id, topic, payload, original_created_at, failure_reason, retry_count) VALUES (?, ?, ?, ?::jsonb, ?, ?, ?)")) {
                stmt.setString(1, "outbox");
                stmt.setLong(2, 1);
                stmt.setString(3, "test-topic");
                stmt.setString(4, "{\"test\": \"data\"}");
                stmt.setTimestamp(5, new java.sql.Timestamp(System.currentTimeMillis()));
                stmt.setString(6, "test failure");
                stmt.setInt(7, 3);
                stmt.executeUpdate();
            }
        }
    }

    private void insertLargeAmountOfTestData() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            // Insert many outbox messages to potentially trigger degraded state
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO outbox (topic, payload, status) VALUES (?, ?::jsonb, ?)")) {
                for (int i = 0; i < 100; i++) {
                    stmt.setString(1, "test-topic-" + i);
                    stmt.setString(2, "{\"test\": \"data\", \"id\": " + i + "}");
                    stmt.setString(3, "PENDING");
                    stmt.addBatch();
                }
                stmt.executeBatch();
            }
        }
    }
}
