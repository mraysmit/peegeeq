package dev.mars.peegeeq.failover;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
    private final PgNodeConfig config;
    private final PgPrimaryElector elector;
    private final PgLocalStateStore store;
    private final PgProcessControl process;
    private final PgAdmissionGate gate;
    private volatile PgControlRecord lastHeld;
    private Future<?> tail = Future.succeededFuture();

    public PgFailoverMonitor(PgNodeConfig config, PgPrimaryElector elector, PgLocalStateStore store,
                             PgProcessControl process, PgAdmissionGate gate) {
        this.config = Objects.requireNonNull(config, "config");
        this.elector = Objects.requireNonNull(elector, "elector");
        this.store = Objects.requireNonNull(store, "store");
        this.process = Objects.requireNonNull(process, "process");
        this.gate = Objects.requireNonNull(gate, "gate");
    }

    /** Starts PostgreSQL in recovery with admission closed. No lease is required. */
    public Future<Void> startStandby() {
        return serialized(() -> store.closeGrant()
            .compose(ignored -> gate.close(config.stopTimeout()))
            .compose(ignored -> process.startInRecovery()));
    }

    /**
     * Starts PostgreSQL writable under this node's fresh lease, with admission closed. Refused
     * without fresh ownership of exactly this record, under quarantine, or when the gate cannot
     * be closed first. If the start fails, or ownership is lost while it runs, the node is
     * demoted and the call fails.
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
     * One ownership cycle. A failed renewal withdraws admission and stops the local writer, then
     * fails with the renewal failure. A demotion failure is attached as suppressed.
     */
    public Future<PgControlRecord> renew() {
        return elector.renew().transform(renewal -> {
            if (renewal.succeeded()) {
                lastHeld = renewal.result();
                return Future.succeededFuture(renewal.result());
            }
            PgControlRecord held = lastHeld;
            return serialized(() -> {
                logger.error("Demoting local PostgreSQL on node {}: {}", config.nodeId(), "Lease renewal failed",
                    renewal.cause());
                return demoteNow(held, "Lease renewal failed");
            }).transform(demotion -> {
                if (demotion.failed()) renewal.cause().addSuppressed(demotion.cause());
                return Future.<PgControlRecord>failedFuture(renewal.cause());
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
        List<Throwable> failures = new ArrayList<>();
        String operation = held == null ? null : held.intent().getString("operationId");
        // Every step runs even when an earlier one fails: a storage fault must not leave the writer running.
        return attempt(store.closeGrant(), failures)
            .compose(ignored -> held == null ? Future.<Void>succeededFuture()
                : attempt(store.begin(held.generation(), operation, STOP_WRITER, config.nodeId(),
                    new JsonObject().put("reason", reason)), failures))
            .compose(ignored -> process.stop(config.stopTimeout()).transform(stopped -> {
                if (stopped.succeeded()) {
                    return held == null ? Future.<Void>succeededFuture()
                        : attempt(store.record(held.generation(), operation, STOP_WRITER, config.nodeId(),
                            PgActionResult.COMPLETED, state(PgProcessState.STOPPED)), failures);
                }
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
            return result.onFailure(failure ->
                logger.error("Local supervision operation failed on node {}", config.nodeId(), failure));
        });
        tail = next;
        return next;
    }
}
