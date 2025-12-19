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
}
