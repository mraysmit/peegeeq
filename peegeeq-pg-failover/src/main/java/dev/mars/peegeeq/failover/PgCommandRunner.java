package dev.mars.peegeeq.failover;

import io.vertx.core.Future;
import java.time.Duration;
import java.util.List;

/**
 * Runs one local command to completion. A non-zero exit is a result. A command that cannot
 * start, or does not finish within its timeout, fails with {@link PgProcessControlException}.
 */
public interface PgCommandRunner {
    Future<PgCommandResult> run(List<String> command, Duration timeout);
}