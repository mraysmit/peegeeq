package dev.mars.peegeeq.failover.consul;

import dev.mars.peegeeq.failover.PgLsn;
import dev.mars.peegeeq.failover.PgMemberPosition;
import dev.mars.peegeeq.failover.PgMemberPositionException;
import dev.mars.peegeeq.failover.PgMemberPositions;
import dev.mars.peegeeq.failover.PgMemberPositionsContract;
import dev.mars.peegeeq.failover.PgNodeConfig;
import dev.mars.peegeeq.failover.PgWatchdogMode;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.logging.ExpectedErrorLog;
import dev.mars.peegeeq.test.vertx.TestCleanup;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Consul binding of the member-position contract (plan §9.28, P7a and P7b): one real Consul server with ACLs and
 * node-scoped tokens. Cases declared here assert Consul wire details: the member session, the ACL that binds a record
 * to its member, and the adapter's own argument checks.
 */
@Tag(TestCategories.INTEGRATION)
@io.vertx.junit5.Timeout(value = 120, timeUnit = TimeUnit.SECONDS)
@Isolated
class ConsulMemberPositionsIntegrationTest extends PgMemberPositionsContract {
    private static final String ADMIN_TOKEN = "00000000-0000-0000-0000-000000000001";
    private static final String CONSUL_NODE = "consul-1";
    private static final GenericContainer<?> CONSUL = new GenericContainer<>("hashicorp/consul:1.22.1")
        .withExposedPorts(8500)
        .withEnv("CONSUL_LOCAL_CONFIG", new JsonObject().put("log_level", "warn")
            .put("acl", new JsonObject().put("enabled", true).put("default_policy", "deny")
                .put("tokens", new JsonObject().put("initial_management", ADMIN_TOKEN))).encode())
        .withCommand("agent", "-server", "-bootstrap-expect=1", "-node=" + CONSUL_NODE, "-client=0.0.0.0")
        .waitingFor(Wait.forHttp("/v1/status/leader").forPort(8500).withStartupTimeout(Duration.ofSeconds(60)));
    private WebClient admin;
    private HttpServer silent;

    @BeforeAll static void startConsul() {
        CONSUL.start();
    }

    @AfterAll static void stopConsul() {
        CONSUL.stop();
    }

    // ---------------------------------------------------------------- contract binding

    @Override protected Future<Void> prepareBinding() {
        admin = WebClient.create(vertx);
        silent = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        return waitForLeader(deadline).compose(ignored -> waitForRegisteredServer(deadline));
    }

    @Override protected Future<Void> releaseBinding() {
        Future<Void> closed = silent == null ? Future.succeededFuture() : silent.close();
        return closed.compose(ignored -> {
            admin.close();
            return Future.<Void>succeededFuture();
        });
    }

    @Override protected PgNodeConfig nodeConfig(String nodeId) {
        // The lease TTL is the member session's TTL. Ten seconds is the shortest Consul accepts.
        return new PgNodeConfig("positions-tests", incarnation, nodeId, MEMBERS, Duration.ofSeconds(10),
            Duration.ofMillis(250), Duration.ofSeconds(1), Duration.ofSeconds(1), PgWatchdogMode.OFF);
    }

    @Override protected Future<PgMemberPositions> positions(PgNodeConfig node) {
        return scopedToken(node.nodeId()).map(token -> adapter(node, endpoint(), token));
    }

    @Override protected Future<PgMemberPositions> writeRejectedPositions(PgNodeConfig node) {
        // The token may create sessions and read the members, and write no key: Consul answers the write with HTTP 403.
        return token("key_prefix \"" + prefix() + "\" { policy = \"read\" } " + sessionRules())
            .map(token -> adapter(node, endpoint(), token));
    }

    @Override protected Future<PgMemberPositions> filteredPositions(PgNodeConfig node) {
        // This token can read its own member's record only, so Consul filters every other record from the listing. A
        // list rule on the prefix would return every value (observed on Consul 1.22.1), so the token has none.
        return token("key \"" + prefix() + node.nodeId() + "\" { policy = \"read\" } " + sessionRules())
            .map(token -> adapter(node, endpoint(), token));
    }

