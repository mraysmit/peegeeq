package dev.mars.peegeeq.bitemporal;

import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import dev.mars.peegeeq.test.categories.TestCategories;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@Tag(TestCategories.INTEGRATION)
class DatabaseWorkerVerticleTest {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseWorkerVerticleTest.class);

    @Container
    static PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE);
        container.withDatabaseName("peegeeq_test")
                .withUsername("test")
                .withPassword("test");
        return container;
    }

    private PeeGeeQManager manager;
    private PgBiTemporalEventStore<TestEvent> eventStore;
    private PgBiTemporalEventStore<TestEvent> secondaryEventStore;
    private Vertx vertx;
    private final Map<String, String> originalProperties = new HashMap<>();

    private static <T> T await(io.vertx.core.Future<T> future, long timeout, TimeUnit unit) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(timeout, unit);
    }

    @BeforeEach
    void setUp() throws Exception {
        logger.info("Setting up: configuring database connection properties");
        // Set database connection properties
        setTestProperty("peegeeq.database.host", postgres.getHost());
        setTestProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        setTestProperty("peegeeq.database.name", postgres.getDatabaseName());
        setTestProperty("peegeeq.database.username", postgres.getUsername());
        setTestProperty("peegeeq.database.password", postgres.getPassword());
        setTestProperty("peegeeq.health-check.queue-checks-enabled", "false");

        // Initialize schema
        logger.info("Initializing bitemporal schema");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.BITEMPORAL);

        // Initialize manager and event store
        logger.info("Starting PeeGeeQManager and creating event store");
        manager = new PeeGeeQManager(new PeeGeeQConfiguration());
        manager.start().await();

        vertx = Vertx.vertx();

        eventStore = new PgBiTemporalEventStore<>(
                vertx,
                manager,
                TestEvent.class,
                "bitemporal_event_log",
                new com.fasterxml.jackson.databind.ObjectMapper()
        );
        logger.info("Setup complete: manager started, event store created");
    }
    
    @AfterEach
    void tearDown() {
        logger.info("Tearing down: closing event stores and manager");
        if (secondaryEventStore != null) {
            secondaryEventStore.close();
            secondaryEventStore = null;
        }
        if (eventStore != null) {
            eventStore.close();
            eventStore = null;
        }
        if (manager != null) {
            manager.closeReactive().toCompletionStage().toCompletableFuture().join();
            manager = null;
        }
        restoreTestProperties();
        logger.info("Teardown complete");
    }

    private void setTestProperty(String key, String value) {
        originalProperties.putIfAbsent(key, System.getProperty(key));
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private void restoreTestProperties() {
        for (Map.Entry<String, String> entry : originalProperties.entrySet()) {
            if (entry.getValue() == null) {
                System.clearProperty(entry.getKey());
            } else {
                System.setProperty(entry.getKey(), entry.getValue());
            }
        }
        originalProperties.clear();
    }

    @Test
    void shouldProcessAppendOperationViaEventBus() throws Exception {
        logger.info("Test: verify append operation via event bus routes to the correct worker verticle");
        // Given
        String tableName = "bitemporal_event_log";
        
        // Deploy the worker verticle
        logger.info("Deploying database worker verticle for table '{}'", tableName);
        PgBiTemporalEventStore.deployDatabaseWorkerVerticles(vertx, 1, tableName)
            .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        
        // Prepare append operation message
        JsonObject payload = new JsonObject()
            .put("id", "test-id")
            .put("data", "test-data")
            .put("value", 123);
            
        JsonObject message = new JsonObject()
            .put("operation", "append")
            .put("requestId", UUID.randomUUID().toString())
            .put("instanceKey", getEventBusInstanceKey(eventStore))
            .put("eventType", "test.event")
            .put("payload", payload)
            .put("validTime", Instant.now().toString())
            .put("eventId", UUID.randomUUID().toString())
            .put("transactionTime", Instant.now().toString())
            .put("correlationId", UUID.randomUUID().toString())
            .put("aggregateId", "agg-1");

        // When
        logger.info("Sending append request via event bus to address '{}'",
                PgBiTemporalEventStore.databaseOperationAddress(tableName));
        JsonObject result = vertx.eventBus().<JsonObject>request(PgBiTemporalEventStore.databaseOperationAddress(tableName), message)
            .map(msg -> msg.body())
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        // Then
        logger.info("Append result received: id={}, eventType={}", result.getString("id"), result.getString("eventType"));
        assertNotNull(result);
        assertNotNull(result.getString("id"));
        assertEquals("test.event", result.getString("eventType"));
        assertEquals(payload, result.getJsonObject("payload"));
        logger.info("Append operation via event bus completed successfully");
    }

    @Test
    void shouldFailAppendOperationForUnknownClientKey() throws Exception {
        logger.info("Test: verify append with unknown instanceKey is rejected");
        // Given
        String tableName = "bitemporal_event_log";

        logger.info("Deploying database worker verticle for table '{}'", tableName);
        PgBiTemporalEventStore.deployDatabaseWorkerVerticles(vertx, 1, tableName)
            .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

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

        logger.info("Sending append with instanceKey='does-not-exist' expecting rejection");
        logger.error("THIS IS AN INTENTIONAL TEST ERROR: Negative-path case = append via event bus with unknown instanceKey/clientKey");
        Exception exception = assertThrows(Exception.class, () ->
            vertx.eventBus().<JsonObject>request(PgBiTemporalEventStore.databaseOperationAddress(tableName), message)
                .map(msg -> msg.body())
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS));
        logger.error("THIS IS AN INTENTIONAL TEST ERROR: Captured expected failure = {}", exception.getMessage());
        assertTrue(exception.getMessage().contains("Database pool not initialized"),
            "Expected missing pool error for unknown client key");
    }

    @Test
    void shouldRouteEventBusDistributionToCorrectTableWhenMultipleWorkerDeploymentsExist() throws Exception {
        logger.info("Test: verify events route to correct table when multiple worker deployments exist");
        String primaryTable = "bitemporal_event_log";
        String secondaryTable = "bitemporal_event_log_secondary";

        logger.info("Creating secondary table '{}' and deploying workers for both tables", secondaryTable);
        createSecondaryBitemporalTable(secondaryTable);
        setTestProperty("peegeeq.database.use.event.bus.distribution", "true");

        secondaryEventStore = new PgBiTemporalEventStore<>(
            vertx,
            manager,
            TestEvent.class,
            secondaryTable,
            new com.fasterxml.jackson.databind.ObjectMapper()
        );

        PgBiTemporalEventStore.deployDatabaseWorkerVerticles(vertx, 1, primaryTable)
            .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        PgBiTemporalEventStore.deployDatabaseWorkerVerticles(vertx, 1, secondaryTable)
            .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        logger.info("Appending event to primary table and secondary table");
        await(eventStore.appendBuilder().eventType("table.primary").payload(new TestEvent("p1", "primary", 1)).validTime(Instant.now()).execute(), 10, TimeUnit.SECONDS);
        await(secondaryEventStore.appendBuilder().eventType("table.secondary").payload(new TestEvent("s1", "secondary", 2)).validTime(Instant.now()).execute(), 10, TimeUnit.SECONDS);

        logger.info("Verifying events landed in the correct tables (no cross-contamination)");
        assertEquals(1L, countRowsForEventType(primaryTable, "table.primary"));
        assertEquals(0L, countRowsForEventType(primaryTable, "table.secondary"));
        assertEquals(1L, countRowsForEventType(secondaryTable, "table.secondary"));
        assertEquals(0L, countRowsForEventType(secondaryTable, "table.primary"));
        logger.info("Event routing verified: each table contains only its own events");
    }

    @Test
    void shouldRejectLegacyClientKeyFallbackWhenMultipleStoresShareClientKey() throws Exception {
        logger.info("Test: verify ambiguous legacy clientKey fallback is rejected when multiple stores share __default__");
        String primaryTable = "bitemporal_event_log";
        String secondaryTable = "bitemporal_event_log_secondary_ambiguous";

        logger.info("Creating secondary table with same default client key to force ambiguity");
        createSecondaryBitemporalTable(secondaryTable);

        // Create second store with same default client key (__default__) to force ambiguity.
        secondaryEventStore = new PgBiTemporalEventStore<>(
            vertx,
            manager,
            TestEvent.class,
            secondaryTable,
            new com.fasterxml.jackson.databind.ObjectMapper()
        );

        PgBiTemporalEventStore.deployDatabaseWorkerVerticles(vertx, 1, primaryTable)
            .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        JsonObject payload = new JsonObject()
            .put("id", "ambiguous-id")
            .put("data", "ambiguous")
            .put("value", 999);

        JsonObject message = new JsonObject()
            .put("operation", "append")
            .put("requestId", UUID.randomUUID().toString())
            // Intentionally omit instanceKey to exercise legacy fallback path.
            .put("clientKey", "__default__")
            .put("eventType", "test.event.ambiguous")
            .put("payload", payload)
            .put("validTime", Instant.now().toString())
            .put("correlationId", UUID.randomUUID().toString())
            .put("aggregateId", "agg-ambiguous");

        logger.info("Sending append with clientKey='__default__' (ambiguous) expecting rejection");
        logger.error("THIS IS AN INTENTIONAL TEST ERROR: Negative-path case = ambiguous legacy clientKey fallback across worker deployments");
        Exception exception = assertThrows(Exception.class, () ->
            vertx.eventBus().<JsonObject>request(PgBiTemporalEventStore.databaseOperationAddress(primaryTable), message)
                .map(msg -> msg.body())
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS));
        logger.error("THIS IS AN INTENTIONAL TEST ERROR: Captured expected failure = {}", exception.getMessage());
        assertTrue(exception.getMessage().contains("Database pool not initialized"),
            "Ambiguous legacy client-key fallback should be rejected");
    }

    private void createSecondaryBitemporalTable(String tableName) throws Exception {
        String createSql = "CREATE TABLE IF NOT EXISTS " + tableName + " (LIKE bitemporal_event_log INCLUDING ALL)";
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute(createSql);
            statement.execute("TRUNCATE TABLE " + tableName);
        }
    }

    private long countRowsForEventType(String tableName, String eventType) throws Exception {
        String sql = "SELECT COUNT(*) AS cnt FROM " + tableName + " WHERE event_type = '" + eventType + "'";
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong("cnt");
        }
    }

    private String getEventBusInstanceKey(PgBiTemporalEventStore<?> store) throws Exception {
        return store.eventBusInstanceKey();
    }
}



