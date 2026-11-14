package dev.mars.peegeeq.failover;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Takeover steps of the new writer: acquiring the lease after the former one expired or was released
 * (plan §9.29), and the policy cutover after promotion (plan §9.23). Every failure and refusal is logged
 * once at ERROR and returned.
 */
public final class PgTakeover {
    private static final Logger logger = LoggerFactory.getLogger(PgTakeover.class);
    private final PgNodeConfig config;
    private final PgPrimaryElector elector;
    private final PgLocalStateStore store;
    private final PgFailoverMonitor monitor;

    public PgTakeover(PgNodeConfig config, PgPrimaryElector elector, PgLocalStateStore store,
                      PgFailoverMonitor monitor) {
        this.config = Objects.requireNonNull(config, "config");
        this.elector = Objects.requireNonNull(elector, "elector");
        this.store = Objects.requireNonNull(store, "store");
        this.monitor = Objects.requireNonNull(monitor, "monitor");
    }

    /**
     * Takes over the observed control record after the former writer's lease expired or was released (plan §9.29).
     * The intent names this node as writer and the observed writer as previous writer, has a new operation, is
     * withdrawn, and carries the confirmed and pending policies unchanged. Returns the acquired record. The caller
     * starts its renewal loop directly afterwards, before promotion.
     *
     * <p>Refused before any coordinator write, so the elector stays available for a later attempt: a record that
     * still has a lease holder, a record that names this node as writer (that is a re-join), or a confirmed policy that
     * does not name this node ({@link PgLeaseProtocolException}); quarantine ({@link PgLocalStateException}); in
     * {@code AUTOMATIC} mode, a candidacy that is not preferred ({@link PgLeaseProtocolException}). A manual takeover
     * names its target and is not ranked. The successor never waits for a reply from the former writer: its exclusion
     * is the former writer's own supervisor's demotion before the lease can expire.
     */
    public Future<PgControlRecord> acquire(PgControlRecord observed, PgFailoverMode mode) {
        return Future.<Void>succeededFuture()
            .compose(ignored -> {
                Objects.requireNonNull(observed, "observed");
                Objects.requireNonNull(mode, "mode");
                requireTakeoverCandidate(observed);
                return requireNoQuarantine();
            })
            .compose(ignored -> mode == PgFailoverMode.AUTOMATIC ? requirePreferred(observed)
                : Future.<Void>succeededFuture())
            .compose(ignored -> elector.acquireAfterRelease(observed, takeoverIntent(observed)))
            // andThen, not onFailure: the caller sees the failure only after it is logged.
            .andThen(outcome -> {
                if (outcome.failed()) {
                    logger.error("Takeover acquisition failed on node {}", config.nodeId(), outcome.cause());
                }
            });
    }

    private void requireTakeoverCandidate(PgControlRecord observed) {
        JsonObject intent = observed.intent();
        JsonObject confirmed = intent.getJsonObject("durabilityPolicy");
        if (observed.leaseHolder() != null) {
            throw new PgLeaseProtocolException("The control record is still owned; a takeover waits until the former "
                + "lease expires or is released");
        }
        if (config.nodeId().equals(intent.getString("writerNodeId"))) {
            throw new PgLeaseProtocolException("The control record names this node as writer; its return is a re-join, "
                + "not a takeover");
        }
        if (confirmed == null || !confirmed.getJsonArray("requiredStandbyNodeIds").contains(config.nodeId())) {
            throw new PgLeaseProtocolException("A takeover requires a confirmed durability policy that names this node");
        }
        if (!config.tagsOf(config.nodeId()).eligibleForTakeover()) {
            throw new PgLeaseProtocolException("This node is tagged nofailover or nosync; it is never a takeover target");
        }
    }

    /** In automatic mode only a preferred candidate acquires (plan §9.28, P7d). */
    private Future<Void> requirePreferred(PgControlRecord observed) {
        return monitor.takeoverCandidacy(observed).map(candidacy -> {
            if (!candidacy.preferred()) {
                throw new PgLeaseProtocolException("This node is not a preferred takeover candidate: "
                    + candidacy.reason());
            }
            return null;
        });
    }

    /** Withdrawn intent for this node, with the observed writer as previous writer and both policies carried. */
    private JsonObject takeoverIntent(PgControlRecord observed) {
        JsonObject history = observed.intent();
        JsonObject intent = new JsonObject().put("writerNodeId", config.nodeId()).put("phase", "WITHDRAWN")
            .put("operationId", UUID.randomUUID().toString())
            .put("previousWriterNodeId", history.getString("writerNodeId"))
            .put("durabilityPolicy", history.getJsonObject("durabilityPolicy"));
        if (history.containsKey("pendingDurabilityPolicy")) {
            intent.put("pendingDurabilityPolicy", history.getJsonObject("pendingDurabilityPolicy"));
        }
        return intent;
    }

