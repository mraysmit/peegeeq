package dev.mars.peegeeq.failover;

import dev.mars.peegeeq.failover.consul.ConsulLeaseCoordinator;
import dev.mars.peegeeq.failover.consul.ConsulMemberPositions;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.logging.ExpectedErrorLog;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
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
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static dev.mars.peegeeq.failover.PgReplicationFixture.MEMBERS;
import static dev.mars.peegeeq.failover.PgReplicationFixture.STANDBYS;
import static dev.mars.peegeeq.failover.PgReplicationFixture.WRITER;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The re-join (plan §9.32, contract 6) under the member's own supervisor: a real writer, two real standbys, a real
 * takeover in which the former writer diverges and is then stopped as a host loss stops it, and real node-local
 * storage. The re-joining node holds no lease, so the record it observes is supplied as the coordinator returns it;
 * the elector and the positions adapter are never called and point at an address with nothing behind it.
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Isolated
@Timeout(value = 300, unit = TimeUnit.SECONDS)
@io.vertx.junit5.Timeout(value = 300, timeUnit = TimeUnit.SECONDS)
class PgRejoinIntegrationTest {
    private static final URI NOTHING = URI.create("http://127.0.0.1:1");
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration BUDGET = Duration.ofSeconds(60);
    private static final String NEW_WRITER = STANDBYS.get(0);
    private static final String FOLLOWER = STANDBYS.get(1);
    private static final PgReplicationFixture NODES = new PgReplicationFixture();
    @TempDir Path stateDirectory;
    private final List<PgPrimaryElector> electors = new ArrayList<>();
    private final List<PgMemberPositions> positionAdapters = new ArrayList<>();
    private Vertx vertx;
    private PgCommandRunner runner;
    private String incarnation;

    @BeforeAll static void startNodes() {
        NODES.start();
    }

    @AfterAll static void stopNodes() {
        NODES.close();
    }

