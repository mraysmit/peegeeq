package dev.mars.peegeeq.bitemporal;

import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.Properties;
import java.util.UUID;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@ExtendWith(VertxExtension.class)
@Tag(TestCategories.INTEGRATION)
class DatabaseWorkerVerticleTest {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseWorkerVerticleTest.class);

    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private PgBiTemporalEventStore<TestEvent> eventStore;
    private PgBiTemporalEventStore<TestEvent> secondaryEventStore;
    private Vertx vertx;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext testContext) throws Exception {
        this.vertx = vertx;
        logger.info("Setting up: configuring database connection properties");

        Properties testProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.health-check.queue-checks-enabled", "false")
                .property("peegeeq.database.use.event.bus.distribution", "true")
                .build();

        // Initialize schema
        logger.info("Initializing bitemporal schema");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.BITEMPORAL);

        // Initialize manager and event store
        logger.info("Starting PeeGeeQManager and creating event store");
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("default", testProps), new SimpleMeterRegistry());
        manager.start()
                .onSuccess(v -> {
                    eventStore = new PgBiTemporalEventStore<>(
                            vertx,
                            manager,
                            TestEvent.class,
                            "bitemporal_event_log",
                            new com.fasterxml.jackson.databind.ObjectMapper()
                    );
                    logger.info("Setup complete: manager started, event store created");
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
    }
    
    @AfterEach
    void tearDown(VertxTestContext testContext) {
        logger.info("Tearing down: closing event stores and manager");
        if (secondaryEventStore != null) {
            secondaryEventStore.close();
            secondaryEventStore = null;
        }
        if (eventStore != null) {
            eventStore.close();
            eventStore = null;
        }
        Future<Void> closeFuture = (manager != null)
                ? manager.closeReactive().transform(ar -> {
                    if (ar.failed()) logger.warn("Error closing manager: {}", ar.cause().getMessage());
                    return Future.succeededFuture();
                })
                : Future.succeededFuture();
        closeFuture.onSuccess(v -> {
            manager = null;
            logger.info("Teardown complete");
            testContext.completeNow();
        }).onFailure(testContext::failNow);
    }

    @Test
    void shouldProcessAppendOperationViaEventBus(VertxTestContext testContext) throws Exception {
        logger.info("Test: verify append operation via event bus routes to the correct worker verticle");
        String tableName = "bitemporal_event_log";

        JsonObject payload = new JsonObject()
            .put("id", "test-id")
            .put("data", "test-data")
            .put("value", 123);

        PgBiTemporalEventStore.deployDatabaseWorkerVerticles(vertx, 1, tableName)
            .compose(v -> {
                JsonObject message = new JsonObject()
                    .put("operation", "append")
                    .put("requestId", UUID.randomUUID().toString())
                    .put("instanceKey", eventStore.eventBusInstanceKey())
                    .put("eventType", "test.event")
                    .put("payload", payload)
                    .put("validTime", Instant.now().toString())
                    .put("eventId", UUID.randomUUID().toString())
                    .put("transactionTime", Instant.now().toString())
                    .put("correlationId", UUID.randomUUID().toString())
                    .put("aggregateId", "agg-1");
                return vertx.eventBus().<JsonObject>request(
                        PgBiTemporalEventStore.databaseOperationAddress(tableName), message)
                        .map(msg -> msg.body());
            })
            .onComplete(testContext.succeeding(result -> testContext.verify(() -> {
                assertNotNull(result);
                assertNotNull(result.getString("id"));
                assertEquals("test.event", result.getString("eventType"));
                assertEquals(payload, result.getJsonObject("payload"));
                logger.info("Append operation via event bus completed successfully");
                testContext.completeNow();
            })));

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    @Test
    void shouldFailAppendOperationForUnknownClientKey(VertxTestContext testContext) throws Exception {
        logger.info("Test: verify append with unknown instanceKey is rejected");
        String tableName = "bitemporal_event_log";

        JsonObject payload = new JsonObject()
            .put("id", "test-id-unknown")
            .put("data", "test-data")
            .put("value", 123);

        JsonObject message = new JsonObject()
            .put("operation", "append")
            .put("requestId", UUID.randomUUID().toString())
            .put("instanceKey", "does-not-exist")
            .put("eventType", "test.event.unknown")
            .put("payload", payload)
            .put("validTime", Instant.now().toString())
            .put("correlationId", UUID.randomUUID().toString())
            .put("aggregateId", "agg-unknown")
            .put("clientKey", "does-not-exist");

        logger.error("THIS IS AN INTENTIONAL TEST ERROR: Negative-path case = append via event bus with unknown instanceKey/clientKey");
        PgBiTemporalEventStore.deployDatabaseWorkerVerticles(vertx, 1, tableName)
            .compose(v -> vertx.eventBus().<JsonObject>request(
                    PgBiTemporalEventStore.databaseOperationAddress(tableName), message)
                    .map(msg -> msg.body()))
            .onSuccess(result -> testContext.failNow(
                    new AssertionError("Expected failure for unknown instanceKey but got success")))
            .onFailure(err -> testContext.verify(() -> {
                logger.error("THIS IS AN INTENTIONAL TEST ERROR: Captured expected failure = {}", err.getMessage());
                assertTrue(err.getMessage().contains("Database pool not initialized"),
                        "Expected missing pool error for unknown client key");
                testContext.completeNow();
            }));

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    @Test
    void shouldRouteEventBusDistributionToCorrectTableWhenMultipleWorkerDeploymentsExist(VertxTestContext testContext) throws Exception {
        logger.info("Test: verify events route to correct table when multiple worker deployments exist");
        String primaryTable = "bitemporal_event_log";
        String secondaryTable = "bitemporal_event_log_secondary";

        logger.info("Creating secondary table '{}' and deploying workers for both tables", secondaryTable);
        createSecondaryBitemporalTable(secondaryTable);

        secondaryEventStore = new PgBiTemporalEventStore<>(
            vertx, manager, TestEvent.class, secondaryTable,
            new com.fasterxml.jackson.databind.ObjectMapper()
        );

        PgBiTemporalEventStore.deployDatabaseWorkerVerticles(vertx, 1, primaryTable)
            .compose(v -> PgBiTemporalEventStore.deployDatabaseWorkerVerticles(vertx, 1, secondaryTable))
            .compose(v -> eventStore.appendBuilder().eventType("table.primary")
                    .payload(new TestEvent("p1", "primary", 1)).validTime(Instant.now()).execute())
            .compose(v -> secondaryEventStore.appendBuilder().eventType("table.secondary")
                    .payload(new TestEvent("s1", "secondary", 2)).validTime(Instant.now()).execute())
            .compose(v -> countRowsForEventType(primaryTable, "table.primary"))
            .compose(primaryCount -> {
                testContext.verify(() -> assertEquals(1L, primaryCount,
                        "primary table should have 1 row for table.primary"));
                return countRowsForEventType(primaryTable, "table.secondary");
            })
            .compose(crossContamination -> {
                testContext.verify(() -> assertEquals(0L, crossContamination,
                        "primary table should have 0 rows for table.secondary"));
                return countRowsForEventType(secondaryTable, "table.secondary");
            })
            .compose(secondaryCount -> {
                testContext.verify(() -> assertEquals(1L, secondaryCount,
                        "secondary table should have 1 row for table.secondary"));
                return countRowsForEventType(secondaryTable, "table.primary");
            })
            .onComplete(testContext.succeeding(crossContamination2 -> testContext.verify(() -> {
                assertEquals(0L, crossContamination2,
                        "secondary table should have 0 rows for table.primary");
                logger.info("Event routing verified: each table contains only its own events");
                testContext.completeNow();
            })));

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    @Test
    void shouldRejectLegacyClientKeyFallbackWhenMultipleStoresShareClientKey(VertxTestContext testContext) throws Exception {
        logger.info("Test: verify ambiguous legacy clientKey fallback is rejected when multiple stores share __default__");
        String primaryTable = "bitemporal_event_log";
        String secondaryTable = "bitemporal_event_log_secondary_ambiguous";

        logger.info("Creating secondary table with same default client key to force ambiguity");
        createSecondaryBitemporalTable(secondaryTable);

        secondaryEventStore = new PgBiTemporalEventStore<>(
            vertx, manager, TestEvent.class, secondaryTable,
            new com.fasterxml.jackson.databind.ObjectMapper()
        );

        JsonObject payload = new JsonObject()
            .put("id", "ambiguous-id")
            .put("data", "ambiguous")
            .put("value", 999);

        JsonObject message = new JsonObject()
            .put("operation", "append")
            .put("requestId", UUID.randomUUID().toString())
            .put("clientKey", "__default__")
            .put("eventType", "test.event.ambiguous")
            .put("payload", payload)
            .put("validTime", Instant.now().toString())
            .put("correlationId", UUID.randomUUID().toString())
            .put("aggregateId", "agg-ambiguous");

        logger.error("THIS IS AN INTENTIONAL TEST ERROR: Negative-path case = ambiguous legacy clientKey fallback across worker deployments");
        PgBiTemporalEventStore.deployDatabaseWorkerVerticles(vertx, 1, primaryTable)
            .compose(v -> vertx.eventBus().<JsonObject>request(
                    PgBiTemporalEventStore.databaseOperationAddress(primaryTable), message)
                    .map(msg -> msg.body()))
            .onSuccess(result -> testContext.failNow(
                    new AssertionError("Expected failure for ambiguous clientKey but got success")))
            .onFailure(err -> testContext.verify(() -> {
                logger.error("THIS IS AN INTENTIONAL TEST ERROR: Captured expected failure = {}", err.getMessage());
                assertTrue(err.getMessage().contains("Database pool not initialized"),
                        "Ambiguous legacy client-key fallback should be rejected");
                testContext.completeNow();
            }));

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) throw new RuntimeException(testContext.causeOfFailure());
    }

    private void createSecondaryBitemporalTable(String tableName) throws Exception {
        String createSql = "CREATE TABLE IF NOT EXISTS " + tableName + " (LIKE bitemporal_event_log INCLUDING ALL)";
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement()) {
            // The LIKE source and the new table must live in the resolved schema the
            // manager targets (suite-wide convention)
            statement.execute("SET search_path TO " + PostgreSQLTestConstants.TEST_SCHEMA);
            statement.execute(createSql);
            statement.execute("TRUNCATE TABLE " + tableName);
        }
    }

    private Future<Long> countRowsForEventType(String tableName, String eventType) {
        return manager.getPool()
                .preparedQuery("SELECT COUNT(*) AS cnt FROM " + tableName + " WHERE event_type = $1")
                .execute(io.vertx.sqlclient.Tuple.of(eventType))
                .map(rows -> rows.iterator().next().getLong("cnt"));
    }

    private String getEventBusInstanceKey(PgBiTemporalEventStore<?> store) {
        return store.eventBusInstanceKey();
    }
}



