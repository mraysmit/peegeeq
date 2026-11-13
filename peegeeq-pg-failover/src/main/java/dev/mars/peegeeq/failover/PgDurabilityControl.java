package dev.mars.peegeeq.failover;

import io.vertx.core.Future;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Synchronous durability on the local PostgreSQL: observation of the writer's WAL boundary and its
 * replication peers, and installation of a durability policy. Observations are derived and never
 * stored. Every operation fails with {@link PgDurabilityException} when its result is not confirmed.
 */
public interface PgDurabilityControl {
    /** The writer's current WAL flush position. Fails when PostgreSQL is in recovery. */
    Future<PgLsn> flushBoundary();

    /** Every replication connection the local server reports. */
    Future<List<PgReplicationPeer>> peers();

    /**
     * Observes one fresh writer flush boundary, then waits until every required standby of the
     * policy has exactly one replication connection under its own identity, streaming, with flush
     * and replay at or after that boundary. The caller keeps application writes excluded, so the
     * boundary does not move. A command failure, a malformed observation, an identity mismatch, or a
     * required standby whose member slot is lost (OD-33) fails at once. A standby that is not yet
     * covered fails the call only when the budget ends,
     * with a {@link PgStandbyNotCoveredException} that names the last uncovered reason.
     */
    Future<Void> confirmCovered(PgDurabilityPolicy policy);

    /**
     * Installs the policy's generated {@code synchronous_standby_names} and confirms it is in
     * effect: the configuration was reloaded, the setting equals the generated value, and every
     * required standby reports quorum synchronous state. The caller keeps application admission
     * closed. A failure leaves the installed setting in place.
     */
    Future<Void> install(PgDurabilityPolicy policy);

    /**
     * Ensures that the local PostgreSQL, a writer or a standby (OD-35), holds one physical replication slot for each
     * named member, under the member's derived slot name ({@link PgNodeConfig#slotName}). A missing slot is created
     * with its WAL position reserved at once. A slot that exists is not recreated, moved, or dropped. Refused when
     * {@code max_slot_wal_keep_size} is unbounded (OD-27). A slot under a member slot name that is not physical fails
     * the call.
     */
    Future<Void> ensureSlots(List<String> nodeIds);

    /**
     * Replaces the member's slot, only when it exists and is lost, by a new physical slot with its position
     * reserved at once, so that a rebuild of the member can take its base backup through it (plan §9.26). A slot
     * that is not lost is never dropped. Refused as {@link #ensureSlots} refuses, when PostgreSQL is in recovery (a
     * lost slot is replaced on the writer), and when the slot does not exist.
     */
    Future<Void> replaceLostSlot(String nodeId);

    /**
     * This node's WAL position (plan §9.27): on a writer, the timeline of its current WAL position and its flush
     * position; on a standby, the timeline and flushed position of its streaming WAL receiver. A standby that is not
     * streaming has no position, and the call fails.
     */
    Future<PgMemberPosition> position(String nodeId);

    /**
     * This standby's position as a takeover candidate (plan §9.28, P7d): the larger of its received and replayed WAL
     * position, on the timeline of its WAL receiver, or of its last restartpoint when it has no receiver. It is observed
     * after the writer is lost, when the standby no longer streams. Refused on a writer, which is not a candidate.
     */
    Future<PgMemberPosition> candidatePosition(String nodeId);

    /**
     * Advances the standby's member slots to published positions and returns the members whose slots it advanced
     * (plan §9.27, contract 4). A slot moves to the smaller of the published position and the local replay position,
     * only forward, and only when the published timeline is the timeline of the streaming WAL receiver. A missing,
     * lost, or unreserved slot, a position on another timeline, and a standby that is not streaming leave slots
     * unchanged. A slot under a member slot name that is not physical fails the call. Refused on a writer, whose slots
     * advance by streaming.
     */
    Future<Set<String>> advanceSlots(Map<String, PgMemberPosition> positions);
}