    /**
     * Cuts the durability policy over to the standbys that remain after this node's promotion, and
     * serves. The record is the takeover record that {@link PgFailoverMonitor#promoteStandby} accepted,
     * or that record with the pending policy this method wrote. Returns the serving record.
     *
     * <p>Refused before any effect: no fresh ownership of exactly {@code held}, a phase other than
     * {@code WITHDRAWN}, no previous writer or this node as previous writer, or a confirmed policy that
     * does not name this node ({@link PgLeaseProtocolException}); no completed
     * {@value PgFailoverMonitor#CHECKPOINT_AFTER_PROMOTE} receipt of this operation
     * ({@link PgPromotionException}); quarantine ({@link PgLocalStateException}); no standby left in
     * the confirmed set once this node is removed ({@link PgDurabilityException}).
     *
     * <p>The next policy is the confirmed set without this node, at one more than the larger of the
     * confirmed revision and any carried pending revision. A carried pending policy with that set is
     * used unchanged; otherwise the pending policy is written first, with the phase still withdrawn.
     * The serving sequence then installs, confirms, prepares, publishes, and activates (plan §9.23).
     */
    public Future<PgControlRecord> cutover(PgControlRecord held, PgFailoverMode mode) {
        return Future.<Void>succeededFuture()
            .compose(ignored -> {
                Objects.requireNonNull(held, "held");
                Objects.requireNonNull(mode, "mode");
                requireTakeoverRecord(held);
                return requirePromotionCompleted(held);
            })
            .compose(ignored -> requireNoQuarantine())
            .compose(ignored -> withNextPolicyPending(held))
            .compose(pending -> PgPolicyCutover.serve(elector, monitor, pending, mode))
            // andThen, not onFailure: the caller sees the failure only after it is logged.
            .andThen(outcome -> {
                if (outcome.failed()) {
                    logger.error("Takeover cutover failed on node {}", config.nodeId(), outcome.cause());
                }
            });
    }

    private void requireTakeoverRecord(PgControlRecord held) {
        JsonObject intent = held.intent();
        JsonObject confirmed = intent.getJsonObject("durabilityPolicy");
        String previous = intent.getString("previousWriterNodeId");
        if (!elector.holds(held) || !"WITHDRAWN".equals(intent.getString("phase"))
                || previous == null || previous.equals(config.nodeId()) || confirmed == null
                || !confirmed.getJsonArray("requiredStandbyNodeIds").contains(config.nodeId())) {
            throw new PgLeaseProtocolException("The policy cutover requires fresh ownership of a withdrawn takeover "
                + "record whose confirmed durability policy names this node");
        }
    }

    /** The promotion sequence of this operation completed on this node, including its checkpoint. */
    private Future<Void> requirePromotionCompleted(PgControlRecord held) {
        String operation = held.intent().getString("operationId");
        return store.receipt(held.generation(), operation, PgFailoverMonitor.CHECKPOINT_AFTER_PROMOTE, config.nodeId())
            .map(receipt -> {
                if (receipt.filter(found -> found.result() == PgActionResult.COMPLETED).isEmpty()) {
                    throw new PgPromotionException("Operation " + operation + " has not completed its promotion on "
                        + "this node; its policy cannot be cut over");
                }
                return null;
            });
    }

    private Future<Void> requireNoQuarantine() {
        return store.quarantine().map(quarantine -> {
            if (quarantine.isPresent()) {
                throw new PgLocalStateException("Node is quarantined: " + quarantine.orElseThrow().reason());
            }
            return null;
        });
    }

    /** Returns the record with the next policy pending, writing it only when the record does not already hold it. */
    private Future<PgControlRecord> withNextPolicyPending(PgControlRecord held) {
        JsonObject intent = held.intent();
        PgDurabilityPolicy confirmed = PgDurabilityPolicy.fromJson(intent.getJsonObject("durabilityPolicy"));
        // A member tagged nosync is never in a required set (plan §9.31).
        List<String> remaining = confirmed.requiredStandbyNodeIds().stream()
            .filter(standby -> !standby.equals(config.nodeId()) && !config.tagsOf(standby).noSync()).toList();
        if (remaining.isEmpty()) {
            throw new PgDurabilityException("No standby of the confirmed policy remains after the promotion of "
                + config.nodeId() + "; committed-write admission stays closed until a standby is restored");
        }
        JsonObject carried = intent.getJsonObject("pendingDurabilityPolicy");
        long highest = confirmed.revision();
        if (carried != null) {
            PgDurabilityPolicy pending = PgDurabilityPolicy.fromJson(carried);
            if (Set.copyOf(pending.requiredStandbyNodeIds()).equals(Set.copyOf(remaining))) {
                return Future.succeededFuture(held);
            }
            highest = Math.max(highest, pending.revision());
        }
        PgDurabilityPolicy next = new PgDurabilityPolicy(highest + 1, remaining);
        return elector.update(held, intent.put("pendingDurabilityPolicy", next.toJson()));
    }
}
