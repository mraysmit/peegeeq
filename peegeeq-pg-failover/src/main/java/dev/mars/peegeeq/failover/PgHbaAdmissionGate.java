package dev.mars.peegeeq.failover;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Admission gate through PostgreSQL host-based authentication. The deployment provisions two
 * files: one that admits application connections and one that rejects them. Both keep the local
 * socket for the supervisor and the replication rules. The gate installs one of them as
 * {@code pg_hba.conf} and reloads PostgreSQL.
 *
 * <p>A reload blocks new connections only. Closing therefore also ends every client backend
 * that arrived over the network and observes that none remains.
 */
public final class PgHbaAdmissionGate implements PgAdmissionGate {
    private static final Logger logger = LoggerFactory.getLogger(PgHbaAdmissionGate.class);
    private static final long POLL_MILLIS = 100;
    private static final String NETWORK_CLIENTS =
        "from pg_stat_activity where backend_type = 'client backend' and client_addr is not null";
    private final Vertx vertx;
    private final PgCommandRunner runner;
    private final List<String> commandPrefix;
    private final String pgCtl;
    private final String psql;
    private final String dataDirectory;
    private final String openFile;
    private final String closedFile;
    private final Duration commandTimeout;

    public PgHbaAdmissionGate(Vertx vertx, PgCommandRunner runner, List<String> commandPrefix, String pgCtl,
                              String psql, String dataDirectory, String openFile, String closedFile,
                              Duration commandTimeout) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.commandPrefix = List.copyOf(commandPrefix);
        this.pgCtl = requireText(pgCtl, "pgCtl");
        this.psql = requireText(psql, "psql");
        this.dataDirectory = requireText(dataDirectory, "dataDirectory");
        this.openFile = requireText(openFile, "openFile");
        this.closedFile = requireText(closedFile, "closedFile");
        if (commandTimeout == null || commandTimeout.toMillis() <= 0) {
            throw new IllegalArgumentException("Command timeout must be positive");
        }
        this.commandTimeout = commandTimeout;
    }

    @Override
    public Future<Void> close(Duration budget) {
        if (budget == null || budget.toMillis() <= 0) {
            return Future.failedFuture(new PgProcessControlException("Closing admission requires a positive budget"));
        }
        long deadline = System.nanoTime() + budget.toNanos();
        return install(closedFile, deadline).compose(ignored -> running(deadline)).compose(running -> {
            // Stopped: the installed file is what the next start loads. There is nothing to end.
            if (!running) return Future.<Void>succeededFuture();
            return reload(deadline)
                .compose(ignored -> sql(deadline, "select count(pg_terminate_backend(pid)) " + NETWORK_CLIENTS))
                .compose(ignored -> awaitNoNetworkClients(deadline));
        });
    }

    @Override
    public Future<Void> open() {
        long deadline = System.nanoTime() + commandTimeout.toNanos();
        return running(deadline).compose(running -> {
            if (!running) throw new PgProcessControlException("Admission cannot open while PostgreSQL is stopped");
            return install(openFile, deadline);
        }).compose(ignored -> reload(deadline));
    }

    /** Copies the file into place and confirms the active file equals it. */
    private Future<Void> install(String source, long deadline) {
        String active = dataDirectory + "/pg_hba.conf";
        return run(deadline, "cp", "-f", source, active)
            .map(result -> requireSuccess(result, "Installing admission file " + source))
            .compose(ignored -> run(deadline, "cmp", "-s", source, active))
            .map(result -> requireSuccess(result, "Verifying admission file " + source));
    }

    private Future<Boolean> running(long deadline) {
        return run(deadline, pgCtl, "status", "-D", dataDirectory).map(result -> {
            if (result.exitCode() == 0) return true;
            if (result.exitCode() == 3) return false;
            throw new PgProcessControlException("pg_ctl status exit " + result.exitCode() + ": " + result.output());
        });
    }

    /** A reload is asynchronous. It is confirmed when the configuration load time has changed. */
    private Future<Void> reload(long deadline) {
        String loadTime = "select extract(epoch from pg_conf_load_time())";
        return sql(deadline, loadTime).compose(before -> run(deadline, pgCtl, "reload", "-D", dataDirectory)
                .map(result -> requireSuccess(result, "pg_ctl reload"))
                .compose(ignored -> awaitChange(deadline, loadTime, before)))
            .compose(ignored -> sql(deadline, "select count(*) from pg_hba_file_rules where error is not null"))
            .map(errors -> {
                if (!"0".equals(errors)) throw new PgProcessControlException("Admission file has invalid rules");
                return null;
            });
    }

    private Future<Void> awaitChange(long deadline, String statement, String before) {
        return sql(deadline, statement).compose(current -> current.equals(before)
            ? vertx.timer(POLL_MILLIS).compose(ignored -> awaitChange(deadline, statement, before))
            : Future.<Void>succeededFuture());
    }

    private Future<Void> awaitNoNetworkClients(long deadline) {
        return sql(deadline, "select count(*) " + NETWORK_CLIENTS).compose(count -> "0".equals(count)
            ? Future.<Void>succeededFuture()
            : vertx.timer(POLL_MILLIS).compose(ignored -> awaitNoNetworkClients(deadline)));
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
            return Future.failedFuture(new PgProcessControlException(
                "Admission budget exhausted before " + command[0] + " could run"));
        }
        List<String> full = new ArrayList<>(commandPrefix);
        full.addAll(List.of(command));
        return runner.run(full, Duration.ofNanos(remaining)).transform(outcome -> {
            if (outcome.failed()) {
                logger.warn("Admission command failed: {}", command[0], outcome.cause());
                return Future.failedFuture(outcome.cause() instanceof PgProcessControlException
                    ? outcome.cause()
                    : new PgProcessControlException("Command failed: " + command[0], outcome.cause()));
            }
            if (outcome.result() == null) {
                return Future.failedFuture(new PgProcessControlException("Command returned no result: " + command[0]));
            }
            return Future.succeededFuture(outcome.result());
        });
    }

    private static Void requireSuccess(PgCommandResult result, String action) {
        if (result.exitCode() != 0) {
            throw new PgProcessControlException(action + " failed with exit " + result.exitCode() + ": " + result.output());
        }
        return null;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
