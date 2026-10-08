package dev.mars.peegeeq.failover;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import java.util.Optional;

/**
 * Coordinator port: one time-bounded, exclusively owned control record with conditional updates.
 * An adapter binds the port to one coordinator product. Every operation has a request deadline
 * and fails with {@link PgLeaseProtocolException}. A timeout or lost reply never counts as success.
 * Conditions and their writes are atomic; a failed condition changes nothing.
 */
public interface PgLeaseCoordinator {
    /** Creates the record with this intent under a new lease. Fails when the record exists. */
    Future<PgControlRecord> acquireInitial(JsonObject intent);

    /**
     * Takes ownership of an unowned record under a new lease and writes this intent.
     * Fails when the record is owned or is not at the revision of {@code released}.
     * The generation advances.
     */
    Future<PgControlRecord> acquireAfterRelease(PgControlRecord released, JsonObject intent);

    /** An authoritative read. A read that cannot be established as current fails. */
    Future<Optional<PgControlRecord>> read();

    /** Extends the lease that holds {@code held} and returns the record observed afterwards. */
    Future<PgControlRecord> renew(PgControlRecord held);

    /**
     * Replaces the intent. Fails unless the caller's lease holds the record at the revision of
     * {@code held}. The generation does not change. The lease is not extended.
     */
    Future<PgControlRecord> update(PgControlRecord held, JsonObject intent);

    /**
     * Ends ownership and retains the value as unowned history. Fails unless the caller's lease
     * holds the record at the revision of {@code held}.
     */
    Future<PgControlRecord> release(PgControlRecord held);

    /** Frees client resources. Closing never releases the lease. */
    Future<Void> close();
}
