package dev.mars.peegeeq.failover;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Node-owned Consul protocol. Control ownership alone does not authorise PostgreSQL writes. */
public final class PgPrimaryElector {
    @FunctionalInterface
    private interface Operation<T> { Future<T> execute(); }
    private static final Logger logger = LoggerFactory.getLogger(PgPrimaryElector.class);
    private final PgNodeConfig config;
    private final WebClient client;
    private final String endpoint;
    private final String consulNodeName;
    private final String token;
    private long epoch;
    private boolean attempted;
    private boolean retired;
    private boolean closed;
    private boolean busy;
    private PgControlRecord held;
    private long freshUntil;

    public PgPrimaryElector(Vertx vertx, PgNodeConfig config, URI endpoint,
                            String consulNodeName, String token) {
        this.config = Objects.requireNonNull(config, "config");
        Objects.requireNonNull(endpoint, "endpoint");
        PgNodeConfig.requireIdentity(consulNodeName);
        if (token == null || token.isBlank()) throw new IllegalArgumentException("Consul token is required");
        if (endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getQuery() != null
                || endpoint.getFragment() != null || !Set.of("", "/").contains(endpoint.getPath())
                || !("https".equals(endpoint.getScheme()) || "http".equals(endpoint.getScheme())
                    && Set.of("localhost", "127.0.0.1", "[::1]").contains(endpoint.getHost()))) {
            throw new IllegalArgumentException("Consul requires HTTPS, or loopback HTTP for local fixtures");
        }
        this.endpoint = endpoint.toString().replaceAll("/$", "");
        this.consulNodeName = consulNodeName;
        this.token = token;
        client = WebClient.create(Objects.requireNonNull(vertx, "vertx"));
    }

    /** Conditional initial intent only. The caller must verify authenticated provisioning first. */
    public Future<PgControlRecord> createInitialIntent(JsonObject intent) {
        final long current;
        synchronized (this) {
            if (closed || retired || attempted || busy) return failed("Initial acquisition is unavailable");
            attempted = true;
            busy = true;
            current = epoch;
        }
        long started = System.nanoTime();
        return cycle(current, started, () -> {
            JsonObject value = validateIntent(intent);
            if (!config.nodeId().equals(value.getString("writerNodeId"))
                    || !"WITHDRAWN".equals(value.getString("phase"))
                    || value.containsKey("previousWriterNodeId") || value.containsKey("durabilityPolicy")
                    || value.getJsonObject("pendingDurabilityPolicy").getLong("revision") != 1L) {
                throw protocol("Initial acquisition requires withdrawn first-policy intent");
            }
            JsonObject session = new JsonObject().put("Name", config.nodeId())
                .put("Node", consulNodeName).put("TTL", config.sessionTtl().toSeconds() + "s")
                .put("LockDelay", "0s").put("Behavior", "release")
                .put("NodeChecks", new JsonArray()).put("ServiceChecks", new JsonArray());
            return request(HttpMethod.PUT, "/v1/session/create", session).map(response -> {
                success(response);
                String id = response.bodyAsJsonObject().getString("ID");
                UUID.fromString(id);
                return id;
            }).compose(id -> request(HttpMethod.GET, "/v1/session/info/" + id + "?consistent", null)
                .map(response -> { validateSession(response, id); return id; }))
                .compose(id -> transact(new JsonArray()
                    .add(op("check-not-exists", null, null, null))
                    .add(op("lock", id, null, encode(value)))
                    .add(op("get", null, null, null))).map(record -> {
                        requireOwned(record, id);
                        return record;
                    }));
        });
    }

