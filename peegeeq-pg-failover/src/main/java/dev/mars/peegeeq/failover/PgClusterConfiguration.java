package dev.mars.peegeeq.failover;

import io.vertx.core.Future;
import java.util.Optional;

/**
 * Coordinator port for the cluster settings, separate from the lease port (plan §9.34, contract 3, OD-68). One record per
 * cluster incarnation. A write never needs the writer lease; an edit is guarded by the record's revision instead. Every
 * operation has a request deadline and fails with {@link PgClusterConfigurationException}, including a synchronous throw;
 * a timeout or lost reply never counts as success. A stored value that is not valid cluster settings fails the read;
 * nothing is adjusted (OD-66).
 */
public interface PgClusterConfiguration {
    /** An authoritative read. Empty when no record exists. */
    Future<Optional<PgClusterSettingsRecord>> read();

    /** Writes the first record and returns it as read back. Fails when a record exists. */
    Future<PgClusterSettingsRecord> create(PgClusterSettings settings);

    /**
     * Replaces the settings only when the record is still at the revision of {@code expected}, and returns the new record
     * as read back. A record that changed since fails the call and changes nothing.
     */
    Future<PgClusterSettingsRecord> update(PgClusterSettingsRecord expected, PgClusterSettings settings);

    /** Frees client resources. */
    Future<Void> close();
}
