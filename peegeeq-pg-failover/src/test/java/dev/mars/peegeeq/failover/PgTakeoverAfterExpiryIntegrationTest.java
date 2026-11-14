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
 * Takeover after the former writer's lease expired (plan §9.29): a real coordinator whose 10-second lease TTL expires
 * inside a test, a real former writer that either loses its host or loses its link to the coordinator while its own
 * supervisor runs, and a real successor that acquires only after expiry, with no reply from the former writer.
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Isolated
@Timeout(value = 300, unit = TimeUnit.SECONDS)
@io.vertx.junit5.Timeout(value = 300, timeUnit = TimeUnit.SECONDS)
class PgTakeoverAfterExpiryIntegrationTest {
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
    private final List<PgPrimaryElector> observers = new ArrayList<>();
    private final List<PgMemberPositions> positionAdapters = new ArrayList<>();
    private final List<PgRenewalLoop> loops = new ArrayList<>();
    private Vertx vertx;
    private WebClient admin;
    private PgCommandRunner runner;
    private PgNodeConfig formerConfig;
    private PgNodeConfig config;
    private PgNodeConfig followerConfig;
    private ObservedLeaseCoordinator formerCoordinator;
    private PgPrimaryElector formerElector;
    private PgPrimaryElector elector;
    private PgPrimaryElector followerElector;
    private PgLocalStateStore formerStore;
    private PgLocalStateStore store;
    private PgLocalStateStore followerStore;
    private PgFailoverMonitor formerMonitor;
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

    @BeforeEach void setUp(Vertx vertx, VertxTestContext context) {
        this.vertx = vertx;
        admin = WebClient.create(vertx);
        runner = new LocalCommandRunner(vertx);
        String incarnation = "inc-" + UUID.randomUUID();
        formerConfig = nodeConfig(WRITER, incarnation);
        config = nodeConfig(NEW_WRITER, incarnation);
        followerConfig = nodeConfig(FOLLOWER, incarnation);
        // The former writer's coordinator is observed, so a test can cut its renewals off from the coordinator.
        formerCoordinator = new ObservedLeaseCoordinator(
            new ConsulLeaseCoordinator(vertx, formerConfig, endpoint(), "consul-1", TOKEN));
        formerElector = new PgPrimaryElector(formerConfig, formerCoordinator);
        elector = new PgPrimaryElector(config, new ConsulLeaseCoordinator(vertx, config, endpoint(), "consul-1", TOKEN));
        followerElector = new PgPrimaryElector(followerConfig,
            new ConsulLeaseCoordinator(vertx, followerConfig, endpoint(), "consul-1", TOKEN));
        waitForLeader(System.nanoTime() + TimeUnit.SECONDS.toNanos(30))
            .compose(ignored -> NODES.provision(runner))
            .compose(ignored -> NODES.control(runner, WRITER).startPrimary())
            .compose(ignored -> NODES.control(runner, NEW_WRITER).startInRecovery())
            .compose(ignored -> NODES.control(runner, FOLLOWER).startInRecovery())
            .compose(ignored -> durabilityOf(WRITER).confirmCovered(new PgDurabilityPolicy(1, STANDBYS)))
            .compose(ignored -> memberStore(WRITER))
            .compose(opened -> {
                formerStore = opened;
                return memberStore(NEW_WRITER);
            }).compose(opened -> {
                store = opened;
                return memberStore(FOLLOWER);
            }).onSuccess(opened -> context.verify(() -> {
                followerStore = opened;
                formerMonitor = monitorOf(formerConfig, formerElector, formerStore, WRITER);
                monitor = monitorOf(config, elector, store, NEW_WRITER);
                followerMonitor = monitorOf(followerConfig, followerElector, followerStore, FOLLOWER);
                context.completeNow();
            })).onFailure(context::failNow);
    }

