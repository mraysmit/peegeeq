package dev.mars.peegeeq.failover.consul;

import dev.mars.peegeeq.failover.LocalCommandRunner;
import dev.mars.peegeeq.failover.PgActionResult;
import dev.mars.peegeeq.failover.PgAdmissionGate;
import dev.mars.peegeeq.failover.PgCommandRunner;
import dev.mars.peegeeq.failover.PgConfigurationControl;
import dev.mars.peegeeq.failover.PgConfigurationException;
import dev.mars.peegeeq.failover.PgControlRecord;
import dev.mars.peegeeq.failover.PgCtlProcessControl;
import dev.mars.peegeeq.failover.PgDataDirectory;
import dev.mars.peegeeq.failover.PgDeviceWatchdog;
import dev.mars.peegeeq.failover.PgFailoverMode;
import dev.mars.peegeeq.failover.PgFailoverMonitor;
import dev.mars.peegeeq.failover.PgGrantState;
import dev.mars.peegeeq.failover.PgHbaAdmissionGate;
import dev.mars.peegeeq.failover.PgLeaseProtocolException;
import dev.mars.peegeeq.failover.PgLocalStateException;
import dev.mars.peegeeq.failover.PgLocalStateStore;
import dev.mars.peegeeq.failover.PgMemberPositions;
import dev.mars.peegeeq.failover.PgNodeConfig;
import dev.mars.peegeeq.failover.PgPrimaryElector;
import dev.mars.peegeeq.failover.PgProcessControl;
import dev.mars.peegeeq.failover.PgProcessControlException;
import dev.mars.peegeeq.failover.PgProcessState;
import dev.mars.peegeeq.failover.PgPromotionException;
import dev.mars.peegeeq.failover.PgRestartRequest;
import dev.mars.peegeeq.failover.PgRestartResult;
import dev.mars.peegeeq.failover.PgRestartRole;
import dev.mars.peegeeq.failover.PgSqlConfigurationControl;
import dev.mars.peegeeq.failover.PgSqlDurabilityControl;
import dev.mars.peegeeq.failover.PgSqlPromotionControl;
import dev.mars.peegeeq.failover.PgSupervisedContainer;
import dev.mars.peegeeq.failover.PgWatchdogMode;
import dev.mars.peegeeq.failover.PgWriterEndpoint;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.logging.ExpectedErrorLog;
import dev.mars.peegeeq.test.vertx.TestCleanup;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
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
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Reload and restart through the supervisor (plan §9.33, contracts 2 and 3): a real coordinator, a real PostgreSQL that
 * only the supervisor starts, and real node-local storage. A restart is the supervisor's stop and guarded start (OD-54);
 * a serving writer is admitted again (OD-58); a condition that is not met restarts nothing (OD-57).
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Isolated
@Timeout(value = 180, unit = TimeUnit.SECONDS)
@io.vertx.junit5.Timeout(value = 180, timeUnit = TimeUnit.SECONDS)
class PgRestartIntegrationTest {
    private static final List<String> MEMBERS = List.of("pg-node-1", "pg-node-2", "pg-node-3");
    private static final String TOKEN = "unused-without-acls";
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(20);
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
    private PgPrimaryElector elector;
    private PgMemberPositions positions;
    private PgLocalStateStore store;
    private PgProcessControl process;
    private PgAdmissionGate gate;
    private PgFailoverMonitor monitor;

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
        // A 60-second lease and an 8-second stop budget give 52 seconds of ownership without a renewal loop.
        config = new PgNodeConfig("restart-tests", "inc-" + UUID.randomUUID(), "pg-node-1", MEMBERS,
            Duration.ofSeconds(60), Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofSeconds(8),
            PgWatchdogMode.OFF);
        elector = new PgPrimaryElector(config, new ConsulLeaseCoordinator(vertx, config, endpoint(), "consul-1", TOKEN));
        positions = new ConsulMemberPositions(vertx, config, endpoint(), "consul-1", TOKEN);
        waitForLeader(System.nanoTime() + TimeUnit.SECONDS.toNanos(30))
            .compose(ignored -> POSTGRES.newDataDirectory(runner))
            .compose(created -> {
                dataDirectory = created;
                return POSTGRES.provisionAdmission(runner, created);
            }).compose(ignored -> PgLocalStateStore.open(vertx, stateDirectory, config.nodeId()))
            .onSuccess(opened -> context.verify(() -> {
                store = opened;
                process = new PgCtlProcessControl(runner, POSTGRES.prefix(), PgSupervisedContainer.PG_CTL, dataDirectory,
                    POSTGRES.logFile(dataDirectory), COMMAND_TIMEOUT);
                gate = gate(POSTGRES.openHba(dataDirectory));
                monitor = monitorOf(process, gate);
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @AfterEach void tearDown(VertxTestContext context) {
        TestCleanup.run(
                () -> POSTGRES.forceStop(runner, dataDirectory),
                () -> elector.close(),
                () -> positions.close(),
                () -> {
                    admin.close();
                    return Future.<Void>succeededFuture();
                })
            .onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    // ---------------------------------------------------------------- writer

    @Test void aServingWriterRestartsUnderItsLeaseAndServesAgain(VertxTestContext context) {
        var request = PgRestartRequest.now();
        servingWriter().compose(serving -> startTime().compose(before -> monitor.restart(serving, request).compose(result -> {
            assertTrue(result.restarted(), result.reason());
            return startTime().map(after -> {
                assertTrue(after > before, "The postmaster did not start again");
                return serving;
            });
        }))).compose(serving -> sql("select pg_is_in_recovery()").compose(recovery -> {
            assertEquals("f", recovery, "The restarted writer is in recovery");
            return store.grant();
        }).compose(grant -> {
            assertEquals(PgGrantState.OPEN, grant.orElseThrow().state(), "A serving writer was not admitted again");
            assertEquals(1L, grant.orElseThrow().policyRevision());
            return POSTGRES.applicationSql(runner, "select 1");
        }).compose(application -> {
            assertEquals(0, application.exitCode(), "An application was not admitted after the restart: "
                + application.output());
            return store.receipt(serving.generation(), request.operationId(), "restart", config.nodeId());
        })).onSuccess(receipt -> context.verify(() -> {
            assertEquals(PgActionResult.COMPLETED, receipt.orElseThrow().result());
            assertEquals(request.conditions(), receipt.orElseThrow().parameters());
            assertTrue(elector.hasFreshOwnership(), "A restart ended ownership");
            context.completeNow();
        })).onFailure(context::failNow);
    }

    @Test void aWriterWithClosedAdmissionRestartsAndKeepsAdmissionClosed(VertxTestContext context) {
        elector.createInitialIntent(initialIntent("pg-node-1")).compose(held -> monitor.startPrimary(held)
            .compose(ignored -> startTime()).compose(before -> monitor.restart(held, PgRestartRequest.now()).compose(result -> {
                assertTrue(result.restarted(), result.reason());
                return startTime().map(after -> {
                    assertTrue(after > before, "The postmaster did not start again");
                    return null;
                });
            })))
            .compose(ignored -> POSTGRES.applicationSql(runner, "select 1")).compose(application -> {
                assertNotEquals(0, application.exitCode(), "A restart opened admission that was closed");
                return store.grant();
            }).onSuccess(grant -> context.verify(() -> {
                assertTrue(grant.filter(stored -> stored.state() == PgGrantState.OPEN).isEmpty(),
                    "A restart opened a grant that was not open");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation rejected on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    void aWriterRestartWithoutFreshOwnershipIsRefusedAndRestartsNothing(VertxTestContext context) {
        var unowned = new PgControlRecord(config.controlName(), 1, 1, UUID.randomUUID().toString(),
            initialIntent("pg-node-1"));
        process.startPrimary().compose(ignored -> unchangedAfter(() -> monitor.restart(unowned, PgRestartRequest.now()),
                PgLeaseProtocolException.class))
            .onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation failed on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLocalStateException.class)
    void aQuarantinedNodeIsRefusedAndRestartsNothing(VertxTestContext context) {
        elector.createInitialIntent(initialIntent("pg-node-1")).compose(held -> monitor.startPrimary(held)
                .compose(ignored -> store.quarantine("awaiting reconciliation"))
                .compose(ignored -> unchangedAfter(() -> monitor.restart(held, PgRestartRequest.now()),
                    PgLocalStateException.class)))
            .onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation failed on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgProcessControlException.class)
    void aStoppedServerIsRefused(VertxTestContext context) {
        monitor.restart(standbyRecord(), PgRestartRequest.now()).transform(restart -> {
            failedWith(restart, PgProcessControlException.class);
            return process.status();
        }).onSuccess(status -> context.verify(() -> {
            assertEquals(PgProcessState.STOPPED, status, "A refused restart started PostgreSQL");
            context.completeNow();
        })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation failed on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgPromotionException.class)
    void aWritableNodeThatTheRecordDoesNotNameIsRefusedAndRestartsNothing(VertxTestContext context) {
        // A writable node that the record does not name needs a re-join, not a restart.
        process.startPrimary().compose(ignored -> unchangedAfter(() -> monitor.restart(standbyRecord(),
                PgRestartRequest.now()), PgPromotionException.class))
            .onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Demoting local PostgreSQL on node pg-node-1: Restart did not complete under ownership",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgProcessControlException.class)
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation failed on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgProcessControlException.class)
    void aWriterWhoseStartFailsIsDemotedAndTheRestartFails(VertxTestContext context) {
        var request = PgRestartRequest.now();
        var failingStart = monitorOf(new FaultingProcessControl(process, false, true, false), gate);
        elector.createInitialIntent(initialIntent("pg-node-1")).compose(held -> monitor.startPrimary(held)
            .compose(ignored -> failingStart.restart(held, request)).transform(restart -> {
                failedWith(restart, PgProcessControlException.class);
                assertFalse(elector.hasFreshOwnership(), "A writer whose restart failed kept its ownership");
                return process.status();
            }).compose(status -> {
                assertEquals(PgProcessState.STOPPED, status, "A writer whose restart failed is running");
                return store.receipt(held.generation(), request.operationId(), "restart", config.nodeId());
            })).onSuccess(receipt -> context.verify(() -> {
                assertEquals(PgActionResult.UNKNOWN, receipt.orElseThrow().result());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
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
    void anUnconfirmedStopQuarantinesTheNodeAndFails(VertxTestContext context) {
        var request = PgRestartRequest.now();
        var failingStop = monitorOf(new FaultingProcessControl(process, true, false, false), gate);
        elector.createInitialIntent(initialIntent("pg-node-1")).compose(held -> monitor.startPrimary(held)
            .compose(ignored -> failingStop.restart(held, request)).transform(restart -> {
                failedWith(restart, PgProcessControlException.class);
                return store.quarantine();
            }).compose(quarantine -> {
                assertTrue(quarantine.isPresent(), "An unconfirmed stop did not quarantine the node");
                return store.receipt(held.generation(), request.operationId(), "restart", config.nodeId());
            })).onSuccess(receipt -> context.verify(() -> {
                assertEquals(PgActionResult.UNKNOWN, receipt.orElseThrow().result());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation failed on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgProcessControlException.class)
    void aRestartWhoseStopAndStartReportSuccessWithoutEffectFails(VertxTestContext context) {
        // The evidence of a restart is a later postmaster start time, not the result of the stop and start commands.
        var request = PgRestartRequest.now();
        var noEffect = monitorOf(new NoEffectProcessControl(process), gate);
        elector.createInitialIntent(initialIntent("pg-node-1")).compose(held -> monitor.startPrimary(held)
            .compose(ignored -> noEffect.restart(held, request)).transform(restart -> {
                failedWith(restart, PgProcessControlException.class);
                assertTrue(restart.cause().getMessage().startsWith("The postmaster did not start again"),
                    restart.cause().getMessage());
                return store.receipt(held.generation(), request.operationId(), "restart", config.nodeId());
            })).onSuccess(receipt -> context.verify(() -> {
                assertEquals(PgActionResult.UNKNOWN, receipt.orElseThrow().result());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Revoking writer admission on node pg-node-1: Restart did not restore admission",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgProcessControlException.class)
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation failed on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgProcessControlException.class)
    void aGateThatDoesNotOpenAgainRevokesAdmissionAndFails(VertxTestContext context) {
        var brokenOpen = monitorOf(process, gate(dataDirectory + "/absent.conf"));
        servingWriter().compose(serving -> brokenOpen.restart(serving, PgRestartRequest.now())).transform(restart -> {
            failedWith(restart, PgProcessControlException.class);
            return store.grant();
        }).compose(grant -> {
            assertEquals(PgGrantState.CLOSED, grant.orElseThrow().state(), "A failed re-admission left a grant open");
            return POSTGRES.applicationSql(runner, "select 1");
        }).onSuccess(application -> context.verify(() -> {
            assertNotEquals(0, application.exitCode(), "An application was admitted after a failed re-admission");
            context.completeNow();
        })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgLocalStateStore",
        message = "Node-local state operation failed",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLocalStateException.class)
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation failed on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLocalStateException.class)
    void aReceiptWithOtherParametersRefusesTheRestartAndRestartsNothing(VertxTestContext context) {
        var request = PgRestartRequest.now();
        elector.createInitialIntent(initialIntent("pg-node-1")).compose(held -> monitor.startPrimary(held)
                .compose(ignored -> store.begin(held.generation(), request.operationId(), "restart", config.nodeId(),
                    new JsonObject().put("onlyIfPending", true)))
                .compose(ignored -> unchangedAfter(() -> monitor.restart(held, request), PgLocalStateException.class)))
            .onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    // ---------------------------------------------------------------- standby

    @Test void aStandbyRestartsInRecovery(VertxTestContext context) {
        monitor.startStandby().compose(ignored -> startTime()).compose(before ->
                monitor.restart(standbyRecord(), PgRestartRequest.now()).compose(result -> {
                    assertTrue(result.restarted(), result.reason());
                    return startTime().map(after -> {
                        assertTrue(after > before, "The postmaster did not start again");
                        return null;
                    });
                }))
            .compose(ignored -> sql("select pg_is_in_recovery()"))
            .onSuccess(recovery -> context.verify(() -> {
                assertEquals("t", recovery, "A restarted standby left recovery");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation failed on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgProcessControlException.class)
    void aStandbyWhoseStartFailsFailsTheRestart(VertxTestContext context) {
        var request = PgRestartRequest.now();
        var observed = standbyRecord();
        var failingStart = monitorOf(new FaultingProcessControl(process, false, false, true), gate);
        monitor.startStandby().compose(ignored -> failingStart.restart(observed, request)).transform(restart -> {
            failedWith(restart, PgProcessControlException.class);
            return store.receipt(observed.generation(), request.operationId(), "restart", config.nodeId());
        }).onSuccess(receipt -> context.verify(() -> {
            assertEquals(PgActionResult.UNKNOWN, receipt.orElseThrow().result());
            context.completeNow();
        })).onFailure(context::failNow);
    }

    // ---------------------------------------------------------------- conditions

    @Test void aRestartThatRequiresAPendingParameterRestartsNothingWhenNoneIsPending(VertxTestContext context) {
        conditionHolds(context, new PgRestartRequest(UUID.randomUUID().toString(), true, Optional.empty(),
            OptionalInt.empty()), false, "pending");
    }

    @Test void aRestartThatRequiresAPendingParameterRestartsAndAppliesIt(VertxTestContext context) {
        var request = new PgRestartRequest(UUID.randomUUID().toString(), true, Optional.empty(), OptionalInt.empty());
        elector.createInitialIntent(initialIntent("pg-node-1")).compose(held -> monitor.startPrimary(held)
                .compose(ignored -> sql("alter system set max_connections = 150"))
                .compose(ignored -> monitor.restart(held, request)))
            .compose(result -> {
                assertTrue(result.restarted(), result.reason());
                return sql("show max_connections");
            }).onSuccess(effective -> context.verify(() -> {
                assertEquals("150", effective, "The pending parameter was not applied by the restart");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void aRestartThatRequiresAnotherRoleRestartsNothing(VertxTestContext context) {
        conditionHolds(context, new PgRestartRequest(UUID.randomUUID().toString(), false,
            Optional.of(PgRestartRole.STANDBY), OptionalInt.empty()), false, "STANDBY");
    }

    @Test void aRestartThatRequiresAVersionBelowTheServersRestartsNothing(VertxTestContext context) {
        serverVersion().onSuccess(version -> conditionHolds(context, new PgRestartRequest(UUID.randomUUID().toString(),
            false, Optional.empty(), OptionalInt.of(version)), false, String.valueOf(version))).onFailure(context::failNow);
    }

    @Test void aRestartThatRequiresAVersionAboveTheServersRestarts(VertxTestContext context) {
        serverVersion().onSuccess(version -> conditionHolds(context, new PgRestartRequest(UUID.randomUUID().toString(),
            false, Optional.of(PgRestartRole.WRITER), OptionalInt.of(version + 1)), true, ""))
            .onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation failed on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgConfigurationException.class)
    void anInvalidRestartParameterRefusesTheRestartBeforeAnythingIsStopped(VertxTestContext context) {
        // Plan §9.33, check 8: PostgreSQL does not start with an invalid value, so the writer would be left stopped.
        elector.createInitialIntent(initialIntent("pg-node-1")).compose(held -> monitor.startPrimary(held)
                .compose(ignored -> POSTGRES.exec(runner, "sh", "-c", "printf 'shared_buffers = nonsense\\n' >> "
                    + dataDirectory + "/postgresql.conf"))
                .compose(ignored -> unchangedAfter(() -> monitor.restart(held, PgRestartRequest.now()),
                    PgConfigurationException.class)))
            .onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    // ---------------------------------------------------------------- reload

    @Test void aReloadAppliesAReloadableParameter(VertxTestContext context) {
        monitor.startStandby().compose(ignored -> sql("alter system set work_mem = '8MB'"))
            .compose(ignored -> monitor.reload()).compose(ignored -> sql("show work_mem"))
            .onSuccess(workMem -> context.verify(() -> {
                assertEquals("8MB", workMem);
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void aReloadIsAllowedUnderQuarantine(VertxTestContext context) {
        // OD-61: a reload changes no role and no admission.
        monitor.startStandby().compose(ignored -> store.quarantine("awaiting reconciliation"))
            .compose(ignored -> sql("alter system set work_mem = '8MB'"))
            .compose(ignored -> monitor.reload()).compose(ignored -> sql("show work_mem"))
            .onSuccess(workMem -> context.verify(() -> {
                assertEquals("8MB", workMem);
                context.completeNow();
            })).onFailure(context::failNow);
    }

    // ---------------------------------------------------------------- scheduled restart (contracts 4 and 5)

    @Test void aDueScheduledRestartRunsAndIsRemoved(VertxTestContext context) {
        var request = PgRestartRequest.now();
        elector.createInitialIntent(initialIntent("pg-node-1")).compose(held -> monitor.startPrimary(held)
            .compose(ignored -> monitor.scheduleRestart(request, Instant.now().minusSeconds(1)))
            .compose(ignored -> startTime()).compose(before -> monitor.runScheduledRestart(held, Instant.now())
                .compose(result -> {
                    assertTrue(result.restarted(), result.reason());
                    return startTime().map(after -> {
                        assertTrue(after > before, "The postmaster did not start again");
                        return null;
                    });
                }))
            .compose(ignored -> store.scheduledRestart()).compose(stored -> {
                assertTrue(stored.isEmpty(), "A scheduled restart that ran is still stored");
                return store.receipt(held.generation(), request.operationId(), "restart", config.nodeId());
            })).onSuccess(receipt -> context.verify(() -> {
                assertEquals(PgActionResult.COMPLETED, receipt.orElseThrow().result());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void aScheduledRestartThatIsNotDueRunsNothingAndIsKept(VertxTestContext context) {
        elector.createInitialIntent(initialIntent("pg-node-1")).compose(held -> monitor.startPrimary(held)
            .compose(ignored -> monitor.scheduleRestart(PgRestartRequest.now(), Instant.now().plusSeconds(3600)))
            .compose(ignored -> startTime()).compose(before -> monitor.runScheduledRestart(held, Instant.now())
                .compose(result -> {
                    assertFalse(result.restarted(), "A scheduled restart ran before it was due");
                    assertTrue(result.reason().contains("due"), result.reason());
                    return startTime().map(after -> {
                        assertEquals(before, after, "A scheduled restart that was not due restarted PostgreSQL");
                        return null;
                    });
                })))
            .compose(ignored -> store.scheduledRestart())
            .onSuccess(stored -> context.verify(() -> {
                assertTrue(stored.isPresent(), "A scheduled restart that was not due was removed");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void aRestartSinceTheScheduleWasStoredCancelsIt(VertxTestContext context) {
        // Patroni's rule: a postmaster that started since the schedule was made means the restart already happened.
        elector.createInitialIntent(initialIntent("pg-node-1")).compose(held -> monitor.startPrimary(held)
            .compose(ignored -> monitor.scheduleRestart(PgRestartRequest.now(), Instant.now().minusSeconds(1)))
            .compose(ignored -> monitor.restart(held, PgRestartRequest.now()))
            .compose(ignored -> startTime()).compose(before -> monitor.runScheduledRestart(held, Instant.now())
                .compose(result -> {
                    assertFalse(result.restarted(), "A scheduled restart ran after PostgreSQL had restarted");
                    assertTrue(result.reason().contains("already"), result.reason());
                    return startTime().map(after -> {
                        assertEquals(before, after, "A cancelled schedule restarted PostgreSQL");
                        return null;
                    });
                })))
            .compose(ignored -> store.scheduledRestart())
            .onSuccess(stored -> context.verify(() -> {
                assertTrue(stored.isEmpty(), "A cancelled schedule is still stored");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void withoutAScheduleNothingRuns(VertxTestContext context) {
        elector.createInitialIntent(initialIntent("pg-node-1")).compose(held -> monitor.startPrimary(held)
            .compose(ignored -> monitor.runScheduledRestart(held, Instant.now())))
            .onSuccess(result -> context.verify(() -> {
                assertFalse(result.restarted());
                assertTrue(result.reason().startsWith("No restart is scheduled"), result.reason());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void cancellingRemovesTheScheduleAndReportsWhetherOneExisted(VertxTestContext context) {
        elector.createInitialIntent(initialIntent("pg-node-1")).compose(held -> monitor.startPrimary(held)
            .compose(ignored -> monitor.scheduleRestart(PgRestartRequest.now(), Instant.now().minusSeconds(1)))
            .compose(ignored -> monitor.cancelScheduledRestart()).compose(cancelled -> {
                assertTrue(cancelled, "Cancelling a stored schedule reported none");
                return monitor.cancelScheduledRestart();
            }).compose(again -> {
                assertFalse(again, "Cancelling twice reported a schedule");
                return monitor.runScheduledRestart(held, Instant.now());
            })).onSuccess(result -> context.verify(() -> {
                assertFalse(result.restarted(), "A cancelled schedule ran");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgLocalStateStore",
        message = "Node-local state operation failed",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLocalStateException.class)
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation failed on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLocalStateException.class)
    void aSecondScheduleIsRefused(VertxTestContext context) {
        monitor.startStandby().compose(ignored -> monitor.scheduleRestart(PgRestartRequest.now(),
                Instant.now().plusSeconds(3600)))
            .compose(ignored -> monitor.scheduleRestart(PgRestartRequest.now(), Instant.now().plusSeconds(7200)))
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgLocalStateException.class, failure);
                context.completeNow();
            })));
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Demoting local PostgreSQL on node pg-node-1: Restart did not complete under ownership",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgProcessControlException.class)
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation failed on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgProcessControlException.class)
    void aScheduledRestartThatFailsIsRemovedAndItsFailureReturned(VertxTestContext context) {
        var failingStart = monitorOf(new FaultingProcessControl(process, false, true, false), gate);
        elector.createInitialIntent(initialIntent("pg-node-1")).compose(held -> monitor.startPrimary(held)
            .compose(ignored -> failingStart.scheduleRestart(PgRestartRequest.now(), Instant.now().minusSeconds(1)))
            .compose(ignored -> failingStart.runScheduledRestart(held, Instant.now())))
            .transform(run -> {
                failedWith(run, PgProcessControlException.class);
                return store.scheduledRestart();
            }).onSuccess(stored -> context.verify(() -> {
                assertTrue(stored.isEmpty(), "A scheduled restart that failed is still stored");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation failed on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgConfigurationException.class)
    void aScheduleWhilePostgreSqlIsStoppedIsRefusedAndStoresNothing(VertxTestContext context) {
        monitor.scheduleRestart(PgRestartRequest.now(), Instant.now().plusSeconds(3600)).transform(schedule -> {
            failedWith(schedule, PgConfigurationException.class);
            return store.scheduledRestart();
        }).onSuccess(stored -> context.verify(() -> {
            assertTrue(stored.isEmpty(), "A refused schedule was stored");
            context.completeNow();
        })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation failed on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLocalStateException.class)
    void aScheduleUnderQuarantineIsRefusedAndStoresNothing(VertxTestContext context) {
        monitor.startStandby().compose(ignored -> store.quarantine("awaiting reconciliation"))
            .compose(ignored -> monitor.scheduleRestart(PgRestartRequest.now(), Instant.now().plusSeconds(3600)))
            .transform(schedule -> {
                failedWith(schedule, PgLocalStateException.class);
                return store.scheduledRestart();
            }).onSuccess(stored -> context.verify(() -> {
                assertTrue(stored.isEmpty(), "A refused schedule was stored");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    // ---------------------------------------------------------------- helpers

    /**
     * On a writer under its own lease: a condition that holds restarts it; one that does not leaves it untouched, and the
     * reason of the result contains {@code reasonNames}.
     */
    private void conditionHolds(VertxTestContext context, PgRestartRequest request, boolean restarts, String reasonNames) {
        elector.createInitialIntent(initialIntent("pg-node-1")).compose(held -> monitor.startPrimary(held)
            .compose(ignored -> startTime()).compose(before -> monitor.restart(held, request).compose(result -> {
                assertEquals(restarts, result.restarted(), result.reason());
                assertTrue(result.reason().contains(reasonNames), result.reason());
                return startTime().compose(after -> {
                    assertEquals(restarts, after > before, "The postmaster start time does not match the result");
                    return store.receipt(held.generation(), request.operationId(), "restart", config.nodeId());
                });
            }))).onSuccess(receipt -> context.verify(() -> {
                assertEquals(restarts, receipt.isPresent(), "A restart that did not run recorded a receipt, or one "
                    + "that ran recorded none");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    /** The restart fails with the given type, PostgreSQL keeps running, and its postmaster did not start again. */
    private Future<Void> unchangedAfter(java.util.function.Supplier<Future<PgRestartResult>> restart,
                                        Class<? extends Throwable> type) {
        return startTime().compose(before -> restart.get().transform(outcome -> {
            failedWith(outcome, type);
            return process.status();
        }).compose(status -> {
            assertEquals(PgProcessState.RUNNING, status, "A refused restart stopped PostgreSQL");
            return startTime();
        }).map(after -> {
            assertEquals(before, after, "A refused restart restarted PostgreSQL");
            return null;
        }));
    }

    private static void failedWith(AsyncResult<?> outcome, Class<? extends Throwable> type) {
        assertTrue(outcome.failed(), "The restart succeeded; expected " + type.getSimpleName());
        assertInstanceOf(type, outcome.cause());
    }

    /** A writer under this node's lease, serving with an open grant for policy revision 1. */
    private Future<PgControlRecord> servingWriter() {
        return elector.createInitialIntent(initialIntent("pg-node-1")).compose(held -> monitor.startPrimary(held)
            .compose(ignored -> elector.update(held, servingIntent(held))))
            .compose(serving -> monitor.prepareWriter(serving, PgFailoverMode.MANUAL)
                .compose(prepared -> monitor.activateWriter(serving, prepared)).map(open -> serving));
    }

    /** A record that names another member as writer, as a standby observes it. */
    private PgControlRecord standbyRecord() {
        return new PgControlRecord(config.controlName(), 1, 1, UUID.randomUUID().toString(), initialIntent("pg-node-2"));
    }

    private Future<Long> startTime() {
        return sql("select (extract(epoch from pg_postmaster_start_time()) * 1000000)::bigint").map(Long::parseLong);
    }

    private Future<Integer> serverVersion() {
        return monitor.startStandby().compose(ignored -> sql("show server_version_num")).map(Integer::parseInt)
            .compose(version -> process.stop(COMMAND_TIMEOUT).map(version));
    }

    private Future<String> sql(String statement) {
        return POSTGRES.sql(runner, statement).map(result -> {
            assertEquals(0, result.exitCode(), result.output());
            return result.output().strip();
        });
    }

    private PgFailoverMonitor monitorOf(PgProcessControl control, PgAdmissionGate admission) {
        var watchdog = new PgDeviceWatchdog(vertx, stateDirectory.resolve("no-watchdog"),
            stateDirectory.resolve("no-watchdog-timeout"));
        return PgFailoverMonitor.builder().config(config).elector(elector).store(store).process(control).gate(admission)
            .watchdog(watchdog)
            .durability(new PgSqlDurabilityControl(vertx, runner, POSTGRES.prefix(), PgSupervisedContainer.PG_CTL, "psql",
                dataDirectory, COMMAND_TIMEOUT))
            .promotion(new PgSqlPromotionControl(vertx, runner, POSTGRES.prefix(), PgSupervisedContainer.PG_CTL, "psql",
                dataDirectory, COMMAND_TIMEOUT))
            .positions(positions)
            .configuration(configuration())
            .build();
    }

    private PgConfigurationControl configuration() {
        return new PgSqlConfigurationControl(vertx, runner, POSTGRES.prefix(), PgSupervisedContainer.PG_CTL, "psql",
            dataDirectory, COMMAND_TIMEOUT);
    }

    private PgAdmissionGate gate(String openFile) {
        return new PgHbaAdmissionGate(vertx, runner, POSTGRES.prefix(), PgSupervisedContainer.PG_CTL, "psql",
            dataDirectory, openFile, POSTGRES.closedHba(dataDirectory), COMMAND_TIMEOUT);
    }

    private static JsonObject initialIntent(String writer) {
        return new JsonObject().put("writerNodeId", writer).put("phase", "WITHDRAWN")
            .put("operationId", UUID.randomUUID().toString())
            .put("pendingDurabilityPolicy", new JsonObject().put("revision", 1L)
                .put("requiredStandbyNodeIds", new JsonArray().add("pg-node-2").add("pg-node-3")));
    }

    /** Serving intent: the pending policy becomes the confirmed policy. */
    private static JsonObject servingIntent(PgControlRecord held) {
        JsonObject intent = held.intent();
        JsonObject policy = (JsonObject) intent.remove("pendingDurabilityPolicy");
        return intent.put("phase", "SERVING").put("durabilityPolicy", policy);
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

    /**
     * The real process control, except that a stop and a writable start report success and do nothing: PostgreSQL keeps
     * running, and its postmaster never starts again.
     */
    private record NoEffectProcessControl(PgProcessControl real) implements PgProcessControl {
        @Override public Future<PgProcessState> status() { return real.status(); }
        @Override public Future<Void> startInRecovery() { return real.startInRecovery(); }
        @Override public Future<Void> startPrimary() { return Future.succeededFuture(); }
        @Override public Future<Void> stop(Duration budget) { return Future.succeededFuture(); }
        @Override public Future<Optional<PgDataDirectory>> inspect() { return real.inspect(); }

        @Override public Future<Void> rewind(PgWriterEndpoint source, String nodeId, Duration budget) {
            return real.rewind(source, nodeId, budget);
        }

        @Override public Future<Void> rebuild(PgWriterEndpoint source, String nodeId, Duration budget) {
            return real.rebuild(source, nodeId, budget);
        }
    }

    /**
     * The real process control, with one operation made to fail without its effect: a stop that leaves PostgreSQL running,
     * or a start that leaves it stopped. Every other operation reaches the real control.
     */
    private record FaultingProcessControl(PgProcessControl real, boolean failStop, boolean failStartPrimary,
                                          boolean failStartInRecovery) implements PgProcessControl {
        @Override public Future<PgProcessState> status() { return real.status(); }

        @Override public Future<Void> startInRecovery() {
            return failStartInRecovery ? Future.failedFuture(new PgProcessControlException("Start in recovery failed"))
                : real.startInRecovery();
        }

        @Override public Future<Void> startPrimary() {
            return failStartPrimary ? Future.failedFuture(new PgProcessControlException("Primary start failed"))
                : real.startPrimary();
        }

        @Override public Future<Void> stop(Duration budget) {
            return failStop ? Future.failedFuture(new PgProcessControlException("Stop not confirmed")) : real.stop(budget);
        }

        @Override public Future<Optional<PgDataDirectory>> inspect() { return real.inspect(); }

        @Override public Future<Void> rewind(PgWriterEndpoint source, String nodeId, Duration budget) {
            return real.rewind(source, nodeId, budget);
        }

        @Override public Future<Void> rebuild(PgWriterEndpoint source, String nodeId, Duration budget) {
            return real.rebuild(source, nodeId, budget);
        }
    }
}
