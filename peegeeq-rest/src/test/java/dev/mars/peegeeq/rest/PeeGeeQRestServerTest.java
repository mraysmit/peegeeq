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

package dev.mars.peegeeq.rest;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;



import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive integration tests for PeeGeeQ REST Server using TestContainers.
 *
 * Tests the complete Vert.x-based REST API functionality with real PostgreSQL
 * database, ensuring full integration testing without mocking.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-18
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class PeeGeeQRestServerTest {

    private static final Logger logger = LoggerFactory.getLogger(PeeGeeQRestServerTest.class);
    private static final int TEST_PORT = 8081;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_rest_test")
            .withUsername("peegeeq_test")
            .withPassword("peegeeq_test")
            .withSharedMemorySize(256 * 1024 * 1024L)
            .withReuse(false);

    private WebClient client;
    private String testSetupId;
    
    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext testContext) {
        client = WebClient.create(vertx);
        testSetupId = "test-setup-" + System.currentTimeMillis();

        logger.info("Starting REST server on port {} for test setup: {}", TEST_PORT, testSetupId);

        // Deploy the REST server
        vertx.deployVerticle(new PeeGeeQRestServer(TEST_PORT))
            .onSuccess(id -> {
                logger.info("REST server deployed successfully with deployment ID: {}", id);
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(Vertx vertx, VertxTestContext testContext) {
        if (client != null) {
            client.close();
        }
        logger.info("Test cleanup completed for setup: {}", testSetupId);
        testContext.completeNow();
    }
    
    @Test
    @Order(1)
    void testHealthEndpoint(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Testing Health Endpoint ===");

        client.get(TEST_PORT, "localhost", "/health")
                .timeout(5000)
                .send()
                .onSuccess(response -> testContext.verify(() -> {
                    assertEquals(200, response.statusCode());
                    JsonObject body = response.bodyAsJsonObject();
                    assertEquals("UP", body.getString("status"));
                    assertEquals("peegeeq-rest-api", body.getString("service"));

                    logger.info("Health endpoint responded correctly: {}", body.encode());
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    @Order(2)
    void testMetricsEndpoint(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Testing Metrics Endpoint ===");

        client.get(TEST_PORT, "localhost", "/metrics")
                .timeout(5000)
                .send()
                .onSuccess(response -> testContext.verify(() -> {
                    assertEquals(200, response.statusCode());
                    String body = response.bodyAsString();
                    assertTrue(body.contains("peegeeq_http_requests_total") || body.contains("PeeGeeQ REST API Metrics"));

                    logger.info("Metrics endpoint responded correctly");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }
    
    @Test
    @Order(3)
    void testCreateDatabaseSetup(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Testing Create Database Setup ===");

        JsonObject setupRequest = createTestSetupRequest();

        client.post(TEST_PORT, "localhost", "/api/v1/database-setup/create")
                .putHeader("content-type", "application/json")
                .timeout(30000) // 30 second timeout for database operations
                .sendJsonObject(setupRequest)
                .onSuccess(response -> testContext.verify(() -> {
                    logger.info("Create setup response status: {}", response.statusCode());
                    logger.info("Create setup response body: {}", response.bodyAsString());

                    // Should succeed with real database - 201 Created is proper REST status for POST creating a resource
                    assertEquals(201, response.statusCode());

                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body);
                    assertEquals(testSetupId, body.getString("setupId"));
                    assertEquals("ACTIVE", body.getString("status"));

                    logger.info("Database setup created successfully: {}", testSetupId);
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }
    
    @Test
    @Order(4)
    void testGetSetupStatus(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Testing Get Setup Status ===");

        // Test getting status for non-existent setup (should return 404)
        client.get(TEST_PORT, "localhost", "/api/v1/database-setup/nonexistent/status")
                .timeout(10000)
                .send()
                .onSuccess(response -> testContext.verify(() -> {
                    logger.info("Status response: {} - {}", response.statusCode(), response.bodyAsString());

                    assertEquals(404, response.statusCode());

                    logger.info("Non-existent setup properly returned 404");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }
    
    @Test
    @Order(5)
    void testSendMessage(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Testing Send Message ===");

        JsonObject messageRequest = new JsonObject()
                .put("payload", new JsonObject().put("orderId", "12345").put("amount", 99.99))
                .put("priority", 5)
                .put("headers", new JsonObject().put("source", "test"));

        // Test sending message to non-existent setup (should return 400)
        client.post(TEST_PORT, "localhost", "/api/v1/queues/nonexistent/orders/messages")
                .putHeader("content-type", "application/json")
                .timeout(10000)
                .sendJsonObject(messageRequest)
                .onSuccess(response -> testContext.verify(() -> {
                    logger.info("Message response: {} - {}", response.statusCode(), response.bodyAsString());

                    assertTrue(response.statusCode() == 400 || response.statusCode() == 404);

                    logger.info("Message to non-existent setup properly rejected");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }
    
    @Test
    @Order(6)
    void testGetQueueStats(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Testing Get Queue Stats ===");

        // Test getting stats for non-existent queue (should return 404)
        client.get(TEST_PORT, "localhost", "/api/v1/queues/nonexistent/orders/stats")
                .timeout(10000)
                .send()
                .onSuccess(response -> testContext.verify(() -> {
                    logger.info("Queue stats response: {} - {}", response.statusCode(), response.bodyAsString());

                    assertTrue(response.statusCode() == 404 || response.statusCode() == 400);

                    logger.info("Queue stats for non-existent setup properly rejected");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }
    
    @Test
    @Order(7)
    void testStoreEvent(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Testing Store Event ===");

        JsonObject eventRequest = new JsonObject()
                .put("eventType", "OrderCreated")
                .put("eventData", new JsonObject()
                        .put("orderId", "12345")
                        .put("customerId", "67890")
                        .put("amount", 99.99))
                .put("correlationId", "correlation-123");

        // Test storing event to non-existent setup (should return 400)
        client.post(TEST_PORT, "localhost", "/api/v1/eventstores/nonexistent/order-events/events")
                .putHeader("content-type", "application/json")
                .timeout(10000)
                .sendJsonObject(eventRequest)
                .onSuccess(response -> testContext.verify(() -> {
                    logger.info("Store event response: {} - {}", response.statusCode(), response.bodyAsString());

                    assertTrue(response.statusCode() == 400 || response.statusCode() == 404);

                    logger.info("Event storage to non-existent setup properly rejected");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }
    
    @Test
    @Order(8)
    void testQueryEvents(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Testing Query Events ===");

        // Test querying events from non-existent setup (should return 404)
        client.get(TEST_PORT, "localhost", "/api/v1/eventstores/nonexistent/order-events/events")
                .addQueryParam("eventType", "OrderCreated")
                .addQueryParam("limit", "10")
                .timeout(10000)
                .send()
                .onSuccess(response -> testContext.verify(() -> {
                    logger.info("Query events response: {} - {}", response.statusCode(), response.bodyAsString());

                    assertTrue(response.statusCode() == 404 || response.statusCode() == 400 || response.statusCode() == 500);

                    logger.info("Event query from non-existent setup properly rejected");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    @Order(9)
    void testCorsHeaders(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Testing CORS Headers ===");

        client.request(HttpMethod.OPTIONS, TEST_PORT, "localhost", "/api/v1/database-setup/create")
                .putHeader("Origin", "http://localhost:3000")
                .putHeader("Access-Control-Request-Method", "POST")
                .timeout(5000)
                .send()
                .onSuccess(response -> testContext.verify(() -> {
                    logger.info("CORS response: {} - Headers: {}", response.statusCode(), response.headers().names());

                    // OPTIONS requests typically return 204 (No Content), not 200
                    assertEquals(204, response.statusCode());
                    assertNotNull(response.getHeader("Access-Control-Allow-Origin"));

                    logger.info("CORS headers working correctly");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    @Order(10)
    void testInvalidEndpoint(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Testing Invalid Endpoint ===");

        client.get(TEST_PORT, "localhost", "/api/v1/invalid-endpoint")
                .timeout(5000)
                .send()
                .onSuccess(response -> testContext.verify(() -> {
                    logger.info("Invalid endpoint response: {}", response.statusCode());

                    assertEquals(404, response.statusCode());

                    logger.info("Invalid endpoint properly rejected");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    // Helper methods for creating test requests

    private JsonObject createTestSetupRequest() {
        return new JsonObject()
                .put("setupId", testSetupId)
                .put("databaseConfig", new JsonObject()
                        .put("host", postgres.getHost())
                        .put("port", postgres.getFirstMappedPort())
                        .put("databaseName", "test_db_" + System.currentTimeMillis())
                        .put("username", postgres.getUsername())
                        .put("password", postgres.getPassword())
                        .put("schema", "public")
                        .put("templateDatabase", "template0")
                        .put("encoding", "UTF8"))
                .put("queues", new JsonArray())
                .put("eventStores", new JsonArray())
                .put("additionalProperties", new JsonObject().put("test", true));
    }


}
