package dev.mars.peegeeq.failover;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Takeover steps that run on the new writer after it holds the lease. This increment has one: the
 * policy cutover after promotion. Every failure and refusal is logged once at ERROR and returned.
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
     * Stub: the tests of plan §9.29 are written against it before the implementation.
     */
    public Future<PgControlRecord> acquire(PgControlRecord observed, PgFailoverMode mode) {
        return Future.failedFuture(new UnsupportedOperationException("not implemented"));
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
        List<String> remaining = confirmed.requiredStandbyNodeIds().stream()
            .filter(standby -> !standby.equals(config.nodeId())).toList();
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
