package dev.mars.peegeeq.failover;

import java.util.Objects;

/**
 * One member's WAL position as its own supervisor observed it: the timeline and the flush position of the WAL the
 * member holds. A published copy is not authority for exclusion, coverage, policy, or routing. It advances the member's
 * slots on standbys, where a stale value keeps more WAL (plan §9.27), and orders the covered takeover candidates
 * (plan §9.28, P7d), where it never makes a node eligible.
 */
public record PgMemberPosition(String nodeId, long timeline, PgLsn lsn) {
    public PgMemberPosition {
        PgNodeConfig.requireIdentity(nodeId);
        if (timeline < 1) throw new IllegalArgumentException("A timeline is at least 1");
        Objects.requireNonNull(lsn, "lsn");
    }
}
