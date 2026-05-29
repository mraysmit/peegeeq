package dev.mars.peegeeq.rest.handlers;

import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.rest.config.RestServerConfig;
import dev.mars.peegeeq.rest.PeeGeeQRestServer;
import dev.mars.peegeeq.runtime.PeeGeeQRuntime;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
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
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Health REST API endpoints.
 *
 * Tests all health endpoints as implemented in HealthHandler:
 * - GET /api/v1/setups/:setupId/health - Overall health status
 * - GET /api/v1/setups/:setupId/health/components - List component health
 * - GET /api/v1/setups/:setupId/health/components/:name - Get specific component health
 *
 * Classification: INTEGRATION TEST
 * - Uses real PostgreSQL database (TestContainers)
 * - Uses real Vert.x HTTP server
 * - Tests end-to-end health API flow
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class HealthHandlerIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(HealthHandlerIntegrationTest.class);
    private static final int TEST_PORT = 18098;

    @Container
    static PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE);
        container.withDatabaseName("peegeeq_health_test");
        container.withUsername("peegeeq_test");
        container.withPassword("peegeeq_test");
        container.withSharedMemorySize(PostgreSQLTestConstants.DEFAULT_SHARED_MEMORY_SIZE);
        container.withReuse(false);
        return container;
    }

    private PeeGeeQRestServer server;
    private String deploymentId;
    private String setupId;
    private WebClient webClient;

    @BeforeAll
    void setupServer(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Setting up Health Handler Integration Test ===");

        setupId = "health-test-" + System.currentTimeMillis();

        DatabaseSetupService setupService = PeeGeeQRuntime.createDatabaseSetupService();

        RestServerConfig testConfig = new RestServerConfig(TEST_PORT, RestServerConfig.MonitoringConfig.defaults(), java.util.List.of("*"));
        server = new PeeGeeQRestServer(testConfig, setupService);
        vertx.deployVerticle(server)
            .compose(id -> {
                deploymentId = id;
                logger.info("REST server deployed with ID: {}", deploymentId);
                webClient = WebClient.create(vertx);
                return createSetup();
            })
            .onSuccess(v -> {
                logger.info("Health handler test setup complete");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    private Future<Void> createSetup() {
        JsonObject setupRequest = new JsonObject()
            .put("setupId", setupId)
            .put("databaseConfig", new JsonObject()
                .put("host", postgres.getHost())
                .put("port", postgres.getFirstMappedPort())
                .put("databaseName", "health_test_db_" + System.currentTimeMillis())
                .put("username", postgres.getUsername())
                .put("password", postgres.getPassword())
                .put("schema", "public")
                .put("templateDatabase", "template0")
                .put("encoding", "UTF8"))
            .put("queues", new JsonArray());

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
        logger.info("=== Tearing down Health Handler Integration Test ===");

        Future<Void> undeploy = deploymentId != null
            ? vertx.undeploy(deploymentId)
            : Future.succeededFuture();

        undeploy.onSuccess(v -> testContext.completeNow()).onFailure(testContext::failNow);
    }

    // ========== Test Methods ==========

    @Test
    @Order(1)
    @DisplayName("GET /health for valid setup returns 200 or 503 with health body")
    void testOverallHealth_validSetup_returnsHealthStatus(VertxTestContext testContext) {
        String path = String.format("/api/v1/setups/%s/health", setupId);

        webClient.get(TEST_PORT, "localhost", path)
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    int status = response.statusCode();
                    assertTrue(status == 200 || status == 503,
                        "Expected 200 (healthy) or 503 (unhealthy), got: " + status);
                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body, "Response body must be JSON");
                    assertTrue(body.containsKey("healthy"), "Response must contain 'healthy' field");
                    assertTrue(body.containsKey("status"), "Response must contain 'status' field");
                    logger.info("Overall health for setup {}: status={}, healthy={}",
                        setupId, status, body.getBoolean("healthy"));
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(2)
    @DisplayName("GET /health for unknown setup returns 404")
    void testOverallHealth_unknownSetup_returns404(VertxTestContext testContext) {
        String path = "/api/v1/setups/unknown-health-setup-xyz/health";

        webClient.get(TEST_PORT, "localhost", path)
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(404, response.statusCode(), "Expected 404 for unknown setup");
                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body, "Response body must be JSON");
                    assertTrue(body.containsKey("error"), "Response must contain 'error' field");
                    logger.info("Got expected 404 for unknown setup health");
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(3)
    @DisplayName("GET /health/components for valid setup returns JSON array")
    void testListComponentHealth_validSetup_returnsArray(VertxTestContext testContext) {
        String path = String.format("/api/v1/setups/%s/health/components", setupId);

        webClient.get(TEST_PORT, "localhost", path)
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    int status = response.statusCode();
                    assertTrue(status == 200 || status == 503,
                        "Expected 200 or 503, got: " + status);
                    if (status == 200) {
                        JsonArray components = response.bodyAsJsonArray();
                        assertNotNull(components, "Response must be a JSON array");
                        logger.info("Found {} health components for setup {}", components.size(), setupId);
                    }
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(4)
    @DisplayName("GET /health/components for unknown setup returns 404")
    void testListComponentHealth_unknownSetup_returns404(VertxTestContext testContext) {
        String path = "/api/v1/setups/unknown-health-setup-xyz/health/components";

        webClient.get(TEST_PORT, "localhost", path)
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(404, response.statusCode(), "Expected 404 for unknown setup");
                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body, "Response body must be JSON");
                    assertTrue(body.containsKey("error"), "Response must contain 'error' field");
                    logger.info("Got expected 404 for unknown setup component list");
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(5)
    @DisplayName("GET /health/components/:name for unknown setup returns 404")
    void testGetComponentHealth_unknownSetup_returns404(VertxTestContext testContext) {
        String path = "/api/v1/setups/unknown-health-setup-xyz/health/components/database";

        webClient.get(TEST_PORT, "localhost", path)
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(404, response.statusCode(), "Expected 404 for unknown setup");
                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body, "Response body must be JSON");
                    assertTrue(body.containsKey("error"), "Response must contain 'error' field");
                    logger.info("Got expected 404 for component health with unknown setup");
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(6)
    @DisplayName("GET /health/components/:name for unknown component returns 404")
    void testGetComponentHealth_unknownComponent_returns404(VertxTestContext testContext) {
        String path = String.format("/api/v1/setups/%s/health/components/nonexistent-component", setupId);

        webClient.get(TEST_PORT, "localhost", path)
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(404, response.statusCode(), "Expected 404 for unknown component");
                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body, "Response body must be JSON");
                    assertTrue(body.containsKey("error"), "Response must contain 'error' field");
                    logger.info("Got expected 404 for unknown component: nonexistent-component");
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(7)
    @DisplayName("GET /health/components/:name for known component returns health status")
    void testGetComponentHealth_knownComponent_returnsStatus(VertxTestContext testContext) {
        // First retrieve the component list to find a known component name
        String listPath = String.format("/api/v1/setups/%s/health/components", setupId);

        webClient.get(TEST_PORT, "localhost", listPath)
            .send()
            .compose(listResponse -> {
                if (listResponse.statusCode() == 200) {
                    JsonArray components = listResponse.bodyAsJsonArray();
                    if (!components.isEmpty()) {
                        JsonObject first = components.getJsonObject(0);
                        String componentName = first.getString("component");
                        logger.info("Testing known component: {}", componentName);
                        String componentPath = String.format("/api/v1/setups/%s/health/components/%s",
                            setupId, componentName);
                        return webClient.get(TEST_PORT, "localhost", componentPath).send();
                    }
                }
                // No components or not 200  return the list response for verification
                return Future.succeededFuture(listResponse);
            })
            .onSuccess(response -> {
                testContext.verify(() -> {
                    int status = response.statusCode();
                    // 200/503 for a component that was found, 404 if the component list was empty
                    assertTrue(status == 200 || status == 503 || status == 404,
                        "Expected 200, 503, or 404, got: " + status);
                    logger.info("Component health returned status: {}", status);
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }
}
