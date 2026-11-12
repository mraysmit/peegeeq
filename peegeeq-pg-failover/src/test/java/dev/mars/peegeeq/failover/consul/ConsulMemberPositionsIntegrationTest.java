package dev.mars.peegeeq.failover.consul;

import dev.mars.peegeeq.failover.PgLeaseProtocolException;
import dev.mars.peegeeq.failover.PgLsn;
import dev.mars.peegeeq.failover.PgMemberPosition;
import dev.mars.peegeeq.failover.PgMemberPositions;
import dev.mars.peegeeq.failover.PgNodeConfig;
import dev.mars.peegeeq.failover.PgWatchdogMode;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.logging.ExpectedErrorLog;
import dev.mars.peegeeq.test.vertx.TestCleanup;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Isolated;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The member-position port against a real Consul server with ACLs and node-scoped tokens (plan §9.27, OD-41). Each
 * case states an obligation of the port: a member writes only its own record, and a malformed, filtered, rejected,
 * unanswered, or unreachable response fails.
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Isolated
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ConsulMemberPositionsIntegrationTest {
    private static final List<String> MEMBERS = List.of("pg-node-1", "pg-node-2", "pg-node-3");
    private static final String ADMIN_TOKEN = "00000000-0000-0000-0000-000000000001";
    private static final GenericContainer<?> CONSUL = new GenericContainer<>("hashicorp/consul:1.22.1")
        .withExposedPorts(8500)
        .withEnv("CONSUL_LOCAL_CONFIG", new JsonObject().put("log_level", "warn")
            .put("acl", new JsonObject().put("enabled", true).put("default_policy", "deny")
                .put("tokens", new JsonObject().put("initial_management", ADMIN_TOKEN))).encode())
        .withCommand("agent", "-server", "-bootstrap-expect=1", "-node=consul-1", "-client=0.0.0.0")
        .waitingFor(Wait.forHttp("/v1/status/leader").forPort(8500).withStartupTimeout(Duration.ofSeconds(60)));
    private final List<PgMemberPositions> opened = new ArrayList<>();
    private Vertx vertx;
    private WebClient admin;
    private String incarnation;
    private HttpServer silent;

    @BeforeAll static void startConsul() {
        CONSUL.start();
    }

    @AfterAll static void stopConsul() {
        CONSUL.stop();
    }

    @BeforeEach void setUp(Vertx vertx, VertxTestContext context) {
        this.vertx = vertx;
        admin = WebClient.create(vertx);
        incarnation = "inc-" + UUID.randomUUID();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        waitForLeader(deadline).compose(ignored -> waitForRegisteredServer(deadline))
            .onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @AfterEach void tearDown(VertxTestContext context) {
        TestCleanup.run(() -> Future.join(opened.stream().map(PgMemberPositions::close).toList()),
            () -> silent == null ? Future.succeededFuture() : silent.close(),
            () -> {
                admin.close();
                return Future.<Void>succeededFuture();
            }).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void everyPublishedPositionIsReadBackByEveryMember(VertxTestContext context) {
        var first = new PgMemberPosition("pg-node-1", 1, PgLsn.parse("0/A000028"));
        var second = new PgMemberPosition("pg-node-2", 1, PgLsn.parse("0/9FFF000"));
        positionsOf("pg-node-1").compose(one -> one.publish(first))
            .compose(ignored -> positionsOf("pg-node-2")).compose(two -> two.publish(second))
            .compose(ignored -> positionsOf("pg-node-3")).compose(PgMemberPositions::read)
            .onSuccess(read -> context.verify(() -> {
                assertEquals(Map.of("pg-node-1", first, "pg-node-2", second), read);
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void aLaterPublicationReplacesTheEarlierOne(VertxTestContext context) {
        var earlier = new PgMemberPosition("pg-node-2", 1, PgLsn.parse("0/5000000"));
        var later = new PgMemberPosition("pg-node-2", 2, PgLsn.parse("0/6000000"));
        positionsOf("pg-node-2").compose(two -> two.publish(earlier).compose(ignored -> two.publish(later))
            .compose(ignored -> two.read()))
            .onSuccess(read -> context.verify(() -> {
                assertEquals(Map.of("pg-node-2", later), read);
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void anEmptyNamespaceReadsAsNoPositions(VertxTestContext context) {
        positionsOf("pg-node-1").compose(PgMemberPositions::read)
            .onSuccess(read -> context.verify(() -> {
                assertEquals(Map.of(), read, "A member that never published must be absent");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.consul.ConsulMemberPositions",
        message = "Consul request rejected before sending",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgLeaseProtocolException.class)
    void aNodeCannotPublishAnotherMembersPositionThroughTheAdapterOrItsToken(VertxTestContext context) {
        var foreign = new PgMemberPosition("pg-node-2", 1, PgLsn.parse("0/5000000"));
        positionsOf("pg-node-1").compose(one -> one.publish(foreign).transform(refusal -> {
            assertTrue(refusal.failed(), "A node published another member's position");
            assertInstanceOf(PgLeaseProtocolException.class, refusal.cause());
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

    @Test void aPublicationThatConsulRejectsFails(VertxTestContext context) {
        // A token that may read the members but write nothing: Consul answers HTTP 403.
        readOnlyToken().map(token -> track(new ConsulMemberPositions(vertx, nodeConfig("pg-node-1"), endpoint(), token)))
            .compose(one -> one.publish(new PgMemberPosition("pg-node-1", 1, PgLsn.parse("0/5000000"))))
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgLeaseProtocolException.class, failure);
                assertTrue(failure.getMessage().contains("HTTP 403"), failure.getMessage());
                context.completeNow();
            })));
    }

    @Test void aMalformedValueOrARecordOfANonMemberFailsTheRead(VertxTestContext context) {
        putAsAdmin("pg-node-2", "not-json")
            .compose(ignored -> positionsOf("pg-node-1")).compose(one -> one.read().transform(read -> {
                assertTrue(read.failed(), "A malformed member record was read as a position");
                assertInstanceOf(PgLeaseProtocolException.class, read.cause());
                return Future.<Void>succeededFuture();
            }).compose(ignored -> putAsAdmin("pg-node-2", new JsonObject().put("timeline", 1).put("lsn", "0/5000000").encode()))
                .compose(ignored -> putAsAdmin("pg-node-9", new JsonObject().put("timeline", 1).put("lsn", "0/5000000").encode()))
                .compose(ignored -> one.read().transform(read -> {
                    assertTrue(read.failed(), "A record of a node outside the membership was read as a position");
                    assertInstanceOf(PgLeaseProtocolException.class, read.cause());
                    return Future.<Void>succeededFuture();
                }))).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void aReadThatAclsFilteredFails(VertxTestContext context) {
        String value = new JsonObject().put("timeline", 1).put("lsn", "0/5000000").encode();
        putAsAdmin("pg-node-1", value).compose(ignored -> putAsAdmin("pg-node-2", value))
            // This token can read pg-node-1's record only, so Consul filters pg-node-2's from the listing. A list rule on
            // the prefix would return every value (observed on Consul 1.22.1), so the token has none.
            .compose(ignored -> token("key \"" + prefix() + "pg-node-1\" { policy = \"read\" }"))
            .map(token -> track(new ConsulMemberPositions(vertx, nodeConfig("pg-node-1"), endpoint(), token)))
            .compose(PgMemberPositions::read)
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgLeaseProtocolException.class, failure);
                context.completeNow();
            })));
    }

    @Test void aCoordinatorThatDoesNotAnswerFailsBothOperationsWithinTheRequestDeadline(VertxTestContext context) {
        // A local server that accepts every request and never answers: a lost reply.
        vertx.createHttpServer().requestHandler(request -> { }).listen(0, "127.0.0.1").compose(server -> {
            silent = server;
            var positions = track(new ConsulMemberPositions(vertx, nodeConfig("pg-node-1"),
                URI.create("http://127.0.0.1:" + server.actualPort()), ADMIN_TOKEN));
            long started = System.nanoTime();
            return positions.publish(new PgMemberPosition("pg-node-1", 1, PgLsn.parse("0/5000000")))
                .transform(publish -> {
                    assertTrue(publish.failed(), "An unanswered publication was reported as stored");
                    assertInstanceOf(PgLeaseProtocolException.class, publish.cause());
                    return Future.<Void>succeededFuture();
                }).compose(ignored -> positions.read().transform(read -> {
                    assertTrue(read.failed(), "An unanswered read returned positions");
                    assertInstanceOf(PgLeaseProtocolException.class, read.cause());
                    assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(10),
                        "The request deadline was not honoured");
                    return Future.<Void>succeededFuture();
                }));
        }).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void anUnreachableCoordinatorFailsBothOperations(VertxTestContext context) {
        var positions = track(new ConsulMemberPositions(vertx, nodeConfig("pg-node-1"), URI.create("http://127.0.0.1:1"),
            ADMIN_TOKEN));
        positions.publish(new PgMemberPosition("pg-node-1", 1, PgLsn.parse("0/5000000"))).transform(publish -> {
            assertTrue(publish.failed());
            assertInstanceOf(PgLeaseProtocolException.class, publish.cause());
            return Future.<Void>succeededFuture();
        }).compose(ignored -> positions.read().transform(read -> {
            assertTrue(read.failed());
            assertInstanceOf(PgLeaseProtocolException.class, read.cause());
            return Future.<Void>succeededFuture();
        })).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void theAdapterRejectsPlainHttpToANonLoopbackEndpointAndAMissingToken() {
        assertThrows(IllegalArgumentException.class, () -> new ConsulMemberPositions(vertx, nodeConfig("pg-node-1"),
            URI.create("http://consul.example.test:8500"), ADMIN_TOKEN));
        assertThrows(IllegalArgumentException.class, () -> new ConsulMemberPositions(vertx, nodeConfig("pg-node-1"),
            endpoint(), " "));
    }

    // ---------------------------------------------------------------- fixture

    private Future<PgMemberPositions> positionsOf(String nodeId) {
        return scopedToken(nodeId).map(token -> track(new ConsulMemberPositions(vertx, nodeConfig(nodeId), endpoint(), token)));
    }

    private PgMemberPositions track(PgMemberPositions positions) {
        opened.add(positions);
        return positions;
    }

    /** The ACL of OD-41's binding: a node writes its own member key and reads the members prefix. */
    private Future<String> scopedToken(String nodeId) {
        return token("key \"" + prefix() + nodeId + "\" { policy = \"write\" } key_prefix \"" + prefix()
            + "\" { policy = \"read\" }");
    }

    private Future<String> readOnlyToken() {
        return token("key_prefix \"" + prefix() + "\" { policy = \"read\" }");
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

    private Future<Void> putAsAdmin(String member, String value) {
        return admin.putAbs(endpoint() + "/v1/kv/" + prefix() + member).putHeader("X-Consul-Token", ADMIN_TOKEN)
            .timeout(1000).sendBuffer(Buffer.buffer(value)).map(response -> {
                assertEquals(200, response.statusCode(), response.bodyAsString());
                return null;
            });
    }

    private String prefix() {
        return "peegeeq/pg/positions-tests/" + incarnation + "/members/";
    }

    private PgNodeConfig nodeConfig(String nodeId) {
        return new PgNodeConfig("positions-tests", incarnation, nodeId, MEMBERS, Duration.ofSeconds(10),
            Duration.ofMillis(250), Duration.ofSeconds(1), Duration.ofSeconds(1), PgWatchdogMode.OFF);
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
                        .anyMatch(node -> "consul-1".equals(((JsonObject) node).getString("Node")))) {
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
