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
 * A standby follows the new writer under its own supervisor: a real coordinator, a real takeover record, a
 * real promoted standby, a real second standby that still streams from the former writer, and real
 * node-local storage. The follower holds no lease. Application admission stays closed throughout.
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Isolated
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class PgFollowIntegrationTest {
    private static final List<String> MEMBERS = List.of("pg-node-1", "pg-node-2", "pg-node-3");
    private static final String NEW_WRITER = "pg-node-2";
    private static final String FOLLOWER = "pg-node-3";
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
    private PgNodeConfig followerConfig;
    private PgPrimaryElector historyElector;
    private PgPrimaryElector writerElector;
    private PgPrimaryElector followerElector;
    private PgLocalStateStore store;
    private PgAdmissionGate gate;
    private PgPromotionControl promotion;
    private PgFailoverMonitor monitor;
    private PgControlRecord held;

    @BeforeAll static void startContainers() {
        CONSUL.start();
        NODES.start();
    }

    @AfterAll static void stopContainers() {
        NODES.close();
        CONSUL.stop();
    }

    // Provisioning three nodes, the takeover, and the promotion can approach the 30-second Vert.x context
    // default. The JUnit @Timeout on the class does not set it: the extension reads io.vertx.junit5.Timeout.
    @io.vertx.junit5.Timeout(value = 180, timeUnit = TimeUnit.SECONDS)
    @BeforeEach void setUp(Vertx vertx, VertxTestContext context) {
        this.vertx = vertx;
        admin = WebClient.create(vertx);
        runner = new LocalCommandRunner(vertx);
        String incarnation = "inc-" + UUID.randomUUID();
        var historyConfig = nodeConfig("pg-node-1", incarnation);
        var writerConfig = nodeConfig(NEW_WRITER, incarnation);
        followerConfig = nodeConfig(FOLLOWER, incarnation);
        historyElector = new PgPrimaryElector(historyConfig,
            new ConsulLeaseCoordinator(vertx, historyConfig, endpoint(), "consul-1", TOKEN));
        writerElector = new PgPrimaryElector(writerConfig,
            new ConsulLeaseCoordinator(vertx, writerConfig, endpoint(), "consul-1", TOKEN));
        followerElector = new PgPrimaryElector(followerConfig,
            new ConsulLeaseCoordinator(vertx, followerConfig, endpoint(), "consul-1", TOKEN));
        waitForLeader(System.nanoTime() + TimeUnit.SECONDS.toNanos(30))
            .compose(ignored -> NODES.provision(runner))
            .compose(ignored -> NODES.control(runner, WRITER).startPrimary())
            .compose(ignored -> NODES.control(runner, NEW_WRITER).startInRecovery())
            .compose(ignored -> NODES.control(runner, FOLLOWER).startInRecovery())
            .compose(ignored -> new PgSqlDurabilityControl(vertx, runner, NODES.node(WRITER).prefix(),
                PgSupervisedContainer.PG_CTL, "psql", NODES.dataDirectory(), COMMAND_TIMEOUT)
                .confirmCovered(new PgDurabilityPolicy(1, STANDBYS)))
            .compose(ignored -> takeover())
            .compose(taken -> {
                held = taken;
                var newWriter = promotionControl(NEW_WRITER);
                return newWriter.promote().compose(promoted -> newWriter.checkpoint());
            }).compose(ignored -> {
                var follower = NODES.node(FOLLOWER);
                String directory = NODES.dataDirectory();
                promotion = promotionControl(FOLLOWER);
                gate = new PgHbaAdmissionGate(vertx, runner, follower.prefix(), PgSupervisedContainer.PG_CTL,
                    "psql", directory, follower.openHba(directory), follower.closedHba(directory), COMMAND_TIMEOUT);
                var durability = new PgSqlDurabilityControl(vertx, runner, follower.prefix(),
                    PgSupervisedContainer.PG_CTL, "psql", directory, COMMAND_TIMEOUT);
                // This host has no watchdog device. A follower never writes, so the mode is never applied.
                var watchdog = new PgDeviceWatchdog(vertx, stateDirectory.resolve("no-watchdog"),
                    stateDirectory.resolve("no-watchdog-timeout"));
                return PgLocalStateStore.open(vertx, stateDirectory, FOLLOWER).map(opened -> {
                    store = opened;
                    monitor = new PgFailoverMonitor(followerConfig, followerElector, store,
                        NODES.control(runner, FOLLOWER), gate, watchdog, durability, promotion);
                    return null;
                });
            }).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    // Every step runs, and every failure fails the test. Future.eventually discards a cleanup failure.
    @AfterEach void tearDown(VertxTestContext context) {
        List<Throwable> failures = new ArrayList<>();
        NODES.forceStopAll(runner).transform(stopped -> settle(stopped, failures))
            .compose(ignored -> historyElector.close().transform(closed -> settle(closed, failures)))
            .compose(ignored -> writerElector.close().transform(closed -> settle(closed, failures)))
            .compose(ignored -> followerElector.close().transform(closed -> settle(closed, failures)))
            .onSuccess(ignored -> context.verify(() -> {
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

    @Test void followingTheNewWriterStreamsFromItWithAdmissionClosedAndACompletedReceipt(VertxTestContext context) {
        var follower = NODES.node(FOLLOWER);
        monitor.followWriter(held, writerEndpoint()).compose(ignored -> assertStreamingFromTheNewWriter())
            .compose(ignored -> store.receipt(held.generation(), operationId(), PgFailoverMonitor.FOLLOW_WRITER,
                FOLLOWER))
            .compose(receipt -> {
                assertEquals(PgActionResult.COMPLETED, receipt.orElseThrow().result());
                assertEquals(new JsonObject().put("host", NEW_WRITER).put("port", 5432),
                    receipt.orElseThrow().parameters());
                return store.grant();
            }).compose(grant -> {
                assertTrue(grant.filter(open -> open.state() != PgGrantState.CLOSED).isEmpty(),
                    "Following never opens admission");
                return follower.applicationSql(runner, "select 1");
            }).onSuccess(application -> context.verify(() -> {
                assertNotEquals(0, application.exitCode(), "Following admitted an application connection");
                assertFalse(followerElector.hasFreshOwnership(), "A follower holds no lease");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void followingClosesAnOpenGrantAndAdmissionBeforeItChangesAnything(VertxTestContext context) {
        var follower = NODES.node(FOLLOWER);
        store.prepare(PgFailoverMode.MANUAL, held.generation(), operationId(), 1).compose(store::activate)
            .compose(open -> gate.open())
            .compose(ignored -> follower.applicationSql(runner, "select 1"))
            .compose(admitted -> {
                assertEquals(0, admitted.exitCode(), "The precondition failed: admission was not open");
                return monitor.followWriter(held, writerEndpoint());
            }).compose(ignored -> store.grant())
            .compose(grant -> {
                assertEquals(PgGrantState.CLOSED, grant.orElseThrow().state(), "The grant stayed open");
                return follower.applicationSql(runner, "select 1");
            }).onSuccess(application -> context.verify(() -> {
                assertNotEquals(0, application.exitCode(), "Admission stayed open while following");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation rejected on node pg-node-3",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    void aRecordThatNamesThisNodeAsTheWriterIsRefusedAndChangesNothing(VertxTestContext context) {
        var intent = new JsonObject().put("writerNodeId", FOLLOWER).put("phase", "WITHDRAWN")
            .put("operationId", UUID.randomUUID().toString())
            .put("pendingDurabilityPolicy", new PgDurabilityPolicy(1, List.of("pg-node-1", NEW_WRITER)).toJson());
        var naming = new PgControlRecord(followerConfig.controlName(), 2, 2, UUID.randomUUID().toString(), intent);
        monitor.followWriter(naming, writerEndpoint()).transform(follow -> {
            assertTrue(follow.failed(), "A node was told to follow itself");
            assertInstanceOf(PgLeaseProtocolException.class, follow.cause());
            return assertNotFollowingWithNoReceipt(naming);
        }).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation failed on node pg-node-3",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLocalStateException.class)
    void aQuarantinedNodeCannotFollow(VertxTestContext context) {
        store.quarantine("awaiting rewind").compose(ignored -> monitor.followWriter(held, writerEndpoint()))
            .transform(follow -> {
                assertTrue(follow.failed(), "A quarantined node followed a writer");
                assertInstanceOf(PgLocalStateException.class, follow.cause());
                return assertNotFollowingWithNoReceipt(held);
            }).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation failed on node pg-node-3",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgPromotionException.class)
    void aWritableNodeCannotFollowAndChangesNothing(VertxTestContext context) {
        promotion.promote().compose(ignored -> monitor.followWriter(held, writerEndpoint())).transform(follow -> {
            assertTrue(follow.failed(), "A writable node was pointed at another writer");
            assertInstanceOf(PgPromotionException.class, follow.cause());
            return store.receipt(held.generation(), operationId(), PgFailoverMonitor.FOLLOW_WRITER, FOLLOWER);
        }).onSuccess(receipt -> context.verify(() -> {
            assertTrue(receipt.isEmpty(), "A refused follow must not record an effect");
            context.completeNow();
        })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation failed on node pg-node-3",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgPromotionException.class)
    void aFailedFollowRecordsAnUnknownReceiptAndLeavesAdmissionClosed(VertxTestContext context) {
        var failing = monitorWith(new FailingFollowControl(promotion));
        failing.followWriter(held, writerEndpoint()).transform(follow -> {
            assertTrue(follow.failed(), "A failed follow was reported as success");
            assertInstanceOf(PgPromotionException.class, follow.cause());
            return store.receipt(held.generation(), operationId(), PgFailoverMonitor.FOLLOW_WRITER, FOLLOWER);
        }).compose(receipt -> {
            assertEquals(PgActionResult.UNKNOWN, receipt.orElseThrow().result());
            return NODES.node(FOLLOWER).applicationSql(runner, "select 1");
        }).onSuccess(application -> context.verify(() -> {
            assertNotEquals(0, application.exitCode(), "A failed follow left admission open");
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
        message = "Local supervision operation failed on node pg-node-3",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = FileSystemException.class)
    void aStorageFaultWhenTheReceiptIsBegunFailsTheFollowBeforeAnyEffect(VertxTestContext context) {
        // A directory where the store must create its temporary receipt file: the write fails.
        vertx.fileSystem().mkdirs(receiptTemporaryFile().toString())
            .compose(ignored -> monitor.followWriter(held, writerEndpoint())).transform(follow -> {
                assertTrue(follow.failed(), "The follow succeeded although its receipt could not be written");
                assertInstanceOf(PgLocalStateException.class, follow.cause());
                return countReceiversFrom(NEW_WRITER);
            }).onSuccess(count -> context.verify(() -> {
                assertEquals("0", count, "The node followed the writer without a receipt");
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
        message = "Local supervision operation failed on node pg-node-3",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLocalStateException.class)
    void aReceiptForAnotherWriterAddressRefusesTheFollowAndChangesNothing(VertxTestContext context) {
        var other = new JsonObject().put("host", "pg-elsewhere").put("port", 5432);
        store.begin(held.generation(), operationId(), PgFailoverMonitor.FOLLOW_WRITER, FOLLOWER, other)
            .compose(begun -> monitor.followWriter(held, writerEndpoint())).transform(follow -> {
                assertTrue(follow.failed(), "A receipt for another writer address did not stop the follow");
                assertInstanceOf(PgLocalStateException.class, follow.cause());
                return countReceiversFrom(NEW_WRITER);
            }).onSuccess(count -> context.verify(() -> {
                assertEquals("0", count);
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void followingTwiceIsIdempotentAndKeepsTheReceiptCompleted(VertxTestContext context) {
        monitor.followWriter(held, writerEndpoint()).compose(ignored -> monitor.followWriter(held, writerEndpoint()))
            .compose(ignored -> assertStreamingFromTheNewWriter())
            .compose(ignored -> store.receipt(held.generation(), operationId(), PgFailoverMonitor.FOLLOW_WRITER,
                FOLLOWER))
            .onSuccess(receipt -> context.verify(() -> {
                assertEquals(PgActionResult.COMPLETED, receipt.orElseThrow().result());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void aRerunAfterAnUnknownReceiptCompletesTheSameReceipt(VertxTestContext context) {
        var params = new JsonObject().put("host", NEW_WRITER).put("port", 5432);
        store.begin(held.generation(), operationId(), PgFailoverMonitor.FOLLOW_WRITER, FOLLOWER, params)
            .compose(begun -> store.record(held.generation(), operationId(), PgFailoverMonitor.FOLLOW_WRITER,
                FOLLOWER, PgActionResult.UNKNOWN, new JsonObject()))
            .compose(ignored -> monitor.followWriter(held, writerEndpoint()))
            .compose(ignored -> store.receipt(held.generation(), operationId(), PgFailoverMonitor.FOLLOW_WRITER,
                FOLLOWER))
            .onSuccess(receipt -> context.verify(() -> {
                assertEquals(PgActionResult.COMPLETED, receipt.orElseThrow().result());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    // ---------------------------------------------------------------- fault wrapper over the real control

    /** Wraps the real promotion control. Following fails and has no effect. */
    private record FailingFollowControl(PgPromotionControl real) implements PgPromotionControl {
        @Override public Future<Boolean> inRecovery() { return real.inRecovery(); }

        @Override public Future<Void> promote() { return real.promote(); }

        @Override public Future<Void> checkpoint() { return real.checkpoint(); }

        @Override public Future<Void> follow(String nodeId, PgWriterEndpoint writer) {
            return Future.failedFuture(new PgPromotionException("Following failed (injected)"));
        }
    }

    // ---------------------------------------------------------------- helpers

    private Future<Void> assertStreamingFromTheNewWriter() {
        return NODES.node(FOLLOWER).sql(runner,
            "select status || '|' || sender_host || '|' || sender_port from pg_stat_wal_receiver").map(receiver -> {
                assertEquals("streaming|" + NEW_WRITER + "|5432", receiver.output().strip(),
                    "The follower does not stream from the new writer");
                return null;
            });
    }

    private Future<Void> assertNotFollowingWithNoReceipt(PgControlRecord record) {
        return countReceiversFrom(NEW_WRITER).compose(count -> {
            assertEquals("0", count, "A refused follow changed the WAL receiver");
            return store.receipt(record.generation(), record.intent().getString("operationId"),
                PgFailoverMonitor.FOLLOW_WRITER, FOLLOWER);
        }).map(receipt -> {
            assertTrue(receipt.isEmpty(), "A refused follow must not record an effect");
            return null;
        });
    }

    private Future<String> countReceiversFrom(String host) {
        return NODES.node(FOLLOWER).sql(runner,
            "select count(*) from pg_stat_wal_receiver where sender_host = '" + host + "'")
            .map(count -> count.output().strip());
    }

    private PgFailoverMonitor monitorWith(PgPromotionControl control) {
        var follower = NODES.node(FOLLOWER);
        String directory = NODES.dataDirectory();
        var durability = new PgSqlDurabilityControl(vertx, runner, follower.prefix(), PgSupervisedContainer.PG_CTL,
            "psql", directory, COMMAND_TIMEOUT);
        var watchdog = new PgDeviceWatchdog(vertx, stateDirectory.resolve("no-watchdog"),
            stateDirectory.resolve("no-watchdog-timeout"));
        return new PgFailoverMonitor(followerConfig, followerElector, store, NODES.control(runner, FOLLOWER), gate,
            watchdog, durability, control);
    }

    private PgPromotionControl promotionControl(String nodeId) {
        return new PgSqlPromotionControl(vertx, runner, NODES.node(nodeId).prefix(), PgSupervisedContainer.PG_CTL,
            "psql", NODES.dataDirectory(), COMMAND_TIMEOUT);
    }

    private PgWriterEndpoint writerEndpoint() {
        return NODES.endpoint(NEW_WRITER, FOLLOWER);
    }

    /**
     * The former writer confirms revision 1 of the policy and releases the lease. The new writer's elector
     * takes the released lease with the takeover intent, and the former writer is stopped, as it is when its
     * host is lost.
     */
    private Future<PgControlRecord> takeover() {
        JsonObject first = new JsonObject().put("writerNodeId", "pg-node-1").put("phase", "WITHDRAWN")
            .put("operationId", UUID.randomUUID().toString())
            .put("pendingDurabilityPolicy", new PgDurabilityPolicy(1, STANDBYS).toJson());
        return historyElector.createInitialIntent(first).compose(initial -> {
            JsonObject confirmed = initial.intent();
            confirmed.put("durabilityPolicy", confirmed.remove("pendingDurabilityPolicy"));
            return historyElector.update(initial, confirmed);
        }).compose(historyElector::release)
            .compose(released -> NODES.control(runner, WRITER).stop(Duration.ofSeconds(8))
                .compose(stopped -> writerElector.acquireAfterRelease(released, new JsonObject()
                    .put("writerNodeId", NEW_WRITER).put("phase", "WITHDRAWN")
                    .put("operationId", UUID.randomUUID().toString()).put("previousWriterNodeId", "pg-node-1")
                    .put("durabilityPolicy", new JsonObject().put("revision", 1L)
                        .put("requiredStandbyNodeIds", new JsonArray(new ArrayList<>(STANDBYS)))))));
    }

    /** The ownership budget (TTL minus stop) outlasts one test, so no renewal loop is needed. */
    private static PgNodeConfig nodeConfig(String nodeId, String incarnation) {
        return new PgNodeConfig("follow-tests", incarnation, nodeId, MEMBERS, Duration.ofSeconds(80),
            Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofSeconds(8), PgWatchdogMode.OFF);
    }

    /** The temporary file the store writes before it atomically replaces the follow receipt. */
    private Path receiptTemporaryFile() {
        return stateDirectory.resolve("receipts").resolve(held.generation() + "+" + operationId() + "+"
            + PgFailoverMonitor.FOLLOW_WRITER + "+" + FOLLOWER + ".json.tmp");
    }

    private String operationId() {
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
