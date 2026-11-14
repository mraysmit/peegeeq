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
import java.util.concurrent.TimeUnit;
import static dev.mars.peegeeq.failover.PgReplicationFixture.STANDBYS;
import static dev.mars.peegeeq.failover.PgReplicationFixture.WRITER;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Local promotion, role observation, and the CHECKPOINT after promotion through the local socket,
 * against a real writer and two real physical standbys that only the test starts.
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Isolated
@Timeout(value = 180, unit = TimeUnit.SECONDS)
@io.vertx.junit5.Timeout(value = 180, timeUnit = TimeUnit.SECONDS)
class PgSqlPromotionControlIntegrationTest {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(20);
    private static final String STANDBY = STANDBYS.get(0);
    private static final String FOLLOWER = STANDBYS.get(1);
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
        started.onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @AfterEach void tearDown(VertxTestContext context) {
        NODES.forceStopAll(runner).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void aStandbyIsInRecoveryAndTheWriterIsNot(VertxTestContext context) {
        control(STANDBY).inRecovery().compose(standby -> {
            assertTrue(standby, "A standby was reported as writable");
            return control(WRITER).inRecovery();
        }).onSuccess(writer -> context.verify(() -> {
            assertFalse(writer, "The writer was reported as in recovery");
            context.completeNow();
        })).onFailure(context::failNow);
    }

    @Test void promotingARunningStandbyEndsRecoveryAcceptsWritesAndRemovesTheStartInhibitor(VertxTestContext context) {
        var standby = NODES.node(STANDBY);
        control(STANDBY).promote().compose(ignored -> standby.sql(runner, "select pg_is_in_recovery()"))
            .compose(recovery -> {
                assertEquals("f", recovery.output().strip());
                return standby.sql(runner, "create table promoted_probe (id int)");
            }).compose(created -> {
                assertEquals(0, created.exitCode(), "The promoted server rejected a write: " + created.output());
                return standby.exec(runner, "test", "!", "-e", NODES.dataDirectory() + "/standby.signal");
            }).onSuccess(inhibitor -> context.verify(() -> {
                assertEquals(0, inhibitor.exitCode(), "The start inhibitor is still in place after promotion");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void promotionOfAServerThatIsNotInRecoveryIsRefusedAndChangesNothing(VertxTestContext context) {
        control(WRITER).promote().transform(promotion -> {
            assertTrue(promotion.failed(), "A writable server was promoted");
            assertInstanceOf(PgPromotionException.class, promotion.cause());
            assertTrue(promotion.cause().getMessage().startsWith("PostgreSQL is not in recovery"),
                promotion.cause().getMessage());
            return NODES.node(WRITER).sql(runner, "select pg_is_in_recovery()");
        }).onSuccess(recovery -> context.verify(() -> {
            assertEquals("f", recovery.output().strip());
            context.completeNow();
        })).onFailure(context::failNow);
    }

    @Test void promotionOfAStoppedServerFails(VertxTestContext context) {
        NODES.node(STANDBY).forceStop(runner, NODES.dataDirectory())
            .compose(ignored -> control(STANDBY).promote())
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgPromotionException.class, failure);
                assertTrue(failure.getMessage().startsWith("Local SQL failed with exit"), failure.getMessage());
                context.completeNow();
            })));
    }

    @Test void aPromoteCommandThatReportsSuccessButLeavesTheServerInRecoveryFailsWithinItsBudget(
            VertxTestContext context) {
        var budget = Duration.ofSeconds(5);
        var control = new PgSqlPromotionControl(vertx, new PromoteDoesNotPromoteCommandRunner(runner),
            NODES.node(STANDBY).prefix(), PgSupervisedContainer.PG_CTL, "psql", NODES.dataDirectory(), budget);
        long started = System.nanoTime();
        control.promote().transform(promotion -> {
            assertTrue(promotion.failed(), "A promotion was reported although the role did not change");
            assertInstanceOf(PgPromotionException.class, promotion.cause());
            assertTrue(promotion.cause().getMessage().startsWith("PostgreSQL is still in recovery"),
                promotion.cause().getMessage());
            assertTrue(System.nanoTime() - started < budget.plusSeconds(10).toNanos(), "The wait overran its budget");
            return NODES.node(STANDBY).sql(runner, "select pg_is_in_recovery()");
        }).onSuccess(recovery -> context.verify(() -> {
            assertEquals("t", recovery.output().strip());
            context.completeNow();
        })).onFailure(context::failNow);
    }

