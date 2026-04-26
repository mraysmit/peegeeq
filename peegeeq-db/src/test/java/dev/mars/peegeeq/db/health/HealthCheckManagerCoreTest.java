package dev.mars.peegeeq.db.health;

import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
@Tag(TestCategories.CORE)
@Execution(ExecutionMode.SAME_THREAD)
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
    void testStartAndStop(VertxTestContext testContext) throws Exception {
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        healthCheckManager.start()
            .compose(v -> {
                try {
                    assertTrue(healthCheckManager.isRunning());
                } catch (Throwable t) {
                    return Future.failedFuture(t);
                }
                return healthCheckManager.stop();
            })
            .onSuccess(v -> {
                try {
                    assertFalse(healthCheckManager.isRunning());
                } catch (Throwable t) {
                    errorRef.set(t);
                } finally {
                    testContext.completeNow();
                }
            })
            .onFailure(e -> { errorRef.set(e); testContext.completeNow(); });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
    }

    @Test
    void testDatabaseHealthCheck(VertxTestContext testContext) throws Exception {
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        healthCheckManager.start()
            .compose(v -> manager.getVertx().timer(500).mapEmpty())
            .onSuccess(v -> {
                try {
                    HealthStatus dbHealth = healthCheckManager.getHealthStatus("database");
                    assertNotNull(dbHealth);
                    assertEquals("database", dbHealth.getComponent());
                    assertTrue(dbHealth.isHealthy(), "Database should be healthy");
                } catch (Throwable t) {
                    errorRef.set(t);
                } finally {
                    testContext.completeNow();
                }
            })
            .onFailure(e -> { errorRef.set(e); testContext.completeNow(); });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
    }

    @Test
    void testMemoryHealthCheck(VertxTestContext testContext) throws Exception {
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        healthCheckManager.start()
            .compose(v -> manager.getVertx().timer(500).mapEmpty())
            .onSuccess(v -> {
                try {
                    HealthStatus memoryHealth = healthCheckManager.getHealthStatus("memory");
                    assertNotNull(memoryHealth);
                    assertEquals("memory", memoryHealth.getComponent());
                    assertTrue(memoryHealth.isHealthy() || memoryHealth.isDegraded());
                    assertNotNull(memoryHealth.getDetails());
                    assertTrue(memoryHealth.getDetails().containsKey("max_memory_mb"));
                    assertTrue(memoryHealth.getDetails().containsKey("used_memory_mb"));
                    assertTrue(memoryHealth.getDetails().containsKey("memory_usage_percent"));
                } catch (Throwable t) {
                    errorRef.set(t);
                } finally {
                    testContext.completeNow();
                }
            })
            .onFailure(e -> { errorRef.set(e); testContext.completeNow(); });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
    }

    @Test
    void testDiskSpaceHealthCheck(VertxTestContext testContext) throws Exception {
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        healthCheckManager.start()
            .compose(v -> manager.getVertx().timer(500).mapEmpty())
            .onSuccess(v -> {
                try {
                    HealthStatus diskHealth = healthCheckManager.getHealthStatus("disk-space");
                    assertNotNull(diskHealth);
                    assertEquals("disk-space", diskHealth.getComponent());
                    assertTrue(diskHealth.isHealthy() || diskHealth.isDegraded());
                    assertNotNull(diskHealth.getDetails());
                    assertTrue(diskHealth.getDetails().containsKey("total_space_gb"));
                    assertTrue(diskHealth.getDetails().containsKey("free_space_gb"));
                    assertTrue(diskHealth.getDetails().containsKey("disk_usage_percent"));
                } catch (Throwable t) {
                    errorRef.set(t);
                } finally {
                    testContext.completeNow();
                }
            })
            .onFailure(e -> { errorRef.set(e); testContext.completeNow(); });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
    }

    @Test
    void testOverallHealthStatus(VertxTestContext testContext) throws Exception {
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        healthCheckManager.start()
            .compose(v -> manager.getVertx().timer(500).mapEmpty())
            .onSuccess(v -> {
                try {
                    OverallHealthStatus overallHealth = healthCheckManager.getOverallHealthInternal();
                    assertNotNull(overallHealth);
                    assertEquals("UP", overallHealth.getStatus());
                    assertNotNull(overallHealth.getComponents());
                    assertTrue(overallHealth.getComponents().containsKey("database"));
                    assertTrue(overallHealth.getComponents().containsKey("memory"));
                    assertTrue(overallHealth.getComponents().containsKey("disk-space"));
                    assertNotNull(overallHealth.getTimestamp());
                } catch (Throwable t) {
                    errorRef.set(t);
                } finally {
                    testContext.completeNow();
                }
            })
            .onFailure(e -> { errorRef.set(e); testContext.completeNow(); });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
    }

    @Test
    void testIsHealthy(VertxTestContext testContext) throws Exception {
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        healthCheckManager.start()
            .compose(v -> manager.getVertx().timer(500).mapEmpty())
            .onSuccess(v -> {
                try {
                    assertTrue(healthCheckManager.isHealthy());
                } catch (Throwable t) {
                    errorRef.set(t);
                } finally {
                    testContext.completeNow();
                }
            })
            .onFailure(e -> { errorRef.set(e); testContext.completeNow(); });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
    }

    @Test
    void testRegisterCustomHealthCheck(VertxTestContext testContext) throws Exception {
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        HealthCheck customCheck = () -> HealthStatus.healthy("custom-check");
        healthCheckManager.registerHealthCheck("custom-check", customCheck);

        healthCheckManager.start()
            .compose(v -> manager.getVertx().timer(500).mapEmpty())
            .onSuccess(v -> {
                try {
                    HealthStatus customHealth = healthCheckManager.getHealthStatus("custom-check");
                    assertNotNull(customHealth);
                    assertEquals("custom-check", customHealth.getComponent());
                    assertTrue(customHealth.isHealthy());
                } catch (Throwable t) {
                    errorRef.set(t);
                } finally {
                    testContext.completeNow();
                }
            })
            .onFailure(e -> { errorRef.set(e); testContext.completeNow(); });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
    }

    @Test
    void testQueueHealthChecksDisabled(VertxTestContext testContext) throws Exception {
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        healthCheckManager.start()
            .compose(v -> manager.getVertx().timer(500).mapEmpty())
            .onSuccess(v -> {
                try {
                    assertNull(healthCheckManager.getHealthStatus("outbox-queue"));
                    assertNull(healthCheckManager.getHealthStatus("native-queue"));
                    assertNull(healthCheckManager.getHealthStatus("dead-letter-queue"));
                } catch (Throwable t) {
                    errorRef.set(t);
                } finally {
                    testContext.completeNow();
                }
            })
            .onFailure(e -> { errorRef.set(e); testContext.completeNow(); });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
    }

    @Test
    void testQueueHealthChecksEnabled(VertxTestContext testContext) throws Exception {
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        HealthCheckManager queueEnabledManager = new HealthCheckManager(
            reactivePool,
            manager.getVertx(),
            Duration.ofSeconds(5),
            Duration.ofSeconds(2),
            true
        );

        queueEnabledManager.start()
            .compose(v -> manager.getVertx().timer(500).mapEmpty())
            .onSuccess(v -> {
                try {
                    HealthStatus outboxHealth = queueEnabledManager.getHealthStatus("outbox-queue");
                    HealthStatus nativeHealth = queueEnabledManager.getHealthStatus("native-queue");
                    HealthStatus dlqHealth = queueEnabledManager.getHealthStatus("dead-letter-queue");
                    assertNotNull(outboxHealth);
                    assertNotNull(nativeHealth);
                    assertNotNull(dlqHealth);
                    assertTrue(outboxHealth.isHealthy(), "Outbox queue should be healthy: " + outboxHealth.getMessage());
                    assertTrue(nativeHealth.isHealthy(), "Native queue should be healthy: " + nativeHealth.getMessage());
                    assertTrue(dlqHealth.isHealthy(), "Dead letter queue should be healthy: " + dlqHealth.getMessage());
                } catch (Throwable t) {
                    errorRef.set(t);
                } finally {
                    if (queueEnabledManager.isRunning()) {
                        queueEnabledManager.stop()
                            .onSuccess(x -> testContext.completeNow())
                            .onFailure(testContext::failNow);
                    } else {
                        testContext.completeNow();
                    }
                }
            })
            .onFailure(e -> {
                errorRef.set(e);
                if (queueEnabledManager.isRunning()) {
                    queueEnabledManager.stop()
                        .onSuccess(x -> testContext.completeNow())
                        .onFailure(testContext::failNow);
                } else {
                    testContext.completeNow();
                }
            });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
    }

    @Test
    void testStartWhenAlreadyRunning(VertxTestContext testContext) throws Exception {
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        healthCheckManager.start()
            .compose(v -> {
                try {
                    assertTrue(healthCheckManager.isRunning());
                } catch (Throwable t) {
                    return Future.failedFuture(t);
                }
                return healthCheckManager.start();
            })
            .onSuccess(v -> {
                try {
                    assertTrue(healthCheckManager.isRunning());
                } catch (Throwable t) {
                    errorRef.set(t);
                } finally {
                    testContext.completeNow();
                }
            })
            .onFailure(e -> { errorRef.set(e); testContext.completeNow(); });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
    }

    @Test
    void testStopWhenNotRunning(VertxTestContext testContext) throws Exception {
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        healthCheckManager.stop()
            .onSuccess(v -> {
                try {
                    assertFalse(healthCheckManager.isRunning());
                } catch (Throwable t) {
                    errorRef.set(t);
                } finally {
                    testContext.completeNow();
                }
            })
            .onFailure(e -> { errorRef.set(e); testContext.completeNow(); });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
    }

    @Test
    void testRestartAfterStop(VertxTestContext testContext) throws Exception {
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

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
            .onSuccess(v -> {
                try {
                    assertTrue(healthCheckManager.isRunning());
                } catch (Throwable t) {
                    errorRef.set(t);
                } finally {
                    testContext.completeNow();
                }
            })
            .onFailure(e -> { errorRef.set(e); testContext.completeNow(); });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
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
    void testTimedOutHealthCheckDoesNotAccumulateConcurrentRuns(VertxTestContext testContext) throws Exception {
        AtomicInteger invocations = new AtomicInteger(0);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        HealthCheck nonInterruptibleSlowCheck = () -> {
            invocations.incrementAndGet();
            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (System.nanoTime() < deadlineNanos) {
                // busy-wait - simulates non-interruptible slow health check
            }
            return HealthStatus.healthy("slow");
        };

        HealthCheckManager fastManager = new HealthCheckManager(
            reactivePool,
            manager.getVertx(),
            Duration.ofMillis(100),
            Duration.ofMillis(150),
            false
        );

        fastManager.registerHealthCheck("slow", nonInterruptibleSlowCheck);
        fastManager.start()
            .compose(v -> manager.getVertx().timer(700).mapEmpty())
            .onSuccess(v -> {
                try {
                    assertEquals(1, invocations.get(),
                        "Timed-out checks must not be re-started while a prior run is still in-flight");
                } catch (Throwable t) {
                    errorRef.set(t);
                } finally {
                    fastManager.stop()
                        .onSuccess(x -> testContext.completeNow())
                        .onFailure(testContext::failNow);
                }
            })
            .onFailure(e -> {
                errorRef.set(e);
                fastManager.stop()
                    .onSuccess(x -> testContext.completeNow())
                    .onFailure(testContext::failNow);
            });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
    }
}