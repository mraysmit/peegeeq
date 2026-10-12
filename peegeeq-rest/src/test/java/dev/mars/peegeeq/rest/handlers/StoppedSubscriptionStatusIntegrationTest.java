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
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test verifying that a STOPPED consumer-group subscription can be stored in a
 * setup created through the REST API and is reported with the status {@code "stopped"}.
 *
 * <p>The setup is created by {@code POST /api/v1/database-setup/create}, which provisions
 * the schema from the SQL templates. A graceful stop of a whole consumer group stores the
 * status STOPPED, so the template schema must accept that status and
 * {@code ManagementApiHandler} must map it.
 *
 * <p>Classification: INTEGRATION TEST
 * <ul>
 *   <li>Real PostgreSQL database via TestContainers</li>
 *   <li>Real Vert.x HTTP server</li>
 *   <li>No mocks</li>
 * </ul>
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class StoppedSubscriptionStatusIntegrationTest {

    private static final int TEST_PORT = 18142;
    private static final String QUEUE_NAME = "stopped_status_test_queue";

    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private DatabaseSetupService setupService;
    private String deploymentId;
    private String setupId;
    private String groupName;
    private WebClient webClient;

    // ── Setup / Teardown ──────────────────────────────────────────────────────

    @BeforeAll
    void setupServer(Vertx vertx, VertxTestContext testContext) {
        setupId   = "stopped-status-test-" + System.currentTimeMillis();
        groupName = "stopped_status_group_" + System.currentTimeMillis();

        setupService = PeeGeeQRuntime.createDatabaseSetupService();

        RestServerConfig testConfig = new RestServerConfig(
            TEST_PORT,
            RestServerConfig.MonitoringConfig.defaults(),
            java.util.List.of("*")
        );
        PeeGeeQRestServer restServer = new PeeGeeQRestServer(testConfig, setupService);

        vertx.deployVerticle(restServer)
            .compose(id -> {
                deploymentId = id;
                webClient = WebClient.create(vertx);
                return createSetupWithQueue();
            })
            .compose(v -> createConsumerGroupSubscription())
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @AfterAll
    void tearDown(Vertx vertx, VertxTestContext testContext) {
        if (webClient != null) {
            webClient.close();
        }
        Future<Void> undeploy = deploymentId != null
            ? vertx.undeploy(deploymentId)
            : Future.succeededFuture();
        undeploy.onSuccess(v -> testContext.completeNow()).onFailure(testContext::failNow);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Future<Void> createSetupWithQueue() {
        JsonObject setupRequest = new JsonObject()
            .put("setupId", setupId)
            .put("databaseConfig", new JsonObject()
                .put("host",         postgres.getHost())
                .put("port",         postgres.getFirstMappedPort())
                .put("databaseName", "stopped_status_db_" + System.currentTimeMillis())
                .put("username",     postgres.getUsername())
                .put("password",     postgres.getPassword())
                .put("schema", PostgreSQLTestConstants.TEST_SCHEMA)
                .put("templateDatabase", "template0")
                .put("encoding",     "UTF8"))
            .put("queues", new JsonArray()
                .add(new JsonObject()
                    .put("queueName",         QUEUE_NAME)
                    .put("maxRetries",        3)
                    .put("visibilityTimeout", 30)));

        return webClient.post(TEST_PORT, "localhost", "/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .timeout(60_000)
            .sendJsonObject(setupRequest)
            .compose(response -> {
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return Future.succeededFuture();
                }
                return Future.failedFuture("Failed to create setup: "
                    + response.statusCode() + " " + response.bodyAsString());
            });
    }

    private Future<Void> createConsumerGroupSubscription() {
        JsonObject groupRequest = new JsonObject()
            .put("name",      groupName)
            .put("setup",     setupId)
            .put("queueName", QUEUE_NAME);

        return webClient.post(TEST_PORT, "localhost", "/api/v1/management/consumer-groups")
            .putHeader("content-type", "application/json")
            .timeout(30_000)
            .sendJsonObject(groupRequest)
            .compose(response -> {
                if (response.statusCode() == 201) {
                    return Future.succeededFuture();
                }
                return Future.failedFuture("Failed to create consumer group: "
                    + response.statusCode() + " " + response.bodyAsString());
            });
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A STOPPED subscription is stored in a template-provisioned setup and reported as 'stopped'")
    void stoppedSubscriptionIsReportedAsStopped(VertxTestContext testContext) {
        setupService.getSubscriptionServiceForSetup(setupId)
            .stopGroup(QUEUE_NAME, groupName)
            .compose(v -> webClient.get(TEST_PORT, "localhost",
                    "/api/v1/queues/" + setupId + "/" + QUEUE_NAME + "/consumers")
                .send())
            .onSuccess(response -> testContext.verify(() -> {
                assertEquals(200, response.statusCode(),
                    "Queue consumers endpoint should return 200 OK");

                JsonArray consumers = response.bodyAsJsonObject().getJsonArray("consumers");
                assertEquals(1, consumers.size(),
                    "Exactly one consumer-group subscription should be listed");

                JsonObject consumer = consumers.getJsonObject(0);
                assertEquals(groupName, consumer.getString("groupName"));
                assertEquals("stopped", consumer.getString("status"),
                    "A STOPPED subscription must be reported with status 'stopped'");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }
}
