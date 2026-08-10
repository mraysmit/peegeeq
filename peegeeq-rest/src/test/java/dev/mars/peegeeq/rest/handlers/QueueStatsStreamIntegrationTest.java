package dev.mars.peegeeq.rest.handlers;

import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.rest.PeeGeeQRestServer;
import dev.mars.peegeeq.rest.config.RestServerConfig;
import dev.mars.peegeeq.runtime.PeeGeeQRuntime;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the fast per-queue stats stream (telemetry G4 — metrics-stack
 * remediation step 6): {@code GET /api/v1/queues/{setupId}/{queueName}/stats/stream}.
 *
 * <p>Contracts pinned here:
 * <ul>
 *   <li>the stream emits {@code stats} frames at at least the requested cadence — the defect
 *       class this exists to prevent is the /sse/metrics one, where a period bug halved the
 *       delivered rate;</li>
 *   <li>each frame carries the SAME flat shape as {@code GET .../stats} (one shared builder,
 *       {@code QueueHandler.queueStatsJson}) — utilities-ui parses these exact field names;</li>
 *   <li>frames are FRESH per-tick reads, not a cached snapshot: messages sent mid-stream
 *       appear in a later frame's {@code pendingMessages};</li>
 *   <li>an unknown queue or setup answers 404 rather than an empty stream.</li>
 * </ul>
 *
 * Classification: INTEGRATION TEST — real PostgreSQL (TestContainers) + deployed REST server.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class QueueStatsStreamIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(QueueStatsStreamIntegrationTest.class);
    private static final int TEST_PORT = 18119;
    private static final String QUEUE_NAME = "stats_stream_queue";

    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQRestServer server;
    private String deploymentId;
    private String setupId;
    private WebClient webClient;

    @BeforeAll
    void setupServer(Vertx vertx, VertxTestContext testContext) {
        setupId = "stats-stream-test-" + System.currentTimeMillis();

        DatabaseSetupService setupService = PeeGeeQRuntime.createDatabaseSetupService();
        RestServerConfig testConfig = new RestServerConfig(
            TEST_PORT, RestServerConfig.MonitoringConfig.defaults(), java.util.List.of("*"));
        server = new PeeGeeQRestServer(testConfig, setupService);
        vertx.deployVerticle(server)
            .compose(id -> {
                deploymentId = id;
                webClient = WebClient.create(vertx);
                return createSetupWithQueue();
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    private Future<Void> createSetupWithQueue() {
        JsonObject setupRequest = new JsonObject()
            .put("setupId", setupId)
            .put("databaseConfig", new JsonObject()
                .put("host", postgres.getHost())
                .put("port", postgres.getFirstMappedPort())
                .put("databaseName", "stats_stream_db_" + System.currentTimeMillis())
                .put("username", postgres.getUsername())
                .put("password", postgres.getPassword())
                .put("schema", PostgreSQLTestConstants.TEST_SCHEMA)
                .put("templateDatabase", "template0")
                .put("encoding", "UTF8"))
            .put("queues", new JsonArray()
                .add(new JsonObject()
                    .put("queueName", QUEUE_NAME)
                    .put("implementationType", "native")
                    .put("maxRetries", 3)
                    .put("visibilityTimeoutSeconds", 30)))
            .put("eventStores", new JsonArray())
            .put("additionalProperties", new JsonObject());

        return webClient.post(TEST_PORT, "localhost", "/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .timeout(60000)
            .sendJsonObject(setupRequest)
            .compose(response -> {
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return Future.succeededFuture();
                }
                return Future.failedFuture("Failed to create setup: " + response.statusCode()
                    + " - " + response.bodyAsString());
            });
    }

    @AfterAll
    void tearDown(Vertx vertx, VertxTestContext testContext) {
        Future<Void> undeploy = deploymentId != null
            ? vertx.undeploy(deploymentId)
            : Future.succeededFuture();
        undeploy.onSuccess(v -> testContext.completeNow()).onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("stats stream - delivers /stats-shaped frames at at least the requested cadence")
    void testStreamDeliversFramesAtRequestedCadence(Vertx vertx, VertxTestContext testContext) {
        List<JsonObject> frames = new ArrayList<>();
        HttpClient client = vertx.createHttpClient();

        openStream(client, "/api/v1/queues/" + setupId + "/" + QUEUE_NAME
                + "/stats/stream?intervalMs=250", frame -> frames.add(frame), testContext);

        // 3 s at 250 ms plus the immediate first sample is ~13 frames; >= 8 leaves slack for a
        // loaded box while still failing hard on the halved-rate defect class this pins.
        vertx.timer(3000).onSuccess(t -> testContext.verify(() -> {
            logger.info("Received {} stats frames in 3 s at 250 ms", frames.size());
            assertTrue(frames.size() >= 8,
                "expected >= 8 stats frames in 3 s at 250 ms; got " + frames.size()
                    + " — the stream is not honouring the requested cadence");

            JsonObject first = frames.get(0);
            // The /stats shape, by construction (QueueHandler.queueStatsJson).
            assertEquals(QUEUE_NAME, first.getString("queueName"));
            assertEquals(setupId, first.getString("setupId"));
            assertEquals("native", first.getString("implementationType"));
            assertNotNull(first.getLong("pendingMessages"), "frame must carry pendingMessages");
            assertNotNull(first.getLong("totalMessages"), "frame must carry totalMessages");
            assertNotNull(first.getBoolean("healthy"), "frame must carry healthy");
            assertNotNull(first.getLong("timestamp"), "frame must carry timestamp");

            client.close();
            testContext.completeNow();
        }));
    }

    @Test
    @DisplayName("stats stream - frames are fresh per-tick reads: mid-stream sends appear in later frames")
    void testFramesAreFreshReads(Vertx vertx, VertxTestContext testContext) {
        List<JsonObject> frames = new ArrayList<>();
        HttpClient client = vertx.createHttpClient();

        openStream(client, "/api/v1/queues/" + setupId + "/" + QUEUE_NAME
                + "/stats/stream?intervalMs=250", frame -> {
            frames.add(frame);
            if (frames.size() == 1) {
                // After the first frame: enqueue 3 messages. Nothing consumes this queue, so
                // they stay pending and MUST surface in a later frame — a cached or frozen
                // stream never shows them.
                sendMessages(3);
            }
            long baseline = frames.get(0).getLong("pendingMessages", 0L);
            if (frames.size() > 1 && frame.getLong("pendingMessages", 0L) >= baseline + 3) {
                testContext.verify(() -> {
                    logger.info("Frame {} reflects the mid-stream sends (pending {} -> {})",
                        frames.size(), baseline, frame.getLong("pendingMessages"));
                    client.close();
                    testContext.completeNow();
                });
            }
        }, testContext);
        // No explicit timer: if no frame ever reflects the sends, the VertxTestContext
        // timeout bounds the test and fails it — frozen frames are exactly the defect.
    }

    @Test
    @DisplayName("stats stream - unknown queue answers 404, not an empty stream")
    void testUnknownQueueAnswers404(Vertx vertx, VertxTestContext testContext) {
        HttpClient client = vertx.createHttpClient();
        client.request(HttpMethod.GET, TEST_PORT, "localhost",
                "/api/v1/queues/" + setupId + "/no_such_queue/stats/stream")
            .compose(req -> req.send())
            .onSuccess(resp -> testContext.verify(() -> {
                assertEquals(404, resp.statusCode(),
                    "an unknown queue must be a stated 404, not a silent empty stream");
                client.close();
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("stats stream - unknown setup answers 404")
    void testUnknownSetupAnswers404(Vertx vertx, VertxTestContext testContext) {
        HttpClient client = vertx.createHttpClient();
        client.request(HttpMethod.GET, TEST_PORT, "localhost",
                "/api/v1/queues/no-such-setup/" + QUEUE_NAME + "/stats/stream")
            .compose(req -> req.send())
            .onSuccess(resp -> testContext.verify(() -> {
                assertEquals(404, resp.statusCode());
                client.close();
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    /** Opens the SSE stream and invokes {@code onStats} for every parsed {@code stats} event. */
    private void openStream(HttpClient client, String path, Consumer<JsonObject> onStats,
            VertxTestContext testContext) {
        client.request(HttpMethod.GET, TEST_PORT, "localhost", path)
            .compose(req -> req.send())
            .onSuccess(resp -> {
                if (resp.statusCode() != 200) {
                    testContext.failNow(new AssertionError(
                        "stream request answered " + resp.statusCode()));
                    return;
                }
                StringBuilder buffer = new StringBuilder();
                resp.handler(chunk -> {
                    buffer.append(chunk.toString());
                    int idx;
                    while ((idx = buffer.indexOf("\n\n")) != -1) {
                        String rawEvent = buffer.substring(0, idx);
                        buffer.delete(0, idx + 2);
                        String event = null;
                        String data = null;
                        for (String line : rawEvent.split("\n")) {
                            if (line.startsWith("event: ")) {
                                event = line.substring(7).trim();
                            } else if (line.startsWith("data: ")) {
                                data = line.substring(6).trim();
                            }
                        }
                        if ("stats".equals(event) && data != null) {
                            onStats.accept(new JsonObject(data));
                        } else if ("error".equals(event)) {
                            testContext.failNow(new AssertionError(
                                "stream reported an error frame: " + data));
                        }
                    }
                });
            })
            .onFailure(testContext::failNow);
    }

    /** Fire-and-observe batch of sends; a failure fails the test rather than being lost. */
    private void sendMessages(int count) {
        for (int i = 0; i < count; i++) {
            webClient.post(TEST_PORT, "localhost",
                    "/api/v1/queues/" + setupId + "/" + QUEUE_NAME + "/messages")
                .putHeader("content-type", "application/json")
                .sendJsonObject(new JsonObject()
                    .put("payload", new JsonObject().put("probe", "g4-stream").put("seq", i)))
                .onFailure(err -> logger.error("Mid-stream send failed", err));
        }
    }
}
