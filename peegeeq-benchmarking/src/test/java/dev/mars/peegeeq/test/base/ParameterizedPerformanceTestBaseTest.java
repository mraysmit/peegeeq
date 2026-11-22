package dev.mars.peegeeq.test.base;

import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory.PerformanceProfile;
import dev.mars.peegeeq.test.vertx.TestCleanup;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for the parameterized benchmark base.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
class ParameterizedPerformanceTestBaseTest extends ParameterizedPerformanceTestBase {

    /**
     * The synchronous_commit setting each profile starts PostgreSQL with. BASIC keeps the
     * server default. fsync does not separate the profiles: Testcontainers starts every
     * PostgreSQL container with fsync off.
     */
    private static final Map<PerformanceProfile, String> EXPECTED_SYNCHRONOUS_COMMIT = Map.of(
        PerformanceProfile.BASIC, "on",
        PerformanceProfile.STANDARD, "off",
        PerformanceProfile.HIGH_PERFORMANCE, "off");

    /** The max_connections setting each profile starts PostgreSQL with. */
    private static final Map<PerformanceProfile, String> EXPECTED_MAX_CONNECTIONS = Map.of(
        PerformanceProfile.BASIC, "100",
        PerformanceProfile.STANDARD, "100",
        PerformanceProfile.HIGH_PERFORMANCE, "300");

    /**
     * The base class starts a STANDARD container before each test; runTestWithProfile must
     * replace it with a container of the requested profile, which the running server's
     * settings prove.
     */
    @ParameterizedTest
    @EnumSource(value = PerformanceProfile.class, names = {"BASIC", "STANDARD", "HIGH_PERFORMANCE"})
    void testPerformanceAcrossProfiles(PerformanceProfile profile, Vertx vertx, VertxTestContext testContext) {
        PerformanceTestResult result = runTestWithProfile(profile, () -> createPerformanceMetrics(
            1000.0,
            50.0,
            75.0,
            0.0
        ));

        assertNotNull(result, "Result should not be null");
        assertEquals(profile, result.getProfile(), "Profile should match");
        assertTrue(result.isSuccess(), "Test should be successful");
        assertEquals(1000.0, result.getThroughput(), 0.1, "Throughput should match the supplied measurement");
        validatePerformanceThresholds(result, profile);
        assertEquals(profile, getCurrentProfile(), "The current profile should be the requested one");

        Pool pool = PgBuilder.pool()
            .with(new PoolOptions().setMaxSize(1).setShared(false))
            .connectingTo(new PgConnectOptions()
                .setHost(getContainer().getHost())
                .setPort(getContainer().getFirstMappedPort())
                .setDatabase(getContainer().getDatabaseName())
                .setUser(getContainer().getUsername())
                .setPassword(getContainer().getPassword()))
            .using(vertx)
            .build();

        pool.query("SELECT current_setting('synchronous_commit') AS synchronous_commit, current_setting('max_connections') AS max_connections")
            .execute()
            .map(rows -> rows.iterator().next())
            .transform(TestCleanup.cleanup(() -> pool.close()))
            .onSuccess((Row settings) -> testContext.verify(() -> {
                assertEquals(EXPECTED_SYNCHRONOUS_COMMIT.get(profile), settings.getString("synchronous_commit"),
                    "The running container must have the synchronous_commit setting of profile " + profile);
                assertEquals(EXPECTED_MAX_CONNECTIONS.get(profile), settings.getString("max_connections"),
                    "The running container must have the max_connections setting of profile " + profile);
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testPerformanceMetricsCreation() {
        Map<String, Object> metrics = createPerformanceMetrics(500.0, 25.0, 40.0, 1.5);

        assertEquals(500.0, metrics.get("throughput"), "Throughput should match");
        assertEquals(25.0, metrics.get("average_latency"), "Average latency should match");
        assertEquals(40.0, metrics.get("p95_latency"), "P95 latency should match");
        assertEquals(1.5, metrics.get("error_rate"), "Error rate should match");
    }
}
