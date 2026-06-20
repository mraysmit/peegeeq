package dev.mars.peegeeq.rest.handlers;

import dev.mars.peegeeq.rest.PeeGeeQRestServer;
import dev.mars.peegeeq.rest.config.RestServerConfig;
import dev.mars.peegeeq.rest.support.ControllableSetupService;
import dev.mars.peegeeq.api.setup.SetupNotFoundException;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
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
 * TDD error-path tests for DatabaseSetupHandler.
 *
 * Deploys PeeGeeQRestServer with a hand-written ControllableSetupService test double.
 * No database. No Testcontainers. The HTTP server is real; only the service is controlled.
 *
 * Port 18110: class-level server with defaults()  used by A1 (listSetups) and A2 (destroySetup).
 * Port 18119: per-test inline servers  used by A3-A8 where specific failure modes are required.
 */
@Tag(TestCategories.CORE)
@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatabaseSetupHandlerErrorTest {

    private static final int TEST_PORT = 18110;
    // Auxiliary port for per-test servers that need specific service configurations.
    // Tests are sequential by default; the port is freed before the next test starts.
    private static final int AUX_PORT = 18119;

    private static final Logger logger = LoggerFactory.getLogger(DatabaseSetupHandlerErrorTest.class);

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
    // A1  listSetups with empty service returns 200 with empty setupIds
    // Uses class-level server (port 18110, defaults()).
    // =========================================================================

    @Test
    void listSetups_emptyService_returns200(VertxTestContext testContext) {
        webClient.get(TEST_PORT, "localhost", "/api/v1/setups")
                .send()
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    assertEquals(200, response.statusCode());
                    assertEquals(0, response.bodyAsJsonObject().getInteger("count"));
                    testContext.completeNow();
                })));
    }

    // =========================================================================
    // A2  destroySetup with succeeding service returns 204 empty body
    // Uses class-level server (port 18110, defaults()).
    // =========================================================================

    @Test
    void destroySetup_serviceSucceeds_returns204(VertxTestContext testContext) {
        webClient.delete(TEST_PORT, "localhost", "/api/v1/setups/any-id")
                .send()
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    assertEquals(204, response.statusCode());
                    testContext.completeNow();
                })));
    }

    // =========================================================================
    // A3  getSetupStatus with SetupNotFoundException returns 404
    // Per-test server: isSetupNotFoundError checks instanceof SetupNotFoundException.
    // RED means: handler returns 503  exception type not recognised.
    // =========================================================================

    @Test
    void getSetupStatus_unknownSetup_returns404(Vertx vertx, VertxTestContext testContext) {
        logger.info("--- EXPECTED ERROR (A3: getSetupStatus unknown setup → 404, SetupNotFoundException) ---");
        ControllableSetupService svc = ControllableSetupService.defaults()
                .withGetSetupStatus(id -> Future.failedFuture(new SetupNotFoundException("Setup not found: " + id)));
        RestServerConfig config = new RestServerConfig(
                AUX_PORT, RestServerConfig.MonitoringConfig.defaults(), List.of("*"));

        vertx.deployVerticle(new PeeGeeQRestServer(config, svc))
                .compose(auxId ->
                        webClient.get(AUX_PORT, "localhost", "/api/v1/setups/bad-id/status")
                                .send()
                                .eventually(() -> vertx.undeploy(auxId)))
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    assertEquals(404, response.statusCode());
                    assertNotNull(response.bodyAsJsonObject().getString("error"));
                    testContext.completeNow();
                })));
    }

    // =========================================================================
    // A4  getSetupStatus with RuntimeException returns 503
    // Per-test server: plain RuntimeException does NOT trigger isSetupNotFoundError.
    // RED means: handler hangs or crashes  failure path not reached.
    // =========================================================================

    @Test
    void getSetupStatus_serviceFails_returns503(Vertx vertx, VertxTestContext testContext) {
        logger.info("--- EXPECTED ERROR (A4: getSetupStatus service failure → 503, RuntimeException) ---");
        ControllableSetupService svc = ControllableSetupService.defaults()
                .withGetSetupStatus(id -> Future.failedFuture(new RuntimeException("service unavailable")));
        RestServerConfig config = new RestServerConfig(
                AUX_PORT, RestServerConfig.MonitoringConfig.defaults(), List.of("*"));

        vertx.deployVerticle(new PeeGeeQRestServer(config, svc))
                .compose(auxId ->
                        webClient.get(AUX_PORT, "localhost", "/api/v1/setups/any-id/status")
                                .send()
                                .eventually(() -> vertx.undeploy(auxId)))
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    assertEquals(503, response.statusCode());
                    assertNotNull(response.bodyAsJsonObject().getString("error"));
                    testContext.completeNow();
                })));
    }

    // =========================================================================
    // A5  getSetupDetails with SetupNotFoundException returns 404
    // Per-test server: handler calls getSetupResult, uses isSetupNotFoundError.
    // RED means: handler returns 503  same root cause as A3.
    // =========================================================================

    @Test
    void getSetupDetails_unknownSetup_returns404(Vertx vertx, VertxTestContext testContext) {
        logger.info("--- EXPECTED ERROR (A5: getSetupDetails unknown setup → 404, SetupNotFoundException) ---");
        ControllableSetupService svc = ControllableSetupService.defaults()
                .withGetSetupResult(id -> Future.failedFuture(new SetupNotFoundException("Setup not found: " + id)));
        RestServerConfig config = new RestServerConfig(
                AUX_PORT, RestServerConfig.MonitoringConfig.defaults(), List.of("*"));

        vertx.deployVerticle(new PeeGeeQRestServer(config, svc))
                .compose(auxId ->
                        webClient.get(AUX_PORT, "localhost", "/api/v1/setups/bad-id")
                                .send()
                                .eventually(() -> vertx.undeploy(auxId)))
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    assertEquals(404, response.statusCode());
                    assertNotNull(response.bodyAsJsonObject().getString("error"));
                    testContext.completeNow();
                })));
    }

    // =========================================================================
    // A6  addQueue with "Setup not found" failure message returns 404
    // Per-test server: addQueue checks cause.getMessage().contains("Setup not found"),
    // NOT isSetupNotFoundError. Message-based check, not class-name-based.
    // RED means: handler returns 503  message check not triggering.
    // =========================================================================

    @Test
    void addQueue_unknownSetup_returns404(Vertx vertx, VertxTestContext testContext) {
        logger.info("--- EXPECTED ERROR (A6: addQueue unknown setup → 404, RuntimeException) ---");
        ControllableSetupService svc = ControllableSetupService.defaults()
                .withAddQueue((id, cfg) -> Future.failedFuture(new RuntimeException("Setup not found: " + id)));
        RestServerConfig config = new RestServerConfig(
                AUX_PORT, RestServerConfig.MonitoringConfig.defaults(), List.of("*"));

        vertx.deployVerticle(new PeeGeeQRestServer(config, svc))
                .compose(auxId ->
                        webClient.post(AUX_PORT, "localhost", "/api/v1/setups/bad-id/queues")
                                .putHeader("Content-Type", "application/json")
                                .sendJsonObject(io.vertx.core.json.JsonObject.of("queueName", "test-q"))
                                .eventually(() -> vertx.undeploy(auxId)))
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    assertEquals(404, response.statusCode());
                    assertNotNull(response.bodyAsJsonObject().getString("error"));
                    testContext.completeNow();
                })));
    }

    // =========================================================================
    // A7  createSetup with missing body returns 400
    // Uses class-level server (port 18110): body parsing fails before service call.
    // RED means: handler NPE produces 500 or no response instead of 400.
    // =========================================================================

    @Test
    void createSetup_missingBody_returns400(VertxTestContext testContext) {
        logger.info("--- EXPECTED ERROR (A7: createSetup missing body → 400, NullPointerException) ---");
        webClient.post(TEST_PORT, "localhost", "/api/v1/setups")
                .send()
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    assertEquals(400, response.statusCode());
                    assertNotNull(response.bodyAsJsonObject().getString("error"));
                    testContext.completeNow();
                })));
    }

    // =========================================================================
    // A8  createSetup where service fails returns 503
    // Per-test server: valid body passes parsing; service returns failed future.
    // Failure message must not contain "already exists" or "invalid" (those produce
    // 409 and 400 respectively).
    // RED means: handler does not reach onFailure or returns wrong status.
    // =========================================================================

    @Test
    void createSetup_serviceFails_returns503(Vertx vertx, VertxTestContext testContext) {
        logger.info("--- EXPECTED ERROR (A8: createSetup service failure → 503, RuntimeException) ---");
        ControllableSetupService svc = ControllableSetupService.defaults()
                .withCreateCompleteSetup(req -> Future.failedFuture(new RuntimeException("service unavailable")));
        RestServerConfig config = new RestServerConfig(
                AUX_PORT, RestServerConfig.MonitoringConfig.defaults(), List.of("*"));

        io.vertx.core.json.JsonObject body = io.vertx.core.json.JsonObject.of(
                "setupId", "error-test",
                "databaseConfig", io.vertx.core.json.JsonObject.of(
                        "host", "localhost",
                        "port", 5432,
                        "databaseName", "testdb",
                        "username", "postgres",
                        "password", "postgres",
                        "schema", PostgreSQLTestConstants.TEST_SCHEMA));

        vertx.deployVerticle(new PeeGeeQRestServer(config, svc))
                .compose(auxId ->
                        webClient.post(AUX_PORT, "localhost", "/api/v1/setups")
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
    // A9  createSetup where service fails with "already exists" message returns 409
    //
    // Per-test server: the 409 path is triggered by cause.getMessage().contains("already exists").
    // isDatabaseCreationConflictError is used only for log suppression — it does NOT set the
    // status code. A plain RuntimeException with the right message is sufficient.
    // RED means: handler returns 503  the message-content check is not reached or not matching.
    // =========================================================================

    @Test
    void createSetup_conflictingSetup_returns409(Vertx vertx, VertxTestContext testContext) {
        logger.info("--- EXPECTED ERROR (A9: createSetup conflict → 409, 'already exists' message) ---");
        ControllableSetupService svc = ControllableSetupService.defaults()
                .withCreateCompleteSetup(req -> Future.failedFuture(
                        new RuntimeException("Setup '" + req.getSetupId() + "' already exists")));
        RestServerConfig config = new RestServerConfig(
                AUX_PORT, RestServerConfig.MonitoringConfig.defaults(), List.of("*"));

        io.vertx.core.json.JsonObject body = io.vertx.core.json.JsonObject.of(
                "setupId", "duplicate-setup",
                "databaseConfig", io.vertx.core.json.JsonObject.of(
                        "host", "localhost",
                        "port", 5432,
                        "databaseName", "testdb",
                        "username", "postgres",
                        "password", "postgres",
                        "schema", PostgreSQLTestConstants.TEST_SCHEMA));

        vertx.deployVerticle(new PeeGeeQRestServer(config, svc))
                .compose(auxId ->
                        webClient.post(AUX_PORT, "localhost", "/api/v1/setups")
                                .putHeader("Content-Type", "application/json")
                                .sendJsonObject(body)
                                .eventually(() -> vertx.undeploy(auxId)))
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    assertEquals(409, response.statusCode());
                    assertNotNull(response.bodyAsJsonObject().getString("error"));
                    testContext.completeNow();
                })));
    }

    // =========================================================================
    // A10  createSetup with missing schema returns 400
    // Uses class-level server (port 18110): schema is required; the env-driven
    // "peegeeq" fallback no longer exists — schema must come from the request.
    // =========================================================================

    @Test
    void createSetup_missingSchema_returns400(VertxTestContext testContext) {
        logger.info("--- EXPECTED ERROR (A10: createSetup missing schema → 400) ---");
        io.vertx.core.json.JsonObject body = io.vertx.core.json.JsonObject.of(
                "setupId", "schema-test",
                "databaseConfig", io.vertx.core.json.JsonObject.of(
                        "host", "localhost",
                        "port", 5432,
                        "databaseName", "testdb",
                        "username", "postgres",
                        "password", "postgres"
                        // schema intentionally omitted
                ));

        webClient.post(TEST_PORT, "localhost", "/api/v1/setups")
                .putHeader("Content-Type", "application/json")
                .sendJsonObject(body)
                .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                    assertEquals(400, response.statusCode());
                    assertNotNull(response.bodyAsJsonObject().getString("error"));
                    testContext.completeNow();
                })));
    }
}
