package dev.mars.peegeeq.failover;

import java.util.Objects;

/**
 * Whether a restart ran (plan §9.33, contract 3), and why not when it did not. A condition that is not met is a result,
 * not a failure (OD-57). Derived, never stored.
 */
public record PgRestartResult(boolean restarted, String reason) {
    public PgRestartResult {
        Objects.requireNonNull(reason, "reason");
    }
}
