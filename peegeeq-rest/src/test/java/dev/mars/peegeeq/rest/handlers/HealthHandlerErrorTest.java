package dev.mars.peegeeq.rest.handlers;

import dev.mars.peegeeq.rest.PeeGeeQRestServer;
import dev.mars.peegeeq.rest.config.RestServerConfig;
import dev.mars.peegeeq.rest.support.ControllableHealthService;
import dev.mars.peegeeq.rest.support.ControllableSetupService;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
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
 * TDD error-path tests for HealthHandler.
 *
 * Deploys PeeGeeQRestServer with a ControllableSetupService test double.
 * No database. No Testcontainers. The HTTP server is real; only the service is controlled.
 *
 * All tests use defaults() service where getHealthServiceForSetup() returns null.
 * This exercises the synchronous null-guard in all three handler methods, which
 * produces an immediate 404 without calling the HealthService.
 *
 * D1 (smoke): GET /api/v1/health is a static inline lambda in PeeGeeQRestServer —
 * it never calls the service and always returns 200.
 *
 * Port 18113: class-level server with defaults() — used for D1–D4.
 * Port 18114: per-test AUX server with a non-null HealthService — used for E1–E3.
 */
@Tag(TestCategories.CORE)
@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HealthHandlerErrorTest {

    private static final int TEST_PORT = 18113;
    private static final int AUX_PORT  = 18114;

    private static final Logger logger = LoggerFactory.getLogger(HealthHandlerErrorTest.class);

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
    // D1  GET /api/v1/health returns 200 (scaffold smoke test)
    //
    // The top-level health route is a static inline lambda in PeeGeeQRestServer.
    // It never calls the service and always returns a hardcoded JSON object.
    // This test confirms the server deployed correctly and responds on the port.
    // Route: GET /api/v1/health
    // Always GREEN — its value is confirming the server is reachable.
    // =========================================================================

    @Test
    void topLevelHealth_serverUp_returns200(VertxTestContext testContext) {
        webClient.get(TEST_PORT, "localhost", "/api/v1/health")
                .send()
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    assertEquals(200, response.statusCode());
                    assertEquals("UP", response.bodyAsJsonObject().getString("status"));
                    testContext.completeNow();
                })));
    }

    // =========================================================================
    // D2  getOverallHealth for unknown setup returns 404
    //
    // defaults() returns null from getHealthServiceForSetup.
    // Handler checks null synchronously before any async call  404 immediately.
    // Route: GET /api/v1/setups/:setupId/health
    // RED means: handler crashes with NPE or returns 500 instead of 404.
    // =========================================================================

    @Test
    void getOverallHealth_unknownSetup_returns404(VertxTestContext testContext) {
        webClient.get(TEST_PORT, "localhost", "/api/v1/setups/bad-id/health")
                .send()
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    assertEquals(404, response.statusCode());
                    assertNotNull(response.bodyAsJsonObject().getString("error"));
                    testContext.completeNow();
                })));
    }

    // =========================================================================
    // D3  listComponentHealth for unknown setup returns 404
    //
    // Same null-guard path as D2 but for the components list endpoint.
    // Route: GET /api/v1/setups/:setupId/health/components
    // RED means: handler crashes or returns wrong status code.
    // =========================================================================

    @Test
    void listComponentHealth_unknownSetup_returns404(VertxTestContext testContext) {
        webClient.get(TEST_PORT, "localhost", "/api/v1/setups/bad-id/health/components")
                .send()
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    assertEquals(404, response.statusCode());
                    assertNotNull(response.bodyAsJsonObject().getString("error"));
                    testContext.completeNow();
                })));
    }

    // =========================================================================
    // D4  getComponentHealth for unknown setup returns 404
    //
    // Same null-guard path as D2 but for a named component.
    // Route: GET /api/v1/setups/:setupId/health/components/:name
    // RED means: handler crashes or returns wrong status code.
    // =========================================================================

    @Test
    void getComponentHealth_unknownSetup_returns404(VertxTestContext testContext) {
        webClient.get(TEST_PORT, "localhost", "/api/v1/setups/bad-id/health/components/queue")
                .send()
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    assertEquals(404, response.statusCode());
                    assertNotNull(response.bodyAsJsonObject().getString("error"));
                    testContext.completeNow();
                })));
    }

    // =========================================================================
    // E1  getOverallHealth — async failure returns 500
    //
    // ControllableSetupService returns a non-null ControllableHealthService so the
    // null-guard passes. ControllableHealthService.getOverallHealthAsync() returns a
    // failed future. Handler must catch the failure and send 500.
    // Route: GET /api/v1/setups/test-id/health
    // RED means: handler returns 200 (swallows failure) or crashes without responding.
    // =========================================================================

    @Test
    void getOverallHealth_asyncFails_returns500(Vertx vertx, VertxTestContext testContext) {
        logger.info("--- EXPECTED ERROR (E1: getOverallHealth async failure → 500, RuntimeException) ---");
        ControllableHealthService failingHealth = ControllableHealthService.alwaysFailing("health check unavailable");

        ControllableSetupService service = ControllableSetupService.defaults()
                .withHealthServiceForSetup(id -> failingHealth);

        RestServerConfig config = new RestServerConfig(
                AUX_PORT, RestServerConfig.MonitoringConfig.defaults(), List.of("*"));

        vertx.deployVerticle(new PeeGeeQRestServer(config, service))
                .compose(auxId -> webClient.get(AUX_PORT, "localhost", "/api/v1/setups/test-id/health")
                        .send()
                        .eventually(() -> vertx.undeploy(auxId)))
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    assertEquals(500, response.statusCode());
                    assertNotNull(response.bodyAsJsonObject().getString("error"));
                    testContext.completeNow();
                })));
    }

    // =========================================================================
    // E2  listComponentHealth — async failure returns 500
    //
    // Same setup as E1 but for GET /api/v1/setups/:setupId/health/components.
    // Handler calls getOverallHealthAsync() to build the component list;
    // failure must produce 500.
    // Route: GET /api/v1/setups/test-id/health/components
    // RED means: handler returns 200 with empty array (swallows failure).
    // =========================================================================

    @Test
    void listComponentHealth_asyncFails_returns500(Vertx vertx, VertxTestContext testContext) {
        logger.info("--- EXPECTED ERROR (E2: listComponentHealth async failure → 500, RuntimeException) ---");
        ControllableHealthService failingHealth = ControllableHealthService.alwaysFailing("health check unavailable");

        ControllableSetupService service = ControllableSetupService.defaults()
                .withHealthServiceForSetup(id -> failingHealth);

        RestServerConfig config = new RestServerConfig(
                AUX_PORT, RestServerConfig.MonitoringConfig.defaults(), List.of("*"));

        vertx.deployVerticle(new PeeGeeQRestServer(config, service))
                .compose(auxId -> webClient.get(AUX_PORT, "localhost", "/api/v1/setups/test-id/health/components")
                        .send()
                        .eventually(() -> vertx.undeploy(auxId)))
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    assertEquals(500, response.statusCode());
                    assertNotNull(response.bodyAsJsonObject().getString("error"));
                    testContext.completeNow();
                })));
    }

    // =========================================================================
    // E3  getComponentHealth — async failure returns 500
    //
    // Same setup as E1 but for GET /api/v1/setups/:setupId/health/components/:name.
    // Handler calls getComponentHealthAsync(name); failure must produce 500.
    // Route: GET /api/v1/setups/test-id/health/components/database
    // RED means: handler returns 200 or 404 (swallows failure or misroutes it).
    // =========================================================================

    @Test
    void getComponentHealth_asyncFails_returns500(Vertx vertx, VertxTestContext testContext) {
        logger.info("--- EXPECTED ERROR (E3: getComponentHealth async failure → 500, RuntimeException) ---");
        ControllableHealthService failingHealth = ControllableHealthService.alwaysFailing("health check unavailable");

        ControllableSetupService service = ControllableSetupService.defaults()
                .withHealthServiceForSetup(id -> failingHealth);

        RestServerConfig config = new RestServerConfig(
                AUX_PORT, RestServerConfig.MonitoringConfig.defaults(), List.of("*"));

        vertx.deployVerticle(new PeeGeeQRestServer(config, service))
                .compose(auxId -> webClient.get(AUX_PORT, "localhost", "/api/v1/setups/test-id/health/components/database")
                        .send()
                        .eventually(() -> vertx.undeploy(auxId)))
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    assertEquals(500, response.statusCode());
                    assertNotNull(response.bodyAsJsonObject().getString("error"));
                    testContext.completeNow();
                })));
    }
}
