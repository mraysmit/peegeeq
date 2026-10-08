package dev.mars.peegeeq.failover;

import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Isolated
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ConsulLeaseProtocolIntegrationTest {
    private static final Logger logger = LoggerFactory.getLogger(ConsulLeaseProtocolIntegrationTest.class);
    private static final String IMAGE = "hashicorp/consul:1.22.1";
    private static final String ADMIN_TOKEN = "00000000-0000-0000-0000-000000000001";
    private static final List<String> MEMBERS = List.of("pg-node-1", "pg-node-2", "pg-node-3");
    private static final List<GenericContainer<?>> SERVERS = new ArrayList<>();
    private static Network network;
    private Vertx vertx;
    private WebClient admin;
    private PgNodeConfig config;
    private PgPrimaryElector owner;
    private final List<PgPrimaryElector> electors = new ArrayList<>();
    private final List<Integer> paused = new ArrayList<>();
    private HttpServer faultProxy;
    private String faultBody;
    private String faultPath;
    private String proxyMode = "forward";
    private Promise<Void> intercepted;
    private Runnable delayedReply;

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

    @BeforeEach void setUp(Vertx vertx, VertxTestContext context) {
        this.vertx = vertx;
        admin = WebClient.create(vertx);
        config = quickConfig("inc-" + UUID.randomUUID(), "pg-node-1");
        waitForLeader(System.nanoTime() + TimeUnit.SECONDS.toNanos(30))
            .compose(ignored -> createScopedToken(config.controlKey(), "consul-1"))
            .onSuccess(token -> context.verify(() -> {
                owner = elector(config, endpoint(0), "consul-1", token);
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @AfterEach void tearDown(VertxTestContext context) {
        restoreQuorum().transform(restored -> Future.all(electors.stream()
            .map(PgPrimaryElector::close).toList()).compose(ignored -> {
                admin.close();
                return faultProxy == null ? Future.<Void>succeededFuture() : faultProxy.close();
            }).compose(ignored -> restored.succeeded()
                ? Future.<Void>succeededFuture() : Future.failedFuture(restored.cause())))
            .onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void initialIntentOwnsTtlOnlySession(VertxTestContext context) {
        owner.createInitialIntent(initialIntent("pg-node-1"))
            .compose(record -> {
                assertTrue(owner.hasFreshOwnership());
                assertEquals(config.controlKey(), record.key());
                assertEquals("WITHDRAWN", record.intent().getString("phase"));
                return admin.getAbs(endpoint(0) + "/v1/session/info/" + record.sessionId() + "?consistent")
                    .putHeader("X-Consul-Token", ADMIN_TOKEN).send();
            }).onSuccess(response -> context.verify(() -> {
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

    @Test void simultaneousInitialOwnersCannotBothAcquire(VertxTestContext context) {
        var otherConfig = quickConfig(config.incarnation(), "pg-node-2");
        createScopedToken(config.controlKey(), "consul-2").compose(token -> {
            var other = elector(otherConfig, endpoint(1), "consul-2", token);
            Future<Boolean> first = owner.createInitialIntent(initialIntent("pg-node-1"))
                .transform(result -> Future.succeededFuture(result.succeeded()));
            Future<Boolean> second = other.createInitialIntent(initialIntent("pg-node-2"))
                .transform(result -> Future.succeededFuture(result.succeeded()));
            return Future.all(first, second).map(results ->
                (results.<Boolean>resultAt(0) ? 1 : 0) + (results.<Boolean>resultAt(1) ? 1 : 0));
        }).onSuccess(winners -> context.verify(() -> {
            assertEquals(1, winners);
            context.completeNow();
        })).onFailure(context::failNow);
    }

    @Test void conflictingRevisionCannotOverwriteIntent(VertxTestContext context) {
        owner.createInitialIntent(initialIntent("pg-node-1")).compose(original -> {
            JsonObject changed = original.intent().put("phase", "FENCING");
            return owner.update(original, changed).compose(updated -> {
                assertTrue(updated.modifyIndex() > original.modifyIndex());
                return owner.update(original, original.intent()).transform(result -> {
                    assertTrue(result.failed());
                    assertInstanceOf(PgLeaseProtocolException.class, result.cause());
                    return owner.read();
                });
            });
        }).onSuccess(current -> context.verify(() -> {
            assertEquals("FENCING", current.orElseThrow().intent().getString("phase"));
            context.completeNow();
        })).onFailure(context::failNow);
    }

    @Test void serverRevisionConflictRollsBackUpdate(VertxTestContext context) {
        owner.createInitialIntent(initialIntent("pg-node-1")).compose(original ->
            admin.putAbs(endpoint(0) + "/v1/kv/" + config.controlKey() + "?acquire=" + original.sessionId())
                .putHeader("X-Consul-Token", ADMIN_TOKEN).timeout(1000)
                .sendBuffer(Buffer.buffer(original.intent().put("phase", "FENCING").encode()))
                .compose(response -> {
                    assertEquals(200, response.statusCode());
                    assertEquals("true", response.bodyAsString());
                    return owner.update(original, original.intent().put("phase", "PROMOTING"));
                }).transform(result -> {
                    assertTrue(result.failed());
                    assertInstanceOf(PgLeaseProtocolException.class, result.cause());
                    assertFalse(owner.hasFreshOwnership());
                    return owner.read();
                }).map(current -> {
                    assertEquals("FENCING", current.orElseThrow().intent().getString("phase"));
                    assertEquals(original.lockIndex(), current.orElseThrow().lockIndex());
                    return current;
                })).onSuccess(current -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void observedMissingRecordRetiresCachedOwnership(VertxTestContext context) {
        owner.createInitialIntent(initialIntent("pg-node-1")).compose(original ->
            admin.deleteAbs(endpoint(0) + "/v1/kv/" + config.controlKey())
                .putHeader("X-Consul-Token", ADMIN_TOKEN).timeout(1000).send()
                .compose(response -> {
                    assertEquals(200, response.statusCode());
                    assertEquals("true", response.bodyAsString());
                    return owner.read();
                })).onSuccess(current -> context.verify(() -> {
                    assertTrue(current.isEmpty());
                    assertFalse(owner.hasFreshOwnership());
                    context.completeNow();
                })).onFailure(context::failNow);
    }

    @Test void nodeScopedAclCannotWriteAnotherIncarnation(VertxTestContext context) {
        createScopedToken(config.controlKey(), "consul-1").compose(token ->
            admin.putAbs(endpoint(0) + "/v1/kv/" + quickConfig("forbidden", "pg-node-1").controlKey())
                .putHeader("X-Consul-Token", token).timeout(1000).sendBuffer(Buffer.buffer("forbidden")))
            .onSuccess(response -> context.verify(() -> {
                assertEquals(403, response.statusCode());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void anotherNodeCannotRenewOwnersSession(VertxTestContext context) {
        owner.createInitialIntent(initialIntent("pg-node-1")).compose(record ->
            createScopedToken(config.controlKey(), "consul-2").compose(token -> {
                var other = elector(quickConfig(config.incarnation(), "pg-node-2"), endpoint(1), "consul-2", token);
                return other.renew();
            })).onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgLeaseProtocolException.class, failure);
                assertTrue(owner.hasFreshOwnership());
                context.completeNow();
            })));
    }

    @Test void retiredOwnerCannotRegainAuthority(VertxTestContext context) {
        owner.createInitialIntent(initialIntent("pg-node-1")).compose(record -> {
            owner.retire();
            assertFalse(owner.hasFreshOwnership());
            return owner.renew();
        }).onComplete(context.failing(failure -> context.verify(() -> {
            assertInstanceOf(PgLeaseProtocolException.class, failure);
            assertFalse(owner.hasFreshOwnership());
            context.completeNow();
        })));
    }

    @Test void renewalPreservesGenerationAndPolicy(VertxTestContext context) {
        owner.createInitialIntent(initialIntent("pg-node-1")).compose(original -> owner.renew()
            .map(renewed -> {
                assertEquals(original, renewed);
                return renewed;
            })).onSuccess(renewed -> context.verify(() -> {
                assertTrue(owner.hasFreshOwnership());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void nodeScopedAclRejectsForeignSessionRenewalAndDestruction(VertxTestContext context) {
        owner.createInitialIntent(initialIntent("pg-node-1")).compose(record ->
            createScopedToken(config.controlKey(), "consul-2").compose(token ->
                admin.putAbs(endpoint(1) + "/v1/session/renew/" + record.sessionId())
                    .putHeader("X-Consul-Token", token).timeout(1000).send().compose(renewal -> {
                        assertEquals(403, renewal.statusCode());
                        return admin.putAbs(endpoint(1) + "/v1/session/destroy/" + record.sessionId())
                            .putHeader("X-Consul-Token", token).timeout(1000).send();
                    }))).onSuccess(destruction -> context.verify(() -> {
                assertEquals(403, destruction.statusCode());
                assertTrue(owner.hasFreshOwnership());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void restartedElectorCannotReinitialiseExistingHistory(VertxTestContext context) {
        owner.createInitialIntent(initialIntent("pg-node-1")).compose(original -> owner.close()
            .compose(ignored -> createScopedToken(config.controlKey(), "consul-1"))
            .compose(token -> {
                var restarted = elector(config, endpoint(0), "consul-1", token);
                return restarted.createInitialIntent(initialIntent("pg-node-1")).transform(result -> {
                    assertTrue(result.failed());
                    assertInstanceOf(PgLeaseProtocolException.class, result.cause());
                    assertFalse(restarted.hasFreshOwnership());
                    return restarted.read();
                });
            }).map(current -> {
                assertEquals(original, current.orElseThrow());
                return current;
            })).onSuccess(current -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void absenceWithoutKnownLeaderCannotAuthoriseBootstrap(VertxTestContext context) {
        faultPath = "/v1/kv/";
        proxyMode = "untrusted-absence";
        startProxy().compose(uri -> elector(config, uri, "consul-1", ADMIN_TOKEN).read())
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgLeaseProtocolException.class, failure);
                context.completeNow();
            })));
    }

    @Test void closeKeepsControlHistoryAndSession(VertxTestContext context) {
        owner.createInitialIntent(initialIntent("pg-node-1")).compose(record -> owner.close()
            .compose(ignored -> admin.getAbs(endpoint(0) + "/v1/kv/" + config.controlKey() + "?consistent")
                .putHeader("X-Consul-Token", ADMIN_TOKEN).send())
            .map(response -> {
                assertEquals(200, response.statusCode(), response.bodyAsString());
                assertEquals(record.sessionId(), response.bodyAsJsonArray().getJsonObject(0).getString("Session"));
                return response;
            })).onSuccess(response -> context.verify(() -> {
                assertFalse(owner.hasFreshOwnership());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void expiryRetainsPolicyHistoryAndWithdrawsOwnership(VertxTestContext context) {
        owner.createInitialIntent(initialIntent("pg-node-1")).compose(record ->
            waitForExpiry(record, System.nanoTime() + TimeUnit.SECONDS.toNanos(40)))
            .onSuccess(record -> context.verify(() -> {
                assertNull(record.sessionId());
                assertEquals(1L, record.intent().getJsonObject("pendingDurabilityPolicy").getLong("revision"));
                assertFalse(owner.hasFreshOwnership());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void namespaceIsolationPreservesIndependentOwnership(VertxTestContext context) {
        var otherConfig = quickConfig("other-" + UUID.randomUUID(), "pg-node-1");
        createScopedToken(otherConfig.controlKey(), "consul-1").compose(token -> {
            var other = elector(otherConfig, endpoint(0), "consul-1", token);
            return owner.createInitialIntent(initialIntent("pg-node-1"))
                .compose(first -> other.createInitialIntent(initialIntent("pg-node-1")))
                .map(second -> other);
        }).onSuccess(other -> context.verify(() -> {
            assertTrue(owner.hasFreshOwnership());
            assertTrue(other.hasFreshOwnership());
            context.completeNow();
        })).onFailure(context::failNow);
    }

    @Test void accessDeniedCannotCreateAuthority(VertxTestContext context) {
        var denied = elector(config, endpoint(0), "consul-1", UUID.randomUUID().toString());
        denied.createInitialIntent(initialIntent("pg-node-1"))
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgLeaseProtocolException.class, failure);
                assertFalse(denied.hasFreshOwnership());
                context.completeNow();
            })));
    }

    @Test void minorityCannotRenewOrReturnAuthoritativeRead(VertxTestContext context) {
        owner.createInitialIntent(initialIntent("pg-node-1")).compose(record -> pausePeers())
            .compose(ignored -> owner.renew().transform(result -> {
                assertTrue(result.failed());
                assertInstanceOf(PgLeaseProtocolException.class, result.cause());
                assertFalse(owner.hasFreshOwnership());
                return owner.read();
            })).onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgLeaseProtocolException.class, failure);
                context.completeNow();
            })));
    }

    @ParameterizedTest @ValueSource(strings = {"null", "{}", "[null]", "not-json"})
    void malformedReadCannotBecomeAuthority(String body, VertxTestContext context) {
        faultBody = body;
        faultPath = "/v1/kv/";
        proxyMode = "malformed";
        startProxy().compose(uri -> {
            var faulty = elector(config, uri, "consul-1", ADMIN_TOKEN);
            return faulty.read();
        }).onComplete(context.failing(failure -> context.verify(() -> {
            assertInstanceOf(PgLeaseProtocolException.class, failure);
            context.completeNow();
        })));
    }

    @Test void silentResponseHasBoundedFailure(VertxTestContext context) {
        faultPath = "/v1/kv/";
        proxyMode = "silent";
        long started = System.nanoTime();
        startProxy().compose(uri -> elector(config, uri, "consul-1", ADMIN_TOKEN).read())
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgLeaseProtocolException.class, failure);
                assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5));
                context.completeNow();
            })));
    }

    @Test void lostAcquisitionReplyRequiresObservation(VertxTestContext context) {
        faultPath = "/v1/txn";
        proxyMode = "drop";
        startProxy().compose(uri -> {
            var faulty = elector(config, uri, "consul-1", ADMIN_TOKEN);
            return faulty.createInitialIntent(initialIntent("pg-node-1")).transform(result -> {
                assertTrue(result.failed());
                assertInstanceOf(PgLeaseProtocolException.class, result.cause());
                assertFalse(faulty.hasFreshOwnership());
                return owner.read();
            });
        }).onSuccess(record -> context.verify(() -> {
            assertTrue(record.isPresent(), "Consul applied the acquisition despite the lost reply");
            assertNotNull(record.orElseThrow().sessionId());
            context.completeNow();
        })).onFailure(context::failNow);
    }

    @Test void lateRenewalCannotUndoRetirement(VertxTestContext context) {
        intercepted = Promise.promise();
        startProxy().compose(uri -> {
            var delayed = elector(config, uri, "consul-1", ADMIN_TOKEN);
            return delayed.createInitialIntent(initialIntent("pg-node-1")).compose(record -> {
                faultPath = "/v1/session/renew/";
                proxyMode = "delay";
                Future<PgControlRecord> renewal = delayed.renew()
                    .onFailure(failure -> logger.debug("Expected retired renewal", failure));
                return intercepted.future().compose(ignored -> {
                    delayed.retire();
                    delayedReply.run();
                    return renewal.transform(result -> {
                        assertTrue(result.failed());
                        assertInstanceOf(PgLeaseProtocolException.class, result.cause());
                        assertFalse(delayed.hasFreshOwnership());
                        return Future.<Void>succeededFuture();
                    });
                });
            });
        }).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void unsafeSessionConfigurationCannotGrantOwnership(VertxTestContext context) {
        faultPath = "/v1/session/info/";
        proxyMode = "unsafe-session";
        startProxy().compose(uri -> elector(config, uri, "consul-1", ADMIN_TOKEN)
            .createInitialIntent(initialIntent("pg-node-1")))
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgLeaseProtocolException.class, failure);
                context.completeNow();
            })));
    }

    @Test void failedConsistentReadRetiresCachedOwnership(VertxTestContext context) {
        startProxy().compose(uri -> {
            var faulty = elector(config, uri, "consul-1", ADMIN_TOKEN);
            return faulty.createInitialIntent(initialIntent("pg-node-1")).compose(record -> {
                faultPath = "/v1/kv/";
                faultBody = "null";
                proxyMode = "malformed";
                return faulty.read().transform(result -> {
                    assertTrue(result.failed());
                    assertInstanceOf(PgLeaseProtocolException.class, result.cause());
                    assertFalse(faulty.hasFreshOwnership());
                    return Future.<Void>succeededFuture();
                });
            });
        }).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void fractionalPolicyRevisionCannotCreateOwnership(VertxTestContext context) {
        JsonObject intent = initialIntent("pg-node-1");
        intent.getJsonObject("pendingDurabilityPolicy").put("revision", 1.5);
        owner.createInitialIntent(intent).onComplete(context.failing(failure -> context.verify(() -> {
            assertInstanceOf(PgLeaseProtocolException.class, failure);
            assertFalse(owner.hasFreshOwnership());
            context.completeNow();
        })));
    }

    @Test void timedOutRenewalCannotRegainAuthorityFromLateReply(VertxTestContext context) {
        intercepted = Promise.promise();
        startProxy().compose(uri -> {
            var delayed = elector(config, uri, "consul-1", ADMIN_TOKEN);
            return delayed.createInitialIntent(initialIntent("pg-node-1")).compose(original -> {
                faultPath = "/v1/session/renew/";
                proxyMode = "delay";
                Future<PgControlRecord> renewal = delayed.renew()
                    .onFailure(failure -> logger.debug("Expected timed-out renewal", failure));
                return intercepted.future().compose(ignored -> renewal.transform(result -> {
                    assertTrue(result.failed());
                    assertInstanceOf(PgLeaseProtocolException.class, result.cause());
                    assertFalse(delayed.hasFreshOwnership());
                    delayedReply.run();
                    return delayed.read();
                })).map(current -> {
                    assertEquals(original, current.orElseThrow());
                    assertFalse(delayed.hasFreshOwnership());
                    return current;
                });
            });
        }).onSuccess(current -> context.completeNow()).onFailure(context::failNow);
    }

    private PgPrimaryElector elector(PgNodeConfig node, URI uri, String consulNode, String token) {
        var elector = new PgPrimaryElector(vertx, node, uri, consulNode, token);
        electors.add(elector);
        return elector;
    }

    private static PgNodeConfig quickConfig(String incarnation, String node) {
        return new PgNodeConfig("lease-tests", incarnation, node, MEMBERS,
            Duration.ofSeconds(10), Duration.ofMillis(250), Duration.ofSeconds(1), Duration.ofSeconds(1));
    }

    private static JsonObject initialIntent(String writer) {
        return new JsonObject().put("writerNodeId", writer).put("phase", "WITHDRAWN")
            .put("operationId", UUID.randomUUID().toString())
            .put("pendingDurabilityPolicy", new JsonObject().put("revision", 1L)
                .put("requiredStandbyNodeIds", new JsonArray(MEMBERS.stream().filter(id -> !id.equals(writer)).toList())));
    }

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

    private Future<PgControlRecord> waitForExpiry(PgControlRecord original, long deadline) {
        return owner.read().compose(current -> {
            PgControlRecord record = current.orElseThrow();
            if (record.sessionId() == null) return Future.succeededFuture(record);
            assertEquals(original.sessionId(), record.sessionId());
            if (System.nanoTime() >= deadline) return Future.failedFuture(new AssertionError("Consul session did not expire"));
            return vertx.timer(100).compose(ignored -> waitForExpiry(original, deadline));
        });
    }

    private Future<Void> pausePeers() {
        return vertx.<Void>executeBlocking(() -> {
            for (int index : List.of(1, 2)) {
                var server = SERVERS.get(index);
                server.getDockerClient().pauseContainerCmd(server.getContainerId()).exec();
                paused.add(index);
            }
            return null;
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
            if (faultPath != null && request.path().startsWith(faultPath)) {
                if ("untrusted-absence".equals(proxyMode)) {
                    request.response().setStatusCode(404).putHeader("X-Consul-KnownLeader", "false").end()
                        .onFailure(failure -> logger.warn("Untrusted absence response failed", failure));
                    return;
                }
                if ("malformed".equals(proxyMode)) {
                    request.response().putHeader("X-Consul-KnownLeader", "true").end(faultBody)
                        .onFailure(failure -> logger.warn("Fault response failed", failure));
                    return;
                }
                if ("silent".equals(proxyMode)) return;
            }
            request.body().compose(body -> admin.requestAbs(HttpMethod.valueOf(request.method().name()),
                    endpoint(0) + request.uri()).putHeader("X-Consul-Token", ADMIN_TOKEN).timeout(1000).sendBuffer(body))
                .compose(response -> {
                    if (faultPath != null && request.path().startsWith(faultPath)) {
                        if ("drop".equals(proxyMode)) return Future.<Void>succeededFuture();
                        if ("delay".equals(proxyMode)) {
                            delayedReply = () -> request.response().setStatusCode(response.statusCode())
                                .end(response.body()).onFailure(failure -> logger.warn("Delayed response failed", failure));
                            intercepted.complete();
                            return Future.<Void>succeededFuture();
                        }
                        if ("unsafe-session".equals(proxyMode)) {
                            JsonArray sessions = response.bodyAsJsonArray();
                            sessions.getJsonObject(0).put("NodeChecks", new JsonArray().add("serfHealth"));
                            return request.response().setStatusCode(200).end(sessions.encode());
                        }
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
