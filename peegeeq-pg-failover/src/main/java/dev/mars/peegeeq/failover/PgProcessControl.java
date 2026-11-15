package dev.mars.peegeeq.failover;

import io.vertx.core.Future;
import java.time.Duration;
import java.util.Optional;

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

    /**
     * The data directory as its control file reports it, or empty when the directory does not exist (plan §9.32,
     * contract 1). A directory whose control file cannot be read fails.
     */
    Future<Optional<PgDataDirectory>> inspect();

    /**
     * Rewinds the stopped data directory against the source, through the node's own replication role (plan §9.32,
     * contract 2). Refused while PostgreSQL runs. A target that was not shut down cleanly is first recovered by a start
     * in recovery and a fast stop. The node's own configuration files are kept, and the start inhibitor is in place
     * afterwards, whatever the outcome. "no rewind required" is success. Bounded by the budget.
     */
    Future<Void> rewind(PgWriterEndpoint source, String nodeId, Duration budget);

    /**
     * Removes the data directory and takes a new base backup from the source through the node's own slot on it (plan
     * §9.32, contract 3). Refused while PostgreSQL runs. The node's own configuration files are kept, and the start
     * inhibitor is in place afterwards. Bounded by the budget.
     */
    Future<Void> rebuild(PgWriterEndpoint source, String nodeId, Duration budget);
}