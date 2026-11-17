package dev.mars.peegeeq.failover;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Per-node supervision of the local PostgreSQL process under the node's own writer lease.
 * It owns guarded start, the local writer grant and admission gate, and lease-loss shutdown.
 * Local effects run one at a time. Lease renewal does not queue behind them.
 *
 * <p>A demotion or revocation that the caller requested is logged at INFO. One forced by a
 * failure, and every refused or failed operation, is logged at ERROR.
 */
public final class PgFailoverMonitor {
    @FunctionalInterface
    private interface Operation<T> { Future<T> execute(); }
    private static final Logger logger = LoggerFactory.getLogger(PgFailoverMonitor.class);
    static final String START_PRIMARY = "start-primary";
    static final String STOP_WRITER = "stop-writer";
    static final String INSTALL_POLICY = "install-policy";
    static final String PROMOTE = "promote";
    static final String CHECKPOINT_AFTER_PROMOTE = "checkpoint-after-promote";
    static final String FOLLOW_WRITER = "follow-writer";
    static final String REWIND = "rewind";
    static final String REBUILD = "rebuild";
    static final String RESTART = "restart";
    /** The reason of the quarantine that a re-join keeps until standby validation (plan §9.32, OD-51). */
    static final String REJOIN_QUARANTINE = "Re-join in progress: rewind or rebuild, then standby validation";
    /** The start of the reason of a quarantine that only an operator clears (plan §9.32, OD-51). */
    static final String OPERATOR_QUARANTINE = "Operator reconciliation required: ";
    private final PgNodeConfig config;
    private final PgPrimaryElector elector;
    private final PgLocalStateStore store;
    private final PgProcessControl process;
    private final PgAdmissionGate gate;
    private final PgWatchdog watchdog;
    private final PgDurabilityControl durability;
    private final PgPromotionControl promotion;
    private final PgMemberPositions positions;
    private final PgConfigurationControl configuration;
    private volatile PgControlRecord lastHeld;
    // Derived at run time: whether this monitor armed the watchdog and has not disarmed it.
    private volatile boolean armed;
    private Future<?> tail = Future.succeededFuture();

    private PgFailoverMonitor(Builder builder) {
        this.config = builder.config;
        this.elector = builder.elector;
        this.store = builder.store;
        this.process = builder.process;
        this.gate = builder.gate;
        this.watchdog = builder.watchdog;
        this.durability = builder.durability;
        this.promotion = builder.promotion;
        this.positions = builder.positions;
        this.configuration = builder.configuration;
    }

    /** A builder that requires every dependency (plan §9.28, P7c). */
    public static Builder builder() {
        return new Builder();
    }

    /** Sets the monitor's dependencies. {@link #build()} refuses a missing one by name. */
    public static final class Builder {
        private PgNodeConfig config;
        private PgPrimaryElector elector;
        private PgLocalStateStore store;
        private PgProcessControl process;
        private PgAdmissionGate gate;
        private PgWatchdog watchdog;
        private PgDurabilityControl durability;
        private PgPromotionControl promotion;
        private PgMemberPositions positions;
        private PgConfigurationControl configuration;

        private Builder() {
        }

        public Builder config(PgNodeConfig value) { config = value; return this; }

        public Builder elector(PgPrimaryElector value) { elector = value; return this; }

        public Builder store(PgLocalStateStore value) { store = value; return this; }

        public Builder process(PgProcessControl value) { process = value; return this; }

        public Builder gate(PgAdmissionGate value) { gate = value; return this; }

        /** Used only when the configured watchdog mode is not {@code OFF}. */
        public Builder watchdog(PgWatchdog value) { watchdog = value; return this; }

        public Builder durability(PgDurabilityControl value) { durability = value; return this; }

        public Builder promotion(PgPromotionControl value) { promotion = value; return this; }

        public Builder positions(PgMemberPositions value) { positions = value; return this; }

        /** Reload and the settings observation (plan §9.33, OD-55). */
        public Builder configuration(PgConfigurationControl value) { configuration = value; return this; }

        public PgFailoverMonitor build() {
            Objects.requireNonNull(config, "config");
            Objects.requireNonNull(elector, "elector");
            Objects.requireNonNull(store, "store");
            Objects.requireNonNull(process, "process");
            Objects.requireNonNull(gate, "gate");
            Objects.requireNonNull(watchdog, "watchdog");
            Objects.requireNonNull(durability, "durability");
            Objects.requireNonNull(promotion, "promotion");
            Objects.requireNonNull(positions, "positions");
            Objects.requireNonNull(configuration, "configuration");
            return new PgFailoverMonitor(this);
        }
    }

    /**
     * Observes this node's WAL position and publishes it (plan §9.27, OD-37; §9.28, P7c). No lease is required: a
     * published position is never authority for exclusion. Refused under quarantine ({@link PgLocalStateException}),
     * because a quarantined node's position can belong to a diverged history.
     */
    public Future<Void> publishPosition() {
        return serialized(() -> requireNoQuarantine().compose(ignored -> durability.position(config.nodeId()))
            .compose(positions::publish));
    }

    /**
     * Whether this node is a preferred takeover candidate for the observed record (plan §9.28, P7d). Refused when the
     * record's confirmed policy does not name this node ({@link PgLeaseProtocolException}), and under quarantine. Not
     * preferred when another member of the confirmed policy published a position on a higher timeline, or on the same
     * timeline beyond this node's candidate position. A member with no published position is ignored.
     */
    public Future<PgCandidacy> takeoverCandidacy(PgControlRecord observed) {
        return serialized(() -> {
            Objects.requireNonNull(observed, "observed");
            JsonObject confirmed = observed.intent().getJsonObject("durabilityPolicy");
            if (confirmed == null || !confirmed.getJsonArray("requiredStandbyNodeIds").contains(config.nodeId())) {
                throw new PgLeaseProtocolException("Takeover candidacy requires a confirmed durability policy that names "
                    + "this node");
            }
            // A rival tagged nofailover or nosync can never take over, so it is no reason to defer (plan §9.31).
            List<String> rivals = PgDurabilityPolicy.fromJson(confirmed).requiredStandbyNodeIds().stream()
                .filter(standby -> !standby.equals(config.nodeId()) && config.tagsOf(standby).eligibleForTakeover())
                .toList();
            return requireNoQuarantine().compose(ignored -> durability.candidatePosition(config.nodeId()))
                .compose(own -> positions.read().map(published -> candidacy(own, rivals, published)));
        });
    }

    /**
     * Not preferred when a rival published a position on a higher timeline, or on the same timeline beyond this node's.
     * A published position is at most the rival's present position, so this node defers only to a rival that is ahead.
     */
    private static PgCandidacy candidacy(PgMemberPosition own, List<String> rivals, Map<String, PgMemberPosition> published) {
        for (String rival : rivals) {
            PgMemberPosition other = published.get(rival);
            if (other == null) continue;
            if (other.timeline() > own.timeline()
                    || other.timeline() == own.timeline() && other.lsn().compareTo(own.lsn()) > 0) {
                return new PgCandidacy(false, rival + " published timeline " + other.timeline() + " at " + other.lsn()
                    + ", ahead of this node's timeline " + own.timeline() + " at " + own.lsn());
            }
        }
        return new PgCandidacy(true, "No other covered candidate published a position ahead of this node's timeline "
            + own.timeline() + " at " + own.lsn());
    }

