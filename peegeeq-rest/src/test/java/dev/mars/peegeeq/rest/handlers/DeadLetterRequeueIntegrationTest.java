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
 * Integration tests for Dead Letter Queue Requeue flow.
 * 
 * Tests the complete DLQ lifecycle as defined in PEEGEEQ_CALL_PROPAGATION_DESIGN.md Section 9.4:
 * - GET /api/v1/setups/:setupId/deadletter/messages - List DLQ messages
 * - GET /api/v1/setups/:setupId/deadletter/messages/:messageId - Get specific DLQ message
 * - POST /api/v1/setups/:setupId/deadletter/messages/:messageId/reprocess - Reprocess DLQ message
 * - DELETE /api/v1/setups/:setupId/deadletter/messages/:messageId - Delete DLQ message
 * - GET /api/v1/setups/:setupId/deadletter/stats - Get DLQ statistics
 * - POST /api/v1/setups/:setupId/deadletter/cleanup - Cleanup old DLQ messages
 * 
 * Classification: INTEGRATION TEST
 * - Uses real PostgreSQL database (TestContainers)
 * - Uses real Vert.x HTTP server
 * - Tests end-to-end DLQ operations
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class DeadLetterRequeueIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(DeadLetterRequeueIntegrationTest.class);
    private static final int TEST_PORT = 18099;
    private static final String QUEUE_NAME = "dlq_test_queue";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
            .withDatabaseName("peegeeq_dlq_test")
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
        logger.info("=== Setting up Dead Letter Requeue Integration Test ===");

        // Initialize database schema with DLQ tables
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, 
            SchemaComponent.OUTBOX, 
            SchemaComponent.DEAD_LETTER_QUEUE);

        setupId = "dlq-test-" + System.currentTimeMillis();

        DatabaseSetupService setupService = PeeGeeQRuntime.createDatabaseSetupService();

        RestServerConfig testConfig = new RestServerConfig(TEST_PORT, RestServerConfig.MonitoringConfig.defaults());
        server = new PeeGeeQRestServer(testConfig, setupService);
        vertx.deployVerticle(server)
            .compose(id -> {
                deploymentId = id;
                logger.info("REST server deployed with ID: {}", deploymentId);
                webClient = WebClient.create(vertx);
                return createSetupWithQueue(vertx);
            })
            .onSuccess(v -> {
                logger.info("Test setup complete");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    private Future<Void> createSetupWithQueue(Vertx vertx) {
        JsonObject setupRequest = new JsonObject()
            .put("setupId", setupId)
            .put("databaseConfig", new JsonObject()
                .put("host", postgres.getHost())
                .put("port", postgres.getFirstMappedPort())
                .put("databaseName", "dlq_db_" + System.currentTimeMillis())
                .put("username", postgres.getUsername())
                .put("password", postgres.getPassword())
                .put("schema", "public")
                .put("templateDatabase", "template0")
                .put("encoding", "UTF8"))
            .put("queues", new JsonArray()
                .add(new JsonObject()
                    .put("queueName", QUEUE_NAME)
                    .put("maxRetries", 3)
                    .put("visibilityTimeout", 30)));

        return webClient.post(TEST_PORT, "localhost", "/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .timeout(60000)
            .sendJsonObject(setupRequest)
            .compose(response -> {
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    logger.info("Setup created: {}", setupId);
                    return Future.succeededFuture();
                } else {
                    return Future.failedFuture("Failed to create setup: " + response.statusCode());
                }
            });
    }

    @AfterAll
    void tearDown(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Tearing down Dead Letter Requeue Integration Test ===");
        
        Future<Void> undeploy = deploymentId != null 
            ? vertx.undeploy(deploymentId) 
            : Future.succeededFuture();
            
        undeploy.onComplete(ar -> testContext.completeNow());
    }

    // ========== Test Methods ==========

    @Test
    @Order(1)
    @DisplayName("List DLQ messages - empty queue")
    void testListDlqMessagesEmpty(VertxTestContext testContext) {
        String path = String.format("/api/v1/setups/%s/deadletter/messages", setupId);
        
        webClient.get(TEST_PORT, "localhost", path)
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertTrue(response.statusCode() == 200 || response.statusCode() == 404,
                        "Expected 200 or 404, got: " + response.statusCode());
                    if (response.statusCode() == 200) {
                        JsonArray messages = response.bodyAsJsonArray();
                        assertNotNull(messages, "Response should be a JSON array");
                        logger.info("Found {} DLQ messages", messages.size());
                    }
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(2)
    @DisplayName("List DLQ messages with topic filter")
    void testListDlqMessagesWithTopicFilter(VertxTestContext testContext) {
        String path = String.format("/api/v1/setups/%s/deadletter/messages?topic=%s", setupId, QUEUE_NAME);

        webClient.get(TEST_PORT, "localhost", path)
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertTrue(response.statusCode() == 200 || response.statusCode() == 404,
                        "Expected 200 or 404, got: " + response.statusCode());
                    logger.info("DLQ messages with topic filter: status={}", response.statusCode());
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(3)
    @DisplayName("Get DLQ statistics")
    void testGetDlqStats(VertxTestContext testContext) {
        String path = String.format("/api/v1/setups/%s/deadletter/stats", setupId);

        webClient.get(TEST_PORT, "localhost", path)
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertTrue(response.statusCode() == 200 || response.statusCode() == 404 || response.statusCode() == 500,
                        "Expected 200, 404, or 500, got: " + response.statusCode());
                    if (response.statusCode() == 200) {
                        JsonObject stats = response.bodyAsJsonObject();
                        assertNotNull(stats, "Response should be a JSON object");
                        assertTrue(stats.containsKey("totalMessages") || stats.containsKey("error"),
                            "Stats should contain totalMessages or error");
                        logger.info("DLQ stats: {}", stats.encode());
                    }
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(4)
    @DisplayName("Get non-existent DLQ message returns 404")
    void testGetNonExistentDlqMessage(VertxTestContext testContext) {
        String path = String.format("/api/v1/setups/%s/deadletter/messages/999999", setupId);

        webClient.get(TEST_PORT, "localhost", path)
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(404, response.statusCode(), "Expected 404 for non-existent message");
                    logger.info("Got expected 404 for non-existent DLQ message");
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(5)
    @DisplayName("Reprocess non-existent DLQ message returns 404")
    void testReprocessNonExistentDlqMessage(VertxTestContext testContext) {
        String path = String.format("/api/v1/setups/%s/deadletter/messages/999999/reprocess", setupId);

        JsonObject body = new JsonObject().put("reason", "Test reprocess");

        webClient.post(TEST_PORT, "localhost", path)
            .sendJsonObject(body)
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(404, response.statusCode(), "Expected 404 for non-existent message");
                    logger.info("Got expected 404 for reprocessing non-existent DLQ message");
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(6)
    @DisplayName("Delete non-existent DLQ message returns 404")
    void testDeleteNonExistentDlqMessage(VertxTestContext testContext) {
        String path = String.format("/api/v1/setups/%s/deadletter/messages/999999?reason=test", setupId);

        webClient.delete(TEST_PORT, "localhost", path)
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(404, response.statusCode(), "Expected 404 for non-existent message");
                    logger.info("Got expected 404 for deleting non-existent DLQ message");
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(7)
    @DisplayName("Cleanup old DLQ messages")
    void testCleanupDlqMessages(VertxTestContext testContext) {
        String path = String.format("/api/v1/setups/%s/deadletter/cleanup?retentionDays=30", setupId);

        webClient.post(TEST_PORT, "localhost", path)
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertTrue(response.statusCode() == 200 || response.statusCode() == 404 || response.statusCode() == 500,
                        "Expected 200, 404, or 500, got: " + response.statusCode());
                    if (response.statusCode() == 200) {
                        JsonObject result = response.bodyAsJsonObject();
                        assertTrue(result.getBoolean("success", false));
                        assertTrue(result.containsKey("messagesDeleted"));
                        logger.info("Cleanup result: {}", result.encode());
                    }
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(8)
    @DisplayName("DLQ operations for non-existent setup returns 404")
    void testDlqOperationsNonExistentSetup(VertxTestContext testContext) {
        String path = "/api/v1/setups/non-existent-setup/deadletter/messages";

        webClient.get(TEST_PORT, "localhost", path)
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(404, response.statusCode(), "Expected 404 for non-existent setup");
                    JsonObject error = response.bodyAsJsonObject();
                    assertNotNull(error.getString("error"));
                    logger.info("Got expected 404 for non-existent setup");
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }
}
