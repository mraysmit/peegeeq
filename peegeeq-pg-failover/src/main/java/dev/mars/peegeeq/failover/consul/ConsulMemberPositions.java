package dev.mars.peegeeq.failover.consul;

import dev.mars.peegeeq.failover.PgLsn;
import dev.mars.peegeeq.failover.PgMemberPosition;
import dev.mars.peegeeq.failover.PgMemberPositionException;
import dev.mars.peegeeq.failover.PgMemberPositions;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Consul adapter for the member-position port (plan §9.28, P7b). Each member's position is one KV key under
 * {@code peegeeq/pg/<clusterId>/<incarnation>/members/}, held by that member's own session: TTL equal to the lease TTL,
 * {@code Behavior=delete}, {@code LockDelay=0s}, and no checks. A publication renews the session, or creates one when
 * the adapter has none or the session has ended, and writes the record with {@code ?acquire}. When the member stops
 * publishing, its session expires and Consul deletes its record. The session is separate from the writer lease, so the
 * lease port is unchanged (OD-36). The value is {@code {"timeline": n, "lsn": "X/Y"}}. A read lists the prefix; it need
 * not be consistent. Publications of one adapter run one at a time; the supervisor serializes them.
 */
public final class ConsulMemberPositions implements PgMemberPositions {
    @FunctionalInterface
    private interface Operation<T> { Future<T> execute(); }
    private static final Logger logger = LoggerFactory.getLogger(ConsulMemberPositions.class);
    private final WebClient client;
    private final String endpoint;
    private final String consulNodeName;
    private final String token;
    private final String prefix;
    private final String nodeId;
    private final List<String> members;
    private final String ttl;
    private final long requestTimeoutMillis;
    private volatile String session;
    private boolean closed;

    public ConsulMemberPositions(Vertx vertx, PgNodeConfig config, URI endpoint, String consulNodeName, String token) {
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
        this.prefix = "peegeeq/pg/" + config.clusterId() + "/" + config.incarnation() + "/members/";
        this.nodeId = config.nodeId();
        this.members = config.memberNodeIds();
        this.ttl = config.leaseTtl().toSeconds() + "s";
        this.requestTimeoutMillis = config.requestTimeout().toMillis();
        client = WebClient.create(Objects.requireNonNull(vertx, "vertx"));
    }

    @Override
    public Future<Void> publish(PgMemberPosition position) {
        return guarded(() -> {
            Objects.requireNonNull(position, "position");
            if (!nodeId.equals(position.nodeId())) {
                throw failure("Node " + nodeId + " publishes only its own position, not that of " + position.nodeId());
            }
            Buffer value = Buffer.buffer(new JsonObject().put("timeline", position.timeline())
                .put("lsn", position.lsn().toString()).encode());
            return liveSession().compose(id -> request(HttpMethod.PUT, "/v1/kv/" + prefix + nodeId + "?acquire=" + id,
                value)).map(response -> {
                    success(response);
                    if (!"true".equals(response.bodyAsString() == null ? null : response.bodyAsString().strip())) {
                        throw failure("Consul did not store the member position under the member's session");
                    }
                    return null;
                });
        });
    }

    @Override
    public Future<Map<String, PgMemberPosition>> read() {
        return guarded(() -> request(HttpMethod.GET, "/v1/kv/" + prefix + "?recurse", null).map(response -> {
            if ("true".equals(response.getHeader("X-Consul-Results-Filtered-By-ACLs"))) {
                throw failure("The member positions were filtered by ACLs");
            }
            if (response.statusCode() == 404) return Map.<String, PgMemberPosition>of();
            success(response);
            JsonArray entries = response.bodyAsJsonArray();
            if (entries == null) throw failure("The member positions have no body");
            Map<String, PgMemberPosition> positions = new HashMap<>();
            for (Object entry : entries) {
                PgMemberPosition position = decode(entry);
                if (positions.put(position.nodeId(), position) != null) throw failure("Duplicate member position");
            }
            return Map.copyOf(positions);
        }));
    }

    /** Closes the HTTP client. The member session is not destroyed; the record ends when the session expires. */
    @Override
    public synchronized Future<Void> close() {
        if (!closed) {
            closed = true;
            client.close();
        }
        return Future.succeededFuture();
    }

