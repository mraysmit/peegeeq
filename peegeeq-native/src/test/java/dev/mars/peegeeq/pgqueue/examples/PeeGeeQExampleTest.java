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

import dev.mars.peegeeq.api.deadletter.DeadLetterMessageInfo;
import dev.mars.peegeeq.api.health.OverallHealthInfo;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.metrics.PeeGeeQMetrics;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
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
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PeeGeeQExampleTest {
    
    private static final Logger logger = LoggerFactory.getLogger(PeeGeeQExampleTest.class);

    // PostgreSQL container configuration
    private static final String DB_NAME = "peegeeq_example";
    private static final String DB_USER = "peegeeq_example";
    private static final String DB_PASSWORD = "peegeeq_example";

    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;

    @BeforeEach
    void setUp(VertxTestContext testContext) {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        logger.info("=== Setting up PeeGeeQ Example Test ===");

        // Display PeeGeeQ logo
        logger.info("");
        logger.info("    ____            ______            ____");
        logger.info("   / __ \\___  ___  / ____/__  ___    / __ \\");
        logger.info("  / /_/ / _ \\/ _ \\/ / __/ _ \\/ _ \\  / / / /");
        logger.info(" / ____/  __/  __/ /_/ /  __/ / /_/ /");
        logger.info("/_/    \\___/\\___/\\____/\\___/\\___/  \\___\\_\\");
        logger.info("");
        logger.info("PostgreSQL Event-Driven Queue System");
        logger.info("JUnit Test - TestContainers PostgreSQL");
        logger.info("");

        // Configure PeeGeeQ to use the container
        Properties testProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.database.pool.min-size", "5")
                .property("peegeeq.database.pool.max-size", "20")
                .property("peegeeq.metrics.enabled", "true")
                .property("peegeeq.migration.enabled", "true")
                .property("peegeeq.migration.auto-migrate", "true")
                .property("peegeeq.health.enabled", "true")
                .property("peegeeq.circuit-breaker.enabled", "false")
                .property("peegeeq.dead-letter.enabled", "true")
                .build();

        // Ensure required schema exists before starting PeeGeeQ
        PeeGeeQTestSchemaInitializer.initializeSchema(
                postgres,
                PostgreSQLTestConstants.TEST_SCHEMA,
                SchemaComponent.NATIVE_QUEUE,
                SchemaComponent.OUTBOX,
                SchemaComponent.DEAD_LETTER_QUEUE
        );

        // Initialize PeeGeeQ Manager
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("default", testProps), new SimpleMeterRegistry());
        manager.start().onSuccess(v -> {
            logger.info("PeeGeeQ Example Test setup completed");
            testContext.completeNow();
        }).onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws Exception {
        logger.info("Tearing down: closing resources and manager");
        logger.info("Cleaning up PeeGeeQ Example Test");

        if (manager != null) {
            manager.closeReactive()
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));

        logger.info("PeeGeeQ Example Test cleanup completed");
    }

    @Test
    void testConfiguration() {
        logger.info("=== Testing Configuration ===");
        
        demonstrateConfiguration(manager);
        
        // Verify configuration is working
        PeeGeeQConfiguration config = manager.getConfiguration();
        assertNotNull(config, "Configuration should not be null");
        assertEquals("default", config.getProfile(), "Profile should be default");
        
        logger.info("Configuration test completed successfully!");
    }

    @Test
    void testHealthChecks(Vertx vertx, VertxTestContext testContext) throws InterruptedException {
        logger.info("=== Testing Health Checks ===");

        // Wait for the reactive health check scheduler to populate statuses
        vertx.setPeriodic(200, id -> {
            OverallHealthInfo h = manager.getHealthCheckManager().getOverallHealth();
            if (h != null && h.getHealthyCount() > 0) {
                vertx.cancelTimer(id);
                testContext.verify(() -> {
                    OverallHealthInfo health = manager.getHealthCheckManager().getOverallHealth();
                    // Log the current health snapshot with details
                    demonstrateHealthChecks(manager);

                    // Verify health checks are working
                    assertNotNull(health, "Health status should not be null");
                    assertTrue(health.getHealthyCount() > 0, "Should have healthy components");

                    logger.info("Health checks test completed successfully!");
                });
                testContext.completeNow();
            }
        });

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS), "Health checks should be populated within 5 seconds");
    }

    @Test
    void testMetrics() {
        logger.info("=== Testing Metrics ===");
        
        demonstrateMetrics(manager);
        
        // Verify metrics are working
        PeeGeeQMetrics metrics = manager.getMetrics();
        assertNotNull(metrics, "Metrics should not be null");
        
        logger.info("Metrics test completed successfully!");
    }

    @Test
    void testCircuitBreaker() {
        logger.info("=== Testing Circuit Breaker ===");
        
        demonstrateCircuitBreaker(manager);
        
        // Verify circuit breaker is working
        var circuitBreakerManager = manager.getCircuitBreakerManager();
        assertNotNull(circuitBreakerManager, "Circuit breaker manager should not be null");
        
        logger.info("Circuit breaker test completed successfully!");
    }

    // testBackpressure deleted 2026-08-09 with the BackpressureManager (metrics-stack review: constructed but never guarded any operation).

    @Test
    void testDeadLetterQueue(VertxTestContext testContext) throws InterruptedException {
        logger.info("=== Testing Dead Letter Queue ===");
        
        demonstrateDeadLetterQueue(manager, testContext);
        
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "DLQ demo should complete");
    }

    @Test
    void testSystemMonitoring(Vertx vertx, VertxTestContext testContext) throws InterruptedException {
        logger.info("=== Testing System Monitoring ===");
        
        monitorSystem(manager, vertx, testContext);
        
        assertTrue(testContext.awaitCompletion(35, TimeUnit.SECONDS), "System monitoring should complete");
    }

    private void demonstrateConfiguration(PeeGeeQManager manager) {
        logger.info("\n === Configuration Demo ===");

        PeeGeeQConfiguration config = manager.getConfiguration();
        logger.info("Profile: {}", config.getProfile());

        logger.info("Database Configuration:");
        logger.info("> Host: {}", config.getString("peegeeq.database.host", "localhost"));
        logger.info("> Port: {}", config.getInt("peegeeq.database.port", 5432));
        logger.info("> Database: {}", config.getString("peegeeq.database.name", "peegeeq"));
        logger.info("> Schema: {}", config.getString("peegeeq.database.schema", PostgreSQLTestConstants.TEST_SCHEMA));
        logger.info("> Pool Min Size: {}", config.getInt("peegeeq.database.pool.min-size", 5));
        logger.info("> Pool Max Size: {}", config.getInt("peegeeq.database.pool.max-size", 20));

        logger.info("Feature Configuration:");
        logger.info("> Metrics Enabled: {}", config.getBoolean("peegeeq.metrics.enabled", true));
        logger.info("> Health Checks Enabled: {}", config.getBoolean("peegeeq.health.enabled", true));
        logger.info("> Circuit Breaker Enabled: {}", config.getCircuitBreakerConfig().isEnabled());
        logger.info("> Dead Letter Enabled: {}", config.getQueueConfig().isDeadLetterEnabled());
        logger.info("> Migration Auto-enabled: {}", config.getBoolean("peegeeq.migration.auto-migrate", true));
    }

    private void demonstrateHealthChecks(PeeGeeQManager manager) {
        logger.info("\n === Health Checks Demo ===");

        OverallHealthInfo health = manager.getHealthCheckManager().getOverallHealth();
        logger.info(">> Overall Health: {}", health.status());
        logger.info("   > Healthy Components: {}", health.getHealthyCount());
        logger.info("   > Degraded Components: {}", health.getDegradedCount());
        logger.info("   > Unhealthy Components: {}", health.getUnhealthyCount());
        logger.info("   > Total Components: {}", health.components().size());

        health.components().forEach((name, status) ->
            logger.info(" {} -> {} ({})", name, status.state(),
                status.message() != null ? status.message() : "OK"));
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
        logger.info("Metrics Summary:");
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
        var cb = circuitBreakerManager.getCircuitBreaker("test-operation");

        if (cb == null) {
            logger.info("Circuit breaker is disabled, skipping demo");
            return;
        }

        // Simulate successful operations
        logger.info("Simulating successful operations...");
        for (int i = 0; i < 5; i++) {
            if (cb.tryAcquirePermission()) {
                cb.onSuccess(1, java.util.concurrent.TimeUnit.NANOSECONDS);
                logger.debug("Circuit breaker: success recorded");
            }
        }

        // Simulate some failures
        logger.info("Simulating failed operations...");
        for (int i = 0; i < 3; i++) {
            if (cb.tryAcquirePermission()) {
                cb.onError(1, java.util.concurrent.TimeUnit.NANOSECONDS, new RuntimeException("Simulated failure " + i));
                logger.debug("Circuit breaker: failure recorded");
            }
        }

        var metrics = circuitBreakerManager.getMetrics("test-operation");
        logger.info("Circuit Breaker Metrics:");
        logger.info("State: {}", metrics.getState());
        logger.info("Successful Calls: {}", metrics.getSuccessfulCalls());
        logger.info("Failed Calls: {}", metrics.getFailedCalls());
        logger.info("Failure Rate: {}%", metrics.getFailureRate());
    }

    // demonstrateBackpressure deleted 2026-08-09 with the BackpressureManager (metrics-stack review: constructed but never guarded any operation).

    private void demonstrateDeadLetterQueue(PeeGeeQManager manager, VertxTestContext testContext) {
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

        dlqManager.moveToDeadLetterQueue("outbox_messages", 1001L, "order-processing",
                        Map.of("orderId", "12345", "customerId", "cust-001", "amount", 99.99),
                        Instant.now().minus(Duration.ofMinutes(5)), "Payment processing failed", 3,
                        headers1, "corr-001", "order-group")
                .compose(v -> dlqManager.moveToDeadLetterQueue("outbox_messages", 1002L, "user-events",
                        Map.of("userId", "user-456", "action", "login", "timestamp", System.currentTimeMillis()),
                        Instant.now().minus(Duration.ofMinutes(2)), "Database connection timeout", 1,
                        headers2, "corr-002", "user-group"))
                .compose(v -> dlqManager.moveToDeadLetterQueue("outbox_messages", 1003L, "notifications",
                        Map.of("email", "user@example.com", "template", "welcome"),
                        Instant.now().minus(Duration.ofMinutes(1)), "Email service unavailable", 5,
                        Map.of("source", "notification-service"), "corr-003", "notification-group"))
                .compose(v -> dlqManager.getStatistics())
                .compose(stats -> {
                    logger.info("Dead Letter Queue Statistics:");
                    logger.info("Total Messages: {}", stats.totalMessages());
                    logger.info("Messages by Topic: (detailed breakdown not available in current API)");

                    return dlqManager.getAllDeadLetterMessages(10, 0);
                })
                .onSuccess(dlqMessages -> {
                    logger.info("Recent Dead Letter Messages:");
                    for (DeadLetterMessageInfo msg : dlqMessages) {
                        logger.info("  ID: {}, Original ID: {}, Topic: {}, Reason: {}, Retry Count: {}",
                            msg.id(), msg.originalId(), msg.topic(), msg.failureReason(), msg.retryCount());
                    }

                    testContext.verify(() -> {
                        assertNotNull(dlqManager, "Dead letter queue manager should not be null");
                    });
                    testContext.completeNow();
                    logger.info("Dead letter queue test completed successfully!");
                })
                .onFailure(testContext::failNow);
    }

    private void monitorSystem(PeeGeeQManager manager, Vertx vertx, VertxTestContext testContext) {
        logger.info("=== System Monitoring ===");
        logger.info("Monitoring system for 30 seconds...");

        int totalChecks = 6; // 30 seconds / 5 seconds per check
        monitorSystemCheck(manager, vertx, testContext, totalChecks);
    }

    private void monitorSystemCheck(
            PeeGeeQManager manager,
            Vertx vertx,
            VertxTestContext testContext,
            int remainingChecks) {
        vertx.timer(5000)
            .compose(ignored -> {
                logger.info("System Status Check:");

                OverallHealthInfo health = manager.getHealthCheckManager().getOverallHealth();
                logger.info("  Health: {} ({} healthy, {} unhealthy)",
                    health.status(), health.getHealthyCount(), health.getUnhealthyCount());

                var metrics = manager.getMetrics().getSummary();
                logger.info("  Messages: {} sent, {} processed, {} failed ({}% success rate)",
                    (long)metrics.getMessagesSent(), (long)metrics.getMessagesProcessed(),
                    (long)metrics.getMessagesFailed(), metrics.getSuccessRate());

                return manager.getDeadLetterQueueManager().getStatistics();
            })
            .onSuccess(dlqStats -> {
                logger.info("  Dead Letter Queue: {} total messages", dlqStats.totalMessages());
                if (remainingChecks == 1) {
                    logger.info("System monitoring completed");
                    testContext.verify(() -> {
                        assertTrue(true, "System monitoring demonstration completed");
                    });
                    testContext.completeNow();
                    logger.info("System monitoring test completed successfully!");
                } else {
                    monitorSystemCheck(manager, vertx, testContext, remainingChecks - 1);
                }
            })
            .onFailure(testContext::failNow);
    }

    // shutdownExecutorGracefully deleted 2026-08-09 with the BackpressureManager (metrics-stack review: constructed but never guarded any operation).
}
