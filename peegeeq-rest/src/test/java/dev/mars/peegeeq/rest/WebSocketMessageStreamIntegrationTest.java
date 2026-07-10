package dev.mars.peegeeq.rest;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.rest.config.RestServerConfig;
import dev.mars.peegeeq.runtime.PeeGeeQRuntime;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the NON-DESTRUCTIVE live message stream over <strong>WebSocket</strong>
 * ({@code WS /ws/queues/{setupId}/{queueName}} → {@code WebSocketHandler.startMessageStreaming},
 * Phase 12.6 — WebSocket parity with the SSE {@code /messages/stream}). The WS handler observes
 * via {@code QueueBrowser.tail()} (LISTEN/NOTIFY + browse → plain SELECT); it never
 * {@code createConsumer()}/{@code subscribe()}s, so it cannot steal messages from real consumers.
 *
 * <p>The WebSocket counterpart of {@link SseMessageStreamDemoIntegrationTest}: for both queue
 * implementations ({@code native} NOTIFY-driven tail and {@code outbox} poll-driven tail) it opens
 * a WS connection, waits for the {@code subscribed} frame (tail live, FROM_NOW watermark seeded),
 * produces 10 messages over REST, observes all 10 stream back as {@code data} frames, then proves
 * the stream did not consume them (all 10 remain browsable).
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-07-09
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebSocketMessageStreamIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketMessageStreamIntegrationTest.class);
    private static final int TEST_PORT = 18099;
    private static final int MESSAGE_COUNT = 10;

    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private WebClient client;
    private WebSocketClient wsClient;
    private String deploymentId;

    @BeforeAll
    void setUp(Vertx vertx, VertxTestContext testContext) {
        client = WebClient.create(vertx);
        wsClient = vertx.createWebSocketClient();
        DatabaseSetupService setupService = PeeGeeQRuntime.createDatabaseSetupService();
        RestServerConfig testConfig = new RestServerConfig(
                TEST_PORT, RestServerConfig.MonitoringConfig.defaults(), List.of("*"));
        vertx.deployVerticle(new PeeGeeQRestServer(testConfig, setupService))
                .onSuccess(id -> {
                    deploymentId = id;
                    logger.info("REST server deployed on port {} for WS stream test", TEST_PORT);
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
    }

    @AfterAll
    void tearDown(Vertx vertx, VertxTestContext testContext) {
        if (client != null) {
            client.close();
        }
        if (wsClient != null) {
            wsClient.close();
        }
        if (deploymentId != null) {
            vertx.undeploy(deploymentId)
                    .onSuccess(v -> testContext.completeNow())
                    .onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }
    }

    @Test
    @Timeout(value = 60, timeUnit = TimeUnit.SECONDS)
    void tenMessages_streamOverWebSocket_nativeQueue(Vertx vertx, VertxTestContext ctx) {
        runTenMessageWsStreamDemo("native", vertx, ctx);
    }

    @Test
    @Timeout(value = 60, timeUnit = TimeUnit.SECONDS)
    void tenMessages_streamOverWebSocket_outboxQueue(Vertx vertx, VertxTestContext ctx) {
        runTenMessageWsStreamDemo("outbox", vertx, ctx);
    }

    /**
     * Produces {@link #MESSAGE_COUNT} messages and asserts they all stream back over the WebSocket
     * and remain browsable (non-destructive), for a queue of the given implementation type.
     */
    private void runTenMessageWsStreamDemo(String implementationType, Vertx vertx, VertxTestContext ctx) {
        String setupId = "ws-stream-" + implementationType + "-" + System.currentTimeMillis();
        String queueName = "ws_stream_" + implementationType + "_queue";
        String runId = implementationType + "-" + System.currentTimeMillis();

        Set<Integer> received = ConcurrentHashMap.newKeySet();
        AtomicBoolean produceStarted = new AtomicBoolean(false);
        AtomicBoolean verifyStarted = new AtomicBoolean(false);
        AtomicBoolean completed = new AtomicBoolean(false);

        WebSocketConnectOptions options = new WebSocketConnectOptions()
                .setHost("localhost")
                .setPort(TEST_PORT)
                .setURI("/ws/queues/" + setupId + "/" + queueName);

        logger.info("=== WS stream test START for '{}' queue (setup={}, queue={}) ===",
                implementationType, setupId, queueName);

        createSetupWithQueue(setupId, queueName, implementationType)
                .compose(v -> wsClient.connect(options))
                .onSuccess(ws -> {
                    logger.info("WS-CONSUMER[{}]: connection established", implementationType);
                    ws.textMessageHandler(frame -> {
                        // The 'subscribed' frame means the non-destructive tail is live (FROM_NOW watermark
                        // seeded), so it is now safe to publish and be sure the tail will observe them.
                        if (frame.contains("\"type\":\"subscribed\"") && produceStarted.compareAndSet(false, true)) {
                            logger.info("WS-CONSUMER[{}]: tail subscribed — publishing {} messages",
                                    implementationType, MESSAGE_COUNT);
                            produceMessages(setupId, queueName, runId, implementationType).onFailure(ctx::failNow);
                            return;
                        }

                        for (int i = 1; i <= MESSAGE_COUNT; i++) {
                            if (!received.contains(i) && frame.contains(marker(runId, i))) {
                                received.add(i);
                                logger.info("WS-CONSUMER[{}]: received message #{} (marker={}) [{}/{}]",
                                        implementationType, i, marker(runId, i), received.size(), MESSAGE_COUNT);
                            }
                        }

                        if (received.size() == MESSAGE_COUNT && verifyStarted.compareAndSet(false, true)) {
                            logger.info("WS-CONSUMER[{}]: all {} messages received over WS — verifying non-destructive",
                                    implementationType, MESSAGE_COUNT);
                            verifyStillBrowsable(setupId, queueName, runId, implementationType, ws, completed, ctx);
                        }
                    });

                    // Closing the WS after completion may trip this handler; that post-completion close is
                    // expected teardown, not a failure — only fail for genuine mid-stream errors.
                    ws.exceptionHandler(err -> {
                        if (completed.get()) {
                            logger.debug("WS-CONSUMER[{}]: stream closed after completion (ignored): {}",
                                    implementationType, err.getMessage());
                        } else {
                            ctx.failNow(err);
                        }
                    });
                })
                .onFailure(ctx::failNow);
    }

    /** Publishes MESSAGE_COUNT messages sequentially via the REST API, logging each send. */
    private Future<Void> produceMessages(String setupId, String queueName, String runId, String type) {
        String sendUrl = "/api/v1/queues/" + setupId + "/" + queueName + "/messages";
        Future<Void> chain = Future.succeededFuture();
        for (int i = 1; i <= MESSAGE_COUNT; i++) {
            final int seq = i;
            final String marker = marker(runId, seq);
            chain = chain.compose(v -> {
                logger.info("PRODUCER[{}]: sending message #{} (marker={})", type, seq, marker);
                JsonObject body = new JsonObject()
                        .put("payload", new JsonObject().put("marker", marker).put("seq", seq));
                return client.post(TEST_PORT, "localhost", sendUrl)
                        .putHeader("content-type", "application/json")
                        .sendJsonObject(body)
                        .map(resp -> {
                            logger.info("PRODUCER[{}]: message #{} accepted (HTTP {})", type, seq, resp.statusCode());
                            return (Void) null;
                        });
            });
        }
        return chain;
    }

    /** Proves the stream was non-destructive: a browse must still return all 10 messages. */
    private void verifyStillBrowsable(String setupId, String queueName, String runId, String type,
            WebSocket ws, AtomicBoolean completed, VertxTestContext ctx) {
        client.get(TEST_PORT, "localhost",
                        "/api/v1/queues/" + setupId + "/" + queueName + "/messages?limit=50")
                .send()
                .onSuccess(resp -> ctx.verify(() -> {
                    String body = resp.bodyAsString();
                    for (int i = 1; i <= MESSAGE_COUNT; i++) {
                        assertTrue(body != null && body.contains(marker(runId, i)),
                                "browse must still show message " + i + " for '" + type
                                        + "' — the WS stream must NOT consume");
                    }
                    logger.info("NON-DESTRUCTIVE verified[{}]: all {} messages still browsable after WS streaming",
                            type, MESSAGE_COUNT);
                    // Mark complete BEFORE closing so the guarded exceptionHandler ignores any close error.
                    completed.set(true);
                    ws.close();
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    private Future<Void> createSetupWithQueue(String setupId, String queueName, String implementationType) {
        JsonObject setupRequest = new JsonObject()
                .put("setupId", setupId)
                .put("databaseConfig", new JsonObject()
                        .put("host", postgres.getHost())
                        .put("port", postgres.getFirstMappedPort())
                        .put("databaseName", "ws_stream_" + implementationType + "_" + System.currentTimeMillis())
                        .put("username", postgres.getUsername())
                        .put("password", postgres.getPassword())
                        .put("schema", PostgreSQLTestConstants.TEST_SCHEMA)
                        .put("templateDatabase", "template0")
                        .put("encoding", "UTF8"))
                .put("queues", new JsonArray()
                        .add(new JsonObject()
                                .put("queueName", queueName)
                                .put("implementationType", implementationType)
                                .put("maxRetries", 3)
                                .put("visibilityTimeoutSeconds", 30)))
                .put("eventStores", new JsonArray())
                .put("additionalProperties", new JsonObject());

        logger.info("Creating setup '{}' with '{}' queue '{}'", setupId, implementationType, queueName);
        return client.post(TEST_PORT, "localhost", "/api/v1/database-setup/create")
                .putHeader("content-type", "application/json")
                .timeout(30000)
                .sendJsonObject(setupRequest)
                .map(resp -> {
                    logger.info("Setup '{}' created (HTTP {})", setupId, resp.statusCode());
                    return (Void) null;
                });
    }

    private static String marker(String runId, int seq) {
        return "ws-stream-" + runId + "-" + seq;
    }
}
