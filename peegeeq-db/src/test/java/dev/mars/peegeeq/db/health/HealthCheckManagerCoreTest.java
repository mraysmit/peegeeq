package dev.mars.peegeeq.db.health;

import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

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
@Tag(TestCategories.INTEGRATION)
public class HealthCheckManagerCoreTest extends BaseIntegrationTest {

    private PgConnectionManager connectionManager;
    private Pool reactivePool;
    private HealthCheckManager healthCheckManager;

    @BeforeEach
    void setUp() {
        connectionManager = new PgConnectionManager(manager.getVertx(), null);

        PostgreSQLContainer postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema("public")
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
            .maxSize(3)
            .shared(false)
            .idleTimeout(Duration.ofSeconds(2))
            .connectionTimeout(Duration.ofSeconds(5))
            .build();

        reactivePool = connectionManager.getOrCreateReactivePool("peegeeq-main", connectionConfig, poolConfig);

        healthCheckManager = new HealthCheckManager(
            reactivePool,
            manager.getVertx(),
            Duration.ofSeconds(5),
            Duration.ofSeconds(2),
            false
        );
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        Future<Void> stopFuture = (healthCheckManager != null && healthCheckManager.isRunning())
            ? healthCheckManager.stop()
            : Future.succeededFuture();
        stopFuture.compose(v -> connectionManager != null ? connectionManager.close() : Future.<Void>succeededFuture())
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testHealthCheckManagerCreation() {
        assertNotNull(healthCheckManager);
        assertFalse(healthCheckManager.isRunning());
    }

    @Test
    void testStartAndStop(VertxTestContext testContext) {
        healthCheckManager.start()
            .compose(v -> {
                try {
                    assertTrue(healthCheckManager.isRunning());
                } catch (Throwable t) {
                    return Future.failedFuture(t);
                }
                return healthCheckManager.stop();
            })
            .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                assertFalse(healthCheckManager.isRunning());
                testContext.completeNow();
            })));
    }

    @Test
    void testDatabaseHealthCheck(VertxTestContext testContext) {
        healthCheckManager.start()
            .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                HealthStatus dbHealth = healthCheckManager.getHealthStatus("database");
                assertNotNull(dbHealth);
                assertEquals("database", dbHealth.getComponent());
                assertTrue(dbHealth.isHealthy(), "Database should be healthy");
                testContext.completeNow();
            })));
    }

    @Test
    void testMemoryHealthCheck(VertxTestContext testContext) {
        healthCheckManager.start()
            .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                HealthStatus memoryHealth = healthCheckManager.getHealthStatus("memory");
                assertNotNull(memoryHealth);
                assertEquals("memory", memoryHealth.getComponent());
                assertTrue(memoryHealth.isHealthy() || memoryHealth.isDegraded());
                assertNotNull(memoryHealth.getDetails());
                assertTrue(memoryHealth.getDetails().containsKey("max_memory_mb"));
                assertTrue(memoryHealth.getDetails().containsKey("used_memory_mb"));
                assertTrue(memoryHealth.getDetails().containsKey("memory_usage_percent"));
                testContext.completeNow();
            })));
    }

    @Test
    void testDiskSpaceHealthCheck(VertxTestContext testContext) {
        healthCheckManager.start()
            .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                HealthStatus diskHealth = healthCheckManager.getHealthStatus("disk-space");
                assertNotNull(diskHealth);
                assertEquals("disk-space", diskHealth.getComponent());
                assertTrue(diskHealth.isHealthy() || diskHealth.isDegraded());
                assertNotNull(diskHealth.getDetails());
                assertTrue(diskHealth.getDetails().containsKey("total_space_gb"));
                assertTrue(diskHealth.getDetails().containsKey("free_space_gb"));
                assertTrue(diskHealth.getDetails().containsKey("disk_usage_percent"));
                testContext.completeNow();
            })));
    }

    @Test
    void testOverallHealthStatus(VertxTestContext testContext) {
        healthCheckManager.start()
            .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                OverallHealthStatus overallHealth = healthCheckManager.getOverallHealthInternal();
                assertNotNull(overallHealth);
                assertEquals("UP", overallHealth.getStatus());
                assertNotNull(overallHealth.getComponents());
                assertTrue(overallHealth.getComponents().containsKey("database"));
                assertTrue(overallHealth.getComponents().containsKey("memory"));
                assertTrue(overallHealth.getComponents().containsKey("disk-space"));
                assertNotNull(overallHealth.getTimestamp());
                testContext.completeNow();
            })));
    }

    @Test
    void testIsHealthy(VertxTestContext testContext) {
        healthCheckManager.start()
            .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                assertTrue(healthCheckManager.isHealthy());
                testContext.completeNow();
            })));
    }

    @Test
    void testRegisterCustomHealthCheck(VertxTestContext testContext) {
        HealthCheck customCheck = () -> Future.succeededFuture(HealthStatus.healthy("custom-check"));
        healthCheckManager.registerHealthCheck("custom-check", customCheck);

        healthCheckManager.start()
            .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                HealthStatus customHealth = healthCheckManager.getHealthStatus("custom-check");
                assertNotNull(customHealth);
                assertEquals("custom-check", customHealth.getComponent());
                assertTrue(customHealth.isHealthy());
                testContext.completeNow();
            })));
    }

    @Test
    void testQueueHealthChecksDisabled(VertxTestContext testContext) {
        healthCheckManager.start()
            .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                assertNull(healthCheckManager.getHealthStatus("outbox-queue"));
                assertNull(healthCheckManager.getHealthStatus("native-queue"));
                assertNull(healthCheckManager.getHealthStatus("dead-letter-queue"));
                testContext.completeNow();
            })));
    }

    @Test
    void testQueueHealthChecksEnabled(VertxTestContext testContext) {
        HealthCheckManager queueEnabledManager = new HealthCheckManager(
            reactivePool,
            manager.getVertx(),
            Duration.ofSeconds(5),
            Duration.ofSeconds(2),
            true
        );

        queueEnabledManager.start()
            .compose(v -> {
                HealthStatus outboxHealth = queueEnabledManager.getHealthStatus("outbox-queue");
                HealthStatus nativeHealth = queueEnabledManager.getHealthStatus("native-queue");
                HealthStatus dlqHealth = queueEnabledManager.getHealthStatus("dead-letter-queue");
                assertNotNull(outboxHealth);
                assertNotNull(nativeHealth);
                assertNotNull(dlqHealth);
                assertTrue(outboxHealth.isHealthy(), "Outbox queue should be healthy: " + outboxHealth.getMessage());
                assertTrue(nativeHealth.isHealthy(), "Native queue should be healthy: " + nativeHealth.getMessage());
                assertTrue(dlqHealth.isHealthy(), "Dead letter queue should be healthy: " + dlqHealth.getMessage());
                return queueEnabledManager.stop();
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testStartWhenAlreadyRunning(VertxTestContext testContext) {
        healthCheckManager.start()
            .compose(v -> {
                try {
                    assertTrue(healthCheckManager.isRunning());
                } catch (Throwable t) {
                    return Future.failedFuture(t);
                }
                return healthCheckManager.start();
            })
            .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                assertTrue(healthCheckManager.isRunning());
                testContext.completeNow();
            })));
    }

    @Test
    void testStopWhenNotRunning(VertxTestContext testContext) {
        healthCheckManager.stop()
            .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                assertFalse(healthCheckManager.isRunning());
                testContext.completeNow();
            })));
    }

    @Test
    void testRestartAfterStop(VertxTestContext testContext) {
        healthCheckManager.start()
            .compose(v -> {
                try {
                    assertTrue(healthCheckManager.isRunning());
                } catch (Throwable t) {
                    return Future.failedFuture(t);
                }
                return healthCheckManager.stop();
            })
            .compose(v -> {
                try {
                    assertFalse(healthCheckManager.isRunning());
                } catch (Throwable t) {
                    return Future.failedFuture(t);
                }
                return healthCheckManager.start();
            })
            .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                assertTrue(healthCheckManager.isRunning());
                testContext.completeNow();
            })));
    }

    @Test
    void testRejectsInvalidSchemaName() {
        assertThrows(IllegalArgumentException.class, () -> new HealthCheckManager(
            reactivePool,
            manager.getVertx(),
            Duration.ofSeconds(5),
            Duration.ofSeconds(2),
            true,
            "public;drop_schema"
        ));
    }

    @Test
    void testConcurrentCyclesAreNeverStarted(VertxTestContext testContext) {
        AtomicInteger concurrent = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);

        HealthCheck slowCheck = () -> {
            int c = concurrent.incrementAndGet();
            maxConcurrent.updateAndGet(max -> Math.max(max, c));
            Promise<HealthStatus> promise = Promise.promise();
            manager.getVertx().setTimer(200, id -> {
                concurrent.decrementAndGet();
                promise.complete(HealthStatus.healthy("slow"));
            });
            return promise.future();
        };

        HealthCheckManager fastManager = new HealthCheckManager(
            reactivePool,
            manager.getVertx(),
            Duration.ofMillis(100),
            Duration.ofSeconds(5),
            false
        );

        fastManager.registerHealthCheck("slow", slowCheck);
        fastManager.start()
            .compose(v -> manager.getVertx().timer(700).mapEmpty())
            .compose(v -> {
                assertEquals(1, maxConcurrent.get(),
                    "Health check cycles must never run concurrently");
                return fastManager.stop();
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }
}