    /** The adapter's session, renewed; a new one when there is none or the renewal finds it gone. */
    private Future<String> liveSession() {
        String current = session;
        if (current == null) return createSession();
        return request(HttpMethod.PUT, "/v1/session/renew/" + current, null).compose(response -> {
            if (response.statusCode() == 404) return createSession();
            validateSession(response, current);
            return Future.succeededFuture(current);
        });
    }

    /** Creates the member session, then reads it back and rejects any differing setting. */
    private Future<String> createSession() {
        JsonObject settings = new JsonObject().put("Name", "peegeeq-member-" + nodeId).put("Node", consulNodeName)
            .put("TTL", ttl).put("LockDelay", "0s").put("Behavior", "delete")
            .put("NodeChecks", new JsonArray()).put("ServiceChecks", new JsonArray());
        return request(HttpMethod.PUT, "/v1/session/create", Buffer.buffer(settings.encode())).map(response -> {
            success(response);
            JsonObject body = response.bodyAsJsonObject();
            String id = body == null ? null : body.getString("ID");
            UUID.fromString(Objects.requireNonNull(id, "session ID"));
            return id;
        }).compose(id -> request(HttpMethod.GET, "/v1/session/info/" + id, null).map(response -> {
            validateSession(response, id);
            session = id;
            return id;
        }));
    }

    private void validateSession(HttpResponse<Buffer> response, String id) {
        success(response);
        JsonArray sessions = response.bodyAsJsonArray();
        if (sessions == null || sessions.size() != 1) throw failure("Member session is absent or ambiguous");
        JsonObject found = sessions.getJsonObject(0);
        if (!id.equals(found.getString("ID")) || !consulNodeName.equals(found.getString("Node"))
                || !ttl.equals(found.getString("TTL")) || !"delete".equals(found.getString("Behavior"))
                || !Long.valueOf(0).equals(found.getLong("LockDelay"))) {
            throw failure("Member session settings differ from the TTL-only delete contract");
        }
        for (String name : Set.of("Checks", "NodeChecks", "ServiceChecks")) {
            JsonArray checks = found.getJsonArray(name);
            if (checks != null && !checks.isEmpty()) throw failure("Member session has a non-TTL invalidation source");
        }
    }

    private PgMemberPosition decode(Object entry) {
        if (!(entry instanceof JsonObject kv) || !(kv.getValue("Key") instanceof String key) || !key.startsWith(prefix)) {
            throw failure("Unexpected member position key");
        }
        String member = key.substring(prefix.length());
        if (!members.contains(member)) throw failure("A member position names a node outside the membership: " + member);
        if (!(kv.getValue("Session") instanceof String holder) || holder.isBlank()) {
            throw failure("The member position of " + member + " is held by no session");
        }
        if (!(kv.getValue("Value") instanceof String encoded)) throw failure("Member position has no value: " + member);
        JsonObject value = new JsonObject(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8));
        if (!(value.getValue("timeline") instanceof Number timeline) || !(value.getValue("lsn") instanceof String lsn)) {
            throw failure("Malformed member position: " + member);
        }
        return new PgMemberPosition(member, timeline.longValue(), PgLsn.parse(lsn));
    }

    /** Every failure leaves the port as {@link PgMemberPositionException}, including a synchronous throw. */
    private <T> Future<T> guarded(Operation<T> operation) {
        Future<T> result;
        try {
            result = operation.execute();
        } catch (RuntimeException failure) {
            PgMemberPositionException rejection = wrap(failure);
            logger.error("Consul request rejected before sending", rejection);
            return Future.failedFuture(rejection);
        }
        return result.transform(outcome -> outcome.failed()
            ? Future.failedFuture(wrap(outcome.cause())) : Future.succeededFuture(outcome.result()));
    }

    private Future<HttpResponse<Buffer>> request(HttpMethod method, String path, Buffer body) {
        var request = client.requestAbs(method, endpoint + path).putHeader("X-Consul-Token", token)
            .timeout(requestTimeoutMillis);
        return body == null ? request.send() : request.sendBuffer(body);
    }

    private static void success(HttpResponse<Buffer> response) {
        if (response.statusCode() != 200) throw failure("Consul rejected the request: HTTP " + response.statusCode());
    }

    private static PgMemberPositionException wrap(Throwable failure) {
        return failure instanceof PgMemberPositionException known
            ? known : new PgMemberPositionException("Consul member-position operation failed", failure);
    }

    private static PgMemberPositionException failure(String message) { return new PgMemberPositionException(message); }
}
