package dev.mars.peegeeq.test.base;

import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory.PerformanceProfile;
import dev.mars.peegeeq.test.vertx.TestCleanup;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for PeeGeeQTestBase functionality.
 *
 * This test validates that the base test class correctly:
 * - Sets up TestContainers with different performance profiles
 * - Integrates with the metrics system
 * - Provides consistent database connectivity
 * - Handles cleanup properly
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-18
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
class PeeGeeQTestBaseTest extends PeeGeeQTestBase {

    @Override
    protected PerformanceProfile getPerformanceProfile() {
        return PerformanceProfile.STANDARD;
    }

    @Test
    void testBasicSetup() {
        // Verify container is running
        assertNotNull(getContainer(), "Container should not be null");
        assertTrue(getContainer().isRunning(), "Container should be running");

        // Verify database connectivity
        assertNotNull(getJdbcUrl(), "JDBC URL should not be null");
        assertNotNull(getHost(), "Host should not be null");
        assertNotNull(getPort(), "Port should not be null");

        // Verify metrics are set up
        assertNotNull(getMetrics(), "Metrics should not be null");
        assertNotNull(getMeterRegistry(), "MeterRegistry should not be null");

        // Verify test instance ID
        assertNotNull(getTestInstanceId(), "Test instance ID should not be null");
        assertTrue(getTestInstanceId().startsWith("test-"), "Test instance ID should start with 'test-'");

        // Verify performance profile
        assertEquals(PerformanceProfile.STANDARD, getCurrentProfile(), "Profile should be STANDARD");
    }

    @Test
    void testDatabaseConnectivity(Vertx vertx, VertxTestContext testContext) {
        PgConnectOptions connectOptions = new PgConnectOptions()
            .setHost(getContainer().getHost())
            .setPort(getContainer().getFirstMappedPort())
            .setDatabase(getContainer().getDatabaseName())
            .setUser(getContainer().getUsername())
            .setPassword(getContainer().getPassword());

        Pool pool = PgBuilder.pool()
            .with(new PoolOptions().setMaxSize(1).setShared(false))
            .connectingTo(connectOptions)
            .using(vertx)
            .build();

        pool.query("SELECT 1 AS test_value").execute()
            .map(rows -> rows.iterator().next().getInteger("test_value"))
            .transform(TestCleanup.cleanup(() -> pool.close()))
            .onSuccess(value -> testContext.verify(() -> {
                assertEquals(1, value, "Test value should be 1");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    /**
     * The gauge and the timer reach the test's registry with the standard tags and the
     * recorded values.
     */
    @Test
    void testMetricsRecording() {
        recordTestMetric("test.metric.gauge", 42.0, Map.of("test_tag", "test_value"));
        recordTestTimer("test.metric.timer", 100L, Map.of("operation", "test_operation"));

        Gauge gauge = getMeterRegistry().find("test.metric.gauge")
            .tag("test_tag", "test_value")
            .tag("profile", PerformanceProfile.STANDARD.name())
            .tag("test_class", getClass().getSimpleName())
            .tag("instance", getTestInstanceId())
            .gauge();
        assertNotNull(gauge, "The gauge should be registered with the standard tags");
        assertEquals(42.0, gauge.value(), 0.0, "The gauge should report the recorded value");

        Timer timer = getMeterRegistry().find("test.metric.timer")
            .tag("operation", "test_operation")
            .tag("profile", PerformanceProfile.STANDARD.name())
            .tag("test_class", getClass().getSimpleName())
            .tag("instance", getTestInstanceId())
            .timer();
        assertNotNull(timer, "The timer should be registered with the standard tags");
        assertEquals(1, timer.count(), "The timer should hold one recording");
        assertEquals(100.0, timer.totalTime(TimeUnit.MILLISECONDS), 0.0, "The timer should hold the recorded duration");
    }
}
