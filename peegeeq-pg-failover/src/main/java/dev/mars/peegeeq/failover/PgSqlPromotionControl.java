package dev.mars.peegeeq.failover;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Promotion control through {@code pg_ctl promote} and SQL on the local socket. The exit code of the
 * promote command is not the evidence: the role is observed afterwards. The checkpoint after promotion
 * is forced and verified by an advanced checkpoint position on the timeline of the current WAL position
 * (OD-18). Each operation has one budget that covers every command it runs.
 */
public final class PgSqlPromotionControl implements PgPromotionControl {
    private static final Logger logger = LoggerFactory.getLogger(PgSqlPromotionControl.class);
    private static final long POLL_MILLIS = 100;
    private static final String RECOVERY = "select pg_is_in_recovery()";
    private static final String CHECKPOINT = "select json_build_object('checkpointLsn', checkpoint_lsn::text, "
        + "'checkpointTimeline', timeline_id, "
        + "'walTimeline', ('x' || substr(pg_walfile_name(pg_current_wal_lsn()), 1, 8))::bit(32)::int) "
        + "from pg_control_checkpoint()";
    // The wait event is part of the observation: the receiver waits on WalReceiverMain only inside its streaming loop,
    // which it enters after START_REPLICATION has succeeded (PostgreSQL 15 walreceiver.c).
    private static final String RECEIVER = "select r.status || '|' || coalesce(r.sender_host, '') || '|' "
        + "|| coalesce(r.sender_port::text, '') || '|' || coalesce(r.slot_name, '') || '|' || coalesce(a.wait_event, '') "
        + "from pg_stat_wal_receiver r left join pg_stat_activity a on a.pid = r.pid";
    private static final String STREAMING_WAIT_EVENT = "WalReceiverMain";
    // recovery|system identifier|status|timeline|sender host|sender port|wait event|replay position; the receiver's
    // fields are empty when there is no receiver.
    private static final String CAUGHT_UP = "select pg_is_in_recovery()::text || '|' "
        + "|| (select system_identifier from pg_control_system())::text || '|' "
        + "|| coalesce((select r.status || '|' || r.received_tli::text || '|' || coalesce(r.sender_host, '') || '|' "
        + "|| coalesce(r.sender_port::text, '') || '|' || coalesce(a.wait_event, '') "
        + "from pg_stat_wal_receiver r left join pg_stat_activity a on a.pid = r.pid), '||||') || '|' "
        + "|| coalesce(pg_last_wal_replay_lsn()::text, '')";
    private static final Pattern RECEIVER_OBSERVATION =
        Pattern.compile("[a-z]+\\|[^|]*\\|[0-9]*\\|[a-z0-9_]*\\|[A-Za-z]*");
    private final Vertx vertx;
    private final PgCommandRunner runner;
    private final List<String> commandPrefix;
    private final String pgCtl;
    private final String psql;
    private final String dataDirectory;
    private final Duration commandTimeout;

