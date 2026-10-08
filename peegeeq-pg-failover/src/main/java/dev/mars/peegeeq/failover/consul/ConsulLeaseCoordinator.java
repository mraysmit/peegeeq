package dev.mars.peegeeq.failover.consul;

import dev.mars.peegeeq.failover.PgControlRecord;
import dev.mars.peegeeq.failover.PgLeaseCoordinator;
import dev.mars.peegeeq.failover.PgLeaseProtocolException;
import dev.mars.peegeeq.failover.PgNodeConfig;
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

/**
 * Consul adapter for the coordinator port. The lease is a TTL-only session holding the lock on
 * one KV key. Lease holder, generation, and revision are the session ID, {@code LockIndex}, and
 * {@code ModifyIndex}.
 */
public final class ConsulLeaseCoordinator implements PgLeaseCoordinator {
    @FunctionalInterface
    private interface Operation<T> { Future<T> execute(); }
    private static final Logger logger = LoggerFactory.getLogger(ConsulLeaseCoordinator.class);
    private final WebClient client;
    private final String endpoint;
    private final String consulNodeName;
    private final String token;
    private final String key;
    private final String nodeId;
    private final String ttl;
    private final long requestTimeoutMillis;
    private boolean closed;

    public ConsulLeaseCoordinator(Vertx vertx, PgNodeConfig config, URI endpoint,
                                  String consulNodeName, String token) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(endpoint, "endpoint");
        if (consulNodeName == null || !consulNodeName.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")) {
            throw new IllegalArgumentException("Invalid Consul node name");
        }
        if (token == null || token.isBlank()) throw new IllegalArgumentException("Consul token is required");
        if (endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getQuery() != null
                || endpoint.getFragment() != null || !Set.of("", "/").contains(endpoint.getPath())
                || !("https".equals(endpoint.getScheme()) || "http".equals(endpoint.getScheme())
                    && Set.of("localhost", "127.0.0.1", "[::1]").contains(endpoint.getHost()))) {
            throw new IllegalArgumentException("Consul requires HTTPS, or loopback HTTP for local fixtures");
        }
        if (config.leaseTtl().toSeconds() < 10 || config.leaseTtl().toSeconds() > 86400
                || config.leaseTtl().getNano() != 0) {
            throw new IllegalArgumentException("Consul TTL must be whole seconds between 10 and 86400");
        }
        this.endpoint = endpoint.toString().replaceAll("/$", "");
        this.consulNodeName = consulNodeName;
        this.token = token;
        this.key = config.controlName();
        this.nodeId = config.nodeId();
        this.ttl = config.leaseTtl().toSeconds() + "s";
        this.requestTimeoutMillis = config.requestTimeout().toMillis();
        client = WebClient.create(Objects.requireNonNull(vertx, "vertx"));
    }

    @Override
    public Future<PgControlRecord> acquireInitial(JsonObject intent) {
        return guarded(() -> createSession().compose(id -> transact(new JsonArray()
            .add(op("check-not-exists", null, null, null))
            .add(op("lock", id, null, encode(intent)))
            .add(op("get", null, null, null))).map(record -> requireHolder(record, id))));
    }

    @Override
    public Future<PgControlRecord> acquireAfterRelease(PgControlRecord released, JsonObject intent) {
        return guarded(() -> {
            requireKey(released);
            return createSession().compose(id -> transact(new JsonArray()
                // The lock verb fails while another session holds the key.
                .add(op("check-index", null, released.revision(), null))
                .add(op("lock", id, null, encode(intent)))
                .add(op("get", null, null, null))).map(record -> requireHolder(record, id)));
        });
    }

    @Override
    public Future<Optional<PgControlRecord>> read() {
        return guarded(() -> request(HttpMethod.GET, "/v1/kv/" + key + "?consistent", null).map(response -> {
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
        }));
    }

    @Override
    public Future<PgControlRecord> renew(PgControlRecord held) {
        return guarded(() -> {
            String id = requireSession(held);
            return request(HttpMethod.PUT, "/v1/session/renew/" + id, null).compose(response -> {
                validateSession(response, id);
                return read();
            }).map(observed -> requireHolder(
                observed.orElseThrow(() -> protocol("Control record disappeared")), id));
        });
    }

    @Override
    public Future<PgControlRecord> update(PgControlRecord held, JsonObject intent) {
        return guarded(() -> {
            String id = requireSession(held);
            return transact(new JsonArray()
                .add(op("check-session", id, null, null))
                .add(op("check-index", null, held.revision(), null))
                // Same-session lock preserves LockIndex; ordinary CAS resets it in Consul 1.22.1.
                .add(op("lock", id, null, encode(intent)))
                .add(op("get", null, null, null))).map(record -> requireHolder(record, id));
        });
    }

    @Override
    public Future<PgControlRecord> release(PgControlRecord held) {
        return guarded(() -> {
            String id = requireSession(held);
            return transact(new JsonArray()
                .add(op("check-session", id, null, null))
                .add(op("check-index", null, held.revision(), null))
                // Unlock writes the entry it is given. Resend the value so that history is retained.
                .add(op("unlock", id, null, encode(held.intent())))
                .add(op("get", null, null, null))).map(record -> {
                    if (record.leaseHolder() != null) throw protocol("Consul did not release the lock");
                    return record;
                });
        });
    }

    /** Closes the HTTP client. The session and the key are left unchanged. */
    @Override
    public synchronized Future<Void> close() {
        if (!closed) {
            closed = true;
            client.close();
        }
        return Future.succeededFuture();
    }

    /** Creates the TTL-only session, then reads it back and rejects any differing setting. */
    private Future<String> createSession() {
        JsonObject session = new JsonObject().put("Name", nodeId)
            .put("Node", consulNodeName).put("TTL", ttl)
            .put("LockDelay", "0s").put("Behavior", "release")
            .put("NodeChecks", new JsonArray()).put("ServiceChecks", new JsonArray());
        return request(HttpMethod.PUT, "/v1/session/create", session).map(response -> {
            success(response);
            String id = response.bodyAsJsonObject().getString("ID");
            UUID.fromString(id);
            return id;
        }).compose(id -> request(HttpMethod.GET, "/v1/session/info/" + id + "?consistent", null)
            .map(response -> { validateSession(response, id); return id; }));
    }

    /** Every failure leaves the port as {@link PgLeaseProtocolException}, including a synchronous throw. */
    private <T> Future<T> guarded(Operation<T> operation) {
        Future<T> result;
        try {
            result = operation.execute();
        } catch (RuntimeException failure) {
            logger.warn("Consul request rejected before sending", failure);
            return Future.failedFuture(wrap(failure));
        }
        return result.transform(outcome -> outcome.failed()
            ? Future.failedFuture(wrap(outcome.cause())) : Future.succeededFuture(outcome.result()));
    }

    private Future<HttpResponse<Buffer>> request(HttpMethod method, String path, Object body) {
        var request = client.requestAbs(method, endpoint + path).putHeader("X-Consul-Token", token)
            .timeout(requestTimeoutMillis);
        return body == null ? request.send() : request.sendJson(body);
    }

    private Future<PgControlRecord> transact(JsonArray operations) {
        return request(HttpMethod.PUT, "/v1/txn", operations).map(response -> {
            success(response);
            JsonObject body = response.bodyAsJsonObject();
            if (body == null) throw protocol("Consul transaction returned no body");
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
        JsonObject kv = new JsonObject().put("Verb", verb).put("Key", key);
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
                || !ttl.equals(session.getString("TTL"))
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
        if (kv == null || !key.equals(kv.getString("Key"))) throw protocol("Unexpected control key");
        String session = kv.getString("Session");
        if (session != null && session.isBlank()) session = null;
        if (session != null) UUID.fromString(session);
        String encoded = kv.getString("Value");
        if (encoded == null) throw protocol("Control key has no value");
        JsonObject intent = new JsonObject(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8));
        return new PgControlRecord(key, positiveInteger(kv, "LockIndex"), positiveInteger(kv, "ModifyIndex"),
            session, intent);
    }

    private void requireKey(PgControlRecord record) {
        if (record == null || !key.equals(record.controlName())) throw protocol("Unexpected control key");
    }

    private String requireSession(PgControlRecord held) {
        requireKey(held);
        if (held.leaseHolder() == null) throw protocol("Control record has no session");
        return held.leaseHolder();
    }

    private static PgControlRecord requireHolder(PgControlRecord record, String session) {
        if (!session.equals(record.leaseHolder())) throw protocol("Control key is not locked by this session");
        return record;
    }

    private static String encode(JsonObject value) {
        return Base64.getEncoder().encodeToString(
            Objects.requireNonNull(value, "intent").encode().getBytes(StandardCharsets.UTF_8));
    }

    private static long positiveInteger(JsonObject source, String field) {
        Object value = source.getValue(field);
        if (!(value instanceof Long || value instanceof Integer) || ((Number) value).longValue() < 1) {
            throw protocol("Invalid integer field: " + field);
        }
        return ((Number) value).longValue();
    }

    private static PgLeaseProtocolException wrap(Throwable failure) {
        return failure instanceof PgLeaseProtocolException known
            ? known : new PgLeaseProtocolException("Consul operation failed", failure);
    }

    private static PgLeaseProtocolException protocol(String message) { return new PgLeaseProtocolException(message); }
}
