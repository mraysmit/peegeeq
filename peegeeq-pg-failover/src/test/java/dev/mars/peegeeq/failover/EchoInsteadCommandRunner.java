package dev.mars.peegeeq.failover;

import io.vertx.core.Future;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Wraps the real command runner. A command that runs the named program is run as {@code echo} with the same
 * arguments: it exits 0 and prints its arguments, so its output is not what the program prints. Every other command
 * reaches the real runner unchanged. This makes the contract violation happen that a command succeeds with output
 * that is not an observation, for a program whose path the caller derives and a test cannot replace.
 */
public final class EchoInsteadCommandRunner implements PgCommandRunner {
    private final PgCommandRunner real;
    private final String program;

    public EchoInsteadCommandRunner(PgCommandRunner real, String program) {
        this.real = Objects.requireNonNull(real, "real");
        this.program = Objects.requireNonNull(program, "program");
    }

    @Override
    public Future<PgCommandResult> run(List<String> command, Duration timeout) {
        int index = command.indexOf(program);
        if (index < 0) return real.run(command, timeout);
        List<String> replaced = new ArrayList<>(command);
        replaced.set(index, "echo");
        return real.run(replaced, timeout);
    }
}