    /** @param commandTimeout the budget of one operation, covering every command it runs */
    public PgSqlPromotionControl(Vertx vertx, PgCommandRunner runner, List<String> commandPrefix, String pgCtl,
                                 String psql, String dataDirectory, Duration commandTimeout) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.commandPrefix = List.copyOf(commandPrefix);
        this.pgCtl = requireText(pgCtl, "pgCtl");
        this.psql = requireText(psql, "psql");
        this.dataDirectory = requireText(dataDirectory, "dataDirectory");
        if (commandTimeout == null || commandTimeout.toMillis() <= 0) {
            throw new IllegalArgumentException("Command timeout must be positive");
        }
        this.commandTimeout = commandTimeout;
    }

    @Override
    public Future<Boolean> inRecovery() {
        return inRecovery(deadline());
    }

    @Override
    public Future<Void> promote() {
        long deadline = deadline();
        return inRecovery(deadline).compose(recovering -> {
            if (!recovering) {
                throw new PgPromotionException("PostgreSQL is not in recovery; there is nothing to promote");
            }
            return run(deadline, pgCtl, "promote", "-D", dataDirectory, "-w", "-t", seconds(deadline));
        }).map(result -> requireSuccess(result, "pg_ctl promote")).compose(ignored -> awaitPromoted(deadline));
    }

    @Override
    public Future<Void> checkpoint() {
        long deadline = deadline();
        return inRecovery(deadline).compose(recovering -> {
            if (recovering) {
                throw new PgPromotionException("PostgreSQL is in recovery; no checkpoint can follow a promotion");
            }
            return checkpointPosition(deadline);
        }).compose(before -> sql(deadline, "checkpoint")
            .compose(ignored -> checkpointPosition(deadline)).map(after -> {
                if (!after.lsn().reached(before.lsn()) || before.lsn().reached(after.lsn())) {
                    throw new PgPromotionException("The checkpoint did not advance: " + before.lsn() + " then "
                        + after.lsn());
                }
                if (after.checkpointTimeline() != after.walTimeline()) {
                    throw new PgPromotionException("The latest checkpoint is on timeline " + after.checkpointTimeline()
                        + ", not on timeline " + after.walTimeline());
                }
                return null;
            }));
    }

    @Override
    public Future<Void> follow(String nodeId, PgWriterEndpoint writer) {
        Objects.requireNonNull(writer, "writer");
        // Built from validated parts only: neither holds a single quote, and the connection string holds no password.
        String connectionInfo = writer.connectionInfo(nodeId);
        String slot = PgNodeConfig.slotName(nodeId);
        long deadline = deadline();
        return inRecovery(deadline).compose(recovering -> {
            if (!recovering) {
                throw new PgPromotionException(
                    "PostgreSQL is writable; a writable server cannot follow a writer without a rewind");
            }
            return requireNoRejectedFile(deadline);
        })
            // Two commands: ALTER SYSTEM cannot run inside the implicit transaction of a multi-statement command.
            .compose(ignored -> sql(deadline, "alter system set primary_conninfo = '" + connectionInfo + "'"))
            .compose(ignored -> sql(deadline, "alter system set primary_slot_name = '" + slot + "'"))
            .compose(ignored -> run(deadline, pgCtl, "reload", "-D", dataDirectory))
            .map(result -> requireSuccess(result, "pg_ctl reload"))
            .compose(ignored -> awaitStreaming(deadline, writer, slot));
    }

    /**
     * Three observations through this node's own role (plan §9.32, contract 4): {@code IDENTIFY_SYSTEM} and
     * {@code READ_REPLICATION_SLOT} on a replication connection, and {@code pg_is_in_recovery()} on database
     * {@code postgres}, because a rewind accepts a source in recovery (check 5). A missing slot reads {@code ||} and a
     * lost one {@code physical||} (check 1); neither is usable.
     */
    @Override
    public Future<PgReplicationSource> observeSource(String nodeId, PgWriterEndpoint source) {
        Objects.requireNonNull(source, "source");
        String replication = source.connectionInfo(nodeId) + " replication=true";
        String database = source.connectionInfo(nodeId) + " dbname=postgres";
        String slot = PgNodeConfig.slotName(nodeId);
        long deadline = deadline();
        return remote(deadline, replication, "IDENTIFY_SYSTEM").map(PgSqlPromotionControl::identity)
            .compose(identity -> remote(deadline, replication, "READ_REPLICATION_SLOT " + slot).compose(slotRow ->
                remote(deadline, database, RECOVERY).map(recovery -> {
                    String[] parts = slotRow.split("\\|", -1);
                    if (parts.length != 3 || !("t".equals(recovery) || "f".equals(recovery))) {
                        throw new PgPromotionException("Malformed promotion observation: " + slotRow + " " + recovery);
                    }
                    return new PgReplicationSource(identity.systemIdentifier(), identity.timeline(), identity.lsn(),
                        "t".equals(recovery), "physical".equals(parts[0]) && !parts[1].isEmpty());
                })));
    }

    /**
     * Observes the writer's {@code IDENTIFY_SYSTEM} once, then polls the local server until it is in recovery, has the
     * writer's system identifier, and its WAL receiver streams from the writer's address on the writer's timeline in its
     * streaming loop, with replay at or after the observed flush position. A server that is not in recovery, or of
     * another system, fails at once, because waiting cannot change either.
     */
    @Override
    public Future<Void> confirmCaughtUp(String nodeId, PgWriterEndpoint writer) {
        Objects.requireNonNull(writer, "writer");
        String replication = writer.connectionInfo(nodeId) + " replication=true";
        long deadline = deadline();
        return remote(deadline, replication, "IDENTIFY_SYSTEM").map(PgSqlPromotionControl::identity)
            .compose(target -> awaitCaughtUp(deadline, writer, target));
    }

    private Future<Void> awaitCaughtUp(long deadline, PgWriterEndpoint writer, Identity target) {
        long started = System.nanoTime();
        return sql(deadline, CAUGHT_UP).compose(output -> {
            String[] parts = output.split("\\|", -1);
            if (parts.length != 8 || !("true".equals(parts[0]) || "false".equals(parts[0]))) {
                throw new PgPromotionException("Malformed promotion observation: " + output);
            }
            if (!"true".equals(parts[0])) {
                throw new PgPromotionException("PostgreSQL is not in recovery; it is not a standby of " + writer.host());
            }
            if (!target.systemIdentifier().equals(parts[1])) {
                throw new PgPromotionException("The standby's system identifier " + parts[1] + " is not the writer's "
                    + target.systemIdentifier());
            }
            if (caughtUp(parts, writer, target)) return Future.<Void>succeededFuture();
            long observation = System.nanoTime() - started;
            if (deadline - System.nanoTime() < 2 * observation + TimeUnit.MILLISECONDS.toNanos(POLL_MILLIS)) {
                return Future.<Void>failedFuture(new PgPromotionException("The standby has not caught up with "
                    + writer.host() + ":" + writer.port() + " at timeline " + target.timeline() + " and " + target.lsn()
                    + " within the budget; last observed: " + output));
            }
            return vertx.timer(POLL_MILLIS).compose(ignored -> awaitCaughtUp(deadline, writer, target));
        });
    }

    private static boolean caughtUp(String[] parts, PgWriterEndpoint writer, Identity target) {
        if (!"streaming".equals(parts[2]) || !String.valueOf(target.timeline()).equals(parts[3])
                || !writer.host().equals(parts[4]) || !String.valueOf(writer.port()).equals(parts[5])
                || !STREAMING_WAIT_EVENT.equals(parts[6]) || parts[7].isEmpty()) {
            return false;
        }
        try {
            return PgLsn.parse(parts[7]).reached(target.lsn());
        } catch (IllegalArgumentException malformed) {
            throw new PgPromotionException("Malformed promotion observation: " + String.join("|", parts), malformed);
        }
    }

    /** {@code systemid|timeline|xlogpos|dbname} of {@code IDENTIFY_SYSTEM}; the database is empty on a physical connection. */
    private static Identity identity(String output) {
        String[] parts = output.split("\\|", -1);
        try {
            if (parts.length != 4) throw new IllegalArgumentException("Expected four fields");
            return new Identity(parts[0], Long.parseLong(parts[1]), PgLsn.parse(parts[2]));
        } catch (RuntimeException malformed) {
            throw new PgPromotionException("Malformed promotion observation: " + output, malformed);
        }
    }

    /** The writer's identity and position, observed live, never stored. */
    private record Identity(String systemIdentifier, long timeline, PgLsn lsn) {
        Identity {
            if (systemIdentifier == null || !systemIdentifier.matches("[0-9]{1,20}") || timeline < 1) {
                throw new IllegalArgumentException("Invalid identity");
            }
        }
    }

    /** One statement on another server, through a connection string that holds no password. The trimmed output is the result. */
    private Future<String> remote(long deadline, String connection, String statement) {
        return run(deadline, psql, "-X", "-tA", "-v", "ON_ERROR_STOP=1", connection, "-c", statement).map(result -> {
            if (result.exitCode() != 0) {
                throw new PgPromotionException("Source observation failed with exit " + result.exitCode() + ": "
                    + result.output());
            }
            return result.output().strip();
        });
    }

    /**
     * A reload is asynchronous. It is confirmed when the WAL receiver streams from exactly the requested address
     * through exactly the requested slot, and waits in its streaming loop. The status, address, and slot alone are not
     * evidence: PostgreSQL reports {@code streaming} with them as soon as the receiver has connected, before
     * {@code START_REPLICATION} runs, and a missing slot fails it after that.
     */
    private Future<Void> awaitStreaming(long deadline, PgWriterEndpoint writer, String slot) {
        long started = System.nanoTime();
        return sql(deadline, RECEIVER).compose(output -> {
            if (!output.isEmpty() && !RECEIVER_OBSERVATION.matcher(output).matches()) {
                throw new PgPromotionException("Malformed promotion observation: " + output);
            }
            if (output.equals("streaming|" + writer.host() + "|" + writer.port() + "|" + slot + "|"
                + STREAMING_WAIT_EVENT)) {
                return Future.<Void>succeededFuture();
            }
            long observation = System.nanoTime() - started;
            if (deadline - System.nanoTime() < 2 * observation + TimeUnit.MILLISECONDS.toNanos(POLL_MILLIS)) {
                return Future.<Void>failedFuture(new PgPromotionException("WAL receiver is not streaming from "
                    + writer.host() + ":" + writer.port() + " through slot " + slot + " within the budget; last observed: "
                    + (output.isEmpty() ? "no WAL receiver" : output)));
            }
            return vertx.timer(POLL_MILLIS).compose(ignored -> awaitStreaming(deadline, writer, slot));
        });
    }

    /**
     * A configuration file that PostgreSQL rejects whole makes a reload apply no change (plan §9.33, check 6), so the new
     * connection string would never take effect and the wait for the WAL receiver would end only at the budget. Such a
     * file refuses the follow before {@code ALTER SYSTEM} runs.
     */
    private Future<Void> requireNoRejectedFile(long deadline) {
        return sql(deadline, PgConfigurationFiles.ERRORS).map(output -> {
            PgConfigurationFiles.Errors errors;
            try {
                errors = PgConfigurationFiles.parse(output);
            } catch (RuntimeException malformed) {
                throw new PgPromotionException("Malformed promotion observation: " + output, malformed);
            }
            if (!errors.rejected().isEmpty()) {
                throw new PgPromotionException("The configuration files hold errors that PostgreSQL rejects, so a reload "
                    + "would apply nothing; the follow was not started: " + errors.rejected());
            }
            return null;
        });
    }

    private Future<Boolean> inRecovery(long deadline) {
        return sql(deadline, RECOVERY).map(output -> {
            if ("t".equals(output)) return true;
            if ("f".equals(output)) return false;
            throw new PgPromotionException("Malformed promotion observation: " + output);
        });
    }

    /** The promotion is confirmed when the server has left recovery. Each observation is bounded by the budget. */
    private Future<Void> awaitPromoted(long deadline) {
        long started = System.nanoTime();
        return inRecovery(deadline).compose(recovering -> {
            if (!recovering) return Future.<Void>succeededFuture();
            long observation = System.nanoTime() - started;
            if (deadline - System.nanoTime() < 2 * observation + TimeUnit.MILLISECONDS.toNanos(POLL_MILLIS)) {
                return Future.<Void>failedFuture(new PgPromotionException(
                    "PostgreSQL is still in recovery after pg_ctl promote within the budget"));
            }
            return vertx.timer(POLL_MILLIS).compose(ignored -> awaitPromoted(deadline));
        });
    }

    private Future<CheckpointPosition> checkpointPosition(long deadline) {
        return sql(deadline, CHECKPOINT).map(output -> {
            try {
                JsonObject observed = new JsonObject(output);
                return new CheckpointPosition(PgLsn.parse(observed.getString("checkpointLsn")),
                    observed.getLong("checkpointTimeline"), observed.getLong("walTimeline"));
            } catch (RuntimeException malformed) {
                throw new PgPromotionException("Malformed promotion observation: " + output, malformed);
            }
        });
    }

    /** The latest checkpoint, and the timeline of the current WAL position. Observed live, never stored. */
    private record CheckpointPosition(PgLsn lsn, long checkpointTimeline, long walTimeline) { }

    /** One statement through the local socket. The trimmed output is the result. */
    private Future<String> sql(long deadline, String statement) {
        return run(deadline, psql, "-X", "-tA", "-v", "ON_ERROR_STOP=1", "-c", statement).map(result -> {
            requireSuccess(result, "Local SQL");
            return result.output().strip();
        });
    }

    /** Every command is bounded by the time that remains. An exhausted budget is a failure. */
    private Future<PgCommandResult> run(long deadline, String... command) {
        long remaining = deadline - System.nanoTime();
        if (remaining < 1_000_000) {
            return Future.failedFuture(new PgPromotionException(
                "Promotion budget exhausted before " + command[0] + " could run"));
        }
        List<String> full = new ArrayList<>(commandPrefix);
        full.addAll(List.of(command));
        return runner.run(full, Duration.ofNanos(remaining)).transform(outcome -> {
            if (outcome.failed()) {
                PgPromotionException failure = new PgPromotionException("Command failed: " + command[0], outcome.cause());
                logger.error("Promotion command failed: {}", command[0], failure);
                return Future.failedFuture(failure);
            }
            if (outcome.result() == null) {
                return Future.failedFuture(new PgPromotionException("Command returned no result: " + command[0]));
            }
            return Future.succeededFuture(outcome.result());
        });
    }

    private long deadline() {
        return System.nanoTime() + commandTimeout.toNanos();
    }

    /** Whole seconds that remain, at least one, as {@code pg_ctl -t} requires. */
    private static String seconds(long deadline) {
        return String.valueOf(Math.max(1, TimeUnit.NANOSECONDS.toSeconds(deadline - System.nanoTime())));
    }

    private static Void requireSuccess(PgCommandResult result, String action) {
        if (result.exitCode() != 0) {
            throw new PgPromotionException(action + " failed with exit " + result.exitCode() + ": " + result.output());
        }
        return null;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
