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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Advanced E2E tests for Queue Management API including:
 * - Concurrent operations
 * - Edge cases
 * - Stress testing
 * - Error recovery
 */
@ExtendWith(VertxExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Testcontainers
@Tag(TestCategories.INTEGRATION)
public class QueueManagementAdvancedE2ETest {

    private static final Logger logger = LoggerFactory.getLogger(QueueManagementAdvancedE2ETest.class);
    private static final int TEST_PORT = 18102;

    @Container
    private static final PostgreSQLContainer<?> postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQRestServer server;
    private String deploymentId;
    private String setupId;
    private String queueName;
    private WebClient webClient;

    @BeforeAll
    void setupServer(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== Setting up Advanced Queue Management E2E Test Suite ===");

        setupId = "e2e-advanced-test-" + System.currentTimeMillis();
        queueName = "test_queue_advanced";

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
                .put("databaseName", "e2e_advanced_db_" + System.currentTimeMillis())
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
        logger.info("=== Tearing down Advanced Queue Management E2E Test Suite ===");
        
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
    @DisplayName("Advanced Test 1: Concurrent Message Publishing")
    void test01_ConcurrentMessagePublishing(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== Advanced Test 1: Concurrent Message Publishing ===");
        
        int concurrentPublishers = 10;
        int messagesPerPublisher = 5;
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(concurrentPublishers * messagesPerPublisher);
        
        // Launch concurrent publishers
        for (int publisher = 0; publisher < concurrentPublishers; publisher++) {
            final int publisherId = publisher;
            
            for (int msg = 0; msg < messagesPerPublisher; msg++) {
                final int messageNum = msg;
                
                JsonObject payload = new JsonObject()
                    .put("publisherId", publisherId)
                    .put("messageNum", messageNum)
                    .put("timestamp", System.currentTimeMillis());
                
                JsonObject message = new JsonObject()
                    .put("payload", payload.encode())
                    .put("correlationId", "publisher-" + publisherId + "-msg-" + messageNum);
                
                webClient.post(TEST_PORT, "localhost", "/api/v1/queues/" + setupId + "/" + queueName + "/publish")
                    .putHeader("content-type", "application/json")
                    .sendJsonObject(message)
                    .onSuccess(response -> {
                        if (response.statusCode() >= 200 && response.statusCode() < 300) {
                            successCount.incrementAndGet();
                        } else {
                            failureCount.incrementAndGet();
                        }
                        latch.countDown();
                    })
                    .onFailure(err -> {
                        failureCount.incrementAndGet();
                        latch.countDown();
                    });
            }
        }

        // Wait for all publishes to complete
        assertTrue(latch.await(30, TimeUnit.SECONDS), "All publishes should complete within 30 seconds");

        int expectedMessages = concurrentPublishers * messagesPerPublisher;
        logger.info("Concurrent publishing complete - Success: {}, Failures: {}, Expected: {}",
            successCount.get(), failureCount.get(), expectedMessages);

        // Verify all messages were published
        testContext.verify(() -> {
            assertEquals(expectedMessages, successCount.get(), "All messages should be published successfully");
            assertEquals(0, failureCount.get(), "No failures should occur");
        });

        // Verify queue has all messages
        webClient.get(TEST_PORT, "localhost", "/api/v1/queues/" + setupId + "/" + queueName)
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode());
                    JsonObject body = response.bodyAsJsonObject();
                    long messageCount = body.getLong("messages", 0L);
                    assertEquals(expectedMessages, messageCount,
                        "Queue should have all " + expectedMessages + " messages");

                    logger.info("✅ Concurrent publishing test passed - {} messages in queue", messageCount);
                    testContext.completeNow();
                });
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(2)
    @DisplayName("Advanced Test 2: Edge Case - Empty Queue Operations")
    void test02_EmptyQueueOperations(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Advanced Test 2: Edge Case - Empty Queue Operations ===");

        // First purge the existing queue to make it empty
        webClient.post(TEST_PORT, "localhost", "/api/v1/queues/" + setupId + "/" + queueName + "/purge")
            .send()
            .compose(purgeResponse -> {
                testContext.verify(() -> {
                    assertEquals(200, purgeResponse.statusCode(), "Purge should succeed");
                });

                // Try to pause empty queue
                return webClient.post(TEST_PORT, "localhost", "/api/v1/queues/" + setupId + "/" + queueName + "/pause")
                    .send();
            })
            .compose(pauseResponse -> {
                testContext.verify(() -> {
                    assertEquals(200, pauseResponse.statusCode(), "Pause should succeed on empty queue");
                });

                // Try to resume empty queue
                return webClient.post(TEST_PORT, "localhost", "/api/v1/queues/" + setupId + "/" + queueName + "/resume")
                    .send();
            })
            .compose(resumeResponse -> {
                testContext.verify(() -> {
                    assertEquals(200, resumeResponse.statusCode(), "Resume should succeed on empty queue");
                });

                // Verify queue is still empty
                return webClient.get(TEST_PORT, "localhost", "/api/v1/queues/" + setupId + "/" + queueName)
                    .send();
            })
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode());
                    JsonObject body = response.bodyAsJsonObject();
                    long messageCount = body.getLong("messages", -1L);
                    assertEquals(0L, messageCount, "Queue should still be empty");

                    logger.info("✅ Empty queue operations test passed");
                    testContext.completeNow();
                });
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(3)
    @DisplayName("Advanced Test 3: Edge Case - Large Message Payload")
    void test03_LargeMessagePayload(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Advanced Test 3: Edge Case - Large Message Payload ===");

        // Create a large payload (1MB)
        StringBuilder largeContent = new StringBuilder();
        for (int i = 0; i < 10000; i++) {
            largeContent.append("This is a test message with some content to make it larger. ");
        }

        JsonObject payload = new JsonObject()
            .put("content", largeContent.toString())
            .put("size", largeContent.length())
            .put("timestamp", System.currentTimeMillis());

        JsonObject message = new JsonObject()
            .put("payload", payload.encode())
            .put("correlationId", "large-message-test");

        webClient.post(TEST_PORT, "localhost", "/api/v1/queues/" + setupId + "/" + queueName + "/publish")
            .putHeader("content-type", "application/json")
            .sendJsonObject(message)
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode(), "Large message should be published successfully");

                    logger.info("✅ Large message payload test passed - {} bytes", largeContent.length());
                    testContext.completeNow();
                });
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(4)
    @DisplayName("Advanced Test 4: Edge Case - Special Characters in Payload")
    void test04_SpecialCharactersInPayload(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Advanced Test 4: Edge Case - Special Characters in Payload ===");

        JsonObject payload = new JsonObject()
            .put("unicode", "Hello 世界 🌍 مرحبا Привет")
            .put("special", "!@#$%^&*()_+-=[]{}|;':\",./<>?")
            .put("escaped", "Line1\\nLine2\\tTabbed\\r\\nNewline")
            .put("quotes", "He said \"Hello\" and she said 'Hi'");

        JsonObject message = new JsonObject()
            .put("payload", payload.encode())
            .put("correlationId", "special-chars-test");

        webClient.post(TEST_PORT, "localhost", "/api/v1/queues/" + setupId + "/" + queueName + "/publish")
            .putHeader("content-type", "application/json")
            .sendJsonObject(message)
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode(), "Message with special characters should be published");

                    logger.info("✅ Special characters test passed");
                    testContext.completeNow();
                });
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(5)
    @DisplayName("Advanced Test 5: Stress Test - Rapid Queue Operations")
    void test05_RapidQueueOperations(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Advanced Test 5: Stress Test - Rapid Queue Operations ===");

        // Perform rapid pause/resume cycles
        Future<Void> operationChain = Future.succeededFuture();

        for (int i = 0; i < 10; i++) {
            operationChain = operationChain
                .compose(v -> webClient.post(TEST_PORT, "localhost", "/api/v1/queues/" + setupId + "/" + queueName + "/pause")
                    .send()
                    .mapEmpty())
                .compose(v -> webClient.post(TEST_PORT, "localhost", "/api/v1/queues/" + setupId + "/" + queueName + "/resume")
                    .send()
                    .mapEmpty());
        }

        operationChain
            .onSuccess(v -> {
                logger.info("✅ Rapid queue operations test passed - 10 pause/resume cycles completed");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(6)
    @DisplayName("Advanced Test 6: Error Recovery - Invalid Message Format")
    void test06_InvalidMessageFormat(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Advanced Test 6: Error Recovery - Invalid Message Format ===");

        // Try to publish invalid JSON
        webClient.post(TEST_PORT, "localhost", "/api/v1/queues/" + setupId + "/" + queueName + "/publish")
            .putHeader("content-type", "application/json")
            .sendBuffer(io.vertx.core.buffer.Buffer.buffer("{invalid json"))
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertTrue(response.statusCode() >= 400, "Should return error for invalid JSON");
                    logger.info("✅ Invalid message format test passed - returned status {}", response.statusCode());
                    testContext.completeNow();
                });
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(7)
    @DisplayName("Advanced Test 7: Concurrent Queue Purge")
    void test07_ConcurrentQueuePurge(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== Advanced Test 7: Concurrent Queue Purge ===");

        // First, add some messages
        Future<Void> publishFuture = Future.succeededFuture();
        for (int i = 0; i < 20; i++) {
            final int msgNum = i;
            JsonObject payload = new JsonObject().put("msg", msgNum);
            JsonObject message = new JsonObject()
                .put("payload", payload.encode())
                .put("correlationId", "purge-test-" + msgNum);

            publishFuture = publishFuture.compose(v ->
                webClient.post(TEST_PORT, "localhost", "/api/v1/queues/" + setupId + "/" + queueName + "/publish")
                    .putHeader("content-type", "application/json")
                    .sendJsonObject(message)
                    .mapEmpty()
            );
        }

        publishFuture
            .compose(v -> {
                // Now try concurrent purges
                List<Future<Void>> purgeFutures = new ArrayList<>();
                for (int i = 0; i < 5; i++) {
                    Future<Void> purgeFuture = webClient.post(TEST_PORT, "localhost", "/api/v1/queues/" + setupId + "/" + queueName + "/purge")
                        .send()
                        .mapEmpty();
                    purgeFutures.add(purgeFuture);
                }
                return Future.all(purgeFutures).mapEmpty();
            })
            .compose(v -> {
                // Verify queue is empty
                return webClient.get(TEST_PORT, "localhost", "/api/v1/queues/" + setupId + "/" + queueName)
                    .send();
            })
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode());
                    JsonObject body = response.bodyAsJsonObject();
                    long messageCount = body.getLong("messages", -1L);
                    assertEquals(0L, messageCount, "Queue should be empty after concurrent purges");

                    logger.info("✅ Concurrent queue purge test passed");
                    testContext.completeNow();
                });
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(8)
    @DisplayName("Advanced Test 8: Queue Details During Operations")
    void test08_QueueDetailsDuringOperations(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Advanced Test 8: Queue Details During Operations ===");

        // Publish messages while checking queue details
        AtomicInteger publishCount = new AtomicInteger(0);

        Future<Void> operationChain = Future.succeededFuture();

        for (int i = 0; i < 10; i++) {
            final int msgNum = i;
            operationChain = operationChain
                .compose(v -> {
                    // Publish a message
                    JsonObject payload = new JsonObject().put("msg", msgNum);
                    JsonObject message = new JsonObject()
                        .put("payload", payload.encode())
                        .put("correlationId", "details-test-" + msgNum);

                    return webClient.post(TEST_PORT, "localhost", "/api/v1/queues/" + setupId + "/" + queueName + "/publish")
                        .putHeader("content-type", "application/json")
                        .sendJsonObject(message)
                        .compose(publishResponse -> {
                            publishCount.incrementAndGet();
                            // Immediately check queue details
                            return webClient.get(TEST_PORT, "localhost", "/api/v1/queues/" + setupId + "/" + queueName)
                                .send()
                                .mapEmpty();
                        });
                });
        }

        operationChain
            .onSuccess(v -> {
                testContext.verify(() -> {
                    assertEquals(10, publishCount.get(), "All messages should be published");
                    logger.info("✅ Queue details during operations test passed");
                    testContext.completeNow();
                });
            })
            .onFailure(testContext::failNow);
    }
}
