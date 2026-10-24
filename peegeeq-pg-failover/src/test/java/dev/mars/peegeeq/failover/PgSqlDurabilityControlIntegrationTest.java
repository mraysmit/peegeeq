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
 * Durability observation and policy installation through the local socket, against a real writer
 * and two real physical standbys that only the test starts.
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Isolated
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class PgSqlDurabilityControlIntegrationTest {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(20);
    private static final PgDurabilityPolicy POLICY = new PgDurabilityPolicy(1, STANDBYS);
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

    @Test void theFlushBoundaryIsTheWritersFlushPositionAndAdvancesWithAWrite(VertxTestContext context) {
        var control = control(WRITER);
        var writer = NODES.node(WRITER);
        control.flushBoundary().compose(before -> writer.sql(runner, "select pg_current_wal_flush_lsn()")
            .compose(observed -> {
                assertEquals(0, observed.exitCode(), observed.output());
                assertTrue(PgLsn.parse(observed.output().strip()).reached(before));
                return writer.sql(runner, "create table durability_probe (id int)");
            }).compose(created -> {
                assertEquals(0, created.exitCode(), created.output());
                return control.flushBoundary();
            }).map(after -> {
                assertTrue(after.reached(before) && !before.reached(after), "A committed write did not advance it");
                return null;
            })).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void aServerInRecoveryHasNoFlushBoundary(VertxTestContext context) {
        control(STANDBYS.get(0)).flushBoundary().onComplete(context.failing(failure -> context.verify(() -> {
            assertInstanceOf(PgDurabilityException.class, failure);
            assertEquals("PostgreSQL is in recovery; it has no writer flush position", failure.getMessage());
            context.completeNow();
        })));
    }

    @Test void eachStandbyReplicatesUnderItsOwnRoleAndReachesTheBoundary(VertxTestContext context) {
        var control = control(WRITER);
        control.flushBoundary().compose(boundary -> awaitCovered(control, boundary, deadline()))
            .onSuccess(peers -> context.verify(() -> {
                for (String standby : STANDBYS) {
                    var peer = peers.stream().filter(found -> standby.equals(found.applicationName()))
                        .findFirst().orElseThrow();
                    assertEquals(standby, peer.role());
                    assertEquals("streaming", peer.state());
                    assertEquals("async", peer.syncState(), "No policy is installed yet");
                }
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void installingThePolicyPutsTheQuotedSettingInEffectWithBothPeersInQuorum(VertxTestContext context) {
        var control = control(WRITER);
        var writer = NODES.node(WRITER);
        control.flushBoundary().compose(boundary -> awaitCovered(control, boundary, deadline()))
            .compose(covered -> control.install(POLICY))
            .compose(ignored -> writer.sql(runner, "show synchronous_standby_names")).compose(effective -> {
                assertEquals(0, effective.exitCode(), effective.output());
                assertEquals("ANY 2 (\"pg-node-2\", \"pg-node-3\")", effective.output().strip());
                return control.peers();
            }).compose(peers -> {
                for (String standby : STANDBYS) {
                    assertTrue(peers.stream().anyMatch(peer -> standby.equals(peer.role())
                        && "quorum".equals(peer.syncState())), standby + " is not a quorum peer: " + peers);
                }
                return writer.sql(runner, "select setting from pg_file_settings where name = "
                    + "'synchronous_standby_names' and sourcefile like '%/postgresql.auto.conf'");
            }).onSuccess(persisted -> context.verify(() -> {
                assertEquals(0, persisted.exitCode(), persisted.output());
                assertEquals("ANY 2 (\"pg-node-2\", \"pg-node-3\")", persisted.output().strip(),
                    "The policy is not persisted for the next start");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void aRequiredPeerThatIsMissingFailsTheInstallWithinItsBudget(VertxTestContext context) {
        var budget = Duration.ofSeconds(5);
        var control = control(WRITER, NODES.node(WRITER).prefix(), "psql", budget);
        var missing = STANDBYS.get(1);
        control.flushBoundary().compose(boundary -> awaitCovered(control, boundary, deadline()))
            .compose(covered -> NODES.node(missing).forceStop(runner, NODES.dataDirectory()))
            .compose(ignored -> awaitDisconnected(control, missing, deadline()))
            .compose(ignored -> {
                long started = System.nanoTime();
                return control.install(POLICY).transform(install -> {
                    assertTrue(install.failed(), "A policy was confirmed without a required peer");
                    assertInstanceOf(PgDurabilityException.class, install.cause());
                    assertTrue(install.cause().getMessage().startsWith("Required standbys did not report quorum"),
                        install.cause().getMessage());
                    assertTrue(System.nanoTime() - started < budget.plusSeconds(10).toNanos(),
                        "The install overran its budget");
                    return Future.<Void>succeededFuture();
                });
            }).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void aSettingThatOverridesTheInstalledPolicyIsReportedAsAMismatch(VertxTestContext context) {
        var writer = NODES.node(WRITER);
        String directory = NODES.dataDirectory();
        // A server-start option outranks postgresql.auto.conf, so the reloaded value differs. An
        // unquoted name cannot contain a hyphen, so the override names no configured node.
        writer.forceStop(runner, directory)
            .compose(ignored -> writer.exec(runner, PgSupervisedContainer.PG_CTL, "start", "-D", directory, "-w",
                "-l", writer.logFile(directory), "-o", "-c synchronous_standby_names=elsewhere"))
            .compose(started -> {
                if (started.exitCode() != 0) {
                    return Future.<Void>failedFuture(new AssertionError("Override start failed: " + started.output()));
                }
                return control(WRITER).install(POLICY);
            }).onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgDurabilityException.class, failure);
                assertTrue(failure.getMessage().startsWith("The setting in effect is elsewhere"), failure.getMessage());
                context.completeNow();
            })));
    }

    @Test void aStoppedServerFailsTheInstall(VertxTestContext context) {
        NODES.node(WRITER).forceStop(runner, NODES.dataDirectory())
            .compose(ignored -> control(WRITER).install(POLICY))
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgDurabilityException.class, failure);
                assertTrue(failure.getMessage().startsWith("Local SQL failed with exit"), failure.getMessage());
                context.completeNow();
            })));
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.LocalCommandRunner",
        message = "Command could not run: [/nonexistent/docker",
        messageMatch = ExpectedErrorLog.MessageMatch.PREFIX,
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = IOException.class)
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgSqlDurabilityControl",
        message = "Durability command failed: psql",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgProcessControlException.class)
    void aCommandThatCannotRunFailsTheObservationAndIsReported(VertxTestContext context) {
        control(WRITER, List.of("/nonexistent/docker"), "psql", COMMAND_TIMEOUT).flushBoundary()
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgDurabilityException.class, failure);
                assertInstanceOf(PgProcessControlException.class, failure.getCause());
                context.completeNow();
            })));
    }

    @Test void outputThatIsNotAnObservationIsRejected(VertxTestContext context) {
        // echo exits 0 and prints its arguments, so the output is not an observation.
        var control = control(WRITER, NODES.node(WRITER).prefix(), "echo", COMMAND_TIMEOUT);
        control.flushBoundary().transform(boundary -> {
            assertTrue(boundary.failed(), "Unparseable output was accepted as a flush position");
            assertInstanceOf(PgDurabilityException.class, boundary.cause());
            assertTrue(boundary.cause().getMessage().startsWith("Malformed durability observation"),
                boundary.cause().getMessage());
            return control.peers();
        }).onComplete(context.failing(failure -> context.verify(() -> {
            assertInstanceOf(PgDurabilityException.class, failure);
            assertTrue(failure.getMessage().startsWith("Malformed durability observation"), failure.getMessage());
            context.completeNow();
        })));
    }

    @Test void anInstallWithoutTimeToRunFailsPromptly(VertxTestContext context) {
        assertThrows(IllegalArgumentException.class,
            () -> control(WRITER, NODES.node(WRITER).prefix(), "psql", Duration.ZERO));
        long started = System.nanoTime();
        control(WRITER, NODES.node(WRITER).prefix(), "psql", Duration.ofMillis(1)).install(POLICY)
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgDurabilityException.class, failure);
                assertTrue(failure.getMessage().startsWith("Durability budget exhausted"), failure.getMessage());
                assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(30));
                context.completeNow();
            })));
    }

    @Test void bothRequiredStandbysAreConfirmedCoveredOnceTheyReachTheBoundary(VertxTestContext context) {
        control(WRITER).confirmCovered(POLICY).onComplete(context.succeedingThenComplete());
    }

    @Test void aRequiredStandbyThatIsNotConnectedFailsCoverageWithinItsBudgetAndNamesTheStandby(
            VertxTestContext context) {
        var budget = Duration.ofSeconds(5);
        var control = control(WRITER, NODES.node(WRITER).prefix(), "psql", budget);
        var missing = STANDBYS.get(1);
        control.flushBoundary().compose(boundary -> awaitCovered(control, boundary, deadline()))
            .compose(covered -> NODES.node(missing).forceStop(runner, NODES.dataDirectory()))
            .compose(ignored -> awaitDisconnected(control, missing, deadline()))
            .compose(ignored -> {
                long started = System.nanoTime();
                return control.confirmCovered(POLICY).transform(coverage -> {
                    assertTrue(coverage.failed(), "Coverage was confirmed without a required standby");
                    assertInstanceOf(PgDurabilityException.class, coverage.cause());
                    assertTrue(coverage.cause().getMessage().startsWith("Required standbys were not covered"),
                        coverage.cause().getMessage());
                    assertTrue(coverage.cause().getMessage().contains(missing + " is not connected"),
                        coverage.cause().getMessage());
                    assertTrue(System.nanoTime() - started < budget.plusSeconds(10).toNanos(),
                        "Coverage overran its budget");
                    return Future.<Void>succeededFuture();
                });
            }).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void coverageOfAServerInRecoveryFailsBecauseItHasNoBoundary(VertxTestContext context) {
        control(STANDBYS.get(0)).confirmCovered(POLICY).onComplete(context.failing(failure -> context.verify(() -> {
            assertInstanceOf(PgDurabilityException.class, failure);
            assertEquals("PostgreSQL is in recovery; it has no writer flush position", failure.getMessage());
            context.completeNow();
        })));
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.LocalCommandRunner",
        message = "Command could not run: [/nonexistent/docker",
        messageMatch = ExpectedErrorLog.MessageMatch.PREFIX,
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = IOException.class)
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgSqlDurabilityControl",
        message = "Durability command failed: psql",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgProcessControlException.class)
    void aCommandThatCannotRunFailsCoverageAndIsNotRetried(VertxTestContext context) {
        long started = System.nanoTime();
        control(WRITER, List.of("/nonexistent/docker"), "psql", COMMAND_TIMEOUT).confirmCovered(POLICY)
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgDurabilityException.class, failure);
                assertInstanceOf(PgProcessControlException.class, failure.getCause());
                assertTrue(System.nanoTime() - started < COMMAND_TIMEOUT.toNanos(),
                    "A command failure was retried until the budget ended");
                context.completeNow();
            })));
    }

    @Test void coverageObservedFromOutputThatIsNotAnObservationIsRejected(VertxTestContext context) {
        control(WRITER, NODES.node(WRITER).prefix(), "echo", COMMAND_TIMEOUT).confirmCovered(POLICY)
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgDurabilityException.class, failure);
                assertTrue(failure.getMessage().startsWith("Malformed durability observation"), failure.getMessage());
                context.completeNow();
            })));
    }

    @Test void coverageWithoutTimeToRunFailsPromptly(VertxTestContext context) {
        control(WRITER, NODES.node(WRITER).prefix(), "psql", Duration.ofMillis(1)).confirmCovered(POLICY)
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgDurabilityException.class, failure);
                assertTrue(failure.getMessage().startsWith("Durability budget exhausted"), failure.getMessage());
                context.completeNow();
            })));
    }

    private Future<List<PgReplicationPeer>> awaitCovered(PgDurabilityControl control, PgLsn boundary, long deadline) {
        return control.peers().compose(peers -> {
            try {
                POLICY.requireCoveredAt(boundary, peers);
                return Future.succeededFuture(peers);
            } catch (PgDurabilityException uncovered) {
                if (System.nanoTime() >= deadline) {
                    return Future.failedFuture(new AssertionError("The standbys never covered the boundary", uncovered));
                }
                return vertx.timer(100).compose(ignored -> awaitCovered(control, boundary, deadline));
            }
        });
    }

    private Future<Void> awaitDisconnected(PgDurabilityControl control, String standby, long deadline) {
        return control.peers().compose(peers -> {
            if (peers.stream().noneMatch(peer -> standby.equals(peer.applicationName()))) {
                return Future.<Void>succeededFuture();
            }
            if (System.nanoTime() >= deadline) {
                return Future.<Void>failedFuture(new AssertionError(standby + " is still connected"));
            }
            return vertx.timer(100).compose(ignored -> awaitDisconnected(control, standby, deadline));
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