    /**
     * On a standby: ensures a slot for every other member, reads the published positions, and advances each slot to the
     * smaller of the published and the local replay position (plan §9.27, contract 4). Returns the members whose slots
     * were advanced. Refused under quarantine ({@link PgLocalStateException}). The read admits members only, and this
     * node holds no slot of its own, so its own published position advances nothing.
     */
    public Future<Set<String>> advanceMemberSlots() {
        return serialized(() -> requireNoQuarantine().compose(ignored -> durability.ensureSlots(otherMembers()))
            .compose(ignored -> positions.read())
            .compose(durability::advanceSlots));
    }

    /**
     * Promotes the local standby to a writable PostgreSQL under this node's lease, with application
     * admission closed, and takes the checkpoint that follows a promotion. The record is the takeover
     * record: fresh ownership of exactly {@code held}, {@code WITHDRAWN} intent, a previous writer other
     * than this node, and a confirmed durability policy that names this node. Anything else, and
     * quarantine, is refused before any effect and with no receipt. The grant and the gate are closed
     * first, and the watchdog mode is applied as a primary start applies it.
     *
     * <p>In recovery, the {@value #PROMOTE} receipt is begun, the standby is promoted, and the result
     * is recorded: {@code COMPLETED}, or {@code UNKNOWN} after a failure. A node that is already
     * writable is accepted only when this operation holds a {@value #PROMOTE} receipt, which a rerun
     * after a lost reply completes. Ownership lost while the node was promoted demotes it and fails
     * the call. The {@value #CHECKPOINT_AFTER_PROMOTE} receipt then covers the forced checkpoint; a
     * completed one is not run again. Last, a physical replication slot is ensured for every other member,
     * with no receipt (OD-32). A failed slot step leaves the node writable with admission closed, and a rerun
     * repeats only the missing work. The control record, the policy, and the grant are not changed.
     */
    public Future<Void> promoteStandby(PgControlRecord held) {
        return serialized(() -> {
            Objects.requireNonNull(held, "held");
            JsonObject intent = held.intent();
            JsonObject confirmed = intent.getJsonObject("durabilityPolicy");
            String previous = intent.getString("previousWriterNodeId");
            if (!elector.holds(held) || !"WITHDRAWN".equals(intent.getString("phase"))
                    || previous == null || previous.equals(config.nodeId()) || confirmed == null
                    || !confirmed.getJsonArray("requiredStandbyNodeIds").contains(config.nodeId())) {
                throw new PgLeaseProtocolException("Promotion requires fresh ownership of a withdrawn takeover "
                    + "record whose confirmed durability policy names this node");
            }
            if (!config.tagsOf(config.nodeId()).eligibleForTakeover()) {
                throw new PgLeaseProtocolException("This node is tagged nofailover or nosync; it is never promoted");
            }
            String operation = intent.getString("operationId");
            return requireNoQuarantine().compose(ignored -> store.closeGrant())
                .compose(ignored -> gate.close(config.stopTimeout()))
                .compose(ignored -> applyWatchdogMode())
                .compose(ignored -> promotion.inRecovery())
                .compose(recovering -> recovering ? promoteAndRecord(held, operation)
                    : confirmEarlierPromotion(held, operation))
                .compose(ignored -> elector.hasFreshOwnership() ? Future.<Void>succeededFuture()
                    : demoteAfterPromotion(held))
                .compose(ignored -> checkpointAfterPromotion(held, operation))
                // The slots exist before any coverage wait on this writer begins (plan §9.24, OD-32).
                .compose(ignored -> durability.ensureSlots(otherMembers()));
        });
    }

    /**
     * Points the local standby at the writer that {@code observed} names, and confirms that its WAL
     * receiver streams from {@code writer} through this node's slot on that writer. The follower holds no
     * lease, so no ownership is required.
     * Refused before any effect and with no receipt: a record that names this node as writer
     * ({@link PgLeaseProtocolException}), quarantine ({@link PgLocalStateException}), and a server that
     * is not in recovery ({@link PgPromotionException}); a writable server needs a rewind, not a follow.
     * The grant and the gate are closed first. The {@value #FOLLOW_WRITER} receipt carries the requested
     * host and port, so a receipt for another address refuses the call. A failed follow records
     * {@code UNKNOWN}. A rerun with equal parameters repeats the observed effect. The control record, the
     * policy, and the grant are not changed.
     */
    public Future<Void> followWriter(PgControlRecord observed, PgWriterEndpoint writer) {
        return serialized(() -> {
            Objects.requireNonNull(observed, "observed");
            Objects.requireNonNull(writer, "writer");
            JsonObject intent = observed.intent();
            if (config.nodeId().equals(intent.getString("writerNodeId"))) {
                throw new PgLeaseProtocolException("A node cannot follow itself as the writer");
            }
            JsonObject parameters = new JsonObject().put("host", writer.host()).put("port", writer.port());
            return requireNoQuarantine().compose(ignored -> promotion.inRecovery()).compose(recovering -> {
                if (!recovering) {
                    throw new PgPromotionException("PostgreSQL is writable; a writable server cannot follow "
                        + "a writer without a rewind");
                }
                return store.closeGrant();
            }).compose(ignored -> gate.close(config.stopTimeout()))
                .compose(ignored -> underReceipt(observed, intent.getString("operationId"), FOLLOW_WRITER,
                    parameters, () -> promotion.follow(config.nodeId(), writer)));
        });
    }

    /**
     * Makes this node a validated standby of the writer that {@code observed} names, without an operator (plan §9.32,
     * contract 6). Refused before any effect and with no receipt: a record that names this node as writer or that no
     * lease holder holds ({@link PgLeaseProtocolException}), and a quarantine with a reason other than
     * {@value #REJOIN_QUARANTINE} ({@link PgLocalStateException}). The node is then quarantined until the end. A present
     * data directory is stopped and rewound under the {@value #REWIND} receipt; when the rewind, the standby start, the
     * follow, or the catch-up fails, it is rebuilt under the {@value #REBUILD} receipt. An absent directory is rebuilt.
     * Before each, the writer must be writable, of this node's system, and hold this node's usable slot; another system
     * leaves the node for the operator. {@code budget} bounds the rewind and the rebuild. Admission stays closed.
     */
    public Future<Void> rejoin(PgControlRecord observed, PgWriterEndpoint writer, Duration budget) {
        return serialized(() -> {
            Objects.requireNonNull(observed, "observed");
            Objects.requireNonNull(writer, "writer");
            Objects.requireNonNull(budget, "budget");
            JsonObject intent = observed.intent();
            if (config.nodeId().equals(intent.getString("writerNodeId"))) {
                throw new PgLeaseProtocolException("A node cannot re-join itself as the writer");
            }
            if (observed.leaseHolder() == null) {
                throw new PgLeaseProtocolException("A re-join needs the writer that holds the lease; the record is unowned");
            }
            Rejoin rejoin = new Rejoin(observed, intent.getString("operationId"), writer, budget,
                new JsonObject().put("host", writer.host()).put("port", writer.port()));
            return store.quarantine().compose(existing -> {
                if (existing.filter(quarantine -> !REJOIN_QUARANTINE.equals(quarantine.reason())).isPresent()) {
                    throw new PgLocalStateException("Node is quarantined: " + existing.orElseThrow().reason());
                }
                logger.info("Re-joining node {} as a standby of {}:{}", config.nodeId(), writer.host(), writer.port());
                return store.quarantine(REJOIN_QUARANTINE).compose(ignored -> store.closeGrant())
                    .compose(ignored -> inspectForRejoin(existing.isPresent()))
                    .compose(found -> found.readable().isPresent()
                        ? rewindOrRebuild(rejoin, found.readable().orElseThrow())
                        : rebuild(rejoin, null, found.exists()));
            }).compose(ignored -> store.clearQuarantine()).map(ignored -> {
                logger.info("Node {} re-joined as a standby of {}:{}", config.nodeId(), writer.host(), writer.port());
                return null;
            });
        });
    }

