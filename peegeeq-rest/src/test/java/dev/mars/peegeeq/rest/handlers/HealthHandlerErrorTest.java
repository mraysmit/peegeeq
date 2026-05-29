package dev.mars.peegeeq.rest.handlers;

import dev.mars.peegeeq.rest.PeeGeeQRestServer;
import dev.mars.peegeeq.rest.config.RestServerConfig;
import dev.mars.peegeeq.rest.support.ControllableSetupService;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

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
 * Port 18113: class-level server with defaults().
 */
@Tag(TestCategories.CORE)
@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HealthHandlerErrorTest {

    private static final int TEST_PORT = 18113;

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
    // D1  getOverallHealth for unknown setup returns 404
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
    // D2  listComponentHealth for unknown setup returns 404
    //
    // Same null-guard path as D1 but for the components list endpoint.
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
    // D3  getComponentHealth for unknown setup returns 404
    //
    // Same null-guard path as D1 but for a named component.
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
}
