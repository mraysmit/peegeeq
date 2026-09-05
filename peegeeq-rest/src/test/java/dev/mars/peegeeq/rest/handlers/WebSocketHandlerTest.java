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
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for WebSocket handler functionality.
 *
 * Uses TestContainers and real PeeGeeQRuntime to test actual WebSocket endpoints.
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
class WebSocketHandlerTest {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketHandlerTest.class);
    private static final int TEST_PORT = 18098;

    @Container
    static PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE);
        container.withDatabaseName("peegeeq_websocket_test");
        container.withUsername("peegeeq_test");
        container.withPassword("peegeeq_test");
        container.withSharedMemorySize(256 * 1024 * 1024L);
        container.withReuse(false);
        return container;
    }

    private WebClient client;
    private WebSocketClient wsClient;
    private String deploymentId;
    private String testSetupId;
    private String testQueueName;

    @BeforeAll
    void setUp(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Starting WebSocket Integration Test ===");

        client = WebClient.create(vertx);
        wsClient = vertx.createWebSocketClient();
        testSetupId = "ws-test-" + System.currentTimeMillis();
        testQueueName = "ws_test_queue";

        // Create the setup service using PeeGeeQRuntime - handles all wiring internally
        DatabaseSetupService setupService = PeeGeeQRuntime.createDatabaseSetupService();

        // Deploy the REST server
        RestServerConfig testConfig = new RestServerConfig(TEST_PORT, RestServerConfig.MonitoringConfig.defaults(), java.util.List.of("*"));
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
        logger.info("=== Tearing Down WebSocket Test ===");

        if (client != null) {
            client.close();
        }

        Future<Void> cleanup = Future.succeededFuture();
        if (wsClient != null) {
            cleanup = cleanup.compose(v -> wsClient.close());
        }
        if (deploymentId != null) {
            cleanup = cleanup.eventually(() -> vertx.undeploy(deploymentId));
        }

        cleanup
            .onSuccess(v -> {
                logger.info("Test cleanup completed");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
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
                .put("databaseName", "ws_test_" + System.currentTimeMillis())
                .put("username", postgres.getUsername())
                .put("password", postgres.getPassword())
                .put("schema", PostgreSQLTestConstants.TEST_SCHEMA)
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
            .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                assertEquals(201, response.statusCode(), "Setup should return 201 Created");
                JsonObject body = response.bodyAsJsonObject();
                assertEquals("ACTIVE", body.getString("status"));
                logger.info("Database setup with queue created successfully");
                testContext.completeNow();
            })));
    }

    @Test
    @Order(2)
    void testWebSocketConnection(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Test 2: WebSocket Connection ===");

        String wsPath = "/ws/queues/" + testSetupId + "/" + testQueueName;

        WebSocketConnectOptions options = new WebSocketConnectOptions()
            .setHost("localhost")
            .setPort(TEST_PORT)
            .setURI(wsPath);

        AtomicReference<WebSocket> wsRef = new AtomicReference<>();

        wsClient.connect(options)
            .onSuccess(ws -> {
                wsRef.set(ws);
                logger.info("WebSocket connected successfully");

                ws.textMessageHandler(message -> {
                    testContext.verify(() -> {
                        JsonObject msg = new JsonObject(message);
                        logger.info("Received WebSocket message: {}", msg.encode());

                        // First message should be welcome
                        if ("welcome".equals(msg.getString("type"))) {
                            assertNotNull(msg.getString("connectionId"), "connectionId should be present");
                            assertEquals(testSetupId, msg.getString("setupId"));
                            assertEquals(testQueueName, msg.getString("queueName"));

                            // Close connection and complete test
                            ws.close()
                                .onSuccess(v -> testContext.completeNow())
                                .onFailure(testContext::failNow);
                        }
                    });
                });

                ws.exceptionHandler(err -> {
                    logger.error("WebSocket error: {}", err.getMessage());
                    testContext.failNow(err);
                });
            })
            .onFailure(err -> {
                logger.error("WebSocket connection failed", err);
                testContext.failNow(err);
            });
    }

    @Test
    @Order(3)
    void testWebSocketPingPong(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Test 3: WebSocket Ping/Pong ===");

        String wsPath = "/ws/queues/" + testSetupId + "/" + testQueueName;

        WebSocketConnectOptions options = new WebSocketConnectOptions()
            .setHost("localhost")
            .setPort(TEST_PORT)
            .setURI(wsPath);

        wsClient.connect(options)
            .onSuccess(ws -> {
                logger.info("WebSocket connected for ping/pong test");

                ws.textMessageHandler(message -> {
                    testContext.verify(() -> {
                        JsonObject msg = new JsonObject(message);
                        logger.info("Received: {}", msg.encode());

                        if ("welcome".equals(msg.getString("type"))) {
                            // Send ping
                            JsonObject ping = new JsonObject()
                                .put("type", "ping")
                                .put("id", "test-ping-123");
                            ws.writeTextMessage(ping.encode()).onFailure(testContext::failNow);
                            logger.info("Sent ping");
                        } else if ("pong".equals(msg.getString("type"))) {
                            assertEquals("test-ping-123", msg.getString("id"));
                            ws.close()
                                .onSuccess(v -> testContext.completeNow())
                                .onFailure(testContext::failNow);
                        }
                    });
                });

                ws.exceptionHandler(err -> {
                    logger.error("WebSocket error: {}", err.getMessage());
                    testContext.failNow(err);
                });
            })
            .onFailure(err -> {
                logger.error("WebSocket connection failed", err);
                testContext.failNow(err);
            });
    }

    @Test
    @Order(4)
    @Timeout(value = 5, timeUnit = TimeUnit.SECONDS)
    void testWebSocketSubscription(VertxTestContext testContext) {
        logger.info("=== Test 4: WebSocket Subscription ===");

        String wsPath = "/ws/queues/" + testSetupId + "/" + testQueueName;

        WebSocketConnectOptions options = new WebSocketConnectOptions()
            .setHost("localhost")
            .setPort(TEST_PORT)
            .setURI(wsPath);

        wsClient.connect(options)
            .onSuccess(ws -> {
                logger.info("WebSocket connected for subscription test");

                ws.textMessageHandler(message -> {
                    testContext.verify(() -> {
                        JsonObject msg = new JsonObject(message);
                        logger.info("Received: {}", msg.encode());

                        if ("subscribed".equals(msg.getString("type")) && msg.containsKey("queueName")) {
                            // The automatic tail-ready frame is distinct from the configuration acknowledgement.
                            assertEquals(testQueueName, msg.getString("queueName"));
                            assertNotNull(msg.getString("connectionId"));
                            assertFalse(msg.containsKey("consumerGroup"));
                            JsonObject subscribe = new JsonObject()
                                .put("type", "subscribe")
                                .put("consumerGroup", "test-group")
                                .put("filters", new JsonObject()
                                    .put("messageType", "OrderCreated"));
                            ws.writeTextMessage(subscribe.encode()).onFailure(testContext::failNow);
                            logger.info("Sent subscribe");
                        } else if ("subscribed".equals(msg.getString("type"))) {
                            assertEquals("test-group", msg.getString("consumerGroup"));
                            assertEquals(new JsonObject().put("messageType", "OrderCreated"),
                                msg.getJsonObject("filters"));
                            ws.close()
                                .onSuccess(v -> testContext.completeNow())
                                .onFailure(testContext::failNow);
                        } else if ("error".equals(msg.getString("type"))) {
                            testContext.failNow(new AssertionError("Subscription failed: " + msg.encode()));
                        }
                    });
                });

                ws.exceptionHandler(err -> {
                    logger.error("WebSocket error: {}", err.getMessage());
                    testContext.failNow(err);
                });
            })
            .onFailure(err -> {
                logger.error("WebSocket connection failed", err);
                testContext.failNow(err);
            });
    }

    @Test
    @Order(5)
    @Timeout(value = 5, timeUnit = TimeUnit.SECONDS)
    void testWebSocketConfiguration(VertxTestContext testContext) {
        logger.info("=== Test 5: WebSocket Configuration ===");

        String wsPath = "/ws/queues/" + testSetupId + "/" + testQueueName;

        WebSocketConnectOptions options = new WebSocketConnectOptions()
            .setHost("localhost")
            .setPort(TEST_PORT)
            .setURI(wsPath);

        wsClient.connect(options)
            .onSuccess(ws -> {
                logger.info("WebSocket connected for configuration test");

                ws.textMessageHandler(message -> {
                    testContext.verify(() -> {
                        JsonObject msg = new JsonObject(message);
                        logger.info("Received: {}", msg.encode());

                        if ("welcome".equals(msg.getString("type"))) {
                            // Send configure message
                            JsonObject configure = new JsonObject()
                                .put("type", "configure")
                                .put("batchSize", 10)
                                .put("maxWaitTime", 30000L);
                            ws.writeTextMessage(configure.encode()).onFailure(testContext::failNow);
                            logger.info("Sent configure");
                        } else if ("configured".equals(msg.getString("type"))) {
                            assertEquals(10, msg.getInteger("batchSize"));
                            assertEquals(30000L, msg.getLong("maxWaitTime"));
                            ws.close()
                                .onSuccess(v -> testContext.completeNow())
                                .onFailure(testContext::failNow);
                        }
                    });
                });

                ws.exceptionHandler(err -> {
                    logger.error("WebSocket error: {}", err.getMessage());
                    testContext.failNow(err);
                });
            })
            .onFailure(err -> {
                logger.error("WebSocket connection failed", err);
                testContext.failNow(err);
            });
    }

    @Test
    @Order(6)
    void testWebSocketPathRejectsPlainHttpRequest(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Test 6: WebSocket Path Rejects Plain HTTP Request ===");

        String wsPath = "/ws/queues/" + testSetupId + "/" + testQueueName;

        client.get(TEST_PORT, "localhost", wsPath)
            .timeout(5000)
            .send()
            .onSuccess(response -> testContext.verify(() -> {
                int status = response.statusCode();
                logger.info("HTTP request to WebSocket endpoint returned: {}", status);
                assertEquals(404, status,
                    "A plain HTTP request must not be accepted by the WebSocket-only handler");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

}
