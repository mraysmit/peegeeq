package dev.mars.peegeeq.examples.patterns;

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
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.db.metrics.PeeGeeQMetrics;
import dev.mars.peegeeq.db.resilience.BackpressureManager;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Map;
import java.util.Properties;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * INFRASTRUCTURE TEST: PeeGeeQ Manager Lifecycle and TestContainers Integration
 *
 *   NOTE: This test does NOT create or test any message queues.
 *
 * WHAT THIS TESTS:
 * - PeeGeeQ Manager initialization and lifecycle management
 * - TestContainers PostgreSQL integration and configuration
 * - System properties configuration for database connections
 * - Basic infrastructure setup and teardown patterns
 *
 * BUSINESS VALUE:
 * - Ensures PeeGeeQ can properly initialize in test environments
 * - Validates TestContainers integration works correctly
 * - Provides confidence in infrastructure setup patterns
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
class PeeGeeQExampleTest {
    private static final Logger logger = LoggerFactory.getLogger(PeeGeeQExampleTest.class);
    private Vertx vertx;
    
    private final ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errContent = new ByteArrayOutputStream();
    private final PrintStream originalOut = System.out;
    private final PrintStream originalErr = System.err;
    
    @BeforeEach
    void setUpStreams(Vertx vertx) {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        this.vertx = vertx;
        System.setOut(new PrintStream(outContent));
        System.setErr(new PrintStream(errContent));
    }
    