    /** One re-join's inputs. */
    private record Rejoin(PgControlRecord observed, String operation, PgWriterEndpoint writer, Duration budget,
                          JsonObject parameters) { }

    /** What the inspection found: the readable data directory, or none; and whether a directory exists at all. */
    private record Found(Optional<PgDataDirectory> readable, boolean exists) { }

    /**
     * The data directory, or none when it is absent. An unreadable one is left for the operator, unless this node was
     * already under the re-join quarantine: then it is an interrupted rebuild, and is rebuilt.
     */
    private Future<Found> inspectForRejoin(boolean resumed) {
        return process.inspect().transform(inspected -> {
            if (inspected.succeeded()) {
                return Future.succeededFuture(new Found(inspected.result(), inspected.result().isPresent()));
            }
            if (resumed) {
                logger.error("The data directory of node {} cannot be read during an interrupted re-join; rebuilding",
                    config.nodeId(), inspected.cause());
                return Future.succeededFuture(new Found(Optional.empty(), true));
            }
            return leaveForOperator("The data directory cannot be read: " + inspected.cause().getMessage(),
                inspected.cause());
        });
    }

    /**
     * Checks the source, stops the server, then rewinds; a rewind that does not produce a caught-up standby is followed
     * by a rebuild. The source is checked first, so a refusal leaves a running server running.
     */
    private Future<Void> rewindOrRebuild(Rejoin rejoin, PgDataDirectory directory) {
        return requireUsableSource(rejoin, directory)
            .compose(ignored -> gate.close(config.stopTimeout()))
            .compose(ignored -> process.stop(config.stopTimeout()))
            .compose(stopped -> underReceipt(rejoin.observed(), rejoin.operation(), REWIND, rejoin.parameters(),
                    () -> process.rewind(rejoin.writer(), config.nodeId(), rejoin.budget()))
                .compose(rewoundDirectory -> startStandby(rejoin))
                .transform(rewound -> {
                    if (rewound.succeeded()) return Future.<Void>succeededFuture();
                    // A storage fault is not a failed rewind: a rebuild cannot cure it, and must not remove data for it.
                    if (rewound.cause() instanceof PgLocalStateException) return Future.<Void>failedFuture(rewound.cause());
                    logger.error("Rewind did not make node {} a standby of {}:{}; rebuilding", config.nodeId(),
                        rejoin.writer().host(), rejoin.writer().port(), rewound.cause());
                    return rebuild(rejoin, directory, true);
                }));
    }

    /**
     * Stops the server when a data directory exists, observes the source again right before the removal, and rebuilds.
     * {@code directory} is the readable data directory found at the start, whose system the source must share; null
     * when there was none.
     */
    private Future<Void> rebuild(Rejoin rejoin, PgDataDirectory directory, boolean exists) {
        return (exists ? process.stop(config.stopTimeout()) : Future.<Void>succeededFuture())
            .compose(ignored -> requireUsableSource(rejoin, directory))
            .compose(ignored -> underReceipt(rejoin.observed(), rejoin.operation(), REBUILD, rejoin.parameters(),
                () -> process.rebuild(rejoin.writer(), config.nodeId(), rejoin.budget())))
            .compose(ignored -> startStandby(rejoin));
    }

    /**
     * The source must be writable and hold this node's usable slot; both can change, so the re-join quarantine stays for
     * a retry. A different system identifier cannot change, so it leaves the node for the operator.
     */
    private Future<Void> requireUsableSource(Rejoin rejoin, PgDataDirectory directory) {
        PgWriterEndpoint writer = rejoin.writer();
        return promotion.observeSource(config.nodeId(), writer).compose(source -> {
            if (directory != null && !directory.systemIdentifier().equals(source.systemIdentifier())) {
                PgPromotionException other = new PgPromotionException("The data directory belongs to system "
                    + directory.systemIdentifier() + " and " + writer.host() + " to system " + source.systemIdentifier());
                return leaveForOperator(other.getMessage(), other).<Void>mapEmpty();
            }
            if (source.inRecovery()) {
                throw new PgPromotionException(writer.host() + ":" + writer.port() + " is in recovery; a re-join uses "
                    + "the writer only");
            }
            if (!source.slotUsable()) {
                throw new PgPromotionException("The slot " + PgNodeConfig.slotName(config.nodeId()) + " on "
                    + writer.host() + " is missing or lost; the writer must create or replace it first");
            }
            return Future.<Void>succeededFuture();
        });
    }

    /** Closes the gate, starts in recovery, follows under its receipt, and waits for the catch-up. */
    private Future<Void> startStandby(Rejoin rejoin) {
        return gate.close(config.stopTimeout()).compose(ignored -> process.startInRecovery())
            .compose(ignored -> underReceipt(rejoin.observed(), rejoin.operation(), FOLLOW_WRITER, rejoin.parameters(),
                () -> promotion.follow(config.nodeId(), rejoin.writer())))
            .compose(ignored -> promotion.confirmCaughtUp(config.nodeId(), rejoin.writer()));
    }

    /** Replaces the re-join quarantine by one that only an operator clears, and fails with the cause. */
    private <T> Future<T> leaveForOperator(String reason, Throwable cause) {
        return store.quarantine(OPERATOR_QUARANTINE + reason).transform(written -> {
            if (written.failed()) cause.addSuppressed(written.cause());
            return Future.<T>failedFuture(cause);
        });
    }

    /**
     * Ensures that this writer holds a physical replication slot for every other member of the membership, so
     * that each member's WAL is kept within the configured bound (plan §9.24). Refused without fresh ownership
     * of exactly {@code held} ({@link PgLeaseProtocolException}) and under quarantine
     * ({@link PgLocalStateException}). No receipt: the slots are the evidence (OD-28).
     */
    public Future<Void> ensureMemberSlots(PgControlRecord held) {
        return serialized(() -> {
            Objects.requireNonNull(held, "held");
            if (!elector.holds(held)) {
                throw new PgLeaseProtocolException("Member slots require fresh ownership of the writer lease");
            }
            return requireNoQuarantine().compose(ignored -> durability.ensureSlots(otherMembers()));
        });
    }

