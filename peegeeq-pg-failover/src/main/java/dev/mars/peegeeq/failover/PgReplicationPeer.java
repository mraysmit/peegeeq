package dev.mars.peegeeq.failover;

import java.util.Objects;

/**
 * One replication connection as the writer reports it: the authenticated role, the client's
 * application name, and its positions. A derived observation; never stored. A position is null
 * before the standby has reported it.
 */
public record PgReplicationPeer(String role, String applicationName, String state, String syncState,
                                PgLsn flushLsn, PgLsn replayLsn) {
    public PgReplicationPeer {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(applicationName, "applicationName");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(syncState, "syncState");
    }
}
