package dev.mars.peegeeq.failover;

import io.vertx.core.Future;
import java.util.List;

/**
 * Synchronous durability on the local PostgreSQL: observation of the writer's WAL boundary and its
 * replication peers, and installation of a durability policy. Observations are derived and never
 * stored. Every operation fails with {@link PgDurabilityException} when its result is not confirmed.
 */
public interface PgDurabilityControl {
    /** The writer's current WAL flush position. Fails when PostgreSQL is in recovery. */
    Future<PgLsn> flushBoundary();

    /** Every replication connection the local server reports. */
    Future<List<PgReplicationPeer>> peers();

    /**
     * Installs the policy's generated {@code synchronous_standby_names} and confirms it is in
     * effect: the configuration was reloaded, the setting equals the generated value, and every
     * required standby reports quorum synchronous state. The caller keeps application admission
     * closed. A failure leaves the installed setting in place.
     */
    Future<Void> install(PgDurabilityPolicy policy);
}