    /**
     * Replaces the lost slot of another member, so that the member can be rebuilt through it (plan §9.26). Refused
     * without fresh ownership of exactly {@code held} ({@link PgLeaseProtocolException}), for an identity that is not
     * another member ({@link IllegalArgumentException}), and under quarantine ({@link PgLocalStateException}). No
     * receipt (OD-34): a rerun after a lost reply is refused, because the new slot is not lost.
     */
    public Future<Void> replaceLostMemberSlot(PgControlRecord held, String nodeId) {
        return serialized(() -> {
            Objects.requireNonNull(held, "held");
            if (!elector.holds(held)) {
                throw new PgLeaseProtocolException("Replacing a member slot requires fresh ownership of the writer lease");
            }
            if (!otherMembers().contains(nodeId)) {
                throw new IllegalArgumentException(nodeId + " is not another member of the membership");
            }
            return requireNoQuarantine().compose(ignored -> durability.replaceLostSlot(nodeId));
        });
    }

    /** The members whose slots this node keeps while it is the writer: the membership without this node. */
    private List<String> otherMembers() {
        return config.memberNodeIds().stream().filter(member -> !member.equals(config.nodeId())).toList();
    }

    /** Begins the receipt, promotes, and records the result. A failed promotion records {@code UNKNOWN}. */
    private Future<Void> promoteAndRecord(PgControlRecord held, String operation) {
        return underReceipt(held, operation, PROMOTE, new JsonObject(), promotion::promote);
    }

    /**
     * Begins the receipt of an action with the given immutable parameters, runs the effect, and records
     * its result: {@code COMPLETED}, or {@code UNKNOWN} after a failure. The effect's failure is returned,
     * with a failure to record attached as suppressed. A failure to record a successful effect is
     * returned, and the receipt stays {@code PENDING}.
     */
    private Future<Void> underReceipt(PgControlRecord held, String operation, String action, JsonObject parameters,
                                      Operation<Void> effect) {
        return store.begin(held.generation(), operation, action, config.nodeId(), parameters)
            .compose(receipt -> effect.execute().transform(done -> {
                PgActionResult result = done.succeeded() ? PgActionResult.COMPLETED : PgActionResult.UNKNOWN;
                return store.record(held.generation(), operation, action, config.nodeId(), result, new JsonObject())
                    .transform(recorded -> settled(done, recorded));
            }));
    }

    /** The effect's failure wins, with a failure to record attached. Otherwise a failure to record fails the call. */
    private static Future<Void> settled(AsyncResult<Void> effect, AsyncResult<?> recorded) {
        if (effect.failed()) {
            if (recorded.failed()) effect.cause().addSuppressed(recorded.cause());
            return Future.failedFuture(effect.cause());
        }
        return recorded.succeeded() ? Future.<Void>succeededFuture() : Future.<Void>failedFuture(recorded.cause());
    }

    /** A writable node is accepted only when this operation recorded a promotion; a pending one is completed. */
    private Future<Void> confirmEarlierPromotion(PgControlRecord held, String operation) {
        return store.receipt(held.generation(), operation, PROMOTE, config.nodeId()).compose(receipt -> {
            if (receipt.isEmpty()) {
                throw new PgPromotionException("PostgreSQL is writable but this operation recorded no promotion; "
                    + "the node was not promoted by it");
            }
            if (receipt.orElseThrow().result() == PgActionResult.COMPLETED) return Future.<Void>succeededFuture();
            return store.record(held.generation(), operation, PROMOTE, config.nodeId(), PgActionResult.COMPLETED,
                new JsonObject()).<Void>mapEmpty();
        });
    }

    /** A writable node that the lease no longer covers must not run. */
    private Future<Void> demoteAfterPromotion(PgControlRecord held) {
        PgLeaseProtocolException cause = new PgLeaseProtocolException("Ownership was lost while the standby was promoted");
        String reason = "Promotion did not complete under ownership";
        logger.error("Demoting local PostgreSQL on node {}: {}", config.nodeId(), reason, cause);
        return demoteNow(held, reason).transform(demotion -> {
            if (demotion.failed()) cause.addSuppressed(demotion.cause());
            return Future.<Void>failedFuture(cause);
        });
    }

    /** Runs the forced checkpoint under its own receipt. A failure records {@code UNKNOWN}. A completed receipt is not run again. */
    private Future<Void> checkpointAfterPromotion(PgControlRecord held, String operation) {
        return store.receipt(held.generation(), operation, CHECKPOINT_AFTER_PROMOTE, config.nodeId())
            .compose(existing -> {
                if (existing.filter(receipt -> receipt.result() == PgActionResult.COMPLETED).isPresent()) {
                    return Future.<Void>succeededFuture();
                }
                return underReceipt(held, operation, CHECKPOINT_AFTER_PROMOTE, new JsonObject(), promotion::checkpoint);
            });
    }

    /**
     * Reloads the local configuration files and confirms the reload (plan §9.33, contract 2). Serialized. No lease and no
     * receipt: the load time is the evidence (OD-28, OD-34). Allowed under quarantine (OD-61): a reload changes no role
     * and no admission.
     */
    public Future<Void> reload() {
        return serialized(configuration::reload);
    }

    /**
     * Restarts the local PostgreSQL through the supervisor's own stop and guarded start, never {@code pg_ctl restart}
     * (plan §9.33, contract 3, OD-54). Serialized. Refused before any effect and with no receipt: quarantine
     * ({@link PgLocalStateException}); a stopped server ({@link PgProcessControlException}); when {@code observed} names
     * this node as writer, no fresh ownership of exactly {@code observed} or a server in recovery
     * ({@link PgLeaseProtocolException}); otherwise a writable server ({@link PgPromotionException}). A reload first makes
     * the pending flags current; a failed reload refuses the restart. A condition of {@code request} that is not met
     * returns a result that did not restart. Otherwise the {@value #RESTART} receipt covers the restart. A writer that
     * was serving with an open grant is admitted again with a grant of the same mode and revision (OD-58); a writer whose
     * start fails, or whose ownership is lost, is demoted.
     */
    public Future<PgRestartResult> restart(PgControlRecord observed, PgRestartRequest request) {
        return serialized(() -> restartNow(observed, request));
    }

