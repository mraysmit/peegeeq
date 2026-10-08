package dev.mars.peegeeq.failover;

import io.vertx.core.Future;
import java.time.Duration;

/**
 * Local PostgreSQL process control. It does not depend on a responsive SQL endpoint. Every
 * operation observes its postcondition and fails with {@link PgProcessControlException} when
 * the effect is not confirmed.
 */
public interface PgProcessControl {
    Future<PgProcessState> status();

    /** Starts PostgreSQL in recovery. It accepts no writes. No lease is required. */
    Future<Void> startInRecovery();

    /** Starts PostgreSQL writable. The caller must hold the writer lease. */
    Future<Void> startPrimary();

    /**
     * Inhibits a writable restart, then stops PostgreSQL and its sessions within the budget.
     * Succeeds only when the process is observed stopped and the inhibitor is in place.
     */
    Future<Void> stop(Duration budget);
}