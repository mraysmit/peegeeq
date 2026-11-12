package dev.mars.peegeeq.failover;

import io.vertx.core.Future;
import java.util.Map;

/**
 * Coordinator port for published member positions, separate from the lease port (plan §9.27, OD-36). Each member's
 * supervisor writes only its own position. A position is never authority, so a stale read, a lost publication, or a
 * delayed older write is safe: each only keeps more WAL on a standby. Every operation has a request deadline and fails
 * with {@link PgLeaseProtocolException}; a malformed, partial, filtered, or unauthenticated response is a failure.
 */
public interface PgMemberPositions {
    /** Publishes this node's own position. A position of another node is refused before anything is sent. */
    Future<Void> publish(PgMemberPosition position);

    /** Every published member position, by node identity. A member that never published is absent. */
    Future<Map<String, PgMemberPosition>> read();

    /** Frees client resources. */
    Future<Void> close();
}
