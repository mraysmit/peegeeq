package dev.mars.peegeeq.rest.handlers;

import dev.mars.peegeeq.api.database.DatabaseConfig;
import dev.mars.peegeeq.api.database.EventStoreConfig;
import dev.mars.peegeeq.api.database.QueueConfig;
import dev.mars.peegeeq.api.setup.DatabaseSetupRequest;
import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.rest.config.RestServerConfig;
import dev.mars.peegeeq.rest.PeeGeeQRestServer;
import dev.mars.peegeeq.runtime.PeeGeeQRuntime;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the non-destructive connect endpoint (Phase S.3):
 * - POST /api/v1/database-setup/connect
 *
 * <p>A separate provisioning service creates the database, schema and self-describing registry, then
 * closes non-destructively (the database persists). The REST server's own service then attaches to that
 * pre-provisioned database via the connect route, reconstituting the queues/event stores from the registry
 * — proving the route, the {@code RuntimeDatabaseSetupService} delegation, and reconstitution end-to-end.
 *
 * Classification: INTEGRATION TEST
 * - Uses real PostgreSQL database (TestContainers)
 * - Uses real Vert.x HTTP server
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class DatabaseSetupConnectIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseSetupConnectIntegrationTest.class);
    private static final int TEST_PORT = 18110;

    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createContainer(
            "peegeeq_connect_test", "peegeeq_test", "peegeeq_test");

    private PeeGeeQRestServer server;
    private String deploymentId;
    private WebClient webClient;

    private String provisionedSetupId;
    private String provisionedDbName;

    @BeforeAll
    void setupServer(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Setting up Database Setup Connect Integration Test ===");

        // Initialize the container's default schema (mirrors SetupManagementIntegrationTest).
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.OUTBOX);

        long ts = System.currentTimeMillis();
        provisionedSetupId = "connect-rest-" + ts;
        provisionedDbName = "connect_rest_db_" + ts;

        DatabaseConfig dbConfig = new DatabaseConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .databaseName(provisionedDbName)
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .templateDatabase("template0")
                .encoding("UTF8")
                .build();

        QueueConfig queue = new QueueConfig.Builder().queueName("connectqueue").maxRetries(3).build();
        EventStoreConfig store = new EventStoreConfig.Builder()
                .eventStoreName("connectstore").tableName("connectstore").build();

        DatabaseSetupRequest createReq = new DatabaseSetupRequest(
                provisionedSetupId, dbConfig, List.of(queue), List.of(store), Map.of());

        DatabaseSetupService serverSetupService = PeeGeeQRuntime.createDatabaseSetupService();

        RestServerConfig testConfig = new RestServerConfig(TEST_PORT,
                RestServerConfig.MonitoringConfig.defaults(), java.util.List.of("*"));
        server = new PeeGeeQRestServer(testConfig, serverSetupService);

        // Pre-provision the database/schema/registry with a SEPARATE service, so the server's own service
        // does not already hold the setup active (the duplicate-attach guard would refuse the connect).
        // The provisioning service is intentionally left running for the class lifetime — like the sibling
        // SetupManagementIntegrationTest, which never closes its setup service. Do NOT close it here: a
        // manager owns its Vert.x, so closeReactive() terminates that event loop, and any continuation
        // chained after close() is emitted on the dead loop and dropped (RejectedExecutionException),
        // hanging @BeforeAll. Provision, then deploy — nothing is chained onto a service close().
        DatabaseSetupService provisioningService = PeeGeeQRuntime.createDatabaseSetupService();
        provisioningService.createCompleteSetup(createReq)
                .compose(created -> {
                    logger.info("Provisioned setup '{}' in database '{}'", provisionedSetupId, provisionedDbName);
                    return vertx.deployVerticle(server);
                })
                .onSuccess(id -> {
                    deploymentId = id;
                    logger.info("REST server deployed with ID: {}", deploymentId);
                    webClient = WebClient.create(vertx);
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
    }

    @AfterAll
    void tearDown(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Tearing down Database Setup Connect Integration Test ===");

        // Mirror SetupManagementIntegrationTest: only undeploy the server verticle. The setup services are
        // NOT closed here — closing a service settles on its manager-owned Vert.x and would drop the
        // teardown continuation (the same hang as @BeforeAll); the sibling tolerates the service leak for
        // the test-JVM lifetime.
        Future<Void> undeploy = deploymentId != null
                ? vertx.undeploy(deploymentId)
                : Future.succeededFuture();

        undeploy
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @Test
    @Order(1)
    @DisplayName("Connect to existing setup - reconstitutes queues + event stores from the registry")
    void testConnectReconstitutesFromRegistry(VertxTestContext testContext) {
        JsonObject connectRequest = new JsonObject()
                .put("setupId", provisionedSetupId)
                .put("databaseConfig", new JsonObject()
                        .put("host", postgres.getHost())
                        .put("port", postgres.getFirstMappedPort())
                        .put("databaseName", provisionedDbName)
                        .put("username", postgres.getUsername())
                        .put("password", postgres.getPassword())
                        .put("schema", PostgreSQLTestConstants.TEST_SCHEMA)
                        .put("templateDatabase", "template0")
                        .put("encoding", "UTF8"))
                // queues/eventStores are ignored on connect (reconstituted from the schema).
                .put("queues", new JsonArray())
                .put("eventStores", new JsonArray());

        webClient.post(TEST_PORT, "localhost", "/api/v1/database-setup/connect")
                .putHeader("content-type", "application/json")
                .timeout(60000)
                .sendJsonObject(connectRequest)
                .onSuccess(response -> {
                    testContext.verify(() -> {
                        logger.info("Connect response: {} - {}", response.statusCode(), response.bodyAsString());
                        assertEquals(200, response.statusCode(),
                                "Expected 200 for connect, got: " + response.statusCode());
                        JsonObject body = response.bodyAsJsonObject();
                        assertNotNull(body, "Response should be a JSON object");
                        assertEquals(provisionedSetupId, body.getString("setupId"),
                                "setupId should be recovered from peegeeq_setup_metadata");
                        assertEquals("ACTIVE", body.getString("status"), "connected setup should be ACTIVE");
                        assertTrue(body.getInteger("queueCount") >= 1,
                                "queue should be reconstituted from the object registry");
                        assertTrue(body.getInteger("eventStoreCount") >= 1,
                                "event store should be reconstituted from the object registry");
                    });
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
    }

    @Test
    @Order(2)
    @DisplayName("Connect with an absent PeeGeeQ schema returns 400")
    void testConnectMissingSchemaReturns400(VertxTestContext testContext) {
        JsonObject connectRequest = new JsonObject()
                .put("setupId", "does-not-exist")
                .put("databaseConfig", new JsonObject()
                        .put("host", postgres.getHost())
                        .put("port", postgres.getFirstMappedPort())
                        // Reachable database, but a schema that holds no PeeGeeQ registry.
                        .put("databaseName", postgres.getDatabaseName())
                        .put("username", postgres.getUsername())
                        .put("password", postgres.getPassword())
                        .put("schema", "no_peegeeq_schema")
                        .put("templateDatabase", "template0")
                        .put("encoding", "UTF8"));

        webClient.post(TEST_PORT, "localhost", "/api/v1/database-setup/connect")
                .putHeader("content-type", "application/json")
                .timeout(60000)
                .sendJsonObject(connectRequest)
                .onSuccess(response -> {
                    testContext.verify(() -> {
                        logger.info("Connect (absent schema) response: {} - {}",
                                response.statusCode(), response.bodyAsString());
                        assertEquals(400, response.statusCode(),
                                "Expected 400 when the PeeGeeQ schema is absent, got: " + response.statusCode());
                    });
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
    }
}
