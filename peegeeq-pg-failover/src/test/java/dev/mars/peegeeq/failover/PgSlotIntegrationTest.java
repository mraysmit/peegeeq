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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static dev.mars.peegeeq.failover.PgReplicationFixture.STANDBYS;
import static dev.mars.peegeeq.failover.PgReplicationFixture.WRITER;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The writer's member replication slots under the supervisor: a real coordinator quorum member, a real writer and
 * two real physical standbys that only the test starts, and real node-local storage. Provisioning leaves the writer
 * holding each standby's slot, as a deployment does (plan §9.24, contract 7). Application admission stays closed.
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Isolated
@Timeout(value = 300, unit = TimeUnit.SECONDS)
@io.vertx.junit5.Timeout(value = 300, timeUnit = TimeUnit.SECONDS)
class PgSlotIntegrationTest {
    private static final List<String> MEMBERS = List.of("pg-node-1", "pg-node-2", "pg-node-3");
    private static final String STOPPED = "pg-node-3";
    private static final String STANDBY = "pg-node-2";
    private static final String TOKEN = "unused-without-acls";
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(20);
    private static final GenericContainer<?> CONSUL = new GenericContainer<>("hashicorp/consul:1.22.1")
        .withExposedPorts(8500)
        .withCommand("agent", "-dev", "-client=0.0.0.0", "-node=consul-1", "-log-level=warn")
        .waitingFor(Wait.forHttp("/v1/status/leader").forPort(8500).withStartupTimeout(Duration.ofSeconds(60)));
    private static final PgReplicationFixture NODES = new PgReplicationFixture();
    @TempDir Path stateDirectory;
    private final List<PgMemberPositions> positionAdapters = new ArrayList<>();
    private final List<PgPrimaryElector> memberElectors = new ArrayList<>();
    private Vertx vertx;
    private WebClient admin;
    private PgCommandRunner runner;
    private PgNodeConfig config;
    private PgPrimaryElector elector;
    private PgLocalStateStore store;
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
        // The ownership budget (TTL minus stop) outlasts one test, so no renewal loop is needed.
        config = new PgNodeConfig("slot-tests", "inc-" + UUID.randomUUID(), WRITER, MEMBERS, Duration.ofSeconds(80),
            Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofSeconds(8), PgWatchdogMode.OFF);
        elector = new PgPrimaryElector(config, new ConsulLeaseCoordinator(vertx, config, endpoint(), "consul-1", TOKEN));
        waitForLeader(System.nanoTime() + TimeUnit.SECONDS.toNanos(30))
            .compose(ignored -> NODES.provision(runner))
            .compose(ignored -> NODES.control(runner, WRITER).startPrimary())
            .compose(ignored -> NODES.control(runner, STANDBYS.get(0)).startInRecovery())
            .compose(ignored -> NODES.control(runner, STANDBYS.get(1)).startInRecovery())
            .compose(ignored -> writerDurability().confirmCovered(new PgDurabilityPolicy(1, STANDBYS)))
            .compose(ignored -> PgLocalStateStore.open(vertx, stateDirectory, config.nodeId()))
            .onSuccess(opened -> context.verify(() -> {
                store = opened;
                var writer = NODES.node(WRITER);
                String directory = NODES.dataDirectory();
                var gate = new PgHbaAdmissionGate(vertx, runner, writer.prefix(), PgSupervisedContainer.PG_CTL, "psql",
                    directory, writer.openHba(directory), writer.closedHba(directory), COMMAND_TIMEOUT);
                var promotion = new PgSqlPromotionControl(vertx, runner, writer.prefix(), PgSupervisedContainer.PG_CTL,
                    "psql", directory, COMMAND_TIMEOUT);
                // This host has no watchdog device. The mode is OFF, so it is never used.
                var watchdog = new PgDeviceWatchdog(vertx, stateDirectory.resolve("no-watchdog"),
                    stateDirectory.resolve("no-watchdog-timeout"));
                monitor = PgFailoverMonitor.builder().config(config).elector(elector).store(store)
                    .process(NODES.control(runner, WRITER)).gate(gate).watchdog(watchdog).durability(writerDurability())
                    .promotion(promotion).positions(positionsFor(WRITER)).build();
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @AfterEach void tearDown(VertxTestContext context) {
        TestCleanup.run(() -> NODES.forceStopAll(runner), () -> elector.close(),
            () -> Future.join(memberElectors.stream().map(PgPrimaryElector::close).toList()),
            () -> Future.join(positionAdapters.stream().map(PgMemberPositions::close).toList()), () -> {
            admin.close();
            return Future.<Void>succeededFuture();
        }).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void aMissingMemberSlotIsCreatedAndAnExistingOneIsKeptInUse(VertxTestContext context) {
        stopAndDropSlot(STOPPED).compose(ignored -> elector.createInitialIntent(intent()))
            .compose(held -> monitor.ensureMemberSlots(held))
            .compose(ignored -> memberSlots())
            .onSuccess(slots -> context.verify(() -> {
                assertEquals("peegeeq_pg_node_2|physical|true|true,peegeeq_pg_node_3|physical|false|true", slots,
                    "The writer must hold a reserved slot for every other member, and keep the one in use");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation rejected on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    void withoutFreshOwnershipNoSlotIsCreated(VertxTestContext context) {
        var unowned = new PgControlRecord(config.controlName(), 2, 2, UUID.randomUUID().toString(), intent());
        stopAndDropSlot(STOPPED).compose(ignored -> monitor.ensureMemberSlots(unowned).transform(refusal -> {
            assertTrue(refusal.failed(), "Slots were ensured without ownership");
            assertInstanceOf(PgLeaseProtocolException.class, refusal.cause());
            return memberSlots();
        })).onSuccess(slots -> context.verify(() -> {
            assertEquals("peegeeq_pg_node_2|physical|true|true", slots, "A refused call created a slot");
            context.completeNow();
        })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation failed on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLocalStateException.class)
    void aQuarantinedNodeCreatesNoSlot(VertxTestContext context) {
        stopAndDropSlot(STOPPED).compose(ignored -> elector.createInitialIntent(intent()))
            .compose(held -> store.quarantine("awaiting rebuild").compose(ignored -> monitor.ensureMemberSlots(held)))
            .transform(refusal -> {
                assertTrue(refusal.failed(), "A quarantined node ensured slots");
                assertInstanceOf(PgLocalStateException.class, refusal.cause());
                return memberSlots();
            }).onSuccess(slots -> context.verify(() -> {
                assertEquals("peegeeq_pg_node_2|physical|true|true", slots, "A refused call created a slot");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    // Writing 96 MB of WAL and the standby's catch-up can exceed the 30-second Vert.x default.
    @io.vertx.junit5.Timeout(value = 120, timeUnit = TimeUnit.SECONDS)
    void aStoppedStandbysSlotKeepsTheWalItNeedsBeyondWalKeepSizeAndTheStandbyResumesStreaming(
            VertxTestContext context) {
        var writer = NODES.node(WRITER);
        String slot = PgNodeConfig.slotName(STOPPED);
        String restart = "select restart_lsn::text from pg_replication_slots where slot_name = '" + slot + "'";
        NODES.node(STOPPED).forceStop(runner, NODES.dataDirectory())
            .compose(ignored -> awaitSlotInactive(slot, System.nanoTime() + TimeUnit.SECONDS.toNanos(30)))
            .compose(ignored -> writer.sql(runner, restart))
            .compose(before -> elector.createInitialIntent(intent())
                .compose(held -> monitor.ensureMemberSlots(held))
                .compose(ignored -> writer.sql(runner, restart))
                .map(after -> {
                    assertEquals(before.output().strip(), after.output().strip(), "An existing slot was moved");
                    return null;
                }))
            // More WAL than wal_keep_size (64 MB) keeps, then a checkpoint, which removes WAL that nothing keeps.
            .compose(ignored -> writer.sql(runner, "create table wal_probe (id int); do $$ declare started pg_lsn := "
                + "pg_current_wal_insert_lsn(); begin while pg_wal_lsn_diff(pg_current_wal_insert_lsn(), started) "
                + "< 96 * 1024 * 1024 loop insert into wal_probe select generate_series(1, 100000); end loop; end $$"))
            .compose(written -> {
                assertEquals(0, written.exitCode(), written.output());
                return writer.sql(runner, "checkpoint");
            }).compose(checkpointed -> {
                assertEquals(0, checkpointed.exitCode(), checkpointed.output());
                return writer.sql(runner, "select wal_status || '|' || (pg_wal_lsn_diff(pg_current_wal_lsn(), "
                    + "restart_lsn) > 64 * 1024 * 1024)::text from pg_replication_slots where slot_name = '" + slot + "'");
            }).compose(kept -> {
                assertEquals("reserved|true", kept.output().strip(),
                    "The slot does not keep WAL beyond what wal_keep_size keeps");
                return NODES.control(runner, STOPPED).startInRecovery();
            })
            // Coverage of a fresh boundary needs the WAL that only the slot kept.
            .compose(ignored -> writerDurability().confirmCovered(new PgDurabilityPolicy(2, List.of(STOPPED))))
            .onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    // ---------------------------------------------------------------- replacing a lost slot for a rebuild (plan §9.26)

    @Test
    // Losing the slot, the rebuild's base backup, and the catch-up can exceed the 30-second Vert.x default.
    @io.vertx.junit5.Timeout(value = 150, timeUnit = TimeUnit.SECONDS)
    void aLostMemberSlotIsReplacedAndTheMemberRebuiltThroughItIsCoveredAgain(VertxTestContext context) {
        var policy = new PgDurabilityPolicy(2, List.of(STOPPED));
        NODES.loseSlot(vertx, runner, STOPPED)
            .compose(ignored -> writerDurability().confirmCovered(policy).transform(coverage -> {
                assertTrue(coverage.failed(), "The precondition failed: coverage held with a lost slot");
                assertTrue(coverage.cause().getMessage().contains("is lost"), coverage.cause().getMessage());
                return elector.createInitialIntent(intent());
            }))
            .compose(held -> monitor.replaceLostMemberSlot(held, STOPPED))
            .compose(ignored -> NODES.rebuildThroughSlot(runner, STOPPED))
            .compose(ignored -> NODES.control(runner, STOPPED).startInRecovery())
            .compose(ignored -> writerDurability().confirmCovered(policy))
            .compose(ignored -> NODES.node(STOPPED).sql(runner, "select status || '|' || slot_name from pg_stat_wal_receiver"))
            .onSuccess(receiver -> context.verify(() -> {
                assertEquals("streaming|" + PgNodeConfig.slotName(STOPPED), receiver.output().strip(),
                    "The rebuilt member does not stream through its replaced slot");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation rejected on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    @io.vertx.junit5.Timeout(value = 120, timeUnit = TimeUnit.SECONDS)
    void withoutFreshOwnershipNoLostSlotIsReplaced(VertxTestContext context) {
        var unowned = new PgControlRecord(config.controlName(), 2, 2, UUID.randomUUID().toString(), intent());
        NODES.loseSlot(vertx, runner, STOPPED)
            .compose(ignored -> monitor.replaceLostMemberSlot(unowned, STOPPED).transform(refusal -> {
                assertTrue(refusal.failed(), "A slot was replaced without ownership");
                assertInstanceOf(PgLeaseProtocolException.class, refusal.cause());
                return walStatus(STOPPED);
            })).onSuccess(status -> context.verify(() -> {
                assertEquals("lost", status, "A refused call replaced the slot");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation failed on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLocalStateException.class)
    @io.vertx.junit5.Timeout(value = 120, timeUnit = TimeUnit.SECONDS)
    void aQuarantinedNodeReplacesNoLostSlot(VertxTestContext context) {
        NODES.loseSlot(vertx, runner, STOPPED).compose(ignored -> elector.createInitialIntent(intent()))
            .compose(held -> store.quarantine("awaiting rebuild")
                .compose(ignored -> monitor.replaceLostMemberSlot(held, STOPPED)))
            .transform(refusal -> {
                assertTrue(refusal.failed(), "A quarantined node replaced a slot");
                assertInstanceOf(PgLocalStateException.class, refusal.cause());
                return walStatus(STOPPED);
            }).onSuccess(status -> context.verify(() -> {
                assertEquals("lost", status, "A refused call replaced the slot");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation rejected on node pg-node-1",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = IllegalArgumentException.class,
        minOccurrences = 2, maxOccurrences = 2)
    void onlyTheSlotOfAnotherMemberCanBeReplaced(VertxTestContext context) {
        elector.createInitialIntent(intent()).compose(held -> monitor.replaceLostMemberSlot(held, WRITER)
            .transform(own -> {
                assertTrue(own.failed(), "The writer replaced a slot for itself");
                assertInstanceOf(IllegalArgumentException.class, own.cause());
                return Future.<Void>succeededFuture();
            }).compose(ignored -> monitor.replaceLostMemberSlot(held, "pg-node-9").transform(stranger -> {
                assertTrue(stranger.failed(), "A slot was replaced for an identity outside the membership");
                assertInstanceOf(IllegalArgumentException.class, stranger.cause());
                return Future.<Void>succeededFuture();
            }))).compose(ignored -> memberSlots())
            .onSuccess(slots -> context.verify(() -> {
                assertEquals("peegeeq_pg_node_2|physical|true|true,peegeeq_pg_node_3|physical|true|true", slots,
                    "A refused call changed a slot");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    // ---------------------------------------------------------------- slots on standbys (plan §9.27)

    @Test
    // Publishing, stopping a standby, and waiting for the other standby's replay can exceed the 30-second default.
    @io.vertx.junit5.Timeout(value = 120, timeUnit = TimeUnit.SECONDS)
    void aStandbyAdvancesItsMemberSlotsToThePositionsTheMembersPublished(VertxTestContext context) {
        memberMonitorOf(WRITER).compose(writer -> writer.monitor().publishPosition())
            .compose(ignored -> memberMonitorOf(STOPPED)).compose(stopped -> stopped.monitor().publishPosition())
            .compose(ignored -> NODES.node(STOPPED).forceStop(runner, NODES.dataDirectory()))
            .compose(ignored -> positionsFor(STANDBY).read()).compose(published -> {
                PgLsn stoppedAt = published.get(STOPPED).lsn();
                return awaitReplay(STANDBY, stoppedAt, System.nanoTime() + TimeUnit.SECONDS.toNanos(30))
                    .compose(ignored -> memberMonitorOf(STANDBY))
                    .compose(standby -> standby.monitor().advanceMemberSlots())
                    .compose(advanced -> {
                        assertEquals(Set.of(WRITER, STOPPED), advanced, "Not every member's slot was advanced");
                        return restartOn(STANDBY, PgNodeConfig.slotName(STOPPED));
                    }).map(restart -> {
                        assertEquals(stoppedAt, restart,
                            "The stopped member's slot on the standby is not at the position it published");
                        return null;
                    });
            }).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation failed on node pg-node-2",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLocalStateException.class,
        minOccurrences = 2, maxOccurrences = 2)
    void aQuarantinedNodeNeitherPublishesNorAdvances(VertxTestContext context) {
        memberMonitorOf(STANDBY).compose(standby -> standby.store().quarantine("diverged history")
            .compose(ignored -> standby.monitor().publishPosition().transform(publish -> {
                assertTrue(publish.failed(), "A quarantined node published its position");
                assertInstanceOf(PgLocalStateException.class, publish.cause());
                return Future.<Void>succeededFuture();
            })).compose(ignored -> standby.monitor().advanceMemberSlots().transform(advance -> {
                assertTrue(advance.failed(), "A quarantined node advanced its slots");
                assertInstanceOf(PgLocalStateException.class, advance.cause());
                return Future.<Void>succeededFuture();
            }))).compose(ignored -> positionsFor(WRITER).read()).compose(published -> {
                assertFalse(published.containsKey(STANDBY), "A refused publication stored a position");
                return NODES.node(STANDBY).sql(runner, "select count(*) from pg_replication_slots");
            }).onSuccess(slots -> context.verify(() -> {
                assertEquals("0", slots.output().strip(), "A refused advance created a slot");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgFailoverMonitor",
        message = "Local supervision operation failed on node pg-node-2",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgMemberPositionException.class,
        minOccurrences = 2, maxOccurrences = 2)
    void aCoordinatorThatCannotBeReachedFailsBothStepsAndIsReported(VertxTestContext context) {
        var unreachable = track(new ConsulMemberPositions(vertx, memberConfig(STANDBY), URI.create("http://127.0.0.1:1"),
            "consul-1", TOKEN));
        memberMonitorOf(STANDBY, unreachable).map(MemberMonitorOf::monitor)
            .compose(standby -> standby.publishPosition().transform(publish -> {
                assertTrue(publish.failed(), "A publication to an unreachable coordinator was reported as stored");
                assertInstanceOf(PgMemberPositionException.class, publish.cause());
                return Future.<Void>succeededFuture();
            }).compose(ignored -> standby.advanceMemberSlots().transform(advance -> {
                assertTrue(advance.failed(), "Slots were advanced without readable positions");
                assertInstanceOf(PgMemberPositionException.class, advance.cause());
                return Future.<Void>succeededFuture();
            }))).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    // ---------------------------------------------------------------- helpers

    private record MemberMonitorOf(PgLocalStateStore store, PgFailoverMonitor monitor) { }

    private Future<MemberMonitorOf> memberMonitorOf(String nodeId) {
        return memberMonitorOf(nodeId, positionsFor(nodeId));
    }

    private Future<MemberMonitorOf> memberMonitorOf(String nodeId, PgMemberPositions positions) {
        return memberStore(nodeId).map(nodeStore -> new MemberMonitorOf(nodeStore,
            memberMonitor(nodeId, nodeStore, positions)));
    }

    /** A member's own supervisor. Publication and slot advancing need no lease, so its elector never acquires one. */
    private PgFailoverMonitor memberMonitor(String nodeId, PgLocalStateStore nodeStore, PgMemberPositions positions) {
        var node = NODES.node(nodeId);
        String directory = NODES.dataDirectory();
        var nodeConfig = memberConfig(nodeId);
        var nodeElector = new PgPrimaryElector(nodeConfig,
            new ConsulLeaseCoordinator(vertx, nodeConfig, endpoint(), "consul-1", TOKEN));
        memberElectors.add(nodeElector);
        return PgFailoverMonitor.builder().config(nodeConfig).elector(nodeElector).store(nodeStore)
            .process(NODES.control(runner, nodeId))
            .gate(new PgHbaAdmissionGate(vertx, runner, node.prefix(), PgSupervisedContainer.PG_CTL, "psql", directory,
                node.openHba(directory), node.closedHba(directory), COMMAND_TIMEOUT))
            .watchdog(new PgDeviceWatchdog(vertx, stateDirectory.resolve("no-watchdog"),
                stateDirectory.resolve("no-watchdog-timeout")))
            .durability(durabilityOf(nodeId))
            .promotion(new PgSqlPromotionControl(vertx, runner, node.prefix(), PgSupervisedContainer.PG_CTL, "psql",
                directory, COMMAND_TIMEOUT))
            .positions(positions).build();
    }

    private Future<PgLocalStateStore> memberStore(String nodeId) {
        Path state = stateDirectory.resolve("member-" + nodeId);
        return vertx.fileSystem().mkdirs(state.toString()).compose(ignored -> PgLocalStateStore.open(vertx, state, nodeId));
    }

    private PgMemberPositions positionsFor(String nodeId) {
        return track(new ConsulMemberPositions(vertx, memberConfig(nodeId), endpoint(), "consul-1", TOKEN));
    }

    private PgMemberPositions track(PgMemberPositions positions) {
        positionAdapters.add(positions);
        return positions;
    }

    private PgNodeConfig memberConfig(String nodeId) {
        return new PgNodeConfig("slot-tests", config.incarnation(), nodeId, MEMBERS, Duration.ofSeconds(80),
            Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofSeconds(8), PgWatchdogMode.OFF);
    }

    private PgDurabilityControl durabilityOf(String nodeId) {
        return new PgSqlDurabilityControl(vertx, runner, NODES.node(nodeId).prefix(), PgSupervisedContainer.PG_CTL,
            "psql", NODES.dataDirectory(), COMMAND_TIMEOUT);
    }

    private Future<PgLsn> restartOn(String nodeId, String slot) {
        return NODES.node(nodeId).sql(runner, "select restart_lsn from pg_replication_slots where slot_name = '"
            + slot + "'").map(observed -> PgLsn.parse(observed.output().strip()));
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

    private Future<String> walStatus(String member) {
        return NODES.node(WRITER).sql(runner, "select coalesce(wal_status, 'none') from pg_replication_slots where "
            + "slot_name = '" + PgNodeConfig.slotName(member) + "'").map(status -> status.output().strip());
    }

    /** Stops the standby, waits until the writer no longer uses its slot, and drops the slot. */
    private Future<Void> stopAndDropSlot(String standby) {
        String slot = PgNodeConfig.slotName(standby);
        return NODES.node(standby).forceStop(runner, NODES.dataDirectory())
            .compose(ignored -> awaitSlotInactive(slot, System.nanoTime() + TimeUnit.SECONDS.toNanos(30)))
            .compose(ignored -> NODES.node(WRITER).sql(runner, "select pg_drop_replication_slot('" + slot + "')"))
            .map(dropped -> {
                assertEquals(0, dropped.exitCode(), dropped.output());
                return null;
            });
    }

    private Future<Void> awaitSlotInactive(String slot, long deadline) {
        return NODES.node(WRITER).sql(runner, "select active::text from pg_replication_slots where slot_name = '"
            + slot + "'").compose(active -> {
                if ("false".equals(active.output().strip())) return Future.<Void>succeededFuture();
                if (System.nanoTime() >= deadline) {
                    return Future.<Void>failedFuture(new AssertionError("Slot " + slot + " stayed in use: "
                        + active.output()));
                }
                return vertx.timer(100).compose(ignored -> awaitSlotInactive(slot, deadline));
            });
    }

    /** Every slot under the member prefix: name, type, whether it is in use, and whether its position is reserved. */
    private Future<String> memberSlots() {
        return NODES.node(WRITER).sql(runner, "select string_agg(slot_name || '|' || slot_type || '|' || active::text "
            + "|| '|' || (restart_lsn is not null)::text, ',' order by slot_name) from pg_replication_slots "
            + "where slot_name like 'peegeeq%'").map(slots -> {
                assertEquals(0, slots.exitCode(), slots.output());
                return slots.output().strip();
            });
    }

    private PgDurabilityControl writerDurability() {
        return new PgSqlDurabilityControl(vertx, runner, NODES.node(WRITER).prefix(), PgSupervisedContainer.PG_CTL,
            "psql", NODES.dataDirectory(), COMMAND_TIMEOUT);
    }

    private static JsonObject intent() {
        return new JsonObject().put("writerNodeId", WRITER).put("phase", "WITHDRAWN")
            .put("operationId", UUID.randomUUID().toString())
            .put("pendingDurabilityPolicy", new PgDurabilityPolicy(1, STANDBYS).toJson());
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
