package dev.mars.peegeeq.rest.handlers;

import dev.mars.peegeeq.rest.PeeGeeQRestServer;
import dev.mars.peegeeq.rest.config.RestServerConfig;
import dev.mars.peegeeq.rest.support.ControllableSetupService;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD error-path tests for QueueHandler.
 *
 * Deploys PeeGeeQRestServer with a ControllableSetupService test double.
 * No database. No Testcontainers. The HTTP server is real; only the service is controlled.
 *
 * Port 18111: class-level server with defaults() (empty queue factory map).
 * All tests exercise error paths reachable without a real database or real queue factory.
 */
@Tag(TestCategories.CORE)
@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QueueHandlerUnitTest {

    private static final int TEST_PORT = 18111;

    private static final Logger logger = LoggerFactory.getLogger(QueueHandlerUnitTest.class);

    private String deploymentId;
    private WebClient webClient;

    @BeforeAll
    void deployServer(Vertx vertx, VertxTestContext testContext) {
        RestServerConfig config = new RestServerConfig(
                TEST_PORT, RestServerConfig.MonitoringConfig.defaults(), List.of("*"));

        vertx.deployVerticle(new PeeGeeQRestServer(config, ControllableSetupService.defaults()))
                .onComplete(testContext.succeeding(id -> testContext.verify(() -> {
                    deploymentId = id;
                    webClient = WebClient.create(vertx);
                    testContext.completeNow();
                })));
    }

    @AfterAll
    void tearDown(Vertx vertx, VertxTestContext testContext) {
        (deploymentId != null ? vertx.undeploy(deploymentId) : Future.<Void>succeededFuture())
                .onComplete(testContext.succeeding(v -> testContext.completeNow()));
    }

    // =========================================================================
    // B1  sendMessage where queue factory is absent returns 404
    //
    // defaults() service returns ACTIVE status but empty queue factory map.
    // getQueueFactory() fails with ResponseException(404).
    // Route: POST /api/v1/queues/:setupId/:queueName/messages
    // RED means: handler returns 200 (shouldn't) or 503 (wrong status branch).
    // =========================================================================

    @Test
    void sendMessage_unknownQueue_returns404(VertxTestContext testContext) {
        webClient.post(TEST_PORT, "localhost", "/api/v1/queues/setup1/test-q/messages")
                .putHeader("Content-Type", "application/json")
                .sendJsonObject(JsonObject.of("payload", "hello"))
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    assertEquals(404, response.statusCode());
                    assertNotNull(response.bodyAsJsonObject().getString("error"));
                    testContext.completeNow();
                })));
    }

    // =========================================================================
    // B2  getQueueStats where setup has no matching queue factory returns 404
    //
    // defaults() service returns ACTIVE status but empty queue factory map.
    // compose() yields ResponseException(404).
    // Route: GET /api/v1/queues/:setupId/:queueName/stats
    // RED means: handler returns 503 or 500 instead of 404.
    // =========================================================================

    @Test
    void getQueueStats_unknownQueue_returns404(VertxTestContext testContext) {
        webClient.get(TEST_PORT, "localhost", "/api/v1/queues/setup1/test-q/stats")
                .send()
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    assertEquals(404, response.statusCode());
                    assertNotNull(response.bodyAsJsonObject().getString("error"));
                    testContext.completeNow();
                })));
    }

    // =========================================================================
    // B3  sendMessage with missing body returns 400
    //
    // parseAndValidateRequest throws IllegalArgumentException when body is absent.
    // The try-catch in sendMessage catches it and returns 400.
    // Route: POST /api/v1/queues/:setupId/:queueName/messages
    // RED means: handler returns 503 or crashes instead of 400.
    // =========================================================================

    @Test
    void sendMessage_missingBody_returns400(VertxTestContext testContext) {
        logger.info("--- EXPECTED ERROR (B3: sendMessage missing body → 400, IllegalArgumentException) ---");
        webClient.post(TEST_PORT, "localhost", "/api/v1/queues/setup1/test-q/messages")
                .send()
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    assertEquals(400, response.statusCode());
                    assertNotNull(response.bodyAsJsonObject().getString("error"));
                    testContext.completeNow();
                })));
    }
}
