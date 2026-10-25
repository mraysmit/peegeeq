package dev.mars.peegeeq.failover;

import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgCommitOutcomeUnknownException;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.failover.consul.ConsulLeaseCoordinator;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.logging.ExpectedErrorLog;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Tuple;
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
    private volatile boolean consulPaused;
    private PgConnectionManager connectionManager;

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
        // A frozen coordinator must be resumed before anything else talks to it.
        (consulPaused ? resumeConsul() : Future.<Void>succeededFuture())
            .compose(ignored -> connectionManager == null ? Future.<Void>succeededFuture() : connectionManager.close())
            .compose(ignored -> NODES.forceStopAll(runner)).eventually(() -> {
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
    void aRerunAfterACoverageFailureCompletesOnceTheMissingStandbyConnects(VertxTestContext context) {
        var bounded = bootstrap(durability(Duration.ofSeconds(5)), gate);
        elector.createInitialIntent(intent()).compose(held -> bounded.monitor.startPrimary(held)
            .compose(ignored -> NODES.control(runner, STANDBYS.get(0)).startInRecovery())
            .compose(ignored -> bounded.bootstrap.serve(held, PgFailoverMode.MANUAL)).transform(first -> {
                assertTrue(first.failed(), "Admission opened without a required standby");
                return NODES.control(runner, STANDBYS.get(1)).startInRecovery();
            }).compose(ignored -> bounded.bootstrap.serve(held, PgFailoverMode.MANUAL)))
            .compose(serving -> {
                assertEquals("SERVING", serving.intent().getString("phase"));
                return store.grant();
            }).onSuccess(grant -> context.verify(() -> {
                assertEquals(PgGrantState.OPEN, grant.orElseThrow().state());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgPrimaryElector",
        message = "Coordinator operation failed",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation rejected on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgBootstrap",
        message = "Bootstrap admission failed on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class,
        minOccurrences = 2, maxOccurrences = 2)
    void aRerunAfterAFailedPublicationIsRefusedBecauseOwnershipWasRetired(VertxTestContext context) {
        started(bootstrap(durability(COMMAND_TIMEOUT), gate)).compose(start -> {
            coordinator.onUpdate((held, intent) -> "SERVING".equals(intent.getString("phase"))
                ? Future.failedFuture(new PgLeaseProtocolException("injected update failure"))
                : coordinator.realUpdate(held, intent));
            return start.bootstrap.serve(start.held, PgFailoverMode.MANUAL).transform(first -> {
                assertTrue(first.failed());
                coordinator.clearHooks();
                assertFalse(elector.hasFreshOwnership(), "A failed update must retire ownership");
                return start.bootstrap.serve(start.held, PgFailoverMode.MANUAL);
            }).transform(second -> {
                assertTrue(second.failed(), "A serve ran without ownership");
                assertInstanceOf(PgLeaseProtocolException.class, second.cause());
                return store.grant();
            });
        }).onSuccess(grant -> context.verify(() -> {
            assertEquals(PgGrantState.PREPARED, grant.orElseThrow().state(), "The rerun changed the prepared grant");
            context.completeNow();
        })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgPrimaryElector",
        message = "Coordinator operation failed",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgBootstrap",
        message = "Bootstrap admission failed on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    void aCoordinatorThatStopsAnsweringBeforePublicationLeavesAPreparedGrantUnopenedAndAdmissionClosed(
            VertxTestContext context) {
        started(bootstrap(durability(COMMAND_TIMEOUT), gate)).compose(start -> {
            // The real adapter meets a real fault: the Consul process is frozen when the publication is sent.
            coordinator.onUpdate((held, intent) -> "SERVING".equals(intent.getString("phase"))
                ? pauseConsul().compose(paused -> coordinator.realUpdate(held, intent))
                : coordinator.realUpdate(held, intent));
            return start.bootstrap.serve(start.held, PgFailoverMode.MANUAL).transform(serving -> {
                coordinator.clearHooks();
                assertTrue(serving.failed(), "Serving succeeded although the coordinator never answered the publication");
                return resumeConsul();
            }).compose(resumed -> store.grant()).compose(grant -> {
                assertEquals(PgGrantState.PREPARED, grant.orElseThrow().state());
                return NODES.node(WRITER).applicationSql(runner, "select 1");
            });
        }).onSuccess(application -> context.verify(() -> {
            assertNotEquals(0, application.exitCode(), "Admission opened after an unanswered publication");
            context.completeNow();
        })).onFailure(context::failNow);
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
    // The test waits for a real freshness deadline of 22 seconds, so it needs more than the 30-second default.
    @io.vertx.junit5.Timeout(value = 120, timeUnit = TimeUnit.SECONDS)
    void ownershipThatExpiredInRealTimeBeforeServingRefusesItAndAdmitsNothing(VertxTestContext context) {
        var expiring = expiringOwnership();
        var parts = bootstrapOn(expiring.config, expiring.elector, durability(COMMAND_TIMEOUT), gate);
        expiring.elector.createInitialIntent(intent()).compose(held -> parts.monitor.startPrimary(held)
            .compose(ignored -> awaitStale(vertx, expiring.elector, System.nanoTime() + TimeUnit.SECONDS.toNanos(60)))
            .compose(ignored -> parts.bootstrap.serve(held, PgFailoverMode.MANUAL)).transform(serving -> {
                assertTrue(serving.failed(), "A serve ran on ownership past its freshness deadline");
                assertInstanceOf(PgLeaseProtocolException.class, serving.cause());
                return assertWithdrawnAndClosed(held, expiring.config);
            })).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation failed on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgBootstrap",
        message = "Bootstrap admission failed on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    // The test waits for a real freshness deadline of 22 seconds, so it needs more than the 30-second default.
    @io.vertx.junit5.Timeout(value = 120, timeUnit = TimeUnit.SECONDS)
    void ownershipThatExpiresInRealTimeDuringTheInstallationStopsBeforeConfirmation(VertxTestContext context) {
        var expiring = expiringOwnership();
        var waiting = new ExpiryWaitingDurabilityControl(vertx, durability(COMMAND_TIMEOUT), expiring.elector);
        var parts = bootstrapOn(expiring.config, expiring.elector, waiting, gate);
        expiring.elector.createInitialIntent(intent()).compose(held -> parts.monitor.startPrimary(held)
            .compose(ignored -> startStandbys())
            .compose(ignored -> parts.bootstrap.serve(held, PgFailoverMode.MANUAL)).transform(serving -> {
                assertTrue(serving.failed(), "Serving continued after ownership expired during the installation");
                assertInstanceOf(PgLeaseProtocolException.class, serving.cause());
                return store.receipt(held.generation(), operationId(held), PgFailoverMonitor.INSTALL_POLICY,
                    config.nodeId());
            }).compose(receipt -> {
                assertEquals(PgActionResult.COMPLETED, receipt.orElseThrow().result(), "The installation took effect");
                return observer(expiring.config).read();
            }).compose(read -> {
                JsonObject intent = read.orElseThrow().intent();
                assertEquals("WITHDRAWN", intent.getString("phase"));
                assertFalse(intent.containsKey("durabilityPolicy"), "A policy was confirmed without ownership");
                return NODES.node(WRITER).applicationSql(runner, "select 1");
            })).onSuccess(application -> context.verify(() -> {
                assertNotEquals(0, application.exitCode(), "Admission opened after ownership expired");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    // ---------------------------------------------------------------- production-manager write (OD-12)

    @Test
    @io.vertx.junit5.Timeout(value = 120, timeUnit = TimeUnit.SECONDS)
    void aProductionManagerWriteBeforeServingIsRejectedByTheClosedGate(VertxTestContext context) {
        started(bootstrap(durability(COMMAND_TIMEOUT), gate)).compose(start -> createProbeTable()
            .compose(created -> writeThroughTheProductionManager()).transform(write -> {
                assertTrue(write.failed(), "The production manager wrote through a closed admission gate");
                return NODES.node(WRITER).sql(runner, "select count(*) from " + PROBE_SCHEMA + ".writes");
            })).onSuccess(count -> context.verify(() -> {
                assertEquals("0", count.output().strip(), "A rejected write left a row");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    @io.vertx.junit5.Timeout(value = 120, timeUnit = TimeUnit.SECONDS)
    void aProductionManagerWriteAfterServingIsAcknowledgedByBothStandbys(VertxTestContext context) {
        var control = durability(COMMAND_TIMEOUT);
        started(bootstrap(control, gate)).compose(start -> createProbeTable()
            .compose(created -> start.bootstrap.serve(start.held, PgFailoverMode.MANUAL))
            .compose(serving -> writeThroughTheProductionManager())
            .compose(lsn -> control.peers().map(peers -> {
                for (String standby : STANDBYS) {
                    var peer = peers.stream().filter(found -> standby.equals(found.applicationName())).findFirst()
                        .orElseThrow(() -> new AssertionError(standby + " is not connected"));
                    assertTrue(peer.flushLsn().reached(lsn), standby + " has not flushed the acknowledged write: "
                        + peer.flushLsn() + " before " + lsn);
                    assertEquals("quorum", peer.syncState());
                }
                return null;
            }))).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @io.vertx.junit5.Timeout(value = 120, timeUnit = TimeUnit.SECONDS)
    void aWeakenedCommitPolicyStillWaitsForBothStandbysAndReportsAnUnknownOutcome(VertxTestContext context) {
        var control = durability(COMMAND_TIMEOUT);
        var missing = STANDBYS.get(1);
        started(bootstrap(control, gate)).compose(start -> createProbeTable()
            .compose(created -> start.bootstrap.serve(start.held, PgFailoverMode.MANUAL))
            .compose(serving -> NODES.node(missing).forceStop(runner, NODES.dataDirectory()))
            .compose(stopped -> awaitPeerGone(control, missing, System.nanoTime() + TimeUnit.SECONDS.toNanos(30)))
            .compose(gone -> {
                var manager = productionManager();
                // The caller asks for asynchronous commit. The manager restores the required policy before commit.
                Future<Void> write = manager.withTransaction(WRITER_SERVICE, connection ->
                    connection.query("select pg_backend_pid() as pid").execute().compose(rows -> {
                        writeBackend = rows.iterator().next().getInteger("pid");
                        return connection.query("set local synchronous_commit = off").execute();
                    }).compose(set -> connection.preparedQuery("insert into " + PROBE_SCHEMA + ".writes (id) values ($1)")
                        .execute(Tuple.of(2))).mapEmpty());
                return awaitCommitWait(manager, write, System.nanoTime() + TimeUnit.SECONDS.toNanos(15))
                    .compose(waiting -> manager.withTransaction(OBSERVER_SERVICE, connection ->
                        connection.preparedQuery("select pg_cancel_backend($1) as cancelled")
                            .execute(Tuple.of(writeBackend))))
                    .compose(cancelled -> {
                        assertTrue(cancelled.iterator().next().getBoolean("cancelled"));
                        return write.transform(result -> {
                            assertTrue(result.failed(), "A write was acknowledged with a required standby missing");
                            assertInstanceOf(PgCommitOutcomeUnknownException.class, result.cause());
                            return NODES.node(WRITER).sql(runner, "select count(*) from " + PROBE_SCHEMA + ".writes");
                        });
                    });
            })).onSuccess(count -> context.verify(() -> {
                assertEquals("1", count.output().strip(),
                    "The unacknowledged commit is locally committed; it is not reported as a rollback");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    private static final String PROBE_SCHEMA = "bootstrap_probe";
    private static final String WRITER_SERVICE = "writer";
    private static final String OBSERVER_SERVICE = "observer";
    private volatile int writeBackend;

    /** The table the production manager writes. It is created over the local socket, which the gate never closes. */
    private Future<Void> createProbeTable() {
        return NODES.node(WRITER).sql(runner, "create schema " + PROBE_SCHEMA + "; create table " + PROBE_SCHEMA
            + ".writes (id int)").map(created -> {
                assertEquals(0, created.exitCode(), created.output());
                return null;
            });
    }

    /** The production connection manager, connected directly to the writer node, not through a proxy. */
    private PgConnectionManager productionManager() {
        if (connectionManager != null) return connectionManager;
        connectionManager = new PgConnectionManager(vertx);
        var pool = new PgPoolConfig.Builder().maxSize(3).shared(false)
            .connectionTimeout(Duration.ofSeconds(2)).idleTimeout(Duration.ofSeconds(2)).build();
        var writer = new PgConnectionConfig.Builder().host(NODES.node(WRITER).host())
            .port(NODES.node(WRITER).mappedPort()).database("postgres").username("postgres").password("postgres")
            .schema(PROBE_SCHEMA).build();
        connectionManager.getOrCreateReactivePool(WRITER_SERVICE, writer, pool);
        connectionManager.getOrCreateReactivePool(OBSERVER_SERVICE, writer, pool);
        return connectionManager;
    }

    /** One acknowledged write. Returns the WAL insert position read inside the transaction. */
    private Future<PgLsn> writeThroughTheProductionManager() {
        return productionManager().withTransaction(WRITER_SERVICE, connection ->
            connection.preparedQuery("insert into " + PROBE_SCHEMA + ".writes (id) values ($1)")
                .execute(Tuple.of(1))
                .compose(inserted -> connection.query("select pg_current_wal_insert_lsn()::text as lsn").execute())
                .map(rows -> PgLsn.parse(rows.iterator().next().getString("lsn"))));
    }

    private Future<Void> awaitPeerGone(PgDurabilityControl control, String standby, long deadline) {
        return control.peers().compose(peers -> {
            if (peers.stream().noneMatch(peer -> standby.equals(peer.applicationName()))) {
                return Future.<Void>succeededFuture();
            }
            if (System.nanoTime() >= deadline) {
                return Future.<Void>failedFuture(new AssertionError(standby + " is still connected"));
            }
            return vertx.timer(100).compose(ignored -> awaitPeerGone(control, standby, deadline));
        });
    }

    /** Polls until the write's backend waits for synchronous replication. Fails if the write ends first. */
    private Future<Void> awaitCommitWait(PgConnectionManager manager, Future<?> write, long deadline) {
        if (write.isComplete()) {
            return Future.failedFuture(new AssertionError("The write completed before the missing standby returned"));
        }
        return manager.withTransaction(OBSERVER_SERVICE, connection -> connection.preparedQuery(
                "select count(*) as count from pg_stat_activity where pid = $1 and wait_event = 'SyncRep'")
            .execute(Tuple.of(writeBackend))).compose(rows -> {
                if (rows.iterator().next().getLong("count") == 1) return Future.<Void>succeededFuture();
                if (System.nanoTime() >= deadline) {
                    return Future.<Void>failedFuture(new AssertionError("The commit never waited for synchronous replication"));
                }
                return vertx.timer(25).compose(ignored -> awaitCommitWait(manager, write, deadline));
            });
    }

    /** Wraps the real durability control. After the installation took effect it waits until ownership is stale. */
    private record ExpiryWaitingDurabilityControl(Vertx vertx, PgDurabilityControl real, PgPrimaryElector elector)
            implements PgDurabilityControl {
        @Override public Future<PgLsn> flushBoundary() { return real.flushBoundary(); }

        @Override public Future<List<PgReplicationPeer>> peers() { return real.peers(); }

        @Override public Future<Void> confirmCovered(PgDurabilityPolicy policy) { return real.confirmCovered(policy); }

        @Override public Future<Void> install(PgDurabilityPolicy policy) {
            return real.install(policy).compose(installed ->
                awaitStale(vertx, elector, System.nanoTime() + TimeUnit.SECONDS.toNanos(60)));
        }
    }

    private record Expiring(PgNodeConfig config, PgPrimaryElector elector) { }

    /** A cluster incarnation whose ownership budget is 22 seconds, so its freshness deadline passes during a test. */
    private Expiring expiringOwnership() {
        var shortLease = new PgNodeConfig("serving-tests", "inc-" + UUID.randomUUID(), WRITER, MEMBERS,
            Duration.ofSeconds(30), Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofSeconds(8),
            PgWatchdogMode.OFF);
        var shortElector = new PgPrimaryElector(shortLease,
            new ConsulLeaseCoordinator(vertx, shortLease, endpoint(), "consul-1", TOKEN));
        observers.add(shortElector);
        return new Expiring(shortLease, shortElector);
    }

    /** Polls until the elector's freshness deadline has passed. Ownership is never renewed in these tests. */
    private static Future<Void> awaitStale(Vertx vertx, PgPrimaryElector owner, long deadline) {
        if (!owner.hasFreshOwnership()) return Future.succeededFuture();
        if (System.nanoTime() >= deadline) {
            return Future.failedFuture(new AssertionError("Ownership stayed fresh past the expected budget"));
        }
        return vertx.timer(200).compose(ignored -> awaitStale(vertx, owner, deadline));
    }

    private Future<Void> pauseConsul() {
        return vertx.executeBlocking(() -> {
            CONSUL.getDockerClient().pauseContainerCmd(CONSUL.getContainerId()).exec();
            consulPaused = true;
            return null;
        });
    }

    private Future<Void> resumeConsul() {
        return vertx.executeBlocking(() -> {
            CONSUL.getDockerClient().unpauseContainerCmd(CONSUL.getContainerId()).exec();
            consulPaused = false;
            return null;
        });
    }

    /** Asserts the coordinator still holds withdrawn intent with the pending policy, and no grant is open. */
    private Future<Void> assertWithdrawnAndClosed(PgControlRecord held) {
        return assertWithdrawnAndClosed(held, config);
    }

    private Future<Void> assertWithdrawnAndClosed(PgControlRecord held, PgNodeConfig recordConfig) {
        return observer(recordConfig).read().compose(read -> {
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
        return bootstrapOn(config, elector, durability, admission);
    }

    private Parts bootstrapOn(PgNodeConfig nodeConfig, PgPrimaryElector owner, PgDurabilityControl durability,
                              PgAdmissionGate admission) {
        var monitor = new PgFailoverMonitor(nodeConfig, owner, store, process, admission, watchdog, durability);
        var loop = new PgRenewalLoop(vertx, nodeConfig, owner, monitor);
        return new Parts(new PgBootstrap(vertx, nodeConfig, owner, store, monitor, loop), monitor);
    }

    private PgPrimaryElector observer() {
        return observer(config);
    }

    private PgPrimaryElector observer(PgNodeConfig recordConfig) {
        var observer = new PgPrimaryElector(recordConfig,
            new ConsulLeaseCoordinator(vertx, recordConfig, endpoint(), "consul-1", TOKEN));
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
