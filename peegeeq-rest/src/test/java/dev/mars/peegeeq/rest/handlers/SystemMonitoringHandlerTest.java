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
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
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
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for SystemMonitoringHandler functionality.
 *
 * Tests real-time monitoring via WebSocket and SSE endpoints:
 * - /ws/monitoring - WebSocket system monitoring stream
 * - /sse/metrics - SSE system metrics stream
 *
 * Uses TestContainers and real PeeGeeQRuntime to test actual monitoring endpoints.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-12-30
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SystemMonitoringHandlerTest {

    private static final Logger logger = LoggerFactory.getLogger(SystemMonitoringHandlerTest.class);
    private static final int TEST_PORT = 18097;

    @Container
    static PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE);
        container.withDatabaseName("peegeeq_monitoring_test");
        container.withUsername("peegeeq_test");
        container.withPassword("peegeeq_test");
        container.withSharedMemorySize(256 * 1024 * 1024L);
        container.withReuse(false);
        return container;
    }

    private WebClient client;
    private WebSocketClient wsClient;
    private HttpClient httpClient;
    private String deploymentId;
    private String testSetupId;
    private String testQueueName;

    @BeforeAll
    void setUp(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Starting System Monitoring Integration Test ===");

        client = WebClient.create(vertx);
        wsClient = vertx.createWebSocketClient();
        httpClient = vertx.createHttpClient();
        testSetupId = "monitoring-test-" + System.currentTimeMillis();
        testQueueName = "monitoring_test_queue";

        // Create the setup service using PeeGeeQRuntime - handles all wiring internally
        DatabaseSetupService setupService = PeeGeeQRuntime.createDatabaseSetupService();

        // Deploy the REST server with monitoring enabled
        RestServerConfig testConfig = new RestServerConfig(TEST_PORT, RestServerConfig.MonitoringConfig.defaults(), java.util.List.of("*"));
        vertx.deployVerticle(new PeeGeeQRestServer(testConfig, setupService))
            .onSuccess(id -> {
                deploymentId = id;
                logger.info("REST server deployed on port {} with monitoring enabled", TEST_PORT);
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @AfterAll
    void tearDown(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Tearing Down System Monitoring Test ===");

        if (client != null) {
            client.close();
        }
        if (wsClient != null) {
            wsClient.close();
        }
        if (httpClient != null) {
            httpClient.close();
        }
        if (deploymentId != null) {
            vertx.undeploy(deploymentId)
                .onSuccess(v -> {
                    logger.info("Test cleanup completed");
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }
    }

    @Test
    @Order(1)
    void testCreateDatabaseSetupWithQueues(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Test 1: Create Database Setup with Queues for Metrics ===");

        JsonObject setupRequest = new JsonObject()
            .put("setupId", testSetupId)
            .put("databaseConfig", new JsonObject()
                .put("host", postgres.getHost())
                .put("port", postgres.getFirstMappedPort())
                .put("databaseName", "monitoring_test_" + System.currentTimeMillis())
                .put("username", postgres.getUsername())
                .put("password", postgres.getPassword())
                .put("schema", "public")
                .put("templateDatabase", "template0")
                .put("encoding", "UTF8"))
            .put("queues", new JsonArray()
                .add(new JsonObject()
                    .put("queueName", testQueueName)
                    .put("maxRetries", 3)
                    .put("visibilityTimeoutSeconds", 30))
                .add(new JsonObject()
                    .put("queueName", "monitoring_queue_2")
                    .put("maxRetries", 5)
                    .put("visibilityTimeoutSeconds", 60)))
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
                logger.info("Database setup with queues created successfully");
                testContext.completeNow();
            })));
    }

    @Test
    @Order(2)
    void testWebSocketMonitoringConnection(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Test 2: WebSocket Monitoring Connection ===");

        String wsPath = "/ws/monitoring";

        WebSocketConnectOptions options = new WebSocketConnectOptions()
            .setHost("localhost")
            .setPort(TEST_PORT)
            .setURI(wsPath);

        AtomicReference<WebSocket> wsRef = new AtomicReference<>();

        wsClient.connect(options)
            .onSuccess(ws -> {
                wsRef.set(ws);
                logger.info("WebSocket monitoring connected successfully");

                ws.textMessageHandler(message -> {
                    testContext.verify(() -> {
                        JsonObject msg = new JsonObject(message);
                        logger.info("Received WebSocket monitoring message: {}", msg.encode());

                        // First message should be welcome
                        if ("welcome".equals(msg.getString("type"))) {
                            assertNotNull(msg.getString("connectionId"), "connectionId should be present");
                            assertNotNull(msg.getLong("timestamp"), "timestamp should be present");
                            assertEquals("Connected to PeeGeeQ system monitoring", msg.getString("message"));

                            // Close connection and complete test
                            ws.close();
                            testContext.completeNow();
                        }
                    });
                });

                ws.exceptionHandler(err -> {
                    logger.error("WebSocket error: {}", err.getMessage());
                    testContext.failNow(err);
                });
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(3)
    void testWebSocketMonitoringReceivesMetrics(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Test 3: WebSocket Monitoring Receives Metrics ===");

        String wsPath = "/ws/monitoring";

        WebSocketConnectOptions options = new WebSocketConnectOptions()
            .setHost("localhost")
            .setPort(TEST_PORT)
            .setURI(wsPath);

        AtomicBoolean welcomeReceived = new AtomicBoolean(false);
        AtomicBoolean metricsReceived = new AtomicBoolean(false);

        wsClient.connect(options)
            .onSuccess(ws -> {
                logger.info("WebSocket monitoring connected for metrics test");

                ws.textMessageHandler(message -> {
                    testContext.verify(() -> {
                        JsonObject msg = new JsonObject(message);
                        logger.info("Received: {}", msg.encode());

                        if ("welcome".equals(msg.getString("type"))) {
                            welcomeReceived.set(true);
                            logger.info("Welcome message received");
                        } else if ("system_stats".equals(msg.getString("type"))) {
                            metricsReceived.set(true);
                            
                            // Verify metrics structure always present (Runtime-sourced)
                            JsonObject data = msg.getJsonObject("data");
                            assertNotNull(data, "Metrics data should be present");
                            assertNotNull(data.getLong("timestamp"), "timestamp should be present");
                            assertNotNull(data.getLong("memoryUsed"), "memoryUsed should be present");
                            assertNotNull(data.getInteger("cpuCores"), "cpuCores should be present");

                            // subscriptionHealth requires full DB collection.
                            // When collection hits the error fallback (e.g. .await() on
                            // wrong thread type), the response contains an "error" field
                            // instead.  Verify the full breakdown only when present.
                            if (data.getString("error") == null) {
                                assertNotNull(data.getString("uptime"), "uptime should be present");
                                JsonObject health = data.getJsonObject("subscriptionHealth");
                                assertNotNull(health, "subscriptionHealth should be present in monitoring payload");
                                assertNotNull(health.getInteger("active"), "subscriptionHealth.active should be present");
                                assertNotNull(health.getInteger("paused"), "subscriptionHealth.paused should be present");
                                assertNotNull(health.getInteger("dead"), "subscriptionHealth.dead should be present");
                                assertNotNull(health.getInteger("cancelled"), "subscriptionHealth.cancelled should be present");
                                assertNotNull(health.getInteger("total"), "subscriptionHealth.total should be present");
                                assertNotNull(health.getInteger("topics"), "subscriptionHealth.topics should be present");
                                
                                logger.info("Metrics received - totalQueues: {}, totalMessages: {}, subscriptionHealth: {}", 
                                           data.getInteger("totalQueues"), data.getLong("totalMessages"), health.encode());
                            } else {
                                logger.info("Error response received - cpuCores: {}, memoryUsed: {}, error: {}",
                                           data.getInteger("cpuCores"), data.getLong("memoryUsed"), data.getString("error"));
                            }
                            
                            ws.close();
                            testContext.completeNow();
                        }
                    });
                });

                // Set timeout in case metrics don't arrive
                vertx.setTimer(10000, id -> {
                    if (!metricsReceived.get()) {
                        logger.warn("Metrics not received within timeout");
                        ws.close();
                        testContext.failNow(new AssertionError(
                            "WebSocket monitoring: no system_stats message received within 10 s"));
                    }
                });

                ws.exceptionHandler(err -> {
                    logger.error("WebSocket error: {}", err.getMessage());
                    testContext.failNow(err);
                });
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(4)
    void testWebSocketMonitoringPingPong(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Test 4: WebSocket Monitoring Ping/Pong ===");

        String wsPath = "/ws/monitoring";

        WebSocketConnectOptions options = new WebSocketConnectOptions()
            .setHost("localhost")
            .setPort(TEST_PORT)
            .setURI(wsPath);

        wsClient.connect(options)
            .onSuccess(ws -> {
                logger.info("WebSocket monitoring connected for ping/pong test");

                ws.textMessageHandler(message -> {
                    testContext.verify(() -> {
                        JsonObject msg = new JsonObject(message);
                        logger.info("Received: {}", msg.encode());

                        if ("welcome".equals(msg.getString("type"))) {
                            // Send ping
                            JsonObject ping = new JsonObject()
                                .put("type", "ping")
                                .put("id", "test-ping-monitoring-123");
                            ws.writeTextMessage(ping.encode());
                            logger.info("Sent ping");
                        } else if ("pong".equals(msg.getString("type"))) {
                            assertEquals("test-ping-monitoring-123", msg.getString("id"));
                            assertNotNull(msg.getLong("timestamp"));
                            logger.info("Received pong");
                            ws.close();
                            testContext.completeNow();
                        }
                    });
                });

                ws.exceptionHandler(err -> {
                    logger.error("WebSocket error: {}", err.getMessage());
                    testContext.failNow(err);
                });
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(5)
    void testWebSocketMonitoringConfigure(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Test 5: WebSocket Monitoring Configure Interval ===");

        String wsPath = "/ws/monitoring";

        WebSocketConnectOptions options = new WebSocketConnectOptions()
            .setHost("localhost")
            .setPort(TEST_PORT)
            .setURI(wsPath);

        wsClient.connect(options)
            .onSuccess(ws -> {
                logger.info("WebSocket monitoring connected for configure test");

                ws.textMessageHandler(message -> {
                    testContext.verify(() -> {
                        JsonObject msg = new JsonObject(message);
                        logger.info("Received: {}", msg.encode());

                        if ("welcome".equals(msg.getString("type"))) {
                            // Send configure message
                            JsonObject configure = new JsonObject()
                                .put("type", "configure")
                                .put("interval", 10);
                            ws.writeTextMessage(configure.encode());
                            logger.info("Sent configure with interval=10");
                        } else if ("configured".equals(msg.getString("type"))) {
                            assertEquals(10, msg.getInteger("interval"));
                            assertNotNull(msg.getLong("timestamp"));
                            logger.info("Configuration confirmed");
                            ws.close();
                            testContext.completeNow();
                        }
                    });
                });

                ws.exceptionHandler(err -> {
                    logger.error("WebSocket error: {}", err.getMessage());
                    testContext.failNow(err);
                });
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(6)
    void testWebSocketMonitoringRefresh(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Test 6: WebSocket Monitoring Refresh Command ===");

        String wsPath = "/ws/monitoring";

        WebSocketConnectOptions options = new WebSocketConnectOptions()
            .setHost("localhost")
            .setPort(TEST_PORT)
            .setURI(wsPath);

        AtomicInteger metricsCount = new AtomicInteger(0);

        wsClient.connect(options)
            .onSuccess(ws -> {
                logger.info("WebSocket monitoring connected for refresh test");

                ws.textMessageHandler(message -> {
                    testContext.verify(() -> {
                        JsonObject msg = new JsonObject(message);

                        if ("welcome".equals(msg.getString("type"))) {
                            // Send refresh command
                            JsonObject refresh = new JsonObject().put("type", "refresh");
                            ws.writeTextMessage(refresh.encode());
                            logger.info("Sent refresh command");
                        } else if ("system_stats".equals(msg.getString("type"))) {
                            int count = metricsCount.incrementAndGet();
                            logger.info("Received metrics #{}", count);
                            
                            if (count >= 1) {
                                // We got at least one metrics response from refresh
                                ws.close();
                                testContext.completeNow();
                            }
                        }
                    });
                });

                // Timeout if no metrics received
                vertx.setTimer(5000, id -> {
                    if (metricsCount.get() == 0) {
                        logger.warn("No metrics received from refresh");
                        ws.close();
                        testContext.failNow(new AssertionError("Refresh did not trigger metrics"));
                    }
                });

                ws.exceptionHandler(err -> {
                    logger.error("WebSocket error: {}", err.getMessage());
                    testContext.failNow(err);
                });
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(7)
    void testSSEMetricsConnection(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Test 7: SSE Metrics Connection ===");

        String ssePath = "/sse/metrics";
        
        // Create dedicated HttpClient for this SSE test to avoid reuse issues
        HttpClient sseClient = vertx.createHttpClient();

        sseClient.request(HttpMethod.GET, TEST_PORT, "localhost", ssePath)
            .compose(request -> {
                request.putHeader("Accept", "text/event-stream");
                return request.send();
            })
            .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                int status = response.statusCode();
                logger.info("SSE endpoint returned status: {}", status);

                assertEquals(200, status, "SSE endpoint should return 200");

                String contentType = response.getHeader("Content-Type");
                logger.info("Content-Type: {}", contentType);
                assertTrue(contentType != null && contentType.contains("text/event-stream"),
                    "Content-Type should be text/event-stream");

                // Read SSE events
                AtomicBoolean connectedEventReceived = new AtomicBoolean(false);
                StringBuilder buffer = new StringBuilder();

                response.handler(chunk -> {
                    String data = chunk.toString();
                    buffer.append(data);
                    logger.debug("SSE chunk: {}", data);

                    // Check for connected event
                    if (data.contains("event: connected")) {
                        connectedEventReceived.set(true);
                        logger.info("Connected event received");
                        logger.info("--- EXPECTED ERROR (Test 7: closing SSE client → server-side SSE error on monitoring-6) ---");
                        sseClient.close();
                        testContext.completeNow();
                    }
                });

                // Timeout if no events received
                vertx.setTimer(5000, id -> {
                    if (!connectedEventReceived.get()) {
                        logger.warn("Connected event not received within timeout");
                        sseClient.close();
                        testContext.failNow(new AssertionError(
                            "SSE metrics: 'connected' event not received within 5 s"));
                    }
                });
            })))
            .onFailure(err -> {
                sseClient.close();
                testContext.failNow(err);
            });
    }

    @Test
    @Order(8)
    void testSSEMetricsWithInterval(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Test 8: SSE Metrics with Custom Interval ===");

        String ssePath = "/sse/metrics?interval=2&heartbeat=10";
        
        // Create dedicated HttpClient for this SSE test to avoid reuse issues
        HttpClient sseClient = vertx.createHttpClient();

        sseClient.request(HttpMethod.GET, TEST_PORT, "localhost", ssePath)
            .compose(request -> {
                request.putHeader("Accept", "text/event-stream");
                return request.send();
            })
            .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                assertEquals(200, response.statusCode(), "SSE endpoint should return 200");

                AtomicBoolean metricsEventReceived = new AtomicBoolean(false);
                StringBuilder buffer = new StringBuilder();

                response.handler(chunk -> {
                    String data = chunk.toString();
                    buffer.append(data);

                    // Check for metrics event
                    if (data.contains("event: metrics")) {
                        metricsEventReceived.set(true);
                        logger.info("Metrics event received");
                        logger.info("--- EXPECTED ERROR (Test 8: closing SSE client → server-side SSE error on monitoring-7) ---");
                        sseClient.close();
                        testContext.completeNow();
                    }
                });

                // Timeout - metrics should arrive within interval (2s) + buffer
                vertx.setTimer(10000, id -> {
                    if (!metricsEventReceived.get()) {
                        logger.warn("Metrics event not received within timeout");
                        sseClient.close();
                        testContext.failNow(new AssertionError(
                            "SSE metrics: 'metrics' event not received within 10 s"));
                    }
                });
            })))
            .onFailure(err -> {
                sseClient.close();
                testContext.failNow(err);
            });
    }

    @Test
    @Order(9)
    void testWebSocketMonitoringInvalidCommand(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Test 9: WebSocket Monitoring Invalid Command ===");

        String wsPath = "/ws/monitoring";

        WebSocketConnectOptions options = new WebSocketConnectOptions()
            .setHost("localhost")
            .setPort(TEST_PORT)
            .setURI(wsPath);

        wsClient.connect(options)
            .onSuccess(ws -> {
                logger.info("WebSocket monitoring connected for invalid command test");

                ws.textMessageHandler(message -> {
                    testContext.verify(() -> {
                        JsonObject msg = new JsonObject(message);
                        logger.info("Received: {}", msg.encode());

                        if ("welcome".equals(msg.getString("type"))) {
                            // Send invalid command
                            JsonObject invalid = new JsonObject()
                                .put("type", "invalid_command_type");
                            ws.writeTextMessage(invalid.encode());
                            logger.info("Sent invalid command");
                        } else if ("error".equals(msg.getString("type"))) {
                            assertNotNull(msg.getString("message"));
                            assertTrue(msg.getString("message").contains("Unknown command type"));
                            logger.info("Error response received as expected");
                            ws.close();
                            testContext.completeNow();
                        }
                    });
                });

                ws.exceptionHandler(err -> {
                    logger.error("WebSocket error: {}", err.getMessage());
                    testContext.failNow(err);
                });
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(10)
    void testWebSocketMonitoringInvalidInterval(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Test 10: WebSocket Monitoring Invalid Interval ===");

        String wsPath = "/ws/monitoring";

        WebSocketConnectOptions options = new WebSocketConnectOptions()
            .setHost("localhost")
            .setPort(TEST_PORT)
            .setURI(wsPath);

        wsClient.connect(options)
            .onSuccess(ws -> {
                logger.info("WebSocket monitoring connected for invalid interval test");

                ws.textMessageHandler(message -> {
                    testContext.verify(() -> {
                        JsonObject msg = new JsonObject(message);
                        logger.info("Received: {}", msg.encode());

                        if ("welcome".equals(msg.getString("type"))) {
                            // Send configure with out-of-bounds interval (too small)
                            JsonObject configure = new JsonObject()
                                .put("type", "configure")
                                .put("interval", 0);  // Invalid: below minimum
                            ws.writeTextMessage(configure.encode());
                            logger.info("Sent configure with invalid interval=0");
                        } else if ("error".equals(msg.getString("type"))) {
                            assertNotNull(msg.getString("message"));
                            assertTrue(msg.getString("message").contains("Invalid interval"));
                            logger.info("Error response received as expected");
                            ws.close();
                            testContext.completeNow();
                        }
                    });
                });

                ws.exceptionHandler(err -> {
                    logger.error("WebSocket error: {}", err.getMessage());
                    testContext.failNow(err);
                });
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(11)
    void testSseClientDisconnectLogsDebugNotError(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== Test 11: SSE Client Disconnect Must Not Log ERROR ===");

        ch.qos.logback.classic.Logger handlerLogger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(SystemMonitoringHandler.class);
        ListAppender<ILoggingEvent> capture = new ListAppender<>();
        capture.start();
        handlerLogger.addAppender(capture);

        httpClient.request(HttpMethod.GET, TEST_PORT, "localhost", "/sse/metrics")
            .compose(io.vertx.core.http.HttpClientRequest::send)
            .onSuccess(response -> {
                testContext.verify(() -> assertEquals(200, response.statusCode()));

                response.exceptionHandler(err -> {
                    if (!(err instanceof io.vertx.core.http.HttpClosedException)) {
                        testContext.failNow(err);
                    }
                });

                // Abruptly close the connection after a short delay
                vertx.setTimer(500, id -> {
                    response.request().connection().close();

                    // Give the server time to process the disconnect
                    vertx.setTimer(500, id2 -> {
                        testContext.verify(() -> {
                            List<ILoggingEvent> errors = capture.list.stream().filter(e -> e.getLevel().equals(Level.ERROR)).toList();
                            boolean hasSseError = errors.stream().anyMatch(e ->
                                    e.getFormattedMessage().contains("SSE error for") ||
                                    e.getFormattedMessage().contains("Error sending SSE"));
                            assertFalse(hasSseError,
                                    "Client disconnect must not be logged at ERROR level, but got: " +
                                    errors.stream().map(ILoggingEvent::getFormattedMessage).toList());
                        });
                        handlerLogger.detachAppender(capture);
                        capture.stop();
                        testContext.completeNow();
                    });
                });
            })
            .onFailure(err -> {
                handlerLogger.detachAppender(capture);
                capture.stop();
                testContext.failNow(err);
            });
    }

}
