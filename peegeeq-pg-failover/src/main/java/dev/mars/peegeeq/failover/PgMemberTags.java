package dev.mars.peegeeq.failover;

/**
 * A member's tags, from deployment configuration (plan §9.31). {@code noFailover}: never a takeover target.
 * {@code noSync}: never in a required standby set, so never a takeover target either. {@code noLoadBalance} and
 * {@code cloneFrom} are kept for features that do not exist yet: a read-only replica endpoint and the automatic
 * rebuild. {@code cloneFrom} is another member's identity, or null.
 */
public record PgMemberTags(boolean noFailover, boolean noSync, boolean noLoadBalance, String cloneFrom) {
    /** The tags of a member that has none. */
    public static final PgMemberTags NONE = new PgMemberTags(false, false, false, null);

    /** Whether a member with these tags can be a takeover target. */
    public boolean eligibleForTakeover() {
        return !noFailover && !noSync;
    }
}
