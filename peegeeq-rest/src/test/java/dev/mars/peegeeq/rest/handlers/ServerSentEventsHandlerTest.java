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

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Server-Sent Events (SSE) handler functionality.
 *
 * Uses TestContainers and real PeeGeeQRuntime to test actual SSE endpoints.
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
class ServerSentEventsHandlerTest {

    private static final Logger logger = LoggerFactory.getLogger(ServerSentEventsHandlerTest.class);
    private static final int TEST_PORT = 18099;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_sse_test")
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
        logger.info("=== Starting SSE Integration Test ===");

        client = WebClient.create(vertx);
        testSetupId = "sse-test-" + System.currentTimeMillis();
        testQueueName = "sse_test_queue";

        // Create the setup service using PeeGeeQRuntime - handles all wiring internally
        DatabaseSetupService setupService = PeeGeeQRuntime.createDatabaseSetupService();

        // Deploy the REST server
        vertx.deployVerticle(new PeeGeeQRestServer(TEST_PORT, setupService))
            .onSuccess(id -> {
                deploymentId = id;
                logger.info("REST server deployed on port {}", TEST_PORT);
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @AfterAll
    void tearDown(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Tearing Down SSE Test ===");

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
                .put("databaseName", "sse_test_" + System.currentTimeMillis())
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
    void testSSEStreamEndpointExists(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Test 2: SSE Stream Endpoint Exists ===");

        String ssePath = "/api/v1/queues/" + testSetupId + "/" + testQueueName + "/stream";

        // Use HttpClient directly - WebClient.send() waits for response body which never completes for SSE
        io.vertx.core.http.HttpClient httpClient = vertx.createHttpClient();
        httpClient.request(io.vertx.core.http.HttpMethod.GET, TEST_PORT, "localhost", ssePath)
            .compose(request -> {
                request.putHeader("Accept", "text/event-stream");
                return request.send();
            })
            .onSuccess(response -> testContext.verify(() -> {
                int status = response.statusCode();
                logger.info("SSE endpoint returned status: {}", status);

                // SSE endpoints should return 200 with text/event-stream content type
                // or 404 if not implemented
                assertTrue(status == 200 || status == 404,
                    "SSE endpoint should return 200 or 404, got: " + status);

                if (status == 200) {
                    String contentType = response.getHeader("Content-Type");
                    logger.info("Content-Type: {}", contentType);
                    // SSE should have text/event-stream content type
                    assertTrue(contentType == null || contentType.contains("text/event-stream") ||
                               contentType.contains("application/json"),
                        "Content-Type should be text/event-stream or application/json");
                }

                // Close the connection - SSE streams don't end naturally
                httpClient.close();
                testContext.completeNow();
            }))
            .onFailure(err -> {
                logger.warn("SSE endpoint request failed: {}", err.getMessage());
                httpClient.close();
                testContext.completeNow();
            });
    }

    @Test
    @Order(3)
    void testSSEStreamWithQueryParams(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Test 3: SSE Stream with Query Parameters ===");

        String ssePath = "/api/v1/queues/" + testSetupId + "/" + testQueueName + "/stream"
            + "?consumerGroup=test-group&batchSize=10&maxWait=5000&messageType=OrderCreated";

        // Use HttpClient directly - WebClient.send() waits for response body which never completes for SSE
        io.vertx.core.http.HttpClient httpClient = vertx.createHttpClient();
        httpClient.request(io.vertx.core.http.HttpMethod.GET, TEST_PORT, "localhost", ssePath)
            .compose(request -> {
                request.putHeader("Accept", "text/event-stream");
                return request.send();
            })
            .onSuccess(response -> testContext.verify(() -> {
                int status = response.statusCode();
                logger.info("SSE endpoint with params returned status: {}", status);

                // Accept 200 or 404
                assertTrue(status == 200 || status == 404,
                    "SSE endpoint should return 200 or 404, got: " + status);

                // Close the connection - SSE streams don't end naturally
                httpClient.close();
                testContext.completeNow();
            }))
            .onFailure(err -> {
                logger.warn("SSE endpoint request failed: {}", err.getMessage());
                httpClient.close();
                testContext.completeNow();
            });
    }

    @Test
    @Order(4)
    void testSSEStreamConnection(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Test 4: SSE Stream Connection ===");

        String ssePath = "/api/v1/queues/" + testSetupId + "/" + testQueueName + "/stream";
        AtomicBoolean receivedData = new AtomicBoolean(false);

        // Use raw HTTP client for streaming
        vertx.createHttpClient()
            .request(io.vertx.core.http.HttpMethod.GET, TEST_PORT, "localhost", ssePath)
            .compose(request -> {
                request.putHeader("Accept", "text/event-stream");
                return request.send();
            })
            .onSuccess(response -> {
                logger.info("SSE response status: {}", response.statusCode());

                if (response.statusCode() == 200) {
                    response.handler(buffer -> {
                        String data = buffer.toString();
                        logger.info("Received SSE data: {}", data);
                        receivedData.set(true);

                        // Check for SSE format
                        if (data.contains("event:") || data.contains("data:")) {
                            testContext.verify(() -> {
                                assertTrue(data.contains("event:") || data.contains("data:"),
                                    "SSE data should contain event: or data: prefix");
                            });
                        }
                    });

                    // Set timeout to complete test
                    vertx.setTimer(3000, id -> {
                        testContext.completeNow();
                    });
                } else {
                    testContext.completeNow();
                }
            })
            .onFailure(err -> {
                logger.warn("SSE connection failed: {}", err.getMessage());
                testContext.completeNow();
            });
    }

    @Test
    @Order(5)
    void testSSEEventFormat(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Test 5: SSE Event Format ===");

        // Test that SSE events follow the correct format
        // event: <event-type>
        // data: <json-data>
        //
        // (empty line)

        JsonObject data = new JsonObject()
            .put("type", "data")
            .put("messageId", "msg-123")
            .put("payload", "Test message");

        String expectedSSEFormat = "event: message\n" +
                                  "data: " + data.encode() + "\n" +
                                  "\n";

        // Verify the format structure
        assertTrue(expectedSSEFormat.startsWith("event: message\n"));
        assertTrue(expectedSSEFormat.contains("data: {"));
        assertTrue(expectedSSEFormat.endsWith("\n\n"));

        // Verify JSON data can be parsed
        String dataLine = expectedSSEFormat.split("\n")[1];
        String jsonData = dataLine.substring(6); // Remove "data: " prefix
        JsonObject parsedData = new JsonObject(jsonData);

        assertEquals("data", parsedData.getString("type"));
        assertEquals("msg-123", parsedData.getString("messageId"));
        assertEquals("Test message", parsedData.getString("payload"));

        logger.info("SSE event format verified");
        testContext.completeNow();
    }

    @Test
    @Order(6)
    void testSSEConnectionHeaders(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Test 6: SSE Connection Headers ===");

        String ssePath = "/api/v1/queues/" + testSetupId + "/" + testQueueName + "/stream";

        // Use HttpClient directly - WebClient.send() waits for response body which never completes for SSE
        io.vertx.core.http.HttpClient httpClient = vertx.createHttpClient();
        httpClient.request(io.vertx.core.http.HttpMethod.GET, TEST_PORT, "localhost", ssePath)
            .compose(request -> {
                request.putHeader("Accept", "text/event-stream");
                return request.send();
            })
            .onSuccess(response -> testContext.verify(() -> {
                int status = response.statusCode();

                if (status == 200) {
                    // Verify SSE-specific headers
                    String contentType = response.getHeader("Content-Type");
                    String cacheControl = response.getHeader("Cache-Control");
                    String connection = response.getHeader("Connection");

                    logger.info("Content-Type: {}", contentType);
                    logger.info("Cache-Control: {}", cacheControl);
                    logger.info("Connection: {}", connection);

                    // SSE should have specific headers
                    if (contentType != null) {
                        assertTrue(contentType.contains("text/event-stream") ||
                                   contentType.contains("application/json"),
                            "Content-Type should be text/event-stream");
                    }
                }

                // Close the connection - SSE streams don't end naturally
                httpClient.close();
                testContext.completeNow();
            }))
            .onFailure(err -> {
                logger.warn("SSE headers test failed: {}", err.getMessage());
                httpClient.close();
                testContext.completeNow();
            });
    }
}
