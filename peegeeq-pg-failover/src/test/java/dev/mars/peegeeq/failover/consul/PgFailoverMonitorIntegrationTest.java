package dev.mars.peegeeq.failover.consul;

import dev.mars.peegeeq.failover.LocalCommandRunner;
import dev.mars.peegeeq.failover.PgActionResult;
import dev.mars.peegeeq.failover.PgAdmissionGate;
import dev.mars.peegeeq.failover.PgCommandResult;
import dev.mars.peegeeq.failover.PgCommandRunner;
import dev.mars.peegeeq.failover.PgControlRecord;
import dev.mars.peegeeq.failover.PgCtlProcessControl;
import dev.mars.peegeeq.failover.PgFailoverMode;
import dev.mars.peegeeq.failover.PgFailoverMonitor;
import dev.mars.peegeeq.failover.PgGrantState;
import dev.mars.peegeeq.failover.PgHbaAdmissionGate;
import dev.mars.peegeeq.failover.PgLeaseProtocolException;
import dev.mars.peegeeq.failover.PgLocalStateException;
import dev.mars.peegeeq.failover.PgLocalStateStore;
import dev.mars.peegeeq.failover.PgNodeConfig;
import dev.mars.peegeeq.failover.PgPrimaryElector;
import dev.mars.peegeeq.failover.PgProcessControl;
import dev.mars.peegeeq.failover.PgProcessControlException;
import dev.mars.peegeeq.failover.PgProcessState;
import dev.mars.peegeeq.failover.PgSupervisedContainer;
import dev.mars.peegeeq.failover.PgWatchdogMode;
import dev.mars.peegeeq.failover.PgWriterGrant;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.logging.ExpectedErrorLog;
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
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Supervisor-owned PostgreSQL start and lease-loss shutdown: a real coordinator, a real
 * PostgreSQL that only the supervisor starts, and real node-local storage.
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Isolated
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class PgFailoverMonitorIntegrationTest {
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
    private PgPrimaryElector elector;
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
        config = new PgNodeConfig("monitor-tests", "inc-" + UUID.randomUUID(), "pg-node-1", MEMBERS,
            Duration.ofSeconds(20), Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofSeconds(8),
            PgWatchdogMode.OFF);
        elector = new PgPrimaryElector(config, new ConsulLeaseCoordinator(vertx, config, endpoint(), "consul-1", TOKEN));
        waitForLeader(System.nanoTime() + TimeUnit.SECONDS.toNanos(30))
            .compose(ignored -> POSTGRES.newDataDirectory(runner))
            .compose(created -> {
                dataDirectory = created;
                return POSTGRES.provisionAdmission(runner, created);
            }).compose(ignored -> {
                process = control(PgSupervisedContainer.PG_CTL);
                gate = gate(POSTGRES.openHba(dataDirectory), POSTGRES.closedHba(dataDirectory));
                return PgLocalStateStore.open(vertx, stateDirectory, config.nodeId());
            }).onSuccess(opened -> context.verify(() -> {
                store = opened;
                monitor = new PgFailoverMonitor(config, elector, store, process, gate);
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @AfterEach void tearDown(VertxTestContext context) {
        POSTGRES.forceStop(runner, dataDirectory).transform(stopped -> elector.close().compose(ignored -> {
            admin.close();
            return stopped.succeeded() ? Future.<Void>succeededFuture() : Future.failedFuture(stopped.cause());
        })).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation rejected on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    void primaryStartWithoutOwnershipIsRefusedAndStartsNothing(VertxTestContext context) {
        var unowned = new PgControlRecord(config.controlName(), 1, 1, UUID.randomUUID().toString(),
            initialIntent());
        monitor.startPrimary(unowned).transform(start -> {
            assertTrue(start.failed());
            assertInstanceOf(PgLeaseProtocolException.class, start.cause());
            return process.status();
        }).onSuccess(status -> context.verify(() -> {
            assertEquals(PgProcessState.STOPPED, status);
            context.completeNow();
        })).onFailure(context::failNow);
    }

    @Test void primaryStartUnderLeaseIsWritableWithAdmissionClosed(VertxTestContext context) {
        elector.createInitialIntent(initialIntent()).compose(held -> monitor.startPrimary(held)
            .compose(ignored -> POSTGRES.sql(runner, "select pg_is_in_recovery()"))
            .compose(recovery -> {
                assertEquals("f", recovery.output().strip());
                return store.grant();
            }).compose(grant -> {
                assertTrue(grant.filter(open -> open.state() != PgGrantState.CLOSED).isEmpty(),
                    "Starting PostgreSQL never opens admission");
                return store.receipt(held.generation(), operationId(held), "start-primary", config.nodeId());
            })).onSuccess(receipt -> context.verify(() -> {
                assertEquals(PgActionResult.COMPLETED, receipt.orElseThrow().result());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation failed on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLocalStateException.class)
    void quarantinedNodeCannotStartAPrimary(VertxTestContext context) {
        elector.createInitialIntent(initialIntent()).compose(held -> store.quarantine("awaiting rewind")
            .compose(ignored -> monitor.startPrimary(held))
            .transform(start -> {
                assertTrue(start.failed());
                assertInstanceOf(PgLocalStateException.class, start.cause());
                return process.status();
            })).onSuccess(status -> context.verify(() -> {
                assertEquals(PgProcessState.STOPPED, status);
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void standbyStartNeedsNoLeaseAndIsReadOnly(VertxTestContext context) {
        monitor.startStandby().compose(ignored -> POSTGRES.sql(runner, "select pg_is_in_recovery()"))
            .onSuccess(recovery -> context.verify(() -> {
                assertEquals("t", recovery.output().strip());
                assertFalse(elector.hasFreshOwnership());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void renewalUnderAHealthyLeaseKeepsTheWriterRunning(VertxTestContext context) {
        elector.createInitialIntent(initialIntent()).compose(held -> monitor.startPrimary(held)
            .compose(ignored -> monitor.renew())
            .compose(renewed -> {
                assertEquals(held, renewed);
                return process.status();
            })).onSuccess(status -> context.verify(() -> {
                assertEquals(PgProcessState.RUNNING, status);
                assertTrue(elector.hasFreshOwnership());
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
        message = "Demoting local PostgreSQL on node pg-node-1: Lease renewal failed",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    void leaseLossWithdrawsAdmissionStopsTheWriterAndEndsSessions(VertxTestContext context) {
        elector.createInitialIntent(initialIntent()).compose(held -> monitor.startPrimary(held)
            .compose(ignored -> elector.update(held, servingIntent(held)))
            .compose(serving -> monitor.prepareWriter(serving, PgFailoverMode.MANUAL)
                .compose(prepared -> monitor.activateWriter(serving, prepared)))
            .compose(open -> {
                Future<PgCommandResult> session = POSTGRES.applicationSql(runner, "select pg_sleep(90)");
                return waitForBackend("pg_sleep", System.nanoTime() + TimeUnit.SECONDS.toNanos(20))
                    .compose(ignored -> deleteRecordExternally())
                    .compose(ignored -> {
                        long lossObserved = System.nanoTime();
                        return monitor.renew().transform(renewal -> {
                            assertTrue(renewal.failed(), "Renewal succeeded after the record was removed");
                            assertInstanceOf(PgLeaseProtocolException.class, renewal.cause());
                            long elapsed = System.nanoTime() - lossObserved;
                            assertTrue(elapsed < config.requestTimeout().plus(config.stopTimeout()).toNanos(),
                                "Demotion exceeded the request and stop budgets: " + elapsed + " ns");
                            assertFalse(elector.hasFreshOwnership());
                            return session;
                        });
                    });
            }).compose(session -> {
                assertNotEquals(0, session.exitCode(), "The session survived demotion: " + session.output());
                return process.status();
            }).compose(status -> {
                assertEquals(PgProcessState.STOPPED, status);
                return store.grant();
            }).compose(grant -> {
                assertEquals(PgGrantState.CLOSED, grant.orElseThrow().state());
                return store.receipt(held.generation(), operationId(held), "stop-writer", config.nodeId());
            })).onSuccess(receipt -> context.verify(() -> {
                assertEquals(PgActionResult.COMPLETED, receipt.orElseThrow().result());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void aSecondEntryPointCannotStartAWriterAfterDemotion(VertxTestContext context) {
        elector.createInitialIntent(initialIntent()).compose(held -> monitor.startPrimary(held)
            .compose(ignored -> monitor.demote(held, "test demotion")))
            // Bypass the supervisor: start PostgreSQL directly.
            .compose(ignored -> POSTGRES.exec(runner, PgSupervisedContainer.PG_CTL, "start", "-D", dataDirectory,
                "-w", "-t", "20", "-l", POSTGRES.logFile(dataDirectory)))
            .compose(started -> {
                assertEquals(0, started.exitCode(), started.output());
                return POSTGRES.sql(runner, "create table second_entry(i int)");
            }).compose(rejected -> {
                assertNotEquals(0, rejected.exitCode(), "The second entry point started a writable server");
                assertTrue(rejected.output().contains("read-only"), rejected.output());
                return POSTGRES.applicationSql(runner, "select 1");
            }).onSuccess(application -> context.verify(() -> {
                assertNotEquals(0, application.exitCode(), "Admission stayed open after demotion");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation rejected on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    void primaryStartAfterDemotionIsRefused(VertxTestContext context) {
        elector.createInitialIntent(initialIntent()).compose(held -> monitor.startPrimary(held)
            .compose(ignored -> monitor.demote(held, "test demotion"))
            .compose(ignored -> monitor.startPrimary(held)))
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgLeaseProtocolException.class, failure);
                context.completeNow();
            })));
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
    void unconfirmedStopIsReportedQuarantinesTheNodeAndKeepsAdmissionClosed(VertxTestContext context) {
        elector.createInitialIntent(initialIntent()).compose(held -> monitor.startPrimary(held)
            .compose(ignored -> store.prepare(PgFailoverMode.MANUAL, held.generation(), operationId(held), 1))
            .compose(store::activate)
            .compose(open -> {
                // This supervisor's stop command cannot run. PostgreSQL keeps running.
                var broken = new PgFailoverMonitor(config, elector, store, control("/nonexistent/pg_ctl"), gate);
                long started = System.nanoTime();
                return broken.demote(held, "test demotion").transform(demotion -> {
                    assertTrue(demotion.failed(), "An unconfirmed stop was reported as success");
                    assertInstanceOf(PgProcessControlException.class, demotion.cause());
                    assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(30));
                    return store.grant();
                });
            }).compose(grant -> {
                assertEquals(PgGrantState.CLOSED, grant.orElseThrow().state());
                return store.quarantine();
            }).compose(quarantine -> {
                assertTrue(quarantine.isPresent(), "An unconfirmed stop must quarantine the node");
                return store.receipt(held.generation(), operationId(held), "stop-writer", config.nodeId());
            })).onSuccess(receipt -> context.verify(() -> {
                assertEquals(PgActionResult.UNKNOWN, receipt.orElseThrow().result());
                context.completeNow();
            })).onFailure(context::failNow);
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
    void failedPrimaryStartDemotesTheNodeAndFails(VertxTestContext context) {
        // This supervisor's process commands cannot run. PostgreSQL never starts.
        var broken = new PgFailoverMonitor(config, elector, store, control("/nonexistent/pg_ctl"), gate);
        elector.createInitialIntent(initialIntent()).compose(broken::startPrimary).transform(start -> {
            assertTrue(start.failed(), "A failed primary start was reported as success");
            assertInstanceOf(PgProcessControlException.class, start.cause());
            assertFalse(elector.hasFreshOwnership(), "A failed primary start must retire ownership");
            return process.status();
        }).onSuccess(status -> context.verify(() -> {
            assertEquals(PgProcessState.STOPPED, status);
            context.completeNow();
        })).onFailure(context::failNow);
    }

    // ---------------------------------------------------------------- local admission

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.LocalCommandRunner",
        message = "Command could not run: ",
        messageMatch = ExpectedErrorLog.MessageMatch.PREFIX,
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = IOException.class)
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgHbaAdmissionGate",
        message = "Admission command failed: cp",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgProcessControlException.class)
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation failed on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgProcessControlException.class)
    void admissionCommandThatCannotRunRefusesStandbyStart(VertxTestContext context) {
        // The command prefix names a program that does not exist on this host.
        var unreachable = new PgHbaAdmissionGate(vertx, runner, List.of(stateDirectory.resolve("absent").toString()),
            PgSupervisedContainer.PG_CTL, "psql", dataDirectory, POSTGRES.openHba(dataDirectory),
            POSTGRES.closedHba(dataDirectory), Duration.ofSeconds(20));
        new PgFailoverMonitor(config, elector, store, process, unreachable).startStandby().transform(start -> {
            assertTrue(start.failed(), "PostgreSQL started without a closed admission gate");
            assertInstanceOf(PgProcessControlException.class, start.cause());
            return process.status();
        }).onSuccess(status -> context.verify(() -> {
            assertEquals(PgProcessState.STOPPED, status);
            context.completeNow();
        })).onFailure(context::failNow);
    }

    @Test void startedPrimaryRejectsApplicationsAndKeepsTheSupervisorSocket(VertxTestContext context) {
        elector.createInitialIntent(initialIntent()).compose(held -> monitor.startPrimary(held))
            .compose(ignored -> POSTGRES.applicationSql(runner, "select 1"))
            .compose(application -> {
                assertNotEquals(0, application.exitCode(), "An application connected before activation");
                return POSTGRES.sql(runner, "select 1");
            }).onSuccess(supervisor -> context.verify(() -> {
                assertEquals("1", supervisor.output().strip());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void activationWithServingIntentAndItsPreparedGrantAdmitsApplications(VertxTestContext context) {
        elector.createInitialIntent(initialIntent()).compose(held -> monitor.startPrimary(held)
            .compose(ignored -> elector.update(held, servingIntent(held))))
            .compose(serving -> monitor.prepareWriter(serving, PgFailoverMode.MANUAL).compose(prepared -> {
                assertEquals(new PgWriterGrant(PgFailoverMode.MANUAL, serving.generation(), operationId(serving),
                    1, config.nodeId(), PgGrantState.PREPARED), prepared);
                return POSTGRES.applicationSql(runner, "select 1").compose(application -> {
                    assertNotEquals(0, application.exitCode(), "A prepared grant admitted an application");
                    return monitor.activateWriter(serving, prepared);
                });
            })).compose(open -> {
                assertEquals(PgGrantState.OPEN, open.state());
                return POSTGRES.applicationSql(runner, "create table admitted(i int)");
            }).compose(created -> {
                assertEquals(0, created.exitCode(), created.output());
                return store.grant();
            }).onSuccess(grant -> context.verify(() -> {
                assertEquals(PgGrantState.OPEN, grant.orElseThrow().state());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation rejected on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    void preparationWithoutAConfirmedPolicyIsRefused(VertxTestContext context) {
        elector.createInitialIntent(initialIntent()).compose(held -> monitor.startPrimary(held)
            .compose(ignored -> monitor.prepareWriter(held, PgFailoverMode.MANUAL)).transform(preparation -> {
                assertTrue(preparation.failed());
                assertInstanceOf(PgLeaseProtocolException.class, preparation.cause());
                return store.grant();
            })).onSuccess(grant -> context.verify(() -> {
                assertTrue(grant.filter(stored -> stored.state() != PgGrantState.CLOSED).isEmpty());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation rejected on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    void activationWithoutServingIntentIsRefusedAndAdmitsNothing(VertxTestContext context) {
        elector.createInitialIntent(initialIntent()).compose(held -> monitor.startPrimary(held)
            .compose(ignored -> elector.update(held, servingIntent(held).put("phase", "PROMOTING"))))
            .compose(promoting -> monitor.prepareWriter(promoting, PgFailoverMode.MANUAL)
                .compose(prepared -> monitor.activateWriter(promoting, prepared)))
            .transform(activation -> {
                assertTrue(activation.failed());
                assertInstanceOf(PgLeaseProtocolException.class, activation.cause());
                return POSTGRES.applicationSql(runner, "select 1");
            }).compose(application -> {
                assertNotEquals(0, application.exitCode());
                return store.grant();
            }).onSuccess(grant -> context.verify(() -> {
                assertEquals(PgGrantState.PREPARED, grant.orElseThrow().state());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation rejected on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLocalStateException.class)
    void activationOfAGrantThatDoesNotMatchTheRecordIsRefused(VertxTestContext context) {
        elector.createInitialIntent(initialIntent()).compose(held -> monitor.startPrimary(held)
            .compose(ignored -> elector.update(held, servingIntent(held))))
            .compose(serving -> monitor.prepareWriter(serving, PgFailoverMode.MANUAL).compose(prepared -> {
                var other = new PgWriterGrant(PgFailoverMode.MANUAL, serving.generation() + 1,
                    operationId(serving), 1, config.nodeId(), PgGrantState.PREPARED);
                return monitor.activateWriter(serving, other);
            })).transform(activation -> {
                assertTrue(activation.failed());
                assertInstanceOf(PgLocalStateException.class, activation.cause());
                return POSTGRES.applicationSql(runner, "select 1");
            }).onSuccess(application -> context.verify(() -> {
                assertNotEquals(0, application.exitCode());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation rejected on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    void activationAfterOwnershipIsRetiredIsRefused(VertxTestContext context) {
        elector.createInitialIntent(initialIntent()).compose(held -> monitor.startPrimary(held)
            .compose(ignored -> elector.update(held, servingIntent(held))))
            .compose(serving -> monitor.prepareWriter(serving, PgFailoverMode.MANUAL).compose(prepared -> {
                elector.retire();
                return monitor.activateWriter(serving, prepared);
            })).transform(activation -> {
                assertTrue(activation.failed(), "A delayed activation succeeded after ownership was retired");
                assertInstanceOf(PgLeaseProtocolException.class, activation.cause());
                return POSTGRES.applicationSql(runner, "select 1");
            }).onSuccess(application -> context.verify(() -> {
                assertNotEquals(0, application.exitCode());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void revocationBlocksNewConnectionsEndsExistingOnesAndKeepsPostgresRunning(VertxTestContext context) {
        elector.createInitialIntent(initialIntent()).compose(held -> monitor.startPrimary(held)
            .compose(ignored -> elector.update(held, servingIntent(held))))
            .compose(serving -> monitor.prepareWriter(serving, PgFailoverMode.MANUAL)
                .compose(prepared -> monitor.activateWriter(serving, prepared)))
            .compose(open -> {
                Future<PgCommandResult> session = POSTGRES.applicationSql(runner, "select pg_sleep(90)");
                return waitForBackend("pg_sleep", System.nanoTime() + TimeUnit.SECONDS.toNanos(20))
                    .compose(ignored -> monitor.revokeWriter("policy change"))
                    .compose(ignored -> session);
            }).compose(session -> {
                assertNotEquals(0, session.exitCode(), "The application session survived revocation");
                return POSTGRES.applicationSql(runner, "select 1");
            }).compose(application -> {
                assertNotEquals(0, application.exitCode(), "A new application connection was admitted");
                return store.grant();
            }).compose(grant -> {
                assertEquals(PgGrantState.CLOSED, grant.orElseThrow().state());
                return process.status();
            }).onSuccess(status -> context.verify(() -> {
                assertEquals(PgProcessState.RUNNING, status);
                assertTrue(elector.hasFreshOwnership(), "Revocation does not end ownership");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation failed on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgProcessControlException.class)
    void missingClosedAdmissionFileRefusesPrimaryStart(VertxTestContext context) {
        var unguarded = new PgFailoverMonitor(config, elector, store, process,
            gate(POSTGRES.openHba(dataDirectory), dataDirectory + "/absent.conf"));
        elector.createInitialIntent(initialIntent()).compose(unguarded::startPrimary).transform(start -> {
            assertTrue(start.failed(), "PostgreSQL started without a closed admission gate");
            assertInstanceOf(PgProcessControlException.class, start.cause());
            return process.status();
        }).onSuccess(status -> context.verify(() -> {
            assertEquals(PgProcessState.STOPPED, status);
            context.completeNow();
        })).onFailure(context::failNow);
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
    void failedAdmissionOpenClosesTheGrantAndAdmitsNothing(VertxTestContext context) {
        var broken = new PgFailoverMonitor(config, elector, store, process,
            gate(dataDirectory + "/absent.conf", POSTGRES.closedHba(dataDirectory)));
        elector.createInitialIntent(initialIntent()).compose(held -> broken.startPrimary(held)
            .compose(ignored -> elector.update(held, servingIntent(held))))
            .compose(serving -> broken.prepareWriter(serving, PgFailoverMode.MANUAL)
                .compose(prepared -> broken.activateWriter(serving, prepared)))
            .transform(activation -> {
                assertTrue(activation.failed());
                assertInstanceOf(PgProcessControlException.class, activation.cause());
                return store.grant();
            }).compose(grant -> {
                assertEquals(PgGrantState.CLOSED, grant.orElseThrow().state());
                return POSTGRES.applicationSql(runner, "select 1");
            }).onSuccess(application -> context.verify(() -> {
                assertNotEquals(0, application.exitCode());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    private PgAdmissionGate gate(String openFile, String closedFile) {
        return new PgHbaAdmissionGate(vertx, runner, POSTGRES.prefix(), PgSupervisedContainer.PG_CTL, "psql",
            dataDirectory, openFile, closedFile, Duration.ofSeconds(20));
    }

    /** Serving intent: the pending policy becomes the confirmed policy. */
    private static JsonObject servingIntent(PgControlRecord held) {
        JsonObject intent = held.intent();
        JsonObject policy = (JsonObject) intent.remove("pendingDurabilityPolicy");
        return intent.put("phase", "SERVING").put("durabilityPolicy", policy);
    }

    private PgProcessControl control(String pgCtl) {
        return new PgCtlProcessControl(runner, POSTGRES.prefix(), pgCtl, dataDirectory,
            POSTGRES.logFile(dataDirectory), Duration.ofSeconds(20));
    }

    private static String operationId(PgControlRecord record) {
        return record.intent().getString("operationId");
    }

    private static JsonObject initialIntent() {
        return new JsonObject().put("writerNodeId", "pg-node-1").put("phase", "WITHDRAWN")
            .put("operationId", UUID.randomUUID().toString())
            .put("pendingDurabilityPolicy", new JsonObject().put("revision", 1L)
                .put("requiredStandbyNodeIds", new JsonArray().add("pg-node-2").add("pg-node-3")));
    }

    private static URI endpoint() {
        return URI.create("http://" + CONSUL.getHost() + ":" + CONSUL.getMappedPort(8500));
    }

    private Future<Void> deleteRecordExternally() {
        return admin.deleteAbs(endpoint() + "/v1/kv/" + config.controlName()).timeout(2000).send()
            .map(response -> {
                assertEquals(200, response.statusCode());
                assertEquals("true", response.bodyAsString());
                return null;
            });
    }

    private Future<Void> waitForLeader(long deadline) {
        return admin.getAbs(endpoint() + "/v1/status/leader").timeout(1000).send().compose(response -> {
            if (response.statusCode() == 200 && response.bodyAsString().length() > 2) return Future.succeededFuture();
            if (System.nanoTime() >= deadline) return Future.failedFuture(new AssertionError("Consul has no leader"));
            return vertx.timer(100).compose(ignored -> waitForLeader(deadline));
        });
    }

    private Future<Void> waitForBackend(String text, long deadline) {
        return POSTGRES.sql(runner, "select count(*) from pg_stat_activity where query like '%" + text
                + "%' and pid <> pg_backend_pid()").compose(result -> {
            if (result.exitCode() == 0 && !"0".equals(result.output().strip())) return Future.succeededFuture();
            if (System.nanoTime() >= deadline) {
                return Future.failedFuture(new AssertionError("Backend did not appear: " + result.output()));
            }
            return vertx.timer(100).compose(ignored -> waitForBackend(text, deadline));
        });
    }
}
