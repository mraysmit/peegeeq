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

    @BeforeEach
    void setUp() throws Exception {
        // Set database connection properties
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.health-check.queue-checks-enabled", "false");

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
            manager.stop();
        }
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
}
