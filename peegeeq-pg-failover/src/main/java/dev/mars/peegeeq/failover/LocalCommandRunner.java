package dev.mars.peegeeq.failover;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Runs a command as a child process of the supervisor, on a worker thread. The wait is bounded
 * by the timeout. A command that overruns is killed and reported as a failure.
 */
public final class LocalCommandRunner implements PgCommandRunner {
    private static final Logger logger = LoggerFactory.getLogger(LocalCommandRunner.class);
    private static final long KILL_WAIT_SECONDS = 5;
    private final Vertx vertx;

    public LocalCommandRunner(Vertx vertx) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
    }

    @Override
    public Future<PgCommandResult> run(List<String> command, Duration timeout) {
        if (command == null || command.isEmpty() || timeout == null || timeout.toMillis() <= 0) {
            return Future.failedFuture(new PgProcessControlException("A command and a positive timeout are required"));
        }
        List<String> arguments = List.copyOf(command);
        // Unordered: a long command must not delay another command issued from the same context.
        return vertx.executeBlocking(() -> execute(arguments, timeout), false);
    }

    private static PgCommandResult execute(List<String> arguments, Duration timeout) {
        Path output = null;
        Process process = null;
        try {
            output = Files.createTempFile("peegeeq-pg-command", ".out");
            process = new ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                boolean killed = process.destroyForcibly().waitFor(KILL_WAIT_SECONDS, TimeUnit.SECONDS);
                throw new PgProcessControlException("Command exceeded " + timeout.toMillis() + " ms"
                    + (killed ? " and was killed: " : " and could not be killed: ") + arguments);
            }
            return new PgCommandResult(process.exitValue(),
                new String(Files.readAllBytes(output), StandardCharsets.UTF_8));
        } catch (IOException failure) {
            logger.warn("Command could not run: {}", arguments, failure);
            throw new PgProcessControlException("Command could not run: " + arguments, failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            if (process != null) process.destroyForcibly();
            logger.warn("Interrupted while waiting for command: {}", arguments, failure);
            throw new PgProcessControlException("Interrupted while waiting for command: " + arguments, failure);
        } finally {
            discard(output);
        }
    }

    private static void discard(Path output) {
        if (output == null) return;
        try {
            Files.deleteIfExists(output);
        } catch (IOException failure) {
            // The command result is already decided. A leftover capture file is reported, not hidden.
            logger.error("Command output file could not be deleted: {}", output, failure);
        }
    }
}
