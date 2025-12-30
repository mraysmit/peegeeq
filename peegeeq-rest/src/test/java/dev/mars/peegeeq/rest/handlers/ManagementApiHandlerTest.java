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
 * Integration tests for Management API Handler functionality.
 *
 * Uses TestContainers and real PeeGeeQRuntime to test actual REST endpoints.
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
class ManagementApiHandlerTest {

    private static final Logger logger = LoggerFactory.getLogger(ManagementApiHandlerTest.class);
    private static final int TEST_PORT = 18095;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_management_test")
            .withUsername("peegeeq_test")
            .withPassword("peegeeq_test")
            .withSharedMemorySize(256 * 1024 * 1024L)
            .withReuse(false);

    private WebClient client;
    private String deploymentId;
    private String testSetupId;

    @BeforeAll
    void setUp(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Starting Management API Integration Test ===");

        client = WebClient.create(vertx);
        testSetupId = "mgmt-test-" + System.currentTimeMillis();

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
        logger.info("=== Tearing Down Management API Test ===");

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
    void testHealthCheckEndpoint(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Test 1: Health Check Endpoint ===");

        client.get(TEST_PORT, "localhost", "/api/v1/health")
            .timeout(10000)
            .send()
            .onSuccess(response -> testContext.verify(() -> {
                assertEquals(200, response.statusCode(), "Health check should return 200 OK");

                JsonObject health = response.bodyAsJsonObject();
                assertNotNull(health, "Response body should not be null");

                // Verify required fields
                assertEquals("UP", health.getString("status"), "Status should be UP");
                assertNotNull(health.getString("timestamp"), "Timestamp should be present");
                assertNotNull(health.getString("uptime"), "Uptime should be present");
                assertEquals("1.0.0", health.getString("version"), "Version should be 1.0.0");
                assertEquals("Phase-5-Management-UI", health.getString("build"), "Build should match");

                logger.info("Health check response: {}", health.encode());
                logger.info("Health check endpoint test passed");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(2)
    void testSystemOverviewEndpoint(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Test 2: System Overview Endpoint ===");

        client.get(TEST_PORT, "localhost", "/api/v1/management/overview")
            .timeout(10000)
            .send()
            .onSuccess(response -> testContext.verify(() -> {
                assertEquals(200, response.statusCode(), "Overview should return 200 OK");

                JsonObject overview = response.bodyAsJsonObject();
                assertNotNull(overview, "Response body should not be null");

                // Verify required sections
                assertNotNull(overview.getJsonObject("systemStats"), "systemStats should be present");
                assertNotNull(overview.getJsonObject("queueSummary"), "queueSummary should be present");
                assertNotNull(overview.getJsonObject("consumerGroupSummary"), "consumerGroupSummary should be present");
                assertNotNull(overview.getJsonObject("eventStoreSummary"), "eventStoreSummary should be present");
                assertTrue(overview.containsKey("timestamp"), "timestamp should be present");

                // Verify systemStats structure
                JsonObject stats = overview.getJsonObject("systemStats");
                assertTrue(stats.containsKey("totalQueues"), "totalQueues should be present");
                assertTrue(stats.containsKey("totalConsumerGroups"), "totalConsumerGroups should be present");
                assertTrue(stats.containsKey("totalEventStores"), "totalEventStores should be present");
                assertTrue(stats.containsKey("uptime"), "uptime should be present");

                logger.info("System overview response: {}", overview.encode());
                logger.info("System overview endpoint test passed");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(3)
    void testQueuesEndpointEmpty(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Test 3: Queues Endpoint (Empty State) ===");

        client.get(TEST_PORT, "localhost", "/api/v1/management/queues")
            .timeout(10000)
            .send()
            .onSuccess(response -> testContext.verify(() -> {
                assertEquals(200, response.statusCode(), "Queues endpoint should return 200 OK");

                JsonObject body = response.bodyAsJsonObject();
                assertNotNull(body, "Response body should not be null");

                assertEquals("Queues retrieved successfully", body.getString("message"));
                assertTrue(body.containsKey("queueCount"), "queueCount should be present");
                assertTrue(body.containsKey("queues"), "queues array should be present");
                assertTrue(body.containsKey("timestamp"), "timestamp should be present");

                // Initially no queues should exist
                assertEquals(0, body.getInteger("queueCount"), "No queues should exist initially");

                logger.info("Queues response: {}", body.encode());
                logger.info("Queues endpoint (empty) test passed");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(4)
    void testQueuesEndpointWithSetup(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Test 4: Queues Endpoint (With Setup) ===");

        // First create a database setup with a queue
        JsonObject setupRequest = new JsonObject()
            .put("setupId", testSetupId)
            .put("databaseConfig", new JsonObject()
                .put("host", postgres.getHost())
                .put("port", postgres.getFirstMappedPort())
                .put("databaseName", "mgmt_queue_test_" + System.currentTimeMillis())
                .put("username", postgres.getUsername())
                .put("password", postgres.getPassword())
                .put("schema", "public")
                .put("templateDatabase", "template0")
                .put("encoding", "UTF8"))
            .put("queues", new JsonArray()
                .add(new JsonObject()
                    .put("queueName", "test_orders")
                    .put("maxRetries", 3)
                    .put("visibilityTimeoutSeconds", 30)))
            .put("eventStores", new JsonArray())
            .put("additionalProperties", new JsonObject());

        client.post(TEST_PORT, "localhost", "/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .timeout(30000)
            .sendJsonObject(setupRequest)
            .compose(setupResponse -> {
                testContext.verify(() -> {
                    assertEquals(201, setupResponse.statusCode(), "Setup should return 201 Created");
                    logger.info("Database setup created successfully");
                });

                // Now query the queues endpoint
                return client.get(TEST_PORT, "localhost", "/api/v1/management/queues")
                    .timeout(10000)
                    .send();
            })
            .onSuccess(response -> testContext.verify(() -> {
                assertEquals(200, response.statusCode(), "Queues endpoint should return 200 OK");

                JsonObject body = response.bodyAsJsonObject();
                assertNotNull(body, "Response body should not be null");

                int queueCount = body.getInteger("queueCount");
                assertTrue(queueCount >= 1, "At least one queue should exist after setup");

                JsonArray queues = body.getJsonArray("queues");
                assertNotNull(queues, "queues array should not be null");
                assertTrue(queues.size() >= 1, "queues array should have at least one entry");

                // Verify queue structure matches frontend expectations
                JsonObject queue = queues.getJsonObject(0);
                validateQueueStructure(queue, "First queue in response");

                logger.info("Queues response with setup: {}", body.encode());
                logger.info("Queues endpoint (with setup) test passed");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(5)
    void testConsumerGroupsEndpoint(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Test 5: Consumer Groups Endpoint ===");

        client.get(TEST_PORT, "localhost", "/api/v1/management/consumer-groups")
            .timeout(10000)
            .send()
            .onSuccess(response -> testContext.verify(() -> {
                assertEquals(200, response.statusCode(), "Consumer groups should return 200 OK");

                JsonObject body = response.bodyAsJsonObject();
                assertNotNull(body, "Response body should not be null");

                assertEquals("Consumer groups retrieved successfully", body.getString("message"));
                assertTrue(body.containsKey("groupCount"), "groupCount should be present");
                assertTrue(body.containsKey("consumerGroups"), "consumerGroups array should be present");
                assertTrue(body.containsKey("timestamp"), "timestamp should be present");

                logger.info("Consumer groups response: {}", body.encode());
                logger.info("Consumer groups endpoint test passed");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(6)
    void testSystemMetricsEndpoint(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Test 6: System Metrics Endpoint ===");

        client.get(TEST_PORT, "localhost", "/api/v1/management/metrics")
            .timeout(10000)
            .send()
            .onSuccess(response -> testContext.verify(() -> {
                assertEquals(200, response.statusCode(), "Metrics should return 200 OK");

                JsonObject metrics = response.bodyAsJsonObject();
                assertNotNull(metrics, "Response body should not be null");

                // Verify required metric fields
                assertTrue(metrics.containsKey("timestamp"), "timestamp should be present");
                assertTrue(metrics.containsKey("uptime"), "uptime should be present");
                assertTrue(metrics.containsKey("memoryUsed"), "memoryUsed should be present");
                assertTrue(metrics.containsKey("memoryTotal"), "memoryTotal should be present");
                assertTrue(metrics.containsKey("cpuCores"), "cpuCores should be present");

                // Verify values are reasonable
                assertTrue(metrics.getLong("uptime") >= 0, "uptime should be non-negative");
                assertTrue(metrics.getLong("memoryUsed") > 0, "memoryUsed should be positive");
                assertTrue(metrics.getInteger("cpuCores") > 0, "cpuCores should be positive");

                logger.info("System metrics response: {}", metrics.encode());
                logger.info("System metrics endpoint test passed");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    /**
     * Validates that a queue object has the structure expected by the frontend.
     * This ensures the backend API contract matches what the UI needs.
     *
     * Expected structure (as returned by backend):
     * {
     *   "name": "string",
     *   "setup": "string",
     *   "implementationType": "string",
     *   "status": "string",
     *   "statistics": {
     *     "totalMessages": number,
     *     "activeConsumers": number,
     *     "messagesPerSecond": number,
     *     "avgProcessingTimeMs": number
     *   }
     * }
     */
    private void validateQueueStructure(JsonObject queue, String context) {
        // Validate top-level required fields (as actually returned by backend)
        assertNotNull(queue.getString("name"), context + ": Queue must have 'name' field");
        assertNotNull(queue.getString("setup"), context + ": Queue must have 'setup' field");
        assertNotNull(queue.getString("implementationType"), context + ": Queue must have 'implementationType' field");
        assertNotNull(queue.getString("status"), context + ": Queue must have 'status' field");

        // Validate nested statistics object - THIS IS CRITICAL FOR FRONTEND
        assertTrue(queue.containsKey("statistics"),
            context + ": Queue must have 'statistics' object (not flat fields)");

        JsonObject statistics = queue.getJsonObject("statistics");
        assertNotNull(statistics, context + ": 'statistics' must be a JSON object, not null");

        // Validate all statistics fields that the frontend uses
        assertTrue(statistics.containsKey("totalMessages"),
            context + ": statistics.totalMessages is required by frontend");
        assertTrue(statistics.containsKey("activeConsumers"),
            context + ": statistics.activeConsumers is required by frontend");
        assertTrue(statistics.containsKey("messagesPerSecond"),
            context + ": statistics.messagesPerSecond is required by frontend");
        assertTrue(statistics.containsKey("avgProcessingTimeMs"),
            context + ": statistics.avgProcessingTimeMs is required by frontend");

        // Validate types to ensure frontend won't get runtime errors
        assertNotNull(statistics.getValue("totalMessages"),
            context + ": statistics.totalMessages must not be null");
        assertNotNull(statistics.getValue("activeConsumers"),
            context + ": statistics.activeConsumers must not be null");
        assertNotNull(statistics.getValue("messagesPerSecond"),
            context + ": statistics.messagesPerSecond must not be null");
        assertNotNull(statistics.getValue("avgProcessingTimeMs"),
            context + ": statistics.avgProcessingTimeMs must not be null");

        // Ensure no flat statistics fields exist (common mistake)
        assertFalse(queue.containsKey("totalMessages"),
            context + ": Queue should NOT have flat 'totalMessages' field - must be in statistics object");
        assertFalse(queue.containsKey("activeConsumers"),
            context + ": Queue should NOT have flat 'activeConsumers' field - must be in statistics object");
        assertFalse(queue.containsKey("messagesPerSecond"),
            context + ": Queue should NOT have flat 'messagesPerSecond' field - must be in statistics object");
        assertFalse(queue.containsKey("avgProcessingTimeMs"),
            context + ": Queue should NOT have flat 'avgProcessingTimeMs' field - must be in statistics object");

        logger.debug("{}: Queue structure validation passed - {}", context, queue.getString("name"));
    }
}
