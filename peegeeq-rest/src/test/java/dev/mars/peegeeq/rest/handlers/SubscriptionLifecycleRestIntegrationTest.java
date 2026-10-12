package dev.mars.peegeeq.rest.handlers;

import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.rest.config.RestServerConfig;
import dev.mars.peegeeq.rest.PeeGeeQRestServer;
import dev.mars.peegeeq.runtime.PeeGeeQRuntime;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test of the REST endpoints that make subscription lifecycle calls.
 *
 * <p>A lifecycle call that the current status of the subscription refuses returns HTTP 409
 * and changes nothing. The queue pause and the queue resume call the lifecycle method only
 * for the subscriptions whose status accepts it.</p>
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
public class SubscriptionLifecycleRestIntegrationTest {

    private static final int TEST_PORT = 18143;
    // Each test that acts on a whole queue has a queue of its own.
    private static final String SINGLE_GROUP_QUEUE = "lifecycle_single_group_queue";
    private static final String PAUSE_RESUME_QUEUE = "lifecycle_pause_resume_queue";

    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private DatabaseSetupService setupService;
    private String deploymentId;
    private String setupId;
    private WebClient webClient;

    // ── Setup / Teardown ──────────────────────────────────────────────────────

    @BeforeAll
    void setupServer(Vertx vertx, VertxTestContext testContext) {
        setupId = "lifecycle-rest-test-" + System.currentTimeMillis();
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
                return createSetupWithQueues();
            })
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

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A pause of a cancelled consumer group returns 409 and leaves it cancelled")
    void pauseOfCancelledConsumerGroupReturns409(VertxTestContext testContext) {
        String groupName = "cancelled_group_" + System.nanoTime();

        createConsumerGroup(SINGLE_GROUP_QUEUE, groupName)
            .compose(v -> setupService.getSubscriptionServiceForSetup(setupId).cancel(SINGLE_GROUP_QUEUE, groupName))
            .compose(v -> webClient.post(TEST_PORT, "localhost",
                    "/api/v1/management/consumer-groups/" + setupId + "/" + SINGLE_GROUP_QUEUE + "/" + groupName + "/pause")
                .send())
            .compose(response -> {
                assertEquals(409, response.statusCode(),
                    "A pause of a cancelled group must return 409. Response: " + response.bodyAsString());
                assertTrue(response.bodyAsString().contains("CANCELLED"),
                    "The response must name the refusing status. Response: " + response.bodyAsString());
                return consumerStatuses(SINGLE_GROUP_QUEUE);
            })
            .onSuccess(statuses -> testContext.verify(() -> {
                assertEquals("cancelled", statuses.get(groupName),
                    "A refused pause must leave the subscription cancelled");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("A heartbeat on a stopped subscription returns 409 and leaves it stopped")
    void heartbeatOnStoppedSubscriptionReturns409(VertxTestContext testContext) {
        String groupName = "stopped_group_" + System.nanoTime();

        createConsumerGroup(SINGLE_GROUP_QUEUE, groupName)
            .compose(v -> setupService.getSubscriptionServiceForSetup(setupId).stopGroup(SINGLE_GROUP_QUEUE, groupName))
            .compose(v -> webClient.post(TEST_PORT, "localhost",
                    "/api/v1/setups/" + setupId + "/subscriptions/" + SINGLE_GROUP_QUEUE + "/" + groupName + "/heartbeat")
                .send())
            .compose(response -> {
                assertEquals(409, response.statusCode(),
                    "A heartbeat on a stopped subscription must return 409. Response: " + response.bodyAsString());
                assertTrue(response.bodyAsString().contains("STOPPED"),
                    "The response must name the refusing status. Response: " + response.bodyAsString());
                return consumerStatuses(SINGLE_GROUP_QUEUE);
            })
            .onSuccess(statuses -> testContext.verify(() -> {
                assertEquals("stopped", statuses.get(groupName),
                    "A refused heartbeat must leave the subscription stopped");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("A resume of a stopped subscription returns 409 and leaves it stopped")
    void resumeOfStoppedSubscriptionReturns409(VertxTestContext testContext) {
        String groupName = "stopped_resume_group_" + System.nanoTime();

        createConsumerGroup(SINGLE_GROUP_QUEUE, groupName)
            .compose(v -> setupService.getSubscriptionServiceForSetup(setupId).stopGroup(SINGLE_GROUP_QUEUE, groupName))
            .compose(v -> webClient.post(TEST_PORT, "localhost",
                    "/api/v1/setups/" + setupId + "/subscriptions/" + SINGLE_GROUP_QUEUE + "/" + groupName + "/resume")
                .send())
            .compose(response -> {
                assertEquals(409, response.statusCode(),
                    "A resume of a stopped subscription must return 409. Response: " + response.bodyAsString());
                return consumerStatuses(SINGLE_GROUP_QUEUE);
            })
            .onSuccess(statuses -> testContext.verify(() -> {
                assertEquals("stopped", statuses.get(groupName),
                    "A refused resume must leave the subscription stopped");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("The queue pause and the queue resume call only the subscriptions whose status accepts the call")
    void queuePauseAndResumeSkipSubscriptionsThatRefuse(VertxTestContext testContext) {
        String activeGroup = "active_group_" + System.nanoTime();
        String stoppedGroup = "stopped_group_" + System.nanoTime();
        String cancelledGroup = "cancelled_group_" + System.nanoTime();
        String pausePath = "/api/v1/queues/" + setupId + "/" + PAUSE_RESUME_QUEUE + "/pause";
        String resumePath = "/api/v1/queues/" + setupId + "/" + PAUSE_RESUME_QUEUE + "/resume";

        createConsumerGroup(PAUSE_RESUME_QUEUE, activeGroup)
            .compose(v -> createConsumerGroup(PAUSE_RESUME_QUEUE, stoppedGroup))
            .compose(v -> createConsumerGroup(PAUSE_RESUME_QUEUE, cancelledGroup))
            .compose(v -> setupService.getSubscriptionServiceForSetup(setupId).cancel(PAUSE_RESUME_QUEUE, cancelledGroup))
            // Pause: the active group is paused, and the cancelled group is left as it is.
            .compose(v -> webClient.post(TEST_PORT, "localhost", pausePath).send())
            .compose(response -> {
                assertEquals(200, response.statusCode(),
                    "The queue pause must succeed when one subscription is cancelled. Response: "
                        + response.bodyAsString());
                assertEquals(2, response.bodyAsJsonObject().getInteger("pausedSubscriptions"),
                    "The two subscriptions that accept a pause must be counted. Response: "
                        + response.bodyAsString());
                return consumerStatuses(PAUSE_RESUME_QUEUE);
            })
            .compose(statuses -> {
                assertEquals("paused", statuses.get(activeGroup));
                assertEquals("paused", statuses.get(stoppedGroup));
                assertEquals("cancelled", statuses.get(cancelledGroup),
                    "The queue pause must leave a cancelled subscription cancelled");
                // One paused group is resumed and then stopped. The queue resume then meets a
                // PAUSED, a STOPPED, and a CANCELLED subscription.
                return setupService.getSubscriptionServiceForSetup(setupId).resume(PAUSE_RESUME_QUEUE, stoppedGroup);
            })
            .compose(v -> setupService.getSubscriptionServiceForSetup(setupId).stopGroup(PAUSE_RESUME_QUEUE, stoppedGroup))
            // Resume: only the paused group is resumed.
            .compose(v -> webClient.post(TEST_PORT, "localhost", resumePath).send())
            .compose(response -> {
                assertEquals(200, response.statusCode(),
                    "The queue resume must succeed when one subscription is stopped and one is cancelled. Response: "
                        + response.bodyAsString());
                assertEquals(1, response.bodyAsJsonObject().getInteger("resumedSubscriptions"),
                    "Only the paused subscription must be resumed. Response: " + response.bodyAsString());
                return consumerStatuses(PAUSE_RESUME_QUEUE);
            })
            .onSuccess(statuses -> testContext.verify(() -> {
                assertEquals("active", statuses.get(activeGroup));
                assertEquals("stopped", statuses.get(stoppedGroup),
                    "The queue resume must leave a stopped subscription stopped");
                assertEquals("cancelled", statuses.get(cancelledGroup),
                    "The queue resume must leave a cancelled subscription cancelled");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Future<Void> createSetupWithQueues() {
        JsonObject setupRequest = new JsonObject()
            .put("setupId", setupId)
            .put("databaseConfig", new JsonObject()
                .put("host",         postgres.getHost())
                .put("port",         postgres.getFirstMappedPort())
                .put("databaseName", "lifecycle_rest_db_" + System.currentTimeMillis())
                .put("username",     postgres.getUsername())
                .put("password",     postgres.getPassword())
                .put("schema", PostgreSQLTestConstants.TEST_SCHEMA)
                .put("templateDatabase", "template0")
                .put("encoding",     "UTF8"))
            .put("queues", new JsonArray()
                .add(new JsonObject()
                    .put("queueName",         SINGLE_GROUP_QUEUE)
                    .put("maxRetries",        3)
                    .put("visibilityTimeout", 30))
                .add(new JsonObject()
                    .put("queueName",         PAUSE_RESUME_QUEUE)
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

    private Future<Void> createConsumerGroup(String queueName, String groupName) {
        JsonObject groupRequest = new JsonObject()
            .put("name",      groupName)
            .put("setup",     setupId)
            .put("queueName", queueName);

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

    /** Reads the reported status of every consumer-group subscription of a queue, by group name. */
    private Future<Map<String, String>> consumerStatuses(String queueName) {
        return webClient.get(TEST_PORT, "localhost",
                "/api/v1/queues/" + setupId + "/" + queueName + "/consumers")
            .send()
            .compose((HttpResponse<Buffer> response) -> {
                if (response.statusCode() != 200) {
                    return Future.failedFuture("Failed to list consumers: "
                        + response.statusCode() + " " + response.bodyAsString());
                }
                Map<String, String> statuses = new HashMap<>();
                JsonArray consumers = response.bodyAsJsonObject().getJsonArray("consumers");
                for (int i = 0; i < consumers.size(); i++) {
                    JsonObject consumer = consumers.getJsonObject(i);
                    statuses.put(consumer.getString("groupName"), consumer.getString("status"));
                }
                return Future.succeededFuture(statuses);
            });
    }
}
