package dev.mars.peegeeq.failover;

import io.vertx.core.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Process control through PostgreSQL's {@code pg_ctl}, run as child processes of the supervisor
 * against the local data directory.
 *
 * <p>Start inhibitor: {@code standby.signal} in the data directory. While it exists, a start by
 * any entry point comes up in recovery and accepts no writes. {@link #stop} creates it before
 * stopping. Only {@link #startPrimary} removes it.
 */
public final class PgCtlProcessControl implements PgProcessControl {
    private static final Logger logger = LoggerFactory.getLogger(PgCtlProcessControl.class);
    private static final int EXIT_RUNNING = 0;
    private static final int EXIT_STOPPED = 3;
    private final PgCommandRunner runner;
    private final List<String> commandPrefix;
    private final String pgCtl;
    private final String dataDirectory;
    private final String logFile;
    private final Duration commandTimeout;

    /**
     * @param commandPrefix arguments placed before every command, for example to run as the
     *                      PostgreSQL operating-system user; empty when none is needed
     * @param commandTimeout bound for status and start commands
     */
    public PgCtlProcessControl(PgCommandRunner runner, List<String> commandPrefix, String pgCtl,
                               String dataDirectory, String logFile, Duration commandTimeout) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.commandPrefix = List.copyOf(commandPrefix);
        this.pgCtl = requireText(pgCtl, "pgCtl");
        this.dataDirectory = requireText(dataDirectory, "dataDirectory");
        this.logFile = requireText(logFile, "logFile");
        if (commandTimeout == null || commandTimeout.toSeconds() < 1) {
            throw new IllegalArgumentException("Command timeout must be at least one second");
        }
        this.commandTimeout = commandTimeout;
    }

    @Override
    public Future<PgProcessState> status() {
        return status(commandTimeout);
    }

    @Override
    public Future<Void> startInRecovery() {
        return inhibit(commandTimeout).compose(ignored -> start());
    }

    @Override
    public Future<Void> startPrimary() {
        return run(commandTimeout, "rm", "-f", inhibitor())
            .map(result -> requireSuccess(result, "Removing the start inhibitor"))
            .compose(ignored -> start());
    }

    @Override
    public Future<Void> stop(Duration budget) {
        if (budget == null || budget.toMillis() <= 0) {
            return Future.failedFuture(new PgProcessControlException("Stop requires a positive budget"));
        }
        long deadline = System.nanoTime() + budget.toNanos();
        // Inhibit first, so that an interrupted stop still cannot be followed by a writable start.
        // A failed inhibitor does not cancel the stop; it fails the result.
        return bounded(deadline, remaining -> inhibit(remaining)).transform(inhibited ->
            bounded(deadline, this::status).compose(state -> state == PgProcessState.STOPPED
                    ? Future.succeededFuture(state)
                    : bounded(deadline, remaining -> stopWith("fast", remaining.dividedBy(2), remaining))
                        .compose(ignored -> bounded(deadline, this::status)))
                .compose(state -> state == PgProcessState.STOPPED
                    ? Future.succeededFuture(state)
                    : bounded(deadline, remaining -> stopWith("immediate", remaining, remaining))
                        .compose(ignored -> bounded(deadline, this::status)))
                .compose(state -> {
                    if (state != PgProcessState.STOPPED) {
                        throw new PgProcessControlException("PostgreSQL is still running after fast and immediate stop");
                    }
                    return inhibited.succeeded() ? Future.<Void>succeededFuture()
                        : Future.<Void>failedFuture(inhibited.cause());
                }));
    }

    private Future<Void> start() {
        return status().compose(state -> {
            if (state != PgProcessState.STOPPED) {
                throw new PgProcessControlException("PostgreSQL is already running; start refused");
            }
            return run(commandTimeout.plusSeconds(5), pgCtl, "start", "-D", dataDirectory, "-w",
                "-t", String.valueOf(commandTimeout.toSeconds()), "-l", logFile);
        }).map(result -> requireSuccess(result, "pg_ctl start")).compose(ignored -> status()).map(state -> {
            if (state != PgProcessState.RUNNING) {
                throw new PgProcessControlException("PostgreSQL is not running after start");
            }
            return null;
        });
    }

    private Future<PgProcessState> status(Duration timeout) {
        return run(timeout, pgCtl, "status", "-D", dataDirectory).map(result -> {
            if (result.exitCode() == EXIT_RUNNING) return PgProcessState.RUNNING;
            if (result.exitCode() == EXIT_STOPPED) return PgProcessState.STOPPED;
            throw new PgProcessControlException("pg_ctl status exit " + result.exitCode() + ": " + result.output());
        });
    }

    /** A stop command's own exit code is not the evidence. The following status observation is. */
    private Future<Void> stopWith(String mode, Duration wait, Duration timeout) {
        return run(timeout, pgCtl, "stop", "-D", dataDirectory, "-m", mode, "-w",
            "-t", String.valueOf(Math.max(1, wait.toSeconds()))).map(result -> {
                if (result.exitCode() != 0) {
                    logger.warn("pg_ctl stop -m {} exit {}: {}", mode, result.exitCode(), result.output());
                }
                return null;
            });
    }

    private Future<Void> inhibit(Duration timeout) {
        return run(timeout, "touch", inhibitor())
            .map(result -> requireSuccess(result, "Creating the start inhibitor"));
    }

    private String inhibitor() {
        return dataDirectory + "/standby.signal";
    }

    @FunctionalInterface
    private interface Step<T> { Future<T> within(Duration remaining); }

    private static <T> Future<T> bounded(long deadline, Step<T> step) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            return Future.failedFuture(new PgProcessControlException(
                "Stop budget exhausted before PostgreSQL was confirmed stopped"));
        }
        return step.within(Duration.ofNanos(remaining));
    }

    private Future<PgCommandResult> run(Duration timeout, String... command) {
        if (timeout.toMillis() <= 0) {
            return Future.failedFuture(new PgProcessControlException("No time remains to run " + command[0]));
        }
        List<String> full = new ArrayList<>(commandPrefix);
        full.addAll(List.of(command));
        return runner.run(full, timeout).transform(outcome -> {
            if (outcome.failed()) {
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
