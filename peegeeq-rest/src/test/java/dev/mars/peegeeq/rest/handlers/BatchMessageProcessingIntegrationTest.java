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
import dev.mars.peegeeq.rest.config.RestServerConfig;
import dev.mars.peegeeq.rest.PeeGeeQRestServer;
import dev.mars.peegeeq.runtime.PeeGeeQRuntime;
import dev.mars.peegeeq.test.categories.TestCategories;
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
 * Integration tests for batch message processing and enhanced message features.
 *
 * Uses TestContainers and real PeeGeeQRuntime to test:
 * - Batch message processing via HTTP endpoints
 * - Enhanced message features (headers, priority, delay)
 * - Single message with rich metadata
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-19
 * @version 2.0
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BatchMessageProcessingIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(BatchMessageProcessingIntegrationTest.class);
    private static final int TEST_PORT = 18100;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_batch_test")
            .withUsername("peegeeq_test")
            .withPassword("peegeeq_test")
            .withSharedMemorySize(256 * 1024 * 1024L)
            .withReuse(false);

    private WebClient client;
    private String deploymentId;
    private String testSetupId;
    private String testQueueName;

    @BeforeAll
    void setUp(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Starting Batch Message Processing Integration Test ===");

        client = WebClient.create(vertx);
        testSetupId = "batch-test-" + System.currentTimeMillis();
        testQueueName = "batch_queue";

        // Create the setup service using PeeGeeQRuntime - handles all wiring internally
        DatabaseSetupService setupService = PeeGeeQRuntime.createDatabaseSetupService();

        // Deploy the REST server
        RestServerConfig testConfig = new RestServerConfig(TEST_PORT, RestServerConfig.MonitoringConfig.defaults());
        vertx.deployVerticle(new PeeGeeQRestServer(testConfig, setupService))
            .onSuccess(id -> {
                deploymentId = id;
                logger.info("REST server deployed on port {}", TEST_PORT);
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @AfterAll
    void tearDown(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Tearing Down Batch Message Processing Test ===");

        if (client != null) {
            client.close();
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
    void testCreateDatabaseSetupWithQueue(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Test 1: Create Database Setup with Queue ===");

        JsonObject setupRequest = new JsonObject()
            .put("setupId", testSetupId)
            .put("databaseConfig", new JsonObject()
                .put("host", postgres.getHost())
                .put("port", postgres.getFirstMappedPort())
                .put("databaseName", "phase2_test_" + System.currentTimeMillis())
                .put("username", postgres.getUsername())
                .put("password", postgres.getPassword())
                .put("schema", "public")
                .put("templateDatabase", "template0")
                .put("encoding", "UTF8"))
            .put("queues", new JsonArray()
                .add(new JsonObject()
                    .put("queueName", testQueueName)
                    .put("maxRetries", 3)
                    .put("visibilityTimeoutSeconds", 30)))
            .put("eventStores", new JsonArray())
            .put("additionalProperties", new JsonObject());

        client.post(TEST_PORT, "localhost", "/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .timeout(30000)
            .sendJsonObject(setupRequest)
            .onSuccess(response -> testContext.verify(() -> {
                assertEquals(201, response.statusCode(), "Setup should return 201 Created");
                JsonObject body = response.bodyAsJsonObject();
                assertEquals("ACTIVE", body.getString("status"));
                logger.info("Database setup with queue created successfully");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(2)
    void testEnhancedSingleMessage(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Test 2: Enhanced Single Message ===");

        JsonObject messageRequest = new JsonObject()
            .put("payload", new JsonObject()
                .put("eventType", "OrderCreated")
                .put("orderId", "ORD-12345")
                .put("customerId", "CUST-67890")
                .put("amount", 299.99)
                .put("currency", "USD")
                .put("items", new JsonArray()
                    .add(new JsonObject().put("sku", "ITEM-001").put("quantity", 2).put("price", 149.99))
                    .add(new JsonObject().put("sku", "ITEM-002").put("quantity", 1).put("price", 0.01))))
            .put("headers", new JsonObject()
                .put("source", "order-service")
                .put("version", "2.1")
                .put("region", "US-WEST")
                .put("correlationId", "CORR-ABC123")
                .put("traceId", "TRACE-XYZ789"))
            .put("priority", 7)
            .put("delaySeconds", 0)
            .put("messageType", "OrderCreatedEvent");

        String path = "/api/v1/queues/" + testSetupId + "/" + testQueueName + "/messages";

        client.post(TEST_PORT, "localhost", path)
            .putHeader("content-type", "application/json")
            .timeout(10000)
            .sendJsonObject(messageRequest)
            .onSuccess(response -> testContext.verify(() -> {
                int status = response.statusCode();
                logger.info("Enhanced single message response status: {}", status);

                // Accept 201 (created) or 200 (ok)
                assertTrue(status == 200 || status == 201,
                    "Message should be accepted, got: " + status);

                JsonObject body = response.bodyAsJsonObject();
                assertNotNull(body, "Response body should not be null");
                logger.info("Response: {}", body.encode());

                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(3)
    void testBatchMessageProcessing(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Test 3: Batch Message Processing ===");

        JsonArray messages = new JsonArray()
            .add(new JsonObject()
                .put("payload", new JsonObject()
                    .put("eventType", "OrderCreated")
                    .put("orderId", "ORD-001"))
                .put("priority", 8)
                .put("messageType", "OrderEvent"))
            .add(new JsonObject()
                .put("payload", new JsonObject()
                    .put("commandType", "ProcessPayment")
                    .put("paymentId", "PAY-001"))
                .put("priority", 9)
                .put("delaySeconds", 5))
            .add(new JsonObject()
                .put("payload", "Simple notification message")
                .put("priority", 3)
                .put("headers", new JsonObject()
                    .put("category", "notification")
                    .put("urgency", "low")))
            .add(new JsonObject()
                .put("payload", new JsonObject()
                    .put("userId", "USER-123")
                    .put("action", "login"))
                .put("priority", 5));

        JsonObject batchRequest = new JsonObject()
            .put("messages", messages)
            .put("failOnError", false)
            .put("maxBatchSize", 10);

        String path = "/api/v1/queues/" + testSetupId + "/" + testQueueName + "/messages/batch";

        client.post(TEST_PORT, "localhost", path)
            .putHeader("content-type", "application/json")
            .timeout(15000)
            .sendJsonObject(batchRequest)
            .onSuccess(response -> testContext.verify(() -> {
                int status = response.statusCode();
                logger.info("Batch message response status: {}", status);

                // Accept 200, 201, or 207 (multi-status)
                assertTrue(status == 200 || status == 201 || status == 207,
                    "Batch should be processed, got: " + status);

                JsonObject body = response.bodyAsJsonObject();
                assertNotNull(body, "Response body should not be null");
                logger.info("Batch response: {}", body.encode());

                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(4)
    void testMessageWithHeaders(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Test 4: Message with Headers ===");

        JsonObject messageRequest = new JsonObject()
            .put("payload", new JsonObject()
                .put("action", "test-action")
                .put("data", "test-data"))
            .put("headers", new JsonObject()
                .put("X-Correlation-Id", "corr-123")
                .put("X-Trace-Id", "trace-456")
                .put("X-Source", "phase2-test"));

        String path = "/api/v1/queues/" + testSetupId + "/" + testQueueName + "/messages";

        client.post(TEST_PORT, "localhost", path)
            .putHeader("content-type", "application/json")
            .timeout(10000)
            .sendJsonObject(messageRequest)
            .onSuccess(response -> testContext.verify(() -> {
                int status = response.statusCode();
                logger.info("Message with headers response status: {}", status);

                assertTrue(status == 200 || status == 201,
                    "Message should be accepted, got: " + status);

                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(5)
    void testMessageWithPriority(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Test 5: Message with Priority ===");

        JsonObject messageRequest = new JsonObject()
            .put("payload", "High priority message")
            .put("priority", 10)
            .put("messageType", "UrgentNotification");

        String path = "/api/v1/queues/" + testSetupId + "/" + testQueueName + "/messages";

        client.post(TEST_PORT, "localhost", path)
            .putHeader("content-type", "application/json")
            .timeout(10000)
            .sendJsonObject(messageRequest)
            .onSuccess(response -> testContext.verify(() -> {
                int status = response.statusCode();
                logger.info("Priority message response status: {}", status);

                assertTrue(status == 200 || status == 201,
                    "Message should be accepted, got: " + status);

                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(6)
    void testMessageWithDelay(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Test 6: Message with Delay ===");

        JsonObject messageRequest = new JsonObject()
            .put("payload", new JsonObject()
                .put("scheduledTask", "cleanup")
                .put("targetTime", System.currentTimeMillis() + 60000))
            .put("delaySeconds", 60)
            .put("messageType", "ScheduledTask");

        String path = "/api/v1/queues/" + testSetupId + "/" + testQueueName + "/messages";

        client.post(TEST_PORT, "localhost", path)
            .putHeader("content-type", "application/json")
            .timeout(10000)
            .sendJsonObject(messageRequest)
            .onSuccess(response -> testContext.verify(() -> {
                int status = response.statusCode();
                logger.info("Delayed message response status: {}", status);

                assertTrue(status == 200 || status == 201,
                    "Message should be accepted, got: " + status);

                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }
}
