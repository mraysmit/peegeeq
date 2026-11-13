package dev.mars.peegeeq.failover;

import dev.mars.peegeeq.failover.consul.ConsulLeaseCoordinator;
import dev.mars.peegeeq.failover.consul.ConsulMemberPositions;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.logging.ExpectedErrorLog;
import dev.mars.peegeeq.test.vertx.TestCleanup;
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
 * Candidate ranking (plan §9.28, P7d): a covered standby defers to a covered member that published a position ahead of
 * it. A real coordinator, a real writer and two real physical standbys that only the test starts, and real node-local
 * storage on each standby. No lease is taken: the ranking runs before an acquisition, and eligibility stays the
 * confirmed policy.
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Isolated
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class PgCandidacyIntegrationTest {
    private static final List<String> MEMBERS = List.of("pg-node-1", "pg-node-2", "pg-node-3");
    private static final String AHEAD = "pg-node-2";
    private static final String BEHIND = "pg-node-3";
    private static final String TOKEN = "unused-without-acls";
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(20);
    private static final GenericContainer<?> CONSUL = new GenericContainer<>("hashicorp/consul:1.22.1")
        .withExposedPorts(8500)
        .withCommand("agent", "-dev", "-client=0.0.0.0", "-node=consul-1", "-log-level=warn")
        .waitingFor(Wait.forHttp("/v1/status/leader").forPort(8500).withStartupTimeout(Duration.ofSeconds(60)));
    private static final PgReplicationFixture NODES = new PgReplicationFixture();
    @TempDir Path stateDirectory;
    private final List<PgPrimaryElector> electors = new ArrayList<>();
    private final List<PgMemberPositions> positionAdapters = new ArrayList<>();
    private Vertx vertx;
    private WebClient admin;
    private PgCommandRunner runner;
    private String incarnation;

    @BeforeAll static void startContainers() {
        CONSUL.start();
        NODES.start();
    }

    @AfterAll static void stopContainers() {
        NODES.close();
        CONSUL.stop();
    }

    // Provisioning three nodes and covering both standbys can approach the 30-second Vert.x context default.
    @io.vertx.junit5.Timeout(value = 150, timeUnit = TimeUnit.SECONDS)
    @BeforeEach void setUp(Vertx vertx, VertxTestContext context) {
        this.vertx = vertx;
        admin = WebClient.create(vertx);
        runner = new LocalCommandRunner(vertx);
        incarnation = "inc-" + UUID.randomUUID();
        waitForLeader(System.nanoTime() + TimeUnit.SECONDS.toNanos(30))
            .compose(ignored -> NODES.provision(runner))
            .compose(ignored -> NODES.control(runner, WRITER).startPrimary())
            .compose(ignored -> NODES.control(runner, AHEAD).startInRecovery())
            .compose(ignored -> NODES.control(runner, BEHIND).startInRecovery())
            .compose(ignored -> durabilityOf(WRITER).confirmCovered(new PgDurabilityPolicy(1, STANDBYS)))
            .onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @AfterEach void tearDown(VertxTestContext context) {
        TestCleanup.run(() -> NODES.forceStopAll(runner),
            () -> Future.join(electors.stream().map(PgPrimaryElector::close).toList()),
            () -> Future.join(positionAdapters.stream().map(PgMemberPositions::close).toList()), () -> {
                admin.close();
                return Future.<Void>succeededFuture();
            }).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void twoCandidatesAtTheSamePositionAreBothPreferred(VertxTestContext context) {
        monitorOf(AHEAD).compose(ahead -> monitorOf(BEHIND).compose(behind -> ahead.publishPosition()
            .compose(ignored -> behind.publishPosition())
            .compose(ignored -> NODES.node(WRITER).forceStop(runner, NODES.dataDirectory()))
            .compose(ignored -> ahead.takeoverCandidacy(record(STANDBYS)))
            .compose(first -> behind.takeoverCandidacy(record(STANDBYS)).map(second -> {
                assertTrue(first.preferred(), first.reason());
                assertTrue(second.preferred(), second.reason());
                return null;
            })))).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    // One session TTL passes while the ahead candidate's record expires.
    @io.vertx.junit5.Timeout(value = 120, timeUnit = TimeUnit.SECONDS)
    void aCandidateBehindAnotherDefersUntilThatCandidatesRecordExpires(VertxTestContext context) {
        monitorOf(AHEAD).compose(ahead -> monitorOf(BEHIND).compose(behind -> loseTheWriterWithOneCandidateBehind(ahead,
                behind)
            .compose(ignored -> behind.takeoverCandidacy(record(STANDBYS))).compose(deferred -> {
                assertFalse(deferred.preferred(), "A candidate behind another covered candidate was preferred");
                assertTrue(deferred.reason().contains(AHEAD), deferred.reason());
                return ahead.takeoverCandidacy(record(STANDBYS));
            }).compose(leading -> {
                assertTrue(leading.preferred(), leading.reason());
                // The ahead candidate stops publishing. Its record ends with its session.
                return awaitRecordGone(AHEAD, System.nanoTime() + TimeUnit.SECONDS.toNanos(60));
            }).compose(ignored -> behind.takeoverCandidacy(record(STANDBYS))).map(remaining -> {
                assertTrue(remaining.preferred(), "A member whose record expired still ranked: " + remaining.reason());
                return null;
            }))).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void aCandidateThatPublishedOnAHigherTimelineMakesThisNodeDefer(VertxTestContext context) {
        monitorOf(AHEAD).compose(ahead -> monitorOf(BEHIND).compose(behind ->
            NODES.node(WRITER).forceStop(runner, NODES.dataDirectory())
                // The ahead candidate was promoted already: it publishes as a writer on timeline 2.
                .compose(ignored -> promotionOf(AHEAD).promote())
                .compose(ignored -> ahead.publishPosition())
                .compose(ignored -> behind.takeoverCandidacy(record(STANDBYS))).map(deferred -> {
                    assertFalse(deferred.preferred(), "A candidate on an earlier timeline was preferred");
                    assertTrue(deferred.reason().contains("timeline 2"), deferred.reason());
                    return null;
                }))).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation rejected on node pg-node-2",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    void aNodeThatTheConfirmedPolicyDoesNotNameIsRefused(VertxTestContext context) {
        monitorOf(AHEAD).compose(ahead -> ahead.takeoverCandidacy(record(List.of(BEHIND))))
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgLeaseProtocolException.class, failure);
                context.completeNow();
            })));
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation failed on node pg-node-2",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLocalStateException.class)
    void aQuarantinedNodeIsRefused(VertxTestContext context) {
        storeOf(AHEAD).compose(store -> store.quarantine("diverged history").map(store))
            .map(store -> monitor(AHEAD, store, positionsOf(AHEAD)))
            .compose(ahead -> ahead.takeoverCandidacy(record(STANDBYS)))
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgLocalStateException.class, failure);
                context.completeNow();
            })));
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation failed on node pg-node-2",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgMemberPositionException.class)
    void aCoordinatorThatCannotBeReachedFailsTheCandidacy(VertxTestContext context) {
        var unreachable = track(new ConsulMemberPositions(vertx, nodeConfig(AHEAD), URI.create("http://127.0.0.1:1"),
            "consul-1", TOKEN));
        storeOf(AHEAD).map(store -> monitor(AHEAD, store, unreachable))
            .compose(ahead -> ahead.takeoverCandidacy(record(STANDBYS)))
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgMemberPositionException.class, failure);
                context.completeNow();
            })));
    }

    // ---------------------------------------------------------------- helpers

    /**
     * The behind candidate publishes and stops. The writer writes WAL that the ahead candidate replays, and the ahead
     * candidate publishes. Then the writer is lost and the behind candidate starts again, with no writer to follow.
     */
    private Future<Void> loseTheWriterWithOneCandidateBehind(PgFailoverMonitor ahead, PgFailoverMonitor behind) {
        return behind.publishPosition().compose(ignored -> NODES.node(BEHIND).forceStop(runner, NODES.dataDirectory()))
            .compose(ignored -> NODES.writeWal(runner, 1))
            .compose(ignored -> NODES.node(WRITER).sql(runner, "select pg_current_wal_flush_lsn()"))
            .compose(flush -> awaitReplay(AHEAD, PgLsn.parse(flush.output().strip()),
                System.nanoTime() + TimeUnit.SECONDS.toNanos(30)))
            .compose(ignored -> ahead.publishPosition())
            .compose(ignored -> NODES.node(WRITER).forceStop(runner, NODES.dataDirectory()))
            .compose(ignored -> NODES.control(runner, BEHIND).startInRecovery());
    }

    /** A record whose confirmed policy requires the given standbys; candidacy reads only its policy. */
    private PgControlRecord record(List<String> standbys) {
        var intent = new JsonObject().put("writerNodeId", WRITER).put("phase", "SERVING")
            .put("operationId", UUID.randomUUID().toString())
            .put("durabilityPolicy", new PgDurabilityPolicy(1, standbys).toJson());
        return new PgControlRecord(nodeConfig(WRITER).controlName(), 1, 1, null, intent);
    }

    private Future<PgFailoverMonitor> monitorOf(String nodeId) {
        return storeOf(nodeId).map(store -> monitor(nodeId, store, positionsOf(nodeId)));
    }

    private Future<PgLocalStateStore> storeOf(String nodeId) {
        Path state = stateDirectory.resolve(nodeId);
        return vertx.fileSystem().mkdirs(state.toString()).compose(ignored -> PgLocalStateStore.open(vertx, state, nodeId));
    }

    private PgFailoverMonitor monitor(String nodeId, PgLocalStateStore store, PgMemberPositions positions) {
        var node = NODES.node(nodeId);
        String directory = NODES.dataDirectory();
        var config = nodeConfig(nodeId);
        var elector = new PgPrimaryElector(config, new ConsulLeaseCoordinator(vertx, config, endpoint(), "consul-1", TOKEN));
        electors.add(elector);
        return PgFailoverMonitor.builder().config(config).elector(elector).store(store)
            .process(NODES.control(runner, nodeId))
            .gate(new PgHbaAdmissionGate(vertx, runner, node.prefix(), PgSupervisedContainer.PG_CTL, "psql", directory,
                node.openHba(directory), node.closedHba(directory), COMMAND_TIMEOUT))
            .watchdog(new PgDeviceWatchdog(vertx, stateDirectory.resolve("no-watchdog"),
                stateDirectory.resolve("no-watchdog-timeout")))
            .durability(durabilityOf(nodeId)).promotion(promotionOf(nodeId)).positions(positions).build();
    }

    private PgMemberPositions positionsOf(String nodeId) {
        return track(new ConsulMemberPositions(vertx, nodeConfig(nodeId), endpoint(), "consul-1", TOKEN));
    }

    private PgMemberPositions track(PgMemberPositions positions) {
        positionAdapters.add(positions);
        return positions;
    }

    private PgDurabilityControl durabilityOf(String nodeId) {
        return new PgSqlDurabilityControl(vertx, runner, NODES.node(nodeId).prefix(), PgSupervisedContainer.PG_CTL,
            "psql", NODES.dataDirectory(), COMMAND_TIMEOUT);
    }

    private PgPromotionControl promotionOf(String nodeId) {
        return new PgSqlPromotionControl(vertx, runner, NODES.node(nodeId).prefix(), PgSupervisedContainer.PG_CTL,
            "psql", NODES.dataDirectory(), COMMAND_TIMEOUT);
    }

    /** A lease TTL of ten seconds, the shortest Consul accepts, so a member session expires within a test. */
    private PgNodeConfig nodeConfig(String nodeId) {
        return new PgNodeConfig("candidacy-tests", incarnation, nodeId, MEMBERS, Duration.ofSeconds(10),
            Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofSeconds(2), PgWatchdogMode.OFF);
    }

    private Future<Void> awaitRecordGone(String member, long deadline) {
        return positionsOf(WRITER).read().compose(read -> {
            if (!read.containsKey(member)) return Future.<Void>succeededFuture();
            if (System.nanoTime() >= deadline) {
                return Future.<Void>failedFuture(new AssertionError("The record of " + member + " outlived its session"));
            }
            return vertx.timer(500).compose(ignored -> awaitRecordGone(member, deadline));
        });
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
