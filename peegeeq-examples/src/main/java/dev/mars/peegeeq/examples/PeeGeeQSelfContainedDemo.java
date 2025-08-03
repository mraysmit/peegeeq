package dev.mars.peegeeq.examples;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Self-contained demo application for PeeGeeQ using TestContainers.
 * 
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
public class PeeGeeQSelfContainedDemo {
    private static final Logger logger = LoggerFactory.getLogger(PeeGeeQSelfContainedDemo.class);
    
    // PostgreSQL container configuration
    private static final String POSTGRES_IMAGE = "postgres:15.13-alpine3.20";
    private static final String DB_NAME = "peegeeq_demo";
    private static final String DB_USER = "peegeeq_demo";
    private static final String DB_PASSWORD = "peegeeq_demo";

    public static void main(String[] args) {
        logger.info(">> Starting PeeGeeQ Self-Contained Demo");
        logger.info("This demo will start a PostgreSQL container and demonstrate all PeeGeeQ features");

        // Start PostgreSQL container
        logger.info(">> Starting PostgreSQL container...");
        PostgreSQLContainer<?> postgres = createPostgreSQLContainer();

        try {
            postgres.start();
            logger.info("PostgreSQL container started successfully");
            logger.info("   Container URL: {}", postgres.getJdbcUrl());
            logger.info("   Username: {}", postgres.getUsername());
            logger.info("   Host: {}:{}", postgres.getHost(), postgres.getFirstMappedPort());

            // Configure PeeGeeQ to use the container
            configureSystemPropertiesForContainer(postgres);

            // Run the demo
            runDemo();

        } catch (Exception e) {
            logger.error("Failed to run self-contained demo", e);
            System.exit(1);
        } finally {
            // Explicitly stop the container to ensure proper cleanup
            logger.info("Stopping PostgreSQL container...");
            try {
                postgres.stop();
                logger.info("PostgreSQL container stopped successfully");
            } catch (Exception e) {
                logger.warn("⚠Error stopping PostgreSQL container: {}", e.getMessage());
            }
        }

        logger.info(" Self-contained demo completed successfully!");
    }
    
    /**
     * Creates and configures the PostgreSQL container.
     */
    @SuppressWarnings("resource")
    private static PostgreSQLContainer<?> createPostgreSQLContainer() {
        return new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withDatabaseName(DB_NAME)
                .withUsername(DB_USER)
                .withPassword(DB_PASSWORD)
                .withSharedMemorySize(256 * 1024 * 1024L) // 256MB for better performance
                .withReuse(false); // Always start fresh for demo
    }
    
    /**
     * Configures system properties to use the TestContainer database.
     */
    private static void configureSystemPropertiesForContainer(PostgreSQLContainer<?> postgres) {
        logger.info("  Configuring PeeGeeQ to use container database...");
        
        // Set database connection properties
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.schema", "public");
        System.setProperty("peegeeq.database.ssl.enabled", "false");
        
        // Configure for demo environment
        System.setProperty("peegeeq.database.pool.min-size", "2");
        System.setProperty("peegeeq.database.pool.max-size", "5");
        System.setProperty("peegeeq.metrics.enabled", "true");
        System.setProperty("peegeeq.health.enabled", "true");
        System.setProperty("peegeeq.circuit-breaker.enabled", "true");
        System.setProperty("peegeeq.migration.enabled", "true");
        System.setProperty("peegeeq.migration.auto-migrate", "true");
        
        logger.info("Configuration complete");
    }
    
    /**
     * Runs the main demo showcasing all PeeGeeQ features.
     */
    public static void runDemo() {
        logger.info("Starting PeeGeeQ feature demonstrations...");
        
        try (PeeGeeQManager manager = new PeeGeeQManager(new PeeGeeQConfiguration("demo"), new SimpleMeterRegistry())) {
            
            // Start the manager
            manager.start();
            logger.info("PeeGeeQ Manager started successfully");
            
            // Run all demonstrations
            demonstrateConfiguration(manager);
            demonstrateHealthChecks(manager);
            demonstrateMetrics(manager);
            demonstrateCircuitBreaker(manager);
            demonstrateBackpressure(manager);
            demonstrateDeadLetterQueue(manager);
            
            // Monitor system briefly
            monitorSystemBriefly(manager);
            
        } catch (Exception e) {
            logger.error("Error running demo", e);
            throw new RuntimeException("Demo failed", e);
        }
    }
    
