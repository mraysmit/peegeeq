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
 * The policy cutover on the new writer after a takeover: a real coordinator, a real released lease, a real
 * promoted standby under its own supervisor, a real surviving standby that its own supervisor points at the
 * new writer, and real node-local storage on both nodes. Admission stays closed until activation.
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Isolated
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class PgTakeoverIntegrationTest {
    private static final List<String> MEMBERS = List.of("pg-node-1", "pg-node-2", "pg-node-3");
    private static final String NEW_WRITER = "pg-node-2";
    private static final String FOLLOWER = "pg-node-3";
    private static final String TOKEN = "unused-without-acls";
    private static final String CUT_OVER = "ANY 1 (\"pg-node-3\")";
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(20);
    private static final GenericContainer<?> CONSUL = new GenericContainer<>("hashicorp/consul:1.22.1")
        .withExposedPorts(8500)
        .withCommand("agent", "-dev", "-client=0.0.0.0", "-node=consul-1", "-log-level=warn")
        .waitingFor(Wait.forHttp("/v1/status/leader").forPort(8500).withStartupTimeout(Duration.ofSeconds(60)));
    private static final PgReplicationFixture NODES = new PgReplicationFixture();
    @TempDir Path stateDirectory;
    private final List<PgPrimaryElector> observers = new ArrayList<>();
    private final List<PgMemberPositions> positionAdapters = new ArrayList<>();
    private Vertx vertx;
    private WebClient admin;
    private PgCommandRunner runner;
    private PgNodeConfig config;
    private PgNodeConfig followerConfig;
    private PgPrimaryElector historyElector;
    private ObservedLeaseCoordinator coordinator;
    private PgPrimaryElector elector;
    private PgPrimaryElector followerElector;
    private PgLocalStateStore store;
    private PgLocalStateStore followerStore;
    private PgFailoverMonitor monitor;
    private PgFailoverMonitor followerMonitor;

    @BeforeAll static void startContainers() {
        CONSUL.start();
        NODES.start();
    }

    @AfterAll static void stopContainers() {
        NODES.close();
        CONSUL.stop();
    }

    // Provisioning three nodes and covering both standbys can approach the 30-second Vert.x context default.
    // The JUnit @Timeout on the class does not set it: the extension reads io.vertx.junit5.Timeout.
    @io.vertx.junit5.Timeout(value = 180, timeUnit = TimeUnit.SECONDS)
    @BeforeEach void setUp(Vertx vertx, VertxTestContext context) {
        this.vertx = vertx;
        admin = WebClient.create(vertx);
        runner = new LocalCommandRunner(vertx);
        String incarnation = "inc-" + UUID.randomUUID();
        var historyConfig = nodeConfig(WRITER, incarnation);
        config = nodeConfig(NEW_WRITER, incarnation);
        followerConfig = nodeConfig(FOLLOWER, incarnation);
        historyElector = new PgPrimaryElector(historyConfig,
            new ConsulLeaseCoordinator(vertx, historyConfig, endpoint(), "consul-1", TOKEN));
        coordinator = new ObservedLeaseCoordinator(new ConsulLeaseCoordinator(vertx, config, endpoint(), "consul-1", TOKEN));
        elector = new PgPrimaryElector(config, coordinator);
        followerElector = new PgPrimaryElector(followerConfig,
            new ConsulLeaseCoordinator(vertx, followerConfig, endpoint(), "consul-1", TOKEN));
        Path writerState = stateDirectory.resolve(NEW_WRITER);
        Path followerState = stateDirectory.resolve(FOLLOWER);
        waitForLeader(System.nanoTime() + TimeUnit.SECONDS.toNanos(30))
            .compose(ignored -> NODES.provision(runner))
            .compose(ignored -> NODES.control(runner, WRITER).startPrimary())
            .compose(ignored -> NODES.control(runner, NEW_WRITER).startInRecovery())
            .compose(ignored -> NODES.control(runner, FOLLOWER).startInRecovery())
            .compose(ignored -> durabilityOf(WRITER, COMMAND_TIMEOUT).confirmCovered(new PgDurabilityPolicy(1, STANDBYS)))
            .compose(ignored -> vertx.fileSystem().mkdirs(writerState.toString()))
            .compose(ignored -> vertx.fileSystem().mkdirs(followerState.toString()))
            .compose(ignored -> PgLocalStateStore.open(vertx, writerState, NEW_WRITER))
            .compose(opened -> {
                store = opened;
                return PgLocalStateStore.open(vertx, followerState, FOLLOWER);
            }).onSuccess(opened -> context.verify(() -> {
                followerStore = opened;
                monitor = monitorOf(config, elector, store, NEW_WRITER, durabilityOf(NEW_WRITER, COMMAND_TIMEOUT));
                followerMonitor = monitorOf(followerConfig, followerElector, followerStore, FOLLOWER,
                    durabilityOf(FOLLOWER, COMMAND_TIMEOUT));
                context.completeNow();
            })).onFailure(context::failNow);
    }

    // Every step runs, and every failure fails the test. Future.eventually discards a cleanup failure.
    @AfterEach void tearDown(VertxTestContext context) {
        List<Throwable> failures = new ArrayList<>();
        coordinator.clearHooks();
        Future<Void> closed = NODES.forceStopAll(runner).transform(stopped -> settle(stopped, failures))
            .compose(ignored -> historyElector.close().transform(done -> settle(done, failures)))
            .compose(ignored -> elector.close().transform(done -> settle(done, failures)))
            .compose(ignored -> followerElector.close().transform(done -> settle(done, failures)));
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
    // Takeover, promotion, the follow, and the cutover together can exceed the 30-second Vert.x default.
    @io.vertx.junit5.Timeout(value = 120, timeUnit = TimeUnit.SECONDS)
    void theCutoverServesTheNewWriterWithOnlyTheSurvivingStandbyRequired(VertxTestContext context) {
        var writer = NODES.node(NEW_WRITER);
        var expected = new PgDurabilityPolicy(2, List.of(FOLLOWER)).toJson();
        takeover(STANDBYS, null).compose(this::promoted).compose(held -> follow(held)
            .compose(ignored -> takeoverOf(monitor).cutover(held, PgFailoverMode.MANUAL)).compose(serving -> {
                JsonObject intent = serving.intent();
                assertEquals("SERVING", intent.getString("phase"));
                assertEquals(NEW_WRITER, intent.getString("writerNodeId"));
                assertEquals(WRITER, intent.getString("previousWriterNodeId"));
                assertEquals(operationId(held), intent.getString("operationId"));
                assertEquals(expected, intent.getJsonObject("durabilityPolicy"),
                    "The served policy is not the confirmed set minus the new writer at the next revision");
                assertFalse(intent.containsKey("pendingDurabilityPolicy"), "Confirming clears the pending policy");
                assertEquals(held.leaseHolder(), serving.leaseHolder());
                assertEquals(held.generation(), serving.generation());
                return observer().read().compose(read -> {
                    assertEquals(serving, read.orElseThrow(), "The coordinator does not hold the serving intent");
                    return store.receipt(held.generation(), operationId(held), PgFailoverMonitor.INSTALL_POLICY,
                        NEW_WRITER);
                });
            }).compose(receipt -> {
                assertEquals(PgActionResult.COMPLETED, receipt.orElseThrow().result());
                assertEquals(expected, receipt.orElseThrow().parameters());
                return store.grant();
            }).compose(grant -> {
                assertEquals(PgGrantState.OPEN, grant.orElseThrow().state());
                assertEquals(2L, grant.orElseThrow().policyRevision());
                assertEquals(held.generation(), grant.orElseThrow().generation());
                assertEquals(PgFailoverMode.MANUAL, grant.orElseThrow().mode());
                return writer.sql(runner, "show synchronous_standby_names");
            }).compose(effective -> {
                assertEquals(CUT_OVER, effective.output().strip());
                return writer.applicationSql(runner, "create table served_after_takeover (id int)");
            }).compose(write -> {
                assertEquals(0, write.exitCode(), "A committed write under the cut-over policy failed: " + write.output());
                return writer.sql(runner, "select application_name || '|' || sync_state from pg_stat_replication");
            }).compose(replication -> {
                assertEquals(FOLLOWER + "|quorum", replication.output().strip(),
                    "The surviving standby is not the synchronous peer of the new writer");
                return writer.sql(runner, "select string_agg(slot_name || '|' || active::text, ',' order by slot_name) "
                    + "from pg_replication_slots");
            })).onSuccess(slots -> context.verify(() -> {
                assertEquals("peegeeq_pg_node_1|false,peegeeq_pg_node_3|true", slots.output().strip(),
                    "The new writer must keep the former writer's slot for re-join and stream the follower through its own");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    // Takeover, promotion, the follow, and the cutover together can exceed the 30-second Vert.x default.
    @io.vertx.junit5.Timeout(value = 120, timeUnit = TimeUnit.SECONDS)
    void aCutoverWaitingForCoverageCompletesWhenTheFollowerFollowsDuringTheWait(VertxTestContext context) {
        takeover(STANDBYS, null).compose(this::promoted).compose(held -> {
            var waiting = new FollowDuringCoverageWait(durabilityOf(NEW_WRITER, COMMAND_TIMEOUT), () -> follow(held));
            var cutover = takeoverOf(monitorOf(config, elector, store, NEW_WRITER, waiting));
            return cutover.cutover(held, PgFailoverMode.MANUAL).compose(serving -> {
                assertTrue(waiting.followStarted(), "The follow did not start inside the coverage wait");
                return followerStore.receipt(held.generation(), operationId(held), PgFailoverMonitor.FOLLOW_WRITER,
                    FOLLOWER).map(receipt -> {
                        assertEquals(PgActionResult.COMPLETED, receipt.orElseThrow().result());
                        return serving;
                    });
            });
        }).onSuccess(serving -> context.verify(() -> {
            assertEquals("SERVING", serving.intent().getString("phase"));
            assertEquals(new PgDurabilityPolicy(2, List.of(FOLLOWER)).toJson(),
                serving.intent().getJsonObject("durabilityPolicy"));
            context.completeNow();
        })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation failed on node pg-node-2",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgDurabilityException.class)
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgTakeover",
        message = "Takeover cutover failed on node pg-node-2",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgDurabilityException.class)
    // The coverage budget of 5 seconds runs out once, then the follow and the rerun follow.
    @io.vertx.junit5.Timeout(value = 120, timeUnit = TimeUnit.SECONDS)
    void aRerunAfterACoverageFailureReusesThePendingPolicyAndServesOnceTheFollowerFollows(VertxTestContext context) {
        var bounded = takeoverOf(monitorOf(config, elector, store, NEW_WRITER,
            durabilityOf(NEW_WRITER, Duration.ofSeconds(5))));
        var pending = new PgDurabilityPolicy(2, List.of(FOLLOWER)).toJson();
        takeover(STANDBYS, null).compose(this::promoted).compose(held -> bounded.cutover(held, PgFailoverMode.MANUAL)
            .transform(first -> {
                assertTrue(first.failed(), "Admission opened although the surviving standby did not follow the new writer");
                assertInstanceOf(PgDurabilityException.class, first.cause());
                return observer().read();
            }).compose(read -> {
                PgControlRecord withdrawn = read.orElseThrow();
                JsonObject intent = withdrawn.intent();
                assertEquals("WITHDRAWN", intent.getString("phase"));
                assertEquals(pending, intent.getJsonObject("pendingDurabilityPolicy"),
                    "The pending policy was not persisted before the installation");
                assertEquals(held.intent().getJsonObject("durabilityPolicy"), intent.getJsonObject("durabilityPolicy"),
                    "The confirmed policy changed without a completed installation");
                return assertNoInstallationAndAdmissionClosed(held)
                    .compose(closed -> follow(held))
                    .compose(followed -> bounded.cutover(withdrawn, PgFailoverMode.MANUAL));
            })).compose(serving -> {
                assertEquals("SERVING", serving.intent().getString("phase"));
                assertEquals(pending, serving.intent().getJsonObject("durabilityPolicy"),
                    "The rerun wrote a new revision instead of reusing the pending policy");
                return store.grant();
            }).onSuccess(grant -> context.verify(() -> {
                assertEquals(PgGrantState.OPEN, grant.orElseThrow().state());
                assertEquals(2L, grant.orElseThrow().policyRevision());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    // Takeover, promotion, the follow, and the cutover together can exceed the 30-second Vert.x default.
    @io.vertx.junit5.Timeout(value = 120, timeUnit = TimeUnit.SECONDS)
    void aPendingPolicyCarriedFromTheFormerWriterIsReplacedAtTheNextRevision(VertxTestContext context) {
        // The former writer had proposed removing pg-node-3 and failed before it confirmed the change.
        var carried = new PgDurabilityPolicy(2, List.of(NEW_WRITER));
        takeover(STANDBYS, carried).compose(held -> {
            assertEquals(carried.toJson(), held.intent().getJsonObject("pendingDurabilityPolicy"),
                "The precondition failed: the takeover record carries no pending policy");
            return promoted(held);
        }).compose(held -> follow(held).compose(ignored -> takeoverOf(monitor).cutover(held, PgFailoverMode.MANUAL)))
            .onSuccess(serving -> context.verify(() -> {
                assertEquals("SERVING", serving.intent().getString("phase"));
                assertEquals(new PgDurabilityPolicy(3, List.of(FOLLOWER)).toJson(),
                    serving.intent().getJsonObject("durabilityPolicy"),
                    "The cutover reused a revision that already named another standby set");
                assertFalse(serving.intent().containsKey("pendingDurabilityPolicy"));
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgTakeover",
        message = "Takeover cutover failed on node pg-node-2",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgDurabilityException.class)
    void aConfirmedPolicyThatNamesOnlyTheNewWriterLeavesNoStandbyAndIsRefusedBeforeAnyEffect(VertxTestContext context) {
        takeover(List.of(NEW_WRITER), null).compose(this::promoted).compose(held -> takeoverOf(monitor)
            .cutover(held, PgFailoverMode.MANUAL).transform(refusal -> {
                assertTrue(refusal.failed(), "A writer was served with no synchronous standby");
                assertInstanceOf(PgDurabilityException.class, refusal.cause());
                return assertUnchanged(held);
            })).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgTakeover",
        message = "Takeover cutover failed on node pg-node-2",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgPromotionException.class)
    void aCutoverBeforeThisOperationsPromotionCompletedIsRefusedBeforeAnyEffect(VertxTestContext context) {
        takeover(STANDBYS, null).compose(held -> takeoverOf(monitor).cutover(held, PgFailoverMode.MANUAL)
            .transform(refusal -> {
                assertTrue(refusal.failed(), "A policy was cut over on a node that this operation never promoted");
                assertInstanceOf(PgPromotionException.class, refusal.cause());
                return assertUnchanged(held);
            })).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgTakeover",
        message = "Takeover cutover failed on node pg-node-2",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLocalStateException.class)
    void aQuarantinedNodeIsRefusedBeforeAnyEffect(VertxTestContext context) {
        takeover(STANDBYS, null).compose(this::promoted).compose(held -> store.quarantine("awaiting reconciliation")
            .compose(ignored -> takeoverOf(monitor).cutover(held, PgFailoverMode.MANUAL)).transform(refusal -> {
                assertTrue(refusal.failed(), "A quarantined node cut its policy over");
                assertInstanceOf(PgLocalStateException.class, refusal.cause());
                return assertUnchanged(held);
            })).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgTakeover",
        message = "Takeover cutover failed on node pg-node-2",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class,
        minOccurrences = 2, maxOccurrences = 2)
    void recordsOtherThanTheHeldTakeoverRecordAreRefusedBeforeAnyEffect(VertxTestContext context) {
        takeover(STANDBYS, null).compose(this::promoted).compose(held -> {
            var unowned = new PgControlRecord(held.controlName(), held.generation(), held.revision(),
                UUID.randomUUID().toString(), held.intent());
            return takeoverOf(monitor).cutover(unowned, PgFailoverMode.MANUAL).transform(first -> {
                assertTrue(first.failed(), "A cutover ran on a record this node does not hold");
                assertInstanceOf(PgLeaseProtocolException.class, first.cause());
                return elector.update(held, held.intent().put("phase", "FENCING"));
            }).compose(fencing -> takeoverOf(monitor).cutover(fencing, PgFailoverMode.MANUAL).transform(second -> {
                assertTrue(second.failed(), "A cutover ran on a record that is not withdrawn");
                assertInstanceOf(PgLeaseProtocolException.class, second.cause());
                return assertUnchanged(fencing);
            }));
        }).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgPrimaryElector",
        message = "Coordinator operation failed",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgTakeover",
        message = "Takeover cutover failed on node pg-node-2",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    // Takeover, promotion, the follow, and the cutover together can exceed the 30-second Vert.x default.
    @io.vertx.junit5.Timeout(value = 120, timeUnit = TimeUnit.SECONDS)
    void aFailedPendingPolicyWriteLeavesTheRecordUnchangedAndInstallsNothing(VertxTestContext context) {
        takeover(STANDBYS, null).compose(this::promoted).compose(held -> follow(held).compose(followed -> {
            coordinator.onUpdate((current, intent) ->
                Future.failedFuture(new PgLeaseProtocolException("injected update failure")));
            // The outcome judged here is the cutover's alone; a failure of the set-up steps fails the test as itself.
            return takeoverOf(monitor).cutover(held, PgFailoverMode.MANUAL).transform(result -> {
                coordinator.clearHooks();
                assertTrue(result.failed(), "The cutover continued although the pending policy was not written");
                assertInstanceOf(PgLeaseProtocolException.class, result.cause());
                assertFalse(elector.hasFreshOwnership(), "A failed update must retire ownership");
                return assertUnchanged(held);
            });
        })).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    // ---------------------------------------------------------------- a standby stopped before the takeover (plan §9.27)

    @Test
    // Publishing, 96 MB of WAL, the takeover, and the stopped standby's catch-up exceed the 30-second Vert.x default.
    @io.vertx.junit5.Timeout(value = 180, timeUnit = TimeUnit.SECONDS)
    void aStandbyStoppedBeforeTheTakeoverFollowsTheNewWriterThroughTheSlotItKeptAsAStandby(VertxTestContext context) {
        loseTheWriterWhileTheFollowerIsStopped(true)
            .compose(held -> NODES.control(runner, FOLLOWER).startInRecovery().compose(ignored -> follow(held)))
            .compose(ignored -> durabilityOf(NEW_WRITER, COMMAND_TIMEOUT)
                .confirmCovered(new PgDurabilityPolicy(2, List.of(FOLLOWER))))
            // After the takeover both nodes publish positions on the promotion's timeline.
            .compose(ignored -> monitor.publishPosition())
            .compose(ignored -> followerMonitor.publishPosition())
            .compose(ignored -> positionsOf(NEW_WRITER).read())
            .onSuccess(published -> context.verify(() -> {
                assertEquals(2, published.get(NEW_WRITER).timeline(), "The new writer's position is not on timeline 2");
                assertEquals(2, published.get(FOLLOWER).timeline(), "The follower's position is not on timeline 2");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation failed on node pg-node-3",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgPromotionException.class)
    // The same takeover; the follow then waits out its 20-second budget.
    @io.vertx.junit5.Timeout(value = 180, timeUnit = TimeUnit.SECONDS)
    void withoutSlotsOnStandbysAStandbyStoppedBeforeTheTakeoverCannotFollowTheNewWriter(VertxTestContext context) {
        String log = NODES.node(FOLLOWER).logFile(NODES.dataDirectory());
        loseTheWriterWhileTheFollowerIsStopped(false)
            .compose(held -> NODES.control(runner, FOLLOWER).startInRecovery().compose(ignored -> follow(held)
                .transform(follow -> {
                    assertTrue(follow.failed(), "The stopped standby followed although no node kept the WAL it needs");
                    assertInstanceOf(PgPromotionException.class, follow.cause());
                    assertTrue(follow.cause().getMessage().startsWith("WAL receiver is not streaming from " + NEW_WRITER
                        + ":5432 through slot " + PgNodeConfig.slotName(FOLLOWER)), follow.cause().getMessage());
                    return Future.<Void>succeededFuture();
                })))
            .compose(ignored -> NODES.node(FOLLOWER).exec(runner, "grep", "-c", "has already been removed", log))
            .onSuccess(removed -> context.verify(() -> {
                assertNotEquals("0", removed.output().strip(),
                    "The standby did not fail because the new writer had removed the WAL it needs");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    /**
     * The writer and the follower publish their positions and the follower stops. When {@code standbySlots}, the new
     * writer, still a standby, advances its member slots to those positions. The writer then writes 96 MB of WAL, more
     * than {@code wal_keep_size} keeps, and checkpoints; the new writer replays it and makes a restartpoint, which
     * removes the WAL that nothing on it keeps. Then the takeover, which stops the former writer, and the promotion.
     */
    private Future<PgControlRecord> loseTheWriterWhileTheFollowerIsStopped(boolean standbySlots) {
        Future<Void> published = !standbySlots ? Future.succeededFuture()
            : memberStore(WRITER).compose(writerStore -> monitorOf(nodeConfig(WRITER, config.incarnation()),
                    historyElector, writerStore, WRITER, durabilityOf(WRITER, COMMAND_TIMEOUT)).publishPosition())
                .compose(ignored -> followerMonitor.publishPosition());
        return published.compose(ignored -> NODES.node(FOLLOWER).forceStop(runner, NODES.dataDirectory()))
            .compose(ignored -> !standbySlots ? Future.<Void>succeededFuture()
                : positionsOf(NEW_WRITER).read().compose(positions -> awaitReplay(NEW_WRITER, positions.get(FOLLOWER).lsn()))
                    .compose(caughtUp -> monitor.advanceMemberSlots()).map(advanced -> {
                        assertTrue(advanced.contains(FOLLOWER), "The new writer did not advance the follower's slot");
                        return null;
                    }))
            .compose(ignored -> NODES.writeWal(runner, 96))
            .compose(ignored -> NODES.node(WRITER).sql(runner, "select pg_current_wal_flush_lsn()"))
            .compose(flush -> awaitReplay(NEW_WRITER, PgLsn.parse(flush.output().strip())))
            .compose(ignored -> NODES.node(NEW_WRITER).sql(runner, "checkpoint"))
            .compose(ignored -> takeover(STANDBYS, null)).compose(this::promoted);
    }

    private PgMemberPositions positionsOf(String nodeId) {
        var positions = new ConsulMemberPositions(vertx, nodeConfig(nodeId, config.incarnation()), endpoint(), "consul-1", TOKEN);
        positionAdapters.add(positions);
        return positions;
    }

    private Future<PgLocalStateStore> memberStore(String nodeId) {
        Path state = stateDirectory.resolve("member-" + nodeId);
        return vertx.fileSystem().mkdirs(state.toString()).compose(ignored -> PgLocalStateStore.open(vertx, state, nodeId));
    }

    private Future<Void> awaitReplay(String nodeId, PgLsn target) {
        return awaitReplay(nodeId, target, System.nanoTime() + TimeUnit.SECONDS.toNanos(60));
    }

    private Future<Void> awaitReplay(String nodeId, PgLsn target, long deadline) {
        return NODES.node(nodeId).sql(runner, "select pg_last_wal_replay_lsn()").compose(observed -> {
            if (PgLsn.parse(observed.output().strip()).reached(target)) return Future.<Void>succeededFuture();
            if (System.nanoTime() >= deadline) {
                return Future.<Void>failedFuture(new AssertionError(nodeId + " did not replay to " + target));
            }
            return vertx.timer(100).compose(ignored -> awaitReplay(nodeId, target, deadline));
        });
    }

    // ---------------------------------------------------------------- wrapper over the real durability control

    /**
     * Wraps the real durability control. Its coverage wait starts the follower's follow after the wait has
     * begun, and completes only when both the wait and the follow have completed. A failure of either fails it.
     */
    private static final class FollowDuringCoverageWait implements PgDurabilityControl {
        @FunctionalInterface
        interface FollowStart { Future<Void> start(); }
        private final PgDurabilityControl real;
        private final FollowStart follow;
        private volatile boolean followStarted;

        FollowDuringCoverageWait(PgDurabilityControl real, FollowStart follow) {
            this.real = real;
            this.follow = follow;
        }

        boolean followStarted() { return followStarted; }

        @Override public Future<PgLsn> flushBoundary() { return real.flushBoundary(); }

        @Override public Future<List<PgReplicationPeer>> peers() { return real.peers(); }

        @Override public Future<Void> confirmCovered(PgDurabilityPolicy policy) {
            Future<Void> covered = real.confirmCovered(policy);
            followStarted = true;
            return Future.join(covered, follow.start()).mapEmpty();
        }

        @Override public Future<Void> install(PgDurabilityPolicy policy) { return real.install(policy); }

        @Override public Future<Void> ensureSlots(List<String> nodeIds) { return real.ensureSlots(nodeIds); }

        @Override public Future<Void> replaceLostSlot(String nodeId) { return real.replaceLostSlot(nodeId); }

        @Override public Future<PgMemberPosition> position(String nodeId) { return real.position(nodeId); }

        @Override public Future<PgMemberPosition> candidatePosition(String nodeId) { return real.candidatePosition(nodeId); }

        @Override public Future<java.util.Set<String>> advanceSlots(java.util.Map<String, PgMemberPosition> positions) {
            return real.advanceSlots(positions);
        }
    }

    // ---------------------------------------------------------------- helpers

    /** The record is unchanged in the coordinator, no policy was installed, no grant is open, and admission is closed. */
    private Future<Void> assertUnchanged(PgControlRecord held) {
        return observer().read().compose(read -> {
            assertEquals(held, read.orElseThrow(), "A refused or failed cutover changed the control record");
            return assertNoInstallationAndAdmissionClosed(held);
        });
    }

    private Future<Void> assertNoInstallationAndAdmissionClosed(PgControlRecord held) {
        return store.receipt(held.generation(), operationId(held), PgFailoverMonitor.INSTALL_POLICY, NEW_WRITER)
            .compose(receipt -> {
                assertTrue(receipt.isEmpty(), "A policy was installed although the cutover did not reach installation");
                return store.grant();
            }).compose(grant -> {
                assertTrue(grant.filter(found -> found.state() != PgGrantState.CLOSED).isEmpty(),
                    "A grant was prepared or opened");
                return NODES.node(NEW_WRITER).applicationSql(runner, "select 1");
            }).map(application -> {
                assertNotEquals(0, application.exitCode(), "Admission opened");
                return null;
            });
    }

    /** Promotes the new writer under its own supervisor, with admission closed (P1). */
    private Future<PgControlRecord> promoted(PgControlRecord held) {
        return monitor.promoteStandby(held).map(held);
    }

    /** The surviving standby's own supervisor points it at the new writer (P2). */
    private Future<Void> follow(PgControlRecord observed) {
        return followerMonitor.followWriter(observed, NODES.endpoint(NEW_WRITER, FOLLOWER));
    }

    /**
     * The former writer confirms revision 1 of the policy for {@code policyStandbys}, optionally proposes
     * {@code carriedPending}, and releases the lease. The former writer is stopped, as it is when its host is
     * lost. The new writer's elector takes the released lease with the takeover intent, which carries both
     * policies unchanged.
     */
    private Future<PgControlRecord> takeover(List<String> policyStandbys, PgDurabilityPolicy carriedPending) {
        JsonObject first = new JsonObject().put("writerNodeId", WRITER).put("phase", "WITHDRAWN")
            .put("operationId", UUID.randomUUID().toString())
            .put("pendingDurabilityPolicy", new PgDurabilityPolicy(1, policyStandbys).toJson());
        return historyElector.createInitialIntent(first).compose(initial -> {
            JsonObject confirmed = initial.intent();
            confirmed.put("durabilityPolicy", confirmed.remove("pendingDurabilityPolicy"));
            return historyElector.update(initial, confirmed);
        }).compose(confirmed -> carriedPending == null ? Future.<PgControlRecord>succeededFuture(confirmed)
            : historyElector.update(confirmed, confirmed.intent().put("pendingDurabilityPolicy", carriedPending.toJson())))
            .compose(historyElector::release)
            .compose(released -> NODES.control(runner, WRITER).stop(Duration.ofSeconds(8)).compose(stopped -> {
                JsonObject history = released.intent();
                JsonObject intent = new JsonObject().put("writerNodeId", NEW_WRITER).put("phase", "WITHDRAWN")
                    .put("operationId", UUID.randomUUID().toString()).put("previousWriterNodeId", WRITER)
                    .put("durabilityPolicy", history.getJsonObject("durabilityPolicy"));
                if (history.containsKey("pendingDurabilityPolicy")) {
                    intent.put("pendingDurabilityPolicy", history.getJsonObject("pendingDurabilityPolicy"));
                }
                return elector.acquireAfterRelease(released, intent);
            }));
    }

    private PgTakeover takeoverOf(PgFailoverMonitor writerMonitor) {
        return new PgTakeover(config, elector, store, writerMonitor);
    }

    private PgFailoverMonitor monitorOf(PgNodeConfig nodeConfig, PgPrimaryElector owner, PgLocalStateStore nodeStore,
                                        String nodeId, PgDurabilityControl durability) {
        var node = NODES.node(nodeId);
        String directory = NODES.dataDirectory();
        var gate = new PgHbaAdmissionGate(vertx, runner, node.prefix(), PgSupervisedContainer.PG_CTL, "psql",
            directory, node.openHba(directory), node.closedHba(directory), COMMAND_TIMEOUT);
        var promotion = new PgSqlPromotionControl(vertx, runner, node.prefix(), PgSupervisedContainer.PG_CTL, "psql",
            directory, COMMAND_TIMEOUT);
        // This host has no watchdog device. The mode is OFF, so it is never used.
        var watchdog = new PgDeviceWatchdog(vertx, stateDirectory.resolve("no-watchdog"),
            stateDirectory.resolve("no-watchdog-timeout"));
        return PgFailoverMonitor.builder().config(nodeConfig).elector(owner).store(nodeStore)
            .process(NODES.control(runner, nodeId)).gate(gate).watchdog(watchdog).durability(durability)
            .promotion(promotion).positions(positionsOf(nodeId)).build();
    }

    private PgDurabilityControl durabilityOf(String nodeId, Duration budget) {
        return new PgSqlDurabilityControl(vertx, runner, NODES.node(nodeId).prefix(), PgSupervisedContainer.PG_CTL,
            "psql", NODES.dataDirectory(), budget);
    }

    private PgPrimaryElector observer() {
        var observer = new PgPrimaryElector(config, new ConsulLeaseCoordinator(vertx, config, endpoint(), "consul-1", TOKEN));
        observers.add(observer);
        return observer;
    }

    /** The ownership budget (TTL minus stop) outlasts one test, so no renewal loop is needed. */
    private static PgNodeConfig nodeConfig(String nodeId, String incarnation) {
        return new PgNodeConfig("takeover-tests", incarnation, nodeId, MEMBERS, Duration.ofSeconds(80),
            Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofSeconds(8), PgWatchdogMode.OFF);
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
