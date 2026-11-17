package dev.mars.peegeeq.failover;

import io.vertx.core.Future;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Wraps the real command runner. A {@code pg_ctl reload} command is replaced by {@code pg_ctl status} on the same data
 * directory. The real command runs and exits 0 on a running server, but no signal is sent, so PostgreSQL does not re-read
 * its configuration files. Every other command reaches the real runner unchanged. This makes the contract violation happen
 * that a command reports success and the configuration load time does not change.
 */
public final class ReloadDoesNotReloadCommandRunner implements PgCommandRunner {
    private final PgCommandRunner real;

    public ReloadDoesNotReloadCommandRunner(PgCommandRunner real) {
        this.real = Objects.requireNonNull(real, "real");
    }

    @Override
    public Future<PgCommandResult> run(List<String> command, Duration timeout) {
        int reload = command.indexOf("reload");
        if (reload < 0) return real.run(command, timeout);
        List<String> replaced = new ArrayList<>(command.subList(0, reload));
        replaced.add("status");
        int directory = command.indexOf("-D");
        replaced.add("-D");
        replaced.add(command.get(directory + 1));
        return real.run(replaced, timeout);
    }
}
