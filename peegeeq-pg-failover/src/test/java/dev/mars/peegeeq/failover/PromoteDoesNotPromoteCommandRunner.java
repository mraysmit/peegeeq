package dev.mars.peegeeq.failover;

import io.vertx.core.Future;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Wraps the real command runner. A {@code pg_ctl promote} command is replaced by {@code pg_ctl status}
 * on the same data directory. The real command runs and exits 0, but the server stays in recovery.
 * Every other command reaches the real runner unchanged. This makes the contract violation happen
 * that a command reports success and the role does not change.
 */
public final class PromoteDoesNotPromoteCommandRunner implements PgCommandRunner {
    private final PgCommandRunner real;

    public PromoteDoesNotPromoteCommandRunner(PgCommandRunner real) {
        this.real = Objects.requireNonNull(real, "real");
    }

    @Override
    public Future<PgCommandResult> run(List<String> command, Duration timeout) {
        int promote = command.indexOf("promote");
        if (promote < 0) return real.run(command, timeout);
        List<String> replaced = new ArrayList<>(command.subList(0, promote));
        replaced.add("status");
        int directory = command.indexOf("-D");
        replaced.add("-D");
        replaced.add(command.get(directory + 1));
        return real.run(replaced, timeout);
    }
}
