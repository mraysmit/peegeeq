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

package dev.mars.peegeeq.integration;

import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.rest.PeeGeeQRestServer;
import dev.mars.peegeeq.rest.config.RestServerConfig;
import dev.mars.peegeeq.runtime.PeeGeeQRuntime;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cross-module critical-path smoke test for PeeGeeQ.
 *
 * <p>Verifies that all modules wire correctly end-to-end — without the React management
 * UI, Playwright, or any mocked dependencies.  Uses a real PostgreSQL container via
 * TestContainers and exercises the full REST API critical path:
 *
 * <ol>
 *   <li>Health endpoint reachable</li>
 *   <li>Create database setup with a queue</li>
 *   <li>Send three messages to the queue</li>
 *   <li>Retrieve queue details and verify message count</li>
 *   <li>List setups — setup appears in the list</li>
 *   <li>Delete setup — setup disappears from the list</li>
 * </ol>
 *
 * <p>Run independently of the UI from the repo root:
 * <pre>
 *   mvn test -pl peegeeq-integration-tests -Pintegration-tests -Dtest=PeeGeeQCriticalPathSmokeTest
 * </pre>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-06-15
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class PeeGeeQCriticalPathSmokeTest {

    private static final Logger logger = LoggerFactory.getLogger(PeeGeeQCriticalPathSmokeTest.class);
    private static final int TEST_PORT = 18200;
    private static final String QUEUE_NAME = "smoke_test_queue";
    private static final int MESSAGE_COUNT = 3;

    @Container
    static PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE);
        container.withDatabaseName("peegeeq_smoke_test");
        container.withUsername("peegeeq_test");
        container.withPassword("peegeeq_test");
        container.withSharedMemorySize(PostgreSQLTestConstants.DEFAULT_SHARED_MEMORY_SIZE);
        container.withReuse(false);
        return container;
    }

    private PeeGeeQRestServer server;
    private String deploymentId;
    private String setupId;
    private WebClient webClient;

    @BeforeAll
    void setupServer(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Setting up PeeGeeQ Critical Path Smoke Test ===");

        setupId = "smoke-" + System.currentTimeMillis();

        DatabaseSetupService setupService = PeeGeeQRuntime.createDatabaseSetupService();

        RestServerConfig testConfig = new RestServerConfig(
                TEST_PORT, RestServerConfig.MonitoringConfig.defaults(), List.of("*"));
        server = new PeeGeeQRestServer(testConfig, setupService);
        vertx.deployVerticle(server)
                .onSuccess(id -> {
                    deploymentId = id;
                    logger.info("REST server deployed with ID: {}", deploymentId);
                    webClient = WebClient.create(vertx);
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
    }

    @AfterAll
    void tearDown(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Tearing down PeeGeeQ Critical Path Smoke Test ===");

        Future<Void> undeploy = deploymentId != null
                ? vertx.undeploy(deploymentId)
                : Future.succeededFuture();

        undeploy.onSuccess(v -> testContext.completeNow()).onFailure(testContext::failNow);
    }

    // ── Test 1: health endpoint reachable ────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("Health endpoint responds with 200")
    void testHealthEndpointResponds(VertxTestContext testContext) {
        logger.info("=== Smoke 1: health endpoint ===");

        webClient.get(TEST_PORT, "localhost", "/health")
                .timeout(10_000)
                .send()
                .onSuccess(response -> {
                    testContext.verify(() -> {
                        assertEquals(200, response.statusCode(), "Health endpoint must return 200");
                        logger.info("Health response: {}", response.bodyAsString());
                    });
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
    }

    // ── Test 2: create a setup with one queue ────────────────────────────────

    @Test
    @Order(2)
    @DisplayName("Create database setup with queue returns 201 ACTIVE")
    void testCreateSetupWithQueue(VertxTestContext testContext) {
        logger.info("=== Smoke 2: create setup {} with queue {} ===", setupId, QUEUE_NAME);

        JsonObject setupRequest = new JsonObject()
                .put("setupId", setupId)
                .put("databaseConfig", new JsonObject()
                        .put("host", postgres.getHost())
                        .put("port", postgres.getFirstMappedPort())
                        .put("databaseName", "smoke_db_" + System.currentTimeMillis())
                        .put("username", postgres.getUsername())
                        .put("password", postgres.getPassword())
                        .put("schema", "public")
                        .put("templateDatabase", "template0")
                        .put("encoding", "UTF8"))
                .put("queues", new JsonArray()
                        .add(new JsonObject()
                                .put("queueName", QUEUE_NAME)
                                .put("maxRetries", 3)
                                .put("visibilityTimeout", 30)))
                .put("eventStores", new JsonArray())
                .put("additionalProperties", new JsonObject());

        webClient.post(TEST_PORT, "localhost", "/api/v1/database-setup/create")
                .putHeader("content-type", "application/json")
                .timeout(60_000)
                .sendJsonObject(setupRequest)
                .onSuccess(response -> {
                    testContext.verify(() -> {
                        logger.info("Create setup response: {} - {}", response.statusCode(), response.bodyAsString());
                        assertTrue(response.statusCode() == 200 || response.statusCode() == 201,
                                "Setup creation must return 200 or 201; got: " + response.statusCode());
                        JsonObject body = response.bodyAsJsonObject();
                        assertNotNull(body, "Response body must be a JSON object");
                        assertEquals("ACTIVE", body.getString("status"),
                                "Setup must be ACTIVE after creation");
                        logger.info("Setup {} created with status ACTIVE", setupId);
                    });
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
    }

    // ── Test 3: send three messages to the queue ─────────────────────────────

    @Test
    @Order(3)
    @DisplayName("Send 3 messages to queue — all return 200 with messageId")
    void testSendMessagesToQueue(VertxTestContext testContext) {
        logger.info("=== Smoke 3: send {} messages to {}/{} ===", MESSAGE_COUNT, setupId, QUEUE_NAME);

        AtomicInteger remaining = new AtomicInteger(MESSAGE_COUNT);
        String path = "/api/v1/queues/" + setupId + "/" + QUEUE_NAME + "/messages";

        for (int i = 1; i <= MESSAGE_COUNT; i++) {
            final int seq = i;
            JsonObject body = new JsonObject()
                    .put("payload", new JsonObject()
                            .put("seq", seq)
                            .put("source", "critical-path-smoke"))
                    .put("headers", new JsonObject()
                            .put("test", "true")
                            .put("seq", String.valueOf(seq)));

            webClient.post(TEST_PORT, "localhost", path)
                    .putHeader("content-type", "application/json")
                    .timeout(10_000)
                    .sendJsonObject(body)
                    .onSuccess(response -> {
                        testContext.verify(() -> {
                            assertEquals(200, response.statusCode(),
                                    "Message send must return 200; body: " + response.bodyAsString());
                            assertNotNull(response.bodyAsJsonObject().getString("messageId"),
                                    "Response must include messageId");
                            logger.info("Message {} sent, messageId={}", seq,
                                    response.bodyAsJsonObject().getString("messageId"));
                        });
                        if (remaining.decrementAndGet() == 0) {
                            testContext.completeNow();
                        }
                    })
                    .onFailure(testContext::failNow);
        }
    }

    // ── Test 4: queue details reflect pending messages ────────────────────────

    @Test
    @Order(4)
    @DisplayName("Queue details show at least 3 pending messages")
    void testQueueDetailsShowPendingMessages(VertxTestContext testContext) {
        logger.info("=== Smoke 4: verify queue {} has >= {} pending messages ===",
                QUEUE_NAME, MESSAGE_COUNT);

        webClient.get(TEST_PORT, "localhost",
                        "/api/v1/queues/" + setupId + "/" + QUEUE_NAME)
                .timeout(10_000)
                .send()
                .onSuccess(response -> {
                    testContext.verify(() -> {
                        assertEquals(200, response.statusCode(),
                                "Queue details must return 200; body: " + response.bodyAsString());

                        JsonObject body = response.bodyAsJsonObject();
                        logger.info("Queue details: {}", body.encodePrettily());

                        // Response shape: top-level "messages" or statistics."totalMessages"
                        int messageCount = body.getInteger("messages",
                                body.getJsonObject("statistics", new JsonObject())
                                        .getInteger("totalMessages", 0));

                        assertTrue(messageCount >= MESSAGE_COUNT,
                                "Queue must report at least " + MESSAGE_COUNT +
                                " pending messages but found " + messageCount);
                    });
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
    }

    // ── Test 5: list setups contains our setup ────────────────────────────────

    @Test
    @Order(5)
    @DisplayName("List setups includes the created setup")
    void testListSetupsContainsCreatedSetup(VertxTestContext testContext) {
        logger.info("=== Smoke 5: list setups contains {} ===", setupId);

        webClient.get(TEST_PORT, "localhost", "/api/v1/setups")
                .timeout(10_000)
                .send()
                .onSuccess(response -> {
                    testContext.verify(() -> {
                        assertEquals(200, response.statusCode(),
                                "List setups must return 200; body: " + response.bodyAsString());

                        JsonObject body = response.bodyAsJsonObject();
                        assertNotNull(body, "Response must be a JSON object");
                        assertTrue(body.containsKey("setupIds"), "Response must contain setupIds");

                        JsonArray setupIds = body.getJsonArray("setupIds");
                        assertTrue(setupIds.contains(setupId),
                                "List setups must contain " + setupId + "; got: " + setupIds.encode());
                        logger.info("Setup {} found in list (count={})", setupId, body.getInteger("count"));
                    });
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
    }

    // ── Test 6: delete setup — it disappears from the list ───────────────────

    @Test
    @Order(6)
    @DisplayName("Delete setup returns 204 and setup no longer appears in list")
    void testDeleteSetupAndVerifyRemoval(VertxTestContext testContext) {
        logger.info("=== Smoke 6: delete setup {} ===", setupId);

        webClient.delete(TEST_PORT, "localhost", "/api/v1/setups/" + setupId)
                .timeout(30_000)
                .send()
                .compose(deleteResp -> {
                    testContext.verify(() -> {
                        assertEquals(204, deleteResp.statusCode(),
                                "Delete must return 204; got " + deleteResp.statusCode());
                        logger.info("Setup {} deleted (204)", setupId);
                    });

                    return webClient.get(TEST_PORT, "localhost", "/api/v1/setups")
                            .timeout(10_000)
                            .send();
                })
                .onSuccess(listResp -> {
                    testContext.verify(() -> {
                        assertEquals(200, listResp.statusCode());
                        JsonArray setupIds = listResp.bodyAsJsonObject().getJsonArray("setupIds");
                        assertFalse(setupIds.contains(setupId),
                                "Deleted setup " + setupId + " must not appear in list; got: " + setupIds.encode());
                        logger.info("Setup {} confirmed absent from list", setupId);
                    });
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
    }
}
