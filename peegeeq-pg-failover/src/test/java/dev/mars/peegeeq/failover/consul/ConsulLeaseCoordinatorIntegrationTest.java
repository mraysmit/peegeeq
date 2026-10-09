package dev.mars.peegeeq.failover.consul;

import dev.mars.peegeeq.failover.PgControlRecord;
import dev.mars.peegeeq.failover.PgLeaseCoordinator;
import dev.mars.peegeeq.failover.PgLeaseCoordinatorContract;
import dev.mars.peegeeq.failover.PgLeaseProtocolException;
import dev.mars.peegeeq.failover.PgNodeConfig;
import dev.mars.peegeeq.failover.PgWatchdogMode;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.logging.ExpectedErrorLog;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Consul binding of the coordinator contract suite: a real three-server quorum with ACLs,
 * node-scoped tokens, and a fault proxy. Cases declared here assert Consul wire details.
 */
@Tag(TestCategories.INTEGRATION)
@Isolated
class ConsulLeaseCoordinatorIntegrationTest extends PgLeaseCoordinatorContract {
    private static final Logger logger = LoggerFactory.getLogger(ConsulLeaseCoordinatorIntegrationTest.class);
    private static final String IMAGE = "hashicorp/consul:1.22.1";
    private static final String ADMIN_TOKEN = "00000000-0000-0000-0000-000000000001";
    private static final List<GenericContainer<?>> SERVERS = new ArrayList<>();
    private static Network network;
    private WebClient admin;
    private final List<Integer> paused = new ArrayList<>();
    private HttpServer faultProxy;
    private volatile Fault fault = Fault.NONE;
    private volatile String malformedBody = "null";
    private volatile boolean unsafeSession;
    private volatile Promise<Void> intercepted = Promise.promise();
    private volatile Runnable delayedReply;

    @BeforeAll static void startQuorum() {
        network = Network.newNetwork();
        for (int i = 1; i <= 3; i++) {
            var server = new GenericContainer<>(IMAGE).withNetwork(network)
                .withNetworkAliases("consul-" + i).withExposedPorts(8500)
                .withEnv("CONSUL_BIND_INTERFACE", "eth0")
                .withEnv("CONSUL_LOCAL_CONFIG", new JsonObject().put("log_level", "warn")
                    .put("acl", new JsonObject().put("enabled", true).put("default_policy", "deny")
                        .put("tokens", new JsonObject().put("initial_management", ADMIN_TOKEN))).encode())
                .withCommand("agent", "-server", "-bootstrap-expect=3", "-node=consul-" + i,
                    "-client=0.0.0.0", "-retry-join=consul-1", "-retry-join=consul-2", "-retry-join=consul-3")
                .waitingFor(Wait.forHttp("/v1/status/leader").forPort(8500)
                    .withStartupTimeout(Duration.ofSeconds(60)));
            SERVERS.add(server);
            server.start();
        }
    }

    @AfterAll static void stopQuorum() {
        for (var server : SERVERS.reversed()) server.stop();
        SERVERS.clear();
        if (network != null) network.close();
    }

    // ---------------------------------------------------------------- contract binding

    @Override protected Future<Void> prepareBinding() {
        admin = WebClient.create(vertx);
        fault = Fault.NONE;
        malformedBody = "null";
        unsafeSession = false;
        intercepted = Promise.promise();
        delayedReply = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        return waitForLeader(deadline).compose(ignored -> waitForRegisteredServers(deadline));
    }

    @Override protected Future<Void> releaseBinding() {
        return restoreQuorum().transform(restored -> {
            admin.close();
            Future<Void> proxyClosed = faultProxy == null ? Future.succeededFuture() : faultProxy.close();
            return proxyClosed.compose(ignored -> restored.succeeded()
                ? Future.<Void>succeededFuture() : Future.failedFuture(restored.cause()));
        });
    }

    @Override protected PgNodeConfig nodeConfig(String incarnation, String nodeId) {
        return new PgNodeConfig("lease-tests", incarnation, nodeId, MEMBERS,
            Duration.ofSeconds(10), Duration.ofMillis(250), Duration.ofSeconds(1), Duration.ofSeconds(1),
            PgWatchdogMode.AUTOMATIC);
    }

    @Override protected Future<PgLeaseCoordinator> coordinator(PgNodeConfig node) {
        int index = MEMBERS.indexOf(node.nodeId());
        String consulNode = "consul-" + (index + 1);
        return createScopedToken(node.controlName(), consulNode)
            .map(token -> new ConsulLeaseCoordinator(vertx, node, endpoint(index), consulNode, token));
    }