    // Provisioning three nodes takes 20 to 35 seconds; the Vert.x context default is 30.
    @io.vertx.junit5.Timeout(value = 120, timeUnit = TimeUnit.SECONDS)
    @BeforeEach void setUp(Vertx vertx, VertxTestContext context) {
        this.vertx = vertx;
        runner = new LocalCommandRunner(vertx);
        incarnation = "inc-" + UUID.randomUUID();
        Future<Void> started = NODES.provision(runner).compose(ignored -> NODES.control(runner, WRITER).startPrimary());
        for (String standby : STANDBYS) {
            started = started.compose(ignored -> NODES.control(runner, standby).startInRecovery());
        }
        started.compose(ignored -> durability(WRITER).confirmCovered(new PgDurabilityPolicy(1, STANDBYS)))
            .onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    // Every step runs, and every failure fails the test.
    @AfterEach void tearDown(VertxTestContext context) {
        List<Throwable> failures = new ArrayList<>();
        Future<Void> closed = NODES.forceStopAll(runner).transform(stopped -> settle(stopped, failures));
        for (PgPrimaryElector elector : electors) {
            closed = closed.compose(ignored -> elector.close().transform(done -> settle(done, failures)));
        }
        for (PgMemberPositions positions : positionAdapters) {
            closed = closed.compose(ignored -> positions.close().transform(done -> settle(done, failures)));
        }
        closed.onSuccess(ignored -> context.verify(() -> {
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

    // ---------------------------------------------------------------- re-join end to end

    @Test void aCrashStoppedDivergedFormerWriterIsRewoundFollowsCatchesUpAndLeavesQuarantine(VertxTestContext context) {
        var record = serving(NEW_WRITER);
        diverge().compose(ignored -> member(WRITER)).compose(former -> former.monitor()
                .rejoin(record, endpoint(NEW_WRITER, WRITER), BUDGET).map(former))
            .compose(former -> assertRejoined(former, record, true))
            .compose(ignored -> assertHistoryOfTheNewWriter(WRITER))
            .onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Rewind did not make node pg-node-1 a standby of pg-node-2:5432; rebuilding",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgProcessControlException.class)
    void aFormerWriterThatCannotBeRewoundIsRebuiltAndLeavesQuarantine(VertxTestContext context) {
        var record = serving(NEW_WRITER);
        // The former writer's last run had wal_log_hints off, so pg_rewind refuses it (plan §9.32, check 7).
        diverge().compose(ignored -> withoutWalLogHints(WRITER))
            .compose(ignored -> member(WRITER)).compose(node -> node.monitor()
                .rejoin(record, endpoint(NEW_WRITER, WRITER), BUDGET).map(node))
            .compose(node -> assertRejoined(node, record, false).compose(ignored -> receipt(node, record,
                PgFailoverMonitor.REWIND)).map(rewind -> {
                    assertEquals(PgActionResult.UNKNOWN, rewind.orElseThrow().result(),
                        "The failed rewind was not recorded as unknown");
                    return null;
                }))
            .compose(ignored -> assertHistoryOfTheNewWriter(WRITER))
            .onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void aMissingDataDirectoryIsRebuiltWithoutARewind(VertxTestContext context) {
        var record = serving(WRITER);
        String directory = NODES.dataDirectory();
        NODES.control(runner, FOLLOWER).stop(COMMAND_TIMEOUT)
            .compose(ignored -> checked(NODES.node(FOLLOWER).exec(runner, "rm", "-rf", directory)))
            .compose(ignored -> member(FOLLOWER)).compose(node -> node.monitor()
                .rejoin(record, endpoint(WRITER, FOLLOWER), BUDGET).map(node))
            .compose(node -> assertRejoined(node, record, false).compose(ignored -> receipt(node, record,
                PgFailoverMonitor.REWIND)).map(rewind -> {
                    assertTrue(rewind.isEmpty(), "A node without a data directory was rewound");
                    return null;
                }))
            .onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Rewind did not make node pg-node-1 a standby of pg-node-2:5432; rebuilding",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgPromotionException.class)
    void aRewoundNodeThatDoesNotFollowIsRebuilt(VertxTestContext context) {
        var record = serving(NEW_WRITER);
        diverge().compose(ignored -> member(WRITER, real -> new FirstFollowFailsControl(real, new AtomicInteger(),
                nodeId -> Future.<Void>succeededFuture())))
            .compose(node -> node.monitor().rejoin(record, endpoint(NEW_WRITER, WRITER), BUDGET).map(node))
            .compose(node -> receipt(node, record, PgFailoverMonitor.REWIND).compose(rewind -> {
                assertEquals(PgActionResult.COMPLETED, rewind.orElseThrow().result(), "The rewind did not complete");
                return assertRejoined(node, record, false);
            }))
            .compose(ignored -> assertHistoryOfTheNewWriter(WRITER))
            .onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
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
    void aStorageRefusalAfterTheRewindFailsWithoutARebuild(VertxTestContext context) {
        // A follow receipt for another writer address refuses the follow after the rewind. A rebuild cannot cure a store
        // refusal, so no data directory is removed for it.
        var record = serving(NEW_WRITER);
        var other = new JsonObject().put("host", "pg-elsewhere").put("port", 5432);
        diverge().compose(ignored -> member(WRITER)).compose(node -> node.store()
            .begin(record.generation(), record.intent().getString("operationId"), PgFailoverMonitor.FOLLOW_WRITER, WRITER,
                other)
            .compose(begun -> node.monitor().rejoin(record, endpoint(NEW_WRITER, WRITER), BUDGET))
            .transform(rejoin -> refused(rejoin, PgLocalStateException.class))
            .compose(ignored -> receipt(node, record, PgFailoverMonitor.REWIND)).compose(rewind -> {
                assertEquals(PgActionResult.COMPLETED, rewind.orElseThrow().result(), "The rewind did not complete");
                return receipt(node, record, PgFailoverMonitor.REBUILD);
            }).compose(rebuild -> {
                assertTrue(rebuild.isEmpty(), "A store refusal led to a rebuild");
                return node.store().quarantine();
            }).map(quarantine -> {
                assertEquals(Optional.of(PgFailoverMonitor.REJOIN_QUARANTINE), quarantine.map(PgQuarantine::reason),
                    "A failed re-join did not keep its quarantine");
                return null;
            })).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation failed on node pg-node-3",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgPromotionException.class)
    void aStandbyThatIsNotConfirmedCaughtUpKeepsTheQuarantineAndFailsTheCall(VertxTestContext context) {
        String directory = NODES.dataDirectory();
        var record = serving(WRITER);
        NODES.control(runner, FOLLOWER).stop(COMMAND_TIMEOUT)
            .compose(ignored -> checked(NODES.node(FOLLOWER).exec(runner, "rm", "-rf", directory)))
            .compose(ignored -> member(FOLLOWER, CatchUpNotConfirmedControl::new))
            .compose(node -> node.monitor().rejoin(record, endpoint(WRITER, FOLLOWER), BUDGET).transform(rejoin -> {
                refusal(rejoin, PgPromotionException.class);
                assertEquals(CatchUpNotConfirmedControl.FAILURE, rejoin.cause().getMessage());
                return node.store().quarantine().compose(quarantine -> {
                    assertEquals(Optional.of(PgFailoverMonitor.REJOIN_QUARANTINE),
                        quarantine.map(PgQuarantine::reason), "A standby that did not catch up left the quarantine");
                    return receipt(node, record, PgFailoverMonitor.FOLLOW_WRITER);
                }).map(follow -> {
                    assertEquals(PgActionResult.COMPLETED, follow.orElseThrow().result(), "The follow did not complete");
                    return null;
                });
            })).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Rewind did not make node pg-node-1 a standby of pg-node-2:5432; rebuilding",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgPromotionException.class)
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation failed on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgPromotionException.class)
    void aSlotThatTheWriterDropsAfterTheRewindIsRefusedBeforeTheDataDirectoryIsRemoved(VertxTestContext context) {
        // The source is usable when the re-join starts. After the rewind the writer drops this node's slot, and the
        // follow fails. The rebuild observes the source again before it removes anything (plan §9.32, "Danger").
        var record = serving(NEW_WRITER);
        diverge().compose(ignored -> member(WRITER, real -> new FirstFollowFailsControl(real, new AtomicInteger(),
                nodeId -> checked(NODES.node(NEW_WRITER).sql(runner,
                    "select pg_drop_replication_slot('" + PgNodeConfig.slotName(nodeId) + "')")))))
            .compose(node -> node.monitor().rejoin(record, endpoint(NEW_WRITER, WRITER), BUDGET).transform(rejoin -> {
                refusal(rejoin, PgPromotionException.class);
                assertTrue(rejoin.cause().getMessage().contains("missing or lost"), rejoin.cause().getMessage());
                return node.store().quarantine().compose(quarantine -> {
                    assertEquals(Optional.of(PgFailoverMonitor.REJOIN_QUARANTINE),
                        quarantine.map(PgQuarantine::reason), "A refused rebuild did not keep the re-join quarantine");
                    return NODES.control(runner, WRITER).inspect();
                }).compose(directory -> {
                    assertTrue(directory.isPresent(), "The data directory was removed after the source changed");
                    assertEquals(2, directory.orElseThrow().timeline(), "The rewound data directory was replaced");
                    return receipt(node, record, PgFailoverMonitor.REBUILD);
                }).map(rebuild -> {
                    assertTrue(rebuild.isEmpty(), "A rebuild started after the source changed");
                    return null;
                });
            })).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    // ---------------------------------------------------------------- refusals before any effect

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation rejected on node pg-node-3",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class,
        minOccurrences = 2, maxOccurrences = 2)
    void aRecordThatNamesThisNodeOrHasNoLeaseHolderIsRefusedAndChangesNothing(VertxTestContext context) {
        var naming = serving(FOLLOWER);
        var unowned = new PgControlRecord(config(FOLLOWER).controlName(), 2, 2, null, serving(WRITER).intent());
        // Each transform judges its own operation, and the chain continues with compose, so a failed assertion ends the
        // chain with its own message instead of reaching the next judgement as its input (plan §9.26).
        member(FOLLOWER).compose(node -> node.monitor().rejoin(naming, endpoint(WRITER, FOLLOWER), BUDGET)
            .transform(first -> refused(first, PgLeaseProtocolException.class))
            .compose(ignored -> node.monitor().rejoin(unowned, endpoint(WRITER, FOLLOWER), BUDGET)
                .transform(second -> refused(second, PgLeaseProtocolException.class)))
            .compose(ignored -> assertUntouched(node, Optional.empty())))
            .onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation failed on node pg-node-3",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLocalStateException.class)
    void aQuarantineWithAnotherReasonRefusesTheRejoinAndKeepsTheServerRunning(VertxTestContext context) {
        String reason = "Writer stop unconfirmed: lease renewal failed";
        member(FOLLOWER).compose(node -> node.store().quarantine(reason)
            .compose(ignored -> node.monitor().rejoin(serving(WRITER), endpoint(WRITER, FOLLOWER), BUDGET))
            .transform(rejoin -> {
                refusal(rejoin, PgLocalStateException.class);
                return assertUntouched(node, Optional.of(reason));
            })).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    // ---------------------------------------------------------------- source rules: nothing is removed

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation failed on node pg-node-3",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgPromotionException.class)
    void aSourceInRecoveryIsRefusedWithTheDataDirectoryIntact(VertxTestContext context) {
        // The record names the new-writer node, which is still a standby here.
        var record = serving(NEW_WRITER);
        identity(FOLLOWER).compose(before -> member(FOLLOWER).compose(node -> node.monitor()
            .rejoin(record, endpoint(NEW_WRITER, FOLLOWER), BUDGET).transform(rejoin -> {
                refusal(rejoin, PgPromotionException.class);
                assertTrue(rejoin.cause().getMessage().contains("is in recovery"), rejoin.cause().getMessage());
                return assertKeptForRetry(node, record, before);
            })))
            // The source is checked before the stop, so the refusal leaves the running standby running.
            .compose(ignored -> NODES.control(runner, FOLLOWER).status())
            .onSuccess(status -> context.verify(() -> {
                assertEquals(PgProcessState.RUNNING, status, "A re-join refused for its source stopped the server");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation failed on node pg-node-3",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgPromotionException.class)
    void aSlotTheWriterDoesNotHoldIsRefusedWithTheDataDirectoryIntact(VertxTestContext context) {
        var record = serving(WRITER);
        identity(FOLLOWER).compose(before -> NODES.control(runner, FOLLOWER).stop(COMMAND_TIMEOUT)
            .compose(ignored -> checked(NODES.node(WRITER).sql(runner,
                "select pg_drop_replication_slot('" + PgNodeConfig.slotName(FOLLOWER) + "')")))
            .compose(ignored -> member(FOLLOWER)).compose(node -> node.monitor()
                .rejoin(record, endpoint(WRITER, FOLLOWER), BUDGET).transform(rejoin -> {
                    refusal(rejoin, PgPromotionException.class);
                    assertTrue(rejoin.cause().getMessage().contains("slot"), rejoin.cause().getMessage());
                    return assertKeptForRetry(node, record, before);
                }))).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation failed on node pg-node-3",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgPromotionException.class)
    void aDataDirectoryOfAnotherSystemIsLeftForTheOperator(VertxTestContext context) {
        String directory = NODES.dataDirectory();
        var record = serving(WRITER);
        // The follower's data directory is replaced by a new cluster, which has its own system identifier, with the
        // admission files a deployment provisions.
        NODES.control(runner, FOLLOWER).stop(COMMAND_TIMEOUT)
            .compose(ignored -> checked(NODES.node(FOLLOWER).exec(runner, "rm", "-rf", directory)))
            .compose(ignored -> checked(NODES.node(FOLLOWER).exec(runner, "initdb", "-D", directory)))
            .compose(ignored -> NODES.node(FOLLOWER).provisionAdmission(runner, directory))
            .compose(ignored -> NODES.control(runner, FOLLOWER).inspect())
            .compose(other -> member(FOLLOWER).compose(node -> node.monitor()
                .rejoin(record, endpoint(WRITER, FOLLOWER), BUDGET).transform(rejoin -> {
                    refusal(rejoin, PgPromotionException.class);
                    return assertLeftForTheOperator(node, record, other.orElseThrow().systemIdentifier());
                }))).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    // ---------------------------------------------------------------- an unreadable data directory

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation failed on node pg-node-3",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgProcessControlException.class)
    void anUnreadableDataDirectoryOutsideARejoinIsLeftForTheOperator(VertxTestContext context) {
        String directory = NODES.dataDirectory();
        var record = serving(WRITER);
        NODES.control(runner, FOLLOWER).stop(COMMAND_TIMEOUT)
            .compose(ignored -> checked(NODES.node(FOLLOWER).exec(runner, "rm", "-f", directory + "/global/pg_control")))
            .compose(ignored -> member(FOLLOWER)).compose(node -> node.monitor()
                .rejoin(record, endpoint(WRITER, FOLLOWER), BUDGET).transform(rejoin -> {
                    refusal(rejoin, PgProcessControlException.class);
                    return node.store().quarantine().compose(quarantine -> {
                        assertTrue(quarantine.orElseThrow().reason().startsWith(PgFailoverMonitor.OPERATOR_QUARANTINE),
                            "An unreadable directory was not left for the operator: " + quarantine);
                        return NODES.node(FOLLOWER).exec(runner, "test", "-e", directory + "/PG_VERSION");
                    }).compose(kept -> {
                        assertEquals(0, kept.exitCode(), "The unreadable data directory was removed");
                        return receipt(node, record, PgFailoverMonitor.REBUILD);
                    }).map(rebuild -> {
                        assertTrue(rebuild.isEmpty(), "An unreadable directory outside a re-join was rebuilt");
                        return null;
                    });
                })).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "The data directory of node pg-node-3 cannot be read during an interrupted re-join; rebuilding",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgProcessControlException.class)
    void anUnreadableDataDirectoryUnderTheRejoinQuarantineIsRebuilt(VertxTestContext context) {
        String directory = NODES.dataDirectory();
        var record = serving(WRITER);
        // An interrupted rebuild leaves a directory without a control file, under the re-join quarantine.
        NODES.control(runner, FOLLOWER).stop(COMMAND_TIMEOUT)
            .compose(ignored -> checked(NODES.node(FOLLOWER).exec(runner, "rm", "-f", directory + "/global/pg_control")))
            .compose(ignored -> member(FOLLOWER)).compose(node -> node.store()
                .quarantine(PgFailoverMonitor.REJOIN_QUARANTINE)
                .compose(ignored -> node.monitor().rejoin(record, endpoint(WRITER, FOLLOWER), BUDGET)).map(node))
            .compose(node -> assertRejoined(node, record, false))
            .onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation failed on node pg-node-3",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgProcessControlException.class)
    void aFailedRebuildKeepsTheQuarantineAndFailsTheCall(VertxTestContext context) {
        String directory = NODES.dataDirectory();
        var record = serving(WRITER);
        NODES.control(runner, FOLLOWER).stop(COMMAND_TIMEOUT)
            .compose(ignored -> checked(NODES.node(FOLLOWER).exec(runner, "rm", "-rf", directory)))
            .compose(ignored -> memberWithProcess(FOLLOWER, FailingRebuildProcessControl::new))
            .compose(node -> node.monitor().rejoin(record, endpoint(WRITER, FOLLOWER), BUDGET).transform(rejoin -> {
                refusal(rejoin, PgProcessControlException.class);
                return node.store().quarantine().compose(quarantine -> {
                    assertEquals(Optional.of(PgFailoverMonitor.REJOIN_QUARANTINE),
                        quarantine.map(PgQuarantine::reason), "A failed rebuild did not keep the re-join quarantine");
                    return receipt(node, record, PgFailoverMonitor.REBUILD);
                }).map(rebuild -> {
                    assertEquals(PgActionResult.UNKNOWN, rebuild.orElseThrow().result(),
                        "The failed rebuild was not recorded as unknown");
                    return null;
                });
            })).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    // ---------------------------------------------------------------- fault wrappers over the real controls

    /**
     * Wraps the real promotion control. The first follow runs {@code beforeFailing} for the following node, then fails
     * without following; later follows are real.
     */
    private record FirstFollowFailsControl(PgPromotionControl real, AtomicInteger follows,
                                           java.util.function.Function<String, Future<Void>> beforeFailing)
            implements PgPromotionControl {
        @Override public Future<Boolean> inRecovery() { return real.inRecovery(); }

        @Override public Future<Void> promote() { return real.promote(); }

        @Override public Future<Void> checkpoint() { return real.checkpoint(); }

        @Override public Future<Void> follow(String nodeId, PgWriterEndpoint writer) {
            if (follows.getAndIncrement() == 0) {
                return beforeFailing.apply(nodeId).compose(ignored ->
                    Future.<Void>failedFuture(new PgPromotionException("The first follow failed (injected)")));
            }
            return real.follow(nodeId, writer);
        }

        @Override public Future<PgReplicationSource> observeSource(String nodeId, PgWriterEndpoint source) {
            return real.observeSource(nodeId, source);
        }

        @Override public Future<Void> confirmCaughtUp(String nodeId, PgWriterEndpoint writer) {
            return real.confirmCaughtUp(nodeId, writer);
        }
    }

    /** Wraps the real promotion control. The catch-up confirmation fails without observing; everything else is real. */
    private record CatchUpNotConfirmedControl(PgPromotionControl real) implements PgPromotionControl {
        static final String FAILURE = "The catch-up was not confirmed (injected)";

        @Override public Future<Boolean> inRecovery() { return real.inRecovery(); }

        @Override public Future<Void> promote() { return real.promote(); }

        @Override public Future<Void> checkpoint() { return real.checkpoint(); }

        @Override public Future<Void> follow(String nodeId, PgWriterEndpoint writer) { return real.follow(nodeId, writer); }

        @Override public Future<PgReplicationSource> observeSource(String nodeId, PgWriterEndpoint source) {
            return real.observeSource(nodeId, source);
        }

        @Override public Future<Void> confirmCaughtUp(String nodeId, PgWriterEndpoint writer) {
            return Future.failedFuture(new PgPromotionException(FAILURE));
        }
    }

    /** Wraps the real process control. The rebuild fails and has no effect. */
    private record FailingRebuildProcessControl(PgProcessControl real) implements PgProcessControl {
        @Override public Future<PgProcessState> status() { return real.status(); }

        @Override public Future<Void> startInRecovery() { return real.startInRecovery(); }

        @Override public Future<Void> startPrimary() { return real.startPrimary(); }

        @Override public Future<Void> stop(Duration budget) { return real.stop(budget); }

        @Override public Future<Optional<PgDataDirectory>> inspect() { return real.inspect(); }

        @Override public Future<Void> rewind(PgWriterEndpoint source, String nodeId, Duration budget) {
            return real.rewind(source, nodeId, budget);
        }

        @Override public Future<Void> rebuild(PgWriterEndpoint source, String nodeId, Duration budget) {
            return Future.failedFuture(new PgProcessControlException("The rebuild failed (injected)"));
        }
    }

    // ---------------------------------------------------------------- helpers

    /** A member's supervisor: its store and monitor, with real local controls. */
    private record Member(String nodeId, PgLocalStateStore store, PgFailoverMonitor monitor) { }

    private Future<Member> member(String nodeId) {
        return member(nodeId, real -> real);
    }

    private Future<Member> member(String nodeId, java.util.function.UnaryOperator<PgPromotionControl> promotionWrapper) {
        return member(nodeId, promotionWrapper, real -> real);
    }

    private Future<Member> memberWithProcess(String nodeId, java.util.function.UnaryOperator<PgProcessControl> wrapper) {
        return member(nodeId, real -> real, wrapper);
    }

    private Future<Member> member(String nodeId, java.util.function.UnaryOperator<PgPromotionControl> promotionWrapper,
                                  java.util.function.UnaryOperator<PgProcessControl> processWrapper) {
        var config = config(nodeId);
        var node = NODES.node(nodeId);
        String directory = NODES.dataDirectory();
        Path state = stateDirectory.resolve(nodeId);
        var elector = new PgPrimaryElector(config, new ConsulLeaseCoordinator(vertx, config, NOTHING, "consul-1", "token"));
        var positions = new ConsulMemberPositions(vertx, config, NOTHING, "consul-1", "token");
        electors.add(elector);
        positionAdapters.add(positions);
        var gate = new PgHbaAdmissionGate(vertx, runner, node.prefix(), PgSupervisedContainer.PG_CTL, "psql", directory,
            node.openHba(directory), node.closedHba(directory), COMMAND_TIMEOUT);
        // This host has no watchdog device. A re-joining standby never writes, so the mode is never applied.
        var watchdog = new PgDeviceWatchdog(vertx, stateDirectory.resolve("no-watchdog"),
            stateDirectory.resolve("no-watchdog-timeout"));
        return vertx.fileSystem().mkdirs(state.toString()).compose(ignored -> PgLocalStateStore.open(vertx, state, nodeId))
            .map(store -> new Member(nodeId, store, PgFailoverMonitor.builder().config(config).elector(elector)
                .store(store).process(processWrapper.apply(NODES.control(runner, nodeId))).gate(gate).watchdog(watchdog)
                .durability(durability(nodeId)).promotion(promotionWrapper.apply(promotion(nodeId)))
                .positions(positions)
                .configuration(new PgSqlConfigurationControl(vertx, runner, node.prefix(), PgSupervisedContainer.PG_CTL,
                    "psql", directory, COMMAND_TIMEOUT)).build()));
    }

    /** A serving record that names {@code writer}, held under a lease, as the coordinator returns it. */
    private PgControlRecord serving(String writer) {
        var intent = new JsonObject().put("writerNodeId", writer).put("phase", "SERVING")
            .put("operationId", UUID.randomUUID().toString())
            .put("durabilityPolicy", new PgDurabilityPolicy(1, MEMBERS.stream().filter(m -> !m.equals(writer)).toList())
                .toJson());
        return new PgControlRecord(config(writer).controlName(), 2, 2, UUID.randomUUID().toString(), intent);
    }

    /**
     * A takeover in which the former writer diverges: the new writer is promoted through the adapter, takes its
     * checkpoint, and creates the slots of the other members, as {@code promoteStandby} does; the former writer, still
     * writable, writes WAL its successor never receives and is then stopped at once, as a host loss stops it.
     */
    private Future<Void> diverge() {
        String directory = NODES.dataDirectory();
        return promotion(NEW_WRITER).promote().compose(ignored -> promotion(NEW_WRITER).checkpoint())
            .compose(ignored -> durability(NEW_WRITER).ensureSlots(List.of(WRITER, FOLLOWER)))
            .compose(ignored -> checked(NODES.node(WRITER).sql(runner,
                "create table diverged (id int); insert into diverged select generate_series(1, 50000)")))
            .compose(ignored -> NODES.node(WRITER).forceStop(runner, directory))
            .compose(ignored -> checked(NODES.node(NEW_WRITER).sql(runner,
                "create table after_takeover (id int); insert into after_takeover select generate_series(1, 7)")));
    }

    /**
     * One run of the stopped node outside recovery with {@code wal_log_hints} off, so that its control file records the
     * setting, then a fast stop. A server in recovery does not write its parameters to the control file. The control
     * file is read to confirm the precondition.
     */
    private Future<Void> withoutWalLogHints(String nodeId) {
        String directory = NODES.dataDirectory();
        var node = NODES.node(nodeId);
        return checked(node.exec(runner, "rm", "-f", directory + "/standby.signal"))
            .compose(ignored -> checked(node.exec(runner, PgSupervisedContainer.PG_CTL, "start", "-D", directory, "-w",
                "-t", "20", "-l", node.logFile(directory), "-o", "-c wal_log_hints=off")))
            .compose(ignored -> checked(node.exec(runner, PgSupervisedContainer.PG_CTL, "stop", "-D", directory, "-m",
                "fast", "-w")))
            .compose(ignored -> node.exec(runner, "env", "LC_ALL=C", "pg_controldata", "-D", directory))
            .map(control -> {
                assertTrue(control.output().matches("(?s).*wal_log_hints setting:\\s+off.*"),
                    "The precondition failed: the control file does not record wal_log_hints off");
                return null;
            });
    }

    /**
     * The node is a standby that streams from the writer and is caught up, with no quarantine, a closed grant, closed
     * admission, and a completed follow receipt; the rewind or the rebuild receipt is completed.
     */
    private Future<Void> assertRejoined(Member node, PgControlRecord record, boolean rewound) {
        String writer = record.intent().getString("writerNodeId");
        return node.store().quarantine().compose(quarantine -> {
            assertTrue(quarantine.isEmpty(), "The re-joined node is still quarantined: " + quarantine);
            return promotion(node.nodeId()).confirmCaughtUp(node.nodeId(), endpoint(writer, node.nodeId()));
        }).compose(ignored -> receipt(node, record, PgFailoverMonitor.FOLLOW_WRITER)).compose(follow -> {
            assertEquals(PgActionResult.COMPLETED, follow.orElseThrow().result(), "The follow did not complete");
            return receipt(node, record, rewound ? PgFailoverMonitor.REWIND : PgFailoverMonitor.REBUILD);
        }).compose(effect -> {
            assertEquals(PgActionResult.COMPLETED, effect.orElseThrow().result(),
                (rewound ? "The rewind" : "The rebuild") + " did not complete");
            assertEquals(new JsonObject().put("host", writer).put("port", 5432), effect.orElseThrow().parameters());
            return rewound ? receipt(node, record, PgFailoverMonitor.REBUILD) : Future.succeededFuture(Optional.empty());
        }).compose(rebuild -> {
            assertTrue(rebuild.isEmpty(), "A node that was rewound was also rebuilt");
            return node.store().grant();
        }).compose(grant -> {
            assertTrue(grant.filter(open -> open.state() != PgGrantState.CLOSED).isEmpty(), "A re-join opened a grant");
            return NODES.node(node.nodeId()).applicationSql(runner, "select 1");
        }).map(application -> {
            assertNotEquals(0, application.exitCode(), "A re-joined standby admitted an application connection");
            return null;
        });
    }

    /** A refused re-join changed nothing: the server still runs, no receipt exists, and the quarantine is as before. */
    private Future<Void> assertUntouched(Member node, Optional<String> quarantineReason) {
        return node.store().quarantine().compose(quarantine -> {
            assertEquals(quarantineReason, quarantine.map(PgQuarantine::reason), "A refused re-join changed the quarantine");
            return NODES.control(runner, node.nodeId()).status();
        }).map(status -> {
            assertEquals(PgProcessState.RUNNING, status, "A refused re-join stopped the server");
            return null;
        });
    }

    /** Refused by a source rule: the re-join quarantine stays for a retry, the data directory is the same, no effect ran. */
    private Future<Void> assertKeptForRetry(Member node, PgControlRecord record, String systemIdentifier) {
        return node.store().quarantine().compose(quarantine -> {
            assertEquals(Optional.of(PgFailoverMonitor.REJOIN_QUARANTINE), quarantine.map(PgQuarantine::reason),
                "A refused re-join did not keep its own quarantine");
            return NODES.control(runner, node.nodeId()).inspect();
        }).compose(directory -> {
            assertEquals(systemIdentifier, directory.orElseThrow().systemIdentifier(), "The data directory was replaced");
            return receipt(node, record, PgFailoverMonitor.REWIND);
        }).compose(rewind -> {
            assertTrue(rewind.isEmpty(), "A refused re-join rewound the node");
            return receipt(node, record, PgFailoverMonitor.REBUILD);
        }).map(rebuild -> {
            assertTrue(rebuild.isEmpty(), "A refused re-join rebuilt the node");
            return null;
        });
    }

    /** Another system: the operator quarantine replaces the re-join one, nothing is removed, and a rerun is refused. */
    private Future<Void> assertLeftForTheOperator(Member node, PgControlRecord record, String systemIdentifier) {
        return node.store().quarantine().compose(quarantine -> {
            assertTrue(quarantine.orElseThrow().reason().startsWith(PgFailoverMonitor.OPERATOR_QUARANTINE),
                "Another system was not left for the operator: " + quarantine);
            return NODES.control(runner, node.nodeId()).inspect();
        }).compose(directory -> {
            assertEquals(systemIdentifier, directory.orElseThrow().systemIdentifier(), "The data directory was replaced");
            return receipt(node, record, PgFailoverMonitor.REBUILD);
        }).map(rebuild -> {
            assertTrue(rebuild.isEmpty(), "A data directory of another system was rebuilt");
            return null;
        });
    }

    private Future<Optional<PgActionReceipt>> receipt(Member node, PgControlRecord record, String action) {
        return node.store().receipt(record.generation(), record.intent().getString("operationId"), action, node.nodeId());
    }

    /** The node holds the new writer's history: the rows written after the takeover, and none of the diverged ones. */
    private Future<Void> assertHistoryOfTheNewWriter(String nodeId) {
        return NODES.node(nodeId).sql(runner, "select count(*) from after_takeover").compose(after -> {
            assertEquals("7", after.output().strip(), "The node does not hold the new writer's rows: " + after.output());
            return NODES.node(nodeId).sql(runner, "select to_regclass('diverged') is null");
        }).map(diverged -> {
            assertEquals("t", diverged.output().strip(), "The node kept the former writer's diverged table");
            return null;
        });
    }

    private Future<String> identity(String nodeId) {
        return NODES.node(nodeId).sql(runner, "select system_identifier from pg_control_system()")
            .map(result -> result.output().strip());
    }

    private static void refusal(AsyncResult<?> outcome, Class<? extends Throwable> type) {
        assertTrue(outcome.failed(), "A re-join that had to be refused succeeded");
        assertInstanceOf(type, outcome.cause());
    }

    /** As {@link #refusal}, as a step of a chain: it succeeds, so the next step follows through {@code compose}. */
    private static Future<Void> refused(AsyncResult<?> outcome, Class<? extends Throwable> type) {
        refusal(outcome, type);
        return Future.succeededFuture();
    }

    private PgNodeConfig config(String nodeId) {
        return new PgNodeConfig("rejoin-tests", incarnation, nodeId, MEMBERS, Duration.ofSeconds(80),
            Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofSeconds(8), PgWatchdogMode.OFF);
    }

    private PgWriterEndpoint endpoint(String source, String member) {
        return NODES.endpoint(source, member);
    }

    private PgPromotionControl promotion(String nodeId) {
        return new PgSqlPromotionControl(vertx, runner, NODES.node(nodeId).prefix(), PgSupervisedContainer.PG_CTL,
            "psql", NODES.dataDirectory(), COMMAND_TIMEOUT);
    }

    private PgDurabilityControl durability(String nodeId) {
        return new PgSqlDurabilityControl(vertx, runner, NODES.node(nodeId).prefix(), PgSupervisedContainer.PG_CTL,
            "psql", NODES.dataDirectory(), COMMAND_TIMEOUT);
    }

    private static Future<Void> checked(Future<PgCommandResult> command) {
        return command.map(result -> {
            assertEquals(0, result.exitCode(), result.output());
            return null;
        });
    }
}
