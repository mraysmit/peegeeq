package dev.mars.peegeeq.failover;

import dev.mars.peegeeq.failover.consul.ConsulLeaseCoordinator;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.logging.ExpectedErrorLog;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
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
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static dev.mars.peegeeq.failover.PgReplicationFixture.STANDBYS;
import static dev.mars.peegeeq.failover.PgReplicationFixture.WRITER;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The second half of first-start bootstrap: policy installation, confirmation of revision 1,
 * publication of {@code SERVING} intent, and activation of the matching grant. A real coordinator,
 * a real writer and two real physical standbys that only the test starts, and real node-local
 * storage.
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Isolated
@Timeout(value = 240, unit = TimeUnit.SECONDS)
class PgBootstrapServingIntegrationTest {
    private static final List<String> MEMBERS = List.of("pg-node-1", "pg-node-2", "pg-node-3");
    private static final String TOKEN = "unused-without-acls";
    private static final String INSTALLED = "ANY 2 (\"pg-node-2\", \"pg-node-3\")";
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(20);
    private static final GenericContainer<?> CONSUL = new GenericContainer<>("hashicorp/consul:1.22.1")
        .withExposedPorts(8500)
        .withCommand("agent", "-dev", "-client=0.0.0.0", "-node=consul-1", "-log-level=warn")
        .waitingFor(Wait.forHttp("/v1/status/leader").forPort(8500).withStartupTimeout(Duration.ofSeconds(60)));
    private static final PgReplicationFixture NODES = new PgReplicationFixture();
    @TempDir Path stateDirectory;
    private final List<PgPrimaryElector> observers = new ArrayList<>();
    private Vertx vertx;
    private WebClient admin;
    private PgCommandRunner runner;
    private PgNodeConfig config;
    private ObservedLeaseCoordinator coordinator;
    private PgPrimaryElector elector;
    private PgLocalStateStore store;
    private PgProcessControl process;
    private PgAdmissionGate gate;
    private PgWatchdog watchdog;

    @BeforeAll static void startContainers() {
        CONSUL.start();
        NODES.start();
    }

    @AfterAll static void stopContainers() {
        NODES.close();
        CONSUL.stop();
    }

    // Provisioning three nodes can approach the 30-second Vert.x context default. The JUnit @Timeout on
    // the class does not set it: the extension reads io.vertx.junit5.Timeout.
    @io.vertx.junit5.Timeout(value = 120, timeUnit = TimeUnit.SECONDS)
    @BeforeEach void setUp(Vertx vertx, VertxTestContext context) {
        this.vertx = vertx;
        admin = WebClient.create(vertx);
        runner = new LocalCommandRunner(vertx);
        // The ownership budget (TTL minus stop) outlasts one test, so no renewal loop is needed.
        config = new PgNodeConfig("serving-tests", "inc-" + UUID.randomUUID(), WRITER, MEMBERS,
            Duration.ofSeconds(80), Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofSeconds(8),
            PgWatchdogMode.OFF);
        coordinator = new ObservedLeaseCoordinator(new ConsulLeaseCoordinator(vertx, config, endpoint(), "consul-1", TOKEN));
        elector = new PgPrimaryElector(config, coordinator);
        waitForLeader(System.nanoTime() + TimeUnit.SECONDS.toNanos(30))
            .compose(ignored -> NODES.provision(runner))
            .compose(ignored -> {
                process = NODES.control(runner, WRITER);
                gate = gate(NODES.node(WRITER).openHba(NODES.dataDirectory()));
                // This host has no watchdog device. Off mode never uses it.
                watchdog = new PgDeviceWatchdog(vertx, stateDirectory.resolve("no-watchdog"),
                    stateDirectory.resolve("no-watchdog-timeout"));
                return PgLocalStateStore.open(vertx, stateDirectory, config.nodeId());
            }).onSuccess(opened -> {
                store = opened;
                context.completeNow();
            }).onFailure(context::failNow);
    }

