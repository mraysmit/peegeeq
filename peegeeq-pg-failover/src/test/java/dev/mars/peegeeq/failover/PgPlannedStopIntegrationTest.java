package dev.mars.peegeeq.failover;

import dev.mars.peegeeq.failover.consul.ConsulLeaseCoordinator;
import dev.mars.peegeeq.failover.consul.ConsulMemberPositions;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.logging.ExpectedErrorLog;
import io.vertx.core.AsyncResult;
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
 * Planned stop with release (plan §9.30, OD-15): a real writer under its own supervisor stops its PostgreSQL, the stop
 * is confirmed, and only then is the lease released, so a real successor acquires before the lease could expire.
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Isolated
@Timeout(value = 300, unit = TimeUnit.SECONDS)
@io.vertx.junit5.Timeout(value = 300, timeUnit = TimeUnit.SECONDS)
class PgPlannedStopIntegrationTest {
    private static final List<String> MEMBERS = List.of("pg-node-1", "pg-node-2", "pg-node-3");
    private static final String NEW_WRITER = "pg-node-2";
    private static final String TOKEN = "unused-without-acls";
    private static final Duration LEASE_TTL = Duration.ofSeconds(10);
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(20);
    private static final GenericContainer<?> CONSUL = new GenericContainer<>("hashicorp/consul:1.22.1")
        .withExposedPorts(8500)
        .withCommand("agent", "-dev", "-client=0.0.0.0", "-node=consul-1", "-log-level=warn")
        .waitingFor(Wait.forHttp("/v1/status/leader").forPort(8500).withStartupTimeout(Duration.ofSeconds(60)));
    private static final PgReplicationFixture NODES = new PgReplicationFixture();
    @TempDir Path stateDirectory;
    private final List<PgPrimaryElector> observers = new ArrayList<>();
    private final List<PgMemberPositions> positionAdapters = new ArrayList<>();
    private final List<PgRenewalLoop> loops = new ArrayList<>();
    private Vertx vertx;
    private WebClient admin;
    private PgCommandRunner runner;
    private PgNodeConfig writerConfig;
    private PgNodeConfig successorConfig;
    private ObservedLeaseCoordinator writerCoordinator;
    private PgPrimaryElector writerElector;
    private PgPrimaryElector successorElector;
    private PgLocalStateStore writerStore;
    private PgLocalStateStore successorStore;
    private PgFailoverMonitor writerMonitor;
    private PgFailoverMonitor successorMonitor;

    @BeforeAll static void startContainers() {
        CONSUL.start();
        NODES.start();
    }

    @AfterAll static void stopContainers() {
        NODES.close();
        CONSUL.stop();
    }

    @BeforeEach void setUp(Vertx vertx, VertxTestContext context) {
        this.vertx = vertx;
        admin = WebClient.create(vertx);
        runner = new LocalCommandRunner(vertx);
        String incarnation = "inc-" + UUID.randomUUID();
        writerConfig = nodeConfig(WRITER, incarnation);
        successorConfig = nodeConfig(NEW_WRITER, incarnation);
        // The writer's coordinator is observed, so a test can check or fail the release.
        writerCoordinator = new ObservedLeaseCoordinator(
            new ConsulLeaseCoordinator(vertx, writerConfig, endpoint(), "consul-1", TOKEN));
        writerElector = new PgPrimaryElector(writerConfig, writerCoordinator);
        successorElector = new PgPrimaryElector(successorConfig,
            new ConsulLeaseCoordinator(vertx, successorConfig, endpoint(), "consul-1", TOKEN));
        waitForLeader(System.nanoTime() + TimeUnit.SECONDS.toNanos(30))
            .compose(ignored -> NODES.provision(runner))
            .compose(ignored -> NODES.control(runner, WRITER).startPrimary())
            .compose(ignored -> NODES.control(runner, NEW_WRITER).startInRecovery())
            .compose(ignored -> NODES.control(runner, STANDBYS.get(1)).startInRecovery())
            .compose(ignored -> durabilityOf(WRITER).confirmCovered(new PgDurabilityPolicy(1, STANDBYS)))
            .compose(ignored -> memberStore(WRITER))
            .compose(opened -> {
                writerStore = opened;
                return memberStore(NEW_WRITER);
            }).onSuccess(opened -> context.verify(() -> {
                successorStore = opened;
                writerMonitor = monitorOf(writerConfig, writerElector, writerStore, WRITER, NODES.control(runner, WRITER));
                successorMonitor = monitorOf(successorConfig, successorElector, successorStore, NEW_WRITER,
                    NODES.control(runner, NEW_WRITER));
                context.completeNow();
            })).onFailure(context::failNow);
    }

