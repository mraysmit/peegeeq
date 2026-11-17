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
 * Configuration control through SQL on the local socket and {@code pg_ctl reload} (plan §9.33, contract 1). Each
 * operation has one budget that covers every command it runs.
 *
 * <p>No reload is sent for a file that PostgreSQL would reject: with an unknown parameter or a syntax error it applies
 * nothing and leaves the load time unchanged, and with an invalid {@code pg_hba.conf} rule it keeps the previous rules
 * (plan §9.33, checks 6 and 7). Both views read the files when they are queried, so they report these before the reload.
 * An invalid value of a known parameter carries the same error text as a valid change that waits for a restart; only
 * {@code pg_settings.pending_restart} after the reload tells them apart (check 8). PostgreSQL applies the other changes
 * of such a file (check 5), and the reload then fails, naming the invalid values.
 */
public final class PgSqlConfigurationControl implements PgConfigurationControl {
    @FunctionalInterface
    private interface Parser<T> { T parse(); }
    private static final Logger logger = LoggerFactory.getLogger(PgSqlConfigurationControl.class);
    private static final long POLL_MILLIS = 100;
    private static final String LOAD_TIME = "select extract(epoch from pg_conf_load_time())";
    private static final String SETTINGS = "select json_build_object("
        + "'postmasterStart', (extract(epoch from pg_postmaster_start_time()) * 1000000)::bigint, "
        + "'serverVersion', current_setting('server_version_num')::int, "
        + "'inRecovery', pg_is_in_recovery(), "
        + "'pendingRestart', coalesce((select json_agg(name order by name) from pg_settings where pending_restart), "
        + "'[]'::json))";
    private final Vertx vertx;
    private final PgCommandRunner runner;
    private final List<String> commandPrefix;
    private final String pgCtl;
    private final String psql;
    private final String dataDirectory;
    private final Duration commandTimeout;

    /** @param commandTimeout the budget of one operation, covering every command it runs */
    public PgSqlConfigurationControl(Vertx vertx, PgCommandRunner runner, List<String> commandPrefix, String pgCtl,
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
    public Future<PgServerSettings> settings() {
        return sql(deadline(), SETTINGS).map(output -> {
            JsonObject observed = observation(output, () -> new JsonObject(output));
            if (!(observed.getValue("postmasterStart") instanceof Number start)
                    || !(observed.getValue("serverVersion") instanceof Number version)
                    || !(observed.getValue("inRecovery") instanceof Boolean inRecovery)
                    || !(observed.getValue("pendingRestart") instanceof JsonArray pending)) {
                throw new PgConfigurationException("Malformed configuration observation: " + output);
            }
            return observation(output, () -> new PgServerSettings(start.longValue(), version.intValue(), inRecovery,
                pending.stream().map(name -> (String) name).toList()));
        });
    }

    @Override
    public Future<Void> reload() {
        long deadline = deadline();
        return fileErrors(deadline).map(before -> {
                // An invalid value cannot be told apart from a pending restart before the reload (check 8).
                List<Object> refused = before.wholeFile();
                if (!refused.isEmpty()) {
                    throw new PgConfigurationException("The configuration files hold errors that PostgreSQL would "
                        + "reject, so no reload was sent: " + refused);
                }
                return null;
            })
            .compose(ignored -> sql(deadline, LOAD_TIME))
            .compose(before -> run(deadline, pgCtl, "reload", "-D", dataDirectory)
                .map(result -> requireSuccess(result, "pg_ctl reload"))
                .compose(ignored -> awaitChange(deadline, before)))
            .compose(ignored -> fileErrors(deadline)).map(after -> {
                if (!after.wholeFile().isEmpty()) {
                    throw new PgConfigurationException("A configuration file changed during the reload and now holds "
                        + "errors that PostgreSQL rejects: " + after.wholeFile());
                }
                if (!after.invalid().isEmpty()) {
                    throw new PgConfigurationException("PostgreSQL applied the other changes of the reload and reports "
                        + "invalid values, which a restart would not start with: " + after.invalid());
                }
                return null;
            });
    }

    private Future<PgConfigurationFiles.Errors> fileErrors(long deadline) {
        return sql(deadline, PgConfigurationFiles.ERRORS)
            .map(output -> observation(output, () -> PgConfigurationFiles.parse(output)));
    }

    /**
     * A reload is asynchronous. It is confirmed when the configuration load time has changed. The wait ends with a
     * failure when another observation would not fit in the budget.
     */
    private Future<Void> awaitChange(long deadline, String before) {
        long started = System.nanoTime();
        return sql(deadline, LOAD_TIME).compose(current -> {
            if (!current.equals(before)) return Future.<Void>succeededFuture();
            long observation = System.nanoTime() - started;
            if (deadline - System.nanoTime() < 2 * observation + TimeUnit.MILLISECONDS.toNanos(POLL_MILLIS)) {
                return Future.<Void>failedFuture(new PgConfigurationException(
                    "The configuration load time did not change within the budget after pg_ctl reload"));
            }
            return vertx.timer(POLL_MILLIS).compose(ignored -> awaitChange(deadline, before));
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
            return Future.failedFuture(new PgConfigurationException(
                "Configuration budget exhausted before " + command[0] + " could run"));
        }
        List<String> full = new ArrayList<>(commandPrefix);
        full.addAll(List.of(command));
        return runner.run(full, Duration.ofNanos(remaining)).transform(outcome -> {
            if (outcome.failed()) {
                PgConfigurationException failure = new PgConfigurationException("Command failed: " + command[0],
                    outcome.cause());
                logger.error("Configuration command failed: {}", command[0], failure);
                return Future.failedFuture(failure);
            }
            if (outcome.result() == null) {
                return Future.failedFuture(new PgConfigurationException("Command returned no result: " + command[0]));
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
            throw new PgConfigurationException("Malformed configuration observation: " + output, malformed);
        }
    }

    private static Void requireSuccess(PgCommandResult result, String action) {
        if (result.exitCode() != 0) {
            throw new PgConfigurationException(action + " failed with exit " + result.exitCode() + ": " + result.output());
        }
        return null;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
