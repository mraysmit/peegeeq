package dev.mars.peegeeq.failover;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Durability control through SQL on the local socket. A policy is installed with
 * {@code ALTER SYSTEM}, which persists it in {@code postgresql.auto.conf}, followed by
 * {@code pg_ctl reload} (OD-9). The reload is confirmed when the configuration load time changes.
 * The setting in effect must then equal the generated value, and every required standby must
 * report quorum synchronous state. Peer identity is the authenticated replication role.
 */
public final class PgSqlDurabilityControl implements PgDurabilityControl {
    @FunctionalInterface
    private interface Parser<T> { T parse(); }
    private static final Logger logger = LoggerFactory.getLogger(PgSqlDurabilityControl.class);
    private static final long POLL_MILLIS = 100;
    private static final String LOAD_TIME = "select extract(epoch from pg_conf_load_time())";
    private static final String WRITER = "select json_build_object('inRecovery', pg_is_in_recovery(), "
        + "'flushLsn', case when pg_is_in_recovery() then null else pg_current_wal_flush_lsn()::text end)";
    private static final String PEERS = "select coalesce(json_agg(json_build_object('role', usename, "
        + "'applicationName', application_name, 'state', state, 'syncState', sync_state, "
        + "'flushLsn', flush_lsn::text, 'replayLsn', replay_lsn::text)), '[]'::json) from pg_stat_replication";
    private final Vertx vertx;
    private final PgCommandRunner runner;
    private final List<String> commandPrefix;
    private final String pgCtl;
    private final String psql;
    private final String dataDirectory;
    private final Duration commandTimeout;