    // Every step runs, and every failure fails the test. Future.eventually discards a cleanup failure.
    @AfterEach void tearDown(VertxTestContext context) {
        List<Throwable> failures = new ArrayList<>();
        writerCoordinator.clearHooks();
        Future<Void> closed = Future.succeededFuture();
        for (PgRenewalLoop loop : loops) {
            closed = closed.compose(ignored -> loop.stop().transform(done -> settle(done, failures)));
        }
        closed = closed.compose(ignored -> NODES.forceStopAll(runner).transform(stopped -> settle(stopped, failures)))
            .compose(ignored -> writerElector.close().transform(done -> settle(done, failures)))
            .compose(ignored -> successorElector.close().transform(done -> settle(done, failures)));
        for (PgPrimaryElector observer : observers) {
            closed = closed.compose(ignored -> observer.close().transform(done -> settle(done, failures)));
        }
        for (PgMemberPositions positions : positionAdapters) {
            closed = closed.compose(ignored -> positions.close().transform(done -> settle(done, failures)));
        }
        closed.onSuccess(ignored -> context.verify(() -> {
            admin.close();
            if (failures.isEmpty()) {
                context.completeNow();
                return;
            }
            Throwable first = failures.get(0);
            failures.stream().skip(1).forEach(first::addSuppressed);
            context.failNow(first);
        })).onFailure(context::failNow);
    }

    private static Future<Void> settle(AsyncResult<?> step, List<Throwable> failures) {
        if (step.failed()) failures.add(step.cause());
        return Future.succeededFuture();
    }