    public Future<Optional<PgControlRecord>> read() {
        final long current;
        synchronized (this) {
            if (closed) return failed("Elector is closed");
            current = epoch;
        }
        return bounded(() -> request(HttpMethod.GET, "/v1/kv/" + config.controlKey() + "?consistent", null)
            .map(response -> {
                if (!"true".equals(response.getHeader("X-Consul-KnownLeader"))) {
                    throw protocol("Consul read has no known leader");
                }
                if ("true".equals(response.getHeader("X-Consul-Results-Filtered-By-ACLs"))) {
                    throw protocol("Consul read was filtered by ACLs");
                }
                if (response.statusCode() == 404) return Optional.<PgControlRecord>empty();
                success(response);
                JsonArray values = response.bodyAsJsonArray();
                if (values == null || values.size() != 1) throw protocol("Invalid control read cardinality");
                return Optional.of(decode(values.getJsonObject(0)));
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
            if (!hasFreshOwnership() || busy) return failed("No renewable local ownership");
            current = epoch;
            expected = held;
            busy = true;
        }
        long started = System.nanoTime();
        return cycle(current, started, () ->
            request(HttpMethod.PUT, "/v1/session/renew/" + expected.sessionId(), null)
                .compose(response -> {
                    validateSession(response, expected.sessionId());
                    return read();
                }).map(observed -> {
                    PgControlRecord record = observed.orElseThrow(() -> protocol("Control record disappeared"));
                    requireOwned(record, expected.sessionId());
                    if (record.lockIndex() != expected.lockIndex() || record.modifyIndex() != expected.modifyIndex()
                            || !record.intent().equals(expected.intent())) {
                        throw protocol("Control generation or intent changed during renewal");
                    }
                    return record;
                }));
    }

    public Future<PgControlRecord> update(PgControlRecord expected, JsonObject intent) {
        final long current;
        final long deadline;
        synchronized (this) {
            if (!hasFreshOwnership() || busy || expected == null || !held.equals(expected)) {
                return failed("Update requires current local ownership and revision");
            }
            current = epoch;
            deadline = freshUntil;
            busy = true;
        }
        // A KV mutation does not renew the TTL. Preserve the renewal-derived deadline.
        return finish(current, deadline, () -> {
            JsonObject value = validateIntent(intent);
            if (!config.nodeId().equals(value.getString("writerNodeId"))
                    || !expected.intent().getString("operationId").equals(value.getString("operationId"))) {
                throw protocol("Update cannot replace writer or operation identity");
            }
            return transact(new JsonArray()
                .add(op("check-session", expected.sessionId(), null, null))
                .add(op("check-index", null, expected.modifyIndex(), null))
                // Same-session lock preserves LockIndex; ordinary CAS resets it in Consul 1.22.1.
                .add(op("lock", expected.sessionId(), null, encode(value)))
                .add(op("get", null, null, null))).map(record -> {
                    requireOwned(record, expected.sessionId());
                    if (record.lockIndex() != expected.lockIndex()) throw protocol("Update changed lock generation");
                    return record;
                });
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

    /** Preserve the session and control history. Generic cleanup must not grant takeover. */
    public synchronized Future<Void> close() {
        if (!closed) {
            retire();
            closed = true;
            client.close();
        }
        return Future.succeededFuture();
    }

    private Future<PgControlRecord> cycle(long current, long started,
                                          Operation<PgControlRecord> operation) {
        long deadline = started + config.sessionTtl().minus(config.watchdogTimeout()).toNanos();
        return finish(current, deadline, operation);
    }

    private Future<PgControlRecord> finish(long current, long deadline,
                                           Operation<PgControlRecord> operation) {
        // State changes occur after the total timeout, so a late underlying reply cannot grant ownership.
        return bounded(operation).map(record -> {
            synchronized (this) {
                if (closed || retired || epoch != current || System.nanoTime() - deadline >= 0) {
                    throw protocol("Lease operation completed after retirement or freshness deadline");
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
            logger.warn("Consul protocol request rejected", failure);
            return Future.failedFuture(new PgLeaseProtocolException("Consul protocol request rejected", failure));
        }
        return result.timeout(config.requestTimeout().toMillis(), TimeUnit.MILLISECONDS).transform(outcome -> {
            if (outcome.failed()) {
                logger.warn("Consul protocol operation failed", outcome.cause());
                return Future.failedFuture(outcome.cause() instanceof PgLeaseProtocolException
                    ? outcome.cause() : new PgLeaseProtocolException("Consul protocol operation failed", outcome.cause()));
            }
            return Future.succeededFuture(outcome.result());
        });
    }

    private Future<HttpResponse<Buffer>> request(HttpMethod method, String path, Object body) {
        var request = client.requestAbs(method, endpoint + path).putHeader("X-Consul-Token", token)
            .timeout(config.requestTimeout().toMillis());
        return body == null ? request.send() : request.sendJson(body);
    }

    private Future<PgControlRecord> transact(JsonArray operations) {
        return request(HttpMethod.PUT, "/v1/txn", operations).map(response -> {
            success(response);
            JsonObject body = response.bodyAsJsonObject();
            JsonArray errors = body.getJsonArray("Errors");
            JsonArray results = body.getJsonArray("Results");
            // Consul 1.22.1 omits a result for successful check-not-exists.
            long expectedResults = operations.stream().filter(operation ->
                !"check-not-exists".equals(((JsonObject) operation).getJsonObject("KV").getString("Verb"))).count();
            if (errors != null && !errors.isEmpty() || results == null || results.size() != expectedResults) {
                throw protocol("Consul transaction did not return all operations");
            }
            return decode(results.getJsonObject(results.size() - 1).getJsonObject("KV"));
        });
    }

    private JsonObject op(String verb, String session, Long index, String value) {
        JsonObject kv = new JsonObject().put("Verb", verb).put("Key", config.controlKey());
        if (session != null) kv.put("Session", session);
        if (index != null) kv.put("Index", index);
        if (value != null) kv.put("Value", value);
        return new JsonObject().put("KV", kv);
    }

    private void success(HttpResponse<Buffer> response) {
        if (response.statusCode() != 200 || "true".equals(response.getHeader("X-Consul-Results-Filtered-By-ACLs"))) {
            throw protocol("Consul rejected or filtered request: HTTP " + response.statusCode());
        }
    }

    private void validateSession(HttpResponse<Buffer> response, String id) {
        success(response);
        JsonArray sessions = response.bodyAsJsonArray();
        if (sessions == null || sessions.size() != 1) throw protocol("Session is absent or ambiguous");
        JsonObject session = sessions.getJsonObject(0);
        if (!id.equals(session.getString("ID")) || !consulNodeName.equals(session.getString("Node"))
                || !(config.sessionTtl().toSeconds() + "s").equals(session.getString("TTL"))
                || !"release".equals(session.getString("Behavior"))
                || !Long.valueOf(0).equals(session.getLong("LockDelay"))) {
            throw protocol("Session settings differ from qualified TTL-only contract");
        }
        for (String name : Set.of("Checks", "NodeChecks", "ServiceChecks")) {
            JsonArray checks = session.getJsonArray(name);
            if (checks != null && !checks.isEmpty()) throw protocol("Session has a non-TTL invalidation source");
        }
    }

    private PgControlRecord decode(JsonObject kv) {
        if (kv == null || !config.controlKey().equals(kv.getString("Key"))) throw protocol("Unexpected control key");
        String session = kv.getString("Session");
        if (session != null && session.isBlank()) session = null;
        if (session != null) UUID.fromString(session);
        String encoded = kv.getString("Value");
        JsonObject intent = new JsonObject(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8));
        return new PgControlRecord(config.controlKey(), positiveInteger(kv, "LockIndex"), positiveInteger(kv, "ModifyIndex"),
            session, validateIntent(intent));
    }

    private void requireOwned(PgControlRecord record, String session) {
        if (!session.equals(record.sessionId()) || !config.nodeId().equals(record.intent().getString("writerNodeId"))) {
            throw protocol("Control record is not owned by this node and session");
        }
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
                        || !config.memberNodeIds().contains(peer) || intent.getString("writerNodeId").equals(peer))) {
                throw protocol("Invalid required standby membership");
            }
        }
        if (confirmed != null && pending != null && pending.getLong("revision") <= confirmed.getLong("revision")) {
            throw protocol("Pending policy must advance the confirmed revision");
        }
        return intent;
    }

    private static String encode(JsonObject value) {
        return Base64.getEncoder().encodeToString(value.encode().getBytes(StandardCharsets.UTF_8));
    }

    private static long positiveInteger(JsonObject source, String field) {
        Object value = source.getValue(field);
        if (!(value instanceof Long || value instanceof Integer) || ((Number) value).longValue() < 1) {
            throw protocol("Invalid integer field: " + field);
        }
        return ((Number) value).longValue();
    }

    private static PgLeaseProtocolException protocol(String message) { return new PgLeaseProtocolException(message); }
    private static <T> Future<T> failed(String message) { return Future.failedFuture(protocol(message)); }
}