    @Override protected Future<PgLeaseCoordinator> unauthorisedCoordinator(PgNodeConfig node) {
        return Future.succeededFuture(new ConsulLeaseCoordinator(vertx, node, endpoint(0), "consul-1",
            UUID.randomUUID().toString()));
    }

    @Override protected Future<PgLeaseCoordinator> interceptedCoordinator(PgNodeConfig node) {
        return startProxy().map(uri -> new ConsulLeaseCoordinator(vertx, node, uri, "consul-1", ADMIN_TOKEN));
    }

    @Override protected void inject(Fault selected) {
        if (selected == Fault.DELAY_MUTATION_REPLY || selected == Fault.DELAY_RENEWAL_REPLY) {
            intercepted = Promise.promise();
            delayedReply = null;
        }
        fault = selected;
    }

    @Override protected Future<Void> replyCaptured() {
        return intercepted.future();
    }

    @Override protected void deliverCapturedReply() {
        delayedReply.run();
    }

    @Override protected Future<Void> overwriteIntentExternally(PgControlRecord record, JsonObject intent) {
        return admin.putAbs(endpoint(0) + "/v1/kv/" + record.controlName() + "?acquire=" + record.leaseHolder())
            .putHeader("X-Consul-Token", ADMIN_TOKEN).timeout(1000)
            .sendBuffer(Buffer.buffer(intent.encode()))
            .map(response -> {
                assertEquals(200, response.statusCode());
                assertEquals("true", response.bodyAsString());
                return null;
            });
    }

    @Override protected Future<Void> deleteRecordExternally() {
        return admin.deleteAbs(endpoint(0) + "/v1/kv/" + config.controlName())
            .putHeader("X-Consul-Token", ADMIN_TOKEN).timeout(1000).send()
            .map(response -> {
                assertEquals(200, response.statusCode());
                assertEquals("true", response.bodyAsString());
                return null;
            });
    }

    @Override protected Future<Void> suspendQuorumMajority() {
        return vertx.<Void>executeBlocking(() -> {
            for (int index : List.of(1, 2)) {
                var server = SERVERS.get(index);
                server.getDockerClient().pauseContainerCmd(server.getContainerId()).exec();
                paused.add(index);
            }
            return null;
        });
    }

    // ---------------------------------------------------------------- Consul wire details

