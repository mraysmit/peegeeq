package dev.mars.peegeeq.rest.handlers;

import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.rest.PeeGeeQRestServer;
import dev.mars.peegeeq.rest.config.RestServerConfig;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the database-level telemetry endpoint (`DatabaseTelemetryHandler`),
 * telemetry requirements §4A / gap G7. Exercises the wired route end-to-end against a real
 * PostgreSQL (TestContainers) and a deployed {@link PeeGeeQRestServer}:
 *
 * <ul>
 *   <li>GET /api/v1/setups/:setupId/db-telemetry — per-table churn/vacuum/scan/size stats
 *       plus cluster signals for the setup's database</li>
 * </ul>
 *
 * Contracts pinned here: the snapshot is scoped to the setup's schema and contains the
 * churn-bearing queue tables; sizes are real reads (a provisioned table with indexes has
 * a positive total size); vacuum timestamps follow the absence-not-zero contract; the
 * cluster block carries the §4A signal set with {@code numbackends} counting at least the
 * endpoint's own querying connection; churn counters reflect real inserts (polled, because
 * PostgreSQL flushes backend stats asynchronously); an unknown setup returns 404.
 *
 * Classification: INTEGRATION TEST — real PostgreSQL (TestContainers) + real Vert.x HTTP server.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class DatabaseTelemetryHandlerIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseTelemetryHandlerIntegrationTest.class);
    private static final int TEST_PORT = 18117;
    private static final String QUEUE_NAME = "telemetry_test_queue";
    private static final int CHURN_MESSAGE_COUNT = 5;
    private static final long CHURN_POLL_DEADLINE_MS = 15_000;

    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQRestServer server;
    private String deploymentId;
    private String setupId;
    private WebClient webClient;

    @BeforeAll
    void setupServer(Vertx vertx, VertxTestContext testContext) {
        setupId = "db-telemetry-test-" + System.currentTimeMillis();

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
                .put("databaseName", "db_telemetry_db_" + System.currentTimeMillis())
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
                    logger.info("Setup created: {}", setupId);
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
    @DisplayName("db-telemetry - snapshot is scoped to the setup schema and carries the queue tables with real stats")
    void testSnapshotContainsQueueTables(VertxTestContext testContext) {
        fetchSnapshot()
            .onSuccess(snapshot -> testContext.verify(() -> {
                logger.info("Telemetry snapshot: {}", snapshot.encode());

                assertEquals(setupId, snapshot.getString("setupId"), "snapshot must name the setup");
                assertEquals(PostgreSQLTestConstants.TEST_SCHEMA, snapshot.getString("schema"),
                    "snapshot must be scoped to the setup's schema");
                assertNotNull(snapshot.getString("databaseName"), "snapshot must name the database");
                assertNotNull(snapshot.getLong("sampledAt"), "snapshot must carry the sample time");

                JsonArray tables = snapshot.getJsonArray("tables");
                assertNotNull(tables, "snapshot must carry a tables array");

                // The churn-bearing tables named in telemetry §4A must all be present.
                for (String required : new String[]{"queue_messages", "outbox", "dead_letter_queue", QUEUE_NAME}) {
                    JsonObject table = findTable(tables, required);
                    assertNotNull(table, "tables must include '" + required + "'; got: " + tables.encode());

                    // Full cumulative-counter contract per row.
                    for (String field : new String[]{"nTupIns", "nTupUpd", "nTupDel", "nTupHotUpd",
                            "nLiveTup", "nDeadTup", "seqScan", "vacuumCount", "autovacuumCount",
                            "heapBlksHit", "heapBlksRead", "heapBytes", "indexBytes", "totalBytes"}) {
                        assertNotNull(table.getLong(field),
                            "table '" + required + "' must carry '" + field + "'; row: " + table.encode());
                    }

                    // Sizes are real reads, not defaults: every provisioned queue table has
                    // indexes, so its total relation size is positive even when empty.
                    assertTrue(table.getLong("totalBytes") > 0,
                        "table '" + required + "' must report a positive totalBytes; row: " + table.encode());

                    // Absence-not-zero contract: a table that was never manually vacuumed
                    // (vacuumCount == 0) must OMIT lastVacuum, not report a zeroed stamp.
                    if (table.getLong("vacuumCount") == 0L) {
                        assertFalse(table.containsKey("lastVacuum"),
                            "table '" + required + "' with vacuumCount=0 must omit lastVacuum; row: " + table.encode());
                    }
                }
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("db-telemetry - cluster block carries the §4A signal set including the querying backend")
    void testClusterSignalsPresent(VertxTestContext testContext) {
        fetchSnapshot()
            .onSuccess(snapshot -> testContext.verify(() -> {
                JsonObject cluster = snapshot.getJsonObject("cluster");
                assertNotNull(cluster, "snapshot must carry a cluster block; got: " + snapshot.encode());
                logger.info("Cluster block: {}", cluster.encode());

                for (String field : new String[]{"backendsHoldingXmin", "locksTotal", "locksWaiting",
                        "xidAge", "walRecords", "walBytes", "walLsnBytes", "checkpointsTimed",
                        "checkpointsRequested", "buffersCheckpoint", "xactCommit", "xactRollback",
                        "deadlocks", "tupReturned", "tupFetched", "numbackends", "blksHit", "blksRead"}) {
                    assertNotNull(cluster.getValue(field),
                        "cluster block must carry '" + field + "'; block: " + cluster.encode());
                }

                // The endpoint's own querying connection is connected to the setup database at
                // sample time, so the database must report at least one backend.
                assertTrue(cluster.getInteger("numbackends") >= 1,
                    "numbackends must count at least the querying connection; block: " + cluster.encode());

                // Telemetry G3 (T.4, moved here 2026-08-09): NOTIFY queue usage belongs with the
                // other cluster signals PostgreSQL itself maintains — the database is the
                // producer, this endpoint is a collector. The native queue signals consumers
                // with NOTIFY; when that fixed-size instance-wide buffer fills, NOTIFY blocks
                // committing transactions — write latency with no cause visible in any
                // app-level counter. pg_notification_queue_usage() reports the fraction in
                // use, so the value is always present and always 0..1.
                Double notifyUsage = cluster.getDouble("notifyQueueUsage");
                assertNotNull(notifyUsage,
                    "cluster block must carry notifyQueueUsage (telemetry G3); block: " + cluster.encode());
                assertTrue(notifyUsage >= 0.0 && notifyUsage <= 1.0,
                    "notifyQueueUsage is a fraction of the NOTIFY queue and must be 0..1: " + notifyUsage);

                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("db-telemetry - churn counters reflect real inserts on the native queue table")
    void testChurnCountersReflectInserts(Vertx vertx, VertxTestContext testContext) {
        sendMessages(CHURN_MESSAGE_COUNT)
            .compose(v -> pollForTableInserts(vertx, "queue_messages", CHURN_MESSAGE_COUNT,
                System.currentTimeMillis() + CHURN_POLL_DEADLINE_MS))
            .onSuccess(table -> testContext.verify(() -> {
                logger.info("queue_messages after {} sends: {}", CHURN_MESSAGE_COUNT, table.encode());
                assertTrue(table.getLong("nTupIns") >= CHURN_MESSAGE_COUNT,
                    "queue_messages nTupIns must reflect the " + CHURN_MESSAGE_COUNT + " inserts");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("db-telemetry - unknown setup returns 404")
    void testUnknownSetupReturns404(VertxTestContext testContext) {
        webClient.get(TEST_PORT, "localhost", "/api/v1/setups/nonexistent-setup-xyz/db-telemetry")
            .send()
            .onSuccess(response -> testContext.verify(() -> {
                logger.info("Unknown-setup response: {} - {}",
                    response.statusCode(), response.bodyAsString());
                assertEquals(404, response.statusCode(),
                    "An unknown setup must return 404 (setupNotFound)");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    /** Fetches one telemetry snapshot, failing the Future on any non-200 status. */
    private Future<JsonObject> fetchSnapshot() {
        return webClient.get(TEST_PORT, "localhost", "/api/v1/setups/" + setupId + "/db-telemetry")
            .send()
            .compose(response -> {
                if (response.statusCode() != 200) {
                    return Future.failedFuture("db-telemetry returned " + response.statusCode()
                        + " - " + response.bodyAsString());
                }
                return Future.succeededFuture(response.bodyAsJsonObject());
            });
    }

    /** Sends {@code count} messages to the test queue as a sequential compose chain. */
    private Future<Void> sendMessages(int count) {
        Future<Void> chain = Future.succeededFuture();
        for (int i = 0; i < count; i++) {
            final int seq = i;
            chain = chain.compose(v -> webClient.post(TEST_PORT, "localhost",
                    "/api/v1/queues/" + setupId + "/" + QUEUE_NAME + "/messages")
                .putHeader("content-type", "application/json")
                .sendJsonObject(new JsonObject()
                    .put("payload", new JsonObject().put("probe", "t7-churn").put("seq", seq)))
                .compose(response -> {
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        return Future.succeededFuture();
                    }
                    return Future.failedFuture("send #" + seq + " failed: " + response.statusCode()
                        + " - " + response.bodyAsString());
                }));
        }
        return chain;
    }

    /**
     * Polls the telemetry endpoint via a recursive {@code vertx.timer} chain until the named
     * table reports {@code nTupIns >= minInserts}, failing at the deadline. PostgreSQL flushes
     * backend stats asynchronously (up to ~1 s after commit), so a single read after the sends
     * would race the stats collector.
     */
    private Future<JsonObject> pollForTableInserts(Vertx vertx, String tableName,
                                                   long minInserts, long deadline) {
        return fetchSnapshot()
            .compose(snapshot -> {
                JsonObject table = findTable(snapshot.getJsonArray("tables"), tableName);
                if (table != null && table.getLong("nTupIns", 0L) >= minInserts) {
                    return Future.succeededFuture(table);
                }
                if (System.currentTimeMillis() >= deadline) {
                    return Future.failedFuture(new AssertionError(
                        "Table '" + tableName + "' did not report nTupIns >= " + minInserts
                            + " before the deadline; last snapshot: " + snapshot.encode()));
                }
                return vertx.timer(500).compose(t ->
                    pollForTableInserts(vertx, tableName, minInserts, deadline));
            });
    }

    private static JsonObject findTable(JsonArray tables, String tableName) {
        if (tables == null) {
            return null;
        }
        for (int i = 0; i < tables.size(); i++) {
            JsonObject table = tables.getJsonObject(i);
            if (tableName.equals(table.getString("tableName"))) {
                return table;
            }
        }
        return null;
    }
}
