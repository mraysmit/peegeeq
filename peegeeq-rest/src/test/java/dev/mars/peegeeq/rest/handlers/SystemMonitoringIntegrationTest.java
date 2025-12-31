package dev.mars.peegeeq.rest.handlers;

import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.rest.PeeGeeQRestServer;
import dev.mars.peegeeq.rest.config.RestServerConfig;
import dev.mars.peegeeq.runtime.PeeGeeQRuntime;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;

import io.vertx.core.Vertx;

import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
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

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for System Monitoring Handler.
 * Verifies WebSocket monitoring, SSE metrics, and connection limits.
 *
 * Classification: INTEGRATION TEST
 * - Uses real PostgreSQL database (TestContainers)
 * - Uses real Vert.x HTTP server
 * - Uses real /metrics, /sse/metrics, and /ws/monitoring endpoints
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class SystemMonitoringIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(SystemMonitoringIntegrationTest.class);
    private static final int REST_PORT = 18099;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
            .withDatabaseName("peegeeq_monitoring_test")
            .withUsername("peegeeq_test")
            .withPassword("peegeeq_test")
            .withSharedMemorySize(PostgreSQLTestConstants.DEFAULT_SHARED_MEMORY_SIZE)
            .withReuse(false);

    private PeeGeeQRestServer server;
    private String deploymentId;
    private WebClient webClient;
    private WebSocketClient wsClient;

    @BeforeAll
    void setupServer(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Setting up System Monitoring Integration Test ===");

        // Configure strict monitoring limits for testing
        RestServerConfig.MonitoringConfig monitoringConfig = new RestServerConfig.MonitoringConfig(
                5, // maxConnections (low for testing)
                5, // maxConnectionsPerIp
                1, // defaultIntervalSeconds
                1, // minIntervalSeconds
                5, // maxIntervalSeconds
                5000, // idleTimeoutMs
                100, // cacheTtlMs
                0 // jitterMs (0 for predictable tests)
        );

        RestServerConfig testConfig = new RestServerConfig(REST_PORT, monitoringConfig, java.util.List.of("*"));

        // Create service and server
        DatabaseSetupService setupService = PeeGeeQRuntime.createDatabaseSetupService();
        server = new PeeGeeQRestServer(testConfig, setupService);

        vertx.deployVerticle(server)
                .onSuccess(id -> {
                    deploymentId = id;
                    webClient = WebClient.create(vertx);
                    wsClient = vertx.createWebSocketClient();
                    logger.info("Server deployed with ID: {}", id);
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
    }

    @AfterAll
    void tearDown(Vertx vertx, VertxTestContext testContext) {
        if (wsClient != null) {
            wsClient.close();
        }
        if (postgres != null) {
            postgres.stop();
        }
        if (deploymentId != null) {
            vertx.undeploy(deploymentId)
                    .onComplete(ar -> testContext.completeNow());
        } else {
            testContext.completeNow();
        }
    }

    @Test
    @Order(1)
    @DisplayName("Metrics Endpoint - Should return Prometheus formatted metrics")
    void testMetricsEndpoint(Vertx vertx, VertxTestContext testContext) {
        webClient.get(REST_PORT, "localhost", "/metrics")
                .send()
                .onSuccess(response -> {
                    testContext.verify(() -> {
                        assertEquals(200, response.statusCode());
                        String body = response.bodyAsString();
                        assertNotNull(body);

                        // Check for specific metrics we registered
                        assertTrue(body.contains("peegeeq_monitoring_connections_active"),
                                "Should contain active connections metric");

                        testContext.completeNow();
                    });
                })
                .onFailure(testContext::failNow);
    }

    @Test
    @Order(2)
    @DisplayName("WebSocket Monitoring - Should stream system stats")
    void testWebSocketMonitoring(Vertx vertx, VertxTestContext testContext) {
        // Use WebSocketClient
        WebSocketConnectOptions options = new WebSocketConnectOptions()
                .setPort(REST_PORT)
                .setHost("localhost")
                .setURI("/ws/monitoring");

        wsClient.connect(options)
                .onSuccess(ws -> {
                    AtomicInteger messageCount = new AtomicInteger(0);

                    ws.handler(buffer -> {
                        try {
                            JsonObject message = new JsonObject(buffer.toString());
                            String type = message.getString("type");

                            logger.info("Received WS message: {}", type);

                            if ("welcome".equals(type)) {
                                assertNotNull(message.getString("connectionId"));
                            } else if ("system_stats".equals(type)) {
                                JsonObject data = message.getJsonObject("data");
                                assertNotNull(data);
                                assertTrue(data.containsKey("uptime"));
                                assertTrue(data.containsKey("memoryUsed"));

                                if (messageCount.incrementAndGet() >= 2) {
                                    ws.close();
                                    testContext.completeNow();
                                }
                            }
                        } catch (Exception e) {
                            testContext.failNow(e);
                        }
                    });
                })
                .onFailure(testContext::failNow);
    }

    @Test
    @Order(3)
    @DisplayName("SSE Metrics - Should stream metrics events")
    void testSSEMetrics(Vertx vertx, VertxTestContext testContext) {
        // Use standard HttpClient for SSE streaming verification
        vertx.createHttpClient()
                .request(io.vertx.core.http.HttpMethod.GET, REST_PORT, "localhost", "/sse/metrics?interval=1")
                .compose(req -> req.send())
                .onSuccess(resp -> {
                    if (resp.statusCode() != 200) {
                        testContext.failNow("Status not 200: " + resp.statusCode());
                        return;
                    }

                    String contentType = resp.getHeader("Content-Type");
                    if (contentType == null || !contentType.contains("text/event-stream")) {
                        testContext.failNow("Invalid Content-Type: " + contentType);
                        return;
                    }

                    resp.handler(buffer -> {
                        String data = buffer.toString();
                        if (data.contains("event: metrics")) {
                            testContext.completeNow();
                        }
                    });
                })
                .onFailure(testContext::failNow);
    }
}
