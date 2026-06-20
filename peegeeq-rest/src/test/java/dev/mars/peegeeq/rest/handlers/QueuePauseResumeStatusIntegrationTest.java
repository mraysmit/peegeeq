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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test verifying that {@code GET /api/v1/queues/{setupId}/{queueName}}
 * returns the correct {@code status} field after pause and resume operations.
 *
 * <p>The fix in {@code ManagementApiHandler.getQueueDetails} derives the queue status
 * from subscription states rather than only from the health check flag.  A queue whose
 * subscriptions are all {@code PAUSED} must report {@code "paused"}; after resume it
 * must report {@code "active"} again.
 *
 * <p>A consumer-group subscription is created during setup so that calling
 * {@code /pause} actually transitions a subscription to the {@code PAUSED} state —
 * without at least one subscription, {@code allMatch(PAUSED)} on an empty list would
 * return {@code true} vacuously but the guard {@code !subs.isEmpty()} prevents it.
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
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class QueuePauseResumeStatusIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(QueuePauseResumeStatusIntegrationTest.class);
    private static final int TEST_PORT = 18102;
    private static final String QUEUE_NAME = "status_test_queue";

    @Container
    static PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE);
        container.withDatabaseName("peegeeq_pause_status_test");
        container.withUsername("peegeeq_test");
        container.withPassword("peegeeq_test");
        container.withSharedMemorySize(PostgreSQLTestConstants.DEFAULT_SHARED_MEMORY_SIZE);
        container.withReuse(false);
        return container;
    }

    private String deploymentId;
    private String setupId;
    private String groupName;
    private WebClient webClient;

    // ── Setup / Teardown ──────────────────────────────────────────────────────

    @BeforeAll
    void setupServer(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Setting up Queue Pause/Resume Status Integration Test ===");

        setupId   = "pause-status-test-" + System.currentTimeMillis();
        groupName = "status_test_group_"  + System.currentTimeMillis();

        DatabaseSetupService setupService = PeeGeeQRuntime.createDatabaseSetupService();

        RestServerConfig testConfig = new RestServerConfig(
            TEST_PORT,
            RestServerConfig.MonitoringConfig.defaults(),
            java.util.List.of("*")
        );
        PeeGeeQRestServer restServer = new PeeGeeQRestServer(testConfig, setupService);

        vertx.deployVerticle(restServer)
            .compose(id -> {
                deploymentId = id;
                logger.info("REST server deployed: {}", deploymentId);
                webClient = WebClient.create(vertx);
                return createSetupWithQueue();
            })
            .compose(v -> createConsumerGroupSubscription())
            .onSuccess(v -> {
                logger.info("Test setup complete — setupId={}, queue={}, group={}",
                    setupId, QUEUE_NAME, groupName);
                testContext.completeNow();
            })
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
                .put("databaseName", "pause_status_db_" + System.currentTimeMillis())
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
                    logger.info("Setup created: {}", setupId);
                    return Future.succeededFuture();
                }
                return Future.failedFuture("Failed to create setup: "
                    + response.statusCode() + " " + response.bodyAsString());
            });
    }

    /**
     * Create a consumer-group subscription on the test queue.
     *
     * <p>This is essential: the backend fix only sets {@code status = "paused"} when
     * at least one subscription exists AND all of them are in the {@code PAUSED} state.
     * Without a subscription the queue would always appear {@code "active"}.
     */
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
                    logger.info("Consumer group subscription created: {}", groupName);
                    return Future.succeededFuture();
                }
                return Future.failedFuture("Failed to create consumer group: "
                    + response.statusCode() + " " + response.bodyAsString());
            });
    }

    private Future<String> getQueueStatus() {
        return webClient.get(TEST_PORT, "localhost",
                "/api/v1/queues/" + setupId + "/" + QUEUE_NAME)
            .send()
            .compose(response -> {
                if (response.statusCode() == 200) {
                    return Future.succeededFuture(response.bodyAsJsonObject().getString("status"));
                }
                return Future.failedFuture("getQueueDetails failed: "
                    + response.statusCode() + " " + response.bodyAsString());
            });
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("01 getQueueDetails returns status=active before any pause")
    void test01_InitialStatusIsActive(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Test 01: Initial status should be active ===");

        getQueueStatus()
            .onSuccess(status -> testContext.verify(() -> {
                logger.info("Initial queue status: {}", status);
                assertEquals("active", status,
                    "Queue should report 'active' before any pause");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(2)
    @DisplayName("02 Calling /pause transitions status to paused")
    void test02_PauseTransitionsStatusToPaused(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Test 02: Pause queue and verify status=paused ===");

        webClient.post(TEST_PORT, "localhost",
                "/api/v1/queues/" + setupId + "/" + QUEUE_NAME + "/pause")
            .send()
            .compose(pauseResponse -> {
                testContext.verify(() -> {
                    assertEquals(200, pauseResponse.statusCode(),
                        "Pause endpoint should return 200 OK");

                    JsonObject body = pauseResponse.bodyAsJsonObject();
                    assertNotNull(body.getString("message"), "Pause response should have a message field");

                    int pausedCount = body.getInteger("pausedSubscriptions");
                    logger.info("Pause response: {} subscription(s) paused", pausedCount);
                    assertEquals(1, pausedCount,
                        "Exactly one consumer-group subscription should have been paused");
                });
                return getQueueStatus();
            })
            .onSuccess(status -> testContext.verify(() -> {
                logger.info("Queue status after pause: {}", status);
                assertEquals("paused", status,
                    "getQueueDetails should return 'paused' after calling /pause");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(3)
    @DisplayName("03 Calling /resume transitions status back to active")
    void test03_ResumeTransitionsStatusBackToActive(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Test 03: Resume queue and verify status=active ===");

        webClient.post(TEST_PORT, "localhost",
                "/api/v1/queues/" + setupId + "/" + QUEUE_NAME + "/resume")
            .send()
            .compose(resumeResponse -> {
                testContext.verify(() -> {
                    assertEquals(200, resumeResponse.statusCode(),
                        "Resume endpoint should return 200 OK");

                    JsonObject body = resumeResponse.bodyAsJsonObject();
                    assertNotNull(body.getString("message"), "Resume response should have a message field");

                    int resumedCount = body.getInteger("resumedSubscriptions");
                    logger.info("Resume response: {} subscription(s) resumed", resumedCount);
                    assertEquals(1, resumedCount,
                        "Exactly one consumer-group subscription should have been resumed");
                });
                return getQueueStatus();
            })
            .onSuccess(status -> testContext.verify(() -> {
                logger.info("Queue status after resume: {}", status);
                assertEquals("active", status,
                    "getQueueDetails should return 'active' after calling /resume");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(4)
    @DisplayName("04 Full pause-resume cycle: active → paused → active")
    void test04_FullPauseResumeCycle(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Test 04: Full pause-resume lifecycle ===");

        // Confirm currently active (post-test-03 state)
        getQueueStatus()
            .compose(initialStatus -> {
                testContext.verify(() ->
                    assertEquals("active", initialStatus, "Pre-condition: status should be active"));

                // Pause
                return webClient.post(TEST_PORT, "localhost",
                        "/api/v1/queues/" + setupId + "/" + QUEUE_NAME + "/pause")
                    .send();
            })
            .compose(pauseResponse -> {
                testContext.verify(() ->
                    assertEquals(200, pauseResponse.statusCode(), "Pause should return 200"));
                return getQueueStatus();
            })
            .compose(pausedStatus -> {
                testContext.verify(() ->
                    assertEquals("paused", pausedStatus, "Status should be 'paused' after /pause"));
                logger.info("Confirmed paused → resuming now");

                // Resume
                return webClient.post(TEST_PORT, "localhost",
                        "/api/v1/queues/" + setupId + "/" + QUEUE_NAME + "/resume")
                    .send();
            })
            .compose(resumeResponse -> {
                testContext.verify(() ->
                    assertEquals(200, resumeResponse.statusCode(), "Resume should return 200"));
                return getQueueStatus();
            })
            .onSuccess(finalStatus -> testContext.verify(() -> {
                assertEquals("active", finalStatus, "Status should be 'active' after /resume");
                logger.info("Full pause-resume cycle verified: active → paused → active");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }
}