    @Override protected Future<PgMemberPositions> silentPositions(PgNodeConfig node) {
        // A local server that accepts every request and never answers: a lost reply.
        return vertx.createHttpServer().requestHandler(request -> { }).listen(0, "127.0.0.1").map(server -> {
            silent = server;
            return adapter(node, URI.create("http://127.0.0.1:" + server.actualPort()), ADMIN_TOKEN);
        });
    }

    @Override protected PgMemberPositions unreachablePositions(PgNodeConfig node) {
        return adapter(node, URI.create("http://127.0.0.1:1"), ADMIN_TOKEN);
    }

    @Override protected Future<Void> storeExternally(String member, String value, boolean held) {
        Future<String> query = held ? adminSession().map(session -> "?acquire=" + session) : Future.succeededFuture("");
        return query.compose(suffix -> admin.putAbs(endpoint() + "/v1/kv/" + prefix() + member + suffix)
            .putHeader("X-Consul-Token", ADMIN_TOKEN).timeout(1000).sendBuffer(Buffer.buffer(value)))
            .map(response -> {
                assertEquals(200, response.statusCode(), response.bodyAsString());
                assertEquals("true", response.bodyAsString().strip(), "Consul did not store the external record");
                return null;
            });
    }

    // ---------------------------------------------------------------- Consul wire details

