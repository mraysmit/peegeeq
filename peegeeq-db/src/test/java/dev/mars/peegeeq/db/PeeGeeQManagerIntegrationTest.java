package dev.mars.peegeeq.db;

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


import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.health.OverallHealthStatus;
import dev.mars.peegeeq.db.metrics.PeeGeeQMetrics;
import dev.mars.peegeeq.db.resilience.BackpressureManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.mars.peegeeq.test.categories.TestCategories;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.Properties;


import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for PeeGeeQManager with all production readiness features.
 *
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith({SharedPostgresTestExtension.class, VertxExtension.class})
@ResourceLock(value = "dead-letter-queue-database", mode = org.junit.jupiter.api.parallel.ResourceAccessMode.READ_WRITE)
@ResourceLock(value = "system-properties")
public class PeeGeeQManagerIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(PeeGeeQManagerIntegrationTest.class);

    private PeeGeeQManager manager;
    private PeeGeeQConfiguration configuration;

    @BeforeEach
    void setUp() {
        PostgreSQLContainer postgres = SharedPostgresTestExtension.getContainer();

        // Create test configuration
        Properties testProps = new Properties();
        testProps.setProperty("peegeeq.database.host", postgres.getHost());
        testProps.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        testProps.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        testProps.setProperty("peegeeq.database.username", postgres.getUsername());
        testProps.setProperty("peegeeq.database.password", postgres.getPassword());
        testProps.setProperty("peegeeq.database.ssl.enabled", "false");
        testProps.setProperty("peegeeq.database.schema", "public"); // Use public schema for test container

        // Set valid pool configuration
        testProps.setProperty("peegeeq.database.pool.min-size", "2");
        testProps.setProperty("peegeeq.database.pool.max-size", "3");
        testProps.setProperty("peegeeq.database.pool.shared", "false");
        testProps.setProperty("peegeeq.database.pool.idle-timeout-ms", "2000");
        testProps.setProperty("peegeeq.database.pool.connection-timeout-ms", "5000");

        // Reduce timeouts for faster tests
        testProps.setProperty("peegeeq.health.check-interval", "PT5S");
        testProps.setProperty("peegeeq.metrics.reporting-interval", "PT10S");
        testProps.setProperty("peegeeq.circuit-breaker.enabled", "true");
        // Disable auto-migration since schema is already initialized by SharedPostgresTestExtension
        testProps.setProperty("peegeeq.migration.enabled", "false");
        testProps.setProperty("peegeeq.migration.auto-migrate", "false");

        // Override system properties for test
        testProps.forEach((key, value) -> System.setProperty(key.toString(), value.toString()));

        configuration = new PeeGeeQConfiguration("test");
        manager = new PeeGeeQManager(configuration, new SimpleMeterRegistry());
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        if (manager != null) {
            manager.closeReactive()
                .onFailure(t -> logger.warn("Error during manager teardown: {}", t.getMessage()))
                .eventually(() -> {
                    System.getProperties().entrySet().removeIf(entry ->
                        entry.getKey().toString().startsWith("peegeeq."));
                    return Future.succeededFuture();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
        } else {
            System.getProperties().entrySet().removeIf(entry ->
                entry.getKey().toString().startsWith("peegeeq."));
            testContext.completeNow();
        }
    }

    @Test
    void testManagerInitialization() {
        assertNotNull(manager);
        assertNotNull(manager.getConfiguration());
        assertNotNull(manager.getDatabaseService());
        assertNotNull(manager.getHealthCheckManager());
        assertNotNull(manager.getMetrics());
        assertNotNull(manager.getCircuitBreakerManager());
        assertNotNull(manager.getBackpressureManager());
        assertNotNull(manager.getDeadLetterQueueManager());
    }

    @Test
    void testStartAndStop(VertxTestContext testContext) {
        manager.start()
            .compose(v -> manager.getVertx().timer(2000))
            .compose(v -> {
                assertTrue(manager.isHealthy(), "System should be healthy after start");
                return manager.getSystemStatus();
            })
            .compose(status -> {
                assertNotNull(status);
                assertTrue(status.isStarted());
                assertEquals("test", status.getProfile());
                return manager.stop();
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testDatabaseMigration(VertxTestContext testContext) {
        manager.start()
            .compose(v -> manager.getDatabaseService().getConnectionProvider()
                .withConnection("peegeeq-main", connection ->
                    connection.query("SELECT COUNT(*) FROM outbox")
                        .execute()
                        .map(rowSet -> {
                            var row = rowSet.iterator().next();
                            long count = row.getLong(0);
                            assertTrue(count >= 0, "Outbox table should exist and be queryable");
                            return count;
                        })
                ))
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testHealthChecks(VertxTestContext testContext) {
        manager.start()
            .compose(v -> manager.getVertx().timer(3000))
            .compose(v -> {
                assertTrue(manager.isHealthy());

                OverallHealthStatus healthStatus = manager.getHealthCheckManager().getOverallHealthInternal();
                assertNotNull(healthStatus);
                assertTrue(healthStatus.isHealthy());
                assertFalse(healthStatus.getComponents().isEmpty());

                assertTrue(healthStatus.getComponents().containsKey("database"));
                assertTrue(healthStatus.getComponents().containsKey("memory"));
                return Future.succeededFuture();
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testMetrics(VertxTestContext testContext) {
        manager.start()
            .compose(v -> {
                PeeGeeQMetrics metrics = manager.getMetrics();
                assertNotNull(metrics);

                metrics.recordMessageSent("test-topic");
                metrics.recordMessageReceived("test-topic");
                metrics.recordMessageProcessed("test-topic", Duration.ofMillis(100));

                PeeGeeQMetrics.MetricsSummary summary = metrics.getSummary();
                assertNotNull(summary);
                assertEquals(1.0, summary.getMessagesSent());
                assertEquals(1.0, summary.getMessagesReceived());
                assertEquals(1.0, summary.getMessagesProcessed());
                return Future.succeededFuture();
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testCircuitBreaker(VertxTestContext testContext) {
        manager.start()
            .compose(v -> {
                var circuitBreakerManager = manager.getCircuitBreakerManager();
                assertNotNull(circuitBreakerManager);

                var cb = circuitBreakerManager.getCircuitBreaker("test-operation");
                assertNotNull(cb);
                assertTrue(circuitBreakerManager.getCircuitBreakerNames().contains("test-operation"));

                var metrics = circuitBreakerManager.getMetrics("test-operation");
                assertNotNull(metrics);
                assertTrue(metrics.isEnabled());
                assertEquals("CLOSED", metrics.getState());
                return Future.succeededFuture();
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testBackpressure(VertxTestContext testContext) {
        manager.start()
            .compose(v -> {
                try {
                    BackpressureManager backpressureManager = manager.getBackpressureManager();
                    assertNotNull(backpressureManager);

                    String result = backpressureManager.execute("test-op", () -> "success");
                    assertEquals("success", result);

                    BackpressureManager.BackpressureMetrics metrics = backpressureManager.getMetrics();
                    assertNotNull(metrics);
                    assertEquals(1, metrics.getSuccessfulOperations());
                    assertEquals(0, metrics.getFailedOperations());
                    return Future.succeededFuture();
                } catch (Exception e) {
                    return Future.failedFuture(e);
                }
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testDeadLetterQueue(VertxTestContext testContext) {
        manager.start()
            .compose(v -> {
                var dlqManager = manager.getDeadLetterQueueManager();
                assertNotNull(dlqManager);
                return dlqManager.cleanupOldMessages(1);
            })
            .compose(cleaned -> manager.getVertx().timer(100))
            .compose(v -> manager.getDeadLetterQueueManager().getStatistics())
            .compose(stats -> {
                assertNotNull(stats);
                assertTrue(stats.totalMessages() >= 0,
                    "Total messages should be non-negative, got: " + stats.totalMessages());
                return Future.succeededFuture();
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testConfigurationProfiles() {
        PostgreSQLContainer postgres = SharedPostgresTestExtension.getContainer();

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
    void testSystemStatusReporting(VertxTestContext testContext) {
        manager.start()
            .compose(v -> manager.getVertx().timer(2000))
            .compose(v -> manager.getSystemStatus())
            .compose(status -> {
                assertNotNull(status);
                assertTrue(status.isStarted());
                assertEquals("test", status.getProfile());

                assertNotNull(status.getHealthStatus());
                assertNotNull(status.getMetricsSummary());
                assertNotNull(status.getBackpressureMetrics());
                assertNotNull(status.getDeadLetterStats());

                String statusString = status.toString();
                assertNotNull(statusString);
                assertTrue(statusString.contains("started=true"));
                assertTrue(statusString.contains("profile='test'"));
                return Future.succeededFuture();
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testResourceCleanup(VertxTestContext testContext) {
        manager.start()
            .compose(v -> manager.getSystemStatus())
            .compose(statusBeforeClose -> {
                assertTrue(statusBeforeClose.isStarted());
                manager.close();
                return manager.stop();
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }
}
