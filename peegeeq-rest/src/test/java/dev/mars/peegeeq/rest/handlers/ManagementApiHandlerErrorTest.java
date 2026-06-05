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
 * TDD error-path tests for ManagementApiHandler.
 *
 * Deploys PeeGeeQRestServer with a ControllableSetupService test double.
 * No database. No Testcontainers. The HTTP server is real; only the service is controlled.
 *
 * NOTE on read-path error absorption: getRealQueues(), getRealConsumerGroups(), and
 * getRealEventStores() each use .transform(ar -> succeededFuture(emptyArray)) to absorb
 * getAllActiveSetupIds() failures. Those routes always return 200 with an empty list even
 * when the service fails — there is no 503 path to test on them.
 * Only getSystemOverview() propagates service failures to onFailure(503).
 *
 * Port 18112: class-level server with defaults() — used by C1 (overview), C2 (queues),
 *             C4 (createQueue body missing), C7 (deleteQueue), C8 (updateQueue).
 * Port 18129: per-test inline servers — used by C3, C5, C6 (custom service configurations).
 */
@Tag(TestCategories.CORE)
@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ManagementApiHandlerErrorTest {

    private static final int TEST_PORT = 18112;
    private static final int AUX_PORT = 18129;

    private static final Logger logger = LoggerFactory.getLogger(ManagementApiHandlerErrorTest.class);

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
    // C1  getSystemOverview with no setups returns 200 with empty setups array
    //
    // defaults() service returns Set.of() from getAllActiveSetupIds().
    // Handler composes to an empty JsonArray and returns 200.
    // Route: GET /api/v1/management/overview
    // RED means: handler crashes on empty result or returns wrong status.
    // =========================================================================

    @Test
    void getOverview_noSetups_returns200(VertxTestContext testContext) {
        webClient.get(TEST_PORT, "localhost", "/api/v1/management/overview")
                .send()
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    assertEquals(200, response.statusCode());
                    testContext.completeNow();
                })));
    }

    // =========================================================================
    // C2  getQueues with no setups returns 200 with empty queues array
    //
    // defaults() service returns Set.of(). getRealQueues() returns empty array.
    // Route: GET /api/v1/management/queues
    // RED means: handler crashes on empty result or returns wrong status.
    // =========================================================================

    @Test
    void getQueues_noSetups_returns200(VertxTestContext testContext) {
        webClient.get(TEST_PORT, "localhost", "/api/v1/management/queues")
                .send()
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    assertEquals(200, response.statusCode());
                    testContext.completeNow();
                })));
    }

    // =========================================================================
    // C3  getSystemOverview where getAllActiveSetupIds fails returns 503
    //
    // getSystemOverview calls getAllActiveSetupIds() directly and composes on it.
    // Unlike getRealQueues/ConsumerGroups/EventStores, it does NOT absorb failures
    // with .transform — onFailure(503) fires.
    // Per-test server with alwaysFailing("service down").
    // Route: GET /api/v1/management/overview
    // RED means: handler silently absorbs the failure and returns 200 instead of 503.
    // =========================================================================

    @Test
    void getOverview_serviceFails_returns503(Vertx vertx, VertxTestContext testContext) {
        logger.info("--- EXPECTED ERROR (C3: getSystemOverview service failure → 503) ---");
        ControllableSetupService svc = ControllableSetupService.alwaysFailing("service down");
        RestServerConfig config = new RestServerConfig(
                AUX_PORT, RestServerConfig.MonitoringConfig.defaults(), List.of("*"));

        vertx.deployVerticle(new PeeGeeQRestServer(config, svc))
                .compose(auxId ->
                        webClient.get(AUX_PORT, "localhost", "/api/v1/management/overview")
                                .send()
                                .eventually(() -> vertx.undeploy(auxId)))
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    assertEquals(503, response.statusCode());
                    assertNotNull(response.bodyAsJsonObject().getString("error"));
                    testContext.completeNow();
                })));
    }

    // =========================================================================
    // C4  createQueue with missing body returns 400
    //
    // ctx.body().asJsonObject() returns null; ConfigParser.getSetupId(null, null)
    // throws NullPointerException caught by catch(Exception) → 400.
    // Route: POST /api/v1/management/queues
    // RED means: handler crashes without sending a response.
    // =========================================================================

    @Test
    void createQueue_missingBody_returns400(VertxTestContext testContext) {
        logger.info("--- EXPECTED ERROR (C1: createQueue missing body → 400, NullPointerException) ---");
        webClient.post(TEST_PORT, "localhost", "/api/v1/management/queues")
                .send()
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    assertEquals(400, response.statusCode());
                    assertNotNull(response.bodyAsJsonObject().getString("error"));
                    testContext.completeNow();
                })));
    }

    // =========================================================================
    // C5  createQueue where addQueue fails with "Setup not found" returns 404
    //
    // Per-test server: the handler checks cause.getMessage().contains("Setup not found").
    // RED means: handler returns 503  message-based check not triggering.
    // =========================================================================

    @Test
    void createQueue_serviceFailsWithSetupNotFound_returns404(Vertx vertx, VertxTestContext testContext) {
        logger.info("--- EXPECTED ERROR (C2: createQueue Setup not found → 404, RuntimeException) ---");
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
    // C6  createQueue where addQueue fails with unrelated error returns 503
    //
    // Per-test server: the failure message does not contain "Setup not found"  503.
    // RED means: handler returns 404 (wrong branch) or 400.
    // =========================================================================

    @Test
    void createQueue_serviceFails_returns503(Vertx vertx, VertxTestContext testContext) {
        logger.info("--- EXPECTED ERROR (C3: createQueue service failure → 503, RuntimeException) ---");
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
    // C7  deleteQueue where queue is not in setup factory map returns 404
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
    // C8  updateQueue where queue is not in setup factory map returns 404
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
