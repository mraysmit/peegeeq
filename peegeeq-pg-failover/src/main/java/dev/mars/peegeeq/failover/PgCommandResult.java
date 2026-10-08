package dev.mars.peegeeq.failover;

import java.util.Objects;

/** Exit code and combined standard output and error of one finished command. */
public record PgCommandResult(int exitCode, String output) {
    public PgCommandResult {
        Objects.requireNonNull(output, "output");
    }
}