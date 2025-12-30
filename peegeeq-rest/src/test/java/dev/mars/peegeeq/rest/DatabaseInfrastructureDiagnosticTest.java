package dev.mars.peegeeq.rest;

import dev.mars.peegeeq.api.setup.DatabaseSetupService;
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
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Diagnostic test to verify database infrastructure is created correctly by DatabaseSetupService.
 * This test connects directly to the created databases and checks what tables actually exist.
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatabaseInfrastructureDiagnosticTest {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseInfrastructureDiagnosticTest.class);
    private static final int TEST_PORT = 18094;

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
            .withDatabaseName("diagnostic_test")
            .withUsername("test_user")
            .withPassword("test_pass");

    private String deploymentId;
    private WebClient client;
    private String dbName;
    private String schema;

    @BeforeAll
    void setUp(Vertx vertx, VertxTestContext testContext) {
        logger.info("========== DIAGNOSTIC SETUP STARTING ==========");

        schema = "test_schema";
        dbName = "test_db_" + System.currentTimeMillis();

        client = WebClient.create(vertx);
        DatabaseSetupService setupService = PeeGeeQRuntime.createDatabaseSetupService();

        RestServerConfig testConfig = new RestServerConfig(TEST_PORT, RestServerConfig.MonitoringConfig.defaults());
        vertx.deployVerticle(new PeeGeeQRestServer(testConfig, setupService))
            .compose(id -> {
                deploymentId = id;
                logger.info("Deployed REST server on port {}", TEST_PORT);

                // Create a simple setup with one queue
                JsonObject setupRequest = new JsonObject()
                        .put("setupId", "diagnostic-setup")
                        .put("databaseConfig", new JsonObject()
                                .put("host", postgres.getHost())
                                .put("port", postgres.getFirstMappedPort())
                                .put("databaseName", dbName)
                                .put("username", postgres.getUsername())
                                .put("password", postgres.getPassword())
                                .put("schema", schema)
                                .put("templateDatabase", "template0")
                                .put("encoding", "UTF8"))
                        .put("queues", new JsonArray()
                                .add(new JsonObject().put("queueName", "test_queue")))
                        .put("eventStores", new JsonArray());

                return client.post(TEST_PORT, "localhost", "/api/v1/setups")
                        .timeout(30000)
                        .sendJsonObject(setupRequest);
            })
            .onSuccess(response -> {
                logger.info("Setup created with status: {}", response.statusCode());
                logger.info("Response body: {}", response.bodyAsString());
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    void testVerifyDatabaseInfrastructure(Vertx vertx, VertxTestContext testContext) {
        logger.info("========== DIAGNOSTIC TEST: Verify Database Infrastructure ==========");

        // Connect directly to the created database
        PgConnectOptions connectOptions = new PgConnectOptions()
                .setHost(postgres.getHost())
                .setPort(postgres.getFirstMappedPort())
                .setDatabase(dbName)
                .setUser(postgres.getUsername())
                .setPassword(postgres.getPassword());

        Pool pool = PgBuilder.pool()
                .with(new PoolOptions().setMaxSize(1))
                .connectingTo(connectOptions)
                .using(vertx)
                .build();

        pool.withConnection(conn -> {
            // Check what schemas exist
            return conn.query("SELECT schema_name FROM information_schema.schemata ORDER BY schema_name").execute()
                    .compose(rowSet -> {
                        logger.info("========== SCHEMAS IN DATABASE: {} ==========", dbName);
                        rowSet.forEach(row -> logger.info("  - {}", row.getString("schema_name")));

                        // Check what tables exist in our schema
                        String checkTablesSQL = String.format(
                                "SELECT table_name FROM information_schema.tables WHERE table_schema = '%s' ORDER BY table_name",
                                schema);
                        return conn.query(checkTablesSQL).execute();
                    })
                    .compose(rowSet -> {
                        logger.info("========== TABLES IN SCHEMA: {} ==========", schema);
                        if (rowSet.size() == 0) {
                            logger.error("NO TABLES FOUND IN SCHEMA: {}", schema);
                        } else {
                            rowSet.forEach(row -> logger.info("  - {}", row.getString("table_name")));
                        }

                        // Check for required tables
                        boolean hasQueueMessages = false;
                        boolean hasOutbox = false;
                        boolean hasDeadLetterQueue = false;

                        for (var row : rowSet) {
                            String tableName = row.getString("table_name");
                            if ("queue_messages".equals(tableName)) hasQueueMessages = true;
                            if ("outbox".equals(tableName)) hasOutbox = true;
                            if ("dead_letter_queue".equals(tableName)) hasDeadLetterQueue = true;
                        }

                        logger.info("========== REQUIRED TABLES CHECK ==========");
                        logger.info("  queue_messages: {}", hasQueueMessages ? "✓ EXISTS" : "✗ MISSING");
                        logger.info("  outbox: {}", hasOutbox ? "✓ EXISTS" : "✗ MISSING");
                        logger.info("  dead_letter_queue: {}", hasDeadLetterQueue ? "✓ EXISTS" : "✗ MISSING");

                        return Future.succeededFuture();
                    });
        })
        .onComplete(ar -> pool.close())
        .onSuccess(v -> testContext.completeNow())
        .onFailure(testContext::failNow);
    }

    @AfterAll
    void tearDown(Vertx vertx, VertxTestContext testContext) {
        logger.info("========== DIAGNOSTIC TEARDOWN ==========");
        if (deploymentId != null) {
            vertx.undeploy(deploymentId)
                .onComplete(ar -> testContext.completeNow());
        } else {
            testContext.completeNow();
        }
    }
}

