package dev.mars.peegeeq.rest.handlers;

import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.rest.config.RestServerConfig;
import dev.mars.peegeeq.rest.PeeGeeQRestServer;
import dev.mars.peegeeq.runtime.PeeGeeQRuntime;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Setup Management REST API endpoints.
 * 
 * Tests the complete setup management lifecycle as defined in PEEGEEQ_CALL_PROPAGATION_DESIGN.md Section 9.1:
 * - GET /api/v1/setups - List all setups
 * - POST /api/v1/setups - Create setup
 * - GET /api/v1/setups/:setupId - Get setup details
 * - GET /api/v1/setups/:setupId/status - Get setup status
 * - DELETE /api/v1/setups/:setupId - Delete setup
 * - POST /api/v1/setups/:setupId/queues - Add queue to setup
 * - POST /api/v1/setups/:setupId/eventstores - Add event store to setup
 * 
 * Classification: INTEGRATION TEST
 * - Uses real PostgreSQL database (TestContainers)
 * - Uses real Vert.x HTTP server
 * - Tests end-to-end setup management flow
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class SetupManagementIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(SetupManagementIntegrationTest.class);
    private static final int TEST_PORT = 18100;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
            .withDatabaseName("peegeeq_setup_mgmt_test")
            .withUsername("peegeeq_test")
            .withPassword("peegeeq_test")
            .withSharedMemorySize(PostgreSQLTestConstants.DEFAULT_SHARED_MEMORY_SIZE)
            .withReuse(false);

    private PeeGeeQRestServer server;
    private String deploymentId;
    private String setupId;
    private WebClient webClient;

    @BeforeAll
    void setupServer(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== Setting up Setup Management Integration Test ===");

        // Initialize database schema
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.OUTBOX);

        setupId = "setup-mgmt-test-" + System.currentTimeMillis();

        DatabaseSetupService setupService = PeeGeeQRuntime.createDatabaseSetupService();

        RestServerConfig testConfig = new RestServerConfig(TEST_PORT, RestServerConfig.MonitoringConfig.defaults(), java.util.List.of("*"));
        server = new PeeGeeQRestServer(testConfig, setupService);
        vertx.deployVerticle(server)
            .onSuccess(id -> {
                deploymentId = id;
                logger.info("REST server deployed with ID: {}", deploymentId);
                webClient = WebClient.create(vertx);
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @AfterAll
    void tearDown(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Tearing down Setup Management Integration Test ===");
        
        Future<Void> undeploy = deploymentId != null 
            ? vertx.undeploy(deploymentId) 
            : Future.succeededFuture();
            
        undeploy.onComplete(ar -> testContext.completeNow());
    }

    // ========== Test Methods ==========

    @Test
    @Order(1)
    @DisplayName("List setups - initially empty or has existing setups")
    void testListSetupsInitial(VertxTestContext testContext) {
        webClient.get(TEST_PORT, "localhost", "/api/v1/setups")
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode(), "Expected 200 for list setups");
                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body, "Response should be a JSON object");
                    assertTrue(body.containsKey("count"), "Response should contain count");
                    assertTrue(body.containsKey("setupIds"), "Response should contain setupIds");
                    logger.info("Initial setups count: {}", body.getInteger("count"));
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(2)
    @DisplayName("Create setup with queue")
    void testCreateSetup(VertxTestContext testContext) {
        JsonObject setupRequest = new JsonObject()
            .put("setupId", setupId)
            .put("databaseConfig", new JsonObject()
                .put("host", postgres.getHost())
                .put("port", postgres.getFirstMappedPort())
                .put("databaseName", "setup_mgmt_db_" + System.currentTimeMillis())
                .put("username", postgres.getUsername())
                .put("password", postgres.getPassword())
                .put("schema", "public")
                .put("templateDatabase", "template0")
                .put("encoding", "UTF8"))
            .put("queues", new JsonArray()
                .add(new JsonObject()
                    .put("queueName", "test_queue")
                    .put("maxRetries", 3)
                    .put("visibilityTimeout", 30)));

        webClient.post(TEST_PORT, "localhost", "/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .timeout(60000)
            .sendJsonObject(setupRequest)
            .onSuccess(response -> {
                testContext.verify(() -> {
                    logger.info("Create setup response: {} - {}", response.statusCode(), response.bodyAsString());
                    assertTrue(response.statusCode() == 200 || response.statusCode() == 201,
                        "Expected 200 or 201, got: " + response.statusCode());
                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body, "Response should be a JSON object");
                    assertEquals(setupId, body.getString("setupId"));
                    logger.info("Setup created: {}", body.encode());
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(3)
    @DisplayName("Get setup details")
    void testGetSetupDetails(VertxTestContext testContext) {
        String path = String.format("/api/v1/setups/%s", setupId);

        webClient.get(TEST_PORT, "localhost", path)
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode(), "Expected 200 for get setup details");
                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body, "Response should be a JSON object");
                    assertEquals(setupId, body.getString("setupId"));
                    assertTrue(body.containsKey("status"), "Response should contain status");
                    logger.info("Setup details: {}", body.encode());
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(4)
    @DisplayName("Get setup status")
    void testGetSetupStatus(VertxTestContext testContext) {
        String path = String.format("/api/v1/setups/%s/status", setupId);

        webClient.get(TEST_PORT, "localhost", path)
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode(), "Expected 200 for get setup status");
                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body, "Response should be a JSON object");
                    assertEquals(setupId, body.getString("setupId"));
                    assertTrue(body.containsKey("status"), "Response should contain status");
                    logger.info("Setup status: {}", body.encode());
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(5)
    @DisplayName("Add queue to existing setup")
    void testAddQueueToSetup(VertxTestContext testContext) {
        String path = String.format("/api/v1/setups/%s/queues", setupId);

        JsonObject queueRequest = new JsonObject()
            .put("queueName", "additional_queue")
            .put("maxRetries", 5)
            .put("visibilityTimeoutSeconds", 60);

        webClient.post(TEST_PORT, "localhost", path)
            .sendJsonObject(queueRequest)
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertTrue(response.statusCode() == 200 || response.statusCode() == 201,
                        "Expected 200 or 201, got: " + response.statusCode());
                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body, "Response should be a JSON object");
                    logger.info("Queue added: {}", body.encode());
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(6)
    @DisplayName("Add event store to existing setup")
    void testAddEventStoreToSetup(VertxTestContext testContext) {
        String path = String.format("/api/v1/setups/%s/eventstores", setupId);

        JsonObject eventStoreRequest = new JsonObject()
            .put("eventStoreName", "test_events")
            .put("tableName", "test_events_table")
            .put("biTemporalEnabled", true);

        webClient.post(TEST_PORT, "localhost", path)
            .sendJsonObject(eventStoreRequest)
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertTrue(response.statusCode() == 200 || response.statusCode() == 201,
                        "Expected 200 or 201, got: " + response.statusCode());
                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body, "Response should be a JSON object");
                    logger.info("Event store added: {}", body.encode());
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(7)
    @DisplayName("List setups - should include created setup")
    void testListSetupsAfterCreate(VertxTestContext testContext) {
        webClient.get(TEST_PORT, "localhost", "/api/v1/setups")
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode(), "Expected 200 for list setups");
                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body, "Response should be a JSON object");
                    assertTrue(body.getInteger("count") >= 1, "Should have at least 1 setup");
                    JsonArray setupIds = body.getJsonArray("setupIds");
                    assertTrue(setupIds.contains(setupId), "Should contain our setup ID");
                    logger.info("Setups after create: {}", body.encode());
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(8)
    @DisplayName("Get non-existent setup returns 404")
    void testGetNonExistentSetup(VertxTestContext testContext) {
        webClient.get(TEST_PORT, "localhost", "/api/v1/setups/non-existent-setup")
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(404, response.statusCode(), "Expected 404 for non-existent setup");
                    logger.info("Got expected 404 for non-existent setup");
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(9)
    @DisplayName("Delete setup")
    void testDeleteSetup(VertxTestContext testContext) {
        String path = String.format("/api/v1/setups/%s", setupId);

        webClient.delete(TEST_PORT, "localhost", path)
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(204, response.statusCode(), "Expected 204 for delete setup");
                    logger.info("Setup deleted successfully");
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(10)
    @DisplayName("Get deleted setup returns 404")
    void testGetDeletedSetup(VertxTestContext testContext) {
        String path = String.format("/api/v1/setups/%s", setupId);

        webClient.get(TEST_PORT, "localhost", path)
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(404, response.statusCode(), "Expected 404 for deleted setup");
                    logger.info("Got expected 404 for deleted setup");
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }
}
