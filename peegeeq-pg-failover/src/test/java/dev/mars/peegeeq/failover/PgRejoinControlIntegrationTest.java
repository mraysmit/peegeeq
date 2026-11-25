package dev.mars.peegeeq.failover;

import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.AsyncResult;
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
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static dev.mars.peegeeq.failover.PgReplicationFixture.STANDBYS;
import static dev.mars.peegeeq.failover.PgReplicationFixture.WRITER;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The adapters of a re-join (plan §9.32, contracts 1 to 5): inspection of the data directory, rewind, rebuild, the
 * source observation, and the catch-up confirmation, against a real writer, two real standbys, and a real takeover in
 * which the former writer writes WAL that its successor never received and is then stopped as a host loss stops it.
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Isolated
@Timeout(value = 300, unit = TimeUnit.SECONDS)
@io.vertx.junit5.Timeout(value = 300, timeUnit = TimeUnit.SECONDS)
class PgRejoinControlIntegrationTest {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration BUDGET = Duration.ofSeconds(60);
    private static final String NEW_WRITER = STANDBYS.get(0);
    private static final String FOLLOWER = STANDBYS.get(1);
    private static final String MARKER = "# node-local marker";
    private static final PgReplicationFixture NODES = new PgReplicationFixture();
    private Vertx vertx;
    private PgCommandRunner runner;

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
        Future<Void> started = NODES.provision(runner).compose(ignored -> process(WRITER).startPrimary());
        for (String standby : STANDBYS) {
            started = started.compose(ignored -> process(standby).startInRecovery());
        }
        started.compose(ignored -> durability(WRITER).confirmCovered(new PgDurabilityPolicy(1, STANDBYS)))
            .onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @AfterEach void tearDown(VertxTestContext context) {
        NODES.forceStopAll(runner).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    // ---------------------------------------------------------------- inspection (contract 1)

    @Test void anAbsentDataDirectoryIsReportedAsAbsent(VertxTestContext context) {
        String absent = "/var/lib/postgresql/absent-" + UUID.randomUUID();
        processAt(FOLLOWER, absent).inspect().onSuccess(observed -> context.verify(() -> {
            assertTrue(observed.isEmpty(), "An absent data directory was reported as " + observed);
            context.completeNow();
        })).onFailure(context::failNow);
    }

    @Test void aStoppedDataDirectoryReportsItsSystemIdentifierStateAndTimeline(VertxTestContext context) {
        NODES.node(WRITER).sql(runner, "select system_identifier from pg_control_system()").compose(identity ->
            process(WRITER).stop(COMMAND_TIMEOUT).compose(ignored -> process(FOLLOWER).stop(COMMAND_TIMEOUT))
                .compose(ignored -> process(WRITER).inspect()).compose(writer -> {
                    PgDataDirectory observed = writer.orElseThrow(() -> new AssertionError("The writer's directory is absent"));
                    assertEquals(identity.output().strip(), observed.systemIdentifier());
                    assertEquals("shut down", observed.clusterState());
                    assertEquals(1, observed.timeline());
                    assertTrue(observed.shutDownCleanly());
                    return process(FOLLOWER).inspect();
                })).onSuccess(follower -> context.verify(() -> {
                    PgDataDirectory observed = follower.orElseThrow();
                    assertEquals("shut down in recovery", observed.clusterState());
                    assertTrue(observed.shutDownCleanly());
                    context.completeNow();
                })).onFailure(context::failNow);
    }

    @Test void aDataDirectoryWithoutAControlFileFailsTheObservation(VertxTestContext context) {
        // A base backup that was interrupted after it wrote PG_VERSION and before it wrote the control file.
        String partial = "/var/lib/postgresql/partial-" + UUID.randomUUID();
        checked(NODES.node(FOLLOWER).exec(runner, "sh", "-c", "mkdir -p " + partial + "/base && printf '15\\n' > "
                + partial + "/PG_VERSION"))
            .compose(ignored -> processAt(FOLLOWER, partial).inspect())
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgProcessControlException.class, failure);
                assertTrue(failure.getMessage().startsWith("pg_controldata failed with exit"), failure.getMessage());
                context.completeNow();
            })));
    }

    @Test void aDirectoryThatIsNotADatabaseClusterIsNeitherInspectedNorRemoved(VertxTestContext context) {
        // pg_ctl status exits 4 for this directory as for an absent one; only the absent one may be rebuilt.
        String other = "/var/lib/postgresql/other-" + UUID.randomUUID();
        var control = processAt(FOLLOWER, other);
        String refusal = other + " exists but is not a database cluster directory";
        checked(NODES.node(FOLLOWER).exec(runner, "sh", "-c", "mkdir -p " + other + " && printf 'kept\\n' > " + other
                + "/keep.txt"))
            .compose(ignored -> control.inspect().transform(inspection -> refused(inspection, refusal)))
            .compose(ignored -> control.rebuild(endpoint(WRITER, FOLLOWER), FOLLOWER, BUDGET)
                .transform(rebuild -> refused(rebuild, refusal)))
            .compose(ignored -> NODES.node(FOLLOWER).exec(runner, "test", "-f", other + "/keep.txt"))
            .onSuccess(kept -> context.verify(() -> {
                assertEquals(0, kept.exitCode(), "A rebuild removed a directory that is not a database cluster");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void controlDataOutputThatIsNotAnObservationIsRejected(VertxTestContext context) {
        var replaced = new PgCtlProcessControl(new EchoInsteadCommandRunner(runner, "pg_controldata"),
            NODES.node(FOLLOWER).prefix(), PgSupervisedContainer.PG_CTL, NODES.dataDirectory(),
            NODES.node(FOLLOWER).logFile(NODES.dataDirectory()), COMMAND_TIMEOUT);
        replaced.inspect().onComplete(context.failing(failure -> context.verify(() -> {
            assertInstanceOf(PgProcessControlException.class, failure);
            assertTrue(failure.getMessage().startsWith("Malformed control data"), failure.getMessage());
            context.completeNow();
        })));
    }

    @Test void aRunnerThatReturnsNoResultFailsEveryOperation(VertxTestContext context) {
        var silent = new PgCtlProcessControl(new NoResultCommandRunner(runner), NODES.node(FOLLOWER).prefix(),
            PgSupervisedContainer.PG_CTL, NODES.dataDirectory(), NODES.node(FOLLOWER).logFile(NODES.dataDirectory()),
            COMMAND_TIMEOUT);
        String noResult = "Command returned no result";
        var source = endpoint(WRITER, FOLLOWER);
        silent.inspect().transform(inspection -> refused(inspection, noResult))
            .compose(ignored -> silent.rewind(source, FOLLOWER, BUDGET).transform(rewind -> refused(rewind, noResult)))
            .compose(ignored -> silent.rebuild(source, FOLLOWER, BUDGET).transform(rebuild -> refused(rebuild, noResult)))
            .compose(ignored -> NODES.node(FOLLOWER).sql(runner, "select pg_is_in_recovery()"))
            .onSuccess(recovery -> context.verify(() -> {
                assertEquals("t", recovery.output().strip(), "A refused operation changed the running standby");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    // ---------------------------------------------------------------- rewind (contract 2)

    @Test void aCrashStoppedDivergedFormerWriterIsRewoundKeepsItsOwnConfigurationAndFollowsTheNewWriter(
            VertxTestContext context) {
        // The re-join's stop puts the inhibitor in place first, also on a server that the crash already stopped. With
        // the inhibitor in place, pg_rewind cannot complete the crash recovery itself (plan §9.32, check 3).
        diverge().compose(ignored -> checked(NODES.node(WRITER).exec(runner, "touch", NODES.dataDirectory()
                + "/standby.signal")))
            .compose(ignored -> markConfiguration(WRITER)).compose(digest ->
            process(WRITER).rewind(endpoint(NEW_WRITER, WRITER), WRITER, BUDGET)
                .compose(ignored -> assertKeptConfigurationAndInhibitor(WRITER, digest)))
            .compose(ignored -> assertNoSavedCopy(WRITER))
            .compose(ignored -> process(WRITER).inspect()).compose(observed -> {
                assertEquals(2, observed.orElseThrow().timeline(), "The rewound directory is not on the new timeline");
                return process(WRITER).startInRecovery();
            }).compose(ignored -> promotion(WRITER).follow(WRITER, endpoint(NEW_WRITER, WRITER)))
            .compose(ignored -> promotion(WRITER).confirmCaughtUp(WRITER, endpoint(NEW_WRITER, WRITER)))
            .compose(ignored -> assertHistoryOfTheNewWriter(WRITER))
            .onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void aStandbyThatNeverDivergedNeedsNoRewindAndKeepsItsFiles(VertxTestContext context) {
        // The follower stops cleanly before the takeover, so its history is a prefix of the new writer's.
        process(FOLLOWER).stop(COMMAND_TIMEOUT).compose(ignored -> diverge())
            .compose(ignored -> markConfiguration(FOLLOWER)).compose(digest ->
                process(FOLLOWER).rewind(endpoint(NEW_WRITER, FOLLOWER), FOLLOWER, BUDGET)
                    .compose(ignored -> assertKeptConfigurationAndInhibitor(FOLLOWER, digest)))
            .compose(ignored -> assertNoSavedCopy(FOLLOWER))
            .compose(ignored -> process(FOLLOWER).startInRecovery())
            .compose(ignored -> promotion(FOLLOWER).follow(FOLLOWER, endpoint(NEW_WRITER, FOLLOWER)))
            .compose(ignored -> promotion(FOLLOWER).confirmCaughtUp(FOLLOWER, endpoint(NEW_WRITER, FOLLOWER)))
            .onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void aTargetWithoutWalLogHintsFailsTheRewindAndKeepsItsInhibitorAndConfiguration(VertxTestContext context) {
        // The former writer's last run had wal_log_hints off (plan §9.32, check 7). A server in recovery does not write
        // its parameters to the control file, so that run is outside recovery.
        diverge().compose(ignored -> withoutWalLogHints(WRITER))
            .compose(ignored -> markConfiguration(WRITER)).compose(digest ->
                process(WRITER).rewind(endpoint(NEW_WRITER, WRITER), WRITER, BUDGET).transform(rewind -> {
                    assertTrue(rewind.failed(), "A target without wal_log_hints or checksums was rewound");
                    assertInstanceOf(PgProcessControlException.class, rewind.cause());
                    assertTrue(rewind.cause().getMessage().contains("wal_log_hints"), rewind.cause().getMessage());
                    return assertKeptConfigurationAndInhibitor(WRITER, digest);
                })).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void aRewindThatFailsAfterItChangedTheDirectoryRestoresTheConfigurationAndKeepsTheSavedCopy(
            VertxTestContext context) {
        // The real pg_rewind runs and copies the source's configuration files; its result is then reported as a
        // failure, as for a pg_rewind interrupted after its file copy.
        var node = NODES.node(WRITER);
        var interrupted = new PgCtlProcessControl(new FailsAfterRunningCommandRunner(runner, "pg_rewind"), node.prefix(),
            PgSupervisedContainer.PG_CTL, NODES.dataDirectory(), node.logFile(NODES.dataDirectory()), COMMAND_TIMEOUT);
        diverge().compose(ignored -> checked(node.exec(runner, "touch", NODES.dataDirectory() + "/standby.signal")))
            .compose(ignored -> markConfiguration(WRITER)).compose(digest ->
                interrupted.rewind(endpoint(NEW_WRITER, WRITER), WRITER, BUDGET)
                    .transform(rewind -> refused(rewind, "pg_rewind failed with exit 1"))
                    .compose(ignored -> process(WRITER).inspect()).compose(observed -> {
                    assertEquals(2, observed.orElseThrow().timeline(), "The precondition failed: pg_rewind did not run");
                    return assertKeptConfigurationAndInhibitor(WRITER, digest);
                }))
            .compose(ignored -> node.exec(runner, "test", "-d", NODES.dataDirectory() + ".rejoin-saved"))
            .onSuccess(saved -> context.verify(() -> {
                assertEquals(0, saved.exitCode(), "A failed rewind removed the saved configuration");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void aRunningServerIsNeitherRewoundNorRebuilt(VertxTestContext context) {
        var source = endpoint(WRITER, FOLLOWER);
        process(FOLLOWER).rewind(source, FOLLOWER, BUDGET).transform(rewind -> refused(rewind, "PostgreSQL is running"))
            .compose(ignored -> process(FOLLOWER).rebuild(source, FOLLOWER, BUDGET)
                .transform(rebuild -> refused(rebuild, "PostgreSQL is running")))
            .compose(ignored -> NODES.node(FOLLOWER).sql(runner, "select pg_is_in_recovery()"))
            .onSuccess(recovery -> context.verify(() -> {
                assertEquals("t", recovery.output().strip(), "A refused operation changed the running standby");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void anUnreachableSourceFailsTheRewindWithTheInhibitorInPlace(VertxTestContext context) {
        var nowhere = new PgWriterEndpoint("pg-nonexistent", 5432, NODES.passfile(FOLLOWER));
        process(FOLLOWER).stop(COMMAND_TIMEOUT).compose(ignored -> markConfiguration(FOLLOWER)).compose(digest ->
            process(FOLLOWER).rewind(nowhere, FOLLOWER, BUDGET).transform(rewind -> {
                assertTrue(rewind.failed(), "A rewind against an unreachable source succeeded");
                assertInstanceOf(PgProcessControlException.class, rewind.cause());
                assertTrue(rewind.cause().getMessage().startsWith("pg_rewind failed with exit"),
                    rewind.cause().getMessage());
                return assertKeptConfigurationAndInhibitor(FOLLOWER, digest);
            })).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    // ---------------------------------------------------------------- rebuild (contract 3)

    @Test void aDivergedDataDirectoryIsRebuiltKeepsItsOwnConfigurationAndFollowsTheNewWriter(VertxTestContext context) {
        diverge().compose(ignored -> markConfiguration(WRITER)).compose(digest ->
            process(WRITER).rebuild(endpoint(NEW_WRITER, WRITER), WRITER, BUDGET)
                .compose(ignored -> assertKeptConfigurationAndInhibitor(WRITER, digest)))
            .compose(ignored -> assertNoSavedCopy(WRITER))
            .compose(ignored -> process(WRITER).startInRecovery())
            .compose(ignored -> promotion(WRITER).follow(WRITER, endpoint(NEW_WRITER, WRITER)))
            .compose(ignored -> promotion(WRITER).confirmCaughtUp(WRITER, endpoint(NEW_WRITER, WRITER)))
            .compose(ignored -> assertHistoryOfTheNewWriter(WRITER))
            .onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void anAbsentDataDirectoryIsRebuiltWithTheInhibitorInPlace(VertxTestContext context) {
        String directory = NODES.dataDirectory();
        process(FOLLOWER).stop(COMMAND_TIMEOUT)
            .compose(ignored -> checked(NODES.node(FOLLOWER).exec(runner, "rm", "-rf", directory)))
            .compose(ignored -> process(FOLLOWER).rebuild(endpoint(WRITER, FOLLOWER), FOLLOWER, BUDGET))
            .compose(ignored -> NODES.node(FOLLOWER).exec(runner, "test", "-e", directory + "/standby.signal"))
            .compose(inhibitor -> {
                assertEquals(0, inhibitor.exitCode(), "A rebuilt data directory has no start inhibitor");
                return process(FOLLOWER).startInRecovery();
            }).compose(ignored -> promotion(FOLLOWER).follow(FOLLOWER, endpoint(WRITER, FOLLOWER)))
            .compose(ignored -> promotion(FOLLOWER).confirmCaughtUp(FOLLOWER, endpoint(WRITER, FOLLOWER)))
            .onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void aRebuildThroughASlotTheSourceDoesNotHoldFailsAndKeepsTheSavedConfiguration(VertxTestContext context) {
        String directory = NODES.dataDirectory();
        // The writer drops the follower's slot; a base backup through it fails (no -C).
        process(FOLLOWER).stop(COMMAND_TIMEOUT)
            .compose(ignored -> checked(NODES.node(WRITER).sql(runner,
                "select pg_drop_replication_slot('" + PgNodeConfig.slotName(FOLLOWER) + "')")))
            .compose(ignored -> process(FOLLOWER).rebuild(endpoint(WRITER, FOLLOWER), FOLLOWER, BUDGET))
            .transform(rebuild -> {
                assertTrue(rebuild.failed(), "A base backup through a slot the source does not hold succeeded");
                assertInstanceOf(PgProcessControlException.class, rebuild.cause());
                assertTrue(rebuild.cause().getMessage().startsWith("pg_basebackup failed with exit"),
                    rebuild.cause().getMessage());
                return NODES.node(FOLLOWER).exec(runner, "test", "-f", directory + ".rejoin-saved/postgresql.conf");
            }).onSuccess(saved -> context.verify(() -> {
                assertEquals(0, saved.exitCode(), "A failed rebuild removed the saved configuration");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    // ---------------------------------------------------------------- source observation (contract 4)

    @Test void theWriterIsObservedWithItsIdentityPositionAndTheFollowersUsableSlot(VertxTestContext context) {
        NODES.node(WRITER).sql(runner, "select system_identifier from pg_control_system()").compose(identity ->
            promotion(FOLLOWER).observeSource(FOLLOWER, endpoint(WRITER, FOLLOWER)).map(source -> {
                assertEquals(identity.output().strip(), source.systemIdentifier());
                assertEquals(1, source.timeline());
                assertFalse(source.inRecovery(), "The writer was observed in recovery");
                assertTrue(source.slotUsable(), "The follower's slot on the writer was observed as not usable");
                return source;
            })).compose(source -> NODES.node(WRITER).sql(runner, "select pg_current_wal_flush_lsn()").map(flush -> {
                assertTrue(PgLsn.parse(flush.output().strip()).reached(source.flushLsn()),
                    "The observed flush position is ahead of the writer's");
                return null;
            })).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void aStandbySourceIsObservedInRecoveryAndWithoutTheSlot(VertxTestContext context) {
        // The new-writer node is still a standby here; it holds no slot for the follower.
        promotion(FOLLOWER).observeSource(FOLLOWER, endpoint(NEW_WRITER, FOLLOWER))
            .onSuccess(source -> context.verify(() -> {
                assertTrue(source.inRecovery(), "A standby source was observed as writable");
                assertFalse(source.slotUsable(), "A slot the source does not hold was observed as usable");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void aLostSlotIsNotUsable(VertxTestContext context) {
        NODES.loseSlot(vertx, runner, FOLLOWER)
            .compose(ignored -> promotion(FOLLOWER).observeSource(FOLLOWER, endpoint(WRITER, FOLLOWER)))
            .onSuccess(source -> context.verify(() -> {
                assertFalse(source.slotUsable(), "A lost slot was observed as usable");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void anUnreachableSourceFailsTheObservation(VertxTestContext context) {
        var nowhere = new PgWriterEndpoint("pg-nonexistent", 5432, NODES.passfile(FOLLOWER));
        promotion(FOLLOWER).observeSource(FOLLOWER, nowhere).onComplete(context.failing(failure -> context.verify(() -> {
            assertInstanceOf(PgPromotionException.class, failure);
            assertTrue(failure.getMessage().startsWith("Source observation failed with exit"), failure.getMessage());
            context.completeNow();
        })));
    }

    @Test void sourceOutputThatIsNotAnObservationIsRejected(VertxTestContext context) {
        // echo exits 0 and prints its arguments, so the output is not a replication command's result.
        new PgSqlPromotionControl(vertx, runner, NODES.node(FOLLOWER).prefix(), PgSupervisedContainer.PG_CTL, "echo",
            NODES.dataDirectory(), COMMAND_TIMEOUT).observeSource(FOLLOWER, endpoint(WRITER, FOLLOWER))
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgPromotionException.class, failure);
                assertTrue(failure.getMessage().startsWith("Malformed promotion observation"), failure.getMessage());
                context.completeNow();
            })));
    }

    @Test void aSlotRowOrRecoveryValueThatIsNotAnObservationIsRejected(VertxTestContext context) {
        // The identity is the writer's real one. Then the slot row has two fields, or the recovery value is neither t
        // nor f.
        var slotRow = promotion(FOLLOWER, new RewrittenOutputCommandRunner(runner, "READ_REPLICATION_SLOT",
            output -> "physical|0/3000000"), COMMAND_TIMEOUT);
        var recovery = promotion(FOLLOWER, new RewrittenOutputCommandRunner(runner, "pg_is_in_recovery()",
            output -> "unknown"), COMMAND_TIMEOUT);
        var writer = endpoint(WRITER, FOLLOWER);
        slotRow.observeSource(FOLLOWER, writer)
            .transform(source -> promotionRefused(source, "Malformed promotion observation: physical|0/3000000 "))
            .compose(ignored -> recovery.observeSource(FOLLOWER, writer).transform(source -> {
                promotionRefused(source, "Malformed promotion observation: physical|");
                assertTrue(source.cause().getMessage().endsWith(" unknown"), source.cause().getMessage());
                return Future.<Void>succeededFuture();
            })).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void aPromotionRunnerThatReturnsNoResultFailsTheObservationAndTheConfirmation(VertxTestContext context) {
        var silent = new PgSqlPromotionControl(vertx, new NoResultCommandRunner(runner), NODES.node(FOLLOWER).prefix(),
            PgSupervisedContainer.PG_CTL, "psql", NODES.dataDirectory(), COMMAND_TIMEOUT);
        String noResult = "Command returned no result";
        var writer = endpoint(WRITER, FOLLOWER);
        silent.observeSource(FOLLOWER, writer).transform(source -> promotionRefused(source, noResult))
            .compose(ignored -> silent.confirmCaughtUp(FOLLOWER, writer)
                .transform(caughtUp -> promotionRefused(caughtUp, noResult)))
            .onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    // ---------------------------------------------------------------- catch-up confirmation (contract 5)

    @Test void aStandbyStreamingFromTheWriterIsConfirmedCaughtUp(VertxTestContext context) {
        NODES.writeWal(runner, 1)
            .compose(ignored -> promotion(FOLLOWER).confirmCaughtUp(FOLLOWER, endpoint(WRITER, FOLLOWER)))
            .onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void aStandbyThatDoesNotStreamOnTheWritersTimelineIsNotConfirmedWithinTheBudget(VertxTestContext context) {
        var budget = Duration.ofSeconds(5);
        var hurried = new PgSqlPromotionControl(vertx, runner, NODES.node(FOLLOWER).prefix(),
            PgSupervisedContainer.PG_CTL, "psql", NODES.dataDirectory(), budget);
        // After the takeover the follower is still on timeline 1 and does not stream from the new writer.
        diverge().compose(ignored -> {
            long started = System.nanoTime();
            return hurried.confirmCaughtUp(FOLLOWER, endpoint(NEW_WRITER, FOLLOWER)).transform(confirmation -> {
                promotionRefused(confirmation, "The standby has not caught up with");
                assertTrue(System.nanoTime() - started < budget.plusSeconds(10).toNanos(), "The wait overran its budget");
                return Future.<Void>succeededFuture();
            });
        }).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void aStandbyWhoseReplayIsBehindTheWriterIsNotConfirmedWithinTheBudget(VertxTestContext context) {
        var budget = Duration.ofSeconds(5);
        var hurried = new PgSqlPromotionControl(vertx, runner, NODES.node(FOLLOWER).prefix(),
            PgSupervisedContainer.PG_CTL, "psql", NODES.dataDirectory(), budget);
        // The follower streams from the writer on its timeline, but its replay is paused behind the writer's position.
        checked(NODES.node(FOLLOWER).sql(runner, "select pg_wal_replay_pause()"))
            .compose(ignored -> NODES.writeWal(runner, 1))
            .compose(ignored -> hurried.confirmCaughtUp(FOLLOWER, endpoint(WRITER, FOLLOWER))
                .transform(confirmation -> promotionRefused(confirmation, "The standby has not caught up with")))
            .compose(ignored -> checked(NODES.node(FOLLOWER).sql(runner, "select pg_wal_replay_resume()")))
            .onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void aReceiverOnAnotherTimelineThanTheWritersIsNotConfirmedWithinTheBudget(VertxTestContext context) {
        var budget = Duration.ofSeconds(5);
        // The follower streams from the writer and has replayed its position; only the reported receiver timeline
        // differs from the writer's. The real confirmation afterwards shows that no other field was missing.
        var otherTimeline = promotion(FOLLOWER, new RewrittenOutputCommandRunner(runner, "pg_last_wal_replay_lsn()",
            output -> withField(output, 3, timeline -> String.valueOf(Long.parseLong(timeline) + 1))), budget);
        NODES.writeWal(runner, 1)
            .compose(ignored -> otherTimeline.confirmCaughtUp(FOLLOWER, endpoint(WRITER, FOLLOWER))
                .transform(confirmation -> promotionRefused(confirmation, "The standby has not caught up with")))
            .compose(ignored -> promotion(FOLLOWER).confirmCaughtUp(FOLLOWER, endpoint(WRITER, FOLLOWER)))
            .onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void aStandbyOfAnotherSystemIsNotConfirmedCaughtUp(VertxTestContext context) {
        // The follower streams from the writer and has replayed its position; only the reported system identifier
        // differs from the writer's. The real confirmation afterwards shows that no other field was missing.
        var otherSystem = promotion(FOLLOWER, new RewrittenOutputCommandRunner(runner, "pg_last_wal_replay_lsn()",
            output -> withField(output, 1, identifier -> identifier.equals("1") ? "2" : "1")), COMMAND_TIMEOUT);
        NODES.writeWal(runner, 1)
            .compose(ignored -> otherSystem.confirmCaughtUp(FOLLOWER, endpoint(WRITER, FOLLOWER))
                .transform(confirmation -> promotionRefused(confirmation, "The standby's system identifier")))
            .compose(ignored -> promotion(FOLLOWER).confirmCaughtUp(FOLLOWER, endpoint(WRITER, FOLLOWER)))
            .onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    // ---------------------------------------------------------------- helpers

    /**
     * A takeover in which the former writer diverges: the new writer is promoted through the adapter, takes its
     * checkpoint, and creates the slots of the other members, as {@code promoteStandby} does; the former writer, still
     * writable, writes WAL its successor never receives and is then stopped at once, as a host loss stops it; the new
     * writer writes WAL of its own afterwards.
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
     * setting, then a fast stop. The control file is read to confirm the precondition.
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
     * Adds a line to the node's own {@code postgresql.conf}, {@code pg_hba.conf}, and {@code pg_ident.conf}, then returns
     * the digest of its node-local files. The fixture's base backups give every node the same copy of these three files,
     * so without the line a node that received the source's copy would show the same digest. A rewind and a base
     * backup both copy all three from the source (plan §9.32, checks 4 and 9). {@code postgresql.auto.conf} already
     * differs between nodes: each standby's copy names its own replication user.
     */
    private Future<String> markConfiguration(String nodeId) {
        String directory = NODES.dataDirectory();
        return checked(NODES.node(nodeId).exec(runner, "sh", "-c", "for file in postgresql.conf pg_hba.conf pg_ident.conf;"
            + " do printf '" + MARKER + "\\n' >> " + directory + "/$file || exit 1; done"))
            .compose(ignored -> configurationDigest(nodeId));
    }

    private Future<String> configurationDigest(String nodeId) {
        String directory = NODES.dataDirectory();
        return NODES.node(nodeId).exec(runner, "md5sum", directory + "/postgresql.conf", directory + "/postgresql.auto.conf",
            directory + "/pg_hba.conf", directory + "/pg_ident.conf").map(digest -> {
                assertEquals(0, digest.exitCode(), digest.output());
                return digest.output().strip();
            });
    }

    /** The node's own configuration files are unchanged and the inhibitor is in place. */
    private Future<Void> assertKeptConfigurationAndInhibitor(String nodeId, String digest) {
        String directory = NODES.dataDirectory();
        var node = NODES.node(nodeId);
        return configurationDigest(nodeId).compose(after -> {
            assertEquals(digest, after, "The node's own configuration files were not kept");
            return node.exec(runner, "test", "-e", directory + "/standby.signal");
        }).map(inhibitor -> {
            assertEquals(0, inhibitor.exitCode(), "The start inhibitor is not in place");
            return null;
        });
    }

    /** A successful rewind or rebuild removes the copy of the configuration files it saved. */
    private Future<Void> assertNoSavedCopy(String nodeId) {
        return NODES.node(nodeId).exec(runner, "test", "!", "-e", NODES.dataDirectory() + ".rejoin-saved").map(absent -> {
            assertEquals(0, absent.exitCode(), "A successful operation left its saved configuration copy");
            return null;
        });
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

    private PgWriterEndpoint endpoint(String source, String member) {
        return NODES.endpoint(source, member);
    }

    private PgProcessControl process(String nodeId) {
        return NODES.control(runner, nodeId);
    }

    private PgProcessControl processAt(String nodeId, String directory) {
        var node = NODES.node(nodeId);
        return new PgCtlProcessControl(runner, node.prefix(), PgSupervisedContainer.PG_CTL, directory,
            node.logFile(directory), COMMAND_TIMEOUT);
    }

    private PgPromotionControl promotion(String nodeId) {
        return new PgSqlPromotionControl(vertx, runner, NODES.node(nodeId).prefix(), PgSupervisedContainer.PG_CTL,
            "psql", NODES.dataDirectory(), COMMAND_TIMEOUT);
    }

    private PgPromotionControl promotion(String nodeId, PgCommandRunner commands, Duration budget) {
        return new PgSqlPromotionControl(vertx, commands, NODES.node(nodeId).prefix(), PgSupervisedContainer.PG_CTL,
            "psql", NODES.dataDirectory(), budget);
    }

    private PgDurabilityControl durability(String nodeId) {
        return new PgSqlDurabilityControl(vertx, runner, NODES.node(nodeId).prefix(), PgSupervisedContainer.PG_CTL,
            "psql", NODES.dataDirectory(), COMMAND_TIMEOUT);
    }

    /** The output with one {@code |}-separated field changed, when the output has that field and it is not empty. */
    private static String withField(String output, int index, java.util.function.UnaryOperator<String> change) {
        String[] fields = output.strip().split("\\|", -1);
        if (fields.length <= index || fields[index].isEmpty()) return output;
        fields[index] = change.apply(fields[index]);
        return String.join("|", fields);
    }

    private static Future<Void> checked(Future<PgCommandResult> command) {
        return command.map(result -> {
            assertEquals(0, result.exitCode(), result.output());
            return null;
        });
    }

    /**
     * Asserts that the operation failed with a {@link PgProcessControlException} whose message starts with the prefix,
     * and succeeds, so that a failed assertion ends the chain with its own message.
     */
    private static Future<Void> refused(AsyncResult<?> outcome, String messagePrefix) {
        assertTrue(outcome.failed(), "The operation succeeded; expected a failure starting with: " + messagePrefix);
        assertInstanceOf(PgProcessControlException.class, outcome.cause());
        assertTrue(outcome.cause().getMessage().startsWith(messagePrefix), outcome.cause().getMessage());
        return Future.succeededFuture();
    }

    /** As {@link #refused}, for a {@link PgPromotionException}. */
    private static Future<Void> promotionRefused(AsyncResult<?> outcome, String messagePrefix) {
        assertTrue(outcome.failed(), "The operation succeeded; expected a failure starting with: " + messagePrefix);
        assertInstanceOf(PgPromotionException.class, outcome.cause());
        assertTrue(outcome.cause().getMessage().startsWith(messagePrefix), outcome.cause().getMessage());
        return Future.succeededFuture();
    }
}
