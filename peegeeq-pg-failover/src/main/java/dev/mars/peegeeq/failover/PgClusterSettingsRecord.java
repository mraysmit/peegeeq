package dev.mars.peegeeq.failover;

import java.util.Objects;

/**
 * The stored cluster settings with the coordinator's revision of the record (plan §9.34). The revision is coordinator
 * metadata and guards an edit; it is not part of the stored value.
 */
public record PgClusterSettingsRecord(long revision, PgClusterSettings settings) {
    public PgClusterSettingsRecord {
        Objects.requireNonNull(settings, "settings");
    }
}
