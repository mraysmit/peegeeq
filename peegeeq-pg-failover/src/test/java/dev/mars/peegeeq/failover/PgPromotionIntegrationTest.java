package dev.mars.peegeeq.failover;

import dev.mars.peegeeq.failover.consul.ConsulLeaseCoordinator;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.logging.ExpectedErrorLog;
import io.vertx.core.AsyncResult;
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
import java.nio.file.NoSuchFileException;
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
 * Guarded local promotion of the takeover target by the supervisor: a real coordinator quorum member,
 * a real released lease, a real standby that was streaming from the former writer, and real
 * node-local storage. Application admission stays closed throughout.
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Isolated
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class PgPromotionIntegrationTest {
    private static final List<String> MEMBERS = List.of("pg-node-1", "pg-node-2", "pg-node-3");
    private static final String TARGET = "pg-node-2";
    private static final String TOKEN = "unused-without-acls";
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
    private PgNodeConfig historyConfig;
    private PgNodeConfig config;
    private PgPrimaryElector historyElector;
    private PgPrimaryElector elector;
    private PgPrimaryElector modeElector;
    private PgLocalStateStore store;
    private PgProcessControl process;
    private PgAdmissionGate gate;
    private PgWatchdog watchdog;
    private PgDurabilityControl durability;
    private PgPromotionControl promotion;
    private PgFailoverMonitor monitor;

    @BeforeAll static void startContainers() {
        CONSUL.start();
        NODES.start();
    }

    @AfterAll static void stopContainers() {
        NODES.close();
        CONSUL.stop();
    }

    // Provisioning three nodes and covering both standbys can approach the 30-second Vert.x context
    // default. The JUnit @Timeout on the class does not set it: the extension reads io.vertx.junit5.Timeout.
    @io.vertx.junit5.Timeout(value = 150, timeUnit = TimeUnit.SECONDS)
    @BeforeEach void setUp(Vertx vertx, VertxTestContext context) {
        this.vertx = vertx;
        admin = WebClient.create(vertx);
        runner = new LocalCommandRunner(vertx);
        String incarnation = "inc-" + UUID.randomUUID();
        historyConfig = nodeConfig("pg-node-1", incarnation, PgWatchdogMode.OFF);
        config = nodeConfig(TARGET, incarnation, PgWatchdogMode.OFF);
        historyElector = new PgPrimaryElector(historyConfig,
            new ConsulLeaseCoordinator(vertx, historyConfig, endpoint(), "consul-1", TOKEN));
        elector = new PgPrimaryElector(config, new ConsulLeaseCoordinator(vertx, config, endpoint(), "consul-1", TOKEN));
        waitForLeader(System.nanoTime() + TimeUnit.SECONDS.toNanos(30))
            .compose(ignored -> NODES.provision(runner))
            .compose(ignored -> NODES.control(runner, WRITER).startPrimary())
            .compose(ignored -> NODES.control(runner, STANDBYS.get(0)).startInRecovery())
            .compose(ignored -> NODES.control(runner, STANDBYS.get(1)).startInRecovery())
            .compose(ignored -> NODES.node(WRITER).sql(runner,
                "create table before_failover (id int); insert into before_failover values (1)"))
            .compose(written -> {
                assertEquals(0, written.exitCode(), written.output());
                return writerDurability().confirmCovered(new PgDurabilityPolicy(1, STANDBYS));
            }).compose(ignored -> {
                var target = NODES.node(TARGET);
                String directory = NODES.dataDirectory();
                process = NODES.control(runner, TARGET);
                gate = new PgHbaAdmissionGate(vertx, runner, target.prefix(), PgSupervisedContainer.PG_CTL, "psql",
                    directory, target.openHba(directory), target.closedHba(directory), COMMAND_TIMEOUT);
                durability = new PgSqlDurabilityControl(vertx, runner, target.prefix(), PgSupervisedContainer.PG_CTL,
                    "psql", directory, COMMAND_TIMEOUT);
                promotion = new PgSqlPromotionControl(vertx, runner, target.prefix(), PgSupervisedContainer.PG_CTL,
                    "psql", directory, COMMAND_TIMEOUT);
                // This host has no watchdog device. Neither path exists.
                watchdog = new PgDeviceWatchdog(vertx, stateDirectory.resolve("no-watchdog"),
                    stateDirectory.resolve("no-watchdog-timeout"));
                return PgLocalStateStore.open(vertx, stateDirectory, config.nodeId());
            }).onSuccess(opened -> context.verify(() -> {
                store = opened;
                monitor = monitor(promotion);
                context.completeNow();
            })).onFailure(context::failNow);
    }

    // Every step runs, and every failure fails the test. Future.eventually discards a cleanup failure.
    @AfterEach void tearDown(VertxTestContext context) {
        List<Throwable> failures = new ArrayList<>();
        NODES.forceStopAll(runner).transform(stopped -> settle(stopped, failures))
            .compose(ignored -> historyElector.close().transform(closed -> settle(closed, failures)))
            .compose(ignored -> elector.close().transform(closed -> settle(closed, failures)))
            .compose(ignored -> modeElector == null ? Future.<Void>succeededFuture()
                : modeElector.close().transform(closed -> settle(closed, failures)))
            .onSuccess(ignored -> {
                admin.close();
                if (failures.isEmpty()) {
                    context.completeNow();
                    return;
                }
                Throwable first = failures.get(0);
                failures.stream().skip(1).forEach(first::addSuppressed);
                context.failNow(first);
            }).onFailure(context::failNow);
    }

    private static Future<Void> settle(AsyncResult<?> step, List<Throwable> failures) {
        if (step.failed()) failures.add(step.cause());
        return Future.succeededFuture();
    }

    @Test void promotingTheTakeoverTargetMakesItWritableWithItsDataAndAdmissionClosed(VertxTestContext context) {
        var target = NODES.node(TARGET);
        takeover(elector, STANDBYS).compose(held -> monitor.promoteStandby(held)
            .compose(ignored -> target.sql(runner, "select pg_is_in_recovery()"))
            .compose(recovery -> {
                assertEquals("f", recovery.output().strip(), "The target is still in recovery");
                return target.sql(runner, "select count(*) from before_failover");
            }).compose(rows -> {
                assertEquals("1", rows.output().strip(), "The acknowledged row is missing on the promoted node");
                return target.sql(runner, "select timeline_id from pg_control_checkpoint()");
            }).compose(timeline -> {
                assertEquals("2", timeline.output().strip(), "No checkpoint follows the promotion");
                return store.receipt(held.generation(), operationId(held), PgFailoverMonitor.PROMOTE, config.nodeId());
            }).compose(promote -> {
                assertEquals(PgActionResult.COMPLETED, promote.orElseThrow().result());
                assertEquals(new JsonObject(), promote.orElseThrow().parameters());
                return store.receipt(held.generation(), operationId(held), PgFailoverMonitor.CHECKPOINT_AFTER_PROMOTE,
                    config.nodeId());
            }).compose(checkpoint -> {
                assertEquals(PgActionResult.COMPLETED, checkpoint.orElseThrow().result());
                return store.grant();
            }).compose(grant -> {
                assertTrue(grant.filter(open -> open.state() != PgGrantState.CLOSED).isEmpty(),
                    "Promotion never opens admission");
                return target.applicationSql(runner, "select 1");
            })).onSuccess(application -> context.verify(() -> {
                assertNotEquals(0, application.exitCode(), "Promotion admitted an application connection");
                assertTrue(elector.hasFreshOwnership(), "Promotion does not end ownership");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void promotionClosesAnOpenGrantAndAdmissionBeforeItChangesAnything(VertxTestContext context) {
        var target = NODES.node(TARGET);
        takeover(elector, STANDBYS).compose(held ->
            store.prepare(PgFailoverMode.MANUAL, held.generation(), operationId(held), 1)
                .compose(store::activate)
                .compose(open -> gate.open())
                .compose(ignored -> target.applicationSql(runner, "select 1"))
                .compose(admitted -> {
                    assertEquals(0, admitted.exitCode(), "The precondition failed: admission was not open");
                    return monitor.promoteStandby(held);
                }).compose(ignored -> store.grant())
                .compose(grant -> {
                    assertEquals(PgGrantState.CLOSED, grant.orElseThrow().state(), "The grant stayed open");
                    return target.applicationSql(runner, "select 1");
                })).onSuccess(application -> context.verify(() -> {
                    assertNotEquals(0, application.exitCode(), "Admission stayed open during the promotion");
                    context.completeNow();
                })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation rejected on node pg-node-2",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    void promotionWithoutOwnershipIsRefusedAndNothingChanges(VertxTestContext context) {
        var unowned = new PgControlRecord(config.controlName(), 2, 2, UUID.randomUUID().toString(), takeoverIntent(STANDBYS));
        monitor.promoteStandby(unowned).transform(refusal -> {
            assertTrue(refusal.failed(), "A standby was promoted without ownership");
            assertInstanceOf(PgLeaseProtocolException.class, refusal.cause());
            return assertStillInRecoveryWithNoReceipt(unowned);
        }).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation rejected on node pg-node-2",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    void promotionWhileTheIntentIsNotWithdrawnIsRefused(VertxTestContext context) {
        takeover(elector, STANDBYS).compose(held -> elector.update(held, held.intent().put("phase", "FENCING")))
            .compose(fencing -> monitor.promoteStandby(fencing).transform(refusal -> {
                assertTrue(refusal.failed(), "A standby was promoted while the intent was not withdrawn");
                assertInstanceOf(PgLeaseProtocolException.class, refusal.cause());
                return assertStillInRecoveryWithNoReceipt(fencing);
            })).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation rejected on node pg-node-2",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    void aRecordThatIsNotATakeoverIsRefused(VertxTestContext context) {
        // First-start intent for this node: no previous writer and no confirmed policy.
        elector.createInitialIntent(new JsonObject().put("writerNodeId", TARGET).put("phase", "WITHDRAWN")
                .put("operationId", UUID.randomUUID().toString())
                .put("pendingDurabilityPolicy", new PgDurabilityPolicy(1, List.of("pg-node-1", "pg-node-3")).toJson()))
            .compose(held -> monitor.promoteStandby(held).transform(refusal -> {
                assertTrue(refusal.failed(), "A standby was promoted from a record that is not a takeover");
                assertInstanceOf(PgLeaseProtocolException.class, refusal.cause());
                return assertStillInRecoveryWithNoReceipt(held);
            })).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation rejected on node pg-node-2",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    void aTargetThatTheConfirmedPolicyDoesNotNameIsRefused(VertxTestContext context) {
        // The confirmed policy requires only pg-node-3, so pg-node-2 holds no acknowledged commit by right.
        takeover(elector, List.of("pg-node-3")).compose(held -> monitor.promoteStandby(held).transform(refusal -> {
            assertTrue(refusal.failed(), "A standby that the confirmed policy does not name was promoted");
            assertInstanceOf(PgLeaseProtocolException.class, refusal.cause());
            return assertStillInRecoveryWithNoReceipt(held);
        })).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation failed on node pg-node-2",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLocalStateException.class)
    void aQuarantinedNodeCannotBePromoted(VertxTestContext context) {
        takeover(elector, STANDBYS).compose(held -> store.quarantine("awaiting rewind")
            .compose(ignored -> monitor.promoteStandby(held)).transform(refusal -> {
                assertTrue(refusal.failed(), "A quarantined node was promoted");
                assertInstanceOf(PgLocalStateException.class, refusal.cause());
                return assertStillInRecoveryWithNoReceipt(held);
            })).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation failed on node pg-node-2",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgPromotionException.class)
    void aWritableNodeWithNoPromotionReceiptOfThisOperationIsRefused(VertxTestContext context) {
        takeover(elector, STANDBYS).compose(held -> promotion.promote()
            .compose(ignored -> monitor.promoteStandby(held)).transform(result -> {
                assertTrue(result.failed(), "A writable node with no promotion of this operation was accepted");
                assertInstanceOf(PgPromotionException.class, result.cause());
                return store.receipt(held.generation(), operationId(held), PgFailoverMonitor.PROMOTE, config.nodeId());
            })).onSuccess(receipt -> context.verify(() -> {
                assertTrue(receipt.isEmpty(), "A refused promotion must not record a promote effect");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void aRerunAfterALostPromotionReplyCompletesBothReceiptsWithoutPromotingAgain(VertxTestContext context) {
        takeover(elector, STANDBYS).compose(held ->
            store.begin(held.generation(), operationId(held), PgFailoverMonitor.PROMOTE, config.nodeId(), new JsonObject())
                .compose(begun -> promotion.promote())
                .compose(ignored -> monitor.promoteStandby(held))
                .compose(ignored -> store.receipt(held.generation(), operationId(held), PgFailoverMonitor.PROMOTE,
                    config.nodeId()))
                .compose(promote -> {
                    assertEquals(PgActionResult.COMPLETED, promote.orElseThrow().result());
                    return store.receipt(held.generation(), operationId(held),
                        PgFailoverMonitor.CHECKPOINT_AFTER_PROMOTE, config.nodeId());
                })).onSuccess(checkpoint -> context.verify(() -> {
                    assertEquals(PgActionResult.COMPLETED, checkpoint.orElseThrow().result());
                    context.completeNow();
                })).onFailure(context::failNow);
    }

    @Test void promotingTwiceIsIdempotentAndTakesNoSecondCheckpoint(VertxTestContext context) {
        var target = NODES.node(TARGET);
        takeover(elector, STANDBYS).compose(held -> monitor.promoteStandby(held)
            .compose(ignored -> target.sql(runner, "select checkpoint_lsn from pg_control_checkpoint()"))
            .compose(first -> monitor.promoteStandby(held)
                .compose(ignored -> target.sql(runner, "select checkpoint_lsn from pg_control_checkpoint()"))
                .map(second -> {
                    assertEquals(first.output().strip(), second.output().strip(),
                        "A completed checkpoint receipt was run again");
                    return null;
                })).compose(ignored -> store.receipt(held.generation(), operationId(held),
                    PgFailoverMonitor.PROMOTE, config.nodeId())))
            .onSuccess(receipt -> context.verify(() -> {
                assertEquals(PgActionResult.COMPLETED, receipt.orElseThrow().result());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation failed on node pg-node-2",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgPromotionException.class)
    void aFailedPromotionRecordsAnUnknownReceiptAndLeavesTheNodeInRecoveryWithAdmissionClosed(
            VertxTestContext context) {
        var target = NODES.node(TARGET);
        var failing = monitor(new FailingPromotionControl(promotion));
        takeover(elector, STANDBYS).compose(held -> failing.promoteStandby(held).transform(result -> {
            assertTrue(result.failed(), "A failed promotion was reported as success");
            assertInstanceOf(PgPromotionException.class, result.cause());
            return store.receipt(held.generation(), operationId(held), PgFailoverMonitor.PROMOTE, config.nodeId());
        }).compose(receipt -> {
            assertEquals(PgActionResult.UNKNOWN, receipt.orElseThrow().result());
            return target.sql(runner, "select pg_is_in_recovery()");
        }).compose(recovery -> {
            assertEquals("t", recovery.output().strip());
            return target.applicationSql(runner, "select 1");
        })).onSuccess(application -> context.verify(() -> {
            assertNotEquals(0, application.exitCode(), "A failed promotion left admission open");
            context.completeNow();
        })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation failed on node pg-node-2",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgPromotionException.class)
    void aFailedCheckpointFailsTheCallAfterPromotionAndLeavesTheNodeWritableWithAdmissionClosed(
            VertxTestContext context) {
        var target = NODES.node(TARGET);
        var failing = monitor(new FailingCheckpointControl(promotion));
        takeover(elector, STANDBYS).compose(held -> failing.promoteStandby(held).transform(result -> {
            assertTrue(result.failed(), "A failed checkpoint was reported as success");
            assertInstanceOf(PgPromotionException.class, result.cause());
            return store.receipt(held.generation(), operationId(held), PgFailoverMonitor.PROMOTE, config.nodeId());
        }).compose(promote -> {
            assertEquals(PgActionResult.COMPLETED, promote.orElseThrow().result());
            return store.receipt(held.generation(), operationId(held), PgFailoverMonitor.CHECKPOINT_AFTER_PROMOTE,
                config.nodeId());
        }).compose(checkpoint -> {
            assertEquals(PgActionResult.UNKNOWN, checkpoint.orElseThrow().result());
            return target.sql(runner, "select pg_is_in_recovery()");
        }).compose(recovery -> {
            assertEquals("f", recovery.output().strip());
            return target.applicationSql(runner, "select 1");
        })).onSuccess(application -> context.verify(() -> {
            assertNotEquals(0, application.exitCode(), "A failed checkpoint left admission open");
            context.completeNow();
        })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Demoting local PostgreSQL on node pg-node-2: Promotion did not complete under ownership",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation failed on node pg-node-2",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    void ownershipLostWhilePromotingStopsTheNodeAndFailsTheCallWithoutACheckpoint(VertxTestContext context) {
        var losing = monitor(new OwnershipLosingPromotionControl(promotion, elector));
        takeover(elector, STANDBYS).compose(held -> losing.promoteStandby(held).transform(result -> {
            assertTrue(result.failed(), "A promotion succeeded after ownership was lost");
            assertInstanceOf(PgLeaseProtocolException.class, result.cause());
            return process.status();
        }).compose(status -> {
            assertEquals(PgProcessState.STOPPED, status, "A writable node outlived its ownership");
            return store.receipt(held.generation(), operationId(held), PgFailoverMonitor.PROMOTE, config.nodeId());
        }).compose(promote -> {
            assertEquals(PgActionResult.COMPLETED, promote.orElseThrow().result(),
                "The promotion took effect and is recorded as such");
            return store.receipt(held.generation(), operationId(held), PgFailoverMonitor.CHECKPOINT_AFTER_PROMOTE,
                config.nodeId());
        }).compose(checkpoint -> {
            assertTrue(checkpoint.isEmpty(), "A checkpoint ran although ownership was lost");
            return store.receipt(held.generation(), operationId(held), PgFailoverMonitor.STOP_WRITER, config.nodeId());
        })).onSuccess(stop -> context.verify(() -> {
            assertEquals(PgActionResult.COMPLETED, stop.orElseThrow().result());
            assertFalse(elector.hasFreshOwnership());
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
        message = "Local supervision operation failed on node pg-node-2",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = FileSystemException.class)
    void aStorageFaultWhenTheReceiptIsBegunFailsThePromotionBeforeAnyEffect(VertxTestContext context) {
        takeover(elector, STANDBYS)
            // A directory where the store must create its temporary receipt file: the write fails.
            .compose(held -> vertx.fileSystem().mkdirs(receiptTemporaryFile(held, PgFailoverMonitor.PROMOTE).toString())
                .compose(ignored -> monitor.promoteStandby(held)).transform(result -> {
                    assertTrue(result.failed(), "The promotion succeeded although its receipt could not be written");
                    assertInstanceOf(PgLocalStateException.class, result.cause());
                    return NODES.node(TARGET).sql(runner, "select pg_is_in_recovery()");
                })).onSuccess(recovery -> context.verify(() -> {
                    assertEquals("t", recovery.output().strip(), "The node was promoted without a receipt");
                    context.completeNow();
                })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgDeviceWatchdog",
        message = "Watchdog operation failed: activate",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = NoSuchFileException.class)
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation failed on node pg-node-2",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgWatchdogException.class)
    void requiredWatchdogModeWithoutADeviceRefusesThePromotion(VertxTestContext context) {
        var requiredConfig = nodeConfig(TARGET, config.incarnation(), PgWatchdogMode.REQUIRED);
        modeElector = new PgPrimaryElector(requiredConfig,
            new ConsulLeaseCoordinator(vertx, requiredConfig, endpoint(), "consul-1", TOKEN));
        var required = new PgFailoverMonitor(requiredConfig, modeElector, store, process, gate, watchdog, durability,
            promotion);
        takeover(modeElector, STANDBYS).compose(held -> required.promoteStandby(held).transform(result -> {
            assertTrue(result.failed(), "A standby was promoted in required mode without a watchdog");
            assertInstanceOf(PgWatchdogException.class, result.cause());
            return store.receipt(held.generation(), operationId(held), PgFailoverMonitor.PROMOTE, config.nodeId());
        })).onSuccess(receipt -> context.verify(() -> {
            assertTrue(receipt.isEmpty(), "A refused promotion must not record a promote effect");
            context.completeNow();
        })).onFailure(context::failNow);
    }

    // ---------------------------------------------------------------- fault wrappers over the real control

    /** Wraps the real promotion control. The promotion fails and has no effect. */
    private record FailingPromotionControl(PgPromotionControl real) implements PgPromotionControl {
        @Override public Future<Boolean> inRecovery() { return real.inRecovery(); }

        @Override public Future<Void> promote() {
            return Future.failedFuture(new PgPromotionException("The promotion failed (injected)"));
        }

        @Override public Future<Void> checkpoint() { return real.checkpoint(); }

        @Override public Future<Void> follow(String nodeId, PgWriterEndpoint writer) { return real.follow(nodeId, writer); }
    }

    /** Wraps the real promotion control. The promotion takes effect and the checkpoint then fails. */
    private record FailingCheckpointControl(PgPromotionControl real) implements PgPromotionControl {
        @Override public Future<Boolean> inRecovery() { return real.inRecovery(); }

        @Override public Future<Void> promote() { return real.promote(); }

        @Override public Future<Void> checkpoint() {
            return Future.failedFuture(new PgPromotionException("The checkpoint failed (injected)"));
        }

        @Override public Future<Void> follow(String nodeId, PgWriterEndpoint writer) { return real.follow(nodeId, writer); }
    }

    /** Wraps the real promotion control. The promotion takes effect, then ownership is lost. */
    private record OwnershipLosingPromotionControl(PgPromotionControl real, PgPrimaryElector elector)
            implements PgPromotionControl {
        @Override public Future<Boolean> inRecovery() { return real.inRecovery(); }

        @Override public Future<Void> promote() {
            return real.promote().map(promoted -> {
                elector.retire();
                return promoted;
            });
        }

        @Override public Future<Void> checkpoint() { return real.checkpoint(); }

        @Override public Future<Void> follow(String nodeId, PgWriterEndpoint writer) { return real.follow(nodeId, writer); }
    }

    // ---------------------------------------------------------------- helpers

    private Future<Void> assertStillInRecoveryWithNoReceipt(PgControlRecord held) {
        return NODES.node(TARGET).sql(runner, "select pg_is_in_recovery()").compose(recovery -> {
            assertEquals("t", recovery.output().strip(), "A refused promotion changed the role");
            return store.receipt(held.generation(), operationId(held), PgFailoverMonitor.PROMOTE, config.nodeId());
        }).map(receipt -> {
            assertTrue(receipt.isEmpty(), "A refused promotion must not record a promote effect");
            return null;
        });
    }

    /**
     * The former writer holds the lease and confirms revision 1 of the policy for {@code policyStandbys},
     * then releases it. The target's elector takes the released lease with the takeover intent, and the
     * former writer is stopped, as it is when its host is lost.
     */
    private Future<PgControlRecord> takeover(PgPrimaryElector targetElector, List<String> policyStandbys) {
        JsonObject first = new JsonObject().put("writerNodeId", "pg-node-1").put("phase", "WITHDRAWN")
            .put("operationId", UUID.randomUUID().toString())
            .put("pendingDurabilityPolicy", new PgDurabilityPolicy(1, policyStandbys).toJson());
        return historyElector.createInitialIntent(first).compose(held -> {
            JsonObject confirmed = held.intent();
            confirmed.put("durabilityPolicy", confirmed.remove("pendingDurabilityPolicy"));
            return historyElector.update(held, confirmed);
        }).compose(historyElector::release)
            .compose(released -> NODES.control(runner, WRITER).stop(Duration.ofSeconds(8))
                .compose(stopped -> targetElector.acquireAfterRelease(released, takeoverIntent(policyStandbys))));
    }

    private static JsonObject takeoverIntent(List<String> policyStandbys) {
        return new JsonObject().put("writerNodeId", TARGET).put("phase", "WITHDRAWN")
            .put("operationId", UUID.randomUUID().toString()).put("previousWriterNodeId", "pg-node-1")
            .put("durabilityPolicy", new JsonObject().put("revision", 1L)
                .put("requiredStandbyNodeIds", new JsonArray(new ArrayList<>(policyStandbys))));
    }

    private PgFailoverMonitor monitor(PgPromotionControl promotionControl) {
        return new PgFailoverMonitor(config, elector, store, process, gate, watchdog, durability, promotionControl);
    }

    private PgDurabilityControl writerDurability() {
        return new PgSqlDurabilityControl(vertx, runner, NODES.node(WRITER).prefix(), PgSupervisedContainer.PG_CTL,
            "psql", NODES.dataDirectory(), COMMAND_TIMEOUT);
    }

    /** The ownership budget (TTL minus stop) outlasts one test, so no renewal loop is needed. */
    private static PgNodeConfig nodeConfig(String nodeId, String incarnation, PgWatchdogMode mode) {
        return new PgNodeConfig("promotion-tests", incarnation, nodeId, MEMBERS, Duration.ofSeconds(80),
            Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofSeconds(8), mode);
    }

    /** The temporary file the store writes before it atomically replaces a receipt. */
    private Path receiptTemporaryFile(PgControlRecord held, String action) {
        return stateDirectory.resolve("receipts").resolve(held.generation() + "+" + operationId(held) + "+"
            + action + "+" + config.nodeId() + ".json.tmp");
    }

    private static String operationId(PgControlRecord record) {
        return record.intent().getString("operationId");
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
