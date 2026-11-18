package dev.mars.peegeeq.failover.consul;

import dev.mars.peegeeq.failover.PgClusterConfiguration;
import dev.mars.peegeeq.failover.PgClusterConfigurationException;
import dev.mars.peegeeq.failover.PgClusterSettings;
import dev.mars.peegeeq.failover.PgClusterSettingsRecord;
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
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Consul adapter for the cluster-settings port (plan §9.34, contract 4). The record is the KV key
 * {@code peegeeq/pg/<clusterId>/<incarnation>/config}, its value the settings as JSON, its revision the key's
 * {@code ModifyIndex}. A read is consistent; a create writes with {@code ?cas=0} and an update with
 * {@code ?cas=<revision>}; a write that Consul does not answer with {@code true} fails, and a written record is read back.
 * Settings whose lease TTL Consul cannot serve as a session TTL (whole seconds, 10 to 86 400) are refused before sending
 * and fail a read.
 */
public final class ConsulClusterConfiguration implements PgClusterConfiguration {
    @FunctionalInterface
    private interface Operation<T> { Future<T> execute(); }
    private static final Logger logger = LoggerFactory.getLogger(ConsulClusterConfiguration.class);
    private final WebClient client;
    private final String endpoint;
    private final String token;
    private final String key;
    private final long requestTimeoutMillis;
    private boolean closed;

    public ConsulClusterConfiguration(Vertx vertx, PgNodeConfig config, URI endpoint, String token) {
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
        this.key = "peegeeq/pg/" + config.clusterId() + "/" + config.incarnation() + "/config";
        this.requestTimeoutMillis = config.requestTimeout().toMillis();
        client = WebClient.create(Objects.requireNonNull(vertx, "vertx"));
    }

    @Override
    public Future<Optional<PgClusterSettingsRecord>> read() {
        return guarded(() -> request(HttpMethod.GET, "/v1/kv/" + key + "?consistent", null).map(response -> {
            requireAuthoritative(response);
            if (response.statusCode() == 404) return Optional.<PgClusterSettingsRecord>empty();
            JsonArray values = response.bodyAsJsonArray();
            if (values == null || values.size() != 1) throw failure("Invalid cluster settings read cardinality");
            return Optional.of(decode(values.getJsonObject(0)));
        }));
    }

    @Override
    public Future<PgClusterSettingsRecord> create(PgClusterSettings settings) {
        return guarded(() -> write(servable(settings), 0, "a record already exists"));
    }

    @Override
    public Future<PgClusterSettingsRecord> update(PgClusterSettingsRecord expected, PgClusterSettings settings) {
        return guarded(() -> {
            Objects.requireNonNull(expected, "expected");
            return write(servable(settings), expected.revision(), "the record changed since revision "
                + expected.revision() + " or does not exist").map(written -> {
                    if (written.revision() <= expected.revision()) {
                        throw failure("The cluster settings update did not advance the revision");
                    }
                    return written;
                });
        });
    }

    /** Closes the HTTP client. The record is left unchanged. */
    @Override
    public synchronized Future<Void> close() {
        if (!closed) {
            closed = true;
            client.close();
        }
        return Future.succeededFuture();
    }

    /** Writes with the compare-and-set index, then reads the record back; it must hold exactly these settings. */
    private Future<PgClusterSettingsRecord> write(PgClusterSettings settings, long index, String refusal) {
        return request(HttpMethod.PUT, "/v1/kv/" + key + "?cas=" + index, Buffer.buffer(settings.toJson().encode()))
            .compose(response -> {
                success(response);
                String body = response.bodyAsString();
                if (!"true".equals(body == null ? null : body.strip())) {
                    throw failure("Consul did not store the cluster settings: " + refusal);
                }
                return read();
            }).map(found -> {
                PgClusterSettingsRecord written = found.orElseThrow(() ->
                    failure("The cluster settings were not found after they were stored"));
                if (!settings.equals(written.settings())) {
                    throw failure("The cluster settings read back differ from the settings written");
                }
                return written;
            });
    }

    /** A refusal reports its status; a 200 or 404 must also come from a known leader and be unfiltered. */
    private static void requireAuthoritative(HttpResponse<Buffer> response) {
        if (response.statusCode() != 404) success(response);
        if (!"true".equals(response.getHeader("X-Consul-KnownLeader"))) {
            throw failure("Consul read of the cluster settings has no known leader");
        }
        if ("true".equals(response.getHeader("X-Consul-Results-Filtered-By-ACLs"))) {
            throw failure("Consul read of the cluster settings was filtered by ACLs");
        }
    }

    private PgClusterSettingsRecord decode(JsonObject kv) {
        if (kv == null || !key.equals(kv.getString("Key"))) throw failure("Unexpected cluster settings key");
        Object revision = kv.getValue("ModifyIndex");
        if (!(revision instanceof Long || revision instanceof Integer) || ((Number) revision).longValue() < 1) {
            throw failure("Invalid cluster settings revision");
        }
        String encoded = kv.getString("Value");
        if (encoded == null) throw failure("The cluster settings record has no value");
        JsonObject value = new JsonObject(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8));
        return new PgClusterSettingsRecord(((Number) revision).longValue(), servable(PgClusterSettings.fromJson(value)));
    }

    /** Consul serves a lease TTL only as a session TTL of whole seconds between 10 and 86 400. */
    private static PgClusterSettings servable(PgClusterSettings settings) {
        Duration ttl = Objects.requireNonNull(settings, "settings").leaseTtl();
        if (ttl.toSeconds() < 10 || ttl.toSeconds() > 86400 || ttl.getNano() != 0) {
            throw failure("Consul TTL must be whole seconds between 10 and 86400: " + ttl);
        }
        return settings;
    }

    /** Every failure leaves the port as {@link PgClusterConfigurationException}, including a synchronous throw. */
    private <T> Future<T> guarded(Operation<T> operation) {
        Future<T> result;
        try {
            result = operation.execute();
        } catch (RuntimeException rejected) {
            PgClusterConfigurationException rejection = wrap(rejected);
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
        if (response.statusCode() != 200) {
            throw failure("Consul rejected the cluster settings request: HTTP " + response.statusCode());
        }
    }

    private static PgClusterConfigurationException wrap(Throwable cause) {
        return cause instanceof PgClusterConfigurationException known
            ? known : new PgClusterConfigurationException("Consul cluster settings operation failed", cause);
    }

    private static PgClusterConfigurationException failure(String message) {
        return new PgClusterConfigurationException(message);
    }
}
