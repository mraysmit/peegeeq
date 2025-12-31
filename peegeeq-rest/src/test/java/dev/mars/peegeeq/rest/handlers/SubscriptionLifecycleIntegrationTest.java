package dev.mars.peegeeq.rest.handlers;

import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.api.subscription.SubscriptionService;
import dev.mars.peegeeq.rest.PeeGeeQRestServer;
import dev.mars.peegeeq.rest.config.RestServerConfig;
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
 * Integration tests for Subscription Lifecycle REST API endpoints.
 * 
 * Tests the complete subscription lifecycle as defined in PEEGEEQ_CALL_PROPAGATION_DESIGN.md Section 9.5:
 * - GET /api/v1/setups/:setupId/subscriptions/:topic - List subscriptions
 * - GET /api/v1/setups/:setupId/subscriptions/:topic/:groupName - Get subscription
 * - POST /api/v1/setups/:setupId/subscriptions/:topic/:groupName/pause - Pause subscription
 * - POST /api/v1/setups/:setupId/subscriptions/:topic/:groupName/resume - Resume subscription
 * - POST /api/v1/setups/:setupId/subscriptions/:topic/:groupName/heartbeat - Update heartbeat
 * - DELETE /api/v1/setups/:setupId/subscriptions/:topic/:groupName - Cancel subscription
 * 
 * Classification: INTEGRATION TEST
 * - Uses real PostgreSQL database (TestContainers)
 * - Uses real Vert.x HTTP server
 * - Tests end-to-end subscription lifecycle flow
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class SubscriptionLifecycleIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(SubscriptionLifecycleIntegrationTest.class);
    private static final int TEST_PORT = 18098;
    private static final String TOPIC_NAME = "subscription_test_topic";
    private static final String GROUP_NAME = "test-consumer-group";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
            .withDatabaseName("peegeeq_subscription_test")
            .withUsername("peegeeq_test")
            .withPassword("peegeeq_test")
            .withSharedMemorySize(PostgreSQLTestConstants.DEFAULT_SHARED_MEMORY_SIZE)
            .withReuse(false);

    private PeeGeeQRestServer server;
    private DatabaseSetupService setupService;
    private String deploymentId;
    private String setupId;
    private String newDbName;
    private WebClient webClient;

    @BeforeAll
    void setupServer(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== Setting up Subscription Lifecycle Integration Test ===");

        // Note: We do NOT initialize schema here because the REST API will create a NEW database
        // and we need to apply the fanout schema to THAT database, not the TestContainer's default database

        setupId = "subscription-test-" + System.currentTimeMillis();
        newDbName = "sub_db_" + System.currentTimeMillis();

        setupService = PeeGeeQRuntime.createDatabaseSetupService();

        RestServerConfig testConfig = new RestServerConfig(TEST_PORT, RestServerConfig.MonitoringConfig.defaults(), java.util.List.of("*"));
        server = new PeeGeeQRestServer(testConfig, setupService);
        vertx.deployVerticle(server)
            .compose(id -> {
                deploymentId = id;
                logger.info("REST server deployed with ID: {}", deploymentId);
                webClient = WebClient.create(vertx);
                return createSetupWithQueue(vertx);
            })
            .compose(v -> applyFanoutSchema())
            .compose(v -> createSubscription(vertx))
            .onSuccess(v -> {
                logger.info("Test setup complete with subscription created");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    /**
     * Apply the Consumer Group Fanout schema to the newly created database.
     * This must be done AFTER the REST API creates the database because the REST API
     * drops and recreates the database, removing any previously applied schema.
     */
    private Future<Void> applyFanoutSchema() {
        try {
            logger.info("Applying Consumer Group Fanout schema to new database: {}", newDbName);
            String jdbcUrl = String.format("jdbc:postgresql://%s:%d/%s",
                postgres.getHost(), postgres.getMappedPort(5432), newDbName);
            PeeGeeQTestSchemaInitializer.initializeSchema(jdbcUrl,
                postgres.getUsername(), postgres.getPassword(),
                SchemaComponent.OUTBOX, SchemaComponent.CONSUMER_GROUP_FANOUT);
            logger.info("Consumer Group Fanout schema applied successfully");
            return Future.succeededFuture();
        } catch (Exception e) {
            logger.error("Failed to apply fanout schema", e);
            return Future.failedFuture(e);
        }
    }

    private Future<Void> createSetupWithQueue(Vertx vertx) {
        JsonObject setupRequest = new JsonObject()
            .put("setupId", setupId)
            .put("databaseConfig", new JsonObject()
                .put("host", postgres.getHost())
                .put("port", postgres.getFirstMappedPort())
                .put("databaseName", newDbName)
                .put("username", postgres.getUsername())
                .put("password", postgres.getPassword())
                .put("schema", "public")
                .put("templateDatabase", "template0")
                .put("encoding", "UTF8"))
            .put("queues", new JsonArray()
                .add(new JsonObject()
                    .put("queueName", TOPIC_NAME)
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

    /**
     * Creates a subscription record in the outbox_topic_subscriptions table.
     * This is the key step that was missing - we need to actually call subscribe()
     * to create the subscription record that the lifecycle tests operate on.
     */
    private Future<Void> createSubscription(Vertx vertx) {
        // Get the SubscriptionService for this setup
        SubscriptionService subscriptionService = setupService.getSubscriptionServiceForSetup(setupId);
        if (subscriptionService == null) {
            return Future.failedFuture("SubscriptionService not available for setup: " + setupId);
        }

        // Create a subscription record with default options
        SubscriptionOptions options = SubscriptionOptions.builder()
            .build();  // Uses FROM_NOW with default heartbeat settings

        return subscriptionService.subscribe(TOPIC_NAME, GROUP_NAME, options)
            .onSuccess(v -> logger.info("Subscription created: topic={}, group={}", TOPIC_NAME, GROUP_NAME))
            .onFailure(e -> logger.error("Failed to create subscription: {}", e.getMessage(), e));
    }

    @AfterAll
    void tearDown(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Tearing down Subscription Lifecycle Integration Test ===");
        
        Future<Void> undeploy = deploymentId != null 
            ? vertx.undeploy(deploymentId) 
            : Future.succeededFuture();
            
        undeploy.onComplete(ar -> testContext.completeNow());
    }

    // ========== Test Methods ==========

    @Test
    @Order(1)
    @DisplayName("List subscriptions for topic")
    void testListSubscriptions(VertxTestContext testContext) {
        String path = String.format("/api/v1/setups/%s/subscriptions/%s", setupId, TOPIC_NAME);

        webClient.get(TEST_PORT, "localhost", path)
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode(),
                        "Expected 200, got: " + response.statusCode() + " - " + response.bodyAsString());
                    JsonArray subscriptions = response.bodyAsJsonArray();
                    assertNotNull(subscriptions, "Response should be a JSON array");
                    assertTrue(subscriptions.size() >= 1, "Should have at least 1 subscription");
                    logger.info("Found {} subscriptions for topic {}", subscriptions.size(), TOPIC_NAME);
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(2)
    @DisplayName("Get specific subscription")
    void testGetSubscription(VertxTestContext testContext) {
        String path = String.format("/api/v1/setups/%s/subscriptions/%s/%s", setupId, TOPIC_NAME, GROUP_NAME);

        webClient.get(TEST_PORT, "localhost", path)
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode(),
                        "Expected 200, got: " + response.statusCode() + " - " + response.bodyAsString());
                    JsonObject subscription = response.bodyAsJsonObject();
                    assertNotNull(subscription, "Response should be a JSON object");
                    assertEquals(TOPIC_NAME, subscription.getString("topic"));
                    assertEquals(GROUP_NAME, subscription.getString("groupName"));
                    logger.info("Got subscription: {}", subscription.encode());
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(3)
    @DisplayName("Pause subscription")
    void testPauseSubscription(VertxTestContext testContext) {
        String path = String.format("/api/v1/setups/%s/subscriptions/%s/%s/pause", setupId, TOPIC_NAME, GROUP_NAME);

        webClient.post(TEST_PORT, "localhost", path)
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode(),
                        "Expected 200, got: " + response.statusCode() + " - " + response.bodyAsString());
                    JsonObject result = response.bodyAsJsonObject();
                    assertTrue(result.getBoolean("success", false));
                    assertEquals("paused", result.getString("action"));
                    logger.info("Subscription paused successfully");
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(4)
    @DisplayName("Resume subscription")
    void testResumeSubscription(VertxTestContext testContext) {
        String path = String.format("/api/v1/setups/%s/subscriptions/%s/%s/resume", setupId, TOPIC_NAME, GROUP_NAME);

        webClient.post(TEST_PORT, "localhost", path)
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode(),
                        "Expected 200, got: " + response.statusCode() + " - " + response.bodyAsString());
                    JsonObject result = response.bodyAsJsonObject();
                    assertTrue(result.getBoolean("success", false));
                    assertEquals("resumed", result.getString("action"));
                    logger.info("Subscription resumed successfully");
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(5)
    @DisplayName("Update heartbeat")
    void testUpdateHeartbeat(VertxTestContext testContext) {
        String path = String.format("/api/v1/setups/%s/subscriptions/%s/%s/heartbeat", setupId, TOPIC_NAME, GROUP_NAME);

        webClient.post(TEST_PORT, "localhost", path)
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode(),
                        "Expected 200, got: " + response.statusCode() + " - " + response.bodyAsString());
                    JsonObject result = response.bodyAsJsonObject();
                    assertTrue(result.getBoolean("success", false));
                    assertEquals("heartbeat_updated", result.getString("action"));
                    logger.info("Heartbeat updated successfully");
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(6)
    @DisplayName("Cancel subscription")
    void testCancelSubscription(VertxTestContext testContext) {
        String path = String.format("/api/v1/setups/%s/subscriptions/%s/%s", setupId, TOPIC_NAME, GROUP_NAME);

        webClient.delete(TEST_PORT, "localhost", path)
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode(),
                        "Expected 200, got: " + response.statusCode() + " - " + response.bodyAsString());
                    JsonObject result = response.bodyAsJsonObject();
                    assertTrue(result.getBoolean("success", false));
                    assertEquals("cancelled", result.getString("action"));
                    logger.info("Subscription cancelled successfully");
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(7)
    @DisplayName("Get subscription for non-existent setup returns 404")
    void testGetSubscriptionNonExistentSetup(VertxTestContext testContext) {
        String path = String.format("/api/v1/setups/%s/subscriptions/%s/%s", "non-existent-setup", TOPIC_NAME, GROUP_NAME);

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