    /**
     * The restart of {@link #restart}, for a caller that already runs inside a serialized operation. A refusal of the
     * record or the ownership is thrown at once.
     */
    private Future<PgRestartResult> restartNow(PgControlRecord observed, PgRestartRequest request) {
        Objects.requireNonNull(observed, "observed");
        Objects.requireNonNull(request, "request");
        String named = observed.intent().getString("writerNodeId");
        boolean writer = config.nodeId().equals(named);
        if (writer && !elector.holds(observed)) {
            throw new PgLeaseProtocolException("A writer restart requires fresh ownership of exactly the observed "
                + "record");
        }
        return requireNoQuarantine().compose(ignored -> process.status()).compose(state -> {
            if (state != PgProcessState.RUNNING) {
                throw new PgProcessControlException("PostgreSQL is stopped; a restart needs a running server");
            }
            return promotion.inRecovery();
        }).compose(recovering -> {
            if (writer && recovering) {
                throw new PgLeaseProtocolException("PostgreSQL is in recovery although the record names this node "
                    + "as writer");
            }
            if (!writer && !recovering) {
                throw new PgPromotionException("PostgreSQL is writable but the record names " + named + " as writer; "
                    + "this node needs a re-join, not a restart");
            }
            // The reload makes the pending flags current, and refuses a value PostgreSQL would not start with.
            return configuration.reload();
        }).compose(ignored -> configuration.settings()).compose(before -> {
            Optional<String> unmet = unmetCondition(request, before, writer);
            if (unmet.isPresent()) {
                logger.info("Restart of node {} not run: {}", config.nodeId(), unmet.orElseThrow());
                return Future.succeededFuture(new PgRestartResult(false, unmet.orElseThrow()));
            }
            logger.info("Restarting local PostgreSQL on node {} as {}", config.nodeId(), writer ? "writer" : "standby");
            return underReceipt(observed, request.operationId(), RESTART, request.conditions(),
                    () -> (writer ? restartWriter(observed) : restartStandby()).compose(ignored -> startedAgain(before)))
                .map(ignored -> new PgRestartResult(true, "Restarted as " + (writer ? "writer" : "standby")));
        });
    }

    /**
     * Schedules a restart of this node at {@code due} (plan §9.33, contract 4). Serialized. Observes the postmaster start
     * time and stores the schedule in the node-local store (OD-59). Refused while a schedule exists
     * ({@link PgLocalStateException}), while PostgreSQL is stopped ({@link PgConfigurationException}), and under
     * quarantine ({@link PgLocalStateException}).
     */
    public Future<Void> scheduleRestart(PgRestartRequest request, java.time.Instant due) {
        return serialized(() -> {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(due, "due");
            return requireNoQuarantine().compose(ignored -> configuration.settings())
                .compose(settings -> store.scheduleRestart(new PgScheduledRestart(config.nodeId(), request, due,
                    settings.postmasterStartMicros())))
                .map(ignored -> {
                    logger.info("Restart of node {} scheduled for {}", config.nodeId(), due);
                    return null;
                });
        });
    }

    /** Removes the scheduled restart, and returns whether one existed (plan §9.33, contract 4). Serialized. */
    public Future<Boolean> cancelScheduledRestart() {
        return serialized(store::clearScheduledRestart);
    }

    /**
     * Runs the scheduled restart when it is due (plan §9.33, contract 5). Serialized. No schedule, or {@code now} before
     * the due time: nothing runs. A postmaster start time other than the stored one: the restart already happened, so the
     * schedule is removed and nothing runs. Otherwise the restart of {@link #restart} runs, and the schedule is removed
     * whatever its outcome; a failed restart is returned, with a failure to remove the schedule attached.
     */
    public Future<PgRestartResult> runScheduledRestart(PgControlRecord observed, java.time.Instant now) {
        return serialized(() -> {
            Objects.requireNonNull(observed, "observed");
            Objects.requireNonNull(now, "now");
            return store.scheduledRestart().compose(found -> {
                if (found.isEmpty()) return Future.succeededFuture(new PgRestartResult(false, "No restart is scheduled"));
                PgScheduledRestart schedule = found.orElseThrow();
                if (now.isBefore(schedule.due())) {
                    return Future.succeededFuture(new PgRestartResult(false, "The scheduled restart is due at "
                        + schedule.due()));
                }
                return configuration.settings().compose(settings -> {
                    if (settings.postmasterStartMicros() != schedule.postmasterStartMicros()) {
                        String reason = "PostgreSQL started again since the restart was scheduled; the restart already "
                            + "happened";
                        logger.info("Cancelling the scheduled restart of node {}: {}", config.nodeId(), reason);
                        return store.clearScheduledRestart().map(cleared -> new PgRestartResult(false, reason));
                    }
                    logger.info("Running the restart of node {} scheduled for {}", config.nodeId(), schedule.due());
                    // The schedule is removed whatever the outcome, as Patroni clears it after the attempt.
                    return Future.<Void>succeededFuture().compose(ignored -> restartNow(observed, schedule.request()))
                        .transform(outcome -> store.clearScheduledRestart().transform(cleared -> {
                            if (outcome.failed()) {
                                if (cleared.failed()) outcome.cause().addSuppressed(cleared.cause());
                                return Future.<PgRestartResult>failedFuture(outcome.cause());
                            }
                            return cleared.failed() ? Future.<PgRestartResult>failedFuture(cleared.cause())
                                : Future.succeededFuture(outcome.result());
                        }));
                });
            });
        });
    }

    /** The first condition of the request that the live observation does not meet, or none (OD-57). */
    private static Optional<String> unmetCondition(PgRestartRequest request, PgServerSettings settings, boolean writer) {
        if (request.onlyIfPending() && settings.pendingRestart().isEmpty()) {
            return Optional.of("No parameter is pending a restart, and the request restarts only when one is pending");
        }
        PgRestartRole actual = writer ? PgRestartRole.WRITER : PgRestartRole.STANDBY;
        if (request.role().filter(required -> required != actual).isPresent()) {
            return Optional.of("The node is a " + actual + ", and the request restarts only a "
                + request.role().orElseThrow());
        }
        if (request.belowVersion().isPresent() && settings.serverVersion() >= request.belowVersion().getAsInt()) {
            return Optional.of("The server version " + settings.serverVersion() + " is not below "
                + request.belowVersion().getAsInt());
        }
        return Optional.empty();
    }

    /** The restart is confirmed when the postmaster started later than before the stop. */
    private Future<Void> startedAgain(PgServerSettings before) {
        return configuration.settings().map(after -> {
            if (after.postmasterStartMicros() <= before.postmasterStartMicros()) {
                throw new PgProcessControlException("The postmaster did not start again");
            }
            return null;
        });
    }

    /**
     * Closes admission, stops the writer, and starts it writable through the guarded path. An unconfirmed stop
     * quarantines the node. A start that fails, or ownership lost while it ran, demotes the node. A writer that served
     * with the open grant of exactly this record is admitted again with a grant of the same mode and revision (OD-58).
     */
    private Future<Void> restartWriter(PgControlRecord observed) {
        return store.grant().compose(stored -> {
            Optional<PgWriterGrant> serving = stored.filter(grant -> servedUnder(grant, observed));
            return store.closeGrant().compose(ignored -> gate.close(config.stopTimeout()))
                .compose(ignored -> stopForRestart())
                .compose(ignored -> applyWatchdogMode().compose(applied -> process.startPrimary()).transform(started -> {
                    if (started.succeeded() && elector.holds(observed)) return Future.<Void>succeededFuture();
                    Throwable cause = started.failed() ? started.cause()
                        : new PgLeaseProtocolException("Ownership was lost while PostgreSQL restarted");
                    String reason = "Restart did not complete under ownership";
                    logger.error("Demoting local PostgreSQL on node {}: {}", config.nodeId(), reason, cause);
                    return demoteNow(observed, reason).transform(demotion -> {
                        if (demotion.failed()) cause.addSuppressed(demotion.cause());
                        return Future.<Void>failedFuture(cause);
                    });
                }))
                .compose(ignored -> serving.isPresent() ? readmit(observed, serving.orElseThrow())
                    : Future.<Void>succeededFuture());
        });
    }

