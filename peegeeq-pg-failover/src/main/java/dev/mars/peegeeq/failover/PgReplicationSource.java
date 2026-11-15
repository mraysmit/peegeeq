package dev.mars.peegeeq.failover;

import java.util.Objects;

/**
 * The node that a re-join rewinds against or rebuilds from, as this node observes it through its own replication role
 * (plan §9.32): its system identifier, timeline, and flush position, whether it is in recovery, and whether this node's
 * slot on it is usable, that is physical with a position. Observed live, never stored.
 */
public record PgReplicationSource(String systemIdentifier, long timeline, PgLsn flushLsn, boolean inRecovery,
                                  boolean slotUsable) {
    public PgReplicationSource {
        if (systemIdentifier == null || !systemIdentifier.matches("[0-9]{1,20}")) {
            throw new IllegalArgumentException("Invalid system identifier: " + systemIdentifier);
        }
        if (timeline < 1) throw new IllegalArgumentException("A timeline is at least 1");
        Objects.requireNonNull(flushLsn, "flushLsn");
    }
}
