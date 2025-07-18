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

import java.util.concurrent.TimeUnit;

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
        vertx.deployVerticle(new PeeGeeQRestServer(TEST_PORT), testContext.succeeding(id -> {
            logger.info("REST server deployed successfully with deployment ID: {}", id);
            testContext.completeNow();
        }));
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
                .send(testContext.succeeding(response -> testContext.verify(() -> {
                    assertEquals(200, response.statusCode());
                    JsonObject body = response.bodyAsJsonObject();
                    assertEquals("UP", body.getString("status"));
                    assertEquals("peegeeq-rest-api", body.getString("service"));

                    logger.info("Health endpoint responded correctly: {}", body.encode());
                    testContext.completeNow();
                })));
    }

    @Test
    @Order(2)
    void testMetricsEndpoint(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Testing Metrics Endpoint ===");

        client.get(TEST_PORT, "localhost", "/metrics")
                .timeout(5000)
                .send(testContext.succeeding(response -> testContext.verify(() -> {
                    assertEquals(200, response.statusCode());
                    String body = response.bodyAsString();
                    assertTrue(body.contains("PeeGeeQ REST API Metrics"));

                    logger.info("Metrics endpoint responded correctly");
                    testContext.completeNow();
                })));
    }
    
    @Test
    @Order(3)
    void testCreateDatabaseSetup(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Testing Create Database Setup ===");

        JsonObject setupRequest = createTestSetupRequest();

        client.post(TEST_PORT, "localhost", "/api/v1/database-setup/create")
                .putHeader("content-type", "application/json")
                .timeout(30000) // 30 second timeout for database operations
                .sendJsonObject(setupRequest, testContext.succeeding(response -> testContext.verify(() -> {
                    logger.info("Create setup response status: {}", response.statusCode());
                    logger.info("Create setup response body: {}", response.bodyAsString());

                    // Should succeed with real database
                    assertEquals(200, response.statusCode());

                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body);
                    assertEquals(testSetupId, body.getString("setupId"));
                    assertEquals("ACTIVE", body.getString("status"));

                    logger.info("Database setup created successfully: {}", testSetupId);
                    testContext.completeNow();
                })));
    }
    
    @Test
    @Order(4)
    void testGetSetupStatus(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Testing Get Setup Status ===");

        // First create a setup, then get its status
        JsonObject setupRequest = createTestSetupRequest();

        client.post(TEST_PORT, "localhost", "/api/v1/database-setup/create")
                .putHeader("content-type", "application/json")
                .timeout(30000)
                .sendJsonObject(setupRequest, testContext.succeeding(createResponse -> {
                    logger.info("Setup created, now checking status");

                    // Now get the status
                    client.get(TEST_PORT, "localhost", "/api/v1/database-setup/" + testSetupId + "/status")
                            .timeout(10000)
                            .send(testContext.succeeding(statusResponse -> testContext.verify(() -> {
                                logger.info("Status response: {} - {}", statusResponse.statusCode(), statusResponse.bodyAsString());

                                assertEquals(200, statusResponse.statusCode());
                                String status = statusResponse.bodyAsString();
                                assertTrue(status.contains("ACTIVE") || status.contains("\"ACTIVE\""));

                                logger.info("Setup status retrieved successfully");
                                testContext.completeNow();
                            })));
                }));
    }
    
    @Test
    @Order(5)
    void testSendMessage(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Testing Send Message ===");

        JsonObject messageRequest = new JsonObject()
                .put("payload", new JsonObject().put("orderId", "12345").put("amount", 99.99))
                .put("priority", 5)
                .put("headers", new JsonObject().put("source", "test"));

        // First create a setup with a queue
        JsonObject setupRequest = createTestSetupRequestWithQueue();

        client.post(TEST_PORT, "localhost", "/api/v1/database-setup/create")
                .putHeader("content-type", "application/json")
                .timeout(30000)
                .sendJsonObject(setupRequest, testContext.succeeding(createResponse -> {
                    logger.info("Setup with queue created, now sending message");

                    // Now send a message to the queue
                    client.post(TEST_PORT, "localhost", "/api/v1/queues/" + testSetupId + "/orders/messages")
                            .putHeader("content-type", "application/json")
                            .timeout(10000)
                            .sendJsonObject(messageRequest, testContext.succeeding(messageResponse -> testContext.verify(() -> {
                                logger.info("Message response: {} - {}", messageResponse.statusCode(), messageResponse.bodyAsString());

                                assertEquals(200, messageResponse.statusCode());
                                JsonObject body = messageResponse.bodyAsJsonObject();
                                assertNotNull(body);
                                assertEquals("Message sent successfully", body.getString("message"));
                                assertNotNull(body.getString("messageId"));

                                logger.info("Message sent successfully");
                                testContext.completeNow();
                            })));
                }));
    }
    
    @Test
    @Order(6)
    void testGetQueueStats(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Testing Get Queue Stats ===");

        // First create a setup with a queue
        JsonObject setupRequest = createTestSetupRequestWithQueue();

        client.post(TEST_PORT, "localhost", "/api/v1/database-setup/create")
                .putHeader("content-type", "application/json")
                .timeout(30000)
                .sendJsonObject(setupRequest, testContext.succeeding(createResponse -> {
                    logger.info("Setup with queue created, now getting queue stats");

                    // Now get queue stats
                    client.get(TEST_PORT, "localhost", "/api/v1/queues/" + testSetupId + "/orders/stats")
                            .timeout(10000)
                            .send(testContext.succeeding(statsResponse -> testContext.verify(() -> {
                                logger.info("Queue stats response: {} - {}", statsResponse.statusCode(), statsResponse.bodyAsString());

                                assertEquals(200, statsResponse.statusCode());
                                JsonObject body = statsResponse.bodyAsJsonObject();
                                assertNotNull(body);
                                assertEquals("orders", body.getString("queueName"));
                                assertTrue(body.containsKey("totalMessages"));
                                assertTrue(body.containsKey("pendingMessages"));
                                assertTrue(body.containsKey("processedMessages"));

                                logger.info("Queue stats retrieved successfully");
                                testContext.completeNow();
                            })));
                }));
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

        // First create a setup with an event store
        JsonObject setupRequest = createTestSetupRequestWithEventStore();

        client.post(TEST_PORT, "localhost", "/api/v1/database-setup/create")
                .putHeader("content-type", "application/json")
                .timeout(30000)
                .sendJsonObject(setupRequest, testContext.succeeding(createResponse -> {
                    logger.info("Setup with event store created, now storing event");

                    // Now store an event
                    client.post(TEST_PORT, "localhost", "/api/v1/eventstores/" + testSetupId + "/order-events/events")
                            .putHeader("content-type", "application/json")
                            .timeout(10000)
                            .sendJsonObject(eventRequest, testContext.succeeding(eventResponse -> testContext.verify(() -> {
                                logger.info("Store event response: {} - {}", eventResponse.statusCode(), eventResponse.bodyAsString());

                                assertEquals(200, eventResponse.statusCode());
                                JsonObject body = eventResponse.bodyAsJsonObject();
                                assertNotNull(body);
                                assertEquals("Event stored successfully", body.getString("message"));
                                assertNotNull(body.getString("eventId"));
                                assertNotNull(body.getString("transactionTime"));

                                logger.info("Event stored successfully");
                                testContext.completeNow();
                            })));
                }));
    }
    
    @Test
    @Order(8)
    void testQueryEvents(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Testing Query Events ===");

        // First create a setup with an event store
        JsonObject setupRequest = createTestSetupRequestWithEventStore();

        client.post(TEST_PORT, "localhost", "/api/v1/database-setup/create")
                .putHeader("content-type", "application/json")
                .timeout(30000)
                .sendJsonObject(setupRequest, testContext.succeeding(createResponse -> {
                    logger.info("Setup with event store created, now querying events");

                    // Now query events
                    client.get(TEST_PORT, "localhost", "/api/v1/eventstores/" + testSetupId + "/order-events/events")
                            .addQueryParam("eventType", "OrderCreated")
                            .addQueryParam("limit", "10")
                            .timeout(10000)
                            .send(testContext.succeeding(queryResponse -> testContext.verify(() -> {
                                logger.info("Query events response: {} - {}", queryResponse.statusCode(), queryResponse.bodyAsString());

                                assertEquals(200, queryResponse.statusCode());
                                JsonArray events = queryResponse.bodyAsJsonArray();
                                assertNotNull(events);
                                // Should be empty initially
                                assertEquals(0, events.size());

                                logger.info("Events queried successfully");
                                testContext.completeNow();
                            })));
                }));
    }

    @Test
    @Order(9)
    void testCorsHeaders(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Testing CORS Headers ===");

        client.options(TEST_PORT, "localhost", "/api/v1/database-setup/create")
                .putHeader("Origin", "http://localhost:3000")
                .putHeader("Access-Control-Request-Method", "POST")
                .timeout(5000)
                .send(testContext.succeeding(response -> testContext.verify(() -> {
                    logger.info("CORS response: {} - Headers: {}", response.statusCode(), response.headers().names());

                    assertEquals(200, response.statusCode());
                    assertNotNull(response.getHeader("Access-Control-Allow-Origin"));

                    logger.info("CORS headers working correctly");
                    testContext.completeNow();
                })));
    }

    @Test
    @Order(10)
    void testInvalidEndpoint(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Testing Invalid Endpoint ===");

        client.get(TEST_PORT, "localhost", "/api/v1/invalid-endpoint")
                .timeout(5000)
                .send(testContext.succeeding(response -> testContext.verify(() -> {
                    logger.info("Invalid endpoint response: {}", response.statusCode());

                    assertEquals(404, response.statusCode());

                    logger.info("Invalid endpoint properly rejected");
                    testContext.completeNow();
                })));
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

    private JsonObject createTestSetupRequestWithQueue() {
        JsonObject request = createTestSetupRequest();

        JsonArray queues = new JsonArray()
                .add(new JsonObject()
                        .put("queueName", "orders")
                        .put("maxRetries", 3)
                        .put("visibilityTimeoutSeconds", 30)
                        .put("deadLetterEnabled", true));

        request.put("queues", queues);
        return request;
    }

    private JsonObject createTestSetupRequestWithEventStore() {
        JsonObject request = createTestSetupRequest();

        JsonArray eventStores = new JsonArray()
                .add(new JsonObject()
                        .put("eventStoreName", "order-events")
                        .put("tableName", "order_events")
                        .put("biTemporalEnabled", true)
                        .put("notificationPrefix", "order_events_"));

        request.put("eventStores", eventStores);
        return request;
    }

    private JsonObject createCompleteTestSetupRequest() {
        JsonObject request = createTestSetupRequest();

        JsonArray queues = new JsonArray()
                .add(new JsonObject()
                        .put("queueName", "orders")
                        .put("maxRetries", 3)
                        .put("visibilityTimeoutSeconds", 30)
                        .put("deadLetterEnabled", true))
                .add(new JsonObject()
                        .put("queueName", "notifications")
                        .put("maxRetries", 5)
                        .put("visibilityTimeoutSeconds", 60)
                        .put("deadLetterEnabled", true));

        JsonArray eventStores = new JsonArray()
                .add(new JsonObject()
                        .put("eventStoreName", "order-events")
                        .put("tableName", "order_events")
                        .put("biTemporalEnabled", true)
                        .put("notificationPrefix", "order_events_"))
                .add(new JsonObject()
                        .put("eventStoreName", "user-events")
                        .put("tableName", "user_events")
                        .put("biTemporalEnabled", true)
                        .put("notificationPrefix", "user_events_"));

        request.put("queues", queues);
        request.put("eventStores", eventStores);
        return request;
    }
}
