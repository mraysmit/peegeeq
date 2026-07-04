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
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
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
 * Demonstration / integration test for the NON-DESTRUCTIVE live message stream
 * ({@code GET /api/v1/queues/{setupId}/{queueName}/messages/stream}, Phase 12.4), run against
 * <strong>both</strong> queue implementations — {@code native} (NOTIFY-driven tail) and
 * {@code outbox} (poll-driven tail).
 *
 * <p>For each implementation it produces 10 messages over the REST API, observes them streaming
 * back over an SSE connection, then proves the stream did not consume them (all 10 remain
 * browsable). Each producer send logs {@code PRODUCER[type]: …}, each SSE delivery logs
 * {@code SSE-CONSUMER[type]: …}, and the server handler logs each push. Run with {@code Tee-Object}
 * and inspect the log file to see the full producer→server→consumer flow for native and outbox.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-06-18
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SseMessageStreamDemoIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(SseMessageStreamDemoIntegrationTest.class);
    private static final int TEST_PORT = 18097;
    private static final int MESSAGE_COUNT = 10;

    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private WebClient client;
    private String deploymentId;

    @BeforeAll
    void setUp(Vertx vertx, VertxTestContext testContext) {
        client = WebClient.create(vertx);
        DatabaseSetupService setupService = PeeGeeQRuntime.createDatabaseSetupService();
        RestServerConfig testConfig = new RestServerConfig(
                TEST_PORT, RestServerConfig.MonitoringConfig.defaults(), List.of("*"));
        vertx.deployVerticle(new PeeGeeQRestServer(testConfig, setupService))
                .onSuccess(id -> {
                    deploymentId = id;
                    logger.info("REST server deployed on port {} for SSE stream demo", TEST_PORT);
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
    }

    @AfterAll
    void tearDown(Vertx vertx, VertxTestContext testContext) {
        if (client != null) {
            client.close();
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
    void tenMessages_streamOverSse_nativeQueue(Vertx vertx, VertxTestContext ctx) {
        runTenMessageStreamDemo("native", vertx, ctx);
    }

    @Test
    @Timeout(value = 60, timeUnit = TimeUnit.SECONDS)
    void tenMessages_streamOverSse_outboxQueue(Vertx vertx, VertxTestContext ctx) {
        runTenMessageStreamDemo("outbox", vertx, ctx);
    }

    /**
     * Regression lock for the multi-setup native LISTEN/NOTIFY bug (Phase F1 fallout, fixed
     * 2026-06-27). A SECOND native setup on the same service must also stream over SSE. When the
     * per-setup managers shared one Vert.x (F1), the second setup's dedicated LISTEN connection
     * silently received no NOTIFYs and this timed out; with a per-setup Vert.x it delivers. Runs
     * alongside {@link #tenMessages_streamOverSse_nativeQueue} — two independent native SSE tails
     * (each its own setup/database) coexisting on one service.
     */
    @Test
    @Timeout(value = 60, timeUnit = TimeUnit.SECONDS)
    void secondNativeSetup_alsoStreamsOverSse(Vertx vertx, VertxTestContext ctx) {
        runTenMessageStreamDemo("native", vertx, ctx);
    }

    /**
     * Produces {@link #MESSAGE_COUNT} messages and asserts they all stream back over SSE and remain
     * browsable, for a queue of the given implementation type ({@code native} or {@code outbox}).
     */
    private void runTenMessageStreamDemo(String implementationType, Vertx vertx, VertxTestContext ctx) {
        String setupId = "sse-demo-" + implementationType + "-" + System.currentTimeMillis();
        String queueName = "sse_demo_" + implementationType + "_queue";
        String runId = implementationType + "-" + System.currentTimeMillis();

        Set<Integer> received = ConcurrentHashMap.newKeySet();
        AtomicBoolean produceStarted = new AtomicBoolean(false);
        AtomicBoolean verifyStarted = new AtomicBoolean(false);
        AtomicBoolean completed = new AtomicBoolean(false);
        StringBuilder sseBuffer = new StringBuilder();
        HttpClient sseClient = vertx.createHttpClient();

        logger.info("=== SSE stream demo START for '{}' queue (setup={}, queue={}) ===",
                implementationType, setupId, queueName);

        createSetupWithQueue(setupId, queueName, implementationType)
                .compose(v -> sseClient.request(HttpMethod.GET, TEST_PORT, "localhost",
                        "/api/v1/queues/" + setupId + "/" + queueName + "/messages/stream"))
                .compose(request -> {
                    request.putHeader("Accept", "text/event-stream");
                    return request.send();
                })
                .onSuccess(response -> {
                    logger.info("SSE-CONSUMER[{}]: stream connection established (HTTP {})",
                            implementationType, response.statusCode());
                    response.handler(buffer -> {
                        sseBuffer.append(buffer.toString());
                        String acc = sseBuffer.toString();

                        // The 'subscribed' event means the tail is live (FROM_NOW watermark seeded),
                        // so it is now safe to publish and be sure the tail will observe the messages.
                        if (acc.contains("event: subscribed") && produceStarted.compareAndSet(false, true)) {
                            logger.info("SSE-CONSUMER[{}]: tail subscribed — publishing {} messages",
                                    implementationType, MESSAGE_COUNT);
                            produceMessages(setupId, queueName, runId, implementationType).onFailure(ctx::failNow);
                        }

                        for (int i = 1; i <= MESSAGE_COUNT; i++) {
                            if (!received.contains(i) && acc.contains(marker(runId, i))) {
                                received.add(i);
                                logger.info("SSE-CONSUMER[{}]: received message #{} (marker={}) [{}/{}]",
                                        implementationType, i, marker(runId, i), received.size(), MESSAGE_COUNT);
                            }
                        }

                        if (received.size() == MESSAGE_COUNT && verifyStarted.compareAndSet(false, true)) {
                            logger.info("SSE-CONSUMER[{}]: all {} messages received over SSE — verifying non-destructive",
                                    implementationType, MESSAGE_COUNT);
                            verifyStillBrowsable(setupId, queueName, runId, implementationType, sseClient, completed, ctx);
                        }
                    });
                    // After the assertions complete we intentionally close the SSE client, which trips
                    // this handler with HttpClosedException. That post-completion close is expected
                    // teardown, not a failure — only fail for genuine mid-stream errors.
                    response.exceptionHandler(err -> {
                        if (completed.get()) {
                            logger.debug("SSE-CONSUMER[{}]: stream closed after completion (ignored): {}",
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
            HttpClient sseClient, AtomicBoolean completed, VertxTestContext ctx) {
        client.get(TEST_PORT, "localhost",
                        "/api/v1/queues/" + setupId + "/" + queueName + "/messages?limit=50")
                .send()
                .onSuccess(resp -> ctx.verify(() -> {
                    String body = resp.bodyAsString();
                    for (int i = 1; i <= MESSAGE_COUNT; i++) {
                        assertTrue(body != null && body.contains(marker(runId, i)),
                                "browse must still show message " + i + " for '" + type
                                        + "' — the SSE stream must NOT consume");
                    }
                    logger.info("NON-DESTRUCTIVE verified[{}]: all {} messages still browsable after streaming",
                            type, MESSAGE_COUNT);
                    // Mark complete BEFORE closing so the guarded exceptionHandler ignores the
                    // HttpClosedException this close triggers on the still-open SSE stream.
                    completed.set(true);
                    sseClient.close();
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
                        .put("databaseName", "sse_demo_" + implementationType + "_" + System.currentTimeMillis())
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
        return "sse-demo-" + runId + "-" + seq;
    }
}