    private static void demonstrateConfiguration(PeeGeeQManager manager) {
        logger.info("\n === Configuration Demo ===");
        
        PeeGeeQConfiguration config = manager.getConfiguration();
        logger.info("🏷Profile: {}", config.getProfile());
        logger.info("🗄Database Host: {}", config.getDatabaseConfig().getHost());
        logger.info("Max Pool Size: {}", config.getPoolConfig().getMaximumPoolSize());
        logger.info("Metrics Enabled: {}", config.getMetricsConfig().isEnabled());
        logger.info("Circuit Breaker Enabled: {}", config.getCircuitBreakerConfig().isEnabled());
        logger.info("Dead Letter Enabled: {}", config.getQueueConfig().isDeadLetterEnabled());
    }
    
    private static void demonstrateHealthChecks(PeeGeeQManager manager) {
        logger.info("\n === Health Checks Demo ===");
        
        OverallHealthStatus health = manager.getHealthCheckManager().getOverallHealth();
        logger.info("Overall Health: {}", health.getStatus());
        logger.info("Healthy Components: {}", health.getHealthyCount());
        logger.info("️  Degraded Components: {}", health.getDegradedCount());
        logger.info(" Unhealthy Components: {}", health.getUnhealthyCount());
        
        health.getComponents().forEach((name, status) -> 
            logger.info("    {} -> {} ({})", name, status.getStatus(),
                status.getMessage() != null ? status.getMessage() : "OK"));
    }
    
    private static void demonstrateMetrics(PeeGeeQManager manager) {
        logger.info("\n === Metrics Demo ===");
        
        PeeGeeQMetrics metrics = manager.getMetrics();
        
        // Simulate some message processing
        logger.info(" Simulating message processing...");
        for (int i = 0; i < 10; i++) {
            metrics.recordMessageSent("demo-topic");
            metrics.recordMessageReceived("demo-topic");
            
            if (i % 3 == 0) {
                metrics.recordMessageFailed("demo-topic", "simulation");
            } else {
                metrics.recordMessageProcessed("demo-topic", Duration.ofMillis(50 + i * 10));
            }
        }
        
        PeeGeeQMetrics.MetricsSummary summary = metrics.getSummary();
        logger.info(" Messages Sent: {}", summary.getMessagesSent());
        logger.info(" Messages Received: {}", summary.getMessagesReceived());
        logger.info(" Messages Processed: {}", summary.getMessagesProcessed());
        logger.info(" Messages Failed: {}", summary.getMessagesFailed());
        logger.info(" Success Rate: {}%", summary.getSuccessRate());
        logger.info(" Outbox Queue Depth: {}", summary.getOutboxQueueDepth());
        logger.info(" Native Queue Depth: {}", summary.getNativeQueueDepth());
    }

    private static void demonstrateCircuitBreaker(PeeGeeQManager manager) {
        logger.info("\n === Circuit Breaker Demo ===");

        var circuitBreakerManager = manager.getCircuitBreakerManager();

        // Simulate successful operations
        logger.info(" Testing successful operations...");
        for (int i = 0; i < 5; i++) {
            final int index = i;
            String result = circuitBreakerManager.executeSupplier("demo-operation",
                () -> "Success " + index);
            logger.info("    Circuit breaker result: {}", result);
        }

        // Simulate some failures
        logger.info(" Testing failure scenarios...");
        for (int i = 0; i < 3; i++) {
            final int index = i;
            try {
                circuitBreakerManager.executeSupplier("demo-operation", () -> {
                    throw new RuntimeException("Simulated failure " + index);
                });
            } catch (Exception e) {
                logger.info("   🛡  Circuit breaker caught failure: {}", e.getMessage());
            }
        }

        var metrics = circuitBreakerManager.getMetrics("demo-operation");
        logger.info(" Circuit Breaker State: {}", metrics.getState());
        logger.info(" Successful Calls: {}", metrics.getSuccessfulCalls());
        logger.info(" Failed Calls: {}", metrics.getFailedCalls());
        logger.info(" Failure Rate: {}%", metrics.getFailureRate());
    }

    private static void demonstrateBackpressure(PeeGeeQManager manager) {
        logger.info("\n === Backpressure Demo ===");

        BackpressureManager backpressureManager = manager.getBackpressureManager();

        // Simulate concurrent operations
        logger.info(" Simulating concurrent operations...");
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(5);

        try {
            for (int i = 0; i < 20; i++) {
                final int operationId = i;
                executor.submit(() -> {
                    try {
                        String result = backpressureManager.execute("demo-backpressure", () -> {
                            // Simulate work
                            Thread.sleep(100);
                            return "Operation " + operationId + " completed";
                        });
                        logger.debug("    Backpressure result: {}", result);
                    } catch (BackpressureManager.BackpressureException e) {
                        logger.warn("    Backpressure rejected operation {}: {}", operationId, e.getMessage());
                    } catch (Exception e) {
                        logger.error("    Operation {} failed", operationId, e);
                    }
                });
            }

            // Wait and check metrics
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            BackpressureManager.BackpressureMetrics metrics = backpressureManager.getMetrics();
            logger.info(" Total Requests: {}", metrics.getTotalRequests());
            logger.info(" Successful Operations: {}", metrics.getSuccessfulOperations());
            logger.info(" Rejected Requests: {}", metrics.getRejectedRequests());
            logger.info(" Current Utilization: {}%", metrics.getUtilization() * 100);
            logger.info(" Success Rate: {}%", metrics.getCurrentSuccessRate() * 100);

        } finally {
            // Properly shutdown the executor
            shutdownExecutorGracefully(executor, "backpressure-demo");
        }
    }

