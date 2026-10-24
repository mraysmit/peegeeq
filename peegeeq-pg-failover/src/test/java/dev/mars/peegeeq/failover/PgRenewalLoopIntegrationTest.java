package dev.mars.peegeeq.failover;

import dev.mars.peegeeq.failover.consul.ConsulLeaseCoordinator;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.logging.ExpectedErrorLog;
import io.vertx.core.Future;
import io.vertx.core.Promise;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The renewal timer of a node that holds the writer lease: a real coordinator, a real PostgreSQL
 * that only the supervisor starts, and real node-local storage.
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Isolated
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class PgRenewalLoopIntegrationTest {
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
    private ObservedLeaseCoordinator coordinator;
    private PgPrimaryElector elector;
    private PgLocalStateStore store;
    private PgProcessControl process;
    private PgFailoverMonitor monitor;
    private PgRenewalLoop loop;

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
        config = new PgNodeConfig("loop-tests", "inc-" + UUID.randomUUID(), "pg-node-1", MEMBERS,
            Duration.ofSeconds(20), Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofSeconds(8),
            PgWatchdogMode.OFF);
        coordinator = new ObservedLeaseCoordinator(
            new ConsulLeaseCoordinator(vertx, config, endpoint(), "consul-1", TOKEN));
        elector = new PgPrimaryElector(config, coordinator);
        waitForLeader(System.nanoTime() + TimeUnit.SECONDS.toNanos(30))
            .compose(ignored -> POSTGRES.newDataDirectory(runner))
            .compose(created -> {
                dataDirectory = created;
                return POSTGRES.provisionAdmission(runner, created);
            }).compose(ignored -> {
                process = new PgCtlProcessControl(runner, POSTGRES.prefix(), PgSupervisedContainer.PG_CTL,
                    dataDirectory, POSTGRES.logFile(dataDirectory), Duration.ofSeconds(20));
                return PgLocalStateStore.open(vertx, stateDirectory, config.nodeId());
            }).onSuccess(opened -> context.verify(() -> {
                store = opened;
                var gate = new PgHbaAdmissionGate(vertx, runner, POSTGRES.prefix(), PgSupervisedContainer.PG_CTL,
                    "psql", dataDirectory, POSTGRES.openHba(dataDirectory), POSTGRES.closedHba(dataDirectory),
                    Duration.ofSeconds(20));
                // Off mode. This host has no watchdog device; neither path exists.
                var watchdog = new PgDeviceWatchdog(vertx, stateDirectory.resolve("no-watchdog"),
                    stateDirectory.resolve("no-watchdog-timeout"));
                var durability = new PgSqlDurabilityControl(vertx, runner, POSTGRES.prefix(),
                    PgSupervisedContainer.PG_CTL, "psql", dataDirectory, Duration.ofSeconds(20));
                monitor = new PgFailoverMonitor(config, elector, store, process, gate, watchdog, durability);
                loop = new PgRenewalLoop(vertx, config, elector, monitor);
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @AfterEach void tearDown(VertxTestContext context) {
        loop.stop().compose(ignored -> POSTGRES.forceStop(runner, dataDirectory))
            .eventually(() -> elector.close())
            .eventually(() -> {
                admin.close();
                return Future.<Void>succeededFuture();
            }).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void renewalKeepsOwnershipFreshBeyondOneOwnershipBudget(VertxTestContext context) {
        elector.createInitialIntent(initialIntent()).compose(held -> loop.start()
            .compose(ignored -> monitor.startPrimary(held))
            // Without renewal, ownership lapses at the ownership budget: 12 seconds.
            .compose(ignored -> vertx.timer(config.ownershipBudget().toMillis() + 1000))
            .compose(ignored -> process.status())).onSuccess(status -> context.verify(() -> {
                assertTrue(elector.hasFreshOwnership(), "Ownership lapsed although the loop was running");
                assertEquals(PgProcessState.RUNNING, status);
                assertFalse(loop.completion().isComplete());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void renewalsStartNoSoonerThanTheLoopIntervalApart(VertxTestContext context) {
        elector.createInitialIntent(initialIntent()).compose(held -> loop.start()
            .compose(ignored -> awaitRenewals(4, System.nanoTime() + TimeUnit.SECONDS.toNanos(20))))
            .onSuccess(ignored -> context.verify(() -> {
                var starts = coordinator.renewStarts();
                for (int i = 1; i < starts.size(); i++) {
                    // Timers have millisecond resolution; 5 ms is the allowance for the clock difference.
                    assertTrue(starts.get(i) - starts.get(i - 1) >= config.loopInterval().toNanos() - 5_000_000L,
                        "Renewals " + (i - 1) + " and " + i + " started closer than the loop interval");
                }
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
    void lostLeaseStopsTheLoopDemotesTheWriterAndFailsCompletion(VertxTestContext context) {
        elector.createInitialIntent(initialIntent()).compose(held -> loop.start()
            .compose(ignored -> monitor.startPrimary(held))
            .compose(ignored -> deleteRecordExternally())
            .compose(ignored -> loop.completion()).transform(ended -> {
                assertTrue(ended.failed(), "The loop ended without reporting the lost lease");
                assertInstanceOf(PgLeaseProtocolException.class, ended.cause());
                return process.status();
            })).compose(status -> {
                assertEquals(PgProcessState.STOPPED, status);
                assertFalse(elector.hasFreshOwnership());
                return assertNoFurtherRenewals();
            }).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgPrimaryElector",
        message = "Coordinator request rejected",
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
    void aCoordinatorThatThrowsOnRenewalEndsTheLoopAndDemotesTheWriter(VertxTestContext context) {
        elector.createInitialIntent(initialIntent()).compose(held -> monitor.startPrimary(held)
            .compose(ignored -> {
                coordinator.onRenew(asked -> {
                    throw new IllegalStateException("renewal threw");
                });
                return loop.start();
            }).compose(ignored -> loop.completion()).transform(ended -> {
                assertTrue(ended.failed());
                assertInstanceOf(PgLeaseProtocolException.class, ended.cause());
                return process.status();
            })).onSuccess(status -> context.verify(() -> {
                assertEquals(PgProcessState.STOPPED, status);
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgPrimaryElector",
        message = "Coordinator request rejected",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = NullPointerException.class)
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
    void aCoordinatorThatReturnsNoResultOnRenewalEndsTheLoopAndDemotesTheWriter(VertxTestContext context) {
        elector.createInitialIntent(initialIntent()).compose(held -> monitor.startPrimary(held)
            .compose(ignored -> {
                coordinator.onRenew(asked -> null);
                return loop.start();
            }).compose(ignored -> loop.completion()).transform(ended -> {
                assertTrue(ended.failed());
                assertInstanceOf(PgLeaseProtocolException.class, ended.cause());
                return process.status();
            })).onSuccess(status -> context.verify(() -> {
                assertEquals(PgProcessState.STOPPED, status);
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
    void aRenewalThatNeverRepliesEndsTheLoopAtTheRequestDeadline(VertxTestContext context) {
        elector.createInitialIntent(initialIntent()).compose(held -> monitor.startPrimary(held)
            .compose(ignored -> {
                coordinator.onRenew(asked -> Promise.<PgControlRecord>promise().future());
                return loop.start();
            }).compose(ignored -> loop.completion()).transform(ended -> {
                assertTrue(ended.failed());
                assertInstanceOf(PgLeaseProtocolException.class, ended.cause());
                return process.status();
            })).onSuccess(status -> context.verify(() -> {
                assertEquals(PgProcessState.STOPPED, status);
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void stopCancelsFutureRenewalsAndLeavesTheWriterRunning(VertxTestContext context) {
        elector.createInitialIntent(initialIntent()).compose(held -> loop.start()
            .compose(ignored -> monitor.startPrimary(held))
            .compose(ignored -> awaitRenewals(2, System.nanoTime() + TimeUnit.SECONDS.toNanos(20)))
            .compose(ignored -> loop.stop())
            .compose(ignored -> assertNoFurtherRenewals())
            .compose(ignored -> process.status())).onSuccess(status -> context.verify(() -> {
                assertEquals(PgProcessState.RUNNING, status, "Stopping the loop must not stop the writer");
                assertTrue(elector.hasFreshOwnership());
                assertTrue(loop.completion().succeeded());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void stopWaitsForTheRenewalInFlight(VertxTestContext context) {
        Promise<Void> entered = Promise.promise();
        Promise<Void> release = Promise.promise();
        coordinator.onRenew(asked -> {
            entered.tryComplete();
            return release.future().compose(ignored -> coordinator.realRenew(asked));
        });
        elector.createInitialIntent(initialIntent()).compose(held -> loop.start()
            .compose(ignored -> entered.future())
            .compose(ignored -> {
                Future<Void> stopped = loop.stop();
                assertFalse(stopped.isComplete(), "stop() returned while a renewal was in flight");
                release.complete();
                return stopped;
            }).compose(ignored -> assertNoFurtherRenewals()))
            .onSuccess(ignored -> context.verify(() -> {
                assertTrue(elector.hasFreshOwnership(), "The renewal in flight at stop() must complete");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void aRenewalDueDuringAnUpdateWaitsForItWithoutDemotingTheWriter(VertxTestContext context) {
        Promise<Void> entered = Promise.promise();
        Promise<Void> release = Promise.promise();
        elector.createInitialIntent(initialIntent()).compose(held -> loop.start()
            .compose(ignored -> monitor.startPrimary(held))
            .compose(ignored -> {
                coordinator.onUpdate((asked, intent) -> {
                    entered.tryComplete();
                    return release.future().compose(open -> coordinator.realUpdate(asked, intent));
                });
                Future<PgControlRecord> update = elector.update(held, servingIntent(held));
                return entered.future().compose(started -> {
                    int before = coordinator.renewStarts().size();
                    // Three loop intervals pass while the update is held. Ticks are due in this time.
                    return vertx.timer(3 * config.loopInterval().toMillis()).compose(waited -> {
                        assertEquals(before, coordinator.renewStarts().size(),
                            "A renewal reached the coordinator during the update");
                        release.complete();
                        return update.compose(updated -> awaitRenewals(before + 1,
                            System.nanoTime() + TimeUnit.SECONDS.toNanos(20)));
                    });
                });
            }).compose(ignored -> process.status())).onSuccess(status -> context.verify(() -> {
                assertEquals(PgProcessState.RUNNING, status, "A renewal waiting for an update demoted the writer");
                assertTrue(elector.hasFreshOwnership());
                assertFalse(loop.completion().isComplete());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgRenewalLoop",
        message = "Renewal loop start refused on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    void startWithoutOwnershipIsRefused(VertxTestContext context) {
        loop.start().onComplete(context.failing(failure -> context.verify(() -> {
            assertInstanceOf(PgLeaseProtocolException.class, failure);
            assertTrue(coordinator.renewStarts().isEmpty());
            context.completeNow();
        })));
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgRenewalLoop",
        message = "Renewal loop start refused on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    void aSecondStartIsRefused(VertxTestContext context) {
        elector.createInitialIntent(initialIntent()).compose(held -> loop.start()
            .compose(ignored -> loop.start())).onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgLeaseProtocolException.class, failure);
                context.completeNow();
            })));
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgRenewalLoop",
        message = "Renewal loop start refused on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    void aStoppedLoopCannotBeStartedAgain(VertxTestContext context) {
        elector.createInitialIntent(initialIntent()).compose(held -> loop.start()
            .compose(ignored -> loop.stop())
            .compose(ignored -> loop.start())).onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgLeaseProtocolException.class, failure);
                context.completeNow();
            })));
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgRenewalLoop",
        message = "Renewal loop start failed on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = RuntimeException.class)
    void startFailsWhenTheTimerCannotBeScheduled(VertxTestContext context) {
        Vertx closed = Vertx.vertx();
        closed.close().compose(ignored -> elector.createInitialIntent(initialIntent()))
            .compose(held -> new PgRenewalLoop(closed, config, elector, monitor).start())
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(RuntimeException.class, failure);
                assertTrue(coordinator.renewStarts().isEmpty());
                context.completeNow();
            })));
    }

    private Future<Void> awaitRenewals(int count, long deadline) {
        if (coordinator.renewStarts().size() >= count) return Future.succeededFuture();
        if (System.nanoTime() >= deadline) {
            return Future.failedFuture(new AssertionError("Fewer than " + count + " renewals started"));
        }
        return vertx.timer(50).compose(ignored -> awaitRenewals(count, deadline));
    }

    /** Three loop intervals pass and the coordinator receives no renewal. */
    private Future<Void> assertNoFurtherRenewals() {
        int before = coordinator.renewStarts().size();
        return vertx.timer(3 * config.loopInterval().toMillis()).map(ignored -> {
            assertEquals(before, coordinator.renewStarts().size(), "A renewal started after the loop ended");
            return null;
        });
    }

    /** Serving intent: the pending policy becomes the confirmed policy. */
    private static JsonObject servingIntent(PgControlRecord held) {
        JsonObject intent = held.intent();
        JsonObject policy = (JsonObject) intent.remove("pendingDurabilityPolicy");
        return intent.put("phase", "SERVING").put("durabilityPolicy", policy);
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
}
