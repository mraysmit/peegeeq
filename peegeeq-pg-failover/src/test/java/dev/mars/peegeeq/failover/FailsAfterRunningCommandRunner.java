package dev.mars.peegeeq.failover;

import io.vertx.core.Future;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Wraps the real command runner. A command that runs the named program runs for real; when it exits 0, its result is
 * then reported as exit 1. Every other command reaches the real runner unchanged. This makes the contract violation
 * happen that a command fails after it has changed the data directory, as a {@code pg_rewind} interrupted after its
 * file copy does.
 */
public final class FailsAfterRunningCommandRunner implements PgCommandRunner {
    private final PgCommandRunner real;
    private final String program;

    public FailsAfterRunningCommandRunner(PgCommandRunner real, String program) {
        this.real = Objects.requireNonNull(real, "real");
        this.program = Objects.requireNonNull(program, "program");
    }

    @Override
    public Future<PgCommandResult> run(List<String> command, Duration timeout) {
        if (!command.contains(program)) return real.run(command, timeout);
        return real.run(command, timeout).map(result -> result.exitCode() != 0 ? result
            : new PgCommandResult(1, result.output() + "\n" + program + " ran; its result is reported as a failure"));
    }
}
