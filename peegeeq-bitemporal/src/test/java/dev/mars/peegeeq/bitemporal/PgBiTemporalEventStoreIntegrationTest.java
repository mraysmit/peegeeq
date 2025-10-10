package dev.mars.peegeeq.bitemporal;

import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.junit5.VertxExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test to verify ReactiveNotificationHandler integration with PgBiTemporalEventStore.
 * Tests the complete integration following PGQ coding principles.
 */
@ExtendWith(VertxExtension.class)
@Testcontainers
class PgBiTemporalEventStoreIntegrationTest {
    private static final Logger logger = LoggerFactory.getLogger(PgBiTemporalEventStoreIntegrationTest.class);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_integration_test")
            .withUsername("peegeeq_test")
            .withPassword("peegeeq_test")
            .withSharedMemorySize(256 * 1024 * 1024L)
            .withReuse(false);

    private PeeGeeQManager peeGeeQManager;
    private PgBiTemporalEventStore<Map<String, Object>> eventStore;

    @BeforeEach
    void setUp() throws Exception {
        logger.info("Setting up ReactiveNotificationHandler integration test...");

        // Set system properties for PeeGeeQ configuration - following exact outbox pattern
        configureSystemPropertiesForContainer(postgres);

        // Create bitemporal_event_log table - following established pattern from SharedPostgresExtension
        createBiTemporalEventLogTable();

        logger.info("✓ ReactiveNotificationHandler integration test setup completed");
    }

    /**
     * Create the bitemporal_event_log table following the established pattern from migration scripts.
     */
    private void createBiTemporalEventLogTable() throws Exception {
        logger.info("Creating bitemporal_event_log table using PeeGeeQTestSchemaInitializer...");

        // Use the centralized schema initializer - following established patterns
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.BITEMPORAL);

        logger.info("bitemporal_event_log table created successfully");
    }

    /**
     * Configures system properties to use the TestContainer database - following exact outbox pattern.
     */
    private void configureSystemPropertiesForContainer(PostgreSQLContainer<?> postgres) {
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.schema", "public");
        System.setProperty("peegeeq.database.ssl.enabled", "false");
        System.setProperty("peegeeq.metrics.enabled", "true");
        System.setProperty("peegeeq.health.enabled", "true");
        System.setProperty("peegeeq.migration.enabled", "true");
        System.setProperty("peegeeq.migration.auto-migrate", "true");
    }

    @Test
    void testReactiveNotificationHandlerIntegration() throws Exception {
        logger.info("=== Testing ReactiveNotificationHandler Integration ===");

        // Initialize PeeGeeQ Manager - following exact outbox pattern
        peeGeeQManager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());
        peeGeeQManager.start();
        logger.info("PeeGeeQ Manager started successfully");

        // Create event store - this will test ReactiveNotificationHandler integration
        Class<Map<String, Object>> mapClass = (Class<Map<String, Object>>) (Class<?>) Map.class;
        eventStore = new PgBiTemporalEventStore<>(peeGeeQManager, mapClass, new ObjectMapper());
        logger.info("PgBiTemporalEventStore created successfully");

        // Test data
        String eventType = "test_event";
        String aggregateId = "test-aggregate-123";
        Map<String, Object> payload = Map.of("message", "integration test", "timestamp", Instant.now().toString());

        // Set up notification handler to capture events
        CountDownLatch notificationLatch = new CountDownLatch(1);
        AtomicReference<BiTemporalEvent<Map<String, Object>>> receivedEvent = new AtomicReference<>();

        // Subscribe to notifications - this tests ReactiveNotificationHandler.subscribe()
        // Following established pattern from PeeGeeQBiTemporalIntegrationTest
        CompletableFuture<Void> subscriptionFuture = eventStore.subscribe(eventType, message -> {
            BiTemporalEvent<Map<String, Object>> event = message.getPayload();
            logger.info("Received notification for event: {}", event.getEventId());
            receivedEvent.set(event);
            notificationLatch.countDown();
            return CompletableFuture.completedFuture(null);
        });
        subscriptionFuture.get(10, TimeUnit.SECONDS);
        logger.info("Subscription established successfully");

        // Give subscription time to be established
        Thread.sleep(1000);

        // Append event - this should trigger notification via ReactiveNotificationHandler
        // Following established pattern: append(eventType, payload, validTime, headers, correlationId, aggregateId)
        BiTemporalEvent<Map<String, Object>> appendedEvent = eventStore.append(
            eventType, payload, Instant.now(),
            Map.of("source", "integration-test"),
            "test-correlation-" + System.currentTimeMillis(),
            aggregateId
        ).join();
        logger.info("Event appended: {}", appendedEvent.getEventId());

        // Wait for notification - this tests the complete integration
        boolean notificationReceived = notificationLatch.await(15, TimeUnit.SECONDS);
        assertTrue(notificationReceived, "Should receive notification within 15 seconds");

        // Verify notification content
        BiTemporalEvent<Map<String, Object>> notification = receivedEvent.get();
        assertNotNull(notification, "Notification event should not be null");
        assertEquals(appendedEvent.getEventId(), notification.getEventId(), "Event ID should match");
        assertEquals(eventType, notification.getEventType(), "Event type should match");
        assertEquals(aggregateId, notification.getAggregateId(), "Aggregate ID should match");
        assertEquals(payload, notification.getPayload(), "Payload should match");

        logger.info("✅ ReactiveNotificationHandler integration test completed successfully");
    }

    @AfterEach
    void tearDown() {
        logger.info("Cleaning up integration test...");

        if (eventStore != null) {
            try {
                eventStore.close();
            } catch (Exception e) {
                logger.warn("Error closing event store: {}", e.getMessage(), e);
            }
        }

        if (peeGeeQManager != null) {
            try {
                peeGeeQManager.close();
            } catch (Exception e) {
                logger.warn("Error closing PeeGeeQManager: {}", e.getMessage(), e);
            }
        }

        logger.info("Integration test cleanup completed");
    }

}
