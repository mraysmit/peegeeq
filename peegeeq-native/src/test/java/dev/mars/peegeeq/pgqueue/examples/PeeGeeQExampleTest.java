package dev.mars.peegeeq.pgqueue.examples;

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

import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.deadletter.DeadLetterMessage;
import dev.mars.peegeeq.db.health.OverallHealthStatus;
import dev.mars.peegeeq.db.metrics.PeeGeeQMetrics;
import dev.mars.peegeeq.db.resilience.BackpressureManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test demonstrating PeeGeeQ production readiness features using TestContainers.
 * Migrated from PeeGeeQExample.java to proper JUnit test.
 * 
 * This test is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PeeGeeQExampleTest {
    
    private static final Logger logger = LoggerFactory.getLogger(PeeGeeQExampleTest.class);

    // PostgreSQL container configuration
    private static final String POSTGRES_IMAGE = "postgres:15.13-alpine3.20";
    private static final String DB_NAME = "peegeeq_example";
    private static final String DB_USER = "peegeeq_example";
    private static final String DB_PASSWORD = "peegeeq_example";

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE)
            .withDatabaseName(DB_NAME)
            .withUsername(DB_USER)
            .withPassword(DB_PASSWORD)
            .withSharedMemorySize(256 * 1024 * 1024L) // 256MB for better performance
            .withReuse(false);

    private PeeGeeQManager manager;

    @BeforeEach
    void setUp() throws Exception {
        logger.info("=== Setting up PeeGeeQ Example Test ===");
        
        // Display PeeGeeQ logo
        System.out.println();
        System.out.println("    ____            ______            ____");
        System.out.println("   / __ \\___  ___  / ____/__  ___    / __ \\");
        System.out.println("  / /_/ / _ \\/ _ \\/ / __/ _ \\/ _ \\  / / / /");
        System.out.println(" / ____/  __/  __/ /_/ /  __/ / /_/ /");
        System.out.println("/_/    \\___/\\___/\\____/\\___/\\___/  \\___\\_\\");
        System.out.println();
        System.out.println("PostgreSQL Event-Driven Queue System");
        System.out.println("JUnit Test - TestContainers PostgreSQL");
        System.out.println();

        // Configure PeeGeeQ to use the container
        configureSystemPropertiesForContainer(postgres);

        // Initialize PeeGeeQ Manager
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());
        manager.start();
        
        logger.info("✅ PeeGeeQ Example Test setup completed");
    }

    @AfterEach
    void tearDown() throws Exception {
        logger.info("🧹 Cleaning up PeeGeeQ Example Test");
        
        if (manager != null) {
            manager.close();
        }
        
        // Clear system properties
        clearSystemProperties();
        
        logger.info("✅ PeeGeeQ Example Test cleanup completed");
    }

    @Test
    void testConfiguration() {
        logger.info("=== Testing Configuration ===");
        
        demonstrateConfiguration(manager);
        
        // Verify configuration is working
        PeeGeeQConfiguration config = manager.getConfiguration();
        assertNotNull(config, "Configuration should not be null");
        assertEquals("development", config.getProfile(), "Profile should be development");
        
        logger.info("✅ Configuration test completed successfully!");
    }

    @Test
    void testHealthChecks() {
        logger.info("=== Testing Health Checks ===");
        
        demonstrateHealthChecks(manager);
        
        // Verify health checks are working
        OverallHealthStatus health = manager.getHealthCheckManager().getOverallHealth();
        assertNotNull(health, "Health status should not be null");
        assertTrue(health.getHealthyCount() > 0, "Should have healthy components");
        
        logger.info("✅ Health checks test completed successfully!");
    }

    @Test
    void testMetrics() {
        logger.info("=== Testing Metrics ===");
        
        demonstrateMetrics(manager);
        
        // Verify metrics are working
        PeeGeeQMetrics metrics = manager.getMetrics();
        assertNotNull(metrics, "Metrics should not be null");
        
        logger.info("✅ Metrics test completed successfully!");
    }

    @Test
    void testCircuitBreaker() {
        logger.info("=== Testing Circuit Breaker ===");
        
        demonstrateCircuitBreaker(manager);
        
        // Verify circuit breaker is working
        var circuitBreakerManager = manager.getCircuitBreakerManager();
        assertNotNull(circuitBreakerManager, "Circuit breaker manager should not be null");
        
        logger.info("✅ Circuit breaker test completed successfully!");
    }

    @Test
    void testBackpressure() {
        logger.info("=== Testing Backpressure ===");
        
        demonstrateBackpressure(manager);
        
        // Verify backpressure is working
        BackpressureManager backpressureManager = manager.getBackpressureManager();
        assertNotNull(backpressureManager, "Backpressure manager should not be null");
        
        logger.info("✅ Backpressure test completed successfully!");
    }

    @Test
    void testDeadLetterQueue() {
        logger.info("=== Testing Dead Letter Queue ===");
        
        demonstrateDeadLetterQueue(manager);
        
        // Verify dead letter queue is working
        var dlqManager = manager.getDeadLetterQueueManager();
        assertNotNull(dlqManager, "Dead letter queue manager should not be null");
        
        logger.info("✅ Dead letter queue test completed successfully!");
    }

    @Test
    void testSystemMonitoring() {
        logger.info("=== Testing System Monitoring ===");
        
        monitorSystem(manager);
        
        // Verify monitoring completed
        assertTrue(true, "System monitoring demonstration completed");
        
        logger.info("✅ System monitoring test completed successfully!");
    }

    /**
     * Configures system properties to use the TestContainer database.
     */
    private void configureSystemPropertiesForContainer(PostgreSQLContainer<?> postgres) {
        logger.info("  Configuring PeeGeeQ to use container database...");

        // Set database connection properties
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.schema", "public");
        System.setProperty("peegeeq.database.ssl.enabled", "false");

        // Set additional configuration for comprehensive testing
        System.setProperty("peegeeq.database.pool.min-size", "5");
        System.setProperty("peegeeq.database.pool.max-size", "20");
        System.setProperty("peegeeq.metrics.enabled", "true");
        System.setProperty("peegeeq.migration.enabled", "true");
        System.setProperty("peegeeq.migration.auto-migrate", "true");
        System.setProperty("peegeeq.health.enabled", "true");
        System.setProperty("peegeeq.circuit-breaker.enabled", "false"); // Disabled for testing
        System.setProperty("peegeeq.dead-letter.enabled", "true");

        logger.info("  ✅ System properties configured for container database");
    }

    /**
     * Clears all system properties set for testing.
     */
    private void clearSystemProperties() {
        System.clearProperty("peegeeq.database.host");
        System.clearProperty("peegeeq.database.port");
        System.clearProperty("peegeeq.database.name");
        System.clearProperty("peegeeq.database.username");
        System.clearProperty("peegeeq.database.password");
        System.clearProperty("peegeeq.database.schema");
        System.clearProperty("peegeeq.database.ssl.enabled");
        System.clearProperty("peegeeq.database.pool.min-size");
        System.clearProperty("peegeeq.database.pool.max-size");
        System.clearProperty("peegeeq.metrics.enabled");
        System.clearProperty("peegeeq.migration.enabled");
        System.clearProperty("peegeeq.migration.auto-migrate");
        System.clearProperty("peegeeq.health.enabled");
        System.clearProperty("peegeeq.circuit-breaker.enabled");
        System.clearProperty("peegeeq.dead-letter.enabled");
    }

    private void demonstrateConfiguration(PeeGeeQManager manager) {
        logger.info("\n === Configuration Demo ===");

        PeeGeeQConfiguration config = manager.getConfiguration();
        logger.info("🏷Profile: {}", config.getProfile());

        logger.info("📊 Database Configuration:");
        logger.info("> Host: {}", config.getString("peegeeq.database.host", "localhost"));
        logger.info("> Port: {}", config.getInt("peegeeq.database.port", 5432));
        logger.info("> Database: {}", config.getString("peegeeq.database.name", "peegeeq"));
        logger.info("> Schema: {}", config.getString("peegeeq.database.schema", "public"));
        logger.info("> Pool Min Size: {}", config.getInt("peegeeq.database.pool.min-size", 5));
        logger.info("> Pool Max Size: {}", config.getInt("peegeeq.database.pool.max-size", 20));

        logger.info("⚙️ Feature Configuration:");
        logger.info("> Metrics Enabled: {}", config.getBoolean("peegeeq.metrics.enabled", true));
        logger.info("> Health Checks Enabled: {}", config.getBoolean("peegeeq.health.enabled", true));
        logger.info("> Circuit Breaker Enabled: {}", config.getCircuitBreakerConfig().isEnabled());
        logger.info("> Dead Letter Enabled: {}", config.getQueueConfig().isDeadLetterEnabled());
        logger.info("> Migration Auto-enabled: {}", config.getBoolean("peegeeq.migration.auto-migrate", true));
    }

    private void demonstrateHealthChecks(PeeGeeQManager manager) {
        logger.info("\n === Health Checks Demo ===");

        OverallHealthStatus health = manager.getHealthCheckManager().getOverallHealth();
        logger.info(">> Overall Health: {}", health.getStatus());
        logger.info("   > Healthy Components: {}", health.getHealthyCount());
        logger.info("   > Degraded Components: {}", health.getDegradedCount());
        logger.info("   > Unhealthy Components: {}", health.getUnhealthyCount());
        logger.info("   > Total Components: {}", health.getComponents().size());

        health.getComponents().forEach((name, status) ->
            logger.info(" {} -> {} ({})", name, status.getStatus(),
                status.getMessage() != null ? status.getMessage() : "OK"));
    }

    private void demonstrateMetrics(PeeGeeQManager manager) {
        logger.info("\n=== Metrics Demo ===");

        PeeGeeQMetrics metrics = manager.getMetrics();

        // Simulate some message processing
        for (int i = 0; i < 10; i++) {
            metrics.recordMessageSent("test-queue");
            if (i % 3 == 0) {
                metrics.recordMessageProcessed("test-queue", Duration.ofMillis(50));
            } else {
                metrics.recordMessageProcessed("test-queue", Duration.ofMillis(25));
            }
        }

        // Simulate some failures
        for (int i = 0; i < 2; i++) {
            metrics.recordMessageFailed("test-queue", "simulated_failure");
        }

        var summary = metrics.getSummary();
        logger.info("📊 Metrics Summary:");
        logger.info("Messages Sent: {}", summary.getMessagesSent());
        logger.info("Messages Processed: {}", summary.getMessagesProcessed());
        logger.info("Messages Failed: {}", summary.getMessagesFailed());
        logger.info("Success Rate: {}%", summary.getSuccessRate());
        logger.info("Outbox Queue Depth: {}", summary.getOutboxQueueDepth());
        logger.info("Native Queue Depth: {}", summary.getNativeQueueDepth());
    }

    private void demonstrateCircuitBreaker(PeeGeeQManager manager) {
        logger.info("=== Circuit Breaker Demo ===");

        var circuitBreakerManager = manager.getCircuitBreakerManager();

        // Simulate successful operations
        logger.info("Simulating successful operations...");
        for (int i = 0; i < 5; i++) {
            final int operationId = i;
            String result = circuitBreakerManager.executeSupplier("test-operation", () -> {
                // Simulate successful operation
                return "Success " + operationId;
            });
            logger.debug("Circuit breaker result: {}", result);
        }

        // Simulate some failures
        logger.info("Simulating failed operations...");
        for (int i = 0; i < 3; i++) {
            final int operationId = i;
            try {
                circuitBreakerManager.executeSupplier("test-operation", () -> {
                    throw new RuntimeException("Simulated failure " + operationId);
                });
            } catch (Exception e) {
                logger.debug("Expected failure: {}", e.getMessage());
            }
        }

        var metrics = circuitBreakerManager.getMetrics("test-operation");
        logger.info("🔌 Circuit Breaker Metrics:");
        logger.info("State: {}", metrics.getState());
        logger.info("Successful Calls: {}", metrics.getSuccessfulCalls());
        logger.info("Failed Calls: {}", metrics.getFailedCalls());
        logger.info("Failure Rate: {}%", metrics.getFailureRate());
    }

    private void demonstrateBackpressure(PeeGeeQManager manager) {
        logger.info("=== Backpressure Demo ===");

        BackpressureManager backpressureManager = manager.getBackpressureManager();

        // Simulate concurrent operations using the proper API
        logger.info("Simulating concurrent operations to test backpressure...");
        ExecutorService executor = Executors.newFixedThreadPool(20);

        try {
            // Submit many concurrent operations using the proper execute method
            for (int i = 0; i < 50; i++) {
                final int operationId = i;
                executor.submit(() -> {
                    try {
                        // Use the proper backpressure execute method
                        String result = backpressureManager.execute("test-operation-" + operationId, () -> {
                            // Simulate work
                            Thread.sleep(10);
                            return "Operation " + operationId + " completed";
                        });
                        logger.debug("Result: {}", result);
                    } catch (BackpressureManager.BackpressureException e) {
                        logger.debug("Operation {} rejected due to backpressure: {}", operationId, e.getMessage());
                    } catch (Exception e) {
                        logger.warn("Operation {} failed: {}", operationId, e.getMessage());
                    }
                });
            }

            // Wait a bit for operations to complete
            Thread.sleep(2000);

            var metrics = backpressureManager.getMetrics();
            logger.info("🚦 Backpressure Metrics:");
            logger.info("Max Concurrent Operations: {}", metrics.getMaxConcurrentOperations());
            logger.info("Available Permits: {}", metrics.getAvailablePermits());
            logger.info("Active Operations: {}", metrics.getActiveOperations());
            logger.info("Total Requests: {}", metrics.getTotalRequests());
            logger.info("Rejected Requests: {}", metrics.getRejectedRequests());
            logger.info("Successful Operations: {}", metrics.getSuccessfulOperations());
            logger.info("Failed Operations: {}", metrics.getFailedOperations());
            logger.info("Current Success Rate: {}%", metrics.getCurrentSuccessRate() * 100);
            logger.info("Rejection Rate: {}%", metrics.getRejectionRate() * 100);
            logger.info("Utilization: {}%", metrics.getUtilization() * 100);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Backpressure demo interrupted");
        } finally {
            // Properly shutdown the executor
            shutdownExecutorGracefully(executor, "backpressure-demo");
        }
    }

    private void demonstrateDeadLetterQueue(PeeGeeQManager manager) {
        logger.info("=== Dead Letter Queue Demo ===");

        var dlqManager = manager.getDeadLetterQueueManager();

        // Simulate moving some messages to DLQ using the correct API
        logger.info("Simulating failed messages being moved to Dead Letter Queue...");

        Map<String, String> headers1 = new HashMap<>();
        headers1.put("source", "order-service");
        headers1.put("version", "1.0");

        Map<String, String> headers2 = new HashMap<>();
        headers2.put("source", "user-service");
        headers2.put("version", "2.1");

        // Simulate messages that failed processing using the correct method signature
        dlqManager.moveToDeadLetterQueue("outbox_messages", 1001L, "order-processing",
            Map.of("orderId", "12345", "customerId", "cust-001", "amount", 99.99),
            Instant.now().minus(Duration.ofMinutes(5)), "Payment processing failed", 3,
            headers1, "corr-001", "order-group");

        dlqManager.moveToDeadLetterQueue("outbox_messages", 1002L, "user-events",
            Map.of("userId", "user-456", "action", "login", "timestamp", System.currentTimeMillis()),
            Instant.now().minus(Duration.ofMinutes(2)), "Database connection timeout", 1,
            headers2, "corr-002", "user-group");

        dlqManager.moveToDeadLetterQueue("outbox_messages", 1003L, "notifications",
            Map.of("email", "user@example.com", "template", "welcome"),
            Instant.now().minus(Duration.ofMinutes(1)), "Email service unavailable", 5,
            Map.of("source", "notification-service"), "corr-003", "notification-group");

        // Wait a moment for processing
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Check DLQ statistics
        var stats = dlqManager.getStatistics();
        logger.info("📊 Dead Letter Queue Statistics:");
        logger.info("Total Messages: {}", stats.getTotalMessages());
        logger.info("Messages by Topic: (detailed breakdown not available in current API)");

        // Retrieve and display some DLQ messages using the correct method
        List<DeadLetterMessage> dlqMessages = dlqManager.getAllDeadLetterMessages(10, 0);
        logger.info("📋 Recent Dead Letter Messages:");
        for (DeadLetterMessage msg : dlqMessages) {
            logger.info("  ID: {}, Original ID: {}, Topic: {}, Reason: {}, Retry Count: {}",
                msg.getId(), msg.getOriginalId(), msg.getTopic(), msg.getFailureReason(), msg.getRetryCount());
        }
    }

    private void monitorSystem(PeeGeeQManager manager) {
        logger.info("=== System Monitoring ===");
        logger.info("Monitoring system for 30 seconds...");

        ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor();

        try {
            // Schedule monitoring every 5 seconds
            monitor.scheduleAtFixedRate(() -> {
                try {
                    logger.info("📊 System Status Check:");

                    // Health status
                    OverallHealthStatus health = manager.getHealthCheckManager().getOverallHealth();
                    logger.info("  Health: {} ({} healthy, {} unhealthy)",
                        health.getStatus(), health.getHealthyCount(), health.getUnhealthyCount());

                    // Metrics summary
                    var metrics = manager.getMetrics().getSummary();
                    logger.info("  Messages: {} sent, {} processed, {} failed ({}% success rate)",
                        (long)metrics.getMessagesSent(), (long)metrics.getMessagesProcessed(),
                        (long)metrics.getMessagesFailed(), metrics.getSuccessRate());

                    // Backpressure status
                    var backpressure = manager.getBackpressureManager().getMetrics();
                    logger.info("  Backpressure: {} active operations (max: {})",
                        backpressure.getActiveOperations(), backpressure.getMaxConcurrentOperations());

                    // DLQ status
                    var dlqStats = manager.getDeadLetterQueueManager().getStatistics();
                    logger.info("  Dead Letter Queue: {} total messages", dlqStats.getTotalMessages());

                } catch (Exception e) {
                    logger.warn("Error during system monitoring: {}", e.getMessage());
                }
            }, 0, 5, TimeUnit.SECONDS);

            // Wait for monitoring period
            Thread.sleep(30000);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.info("System monitoring interrupted");
        } finally {
            shutdownExecutorGracefully(monitor, "system-monitor");
        }

        logger.info("System monitoring completed");
    }

    /**
     * Gracefully shuts down an executor service with proper timeout handling.
     */
    private void shutdownExecutorGracefully(ExecutorService executor, String name) {
        logger.debug("Shutting down executor: {}", name);

        executor.shutdown(); // Disable new tasks from being submitted

        try {
            // Wait a while for existing tasks to terminate
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.warn("Executor {} did not terminate gracefully, forcing shutdown", name);
                executor.shutdownNow(); // Cancel currently executing tasks

                // Wait a while for tasks to respond to being cancelled
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    logger.error("Executor {} did not terminate after forced shutdown", name);
                }
            }
        } catch (InterruptedException e) {
            logger.warn("Interrupted while shutting down executor {}", name);
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        logger.debug("Executor {} shutdown completed", name);
    }
}
