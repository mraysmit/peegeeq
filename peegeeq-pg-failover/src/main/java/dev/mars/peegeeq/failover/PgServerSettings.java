package dev.mars.peegeeq.failover;

import java.util.List;
import java.util.Objects;

/**
 * The local server as one observation sees it (plan §9.33): when its postmaster started, in microseconds since the epoch;
 * its version number ({@code server_version_num}); whether it is in recovery; and the parameters changed in a
 * configuration file that wait for a restart ({@code pg_settings.pending_restart}). Observed live, never stored.
 */
public record PgServerSettings(long postmasterStartMicros, int serverVersion, boolean inRecovery,
                               List<String> pendingRestart) {
    public PgServerSettings {
        if (postmasterStartMicros <= 0) throw new IllegalArgumentException("A postmaster start time is required");
        if (serverVersion <= 0) throw new IllegalArgumentException("A server version number is required");
        pendingRestart = List.copyOf(Objects.requireNonNull(pendingRestart, "pendingRestart"));
    }
}