    @Test void initialIntentOwnsTtlOnlySession(VertxTestContext context) {
        owner.createInitialIntent(initialIntent("pg-node-1"))
            .compose(record -> admin.getAbs(endpoint(0) + "/v1/session/info/" + record.leaseHolder() + "?consistent")
                .putHeader("X-Consul-Token", ADMIN_TOKEN).send())
            .onSuccess(response -> context.verify(() -> {
                var session = response.bodyAsJsonArray().getJsonObject(0);
                assertEquals("10s", session.getString("TTL"));
                assertEquals("release", session.getString("Behavior"));
                assertEquals(0L, session.getLong("LockDelay"));
                assertEquals("consul-1", session.getString("Node"));
                for (String field : List.of("Checks", "NodeChecks", "ServiceChecks")) {
                    JsonArray checks = session.getJsonArray(field);
                    assertTrue(checks == null || checks.isEmpty(), field + " must contain no failure detector");
                }
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void releaseKeepsTheKeyAndClearsItsSession(VertxTestContext context) {
        owner.createInitialIntent(initialIntent("pg-node-1")).compose(held -> owner.release(held)
            .compose(released -> admin.getAbs(endpoint(0) + "/v1/kv/" + config.controlName() + "?consistent")
                .putHeader("X-Consul-Token", ADMIN_TOKEN).send())
            .map(response -> {
                assertEquals(200, response.statusCode(), response.bodyAsString());
                JsonObject entry = response.bodyAsJsonArray().getJsonObject(0);
                assertNull(entry.getString("Session"));
                assertEquals(held.generation(), entry.getLong("LockIndex").longValue());
                assertEquals(held.intent(), new JsonObject(new String(
                    Base64.getDecoder().decode(entry.getString("Value")), StandardCharsets.UTF_8)));
                return response;
            })).onSuccess(response -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void nodeScopedAclCannotWriteAnotherIncarnation(VertxTestContext context) {
        createScopedToken(config.controlName(), "consul-1").compose(token ->
            admin.putAbs(endpoint(0) + "/v1/kv/" + nodeConfig("forbidden", "pg-node-1").controlName())
                .putHeader("X-Consul-Token", token).timeout(1000).sendBuffer(Buffer.buffer("forbidden")))
            .onSuccess(response -> context.verify(() -> {
                assertEquals(403, response.statusCode());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void nodeScopedAclRejectsForeignSessionRenewalAndDestruction(VertxTestContext context) {
        owner.createInitialIntent(initialIntent("pg-node-1")).compose(record ->
            createScopedToken(config.controlName(), "consul-2").compose(token ->
                admin.putAbs(endpoint(1) + "/v1/session/renew/" + record.leaseHolder())
                    .putHeader("X-Consul-Token", token).timeout(1000).send().compose(renewal -> {
                        assertEquals(403, renewal.statusCode());
                        return admin.putAbs(endpoint(1) + "/v1/session/destroy/" + record.leaseHolder())
                            .putHeader("X-Consul-Token", token).timeout(1000).send();
                    }))).onSuccess(destruction -> context.verify(() -> {
                assertEquals(403, destruction.statusCode());
                assertTrue(owner.hasFreshOwnership());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @ParameterizedTest @ValueSource(strings = {"null", "{}", "[null]", "not-json"})
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgPrimaryElector",
        message = "Coordinator operation failed",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    void malformedReadBodyCannotBecomeAuthority(String body, VertxTestContext context) {
        interceptedCoordinator(config).compose(coordinator -> {
            malformedBody = body;
            inject(Fault.MALFORMED_READ);
            return elector(config, coordinator).read();
        }).onComplete(context.failing(failure -> context.verify(() -> {
            assertInstanceOf(PgLeaseProtocolException.class, failure);
            context.completeNow();
        })));
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgPrimaryElector",
        message = "Coordinator operation failed",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    void unsafeSessionConfigurationCannotGrantOwnership(VertxTestContext context) {
        interceptedCoordinator(config).compose(coordinator -> {
            unsafeSession = true;
            return elector(config, coordinator).createInitialIntent(initialIntent("pg-node-1"));
        }).onComplete(context.failing(failure -> context.verify(() -> {
            assertInstanceOf(PgLeaseProtocolException.class, failure);
            context.completeNow();
        })));
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.consul.ConsulLeaseCoordinator",
        message = "Consul request rejected before sending",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    void adapterRejectsRenewalOfARecordWithoutALeaseHolder(VertxTestContext context) {
        var unowned = new PgControlRecord(config.controlName(), 1, 1, null, initialIntent("pg-node-1"));
        coordinator(config).compose(coordinator -> tracked(config, coordinator).renew(unowned))
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgLeaseProtocolException.class, failure);
                context.completeNow();
            })));
    }

    @Test void adapterRejectsLeaseTtlOutsideConsulRange() {
        for (Duration ttl : List.of(Duration.ofSeconds(9), Duration.ofMillis(10500), Duration.ofSeconds(86401))) {
            var node = new PgNodeConfig("lease-tests", "ttl-range", "pg-node-1", MEMBERS, ttl,
                Duration.ofMillis(250), Duration.ofSeconds(1), Duration.ofSeconds(1), PgWatchdogMode.OFF);
            assertThrows(IllegalArgumentException.class,
                () -> new ConsulLeaseCoordinator(vertx, node, endpoint(0), "consul-1", ADMIN_TOKEN),
                "TTL " + ttl + " is outside the range Consul accepts");
        }
    }

    @Test void adapterRejectsPlainHttpToNonLoopbackEndpoint() {
        assertThrows(IllegalArgumentException.class, () -> new ConsulLeaseCoordinator(vertx, config,
            URI.create("http://consul.example.test:8500"), "consul-1", ADMIN_TOKEN));
    }

    // ---------------------------------------------------------------- fixture

    private static URI endpoint(int index) {
        var server = SERVERS.get(index);
        return URI.create("http://" + server.getHost() + ":" + server.getMappedPort(8500));
    }

    private Future<String> createScopedToken(String key, String node) {
        String name = "lease-" + UUID.randomUUID();
        String rules = "key \"" + key + "\" { policy = \"write\" } "
            + "session \"" + node + "\" { policy = \"write\" } "
            + "session_prefix \"\" { policy = \"read\" } node_prefix \"\" { policy = \"read\" }";
        return admin.putAbs(endpoint(0) + "/v1/acl/policy").putHeader("X-Consul-Token", ADMIN_TOKEN)
            .sendJsonObject(new JsonObject().put("Name", name).put("Rules", rules))
            .compose(response -> {
                assertEquals(200, response.statusCode());
                String id = response.bodyAsJsonObject().getString("ID");
                return admin.putAbs(endpoint(0) + "/v1/acl/token").putHeader("X-Consul-Token", ADMIN_TOKEN)
                    .sendJsonObject(new JsonObject().put("Description", name)
                        .put("Policies", new JsonArray().add(new JsonObject().put("ID", id))));
            }).map(response -> {
                assertEquals(200, response.statusCode());
                return response.bodyAsJsonObject().getString("SecretID");
            });
    }

    private Future<Void> waitForLeader(long deadline) {
        return admin.getAbs(endpoint(0) + "/v1/status/leader").putHeader("X-Consul-Token", ADMIN_TOKEN)
            .timeout(1000).send().compose(response -> {
                if (response.statusCode() == 200 && response.bodyAsString().length() > 2) return Future.succeededFuture();
                if (System.nanoTime() >= deadline) return Future.failedFuture(new AssertionError("Consul quorum has no leader"));
                return vertx.timer(100).compose(ignored -> waitForLeader(deadline));
            });
    }

    /**
     * A new leader is visible before it has bootstrapped ACLs and registered the servers in the
     * catalog. Until then the management token gets HTTP 403 and session creation gets HTTP 500.
     */
    private Future<Void> waitForRegisteredServers(long deadline) {
        return admin.getAbs(endpoint(0) + "/v1/catalog/nodes").putHeader("X-Consul-Token", ADMIN_TOKEN)
            .timeout(1000).send().compose(response -> {
                if (response.statusCode() == 200 && response.bodyAsJsonArray().stream()
                        .map(node -> ((JsonObject) node).getString("Node")).toList()
                        .containsAll(List.of("consul-1", "consul-2", "consul-3"))) return Future.succeededFuture();
                if (System.nanoTime() >= deadline) {
                    return Future.failedFuture(new AssertionError("Consul servers are not registered: HTTP "
                        + response.statusCode() + " " + response.bodyAsString()));
                }
                return vertx.timer(100).compose(ignored -> waitForRegisteredServers(deadline));
            });
    }

    private Future<Void> restoreQuorum() {
        return vertx.<Void>executeBlocking(() -> {
            for (int index : paused) {
                var server = SERVERS.get(index);
                server.getDockerClient().unpauseContainerCmd(server.getContainerId()).exec();
            }
            paused.clear();
            return null;
        });
    }

    private Future<URI> startProxy() {
        faultProxy = vertx.createHttpServer().requestHandler(request -> {
            Fault active = fault;
            String path = request.path();
            boolean read = path.startsWith("/v1/kv/");
            boolean mutation = path.startsWith("/v1/txn");
            boolean renewal = path.startsWith("/v1/session/renew/");
            if (read && active == Fault.NON_AUTHORITATIVE_ABSENCE) {
                request.response().setStatusCode(404).putHeader("X-Consul-KnownLeader", "false").end()
                    .onFailure(failure -> logger.warn("Untrusted absence response failed", failure));
                return;
            }
            if (read && active == Fault.MALFORMED_READ) {
                request.response().putHeader("X-Consul-KnownLeader", "true").end(malformedBody)
                    .onFailure(failure -> logger.warn("Fault response failed", failure));
                return;
            }
            if (mutation && active == Fault.MALFORMED_MUTATION) {
                request.response().end("{}")
                    .onFailure(failure -> logger.warn("Fault response failed", failure));
                return;
            }
            if (read && active == Fault.SILENT_READ || mutation && active == Fault.SILENT_MUTATION) return;
            request.body().compose(body -> admin.requestAbs(HttpMethod.valueOf(request.method().name()),
                    endpoint(0) + request.uri()).putHeader("X-Consul-Token", ADMIN_TOKEN).timeout(1000).sendBuffer(body))
                .compose(response -> {
                    if (mutation && active == Fault.DROP_MUTATION_REPLY) return Future.<Void>succeededFuture();
                    if (mutation && active == Fault.DELAY_MUTATION_REPLY
                            || renewal && active == Fault.DELAY_RENEWAL_REPLY) {
                        delayedReply = () -> request.response().setStatusCode(response.statusCode())
                            .end(response.body()).onFailure(failure -> logger.warn("Delayed response failed", failure));
                        intercepted.tryComplete();
                        return Future.<Void>succeededFuture();
                    }
                    if (unsafeSession && path.startsWith("/v1/session/info/")) {
                        JsonArray sessions = response.bodyAsJsonArray();
                        sessions.getJsonObject(0).put("NodeChecks", new JsonArray().add("serfHealth"));
                        return request.response().setStatusCode(200).end(sessions.encode());
                    }
                    if (response.getHeader("X-Consul-KnownLeader") != null) {
                        request.response().putHeader("X-Consul-KnownLeader", response.getHeader("X-Consul-KnownLeader"));
                    }
                    return request.response().setStatusCode(response.statusCode()).end(response.body());
                }).onFailure(failure -> {
                    logger.warn("Fault proxy forwarding failed", failure);
                    request.response().setStatusCode(502).end().onFailure(error -> logger.warn("Proxy close failed", error));
                });
        });
        return faultProxy.listen(0, "127.0.0.1").map(server ->
            URI.create("http://127.0.0.1:" + server.actualPort()));
    }
}