    @Test void aPublishedRecordIsHeldByAMemberSessionThatOnlyItsTtlEnds(VertxTestContext context) {
        member("pg-node-2").compose(two -> two.publish(new PgMemberPosition("pg-node-2", 1, PgLsn.parse("0/5000000"))))
            .compose(ignored -> admin.getAbs(endpoint() + "/v1/kv/" + prefix() + "pg-node-2")
                .putHeader("X-Consul-Token", ADMIN_TOKEN).timeout(1000).send())
            .compose(stored -> {
                assertEquals(200, stored.statusCode(), stored.bodyAsString());
                String session = stored.bodyAsJsonArray().getJsonObject(0).getString("Session");
                assertNotNull(session, "The record is not held by a session");
                return admin.getAbs(endpoint() + "/v1/session/info/" + session)
                    .putHeader("X-Consul-Token", ADMIN_TOKEN).timeout(1000).send();
            }).onSuccess(info -> context.verify(() -> {
                JsonObject session = info.bodyAsJsonArray().getJsonObject(0);
                assertEquals("10s", session.getString("TTL"));
                assertEquals("delete", session.getString("Behavior"), "The session's end must delete the record");
                assertEquals(0L, session.getLong("LockDelay"));
                assertEquals(CONSUL_NODE, session.getString("Node"));
                for (String field : List.of("Checks", "NodeChecks", "ServiceChecks")) {
                    JsonArray checks = session.getJsonArray(field);
                    assertTrue(checks == null || checks.isEmpty(), field + " must hold no failure detector");
                }
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.consul.ConsulMemberPositions",
        message = "Consul request rejected before sending",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgMemberPositionException.class)
    void aNodeCannotPublishAnotherMembersPositionThroughTheAdapterOrItsToken(VertxTestContext context) {
        var foreign = new PgMemberPosition("pg-node-2", 1, PgLsn.parse("0/5000000"));
        member("pg-node-1").compose(one -> one.publish(foreign).transform(refusal -> {
            assertTrue(refusal.failed(), "A node published another member's position");
            assertInstanceOf(PgMemberPositionException.class, refusal.cause());
            return Future.<Void>succeededFuture();
        })).compose(ignored -> scopedToken("pg-node-1"))
            // The node's own token cannot write another member's key either: the ACL binds the record to its member.
            .compose(token -> admin.putAbs(endpoint() + "/v1/kv/" + prefix() + "pg-node-2")
                .putHeader("X-Consul-Token", token).timeout(1000)
                .sendBuffer(Buffer.buffer(new JsonObject().put("timeline", 1).put("lsn", "0/5000000").encode())))
            .onSuccess(response -> context.verify(() -> {
                assertEquals(403, response.statusCode(), response.bodyAsString());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void aPublicationWhoseMemberSessionCannotBeCreatedFailsAndStoresNothing(VertxTestContext context) {
        // The token may write its own key and may not create a session: Consul answers the session request with 403.
        token("key \"" + prefix() + "pg-node-1\" { policy = \"write\" } key_prefix \"" + prefix() + "\" { policy = \"read\" }")
            .map(token -> track(adapter(nodeConfig("pg-node-1"), endpoint(), token)))
            .compose(one -> one.publish(new PgMemberPosition("pg-node-1", 1, PgLsn.parse("0/5000000"))).transform(publish -> {
                assertTrue(publish.failed(), "A publication without a member session was reported as stored");
                assertInstanceOf(PgMemberPositionException.class, publish.cause());
                assertTrue(publish.cause().getMessage().contains("HTTP 403"), publish.cause().getMessage());
                return member("pg-node-2").compose(PgMemberPositions::read);
            })).onSuccess(read -> context.verify(() -> {
                assertEquals(java.util.Map.of(), read, "A refused publication stored a position");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void aPublicationThatConsulDoesNotAcquireFailsAndLeavesTheHeldRecord(VertxTestContext context) {
        // A second adapter for the same member has its own session. While the first adapter's session holds the key,
        // Consul answers the second adapter's acquiring write with false and stores nothing.
        var held = new PgMemberPosition("pg-node-2", 1, PgLsn.parse("0/5000000"));
        var refused = new PgMemberPosition("pg-node-2", 1, PgLsn.parse("0/6000000"));
        member("pg-node-2").compose(first -> first.publish(held)
            .compose(ignored -> member("pg-node-2"))
            .compose(second -> second.publish(refused).transform(publish -> {
                assertTrue(publish.failed(), "A write that Consul did not acquire was reported as stored");
                assertInstanceOf(PgMemberPositionException.class, publish.cause());
                assertTrue(publish.cause().getMessage().startsWith(
                    "Consul did not store the member position under the member's session"), publish.cause().getMessage());
                return first.read();
            }))).onSuccess(read -> context.verify(() -> {
                assertEquals(java.util.Map.of("pg-node-2", held), read, "A refused publication replaced the held record");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void aMemberSessionWhoseSettingsDifferOnReadBackFailsThePublication(VertxTestContext context) {
        // A proxy forwards to Consul and reports the created session with Behavior=release, which would keep the record
        // after the member stops publishing.
        WebClient forward = WebClient.create(vertx);
        vertx.createHttpServer().requestHandler(request -> request.body().compose(body -> forward.requestAbs(
                request.method(), endpoint() + request.uri()).putHeader("X-Consul-Token", ADMIN_TOKEN).timeout(1000)
                .sendBuffer(body)).compose(response -> {
                    Buffer reply = response.body() == null ? Buffer.buffer() : response.body();
                    if (request.path().startsWith("/v1/session/info/")) {
                        JsonArray sessions = response.bodyAsJsonArray();
                        sessions.getJsonObject(0).put("Behavior", "release");
                        reply = Buffer.buffer(sessions.encode());
                    }
                    return request.response().setStatusCode(response.statusCode()).end(reply);
                }).onFailure(failure -> request.response().setStatusCode(502).end()
                    .onFailure(context::failNow)))
            .listen(0, "127.0.0.1").compose(server -> {
                silent = server;
                var proxied = track(adapter(nodeConfig("pg-node-1"), URI.create("http://127.0.0.1:" + server.actualPort()),
                    ADMIN_TOKEN));
                return proxied.publish(new PgMemberPosition("pg-node-1", 1, PgLsn.parse("0/5000000")));
            }).transform(TestCleanup.cleanup(() -> {
                forward.close();
                return Future.<Void>succeededFuture();
            })).onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgMemberPositionException.class, failure);
                assertTrue(failure.getMessage().startsWith("Member session settings differ"), failure.getMessage());
                context.completeNow();
            })));
    }

    @Test void theAdapterRejectsPlainHttpToANonLoopbackEndpointAMissingTokenABadNodeNameAndAnOutOfRangeTtl() {
        var node = nodeConfig("pg-node-1");
        URI remote = URI.create("http://consul.example.test:8500");
        URI local = endpoint();
        assertThrows(IllegalArgumentException.class, () -> new ConsulMemberPositions(vertx, node, remote, CONSUL_NODE,
            ADMIN_TOKEN));
        assertThrows(IllegalArgumentException.class, () -> new ConsulMemberPositions(vertx, node, local, CONSUL_NODE, " "));
        assertThrows(IllegalArgumentException.class, () -> new ConsulMemberPositions(vertx, node, local, "consul node",
            ADMIN_TOKEN));
        var shortTtl = new PgNodeConfig("positions-tests", incarnation, "pg-node-1", MEMBERS, Duration.ofSeconds(9),
            Duration.ofMillis(250), Duration.ofSeconds(1), Duration.ofSeconds(1), PgWatchdogMode.OFF);
        assertThrows(IllegalArgumentException.class, () -> new ConsulMemberPositions(vertx, shortTtl, local, CONSUL_NODE,
            ADMIN_TOKEN));
    }

    // ---------------------------------------------------------------- fixture

    private PgMemberPositions adapter(PgNodeConfig node, URI uri, String token) {
        return new ConsulMemberPositions(vertx, node, uri, CONSUL_NODE, token);
    }

    /** The ACL of the binding: a node writes its own member key, reads the members prefix, and owns its sessions. */
    private Future<String> scopedToken(String nodeId) {
        return token("key \"" + prefix() + nodeId + "\" { policy = \"write\" } key_prefix \"" + prefix()
            + "\" { policy = \"read\" } " + sessionRules());
    }

    private static String sessionRules() {
        return "session \"" + CONSUL_NODE + "\" { policy = \"write\" } session_prefix \"\" { policy = \"read\" }";
    }

    private Future<String> token(String rules) {
        String name = "positions-" + UUID.randomUUID();
        return admin.putAbs(endpoint() + "/v1/acl/policy").putHeader("X-Consul-Token", ADMIN_TOKEN)
            .sendJsonObject(new JsonObject().put("Name", name).put("Rules", rules)).compose(response -> {
                assertEquals(200, response.statusCode(), response.bodyAsString());
                String id = response.bodyAsJsonObject().getString("ID");
                return admin.putAbs(endpoint() + "/v1/acl/token").putHeader("X-Consul-Token", ADMIN_TOKEN)
                    .sendJsonObject(new JsonObject().put("Description", name)
                        .put("Policies", new JsonArray().add(new JsonObject().put("ID", id))));
            }).map(response -> {
                assertEquals(200, response.statusCode(), response.bodyAsString());
                return response.bodyAsJsonObject().getString("SecretID");
            });
    }

    /** A live session of the management token, so that an external record is held as a publication's record is. */
    private Future<String> adminSession() {
        return admin.putAbs(endpoint() + "/v1/session/create").putHeader("X-Consul-Token", ADMIN_TOKEN).timeout(1000)
            .sendJsonObject(new JsonObject().put("Node", CONSUL_NODE).put("TTL", "60s").put("Behavior", "delete")
                .put("LockDelay", "0s").put("NodeChecks", new JsonArray()).put("ServiceChecks", new JsonArray()))
            .map(response -> {
                assertEquals(200, response.statusCode(), response.bodyAsString());
                return response.bodyAsJsonObject().getString("ID");
            });
    }

    private String prefix() {
        return "peegeeq/pg/positions-tests/" + incarnation + "/members/";
    }

    private static URI endpoint() {
        return URI.create("http://" + CONSUL.getHost() + ":" + CONSUL.getMappedPort(8500));
    }

    private Future<Void> waitForLeader(long deadline) {
        return admin.getAbs(endpoint() + "/v1/status/leader").putHeader("X-Consul-Token", ADMIN_TOKEN)
            .timeout(1000).send().compose(response -> {
                if (response.statusCode() == 200 && response.bodyAsString().length() > 2) return Future.succeededFuture();
                if (System.nanoTime() >= deadline) return Future.failedFuture(new AssertionError("Consul has no leader"));
                return vertx.timer(100).compose(ignored -> waitForLeader(deadline));
            });
    }

    /** A new leader is visible before it has bootstrapped ACLs and registered itself. Until then the token gets 403. */
    private Future<Void> waitForRegisteredServer(long deadline) {
        return admin.getAbs(endpoint() + "/v1/catalog/nodes").putHeader("X-Consul-Token", ADMIN_TOKEN)
            .timeout(1000).send().compose(response -> {
                if (response.statusCode() == 200 && response.bodyAsJsonArray().stream()
                        .anyMatch(node -> CONSUL_NODE.equals(((JsonObject) node).getString("Node")))) {
                    return Future.<Void>succeededFuture();
                }
                if (System.nanoTime() >= deadline) {
                    return Future.<Void>failedFuture(new AssertionError("Consul server is not registered: HTTP "
                        + response.statusCode() + " " + response.bodyAsString()));
                }
                return vertx.timer(100).compose(ignored -> waitForRegisteredServer(deadline));
            });
    }
}
