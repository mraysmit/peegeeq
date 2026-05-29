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

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD error-path tests for ManagementApiHandler.
 *
 * Deploys PeeGeeQRestServer with a ControllableSetupService test double.
 * No database. No Testcontainers. The HTTP server is real; only the service is controlled.
 *
 * Most ManagementApiHandler read-path errors are silently absorbed (transform to empty arrays);
 * only the write-path operations (createQueue, deleteQueue, updateQueue) have
 * observable error responses.
 *
 * Port 18112: class-level server with defaults()  used by C1, C4, C5.
 * Port 18129: per-test inline servers  used by C2, C3 (custom addQueue failures).
 */
@Tag(TestCategories.CORE)
@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ManagementApiHandlerErrorTest {

    private static final int TEST_PORT = 18112;
    private static final int AUX_PORT = 18129;

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
    // C1  createQueue with missing body returns 400
    //
    // ctx.body().asJsonObject() returns null; ConfigParser.getSetupId(null, null)
    // throws NullPointerException caught by catch(Exception)  400.
    // Route: POST /api/v1/management/queues
    // RED means: handler crashes without sending a response.
    // =========================================================================

    @Test
    void createQueue_missingBody_returns400(VertxTestContext testContext) {
        webClient.post(TEST_PORT, "localhost", "/api/v1/management/queues")
                .send()
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    assertEquals(400, response.statusCode());
                    assertNotNull(response.bodyAsJsonObject().getString("error"));
                    testContext.completeNow();
                })));
    }

    // =========================================================================
    // C2  createQueue where addQueue fails with "Setup not found" returns 404
    //
    // Per-test server: the handler checks cause.getMessage().contains("Setup not found").
    // RED means: handler returns 503  message-based check not triggering.
    // =========================================================================

    @Test
    void createQueue_serviceFailsWithSetupNotFound_returns404(Vertx vertx, VertxTestContext testContext) {
        ControllableSetupService svc = ControllableSetupService.defaults()
                .withAddQueue((id, cfg) -> Future.failedFuture(new RuntimeException("Setup not found: " + id)));
        RestServerConfig config = new RestServerConfig(
                AUX_PORT, RestServerConfig.MonitoringConfig.defaults(), List.of("*"));

        JsonObject body = JsonObject.of("queueName", "test-q", "setup", "test-setup");

        vertx.deployVerticle(new PeeGeeQRestServer(config, svc))
                .compose(auxId ->
                        webClient.post(AUX_PORT, "localhost", "/api/v1/management/queues")
                                .putHeader("Content-Type", "application/json")
                                .sendJsonObject(body)
                                .eventually(() -> vertx.undeploy(auxId)))
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    assertEquals(404, response.statusCode());
                    assertNotNull(response.bodyAsJsonObject().getString("error"));
                    testContext.completeNow();
                })));
    }

    // =========================================================================
    // C3  createQueue where addQueue fails with unrelated error returns 503
    //
    // Per-test server: the failure message does not contain "Setup not found"  503.
    // RED means: handler returns 404 (wrong branch) or 400.
    // =========================================================================

    @Test
    void createQueue_serviceFails_returns503(Vertx vertx, VertxTestContext testContext) {
        ControllableSetupService svc = ControllableSetupService.defaults()
                .withAddQueue((id, cfg) -> Future.failedFuture(new RuntimeException("database connection failed")));
        RestServerConfig config = new RestServerConfig(
                AUX_PORT, RestServerConfig.MonitoringConfig.defaults(), List.of("*"));

        JsonObject body = JsonObject.of("queueName", "test-q", "setup", "test-setup");

        vertx.deployVerticle(new PeeGeeQRestServer(config, svc))
                .compose(auxId ->
                        webClient.post(AUX_PORT, "localhost", "/api/v1/management/queues")
                                .putHeader("Content-Type", "application/json")
                                .sendJsonObject(body)
                                .eventually(() -> vertx.undeploy(auxId)))
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    assertEquals(503, response.statusCode());
                    assertNotNull(response.bodyAsJsonObject().getString("error"));
                    testContext.completeNow();
                })));
    }

    // =========================================================================
    // C4  deleteQueue where queue is not in setup factory map returns 404
    //
    // defaults() service returns ACTIVE setup with empty queue factory map.
    // getSetupResult  ACTIVE, getQueueFactories().get("test-q") = null
    //  ResponseException(404)  sendError(ctx, 404).
    // Route: DELETE /api/v1/management/queues/:setupId/:queueName
    // RED means: handler returns 200 or 503 instead of 404.
    // =========================================================================

    @Test
    void deleteQueue_queueNotFound_returns404(VertxTestContext testContext) {
        webClient.delete(TEST_PORT, "localhost", "/api/v1/management/queues/setup1/test-q")
                .send()
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    assertEquals(404, response.statusCode());
                    assertNotNull(response.bodyAsJsonObject().getString("error"));
                    testContext.completeNow();
                })));
    }

    // =========================================================================
    // C5  updateQueue where queue is not in setup factory map returns 404
    //
    // Same service condition as C4 but via PUT.
    // Route: PUT /api/v1/management/queues/:setupId/:queueName
    // RED means: handler returns 200 or wrong status.
    // =========================================================================

    @Test
    void updateQueue_queueNotFound_returns404(VertxTestContext testContext) {
        webClient.put(TEST_PORT, "localhost", "/api/v1/management/queues/setup1/test-q")
                .send()
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    assertEquals(404, response.statusCode());
                    assertNotNull(response.bodyAsJsonObject().getString("error"));
                    testContext.completeNow();
                })));
    }
}