    @AfterEach void tearDown(VertxTestContext context) {
        NODES.forceStopAll(runner).eventually(() -> {
            Future<Void> closed = elector.close();
            for (PgPrimaryElector observer : observers) closed = closed.compose(ignored -> observer.close());
            return closed;
        }).eventually(() -> {
            admin.close();
            return Future.<Void>succeededFuture();
        }).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void servingInstallsConfirmsPreparesPublishesAndOpensAdmissionForRevisionOne(VertxTestContext context) {
        started(bootstrap(durability(COMMAND_TIMEOUT), gate)).compose(start -> start.bootstrap
            .serve(start.held, PgFailoverMode.MANUAL).compose(serving -> {
                JsonObject intent = serving.intent();
                assertEquals("SERVING", intent.getString("phase"));
                assertEquals(new PgDurabilityPolicy(1, STANDBYS).toJson(), intent.getJsonObject("durabilityPolicy"));
                assertFalse(intent.containsKey("pendingDurabilityPolicy"), "Confirming clears the pending policy");
                assertEquals(start.held.leaseHolder(), serving.leaseHolder());
                assertEquals(start.held.generation(), serving.generation());
                return observer().read().compose(read -> {
                    assertEquals(serving, read.orElseThrow(), "The coordinator does not hold the serving intent");
                    return store.grant();
                });
            }).compose(grant -> {
                assertEquals(PgGrantState.OPEN, grant.orElseThrow().state());
                assertEquals(1L, grant.orElseThrow().policyRevision());
                assertEquals(PgFailoverMode.MANUAL, grant.orElseThrow().mode());
                return NODES.node(WRITER).sql(runner, "show synchronous_standby_names");
            }).compose(effective -> {
                assertEquals(INSTALLED, effective.output().strip());
                return NODES.node(WRITER).applicationSql(runner, "create table served_probe (id int)");
            }).compose(write -> {
                assertEquals(0, write.exitCode(), "A committed write under the installed policy failed: " + write.output());
                return store.receipt(start.held.generation(), operationId(start.held), PgBootstrap.BOOTSTRAP, config.nodeId());
            })).onSuccess(receipt -> context.verify(() -> {
                assertTrue(receipt.isEmpty(), "Serving does not invent a bootstrap receipt; completion is derived");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation failed on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgDurabilityException.class)
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgBootstrap",
        message = "Bootstrap admission failed on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgDurabilityException.class)
    void aRequiredStandbyThatNeverConnectsLeavesTheIntentWithdrawnAndAdmissionClosed(VertxTestContext context) {
        var bounded = bootstrap(durability(Duration.ofSeconds(5)), gate);
        elector.createInitialIntent(intent()).compose(held -> bounded.monitor.startPrimary(held)
            .compose(ignored -> NODES.control(runner, STANDBYS.get(0)).startInRecovery())
            .compose(ignored -> bounded.bootstrap.serve(held, PgFailoverMode.MANUAL)).transform(serving -> {
                assertTrue(serving.failed(), "Admission opened without a required standby");
                assertInstanceOf(PgDurabilityException.class, serving.cause());
                return assertWithdrawnAndClosed(held);
            })).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgBootstrap",
        message = "Bootstrap admission failed on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgPrimaryElector",
        message = "Coordinator operation failed",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    void aFailedConfirmationLeavesThePolicyPendingAndAdmissionClosed(VertxTestContext context) {
        started(bootstrap(durability(COMMAND_TIMEOUT), gate)).compose(start -> {
            coordinator.onUpdate((held, intent) -> Future.failedFuture(new PgLeaseProtocolException("injected update failure")));
            return start.bootstrap.serve(start.held, PgFailoverMode.MANUAL).transform(serving -> {
                assertTrue(serving.failed(), "Serving succeeded although the confirmation was not written");
                coordinator.clearHooks();
                return store.receipt(start.held.generation(), operationId(start.held), PgFailoverMonitor.INSTALL_POLICY,
                    config.nodeId());
            }).compose(receipt -> {
                assertEquals(PgActionResult.COMPLETED, receipt.orElseThrow().result(), "The installation took effect");
                return assertWithdrawnAndClosed(start.held);
            });
        }).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgBootstrap",
        message = "Bootstrap admission failed on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgPrimaryElector",
        message = "Coordinator operation failed",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    void aFailedPublicationLeavesAPreparedGrantUnopenedAndAdmissionClosed(VertxTestContext context) {
        started(bootstrap(durability(COMMAND_TIMEOUT), gate)).compose(start -> {
            // The confirmation is written; the publication of SERVING intent is not.
            coordinator.onUpdate((held, intent) -> "SERVING".equals(intent.getString("phase"))
                ? Future.failedFuture(new PgLeaseProtocolException("injected update failure"))
                : coordinator.realUpdate(held, intent));
            return start.bootstrap.serve(start.held, PgFailoverMode.MANUAL).transform(serving -> {
                assertTrue(serving.failed(), "Serving succeeded although SERVING intent was not published");
                coordinator.clearHooks();
                return observer().read();
            }).compose(read -> {
                JsonObject intent = read.orElseThrow().intent();
                assertEquals("WITHDRAWN", intent.getString("phase"));
                assertEquals(new PgDurabilityPolicy(1, STANDBYS).toJson(), intent.getJsonObject("durabilityPolicy"));
                assertFalse(intent.containsKey("pendingDurabilityPolicy"));
                return store.grant();
            }).compose(grant -> {
                assertEquals(PgGrantState.PREPARED, grant.orElseThrow().state(), "The grant must stay prepared");
                return NODES.node(WRITER).applicationSql(runner, "select 1");
            }).map(application -> {
                assertNotEquals(0, application.exitCode(), "Admission opened without published SERVING intent");
                return null;
            });
        }).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Revoking writer admission on node pg-node-1: Writer activation did not complete under ownership",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgProcessControlException.class)
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation failed on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgProcessControlException.class)
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgBootstrap",
        message = "Bootstrap admission failed on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgProcessControlException.class)
    void aGateThatDoesNotOpenClosesTheGrantAndAdmitsNothing(VertxTestContext context) {
        var broken = bootstrap(durability(COMMAND_TIMEOUT), gate(NODES.dataDirectory() + "/absent.conf"));
        started(broken).compose(start -> start.bootstrap.serve(start.held, PgFailoverMode.MANUAL).transform(serving -> {
            assertTrue(serving.failed(), "Serving succeeded although admission could not open");
            assertInstanceOf(PgProcessControlException.class, serving.cause());
            return store.grant();
        }).compose(grant -> {
            assertEquals(PgGrantState.CLOSED, grant.orElseThrow().state());
            return NODES.node(WRITER).applicationSql(runner, "select 1");
        }).map(application -> {
            assertNotEquals(0, application.exitCode());
            return null;
        })).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation rejected on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgBootstrap",
        message = "Bootstrap admission failed on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    void servingTwiceIsRefusedAndLeavesTheServingIntentUnchanged(VertxTestContext context) {
        started(bootstrap(durability(COMMAND_TIMEOUT), gate)).compose(start -> start.bootstrap
            .serve(start.held, PgFailoverMode.MANUAL).compose(serving -> start.bootstrap
                .serve(start.held, PgFailoverMode.MANUAL).transform(again -> {
                    assertTrue(again.failed(), "A second serving ran over a serving record");
                    assertInstanceOf(PgLeaseProtocolException.class, again.cause());
                    return observer().read();
                }).map(read -> {
                    assertEquals(serving, read.orElseThrow());
                    return null;
                }))).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    /** Asserts the coordinator still holds withdrawn intent with the pending policy, and no grant is open. */
    private Future<Void> assertWithdrawnAndClosed(PgControlRecord held) {
        return observer().read().compose(read -> {
            JsonObject intent = read.orElseThrow().intent();
            assertEquals("WITHDRAWN", intent.getString("phase"));
            assertEquals(held.intent().getJsonObject("pendingDurabilityPolicy"), intent.getJsonObject("pendingDurabilityPolicy"));
            assertFalse(intent.containsKey("durabilityPolicy"), "A policy was confirmed without a complete installation");
            return store.grant();
        }).compose(grant -> {
            assertTrue(grant.filter(open -> open.state() != PgGrantState.CLOSED).isEmpty(), "A grant was prepared or opened");
            return NODES.node(WRITER).applicationSql(runner, "select 1");
        }).map(application -> {
            assertNotEquals(0, application.exitCode(), "Admission opened");
            return null;
        });
    }

    /** The writer running under a fresh lease with both standbys running, as first-start bootstrap leaves it. */
    private Future<Started> started(Parts parts) {
        return elector.createInitialIntent(intent()).compose(held -> parts.monitor.startPrimary(held)
            .compose(ignored -> startStandbys()).map(ignored -> new Started(parts.bootstrap, held)));
    }

    private record Started(PgBootstrap bootstrap, PgControlRecord held) { }

    private record Parts(PgBootstrap bootstrap, PgFailoverMonitor monitor) { }

    private Future<Void> startStandbys() {
        Future<Void> started = Future.succeededFuture();
        for (String standby : STANDBYS) {
            started = started.compose(ignored -> NODES.control(runner, standby).startInRecovery());
        }
        return started;
    }

    private Parts bootstrap(PgDurabilityControl durability, PgAdmissionGate admission) {
        var monitor = new PgFailoverMonitor(config, elector, store, process, admission, watchdog, durability);
        var loop = new PgRenewalLoop(vertx, config, elector, monitor);
        return new Parts(new PgBootstrap(vertx, config, elector, store, monitor, loop), monitor);
    }

    private PgPrimaryElector observer() {
        var observer = new PgPrimaryElector(config, new ConsulLeaseCoordinator(vertx, config, endpoint(), "consul-1", TOKEN));
        observers.add(observer);
        return observer;
    }

    private PgAdmissionGate gate(String openFile) {
        String directory = NODES.dataDirectory();
        var writer = NODES.node(WRITER);
        return new PgHbaAdmissionGate(vertx, runner, writer.prefix(), PgSupervisedContainer.PG_CTL, "psql", directory,
            openFile, writer.closedHba(directory), COMMAND_TIMEOUT);
    }

    private PgDurabilityControl durability(Duration budget) {
        return new PgSqlDurabilityControl(vertx, runner, NODES.node(WRITER).prefix(), PgSupervisedContainer.PG_CTL,
            "psql", NODES.dataDirectory(), budget);
    }

    private JsonObject intent() {
        return new JsonObject().put("writerNodeId", WRITER).put("phase", "WITHDRAWN")
            .put("operationId", UUID.randomUUID().toString())
            .put("pendingDurabilityPolicy", new PgDurabilityPolicy(1, STANDBYS).toJson());
    }

    private static String operationId(PgControlRecord held) {
        return held.intent().getString("operationId");
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