    /** @param commandTimeout the budget of one operation, covering every command it runs */
    public PgSqlDurabilityControl(Vertx vertx, PgCommandRunner runner, List<String> commandPrefix, String pgCtl,
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
    public Future<PgLsn> flushBoundary() {
        return flushBoundary(deadline());
    }

    private Future<PgLsn> flushBoundary(long deadline) {
        return sql(deadline, WRITER).map(output -> {
            JsonObject observed = observation(output, () -> new JsonObject(output));
            Object inRecovery = observed.getValue("inRecovery");
            if (!(inRecovery instanceof Boolean)) throw new PgDurabilityException("Malformed durability observation: " + output);
            if (Boolean.TRUE.equals(inRecovery)) {
                throw new PgDurabilityException("PostgreSQL is in recovery; it has no writer flush position");
            }
            return observation(output, () -> PgLsn.parse(observed.getString("flushLsn")));
        });
    }

    @Override
    public Future<List<PgReplicationPeer>> peers() {
        return peers(deadline());
    }

    @Override
    public Future<Void> confirmCovered(PgDurabilityPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        long deadline = deadline();
        return flushBoundary(deadline).compose(boundary -> awaitCovered(deadline, policy, boundary));
    }

    @Override
    public Future<Void> install(PgDurabilityPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        long deadline = deadline();
        String names = policy.synchronousStandbyNames();
        return sql(deadline, LOAD_TIME).compose(before ->
                sql(deadline, "alter system set synchronous_standby_names = " + escapeString(names))
                    .compose(ignored -> run(deadline, pgCtl, "reload", "-D", dataDirectory))
                    .map(result -> requireSuccess(result, "pg_ctl reload"))
                    .compose(ignored -> awaitChange(deadline, before)))
            .compose(ignored -> sql(deadline, "show synchronous_standby_names"))
            .map(effective -> {
                if (!names.equals(effective)) {
                    throw new PgDurabilityException("The setting in effect is " + effective + ", not the installed " + names);
                }
                return null;
            }).compose(ignored -> awaitQuorum(deadline, policy));
    }

    private Future<List<PgReplicationPeer>> peers(long deadline) {
        return sql(deadline, PEERS).map(output -> observation(output, () -> {
            List<PgReplicationPeer> peers = new ArrayList<>();
            for (Object element : new JsonArray(output)) {
                JsonObject peer = (JsonObject) element;
                peers.add(new PgReplicationPeer(peer.getString("role"), peer.getString("applicationName"),
                    peer.getString("state"), peer.getString("syncState"), lsn(peer.getString("flushLsn")),
                    lsn(peer.getString("replayLsn"))));
            }
            return List.copyOf(peers);
        }));
    }

    /**
     * A standby catches up on its own, so coverage of the fixed boundary is observed until it holds.
     * Only a standby that is not covered yet is observed again. A failed observation and an identity
     * mismatch end the wait at once, because waiting does not change them. The wait ends with the
     * last coverage failure when another observation would not fit in the budget.
     */
    private Future<Void> awaitCovered(long deadline, PgDurabilityPolicy policy, PgLsn boundary) {
        long started = System.nanoTime();
        return peers(deadline).compose(peers -> {
            PgStandbyNotCoveredException uncovered;
            try {
                policy.requireCoveredAt(boundary, peers);
                return Future.<Void>succeededFuture();
            } catch (PgStandbyNotCoveredException failure) {
                uncovered = failure;
            }
            long observation = System.nanoTime() - started;
            if (deadline - System.nanoTime() < 2 * observation + TimeUnit.MILLISECONDS.toNanos(POLL_MILLIS)) {
                return Future.<Void>failedFuture(new PgDurabilityException("Required standbys were not covered at "
                    + boundary + " within the budget: " + uncovered.getMessage(), uncovered));
            }
            return vertx.timer(POLL_MILLIS).compose(ignored -> awaitCovered(deadline, policy, boundary));
        });
    }

    /** A reload is asynchronous. It is confirmed when the configuration load time has changed. */
    private Future<Void> awaitChange(long deadline, String before) {
        return sql(deadline, LOAD_TIME).compose(current -> current.equals(before)
            ? vertx.timer(POLL_MILLIS).compose(ignored -> awaitChange(deadline, before))
            : Future.<Void>succeededFuture());
    }

    /**
     * Each walsender applies the reloaded setting on its own, so quorum state is observed until it
     * holds. The wait ends with the last observation when another one would not fit in the budget.
     */
    private Future<Void> awaitQuorum(long deadline, PgDurabilityPolicy policy) {
        long started = System.nanoTime();
        return peers(deadline).compose(peers -> {
            List<String> missing = policy.requiredStandbyNodeIds().stream()
                .filter(standby -> peers.stream().noneMatch(peer -> standby.equals(peer.role())
                    && standby.equals(peer.applicationName()) && "quorum".equals(peer.syncState())))
                .toList();
            if (missing.isEmpty()) return Future.<Void>succeededFuture();
            long observation = System.nanoTime() - started;
            if (deadline - System.nanoTime() < 2 * observation + TimeUnit.MILLISECONDS.toNanos(POLL_MILLIS)) {
                return Future.<Void>failedFuture(new PgDurabilityException(
                    "Required standbys did not report quorum synchronous state within the budget: " + missing
                        + "; observed " + peers));
            }
            return vertx.timer(POLL_MILLIS).compose(ignored -> awaitQuorum(deadline, policy));
        });
    }

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
            return Future.failedFuture(new PgDurabilityException(
                "Durability budget exhausted before " + command[0] + " could run"));
        }
        List<String> full = new ArrayList<>(commandPrefix);
        full.addAll(List.of(command));
        return runner.run(full, Duration.ofNanos(remaining)).transform(outcome -> {
            if (outcome.failed()) {
                PgDurabilityException failure = new PgDurabilityException("Command failed: " + command[0], outcome.cause());
                logger.error("Durability command failed: {}", command[0], failure);
                return Future.failedFuture(failure);
            }
            if (outcome.result() == null) {
                return Future.failedFuture(new PgDurabilityException("Command returned no result: " + command[0]));
            }
            return Future.succeededFuture(outcome.result());
        });
    }

    private long deadline() {
        return System.nanoTime() + commandTimeout.toNanos();
    }

    private static <T> T observation(String output, Parser<T> parser) {
        try {
            return parser.parse();
        } catch (RuntimeException malformed) {
            throw new PgDurabilityException("Malformed durability observation: " + output, malformed);
        }
    }

    /**
     * An escape-string literal with no double quote in the command line. Windows does not pass an
     * embedded double quote through a child process's arguments; PostgreSQL decodes {@code \x22}.
     */
    private static String escapeString(String value) {
        return "E'" + value.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\x22") + "'";
    }

    private static PgLsn lsn(String text) {
        return text == null ? null : PgLsn.parse(text);
    }

    private static Void requireSuccess(PgCommandResult result, String action) {
        if (result.exitCode() != 0) {
            throw new PgDurabilityException(action + " failed with exit " + result.exitCode() + ": " + result.output());
        }
        return null;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
