package dev.mars.peegeeq.db.example;

import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.deadletter.DeadLetterMessage;
import dev.mars.peegeeq.db.health.OverallHealthStatus;
import dev.mars.peegeeq.db.metrics.PeeGeeQMetrics;
import dev.mars.peegeeq.db.resilience.BackpressureManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Example application demonstrating PeeGeeQ production readiness features.
 * Shows how to use configuration, metrics, health checks, circuit breakers, 
 * backpressure, and dead letter queues.
 */
public class PeeGeeQExample {
    private static final Logger logger = LoggerFactory.getLogger(PeeGeeQExample.class);

    public static void main(String[] args) {
        // Set profile via system property or environment variable
        String profile = System.getProperty("peegeeq.profile", "development");
        logger.info("Starting PeeGeeQ Example with profile: {}", profile);

        try (PeeGeeQManager manager = new PeeGeeQManager(new PeeGeeQConfiguration(profile), new SimpleMeterRegistry())) {

            // Start the manager
            manager.start();
            logger.info("PeeGeeQ Manager started successfully");

            // Demonstrate configuration
            demonstrateConfiguration(manager);

            // Demonstrate health checks
            demonstrateHealthChecks(manager);

            // Demonstrate metrics
            demonstrateMetrics(manager);

            // Demonstrate circuit breaker
            demonstrateCircuitBreaker(manager);

            // Demonstrate backpressure
            demonstrateBackpressure(manager);

            // Demonstrate dead letter queue
            demonstrateDeadLetterQueue(manager);

            // Monitor system for a while
            monitorSystem(manager);

        } catch (Exception e) {
            logger.error("Error running PeeGeeQ Example", e);
        }
    }

    private static void demonstrateConfiguration(PeeGeeQManager manager) {
        logger.info("=== Configuration Demo ===");

        PeeGeeQConfiguration config = manager.getConfiguration();
        logger.info("Profile: {}", config.getProfile());
        logger.info("Database Host: {}", config.getDatabaseConfig().getHost());
        logger.info("Max Pool Size: {}", config.getPoolConfig().getMaximumPoolSize());
        logger.info("Metrics Enabled: {}", config.getMetricsConfig().isEnabled());
        logger.info("Circuit Breaker Enabled: {}", config.getCircuitBreakerConfig().isEnabled());
        logger.info("Dead Letter Enabled: {}", config.getQueueConfig().isDeadLetterEnabled());
    }

    private static void demonstrateHealthChecks(PeeGeeQManager manager) {
        logger.info("=== Health Checks Demo ===");

        OverallHealthStatus health = manager.getHealthCheckManager().getOverallHealth();
        logger.info("Overall Health: {}", health.getStatus());
        logger.info("Healthy Components: {}", health.getHealthyCount());
        logger.info("Degraded Components: {}", health.getDegradedCount());
        logger.info("Unhealthy Components: {}", health.getUnhealthyCount());

        health.getComponents().forEach((name, status) -> 
            logger.info("  {} -> {} ({})", name, status.getStatus(), 
                status.getMessage() != null ? status.getMessage() : "OK"));
    }

    private static void demonstrateMetrics(PeeGeeQManager manager) {
        logger.info("=== Metrics Demo ===");

        PeeGeeQMetrics metrics = manager.getMetrics();

        // Simulate some message processing
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
        logger.info("Messages Sent: {}", summary.getMessagesSent());
        logger.info("Messages Received: {}", summary.getMessagesReceived());
        logger.info("Messages Processed: {}", summary.getMessagesProcessed());
        logger.info("Messages Failed: {}", summary.getMessagesFailed());
        logger.info("Success Rate: {}%", summary.getSuccessRate());
        logger.info("Outbox Queue Depth: {}", summary.getOutboxQueueDepth());
        logger.info("Native Queue Depth: {}", summary.getNativeQueueDepth());
    }

    private static void demonstrateCircuitBreaker(PeeGeeQManager manager) {
        logger.info("=== Circuit Breaker Demo ===");

        var circuitBreakerManager = manager.getCircuitBreakerManager();

        // Simulate successful operations
        for (int i = 0; i < 5; i++) {
            final int index = i;
            String result = circuitBreakerManager.executeSupplier("demo-operation", 
                () -> "Success " + index);
            logger.info("Circuit breaker result: {}", result);
        }

        // Simulate some failures
        for (int i = 0; i < 3; i++) {
            final int index = i;
            try {
                circuitBreakerManager.executeSupplier("demo-operation", () -> {
                    throw new RuntimeException("Simulated failure " + index);
                });
            } catch (Exception e) {
                logger.info("Circuit breaker caught failure: {}", e.getMessage());
            }
        }

        var metrics = circuitBreakerManager.getMetrics("demo-operation");
        logger.info("Circuit Breaker State: {}", metrics.getState());
        logger.info("Successful Calls: {}", metrics.getSuccessfulCalls());
        logger.info("Failed Calls: {}", metrics.getFailedCalls());
        logger.info("Failure Rate: {}%", metrics.getFailureRate());
    }

