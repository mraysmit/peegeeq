package dev.mars.peegeeq.rest.handlers;

import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.rest.config.RestServerConfig;
import dev.mars.peegeeq.rest.PeeGeeQRestServer;
import dev.mars.peegeeq.runtime.PeeGeeQRuntime;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the queue-list SSE notification endpoint.
 *
 * Verifies GET /api/v1/sse/queues/{setupId}:
 * - Connection establishment and initial "connected" event
 * - "queue-changed" event emitted when a queue is created via management API
 * - "queue-changed" event emitted when a queue is deleted via management API
 * - Multiple simultaneous clients all receive the notification
 * - Connection cleanup on client disconnect
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SSEQueueUpdatesIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(SSEQueueUpdatesIntegrationTest.class);
    private static final int TEST_PORT = 18083;

    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private WebClient webClient;
    private HttpClient httpClient;
    private PeeGeeQRestServer server;
    private String deploymentId;
    private String testSetupId;

    @BeforeAll
    void setUpAll(Vertx vertx, VertxTestContext testContext) {
        testSetupId = "sse-updates-test-" + System.currentTimeMillis();

        DatabaseSetupService setupService = PeeGeeQRuntime.createDatabaseSetupService();
        RestServerConfig config = new RestServerConfig(TEST_PORT, RestServerConfig.MonitoringConfig.defaults(), java.util.List.of("*"));
        server = new PeeGeeQRestServer(config, setupService);

        vertx.deployVerticle(server)
            .onSuccess(id -> {
                deploymentId = id;
                httpClient = vertx.createHttpClient();
                webClient = WebClient.create(vertx);
                createDatabaseSetupViaRestApi(testContext);
            })
            .onFailure(testContext::failNow);
    }

    private void createDatabaseSetupViaRestApi(VertxTestContext testContext) {
        JsonObject setupRequest = new JsonObject()
            .put("setupId", testSetupId)
            .put("databaseConfig", new JsonObject()
                .put("host", postgres.getHost())
                .put("port", postgres.getFirstMappedPort())
                .put("databaseName", "sse_updates_db_" + System.currentTimeMillis())
                .put("username", postgres.getUsername())
                .put("password", postgres.getPassword())
                .put("schema", PostgreSQLTestConstants.TEST_SCHEMA)
                .put("templateDatabase", "template0")
                .put("encoding", "UTF8"))
            .put("queues", new JsonArray())
            .put("eventStores", new JsonArray());

        webClient.post(TEST_PORT, "localhost", "/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .timeout(60000)
            .sendJsonObject(setupRequest)
            .onSuccess(response -> {
                if (response.statusCode() == 201 || response.statusCode() == 200) {
                    testContext.completeNow();
                } else {
                    testContext.failNow(new Exception("Failed to create setup: " + response.bodyAsString()));
                }
            })
            .onFailure(testContext::failNow);
    }

    @AfterAll
    void tearDownAll(Vertx vertx, VertxTestContext testContext) {
        if (httpClient != null) httpClient.close();
        if (webClient != null) webClient.close();

        if (testSetupId != null && deploymentId != null) {
            WebClient client = WebClient.create(vertx);
            client.delete(TEST_PORT, "localhost", "/api/v1/database-setup/" + testSetupId)
                .timeout(30000)
                .send()
                .<Void>compose(response -> {
                    if (response.statusCode() != 200 && response.statusCode() != 204) {
                        logger.warn("Failed to cleanup test setup: {}, status: {}", testSetupId, response.statusCode());
                    }
                    return Future.succeededFuture();
                })
                .eventually(() -> { client.close(); return Future.succeededFuture(); })
                .compose(ignored -> vertx.undeploy(deploymentId))
                .onComplete(ar -> testContext.completeNow());
        } else if (deploymentId != null) {
            vertx.undeploy(deploymentId)
                .onComplete(ar -> testContext.completeNow());
        } else {
            testContext.completeNow();
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Future<Void> createQueueViaManagementApi(String queueName) {
        return webClient.post(TEST_PORT, "localhost", "/api/v1/management/queues")
            .putHeader("content-type", "application/json")
            .timeout(10000)
            .sendJsonObject(new JsonObject()
                .put("setupId", testSetupId)
                .put("name", queueName)
                .put("type", "native"))
            .compose(r -> r.statusCode() == 201
                ? Future.succeededFuture()
                : Future.failedFuture("Create queue failed: " + r.statusCode() + " " + r.bodyAsString()));
    }

    private Future<Void> deleteQueueViaManagementApi(String queueName) {
        return webClient.delete(TEST_PORT, "localhost",
                "/api/v1/management/queues/" + testSetupId + "/" + queueName)
            .timeout(10000)
            .send()
            .compose(r -> r.statusCode() == 200 || r.statusCode() == 204
                ? Future.succeededFuture()
                : Future.failedFuture("Delete queue failed: " + r.statusCode() + " " + r.bodyAsString()));
    }

    private String sseUrl() {
        return "/api/v1/sse/queues/" + testSetupId;
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    /**
     * Test 1: Connection establishment.
     * The endpoint must return 200 with SSE headers and emit a "connected" event
     * containing the correct setupId.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testConnectionEstablishment(VertxTestContext testContext) {
        httpClient.request(HttpMethod.GET, TEST_PORT, "localhost", sseUrl())
            .compose(HttpClientRequest::send)
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode());
                    assertEquals("text/event-stream", response.getHeader("Content-Type"));
                    assertEquals("no-cache", response.getHeader("Cache-Control"));
                });

                response.handler(buffer -> {
                    String data = buffer.toString();
                    if (data.contains("event: connected")) {
                        testContext.verify(() -> {
                            assertTrue(data.contains("\"setupId\":\"" + testSetupId + "\""),
                                "connected event must carry the correct setupId");
                            assertTrue(data.contains("\"connectionId\""),
                                "connected event must carry a connectionId");
                        });
                        response.request().connection().close();
                        testContext.completeNow();
                    }
                });
            })
            .onFailure(testContext::failNow);
    }

    /**
     * Test 2: Queue create notification.
     * Creating a queue via the management API must push a "queue-changed" event
     * with event=QUEUE_CREATED.  Mutation is triggered exactly when the SSE
     * "connected" handshake arrives — no timer-based readiness guard.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testQueueCreateTriggersNotification(VertxTestContext testContext) {
        String newQueue = "sse_notify_create_" + System.currentTimeMillis();
        AtomicBoolean mutationTriggered = new AtomicBoolean(false);

        httpClient.request(HttpMethod.GET, TEST_PORT, "localhost", sseUrl())
            .compose(HttpClientRequest::send)
            .onSuccess(response -> response.handler(buffer -> {
                String data = buffer.toString();

                if (data.contains("event: connected") && !mutationTriggered.getAndSet(true)) {
                    createQueueViaManagementApi(newQueue)
                        .onFailure(testContext::failNow);
                }

                if (data.contains("event: queue-changed") && data.contains("QUEUE_CREATED") && data.contains(newQueue)) {
                    testContext.verify(() -> {
                        assertTrue(data.contains("\"setupId\":\"" + testSetupId + "\""));
                        assertTrue(data.contains("\"queueName\":\"" + newQueue + "\""));
                    });
                    response.request().connection().close();
                    testContext.completeNow();
                }
            }))
            .onFailure(testContext::failNow);
    }

    /**
     * Test 3: Queue delete notification.
     * Deleting a queue must push a "queue-changed" event with event=QUEUE_DELETED.
     * The queue is created first (before SSE connects), then the SSE connection
     * triggers the delete on receiving the "connected" handshake.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testQueueDeleteTriggersNotification(VertxTestContext testContext) {
        String queueToDelete = "sse_notify_delete_" + System.currentTimeMillis();
        AtomicBoolean mutationTriggered = new AtomicBoolean(false);

        createQueueViaManagementApi(queueToDelete)
            .compose(v -> httpClient.request(HttpMethod.GET, TEST_PORT, "localhost", sseUrl()))
            .compose(HttpClientRequest::send)
            .onSuccess(response -> response.handler(buffer -> {
                String data = buffer.toString();

                if (data.contains("event: connected") && !mutationTriggered.getAndSet(true)) {
                    deleteQueueViaManagementApi(queueToDelete)
                        .onFailure(testContext::failNow);
                }

                if (data.contains("event: queue-changed") && data.contains("QUEUE_DELETED") && data.contains(queueToDelete)) {
                    testContext.verify(() -> {
                        assertTrue(data.contains("\"setupId\":\"" + testSetupId + "\""));
                        assertTrue(data.contains("\"queueName\":\"" + queueToDelete + "\""));
                    });
                    response.request().connection().close();
                    testContext.completeNow();
                }
            }))
            .onFailure(testContext::failNow);
    }

    /**
     * Test 4: Multiple clients receive the notification.
     * Two simultaneous SSE clients must both receive the same QUEUE_CREATED event.
     * Mutation fires only after both clients have received the "connected" handshake.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testMultipleClientsReceiveNotification(VertxTestContext testContext) {
        String newQueue = "sse_multi_client_" + System.currentTimeMillis();
        AtomicInteger connectedCount = new AtomicInteger(0);
        AtomicBoolean mutationTriggered = new AtomicBoolean(false);
        Checkpoint notified = testContext.checkpoint(2);

        for (int i = 0; i < 2; i++) {
            httpClient.request(HttpMethod.GET, TEST_PORT, "localhost", sseUrl())
                .compose(HttpClientRequest::send)
                .onSuccess(response -> response.handler(buffer -> {
                    String data = buffer.toString();

                    if (data.contains("event: connected")) {
                        if (connectedCount.incrementAndGet() == 2 && !mutationTriggered.getAndSet(true)) {
                            createQueueViaManagementApi(newQueue)
                                .onFailure(testContext::failNow);
                        }
                    }

                    if (data.contains("QUEUE_CREATED") && data.contains(newQueue)) {
                        notified.flag();
                    }
                }))
                .onFailure(testContext::failNow);
        }
    }

    /**
     * Test 5: Connection cleanup on client disconnect.
     * Closing the client connection must not cause errors and subsequent
     * mutations must succeed even when the bus consumer for that connection
     * has been unregistered.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testConnectionCleanupOnDisconnect(VertxTestContext testContext) {
        String newQueue = "sse_cleanup_" + System.currentTimeMillis();

        httpClient.request(HttpMethod.GET, TEST_PORT, "localhost", sseUrl())
            .compose(HttpClientRequest::send)
            .onSuccess(response -> {
                // Trigger disconnect as soon as the connection is confirmed
                response.handler(buffer -> {
                    if (buffer.toString().contains("event: connected")) {
                        response.request().connection().close();
                    }
                });

                // After server detects the close, fire a mutation; must not throw
                response.request().connection().closeHandler(v ->
                    createQueueViaManagementApi(newQueue)
                        .onSuccess(ignored -> testContext.completeNow())
                        .onFailure(testContext::failNow));
            })
            .onFailure(testContext::failNow);
    }
}
