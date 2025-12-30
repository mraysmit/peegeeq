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

package dev.mars.peegeeq.client;

import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.client.config.ClientConfig;
import dev.mars.peegeeq.rest.PeeGeeQRestServer;
import dev.mars.peegeeq.rest.config.RestServerConfig;
import dev.mars.peegeeq.runtime.PeeGeeQRuntime;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
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

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for PeeGeeQRestClient against real PeeGeeQRestServer.
 * Uses TestContainers for PostgreSQL database.
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RestClientIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(RestClientIntegrationTest.class);
    private static final int TEST_PORT = 18300;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_client_test")
            .withUsername("peegeeq_test")
            .withPassword("peegeeq_test")
            .withSharedMemorySize(256 * 1024 * 1024L)
            .withReuse(false);

    private PeeGeeQClient client;
    private WebClient webClient;
    private String deploymentId;
    private String testSetupId;
    private String testDatabaseName;

    @BeforeAll
    void setUp(Vertx vertx, VertxTestContext testContext) {
        testSetupId = "client-integration-test-" + System.currentTimeMillis();
        testDatabaseName = "test_db_" + System.currentTimeMillis();

        logger.info("=== Starting REST Client Integration Test ===");
        logger.info("Test Setup ID: {}", testSetupId);
        logger.info("PostgreSQL: {}:{}", postgres.getHost(), postgres.getFirstMappedPort());

        // Create the setup service using PeeGeeQRuntime
        DatabaseSetupService setupService = PeeGeeQRuntime.createDatabaseSetupService();

        // Create web client for direct HTTP calls
        webClient = WebClient.create(vertx);

        // Deploy the REST server with configuration
        RestServerConfig testConfig = new RestServerConfig(TEST_PORT, RestServerConfig.MonitoringConfig.defaults());
        vertx.deployVerticle(new PeeGeeQRestServer(testConfig, setupService))
            .onSuccess(id -> {
                deploymentId = id;
                logger.info("REST server deployed on port {}", TEST_PORT);

                // Create the REST client
                ClientConfig config = ClientConfig.builder()
                    .baseUrl("http://localhost:" + TEST_PORT)
                    .timeout(Duration.ofSeconds(30))
                    .maxRetries(0)
                    .build();
                client = PeeGeeQRestClient.create(vertx, config);

                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @AfterAll
    void tearDown(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Tearing Down REST Client Integration Test ===");

        if (client != null) {
            client.close();
        }
        if (webClient != null) {
            webClient.close();
        }
        if (deploymentId != null) {
            vertx.undeploy(deploymentId)
                .onComplete(ar -> {
                    logger.info("Test cleanup completed");
                    testContext.completeNow();
                });
        } else {
            testContext.completeNow();
        }
    }

    @Test
    @Order(1)
    @DisplayName("createSetup - creates database setup via REST API")
    void createSetup_success(VertxTestContext testContext) throws Exception {
        JsonObject setupRequest = new JsonObject()
            .put("setupId", testSetupId)
            .put("databaseConfig", new JsonObject()
                .put("host", postgres.getHost())
                .put("port", postgres.getFirstMappedPort())
                .put("databaseName", testDatabaseName)
                .put("username", postgres.getUsername())
                .put("password", postgres.getPassword())
                .put("schema", "public")
                .put("templateDatabase", "template0")
                .put("encoding", "UTF8"))
            .put("queues", new JsonArray()
                .add(new JsonObject()
                    .put("queueName", "orders")
                    .put("maxRetries", 3)
                    .put("visibilityTimeoutSeconds", 30)))
            .put("eventStores", new JsonArray()
                .add(new JsonObject()
                    .put("eventStoreName", "order-events")
                    .put("tableName", "order_events")
                    .put("biTemporalEnabled", true)))
            .put("additionalProperties", new JsonObject());

        webClient.post(TEST_PORT, "localhost", "/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .timeout(30000)
            .sendJsonObject(setupRequest)
            .onComplete(ar -> {
                testContext.verify(() -> {
                    assertTrue(ar.succeeded(), "HTTP request should succeed");
                    HttpResponse<Buffer> response = ar.result();
                    assertEquals(201, response.statusCode(),
                        "Should return 201 Created: " + response.bodyAsString());
                    JsonObject body = response.bodyAsJsonObject();
                    assertEquals(testSetupId, body.getString("setupId"));
                    assertEquals("ACTIVE", body.getString("status"));
                    logger.info("Setup created successfully: {}", body);
                });
                testContext.completeNow();
            });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    @Order(2)
    @DisplayName("listSetups - lists all setups via REST API")
    void listSetups_success(VertxTestContext testContext) throws Exception {
        webClient.get(TEST_PORT, "localhost", "/api/v1/setups")
            .timeout(10000)
            .send()
            .onComplete(ar -> {
                testContext.verify(() -> {
                    assertTrue(ar.succeeded(), "HTTP request should succeed");
                    HttpResponse<Buffer> response = ar.result();
                    assertEquals(200, response.statusCode(),
                        "Should return 200 OK: " + response.bodyAsString());
                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body, "Response should not be null");
                    assertTrue(body.getInteger("count") >= 1, "Should have at least one setup");
                    JsonArray setupIds = body.getJsonArray("setupIds");
                    assertNotNull(setupIds, "setupIds should not be null");
                    logger.info("Listed {} setups: {}", body.getInteger("count"), setupIds);
                });
                testContext.completeNow();
            });

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    @Test
    @Order(3)
    @DisplayName("getSetupStatus - gets setup status via REST API")
    void getSetupStatus_success(VertxTestContext testContext) throws Exception {
        webClient.get(TEST_PORT, "localhost", "/api/v1/setups/" + testSetupId + "/status")
            .timeout(10000)
            .send()
            .onComplete(ar -> {
                testContext.verify(() -> {
                    assertTrue(ar.succeeded(), "HTTP request should succeed");
                    HttpResponse<Buffer> response = ar.result();
                    assertEquals(200, response.statusCode(),
                        "Should return 200 OK: " + response.bodyAsString());
                    JsonObject status = response.bodyAsJsonObject();
                    assertNotNull(status, "Status should not be null");
                    logger.info("Setup status: {}", status);
                });
                testContext.completeNow();
            });

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    @Test
    @Order(4)
    @DisplayName("sendMessage - sends message to queue via REST API")
    void sendMessage_success(VertxTestContext testContext) throws Exception {
        JsonObject messageRequest = new JsonObject()
            .put("payload", new JsonObject()
                .put("orderId", "12345")
                .put("amount", 99.99))
            .put("priority", 5)
            .put("headers", new JsonObject()
                .put("source", "integration-test"));

        webClient.post(TEST_PORT, "localhost",
                "/api/v1/queues/" + testSetupId + "/orders/messages")
            .putHeader("content-type", "application/json")
            .timeout(10000)
            .sendJsonObject(messageRequest)
            .onComplete(ar -> {
                testContext.verify(() -> {
                    assertTrue(ar.succeeded(), "HTTP request should succeed");
                    HttpResponse<Buffer> response = ar.result();
                    assertEquals(200, response.statusCode(),
                        "Should return 200 OK: " + response.bodyAsString());
                    JsonObject result = response.bodyAsJsonObject();
                    assertNotNull(result.getString("messageId"), "Should have messageId");
                    logger.info("Message sent: {}", result);
                });
                testContext.completeNow();
            });

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    @Test
    @Order(5)
    @DisplayName("getQueueDetails - gets queue details via REST API")
    void getQueueDetails_success(VertxTestContext testContext) throws Exception {
        webClient.get(TEST_PORT, "localhost",
                "/api/v1/queues/" + testSetupId + "/orders")
            .timeout(10000)
            .send()
            .onComplete(ar -> {
                testContext.verify(() -> {
                    assertTrue(ar.succeeded(), "HTTP request should succeed");
                    HttpResponse<Buffer> response = ar.result();
                    assertEquals(200, response.statusCode(),
                        "Should return 200 OK: " + response.bodyAsString());
                    JsonObject details = response.bodyAsJsonObject();
                    assertNotNull(details, "Details should not be null");
                    logger.info("Queue details: {}", details);
                });
                testContext.completeNow();
            });

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    @Test
    @Order(6)
    @DisplayName("getHealth - gets health status via REST API")
    void getHealth_success(VertxTestContext testContext) throws Exception {
        webClient.get(TEST_PORT, "localhost",
                "/api/v1/setups/" + testSetupId + "/health")
            .timeout(10000)
            .send()
            .onComplete(ar -> {
                testContext.verify(() -> {
                    assertTrue(ar.succeeded(), "HTTP request should succeed");
                    HttpResponse<Buffer> response = ar.result();
                    assertEquals(200, response.statusCode(),
                        "Should return 200 OK: " + response.bodyAsString());
                    JsonObject health = response.bodyAsJsonObject();
                    assertNotNull(health, "Health should not be null");
                    logger.info("Health status: {}", health);
                });
                testContext.completeNow();
            });

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    @Test
    @Order(7)
    @DisplayName("appendEvent - appends event to event store via REST API")
    void appendEvent_success(VertxTestContext testContext) throws Exception {
        JsonObject eventRequest = new JsonObject()
            .put("eventType", "OrderCreated")
            .put("eventData", new JsonObject()
                .put("orderId", "order-123")
                .put("customerId", "cust-456")
                .put("amount", 199.99));

        webClient.post(TEST_PORT, "localhost",
                "/api/v1/eventstores/" + testSetupId + "/order-events/events")
            .putHeader("content-type", "application/json")
            .timeout(10000)
            .sendJsonObject(eventRequest)
            .onComplete(ar -> {
                testContext.verify(() -> {
                    assertTrue(ar.succeeded(), "HTTP request should succeed");
                    HttpResponse<Buffer> response = ar.result();
                    assertEquals(201, response.statusCode(),
                        "Should return 201 Created: " + response.bodyAsString());
                    JsonObject result = response.bodyAsJsonObject();
                    assertNotNull(result.getString("eventId"), "Should have eventId");
                    logger.info("Event appended: {}", result);
                });
                testContext.completeNow();
            });

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    @Test
    @Order(8)
    @DisplayName("queryEvents - queries events from event store via REST API")
    void queryEvents_success(VertxTestContext testContext) throws Exception {
        webClient.get(TEST_PORT, "localhost",
                "/api/v1/eventstores/" + testSetupId + "/order-events/events")
            .addQueryParam("eventType", "OrderCreated")
            .addQueryParam("limit", "10")
            .timeout(10000)
            .send()
            .onComplete(ar -> {
                testContext.verify(() -> {
                    assertTrue(ar.succeeded(), "HTTP request should succeed");
                    HttpResponse<Buffer> response = ar.result();
                    assertEquals(200, response.statusCode(),
                        "Should return 200 OK: " + response.bodyAsString());
                    JsonObject result = response.bodyAsJsonObject();
                    assertNotNull(result, "Result should not be null");
                    logger.info("Events queried: {}", result);
                });
                testContext.completeNow();
            });

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    @Test
    @Order(9)
    @DisplayName("createConsumerGroup - creates consumer group via REST API")
    void createConsumerGroup_success(VertxTestContext testContext) throws Exception {
        JsonObject groupRequest = new JsonObject()
            .put("groupName", "order-processors");

        webClient.post(TEST_PORT, "localhost",
                "/api/v1/queues/" + testSetupId + "/orders/consumer-groups")
            .putHeader("content-type", "application/json")
            .timeout(10000)
            .sendJsonObject(groupRequest)
            .onComplete(ar -> {
                testContext.verify(() -> {
                    assertTrue(ar.succeeded(), "HTTP request should succeed");
                    HttpResponse<Buffer> response = ar.result();
                    assertEquals(201, response.statusCode(),
                        "Should return 201 Created: " + response.bodyAsString());
                    JsonObject result = response.bodyAsJsonObject();
                    assertNotNull(result, "Result should not be null");
                    logger.info("Consumer group created: {}", result);
                });
                testContext.completeNow();
            });

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    @Test
    @Order(10)
    @DisplayName("listConsumerGroups - lists consumer groups via REST API")
    void listConsumerGroups_success(VertxTestContext testContext) throws Exception {
        webClient.get(TEST_PORT, "localhost",
                "/api/v1/queues/" + testSetupId + "/orders/consumer-groups")
            .timeout(10000)
            .send()
            .onComplete(ar -> {
                testContext.verify(() -> {
                    assertTrue(ar.succeeded(), "HTTP request should succeed");
                    HttpResponse<Buffer> response = ar.result();
                    assertEquals(200, response.statusCode(),
                        "Should return 200 OK: " + response.bodyAsString());
                    JsonObject result = response.bodyAsJsonObject();
                    assertNotNull(result, "Result should not be null");
                    assertNotNull(result.getJsonArray("groups"), "Groups array should not be null");
                    logger.info("Consumer groups: {}", result);
                });
                testContext.completeNow();
            });

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    @Test
    @Order(11)
    @DisplayName("getEventStoreStats - gets event store statistics via REST API")
    void getEventStoreStats_success(VertxTestContext testContext) throws Exception {
        webClient.get(TEST_PORT, "localhost",
                "/api/v1/eventstores/" + testSetupId + "/order-events/stats")
            .timeout(10000)
            .send()
            .onComplete(ar -> {
                testContext.verify(() -> {
                    assertTrue(ar.succeeded(), "HTTP request should succeed");
                    HttpResponse<Buffer> response = ar.result();
                    assertEquals(200, response.statusCode(),
                        "Should return 200 OK: " + response.bodyAsString());
                    JsonObject stats = response.bodyAsJsonObject();
                    assertNotNull(stats, "Stats should not be null");
                    logger.info("Event store stats: {}", stats);
                });
                testContext.completeNow();
            });

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    // ========================================================================
    // DLQ Tests
    // ========================================================================

    @Test
    @Order(12)
    @DisplayName("listDeadLetters - lists dead letter messages via REST API")
    void listDeadLetters_success(VertxTestContext testContext) throws Exception {
        webClient.get(TEST_PORT, "localhost",
                "/api/v1/setups/" + testSetupId + "/deadletter/messages")
            .addQueryParam("offset", "0")
            .addQueryParam("limit", "10")
            .timeout(10000)
            .send()
            .onComplete(ar -> {
                testContext.verify(() -> {
                    assertTrue(ar.succeeded(), "HTTP request should succeed");
                    HttpResponse<Buffer> response = ar.result();
                    assertEquals(200, response.statusCode(),
                        "Should return 200 OK: " + response.bodyAsString());
                    // Response is a JsonArray of dead letter messages
                    JsonArray result = response.bodyAsJsonArray();
                    assertNotNull(result, "Result should not be null");
                    logger.info("Dead letters: {}", result);
                });
                testContext.completeNow();
            });

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    @Test
    @Order(13)
    @DisplayName("getDlqStats - gets DLQ statistics via REST API")
    void getDlqStats_success(VertxTestContext testContext) throws Exception {
        webClient.get(TEST_PORT, "localhost",
                "/api/v1/setups/" + testSetupId + "/deadletter/stats")
            .timeout(10000)
            .send()
            .onComplete(ar -> {
                testContext.verify(() -> {
                    assertTrue(ar.succeeded(), "HTTP request should succeed");
                    HttpResponse<Buffer> response = ar.result();
                    assertEquals(200, response.statusCode(),
                        "Should return 200 OK: " + response.bodyAsString());
                    JsonObject stats = response.bodyAsJsonObject();
                    assertNotNull(stats, "Stats should not be null");
                    logger.info("DLQ stats: {}", stats);
                });
                testContext.completeNow();
            });

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    // ========================================================================
    // Health Tests
    // ========================================================================

    @Test
    @Order(15)
    @DisplayName("listComponentHealth - lists component health via REST API")
    void listComponentHealth_success(VertxTestContext testContext) throws Exception {
        webClient.get(TEST_PORT, "localhost",
                "/api/v1/setups/" + testSetupId + "/health/components")
            .timeout(10000)
            .send()
            .onComplete(ar -> {
                testContext.verify(() -> {
                    assertTrue(ar.succeeded(), "HTTP request should succeed");
                    HttpResponse<Buffer> response = ar.result();
                    assertEquals(200, response.statusCode(),
                        "Should return 200 OK: " + response.bodyAsString());
                    // Response is a JsonArray of component health entries
                    JsonArray result = response.bodyAsJsonArray();
                    assertNotNull(result, "Result should not be null");
                    logger.info("Component health: {}", result);
                });
                testContext.completeNow();
            });

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    @Test
    @Order(100)
    @DisplayName("deleteSetup - deletes setup via REST API")
    void deleteSetup_success(VertxTestContext testContext) throws Exception {
        webClient.delete(TEST_PORT, "localhost", "/api/v1/setups/" + testSetupId)
            .timeout(30000)
            .send()
            .onComplete(ar -> {
                testContext.verify(() -> {
                    assertTrue(ar.succeeded(), "HTTP request should succeed");
                    HttpResponse<Buffer> response = ar.result();
                    // Accept 200 or 204 for delete
                    assertTrue(response.statusCode() == 200 || response.statusCode() == 204,
                        "Should return 200 or 204: " + response.statusCode());
                    logger.info("Setup deleted successfully");
                });
                testContext.completeNow();
            });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }
}

