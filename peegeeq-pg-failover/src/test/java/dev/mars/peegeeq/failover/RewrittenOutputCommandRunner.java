package dev.mars.peegeeq.failover;

import io.vertx.core.Future;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Wraps the real command runner. A command with an argument that contains the marker runs for real, and its output is
 * then rewritten; its exit code is kept. Every other command reaches the real runner unchanged. This makes a real
 * observation differ from the server's state in exactly the field that the rewrite changes, so that the caller's check
 * of that one field is exercised.
 */
public final class RewrittenOutputCommandRunner implements PgCommandRunner {
    private final PgCommandRunner real;
    private final String marker;
    private final UnaryOperator<String> rewrite;

    public RewrittenOutputCommandRunner(PgCommandRunner real, String marker, UnaryOperator<String> rewrite) {
        this.real = Objects.requireNonNull(real, "real");
        this.marker = Objects.requireNonNull(marker, "marker");
        this.rewrite = Objects.requireNonNull(rewrite, "rewrite");
    }

    @Override
    public Future<PgCommandResult> run(List<String> command, Duration timeout) {
        if (command.stream().noneMatch(argument -> argument.contains(marker))) return real.run(command, timeout);
        return real.run(command, timeout).map(result -> new PgCommandResult(result.exitCode(),
            rewrite.apply(result.output())));
    }
}
