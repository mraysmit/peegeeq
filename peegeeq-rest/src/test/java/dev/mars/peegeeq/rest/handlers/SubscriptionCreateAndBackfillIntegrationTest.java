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
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Subscribe REST Endpoint (H3) and Backfill REST Endpoints (H4).
 *
 * <p>Tests the following endpoints:</p>
 * <ul>
 *   <li>POST /api/v1/setups/:setupId/subscriptions/:topic - Create subscription (H3)</li>
 *   <li>POST /api/v1/setups/:setupId/subscriptions/:topic/:groupName/backfill - Start backfill (H4)</li>
 *   <li>GET /api/v1/setups/:setupId/subscriptions/:topic/:groupName/backfill - Get backfill progress (H4)</li>
 *   <li>DELETE /api/v1/setups/:setupId/subscriptions/:topic/:groupName/backfill - Cancel backfill (H4)</li>
 * </ul>
 *
 * <p>Classification: INTEGRATION TEST</p>
 * <ul>
 *   <li>Uses real PostgreSQL database (TestContainers)</li>
 *   <li>Uses real Vert.x HTTP server</li>
 *   <li>Tests end-to-end REST API flow for subscription creation and backfill</li>
 * </ul>
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class SubscriptionCreateAndBackfillIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(SubscriptionCreateAndBackfillIntegrationTest.class);
    private static final int TEST_PORT = 18103;
    private static final String TOPIC_NAME = "create_backfill_test_topic";
    private static final String GROUP_NAME = "test-create-group";
    private static final String BACKFILL_GROUP = "test-backfill-group";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
            .withDatabaseName("peegeeq_create_backfill_test")
            .withUsername("peegeeq_test")
            .withPassword("peegeeq_test")
            .withSharedMemorySize(PostgreSQLTestConstants.DEFAULT_SHARED_MEMORY_SIZE)
            .withReuse(false);

    private PeeGeeQRestServer server;
    private DatabaseSetupService setupService;
    private String deploymentId;
    private String setupId;
    private String newDbName;
    private WebClient webClient;

    @BeforeAll
    void setupServer(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== Setting up Subscription Create & Backfill Integration Test ===");

        setupId = "create-backfill-test-" + System.currentTimeMillis();
        newDbName = "cb_db_" + System.currentTimeMillis();

        setupService = PeeGeeQRuntime.createDatabaseSetupService();

        RestServerConfig testConfig = new RestServerConfig(TEST_PORT, RestServerConfig.MonitoringConfig.defaults(), java.util.List.of("*"));
        server = new PeeGeeQRestServer(testConfig, setupService);
        vertx.deployVerticle(server)
            .compose(id -> {
                deploymentId = id;
                logger.info("REST server deployed with ID: {}", deploymentId);
                webClient = WebClient.create(vertx);
                return createSetupWithQueue(vertx);
            })
            .compose(v -> applyFanoutSchema())
            .onSuccess(v -> {
                logger.info("Test setup complete — ready for subscription creation and backfill tests");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    private Future<Void> applyFanoutSchema() {
        try {
            logger.info("Applying Consumer Group Fanout schema to new database: {}", newDbName);
            String jdbcUrl = String.format("jdbc:postgresql://%s:%d/%s",
                postgres.getHost(), postgres.getMappedPort(5432), newDbName);
            PeeGeeQTestSchemaInitializer.initializeSchema(jdbcUrl,
                postgres.getUsername(), postgres.getPassword(),
                SchemaComponent.OUTBOX, SchemaComponent.CONSUMER_GROUP_FANOUT);
            logger.info("Consumer Group Fanout schema applied successfully");
            return Future.succeededFuture();
        } catch (Exception e) {
            logger.error("Failed to apply fanout schema", e);
            return Future.failedFuture(e);
        }
    }

    private Future<Void> createSetupWithQueue(Vertx vertx) {
        JsonObject setupRequest = new JsonObject()
            .put("setupId", setupId)
            .put("databaseConfig", new JsonObject()
                .put("host", postgres.getHost())
                .put("port", postgres.getFirstMappedPort())
                .put("databaseName", newDbName)
                .put("username", postgres.getUsername())
                .put("password", postgres.getPassword())
                .put("schema", "public")
                .put("templateDatabase", "template0")
                .put("encoding", "UTF8"))
            .put("queues", new JsonArray()
                .add(new JsonObject()
                    .put("queueName", TOPIC_NAME)
                    .put("maxRetries", 3)
                    .put("visibilityTimeout", 30)));

        return webClient.post(TEST_PORT, "localhost", "/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .timeout(60000)
            .sendJsonObject(setupRequest)
            .compose(response -> {
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    logger.info("Setup created: {}", setupId);
                    return Future.succeededFuture();
                } else {
                    return Future.failedFuture("Failed to create setup: " + response.statusCode()
                        + " - " + response.bodyAsString());
                }
            });
    }

    @AfterAll
    void tearDown(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Tearing down Subscription Create & Backfill Integration Test ===");

        Future<Void> undeploy = deploymentId != null
            ? vertx.undeploy(deploymentId)
            : Future.succeededFuture();

        undeploy.onComplete(ar -> testContext.completeNow());
    }

    // ========================================================================
    // H3: Create Subscription REST Endpoint Tests
    // ========================================================================

    @Test
    @Order(1)
    @DisplayName("H3: Create subscription via REST returns 201")
    void testCreateSubscription(VertxTestContext testContext) {
        String path = String.format("/api/v1/setups/%s/subscriptions/%s", setupId, TOPIC_NAME);

        JsonObject body = new JsonObject()
            .put("groupName", GROUP_NAME)
            .put("startPosition", "FROM_NOW")
            .put("heartbeatIntervalSeconds", 30)
            .put("heartbeatTimeoutSeconds", 90);

        webClient.post(TEST_PORT, "localhost", path)
            .putHeader("content-type", "application/json")
            .sendJsonObject(body)
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(201, response.statusCode(),
                        "Expected 201, got: " + response.statusCode() + " - " + response.bodyAsString());
                    JsonObject result = response.bodyAsJsonObject();
                    assertTrue(result.getBoolean("success", false), "success should be true");
                    assertEquals("subscribed", result.getString("action"), "action should be subscribed");
                    assertEquals(TOPIC_NAME, result.getString("topic"));
                    assertEquals(GROUP_NAME, result.getString("groupName"));
                    // Should include subscription details
                    JsonObject subscription = result.getJsonObject("subscription");
                    assertNotNull(subscription, "subscription details should be included");
                    assertEquals(TOPIC_NAME, subscription.getString("topic"));
                    assertEquals(GROUP_NAME, subscription.getString("groupName"));
                    assertEquals("ACTIVE", subscription.getString("state"));
                    logger.info("Subscription created successfully: {}", result.encode());
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(2)
    @DisplayName("H3: Verify created subscription appears in list")
    void testCreatedSubscriptionInList(VertxTestContext testContext) {
        String path = String.format("/api/v1/setups/%s/subscriptions/%s", setupId, TOPIC_NAME);

        webClient.get(TEST_PORT, "localhost", path)
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode());
                    JsonArray subscriptions = response.bodyAsJsonArray();
                    assertNotNull(subscriptions);
                    assertTrue(subscriptions.size() >= 1, "Should have at least 1 subscription");
                    // Find our created subscription
                    boolean found = false;
                    for (int i = 0; i < subscriptions.size(); i++) {
                        JsonObject sub = subscriptions.getJsonObject(i);
                        if (GROUP_NAME.equals(sub.getString("groupName"))) {
                            found = true;
                            assertEquals(TOPIC_NAME, sub.getString("topic"));
                            break;
                        }
                    }
                    assertTrue(found, "Created subscription should appear in list");
                    logger.info("Found {} subscriptions for topic {}", subscriptions.size(), TOPIC_NAME);
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(3)
    @DisplayName("H3: Create subscription with missing groupName returns 400")
    void testCreateSubscriptionMissingGroupName(VertxTestContext testContext) {
        String path = String.format("/api/v1/setups/%s/subscriptions/%s", setupId, TOPIC_NAME);

        JsonObject body = new JsonObject()
            .put("startPosition", "FROM_NOW");
        // groupName intentionally omitted

        webClient.post(TEST_PORT, "localhost", path)
            .putHeader("content-type", "application/json")
            .sendJsonObject(body)
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(400, response.statusCode(),
                        "Expected 400, got: " + response.statusCode() + " - " + response.bodyAsString());
                    JsonObject error = response.bodyAsJsonObject();
                    assertNotNull(error.getString("error"), "Should contain error message");
                    logger.info("Got expected 400 for missing groupName: {}", error.encode());
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(4)
    @DisplayName("H3: Create subscription with invalid startPosition returns 400")
    void testCreateSubscriptionInvalidStartPosition(VertxTestContext testContext) {
        String path = String.format("/api/v1/setups/%s/subscriptions/%s", setupId, TOPIC_NAME);

        JsonObject body = new JsonObject()
            .put("groupName", "invalid-pos-group")
            .put("startPosition", "INVALID_VALUE");

        webClient.post(TEST_PORT, "localhost", path)
            .putHeader("content-type", "application/json")
            .sendJsonObject(body)
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(400, response.statusCode(),
                        "Expected 400, got: " + response.statusCode() + " - " + response.bodyAsString());
                    JsonObject error = response.bodyAsJsonObject();
                    assertNotNull(error.getString("error"), "Should contain error message");
                    logger.info("Got expected 400 for invalid startPosition: {}", error.encode());
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(5)
    @DisplayName("H3: Create subscription for non-existent setup returns 404")
    void testCreateSubscriptionNonExistentSetup(VertxTestContext testContext) {
        String path = String.format("/api/v1/setups/%s/subscriptions/%s", "non-existent-setup", TOPIC_NAME);

        JsonObject body = new JsonObject()
            .put("groupName", "some-group")
            .put("startPosition", "FROM_NOW");

        webClient.post(TEST_PORT, "localhost", path)
            .putHeader("content-type", "application/json")
            .sendJsonObject(body)
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(404, response.statusCode(),
                        "Expected 404, got: " + response.statusCode() + " - " + response.bodyAsString());
                    logger.info("Got expected 404 for non-existent setup");
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(6)
    @DisplayName("H3: Create duplicate subscription returns 409")
    void testCreateDuplicateSubscription(VertxTestContext testContext) {
        String path = String.format("/api/v1/setups/%s/subscriptions/%s", setupId, TOPIC_NAME);

        // GROUP_NAME was already created in Order 1 — creating again should return 409
        JsonObject body = new JsonObject()
            .put("groupName", GROUP_NAME)
            .put("startPosition", "FROM_NOW");

        webClient.post(TEST_PORT, "localhost", path)
            .putHeader("content-type", "application/json")
            .sendJsonObject(body)
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(409, response.statusCode(),
                        "Expected 409, got: " + response.statusCode() + " - " + response.bodyAsString());
                    JsonObject error = response.bodyAsJsonObject();
                    assertNotNull(error.getString("error"), "Should contain error message");
                    logger.info("Got expected 409 for duplicate subscription: {}", error.encode());
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(7)
    @DisplayName("H3: Create subscription with FROM_BEGINNING start position")
    void testCreateSubscriptionFromBeginning(VertxTestContext testContext) {
        String path = String.format("/api/v1/setups/%s/subscriptions/%s", setupId, TOPIC_NAME);

        JsonObject body = new JsonObject()
            .put("groupName", BACKFILL_GROUP)
            .put("startPosition", "FROM_BEGINNING")
            .put("heartbeatIntervalSeconds", 60)
            .put("heartbeatTimeoutSeconds", 180);

        webClient.post(TEST_PORT, "localhost", path)
            .putHeader("content-type", "application/json")
            .sendJsonObject(body)
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(201, response.statusCode(),
                        "Expected 201, got: " + response.statusCode() + " - " + response.bodyAsString());
                    JsonObject result = response.bodyAsJsonObject();
                    assertTrue(result.getBoolean("success", false));
                    assertEquals("subscribed", result.getString("action"));
                    assertEquals(BACKFILL_GROUP, result.getString("groupName"));
                    logger.info("FROM_BEGINNING subscription created: {}", result.encode());
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    // ========================================================================
    // H4: Backfill REST Endpoint Tests
    // ========================================================================

    @Test
    @Order(8)
    @DisplayName("H4: Get backfill progress for subscription")
    void testGetBackfillProgress(VertxTestContext testContext) {
        String path = String.format("/api/v1/setups/%s/subscriptions/%s/%s/backfill",
            setupId, TOPIC_NAME, GROUP_NAME);

        webClient.get(TEST_PORT, "localhost", path)
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode(),
                        "Expected 200, got: " + response.statusCode() + " - " + response.bodyAsString());
                    JsonObject progress = response.bodyAsJsonObject();
                    assertNotNull(progress, "Response should be a JSON object");
                    assertEquals(TOPIC_NAME, progress.getString("topic"));
                    assertEquals(GROUP_NAME, progress.getString("groupName"));
                    assertNotNull(progress.getString("backfillStatus"),
                        "backfillStatus should be present");
                    logger.info("Backfill progress: {}", progress.encode());
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(9)
    @DisplayName("H4: Get backfill progress for non-existent subscription returns 404")
    void testGetBackfillProgressNonExistent(VertxTestContext testContext) {
        String path = String.format("/api/v1/setups/%s/subscriptions/%s/%s/backfill",
            setupId, TOPIC_NAME, "non-existent-group");

        webClient.get(TEST_PORT, "localhost", path)
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(404, response.statusCode(),
                        "Expected 404, got: " + response.statusCode() + " - " + response.bodyAsString());
                    logger.info("Got expected 404 for non-existent subscription backfill progress");
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(10)
    @DisplayName("H4: Get backfill progress for non-existent setup returns 404")
    void testGetBackfillProgressNonExistentSetup(VertxTestContext testContext) {
        String path = String.format("/api/v1/setups/%s/subscriptions/%s/%s/backfill",
            "non-existent-setup", TOPIC_NAME, GROUP_NAME);

        webClient.get(TEST_PORT, "localhost", path)
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(404, response.statusCode(),
                        "Expected 404 for non-existent setup");
                    logger.info("Got expected 404 for non-existent setup backfill progress");
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(11)
    @DisplayName("H4: Cancel backfill for non-existent setup returns 404")
    void testCancelBackfillNonExistentSetup(VertxTestContext testContext) {
        String path = String.format("/api/v1/setups/%s/subscriptions/%s/%s/backfill",
            "non-existent-setup", TOPIC_NAME, GROUP_NAME);

        webClient.delete(TEST_PORT, "localhost", path)
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(404, response.statusCode(),
                        "Expected 404, got: " + response.statusCode() + " - " + response.bodyAsString());
                    logger.info("Got expected 404 for cancel backfill on non-existent setup");
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(12)
    @DisplayName("H4: Start backfill for non-existent setup returns 404")
    void testStartBackfillNonExistentSetup(VertxTestContext testContext) {
        String path = String.format("/api/v1/setups/%s/subscriptions/%s/%s/backfill",
            "non-existent-setup", TOPIC_NAME, GROUP_NAME);

        webClient.post(TEST_PORT, "localhost", path)
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(404, response.statusCode(),
                        "Expected 404, got: " + response.statusCode() + " - " + response.bodyAsString());
                    logger.info("Got expected 404 for start backfill on non-existent setup");
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    // ========================================================================
    // H4: Backfill Happy-Path Tests (require BackfillService wired)
    // ========================================================================

    @Test
    @Order(13)
    @DisplayName("H4: Start backfill on valid subscription returns 200")
    void testStartBackfillHappyPath(VertxTestContext testContext) {
        String path = String.format("/api/v1/setups/%s/subscriptions/%s/%s/backfill",
            setupId, TOPIC_NAME, GROUP_NAME);

        webClient.post(TEST_PORT, "localhost", path)
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode(),
                        "Expected 200, got: " + response.statusCode() + " - " + response.bodyAsString());
                    JsonObject result = response.bodyAsJsonObject();
                    assertNotNull(result.getString("status"), "status should be present");
                    // With 0 messages in topic, backfill completes immediately
                    assertTrue(
                        "COMPLETED".equals(result.getString("status"))
                            || "ALREADY_COMPLETED".equals(result.getString("status")),
                        "status should be COMPLETED or ALREADY_COMPLETED, got: " + result.getString("status"));
                    assertNotNull(result.getLong("processedMessages"), "processedMessages should be present");
                    assertEquals(0L, result.getLong("processedMessages"),
                        "No messages to backfill, should be 0");
                    logger.info("Start backfill happy path: {}", result.encode());
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(14)
    @DisplayName("H4: Start backfill when already completed returns 200 with ALREADY_COMPLETED")
    void testStartBackfillAlreadyCompleted(VertxTestContext testContext) {
        // Backfill was completed in Order 13 — calling again should return ALREADY_COMPLETED
        String path = String.format("/api/v1/setups/%s/subscriptions/%s/%s/backfill",
            setupId, TOPIC_NAME, GROUP_NAME);

        webClient.post(TEST_PORT, "localhost", path)
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode(),
                        "Expected 200, got: " + response.statusCode() + " - " + response.bodyAsString());
                    JsonObject result = response.bodyAsJsonObject();
                    assertEquals("ALREADY_COMPLETED", result.getString("status"),
                        "Backfill was already completed, should return ALREADY_COMPLETED");
                    logger.info("Start backfill already completed: {}", result.encode());
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(15)
    @DisplayName("H4: Cancel backfill on valid subscription returns 200")
    void testCancelBackfillHappyPath(VertxTestContext testContext) {
        String path = String.format("/api/v1/setups/%s/subscriptions/%s/%s/backfill",
            setupId, TOPIC_NAME, GROUP_NAME);

        webClient.delete(TEST_PORT, "localhost", path)
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode(),
                        "Expected 200, got: " + response.statusCode() + " - " + response.bodyAsString());
                    JsonObject result = response.bodyAsJsonObject();
                    assertTrue(result.getBoolean("success", false), "success should be true");
                    assertEquals(TOPIC_NAME, result.getString("topic"));
                    assertEquals(GROUP_NAME, result.getString("groupName"));
                    assertEquals("backfill_cancelled", result.getString("action"));
                    logger.info("Cancel backfill happy path: {}", result.encode());
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(16)
    @DisplayName("H4: Start backfill with explicit scope returns scope in response")
    void testStartBackfillWithExplicitScope(VertxTestContext testContext) {
        String path = String.format("/api/v1/setups/%s/subscriptions/%s/%s/backfill",
            setupId, TOPIC_NAME, GROUP_NAME);

        JsonObject body = new JsonObject()
            .put("backfillScope", "ALL_RETAINED");

        webClient.post(TEST_PORT, "localhost", path)
            .putHeader("content-type", "application/json")
            .sendJsonObject(body)
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode(),
                        "Expected 200, got: " + response.statusCode() + " - " + response.bodyAsString());
                    JsonObject result = response.bodyAsJsonObject();
                    assertNotNull(result.getString("status"), "status should be present");
                    assertEquals("ALL_RETAINED", result.getString("scope"),
                        "Response should echo requested backfill scope");
                    logger.info("Start backfill with explicit scope: {}", result.encode());
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(17)
    @DisplayName("H4: Start backfill with invalid scope returns 400")
    void testStartBackfillInvalidScope(VertxTestContext testContext) {
        String path = String.format("/api/v1/setups/%s/subscriptions/%s/%s/backfill",
            setupId, TOPIC_NAME, GROUP_NAME);

        JsonObject body = new JsonObject()
            .put("backfillScope", "NOT_A_SCOPE");

        webClient.post(TEST_PORT, "localhost", path)
            .putHeader("content-type", "application/json")
            .sendJsonObject(body)
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(400, response.statusCode(),
                        "Expected 400, got: " + response.statusCode() + " - " + response.bodyAsString());
                    JsonObject error = response.bodyAsJsonObject();
                    assertNotNull(error.getString("error"), "error should be present");
                    logger.info("Start backfill invalid scope rejected as expected: {}", error.encode());
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }
}
