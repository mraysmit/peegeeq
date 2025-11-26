package dev.mars.peegeeq.rest.setup;

import dev.mars.peegeeq.api.setup.*;
import dev.mars.peegeeq.api.database.DatabaseConfig;
import dev.mars.peegeeq.api.database.QueueConfig;
import dev.mars.peegeeq.api.database.EventStoreConfig;
import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for RestDatabaseSetupService.
 * Validates that the REST-specific setup service properly registers queue factories.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class RestDatabaseSetupServiceTest {

    private static final Logger logger = LoggerFactory.getLogger(RestDatabaseSetupServiceTest.class);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_rest_setup_test")
            .withUsername("peegeeq_test")
            .withPassword("peegeeq_test")
            .withReuse(false);

    private RestDatabaseSetupService setupService;
    private String testSetupId;

    @BeforeEach
    void setUp() {
        setupService = new RestDatabaseSetupService();
        testSetupId = "rest-test-setup-" + System.currentTimeMillis();
        logger.info("Starting test with setup ID: {}", testSetupId);
    }

    @Test
    @Order(1)
    void testRestSetupServiceCreation() {
        logger.info("=== Testing REST Setup Service Creation ===");

        assertNotNull(setupService, "Setup service should be created");
        logger.info("✅ REST setup service creation test passed");
    }

    @Test
    @Order(2)
    void testCompleteSetupWithQueueFactoryRegistration() throws Exception {
        logger.info("=== Testing Complete Setup with Queue Factory Registration ===");

        // Create database configuration
        DatabaseConfig dbConfig = new DatabaseConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .databaseName("rest_test_db_" + System.currentTimeMillis())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .schema("public")
                .templateDatabase("template0")
                .encoding("UTF8")
                .build();

        // Create queue configurations
        List<QueueConfig> queues = List.of(
                new QueueConfig.Builder()
                        .queueName("orders")
                        .maxRetries(3)
                        .visibilityTimeoutSeconds(30)
                        .build(),
                new QueueConfig.Builder()
                        .queueName("notifications")
                        .maxRetries(5)
                        .visibilityTimeoutSeconds(60)
                        .build()
        );

        // Create event store configurations
        List<EventStoreConfig> eventStores = List.of(
                new EventStoreConfig.Builder()
                        .tableName("order_events")
                        .notificationPrefix("order_")
                        .build()
        );

        // Create setup request
        DatabaseSetupRequest request = new DatabaseSetupRequest(
                testSetupId,
                dbConfig,
                queues,
                eventStores,
                Map.of("test", "true")
        );

        // Execute setup
        var future = setupService.createCompleteSetup(request);
        DatabaseSetupResult result = future.get(60, TimeUnit.SECONDS);

        // Verify result
        assertNotNull(result, "Setup result should not be null");
        assertEquals(testSetupId, result.getSetupId(), "Setup ID should match");
        assertEquals(DatabaseSetupStatus.ACTIVE, result.getStatus(), "Setup should be active");
        assertNotNull(result.getQueueFactories(), "Queue factories should be created");
        assertNotNull(result.getEventStores(), "Event stores should be created");

        logger.info("Setup created successfully with {} queue factories", 
                result.getQueueFactories().size());

        // Verify queue factories were created (REST service should register factories)
        assertFalse(result.getQueueFactories().isEmpty(), 
                "Queue factories should be created by REST setup service");

        // Cleanup
        setupService.destroySetup(testSetupId).get(30, TimeUnit.SECONDS);
        logger.info("✅ Complete setup with queue factory registration test passed");
    }

    @Test
    @Order(3)
    void testSetupWithFactoryRegistrationFailure() throws Exception {
        logger.info("=== Testing Setup with Factory Registration Failure Handling ===");

        // Create a minimal setup that should work even if some factory registrations fail
        DatabaseConfig dbConfig = new DatabaseConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .databaseName("rest_failure_test_db_" + System.currentTimeMillis())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .schema("public")
                .build();

        DatabaseSetupRequest request = new DatabaseSetupRequest(
                testSetupId,
                dbConfig,
                List.of(), // No queues
                List.of(), // No event stores
                Map.of()
        );

        // Should succeed even if factory registration has issues
        assertDoesNotThrow(() -> {
            var future = setupService.createCompleteSetup(request);
            DatabaseSetupResult result = future.get(30, TimeUnit.SECONDS);
            assertNotNull(result);
            assertEquals(DatabaseSetupStatus.ACTIVE, result.getStatus());

            // Cleanup
            setupService.destroySetup(testSetupId).get(30, TimeUnit.SECONDS);
        });

        logger.info("✅ Setup with factory registration failure handling test passed");
    }

    @Test
    @Order(4)
    void testAddQueueToExistingSetup() throws Exception {
        logger.info("=== Testing Add Queue to Existing Setup ===");

        // Create initial setup
        DatabaseConfig dbConfig = new DatabaseConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .databaseName("rest_add_queue_test_db_" + System.currentTimeMillis())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .schema("public")
                .build();

        DatabaseSetupRequest request = new DatabaseSetupRequest(
                testSetupId,
                dbConfig,
                List.of(),
                List.of(),
                Map.of()
        );

        var future = setupService.createCompleteSetup(request);
        DatabaseSetupResult result = future.get(30, TimeUnit.SECONDS);
        assertNotNull(result);

        // Add a queue to the existing setup
        QueueConfig newQueue = new QueueConfig.Builder()
                .queueName("dynamic_queue")
                .maxRetries(3)
                .build();

        assertDoesNotThrow(() -> {
            setupService.addQueue(testSetupId, newQueue).get(30, TimeUnit.SECONDS);
        });

        logger.info("Queue added successfully to existing setup");

        // Cleanup
        setupService.destroySetup(testSetupId).get(30, TimeUnit.SECONDS);
        logger.info("✅ Add queue to existing setup test passed");
    }

    @Test
    @Order(5)
    void testAddEventStoreToExistingSetup() throws Exception {
        logger.info("=== Testing Add Event Store to Existing Setup ===");

        // Create initial setup
        DatabaseConfig dbConfig = new DatabaseConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .databaseName("rest_add_eventstore_test_db_" + System.currentTimeMillis())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .schema("public")
                .build();

        DatabaseSetupRequest request = new DatabaseSetupRequest(
                testSetupId,
                dbConfig,
                List.of(),
                List.of(),
                Map.of()
        );

        var future = setupService.createCompleteSetup(request);
        DatabaseSetupResult result = future.get(30, TimeUnit.SECONDS);
        assertNotNull(result);

        // Add an event store to the existing setup
        EventStoreConfig newEventStore = new EventStoreConfig.Builder()
                .tableName("dynamic_events")
                .notificationPrefix("dynamic_")
                .build();

        assertDoesNotThrow(() -> {
            setupService.addEventStore(testSetupId, newEventStore).get(30, TimeUnit.SECONDS);
        });

        logger.info("Event store added successfully to existing setup");

        // Cleanup
        setupService.destroySetup(testSetupId).get(30, TimeUnit.SECONDS);
        logger.info("✅ Add event store to existing setup test passed");
    }

    @Test
    @Order(6)
    void testGetSetupStatus() throws Exception {
        logger.info("=== Testing Get Setup Status ===");

        // Create setup
        DatabaseConfig dbConfig = new DatabaseConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .databaseName("rest_status_test_db_" + System.currentTimeMillis())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .schema("public")
                .build();

        DatabaseSetupRequest request = new DatabaseSetupRequest(
                testSetupId,
                dbConfig,
                List.of(),
                List.of(),
                Map.of()
        );

        setupService.createCompleteSetup(request).get(30, TimeUnit.SECONDS);

        // Check status
        var statusFuture = setupService.getSetupStatus(testSetupId);
        DatabaseSetupStatus status = statusFuture.get(10, TimeUnit.SECONDS);
        assertEquals(DatabaseSetupStatus.ACTIVE, status);

        // Cleanup
        setupService.destroySetup(testSetupId).get(30, TimeUnit.SECONDS);
        logger.info("✅ Get setup status test passed");
    }

    @Test
    @Order(7)
    void testGetAllActiveSetupIds() throws Exception {
        logger.info("=== Testing Get All Active Setup IDs ===");

        // Initially should be empty or contain other test setups
        var initialIds = setupService.getAllActiveSetupIds().get(10, TimeUnit.SECONDS);
        int initialCount = initialIds.size();

        // Create a setup
        DatabaseConfig dbConfig = new DatabaseConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .databaseName("rest_all_ids_test_db_" + System.currentTimeMillis())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .schema("public")
                .build();

        DatabaseSetupRequest request = new DatabaseSetupRequest(
                testSetupId,
                dbConfig,
                List.of(),
                List.of(),
                Map.of()
        );

        setupService.createCompleteSetup(request).get(30, TimeUnit.SECONDS);

        // Should now include our setup
        var activeIds = setupService.getAllActiveSetupIds().get(10, TimeUnit.SECONDS);
        assertEquals(initialCount + 1, activeIds.size());
        assertTrue(activeIds.contains(testSetupId));

        // Cleanup
        setupService.destroySetup(testSetupId).get(30, TimeUnit.SECONDS);

        // Should be back to initial count
        var finalIds = setupService.getAllActiveSetupIds().get(10, TimeUnit.SECONDS);
        assertEquals(initialCount, finalIds.size());

        logger.info("✅ Get all active setup IDs test passed");
    }
}
