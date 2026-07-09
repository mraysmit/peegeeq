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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the Dead Consumer Alerting REST endpoints (`ConsumerAlertHandler`),
 * closing the §10.5 coverage gap (the handler had no dedicated test class). Exercises the three
 * wired routes end-to-end against a real PostgreSQL (TestContainers) and a deployed
 * {@link PeeGeeQRestServer}:
 *
 * <ul>
 *   <li>GET /api/v1/setups/:setupId/consumer-alerts/dead    — list dead subscriptions</li>
 *   <li>GET /api/v1/setups/:setupId/consumer-alerts/summary — subscription health summary</li>
 *   <li>GET /api/v1/setups/:setupId/consumer-alerts/blocked — blocked message stats</li>
 * </ul>
 *
 * A freshly created setup has no dead/blocked subscriptions, so the meaningful contracts pinned
 * here are: each endpoint returns 200 with a well-formed JSON body over a real subscription
 * service, and an unknown setup returns 404 (the {@code setupNotFound} guard).
 *
 * Classification: INTEGRATION TEST — real PostgreSQL (TestContainers) + real Vert.x HTTP server.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ConsumerAlertHandlerIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(ConsumerAlertHandlerIntegrationTest.class);
    private static final int TEST_PORT = 18101;
    private static final String QUEUE_NAME = "alert_test_queue";

    @Container
    static PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE);
        container.withDatabaseName("peegeeq_alert_test");
        container.withUsername("peegeeq_test");
        container.withPassword("peegeeq_test");
        container.withSharedMemorySize(PostgreSQLTestConstants.DEFAULT_SHARED_MEMORY_SIZE);
        container.withReuse(false);
        return container;
    }

    private PeeGeeQRestServer server;
    private String deploymentId;
    private String setupId;
    private WebClient webClient;

    @BeforeAll
    void setupServer(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Setting up Consumer Alert Handler Integration Test ===");

        setupId = "alert-test-" + System.currentTimeMillis();

        DatabaseSetupService setupService = PeeGeeQRuntime.createDatabaseSetupService();

        RestServerConfig testConfig = new RestServerConfig(
            TEST_PORT, RestServerConfig.MonitoringConfig.defaults(), java.util.List.of("*"));
        server = new PeeGeeQRestServer(testConfig, setupService);
        vertx.deployVerticle(server)
            .compose(id -> {
                deploymentId = id;
                logger.info("REST server deployed with ID: {}", deploymentId);
                webClient = WebClient.create(vertx);
                return createSetupWithQueue(vertx);
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    private Future<Void> createSetupWithQueue(Vertx vertx) {
        JsonObject setupRequest = new JsonObject()
            .put("setupId", setupId)
            .put("databaseConfig", new JsonObject()
                .put("host", postgres.getHost())
                .put("port", postgres.getFirstMappedPort())
                .put("databaseName", "alert_api_db_" + System.currentTimeMillis())
                .put("username", postgres.getUsername())
                .put("password", postgres.getPassword())
                .put("schema", PostgreSQLTestConstants.TEST_SCHEMA)
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
                }
                return Future.failedFuture("Failed to create setup: " + response.statusCode());
            });
    }

    @AfterAll
    void tearDown(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Tearing down Consumer Alert Handler Integration Test ===");
        Future<Void> undeploy = deploymentId != null
            ? vertx.undeploy(deploymentId)
            : Future.succeededFuture();
        undeploy.onSuccess(v -> testContext.completeNow()).onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("Consumer Alerts - GET /consumer-alerts/dead returns an empty dead list for a fresh setup")
    void testListDeadSubscriptions(Vertx vertx, VertxTestContext testContext) {
        webClient.get(TEST_PORT, "localhost",
                "/api/v1/setups/" + setupId + "/consumer-alerts/dead")
            .send()
            .onSuccess(response -> testContext.verify(() -> {
                logger.info("Dead subscriptions response: {} - {}",
                    response.statusCode(), response.bodyAsString());

                assertEquals(200, response.statusCode(), "GET dead should return 200");
                JsonObject body = response.bodyAsJsonObject();
                assertNotNull(body, "Response should be a JSON object");

                JsonArray dead = body.getJsonArray("deadSubscriptions");
                assertNotNull(dead, "Response should contain a deadSubscriptions array");
                assertTrue(dead.isEmpty(), "A fresh setup has no dead subscriptions");
                assertEquals(0, body.getInteger("totalDead").intValue(), "totalDead must be 0");

                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("Consumer Alerts - GET /consumer-alerts/summary returns a 200 JSON summary")
    void testHealthSummary(Vertx vertx, VertxTestContext testContext) {
        webClient.get(TEST_PORT, "localhost",
                "/api/v1/setups/" + setupId + "/consumer-alerts/summary")
            .send()
            .onSuccess(response -> testContext.verify(() -> {
                logger.info("Health summary response: {} - {}",
                    response.statusCode(), response.bodyAsString());

                assertEquals(200, response.statusCode(), "GET summary should return 200");
                assertNotNull(response.bodyAsJsonObject(),
                    "Health summary should be a JSON object");

                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("Consumer Alerts - GET /consumer-alerts/blocked returns a 200 JSON stats object")
    void testBlockedStats(Vertx vertx, VertxTestContext testContext) {
        webClient.get(TEST_PORT, "localhost",
                "/api/v1/setups/" + setupId + "/consumer-alerts/blocked")
            .send()
            .onSuccess(response -> testContext.verify(() -> {
                logger.info("Blocked stats response: {} - {}",
                    response.statusCode(), response.bodyAsString());

                assertEquals(200, response.statusCode(), "GET blocked should return 200");
                assertNotNull(response.bodyAsJsonObject(),
                    "Blocked stats should be a JSON object");

                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("Consumer Alerts - unknown setup returns 404")
    void testUnknownSetupReturns404(Vertx vertx, VertxTestContext testContext) {
        webClient.get(TEST_PORT, "localhost",
                "/api/v1/setups/nonexistent-setup-xyz/consumer-alerts/dead")
            .send()
            .onSuccess(response -> testContext.verify(() -> {
                logger.info("Unknown-setup response: {} - {}",
                    response.statusCode(), response.bodyAsString());

                assertEquals(404, response.statusCode(),
                    "An unknown setup must return 404 (setupNotFound)");

                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }
}
