package dev.mars.peegeeq.db;

import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.deadletter.DeadLetterQueueStats;
import dev.mars.peegeeq.db.health.OverallHealthStatus;
import dev.mars.peegeeq.db.metrics.PeeGeeQMetrics;
import dev.mars.peegeeq.db.resilience.BackpressureManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for PeeGeeQManager with all production readiness features.
 */
@Testcontainers
public class PeeGeeQManagerIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_test")
            .withUsername("peegeeq_test")
            .withPassword("peegeeq_test");

    private PeeGeeQManager manager;
    private PeeGeeQConfiguration configuration;

    @BeforeEach
    void setUp() {
        // Create test configuration
        Properties testProps = new Properties();
        testProps.setProperty("peegeeq.database.host", postgres.getHost());
        testProps.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        testProps.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        testProps.setProperty("peegeeq.database.username", postgres.getUsername());
        testProps.setProperty("peegeeq.database.password", postgres.getPassword());
        testProps.setProperty("peegeeq.database.ssl.enabled", "false");
        
        // Reduce timeouts for faster tests
        testProps.setProperty("peegeeq.health.check-interval", "PT5S");
        testProps.setProperty("peegeeq.metrics.reporting-interval", "PT10S");
        testProps.setProperty("peegeeq.circuit-breaker.enabled", "true");
        testProps.setProperty("peegeeq.migration.enabled", "true");
        testProps.setProperty("peegeeq.migration.auto-migrate", "true");

        // Override system properties for test
        testProps.forEach((key, value) -> System.setProperty(key.toString(), value.toString()));
        
        configuration = new PeeGeeQConfiguration("test");
        manager = new PeeGeeQManager(configuration, new SimpleMeterRegistry());
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close();
        }
        
        // Clean up system properties
        System.getProperties().entrySet().removeIf(entry -> 
            entry.getKey().toString().startsWith("peegeeq."));
    }

    @Test
    void testManagerInitialization() {
        assertNotNull(manager);
        assertNotNull(manager.getConfiguration());
        assertNotNull(manager.getDataSource());
        assertNotNull(manager.getHealthCheckManager());
        assertNotNull(manager.getMetrics());
        assertNotNull(manager.getCircuitBreakerManager());
        assertNotNull(manager.getBackpressureManager());
        assertNotNull(manager.getDeadLetterQueueManager());
    }

    @Test
    void testStartAndStop() {
        // Test start
        assertDoesNotThrow(() -> manager.start());
        
        // Wait a moment for initialization
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Verify system is healthy
        assertTrue(manager.isHealthy(), "System should be healthy after start");
        
        // Test system status
        PeeGeeQManager.SystemStatus status = manager.getSystemStatus();
        assertNotNull(status);
        assertTrue(status.isStarted());
        assertEquals("test", status.getProfile());
        
        // Test stop
        assertDoesNotThrow(() -> manager.stop());
    }

    @Test
    void testDatabaseMigration() {
        manager.start();
        
        // Verify migration manager works
        assertTrue(manager.validateConfiguration());
        
        // Check that tables were created
        assertDoesNotThrow(() -> {
            try (var conn = manager.getDataSource().getConnection();
                 var stmt = conn.createStatement();
                 var rs = stmt.executeQuery("SELECT COUNT(*) FROM schema_version")) {
                
                assertTrue(rs.next());
                assertTrue(rs.getInt(1) > 0, "Should have at least one migration applied");
            }
        });
    }

    @Test
    void testHealthChecks() {
        manager.start();
        
        // Wait for health checks to run
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Test overall health
        assertTrue(manager.isHealthy());
        
        OverallHealthStatus healthStatus = manager.getHealthCheckManager().getOverallHealth();
        assertNotNull(healthStatus);
        assertTrue(healthStatus.isHealthy());
        assertFalse(healthStatus.getComponents().isEmpty());
        
        // Verify specific health checks
        assertTrue(healthStatus.getComponents().containsKey("database"));
        assertTrue(healthStatus.getComponents().containsKey("memory"));
    }

    @Test
    void testMetrics() {
        manager.start();
        
        PeeGeeQMetrics metrics = manager.getMetrics();
        assertNotNull(metrics);
        
        // Test metrics recording
        metrics.recordMessageSent("test-topic");
        metrics.recordMessageReceived("test-topic");
        metrics.recordMessageProcessed("test-topic", Duration.ofMillis(100));
        
        PeeGeeQMetrics.MetricsSummary summary = metrics.getSummary();
        assertNotNull(summary);
        assertEquals(1.0, summary.getMessagesSent());
        assertEquals(1.0, summary.getMessagesReceived());
        assertEquals(1.0, summary.getMessagesProcessed());
    }

    @Test
    void testCircuitBreaker() {
        manager.start();
        
        var circuitBreakerManager = manager.getCircuitBreakerManager();
        assertNotNull(circuitBreakerManager);
        
        // Test circuit breaker execution
        String result = circuitBreakerManager.executeSupplier("test-operation", () -> "success");
        assertEquals("success", result);
        
        // Test metrics
        var metrics = circuitBreakerManager.getMetrics("test-operation");
        assertNotNull(metrics);
        assertTrue(metrics.isEnabled());
    }

    @Test
    void testBackpressure() throws Exception {
        manager.start();
        
        BackpressureManager backpressureManager = manager.getBackpressureManager();
        assertNotNull(backpressureManager);
        
        // Test successful operation
        String result = backpressureManager.execute("test-op", () -> "success");
        assertEquals("success", result);
        
        // Test metrics
        BackpressureManager.BackpressureMetrics metrics = backpressureManager.getMetrics();
        assertNotNull(metrics);
        assertEquals(1, metrics.getSuccessfulOperations());
        assertEquals(0, metrics.getFailedOperations());
    }

    @Test
    void testDeadLetterQueue() {
        manager.start();
        
        var dlqManager = manager.getDeadLetterQueueManager();
        assertNotNull(dlqManager);
        
        // Test statistics (should be empty initially)
        DeadLetterQueueStats stats = dlqManager.getStatistics();
        assertNotNull(stats);
        assertTrue(stats.isEmpty());
        assertEquals(0, stats.getTotalMessages());
    }

    @Test
    void testConfigurationProfiles() {
        // Test that configuration is loaded correctly
        assertEquals("test", configuration.getProfile());
        
        // Test database configuration
        var dbConfig = configuration.getDatabaseConfig();
        assertNotNull(dbConfig);
        assertEquals(postgres.getHost(), dbConfig.getHost());
        assertEquals(postgres.getFirstMappedPort(), dbConfig.getPort());
        
        // Test queue configuration
        var queueConfig = configuration.getQueueConfig();
        assertNotNull(queueConfig);
        assertTrue(queueConfig.getMaxRetries() > 0);
        assertTrue(queueConfig.isDeadLetterEnabled());
        
        // Test metrics configuration
        var metricsConfig = configuration.getMetricsConfig();
        assertNotNull(metricsConfig);
        assertTrue(metricsConfig.isEnabled());
    }

    @Test
    void testSystemStatusReporting() {
        manager.start();
        
        // Wait for components to initialize
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        PeeGeeQManager.SystemStatus status = manager.getSystemStatus();
        assertNotNull(status);
        assertTrue(status.isStarted());
        assertEquals("test", status.getProfile());
        
        // Verify all status components are present
        assertNotNull(status.getHealthStatus());
        assertNotNull(status.getMetricsSummary());
        assertNotNull(status.getBackpressureMetrics());
        assertNotNull(status.getDeadLetterStats());
        
        // Test toString for logging
        String statusString = status.toString();
        assertNotNull(statusString);
        assertTrue(statusString.contains("started=true"));
        assertTrue(statusString.contains("profile='test'"));
    }

    @Test
    void testResourceCleanup() {
        manager.start();
        
        // Verify manager is running
        assertTrue(manager.getSystemStatus().isStarted());
        
        // Test graceful shutdown
        assertDoesNotThrow(() -> manager.close());
        
        // Verify cleanup
        assertFalse(manager.getSystemStatus().isStarted());
    }
}