    private static void demonstrateDeadLetterQueue(PeeGeeQManager manager) {
        logger.info("\n === Dead Letter Queue Demo ===");

        var dlqManager = manager.getDeadLetterQueueManager();

        // Simulate moving some messages to DLQ
        logger.info(" Simulating failed messages...");
        Map<String, String> headers = new HashMap<>();
        headers.put("content-type", "application/json");
        headers.put("source", "self-contained-demo");

        for (int i = 0; i < 3; i++) {
            dlqManager.moveToDeadLetterQueue(
                "outbox",
                1000L + i,
                "demo-topic",
                "{\"message\": \"Demo message " + i + "\", \"demo\": true}",
                Instant.now().minusSeconds(300),
                "Simulated failure for self-contained demo",
                i + 1,
                headers,
                "correlation-demo-" + i,
                "demo-group"
            );
        }

        // Check DLQ statistics
        var stats = dlqManager.getStatistics();
        logger.info(">> Dead Letter Queue Stats:");
        logger.info("    Total Messages: {}", stats.getTotalMessages());
        logger.info("   🏷Unique Topics: {}", stats.getUniqueTopics());
        logger.info("     Unique Tables: {}", stats.getUniqueTables());
        logger.info("    Average Retry Count: {}", stats.getAverageRetryCount());

        // Retrieve and display DLQ messages
        List<DeadLetterMessage> messages = dlqManager.getDeadLetterMessages("demo-topic", 10, 0);
        logger.info(" Dead Letter Messages for demo-topic:");
        for (DeadLetterMessage msg : messages) {
            logger.info("   ID: {}, Original ID: {}, Reason: {}, Retry Count: {}",
                msg.getId(), msg.getOriginalId(), msg.getFailureReason(), msg.getRetryCount());
        }
    }

    private static void monitorSystemBriefly(PeeGeeQManager manager) {
        logger.info("\n === Brief System Monitoring ===");
        logger.info("Monitoring system for 10 seconds...");

        ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor();

        try {
            monitor.scheduleAtFixedRate(() -> {
                try {
                    PeeGeeQManager.SystemStatus status = manager.getSystemStatus();
                    logger.info(" System Status: {}", status);

                    if (!status.getHealthStatus().isHealthy()) {
                        logger.warn("  System health degraded!");
                        status.getHealthStatus().getComponents().forEach((name, health) -> {
                            if (!health.isHealthy()) {
                                logger.warn("   Unhealthy component: {} - {}", name, health.getMessage());
                            }
                        });
                    }

                } catch (Exception e) {
                    logger.error(" Error monitoring system", e);
                }
            }, 0, 5, TimeUnit.SECONDS);

            // Run monitoring for 10 seconds
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

        } finally {
            // Properly shutdown the monitoring executor
            shutdownExecutorGracefully(monitor, "system-monitor");
            logger.info(" Monitoring completed");
        }
    }

    /**
     * Gracefully shuts down an executor service with proper timeout handling.
     */
    private static void shutdownExecutorGracefully(ExecutorService executor, String name) {
        logger.debug("Shutting down executor: {}", name);

        executor.shutdown(); // Disable new tasks from being submitted

        try {
            // Wait a while for existing tasks to terminate
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.warn("Executor {} did not terminate gracefully, forcing shutdown", name);
                executor.shutdownNow(); // Cancel currently executing tasks

                // Wait a while for tasks to respond to being cancelled
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    logger.error("Executor {} did not terminate after forced shutdown", name);
                } else {
                    logger.debug("Executor {} terminated after forced shutdown", name);
                }
            } else {
                logger.debug("Executor {} terminated gracefully", name);
            }
        } catch (InterruptedException e) {
            logger.warn("Interrupted while shutting down executor {}", name);
            // (Re-)Cancel if current thread also interrupted
            executor.shutdownNow();
            // Preserve interrupt status
            Thread.currentThread().interrupt();
        }
    }
}
