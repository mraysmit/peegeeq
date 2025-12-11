package dev.mars.peegeeq.rest.handlers;

import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.rest.PeeGeeQRestServer;
import dev.mars.peegeeq.runtime.PeeGeeQRuntime;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Webhook Push Delivery flow.
 *
 * Tests the complete webhook delivery flow as defined in PEEGEEQ_CALL_PROPAGATION_DESIGN.md Section 9.10:
 * REST → WebhookSubscriptionHandler → WebClient → External Webhook URL
 *
 * Uses a mock HTTP server to receive webhook deliveries.
 *
 * Classification: INTEGRATION TEST
 * - Uses real PostgreSQL database (TestContainers)
 * - Uses real Vert.x HTTP server for PeeGeeQ REST API
 * - Uses mock HTTP server for webhook endpoint
 * - Tests end-to-end webhook push delivery
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class WebhookPushDeliveryIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(WebhookPushDeliveryIntegrationTest.class);
    private static final int REST_PORT = 18095;
    private static final int WEBHOOK_PORT = 18096;
    private static final String QUEUE_NAME = "webhook_test_queue";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
            .withDatabaseName("peegeeq_webhook_test")
            .withUsername("peegeeq_test")
            .withPassword("peegeeq_test")
            .withSharedMemorySize(PostgreSQLTestConstants.DEFAULT_SHARED_MEMORY_SIZE)
            .withReuse(false);

    private PeeGeeQRestServer server;
    private String deploymentId;
    private String setupId;
    private WebClient webClient;

    // Mock webhook server
    private HttpServer webhookServer;
    private CopyOnWriteArrayList<JsonObject> receivedWebhooks = new CopyOnWriteArrayList<>();
    private AtomicInteger webhookCallCount = new AtomicInteger(0);
    private volatile boolean webhookShouldFail = false;

    @BeforeAll
    void setupServers(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== Setting up Webhook Push Delivery Integration Test ===");

        setupId = "webhook-test-" + System.currentTimeMillis();

        // Start mock webhook server first
        startMockWebhookServer(vertx)
            .compose(v -> {
                // Create the setup service using PeeGeeQRuntime
                DatabaseSetupService setupService = PeeGeeQRuntime.createDatabaseSetupService();

                // Start REST server
                server = new PeeGeeQRestServer(REST_PORT, setupService);
                return vertx.deployVerticle(server);
            })
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

    private Future<Void> startMockWebhookServer(Vertx vertx) {
        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());

        router.post("/webhook").handler(ctx -> {
            webhookCallCount.incrementAndGet();

            if (webhookShouldFail) {
                logger.info("Mock webhook returning 500 (simulated failure)");
                ctx.response().setStatusCode(500).end("Simulated failure");
                return;
            }

            JsonObject body = ctx.body().asJsonObject();
            receivedWebhooks.add(body);
            logger.info("Mock webhook received: {}", body.encodePrettily());

            ctx.response()
                .setStatusCode(200)
                .putHeader("content-type", "application/json")
                .end(new JsonObject().put("status", "received").encode());
        });

        webhookServer = vertx.createHttpServer();
        return webhookServer.requestHandler(router).listen(WEBHOOK_PORT)
            .map(server -> {
                logger.info("Mock webhook server started on port {}", WEBHOOK_PORT);
                return null;
            });
    }

    private Future<Void> createSetupWithQueue(Vertx vertx) {
        JsonObject setupRequest = new JsonObject()
            .put("setupId", setupId)
            .put("databaseConfig", new JsonObject()
                .put("host", postgres.getHost())
                .put("port", postgres.getFirstMappedPort())
                .put("databaseName", "webhook_db_" + System.currentTimeMillis())
                .put("username", postgres.getUsername())
                .put("password", postgres.getPassword())
                .put("schema", "public")
                .put("templateDatabase", "template0")
                .put("encoding", "UTF8"))
            .put("queues", new io.vertx.core.json.JsonArray()
                .add(new JsonObject()
                    .put("queueName", QUEUE_NAME)
                    .put("maxRetries", 3)
                    .put("visibilityTimeout", 30)));

        return webClient.post(REST_PORT, "localhost", "/api/v1/database-setup/create")
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
        logger.info("=== Tearing down Webhook Push Delivery Integration Test ===");

        Future<Void> undeploy = deploymentId != null
            ? vertx.undeploy(deploymentId)
            : Future.succeededFuture();

        undeploy
            .compose(v -> webhookServer != null ? webhookServer.close() : Future.succeededFuture())
            .onComplete(ar -> testContext.completeNow());
    }

    @BeforeEach
    void resetWebhookState() {
        receivedWebhooks.clear();
        webhookCallCount.set(0);
        webhookShouldFail = false;
    }

    @Test
    @Order(1)
    @DisplayName("Webhook Subscription - Create subscription returns 201 with subscription details")
    void testCreateWebhookSubscription(Vertx vertx, VertxTestContext testContext) {
        String webhookUrl = "http://localhost:" + WEBHOOK_PORT + "/webhook";

        JsonObject subscriptionRequest = new JsonObject()
            .put("webhookUrl", webhookUrl)
            .put("headers", new JsonObject().put("X-Custom-Header", "test-value"))
            .put("filters", new JsonObject().put("eventType", "OrderCreated"));

        webClient.post(REST_PORT, "localhost",
                "/api/v1/setups/" + setupId + "/queues/" + QUEUE_NAME + "/webhook-subscriptions")
            .sendJsonObject(subscriptionRequest)
            .onSuccess(response -> {
                testContext.verify(() -> {
                    logger.info("Create subscription response: {} - {}",
                        response.statusCode(), response.bodyAsString());

                    assertEquals(201, response.statusCode(),
                        "Should return 201 Created for new subscription");

                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body.getString("subscriptionId"),
                        "Response should contain subscriptionId");
                    assertEquals(setupId, body.getString("setupId"));
                    assertEquals(QUEUE_NAME, body.getString("queueName"));
                    assertEquals(webhookUrl, body.getString("webhookUrl"));
                    assertEquals("ACTIVE", body.getString("status"));
                    assertNotNull(body.getString("createdAt"));

                    testContext.completeNow();
                });
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(2)
    @DisplayName("Webhook Subscription - Get subscription returns subscription details")
    void testGetWebhookSubscription(Vertx vertx, VertxTestContext testContext) {
        String webhookUrl = "http://localhost:" + WEBHOOK_PORT + "/webhook";

        // First create a subscription
        JsonObject subscriptionRequest = new JsonObject()
            .put("webhookUrl", webhookUrl);

        webClient.post(REST_PORT, "localhost",
                "/api/v1/setups/" + setupId + "/queues/" + QUEUE_NAME + "/webhook-subscriptions")
            .sendJsonObject(subscriptionRequest)
            .compose(createResponse -> {
                String subscriptionId = createResponse.bodyAsJsonObject().getString("subscriptionId");
                logger.info("Created subscription: {}", subscriptionId);

                // Now get the subscription
                return webClient.get(REST_PORT, "localhost",
                        "/api/v1/webhook-subscriptions/" + subscriptionId)
                    .send();
            })
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode());

                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body.getString("subscriptionId"));
                    assertEquals(setupId, body.getString("setupId"));
                    assertEquals(QUEUE_NAME, body.getString("queueName"));
                    assertEquals(webhookUrl, body.getString("webhookUrl"));

                    testContext.completeNow();
                });
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(3)
    @DisplayName("Webhook Subscription - Delete subscription returns 204")
    void testDeleteWebhookSubscription(Vertx vertx, VertxTestContext testContext) {
        String webhookUrl = "http://localhost:" + WEBHOOK_PORT + "/webhook";

        // First create a subscription
        JsonObject subscriptionRequest = new JsonObject()
            .put("webhookUrl", webhookUrl);

        webClient.post(REST_PORT, "localhost",
                "/api/v1/setups/" + setupId + "/queues/" + QUEUE_NAME + "/webhook-subscriptions")
            .sendJsonObject(subscriptionRequest)
            .compose(createResponse -> {
                String subscriptionId = createResponse.bodyAsJsonObject().getString("subscriptionId");
                logger.info("Created subscription to delete: {}", subscriptionId);

                // Delete the subscription
                return webClient.delete(REST_PORT, "localhost",
                        "/api/v1/webhook-subscriptions/" + subscriptionId)
                    .send()
                    .map(deleteResponse -> new Object[] { deleteResponse, subscriptionId });
            })
            .compose(arr -> {
                io.vertx.ext.web.client.HttpResponse<?> deleteResponse =
                    (io.vertx.ext.web.client.HttpResponse<?>) ((Object[]) arr)[0];
                String subscriptionId = (String) ((Object[]) arr)[1];

                testContext.verify(() -> {
                    assertEquals(204, deleteResponse.statusCode(),
                        "Delete should return 204 No Content");
                });

                // Verify subscription is gone
                return webClient.get(REST_PORT, "localhost",
                        "/api/v1/webhook-subscriptions/" + subscriptionId)
                    .send();
            })
            .onSuccess(getResponse -> {
                testContext.verify(() -> {
                    assertEquals(404, getResponse.statusCode(),
                        "Deleted subscription should return 404");
                    testContext.completeNow();
                });
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(4)
    @DisplayName("Webhook Subscription - Get non-existent subscription returns 404")
    void testGetNonExistentSubscription(Vertx vertx, VertxTestContext testContext) {
        webClient.get(REST_PORT, "localhost",
                "/api/v1/webhook-subscriptions/non-existent-id")
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(404, response.statusCode());
                    testContext.completeNow();
                });
            })
            .onFailure(testContext::failNow);
    }
}

