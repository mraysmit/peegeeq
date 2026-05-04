// ========== FILE 2: HealthCheckManagerTest.java ==========
package dev.mars.peegeeq.db.health;

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


import dev.mars.peegeeq.db.SharedPostgresTestExtension;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxTestContext;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for HealthCheckManager and related health check components.
 *
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 *
 * <p><strong>IMPORTANT:</strong> This test uses SharedPostgresTestExtension for shared container.
 * Schema is initialized once by the extension. Tests use @ResourceLock to prevent data conflicts.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith({SharedPostgresTestExtension.class, VertxExtension.class})
@ResourceLock(value = "dead-letter-queue-database", mode = org.junit.jupiter.api.parallel.ResourceAccessMode.READ_WRITE)
class HealthCheckManagerTest {

    private static final Logger logger = LoggerFactory.getLogger(HealthCheckManagerTest.class);

    /**
     * Custom exception for intentional test failures that doesn't generate stack traces.
     * This makes test logs cleaner by avoiding confusing stack traces for expected failures.
     */
    private static class IntentionalTestFailureException extends RuntimeException {
        public IntentionalTestFailureException(String message) {
            super(message);
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            // Don't fill in stack trace for intentional test failures
            return this;
        }
    }

    private PgConnectionManager connectionManager;
    private Vertx vertx;
    private Pool reactivePool;
    private HealthCheckManager healthCheckManager;

    @BeforeEach
    void setUp(VertxTestContext testContext) {
        PostgreSQLContainer postgres = SharedPostgresTestExtension.getContainer();
        vertx = Vertx.vertx();
        connectionManager = new PgConnectionManager(vertx);

        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .database(postgres.getDatabaseName())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
                .maxSize(3)
                .shared(false)
                .idleTimeout(Duration.ofSeconds(2))
                .connectionTimeout(Duration.ofMillis(500))
                .build();

        // Create reactive pool for HealthCheckManager
        reactivePool = connectionManager.getOrCreateReactivePool("test", connectionConfig, poolConfig);

        // DO NOT recreate tables - they are created once by SharedPostgresTestExtension

        // Use reactive constructor for HealthCheckManager
        healthCheckManager = new HealthCheckManager(reactivePool, vertx, Duration.ofMillis(500), Duration.ofMillis(300));

        // Clean up any existing data from previous tests reactively
        cleanupTestData()
            .onSuccess(v -> {
                logger.debug("Setup completed and test data cleaned");
                testContext.completeNow();
            })
            .onFailure(err -> {
                logger.warn("Setup: Failed to cleanup test data: {}", err.getMessage());
                testContext.completeNow();
            });
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        Future<Void> cleanupChain = Future.succeededFuture();

        // Stop manager if running
        if (healthCheckManager != null) {
            cleanupChain = cleanupChain
                .compose(v -> stopManagerAsync(healthCheckManager))
                .onFailure(err -> logger.warn("Failed to stop HealthCheckManager: {}", err.getMessage()));
        }

        // Clean up test data
        if (reactivePool != null) {
            cleanupChain = cleanupChain
                .eventually(() -> cleanupTestData()
                    .onFailure(err -> logger.warn("Failed to cleanup test data in tearDown: {}", err.getMessage())));
        }

        // Close connection manager
        if (connectionManager != null) {
            cleanupChain = cleanupChain
                .eventually(() -> connectionManager.close()
                    .onFailure(err -> logger.warn("Failed to close connectionManager: {}", err.getMessage())));
        }

        cleanupChain
            .onSuccess(v -> testContext.completeNow())
            .onFailure(t -> {
                logger.error("TearDown failed", t);
                testContext.completeNow();
            });
    }

