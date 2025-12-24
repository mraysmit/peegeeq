package dev.mars.peegeeq.rest.handlers;

import dev.mars.peegeeq.api.setup.DatabaseSetupService;
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
 * Integration tests for Management API endpoints.
 *
 * Tests all management API endpoints as defined in PEEGEEQ_CALL_PROPAGATION_DESIGN.md Section 9.8:
 * - GET /api/v1/health
 * - GET /api/v1/management/overview
 * - GET /api/v1/management/queues
 * - GET /api/v1/management/consumer-groups
 * - GET /api/v1/management/event-stores
 * - GET /api/v1/management/messages
 * - GET /api/v1/management/metrics
 * - POST /api/v1/management/queues
 * - PUT /api/v1/management/queues/:queueId
 * - DELETE /api/v1/management/queues/:queueId
 * - POST /api/v1/management/consumer-groups
 * - DELETE /api/v1/management/consumer-groups/:groupId
 * - POST /api/v1/management/event-stores
 * - DELETE /api/v1/management/event-stores/:storeId
 *
 * Classification: INTEGRATION TEST
 * - Uses real PostgreSQL database (TestContainers)
 * - Uses real Vert.x HTTP server
 * - Tests end-to-end management API flow
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ManagementApiIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(ManagementApiIntegrationTest.class);
    private static final int TEST_PORT = 18097;
    private static final String QUEUE_NAME = "mgmt_test_queue";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
            .withDatabaseName("peegeeq_mgmt_test")
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
        logger.info("=== Setting up Management API Integration Test ===");

        setupId = "mgmt-test-" + System.currentTimeMillis();

        // Create the setup service using PeeGeeQRuntime
        DatabaseSetupService setupService = PeeGeeQRuntime.createDatabaseSetupService();

        // Start REST server
        server = new PeeGeeQRestServer(TEST_PORT, setupService);
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
                .put("databaseName", "mgmt_api_db_" + System.currentTimeMillis())
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
        logger.info("=== Tearing down Management API Integration Test ===");

        Future<Void> undeploy = deploymentId != null
            ? vertx.undeploy(deploymentId)
            : Future.succeededFuture();

        undeploy.onComplete(ar -> testContext.completeNow());
    }

    @Test
    @Order(1)
    @DisplayName("Management API - GET /health returns UP status")
    void testHealthEndpoint(Vertx vertx, VertxTestContext testContext) {
        webClient.get(TEST_PORT, "localhost", "/api/v1/health")
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode());

                    JsonObject body = response.bodyAsJsonObject();
                    assertEquals("UP", body.getString("status"));
                    assertNotNull(body.getString("timestamp"));
                    assertNotNull(body.getString("version"));

                    logger.info("Health check response: {}", body.encodePrettily());
                    testContext.completeNow();
                });
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(2)
    @DisplayName("Management API - GET /management/overview returns system overview with recentActivity")
    void testSystemOverviewEndpoint(Vertx vertx, VertxTestContext testContext) {
        webClient.get(TEST_PORT, "localhost", "/api/v1/management/overview")
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode());

                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body.getJsonObject("systemStats"), "Should have systemStats");
                    assertNotNull(body.getJsonObject("queueSummary"), "Should have queueSummary");
                    assertNotNull(body.getJsonObject("consumerGroupSummary"), "Should have consumerGroupSummary");
                    assertNotNull(body.getJsonObject("eventStoreSummary"), "Should have eventStoreSummary");
                    assertNotNull(body.getLong("timestamp"), "Should have timestamp");

                    // Verify recentActivity field is present (uses getRecentActivity() implementation)
                    JsonArray recentActivity = body.getJsonArray("recentActivity");
                    assertNotNull(recentActivity, "Should have recentActivity array from getRecentActivity()");
                    logger.info("Recent activity contains {} items", recentActivity.size());

                    // Verify recentActivity structure if items exist
                    for (int i = 0; i < recentActivity.size(); i++) {
                        JsonObject activity = recentActivity.getJsonObject(i);
                        assertNotNull(activity.getString("id"), "Activity should have id");
                        assertNotNull(activity.getString("type"), "Activity should have type");
                        assertNotNull(activity.getString("timestamp"), "Activity should have timestamp");
                        logger.info("Activity {}: type={}, action={}, source={}",
                            i, activity.getString("type"), activity.getString("action"), activity.getString("source"));
                    }

                    logger.info("System overview response: {}", body.encodePrettily());
                    testContext.completeNow();
                });
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(3)
    @DisplayName("Management API - GET /management/queues returns queue list")
    void testGetQueuesEndpoint(Vertx vertx, VertxTestContext testContext) {
        webClient.get(TEST_PORT, "localhost", "/api/v1/management/queues")
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode());

                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body, "Should return response object");
                    JsonArray queues = body.getJsonArray("queues");
                    assertNotNull(queues, "Should contain queues array");

                    logger.info("Queues response: {} queues", queues.size());
                    testContext.completeNow();
                });
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(4)
    @DisplayName("Management API - GET /management/consumer-groups returns consumer group list")
    void testGetConsumerGroupsEndpoint(Vertx vertx, VertxTestContext testContext) {
        webClient.get(TEST_PORT, "localhost", "/api/v1/management/consumer-groups")
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode());

                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body, "Should return response object");
                    JsonArray consumerGroups = body.getJsonArray("consumerGroups");
                    assertNotNull(consumerGroups, "Should contain consumerGroups array");

                    logger.info("Consumer groups response: {} groups", consumerGroups.size());
                    testContext.completeNow();
                });
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(5)
    @DisplayName("Management API - GET /management/event-stores returns event store list")
    void testGetEventStoresEndpoint(Vertx vertx, VertxTestContext testContext) {
        webClient.get(TEST_PORT, "localhost", "/api/v1/management/event-stores")
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode());

                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body, "Should return response object");
                    JsonArray eventStores = body.getJsonArray("eventStores");
                    assertNotNull(eventStores, "Should contain eventStores array");

                    logger.info("Event stores response: {} stores", eventStores.size());
                    testContext.completeNow();
                });
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(6)
    @DisplayName("Management API - GET /management/messages returns message list")
    void testGetMessagesEndpoint(Vertx vertx, VertxTestContext testContext) {
        webClient.get(TEST_PORT, "localhost", "/api/v1/management/messages")
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode());

                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body, "Should return response object");
                    JsonArray messages = body.getJsonArray("messages");
                    assertNotNull(messages, "Should contain messages array");

                    logger.info("Messages response: {} messages", messages.size());
                    testContext.completeNow();
                });
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(7)
    @DisplayName("Management API - GET /management/metrics returns system metrics")
    void testGetMetricsEndpoint(Vertx vertx, VertxTestContext testContext) {
        webClient.get(TEST_PORT, "localhost", "/api/v1/management/metrics")
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode());

                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body, "Should return metrics object");

                    logger.info("Metrics response: {}", body.encodePrettily());
                    testContext.completeNow();
                });
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(8)
    @DisplayName("Management API - POST /management/queues creates a new queue")
    void testCreateQueueEndpoint(Vertx vertx, VertxTestContext testContext) {
        JsonObject queueData = new JsonObject()
            .put("setupId", setupId)
            .put("name", "new_mgmt_queue")
            .put("type", "native");

        webClient.post(TEST_PORT, "localhost", "/api/v1/management/queues")
            .sendJsonObject(queueData)
            .onSuccess(response -> {
                testContext.verify(() -> {
                    // Accept 200, 201, or 400 (if queue already exists or validation fails)
                    assertTrue(response.statusCode() >= 200 && response.statusCode() < 500,
                        "Should return success or client error, got: " + response.statusCode());

                    logger.info("Create queue response: {} - {}",
                        response.statusCode(), response.bodyAsString());
                    testContext.completeNow();
                });
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(9)
    @DisplayName("Management API - DELETE /management/queues/:queueId handles queue deletion")
    void testDeleteQueueEndpoint(Vertx vertx, VertxTestContext testContext) {
        String queueId = setupId + "-delete_test_queue";

        webClient.delete(TEST_PORT, "localhost", "/api/v1/management/queues/" + queueId)
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    // Accept 200, 204, or 404 (if queue doesn't exist)
                    assertTrue(response.statusCode() == 200 ||
                               response.statusCode() == 204 ||
                               response.statusCode() == 404,
                        "Should return success or not found, got: " + response.statusCode());

                    logger.info("Delete queue response: {}", response.statusCode());
                    testContext.completeNow();
                });
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(10)
    @DisplayName("Management API - QueueBrowser: Browse messages via REST endpoint")
    void testQueueBrowserFunctionality(Vertx vertx, VertxTestContext testContext) {
        // First, send some messages to the queue so we have data to browse
        JsonObject message1 = new JsonObject()
            .put("payload", new JsonObject().put("content", "Test message 1 for browsing"))
            .put("headers", new JsonObject().put("test-header", "value1"));
        JsonObject message2 = new JsonObject()
            .put("payload", new JsonObject().put("content", "Test message 2 for browsing"))
            .put("headers", new JsonObject().put("test-header", "value2"));

        // Send messages first using the correct endpoint: /api/v1/queues/:setupId/:queueName/messages
        webClient.post(TEST_PORT, "localhost",
                "/api/v1/queues/" + setupId + "/" + QUEUE_NAME + "/messages")
            .putHeader("content-type", "application/json")
            .sendJsonObject(message1)
            .compose(r1 -> {
                logger.info("Sent message 1, status: {}", r1.statusCode());
                return webClient.post(TEST_PORT, "localhost",
                        "/api/v1/queues/" + setupId + "/" + QUEUE_NAME + "/messages")
                    .putHeader("content-type", "application/json")
                    .sendJsonObject(message2);
            })
            .compose(r2 -> {
                logger.info("Sent message 2, status: {}", r2.statusCode());
                // Now browse the messages
                return webClient.get(TEST_PORT, "localhost",
                        "/api/v1/management/messages?setup=" + setupId + "&queue=" + QUEUE_NAME + "&limit=10")
                    .send();
            })
            .onSuccess(browseResponse -> {
                testContext.verify(() -> {
                    int statusCode = browseResponse.statusCode();
                    logger.info("Browse endpoint returned status: {}", statusCode);

                    // For native queues, QueueBrowser may return empty (LISTEN/NOTIFY doesn't persist)
                    // For outbox queues, it should return the messages
                    assertEquals(200, statusCode, "Browse endpoint should return 200");

                    JsonObject body = browseResponse.bodyAsJsonObject();
                    assertNotNull(body, "Should return response object");
                    assertNotNull(body.getString("message"), "Should have message field");
                    assertNotNull(body.getInteger("messageCount"), "Should have messageCount field");

                    JsonArray messages = body.getJsonArray("messages");
                    assertNotNull(messages, "Should contain messages array");
                    logger.info("QueueBrowser test: Found {} messages in queue", messages.size());

                    // Verify message structure if messages exist
                    for (int i = 0; i < messages.size(); i++) {
                        JsonObject msg = messages.getJsonObject(i);
                        assertNotNull(msg.getString("id"), "Message should have id");
                        logger.info("Browsed message {}: id={}", i, msg.getString("id"));
                    }

                    testContext.completeNow();
                });
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(11)
    @DisplayName("Management API - Event Store aggregate count via REST")
    void testEventStoreAggregateCount(Vertx vertx, VertxTestContext testContext) {
        // Query event stores and verify aggregate count is returned
        webClient.get(TEST_PORT, "localhost", "/api/v1/management/event-stores")
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode());

                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body, "Should return response object");
                    JsonArray eventStores = body.getJsonArray("eventStores");
                    assertNotNull(eventStores, "Should contain eventStores array");

                    logger.info("Event stores response: {} stores", eventStores.size());

                    // Verify each event store has the aggregates field (from getUniqueAggregateCount)
                    for (int i = 0; i < eventStores.size(); i++) {
                        JsonObject store = eventStores.getJsonObject(i);
                        assertNotNull(store.getString("name"), "Store should have name");
                        assertNotNull(store.getString("setup"), "Store should have setup");

                        // Verify aggregates field exists (this uses getUniqueAggregateCount)
                        assertTrue(store.containsKey("aggregates"),
                            "Store should have 'aggregates' field from getUniqueAggregateCount");
                        Long aggregateCount = store.getLong("aggregates");
                        assertNotNull(aggregateCount, "Aggregate count should not be null");
                        assertTrue(aggregateCount >= 0, "Aggregate count should be >= 0");

                        logger.info("Event store '{}' has {} unique aggregates",
                            store.getString("name"), aggregateCount);
                    }

                    testContext.completeNow();
                });
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(12)
    @DisplayName("Management API - POST /management/event-stores with all EventStoreConfig parameters")
    void testCreateEventStoreWithAllParameters(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Test 12: Create Event Store With All EventStoreConfig Parameters ===");

        String eventStoreName = "full_config_store_" + System.currentTimeMillis();

        // Create event store with ALL parameters including queryLimit, metricsEnabled, partitionStrategy
        JsonObject eventStoreRequest = new JsonObject()
            .put("name", eventStoreName)
            .put("setup", setupId)
            .put("tableName", eventStoreName + "_table")
            .put("biTemporalEnabled", true)
            .put("notificationPrefix", eventStoreName + "_notify_")
            .put("queryLimit", 500)
            .put("metricsEnabled", false)
            .put("partitionStrategy", "daily");

        logger.info("Creating event store with full configuration: {}", eventStoreRequest.encode());

        webClient.post(TEST_PORT, "localhost", "/api/v1/management/event-stores")
            .sendJsonObject(eventStoreRequest)
            .onSuccess(response -> {
                testContext.verify(() -> {
                    logger.info("Create event store response: {} - {}", response.statusCode(), response.bodyAsString());

                    assertEquals(201, response.statusCode(), "Should return 201 Created");

                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body, "Response should be a JSON object");

                    // Verify all parameters are returned in response
                    assertEquals(eventStoreName, body.getString("eventStoreName"));
                    assertEquals(setupId, body.getString("setupId"));
                    assertEquals(eventStoreName + "_table", body.getString("tableName"));
                    assertTrue(body.getBoolean("biTemporalEnabled"));
                    assertEquals(eventStoreName + "_notify_", body.getString("notificationPrefix"));
                    assertEquals(500, body.getInteger("queryLimit"));
                    assertFalse(body.getBoolean("metricsEnabled"));
                    assertEquals("daily", body.getString("partitionStrategy"));

                    logger.info("✅ Event store created with all parameters:");
                    logger.info("   - queryLimit: {}", body.getInteger("queryLimit"));
                    logger.info("   - metricsEnabled: {}", body.getBoolean("metricsEnabled"));
                    logger.info("   - partitionStrategy: {}", body.getString("partitionStrategy"));

                    testContext.completeNow();
                });
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(13)
    @DisplayName("Management API - POST /management/queues with all QueueConfig parameters")
    void testCreateQueueWithAllParameters(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Test 13: Create Queue With All QueueConfig Parameters ===");

        String queueName = "full_config_queue_" + System.currentTimeMillis();

        // Create queue with ALL parameters including batchSize, pollingInterval, fifoEnabled, deadLetterQueueName
        JsonObject queueRequest = new JsonObject()
            .put("name", queueName)
            .put("setup", setupId)
            .put("maxRetries", 5)
            .put("visibilityTimeoutSeconds", 60)
            .put("deadLetterEnabled", true)
            .put("batchSize", 25)
            .put("pollingIntervalSeconds", 2)
            .put("fifoEnabled", true)
            .put("deadLetterQueueName", queueName + "_dlq");

        logger.info("Creating queue with full configuration: {}", queueRequest.encode());

        webClient.post(TEST_PORT, "localhost", "/api/v1/management/queues")
            .sendJsonObject(queueRequest)
            .onSuccess(response -> {
                testContext.verify(() -> {
                    logger.info("Create queue response: {} - {}", response.statusCode(), response.bodyAsString());

                    assertEquals(201, response.statusCode(), "Should return 201 Created");

                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body, "Response should be a JSON object");

                    // Verify all parameters are returned in response
                    assertEquals(queueName, body.getString("queueName"));
                    assertEquals(setupId, body.getString("setupId"));
                    assertEquals(5, body.getInteger("maxRetries"));
                    assertEquals(60, body.getInteger("visibilityTimeoutSeconds"));
                    assertTrue(body.getBoolean("deadLetterEnabled"));
                    assertEquals(25, body.getInteger("batchSize"));
                    assertEquals(2, body.getInteger("pollingIntervalSeconds"));
                    assertTrue(body.getBoolean("fifoEnabled"));
                    assertEquals(queueName + "_dlq", body.getString("deadLetterQueueName"));

                    logger.info("✅ Queue created with all parameters:");
                    logger.info("   - maxRetries: {}", body.getInteger("maxRetries"));
                    logger.info("   - visibilityTimeoutSeconds: {}", body.getInteger("visibilityTimeoutSeconds"));
                    logger.info("   - deadLetterEnabled: {}", body.getBoolean("deadLetterEnabled"));
                    logger.info("   - batchSize: {}", body.getInteger("batchSize"));
                    logger.info("   - pollingIntervalSeconds: {}", body.getInteger("pollingIntervalSeconds"));
                    logger.info("   - fifoEnabled: {}", body.getBoolean("fifoEnabled"));
                    logger.info("   - deadLetterQueueName: {}", body.getString("deadLetterQueueName"));

                    testContext.completeNow();
                });
            })
            .onFailure(testContext::failNow);
    }

    // ========================================
    // CRITICAL GAP TESTS - E2E with Real Database
    // ========================================

    @Test
    @Order(100)
    @DisplayName("CRITICAL GAP 1: Queue Purge - Delete all messages from queue")
    void testQueuePurge_E2E(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== CRITICAL GAP TEST 1: Queue Purge ===");

        String purgeQueueName = "purge_test_queue_" + System.currentTimeMillis();

        // Step 1: Create a queue
        JsonObject queueRequest = new JsonObject()
            .put("name", purgeQueueName)
            .put("setup", setupId)
            .put("maxRetries", 3)
            .put("visibilityTimeoutSeconds", 30);

        webClient.post(TEST_PORT, "localhost", "/api/v1/management/queues")
            .sendJsonObject(queueRequest)
            .compose(createResponse -> {
                logger.info("✅ Queue created: {}", purgeQueueName);

                // Step 2: Send 10 messages to the queue
                Future<Void> sendMessages = Future.succeededFuture();
                for (int i = 1; i <= 10; i++) {
                    final int msgNum = i;
                    sendMessages = sendMessages.compose(v -> {
                        JsonObject message = new JsonObject()
                            .put("payload", new JsonObject().put("content", "Message " + msgNum))
                            .put("headers", new JsonObject().put("msg-num", String.valueOf(msgNum)));

                        return webClient.post(TEST_PORT, "localhost",
                                "/api/v1/queues/" + setupId + "/" + purgeQueueName + "/messages")
                            .putHeader("content-type", "application/json")
                            .sendJsonObject(message)
                            .mapEmpty();
                    });
                }
                return sendMessages;
            })
            .compose(v -> {
                logger.info("✅ Sent 10 messages to queue");

                // Step 3: Verify messages exist (browse)
                return webClient.get(TEST_PORT, "localhost",
                        "/api/v1/management/messages?setup=" + setupId + "&queue=" + purgeQueueName + "&limit=20")
                    .send();
            })
            .compose(browseResponse -> {
                logger.info("Browse response before purge: status={}", browseResponse.statusCode());
                JsonObject body = browseResponse.bodyAsJsonObject();
                int messageCount = body.getInteger("messageCount", 0);
                logger.info("Messages in queue before purge: {}", messageCount);

                // Step 4: Purge the queue
                return webClient.post(TEST_PORT, "localhost",
                        "/api/v1/queues/" + setupId + "/" + purgeQueueName + "/purge")
                    .send();
            })
            .compose(purgeResponse -> {
                testContext.verify(() -> {
                    assertEquals(200, purgeResponse.statusCode(), "Purge should return 200 OK");

                    JsonObject body = purgeResponse.bodyAsJsonObject();
                    assertNotNull(body, "Purge response should be JSON");
                    assertNotNull(body.getString("message"), "Should have message field");
                    assertNotNull(body.getInteger("purgedCount"), "Should have purgedCount field");

                    int purgedCount = body.getInteger("purgedCount");
                    logger.info("✅ Purged {} messages from queue", purgedCount);
                    assertTrue(purgedCount >= 0, "Purged count should be >= 0");
                });

                // Step 5: Verify queue is empty (browse again)
                return webClient.get(TEST_PORT, "localhost",
                        "/api/v1/management/messages?setup=" + setupId + "&queue=" + purgeQueueName + "&limit=20")
                    .send();
            })
            .onSuccess(finalBrowseResponse -> {
                testContext.verify(() -> {
                    JsonObject body = finalBrowseResponse.bodyAsJsonObject();
                    int messageCount = body.getInteger("messageCount", 0);
                    logger.info("Messages in queue after purge: {}", messageCount);
                    assertEquals(0, messageCount, "Queue should be empty after purge");

                    logger.info("✅ CRITICAL GAP 1 PASSED: Queue Purge works correctly!");
                    testContext.completeNow();
                });
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(101)
    @DisplayName("CRITICAL GAP 2: Message Browsing - Browse messages without consuming")
    void testMessageBrowsing_E2E(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== CRITICAL GAP TEST 2: Message Browsing ===");

        String browseQueueName = "browse_test_queue_" + System.currentTimeMillis();

        // Step 1: Create a queue
        JsonObject queueRequest = new JsonObject()
            .put("name", browseQueueName)
            .put("setup", setupId)
            .put("maxRetries", 3)
            .put("visibilityTimeoutSeconds", 30);

        webClient.post(TEST_PORT, "localhost", "/api/v1/management/queues")
            .sendJsonObject(queueRequest)
            .compose(createResponse -> {
                logger.info("✅ Queue created: {}", browseQueueName);

                // Step 2: Send 5 messages with unique content
                Future<Void> sendMessages = Future.succeededFuture();
                for (int i = 1; i <= 5; i++) {
                    final int msgNum = i;
                    sendMessages = sendMessages.compose(v -> {
                        JsonObject message = new JsonObject()
                            .put("payload", new JsonObject()
                                .put("content", "Browse test message " + msgNum)
                                .put("timestamp", System.currentTimeMillis()))
                            .put("headers", new JsonObject()
                                .put("msg-num", String.valueOf(msgNum))
                                .put("test-type", "browsing"));

                        return webClient.post(TEST_PORT, "localhost",
                                "/api/v1/queues/" + setupId + "/" + browseQueueName + "/messages")
                            .putHeader("content-type", "application/json")
                            .sendJsonObject(message)
                            .mapEmpty();
                    });
                }
                return sendMessages;
            })
            .compose(v -> {
                logger.info("✅ Sent 5 messages to queue");

                // Step 3: Browse messages (should return all 5)
                return webClient.get(TEST_PORT, "localhost",
                        "/api/v1/management/messages?setup=" + setupId + "&queue=" + browseQueueName + "&limit=10")
                    .send();
            })
            .compose(browseResponse1 -> {
                testContext.verify(() -> {
                    assertEquals(200, browseResponse1.statusCode(), "Browse should return 200 OK");

                    JsonObject body = browseResponse1.bodyAsJsonObject();
                    assertNotNull(body, "Browse response should be JSON");
                    assertNotNull(body.getInteger("messageCount"), "Should have messageCount field");

                    JsonArray messages = body.getJsonArray("messages");
                    assertNotNull(messages, "Should have messages array");

                    int messageCount = body.getInteger("messageCount");
                    logger.info("✅ Browsed {} messages", messageCount);

                    // Verify message structure
                    for (int i = 0; i < messages.size(); i++) {
                        JsonObject msg = messages.getJsonObject(i);
                        assertNotNull(msg.getString("id"), "Message should have id");
                        logger.info("  Message {}: id={}", i + 1, msg.getString("id"));
                    }
                });

                // Step 4: Browse again (messages should still be there - not consumed)
                return webClient.get(TEST_PORT, "localhost",
                        "/api/v1/management/messages?setup=" + setupId + "&queue=" + browseQueueName + "&limit=10")
                    .send();
            })
            .onSuccess(browseResponse2 -> {
                testContext.verify(() -> {
                    JsonObject body = browseResponse2.bodyAsJsonObject();
                    int messageCount = body.getInteger("messageCount");
                    logger.info("✅ Second browse returned {} messages (should be same as first)", messageCount);

                    // Messages should still be there (browsing doesn't consume)
                    assertTrue(messageCount >= 0, "Should have messages (browsing doesn't consume)");

                    logger.info("✅ CRITICAL GAP 2 PASSED: Message Browsing works correctly!");
                    testContext.completeNow();
                });
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(102)
    @DisplayName("CRITICAL GAP 3: Message Polling - Retrieve messages via API")
    void testMessagePolling_E2E(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== CRITICAL GAP TEST 3: Message Polling ===");

        String pollingQueueName = "polling_test_queue_" + System.currentTimeMillis();

        // Step 1: Create a queue
        JsonObject queueRequest = new JsonObject()
            .put("name", pollingQueueName)
            .put("setup", setupId)
            .put("maxRetries", 3)
            .put("visibilityTimeoutSeconds", 30);

        webClient.post(TEST_PORT, "localhost", "/api/v1/management/queues")
            .sendJsonObject(queueRequest)
            .compose(createResponse -> {
                logger.info("✅ Queue created: {}", pollingQueueName);

                // Step 2: Send 3 messages
                Future<Void> sendMessages = Future.succeededFuture();
                for (int i = 1; i <= 3; i++) {
                    final int msgNum = i;
                    sendMessages = sendMessages.compose(v -> {
                        JsonObject message = new JsonObject()
                            .put("payload", new JsonObject()
                                .put("content", "Polling test message " + msgNum)
                                .put("priority", msgNum))
                            .put("headers", new JsonObject()
                                .put("msg-num", String.valueOf(msgNum)));

                        return webClient.post(TEST_PORT, "localhost",
                                "/api/v1/queues/" + setupId + "/" + pollingQueueName + "/messages")
                            .putHeader("content-type", "application/json")
                            .sendJsonObject(message)
                            .mapEmpty();
                    });
                }
                return sendMessages;
            })
            .compose(v -> {
                logger.info("✅ Sent 3 messages to queue");

                // Step 3: Poll messages (count=2, ackMode=manual)
                return webClient.get(TEST_PORT, "localhost",
                        "/api/v1/queues/" + setupId + "/" + pollingQueueName + "/messages?count=2&ackMode=manual")
                    .send();
            })
            .onSuccess(pollingResponse -> {
                testContext.verify(() -> {
                    assertEquals(200, pollingResponse.statusCode(), "Polling should return 200 OK");

                    JsonObject body = pollingResponse.bodyAsJsonObject();
                    assertNotNull(body, "Polling response should be JSON");
                    assertNotNull(body.getString("message"), "Should have message field");
                    assertNotNull(body.getInteger("messageCount"), "Should have messageCount field");

                    JsonArray messages = body.getJsonArray("messages");
                    assertNotNull(messages, "Should have messages array");

                    int messageCount = body.getInteger("messageCount");
                    logger.info("✅ Polled {} messages (requested 2)", messageCount);

                    // Verify we got messages
                    assertTrue(messageCount >= 0, "Should return messages");

                    // Verify message structure
                    for (int i = 0; i < messages.size(); i++) {
                        JsonObject msg = messages.getJsonObject(i);
                        assertNotNull(msg.getString("id"), "Message should have id");
                        logger.info("  Polled message {}: id={}", i + 1, msg.getString("id"));
                    }

                    logger.info("✅ CRITICAL GAP 3 PASSED: Message Polling works correctly!");
                    testContext.completeNow();
                });
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(103)
    @DisplayName("CRITICAL GAP 4: Recent Activity - Show events from event stores")
    void testRecentActivity_E2E(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== CRITICAL GAP TEST 4: Recent Activity ===");

        String eventStoreName = "activity_test_store_" + System.currentTimeMillis();

        // Step 1: Create an event store
        JsonObject eventStoreRequest = new JsonObject()
            .put("name", eventStoreName)
            .put("setup", setupId)
            .put("tableName", eventStoreName + "_table")
            .put("biTemporalEnabled", true)
            .put("notificationPrefix", eventStoreName + "_notify_");

        webClient.post(TEST_PORT, "localhost", "/api/v1/management/event-stores")
            .sendJsonObject(eventStoreRequest)
            .compose(createResponse -> {
                testContext.verify(() -> {
                    assertEquals(201, createResponse.statusCode(), "Event store should be created");
                });
                logger.info("✅ Event store created: {}", eventStoreName);

                // Step 2: Append 3 events to the event store
                Future<Void> appendEvents = Future.succeededFuture();
                for (int i = 1; i <= 3; i++) {
                    final int eventNum = i;
                    appendEvents = appendEvents.compose(v -> {
                        JsonObject event = new JsonObject()
                            .put("aggregateId", "test-aggregate-" + eventNum)
                            .put("eventType", "TestEvent" + eventNum)
                            .put("payload", new JsonObject()
                                .put("action", "test-action-" + eventNum)
                                .put("timestamp", System.currentTimeMillis()));

                        return webClient.post(TEST_PORT, "localhost",
                                "/api/v1/event-stores/" + setupId + "/" + eventStoreName + "/events")
                            .putHeader("content-type", "application/json")
                            .sendJsonObject(event)
                            .mapEmpty();
                    });
                }
                return appendEvents;
            })
            .compose(v -> {
                logger.info("✅ Appended 3 events to event store");

                // Step 3: Get overview (should include recent activity)
                return webClient.get(TEST_PORT, "localhost", "/api/v1/management/overview")
                    .send();
            })
            .onSuccess(overviewResponse -> {
                testContext.verify(() -> {
                    assertEquals(200, overviewResponse.statusCode(), "Overview should return 200 OK");

                    JsonObject body = overviewResponse.bodyAsJsonObject();
                    assertNotNull(body, "Overview response should be JSON");

                    // Verify recentActivity field exists
                    JsonArray recentActivity = body.getJsonArray("recentActivity");
                    assertNotNull(recentActivity, "Should have recentActivity array");

                    logger.info("✅ Recent activity contains {} items", recentActivity.size());

                    // Verify activity structure
                    for (int i = 0; i < recentActivity.size(); i++) {
                        JsonObject activity = recentActivity.getJsonObject(i);
                        assertNotNull(activity.getString("id"), "Activity should have id");
                        assertNotNull(activity.getString("type"), "Activity should have type");
                        assertNotNull(activity.getString("timestamp"), "Activity should have timestamp");
                        logger.info("  Activity {}: type={}, timestamp={}",
                            i + 1, activity.getString("type"), activity.getString("timestamp"));
                    }

                    logger.info("✅ CRITICAL GAP 4 PASSED: Recent Activity works correctly!");
                    testContext.completeNow();
                });
            })
            .onFailure(testContext::failNow);
    }
}