    @AfterEach
    void restoreStreams() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }
    
    // NOTE: Configuration validation tests have been consolidated into
    // ConfigurationValidationTest.java for better maintainability and business focus

    @Test
    void testPeeGeeQExampleWithTestContainers(VertxTestContext testContext) throws Exception {
        logger.info("Testing PeeGeeQExample with TestContainers database");

        // Start PostgreSQL container
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE)) {
            postgres.withDatabaseName("peegeeq_test")
                    .withUsername("peegeeq_test")
                    .withPassword("peegeeq_test");

            postgres.start();
            logger.info("PostgreSQL container started: {}", postgres.getJdbcUrl());

            // Configure database connection properties
            Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA).build();

            logger.info("Starting PeeGeeQ Example functionality test");

            // Initialize database schema before starting manager
            PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.ALL);

            runAllDemonstrations(testProps)
                .onSuccess(v -> {
                    logger.info("PeeGeeQExample test completed successfully");
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);

            assertTrue(testContext.awaitCompletion(120, TimeUnit.SECONDS),
                "PeeGeeQExample demonstrations did not complete within timeout");
        }
    }

    @Test
    void runComprehensivePeeGeeQDemo(VertxTestContext testContext) throws Exception {
        logger.info("** Starting Comprehensive PeeGeeQ Demo Test **");
        logger.info("This test demonstrates all PeeGeeQ features with proper TestContainers lifecycle management");

        // Start PostgreSQL container with proper JUnit lifecycle management
        logger.info(">> Starting PostgreSQL container...");
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE)) {
            postgres.withDatabaseName("peegeeq_demo")
                    .withUsername("peegeeq_demo")
                    .withPassword("peegeeq_demo")
                    .withSharedMemorySize(256 * 1024 * 1024L) // 256MB for better performance
                    .withReuse(false); // Always start fresh for test

            postgres.start();
            logger.info(">> PostgreSQL container started successfully");
            logger.info("   > Container URL: {}", postgres.getJdbcUrl());
            logger.info("   > Username: {}", postgres.getUsername());
            logger.info("   > Host: {}:{}", postgres.getHost(), postgres.getFirstMappedPort());

            // Configure database connection properties
            Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                    .property("peegeeq.database.pool.min-size", "2")
                    .property("peegeeq.database.pool.max-size", "10")
                    .property("peegeeq.metrics.enabled", "true")
                    .property("peegeeq.health.enabled", "true")
                    .property("peegeeq.circuit-breaker.enabled", "true")
                    .property("peegeeq.migration.enabled", "true")
                    .property("peegeeq.migration.auto-migrate", "true")
                    .build();

            // Initialize database schema before starting manager
            PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.ALL);

            // Run comprehensive demonstrations
            runAllDemonstrations(testProps)
                .onSuccess(v -> {
                    logger.info("Comprehensive PeeGeeQ Demo Test completed successfully!");
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);

            assertTrue(testContext.awaitCompletion(180, TimeUnit.SECONDS),
                "Comprehensive demo did not complete within timeout");
        }
    }

    /**
     * Runs all PeeGeeQ feature demonstrations as a chained {@link Future} sequence.
     * The manager is closed in {@code .eventually(...)} so it is released even on failure.
     */
    private Future<Void> runAllDemonstrations(Properties testProps) {
        logger.info("...Starting PeeGeeQ feature demonstrations...");

        final PeeGeeQManager manager = new PeeGeeQManager(
            new PeeGeeQConfiguration("default", testProps), new SimpleMeterRegistry());

        return manager.start()
            .onSuccess(v -> logger.info("PeeGeeQ Manager started successfully"))
            .compose(v -> { demonstrateConfiguration(manager); return Future.<Void>succeededFuture(); })
            .compose(v -> demonstrateHealthChecks(manager))
            .compose(v -> { demonstrateMetrics(manager); return Future.<Void>succeededFuture(); })
            .compose(v -> { demonstrateCircuitBreaker(manager); return Future.<Void>succeededFuture(); })
            .compose(v -> demonstrateBackpressure(manager))
            .compose(v -> demonstrateDeadLetterQueue(manager))
            .compose(v -> monitorSystemBriefly(manager))
            .eventually(() -> manager.closeReactive()
                    .onFailure(e -> logger.warn("Error closing manager", e)));
    }

    private void demonstrateConfiguration(PeeGeeQManager manager) {
        logger.info("=== Configuration Demo ===");

        PeeGeeQConfiguration config = manager.getConfiguration();
        logger.info(">> Profile: {}", config.getProfile());
        logger.info(">> Database: {}:{}/{}",
            config.getString("peegeeq.database.host"),
            config.getInt("peegeeq.database.port", 5432),
            config.getString("peegeeq.database.name"));
        logger.info("Username: {}", config.getString("peegeeq.database.username"));
        logger.info("SSL Enabled: {}", config.getBoolean("peegeeq.database.ssl.enabled", false));
        logger.info("Pool Min/Max: {}/{}",
            config.getInt("peegeeq.database.pool.min-size", 2),
            config.getInt("peegeeq.database.pool.max-size", 10));
        logger.info("Metrics Enabled: {}", config.getMetricsConfig().isEnabled());
        logger.info("Circuit Breaker Enabled: {}", config.getCircuitBreakerConfig().isEnabled());
        logger.info("Dead Letter Enabled: {}", config.getQueueConfig().isDeadLetterEnabled());
        logger.info("Migration Auto-enabled: {}", config.getBoolean("peegeeq.migration.auto-migrate", true));
    }

    private Future<Void> demonstrateHealthChecks(PeeGeeQManager manager) {
        logger.info("=== Health Checks Demo ===");

        boolean isHealthy = manager.isHealthy();
        logger.info(">> Overall Health: {}", isHealthy ? "UP" : "DOWN");

        // Get system status which includes health information
        return manager.getSystemStatus()
            .onSuccess(status -> {
                logger.info("System Status: {}", status);
                // Note: Individual health component details are not exposed in the current API
                // This is a simplified demonstration
                logger.info("Health check demonstration completed");
            })
            .mapEmpty();
    }

    private void demonstrateMetrics(PeeGeeQManager manager) {
        logger.info("=== Metrics Demo ===");
        logger.info("Simulating message processing...");

        PeeGeeQMetrics metrics = manager.getMetrics();

        // Simulate some metrics using the correct API
        for (int i = 0; i < 10; i++) {
            metrics.incrementCounter("messages.sent", Map.of("topic", "demo-topic"));
            metrics.incrementCounter("messages.received", Map.of("topic", "demo-topic"));
            if (i < 6) {
                metrics.incrementCounter("messages.processed", Map.of("topic", "demo-topic"));
            } else {
                metrics.incrementCounter("messages.failed", Map.of("topic", "demo-topic"));
            }
        }

        // Display metrics summary
        PeeGeeQMetrics.MetricsSummary summary = metrics.getSummary();
        logger.info("> Messages Sent: {}", summary.getMessagesSent());
        logger.info("> Messages Received: {}", summary.getMessagesReceived());
        logger.info("> Messages Processed: {}", summary.getMessagesProcessed());
        logger.info("> Messages Failed: {}", summary.getMessagesFailed());
        logger.info("> Success Rate: {}%", summary.getSuccessRate());
        logger.info("> Outbox Queue Depth: {}", summary.getOutboxQueueDepth());
        logger.info("> Native Queue Depth: {}", summary.getNativeQueueDepth());
    }

    private void demonstrateCircuitBreaker(PeeGeeQManager manager) {
        logger.info("=== Circuit Breaker Demo ===");

        var circuitBreakerManager = manager.getCircuitBreakerManager();
        var cb = circuitBreakerManager.getCircuitBreaker("demo-operation");

        // Simulate successful operations using the production caller pattern:
        // tryAcquirePermission()  execute  onSuccess() / onError()
        for (int i = 0; i < 5; i++) {
            if (cb.tryAcquirePermission()) {
                cb.onSuccess(1, java.util.concurrent.TimeUnit.NANOSECONDS);
                logger.info("Circuit breaker: success recorded");
            }
        }

        // Simulate some failures
        for (int i = 0; i < 3; i++) {
            if (cb.tryAcquirePermission()) {
                cb.onError(1, java.util.concurrent.TimeUnit.NANOSECONDS, new RuntimeException("Simulated failure " + i));
                logger.info("Circuit breaker: failure recorded");
            }
        }

        // Display circuit breaker metrics
        var metrics = circuitBreakerManager.getMetrics("demo-operation");
        logger.info("> Circuit Breaker State: {}", metrics.getState());
        logger.info("> Successful Calls: {}", metrics.getSuccessfulCalls());
        logger.info("> Failed Calls: {}", metrics.getFailedCalls());
        logger.info("> Failure Rate: {}%", metrics.getFailureRate());

        logger.info("Circuit breaker demonstration completed");
    }

    private Future<Void> demonstrateBackpressure(PeeGeeQManager manager) {
        logger.info("=== Backpressure Demo ===");
        logger.info("Simulating concurrent operations...");

        BackpressureManager backpressureManager = manager.getBackpressureManager();
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(5);

        for (int i = 0; i < 10; i++) { // Reduced for test
            final int operationId = i;
            executor.submit(() -> {
                try {
                    String result = backpressureManager.execute("demo-backpressure", () ->
                        "Operation " + operationId + " completed"
                    );
                    logger.debug("Backpressure result: {}", result);
                } catch (BackpressureManager.BackpressureException e) {
                    logger.warn("Backpressure rejected operation {}: {}", operationId, e.getMessage());
                } catch (Exception e) {
                    logger.error("Operation {} failed", operationId, e);
                }
            });
        }

        // Wait briefly for operations to complete using Vert.x timer
        return vertx.timer(1000)
            .onSuccess(v -> {
                BackpressureManager.BackpressureMetrics metrics = backpressureManager.getMetrics();
                logger.info(">> Total Requests: {}", metrics.getTotalRequests());
                logger.info(" > Successful Operations: {}", metrics.getSuccessfulOperations());
                logger.info(" > Rejected Requests: {}", metrics.getRejectedRequests());
                logger.info(" > Current Utilization: {}%", metrics.getUtilization() * 100);
                logger.info(" > Success Rate: {}%", metrics.getCurrentSuccessRate() * 100);
            })
            .eventually(() -> {
                shutdownExecutorGracefully(executor, "backpressure-demo");
                return Future.<Void>succeededFuture();
            })
            .mapEmpty();
    }

    private Future<Void> demonstrateDeadLetterQueue(PeeGeeQManager manager) {
        logger.info("=== Dead Letter Queue Demo ===");

        var deadLetterManager = manager.getDeadLetterQueueManager();

        // Display dead letter queue statistics (initially empty)
        return deadLetterManager.getStatistics()
            .onSuccess(stats -> {
                logger.info(">> Dead Letter Queue Stats:");
                logger.info("  > Total Messages: {}", stats.totalMessages());
                logger.info("  > Is Empty: {}", stats.isEmpty());

                // Note: The moveToDeadLetter method signature is different than expected
                // This is a simplified demonstration showing the DLQ manager is available
                logger.info("Dead letter queue manager is available and functional");

                // In a real scenario, messages would be moved to DLQ automatically
                // when they fail processing after max retries
            })
            .mapEmpty();
    }

    private Future<Void> monitorSystemBriefly(PeeGeeQManager manager) {
        logger.info("=== System Monitoring ===");
        logger.info("Monitoring system briefly for test...");

        ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor();

        monitor.scheduleAtFixedRate(() -> {
            manager.getSystemStatus()
                .onSuccess(status -> {
                    logger.info("System Status: {}", status);
                    if (!status.getHealthStatus().isHealthy()) {
                        logger.warn("System health degraded!");
                        status.getHealthStatus().getComponents().forEach((name, health) -> {
                            if (!health.isHealthy()) {
                                logger.warn("  Unhealthy component: {} - {}", name, health.getMessage());
                            }
                        });
                    }
                })
                .onFailure(err -> logger.error("Error monitoring system", err));
        }, 0, 2, TimeUnit.SECONDS); // Faster for test

        // Run monitoring for 5 seconds (shorter for test)
        return vertx.timer(5000)
            .eventually(() -> {
                shutdownExecutorGracefully(monitor, "system-monitor");
                logger.info("Monitoring completed");
                return Future.<Void>succeededFuture();
            })
            .mapEmpty();
    }

    /**
     * Gracefully shuts down an executor service with proper timeout handling.
     */
    private void shutdownExecutorGracefully(ExecutorService executor, String name) {
        logger.debug("Shutting down executor: {}", name);

        executor.shutdown();

        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.warn("Executor {} did not terminate gracefully, forcing shutdown", name);
                executor.shutdownNow();

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
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