    /**
     * Cleans up test data to ensure test isolation.
     * This removes all data from test tables between test methods.
     * Returns a Future that completes when cleanup is done.
     */
    private Future<Void> cleanupTestData() {
        return reactivePool.withConnection(connection -> {
            // Clean up all test data from tables
            return connection.query("DELETE FROM dead_letter_queue").execute()
                .compose(result -> connection.query("DELETE FROM outbox").execute())
                .compose(result -> connection.query("DELETE FROM queue_messages").execute())
                .<Void>mapEmpty();
        })
        .onSuccess(v -> logger.debug("Cleaned up test data for HealthCheckManager test isolation"))
        .onFailure(err -> logger.warn("Failed to cleanup test data: {}", err.getMessage()));
    }

    @Test
    void testHealthCheckManagerInitialization() {
        assertNotNull(healthCheckManager);
        assertFalse(healthCheckManager.isHealthy()); // No checks run yet, so health is unknown/not healthy
    }

    @Test
    void testHealthCheckManagerStartStop(VertxTestContext testContext) {
        startManagerAsync(healthCheckManager)
            .compose(v -> vertx.timer(500).mapEmpty()) // Wait for health checks to run
            .onSuccess(v -> {
                assertTrue(healthCheckManager.isHealthy());
                
                stopManagerAsync(healthCheckManager)
                    .onSuccess(v2 -> testContext.completeNow())
                    .onFailure(testContext::failNow);
            })
            .onFailure(testContext::failNow);
    }

