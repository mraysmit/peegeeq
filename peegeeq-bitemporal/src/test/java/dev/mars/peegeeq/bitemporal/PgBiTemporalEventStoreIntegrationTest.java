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
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.TransactionPropagation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test to verify ReactiveNotificationHandler integration with PgBiTemporalEventStore.
 * Tests the complete integration following PGQ coding principles.
 *
 * <p><b>Implementation notes (reactive migration):</b>
 * <ul>
 *   <li>All blocking {@code await()} helpers removed — tests use {@code .compose()} chains
 *       with {@code VertxTestContext} for async coordination.</li>
 *   <li>All {@code .toCompletionStage().toCompletableFuture().get()} bridges removed.</li>
 *   <li>All {@code CountDownLatch} patterns replaced with compose chains and promise-based completion.</li>
 * </ul>
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
class PgBiTemporalEventStoreIntegrationTest {
    private static final Logger logger = LoggerFactory.getLogger(PgBiTemporalEventStoreIntegrationTest.class);

    @SuppressWarnings("unchecked")
    private static Class<Map<String, Object>> mapClass() {
        return (Class<Map<String, Object>>) (Class<?>) Map.class;
    }

    @Container
    static PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE);
        container.withDatabaseName("peegeeq_integration_test");
        container.withUsername("peegeeq_test");
        container.withPassword("peegeeq_test");
        container.withSharedMemorySize(256 * 1024 * 1024L);
        container.withReuse(false);
        return container;
    }

    private PeeGeeQManager peeGeeQManager;
    private PgBiTemporalEventStore<Map<String, Object>> eventStore;
    private final Map<String, String> originalProperties = new HashMap<>();

    @BeforeEach
    void setUp(VertxTestContext testContext) throws Exception {
        logger.info("Setting up ReactiveNotificationHandler integration test...");
        configureSystemPropertiesForContainer(postgres);
        createBiTemporalEventLogTable();
        logger.info("✓ ReactiveNotificationHandler integration test setup completed");
        testContext.completeNow();
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
                    causation_id VARCHAR(255),
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
            
            // Add notification trigger with schema-qualified channel names (matching V013 migration)
            stmt.execute("""
                CREATE OR REPLACE FUNCTION notify_test_events() RETURNS TRIGGER AS $$
                DECLARE
                    schema_name TEXT;
                    table_name TEXT;
                    channel_prefix TEXT;
                    type_channel_name TEXT;
                    type_base_name TEXT;
                    hash_suffix TEXT;
                BEGIN
                    -- Get schema and table from trigger context
                    schema_name := TG_TABLE_SCHEMA;
                    table_name := TG_TABLE_NAME;
                    
                    -- Build channel prefix: {schema}_bitemporal_events_{table}
                    channel_prefix := schema_name || '_bitemporal_events_' || table_name;
                    
                    -- Send general notification
                    PERFORM pg_notify(
                        channel_prefix,
                        json_build_object(
                            'event_id', NEW.event_id,
                            'event_type', NEW.event_type,
                            'aggregate_id', NEW.aggregate_id,
                            'correlation_id', NEW.correlation_id,
                            'causation_id', NEW.causation_id,
                            'is_correction', NEW.is_correction,
                            'transaction_time', extract(epoch from NEW.transaction_time)
                        )::text
                    );

                    -- Send type-specific notification with truncation support
                    type_base_name := channel_prefix || '_' || replace(NEW.event_type, '.', '_');
                    IF length(type_base_name) > 63 THEN
                        hash_suffix := '_' || substr(md5(type_base_name), 1, 8);
                        type_channel_name := substr(type_base_name, 1, 63 - length(hash_suffix)) || hash_suffix;
                    ELSE
                        type_channel_name := type_base_name;
                    END IF;
                    
                    PERFORM pg_notify(
                        type_channel_name,
                        json_build_object(
                            'event_id', NEW.event_id,
                            'event_type', NEW.event_type,
                            'aggregate_id', NEW.aggregate_id,
                            'correlation_id', NEW.correlation_id,
                            'causation_id', NEW.causation_id,
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
    private void configureSystemPropertiesForContainer(PostgreSQLContainer postgres) {
        setTestProperty("peegeeq.database.host", postgres.getHost());
        setTestProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        setTestProperty("peegeeq.database.name", postgres.getDatabaseName());
        setTestProperty("peegeeq.database.username", postgres.getUsername());
        setTestProperty("peegeeq.database.password", postgres.getPassword());
        setTestProperty("peegeeq.database.schema", "public");
        setTestProperty("peegeeq.database.ssl.enabled", "false");
        setTestProperty("peegeeq.metrics.enabled", "true");
        setTestProperty("peegeeq.health.enabled", "true");
        setTestProperty("peegeeq.migration.enabled", "true");
        setTestProperty("peegeeq.migration.auto-migrate", "true");
    }

    @Test
    void testReactiveNotificationHandlerIntegration(VertxTestContext testContext) throws Exception {
        logger.info("=== Testing ReactiveNotificationHandler Integration ===");

        String eventType = "test_event";
        String aggregateId = "test-aggregate-123";
        Map<String, Object> payload = Map.of("message", "integration test", "timestamp", Instant.now().toString());
        Promise<BiTemporalEvent<Map<String, Object>>> notificationPromise = Promise.promise();

        peeGeeQManager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());

        peeGeeQManager.start()
            .compose(v -> {
                logger.info("PeeGeeQ Manager started successfully");
                eventStore = new PgBiTemporalEventStore<>(peeGeeQManager, mapClass(), "test_events", new ObjectMapper());
                logger.info("PgBiTemporalEventStore created successfully");
                return eventStore.subscribe(eventType, message -> {
                    BiTemporalEvent<Map<String, Object>> event = message.getPayload();
                    logger.info("Received notification for event: {}", event.getEventId());
                    notificationPromise.tryComplete(event);
                    return Future.<Void>succeededFuture();
                });
            })
            .compose(v -> eventStore.appendBuilder().eventType(eventType).payload(payload).validTime(Instant.now())
                .headers(Map.of("source", "integration-test"))
                .correlationId("test-correlation-" + System.currentTimeMillis())
                .causationId(null).aggregateId(aggregateId).execute())
            .compose(appended -> {
                logger.info("Event appended: {}", appended.getEventId());
                return notificationPromise.future().map(notification -> Map.entry(appended, notification));
            })
            .onSuccess(result -> testContext.verify(() -> {
                BiTemporalEvent<Map<String, Object>> appended = result.getKey();
                BiTemporalEvent<Map<String, Object>> notification = result.getValue();
                assertNotNull(notification, "Notification event should not be null");
                assertEquals(appended.getEventId(), notification.getEventId(), "Event ID should match");
                assertEquals(eventType, notification.getEventType(), "Event type should match");
                assertEquals(aggregateId, notification.getAggregateId(), "Aggregate ID should match");
                assertEquals(payload, notification.getPayload(), "Payload should match");
                logger.info("ReactiveNotificationHandler integration test completed successfully");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    @Test
    void testAppendWithTransactionOverloads(VertxTestContext testContext) throws Exception {
        logger.info("=== Testing appendWithTransaction overloads ===");

        peeGeeQManager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());

        peeGeeQManager.start()
            .compose(v -> {
                eventStore = new PgBiTemporalEventStore<>(peeGeeQManager, mapClass(), "test_events", new ObjectMapper());
                Map<String, Object> payload1 = Map.of("test", "overload1");
                return eventStore.appendWithTransaction(
                    "test.overload1", payload1, Instant.now(), TransactionPropagation.CONTEXT);
            })
            .compose(event1 -> {
                testContext.verify(() -> {
                    assertNotNull(event1);
                    assertEquals("test.overload1", event1.getEventType());
                });
                Map<String, Object> payload2 = Map.of("test", "overload2");
                return eventStore.appendWithTransaction(
                    "test.overload2", payload2, Instant.now(), Map.of("header", "value"), TransactionPropagation.CONTEXT);
            })
            .compose(event2 -> {
                testContext.verify(() -> {
                    assertNotNull(event2);
                    assertEquals("test.overload2", event2.getEventType());
                });
                Map<String, Object> payload3 = Map.of("test", "overload3");
                return eventStore.appendWithTransaction(
                    "test.overload3", payload3, Instant.now(), Map.of(), "corr-123", TransactionPropagation.CONTEXT);
            })
            .compose(event3 -> {
                testContext.verify(() -> {
                    assertNotNull(event3);
                    assertEquals("corr-123", event3.getCorrelationId());
                });
                Map<String, Object> payload4 = Map.of("test", "overload4");
                return eventStore.appendWithTransaction(
                    "test.overload4", payload4, Instant.now(), Map.of(), "corr-456", null, "agg-789", TransactionPropagation.CONTEXT);
            })
            .onSuccess(event4 -> testContext.verify(() -> {
                assertNotNull(event4);
                assertEquals("agg-789", event4.getAggregateId());
                logger.info("appendWithTransaction overloads test completed successfully");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    @Test
    void testappend(VertxTestContext testContext) throws Exception {
        logger.info("=== Testing append ===");

        peeGeeQManager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());

        peeGeeQManager.start()
            .compose(v -> {
                eventStore = new PgBiTemporalEventStore<>(peeGeeQManager, mapClass(), "test_events", new ObjectMapper());
                Map<String, Object> payload = Map.of("reactive", "test");
                return eventStore.append("test.reactive", payload, Instant.now());
            })
            .onSuccess(event -> testContext.verify(() -> {
                assertNotNull(event);
                assertEquals("test.reactive", event.getEventType());
                logger.info("append test completed successfully");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    @Test
    void testSubscribeAndUnsubscribe(VertxTestContext testContext) throws Exception {
        logger.info("=== Testing subscribe and unsubscribe ===");

        Promise<BiTemporalEvent<Map<String, Object>>> notificationPromise = Promise.promise();

        peeGeeQManager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());

        peeGeeQManager.start()
            .compose(v -> {
                eventStore = new PgBiTemporalEventStore<>(peeGeeQManager, mapClass(), "test_events", new ObjectMapper());
                return eventStore.subscribe("test.subscribe", message -> {
                    notificationPromise.tryComplete(message.getPayload());
                    return Future.<Void>succeededFuture();
                });
            })
            .compose(v -> eventStore.appendBuilder().eventType("test.subscribe")
                .payload(Map.of("subscribe", "test")).validTime(Instant.now()).execute())
            .compose(v -> notificationPromise.future())
            .onSuccess(received -> testContext.verify(() -> {
                assertNotNull(received);
                eventStore.unsubscribe();
                logger.info("subscribe and unsubscribe test completed successfully");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
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
    void testDotUnderscoreChannelCollisionIsolation(VertxTestContext testContext) throws Exception {
        logger.info("=== Testing dot/underscore channel collision isolation ===");

        AtomicInteger dotSubscriberCount = new AtomicInteger(0);
        AtomicInteger underscoreSubscriberCount = new AtomicInteger(0);
        List<String> dotSubscriberEventTypes = new CopyOnWriteArrayList<>();
        List<String> underscoreSubscriberEventTypes = new CopyOnWriteArrayList<>();
        Promise<Void> bothSubscribersSatisfied = Promise.promise();

        peeGeeQManager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());

        peeGeeQManager.start()
            .compose(v -> {
                eventStore = new PgBiTemporalEventStore<>(peeGeeQManager, mapClass(), "test_events", new ObjectMapper());
                return eventStore.subscribe("my.channel", message -> {
                    String eventType = message.getPayload().getEventType();
                    int dotCount = dotSubscriberCount.incrementAndGet();
                    dotSubscriberEventTypes.add(eventType);
                    logger.info("DOT subscriber received event type: {}", eventType);
                    if (dotCount > 1) {
                        bothSubscribersSatisfied.tryFail("DOT subscriber received duplicate notifications");
                    } else if (dotSubscriberCount.get() == 1 && underscoreSubscriberCount.get() == 1) {
                        bothSubscribersSatisfied.tryComplete();
                    }
                    return Future.<Void>succeededFuture();
                });
            })
            .compose(v -> eventStore.subscribe("my_channel", message -> {
                String eventType = message.getPayload().getEventType();
                int underscoreCount = underscoreSubscriberCount.incrementAndGet();
                underscoreSubscriberEventTypes.add(eventType);
                logger.info("UNDERSCORE subscriber received event type: {}", eventType);
                if (underscoreCount > 1) {
                    bothSubscribersSatisfied.tryFail("UNDERSCORE subscriber received duplicate notifications");
                } else if (dotSubscriberCount.get() == 1 && underscoreSubscriberCount.get() == 1) {
                    bothSubscribersSatisfied.tryComplete();
                }
                return Future.<Void>succeededFuture();
            }))
            .compose(v -> {
                Map<String, Object> dotPayload = Map.of("source", "dot");
                return eventStore.appendBuilder().eventType("my.channel").payload(dotPayload).validTime(Instant.now()).execute();
            })
            .compose(v -> {
                logger.info("Appended event with type 'my.channel'");
                Map<String, Object> underscorePayload = Map.of("source", "underscore");
                return eventStore.appendBuilder().eventType("my_channel").payload(underscorePayload).validTime(Instant.now()).execute();
            })
            .compose(v -> {
                logger.info("Appended event with type 'my_channel'");
                return bothSubscribersSatisfied.future();
            })
            .onSuccess(v -> testContext.verify(() -> {
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
                logger.info("Dot/underscore channel collision isolation test completed successfully");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    @Test
    void testGetAsOfTransactionTime(VertxTestContext testContext) throws Exception {
        logger.info("=== Testing getAsOfTransactionTime ===");

        peeGeeQManager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());

        peeGeeQManager.start()
            .compose(v -> {
                eventStore = new PgBiTemporalEventStore<>(peeGeeQManager, mapClass(), "test_events", new ObjectMapper());
                Map<String, Object> payload = Map.of("asof", "test");
                return eventStore.appendBuilder().eventType("test.asof").payload(payload).validTime(Instant.now()).execute();
            })
            .compose(event -> {
                Instant queryTime = Instant.now();
                return eventStore.getAsOfTransactionTime(event.getEventId(), queryTime)
                    .map(result -> Map.entry(event, result));
            })
            .onSuccess(pair -> testContext.verify(() -> {
                assertNotNull(pair.getValue());
                assertEquals(pair.getKey().getEventId(), pair.getValue().getEventId());
                logger.info("getAsOfTransactionTime test completed successfully");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    @Test
    void testClearCachedPoolsAndInstancePools(VertxTestContext testContext) throws Exception {
        logger.info("=== Testing clearCachedPools and clearInstancePools ===");

        peeGeeQManager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());

        peeGeeQManager.start()
            .compose(v -> {
                eventStore = new PgBiTemporalEventStore<>(peeGeeQManager, mapClass(), "test_events", new ObjectMapper());
                Map<String, Object> payload = Map.of("pool", "test");
                return eventStore.appendBuilder().eventType("test.pool").payload(payload).validTime(Instant.now()).execute();
            })
            .compose(v -> eventStore.clearInstancePools())
            .compose(v -> {
                Map<String, Object> payload2 = Map.of("pool", "test2");
                return eventStore.appendBuilder().eventType("test.pool2").payload(payload2).validTime(Instant.now()).execute();
            })
            .onSuccess(event -> testContext.verify(() -> {
                assertNotNull(event);
                PgBiTemporalEventStore.clearCachedPools();
                logger.info("clearCachedPools and clearInstancePools test completed successfully");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
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
    void testTypeSpecificSubscriptionOnlyReceivesMatchingEvents(VertxTestContext testContext) throws Exception {
        logger.info("=== Test 1: Type-specific subscription only receives matching events ===");

        List<String> receivedEventTypes = new CopyOnWriteArrayList<>();
        Promise<Void> expectedNotifications = Promise.promise();

        peeGeeQManager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());

        peeGeeQManager.start()
            .compose(v -> {
                eventStore = new PgBiTemporalEventStore<>(peeGeeQManager, mapClass(), "test_events", new ObjectMapper());
                return eventStore.subscribe("order.created", message -> {
                    String eventType = message.getPayload().getEventType();
                    receivedEventTypes.add(eventType);
                    logger.info("Received event type: {}", eventType);
                    if (receivedEventTypes.size() == 1) {
                        expectedNotifications.tryComplete();
                    } else if (receivedEventTypes.size() > 1) {
                        expectedNotifications.tryFail("Received more type-specific notifications than expected");
                    }
                    return Future.<Void>succeededFuture();
                });
            })
            .compose(v -> eventStore.appendBuilder().eventType("order.created").payload(Map.of("test", "1")).validTime(Instant.now()).execute())
            .compose(v -> eventStore.appendBuilder().eventType("order.shipped").payload(Map.of("test", "2")).validTime(Instant.now()).execute())
            .compose(v -> eventStore.appendBuilder().eventType("payment.received").payload(Map.of("test", "3")).validTime(Instant.now()).execute())
            .compose(v -> expectedNotifications.future())
            .onSuccess(v -> testContext.verify(() -> {
                assertEquals(1, receivedEventTypes.size(), "Should receive exactly 1 event");
                assertEquals("order.created", receivedEventTypes.get(0), "Should receive order.created");
                eventStore.unsubscribe();
                logger.info("Test 1 completed");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    /**
     * Test 2: General subscription (null event type) receives all events.
     */
    @Test
    void testGeneralSubscriptionReceivesAllEvents(VertxTestContext testContext) throws Exception {
        logger.info("=== Test 2: General subscription receives all events ===");

        List<String> receivedEventTypes = new CopyOnWriteArrayList<>();
        Promise<Void> expectedNotifications = Promise.promise();

        peeGeeQManager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());

        peeGeeQManager.start()
            .compose(v -> {
                eventStore = new PgBiTemporalEventStore<>(peeGeeQManager, mapClass(), "test_events", new ObjectMapper());
                return eventStore.subscribe(null, message -> {
                    String eventType = message.getPayload().getEventType();
                    receivedEventTypes.add(eventType);
                    logger.info("Received event type: {}", eventType);
                    if (receivedEventTypes.size() == 3) {
                        expectedNotifications.tryComplete();
                    } else if (receivedEventTypes.size() > 3) {
                        expectedNotifications.tryFail("General subscription received more notifications than expected");
                    }
                    return Future.<Void>succeededFuture();
                });
            })
            .compose(v -> eventStore.appendBuilder().eventType("order.created").payload(Map.of("test", "1")).validTime(Instant.now()).execute())
            .compose(v -> eventStore.appendBuilder().eventType("order.shipped").payload(Map.of("test", "2")).validTime(Instant.now()).execute())
            .compose(v -> eventStore.appendBuilder().eventType("payment.received").payload(Map.of("test", "3")).validTime(Instant.now()).execute())
            .compose(v -> expectedNotifications.future())
            .onSuccess(v -> testContext.verify(() -> {
                assertEquals(3, receivedEventTypes.size(), "Should receive exactly 3 events");
                assertTrue(receivedEventTypes.contains("order.created"));
                assertTrue(receivedEventTypes.contains("order.shipped"));
                assertTrue(receivedEventTypes.contains("payment.received"));
                eventStore.unsubscribe();
                logger.info("Test 2 completed");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    /**
     * Test 3: Multiple type-specific subscriptions receive only their types.
     */
    @Test
    void testMultipleTypeSpecificSubscriptionsIsolation(VertxTestContext testContext) throws Exception {
        logger.info("=== Test 3: Multiple type-specific subscriptions isolation ===");

        List<String> orderCreatedEvents = new CopyOnWriteArrayList<>();
        List<String> paymentReceivedEvents = new CopyOnWriteArrayList<>();
        Promise<Void> bothSubscriptionsSatisfied = Promise.promise();

        peeGeeQManager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());

        peeGeeQManager.start()
            .compose(v -> {
                eventStore = new PgBiTemporalEventStore<>(peeGeeQManager, mapClass(), "test_events", new ObjectMapper());
                return eventStore.subscribe("order.created", message -> {
                    orderCreatedEvents.add(message.getPayload().getEventType());
                    logger.info("order.created subscriber received: {}", message.getPayload().getEventType());
                    if (orderCreatedEvents.size() > 1) {
                        bothSubscriptionsSatisfied.tryFail("order.created subscriber received duplicate notifications");
                    } else if (orderCreatedEvents.size() == 1 && paymentReceivedEvents.size() == 1) {
                        bothSubscriptionsSatisfied.tryComplete();
                    }
                    return Future.<Void>succeededFuture();
                });
            })
            .compose(v -> eventStore.subscribe("payment.received", message -> {
                paymentReceivedEvents.add(message.getPayload().getEventType());
                logger.info("payment.received subscriber received: {}", message.getPayload().getEventType());
                if (paymentReceivedEvents.size() > 1) {
                    bothSubscriptionsSatisfied.tryFail("payment.received subscriber received duplicate notifications");
                } else if (orderCreatedEvents.size() == 1 && paymentReceivedEvents.size() == 1) {
                    bothSubscriptionsSatisfied.tryComplete();
                }
                return Future.<Void>succeededFuture();
            }))
            .compose(v -> eventStore.appendBuilder().eventType("order.created").payload(Map.of("test", "1")).validTime(Instant.now()).execute())
            .compose(v -> eventStore.appendBuilder().eventType("order.shipped").payload(Map.of("test", "2")).validTime(Instant.now()).execute())
            .compose(v -> eventStore.appendBuilder().eventType("payment.received").payload(Map.of("test", "3")).validTime(Instant.now()).execute())
            .compose(v -> bothSubscriptionsSatisfied.future())
            .onSuccess(v -> testContext.verify(() -> {
                assertEquals(1, orderCreatedEvents.size(), "order.created subscriber should receive 1 event");
                assertEquals("order.created", orderCreatedEvents.get(0));
                assertEquals(1, paymentReceivedEvents.size(), "payment.received subscriber should receive 1 event");
                assertEquals("payment.received", paymentReceivedEvents.get(0));
                eventStore.unsubscribe();
                logger.info("Test 3 completed");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    /**
     * Test 5: No duplicate events - subscriber receives exactly 1 event per append.
     */
    @Test
    void testNoDuplicateEvents(VertxTestContext testContext) throws Exception {
        logger.info("=== Test 5: No duplicate events ===");

        AtomicInteger eventCount = new AtomicInteger(0);
        List<String> receivedEventIds = new CopyOnWriteArrayList<>();
        Promise<Void> expectedNotification = Promise.promise();

        peeGeeQManager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());

        peeGeeQManager.start()
            .compose(v -> {
                eventStore = new PgBiTemporalEventStore<>(peeGeeQManager, mapClass(), "test_events", new ObjectMapper());
                return eventStore.subscribe("test.event", message -> {
                    int currentCount = eventCount.incrementAndGet();
                    receivedEventIds.add(message.getPayload().getEventId());
                    logger.info("Received event #{}: {}", eventCount.get(), message.getPayload().getEventId());
                    if (currentCount == 1) {
                        expectedNotification.tryComplete();
                    } else {
                        expectedNotification.tryFail("Received duplicate notification for single append");
                    }
                    return Future.<Void>succeededFuture();
                });
            })
            .compose(v -> eventStore.appendBuilder().eventType("test.event")
                .payload(Map.of("test", "no-duplicates")).validTime(Instant.now()).execute())
            .compose(appended -> expectedNotification.future().map(x -> appended))
            .onSuccess(appended -> testContext.verify(() -> {
                assertEquals(1, eventCount.get(), "Should receive exactly 1 event (not duplicated)");
                assertEquals(1, receivedEventIds.size());
                assertEquals(appended.getEventId(), receivedEventIds.get(0));
                eventStore.unsubscribe();
                logger.info("Test 5 completed");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    // ==================================================================================
    // WILDCARD SUBSCRIPTION TESTS
    // ==================================================================================

    /**
     * Test 6: Trailing wildcard 'order.*' matches order.created, order.shipped but not payment.received.
     */
    @Test
    void testTrailingWildcard(VertxTestContext testContext) throws Exception {
        logger.info("=== Test 6: Trailing wildcard order.* ===");

        List<String> receivedEventTypes = new CopyOnWriteArrayList<>();
        Promise<Void> expectedNotifications = Promise.promise();

        peeGeeQManager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());

        peeGeeQManager.start()
            .compose(v -> {
                eventStore = new PgBiTemporalEventStore<>(peeGeeQManager, mapClass(), "test_events", new ObjectMapper());
                return eventStore.subscribe("order.*", message -> {
                    String eventType = message.getPayload().getEventType();
                    receivedEventTypes.add(eventType);
                    logger.info("order.* subscriber received: {}", eventType);
                    if (receivedEventTypes.size() == 2) {
                        expectedNotifications.tryComplete();
                    } else if (receivedEventTypes.size() > 2) {
                        expectedNotifications.tryFail("order.* subscriber received too many notifications");
                    }
                    return Future.<Void>succeededFuture();
                });
            })
            .compose(v -> eventStore.appendBuilder().eventType("order.created").payload(Map.of("test", "1")).validTime(Instant.now()).execute())
            .compose(v -> eventStore.appendBuilder().eventType("order.shipped").payload(Map.of("test", "2")).validTime(Instant.now()).execute())
            .compose(v -> eventStore.appendBuilder().eventType("payment.received").payload(Map.of("test", "3")).validTime(Instant.now()).execute())
            .compose(v -> expectedNotifications.future())
            .onSuccess(v -> testContext.verify(() -> {
                assertEquals(2, receivedEventTypes.size(), "Should receive exactly 2 events");
                assertTrue(receivedEventTypes.contains("order.created"));
                assertTrue(receivedEventTypes.contains("order.shipped"));
                assertFalse(receivedEventTypes.contains("payment.received"));
                eventStore.unsubscribe();
                logger.info("Test 6 completed");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    /**
     * Test 7: Leading wildcard '*.created' matches order.created, payment.created but not order.shipped.
     */
    @Test
    void testLeadingWildcard(VertxTestContext testContext) throws Exception {
        logger.info("=== Test 7: Leading wildcard *.created ===");

        List<String> receivedEventTypes = new CopyOnWriteArrayList<>();
        Promise<Void> expectedNotifications = Promise.promise();

        peeGeeQManager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());

        peeGeeQManager.start()
            .compose(v -> {
                eventStore = new PgBiTemporalEventStore<>(peeGeeQManager, mapClass(), "test_events", new ObjectMapper());
                return eventStore.subscribe("*.created", message -> {
                    String eventType = message.getPayload().getEventType();
                    receivedEventTypes.add(eventType);
                    logger.info("*.created subscriber received: {}", eventType);
                    if (receivedEventTypes.size() == 2) {
                        expectedNotifications.tryComplete();
                    } else if (receivedEventTypes.size() > 2) {
                        expectedNotifications.tryFail("*.created subscriber received too many notifications");
                    }
                    return Future.<Void>succeededFuture();
                });
            })
            .compose(v -> eventStore.appendBuilder().eventType("order.created").payload(Map.of("test", "1")).validTime(Instant.now()).execute())
            .compose(v -> eventStore.appendBuilder().eventType("payment.created").payload(Map.of("test", "2")).validTime(Instant.now()).execute())
            .compose(v -> eventStore.appendBuilder().eventType("order.shipped").payload(Map.of("test", "3")).validTime(Instant.now()).execute())
            .compose(v -> expectedNotifications.future())
            .onSuccess(v -> testContext.verify(() -> {
                assertEquals(2, receivedEventTypes.size(), "Should receive exactly 2 events");
                assertTrue(receivedEventTypes.contains("order.created"));
                assertTrue(receivedEventTypes.contains("payment.created"));
                assertFalse(receivedEventTypes.contains("order.shipped"));
                eventStore.unsubscribe();
                logger.info("Test 7 completed");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    /**
     * Test 8: Middle wildcard 'order.*.completed' matches order.payment.completed, order.shipping.completed.
     */
    @Test
    void testMiddleWildcard(VertxTestContext testContext) throws Exception {
        logger.info("=== Test 8: Middle wildcard order.*.completed ===");

        List<String> receivedEventTypes = new CopyOnWriteArrayList<>();
        Promise<Void> expectedNotifications = Promise.promise();

        peeGeeQManager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());

        peeGeeQManager.start()
            .compose(v -> {
                eventStore = new PgBiTemporalEventStore<>(peeGeeQManager, mapClass(), "test_events", new ObjectMapper());
                return eventStore.subscribe("order.*.completed", message -> {
                    String eventType = message.getPayload().getEventType();
                    receivedEventTypes.add(eventType);
                    logger.info("order.*.completed subscriber received: {}", eventType);
                    if (receivedEventTypes.size() == 2) {
                        expectedNotifications.tryComplete();
                    } else if (receivedEventTypes.size() > 2) {
                        expectedNotifications.tryFail("order.*.completed subscriber received too many notifications");
                    }
                    return Future.<Void>succeededFuture();
                });
            })
            .compose(v -> eventStore.appendBuilder().eventType("order.payment.completed").payload(Map.of("test", "1")).validTime(Instant.now()).execute())
            .compose(v -> eventStore.appendBuilder().eventType("order.shipping.completed").payload(Map.of("test", "2")).validTime(Instant.now()).execute())
            .compose(v -> eventStore.appendBuilder().eventType("order.created").payload(Map.of("test", "3")).validTime(Instant.now()).execute())
            .compose(v -> expectedNotifications.future())
            .onSuccess(v -> testContext.verify(() -> {
                assertEquals(2, receivedEventTypes.size(), "Should receive exactly 2 events");
                assertTrue(receivedEventTypes.contains("order.payment.completed"));
                assertTrue(receivedEventTypes.contains("order.shipping.completed"));
                assertFalse(receivedEventTypes.contains("order.created"));
                eventStore.unsubscribe();
                logger.info("Test 8 completed");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    /**
     * Test 9: Wildcard matches whole segments only.
     * 'order.*' should match 'order.created' but NOT 'orders.created' or 'order'.
     */
    @Test
    void testWildcardMatchesWholeSegmentsOnly(VertxTestContext testContext) throws Exception {
        logger.info("=== Test 9: Wildcard matches whole segments only ===");

        List<String> receivedEventTypes = new CopyOnWriteArrayList<>();
        Promise<Void> expectedNotifications = Promise.promise();

        peeGeeQManager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());

        peeGeeQManager.start()
            .compose(v -> {
                eventStore = new PgBiTemporalEventStore<>(peeGeeQManager, mapClass(), "test_events", new ObjectMapper());
                return eventStore.subscribe("order.*", message -> {
                    String eventType = message.getPayload().getEventType();
                    receivedEventTypes.add(eventType);
                    logger.info("order.* subscriber received: {}", eventType);
                    if (receivedEventTypes.size() == 1) {
                        expectedNotifications.tryComplete();
                    } else if (receivedEventTypes.size() > 1) {
                        expectedNotifications.tryFail("order.* subscriber received too many notifications");
                    }
                    return Future.<Void>succeededFuture();
                });
            })
            .compose(v -> eventStore.appendBuilder().eventType("order.created").payload(Map.of("test", "1")).validTime(Instant.now()).execute())
            .compose(v -> eventStore.appendBuilder().eventType("orders.created").payload(Map.of("test", "2")).validTime(Instant.now()).execute())
            .compose(v -> eventStore.appendBuilder().eventType("order").payload(Map.of("test", "3")).validTime(Instant.now()).execute())
            .compose(v -> expectedNotifications.future())
            .onSuccess(v -> testContext.verify(() -> {
                assertEquals(1, receivedEventTypes.size(), "Should receive exactly 1 event");
                assertEquals("order.created", receivedEventTypes.get(0));
                eventStore.unsubscribe();
                logger.info("Test 9 completed");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    /**
     * Test 10: Multiple wildcards '*.order.*' matches foo.order.bar, abc.order.xyz but not order.created.
     */
    @Test
    void testMultipleWildcards(VertxTestContext testContext) throws Exception {
        logger.info("=== Test 10: Multiple wildcards *.order.* ===");

        List<String> receivedEventTypes = new CopyOnWriteArrayList<>();
        Promise<Void> expectedNotifications = Promise.promise();

        peeGeeQManager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());

        peeGeeQManager.start()
            .compose(v -> {
                eventStore = new PgBiTemporalEventStore<>(peeGeeQManager, mapClass(), "test_events", new ObjectMapper());
                return eventStore.subscribe("*.order.*", message -> {
                    String eventType = message.getPayload().getEventType();
                    receivedEventTypes.add(eventType);
                    logger.info("*.order.* subscriber received: {}", eventType);
                    if (receivedEventTypes.size() == 2) {
                        expectedNotifications.tryComplete();
                    } else if (receivedEventTypes.size() > 2) {
                        expectedNotifications.tryFail("*.order.* subscriber received too many notifications");
                    }
                    return Future.<Void>succeededFuture();
                });
            })
            .compose(v -> eventStore.appendBuilder().eventType("foo.order.bar").payload(Map.of("test", "1")).validTime(Instant.now()).execute())
            .compose(v -> eventStore.appendBuilder().eventType("abc.order.xyz").payload(Map.of("test", "2")).validTime(Instant.now()).execute())
            .compose(v -> eventStore.appendBuilder().eventType("order.created").payload(Map.of("test", "3")).validTime(Instant.now()).execute())
            .compose(v -> expectedNotifications.future())
            .onSuccess(v -> testContext.verify(() -> {
                assertEquals(2, receivedEventTypes.size(), "Should receive exactly 2 events");
                assertTrue(receivedEventTypes.contains("foo.order.bar"));
                assertTrue(receivedEventTypes.contains("abc.order.xyz"));
                assertFalse(receivedEventTypes.contains("order.created"));
                eventStore.unsubscribe();
                logger.info("Test 10 completed");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        logger.info("Cleaning up integration test...");

        Future<Void> chain = Future.succeededFuture();

        if (eventStore != null) {
            try {
                eventStore.close();
            } catch (Exception e) {
                logger.warn("Error closing event store: {}", e.getMessage());
            }
        }

        if (peeGeeQManager != null) {
            chain = peeGeeQManager.closeReactive()
                .recover(err -> {
                    logger.warn("Error closing PeeGeeQManager: {}", err.getMessage());
                    return Future.succeededFuture();
                });
        }

        chain.onSuccess(v -> {
            restoreTestProperties();
            logger.info("Integration test cleanup completed");
            testContext.completeNow();
        }).onFailure(testContext::failNow);
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

}


