package dev.mars.peegeeq.failover;

import io.vertx.core.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
    // pg_ctl status exits 4 for a path that does not exist and for a directory that is not a database cluster.
    private static final int EXIT_NOT_A_CLUSTER = 4;
    private final PgCommandRunner runner;
    private final List<String> commandPrefix;
    private final String pgCtl;
    private final String pgRewind;
    private final String pgBasebackup;
    private final String pgControldata;
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
        // Derived, never configured: the server programs share the directory of pg_ctl (plan §9.32).
        this.pgRewind = sibling(pgCtl, "pg_rewind");
        this.pgBasebackup = sibling(pgCtl, "pg_basebackup");
        this.pgControldata = sibling(pgCtl, "pg_controldata");
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

    @Override
    public Future<Optional<PgDataDirectory>> inspect() {
        return inspect(commandTimeout);
    }

    /**
     * Refused while PostgreSQL runs or when there is no data directory. A target that was not shut down cleanly is
     * first recovered by a start in recovery, with the inhibitor in place and no primary connection, and a fast stop
     * (plan §9.32, check 3). The configuration files are saved before {@code pg_rewind}. Afterwards, whatever its
     * outcome, the inhibitor is recreated, because a rewind removes it (check 4), and the saved files are restored. The
     * saved copy is removed only after a successful rewind.
     */
    @Override
    public Future<Void> rewind(PgWriterEndpoint source, String nodeId, Duration budget) {
        Objects.requireNonNull(source, "source");
        String sourceServer = source.connectionInfo(nodeId) + " dbname=postgres";
        if (budget == null || budget.toMillis() <= 0) {
            return Future.failedFuture(new PgProcessControlException("A rewind requires a positive budget"));
        }
        long deadline = System.nanoTime() + budget.toNanos();
        return bounded(deadline, this::requireStopped).compose(exists -> {
            if (!exists) throw new PgProcessControlException("There is no data directory to rewind");
            return bounded(deadline, this::inspect);
        }).compose(observed -> observed.orElseThrow().shutDownCleanly() ? Future.<Void>succeededFuture()
                : recoverAfterCrash(deadline))
            .compose(ignored -> bounded(deadline, this::saveConfiguration))
            .compose(ignored -> bounded(deadline, remaining -> run(remaining, pgRewind, "-D", dataDirectory,
                    "--source-server", sourceServer))
                .map(result -> requireSuccess(result, "pg_rewind"))
                .transform(rewound -> afterDataChange(deadline, rewound.succeeded())
                    .transform(after -> firstFailure(rewound, after))));
    }

    /**
     * Refused while PostgreSQL runs. Saves the configuration files when the data directory exists and no saved copy
     * exists, removes the data directory, and takes a base backup through the node's own slot on the source, without
     * {@code -C}. Then recreates the inhibitor, which a base backup without {@code -R} does not write (plan §9.32,
     * check 9), restores the saved files, and removes the saved copy. A failed base backup keeps the saved copy.
     */
    @Override
    public Future<Void> rebuild(PgWriterEndpoint source, String nodeId, Duration budget) {
        Objects.requireNonNull(source, "source");
        String connection = source.connectionInfo(nodeId);
        String slot = PgNodeConfig.slotName(nodeId);
        if (budget == null || budget.toMillis() <= 0) {
            return Future.failedFuture(new PgProcessControlException("A rebuild requires a positive budget"));
        }
        long deadline = System.nanoTime() + budget.toNanos();
        return bounded(deadline, this::requireStopped)
            .compose(exists -> exists ? bounded(deadline, this::saveConfiguration) : Future.<Void>succeededFuture())
            .compose(ignored -> bounded(deadline, remaining -> run(remaining, "rm", "-rf", dataDirectory)))
            .map(result -> requireSuccess(result, "Removing the data directory"))
            .compose(ignored -> bounded(deadline, remaining -> run(remaining, pgBasebackup, "-d", connection,
                "-D", dataDirectory, "-Fp", "-Xs", "-S", slot)))
            .map(result -> requireSuccess(result, "pg_basebackup"))
            .compose(ignored -> afterDataChange(deadline, true));
    }

    /**
     * Absence first, by the path itself: {@code pg_ctl status} exits 4 both for a path that does not exist and for a
     * directory that is not a database cluster, and only the first is absent. Then the status, then the control file.
     */
    private Future<Optional<PgDataDirectory>> inspect(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        return bounded(deadline, this::exists).compose(exists -> {
            if (!exists) return Future.succeededFuture(Optional.<PgDataDirectory>empty());
            return bounded(deadline, this::clusterStatus)
                // The labels are read in the C locale, whatever the host's locale.
                .compose(running -> bounded(deadline, remaining -> run(remaining, "env", "LC_ALL=C", pgControldata, "-D",
                    dataDirectory)))
                .map(result -> {
                    requireSuccess(result, "pg_controldata");
                    return Optional.of(controlData(result.output()));
                });
        });
    }

    /**
     * True when the data directory exists and PostgreSQL is stopped, false when the path does not exist. Refused while
     * PostgreSQL runs, and for an existing path that is not a database cluster: a rebuild must never remove it.
     */
    private Future<Boolean> requireStopped(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        return bounded(deadline, this::exists).compose(exists -> !exists ? Future.succeededFuture(false)
            : bounded(deadline, this::clusterStatus).map(running -> {
                if (running) {
                    throw new PgProcessControlException(
                        "PostgreSQL is running; a rewind or a rebuild needs a stopped server");
                }
                return true;
            }));
    }

    /** Whether anything exists at the data directory path. */
    private Future<Boolean> exists(Duration timeout) {
        return run(timeout, "test", "-e", dataDirectory).map(result -> result.exitCode() == 0);
    }

    /** Running or stopped, for an existing path. Exit 4 there is a directory that is not a database cluster. */
    private Future<Boolean> clusterStatus(Duration timeout) {
        return run(timeout, pgCtl, "status", "-D", dataDirectory).map(status -> {
            if (status.exitCode() == EXIT_RUNNING) return true;
            if (status.exitCode() == EXIT_STOPPED) return false;
            if (status.exitCode() == EXIT_NOT_A_CLUSTER) {
                throw new PgProcessControlException(dataDirectory + " exists but is not a database cluster directory: "
                    + status.output());
            }
            throw new PgProcessControlException("pg_ctl status exit " + status.exitCode() + ": " + status.output());
        });
    }

    /**
     * Completes crash recovery without removing the inhibitor: a start in recovery with no primary connection, so that
     * only local WAL is replayed, then a fast stop. A clean state is then required.
     */
    private Future<Void> recoverAfterCrash(long deadline) {
        return bounded(deadline, this::inhibit)
            .compose(ignored -> bounded(deadline, remaining -> run(remaining, pgCtl, "start", "-D", dataDirectory, "-w",
                "-t", String.valueOf(Math.max(1, remaining.toSeconds())), "-l", logFile, "-o", "-c primary_conninfo=''")))
            .map(result -> requireSuccess(result, "Starting PostgreSQL in recovery to complete crash recovery"))
            .compose(ignored -> bounded(deadline, remaining -> stopWith("fast", remaining, remaining)))
            .compose(ignored -> bounded(deadline, this::inspect))
            .map(observed -> {
                PgDataDirectory state = observed.orElseThrow();
                if (!state.shutDownCleanly()) {
                    throw new PgProcessControlException("The data directory is not shut down cleanly after crash "
                        + "recovery: " + state.clusterState());
                }
                return null;
            });
    }

    /**
     * Copies every regular top-level {@code *.conf} file of the data directory to the saved copy, unless a saved copy
     * exists: then it holds the files from before the first change of an earlier rewind or rebuild, which are the ones
     * to keep. The copy is written under a temporary name and renamed, so a saved copy is never partial.
     */
    private Future<Void> saveConfiguration(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        String staging = saved() + ".tmp";
        return bounded(deadline, remaining -> run(remaining, "test", "-d", saved())).compose(present -> {
            if (present.exitCode() == 0) return Future.<Void>succeededFuture();
            return bounded(deadline, remaining -> run(remaining, "rm", "-rf", staging))
                .map(result -> requireSuccess(result, "Removing a partial configuration copy"))
                .compose(ignored -> bounded(deadline, remaining -> run(remaining, "mkdir", "-p", staging)))
                .map(result -> requireSuccess(result, "Creating the configuration copy"))
                .compose(ignored -> bounded(deadline, remaining -> run(remaining, "find", dataDirectory, "-maxdepth", "1",
                    "-type", "f", "-name", "*.conf", "-exec", "cp", "-p", "{}", staging + "/", ";")))
                .map(result -> requireSuccess(result, "Copying the configuration files"))
                .compose(ignored -> bounded(deadline, remaining -> run(remaining, "mv", staging, saved())))
                .map(result -> requireSuccess(result, "Keeping the configuration copy"));
        });
    }

    /**
     * After a rewind or a base backup: recreates the inhibitor and restores the saved configuration files, each step run
     * even when the other fails; removes the saved copy only when the change succeeded and both steps did.
     */
    private Future<Void> afterDataChange(long deadline, boolean changeSucceeded) {
        Future<Void> inhibited = bounded(deadline, this::inhibit);
        return inhibited.transform(inhibit -> bounded(deadline, this::restoreConfiguration)
            .transform(restore -> {
                Future<Void> both = firstFailure(inhibit, restore);
                if (both.failed() || !changeSucceeded) return both;
                return bounded(deadline, remaining -> run(remaining, "rm", "-rf", saved()))
                    .map(result -> requireSuccess(result, "Removing the configuration copy"));
            }));
    }

    private Future<Void> restoreConfiguration(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        return bounded(deadline, remaining -> run(remaining, "test", "-d", saved())).compose(present -> {
            if (present.exitCode() != 0) return Future.<Void>succeededFuture();
            return bounded(deadline, remaining -> run(remaining, "find", saved(), "-maxdepth", "1", "-type", "f",
                "-name", "*.conf", "-exec", "cp", "-p", "{}", dataDirectory + "/", ";"))
                .map(result -> requireSuccess(result, "Restoring the configuration files"));
        });
    }

    /** The first failure of two settled steps, with the second attached; success when both succeeded. */
    private static Future<Void> firstFailure(io.vertx.core.AsyncResult<?> first, io.vertx.core.AsyncResult<?> second) {
        if (first.failed()) {
            if (second.failed()) first.cause().addSuppressed(second.cause());
            return Future.failedFuture(first.cause());
        }
        return second.failed() ? Future.failedFuture(second.cause()) : Future.succeededFuture();
    }

    /** Parses the three fields of {@code pg_controldata} that a re-join reads. */
    private static PgDataDirectory controlData(String output) {
        String identifier = field(output, "Database system identifier");
        String state = field(output, "Database cluster state");
        String timeline = field(output, "Latest checkpoint's TimeLineID");
        try {
            return new PgDataDirectory(identifier, state, Long.parseLong(Objects.requireNonNull(timeline, "timeline")));
        } catch (RuntimeException malformed) {
            throw new PgProcessControlException("Malformed control data: " + output, malformed);
        }
    }

    private static String field(String output, String label) {
        for (String line : output.split("\n")) {
            if (line.startsWith(label + ":")) return line.substring(label.length() + 1).strip();
        }
        return null;
    }

    /** Where the configuration files are kept during a rewind or a rebuild: next to the data directory, never in it. */
    private String saved() {
        return dataDirectory + ".rejoin-saved";
    }

    /** A PostgreSQL program in the directory of the configured {@code pg_ctl}, or on the path when that has no directory. */
    private static String sibling(String pgCtl, String program) {
        int slash = pgCtl.lastIndexOf('/');
        return slash < 0 ? program : pgCtl.substring(0, slash + 1) + program;
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
