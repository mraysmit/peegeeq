package dev.mars.peegeeq.bitemporal;

import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import dev.mars.peegeeq.test.categories.TestCategories;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.junit5.VertxExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vertx.core.Future;
import io.vertx.sqlclient.TransactionPropagation;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test to verify ReactiveNotificationHandler integration with PgBiTemporalEventStore.
 * Tests the complete integration following PGQ coding principles.
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
class PgBiTemporalEventStoreIntegrationTest {
    private static final Logger logger = LoggerFactory.getLogger(PgBiTemporalEventStoreIntegrationTest.class);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
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

        // Create bitemporal_event_log table - following established pattern from SharedPostgresTestExtension
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
        
        // Also create test_events table for this specific test
        logger.info("Creating test_events table for integration test...");
        try (var conn = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS test_events (
                    event_id VARCHAR(255) PRIMARY KEY,
                    event_type VARCHAR(255) NOT NULL,
                    aggregate_id VARCHAR(255),
                    correlation_id VARCHAR(255),
                    valid_time TIMESTAMPTZ NOT NULL,
                    transaction_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    payload JSONB NOT NULL,
                    headers JSONB NOT NULL DEFAULT '{}',
                    version BIGINT NOT NULL DEFAULT 1,
                    previous_version_id VARCHAR(255),
                    is_correction BOOLEAN NOT NULL DEFAULT FALSE,
                    correction_reason TEXT,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    CONSTRAINT test_events_version_check CHECK (
                        (version = 1 AND previous_version_id IS NULL) OR
                        (version > 1 AND previous_version_id IS NOT NULL)
                    )
                )
                """);
            
            // Add indexes
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_test_events_event_type ON test_events(event_type)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_test_events_aggregate_id ON test_events(aggregate_id)");
            
            // Add notification trigger (same as bitemporal_event_log)
            stmt.execute("""
                CREATE OR REPLACE FUNCTION notify_test_events() RETURNS TRIGGER AS $$
                BEGIN
                    -- Send notification with event details
                    PERFORM pg_notify(
                        'bitemporal_events',
                        json_build_object(
                            'event_id', NEW.event_id,
                            'event_type', NEW.event_type,
                            'aggregate_id', NEW.aggregate_id,
                            'correlation_id', NEW.correlation_id,
                            'is_correction', NEW.is_correction,
                            'transaction_time', extract(epoch from NEW.transaction_time)
                        )::text
                    );

                    -- Send type-specific notification (replace dots with underscores for valid channel names)
                    PERFORM pg_notify(
                        'bitemporal_events_' || replace(NEW.event_type, '.', '_'),
                        json_build_object(
                            'event_id', NEW.event_id,
                            'event_type', NEW.event_type,
                            'aggregate_id', NEW.aggregate_id,
                            'correlation_id', NEW.correlation_id,
                            'is_correction', NEW.is_correction,
                            'transaction_time', extract(epoch from NEW.transaction_time)
                        )::text
                    );

                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql;
                """);

            stmt.execute("""
                DROP TRIGGER IF EXISTS trigger_notify_test_events ON test_events;
                CREATE TRIGGER trigger_notify_test_events
                    AFTER INSERT ON test_events
                    FOR EACH ROW
                    EXECUTE FUNCTION notify_test_events();
                """);
            
            logger.info("test_events table created successfully");
        }
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
        eventStore = new PgBiTemporalEventStore<>(peeGeeQManager, mapClass, "test_events", new ObjectMapper());
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

    @Test
    void testAppendWithTransactionOverloads() throws Exception {
        logger.info("=== Testing appendWithTransaction overloads ===");

        // Initialize PeeGeeQ Manager
        peeGeeQManager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());
        peeGeeQManager.start();

        Class<Map<String, Object>> mapClass = (Class<Map<String, Object>>) (Class<?>) Map.class;
        eventStore = new PgBiTemporalEventStore<>(peeGeeQManager, mapClass, "test_events", new ObjectMapper());

        // Test appendWithTransaction(eventType, payload, validTime, propagation)
        Map<String, Object> payload1 = Map.of("test", "overload1");
        BiTemporalEvent<Map<String, Object>> event1 = eventStore.appendWithTransaction(
            "test.overload1", payload1, Instant.now(), TransactionPropagation.CONTEXT
        ).get(10, TimeUnit.SECONDS);
        assertNotNull(event1);
        assertEquals("test.overload1", event1.getEventType());

        // Test appendWithTransaction(eventType, payload, validTime, headers, propagation)
        Map<String, Object> payload2 = Map.of("test", "overload2");
        BiTemporalEvent<Map<String, Object>> event2 = eventStore.appendWithTransaction(
            "test.overload2", payload2, Instant.now(), Map.of("header", "value"), TransactionPropagation.CONTEXT
        ).get(10, TimeUnit.SECONDS);
        assertNotNull(event2);
        assertEquals("test.overload2", event2.getEventType());

        // Test appendWithTransaction(eventType, payload, validTime, headers, correlationId, propagation)
        Map<String, Object> payload3 = Map.of("test", "overload3");
        BiTemporalEvent<Map<String, Object>> event3 = eventStore.appendWithTransaction(
            "test.overload3", payload3, Instant.now(), Map.of(), "corr-123", TransactionPropagation.CONTEXT
        ).get(10, TimeUnit.SECONDS);
        assertNotNull(event3);
        assertEquals("corr-123", event3.getCorrelationId());

        // Test appendWithTransaction(eventType, payload, validTime, headers, correlationId, aggregateId, propagation)
        Map<String, Object> payload4 = Map.of("test", "overload4");
        BiTemporalEvent<Map<String, Object>> event4 = eventStore.appendWithTransaction(
            "test.overload4", payload4, Instant.now(), Map.of(), "corr-456", "agg-789", TransactionPropagation.CONTEXT
        ).get(10, TimeUnit.SECONDS);
        assertNotNull(event4);
        assertEquals("agg-789", event4.getAggregateId());

        logger.info("✅ appendWithTransaction overloads test completed successfully");
    }

    @Test
    void testAppendReactive() throws Exception {
        logger.info("=== Testing appendReactive ===");

        peeGeeQManager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());
        peeGeeQManager.start();

        Class<Map<String, Object>> mapClass = (Class<Map<String, Object>>) (Class<?>) Map.class;
        eventStore = new PgBiTemporalEventStore<>(peeGeeQManager, mapClass, "test_events", new ObjectMapper());

        Map<String, Object> payload = Map.of("reactive", "test");
        Future<BiTemporalEvent<Map<String, Object>>> future = eventStore.appendReactive(
            "test.reactive", payload, Instant.now()
        );

        BiTemporalEvent<Map<String, Object>> event = future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertNotNull(event);
        assertEquals("test.reactive", event.getEventType());

        logger.info("✅ appendReactive test completed successfully");
    }

    @Test
    void testSubscribeReactiveAndUnsubscribe() throws Exception {
        logger.info("=== Testing subscribeReactive and unsubscribe ===");

        peeGeeQManager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());
        peeGeeQManager.start();

        Class<Map<String, Object>> mapClass = (Class<Map<String, Object>>) (Class<?>) Map.class;
        eventStore = new PgBiTemporalEventStore<>(peeGeeQManager, mapClass, "test_events", new ObjectMapper());

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<BiTemporalEvent<Map<String, Object>>> received = new AtomicReference<>();

        // Test subscribeReactive - dots are now allowed and converted to underscores for channel names
        Future<Void> subscriptionFuture = eventStore.subscribeReactive("test.subscribe", message -> {
            received.set(message.getPayload());
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });
        subscriptionFuture.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        // Give subscription time to establish
        Thread.sleep(500);

        // Append an event - use dot notation to match subscription
        Map<String, Object> payload = Map.of("subscribe", "test");
        eventStore.append("test.subscribe", payload, Instant.now()).get(10, TimeUnit.SECONDS);

        // Wait for notification
        boolean notified = latch.await(10, TimeUnit.SECONDS);
        assertTrue(notified, "Should receive notification");
        assertNotNull(received.get());

        // Test unsubscribe
        eventStore.unsubscribe();

        logger.info("✅ subscribeReactive and unsubscribe test completed successfully");
    }

    /**
     * Tests that event types with dots and underscores that would map to the same channel name
     * are correctly isolated - subscribers only receive events matching their exact event type.
     *
     * This tests the potential collision scenario where:
     * - "my.channel" maps to channel "bitemporal_events_my_channel"
     * - "my_channel" also maps to channel "bitemporal_events_my_channel"
     *
     * Both subscriptions listen on the same PostgreSQL channel, but the subscription matching
     * uses the original event_type from the notification payload, ensuring correct isolation.
     */
    @Test
    void testDotUnderscoreChannelCollisionIsolation() throws Exception {
        logger.info("=== Testing dot/underscore channel collision isolation ===");

        peeGeeQManager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());
        peeGeeQManager.start();

        Class<Map<String, Object>> mapClass = (Class<Map<String, Object>>) (Class<?>) Map.class;
        eventStore = new PgBiTemporalEventStore<>(peeGeeQManager, mapClass, "test_events", new ObjectMapper());

        // Track received events for each subscription
        AtomicInteger dotSubscriberCount = new AtomicInteger(0);
        AtomicInteger underscoreSubscriberCount = new AtomicInteger(0);
        List<String> dotSubscriberEventTypes = new CopyOnWriteArrayList<>();
        List<String> underscoreSubscriberEventTypes = new CopyOnWriteArrayList<>();
        CountDownLatch allEventsLatch = new CountDownLatch(2); // Expect 2 events total (1 per subscriber)

        // Subscribe to "my.channel" (with dot)
        Future<Void> dotSubscription = eventStore.subscribeReactive("my.channel", message -> {
            String eventType = message.getPayload().getEventType();
            dotSubscriberCount.incrementAndGet();
            dotSubscriberEventTypes.add(eventType);
            logger.info("DOT subscriber received event type: {}", eventType);
            allEventsLatch.countDown();
            return CompletableFuture.completedFuture(null);
        });
        dotSubscription.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        // Subscribe to "my_channel" (with underscore)
        Future<Void> underscoreSubscription = eventStore.subscribeReactive("my_channel", message -> {
            String eventType = message.getPayload().getEventType();
            underscoreSubscriberCount.incrementAndGet();
            underscoreSubscriberEventTypes.add(eventType);
            logger.info("UNDERSCORE subscriber received event type: {}", eventType);
            allEventsLatch.countDown();
            return CompletableFuture.completedFuture(null);
        });
        underscoreSubscription.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        // Give subscriptions time to establish
        Thread.sleep(500);

        // Append event with dot notation
        Map<String, Object> dotPayload = Map.of("source", "dot");
        eventStore.append("my.channel", dotPayload, Instant.now()).get(10, TimeUnit.SECONDS);
        logger.info("Appended event with type 'my.channel'");

        // Append event with underscore notation
        Map<String, Object> underscorePayload = Map.of("source", "underscore");
        eventStore.append("my_channel", underscorePayload, Instant.now()).get(10, TimeUnit.SECONDS);
        logger.info("Appended event with type 'my_channel'");

        // Wait for notifications
        boolean allReceived = allEventsLatch.await(10, TimeUnit.SECONDS);
        assertTrue(allReceived, "Should receive both notifications");

        // Verify isolation: each subscriber should only receive their matching event type
        assertEquals(1, dotSubscriberCount.get(),
            "DOT subscriber should receive exactly 1 event");
        assertEquals(1, underscoreSubscriberCount.get(),
            "UNDERSCORE subscriber should receive exactly 1 event");

        assertTrue(dotSubscriberEventTypes.contains("my.channel"),
            "DOT subscriber should receive 'my.channel' event");
        assertFalse(dotSubscriberEventTypes.contains("my_channel"),
            "DOT subscriber should NOT receive 'my_channel' event");

        assertTrue(underscoreSubscriberEventTypes.contains("my_channel"),
            "UNDERSCORE subscriber should receive 'my_channel' event");
        assertFalse(underscoreSubscriberEventTypes.contains("my.channel"),
            "UNDERSCORE subscriber should NOT receive 'my.channel' event");

        eventStore.unsubscribe();

        logger.info("✅ Dot/underscore channel collision isolation test completed successfully");
    }

    @Test
    void testGetAsOfTransactionTime() throws Exception {
        logger.info("=== Testing getAsOfTransactionTime ===");

        peeGeeQManager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());
        peeGeeQManager.start();

        Class<Map<String, Object>> mapClass = (Class<Map<String, Object>>) (Class<?>) Map.class;
        eventStore = new PgBiTemporalEventStore<>(peeGeeQManager, mapClass, "test_events", new ObjectMapper());

        // Append an event
        Map<String, Object> payload = Map.of("asof", "test");
        BiTemporalEvent<Map<String, Object>> event = eventStore.append(
            "test.asof", payload, Instant.now()
        ).get(10, TimeUnit.SECONDS);

        // Query as of transaction time (slightly after the event was created)
        Instant queryTime = Instant.now();
        CompletableFuture<BiTemporalEvent<Map<String, Object>>> future = eventStore.getAsOfTransactionTime(
            event.getEventId(), queryTime
        );

        BiTemporalEvent<Map<String, Object>> result = future.get(10, TimeUnit.SECONDS);
        assertNotNull(result);
        assertEquals(event.getEventId(), result.getEventId());

        logger.info("✅ getAsOfTransactionTime test completed successfully");
    }

    @Test
    void testClearCachedPoolsAndInstancePools() throws Exception {
        logger.info("=== Testing clearCachedPools and clearInstancePools ===");

        peeGeeQManager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());
        peeGeeQManager.start();

        Class<Map<String, Object>> mapClass = (Class<Map<String, Object>>) (Class<?>) Map.class;
        eventStore = new PgBiTemporalEventStore<>(peeGeeQManager, mapClass, "test_events", new ObjectMapper());

        // Append an event to ensure pools are created
        Map<String, Object> payload = Map.of("pool", "test");
        eventStore.append("test.pool", payload, Instant.now()).get(10, TimeUnit.SECONDS);

        // Test clearInstancePools
        eventStore.clearInstancePools();

        // Append another event to verify pools are recreated
        Map<String, Object> payload2 = Map.of("pool", "test2");
        BiTemporalEvent<Map<String, Object>> event = eventStore.append(
            "test.pool2", payload2, Instant.now()
        ).get(10, TimeUnit.SECONDS);
        assertNotNull(event);

        // Test clearCachedPools (static method)
        PgBiTemporalEventStore.clearCachedPools();

        logger.info("✅ clearCachedPools and clearInstancePools test completed successfully");
    }

    @Test
    void testCloseSharedVertx() throws Exception {
        logger.info("=== Testing closeSharedVertx ===");

        peeGeeQManager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());
        peeGeeQManager.start();

        Class<Map<String, Object>> mapClass = (Class<Map<String, Object>>) (Class<?>) Map.class;
        eventStore = new PgBiTemporalEventStore<>(peeGeeQManager, mapClass, "test_events", new ObjectMapper());

        // Append an event to ensure Vertx is created
        Map<String, Object> payload = Map.of("vertx", "test");
        eventStore.append("test.vertx", payload, Instant.now()).get(10, TimeUnit.SECONDS);

        // Close the event store first
        eventStore.close();
        eventStore = null;

        // Test closeSharedVertx (static method)
        PgBiTemporalEventStore.closeSharedVertx();

        logger.info("✅ closeSharedVertx test completed successfully");
    }

    // ==================================================================================
    // SUBSCRIPTION BEHAVIOR TESTS
    // These tests validate the subscription model as documented in
    // PEEGEEQ_BITEMPORAL_SUBSCRIPTIONS.md
    // ==================================================================================

    /**
     * Test 1: Type-specific subscription only receives matching events.
     *
     * Subscribe to 'order.created', append 3 different event types,
     * verify subscriber receives ONLY the matching event.
     */
    @Test
    void testTypeSpecificSubscriptionOnlyReceivesMatchingEvents() throws Exception {
        logger.info("=== Test 1: Type-specific subscription only receives matching events ===");

        peeGeeQManager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());
        peeGeeQManager.start();

        Class<Map<String, Object>> mapClass = (Class<Map<String, Object>>) (Class<?>) Map.class;
        eventStore = new PgBiTemporalEventStore<>(peeGeeQManager, mapClass, "test_events", new ObjectMapper());

        List<String> receivedEventTypes = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        // Subscribe to order.created only
        Future<Void> subscription = eventStore.subscribeReactive("order.created", message -> {
            String eventType = message.getPayload().getEventType();
            receivedEventTypes.add(eventType);
            logger.info("Received event type: {}", eventType);
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });
        subscription.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        Thread.sleep(500);

        // Append 3 different event types
        eventStore.append("order.created", Map.of("test", "1"), Instant.now()).get(10, TimeUnit.SECONDS);
        eventStore.append("order.shipped", Map.of("test", "2"), Instant.now()).get(10, TimeUnit.SECONDS);
        eventStore.append("payment.received", Map.of("test", "3"), Instant.now()).get(10, TimeUnit.SECONDS);

        // Wait for the expected event
        boolean received = latch.await(10, TimeUnit.SECONDS);
        assertTrue(received, "Should receive the matching event");

        // Give extra time for any unexpected events
        Thread.sleep(1000);

        // Verify only 1 event received
        assertEquals(1, receivedEventTypes.size(), "Should receive exactly 1 event");
        assertEquals("order.created", receivedEventTypes.get(0), "Should receive order.created");

        eventStore.unsubscribe();
        logger.info("✅ Test 1 completed");
    }

    /**
     * Test 2: General subscription (null event type) receives all events.
     */
    @Test
    void testGeneralSubscriptionReceivesAllEvents() throws Exception {
        logger.info("=== Test 2: General subscription receives all events ===");

        peeGeeQManager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());
        peeGeeQManager.start();

        Class<Map<String, Object>> mapClass = (Class<Map<String, Object>>) (Class<?>) Map.class;
        eventStore = new PgBiTemporalEventStore<>(peeGeeQManager, mapClass, "test_events", new ObjectMapper());

        List<String> receivedEventTypes = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(3);

        // Subscribe with null event type (all events)
        Future<Void> subscription = eventStore.subscribeReactive(null, message -> {
            String eventType = message.getPayload().getEventType();
            receivedEventTypes.add(eventType);
            logger.info("Received event type: {}", eventType);
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });
        subscription.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        Thread.sleep(500);

        // Append 3 different event types
        eventStore.append("order.created", Map.of("test", "1"), Instant.now()).get(10, TimeUnit.SECONDS);
        eventStore.append("order.shipped", Map.of("test", "2"), Instant.now()).get(10, TimeUnit.SECONDS);
        eventStore.append("payment.received", Map.of("test", "3"), Instant.now()).get(10, TimeUnit.SECONDS);

        // Wait for all events
        boolean received = latch.await(10, TimeUnit.SECONDS);
        assertTrue(received, "Should receive all 3 events");

        assertEquals(3, receivedEventTypes.size(), "Should receive exactly 3 events");
        assertTrue(receivedEventTypes.contains("order.created"));
        assertTrue(receivedEventTypes.contains("order.shipped"));
        assertTrue(receivedEventTypes.contains("payment.received"));

        eventStore.unsubscribe();
        logger.info("✅ Test 2 completed");
    }

    /**
     * Test 3: Multiple type-specific subscriptions receive only their types.
     */
    @Test
    void testMultipleTypeSpecificSubscriptionsIsolation() throws Exception {
        logger.info("=== Test 3: Multiple type-specific subscriptions isolation ===");

        peeGeeQManager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());
        peeGeeQManager.start();

        Class<Map<String, Object>> mapClass = (Class<Map<String, Object>>) (Class<?>) Map.class;
        eventStore = new PgBiTemporalEventStore<>(peeGeeQManager, mapClass, "test_events", new ObjectMapper());

        List<String> orderCreatedEvents = new CopyOnWriteArrayList<>();
        List<String> paymentReceivedEvents = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(2);

        // Subscribe to order.created
        Future<Void> sub1 = eventStore.subscribeReactive("order.created", message -> {
            orderCreatedEvents.add(message.getPayload().getEventType());
            logger.info("order.created subscriber received: {}", message.getPayload().getEventType());
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });
        sub1.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        // Subscribe to payment.received
        Future<Void> sub2 = eventStore.subscribeReactive("payment.received", message -> {
            paymentReceivedEvents.add(message.getPayload().getEventType());
            logger.info("payment.received subscriber received: {}", message.getPayload().getEventType());
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });
        sub2.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        Thread.sleep(500);

        // Append 3 different event types
        eventStore.append("order.created", Map.of("test", "1"), Instant.now()).get(10, TimeUnit.SECONDS);
        eventStore.append("order.shipped", Map.of("test", "2"), Instant.now()).get(10, TimeUnit.SECONDS);
        eventStore.append("payment.received", Map.of("test", "3"), Instant.now()).get(10, TimeUnit.SECONDS);

        // Wait for expected events
        boolean received = latch.await(10, TimeUnit.SECONDS);
        assertTrue(received, "Should receive both expected events");

        // Give extra time for any unexpected events
        Thread.sleep(1000);

        // Verify isolation
        assertEquals(1, orderCreatedEvents.size(), "order.created subscriber should receive 1 event");
        assertEquals("order.created", orderCreatedEvents.get(0));

        assertEquals(1, paymentReceivedEvents.size(), "payment.received subscriber should receive 1 event");
        assertEquals("payment.received", paymentReceivedEvents.get(0));

        eventStore.unsubscribe();
        logger.info("✅ Test 3 completed");
    }

    /**
     * Test 5: No duplicate events - subscriber receives exactly 1 event per append.
     */
    @Test
    void testNoDuplicateEvents() throws Exception {
        logger.info("=== Test 5: No duplicate events ===");

        peeGeeQManager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());
        peeGeeQManager.start();

        Class<Map<String, Object>> mapClass = (Class<Map<String, Object>>) (Class<?>) Map.class;
        eventStore = new PgBiTemporalEventStore<>(peeGeeQManager, mapClass, "test_events", new ObjectMapper());

        AtomicInteger eventCount = new AtomicInteger(0);
        List<String> receivedEventIds = new CopyOnWriteArrayList<>();

        // Subscribe to test.event
        Future<Void> subscription = eventStore.subscribeReactive("test.event", message -> {
            eventCount.incrementAndGet();
            receivedEventIds.add(message.getPayload().getEventId());
            logger.info("Received event #{}: {}", eventCount.get(), message.getPayload().getEventId());
            return CompletableFuture.completedFuture(null);
        });
        subscription.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        Thread.sleep(500);

        // Append 1 event
        BiTemporalEvent<Map<String, Object>> appendedEvent = eventStore.append(
            "test.event", Map.of("test", "no-duplicates"), Instant.now()
        ).get(10, TimeUnit.SECONDS);
        String expectedEventId = appendedEvent.getEventId();

        // Wait for event and give extra time for any duplicates
        Thread.sleep(2000);

        // Verify exactly 1 event received
        assertEquals(1, eventCount.get(), "Should receive exactly 1 event (not duplicated)");
        assertEquals(1, receivedEventIds.size());
        assertEquals(expectedEventId, receivedEventIds.get(0));

        eventStore.unsubscribe();
        logger.info("✅ Test 5 completed");
    }

    // ==================================================================================
    // WILDCARD SUBSCRIPTION TESTS
    // ==================================================================================

    /**
     * Test 6: Trailing wildcard 'order.*' matches order.created, order.shipped but not payment.received.
     */
    @Test
    void testTrailingWildcard() throws Exception {
        logger.info("=== Test 6: Trailing wildcard order.* ===");

        peeGeeQManager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());
        peeGeeQManager.start();

        Class<Map<String, Object>> mapClass = (Class<Map<String, Object>>) (Class<?>) Map.class;
        eventStore = new PgBiTemporalEventStore<>(peeGeeQManager, mapClass, "test_events", new ObjectMapper());

        List<String> receivedEventTypes = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(2);

        // Subscribe to order.*
        Future<Void> subscription = eventStore.subscribeReactive("order.*", message -> {
            String eventType = message.getPayload().getEventType();
            receivedEventTypes.add(eventType);
            logger.info("order.* subscriber received: {}", eventType);
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });
        subscription.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        Thread.sleep(500);

        // Append events
        eventStore.append("order.created", Map.of("test", "1"), Instant.now()).get(10, TimeUnit.SECONDS);
        eventStore.append("order.shipped", Map.of("test", "2"), Instant.now()).get(10, TimeUnit.SECONDS);
        eventStore.append("payment.received", Map.of("test", "3"), Instant.now()).get(10, TimeUnit.SECONDS);

        // Wait for expected events
        boolean received = latch.await(10, TimeUnit.SECONDS);
        assertTrue(received, "Should receive 2 matching events");

        // Give extra time for any unexpected events
        Thread.sleep(1000);

        assertEquals(2, receivedEventTypes.size(), "Should receive exactly 2 events");
        assertTrue(receivedEventTypes.contains("order.created"));
        assertTrue(receivedEventTypes.contains("order.shipped"));
        assertFalse(receivedEventTypes.contains("payment.received"));

        eventStore.unsubscribe();
        logger.info("✅ Test 6 completed");
    }

    /**
     * Test 7: Leading wildcard '*.created' matches order.created, payment.created but not order.shipped.
     */
    @Test
    void testLeadingWildcard() throws Exception {
        logger.info("=== Test 7: Leading wildcard *.created ===");

        peeGeeQManager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());
        peeGeeQManager.start();

        Class<Map<String, Object>> mapClass = (Class<Map<String, Object>>) (Class<?>) Map.class;
        eventStore = new PgBiTemporalEventStore<>(peeGeeQManager, mapClass, "test_events", new ObjectMapper());

        List<String> receivedEventTypes = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(2);

        // Subscribe to *.created
        Future<Void> subscription = eventStore.subscribeReactive("*.created", message -> {
            String eventType = message.getPayload().getEventType();
            receivedEventTypes.add(eventType);
            logger.info("*.created subscriber received: {}", eventType);
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });
        subscription.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        Thread.sleep(500);

        // Append events
        eventStore.append("order.created", Map.of("test", "1"), Instant.now()).get(10, TimeUnit.SECONDS);
        eventStore.append("payment.created", Map.of("test", "2"), Instant.now()).get(10, TimeUnit.SECONDS);
        eventStore.append("order.shipped", Map.of("test", "3"), Instant.now()).get(10, TimeUnit.SECONDS);

        // Wait for expected events
        boolean received = latch.await(10, TimeUnit.SECONDS);
        assertTrue(received, "Should receive 2 matching events");

        // Give extra time for any unexpected events
        Thread.sleep(1000);

        assertEquals(2, receivedEventTypes.size(), "Should receive exactly 2 events");
        assertTrue(receivedEventTypes.contains("order.created"));
        assertTrue(receivedEventTypes.contains("payment.created"));
        assertFalse(receivedEventTypes.contains("order.shipped"));

        eventStore.unsubscribe();
        logger.info("✅ Test 7 completed");
    }

    /**
     * Test 8: Middle wildcard 'order.*.completed' matches order.payment.completed, order.shipping.completed.
     */
    @Test
    void testMiddleWildcard() throws Exception {
        logger.info("=== Test 8: Middle wildcard order.*.completed ===");

        peeGeeQManager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());
        peeGeeQManager.start();

        Class<Map<String, Object>> mapClass = (Class<Map<String, Object>>) (Class<?>) Map.class;
        eventStore = new PgBiTemporalEventStore<>(peeGeeQManager, mapClass, "test_events", new ObjectMapper());

        List<String> receivedEventTypes = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(2);

        // Subscribe to order.*.completed
        Future<Void> subscription = eventStore.subscribeReactive("order.*.completed", message -> {
            String eventType = message.getPayload().getEventType();
            receivedEventTypes.add(eventType);
            logger.info("order.*.completed subscriber received: {}", eventType);
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });
        subscription.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        Thread.sleep(500);

        // Append events
        eventStore.append("order.payment.completed", Map.of("test", "1"), Instant.now()).get(10, TimeUnit.SECONDS);
        eventStore.append("order.shipping.completed", Map.of("test", "2"), Instant.now()).get(10, TimeUnit.SECONDS);
        eventStore.append("order.created", Map.of("test", "3"), Instant.now()).get(10, TimeUnit.SECONDS);

        // Wait for expected events
        boolean received = latch.await(10, TimeUnit.SECONDS);
        assertTrue(received, "Should receive 2 matching events");

        // Give extra time for any unexpected events
        Thread.sleep(1000);

        assertEquals(2, receivedEventTypes.size(), "Should receive exactly 2 events");
        assertTrue(receivedEventTypes.contains("order.payment.completed"));
        assertTrue(receivedEventTypes.contains("order.shipping.completed"));
        assertFalse(receivedEventTypes.contains("order.created"));

        eventStore.unsubscribe();
        logger.info("✅ Test 8 completed");
    }

    /**
     * Test 9: Wildcard matches whole segments only.
     * 'order.*' should match 'order.created' but NOT 'orders.created' or 'order'.
     */
    @Test
    void testWildcardMatchesWholeSegmentsOnly() throws Exception {
        logger.info("=== Test 9: Wildcard matches whole segments only ===");

        peeGeeQManager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());
        peeGeeQManager.start();

        Class<Map<String, Object>> mapClass = (Class<Map<String, Object>>) (Class<?>) Map.class;
        eventStore = new PgBiTemporalEventStore<>(peeGeeQManager, mapClass, "test_events", new ObjectMapper());

        List<String> receivedEventTypes = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        // Subscribe to order.*
        Future<Void> subscription = eventStore.subscribeReactive("order.*", message -> {
            String eventType = message.getPayload().getEventType();
            receivedEventTypes.add(eventType);
            logger.info("order.* subscriber received: {}", eventType);
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });
        subscription.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        Thread.sleep(500);

        // Append events - only order.created should match
        eventStore.append("order.created", Map.of("test", "1"), Instant.now()).get(10, TimeUnit.SECONDS);
        eventStore.append("orders.created", Map.of("test", "2"), Instant.now()).get(10, TimeUnit.SECONDS);
        eventStore.append("order", Map.of("test", "3"), Instant.now()).get(10, TimeUnit.SECONDS);

        // Wait for expected event
        boolean received = latch.await(10, TimeUnit.SECONDS);
        assertTrue(received, "Should receive 1 matching event");

        // Give extra time for any unexpected events
        Thread.sleep(1000);

        assertEquals(1, receivedEventTypes.size(), "Should receive exactly 1 event");
        assertEquals("order.created", receivedEventTypes.get(0));

        eventStore.unsubscribe();
        logger.info("✅ Test 9 completed");
    }

    /**
     * Test 10: Multiple wildcards '*.order.*' matches foo.order.bar, abc.order.xyz but not order.created.
     */
    @Test
    void testMultipleWildcards() throws Exception {
        logger.info("=== Test 10: Multiple wildcards *.order.* ===");

        peeGeeQManager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());
        peeGeeQManager.start();

        Class<Map<String, Object>> mapClass = (Class<Map<String, Object>>) (Class<?>) Map.class;
        eventStore = new PgBiTemporalEventStore<>(peeGeeQManager, mapClass, "test_events", new ObjectMapper());

        List<String> receivedEventTypes = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(2);

        // Subscribe to *.order.*
        Future<Void> subscription = eventStore.subscribeReactive("*.order.*", message -> {
            String eventType = message.getPayload().getEventType();
            receivedEventTypes.add(eventType);
            logger.info("*.order.* subscriber received: {}", eventType);
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });
        subscription.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        Thread.sleep(500);

        // Append events
        eventStore.append("foo.order.bar", Map.of("test", "1"), Instant.now()).get(10, TimeUnit.SECONDS);
        eventStore.append("abc.order.xyz", Map.of("test", "2"), Instant.now()).get(10, TimeUnit.SECONDS);
        eventStore.append("order.created", Map.of("test", "3"), Instant.now()).get(10, TimeUnit.SECONDS);

        // Wait for expected events
        boolean received = latch.await(10, TimeUnit.SECONDS);
        assertTrue(received, "Should receive 2 matching events");

        // Give extra time for any unexpected events
        Thread.sleep(1000);

        assertEquals(2, receivedEventTypes.size(), "Should receive exactly 2 events");
        assertTrue(receivedEventTypes.contains("foo.order.bar"));
        assertTrue(receivedEventTypes.contains("abc.order.xyz"));
        assertFalse(receivedEventTypes.contains("order.created"));

        eventStore.unsubscribe();
        logger.info("✅ Test 10 completed");
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
