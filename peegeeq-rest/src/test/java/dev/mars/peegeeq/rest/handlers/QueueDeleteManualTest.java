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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Manual test for queue deletion functionality.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class QueueDeleteManualTest {

    private static final Logger logger = LoggerFactory.getLogger(QueueDeleteManualTest.class);
    private static final int TEST_PORT = 18100;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
            .withDatabaseName("peegeeq_delete_test")
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
        logger.info("=== Setting up Queue Delete Manual Test ===");

        setupId = "delete-test-" + System.currentTimeMillis();

        // Create the setup service using PeeGeeQRuntime
        DatabaseSetupService setupService = PeeGeeQRuntime.createDatabaseSetupService();

        // Start REST server
        RestServerConfig testConfig = new RestServerConfig(TEST_PORT, RestServerConfig.MonitoringConfig.defaults(), java.util.List.of("*"));
        server = new PeeGeeQRestServer(testConfig, setupService);
        vertx.deployVerticle(server)
            .compose(id -> {
                deploymentId = id;
                logger.info("REST server deployed with ID: {}", deploymentId);
                webClient = WebClient.create(vertx);

                // Create database setup with queue
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
                .put("databaseName", "delete_test_db_" + System.currentTimeMillis())
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
        logger.info("=== Tearing down Queue Delete Manual Test ===");

        Future<Void> undeploy = deploymentId != null
            ? vertx.undeploy(deploymentId)
            : Future.succeededFuture();

        undeploy.onComplete(ar -> testContext.completeNow());
    }

    @Test
    @DisplayName("Queue Delete - Delete queue and verify cleanup")
    void testQueueDelete(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Testing Queue Delete ===");

        String queueName = "test_queue";

        // Step 1: Verify queue exists
        webClient.get(TEST_PORT, "localhost",
                "/api/v1/queues/" + setupId + "/" + queueName)
            .send()
            .compose(getResponse -> {
                testContext.verify(() -> {
                    assertEquals(200, getResponse.statusCode(), "Queue should exist");
                    logger.info("✅ Queue exists and is accessible");
                });

                // Step 2: Delete the queue
                return webClient.delete(TEST_PORT, "localhost",
                        "/api/v1/queues/" + setupId + "/" + queueName)
                    .send();
            })
            .compose(deleteResponse -> {
                testContext.verify(() -> {
                    assertEquals(200, deleteResponse.statusCode(), "Delete should return 200 OK");

                    JsonObject body = deleteResponse.bodyAsJsonObject();
                    assertNotNull(body, "Delete response should be JSON");
                    assertNotNull(body.getString("message"), "Should have message field");
                    assertEquals(queueName, body.getString("queueName"), "Should return correct queue name");
                    assertEquals(setupId, body.getString("setupId"), "Should return correct setup ID");

                    logger.info("✅ Queue deleted successfully");
                });

                // Step 3: Verify queue no longer exists
                return webClient.get(TEST_PORT, "localhost",
                        "/api/v1/queues/" + setupId + "/" + queueName)
                    .send();
            })
            .onSuccess(verifyResponse -> {
                testContext.verify(() -> {
                    // Queue should not be found after deletion
                    assertEquals(404, verifyResponse.statusCode(), "Queue should not exist after deletion");
                    logger.info("✅ Verified queue no longer exists");
                    logger.info("✅ Queue Delete test PASSED!");
                    testContext.completeNow();
                });
            })
            .onFailure(testContext::failNow);
    }
}