    @Test
    void aPlannedStopReleasesAfterTheConfirmedStopAndTheSuccessorAcquiresAtOnce(VertxTestContext context) {
        // The release hook observes the local process at the moment the release is sent, then sends it.
        writerCoordinator.onRelease(held -> NODES.control(runner, WRITER).status().compose(status -> {
            assertEquals(PgProcessState.STOPPED, status, "The lease was released while PostgreSQL still ran");
            return writerCoordinator.realRelease(held);
        }));
        serving().compose(held -> renewing().compose(loop -> awaitRenewals(2).compose(renewed -> loop.stop()))
            .compose(stopped -> writerMonitor.stopAndRelease(held, "planned maintenance"))
            .compose(released -> {
                assertNull(released.leaseHolder(), "The record is still owned after the release");
                assertEquals(held.generation(), released.generation(), "The release changed the generation");
                assertEquals(held.intent(), released.intent(), "The release changed the intent");
                assertFalse(writerElector.hasFreshOwnership(), "The writer kept ownership after releasing");
                // The successor acquires at once; it does not wait for expiry.
                return new PgTakeover(successorConfig, successorElector, successorStore, successorMonitor)
                    .acquire(released, PgFailoverMode.MANUAL).map(acquired -> {
                        long sinceLastRenewal = System.nanoTime() - lastRenewalStart();
                        assertTrue(sinceLastRenewal < LEASE_TTL.toNanos(), "The successor acquired only after "
                            + TimeUnit.NANOSECONDS.toMillis(sinceLastRenewal) + " ms; the lease could have expired");
                        assertTrue(acquired.generation() > held.generation());
                        return held;
                    });
            }))
            .compose(held -> writerStore.receipt(held.generation(), operationId(held), PgFailoverMonitor.STOP_WRITER,
                WRITER))
            .onSuccess(receipt -> context.verify(() -> {
                assertEquals(PgActionResult.COMPLETED, receipt.orElseThrow().result());
                assertEquals("planned maintenance", receipt.orElseThrow().parameters().getString("reason"));
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
    void anUnconfirmedStopQuarantinesTheNodeAndKeepsTheLease(VertxTestContext context) {
        // This supervisor's stop command cannot run. PostgreSQL keeps running.
        var node = NODES.node(WRITER);
        String directory = NODES.dataDirectory();
        var broken = new PgCtlProcessControl(runner, node.prefix(), "/nonexistent/pg_ctl", directory,
            node.logFile(directory), COMMAND_TIMEOUT);
        var brokenMonitor = monitorOf(writerConfig, writerElector, writerStore, WRITER, broken);
        serving().compose(held -> brokenMonitor.stopAndRelease(held, "planned maintenance").transform(stop -> {
            assertTrue(stop.failed(), "An unconfirmed stop was reported as a completed planned stop");
            assertInstanceOf(PgProcessControlException.class, stop.cause());
            assertFalse(writerElector.hasFreshOwnership(), "An unconfirmed stop must retire ownership, so it cannot renew");
            return observer().read();
        }).compose(read -> {
            assertNotNull(read.orElseThrow().leaseHolder(), "The lease was released after an unconfirmed stop");
            assertEquals(held.generation(), read.orElseThrow().generation());
            return writerStore.quarantine();
        }).compose(quarantine -> {
            assertTrue(quarantine.isPresent(), "An unconfirmed stop must quarantine the node");
            return writerStore.receipt(held.generation(), operationId(held), PgFailoverMonitor.STOP_WRITER, WRITER);
        }).compose(receipt -> {
            assertEquals(PgActionResult.UNKNOWN, receipt.orElseThrow().result());
            return NODES.control(runner, WRITER).status();
        })).onSuccess(status -> context.verify(() -> {
            assertEquals(PgProcessState.RUNNING, status, "The precondition failed: the broken stop stopped PostgreSQL");
            context.completeNow();
        })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation rejected on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    void aRecordThisNodeDoesNotHoldIsRefusedBeforeAnyEffect(VertxTestContext context) {
        serving().compose(held -> {
            var unowned = new PgControlRecord(held.controlName(), held.generation(), held.revision(),
                UUID.randomUUID().toString(), held.intent());
            return writerMonitor.stopAndRelease(unowned, "planned maintenance").transform(refusal -> {
                assertTrue(refusal.failed(), "A planned stop ran on a record this node does not hold");
                assertInstanceOf(PgLeaseProtocolException.class, refusal.cause());
                return observer().read();
            }).compose(read -> {
                assertEquals(held, read.orElseThrow(), "A refused planned stop changed the control record");
                return writerStore.receipt(held.generation(), operationId(held), PgFailoverMonitor.STOP_WRITER, WRITER);
            }).compose(receipt -> {
                assertTrue(receipt.isEmpty(), "A refused planned stop began a stop receipt");
                return NODES.control(runner, WRITER).status();
            });
        }).onSuccess(status -> context.verify(() -> {
            assertEquals(PgProcessState.RUNNING, status, "A refused planned stop stopped PostgreSQL");
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
        message = "Local supervision operation failed on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    void aFailedReleaseLeavesPostgreSQLStoppedAndTheLeaseToExpire(VertxTestContext context) {
        writerCoordinator.onRelease(held -> Future.failedFuture(new PgLeaseProtocolException("injected release failure")));
        serving().compose(held -> writerMonitor.stopAndRelease(held, "planned maintenance").transform(stop -> {
            assertTrue(stop.failed(), "A planned stop reported a release that failed");
            assertInstanceOf(PgLeaseProtocolException.class, stop.cause());
            assertFalse(writerElector.hasFreshOwnership(), "A failed release must retire ownership");
            return observer().read();
        }).compose(read -> {
            assertNotNull(read.orElseThrow().leaseHolder(), "The precondition failed: the release reached the coordinator");
            return NODES.control(runner, WRITER).status();
        })).onSuccess(status -> context.verify(() -> {
            assertEquals(PgProcessState.STOPPED, status, "PostgreSQL must stay stopped when the release fails");
            context.completeNow();
        })).onFailure(context::failNow);
    }

    // ---------------------------------------------------------------- helpers

    /** The writer acquires the lease and confirms revision 1 of the policy for both standbys. */
    private Future<PgControlRecord> serving() {
        JsonObject first = new JsonObject().put("writerNodeId", WRITER).put("phase", "WITHDRAWN")
            .put("operationId", UUID.randomUUID().toString())
            .put("pendingDurabilityPolicy", new PgDurabilityPolicy(1, STANDBYS).toJson());
        return writerElector.createInitialIntent(first).compose(initial -> {
            JsonObject confirmed = initial.intent();
            confirmed.put("durabilityPolicy", confirmed.remove("pendingDurabilityPolicy"));
            return writerElector.update(initial, confirmed);
        });
    }

    private Future<PgRenewalLoop> renewing() {
        var loop = new PgRenewalLoop(vertx, writerConfig, writerElector, writerMonitor);
        loops.add(loop);
        return loop.start().map(loop);
    }

    private Future<Void> awaitRenewals(int count) {
        return awaitRenewals(count, System.nanoTime() + TimeUnit.SECONDS.toNanos(30));
    }

    private Future<Void> awaitRenewals(int count, long deadline) {
        if (writerCoordinator.renewStarts().size() >= count) return Future.succeededFuture();
        if (System.nanoTime() >= deadline) {
            return Future.failedFuture(new AssertionError("The writer did not renew " + count + " times"));
        }
        return vertx.timer(100).compose(ignored -> awaitRenewals(count, deadline));
    }

    private long lastRenewalStart() {
        List<Long> starts = writerCoordinator.renewStarts();
        return starts.get(starts.size() - 1);
    }

    private Future<PgLocalStateStore> memberStore(String nodeId) {
        Path state = stateDirectory.resolve(nodeId);
        return vertx.fileSystem().mkdirs(state.toString()).compose(ignored -> PgLocalStateStore.open(vertx, state, nodeId));
    }

    private PgFailoverMonitor monitorOf(PgNodeConfig nodeConfig, PgPrimaryElector owner, PgLocalStateStore nodeStore,
                                        String nodeId, PgProcessControl process) {
        var node = NODES.node(nodeId);
        String directory = NODES.dataDirectory();
        var gate = new PgHbaAdmissionGate(vertx, runner, node.prefix(), PgSupervisedContainer.PG_CTL, "psql",
            directory, node.openHba(directory), node.closedHba(directory), COMMAND_TIMEOUT);
        var promotion = new PgSqlPromotionControl(vertx, runner, node.prefix(), PgSupervisedContainer.PG_CTL, "psql",
            directory, COMMAND_TIMEOUT);
        // This host has no watchdog device. The mode is OFF, so it is never used.
        var watchdog = new PgDeviceWatchdog(vertx, stateDirectory.resolve("no-watchdog"),
            stateDirectory.resolve("no-watchdog-timeout"));
        var positions = new ConsulMemberPositions(vertx, nodeConfig, endpoint(), "consul-1", TOKEN);
        positionAdapters.add(positions);
        return PgFailoverMonitor.builder().config(nodeConfig).elector(owner).store(nodeStore).process(process)
            .gate(gate).watchdog(watchdog).durability(durabilityOf(nodeId)).promotion(promotion).positions(positions)
            .build();
    }

    private PgDurabilityControl durabilityOf(String nodeId) {
        return new PgSqlDurabilityControl(vertx, runner, NODES.node(nodeId).prefix(), PgSupervisedContainer.PG_CTL,
            "psql", NODES.dataDirectory(), COMMAND_TIMEOUT);
    }

    private PgPrimaryElector observer() {
        var observer = new PgPrimaryElector(successorConfig,
            new ConsulLeaseCoordinator(vertx, successorConfig, endpoint(), "consul-1", TOKEN));
        observers.add(observer);
        return observer;
    }

    /** A 10-second lease TTL, the Consul minimum; ownership stays fresh for the TTL minus the 5-second stop budget. */
    private static PgNodeConfig nodeConfig(String nodeId, String incarnation) {
        return new PgNodeConfig("planned-stop-tests", incarnation, nodeId, MEMBERS, LEASE_TTL,
            Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(5), PgWatchdogMode.OFF);
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
