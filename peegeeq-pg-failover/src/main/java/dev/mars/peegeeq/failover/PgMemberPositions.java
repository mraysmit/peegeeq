package dev.mars.peegeeq.failover;

import io.vertx.core.Future;
import java.util.Map;

/**
 * Coordinator port for published member positions, separate from the lease port (plan §9.27, OD-36). Each member's
 * supervisor writes only its own position. Each record is held by its member's own session and ends when that member
 * stops publishing for longer than the session TTL (plan §9.28, P7b). A position is never authority for exclusion,
 * coverage, policy, or routing; it advances slots on standbys and orders covered takeover candidates. A stale read, a
 * lost publication, or a delayed older write is safe: each only keeps more WAL or makes a node defer to a member that is
 * ahead. Every operation has a request deadline and fails with {@link PgMemberPositionException}; a malformed, partial,
 * filtered, or unauthenticated response, and a record that no session holds, is a failure.
 */
public interface PgMemberPositions {
    /** Publishes this node's own position. A position of another node is refused before anything is sent. */
    Future<Void> publish(PgMemberPosition position);

    /** Every published member position, by node identity. A member that never published is absent. */
    Future<Map<String, PgMemberPosition>> read();

    /** Frees client resources. */
    Future<Void> close();
}
