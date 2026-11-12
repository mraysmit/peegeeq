package dev.mars.peegeeq.failover;

import io.vertx.core.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Member slots on a standby, advanced to the positions that members publish, and the publication of this node's own
 * position (plan §9.27, OD-40). No lease is required: a published position is never authority, and a slot advanced to
 * the smaller of a published position and the local replay position still keeps every WAL segment the member needs.
 * Both steps are refused under quarantine, because a quarantined node's position can belong to a diverged history.
 * Every failure is logged once at ERROR and returned.
 */
public final class PgMemberSlots {
    private static final Logger logger = LoggerFactory.getLogger(PgMemberSlots.class);
    private final PgNodeConfig config;
    private final PgLocalStateStore store;
    private final PgDurabilityControl durability;
    private final PgMemberPositions positions;

    public PgMemberSlots(PgNodeConfig config, PgLocalStateStore store, PgDurabilityControl durability,
                         PgMemberPositions positions) {
        this.config = Objects.requireNonNull(config, "config");
        this.store = Objects.requireNonNull(store, "store");
        this.durability = Objects.requireNonNull(durability, "durability");
        this.positions = Objects.requireNonNull(positions, "positions");
    }

    /** Observes this node's WAL position and publishes it. */
    public Future<Void> publishPosition() {
        return requireNoQuarantine().compose(ignored -> durability.position(config.nodeId()))
            .compose(positions::publish)
            // andThen, not onFailure: the caller sees the failure only after it is logged.
            .andThen(outcome -> {
                if (outcome.failed()) {
                    logger.error("Member position publication failed on node {}", config.nodeId(), outcome.cause());
                }
            });
    }

    /**
     * On a standby: ensures a slot for every other member, reads the published positions, and advances each slot.
     * Returns the members whose slots were advanced. The read admits members only, and this node holds no slot of its
     * own, so its own published position advances nothing.
     */
    public Future<Set<String>> advanceMemberSlots() {
        List<String> others = config.memberNodeIds().stream().filter(member -> !member.equals(config.nodeId())).toList();
        return requireNoQuarantine().compose(ignored -> durability.ensureSlots(others))
            .compose(ignored -> positions.read())
            .compose(durability::advanceSlots)
            .andThen(outcome -> {
                if (outcome.failed()) {
                    logger.error("Member slot advance failed on node {}", config.nodeId(), outcome.cause());
                }
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
}