    /** The grant was open for exactly this serving record: same node, generation, operation, and confirmed revision. */
    private boolean servedUnder(PgWriterGrant grant, PgControlRecord observed) {
        JsonObject intent = observed.intent();
        JsonObject confirmed = intent.getJsonObject("durabilityPolicy");
        return grant.state() == PgGrantState.OPEN && "SERVING".equals(intent.getString("phase")) && confirmed != null
            && grant.generation() == observed.generation()
            && grant.operationId().equals(intent.getString("operationId"))
            && grant.policyRevision() == confirmed.getLong("revision")
            && grant.nodeId().equals(config.nodeId());
    }

    /** Stops PostgreSQL within the stop budget. An unconfirmed stop of a writer quarantines the node, as a demotion does. */
    private Future<Void> stopForRestart() {
        return process.stop(config.stopTimeout()).transform(stopped -> {
            if (stopped.succeeded()) return Future.<Void>succeededFuture();
            logger.error("Local writer stop is unconfirmed on node {}", config.nodeId(), stopped.cause());
            return store.quarantine("Writer stop unconfirmed: restart").transform(quarantined -> {
                if (quarantined.failed()) stopped.cause().addSuppressed(quarantined.cause());
                return Future.<Void>failedFuture(stopped.cause());
            });
        });
    }

    /** Prepares and activates a grant of the same mode and revision, then opens the gate; a failure revokes admission. */
    private Future<Void> readmit(PgControlRecord observed, PgWriterGrant served) {
        return store.prepare(served.mode(), observed.generation(), served.operationId(), served.policyRevision())
            .compose(store::activate)
            .compose(open -> gate.open().transform(opened -> {
                if (opened.succeeded() && elector.holds(observed)) return Future.<Void>succeededFuture();
                Throwable cause = opened.failed() ? opened.cause()
                    : new PgLeaseProtocolException("Ownership was lost while admission opened");
                logger.error("Revoking writer admission on node {}: {}", config.nodeId(),
                    "Restart did not restore admission", cause);
                return revokeNow().transform(revoked -> {
                    if (revoked.failed()) cause.addSuppressed(revoked.cause());
                    return Future.<Void>failedFuture(cause);
                });
            }));
    }

    /** Closes admission, stops the standby, starts it in recovery, and confirms it is in recovery. */
    private Future<Void> restartStandby() {
        return store.closeGrant().compose(ignored -> gate.close(config.stopTimeout()))
            .compose(ignored -> process.stop(config.stopTimeout()))
            .compose(ignored -> process.startInRecovery())
            .compose(ignored -> promotion.inRecovery())
            .map(recovering -> {
                if (!recovering) throw new PgPromotionException("The restarted standby is not in recovery");
                return null;
            });
    }

    /** Starts PostgreSQL in recovery with admission closed. No lease is required. */
    public Future<Void> startStandby() {
        return serialized(() -> store.closeGrant()
            .compose(ignored -> gate.close(config.stopTimeout()))
            .compose(ignored -> process.startInRecovery()));
    }

    /**
     * Starts PostgreSQL writable under this node's fresh lease, with admission closed. Refused
     * without fresh ownership of exactly this record, under quarantine, when the gate cannot be
     * closed first, or in {@code REQUIRED} watchdog mode when the watchdog cannot be armed with a
     * safe timeout. In {@code AUTOMATIC} mode that condition is reported and the start continues.
     * If the start fails, or ownership is lost while it runs, the node is demoted and the call fails.
     */
    public Future<Void> startPrimary(PgControlRecord held) {
        return serialized(() -> {
            Objects.requireNonNull(held, "held");
            if (!elector.holds(held)) {
                throw new PgLeaseProtocolException("Primary start requires fresh ownership of the writer lease");
            }
            String operation = held.intent().getString("operationId");
            return store.quarantine().compose(quarantine -> {
                if (quarantine.isPresent()) {
                    throw new PgLocalStateException("Node is quarantined: " + quarantine.orElseThrow().reason());
                }
                return store.closeGrant();
            }).compose(ignored -> gate.close(config.stopTimeout()))
                .compose(ignored -> applyWatchdogMode())
                .compose(ignored -> store.begin(held.generation(), operation, START_PRIMARY, config.nodeId(),
                    new JsonObject()))
                .compose(receipt -> {
                    lastHeld = held;
                    // Only the start effect leads to demotion. A refusal before it changes nothing.
                    return process.startPrimary().transform(started -> {
                        if (started.succeeded() && elector.hasFreshOwnership()) {
                            return store.record(held.generation(), operation, START_PRIMARY, config.nodeId(),
                                PgActionResult.COMPLETED, state(PgProcessState.RUNNING)).<Void>mapEmpty();
                        }
                        Throwable cause = started.failed() ? started.cause()
                            : new PgLeaseProtocolException("Ownership was lost while PostgreSQL started");
                        logger.error("Demoting local PostgreSQL on node {}: {}", config.nodeId(),
                            "Primary start did not complete under ownership", cause);
                        return demoteNow(held, "Primary start did not complete under ownership")
                            .transform(demotion -> {
                                if (demotion.failed()) cause.addSuppressed(demotion.cause());
                                return Future.<Void>failedFuture(cause);
                            });
                    });
                });
        });
    }