    // Every step runs, and every failure fails the test. Future.eventually discards a cleanup failure.
    @AfterEach void tearDown(VertxTestContext context) {
        List<Throwable> failures = new ArrayList<>();
        formerCoordinator.clearHooks();
        Future<Void> closed = Future.succeededFuture();
        for (PgRenewalLoop loop : loops) {
            closed = closed.compose(ignored -> loop.stop().transform(done -> settle(done, failures)));
        }
        closed = closed.compose(ignored -> NODES.forceStopAll(runner).transform(stopped -> settle(stopped, failures)))
            .compose(ignored -> formerElector.close().transform(done -> settle(done, failures)))
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

    // ---------------------------------------------------------------- the former writer is excluded by expiry

    @Test
    void aTakeoverAfterTheFormerWritersHostIsLostServesTheNewWriter(VertxTestContext context) {
        // The host is lost: PostgreSQL stops at once, nothing renews the lease, and nothing releases it.
        formerServing(STANDBYS).compose(former -> NODES.node(WRITER).forceStop(runner, NODES.dataDirectory())
            .compose(stopped -> awaitExpiry(former))
            .compose(expired -> takeoverOf(monitor).acquire(expired, PgFailoverMode.MANUAL)).map(held -> {
                JsonObject intent = held.intent();
                assertEquals(NEW_WRITER, intent.getString("writerNodeId"));
                assertEquals(WRITER, intent.getString("previousWriterNodeId"));
                assertEquals("WITHDRAWN", intent.getString("phase"));
                assertNotEquals(operationId(former), intent.getString("operationId"), "The takeover reused an operation");
                assertEquals(former.intent().getJsonObject("durabilityPolicy"), intent.getJsonObject("durabilityPolicy"),
                    "The takeover did not carry the confirmed policy unchanged");
                assertTrue(held.generation() > former.generation(), "The takeover did not advance the generation");
                return held;
            }))
            .compose(held -> renewing(config, elector, monitor).compose(loop -> monitor.promoteStandby(held)).map(held))
            .compose(held -> follow(held).compose(followed -> takeoverOf(monitor).cutover(held, PgFailoverMode.MANUAL)))
            .onSuccess(serving -> context.verify(() -> {
                assertEquals("SERVING", serving.intent().getString("phase"));
                assertEquals(new PgDurabilityPolicy(2, List.of(FOLLOWER)).toJson(),
                    serving.intent().getJsonObject("durabilityPolicy"));
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
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgRenewalLoop",
        message = "Renewal loop ended on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    void aFormerWriterCutOffFromTheCoordinatorIsStoppedBeforeTheSuccessorAcquires(VertxTestContext context) {
        formerServing(STANDBYS).compose(former -> renewing(formerConfig, formerElector, formerMonitor)
            .compose(formerLoop -> awaitRenewals(2).compose(renewed -> {
                // From now on no renewal of the former writer reaches the coordinator; its PostgreSQL keeps running.
                formerCoordinator.onRenew(held -> Future.failedFuture(
                    new PgLeaseProtocolException("partitioned from the coordinator")));
                Future<Long> stopped = formerLoop.completion().transform(ended -> {
                    assertTrue(ended.failed(), "The former writer's loop ended without a failed renewal");
                    return Future.succeededFuture(System.nanoTime());
                });
                // The successor waits only for expiry; it never waits for the former writer.
                Future<Long> acquired = awaitExpiry(former)
                    .compose(expired -> takeoverOf(monitor).acquire(expired, PgFailoverMode.MANUAL))
                    .map(held -> System.nanoTime());
                return Future.all(stopped, acquired).map(both -> {
                    long stoppedAt = both.<Long>resultAt(0);
                    long acquiredAt = both.<Long>resultAt(1);
                    assertTrue(stoppedAt < acquiredAt, "The successor acquired the lease "
                        + TimeUnit.NANOSECONDS.toMillis(stoppedAt - acquiredAt) + " ms before the former writer stopped");
                    return former;
                });
            })))
            .compose(former -> formerStore.receipt(former.generation(), operationId(former),
                PgFailoverMonitor.STOP_WRITER, WRITER))
            .compose(receipt -> {
                assertEquals(PgActionResult.COMPLETED, receipt.orElseThrow().result(),
                    "The former writer did not record a confirmed stop");
                return NODES.control(runner, WRITER).status();
            }).onSuccess(status -> context.verify(() -> {
                assertEquals(PgProcessState.STOPPED, status, "The former writer's PostgreSQL is still running");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    // ---------------------------------------------------------------- refusals before any coordinator write

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgTakeover",
        message = "Takeover acquisition failed on node pg-node-2",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    void anAcquisitionWhileTheFormerLeaseIsLiveIsRefusedAndALaterOneAfterExpirySucceeds(VertxTestContext context) {
        formerServing(STANDBYS).compose(former -> takeoverOf(monitor).acquire(former, PgFailoverMode.MANUAL)
            .transform(refusal -> {
                assertTrue(refusal.failed(), "A takeover acquired a lease that the former writer still holds");
                assertInstanceOf(PgLeaseProtocolException.class, refusal.cause());
                assertFalse(elector.hasFreshOwnership());
                return observer().read();
            }).compose(read -> {
                assertEquals(former, read.orElseThrow(), "A refused acquisition changed the control record");
                return NODES.node(WRITER).forceStop(runner, NODES.dataDirectory());
            }).compose(stopped -> awaitExpiry(former))
            // The same takeover and elector acquire once the lease has expired: the refusal did not use up the elector.
            .compose(expired -> takeoverOf(monitor).acquire(expired, PgFailoverMode.MANUAL)))
            .onSuccess(held -> context.verify(() -> {
                assertEquals(NEW_WRITER, held.intent().getString("writerNodeId"));
                assertTrue(elector.holds(held));
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgTakeover",
        message = "Takeover acquisition failed on node pg-node-2",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    void aNodeTheConfirmedPolicyDoesNotNameCannotAcquire(VertxTestContext context) {
        // A released record is unowned as an expired one is; the refusal depends only on the observed record.
        formerServing(List.of(FOLLOWER)).compose(formerElector::release)
            .compose(released -> takeoverOf(monitor).acquire(released, PgFailoverMode.MANUAL).transform(refusal -> {
                assertTrue(refusal.failed(), "A node outside the confirmed policy acquired the lease");
                assertInstanceOf(PgLeaseProtocolException.class, refusal.cause());
                return assertUnchanged(released);
            })).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgTakeover",
        message = "Takeover acquisition failed on node pg-node-2",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    void aNodeTheRecordNamesAsWriterCannotTakeItOverAgain(VertxTestContext context) {
        // After a takeover the record names the new writer, and the confirmed policy carried from the former writer
        // still names it until the cutover. When the new writer's lease lapses before the cutover, the record names
        // that node twice: as writer and in the confirmed policy. Only the writer check can refuse it.
        var again = new PgPrimaryElector(config, new ConsulLeaseCoordinator(vertx, config, endpoint(), "consul-1", TOKEN));
        observers.add(again);
        formerServing(STANDBYS).compose(formerElector::release)
            .compose(released -> takeoverOf(monitor).acquire(released, PgFailoverMode.MANUAL))
            .compose(elector::release)
            .compose(lapsed -> {
                assertEquals(NEW_WRITER, lapsed.intent().getString("writerNodeId"));
                assertTrue(lapsed.intent().getJsonObject("durabilityPolicy").getJsonArray("requiredStandbyNodeIds")
                    .contains(NEW_WRITER), "The precondition failed: the carried policy does not name the new writer");
                return new PgTakeover(config, again, store, monitor).acquire(lapsed, PgFailoverMode.MANUAL)
                    .transform(refusal -> {
                        assertTrue(refusal.failed(), "A node took over a record that names it as writer");
                        assertInstanceOf(PgLeaseProtocolException.class, refusal.cause());
                        return assertUnchanged(lapsed);
                    });
            }).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgTakeover",
        message = "Takeover acquisition failed on node pg-node-2",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLocalStateException.class)
    void aQuarantinedNodeCannotAcquire(VertxTestContext context) {
        formerServing(STANDBYS).compose(formerElector::release)
            .compose(released -> store.quarantine("awaiting reconciliation")
                .compose(ignored -> takeoverOf(monitor).acquire(released, PgFailoverMode.MANUAL)).transform(refusal -> {
                    assertTrue(refusal.failed(), "A quarantined node acquired the lease");
                    assertInstanceOf(PgLocalStateException.class, refusal.cause());
                    return assertUnchanged(released);
                })).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    // ---------------------------------------------------------------- candidate ranking in automatic mode (plan §9.28, P7d)

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgTakeover",
        message = "Takeover acquisition failed on node pg-node-2",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    void inAutomaticModeACandidateBehindAnotherDoesNotAcquireAndTheOneAheadDoes(VertxTestContext context) {
        String directory = NODES.dataDirectory();
        // The new writer stops; the writer writes WAL that only the follower receives; the follower publishes it.
        NODES.node(NEW_WRITER).forceStop(runner, directory)
            .compose(ignored -> NODES.writeWal(runner, 16))
            .compose(ignored -> NODES.node(WRITER).sql(runner, "select pg_current_wal_flush_lsn()"))
            .compose(flush -> awaitReplay(FOLLOWER, PgLsn.parse(flush.output().strip())))
            .compose(ignored -> followerMonitor.publishPosition())
            .compose(ignored -> formerServing(STANDBYS))
            .compose(formerElector::release)
            // The former writer is lost; the new writer restarts behind the follower and cannot catch up.
            .compose(released -> NODES.node(WRITER).forceStop(runner, directory)
                .compose(ignored -> NODES.control(runner, NEW_WRITER).startInRecovery())
                .compose(ignored -> takeoverOf(monitor).acquire(released, PgFailoverMode.AUTOMATIC).transform(behind -> {
                    assertTrue(behind.failed(), "A candidate behind another covered candidate acquired the lease");
                    assertInstanceOf(PgLeaseProtocolException.class, behind.cause());
                    assertTrue(behind.cause().getMessage().contains(FOLLOWER), behind.cause().getMessage());
                    return new PgTakeover(followerConfig, followerElector, followerStore, followerMonitor)
                        .acquire(released, PgFailoverMode.AUTOMATIC);
                })))
            .onSuccess(acquired -> context.verify(() -> {
                assertEquals(FOLLOWER, acquired.intent().getString("writerNodeId"));
                assertEquals(WRITER, acquired.intent().getString("previousWriterNodeId"));
                assertFalse(elector.hasFreshOwnership(), "The candidate behind holds the lease");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    // ---------------------------------------------------------------- member tags (plan §9.31)

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgTakeover",
        message = "Takeover acquisition failed on node pg-node-2",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class,
        minOccurrences = 2, maxOccurrences = 2)
    void aNodeTaggedNofailoverOrNosyncCannotAcquire(VertxTestContext context) {
        var noFailover = config.withMemberTags(java.util.Map.of(NEW_WRITER, new PgMemberTags(true, false, false, null)));
        var noSync = config.withMemberTags(java.util.Map.of(NEW_WRITER, new PgMemberTags(false, true, false, null)));
        formerServing(STANDBYS).compose(formerElector::release).compose(released ->
            new PgTakeover(noFailover, elector, store, monitor).acquire(released, PgFailoverMode.MANUAL).transform(first -> {
                assertTrue(first.failed(), "A node tagged nofailover acquired the lease");
                assertInstanceOf(PgLeaseProtocolException.class, first.cause());
                return new PgTakeover(noSync, elector, store, monitor).acquire(released, PgFailoverMode.MANUAL);
            }).transform(second -> {
                assertTrue(second.failed(), "A node tagged nosync acquired the lease");
                assertInstanceOf(PgLeaseProtocolException.class, second.cause());
                return assertUnchanged(released);
            })).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation rejected on node pg-node-2",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    void aNodeTaggedNofailoverIsNotPromoted(VertxTestContext context) {
        // The node acquired before the tag was set; the promotion still refuses it.
        var noFailover = config.withMemberTags(java.util.Map.of(NEW_WRITER, new PgMemberTags(true, false, false, null)));
        var tagged = monitorOf(noFailover, elector, store, NEW_WRITER);
        formerServing(STANDBYS).compose(formerElector::release)
            .compose(released -> takeoverOf(monitor).acquire(released, PgFailoverMode.MANUAL))
            .compose(held -> tagged.promoteStandby(held).transform(promotion -> {
                assertTrue(promotion.failed(), "A node tagged nofailover was promoted");
                assertInstanceOf(PgLeaseProtocolException.class, promotion.cause());
                return NODES.node(NEW_WRITER).sql(runner, "select pg_is_in_recovery()");
            })).onSuccess(recovery -> context.verify(() -> {
                assertEquals("t", recovery.output().strip(), "A refused promotion changed the role");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    void inAutomaticModeARivalTaggedNofailoverIsIgnoredEvenWhenAhead(VertxTestContext context) {
        // As in the automatic test above, the follower is ahead; here the follower is tagged nofailover, so it can
        // never take over, and the node behind it is preferred.
        var rivalTagged = config.withMemberTags(java.util.Map.of(FOLLOWER, new PgMemberTags(true, false, false, null)));
        var taggedMonitor = monitorOf(rivalTagged, elector, store, NEW_WRITER);
        String directory = NODES.dataDirectory();
        NODES.node(NEW_WRITER).forceStop(runner, directory)
            .compose(ignored -> NODES.writeWal(runner, 16))
            .compose(ignored -> NODES.node(WRITER).sql(runner, "select pg_current_wal_flush_lsn()"))
            .compose(flush -> awaitReplay(FOLLOWER, PgLsn.parse(flush.output().strip())))
            .compose(ignored -> followerMonitor.publishPosition())
            .compose(ignored -> formerServing(STANDBYS))
            .compose(formerElector::release)
            .compose(released -> NODES.node(WRITER).forceStop(runner, directory)
                .compose(ignored -> NODES.control(runner, NEW_WRITER).startInRecovery())
                .compose(ignored -> new PgTakeover(rivalTagged, elector, store, taggedMonitor)
                    .acquire(released, PgFailoverMode.AUTOMATIC)))
            .onSuccess(acquired -> context.verify(() -> {
                assertEquals(NEW_WRITER, acquired.intent().getString("writerNodeId"));
                context.completeNow();
            })).onFailure(context::failNow);
    }

    // ---------------------------------------------------------------- helpers

    /** The former writer acquires the lease and confirms revision 1 of the policy for {@code policyStandbys}. */
    private Future<PgControlRecord> formerServing(List<String> policyStandbys) {
        JsonObject first = new JsonObject().put("writerNodeId", WRITER).put("phase", "WITHDRAWN")
            .put("operationId", UUID.randomUUID().toString())
            .put("pendingDurabilityPolicy", new PgDurabilityPolicy(1, policyStandbys).toJson());
        return formerElector.createInitialIntent(first).compose(initial -> {
            JsonObject confirmed = initial.intent();
            confirmed.put("durabilityPolicy", confirmed.remove("pendingDurabilityPolicy"));
            return formerElector.update(initial, confirmed);
        });
    }

    /** Polls the coordinator until the record has no lease holder. Consul can invalidate a session after its TTL. */
    private Future<PgControlRecord> awaitExpiry(PgControlRecord former) {
        return awaitExpiry(observer(), former, System.nanoTime() + TimeUnit.SECONDS.toNanos(60));
    }

    private Future<PgControlRecord> awaitExpiry(PgPrimaryElector reader, PgControlRecord former, long deadline) {
        return reader.read().compose(read -> {
            PgControlRecord current = read.orElseThrow(() -> new AssertionError("The control record disappeared"));
            if (current.leaseHolder() == null) {
                assertEquals(former.generation(), current.generation(), "Another node acquired the lease first");
                return Future.succeededFuture(current);
            }
            if (System.nanoTime() >= deadline) {
                return Future.failedFuture(new AssertionError("The former writer's lease did not expire"));
            }
            return vertx.timer(250).compose(ignored -> awaitExpiry(reader, former, deadline));
        });
    }

    /** Waits until the former writer's coordinator has received {@code count} renewal calls. */
    private Future<Void> awaitRenewals(int count) {
        return awaitRenewals(count, System.nanoTime() + TimeUnit.SECONDS.toNanos(30));
    }

    private Future<Void> awaitRenewals(int count, long deadline) {
        if (formerCoordinator.renewStarts().size() >= count) return Future.succeededFuture();
        if (System.nanoTime() >= deadline) {
            return Future.failedFuture(new AssertionError("The former writer did not renew " + count + " times"));
        }
        return vertx.timer(100).compose(ignored -> awaitRenewals(count, deadline));
    }

    /** Starts a renewal loop directly after acquisition, as {@link PgRenewalLoop} requires. */
    private Future<PgRenewalLoop> renewing(PgNodeConfig nodeConfig, PgPrimaryElector owner, PgFailoverMonitor nodeMonitor) {
        var loop = new PgRenewalLoop(vertx, nodeConfig, owner, nodeMonitor);
        loops.add(loop);
        return loop.start().map(loop);
    }

    /** The record is unchanged in the coordinator, and this node holds no lease. */
    private Future<Void> assertUnchanged(PgControlRecord observed) {
        return observer().read().map(read -> {
            assertEquals(observed, read.orElseThrow(), "A refused acquisition changed the control record");
            assertFalse(elector.hasFreshOwnership(), "A refused acquisition left this node holding the lease");
            return null;
        });
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

    /** The surviving standby's own supervisor points it at the new writer (P2). */
    private Future<Void> follow(PgControlRecord observed) {
        return followerMonitor.followWriter(observed, NODES.endpoint(NEW_WRITER, FOLLOWER));
    }

    private PgTakeover takeoverOf(PgFailoverMonitor writerMonitor) {
        return new PgTakeover(config, elector, store, writerMonitor);
    }

    private Future<PgLocalStateStore> memberStore(String nodeId) {
        Path state = stateDirectory.resolve(nodeId);
        return vertx.fileSystem().mkdirs(state.toString()).compose(ignored -> PgLocalStateStore.open(vertx, state, nodeId));
    }

    private PgFailoverMonitor monitorOf(PgNodeConfig nodeConfig, PgPrimaryElector owner, PgLocalStateStore nodeStore,
                                        String nodeId) {
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
        return PgFailoverMonitor.builder().config(nodeConfig).elector(owner).store(nodeStore)
            .process(NODES.control(runner, nodeId)).gate(gate).watchdog(watchdog).durability(durabilityOf(nodeId))
            .promotion(promotion).positions(positions).build();
    }

    private PgDurabilityControl durabilityOf(String nodeId) {
        return new PgSqlDurabilityControl(vertx, runner, NODES.node(nodeId).prefix(), PgSupervisedContainer.PG_CTL,
            "psql", NODES.dataDirectory(), COMMAND_TIMEOUT);
    }

    private PgPrimaryElector observer() {
        var observer = new PgPrimaryElector(config, new ConsulLeaseCoordinator(vertx, config, endpoint(), "consul-1", TOKEN));
        observers.add(observer);
        return observer;
    }

    /**
     * A 10-second lease TTL, the Consul minimum, so that expiry happens inside a test. Ownership stays fresh for the
     * TTL minus the 5-second stop budget; a renewal loop keeps a holder's ownership fresh.
     */
    private static PgNodeConfig nodeConfig(String nodeId, String incarnation) {
        return new PgNodeConfig("expiry-tests", incarnation, nodeId, MEMBERS, Duration.ofSeconds(10),
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
