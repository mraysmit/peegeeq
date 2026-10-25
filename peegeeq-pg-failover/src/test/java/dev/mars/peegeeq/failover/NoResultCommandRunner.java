package dev.mars.peegeeq.failover;

import io.vertx.core.Future;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Wraps the real command runner. The real command runs, and its result is then dropped: the Future
 * succeeds with no result. The port forbids that. This wrapper makes the contract violation happen so
 * that the guards of the callers against it are exercised.
 */
public final class NoResultCommandRunner implements PgCommandRunner {
    private final PgCommandRunner real;

    public NoResultCommandRunner(PgCommandRunner real) {
        this.real = Objects.requireNonNull(real, "real");
    }

    @Override
    public Future<PgCommandResult> run(List<String> command, Duration timeout) {
        return real.run(command, timeout).map(ignored -> (PgCommandResult) null);
    }
}
