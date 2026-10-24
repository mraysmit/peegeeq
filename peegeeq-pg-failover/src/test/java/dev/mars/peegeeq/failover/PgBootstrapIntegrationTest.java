package dev.mars.peegeeq.failover;

import dev.mars.peegeeq.failover.consul.ConsulLeaseCoordinator;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.logging.ExpectedErrorLog;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.file.FileSystemException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/**
 * First-start bootstrap up to the guarded primary start: a real coordinator, a real PostgreSQL
 * that only the supervisor starts, and real node-local storage. Policy installation and writer
 * activation are not part of this scope.
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Isolated
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class PgBootstrapIntegrationTest {
    private static final List<String> MEMBERS = List.of("pg-node-1", "pg-node-2", "pg-node-3");
    private static final String TOKEN = "unused-without-acls";
    private static final GenericContainer<?> CONSUL = new GenericContainer<>("hashicorp/consul:1.22.1")
        .withExposedPorts(8500)
        .withCommand("agent", "-dev", "-client=0.0.0.0", "-node=consul-1", "-log-level=warn")
        .waitingFor(Wait.forHttp("/v1/status/leader").forPort(8500).withStartupTimeout(Duration.ofSeconds(60)));
    private static final PgSupervisedContainer POSTGRES = new PgSupervisedContainer();
    @TempDir Path stateDirectory;
    private Vertx vertx;
    private WebClient admin;
    private PgCommandRunner runner;
    private PgNodeConfig config;
    private String dataDirectory;
    private PgLocalStateStore store;
    private PgProcessControl process;
    private PgAdmissionGate gate;
    private PgWatchdog watchdog;
    private Path requestFile;
    private String operationId;
    private final List<PgPrimaryElector> electors = new ArrayList<>();
    private final List<PgRenewalLoop> loops = new ArrayList<>();

    @BeforeAll static void startContainers() {
        CONSUL.start();
        POSTGRES.start();
    }

    @AfterAll static void stopContainers() {
        POSTGRES.close();
        CONSUL.stop();
    }

    @BeforeEach void setUp(Vertx vertx, VertxTestContext context) {
        this.vertx = vertx;
        admin = WebClient.create(vertx);
        runner = new LocalCommandRunner(vertx);
        config = config(Duration.ofSeconds(20), Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofSeconds(8));
        operationId = UUID.randomUUID().toString();
        requestFile = stateDirectory.resolve("bootstrap-request.json");
        waitForLeader(System.nanoTime() + TimeUnit.SECONDS.toNanos(30))
            .compose(ignored -> POSTGRES.newDataDirectory(runner))
            .compose(created -> {
                dataDirectory = created;
                return POSTGRES.provisionAdmission(runner, created);
            }).compose(ignored -> {
                process = control(PgSupervisedContainer.PG_CTL);
                gate = new PgHbaAdmissionGate(vertx, runner, POSTGRES.prefix(), PgSupervisedContainer.PG_CTL,
                    "psql", dataDirectory, POSTGRES.openHba(dataDirectory), POSTGRES.closedHba(dataDirectory),
                    Duration.ofSeconds(20));
                // Off mode. This host has no watchdog device; neither path exists.
                watchdog = new PgDeviceWatchdog(vertx, stateDirectory.resolve("no-watchdog"),
                    stateDirectory.resolve("no-watchdog-timeout"));
                return PgLocalStateStore.open(vertx, stateDirectory, config.nodeId());
            }).onSuccess(opened -> context.verify(() -> {
                store = opened;
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @AfterEach void tearDown(VertxTestContext context) {
        Future<Void> stopped = Future.succeededFuture();
        for (PgRenewalLoop loop : loops) stopped = stopped.compose(ignored -> loop.stop());
        stopped.compose(ignored -> POSTGRES.forceStop(runner, dataDirectory))
            .eventually(() -> {
                Future<Void> closed = Future.succeededFuture();
                for (PgPrimaryElector elector : electors) closed = closed.compose(ignored -> elector.close());
                return closed;
            }).eventually(() -> {
                admin.close();
                return Future.<Void>succeededFuture();
            }).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void aValidRequestStartsAWritablePrimaryWithAdmissionClosedUnderWithdrawnIntent(VertxTestContext context)
            throws IOException {
        writeRequest(request());
        var bootstrap = bootstrap(config, process);
        bootstrap.start(requestFile).compose(held -> {
            assertNotNull(held.leaseHolder());
            assertEquals("WITHDRAWN", held.intent().getString("phase"));
            assertEquals(operationId, held.intent().getString("operationId"));
            assertNull(held.intent().getJsonObject("durabilityPolicy"), "Bootstrap invents no confirmed policy");
            assertFalse(held.intent().containsKey("previousWriterNodeId"));
            assertEquals(1L, held.intent().getJsonObject("pendingDurabilityPolicy").getLong("revision"));
            return POSTGRES.sql(runner, "select pg_is_in_recovery()").compose(recovery -> {
                assertEquals("f", recovery.output().strip());
                return POSTGRES.applicationSql(runner, "select 1");
            }).compose(application -> {
                assertNotEquals(0, application.exitCode(), "Bootstrap opened application admission");
                return store.receipt(held.generation(), operationId, PgBootstrap.BOOTSTRAP, config.nodeId());
            }).compose(receipt -> {
                assertEquals(request(), receipt.orElseThrow().parameters());
                assertEquals(PgActionResult.PENDING, receipt.orElseThrow().result(),
                    "Bootstrap completion is derived later; scope A records no result");
                return store.receipt(held.generation(), operationId, "start-primary", config.nodeId());
            });
        }).onSuccess(started -> context.verify(() -> {
            assertEquals(PgActionResult.COMPLETED, started.orElseThrow().result());
            assertFalse(loops.get(0).completion().isComplete(), "The renewal loop must keep running");
            context.completeNow();
        })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgBootstrapRequest",
        message = "Bootstrap request rejected",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = FileSystemException.class)
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgBootstrap",
        message = "Bootstrap refused on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgBootstrapException.class)
    void withoutARequestFileNothingIsCreatedOrStarted(VertxTestContext context) {
        bootstrap(config, process).start(requestFile).transform(start -> {
            assertTrue(start.failed());
            assertInstanceOf(PgBootstrapException.class, start.cause());
            return assertNothingCreatedOrStarted();
        }).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgBootstrap",
        message = "Bootstrap refused on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgBootstrapException.class)
    void anExistingControlRecordOfAnotherOperationRefusesBootstrap(VertxTestContext context) throws IOException {
        writeRequest(request());
        var other = elector(config);
        var otherOperation = UUID.randomUUID().toString();
        var otherIntent = new JsonObject().put("writerNodeId", "pg-node-1").put("phase", "WITHDRAWN")
            .put("operationId", otherOperation)
            .put("pendingDurabilityPolicy", new JsonObject().put("revision", 1L)
                .put("requiredStandbyNodeIds", new JsonArray().add("pg-node-2").add("pg-node-3")));
        // An earlier deployment left this record. Its lease is still held by that elector.
        other.createInitialIntent(otherIntent)
            .compose(created -> bootstrap(config, process).start(requestFile).transform(start -> {
                assertTrue(start.failed(), "Bootstrap ran against existing history");
                assertInstanceOf(PgBootstrapException.class, start.cause());
                return readRecord();
            })).compose(record -> {
                assertEquals(otherOperation, record.intent().getString("operationId"), "The existing record changed");
                return process.status();
            }).onSuccess(status -> context.verify(() -> {
                assertEquals(PgProcessState.STOPPED, status);
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgBootstrap",
        message = "Bootstrap refused on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgBootstrapException.class)
    void aRerunAfterAnInterruptedBootstrapIsRefusedAndStartsNothing(VertxTestContext context) throws IOException {
        writeRequest(request());
        bootstrap(config, process).start(requestFile).compose(held ->
            // The supervisor process ends: its loop stops, PostgreSQL stops, the lease is kept.
            loops.get(0).stop().compose(ignored -> POSTGRES.forceStop(runner, dataDirectory))
                .compose(ignored -> electors.get(0).close())
                .compose(ignored -> bootstrap(config, process).start(requestFile).transform(rerun -> {
                    assertTrue(rerun.failed(), "A rerun bootstrapped over its own earlier operation");
                    assertInstanceOf(PgBootstrapException.class, rerun.cause());
                    assertTrue(rerun.cause().getMessage().contains("interrupted"), rerun.cause().getMessage());
                    return process.status();
                })).compose(status -> {
                    assertEquals(PgProcessState.STOPPED, status);
                    return store.receipt(held.generation(), operationId, PgBootstrap.BOOTSTRAP, config.nodeId());
                })).onSuccess(receipt -> context.verify(() -> {
                    assertEquals(PgActionResult.PENDING, receipt.orElseThrow().result());
                    context.completeNow();
                })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgBootstrap",
        message = "Bootstrap refused on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgBootstrapException.class)
    void aNodeWithPriorWriterAdmissionRefusesBootstrap(VertxTestContext context) throws IOException {
        writeRequest(request());
        store.prepare(PgFailoverMode.MANUAL, 1, UUID.randomUUID().toString(), 1)
            .compose(ignored -> store.closeGrant())
            .compose(ignored -> bootstrap(config, process).start(requestFile)).transform(start -> {
                assertTrue(start.failed(), "Bootstrap ran on a node with a stored writer grant");
                assertInstanceOf(PgBootstrapException.class, start.cause());
                return assertNothingCreatedOrStarted();
            }).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgBootstrap",
        message = "Bootstrap refused on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgBootstrapException.class)
    void aQuarantinedNodeRefusesBootstrap(VertxTestContext context) throws IOException {
        writeRequest(request());
        store.quarantine("awaiting rebuild")
            .compose(ignored -> bootstrap(config, process).start(requestFile)).transform(start -> {
                assertTrue(start.failed(), "Bootstrap ran on a quarantined node");
                assertInstanceOf(PgBootstrapException.class, start.cause());
                return assertNothingCreatedOrStarted();
            }).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Demoting local PostgreSQL on node pg-node-1: Primary start did not complete under ownership",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgProcessControlException.class)
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local writer stop is unconfirmed on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgProcessControlException.class)
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation failed on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgProcessControlException.class)
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgBootstrap",
        message = "Bootstrap failed on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgProcessControlException.class)
    void aFailedStartDemotesStopsTheLoopAndLeavesThePendingBootstrapReceipt(VertxTestContext context)
            throws IOException {
        // A long loop interval keeps a renewal out of the failed start. The process commands cannot run.
        var slow = config(Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(3), Duration.ofSeconds(5));
        writeRequest(request(slow));
        var bootstrap = bootstrap(slow, control("/nonexistent/pg_ctl"));
        bootstrap.start(requestFile).transform(start -> {
            assertTrue(start.failed(), "A failed start was reported as a bootstrap");
            assertInstanceOf(PgProcessControlException.class, start.cause());
            assertTrue(loops.get(0).completion().isComplete(), "The renewal loop kept running after the failure");
            assertFalse(electors.get(0).hasFreshOwnership());
            return readRecord(slow);
        }).compose(record -> {
            assertEquals(operationId, record.intent().getString("operationId"));
            return store.receipt(record.generation(), operationId, PgBootstrap.BOOTSTRAP, slow.nodeId());
        }).onSuccess(receipt -> context.verify(() -> {
            assertEquals(PgActionResult.PENDING, receipt.orElseThrow().result());
            context.completeNow();
        })).onFailure(context::failNow);
    }

    private Future<Void> assertNothingCreatedOrStarted() {
        var observer = elector(config);
        return observer.read().compose(found -> {
            assertTrue(found.isEmpty(), "A refused bootstrap created a control record");
            return process.status();
        }).map(status -> {
            assertEquals(PgProcessState.STOPPED, status);
            return null;
        });
    }

    private Future<PgControlRecord> readRecord() {
        return readRecord(config);
    }

    private Future<PgControlRecord> readRecord(PgNodeConfig nodeConfig) {
        return elector(nodeConfig).read().map(found -> found.orElseThrow(() -> new AssertionError("No control record")));
    }

    private PgBootstrap bootstrap(PgNodeConfig nodeConfig, PgProcessControl control) {
        var elector = elector(nodeConfig);
        var durability = new PgSqlDurabilityControl(vertx, runner, POSTGRES.prefix(), PgSupervisedContainer.PG_CTL,
            "psql", dataDirectory, Duration.ofSeconds(20));
        var monitor = new PgFailoverMonitor(nodeConfig, elector, store, control, gate, watchdog, durability);
        var loop = new PgRenewalLoop(vertx, nodeConfig, elector, monitor);
        loops.add(loop);
        return new PgBootstrap(vertx, nodeConfig, elector, store, monitor, loop);
    }

    private PgPrimaryElector elector(PgNodeConfig nodeConfig) {
        var elector = new PgPrimaryElector(nodeConfig,
            new ConsulLeaseCoordinator(vertx, nodeConfig, endpoint(), "consul-1", TOKEN));
        electors.add(elector);
        return elector;
    }

    private PgNodeConfig config(Duration ttl, Duration loop, Duration request, Duration stop) {
        String incarnation = config == null ? "inc-" + UUID.randomUUID() : config.incarnation() + "-slow";
        return new PgNodeConfig("bootstrap-tests", incarnation, "pg-node-1", MEMBERS, ttl, loop, request, stop,
            PgWatchdogMode.OFF);
    }

    private PgProcessControl control(String pgCtl) {
        return new PgCtlProcessControl(runner, POSTGRES.prefix(), pgCtl, dataDirectory,
            POSTGRES.logFile(dataDirectory), Duration.ofSeconds(20));
    }

    private void writeRequest(JsonObject request) throws IOException {
        Files.writeString(requestFile, request.encodePrettily());
    }

    private JsonObject request() {
        return request(config);
    }

    private JsonObject request(PgNodeConfig nodeConfig) {
        return new JsonObject().put("clusterId", nodeConfig.clusterId()).put("incarnation", nodeConfig.incarnation())
            .put("memberNodeIds", new JsonArray(MEMBERS)).put("initialWriterNodeId", "pg-node-1")
            .put("operationId", operationId)
            .put("requiredStandbyNodeIds", new JsonArray().add("pg-node-2").add("pg-node-3"));
    }

    private static URI endpoint() {
        return URI.create("http://" + CONSUL.getHost() + ":" + CONSUL.getMappedPort(8500));
    }

    private Future<Void> waitForLeader(long deadline) {
        return admin.getAbs(endpoint() + "/v1/status/leader").timeout(1000).send().compose(response -> {
            if (response.statusCode() == 200 && response.bodyAsString().length() > 2) return Future.succeededFuture();
            if (System.nanoTime() >= deadline) return Future.failedFuture(new AssertionError("Consul has no leader"));
            return vertx.timer(100).compose(ignored -> waitForLeader(deadline));
        });
    }
}
