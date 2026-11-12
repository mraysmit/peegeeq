package dev.mars.peegeeq.failover.consul;

import dev.mars.peegeeq.failover.PgLeaseProtocolException;
import dev.mars.peegeeq.failover.PgLsn;
import dev.mars.peegeeq.failover.PgMemberPosition;
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

/**
 * Consul adapter for the member-position port (plan §9.27). Each member's position is one KV key under
 * {@code peegeeq/pg/<clusterId>/<incarnation>/members/}, written by that member's supervisor with a plain PUT. No
 * session holds it (OD-38). The value is {@code {"timeline": n, "lsn": "X/Y"}}. A read lists the prefix; it need not
 * be consistent, because a stale position only keeps more WAL.
 */
public final class ConsulMemberPositions implements PgMemberPositions {
    @FunctionalInterface
    private interface Operation<T> { Future<T> execute(); }
    private static final Logger logger = LoggerFactory.getLogger(ConsulMemberPositions.class);
    private final WebClient client;
    private final String endpoint;
    private final String token;
    private final String prefix;
    private final String nodeId;
    private final List<String> members;
    private final long requestTimeoutMillis;
    private boolean closed;

    public ConsulMemberPositions(Vertx vertx, PgNodeConfig config, URI endpoint, String token) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(endpoint, "endpoint");
        if (token == null || token.isBlank()) throw new IllegalArgumentException("Consul token is required");
        if (endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getQuery() != null
                || endpoint.getFragment() != null || !Set.of("", "/").contains(endpoint.getPath())
                || !("https".equals(endpoint.getScheme()) || "http".equals(endpoint.getScheme())
                    && Set.of("localhost", "127.0.0.1", "[::1]").contains(endpoint.getHost()))) {
            throw new IllegalArgumentException("Consul requires HTTPS, or loopback HTTP for local fixtures");
        }
        this.endpoint = endpoint.toString().replaceAll("/$", "");
        this.token = token;
        this.prefix = "peegeeq/pg/" + config.clusterId() + "/" + config.incarnation() + "/members/";
        this.nodeId = config.nodeId();
        this.members = config.memberNodeIds();
        this.requestTimeoutMillis = config.requestTimeout().toMillis();
        client = WebClient.create(Objects.requireNonNull(vertx, "vertx"));
    }

    @Override
    public Future<Void> publish(PgMemberPosition position) {
        return guarded(() -> {
            Objects.requireNonNull(position, "position");
            if (!nodeId.equals(position.nodeId())) {
                throw protocol("Node " + nodeId + " publishes only its own position, not that of " + position.nodeId());
            }
            JsonObject value = new JsonObject().put("timeline", position.timeline()).put("lsn", position.lsn().toString());
            return request(HttpMethod.PUT, "/v1/kv/" + prefix + nodeId, Buffer.buffer(value.encode())).map(response -> {
                success(response);
                if (!"true".equals(response.bodyAsString() == null ? null : response.bodyAsString().strip())) {
                    throw protocol("Consul did not store the member position");
                }
                return null;
            });
        });
    }

    @Override
    public Future<Map<String, PgMemberPosition>> read() {
        return guarded(() -> request(HttpMethod.GET, "/v1/kv/" + prefix + "?recurse", null).map(response -> {
            if ("true".equals(response.getHeader("X-Consul-Results-Filtered-By-ACLs"))) {
                throw protocol("The member positions were filtered by ACLs");
            }
            if (response.statusCode() == 404) return Map.<String, PgMemberPosition>of();
            success(response);
            JsonArray entries = response.bodyAsJsonArray();
            if (entries == null) throw protocol("The member positions have no body");
            Map<String, PgMemberPosition> positions = new HashMap<>();
            for (Object entry : entries) {
                PgMemberPosition position = decode(entry);
                if (positions.put(position.nodeId(), position) != null) throw protocol("Duplicate member position");
            }
            return Map.copyOf(positions);
        }));
    }

    /** Closes the HTTP client. Published positions are left unchanged. */
    @Override
    public synchronized Future<Void> close() {
        if (!closed) {
            closed = true;
            client.close();
        }
        return Future.succeededFuture();
    }

    private PgMemberPosition decode(Object entry) {
        if (!(entry instanceof JsonObject kv) || !(kv.getValue("Key") instanceof String key) || !key.startsWith(prefix)) {
            throw protocol("Unexpected member position key");
        }
        String member = key.substring(prefix.length());
        if (!members.contains(member)) throw protocol("A member position names a node outside the membership: " + member);
        if (!(kv.getValue("Value") instanceof String encoded)) throw protocol("Member position has no value: " + member);
        JsonObject value = new JsonObject(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8));
        if (!(value.getValue("timeline") instanceof Number timeline) || !(value.getValue("lsn") instanceof String lsn)) {
            throw protocol("Malformed member position: " + member);
        }
        return new PgMemberPosition(member, timeline.longValue(), PgLsn.parse(lsn));
    }

    /** Every failure leaves the port as {@link PgLeaseProtocolException}, including a synchronous throw. */
    private <T> Future<T> guarded(Operation<T> operation) {
        Future<T> result;
        try {
            result = operation.execute();
        } catch (RuntimeException failure) {
            PgLeaseProtocolException rejection = wrap(failure);
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
        if (response.statusCode() != 200) throw protocol("Consul rejected the request: HTTP " + response.statusCode());
    }

    private static PgLeaseProtocolException wrap(Throwable failure) {
        return failure instanceof PgLeaseProtocolException known
            ? known : new PgLeaseProtocolException("Consul member-position operation failed", failure);
    }

    private static PgLeaseProtocolException protocol(String message) { return new PgLeaseProtocolException(message); }
}
