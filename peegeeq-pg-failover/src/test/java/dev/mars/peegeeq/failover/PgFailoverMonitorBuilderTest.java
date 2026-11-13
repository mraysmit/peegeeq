package dev.mars.peegeeq.failover;

import dev.mars.peegeeq.failover.consul.ConsulLeaseCoordinator;
import dev.mars.peegeeq.failover.consul.ConsulMemberPositions;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.vertx.TestCleanup;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The monitor builder (plan §9.28, P7c): every dependency is required, and a missing one is named. The dependencies are
 * real objects; none of them performs I/O when it is constructed, and no operation of the monitor runs here.
 */
@Tag(TestCategories.CORE)
@ExtendWith(VertxExtension.class)
class PgFailoverMonitorBuilderTest {
    private static final URI LOOPBACK = URI.create("http://127.0.0.1:1");
    private static final List<String> PREFIX = List.of("docker", "exec", "absent");
    @TempDir Path stateDirectory;
    private PgNodeConfig config;
    private PgPrimaryElector elector;
    private PgMemberPositions positions;
    private PgLocalStateStore store;
    private Vertx vertx;

    @BeforeEach void setUp(Vertx vertx, VertxTestContext context) {
        this.vertx = vertx;
        config = PgNodeConfig.defaults("builder-tests", "inc-1", "pg-node-1", List.of("pg-node-1", "pg-node-2"));
        elector = new PgPrimaryElector(config, new ConsulLeaseCoordinator(vertx, config, LOOPBACK, "consul-1", "token"));
        positions = new ConsulMemberPositions(vertx, config, LOOPBACK, "consul-1", "token");
        PgLocalStateStore.open(vertx, stateDirectory, config.nodeId()).onSuccess(opened -> {
            store = opened;
            context.completeNow();
        }).onFailure(context::failNow);
    }

    @AfterEach void tearDown(VertxTestContext context) {
        TestCleanup.run(() -> elector.close(), () -> positions.close())
            .onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void aBuilderWithEveryDependencyBuildsAMonitor() {
        assertNotNull(complete().build());
    }

    @ParameterizedTest
    @ValueSource(strings = {"config", "elector", "store", "process", "gate", "watchdog", "durability", "promotion",
        "positions"})
    void aMissingDependencyIsRefusedByName(String missing) {
        var builder = complete();
        switch (missing) {
            case "config" -> builder.config(null);
            case "elector" -> builder.elector(null);
            case "store" -> builder.store(null);
            case "process" -> builder.process(null);
            case "gate" -> builder.gate(null);
            case "watchdog" -> builder.watchdog(null);
            case "durability" -> builder.durability(null);
            case "promotion" -> builder.promotion(null);
            case "positions" -> builder.positions(null);
            default -> fail("Unknown dependency " + missing);
        }
        var refusal = assertThrows(NullPointerException.class, builder::build);
        assertEquals(missing, refusal.getMessage());
    }

    private PgFailoverMonitor.Builder complete() {
        var runner = new LocalCommandRunner(vertx);
        Duration timeout = Duration.ofSeconds(5);
        return PgFailoverMonitor.builder().config(config).elector(elector).store(store)
            .process(new PgCtlProcessControl(runner, PREFIX, "pg_ctl", "/data", "/data.log", timeout))
            .gate(new PgHbaAdmissionGate(vertx, runner, PREFIX, "pg_ctl", "psql", "/data", "/open", "/closed", timeout))
            .watchdog(new PgDeviceWatchdog(vertx, stateDirectory.resolve("no-watchdog"),
                stateDirectory.resolve("no-watchdog-timeout")))
            .durability(new PgSqlDurabilityControl(vertx, runner, PREFIX, "pg_ctl", "psql", "/data", timeout))
            .promotion(new PgSqlPromotionControl(vertx, runner, PREFIX, "pg_ctl", "psql", "/data", timeout))
            .positions(positions);
    }
}
