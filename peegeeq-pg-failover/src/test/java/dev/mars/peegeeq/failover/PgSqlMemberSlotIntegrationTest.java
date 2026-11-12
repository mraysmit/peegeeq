package dev.mars.peegeeq.failover;

import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.logging.ExpectedErrorLog;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
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
import org.junit.jupiter.api.parallel.Isolated;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import static dev.mars.peegeeq.failover.PgReplicationFixture.STANDBYS;
import static dev.mars.peegeeq.failover.PgReplicationFixture.WRITER;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A node's WAL position, and member slots on a standby advanced to published positions (plan §9.27, contract 4),
 * through the local socket against a real writer and two real physical standbys that only the test starts.
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Isolated
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class PgSqlMemberSlotIntegrationTest {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(20);
    private static final String STANDBY = STANDBYS.get(0);
    private static final String OTHER = STANDBYS.get(1);
    private static final PgReplicationFixture NODES = new PgReplicationFixture();
    private Vertx vertx;
    private PgCommandRunner runner;

    @BeforeAll static void startNodes() {
        NODES.start();
    }

    @AfterAll static void stopNodes() {
        NODES.close();
    }

    // Provisioning three nodes takes 20 to 35 seconds; the Vert.x context default is 30. The JUnit
    // @Timeout on the class does not set it: the extension reads io.vertx.junit5.Timeout.
    @io.vertx.junit5.Timeout(value = 120, timeUnit = TimeUnit.SECONDS)
    @BeforeEach void setUp(Vertx vertx, VertxTestContext context) {
        this.vertx = vertx;
        runner = new LocalCommandRunner(vertx);
        Future<Void> started = NODES.provision(runner)
            .compose(ignored -> NODES.control(runner, WRITER).startPrimary());
        for (String standby : STANDBYS) {
            started = started.compose(ignored -> NODES.control(runner, standby).startInRecovery());
        }
        started.compose(ignored -> control(WRITER).confirmCovered(new PgDurabilityPolicy(1, STANDBYS)))
            .onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @AfterEach void tearDown(VertxTestContext context) {
        NODES.forceStopAll(runner).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    // ---------------------------------------------------------------- the position a node publishes

    @Test void theWritersPositionIsItsTimelineAndItsFlushPosition(VertxTestContext context) {
        control(WRITER).position(WRITER).compose(position -> NODES.node(WRITER)
            .sql(runner, "select pg_current_wal_flush_lsn()").map(flush -> {
                assertEquals(new PgMemberPosition(WRITER, 1, position.lsn()), position);
                assertTrue(PgLsn.parse(flush.output().strip()).reached(position.lsn()),
                    "The writer's position is after its flush position: " + position);
                return null;
            })).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void aStreamingStandbysPositionIsTheTimelineAndFlushedPositionOfItsReceiver(VertxTestContext context) {
        control(STANDBY).position(STANDBY).compose(position -> NODES.node(STANDBY)
            .sql(runner, "select received_tli || '|' || flushed_lsn from pg_stat_wal_receiver").map(receiver -> {
                assertEquals(STANDBY, position.nodeId());
                assertEquals(receiver.output().strip(), position.timeline() + "|" + position.lsn(),
                    "The standby's position is not its receiver's");
                return null;
            })).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void aStandbyThatIsNotStreamingHasNoPosition(VertxTestContext context) {
        NODES.node(WRITER).forceStop(runner, NODES.dataDirectory())
            .compose(ignored -> awaitNoStreamingReceiver(STANDBY, deadline()))
            .compose(ignored -> control(STANDBY).position(STANDBY))
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgDurabilityException.class, failure);
                assertTrue(failure.getMessage().startsWith("PostgreSQL is in recovery without a streaming WAL receiver"),
                    failure.getMessage());
                context.completeNow();
            })));
    }

    // ---------------------------------------------------------------- advancing member slots on a standby

    @Test void aStandbysMemberSlotsAdvanceToPublishedPositionsCappedAtItsReplayPosition(VertxTestContext context) {
        var standby = control(STANDBY);
        String slot = PgNodeConfig.slotName(OTHER);
        standby.ensureSlots(List.of(WRITER, OTHER))
            .compose(ignored -> restartAndReplay(STANDBY, slot))
            .compose(before -> {
                // Between the slot's reserved position and the standby's replay position, so that the cap does not apply.
                var between = new PgLsn((before.restart().value() + before.replay().value()) / 2);
                var beyond = PgLsn.parse("FFFFFFFF/0");
                return standby.advanceSlots(Map.of(OTHER, new PgMemberPosition(OTHER, 1, between),
                    WRITER, new PgMemberPosition(WRITER, 1, beyond))).compose(advanced -> {
                        assertEquals(Set.of(OTHER, WRITER), advanced);
                        return restartOf(STANDBY, slot).compose(other -> {
                            assertEquals(between, other, "A slot was not advanced to the published position");
                            return restartOf(STANDBY, PgNodeConfig.slotName(WRITER));
                        }).map(writerSlot -> {
                            assertTrue(before.replay().reached(writerSlot) && writerSlot.reached(between),
                                "A position beyond the replay position was not capped at it: " + writerSlot);
                            return null;
                        });
                    });
            }).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void positionsThatCannotAdvanceASlotLeaveEverySlotUnchanged(VertxTestContext context) {
        var standby = control(STANDBY);
        String slot = PgNodeConfig.slotName(OTHER);
        standby.ensureSlots(List.of(OTHER)).compose(ignored -> restartAndReplay(STANDBY, slot)).compose(before -> {
            var behind = new PgLsn(before.restart().value() - 1);
            // Another timeline; a position behind the slot; a member that has no slot on this standby.
            return standby.advanceSlots(Map.of(OTHER, new PgMemberPosition(OTHER, 2, before.replay())))
                .compose(otherTimeline -> {
                    assertEquals(Set.of(), otherTimeline, "A position on another timeline advanced a slot");
                    return standby.advanceSlots(Map.of(OTHER, new PgMemberPosition(OTHER, 1, behind),
                        "pg-node-9", new PgMemberPosition("pg-node-9", 1, before.replay())));
                }).compose(behindOrMissing -> {
                    assertEquals(Set.of(), behindOrMissing, "A position behind the slot, or a missing slot, was advanced");
                    return restartOf(STANDBY, slot);
                }).map(after -> {
                    assertEquals(before.restart(), after, "A slot moved although no position could advance it");
                    return null;
                });
        }).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void anUnreservedSlotIsNotAdvanced(VertxTestContext context) {
        String slot = PgNodeConfig.slotName(OTHER);
        // Created without a reserved position: it has no restart position and no WAL status.
        NODES.node(STANDBY).sql(runner, "select pg_create_physical_replication_slot('" + slot + "')").compose(created -> {
            assertEquals(0, created.exitCode(), created.output());
            return control(STANDBY).advanceSlots(Map.of(OTHER, new PgMemberPosition(OTHER, 1, PgLsn.parse("0/3000000"))));
        }).compose(advanced -> {
            assertEquals(Set.of(), advanced, "An unreserved slot was advanced");
            return NODES.node(STANDBY).sql(runner, "select coalesce(restart_lsn::text, 'none') from pg_replication_slots "
                + "where slot_name = '" + slot + "'");
        }).onSuccess(restart -> context.verify(() -> {
            assertEquals("none", restart.output().strip());
            context.completeNow();
        })).onFailure(context::failNow);
    }

    @Test void aStandbyWithoutAStreamingReceiverAdvancesNothing(VertxTestContext context) {
        var standby = control(STANDBY);
        String slot = PgNodeConfig.slotName(OTHER);
        standby.ensureSlots(List.of(OTHER)).compose(ignored -> restartAndReplay(STANDBY, slot)).compose(before ->
            NODES.node(WRITER).forceStop(runner, NODES.dataDirectory())
                .compose(ignored -> awaitNoStreamingReceiver(STANDBY, deadline()))
                // Without a receiver the standby's timeline is unknown, so no position can be matched to it.
                .compose(ignored -> standby.advanceSlots(Map.of(OTHER, new PgMemberPosition(OTHER, 1, before.replay()))))
                .compose(advanced -> {
                    assertEquals(Set.of(), advanced, "A standby without a timeline advanced a slot");
                    return restartOf(STANDBY, slot);
                }).map(after -> {
                    assertEquals(before.restart(), after);
                    return null;
                })).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void aWriterRefusesToAdvanceItsSlotsByPublishedPositions(VertxTestContext context) {
        control(WRITER).advanceSlots(Map.of(OTHER, new PgMemberPosition(OTHER, 1, PgLsn.parse("0/3000000"))))
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgDurabilityException.class, failure);
                assertTrue(failure.getMessage().startsWith("PostgreSQL is not in recovery"), failure.getMessage());
                context.completeNow();
            })));
    }

    // ---------------------------------------------------------------- the command runner and the budget

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.LocalCommandRunner",
        message = "Command could not run: [/nonexistent/docker",
        messageMatch = ExpectedErrorLog.MessageMatch.PREFIX,
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = IOException.class,
        minOccurrences = 2, maxOccurrences = 2)
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgSqlDurabilityControl",
        message = "Durability command failed: psql",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgProcessControlException.class,
        minOccurrences = 2, maxOccurrences = 2)
    void aCommandThatCannotRunFailsBothOperationsAndIsReported(VertxTestContext context) {
        var broken = control(STANDBY, List.of("/nonexistent/docker"), "psql", COMMAND_TIMEOUT);
        broken.position(STANDBY).transform(position -> cannotRun(position))
            .compose(ignored -> broken.advanceSlots(Map.of()).transform(this::cannotRun))
            .onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void malformedOutputNoResultAndNoBudgetFailBothOperations(VertxTestContext context) {
        // echo exits 0 and prints its arguments, so the output is not an observation.
        var echo = control(STANDBY, NODES.node(STANDBY).prefix(), "echo", COMMAND_TIMEOUT);
        var silent = new PgSqlDurabilityControl(vertx, new NoResultCommandRunner(runner), NODES.node(STANDBY).prefix(),
            PgSupervisedContainer.PG_CTL, "psql", NODES.dataDirectory(), COMMAND_TIMEOUT);
        var hurried = control(STANDBY, NODES.node(STANDBY).prefix(), "psql", Duration.ofMillis(1));
        String malformed = "Malformed durability observation";
        String noResult = "Command returned no result";
        String exhausted = "Durability budget exhausted";
        echo.position(STANDBY).transform(outcome -> refused(outcome, malformed))
            .compose(ignored -> echo.advanceSlots(Map.of()).transform(outcome -> refused(outcome, malformed)))
            .compose(ignored -> silent.position(STANDBY).transform(outcome -> refused(outcome, noResult)))
            .compose(ignored -> silent.advanceSlots(Map.of()).transform(outcome -> refused(outcome, noResult)))
            .compose(ignored -> hurried.position(STANDBY).transform(outcome -> refused(outcome, exhausted)))
            .compose(ignored -> hurried.advanceSlots(Map.of()).transform(outcome -> refused(outcome, exhausted)))
            .onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    // ---------------------------------------------------------------- helpers

    private Future<Void> cannotRun(io.vertx.core.AsyncResult<?> outcome) {
        assertTrue(outcome.failed(), "An operation succeeded although its command could not run");
        assertInstanceOf(PgDurabilityException.class, outcome.cause());
        assertInstanceOf(PgProcessControlException.class, outcome.cause().getCause());
        return Future.succeededFuture();
    }

    /**
     * Asserts that the operation failed with a {@link PgDurabilityException} whose message starts with the prefix,
     * and succeeds. The next step follows through {@code compose}, so a failed assertion ends the chain with its own
     * message.
     */
    private static Future<Void> refused(io.vertx.core.AsyncResult<?> outcome, String messagePrefix) {
        assertTrue(outcome.failed(), "The operation succeeded; expected a failure starting with: " + messagePrefix);
        assertInstanceOf(PgDurabilityException.class, outcome.cause());
        assertTrue(outcome.cause().getMessage().startsWith(messagePrefix), outcome.cause().getMessage());
        return Future.succeededFuture();
    }

    private record RestartAndReplay(PgLsn restart, PgLsn replay) { }

    private Future<RestartAndReplay> restartAndReplay(String nodeId, String slot) {
        return NODES.node(nodeId).sql(runner, "select restart_lsn || '|' || pg_last_wal_replay_lsn() from "
            + "pg_replication_slots where slot_name = '" + slot + "'").map(observed -> {
                String[] parts = observed.output().strip().split("\\|");
                assertEquals(2, parts.length, "No reserved slot " + slot + ": " + observed.output());
                return new RestartAndReplay(PgLsn.parse(parts[0]), PgLsn.parse(parts[1]));
            });
    }

    private Future<PgLsn> restartOf(String nodeId, String slot) {
        return NODES.node(nodeId).sql(runner, "select restart_lsn from pg_replication_slots where slot_name = '"
            + slot + "'").map(observed -> PgLsn.parse(observed.output().strip()));
    }

    private Future<Void> awaitNoStreamingReceiver(String nodeId, long deadline) {
        return NODES.node(nodeId).sql(runner, "select count(*) from pg_stat_wal_receiver where status = 'streaming'")
            .compose(count -> {
                if ("0".equals(count.output().strip())) return Future.<Void>succeededFuture();
                if (System.nanoTime() >= deadline) {
                    return Future.<Void>failedFuture(new AssertionError(nodeId + " kept a streaming WAL receiver"));
                }
                return vertx.timer(100).compose(ignored -> awaitNoStreamingReceiver(nodeId, deadline));
            });
    }

    private static long deadline() {
        return System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    }

    private PgDurabilityControl control(String nodeId) {
        return control(nodeId, NODES.node(nodeId).prefix(), "psql", COMMAND_TIMEOUT);
    }

    private PgDurabilityControl control(String nodeId, List<String> prefix, String psql, Duration timeout) {
        return new PgSqlDurabilityControl(vertx, runner, prefix, PgSupervisedContainer.PG_CTL, psql,
            NODES.dataDirectory(), timeout);
    }
}
