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
 * Comprehensive End-to-End test for Queue Management operations.
 * Tests the complete flow: REST API -> Handler -> Service -> Database
 * 
 * Coverage:
 * - Queue creation
 * - Queue details retrieval
 * - Message publishing
 * - Message browsing
 * - Queue purge
 * - Queue pause/resume
 * - Queue deletion
 * - Error handling
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class QueueManagementE2ETest {

    private static final Logger logger = LoggerFactory.getLogger(QueueManagementE2ETest.class);
    private static final int TEST_PORT = 18101;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
            .withDatabaseName("peegeeq_e2e_test")
            .withUsername("peegeeq_test")
            .withPassword("peegeeq_test")
            .withSharedMemorySize(PostgreSQLTestConstants.DEFAULT_SHARED_MEMORY_SIZE)
            .withReuse(false);

    private PeeGeeQRestServer server;
    private String deploymentId;
    private String setupId;
    private String queueName;
    private WebClient webClient;

    @BeforeAll
    void setupServer(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== Setting up Queue Management E2E Test Suite ===");

        setupId = "e2e-test-" + System.currentTimeMillis();
        queueName = "test_queue_e2e";

        // Create the setup service using PeeGeeQRuntime
        DatabaseSetupService setupService = PeeGeeQRuntime.createDatabaseSetupService();

        // Start REST server
        RestServerConfig testConfig = new RestServerConfig(TEST_PORT, RestServerConfig.MonitoringConfig.defaults());
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
                logger.info("Test setup complete - setupId: {}, queueName: {}", setupId, queueName);
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
                .put("databaseName", "e2e_test_db_" + System.currentTimeMillis())
                .put("username", postgres.getUsername())
                .put("password", postgres.getPassword())
                .put("schema", "public")
                .put("templateDatabase", "template0")
                .put("encoding", "UTF8"))
            .put("queues", new JsonArray()
                .add(new JsonObject()
                    .put("queueName", queueName)
                    .put("maxRetries", 3)
                    .put("visibilityTimeout", 30)
                    .put("deadLetterEnabled", true)
                    .put("batchSize", 10)));

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
        logger.info("=== Tearing down Queue Management E2E Test Suite ===");

        Future<Void> undeploy = deploymentId != null
            ? vertx.undeploy(deploymentId)
            : Future.succeededFuture();

        undeploy.onComplete(ar -> testContext.completeNow());
    }

    @Test
    @Order(1)
    @DisplayName("E2E Test 1: Get Queue Details")
    void test01_GetQueueDetails(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== E2E Test 1: Get Queue Details ===");

        webClient.get(TEST_PORT, "localhost", "/api/v1/queues/" + setupId + "/" + queueName)
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode(), "Should return 200 OK");

                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body, "Response should be JSON");
                    assertEquals(queueName, body.getString("name"), "Queue name should match");
                    assertEquals(setupId, body.getString("setup"), "Setup ID should match");
                    assertTrue(body.containsKey("messages"), "Should have messages count");
                    assertTrue(body.containsKey("consumers"), "Should have consumers count");

                    logger.info("✅ Queue details retrieved successfully");
                    testContext.completeNow();
                });
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(2)
    @DisplayName("E2E Test 2: Publish Messages to Queue")
    void test02_PublishMessages(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== E2E Test 2: Publish Messages to Queue ===");

        // Publish 5 test messages using the correct format
        Future<Void> publishFuture = Future.succeededFuture();

        for (int i = 1; i <= 5; i++) {
            final int messageNum = i;
            JsonObject payload = new JsonObject()
                .put("messageId", "msg-" + messageNum)
                .put("content", "Test message " + messageNum)
                .put("timestamp", System.currentTimeMillis());

            JsonObject message = new JsonObject()
                .put("payload", payload.encode())
                .put("correlationId", "test-correlation-" + messageNum);

            publishFuture = publishFuture.compose(v ->
                webClient.post(TEST_PORT, "localhost", "/api/v1/queues/" + setupId + "/" + queueName + "/publish")
                    .putHeader("content-type", "application/json")
                    .sendJsonObject(message)
                    .compose(response -> {
                        if (response.statusCode() >= 200 && response.statusCode() < 300) {
                            logger.info("Published message {}", messageNum);
                            return Future.succeededFuture();
                        } else {
                            logger.error("Failed to publish message {}: {} - {}",
                                messageNum, response.statusCode(), response.bodyAsString());
                            return Future.failedFuture("Failed to publish message " + messageNum +
                                ": " + response.statusCode());
                        }
                    })
            );
        }

        publishFuture
            .onSuccess(v -> {
                logger.info("✅ All 5 messages published successfully");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(3)
    @DisplayName("E2E Test 3: Verify Queue Has Messages")
    void test03_VerifyQueueHasMessages(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== E2E Test 3: Verify Queue Has Messages ===");

        // Wait a bit for messages to be available
        vertx.setTimer(1000, timerId -> {
            webClient.get(TEST_PORT, "localhost", "/api/v1/queues/" + setupId + "/" + queueName)
                .send()
                .onSuccess(response -> {
                    testContext.verify(() -> {
                        assertEquals(200, response.statusCode(), "Should return 200 OK");

                        JsonObject body = response.bodyAsJsonObject();
                        assertNotNull(body, "Response should be JSON");

                        long messageCount = body.getLong("messages", 0L);
                        assertTrue(messageCount > 0, "Should have at least one message, found: " + messageCount);

                        logger.info("✅ Verified queue has {} messages", messageCount);
                        testContext.completeNow();
                    });
                })
                .onFailure(testContext::failNow);
        });
    }

    @Test
    @Order(4)
    @DisplayName("E2E Test 4: Pause Queue")
    void test04_PauseQueue(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== E2E Test 4: Pause Queue ===");

        webClient.post(TEST_PORT, "localhost", "/api/v1/queues/" + setupId + "/" + queueName + "/pause")
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode(), "Should return 200 OK");

                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body, "Response should be JSON");
                    assertEquals("Queue paused successfully", body.getString("message"));
                    assertEquals(queueName, body.getString("queueName"));
                    assertEquals(setupId, body.getString("setupId"));
                    assertTrue(body.containsKey("pausedSubscriptions"), "Should have pausedSubscriptions count");

                    logger.info("✅ Queue paused successfully - {} subscriptions paused",
                        body.getInteger("pausedSubscriptions"));
                    testContext.completeNow();
                });
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(5)
    @DisplayName("E2E Test 5: Resume Queue")
    void test05_ResumeQueue(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== E2E Test 5: Resume Queue ===");

        webClient.post(TEST_PORT, "localhost", "/api/v1/queues/" + setupId + "/" + queueName + "/resume")
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode(), "Should return 200 OK");

                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body, "Response should be JSON");
                    assertEquals("Queue resumed successfully", body.getString("message"));
                    assertEquals(queueName, body.getString("queueName"));
                    assertEquals(setupId, body.getString("setupId"));
                    assertTrue(body.containsKey("resumedSubscriptions"), "Should have resumedSubscriptions count");

                    logger.info("✅ Queue resumed successfully - {} subscriptions resumed",
                        body.getInteger("resumedSubscriptions"));
                    testContext.completeNow();
                });
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(6)
    @DisplayName("E2E Test 6: Purge Queue")
    void test06_PurgeQueue(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== E2E Test 6: Purge Queue ===");

        webClient.post(TEST_PORT, "localhost", "/api/v1/queues/" + setupId + "/" + queueName + "/purge")
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode(), "Should return 200 OK");

                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body, "Response should be JSON");
                    assertEquals("Queue purged successfully", body.getString("message"));
                    assertEquals(queueName, body.getString("queueName"));
                    assertEquals(setupId, body.getString("setupId"));
                    assertTrue(body.containsKey("purgedCount"), "Should have purgedCount");

                    int purgedCount = body.getInteger("purgedCount");
                    logger.info("✅ Queue purged successfully - {} messages removed", purgedCount);
                    testContext.completeNow();
                });
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(7)
    @DisplayName("E2E Test 7: Verify Queue is Empty After Purge")
    void test07_VerifyEmptyAfterPurge(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== E2E Test 7: Verify Queue is Empty After Purge ===");

        webClient.get(TEST_PORT, "localhost", "/api/v1/queues/" + setupId + "/" + queueName)
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode(), "Should return 200 OK");

                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body, "Response should be JSON");

                    long messageCount = body.getLong("messages", -1L);
                    assertEquals(0L, messageCount, "Queue should be empty after purge");

                    logger.info("✅ Verified queue is empty after purge");
                    testContext.completeNow();
                });
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(8)
    @DisplayName("E2E Test 8: Delete Queue")
    void test08_DeleteQueue(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== E2E Test 8: Delete Queue ===");

        webClient.delete(TEST_PORT, "localhost", "/api/v1/queues/" + setupId + "/" + queueName)
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode(), "Should return 200 OK");

                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body, "Response should be JSON");
                    assertEquals("Queue deleted successfully", body.getString("message"));
                    assertEquals(queueName, body.getString("queueName"));
                    assertEquals(setupId, body.getString("setupId"));
                    assertTrue(body.containsKey("deletedMessages"), "Should have deletedMessages count");

                    logger.info("✅ Queue deleted successfully - {} messages removed",
                        body.getInteger("deletedMessages"));
                    testContext.completeNow();
                });
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(9)
    @DisplayName("E2E Test 9: Verify Queue No Longer Exists")
    void test09_VerifyQueueDeleted(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== E2E Test 9: Verify Queue No Longer Exists ===");

        webClient.get(TEST_PORT, "localhost", "/api/v1/queues/" + setupId + "/" + queueName)
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(404, response.statusCode(), "Should return 404 Not Found");
                    logger.info("✅ Verified queue no longer exists");
                    testContext.completeNow();
                });
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(10)
    @DisplayName("E2E Test 10: Error Handling - Invalid Setup ID")
    void test10_ErrorHandling_InvalidSetup(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== E2E Test 10: Error Handling - Invalid Setup ID ===");

        webClient.get(TEST_PORT, "localhost", "/api/v1/queues/invalid-setup/some-queue")
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(404, response.statusCode(), "Should return 404 for invalid setup");
                    logger.info("✅ Correctly handled invalid setup ID");
                    testContext.completeNow();
                });
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(11)
    @DisplayName("E2E Test 11: Error Handling - Invalid Queue Name")
    void test11_ErrorHandling_InvalidQueue(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== E2E Test 11: Error Handling - Invalid Queue Name ===");

        webClient.get(TEST_PORT, "localhost", "/api/v1/queues/" + setupId + "/nonexistent-queue")
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(404, response.statusCode(), "Should return 404 for invalid queue");
                    logger.info("✅ Correctly handled invalid queue name");
                    testContext.completeNow();
                });
            })
            .onFailure(testContext::failNow);
    }
}
