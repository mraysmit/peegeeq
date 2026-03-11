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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@Tag(TestCategories.INTEGRATION)
class DatabaseWorkerVerticleTest {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseWorkerVerticleTest.class);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
            .withDatabaseName("peegeeq_test")
            .withUsername("test")
            .withPassword("test");

    private PeeGeeQManager manager;
    private PgBiTemporalEventStore<TestEvent> eventStore;
    private Vertx vertx;
    private final Map<String, String> originalProperties = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        // Set database connection properties
        setTestProperty("peegeeq.database.host", postgres.getHost());
        setTestProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        setTestProperty("peegeeq.database.name", postgres.getDatabaseName());
        setTestProperty("peegeeq.database.username", postgres.getUsername());
        setTestProperty("peegeeq.database.password", postgres.getPassword());
        setTestProperty("peegeeq.health-check.queue-checks-enabled", "false");

        // Initialize schema
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.BITEMPORAL);

        // Initialize manager and event store
        manager = new PeeGeeQManager(new PeeGeeQConfiguration());
        manager.start();

        eventStore = new PgBiTemporalEventStore<>(
                manager,
                TestEvent.class,
                "bitemporal_event_log",
                new com.fasterxml.jackson.databind.ObjectMapper()
        );
        
        // Get the shared Vertx instance
        vertx = PgBiTemporalEventStore.getOrCreateSharedVertx();
    }
    
    @AfterEach
    void tearDown() {
        if (eventStore != null) {
            eventStore.close();
        }
        if (manager != null) {
            manager.closeReactive().toCompletionStage().toCompletableFuture().join();
        }
        restoreTestProperties();
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
        // Given
        String tableName = "bitemporal_event_log";
        
        // Deploy the worker verticle
        CompletableFuture<String> deploymentFuture = new CompletableFuture<>();
        PgBiTemporalEventStore.deployDatabaseWorkerVerticles(1, tableName)
            .onSuccess(deploymentFuture::complete)
            .onFailure(deploymentFuture::completeExceptionally);
            
        deploymentFuture.get(10, TimeUnit.SECONDS);
        
        // Prepare append operation message
        JsonObject payload = new JsonObject()
            .put("id", "test-id")
            .put("data", "test-data")
            .put("value", 123);
            
        JsonObject message = new JsonObject()
            .put("operation", "append")
            .put("requestId", UUID.randomUUID().toString())
            .put("eventType", "test.event")
            .put("payload", payload)
            .put("validTime", Instant.now().toString())
            .put("correlationId", UUID.randomUUID().toString())
            .put("aggregateId", "agg-1");

        // When
        CompletableFuture<JsonObject> resultFuture = new CompletableFuture<>();
        vertx.eventBus().<JsonObject>request("peegeeq.database.operations", message)
            .onSuccess(msg -> resultFuture.complete(msg.body()))
            .onFailure(resultFuture::completeExceptionally);

        // Then
        JsonObject result = resultFuture.get(5, TimeUnit.SECONDS);
        assertNotNull(result);
        assertNotNull(result.getString("id"));
        assertEquals("test.event", result.getString("eventType"));
        assertEquals(payload, result.getJsonObject("payload"));
    }

    @Test
    void shouldFailAppendOperationForUnknownClientKey() throws Exception {
        // Given
        String tableName = "bitemporal_event_log";

        CompletableFuture<String> deploymentFuture = new CompletableFuture<>();
        PgBiTemporalEventStore.deployDatabaseWorkerVerticles(1, tableName)
            .onSuccess(deploymentFuture::complete)
            .onFailure(deploymentFuture::completeExceptionally);

        deploymentFuture.get(10, TimeUnit.SECONDS);

        JsonObject payload = new JsonObject()
            .put("id", "test-id-unknown")
            .put("data", "test-data")
            .put("value", 123);

        JsonObject message = new JsonObject()
            .put("operation", "append")
            .put("requestId", UUID.randomUUID().toString())
            .put("eventType", "test.event.unknown")
            .put("payload", payload)
            .put("validTime", Instant.now().toString())
            .put("correlationId", UUID.randomUUID().toString())
            .put("aggregateId", "agg-unknown")
            .put("clientKey", "does-not-exist");

        CompletableFuture<JsonObject> resultFuture = new CompletableFuture<>();
        vertx.eventBus().<JsonObject>request("peegeeq.database.operations", message)
            .onSuccess(msg -> resultFuture.complete(msg.body()))
            .onFailure(error -> resultFuture.completeExceptionally(error));

        Exception exception = assertThrows(Exception.class, () -> resultFuture.get(5, TimeUnit.SECONDS));
        assertTrue(exception.getMessage().contains("Database pool not initialized"),
            "Expected missing pool error for unknown client key");
    }
}


