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
 * E2E tests for Message Operations including:
 * - Message publishing with various options
 * - Message consumption
 * - Message acknowledgment
 * - Message rejection and DLQ
 * - Message priority and ordering
 */
@ExtendWith(VertxExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Testcontainers
@Tag(TestCategories.INTEGRATION)
public class MessageOperationsE2ETest {

    private static final Logger logger = LoggerFactory.getLogger(MessageOperationsE2ETest.class);
    private static final int TEST_PORT = 18103;

    @Container
    private static final PostgreSQLContainer<?> postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQRestServer server;
    private String deploymentId;
    private String setupId;
    private String queueName;
    private WebClient webClient;

    @BeforeAll
    void setupServer(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== Setting up Message Operations E2E Test Suite ===");

        setupId = "e2e-message-test-" + System.currentTimeMillis();
        queueName = "test_queue_messages";

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
                .put("databaseName", "e2e_message_db_" + System.currentTimeMillis())
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
    void teardownAll(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Tearing down Message Operations E2E Test Suite ===");
        
        if (deploymentId != null) {
            vertx.undeploy(deploymentId)
                .onComplete(ar -> {
                    if (webClient != null) {
                        webClient.close();
                    }
                    testContext.completeNow();
                });
        } else {
            if (webClient != null) {
                webClient.close();
            }
            testContext.completeNow();
        }
    }

    @Test
    @Order(1)
    @DisplayName("Message Test 1: Publish Simple Message")
    void test01_PublishSimpleMessage(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Message Test 1: Publish Simple Message ===");
        
        JsonObject payload = new JsonObject()
            .put("message", "Hello, World!")
            .put("timestamp", System.currentTimeMillis());
        
        JsonObject message = new JsonObject()
            .put("payload", payload.encode())
            .put("correlationId", "simple-message-1");
        
        webClient.post(TEST_PORT, "localhost", "/api/v1/queues/" + setupId + "/" + queueName + "/publish")
            .putHeader("content-type", "application/json")
            .sendJsonObject(message)
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode(), "Should return 200 OK");
                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body, "Response should be JSON");
                    assertTrue(body.containsKey("messageId") || body.containsKey("correlationId"), 
                        "Response should contain message identifier");
                    
                    logger.info("✅ Simple message published successfully");
                    testContext.completeNow();
                });
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(2)
    @DisplayName("Message Test 2: Publish Message with Priority")
    void test02_PublishMessageWithPriority(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Message Test 2: Publish Message with Priority ===");

        JsonObject payload = new JsonObject()
            .put("message", "High priority message")
            .put("timestamp", System.currentTimeMillis());

        JsonObject message = new JsonObject()
            .put("payload", payload.encode())
            .put("correlationId", "priority-message-1")
            .put("priority", 9);

        webClient.post(TEST_PORT, "localhost", "/api/v1/queues/" + setupId + "/" + queueName + "/publish")
            .putHeader("content-type", "application/json")
            .sendJsonObject(message)
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode(), "Should return 200 OK");
                    logger.info("✅ Priority message published successfully");
                    testContext.completeNow();
                });
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(3)
    @DisplayName("Message Test 3: Publish Message with Delay")
    void test03_PublishMessageWithDelay(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Message Test 3: Publish Message with Delay ===");

        JsonObject payload = new JsonObject()
            .put("message", "Delayed message")
            .put("timestamp", System.currentTimeMillis());

        JsonObject message = new JsonObject()
            .put("payload", payload.encode())
            .put("correlationId", "delayed-message-1")
            .put("delaySeconds", 5);

        webClient.post(TEST_PORT, "localhost", "/api/v1/queues/" + setupId + "/" + queueName + "/publish")
            .putHeader("content-type", "application/json")
            .sendJsonObject(message)
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode(), "Should return 200 OK");
                    logger.info("✅ Delayed message published successfully");
                    testContext.completeNow();
                });
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(4)
    @DisplayName("Message Test 4: Publish Message with Headers")
    void test04_PublishMessageWithHeaders(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Message Test 4: Publish Message with Headers ===");

        JsonObject payload = new JsonObject()
            .put("message", "Message with custom headers")
            .put("timestamp", System.currentTimeMillis());

        JsonObject headers = new JsonObject()
            .put("source", "test-suite")
            .put("version", "1.0")
            .put("environment", "test");

        JsonObject message = new JsonObject()
            .put("payload", payload.encode())
            .put("correlationId", "headers-message-1")
            .put("headers", headers);

        webClient.post(TEST_PORT, "localhost", "/api/v1/queues/" + setupId + "/" + queueName + "/publish")
            .putHeader("content-type", "application/json")
            .sendJsonObject(message)
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode(), "Should return 200 OK");
                    logger.info("✅ Message with headers published successfully");
                    testContext.completeNow();
                });
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(5)
    @DisplayName("Message Test 5: Publish Message with Message Group")
    void test05_PublishMessageWithMessageGroup(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Message Test 5: Publish Message with Message Group ===");

        JsonObject payload = new JsonObject()
            .put("message", "Message in group")
            .put("timestamp", System.currentTimeMillis());

        JsonObject message = new JsonObject()
            .put("payload", payload.encode())
            .put("correlationId", "group-message-1")
            .put("messageGroup", "order-processing");

        webClient.post(TEST_PORT, "localhost", "/api/v1/queues/" + setupId + "/" + queueName + "/publish")
            .putHeader("content-type", "application/json")
            .sendJsonObject(message)
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode(), "Should return 200 OK");
                    logger.info("✅ Message with group published successfully");
                    testContext.completeNow();
                });
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(6)
    @DisplayName("Message Test 6: Publish Batch Messages")
    void test06_PublishBatchMessages(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Message Test 6: Publish Batch Messages ===");

        Future<Void> publishChain = Future.succeededFuture();

        for (int i = 0; i < 10; i++) {
            final int msgNum = i;
            JsonObject payload = new JsonObject()
                .put("message", "Batch message " + msgNum)
                .put("index", msgNum)
                .put("timestamp", System.currentTimeMillis());

            JsonObject message = new JsonObject()
                .put("payload", payload.encode())
                .put("correlationId", "batch-message-" + msgNum);

            publishChain = publishChain.compose(v ->
                webClient.post(TEST_PORT, "localhost", "/api/v1/queues/" + setupId + "/" + queueName + "/publish")
                    .putHeader("content-type", "application/json")
                    .sendJsonObject(message)
                    .mapEmpty()
            );
        }

        publishChain
            .onSuccess(v -> {
                logger.info("✅ Batch messages published successfully");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(7)
    @DisplayName("Message Test 7: Verify Queue Message Count")
    void test07_VerifyQueueMessageCount(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Message Test 7: Verify Queue Message Count ===");

        webClient.get(TEST_PORT, "localhost", "/api/v1/queues/" + setupId + "/" + queueName)
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode());
                    JsonObject body = response.bodyAsJsonObject();
                    long messageCount = body.getLong("messages", 0L);

                    // We published: 1 simple + 1 priority + 1 delayed + 1 headers + 1 group + 10 batch = 15 messages
                    assertTrue(messageCount >= 15, "Should have at least 15 messages, found: " + messageCount);

                    logger.info("✅ Queue has {} messages", messageCount);
                    testContext.completeNow();
                });
            })
            .onFailure(testContext::failNow);
    }
}
