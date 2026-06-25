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

package dev.mars.peegeeq.rest.handlers;

import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.rest.PeeGeeQRestServer;
import dev.mars.peegeeq.rest.config.RestServerConfig;
import dev.mars.peegeeq.runtime.PeeGeeQRuntime;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for Phase 9: live queue message count via the queue-updates SSE.
 *
 * <p>{@code QueueHandler.sendMessage}/{@code sendMessages} and {@code ManagementApiHandler.purgeQueue}
 * publish a {@code queue-changed} event to the per-setup event-bus address on success;
 * {@code ServerSentEventsHandler.handleQueueUpdates} forwards it to {@code GET /api/v1/sse/queues/:setupId}
 * so the management UI refreshes the live message count without waiting for its 30s poll.</p>
 *
 * <p>Verified against a real PostgreSQL via TestContainers: an SSE client connected to the
 * queue-updates stream receives a {@code queue-changed} event after a message send and after a purge.
 * The mutation is triggered exactly when the SSE {@code connected} handshake arrives — no timer-based
 * readiness guard — mirroring {@code SSEQueueUpdatesIntegrationTest}.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-06-25
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QueueHandlerIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(QueueHandlerIntegrationTest.class);
    private static final int TEST_PORT = 18115;

    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private String deploymentId;
    private WebClient webClient;
    private HttpClient httpClient;
    private String testSetupId;

    @BeforeAll
    void setUpAll(Vertx vertx, VertxTestContext testContext) {
        testSetupId = "qh-sse-" + System.currentTimeMillis();
        webClient = WebClient.create(vertx);
        httpClient = vertx.createHttpClient();

        DatabaseSetupService setupService = PeeGeeQRuntime.createDatabaseSetupService();
        RestServerConfig testConfig = new RestServerConfig(
                TEST_PORT, RestServerConfig.MonitoringConfig.defaults(), java.util.List.of("*"));

        vertx.deployVerticle(new PeeGeeQRestServer(testConfig, setupService))
            .compose(id -> {
                deploymentId = id;
                return webClient.post(TEST_PORT, "localhost", "/api/v1/database-setup/create")
                    .putHeader("content-type", "application/json")
                    .timeout(60000)
                    .sendJsonObject(new JsonObject()
                        .put("setupId", testSetupId)
                        .put("databaseConfig", new JsonObject()
                            .put("host", postgres.getHost())
                            .put("port", postgres.getFirstMappedPort())
                            .put("databaseName", "qh_sse_db_" + System.currentTimeMillis())
                            .put("username", postgres.getUsername())
                            .put("password", postgres.getPassword())
                            .put("schema", PostgreSQLTestConstants.TEST_SCHEMA)
                            .put("templateDatabase", "template0")
                            .put("encoding", "UTF8"))
                        .put("queues", new JsonArray())
                        .put("eventStores", new JsonArray()))
                    .compose(r -> (r.statusCode() == 201 || r.statusCode() == 200)
                        ? Future.succeededFuture()
                        : Future.failedFuture("Setup failed: " + r.statusCode() + " " + r.bodyAsString()));
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @AfterAll
    void tearDownAll(Vertx vertx, VertxTestContext testContext) {
        if (httpClient != null) httpClient.close();
        if (webClient != null) webClient.close();
        if (deploymentId != null) {
            vertx.undeploy(deploymentId)
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }
    }

    // ── helpers (mirror SSEQueueUpdatesIntegrationTest) ─────────────────────────

    private Future<Void> createQueue(String queueName) {
        return webClient.post(TEST_PORT, "localhost", "/api/v1/management/queues")
            .putHeader("content-type", "application/json")
            .timeout(10000)
            .sendJsonObject(new JsonObject().put("setupId", testSetupId).put("name", queueName).put("type", "native"))
            .compose(r -> r.statusCode() == 201 || r.statusCode() == 200
                ? Future.succeededFuture()
                : Future.failedFuture("Create queue failed: " + r.statusCode() + " " + r.bodyAsString()));
    }

    private Future<Void> publishMessage(String queueName) {
        return webClient.post(TEST_PORT, "localhost", "/api/v1/queues/" + testSetupId + "/" + queueName + "/messages")
            .putHeader("content-type", "application/json")
            .timeout(10000)
            .sendJsonObject(new JsonObject().put("payload", new JsonObject().put("test", true)).put("headers", new JsonObject()))
            .compose(r -> r.statusCode() == 200 || r.statusCode() == 201
                ? Future.succeededFuture()
                : Future.failedFuture("Publish message failed: " + r.statusCode() + " " + r.bodyAsString()));
    }

    private Future<Void> purgeQueue(String queueName) {
        return webClient.post(TEST_PORT, "localhost", "/api/v1/queues/" + testSetupId + "/" + queueName + "/purge")
            .timeout(10000)
            .send()
            .compose(r -> r.statusCode() == 200
                ? Future.succeededFuture()
                : Future.failedFuture("Purge failed: " + r.statusCode() + " " + r.bodyAsString()));
    }

    private String sseUrl() {
        return "/api/v1/sse/queues/" + testSetupId;
    }

    // ── tests ───────────────────────────────────────────────────────────────────

    /**
     * Publishing a message must push a {@code queue-changed} event so the queue-list live count
     * refreshes. The queue is created before the SSE connects; the message is sent the moment the
     * SSE {@code connected} handshake arrives.
     */
    @Test
    @Order(1)
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testMessageSendTriggersQueueChanged(VertxTestContext testContext) {
        String queueName = "qh_msg_send_" + System.currentTimeMillis();
        AtomicBoolean mutationTriggered = new AtomicBoolean(false);

        createQueue(queueName)
            .compose(v -> httpClient.request(HttpMethod.GET, TEST_PORT, "localhost", sseUrl()))
            .compose(HttpClientRequest::send)
            .onSuccess(response -> response.handler(buffer -> {
                String data = buffer.toString();

                if (data.contains("event: connected") && !mutationTriggered.getAndSet(true)) {
                    publishMessage(queueName).onFailure(testContext::failNow);
                }

                if (data.contains("event: queue-changed") && data.contains(queueName)) {
                    testContext.verify(() -> {
                        assertTrue(data.contains("\"setupId\":\"" + testSetupId + "\""));
                        assertTrue(data.contains("\"queueName\":\"" + queueName + "\""));
                    });
                    response.request().connection().close();
                    testContext.completeNow();
                }
            }))
            .onFailure(testContext::failNow);
    }

    /**
     * Purging a queue must push a {@code queue-changed} event (event=QUEUE_PURGED).
     */
    @Test
    @Order(2)
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testPurgeTriggersQueueChanged(VertxTestContext testContext) {
        String queueName = "qh_purge_" + System.currentTimeMillis();
        AtomicBoolean mutationTriggered = new AtomicBoolean(false);

        createQueue(queueName)
            .compose(v -> publishMessage(queueName))
            .compose(v -> httpClient.request(HttpMethod.GET, TEST_PORT, "localhost", sseUrl()))
            .compose(HttpClientRequest::send)
            .onSuccess(response -> response.handler(buffer -> {
                String data = buffer.toString();

                if (data.contains("event: connected") && !mutationTriggered.getAndSet(true)) {
                    purgeQueue(queueName).onFailure(testContext::failNow);
                }

                if (data.contains("event: queue-changed") && data.contains("QUEUE_PURGED") && data.contains(queueName)) {
                    testContext.verify(() -> {
                        assertTrue(data.contains("\"setupId\":\"" + testSetupId + "\""));
                        assertTrue(data.contains("\"queueName\":\"" + queueName + "\""));
                    });
                    response.request().connection().close();
                    testContext.completeNow();
                }
            }))
            .onFailure(testContext::failNow);
    }
}
