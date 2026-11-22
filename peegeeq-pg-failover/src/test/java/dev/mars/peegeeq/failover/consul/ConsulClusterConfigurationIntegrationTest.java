package dev.mars.peegeeq.failover.consul;

import dev.mars.peegeeq.failover.PgClusterConfiguration;
import dev.mars.peegeeq.failover.PgClusterConfigurationContract;
import dev.mars.peegeeq.failover.PgClusterConfigurationException;
import dev.mars.peegeeq.failover.PgClusterSettings;
import dev.mars.peegeeq.failover.PgNodeConfig;
import dev.mars.peegeeq.failover.PgWatchdogMode;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.containers.TestContainerImages;
import dev.mars.peegeeq.test.logging.ExpectedErrorLog;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Consul binding of the cluster-settings contract (plan §9.34, contract 4): one real Consul server with ACLs and
 * key-scoped tokens. Cases declared here assert Consul wire details: the key and its value, the consistent read, and the
 * adapter's own argument checks.
 */
@Tag(TestCategories.INTEGRATION)
@io.vertx.junit5.Timeout(value = 120, timeUnit = TimeUnit.SECONDS)
@Isolated
class ConsulClusterConfigurationIntegrationTest extends PgClusterConfigurationContract {
    private static final String ADMIN_TOKEN = "00000000-0000-0000-0000-000000000001";
    private static final String CONSUL_NODE = "consul-1";
    private static final GenericContainer<?> CONSUL = new GenericContainer<>(TestContainerImages.CONSUL_IMAGE)
        .withExposedPorts(8500)
        .withEnv("CONSUL_LOCAL_CONFIG", new JsonObject().put("log_level", "warn")
            .put("acl", new JsonObject().put("enabled", true).put("default_policy", "deny")
                .put("tokens", new JsonObject().put("initial_management", ADMIN_TOKEN))).encode())
        .withCommand("agent", "-server", "-bootstrap-expect=1", "-node=" + CONSUL_NODE, "-client=0.0.0.0")
        .waitingFor(Wait.forHttp("/v1/status/leader").forPort(8500).withStartupTimeout(Duration.ofSeconds(60)));
    private WebClient admin;
    private HttpServer fake;

    @BeforeAll static void startConsul() {
        CONSUL.start();
    }

    @AfterAll static void stopConsul() {
        CONSUL.stop();
    }

    // ---------------------------------------------------------------- contract binding

    @Override protected Future<Void> prepareBinding() {
        admin = WebClient.create(vertx);
        fake = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        return waitForLeader(deadline).compose(ignored -> waitForRegisteredServer(deadline));
    }

    @Override protected Future<Void> releaseBinding() {
        Future<Void> closed = fake == null ? Future.succeededFuture() : fake.close();
        return closed.compose(ignored -> {
            admin.close();
            return Future.<Void>succeededFuture();
        });
    }

    @Override protected PgNodeConfig nodeConfig(String nodeId) {
        return new PgNodeConfig("settings-tests", incarnation, nodeId, MEMBERS, Duration.ofSeconds(10),
            Duration.ofMillis(250), Duration.ofSeconds(1), Duration.ofSeconds(1), PgWatchdogMode.OFF);
    }

    @Override protected Future<PgClusterConfiguration> configuration(PgNodeConfig node) {
        return token("key \"" + key() + "\" { policy = \"write\" }").map(token -> adapter(node, endpoint(), token));
    }

    @Override protected Future<PgClusterConfiguration> writeRejectedConfiguration(PgNodeConfig node) {
        // The token may read the key and may not write it: Consul answers the write with HTTP 403.
        return token("key \"" + key() + "\" { policy = \"read\" }").map(token -> adapter(node, endpoint(), token));
    }

    @Override protected Future<PgClusterConfiguration> silentConfiguration(PgNodeConfig node) {
        // A local server that accepts every request and never answers: a lost reply.
        return vertx.createHttpServer().requestHandler(request -> { }).listen(0, "127.0.0.1").map(server -> {
            fake = server;
            return adapter(node, URI.create("http://127.0.0.1:" + server.actualPort()), ADMIN_TOKEN);
        });
    }

    @Override protected PgClusterConfiguration unreachableConfiguration(PgNodeConfig node) {
        return adapter(node, URI.create("http://127.0.0.1:1"), ADMIN_TOKEN);
    }

    @Override protected Future<Void> storeExternally(String value) {
        return admin.putAbs(endpoint() + "/v1/kv/" + key()).putHeader("X-Consul-Token", ADMIN_TOKEN).timeout(1000)
            .sendBuffer(Buffer.buffer(value)).map(response -> {
                assertEquals(200, response.statusCode(), response.bodyAsString());
                assertEquals("true", response.bodyAsString().strip(), "Consul did not store the external record");
                return null;
            });
    }

