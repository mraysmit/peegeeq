package dev.mars.peegeeq.failover;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Node-owned ownership state above the coordinator port: intent validation, the freshness
 * deadline, retirement, and late-reply rejection. Control ownership alone does not authorise
 * PostgreSQL writes.
 *
 * <p>Every operation that fails, is refused, or completes too late is logged once at ERROR and
 * returned to the caller as a failed Future.
 */
public final class PgPrimaryElector {
    @FunctionalInterface
    private interface Operation<T> { Future<T> execute(); }
    private static final Logger logger = LoggerFactory.getLogger(PgPrimaryElector.class);
    private final PgNodeConfig config;
    private final PgLeaseCoordinator coordinator;
    private long epoch;
    private boolean attempted;
    private boolean retired;
    private boolean closed;
    private boolean busy;
    private PgControlRecord held;
    private long freshUntil;

    public PgPrimaryElector(PgNodeConfig config, PgLeaseCoordinator coordinator) {
        this.config = Objects.requireNonNull(config, "config");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    /** Conditional initial intent only. The caller must verify authenticated provisioning first. */
    public Future<PgControlRecord> createInitialIntent(JsonObject intent) {
        final long current;
        synchronized (this) {
            if (closed || retired || attempted || busy) return refused("Initial acquisition is unavailable");
            attempted = true;
            busy = true;
            current = epoch;
        }
        long started = System.nanoTime();
        return cycle(current, started, () -> {
            JsonObject value = validateIntent(intent);
            requireNewPoliciesExcludeWriter(value, null);
            if (!config.nodeId().equals(value.getString("writerNodeId"))
                    || !"WITHDRAWN".equals(value.getString("phase"))
                    || value.containsKey("previousWriterNodeId") || value.containsKey("durabilityPolicy")
                    || value.getJsonObject("pendingDurabilityPolicy").getLong("revision") != 1L) {
                throw protocol("Initial acquisition requires withdrawn first-policy intent");
            }
            return coordinator.acquireInitial(value).map(record -> acquired(record, value));
        });
    }

    /**
     * Takes over retained, unowned history at the revision the caller read. The intent is
     * withdrawn, names this node as writer and the released writer as previous writer, and
     * carries the confirmed and pending policy unchanged.
     */
    public Future<PgControlRecord> acquireAfterRelease(PgControlRecord released, JsonObject intent) {
        final long current;
        synchronized (this) {
            if (closed || retired || attempted || busy) return refused("Acquisition after release is unavailable");
            attempted = true;
            busy = true;
            current = epoch;
        }
        long started = System.nanoTime();
        return cycle(current, started, () -> {
            if (released == null || released.leaseHolder() != null
                    || !config.controlName().equals(released.controlName())) {
                throw protocol("Acquisition after release requires this cluster's unowned control record");
            }
            JsonObject history = validateIntent(released.intent());
            JsonObject value = validateIntent(intent);
            if (!config.nodeId().equals(value.getString("writerNodeId"))
                    || !"WITHDRAWN".equals(value.getString("phase"))
                    || !history.getString("writerNodeId").equals(value.getString("previousWriterNodeId"))
                    || !Objects.equals(history.getJsonObject("durabilityPolicy"), value.getJsonObject("durabilityPolicy"))
                    || !Objects.equals(history.getJsonObject("pendingDurabilityPolicy"),
                        value.getJsonObject("pendingDurabilityPolicy"))) {
                throw protocol("Acquisition after release requires withdrawn intent that preserves history");
            }
            return coordinator.acquireAfterRelease(released, value).map(record -> {
                acquired(record, value);
                if (record.generation() <= released.generation()) {
                    throw protocol("Acquisition after release did not advance the generation");
                }
                return record;
            });
        });
    }

    public Future<Optional<PgControlRecord>> read() {
        final long current;
        synchronized (this) {
            if (closed) return refused("Elector is closed");
            current = epoch;
        }
        return bounded(() -> coordinator.read().map(found -> {
                if (found == null) throw protocol("Coordinator returned no read result");
                found.ifPresent(this::observed);
                return found;
            })).transform(result -> {
                if (result.failed()) {
                    synchronized (this) { if (epoch == current) retire(); }
                    return Future.failedFuture(result.cause());
                }
                synchronized (this) {
                    if (epoch == current && held != null
                            && !result.result().filter(held::equals).isPresent()) retire();
                }
                return Future.succeededFuture(result.result());
            });
    }

    public Future<PgControlRecord> renew() {
        final long current;
        final PgControlRecord expected;
        synchronized (this) {
            if (!hasFreshOwnership() || busy) return refused("No renewable local ownership");
            current = epoch;
            expected = held;
            busy = true;
        }
        long started = System.nanoTime();
        return cycle(current, started, () -> coordinator.renew(expected).map(record -> {
            observed(record);
            if (!expected.equals(record)) {
                throw protocol("Control holder, generation, revision, or intent changed during renewal");
            }
            return record;
        }));
    }

    public Future<PgControlRecord> update(PgControlRecord expected, JsonObject intent) {
        final long current;
        final long deadline;
        synchronized (this) {
            if (!hasFreshOwnership() || busy || expected == null || !held.equals(expected)) {
                return refused("Update requires current local ownership and revision");
            }
            current = epoch;
            deadline = freshUntil;
            busy = true;
        }
        // A value mutation does not renew the lease. Preserve the renewal-derived deadline.
        return finish(current, deadline, () -> {
            JsonObject value = validateIntent(intent);
            requireNewPoliciesExcludeWriter(value, expected.intent());
            if (!config.nodeId().equals(value.getString("writerNodeId"))
                    || !expected.intent().getString("operationId").equals(value.getString("operationId"))) {
                throw protocol("Update cannot replace writer or operation identity");
            }
            return coordinator.update(expected, value).map(record -> {
                observed(record);
                if (!expected.leaseHolder().equals(record.leaseHolder())
                        || !config.nodeId().equals(record.intent().getString("writerNodeId"))
                        || !value.equals(record.intent())) {
                    throw protocol("Updated control record is not owned by this node with the requested intent");
                }
                if (record.generation() != expected.generation()) throw protocol("Update changed the generation");
                return record;
            });
        });
    }

    /**
     * Guarded voluntary release. The caller must first confirm local writer exclusion. This
     * instance is retired whatever the outcome; an uncertain outcome requires a fresh observation.
     */
    public Future<PgControlRecord> release(PgControlRecord expected) {
        final long current;
        synchronized (this) {
            if (closed || retired || busy || held == null || expected == null || !held.equals(expected)) {
                return refused("Release requires the held control record at its current revision");
            }
            current = epoch;
            busy = true;
        }
        return bounded(() -> coordinator.release(expected).map(record -> {
            observed(record);
            if (record.leaseHolder() != null || record.generation() != expected.generation()
                    || !expected.intent().equals(record.intent())) {
                throw protocol("Release did not retain the control record as unowned history");
            }
            return record;
        })).transform(result -> {
            synchronized (this) {
                boolean superseded = epoch != current;
                if (!superseded) retire();
                if (result.failed()) return Future.failedFuture(result.cause());
                if (superseded) return late("Release completed after retirement");
            }
            return Future.succeededFuture(result.result());
        });
    }

    /** Permanently retire this instance. Reconciliation uses a new instance and fresh observations. */
    public synchronized void retire() {
        epoch++;
        retired = true;
        busy = false;
        held = null;
        freshUntil = 0;
    }

    public synchronized boolean hasFreshOwnership() {
        return !closed && !retired && held != null && System.nanoTime() - freshUntil < 0;
    }

    /** Whether this instance freshly holds exactly this record: same holder, generation, revision, and intent. */
    public synchronized boolean holds(PgControlRecord record) {
        return hasFreshOwnership() && held.equals(record);
    }

    /** Preserve the lease and control history. Generic cleanup must not grant takeover. */
    public Future<Void> close() {
        synchronized (this) {
            if (closed) return Future.succeededFuture();
            retire();
            closed = true;
        }
        return coordinator.close();
    }

    private Future<PgControlRecord> cycle(long current, long started,
                                          Operation<PgControlRecord> operation) {
        // Measured from the start of the request: the lease cannot expire before start plus TTL.
        long deadline = started + config.ownershipBudget().toNanos();
        return finish(current, deadline, operation);
    }

    private Future<PgControlRecord> finish(long current, long deadline,
                                           Operation<PgControlRecord> operation) {
        // State changes occur after the total timeout, so a late underlying reply cannot grant ownership.
        return bounded(operation).map(record -> {
            synchronized (this) {
                if (closed || retired || epoch != current || System.nanoTime() - deadline >= 0) {
                    PgLeaseProtocolException late =
                        protocol("Lease operation completed after retirement or freshness deadline");
                    logger.error("Late coordinator reply rejected", late);
                    throw late;
                }
                held = record;
                freshUntil = deadline;
                busy = false;
                return record;
            }
        }).transform(result -> {
            if (result.failed()) {
                synchronized (this) { if (epoch == current) retire(); }
                return Future.failedFuture(result.cause());
            }
            return Future.succeededFuture(result.result());
        });
    }

    private <T> Future<T> bounded(Operation<T> operation) {
        Future<T> result;
        try {
            result = operation.execute();
        } catch (RuntimeException failure) {
            // Every caller retires ownership on this failure. The logged exception is the one returned.
            PgLeaseProtocolException rejection = failure instanceof PgLeaseProtocolException known
                ? known : new PgLeaseProtocolException("Coordinator request rejected", failure);
            logger.error("Coordinator request rejected", rejection);
            return Future.failedFuture(rejection);
        }
        if (result == null) return refused("Coordinator returned no result");
        return result.timeout(config.requestTimeout().toMillis(), TimeUnit.MILLISECONDS).transform(outcome -> {
            if (outcome.failed()) {
                PgLeaseProtocolException failure = outcome.cause() instanceof PgLeaseProtocolException known
                    ? known : new PgLeaseProtocolException("Coordinator operation failed", outcome.cause());
                logger.error("Coordinator operation failed", failure);
                return Future.failedFuture(failure);
            }
            return Future.succeededFuture(outcome.result());
        });
    }

    /** Validates a record returned by the coordinator before any state depends on it. */
    private PgControlRecord observed(PgControlRecord record) {
        if (record == null) throw protocol("Coordinator returned no control record");
        if (!config.controlName().equals(record.controlName())) throw protocol("Unexpected control record name");
        validateIntent(record.intent());
        return record;
    }

    private PgControlRecord acquired(PgControlRecord record, JsonObject value) {
        observed(record);
        if (record.leaseHolder() == null || !config.nodeId().equals(record.intent().getString("writerNodeId"))
                || !value.equals(record.intent())) {
            throw protocol("Control record is not owned by this node with the requested intent");
        }
        return record;
    }

    private JsonObject validateIntent(JsonObject source) {
        JsonObject intent = Objects.requireNonNull(source, "intent").copy();
        if (!Set.of("writerNodeId", "phase", "operationId", "previousWriterNodeId", "durabilityPolicy",
                "pendingDurabilityPolicy").containsAll(intent.fieldNames())
                || !config.memberNodeIds().contains(intent.getString("writerNodeId"))
                || !Set.of("WITHDRAWN", "FENCING", "PROMOTING", "SERVING").contains(intent.getString("phase"))) {
            throw protocol("Invalid control intent schema");
        }
        UUID.fromString(intent.getString("operationId"));
        String previous = intent.getString("previousWriterNodeId");
        if (intent.containsKey("previousWriterNodeId") && !config.memberNodeIds().contains(previous)) {
            throw protocol("Unknown previous writer");
        }
        JsonObject confirmed = intent.getJsonObject("durabilityPolicy");
        JsonObject pending = intent.getJsonObject("pendingDurabilityPolicy");
        if (confirmed == null && pending == null || "SERVING".equals(intent.getString("phase"))
                && (confirmed == null || pending != null)) throw protocol("Control phase has no eligible policy");
        for (JsonObject policy : new JsonObject[] {confirmed, pending}) {
            if (policy == null) continue;
            if (!Set.of("revision", "requiredStandbyNodeIds").equals(policy.fieldNames())
                    || positiveInteger(policy, "revision") < 1) throw protocol("Invalid durability policy revision");
            JsonArray peers = policy.getJsonArray("requiredStandbyNodeIds");
            if (peers == null || peers.isEmpty() || peers.stream().distinct().count() != peers.size()
                    || peers.stream().anyMatch(peer -> !(peer instanceof String)
                        || !config.memberNodeIds().contains(peer))) {
                throw protocol("Invalid required standby membership");
            }
        }
        if (confirmed != null && pending != null && pending.getLong("revision") <= confirmed.getLong("revision")) {
            throw protocol("Pending policy must advance the confirmed revision");
        }
        // The serving set never contains the writer. Retained history may: after a takeover the
        // former writer's confirmed policy names the new writer until the policy cutover replaces it.
        if ("SERVING".equals(intent.getString("phase")) && namesWriter(confirmed, intent)) {
            throw protocol("Serving policy cannot require the writer as its own standby");
        }
        return intent;
    }

    /** A policy this node introduces must not name the writer. A policy carried from history may. */
    private static void requireNewPoliciesExcludeWriter(JsonObject value, JsonObject inherited) {
        for (String field : new String[] {"durabilityPolicy", "pendingDurabilityPolicy"}) {
            JsonObject policy = value.getJsonObject(field);
            if (policy == null || !namesWriter(policy, value)) continue;
            boolean carried = inherited != null && (policy.equals(inherited.getJsonObject("durabilityPolicy"))
                || policy.equals(inherited.getJsonObject("pendingDurabilityPolicy")));
            if (!carried) throw protocol("A new policy cannot require the writer as its own standby");
        }
    }

    private static boolean namesWriter(JsonObject policy, JsonObject intent) {
        return policy != null && policy.getJsonArray("requiredStandbyNodeIds").contains(intent.getString("writerNodeId"));
    }

    private static long positiveInteger(JsonObject source, String field) {
        Object value = source.getValue(field);
        if (!(value instanceof Long || value instanceof Integer) || ((Number) value).longValue() < 1) {
            throw protocol("Invalid integer field: " + field);
        }
        return ((Number) value).longValue();
    }

    private static PgLeaseProtocolException protocol(String message) { return new PgLeaseProtocolException(message); }

    /** Fails an operation that this instance refuses to start. Every refusal is reported. */
    private static <T> Future<T> refused(String message) {
        PgLeaseProtocolException refusal = protocol(message);
        logger.error("Lease operation refused", refusal);
        return Future.failedFuture(refusal);
    }

    /** Fails an operation whose reply arrived after this instance was retired. */
    private static <T> Future<T> late(String message) {
        PgLeaseProtocolException late = protocol(message);
        logger.error("Late coordinator reply rejected", late);
        return Future.failedFuture(late);
    }
}
