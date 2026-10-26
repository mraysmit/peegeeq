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
        return Future.failedFuture(new UnsupportedOperationException("not implemented"));
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
