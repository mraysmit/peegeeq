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

    @Test void aStandbyFollowsTheNewWriterAndStreamsFromIt(VertxTestContext context) {
        var follower = NODES.node(FOLLOWER);
        control(STANDBY).promote().compose(ignored -> control(FOLLOWER).follow(FOLLOWER, writerEndpoint()))
            .compose(ignored -> follower.sql(runner,
                "select status || '|' || sender_host || '|' || sender_port from pg_stat_wal_receiver"))
            .compose(receiver -> {
                assertEquals("streaming|" + STANDBY + "|5432", receiver.output().strip(),
                    "The follower does not stream from the new writer");
                return NODES.node(STANDBY).sql(runner, "select count(*) from pg_stat_replication where "
                    + "application_name = '" + FOLLOWER + "' and usename = '" + FOLLOWER + "' and state = 'streaming'");
            }).compose(peer -> {
                assertEquals("1", peer.output().strip(), "The new writer does not see the follower as a peer");
                return follower.sql(runner, "select pg_is_in_recovery()");
            }).onSuccess(recovery -> context.verify(() -> {
                assertEquals("t", recovery.output().strip(), "A follower must stay a standby");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void theInstalledConnectionStringKeepsTheIdentityAndHoldsNoPassword(VertxTestContext context) {
        var follower = NODES.node(FOLLOWER);
        control(STANDBY).promote().compose(ignored -> control(FOLLOWER).follow(FOLLOWER, writerEndpoint()))
            .compose(ignored -> follower.sql(runner, "select setting from pg_settings where name = 'primary_conninfo'"))
            .onSuccess(conninfo -> context.verify(() -> {
                String installed = conninfo.output().strip();
                assertEquals(writerEndpoint().connectionInfo(FOLLOWER), installed);
                assertFalse(installed.contains("password"), "A password reached the connection string: " + installed);
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void aServerThatIsNotInRecoveryCannotFollowAndChangesNothing(VertxTestContext context) {
        control(WRITER).follow(WRITER, writerEndpoint()).transform(follow -> {
            assertTrue(follow.failed(), "A writable server was pointed at another writer");
            assertInstanceOf(PgPromotionException.class, follow.cause());
            assertTrue(follow.cause().getMessage().startsWith("PostgreSQL is writable"), follow.cause().getMessage());
            return NODES.node(WRITER).sql(runner, "select count(*) from pg_settings where name = 'primary_conninfo' "
                + "and setting <> ''");
        }).onSuccess(configured -> context.verify(() -> {
            assertEquals("0", configured.output().strip(), "A refused follow installed a connection string");
            context.completeNow();
        })).onFailure(context::failNow);
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
        silent.inRecovery().transform(recovery -> {
            assertNoResult(recovery);
            return silent.promote();
        }).transform(promotion -> {
            assertNoResult(promotion);
            return silent.checkpoint();
        }).transform(checkpoint -> {
            assertNoResult(checkpoint);
            return silent.follow(FOLLOWER, writerEndpoint());
        }).onComplete(context.failing(failure -> context.verify(() -> {
            assertInstanceOf(PgPromotionException.class, failure);
            assertTrue(failure.getMessage().startsWith("Command returned no result"), failure.getMessage());
            context.completeNow();
        })));
    }

    private static void assertNoResult(io.vertx.core.AsyncResult<?> outcome) {
        assertTrue(outcome.failed(), "An operation succeeded although its command returned no result");
        assertInstanceOf(PgPromotionException.class, outcome.cause());
        assertTrue(outcome.cause().getMessage().startsWith("Command returned no result"),
            outcome.cause().getMessage());
    }

    @Test void anOperationWithoutTimeToRunFailsPromptly(VertxTestContext context) {
        assertThrows(IllegalArgumentException.class,
            () -> control(STANDBY, NODES.node(STANDBY).prefix(), PgSupervisedContainer.PG_CTL, "psql", Duration.ZERO));
        long started = System.nanoTime();
        var hurried = control(STANDBY, NODES.node(STANDBY).prefix(), PgSupervisedContainer.PG_CTL, "psql",
            Duration.ofMillis(1));
        hurried.promote().transform(promotion -> {
            assertTrue(promotion.failed());
            assertInstanceOf(PgPromotionException.class, promotion.cause());
            assertTrue(promotion.cause().getMessage().startsWith("Promotion budget exhausted"),
                promotion.cause().getMessage());
            return hurried.checkpoint();
        }).transform(checkpoint -> {
            assertTrue(checkpoint.failed());
            assertInstanceOf(PgPromotionException.class, checkpoint.cause());
            assertTrue(checkpoint.cause().getMessage().startsWith("Promotion budget exhausted"),
                checkpoint.cause().getMessage());
            return hurried.follow(FOLLOWER, writerEndpoint());
        }).onComplete(context.failing(failure -> context.verify(() -> {
            assertInstanceOf(PgPromotionException.class, failure);
            assertTrue(failure.getMessage().startsWith("Promotion budget exhausted"), failure.getMessage());
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(30));
            context.completeNow();
        })));
    }

    private PgPromotionControl control(String nodeId) {
        return control(nodeId, NODES.node(nodeId).prefix(), PgSupervisedContainer.PG_CTL, "psql", COMMAND_TIMEOUT);
    }

    private PgPromotionControl control(String nodeId, List<String> prefix, String pgCtl, String psql,
                                       Duration timeout) {
        return new PgSqlPromotionControl(vertx, runner, prefix, pgCtl, psql, NODES.dataDirectory(), timeout);
    }
}