    private static void demonstrateBackpressure(PeeGeeQManager manager) {
        logger.info("=== Backpressure Demo ===");

        BackpressureManager backpressureManager = manager.getBackpressureManager();

        // Simulate concurrent operations
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(5);

        for (int i = 0; i < 20; i++) {
            final int operationId = i;
            executor.submit(() -> {
                try {
                    String result = backpressureManager.execute("demo-backpressure", () -> {
                        // Simulate work
                        Thread.sleep(100);
                        return "Operation " + operationId + " completed";
                    });
                    logger.debug("Backpressure result: {}", result);
                } catch (BackpressureManager.BackpressureException e) {
                    logger.warn("Backpressure rejected operation {}: {}", operationId, e.getMessage());
                } catch (Exception e) {
                    logger.error("Operation {} failed", operationId, e);
                }
            });
        }

        // Wait a bit and check metrics
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        BackpressureManager.BackpressureMetrics metrics = backpressureManager.getMetrics();
        logger.info("Total Requests: {}", metrics.getTotalRequests());
        logger.info("Successful Operations: {}", metrics.getSuccessfulOperations());
        logger.info("Rejected Requests: {}", metrics.getRejectedRequests());
        logger.info("Current Utilization: {}%", metrics.getUtilization() * 100);
        logger.info("Success Rate: {}%", metrics.getCurrentSuccessRate() * 100);

        executor.shutdown();
    }

    private static void demonstrateDeadLetterQueue(PeeGeeQManager manager) {
        logger.info("=== Dead Letter Queue Demo ===");

        var dlqManager = manager.getDeadLetterQueueManager();

        // Simulate moving some messages to DLQ
        Map<String, String> headers = new HashMap<>();
        headers.put("content-type", "application/json");
        headers.put("source", "demo");

        for (int i = 0; i < 3; i++) {
            dlqManager.moveToDeadLetterQueue(
                "outbox",
                1000L + i,
                "demo-topic",
                "{\"message\": \"Demo message " + i + "\"}",
                Instant.now().minusSeconds(300),
                "Simulated failure for demo",
                i + 1,
                headers,
                "correlation-" + i,
                "demo-group"
            );
        }

        // Check DLQ statistics
        var stats = dlqManager.getStatistics();
        logger.info("Dead Letter Queue Stats:");
        logger.info("  Total Messages: {}", stats.getTotalMessages());
        logger.info("  Unique Topics: {}", stats.getUniqueTopics());
        logger.info("  Unique Tables: {}", stats.getUniqueTables());
        logger.info("  Average Retry Count: {}", stats.getAverageRetryCount());

        // Retrieve and display DLQ messages
        List<DeadLetterMessage> messages = dlqManager.getDeadLetterMessages("demo-topic", 10, 0);
        logger.info("Dead Letter Messages for demo-topic:");
        for (DeadLetterMessage msg : messages) {
            logger.info("  ID: {}, Original ID: {}, Reason: {}, Retry Count: {}", 
                msg.getId(), msg.getOriginalId(), msg.getFailureReason(), msg.getRetryCount());
        }
    }

    private static void monitorSystem(PeeGeeQManager manager) {
        logger.info("=== System Monitoring ===");

        // Monitor for 30 seconds
        ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor();

        monitor.scheduleAtFixedRate(() -> {
            try {
                PeeGeeQManager.SystemStatus status = manager.getSystemStatus();
                logger.info("System Status: {}", status);

                if (!status.getHealthStatus().isHealthy()) {
                    logger.warn("System health degraded!");
                    status.getHealthStatus().getComponents().forEach((name, health) -> {
                        if (!health.isHealthy()) {
                            logger.warn("  Unhealthy component: {} - {}", name, health.getMessage());
                        }
                    });
                }

            } catch (Exception e) {
                logger.error("Error monitoring system", e);
            }
        }, 0, 10, TimeUnit.SECONDS);

        // Run monitoring for 30 seconds
        try {
            Thread.sleep(30000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        monitor.shutdown();
        logger.info("Monitoring completed");
    }
}