    /**
     * Installs the held record's pending durability policy on the local writer, which is running
     * with application writes excluded. The policy is derived from the record, not supplied. It is
     * refused without fresh ownership of {@code WITHDRAWN} intent that has a pending policy, and
     * under quarantine. The grant and the gate are closed first. Every required standby must then
     * cover a fresh flush boundary; a refusal before the receipt changes nothing. The installation
     * runs under the {@value #INSTALL_POLICY} receipt, whose parameters are the policy. A failed
     * installation records {@code UNKNOWN}, because the setting may be in effect, and admission
     * stays closed. A receipt that exists with equal parameters is reused, so a rerun is safe.
     * Ownership lost while the policy was installed fails the call after the effect is recorded.
     * The control record is not changed here.
     */
    public Future<Void> installPendingPolicy(PgControlRecord held) {
        return serialized(() -> {
            Objects.requireNonNull(held, "held");
            JsonObject intent = held.intent();
            JsonObject pending = intent.getJsonObject("pendingDurabilityPolicy");
            if (!elector.holds(held) || !"WITHDRAWN".equals(intent.getString("phase")) || pending == null) {
                throw new PgLeaseProtocolException("Policy installation requires fresh ownership of withdrawn "
                    + "intent that has a pending durability policy");
            }
            PgDurabilityPolicy policy = PgDurabilityPolicy.fromJson(pending);
            String operation = intent.getString("operationId");
            return requireNoQuarantine().compose(ignored -> store.closeGrant())
                .compose(ignored -> gate.close(config.stopTimeout()))
                .compose(ignored -> durability.confirmCovered(policy))
                .compose(ignored -> store.begin(held.generation(), operation, INSTALL_POLICY, config.nodeId(),
                    policy.toJson()))
                .compose(receipt -> installAndRecord(held.generation(), operation, policy));
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

    /**
     * Runs the installation and records its result in the receipt that was begun before it. A failed
     * installation records {@code UNKNOWN} and fails with the installation's failure. A successful one
     * records {@code COMPLETED}, then fails if ownership was lost while it ran.
     */
    private Future<Void> installAndRecord(long generation, String operation, PgDurabilityPolicy policy) {
        return durability.install(policy).transform(installed -> {
            if (installed.failed()) return recordUnknownAndFail(generation, operation, installed.cause());
            return store.record(generation, operation, INSTALL_POLICY, config.nodeId(), PgActionResult.COMPLETED,
                new JsonObject()).compose(recorded -> elector.hasFreshOwnership()
                    ? Future.<Void>succeededFuture()
                    : Future.<Void>failedFuture(new PgLeaseProtocolException(
                        "Ownership was lost while the policy was installed")));
        });
    }

    private Future<Void> recordUnknownAndFail(long generation, String operation, Throwable cause) {
        return store.record(generation, operation, INSTALL_POLICY, config.nodeId(), PgActionResult.UNKNOWN,
            new JsonObject()).transform(recorded -> {
                if (recorded.failed()) cause.addSuppressed(recorded.cause());
                return Future.<Void>failedFuture(cause);
            });
    }

    /**
     * Creates the prepared grant for the held record's confirmed policy. Admission stays closed.
     * The policy revision is derived from the record, not supplied.
     */
    public Future<PgWriterGrant> prepareWriter(PgControlRecord held, PgFailoverMode mode) {
        return serialized(() -> {
            Objects.requireNonNull(held, "held");
            JsonObject confirmed = held.intent().getJsonObject("durabilityPolicy");
            if (!elector.holds(held) || confirmed == null) {
                throw new PgLeaseProtocolException(
                    "Writer preparation requires fresh ownership and a confirmed durability policy");
            }
            return store.prepare(mode, held.generation(), held.intent().getString("operationId"),
                confirmed.getLong("revision")).map(prepared -> {
                    lastHeld = held;
                    return prepared;
                });
        });
    }

    /**
     * Opens exactly the prepared grant, then the gate. Requires fresh ownership of a record with
     * {@code SERVING} intent that the grant matches. If the gate does not open, or ownership is
     * lost during activation, admission is revoked and the call fails.
     */
    public Future<PgWriterGrant> activateWriter(PgControlRecord serving, PgWriterGrant prepared) {
        return serialized(() -> {
            Objects.requireNonNull(serving, "serving");
            Objects.requireNonNull(prepared, "prepared");
            JsonObject intent = serving.intent();
            JsonObject confirmed = intent.getJsonObject("durabilityPolicy");
            if (!elector.holds(serving) || !"SERVING".equals(intent.getString("phase")) || confirmed == null) {
                throw new PgLeaseProtocolException("Writer activation requires fresh ownership of serving intent");
            }
            if (prepared.generation() != serving.generation()
                    || !prepared.operationId().equals(intent.getString("operationId"))
                    || prepared.policyRevision() != confirmed.getLong("revision")
                    || !prepared.nodeId().equals(config.nodeId())) {
                throw new PgLocalStateException("The prepared grant does not match the serving control record");
            }
            return store.activate(prepared).compose(open -> {
                lastHeld = serving;
                return gate.open().transform(opened -> {
                    if (opened.succeeded() && elector.holds(serving)) return Future.succeededFuture(open);
                    Throwable cause = opened.failed() ? opened.cause()
                        : new PgLeaseProtocolException("Ownership was lost while admission opened");
                    logger.error("Revoking writer admission on node {}: {}", config.nodeId(),
                        "Writer activation did not complete under ownership", cause);
                    return revokeNow().transform(revoked -> {
                        if (revoked.failed()) cause.addSuppressed(revoked.cause());
                        return Future.<PgWriterGrant>failedFuture(cause);
                    });
                });
            });
        });
    }

    /**
     * Closes the grant and the gate, ends application connections, and observes that none
     * remain. PostgreSQL keeps running and ownership is unchanged. A failure means admission is
     * not confirmed closed; the caller must stop the writer.
     */
    public Future<Void> revokeWriter(String reason) {
        return serialized(() -> {
            logger.info("Revoking writer admission on node {}: {}", config.nodeId(), reason);
            return revokeNow();
        });
    }

    /**
     * One ownership cycle. An armed watchdog is fed only after a successful renewal. A failed
     * renewal or a failed keepalive withdraws admission and stops the local writer, then fails
     * with that failure. A demotion failure is attached as suppressed.
     */
    public Future<PgControlRecord> renew() {
        return elector.renew().transform(renewal -> {
            if (renewal.failed()) return demoteAfter(renewal.cause(), "Lease renewal failed");
            lastHeld = renewal.result();
            if (!armed) return Future.succeededFuture(renewal.result());
            return watchdog.keepalive().transform(fed -> fed.succeeded()
                ? Future.succeededFuture(renewal.result())
                : demoteAfter(fed.cause(), "Watchdog keepalive failed"));
        });
    }

    private Future<PgControlRecord> demoteAfter(Throwable cause, String reason) {
        PgControlRecord held = lastHeld;
        return serialized(() -> {
            logger.error("Demoting local PostgreSQL on node {}: {}", config.nodeId(), reason, cause);
            return demoteNow(held, reason);
        }).transform(demotion -> {
            if (demotion.failed()) cause.addSuppressed(demotion.cause());
            return Future.<PgControlRecord>failedFuture(cause);
        });
    }

    /**
     * Applies the watchdog mode before a writer start. {@code OFF} does nothing. {@code REQUIRED}
     * fails unless the watchdog is armed with a safe timeout. {@code AUTOMATIC} reports that
     * condition and continues without a watchdog.
     */
    private Future<Void> applyWatchdogMode() {
        if (config.watchdogMode() == PgWatchdogMode.OFF || armed) return Future.succeededFuture();
        return watchdog.activate().compose(timeout -> {
            armed = true;
            if (safeWatchdogTimeout(timeout)) return Future.<Void>succeededFuture();
            PgWatchdogException unsafe = new PgWatchdogException("Watchdog timeout " + timeout
                + " is not within the ownership timing; it must not exceed " + config.watchdogTimeout());
            // An unsafe watchdog is not used. If it cannot be disarmed it stays armed and unfed.
            return watchdog.disarm().transform(disarmed -> {
                if (disarmed.succeeded()) {
                    armed = false;
                } else {
                    unsafe.addSuppressed(disarmed.cause());
                }
                return Future.<Void>failedFuture(unsafe);
            });
        }).transform(activation -> {
            if (activation.succeeded()) return Future.<Void>succeededFuture();
            if (config.watchdogMode() == PgWatchdogMode.REQUIRED) return Future.<Void>failedFuture(activation.cause());
            logger.error("Watchdog unavailable on node {}; continuing without independent watchdog protection",
                config.nodeId(), activation.cause());
            return Future.succeededFuture();
        });
    }

    /** The watchdog fires no later than half the lease TTL, and after one loop-and-retry cycle and the stop budget. */
    private boolean safeWatchdogTimeout(Duration timeout) {
        return timeout.compareTo(config.watchdogTimeout()) <= 0
            && config.loopInterval().plus(config.requestTimeout()).compareTo(timeout) < 0
            && config.stopTimeout().compareTo(timeout) < 0;
    }

    /**
     * Planned stop with release (plan §9.30, OD-15): stops local PostgreSQL as a demotion does, and releases the lease
     * only after every local step succeeded, so a successor acquires without waiting for expiry. Returns the released
     * record. The caller stops its renewal loop first. Refused before any effect without fresh ownership of exactly
     * {@code held} ({@link PgLeaseProtocolException}). An unconfirmed stop quarantines the node; it and any other failed
     * step fail the call with no release, and the elector is retired, so the lease expires. A failed release leaves
     * PostgreSQL stopped; the release has retired the elector.
     */
    public Future<PgControlRecord> stopAndRelease(PgControlRecord held, String reason) {
        return serialized(() -> {
            Objects.requireNonNull(held, "held");
            Objects.requireNonNull(reason, "reason");
            if (!elector.holds(held)) {
                throw new PgLeaseProtocolException("A planned stop with release requires fresh ownership of exactly the "
                    + "held record");
            }
            logger.info("Stopping local PostgreSQL on node {} before releasing the lease: {}", config.nodeId(), reason);
            return stopLocally(held, reason).transform(stopped -> {
                if (stopped.succeeded()) return elector.release(held);
                // No release without a confirmed stop: the lease expires instead.
                elector.retire();
                return Future.<PgControlRecord>failedFuture(stopped.cause());
            });
        });
    }

    /**
     * Retires local ownership, closes admission, and stops PostgreSQL within the stop budget.
     * Succeeds only when the grant is closed, the process is observed stopped, and the gate is
     * closed for the next start. An unconfirmed stop quarantines the node and fails.
     *
     * @param held the record this node last held, used to identify the action receipt; may be null
     */
    public Future<Void> demote(PgControlRecord held, String reason) {
        return serialized(() -> {
            logger.info("Demoting local PostgreSQL on node {}: {}", config.nodeId(), reason);
            return demoteNow(held, reason);
        });
    }

    private Future<Void> revokeNow() {
        List<Throwable> failures = new ArrayList<>();
        return attempt(store.closeGrant(), failures)
            .compose(ignored -> attempt(gate.close(config.stopTimeout()), failures))
            .compose(ignored -> combined(failures));
    }

    private Future<Void> demoteNow(PgControlRecord held, String reason) {
        elector.retire();
        return stopLocally(held, reason);
    }

    /**
     * Closes the grant, stops PostgreSQL under the {@value #STOP_WRITER} receipt, and closes the gate. Ownership is not
     * changed here. Succeeds only when every step succeeded; an unconfirmed stop quarantines the node.
     */
    private Future<Void> stopLocally(PgControlRecord held, String reason) {
        List<Throwable> failures = new ArrayList<>();
        String operation = held == null ? null : held.intent().getString("operationId");
        // Every step runs even when an earlier one fails: a storage fault must not leave the writer running.
        return attempt(store.closeGrant(), failures)
            .compose(ignored -> held == null ? Future.<Void>succeededFuture()
                : attempt(store.begin(held.generation(), operation, STOP_WRITER, config.nodeId(),
                    new JsonObject().put("reason", reason)), failures))
            .compose(ignored -> process.stop(config.stopTimeout()).transform(stopped -> {
                if (stopped.succeeded()) {
                    // The writer is confirmed stopped, so an armed watchdog is no longer needed.
                    return (held == null ? Future.<Void>succeededFuture()
                        : attempt(store.record(held.generation(), operation, STOP_WRITER, config.nodeId(),
                            PgActionResult.COMPLETED, state(PgProcessState.STOPPED)), failures))
                        .compose(recorded -> disarmAfterConfirmedStop(failures));
                }
                // An unconfirmed stop keeps an armed watchdog armed and unfed, so it excludes the writer.
                logger.error("Local writer stop is unconfirmed on node {}", config.nodeId(), stopped.cause());
                failures.add(stopped.cause());
                return attempt(store.quarantine("Writer stop unconfirmed: " + reason), failures)
                    .compose(quarantined -> held == null ? Future.<Void>succeededFuture()
                        : attempt(store.record(held.generation(), operation, STOP_WRITER, config.nodeId(),
                            PgActionResult.UNKNOWN, new JsonObject()), failures));
            }))
            // With PostgreSQL stopped this leaves the gate closed for the next start. With an
            // unconfirmed stop it also ends application connections to the running server.
            .compose(ignored -> attempt(gate.close(config.stopTimeout()), failures))
            .compose(ignored -> combined(failures));
    }

    private Future<Void> disarmAfterConfirmedStop(List<Throwable> failures) {
        if (!armed) return Future.succeededFuture();
        return attempt(watchdog.disarm().onSuccess(disarmed -> armed = false), failures);
    }

    /** Runs a step to its end and collects its failure. The collected failures fail the caller. */
    private <T> Future<Void> attempt(Future<T> step, List<Throwable> failures) {
        return step.transform(outcome -> {
            if (outcome.failed()) {
                logger.error("Local supervision step failed on node {}", config.nodeId(), outcome.cause());
                failures.add(outcome.cause());
            }
            return Future.<Void>succeededFuture();
        });
    }

    private static Future<Void> combined(List<Throwable> failures) {
        if (failures.isEmpty()) return Future.succeededFuture();
        Throwable first = failures.get(0);
        failures.stream().skip(1).forEach(first::addSuppressed);
        return Future.failedFuture(first);
    }

    private static JsonObject state(PgProcessState observed) {
        return new JsonObject().put("processState", observed.name());
    }

    private synchronized <T> Future<T> serialized(Operation<T> operation) {
        Future<T> next = tail.transform(previous -> {
            Future<T> result;
            try {
                result = operation.execute();
            } catch (RuntimeException failure) {
                logger.error("Local supervision operation rejected on node {}", config.nodeId(), failure);
                return Future.failedFuture(failure);
            }
            // The failure is also returned to the caller. This is the one place that reports every one.
            // andThen, not onFailure: the caller sees the failure only after it is logged.
            return result.andThen(outcome -> {
                if (outcome.failed()) {
                    logger.error("Local supervision operation failed on node {}", config.nodeId(), outcome.cause());
                }
            });
        });
        tail = next;
        return next;
    }
}
