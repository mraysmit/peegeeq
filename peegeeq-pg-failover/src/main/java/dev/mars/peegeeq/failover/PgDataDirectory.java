package dev.mars.peegeeq.failover;

import java.util.Objects;

/**
 * A data directory as its control file reports it (plan §9.32): the system identifier, the cluster state, and the
 * timeline of the latest checkpoint. Observed live without SQL, never stored.
 */
public record PgDataDirectory(String systemIdentifier, String clusterState, long timeline) {
    public PgDataDirectory {
        if (systemIdentifier == null || !systemIdentifier.matches("[0-9]{1,20}")) {
            throw new IllegalArgumentException("Invalid system identifier: " + systemIdentifier);
        }
        if (Objects.requireNonNull(clusterState, "clusterState").isBlank()) {
            throw new IllegalArgumentException("A cluster state is required");
        }
        if (timeline < 1) throw new IllegalArgumentException("A timeline is at least 1");
    }

    /** Whether the server was shut down cleanly, as {@code pg_rewind} requires of its target (plan §9.32, check 3). */
    public boolean shutDownCleanly() {
        return "shut down".equals(clusterState) || "shut down in recovery".equals(clusterState);
    }
}