    // ---------------------------------------------------------------- Consul wire details

    @Test void theRecordIsTheConfigKeyOfTheIncarnationHoldingTheSettingsAsJson(VertxTestContext context) {
        authorised().compose(writer -> writer.create(FIRST))
            .compose(created -> admin.getAbs(endpoint() + "/v1/kv/" + key()).putHeader("X-Consul-Token", ADMIN_TOKEN)
                .timeout(1000).send().map(stored -> {
                    assertEquals(200, stored.statusCode(), stored.bodyAsString());
                    JsonObject kv = stored.bodyAsJsonArray().getJsonObject(0);
                    assertEquals("peegeeq/pg/settings-tests/" + incarnation + "/config", kv.getString("Key"));
                    assertEquals(created.revision(), kv.getLong("ModifyIndex"), "The revision is the ModifyIndex");
                    assertNull(kv.getString("Session"), "The record is held by no session");
                    String value = new String(Base64.getDecoder().decode(kv.getString("Value")), StandardCharsets.UTF_8);
                    assertEquals(FIRST.toJson(), new JsonObject(value));
                    return null;
                })).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void aReadWithoutAKnownLeaderFails(VertxTestContext context) {
        answering(false, false, context).compose(PgClusterConfiguration::read)
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgClusterConfigurationException.class, failure);
                assertTrue(failure.getMessage().contains("no known leader"), failure.getMessage());
                context.completeNow();
            })));
    }

    @Test void aReadThatTheServiceFilteredFails(VertxTestContext context) {
        answering(true, true, context).compose(PgClusterConfiguration::read)
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgClusterConfigurationException.class, failure);
                assertTrue(failure.getMessage().contains("filtered"), failure.getMessage());
                context.completeNow();
            })));
    }

    @Test void aWrittenRecordThatReadsBackOtherSettingsFails(VertxTestContext context) {
        // Consul stores the write, and the read back shows other settings: another editor wrote in between.
        serving("true", new JsonArray().add(entry(key(), 7, SECOND)), context)
            .compose(writer -> writer.create(FIRST))
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgClusterConfigurationException.class, failure);
                assertTrue(failure.getMessage().contains("differ"), failure.getMessage());
                context.completeNow();
            })));
    }

    @Test void anUpdateWhoseReadBackDoesNotAdvanceTheRevisionFails(VertxTestContext context) {
        serving("true", new JsonArray().add(entry(key(), 7, SECOND)), context)
            .compose(writer -> writer.update(new dev.mars.peegeeq.failover.PgClusterSettingsRecord(7, FIRST), SECOND))
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgClusterConfigurationException.class, failure);
                assertTrue(failure.getMessage().contains("did not advance the revision"), failure.getMessage());
                context.completeNow();
            })));
    }

    @Test void aReadOfAnotherKeyFails(VertxTestContext context) {
        servingRead(new JsonArray().add(entry(key() + "-other", 7, FIRST)), context);
    }

    @Test void aReadWithoutAPositiveRevisionFails(VertxTestContext context) {
        servingRead(new JsonArray().add(entry(key(), 0, FIRST)), context);
    }

    @Test void aReadOfMoreThanOneEntryFails(VertxTestContext context) {
        servingRead(new JsonArray().add(entry(key(), 7, FIRST)).add(entry(key(), 8, FIRST)), context);
    }

    @Test void aTokenWithoutReadFailsTheRead(VertxTestContext context) {
        storeExternally(FIRST.toJson().encode())
            .compose(ignored -> token("key \"" + key() + "-other\" { policy = \"read\" }"))
            .map(token -> track(adapter(nodeConfig("pg-node-1"), endpoint(), token)))
            .compose(PgClusterConfiguration::read)
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgClusterConfigurationException.class, failure);
                assertTrue(failure.getMessage().contains("HTTP 403"), failure.getMessage());
                context.completeNow();
            })));
    }

    @Test void aStoredLeaseTtlThatConsulCannotServeFailsTheRead(VertxTestContext context) {
        // 9 s satisfies the timing rule, and Consul accepts no session TTL below 10 s.
        storeExternally(new PgClusterSettings(Duration.ofSeconds(9), Duration.ofSeconds(1), Duration.ofSeconds(2),
                Duration.ofSeconds(2)).toJson().encode())
            .compose(ignored -> authorised()).compose(PgClusterConfiguration::read)
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgClusterConfigurationException.class, failure);
                context.completeNow();
            })));
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.consul.ConsulClusterConfiguration",
        message = "Consul request rejected before sending",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgClusterConfigurationException.class,
        minOccurrences = 2, maxOccurrences = 2)
    void settingsWithALeaseTtlThatConsulCannotServeAreRefusedBeforeSending(VertxTestContext context) {
        var unservable = new PgClusterSettings(Duration.ofMillis(30_500), Duration.ofSeconds(5), Duration.ofSeconds(3),
            Duration.ofSeconds(5));
        authorised().compose(writer -> writer.create(unservable).transform(create -> {
            refused(create, "A lease TTL that is not whole seconds was stored");
            return writer.create(FIRST).compose(created -> writer.update(created, unservable)).transform(update -> {
                refused(update, "An update to a lease TTL that is not whole seconds was stored");
                return writer.read();
            });
        })).onSuccess(read -> context.verify(() -> {
            assertEquals(FIRST, read.orElseThrow().settings(), "A refused write changed the record");
            context.completeNow();
        })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.consul.ConsulClusterConfiguration",
        message = "Consul request rejected before sending",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgClusterConfigurationException.class,
        minOccurrences = 3, maxOccurrences = 3)
    void missingArgumentsAreRefusedBeforeSending(VertxTestContext context) {
        authorised().compose(writer -> writer.create(null)
            .transform(create -> refused(create, "A create without settings was sent"))
            .compose(ignored -> writer.update(null, FIRST))
            .transform(update -> refused(update, "An update without the expected record was sent"))
            .compose(ignored -> writer.create(FIRST)).compose(created -> writer.update(created, null))
            .transform(update -> refused(update, "An update without settings was sent")))
            .onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void theAdapterRejectsPlainHttpToANonLoopbackEndpointAndAMissingToken() {
        var node = nodeConfig("pg-node-1");
        URI remote = URI.create("http://consul.example.test:8500");
        URI local = endpoint();
        assertThrows(IllegalArgumentException.class, () -> new ConsulClusterConfiguration(vertx, node, remote,
            ADMIN_TOKEN));
        assertThrows(IllegalArgumentException.class, () -> new ConsulClusterConfiguration(vertx, node, local, " "));
        assertThrows(NullPointerException.class, () -> new ConsulClusterConfiguration(vertx, null, local, ADMIN_TOKEN));
    }

    // ---------------------------------------------------------------- fixture

    private PgClusterConfiguration adapter(PgNodeConfig node, URI uri, String token) {
        return new ConsulClusterConfiguration(vertx, node, uri, token);
    }

    /**
     * An adapter whose service answers every read with a valid record, and with the given leader and ACL-filter headers.
     */
    private Future<PgClusterConfiguration> answering(boolean knownLeader, boolean filtered, VertxTestContext context) {
        JsonArray body = new JsonArray().add(new JsonObject().put("Key", key()).put("ModifyIndex", 7).put("LockIndex", 0)
            .put("Value", Base64.getEncoder().encodeToString(FIRST.toJson().encode().getBytes(StandardCharsets.UTF_8))));
        return vertx.createHttpServer().requestHandler(request -> request.response()
                .putHeader("X-Consul-KnownLeader", String.valueOf(knownLeader))
                .putHeader("X-Consul-Results-Filtered-By-ACLs", String.valueOf(filtered))
                .end(body.encode()).onFailure(context::failNow))
            .listen(0, "127.0.0.1").map(server -> {
                fake = server;
                return track(adapter(nodeConfig("pg-node-1"), URI.create("http://127.0.0.1:" + server.actualPort()),
                    ADMIN_TOKEN));
            });
    }

    /** An adapter whose service answers a write with {@code writeReply} and every read with {@code readBody}. */
    private Future<PgClusterConfiguration> serving(String writeReply, JsonArray readBody, VertxTestContext context) {
        return vertx.createHttpServer().requestHandler(request -> {
                var response = request.response().putHeader("X-Consul-KnownLeader", "true");
                (request.method() == io.vertx.core.http.HttpMethod.PUT ? response.end(writeReply)
                    : response.end(readBody.encode())).onFailure(context::failNow);
            })
            .listen(0, "127.0.0.1").map(server -> {
                fake = server;
                return track(adapter(nodeConfig("pg-node-1"), URI.create("http://127.0.0.1:" + server.actualPort()),
                    ADMIN_TOKEN));
            });
    }

    /** The read of {@code readBody} fails with the port's exception. */
    private void servingRead(JsonArray readBody, VertxTestContext context) {
        serving("true", readBody, context).compose(PgClusterConfiguration::read)
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgClusterConfigurationException.class, failure);
                context.completeNow();
            })));
    }

    private static JsonObject entry(String key, long revision, PgClusterSettings settings) {
        return new JsonObject().put("Key", key).put("ModifyIndex", revision).put("LockIndex", 0)
            .put("Value", Base64.getEncoder().encodeToString(settings.toJson().encode().getBytes(StandardCharsets.UTF_8)));
    }

    private Future<String> token(String rules) {
        String name = "settings-" + UUID.randomUUID();
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

    private String key() {
        return "peegeeq/pg/settings-tests/" + incarnation + "/config";
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
