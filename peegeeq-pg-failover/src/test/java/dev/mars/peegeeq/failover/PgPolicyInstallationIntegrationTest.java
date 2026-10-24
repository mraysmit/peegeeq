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
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import static dev.mars.peegeeq.failover.PgReplicationFixture.STANDBYS;
import static dev.mars.peegeeq.failover.PgReplicationFixture.WRITER;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Guarded installation of the pending durability policy by the supervisor: a real coordinator, a
 * real writer and two real physical standbys that only the test starts, and real node-local
 * storage. Application admission stays closed throughout.
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Isolated
@Timeout(value = 240, unit = TimeUnit.SECONDS)
class PgPolicyInstallationIntegrationTest {
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
    private Vertx vertx;
    private WebClient admin;
    private PgCommandRunner runner;
    private PgNodeConfig config;
    private PgPrimaryElector elector;
    private PgLocalStateStore store;
    private PgProcessControl process;
    private PgAdmissionGate gate;
    private PgWatchdog watchdog;
    private PgFailoverMonitor monitor;

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
        config = new PgNodeConfig("policy-tests", "inc-" + UUID.randomUUID(), WRITER, MEMBERS,
            Duration.ofSeconds(80), Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofSeconds(8),
            PgWatchdogMode.OFF);
        elector = new PgPrimaryElector(config, new ConsulLeaseCoordinator(vertx, config, endpoint(), "consul-1", TOKEN));
        waitForLeader(System.nanoTime() + TimeUnit.SECONDS.toNanos(30))
            .compose(ignored -> NODES.provision(runner))
            .compose(ignored -> {
                String directory = NODES.dataDirectory();
                var writer = NODES.node(WRITER);
                process = NODES.control(runner, WRITER);
                gate = new PgHbaAdmissionGate(vertx, runner, writer.prefix(), PgSupervisedContainer.PG_CTL, "psql",
                    directory, writer.openHba(directory), writer.closedHba(directory), COMMAND_TIMEOUT);
                // This host has no watchdog device. Off mode never uses it.
                watchdog = new PgDeviceWatchdog(vertx, stateDirectory.resolve("no-watchdog"),
                    stateDirectory.resolve("no-watchdog-timeout"));
                return PgLocalStateStore.open(vertx, stateDirectory, config.nodeId());
            }).onSuccess(opened -> context.verify(() -> {
                store = opened;
                monitor = monitor(durability(COMMAND_TIMEOUT));
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @AfterEach void tearDown(VertxTestContext context) {
        NODES.forceStopAll(runner).eventually(() -> elector.close()).eventually(() -> {
            admin.close();
            return Future.<Void>succeededFuture();
        }).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void installingThePendingPolicyPutsItInEffectUnderACompletedReceiptWithAdmissionClosed(
            VertxTestContext context) {
        elector.createInitialIntent(intent()).compose(held -> monitor.startPrimary(held)
            .compose(ignored -> startStandbys())
            .compose(ignored -> monitor.installPendingPolicy(held))
            .compose(ignored -> NODES.node(WRITER).sql(runner, "show synchronous_standby_names"))
            .compose(effective -> {
                assertEquals(0, effective.exitCode(), effective.output());
                assertEquals(INSTALLED, effective.output().strip());
                return store.receipt(held.generation(), operationId(held), PgFailoverMonitor.INSTALL_POLICY,
                    config.nodeId());
            }).compose(receipt -> {
                assertEquals(PgActionResult.COMPLETED, receipt.orElseThrow().result());
                assertEquals(pendingPolicy(held), receipt.orElseThrow().parameters());
                return store.grant();
            }).compose(grant -> {
                assertTrue(grant.filter(open -> open.state() != PgGrantState.CLOSED).isEmpty(),
                    "Installing a policy never opens admission");
                return NODES.node(WRITER).applicationSql(runner, "select 1");
            })).onSuccess(application -> context.verify(() -> {
                assertNotEquals(0, application.exitCode(), "Policy installation admitted an application connection");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void installationClosesAnOpenGrantAndAdmissionBeforeItChangesAnything(VertxTestContext context) {
        elector.createInitialIntent(intent()).compose(held -> monitor.startPrimary(held)
            .compose(ignored -> startStandbys())
            .compose(ignored -> store.prepare(PgFailoverMode.MANUAL, held.generation(), operationId(held), 1))
            .compose(store::activate)
            .compose(open -> gate.open())
            .compose(ignored -> NODES.node(WRITER).applicationSql(runner, "select 1"))
            .compose(admitted -> {
                assertEquals(0, admitted.exitCode(), "The precondition failed: admission was not open");
                return monitor.installPendingPolicy(held);
            }).compose(ignored -> store.grant())
            .compose(grant -> {
                assertEquals(PgGrantState.CLOSED, grant.orElseThrow().state(), "The grant stayed open");
                return NODES.node(WRITER).applicationSql(runner, "select 1");
            })).onSuccess(application -> context.verify(() -> {
                assertNotEquals(0, application.exitCode(), "Admission stayed open during the installation");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation failed on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgDurabilityException.class)
    void aRequiredStandbyThatNeverConnectsFailsTheInstallationBeforeAnyReceiptOrSetting(VertxTestContext context) {
        var bounded = monitor(durability(Duration.ofSeconds(5)));
        elector.createInitialIntent(intent()).compose(held -> bounded.startPrimary(held)
            .compose(ignored -> NODES.control(runner, STANDBYS.get(0)).startInRecovery())
            .compose(ignored -> bounded.installPendingPolicy(held).transform(installation -> {
                assertTrue(installation.failed(), "A policy was installed without a required standby");
                assertInstanceOf(PgDurabilityException.class, installation.cause());
                assertTrue(installation.cause().getMessage().startsWith("Required standbys were not covered"),
                    installation.cause().getMessage());
                return NODES.node(WRITER).sql(runner, "show synchronous_standby_names");
            })).compose(effective -> {
                assertEquals("", effective.output().strip(), "A setting was installed before coverage was confirmed");
                return store.receipt(held.generation(), operationId(held), PgFailoverMonitor.INSTALL_POLICY,
                    config.nodeId());
            })).onSuccess(receipt -> context.verify(() -> {
                assertTrue(receipt.isEmpty(), "A refused installation must not record an install effect");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation rejected on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    void installationWithoutOwnershipIsRefusedAndRecordsNothing(VertxTestContext context) {
        var unowned = new PgControlRecord(config.controlName(), 1, 1, UUID.randomUUID().toString(), intent());
        monitor.installPendingPolicy(unowned).transform(installation -> {
            assertTrue(installation.failed());
            assertInstanceOf(PgLeaseProtocolException.class, installation.cause());
            return store.receipt(1, operationId(unowned), PgFailoverMonitor.INSTALL_POLICY, config.nodeId());
        }).onSuccess(receipt -> context.verify(() -> {
            assertTrue(receipt.isEmpty());
            context.completeNow();
        })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation rejected on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    void installationWhileTheIntentIsNotWithdrawnIsRefused(VertxTestContext context) {
        elector.createInitialIntent(intent()).compose(held ->
            elector.update(held, held.intent().put("phase", "FENCING")))
            .compose(fencing -> monitor.installPendingPolicy(fencing).transform(installation -> {
                assertTrue(installation.failed(), "A policy was installed while the intent was not withdrawn");
                assertInstanceOf(PgLeaseProtocolException.class, installation.cause());
                return store.receipt(fencing.generation(), operationId(fencing), PgFailoverMonitor.INSTALL_POLICY,
                    config.nodeId());
            })).onSuccess(receipt -> context.verify(() -> {
                assertTrue(receipt.isEmpty());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation rejected on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    void installationWithoutAPendingPolicyIsRefused(VertxTestContext context) {
        elector.createInitialIntent(intent()).compose(held -> {
            JsonObject confirmed = held.intent();
            confirmed.put("durabilityPolicy", confirmed.remove("pendingDurabilityPolicy"));
            return elector.update(held, confirmed);
        }).compose(settled -> monitor.installPendingPolicy(settled).transform(installation -> {
            assertTrue(installation.failed(), "A policy was installed with no pending policy");
            assertInstanceOf(PgLeaseProtocolException.class, installation.cause());
            return store.receipt(settled.generation(), operationId(settled), PgFailoverMonitor.INSTALL_POLICY,
                config.nodeId());
        })).onSuccess(receipt -> context.verify(() -> {
            assertTrue(receipt.isEmpty());
            context.completeNow();
        })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation failed on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLocalStateException.class)
    void aQuarantinedNodeCannotInstallAPolicy(VertxTestContext context) {
        elector.createInitialIntent(intent()).compose(held -> store.quarantine("awaiting rewind")
            .compose(ignored -> monitor.installPendingPolicy(held)).transform(installation -> {
                assertTrue(installation.failed(), "A quarantined node installed a policy");
                assertInstanceOf(PgLocalStateException.class, installation.cause());
                return store.receipt(held.generation(), operationId(held), PgFailoverMonitor.INSTALL_POLICY,
                    config.nodeId());
            })).onSuccess(receipt -> context.verify(() -> {
                assertTrue(receipt.isEmpty());
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
    void aReceiptWithDifferentParametersRefusesTheInstallationAndInstallsNothing(VertxTestContext context) {
        var other = new JsonObject().put("revision", 1L).put("requiredStandbyNodeIds", new JsonArray().add("pg-node-2"));
        elector.createInitialIntent(intent()).compose(held -> monitor.startPrimary(held)
            .compose(ignored -> startStandbys())
            .compose(ignored -> store.begin(held.generation(), operationId(held), PgFailoverMonitor.INSTALL_POLICY,
                config.nodeId(), other))
            .compose(ignored -> monitor.installPendingPolicy(held)).transform(installation -> {
                assertTrue(installation.failed(), "A receipt with other parameters did not stop the installation");
                assertInstanceOf(PgLocalStateException.class, installation.cause());
                return NODES.node(WRITER).sql(runner, "show synchronous_standby_names");
            })).onSuccess(effective -> context.verify(() -> {
                assertEquals("", effective.output().strip());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation failed on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgDurabilityException.class)
    void aFailedInstallationRecordsAnUnknownReceiptAndLeavesAdmissionClosed(VertxTestContext context) {
        var writer = NODES.node(WRITER);
        String directory = NODES.dataDirectory();
        // A server-start option outranks postgresql.auto.conf, so the reloaded value differs from the installed one.
        elector.createInitialIntent(intent()).compose(held -> writer.exec(runner, PgSupervisedContainer.PG_CTL,
                "start", "-D", directory, "-w", "-l", writer.logFile(directory), "-o",
                "-c synchronous_standby_names=elsewhere")
            .compose(started -> {
                assertEquals(0, started.exitCode(), started.output());
                return startStandbys();
            }).compose(ignored -> monitor.installPendingPolicy(held)).transform(installation -> {
                assertTrue(installation.failed(), "A mismatched setting was reported as installed");
                assertInstanceOf(PgDurabilityException.class, installation.cause());
                assertTrue(installation.cause().getMessage().startsWith("The setting in effect is elsewhere"),
                    installation.cause().getMessage());
                return store.receipt(held.generation(), operationId(held), PgFailoverMonitor.INSTALL_POLICY,
                    config.nodeId());
            }).compose(receipt -> {
                assertEquals(PgActionResult.UNKNOWN, receipt.orElseThrow().result());
                return writer.applicationSql(runner, "select 1");
            })).onSuccess(application -> context.verify(() -> {
                assertNotEquals(0, application.exitCode(), "A failed installation left admission open");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgLocalStateStore",
        message = "Node-local storage failed",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = FileSystemException.class)
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation failed on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = FileSystemException.class)
    void aStorageFaultWhenTheReceiptIsBegunFailsTheInstallationBeforeAnySetting(VertxTestContext context) {
        elector.createInitialIntent(intent()).compose(held -> monitor.startPrimary(held)
            .compose(ignored -> startStandbys())
            // A directory where the store must create its temporary receipt file: the write fails.
            .compose(ignored -> vertx.fileSystem().mkdirs(receiptTemporaryFile(held).toString()))
            .compose(ignored -> monitor.installPendingPolicy(held)).transform(installation -> {
                assertTrue(installation.failed(), "The installation succeeded although its receipt could not be written");
                assertInstanceOf(PgLocalStateException.class, installation.cause());
                return NODES.node(WRITER).sql(runner, "show synchronous_standby_names");
            }).compose(effective -> {
                assertEquals("", effective.output().strip(), "A setting was installed without a receipt");
                return store.receipt(held.generation(), operationId(held), PgFailoverMonitor.INSTALL_POLICY,
                    config.nodeId());
            })).onSuccess(receipt -> context.verify(() -> {
                assertTrue(receipt.isEmpty());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgLocalStateStore",
        message = "Node-local storage failed",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = FileSystemException.class)
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation failed on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = FileSystemException.class)
    void aStorageFaultWhenTheResultIsRecordedFailsTheCallAfterTheEffectAndLeavesThePendingReceipt(
            VertxTestContext context) {
        var broken = new AtomicReference<Path>();
        var faulting = monitor(new FaultBeforeInstallDurabilityControl(durability(COMMAND_TIMEOUT),
            () -> vertx.fileSystem().mkdirs(broken.get().toString())));
        elector.createInitialIntent(intent()).compose(held -> {
            broken.set(receiptTemporaryFile(held));
            return faulting.startPrimary(held).compose(ignored -> startStandbys())
                .compose(ignored -> faulting.installPendingPolicy(held)).transform(installation -> {
                    assertTrue(installation.failed(), "The installation succeeded although its result could not be recorded");
                    assertInstanceOf(PgLocalStateException.class, installation.cause());
                    return NODES.node(WRITER).sql(runner, "show synchronous_standby_names");
                }).compose(effective -> {
                    assertEquals(INSTALLED, effective.output().strip(), "The installation took effect before the fault");
                    return store.receipt(held.generation(), operationId(held), PgFailoverMonitor.INSTALL_POLICY,
                        config.nodeId());
                }).compose(receipt -> {
                    assertEquals(PgActionResult.PENDING, receipt.orElseThrow().result(),
                        "The result was not recorded, so the receipt still needs local observation");
                    return NODES.node(WRITER).applicationSql(runner, "select 1");
                });
        }).onSuccess(application -> context.verify(() -> {
            assertNotEquals(0, application.exitCode(), "A storage fault left admission open");
            context.completeNow();
        })).onFailure(context::failNow);
    }

    @Test void aRerunAfterAnUnknownReceiptCompletesTheSameReceipt(VertxTestContext context) {
        elector.createInitialIntent(intent()).compose(held -> monitor.startPrimary(held)
            .compose(ignored -> startStandbys())
            .compose(ignored -> store.begin(held.generation(), operationId(held), PgFailoverMonitor.INSTALL_POLICY,
                config.nodeId(), pendingPolicy(held)))
            .compose(begun -> store.record(held.generation(), operationId(held), PgFailoverMonitor.INSTALL_POLICY,
                config.nodeId(), PgActionResult.UNKNOWN, new JsonObject()))
            .compose(ignored -> monitor.installPendingPolicy(held))
            .compose(ignored -> store.receipt(held.generation(), operationId(held), PgFailoverMonitor.INSTALL_POLICY,
                config.nodeId())))
            .onSuccess(receipt -> context.verify(() -> {
                assertEquals(PgActionResult.COMPLETED, receipt.orElseThrow().result());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void installingTwiceIsIdempotent(VertxTestContext context) {
        elector.createInitialIntent(intent()).compose(held -> monitor.startPrimary(held)
            .compose(ignored -> startStandbys())
            .compose(ignored -> monitor.installPendingPolicy(held))
            .compose(ignored -> monitor.installPendingPolicy(held))
            .compose(ignored -> NODES.node(WRITER).sql(runner, "show synchronous_standby_names"))
            .compose(effective -> {
                assertEquals(INSTALLED, effective.output().strip());
                return store.receipt(held.generation(), operationId(held), PgFailoverMonitor.INSTALL_POLICY,
                    config.nodeId());
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
        throwableType = PgLeaseProtocolException.class)
    void ownershipLostWhileThePolicyIsInstalledFailsTheCallAndKeepsAdmissionClosed(VertxTestContext context) {
        var losing = monitor(new OwnershipLosingDurabilityControl(durability(COMMAND_TIMEOUT), elector));
        elector.createInitialIntent(intent()).compose(held -> losing.startPrimary(held)
            .compose(ignored -> startStandbys())
            .compose(ignored -> losing.installPendingPolicy(held)).transform(installation -> {
                assertTrue(installation.failed(), "An installation succeeded after ownership was lost");
                assertInstanceOf(PgLeaseProtocolException.class, installation.cause());
                return store.receipt(held.generation(), operationId(held), PgFailoverMonitor.INSTALL_POLICY,
                    config.nodeId());
            }).compose(receipt -> {
                assertEquals(PgActionResult.COMPLETED, receipt.orElseThrow().result(),
                    "The installation took effect and is recorded as such");
                return NODES.node(WRITER).applicationSql(runner, "select 1");
            })).onSuccess(application -> context.verify(() -> {
                assertNotEquals(0, application.exitCode());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    /** Wraps the real durability control. The installation takes effect, then ownership is lost. */
    private record OwnershipLosingDurabilityControl(PgDurabilityControl real, PgPrimaryElector elector)
            implements PgDurabilityControl {
        @Override public Future<PgLsn> flushBoundary() { return real.flushBoundary(); }

        @Override public Future<List<PgReplicationPeer>> peers() { return real.peers(); }

        @Override public Future<Void> confirmCovered(PgDurabilityPolicy policy) { return real.confirmCovered(policy); }

        @Override public Future<Void> install(PgDurabilityPolicy policy) {
            return real.install(policy).map(installed -> {
                elector.retire();
                return installed;
            });
        }
    }

    /** Wraps the real durability control. A fault is introduced just before the installation runs. */
    private record FaultBeforeInstallDurabilityControl(PgDurabilityControl real, Supplier<Future<Void>> fault)
            implements PgDurabilityControl {
        @Override public Future<PgLsn> flushBoundary() { return real.flushBoundary(); }

        @Override public Future<List<PgReplicationPeer>> peers() { return real.peers(); }

        @Override public Future<Void> confirmCovered(PgDurabilityPolicy policy) { return real.confirmCovered(policy); }

        @Override public Future<Void> install(PgDurabilityPolicy policy) {
            return fault.get().compose(ignored -> real.install(policy));
        }
    }

    /** The temporary file the store writes before it atomically replaces the installation receipt. */
    private Path receiptTemporaryFile(PgControlRecord held) {
        return stateDirectory.resolve("receipts").resolve(held.generation() + "+" + operationId(held) + "+"
            + PgFailoverMonitor.INSTALL_POLICY + "+" + config.nodeId() + ".json.tmp");
    }

    private Future<Void> startStandbys() {
        Future<Void> started = Future.succeededFuture();
        for (String standby : STANDBYS) {
            started = started.compose(ignored -> NODES.control(runner, standby).startInRecovery());
        }
        return started;
    }

    private PgFailoverMonitor monitor(PgDurabilityControl durability) {
        return new PgFailoverMonitor(config, elector, store, process, gate, watchdog, durability);
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

    private static JsonObject pendingPolicy(PgControlRecord held) {
        return held.intent().getJsonObject("pendingDurabilityPolicy");
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