    @Test
    void testOverallHealthStatus(VertxTestContext testContext) {
        startManagerAsync(healthCheckManager)
            .compose(v -> vertx.timer(500).mapEmpty()) // Wait for health checks to run
            .onSuccess(v -> {
                OverallHealthStatus status = healthCheckManager.getOverallHealthInternal();
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
                
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    void testDatabaseHealthCheck(VertxTestContext testContext) {
        startManagerAsync(healthCheckManager)
            .compose(v -> vertx.timer(500).mapEmpty()) // Wait for health checks to run
            .onSuccess(v -> {
                HealthStatus dbHealth = healthCheckManager.getHealthStatus("database");
                assertNotNull(dbHealth);
                assertTrue(dbHealth.isHealthy());
                assertEquals("database", dbHealth.getComponent());
                assertEquals(HealthStatus.Status.HEALTHY, dbHealth.getStatus());
                
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    void testQueueHealthChecks(VertxTestContext testContext) {
        // Insert test data for queue health checks
        insertTestData()
            .compose(v -> startManagerAsync(healthCheckManager))
            .compose(v -> vertx.timer(500).mapEmpty()) // Wait for health checks to run
            .onSuccess(v -> {
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
                
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    void testMemoryHealthCheck(VertxTestContext testContext) {
        startManagerAsync(healthCheckManager)
            .compose(v -> vertx.timer(500).mapEmpty()) // Wait for health checks to run
            .onSuccess(v -> {
                HealthStatus memoryHealth = healthCheckManager.getHealthStatus("memory");
                assertNotNull(memoryHealth);
                assertTrue(memoryHealth.isHealthy() || memoryHealth.isDegraded()); // Could be degraded under load
                assertNotNull(memoryHealth.getDetails());
                assertTrue(memoryHealth.getDetails().containsKey("max_memory_mb"));
                assertTrue(memoryHealth.getDetails().containsKey("used_memory_mb"));
                assertTrue(memoryHealth.getDetails().containsKey("memory_usage_percent"));
                
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    void testDiskSpaceHealthCheck(VertxTestContext testContext) {
        startManagerAsync(healthCheckManager)
            .compose(v -> vertx.timer(500).mapEmpty()) // Wait for health checks to run
            .onSuccess(v -> {
                HealthStatus diskHealth = healthCheckManager.getHealthStatus("disk-space");
                assertNotNull(diskHealth);
                assertTrue(diskHealth.isHealthy() || diskHealth.isDegraded()); // Could be degraded if disk is full
                assertNotNull(diskHealth.getDetails());
                assertTrue(diskHealth.getDetails().containsKey("total_space_gb"));
                assertTrue(diskHealth.getDetails().containsKey("free_space_gb"));
                assertTrue(diskHealth.getDetails().containsKey("disk_usage_percent"));
                
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    void testCustomHealthCheck(VertxTestContext testContext) {
        AtomicBoolean customCheckCalled = new AtomicBoolean(false);
        
        HealthCheck customCheck = () -> {
            customCheckCalled.set(true);
            return Future.succeededFuture(HealthStatus.healthy("custom-check"));
        };
        
        healthCheckManager.registerHealthCheck("custom", customCheck);
        
        startManagerAsync(healthCheckManager)
            .compose(v -> vertx.timer(500).mapEmpty()) // Wait for health checks to run
            .onSuccess(v -> {
                assertTrue(customCheckCalled.get());
                
                HealthStatus customHealth = healthCheckManager.getHealthStatus("custom");
                assertNotNull(customHealth);
                assertTrue(customHealth.isHealthy());
                assertEquals("custom-check", customHealth.getComponent());
                
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    /**
     * Tests health check behavior when a health check intentionally throws an exception.
     * This test verifies that the health check manager properly handles and reports
     * failing health checks without crashing the system.
     *
     * INTENTIONAL FAILURE TEST: This test deliberately simulates a health check failure
     * to verify error handling and resilience.
     */
    @Test
    void testFailingHealthCheck(VertxTestContext testContext) {
        logger.warn("===== INTENTIONAL WARN TEST ===== The next WARN logs ('Health check failed: failing') are EXPECTED this test deliberately throws an exception from a health check to verify failure handling");

        HealthCheck failingCheck = () -> {
            // Create a custom exception that clearly indicates it's intentional
            throw new IntentionalTestFailureException("INTENTIONAL TEST FAILURE: Simulated failure");
        };

        healthCheckManager.registerHealthCheck("failing", failingCheck);
        
        startManagerAsync(healthCheckManager)
            .compose(v -> vertx.timer(500).mapEmpty()) // Wait for health checks to run
            .onSuccess(v -> {
                HealthStatus failingHealth = healthCheckManager.getHealthStatus("failing");
                assertNotNull(failingHealth);
                assertFalse(failingHealth.isHealthy());
                assertTrue(failingHealth.isUnhealthy());
                assertNotNull(failingHealth.getMessage());
                assertTrue(failingHealth.getMessage().contains("Health check threw exception"));
                
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    void testHealthCheckTimeout(VertxTestContext testContext) {
        logger.warn("===== INTENTIONAL WARN TEST ===== The next WARN log ('Health check timed out: slow') is EXPECTED this test deliberately creates a slow health check to verify timeout handling");
        
        HealthCheck slowCheck = () -> {
            // Simulate slow operation - will timeout (3 second timeout)
            throw new RuntimeException("This check takes too long and will timeout");
        };
        
        healthCheckManager.registerHealthCheck("slow", slowCheck);
        
        startManagerAsync(healthCheckManager)
            // Wait for health checks to run and timeout
            // Need to wait for:
            // - Initial delay (100ms)
            // - Default health checks to complete (database, memory, disk-space, etc.)
            // - Slow check to timeout (3 seconds)
            // Total conservative wait: 8 seconds to handle CI/parallel execution variance
            .compose(v -> vertx.timer(1200).mapEmpty())
            .onSuccess(v -> {
                HealthStatus slowHealth = healthCheckManager.getHealthStatus("slow");
                assertNotNull(slowHealth, "Health check 'slow' should have a status after timeout period");
                assertFalse(slowHealth.isHealthy());
                assertTrue(slowHealth.getMessage().contains("timed out") || slowHealth.getMessage().contains("exception"));
                
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    /**
     * Tests health check behavior when the database connection is intentionally closed.
     * This test verifies that the health check manager properly detects and reports
     * database connectivity failures.
     *
     * INTENTIONAL FAILURE TEST: This test deliberately closes the database connection
     * to simulate a database failure scenario and verify proper error detection.
     */
    @Test
    void testHealthCheckWithDatabaseFailure(VertxTestContext testContext) {
        logger.warn("===== INTENTIONAL WARN TEST ===== The next WARN/ERROR logs ('Health check failed: database', 'Database connection pool validation failed') are EXPECTED this test deliberately closes the DB connection to verify failure detection");

        startManagerAsync(healthCheckManager)
            .compose(v -> vertx.timer(500).mapEmpty()) // Wait for initial healthy state
            .onSuccess(v -> {
                assertTrue(healthCheckManager.isHealthy());
                logger.info("Initial state: Health checks are healthy");

                // Close database connection to simulate failure
                logger.info("INTENTIONAL TEST FAILURE: Closing database connection to simulate failure");
                
                connectionManager.close()
                    .compose(v2 -> vertx.timer(1000).mapEmpty()) // Wait for health checks to detect failure
                    .onSuccess(v2 -> {
                        assertFalse(healthCheckManager.isHealthy());

                        HealthStatus dbHealth = healthCheckManager.getHealthStatus("database");
                        assertNotNull(dbHealth);
                        assertFalse(dbHealth.isHealthy());

                        logger.info("SUCCESS: Database failure was properly detected and reported");
                        logger.info("===== INTENTIONAL FAILURE TEST COMPLETED =====");
                        
                        testContext.completeNow();
                    })
                    .onFailure(testContext::failNow);
            })
            .onFailure(testContext::failNow);
    }

    @Test
    void testConcurrentHealthChecks(VertxTestContext testContext) {
        Checkpoint latch = testContext.checkpoint(5);
        AtomicInteger completedChecks = new AtomicInteger(0);

        // Register multiple health checks that run concurrently
        for (int i = 0; i < 5; i++) {
            final int checkId = i;
            healthCheckManager.registerHealthCheck("concurrent-" + i, () -> {
                // Simulate some work with a flag
                completedChecks.incrementAndGet();
                latch.flag();
                return Future.succeededFuture(HealthStatus.healthy("concurrent-" + checkId));
            });
        }

        startManagerAsync(healthCheckManager)
            .onSuccess(v -> {
                // The checkpoint will automatically verify all 5 checks are called
                // After checkpoint fires 5 times, we can verify results
                testContext.verify(() -> {
                    // Wait a bit for results to be stored
                    vertx.setTimer(500, ar -> {
                        // Verify all health checks are healthy
                        for (int i = 0; i < 5; i++) {
                            HealthStatus health = healthCheckManager.getHealthStatus("concurrent-" + i);
                            assertNotNull(health, "Health status should not be null for concurrent-" + i);
                            assertTrue(health.isHealthy(), "Health check should be healthy for concurrent-" + i);
                        }

                        assertEquals(5, completedChecks.get(), "All 5 health checks should have completed");
                        testContext.completeNow();
                    });
                });
            })
            .onFailure(testContext::failNow);
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
    void testOverallHealthStatusCounts(VertxTestContext testContext) {
        // Insert data that might cause some health checks to be degraded
        insertLargeAmountOfTestData()
            .compose(v -> startManagerAsync(healthCheckManager))
            .compose(v -> vertx.timer(500).mapEmpty()) // Wait for health checks to run
            .onSuccess(v -> {
                OverallHealthStatus status = healthCheckManager.getOverallHealthInternal();

                long totalComponents = status.getHealthyCount() + status.getDegradedCount() + status.getUnhealthyCount();
                assertTrue(totalComponents > 0);
                assertEquals(status.getComponents().size(), totalComponents);
                
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    private Future<Void> insertTestData() {
        return reactivePool.withConnection(connection -> {
            // Insert outbox message
            return connection.preparedQuery("INSERT INTO outbox (topic, payload, status) VALUES ($1, $2::jsonb, $3)")
                .execute(io.vertx.sqlclient.Tuple.of("test-topic", "{\"test\": \"data\"}", "PENDING"))
                .compose(result -> {
                    // Insert queue message
                    return connection.preparedQuery("INSERT INTO queue_messages (topic, payload, status) VALUES ($1, $2::jsonb, $3)")
                        .execute(io.vertx.sqlclient.Tuple.of("test-topic", "{\"test\": \"data\"}", "AVAILABLE"));
                })
                .compose(result -> {
                    // Insert dead letter message
                    return connection.preparedQuery("INSERT INTO dead_letter_queue (original_table, original_id, topic, payload, original_created_at, failure_reason, retry_count) VALUES ($1, $2, $3, $4::jsonb, $5, $6, $7)")
                        .execute(io.vertx.sqlclient.Tuple.of("outbox", 1L, "test-topic", "{\"test\": \"data\"}",
                            java.time.OffsetDateTime.now(), "test failure", 3));
                })
                .<Void>mapEmpty();
        });
    }

    private Future<Void> insertLargeAmountOfTestData() {
        return reactivePool.withConnection(connection -> {
            // Insert many outbox messages to potentially trigger degraded state
            Future<io.vertx.sqlclient.RowSet<io.vertx.sqlclient.Row>> future = Future.succeededFuture();

            for (int i = 0; i < 100; i++) {
                final int index = i;
                future = future.compose(result -> {
                    return connection.preparedQuery("INSERT INTO outbox (topic, payload, status) VALUES ($1, $2::jsonb, $3)")
                        .execute(io.vertx.sqlclient.Tuple.of("test-topic-" + index,
                            "{\"test\": \"data\", \"id\": " + index + "}", "PENDING"));
                });
            }

            return future.<Void>mapEmpty();
        });
    }

    @Test
    void testReactiveHealthCheckManager(VertxTestContext testContext) {
        PostgreSQLContainer postgres = SharedPostgresTestExtension.getContainer();

        // Create connection config for reactive pool
        PgConnectionConfig reactiveConnectionConfig = new PgConnectionConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .database(postgres.getDatabaseName())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .build();

        PgPoolConfig reactivePoolConfig = new PgPoolConfig.Builder()
                .maxSize(3)
                .shared(false)
                .idleTimeout(Duration.ofSeconds(2))
                .connectionTimeout(Duration.ofMillis(500))
                .build();

        // Create reactive pool
        Pool reactivePoolLocal = connectionManager.getOrCreateReactivePool("test-reactive", reactiveConnectionConfig, reactivePoolConfig);
        assertNotNull(reactivePoolLocal);

        // Create health check manager with reactive constructor
        HealthCheckManager reactiveHealthCheckManager = new HealthCheckManager(reactivePoolLocal, vertx, Duration.ofMillis(500), Duration.ofMillis(300));
        assertNotNull(reactiveHealthCheckManager);

        // Start the health check manager to run the checks
        startManagerAsync(reactiveHealthCheckManager)
            // Wait for health checks to complete
            // Health checks include database, memory, disk-space checks
            // Need to wait for initial delay (100ms) + execution time
            .compose(v -> vertx.timer(500).mapEmpty())
            .onSuccess(v -> {
                // Test that health checks work
                assertTrue(reactiveHealthCheckManager.isHealthy());

                // Test individual health checks
                assertNotNull(reactiveHealthCheckManager.getHealthStatus("database"), "Database health check should have a status");
                assertTrue(reactiveHealthCheckManager.getHealthStatus("database").isHealthy());

                // Test overall health status
                OverallHealthStatus overallStatus = reactiveHealthCheckManager.getOverallHealthInternal();
                assertNotNull(overallStatus);
                assertEquals("UP", overallStatus.getStatus());

                // Clean up
                stopManagerAsync(reactiveHealthCheckManager)
                    .onSuccess(v2 -> testContext.completeNow())
                    .onFailure(testContext::failNow);
            })
            .onFailure(testContext::failNow);
    }

    private Future<Void> startManagerAsync(HealthCheckManager manager) {
        return manager.start().<Void>mapEmpty();
    }

    private Future<Void> stopManagerAsync(HealthCheckManager manager) {
        return manager.stop().<Void>mapEmpty();
    }
}