    @Test void aPromoteCommandThatExitsWithAFailureFailsThePromotion(VertxTestContext context) {
        // The named program does not exist in the container, so the command exits non-zero.
        control(STANDBY, NODES.node(STANDBY).prefix(), "/nonexistent/pg_ctl", "psql", COMMAND_TIMEOUT).promote()
            .transform(promotion -> {
                assertTrue(promotion.failed(), "A failed promote command was reported as success");
                assertInstanceOf(PgPromotionException.class, promotion.cause());
                assertTrue(promotion.cause().getMessage().startsWith("pg_ctl promote failed with exit"),
                    promotion.cause().getMessage());
                return NODES.node(STANDBY).sql(runner, "select pg_is_in_recovery()");
            }).onSuccess(recovery -> context.verify(() -> {
                assertEquals("t", recovery.output().strip());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void theCheckpointAfterPromotionAdvancesTheCheckpointPositionOnTheNewTimeline(VertxTestContext context) {
        var standby = NODES.node(STANDBY);
        var control = control(STANDBY);
        control.promote().compose(ignored -> standby.sql(runner, "select checkpoint_lsn from pg_control_checkpoint()"))
            .compose(before -> {
                assertEquals(0, before.exitCode(), before.output());
                PgLsn recorded = PgLsn.parse(before.output().strip());
                return control.checkpoint().compose(ignored ->
                    standby.sql(runner, "select checkpoint_lsn from pg_control_checkpoint()")).map(after -> {
                        assertEquals(0, after.exitCode(), after.output());
                        PgLsn current = PgLsn.parse(after.output().strip());
                        assertTrue(current.reached(recorded) && !recorded.reached(current),
                            "The checkpoint position did not advance: " + recorded + " then " + current);
                        return null;
                    });
            }).compose(ignored -> standby.sql(runner, "select timeline_id from pg_control_checkpoint()"))
            .onSuccess(timeline -> context.verify(() -> {
                assertEquals("2", timeline.output().strip(), "The checkpoint is not on the timeline of the promotion");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void aCheckpointOfAServerInRecoveryIsRefused(VertxTestContext context) {
        control(STANDBY).checkpoint().onComplete(context.failing(failure -> context.verify(() -> {
            assertInstanceOf(PgPromotionException.class, failure);
            assertTrue(failure.getMessage().startsWith("PostgreSQL is in recovery"), failure.getMessage());
            context.completeNow();
        })));
    }

    // ---------------------------------------------------------------- following a writer

    @Test void aStandbyFollowsTheNewWriterAndStreamsFromItThroughItsOwnSlot(VertxTestContext context) {
        var follower = NODES.node(FOLLOWER);
        var slot = PgNodeConfig.slotName(FOLLOWER);
        // The writer was promoted through the adapter, so its slot step runs here, as promoteStandby runs it.
        control(STANDBY).promote().compose(ignored -> durability(STANDBY).ensureSlots(List.of(FOLLOWER)))
            .compose(ignored -> control(FOLLOWER).follow(FOLLOWER, writerEndpoint()))
            .compose(ignored -> follower.sql(runner, "select status || '|' || sender_host || '|' || sender_port "
                + "|| '|' || slot_name from pg_stat_wal_receiver"))
            .compose(receiver -> {
                assertEquals("streaming|" + STANDBY + "|5432|" + slot, receiver.output().strip(),
                    "The follower does not stream from the new writer through its own slot");
                return NODES.node(STANDBY).sql(runner, "select count(*) from pg_stat_replication where "
                    + "application_name = '" + FOLLOWER + "' and usename = '" + FOLLOWER + "' and state = 'streaming'");
            }).compose(peer -> {
                assertEquals("1", peer.output().strip(), "The new writer does not see the follower as a peer");
                return NODES.node(STANDBY).sql(runner, "select active::text from pg_replication_slots where "
                    + "slot_name = '" + slot + "'");
            }).compose(active -> {
                assertEquals("true", active.output().strip(), "The new writer's slot for the follower is not in use");
                return follower.sql(runner, "select pg_is_in_recovery()");
            }).onSuccess(recovery -> context.verify(() -> {
                assertEquals("t", recovery.output().strip(), "A follower must stay a standby");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void theInstalledSettingsKeepTheIdentityNameTheSlotAndHoldNoPassword(VertxTestContext context) {
        var follower = NODES.node(FOLLOWER);
        // The base backup wrote the follower's slot name. Removing it shows that following installs it.
        follower.sql(runner, "alter system reset primary_slot_name").compose(reset -> {
            assertEquals(0, reset.exitCode(), reset.output());
            return control(STANDBY).promote();
        }).compose(ignored -> durability(STANDBY).ensureSlots(List.of(FOLLOWER)))
            .compose(ignored -> control(FOLLOWER).follow(FOLLOWER, writerEndpoint()))
            .compose(ignored -> follower.sql(runner, "select setting from pg_settings where name = 'primary_conninfo'"))
            .compose(conninfo -> {
                String installed = conninfo.output().strip();
                assertEquals(writerEndpoint().connectionInfo(FOLLOWER), installed);
                assertFalse(installed.contains("password"), "A password reached the connection string: " + installed);
                return follower.sql(runner, "select setting from pg_settings where name = 'primary_slot_name'");
            }).onSuccess(slot -> context.verify(() -> {
                assertEquals(PgNodeConfig.slotName(FOLLOWER), slot.output().strip(), "Following did not install the slot");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void aServerThatIsNotInRecoveryCannotFollowAndChangesNothing(VertxTestContext context) {
        control(WRITER).follow(WRITER, writerEndpoint()).transform(follow -> {
            assertTrue(follow.failed(), "A writable server was pointed at another writer");
            assertInstanceOf(PgPromotionException.class, follow.cause());
            assertTrue(follow.cause().getMessage().startsWith("PostgreSQL is writable"), follow.cause().getMessage());
            return NODES.node(WRITER).sql(runner, "select count(*) from pg_settings where name in "
                + "('primary_conninfo', 'primary_slot_name') and setting <> ''");
        }).onSuccess(configured -> context.verify(() -> {
            assertEquals("0", configured.output().strip(), "A refused follow installed a connection string or a slot");
            context.completeNow();
        })).onFailure(context::failNow);
    }

    @Test void aWriterWithoutTheFollowersSlotIsNotReportedAsFollowedAndTheWaitEndsWithinItsBudget(
            VertxTestContext context) {
        var budget = Duration.ofSeconds(5);
        var control = control(FOLLOWER, NODES.node(FOLLOWER).prefix(), PgSupervisedContainer.PG_CTL, "psql", budget);
        control(STANDBY).promote().compose(ignored -> {
            long started = System.nanoTime();
            return control.follow(FOLLOWER, writerEndpoint()).transform(follow -> {
                assertTrue(follow.failed(), "A follow was confirmed although the writer has no slot for the follower");
                assertInstanceOf(PgPromotionException.class, follow.cause());
                assertTrue(follow.cause().getMessage().startsWith("WAL receiver is not streaming from " + STANDBY
                    + ":5432 through slot " + PgNodeConfig.slotName(FOLLOWER)), follow.cause().getMessage());
                assertTrue(System.nanoTime() - started < budget.plusSeconds(10).toNanos(), "The wait overran its budget");
                return Future.<Void>succeededFuture();
            });
        }).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void aReceiverObservedBeforeItsReplicationStartsIsNotReportedAsFollowed(VertxTestContext context) {
        // The new writer has no slot for the follower, so START_REPLICATION fails after the receiver has connected.
        // Until it fails, pg_stat_wal_receiver shows streaming with the writer's address and the slot. The wrapper
        // returns the receiver observation from inside that window; a short retry interval makes the window recur.
        var budget = Duration.ofSeconds(5);
        var follower = NODES.node(FOLLOWER);
        var writer = writerEndpoint();
        String slot = PgNodeConfig.slotName(FOLLOWER);
        var windowed = new ReceiverWindowCommandRunner(runner, writer.host(), writer.port(), slot);
        var control = new PgSqlPromotionControl(vertx, windowed, follower.prefix(), PgSupervisedContainer.PG_CTL, "psql",
            NODES.dataDirectory(), budget);
        follower.sql(runner, "alter system set wal_retrieve_retry_interval = '100ms'").compose(set -> {
            assertEquals(0, set.exitCode(), set.output());
            return control(STANDBY).promote();
        }).compose(ignored -> control.follow(FOLLOWER, writer).transform(follow -> {
            assertTrue(follow.failed(), "A receiver that has not started replication through the slot was reported as "
                + "following");
            assertInstanceOf(PgPromotionException.class, follow.cause());
            String message = follow.cause().getMessage();
            assertTrue(message.startsWith("WAL receiver is not streaming from " + writer.host() + ":" + writer.port()
                + " through slot " + slot), message);
            assertTrue(windowed.windowObservations() > 0, "The receiver was never observed inside the window: " + message);
            return Future.<Void>succeededFuture();
        })).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void aReceiverThatStreamsFromTheWriterThroughAnotherSlotIsNotReportedAsFollowed(VertxTestContext context) {
        var follower = NODES.node(FOLLOWER);
        String directory = NODES.dataDirectory();
        var control = control(FOLLOWER, follower.prefix(), PgSupervisedContainer.PG_CTL, "psql", Duration.ofSeconds(10));
        // A server-start option outranks the slot name that following installs, so the receiver streams from
        // the requested address through another slot. That slot exists on both writers.
        createSlot(WRITER, "peegeeq_elsewhere").compose(ignored -> control(STANDBY).promote())
            .compose(ignored -> durability(STANDBY).ensureSlots(List.of(FOLLOWER)))
            .compose(ignored -> createSlot(STANDBY, "peegeeq_elsewhere"))
            .compose(ignored -> follower.forceStop(runner, directory))
            .compose(ignored -> follower.exec(runner, PgSupervisedContainer.PG_CTL, "start", "-D", directory, "-w",
                "-l", follower.logFile(directory), "-o", "-c primary_slot_name=peegeeq_elsewhere"))
            .compose(started -> {
                assertEquals(0, started.exitCode(), "Override start failed: " + started.output());
                return control.follow(FOLLOWER, writerEndpoint()).transform(follow -> {
                    assertTrue(follow.failed(), "A receiver that uses another slot was reported as following");
                    assertInstanceOf(PgPromotionException.class, follow.cause());
                    assertTrue(follow.cause().getMessage().startsWith("WAL receiver is not streaming from " + STANDBY
                        + ":5432 through slot " + PgNodeConfig.slotName(FOLLOWER)), follow.cause().getMessage());
                    assertTrue(follow.cause().getMessage().endsWith("last observed: streaming|" + STANDBY
                        + "|5432|peegeeq_elsewhere|WalReceiverMain"), follow.cause().getMessage());
                    return Future.<Void>succeededFuture();
                });
            }).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    private Future<Void> createSlot(String nodeId, String slot) {
        return NODES.node(nodeId).sql(runner, "select pg_create_physical_replication_slot('" + slot + "', true)")
            .map(created -> {
                assertEquals(0, created.exitCode(), created.output());
                return null;
            });
    }

    private PgDurabilityControl durability(String nodeId) {
        return new PgSqlDurabilityControl(vertx, runner, NODES.node(nodeId).prefix(), PgSupervisedContainer.PG_CTL,
            "psql", NODES.dataDirectory(), COMMAND_TIMEOUT);
    }

    @Test void aStoppedServerFailsToFollow(VertxTestContext context) {
        NODES.node(FOLLOWER).forceStop(runner, NODES.dataDirectory())
            .compose(ignored -> control(FOLLOWER).follow(FOLLOWER, writerEndpoint()))
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgPromotionException.class, failure);
                assertTrue(failure.getMessage().startsWith("Local SQL failed with exit"), failure.getMessage());
                context.completeNow();
            })));
    }

    @Test void aWriterThatCannotBeReachedIsNotReportedAsFollowedAndTheWaitEndsWithinItsBudget(
            VertxTestContext context) {
        var budget = Duration.ofSeconds(5);
        var control = control(FOLLOWER, NODES.node(FOLLOWER).prefix(), PgSupervisedContainer.PG_CTL, "psql", budget);
        var nowhere = new PgWriterEndpoint("pg-nonexistent", 5432, NODES.passfile(FOLLOWER));
        long started = System.nanoTime();
        control.follow(FOLLOWER, nowhere).onComplete(context.failing(failure -> context.verify(() -> {
            assertInstanceOf(PgPromotionException.class, failure);
            assertTrue(failure.getMessage().startsWith("WAL receiver is not streaming from pg-nonexistent:5432"),
                failure.getMessage());
            assertTrue(System.nanoTime() - started < budget.plusSeconds(10).toNanos(), "The wait overran its budget");
            context.completeNow();
        })));
    }

    private PgWriterEndpoint writerEndpoint() {
        return NODES.endpoint(STANDBY, FOLLOWER);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.LocalCommandRunner",
        message = "Command could not run: [/nonexistent/docker",
        messageMatch = ExpectedErrorLog.MessageMatch.PREFIX,
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = IOException.class)
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgSqlPromotionControl",
        message = "Promotion command failed: psql",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgProcessControlException.class)
    void aCommandThatCannotRunFailsTheObservationAndIsReported(VertxTestContext context) {
        control(STANDBY, List.of("/nonexistent/docker"), PgSupervisedContainer.PG_CTL, "psql", COMMAND_TIMEOUT)
            .inRecovery().onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgPromotionException.class, failure);
                assertInstanceOf(PgProcessControlException.class, failure.getCause());
                context.completeNow();
            })));
    }

    @Test void outputThatIsNotAnObservationIsRejected(VertxTestContext context) {
        // echo exits 0 and prints its arguments, so the output is neither t nor f.
        control(STANDBY, NODES.node(STANDBY).prefix(), PgSupervisedContainer.PG_CTL, "echo", COMMAND_TIMEOUT)
            .inRecovery().onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgPromotionException.class, failure);
                assertTrue(failure.getMessage().startsWith("Malformed promotion observation"), failure.getMessage());
                context.completeNow();
            })));
    }

    @Test void aRunnerThatReturnsNoResultFailsEveryOperation(VertxTestContext context) {
        var silent = new PgSqlPromotionControl(vertx, new NoResultCommandRunner(runner), NODES.node(STANDBY).prefix(),
            PgSupervisedContainer.PG_CTL, "psql", NODES.dataDirectory(), COMMAND_TIMEOUT);
        String noResult = "Command returned no result";
        silent.inRecovery().transform(recovery -> refused(recovery, noResult))
            .compose(ignored -> silent.promote().transform(promotion -> refused(promotion, noResult)))
            .compose(ignored -> silent.checkpoint().transform(checkpoint -> refused(checkpoint, noResult)))
            .compose(ignored -> silent.follow(FOLLOWER, writerEndpoint()).transform(follow -> refused(follow, noResult)))
            .onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    /**
     * Asserts that the operation failed with a {@link PgPromotionException} whose message starts with the prefix, and
     * succeeds. The next step follows through {@code compose}, so a failed assertion ends the chain with its own
     * message instead of reaching the next step as an unexpected failure.
     */
    private static Future<Void> refused(io.vertx.core.AsyncResult<?> outcome, String messagePrefix) {
        assertTrue(outcome.failed(), "The operation succeeded; expected a failure starting with: " + messagePrefix);
        assertInstanceOf(PgPromotionException.class, outcome.cause());
        assertTrue(outcome.cause().getMessage().startsWith(messagePrefix), outcome.cause().getMessage());
        return Future.succeededFuture();
    }

    @Test void anOperationWithoutTimeToRunFailsPromptly(VertxTestContext context) {
        assertThrows(IllegalArgumentException.class,
            () -> control(STANDBY, NODES.node(STANDBY).prefix(), PgSupervisedContainer.PG_CTL, "psql", Duration.ZERO));
        long started = System.nanoTime();
        var hurried = control(STANDBY, NODES.node(STANDBY).prefix(), PgSupervisedContainer.PG_CTL, "psql",
            Duration.ofMillis(1));
        String exhausted = "Promotion budget exhausted";
        hurried.promote().transform(promotion -> refused(promotion, exhausted))
            .compose(ignored -> hurried.checkpoint().transform(checkpoint -> refused(checkpoint, exhausted)))
            .compose(ignored -> hurried.follow(FOLLOWER, writerEndpoint()).transform(follow -> refused(follow, exhausted)))
            .onSuccess(ignored -> context.verify(() -> {
                assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(30));
                context.completeNow();
            })).onFailure(context::failNow);
    }

    private PgPromotionControl control(String nodeId) {
        return control(nodeId, NODES.node(nodeId).prefix(), PgSupervisedContainer.PG_CTL, "psql", COMMAND_TIMEOUT);
    }

    private PgPromotionControl control(String nodeId, List<String> prefix, String pgCtl, String psql,
                                       Duration timeout) {
        return new PgSqlPromotionControl(vertx, runner, prefix, pgCtl, psql, NODES.dataDirectory(), timeout);
    }
}
