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
import java.util.ArrayList;
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
@io.vertx.junit5.Timeout(value = 180, timeUnit = TimeUnit.SECONDS)
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
            }).compose(ignored -> NODES.node(WRITER).sql(runner, "show synchronous_standby_names"))
            .compose(effective -> {
                assertEquals(POLICY.synchronousStandbyNames(), effective.output().strip(),
                    "A failed installation leaves the installed setting in effect");
                var commit = new ArrayList<>(NODES.node(WRITER).prefix());
                commit.addAll(List.of("psql", "-X", "-tA", "-c", "create table commit_waits (id int)"));
                return runner.run(commit, Duration.ofSeconds(5)).transform(blocked -> {
                    assertTrue(blocked.failed(), "A commit completed although a required standby is missing");
                    assertInstanceOf(PgProcessControlException.class, blocked.cause());
                    assertTrue(blocked.cause().getMessage().startsWith("Command exceeded"), blocked.cause().getMessage());
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
        String malformed = "Malformed durability observation";
        control.flushBoundary().transform(boundary -> refused(boundary, malformed))
            .compose(ignored -> control.peers().transform(peers -> refused(peers, malformed)))
            .compose(ignored -> control.ensureSlots(List.of("pg-node-9")).transform(ensured -> refused(ensured, malformed)))
            .compose(ignored -> control.replaceLostSlot("pg-node-9").transform(replaced -> refused(replaced, malformed)))
            .onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    /**
     * Asserts that the operation failed with a {@link PgDurabilityException} whose message starts with the prefix,
     * and succeeds. The next step follows through {@code compose}, so a failed assertion ends the chain with its own
     * message instead of reaching the next step as an unexpected failure.
     */
    private static Future<Void> refused(io.vertx.core.AsyncResult<?> outcome, String messagePrefix) {
        assertTrue(outcome.failed(), "The operation succeeded; expected a failure starting with: " + messagePrefix);
        assertInstanceOf(PgDurabilityException.class, outcome.cause());
        assertTrue(outcome.cause().getMessage().startsWith(messagePrefix), outcome.cause().getMessage());
        return Future.succeededFuture();
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

    @Test void aStandbyThatConnectsDuringTheWaitIsConfirmedCovered(VertxTestContext context) {
        var control = control(WRITER);
        var late = STANDBYS.get(1);
        control.flushBoundary().compose(boundary -> awaitCovered(control, boundary, deadline()))
            .compose(covered -> NODES.node(late).forceStop(runner, NODES.dataDirectory()))
            .compose(stopped -> awaitDisconnected(control, late, deadline()))
            // Coverage begins while the standby is down. The standby starts during the wait.
            .compose(disconnected -> Future.all(control.confirmCovered(POLICY),
                NODES.control(runner, late).startInRecovery()))
            .onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void aStandbyReplicatingUnderAnotherNameFailsCoverageAtOnceNotAtTheEndOfTheBudget(
            VertxTestContext context) {
        var budget = Duration.ofSeconds(20);
        var control = control(WRITER, NODES.node(WRITER).prefix(), "psql", budget);
        var impostor = STANDBYS.get(1);
        var standby = NODES.node(impostor);
        control.flushBoundary().compose(boundary -> awaitCovered(control, boundary, deadline()))
            .compose(covered -> standby.sql(runner, "alter system set primary_conninfo = '"
                + NODES.primaryConnectionInfo(impostor, "imposter") + "'"))
            .compose(altered -> {
                assertEquals(0, altered.exitCode(), altered.output());
                return standby.sql(runner, "select pg_reload_conf()");
            }).compose(reloaded -> awaitPeerNamed(control, "imposter", deadline()))
            .compose(connected -> {
                long started = System.nanoTime();
                return control.confirmCovered(POLICY).transform(coverage -> {
                    assertTrue(coverage.failed(), "A standby under another name was confirmed covered");
                    assertInstanceOf(PgDurabilityException.class, coverage.cause());
                    assertFalse(coverage.cause() instanceof PgStandbyNotCoveredException,
                        "An identity mismatch was reported as a wait: " + coverage.cause());
                    assertTrue(coverage.cause().getMessage().contains("Role " + impostor + " replicates as imposter"),
                        coverage.cause().getMessage());
                    assertTrue(System.nanoTime() - started < budget.dividedBy(4).toNanos(),
                        "The mismatch was retried until the budget ended");
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

    @Test void aRunnerThatReturnsNoResultFailsEveryOperation(VertxTestContext context) {
        var silent = new PgSqlDurabilityControl(vertx, new NoResultCommandRunner(runner), NODES.node(WRITER).prefix(),
            PgSupervisedContainer.PG_CTL, "psql", NODES.dataDirectory(), COMMAND_TIMEOUT);
        String noResult = "Command returned no result";
        silent.flushBoundary().transform(boundary -> refused(boundary, noResult))
            .compose(ignored -> silent.peers().transform(peers -> refused(peers, noResult)))
            .compose(ignored -> silent.confirmCovered(POLICY).transform(coverage -> refused(coverage, noResult)))
            .compose(ignored -> silent.install(POLICY).transform(install -> refused(install, noResult)))
            .compose(ignored -> silent.ensureSlots(List.of("pg-node-9")).transform(ensured -> refused(ensured, noResult)))
            .compose(ignored -> silent.replaceLostSlot("pg-node-9").transform(replaced -> refused(replaced, noResult)))
            .onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void coverageWithoutTimeToRunFailsPromptly(VertxTestContext context) {
        control(WRITER, NODES.node(WRITER).prefix(), "psql", Duration.ofMillis(1)).confirmCovered(POLICY)
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgDurabilityException.class, failure);
                assertTrue(failure.getMessage().startsWith("Durability budget exhausted"), failure.getMessage());
                context.completeNow();
            })));
    }

    // ---------------------------------------------------------------- member replication slots

    @Test void missingMemberSlotsAreCreatedAsPermanentPhysicalSlotsWithAReservedPosition(VertxTestContext context) {
        control(WRITER).ensureSlots(List.of("pg-node-8", "pg-node-9"))
            .compose(ignored -> NODES.node(WRITER).sql(runner, "select string_agg(slot_name || '|' || slot_type || '|' "
                + "|| temporary::text || '|' || (restart_lsn is not null)::text, ',' order by slot_name) "
                + "from pg_replication_slots where slot_name in ('peegeeq_pg_node_8', 'peegeeq_pg_node_9')"))
            .onSuccess(slots -> context.verify(() -> {
                assertEquals(0, slots.exitCode(), slots.output());
                assertEquals("peegeeq_pg_node_8|physical|false|true,peegeeq_pg_node_9|physical|false|true",
                    slots.output().strip(), "Each missing member slot is a permanent physical slot with a reserved position");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void aSlotThatExistsIsNeitherRecreatedNorMovedAndTheProvisionedSlotsAreKept(VertxTestContext context) {
        var writer = NODES.node(WRITER);
        // Created without a reserved position. A slot that ensureSlots recreated would have one.
        writer.sql(runner, "select pg_create_physical_replication_slot('peegeeq_pg_node_9')").compose(created -> {
            assertEquals(0, created.exitCode(), created.output());
            List<String> members = new ArrayList<>(STANDBYS);
            members.add("pg-node-9");
            return control(WRITER).ensureSlots(members);
        }).compose(ignored -> writer.sql(runner, "select coalesce(restart_lsn::text, 'none') from pg_replication_slots "
                + "where slot_name = 'peegeeq_pg_node_9'"))
            .compose(kept -> {
                assertEquals("none", kept.output().strip(), "An existing slot was recreated or moved");
                return writer.sql(runner, "select count(*) from pg_replication_slots where slot_type = 'physical' "
                    + "and active and slot_name in ('peegeeq_pg_node_2', 'peegeeq_pg_node_3')");
            }).onSuccess(provisioned -> context.verify(() -> {
                assertEquals("2", provisioned.output().strip(), "A provisioned standby slot is gone or no longer in use");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void aStandbyHoldsMemberSlotsButALostSlotIsReplacedOnlyOnTheWriter(VertxTestContext context) {
        var standby = STANDBYS.get(0);
        // Every node holds the member slots (OD-35); a lost slot is replaced on the writer for a rebuild (plan §9.26).
        control(standby).ensureSlots(List.of("pg-node-9"))
            .compose(ignored -> control(standby).replaceLostSlot("pg-node-9").transform(replaced -> refused(replaced,
                "PostgreSQL is in recovery; a lost member slot is replaced on the writer")))
            .compose(ignored -> NODES.node(standby).sql(runner, "select slot_type || '|' || (restart_lsn is not null)::text "
                + "from pg_replication_slots where slot_name = 'peegeeq_pg_node_9'"))
            .onSuccess(slot -> context.verify(() -> {
                assertEquals("physical|true", slot.output().strip(),
                    "The standby does not hold a reserved physical slot for the member");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void anUnboundedSlotRetentionIsRefusedAndNoSlotIsCreated(VertxTestContext context) {
        var writer = NODES.node(WRITER);
        String directory = NODES.dataDirectory();
        // A server-start option outranks postgresql.conf, as a deployment's unbounded setting would.
        writer.forceStop(runner, directory)
            .compose(ignored -> writer.exec(runner, PgSupervisedContainer.PG_CTL, "start", "-D", directory, "-w",
                "-l", writer.logFile(directory), "-o", "-c max_slot_wal_keep_size=-1"))
            .compose(started -> {
                if (started.exitCode() != 0) {
                    return Future.<Void>failedFuture(new AssertionError("Unbounded start failed: " + started.output()));
                }
                String unbounded = "max_slot_wal_keep_size is -1";
                return control(WRITER).ensureSlots(List.of("pg-node-9")).transform(ensured -> refused(ensured, unbounded))
                    .compose(ignored -> control(WRITER).replaceLostSlot("pg-node-9")
                        .transform(replaced -> refused(replaced, unbounded)));
            }).compose(ignored -> writer.sql(runner, "select count(*) from pg_replication_slots where slot_name = "
                + "'peegeeq_pg_node_9'"))
            .onSuccess(count -> context.verify(() -> {
                assertEquals("0", count.output().strip(), "A refused call created a slot");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void aLogicalSlotUnderAMemberSlotNameFailsTheCallAndIsKept(VertxTestContext context) {
        var writer = NODES.node(WRITER);
        String directory = NODES.dataDirectory();
        // A logical slot needs wal_level logical, which is set at server start.
        writer.forceStop(runner, directory)
            .compose(ignored -> writer.exec(runner, PgSupervisedContainer.PG_CTL, "start", "-D", directory, "-w",
                "-l", writer.logFile(directory), "-o", "-c wal_level=logical"))
            .compose(started -> {
                if (started.exitCode() != 0) {
                    return Future.<PgCommandResult>failedFuture(new AssertionError("Logical start failed: " + started.output()));
                }
                return writer.sql(runner, "select pg_create_logical_replication_slot('peegeeq_pg_node_9', 'test_decoding')");
            }).compose(created -> {
                assertEquals(0, created.exitCode(), created.output());
                String logical = "Replication slot peegeeq_pg_node_9 is a logical slot";
                return control(WRITER).ensureSlots(List.of("pg-node-9")).transform(ensured -> refused(ensured, logical))
                    .compose(ignored -> control(WRITER).replaceLostSlot("pg-node-9")
                        .transform(replaced -> refused(replaced, logical)));
            }).compose(ignored -> writer.sql(runner, "select slot_type from pg_replication_slots where slot_name = "
                + "'peegeeq_pg_node_9'"))
            .onSuccess(type -> context.verify(() -> {
                assertEquals("logical", type.output().strip(), "A slot under a member name was replaced or dropped");
                context.completeNow();
            })).onFailure(context::failNow);
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
    void aCommandThatCannotRunFailsSlotCreationAndIsReported(VertxTestContext context) {
        control(WRITER, List.of("/nonexistent/docker"), "psql", COMMAND_TIMEOUT).ensureSlots(List.of("pg-node-9"))
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgDurabilityException.class, failure);
                assertInstanceOf(PgProcessControlException.class, failure.getCause());
                context.completeNow();
            })));
    }

    @Test
    // Writing 96 MB of WAL and waiting for the slot to be lost can exceed the 30-second Vert.x default.
    @io.vertx.junit5.Timeout(value = 120, timeUnit = TimeUnit.SECONDS)
    void aRequiredStandbyWhoseSlotIsLostFailsCoverageAtOnceAndNamesTheSlot(VertxTestContext context) {
        var writer = NODES.node(WRITER);
        var lost = STANDBYS.get(1);
        String slot = PgNodeConfig.slotName(lost);
        var budget = Duration.ofSeconds(20);
        NODES.loseSlot(vertx, runner, lost)
            .compose(ignored -> {
                long started = System.nanoTime();
                return control(WRITER, writer.prefix(), "psql", budget)
                    .confirmCovered(new PgDurabilityPolicy(2, List.of(lost))).transform(coverage -> {
                        assertTrue(coverage.failed(), "Coverage was confirmed for a standby whose slot is lost");
                        assertInstanceOf(PgDurabilityException.class, coverage.cause());
                        assertFalse(coverage.cause() instanceof PgStandbyNotCoveredException,
                            "A lost slot was reported as a wait: " + coverage.cause());
                        assertTrue(coverage.cause().getMessage().startsWith("Replication slot " + slot
                            + " of required standby " + lost + " is lost"), coverage.cause().getMessage());
                        assertTrue(System.nanoTime() - started < budget.dividedBy(4).toNanos(),
                            "A lost slot was retried until the budget ended");
                        return Future.<Void>succeededFuture();
                    });
            }).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    // Writing 96 MB of WAL and waiting for the slot to be lost can exceed the 30-second Vert.x default.
    @io.vertx.junit5.Timeout(value = 120, timeUnit = TimeUnit.SECONDS)
    void aLostSlotIsReplacedByAPhysicalSlotWithAReservedPosition(VertxTestContext context) {
        var lost = STANDBYS.get(1);
        String slot = PgNodeConfig.slotName(lost);
        NODES.loseSlot(vertx, runner, lost).compose(ignored -> control(WRITER).replaceLostSlot(lost))
            .compose(ignored -> NODES.node(WRITER).sql(runner, "select slot_type || '|' || coalesce(wal_status, 'none') "
                + "|| '|' || (restart_lsn is not null)::text from pg_replication_slots where slot_name = '" + slot + "'"))
            .onSuccess(replaced -> context.verify(() -> {
                assertEquals("physical|reserved|true", replaced.output().strip(),
                    "The lost slot was not replaced by a physical slot with a reserved position");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void aSlotThatIsNotLostIsNeverReplacedAndAMissingSlotIsRefused(VertxTestContext context) {
        var writer = NODES.node(WRITER);
        var kept = STANDBYS.get(0);
        control(WRITER).replaceLostSlot(kept)
            .transform(replace -> refused(replace, "Replication slot " + PgNodeConfig.slotName(kept) + " is not lost"))
            .compose(ignored -> control(WRITER).replaceLostSlot("pg-node-9")
                .transform(replace -> refused(replace, "Replication slot peegeeq_pg_node_9 does not exist")))
            .compose(ignored -> writer.sql(runner, "select string_agg(slot_name || '|' || active::text, ',' "
                + "order by slot_name) from pg_replication_slots"))
            .onSuccess(slots -> context.verify(() -> {
            assertEquals("peegeeq_pg_node_2|true,peegeeq_pg_node_3|true", slots.output().strip(),
                "A refused replacement dropped, moved, or created a slot");
            context.completeNow();
        })).onFailure(context::failNow);
    }

    @Test void slotCreationWithoutTimeToRunFailsPromptly(VertxTestContext context) {
        control(WRITER, NODES.node(WRITER).prefix(), "psql", Duration.ofMillis(1)).ensureSlots(List.of("pg-node-9"))
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

    private Future<Void> awaitPeerNamed(PgDurabilityControl control, String applicationName, long deadline) {
        return control.peers().compose(peers -> {
            if (peers.stream().anyMatch(peer -> applicationName.equals(peer.applicationName()))) {
                return Future.<Void>succeededFuture();
            }
            if (System.nanoTime() >= deadline) {
                return Future.<Void>failedFuture(new AssertionError(applicationName + " never connected"));
            }
            return vertx.timer(100).compose(ignored -> awaitPeerNamed(control, applicationName, deadline));
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
