/*
 * Copyright (c) 2025 Cityline Ltd
 * All rights reserved.
 *
 * This software is the confidential and proprietary information of Cityline Ltd.
 * You shall not disclose such confidential information and shall use it only in
 * accordance with the terms of the license agreement you entered into with Cityline Ltd.
 */

package dev.mars.peegeeq.bitemporal;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.MessageHandler;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgBuilder;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import dev.mars.peegeeq.test.categories.TestCategories;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ReactiveNotificationHandler.
 * Following PGQ coding principles: use TestContainers for real infrastructure testing.
 * 
 * Tests cover:
 * - Immutable construction with real PostgreSQL connection
 * - Input validation with actual database operations
 * - Pure Vert.x 5.x Future patterns
 * - Resource management and cleanup
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
class ReactiveNotificationHandlerIntegrationTest {
    private static final Logger logger = LoggerFactory.getLogger(ReactiveNotificationHandlerIntegrationTest.class);

    @Container
    static PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer("postgres:15.13-alpine3.20");
        container.withDatabaseName("reactive_notification_test");
        container.withUsername("test_user");
        container.withPassword("test_password");
        container.withSharedMemorySize(256 * 1024 * 1024L);
        container.withReuse(false);
        return container;
    }

    private ObjectMapper objectMapper;
    private Function<String, Future<BiTemporalEvent<String>>> eventRetriever;
    private PgConnectOptions connectOptions;
    private ConcurrentMap<String, String> eventTypesById;

    @BeforeEach
    void setUp() {
        System.err.println("=== INTEGRATION TEST SETUP STARTED ===");
        System.err.flush();

        // Initialize database schema using centralized schema initializer
        logger.info("Creating bitemporal_event_log table using PeeGeeQTestSchemaInitializer...");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.BITEMPORAL);
        logger.info("bitemporal_event_log table created successfully");

        // Create connection options from TestContainers
        this.connectOptions = new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getFirstMappedPort())
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword());

        this.objectMapper = new ObjectMapper();
        this.eventTypesById = new ConcurrentHashMap<>();
        
        // Simple event retriever for testing - using established pattern
        this.eventRetriever = eventId -> {
            BiTemporalEvent<String> testEvent = new TestBiTemporalEvent(
                eventId, eventTypesById.getOrDefault(eventId, "test_event"), "test payload", java.time.Instant.now()
            );
            return Future.succeededFuture(testEvent);
        };

        System.err.println("=== INTEGRATION TEST SETUP COMPLETED ===");
        System.err.flush();
    }

    @Test
    @DisplayName("Constructor should succeed with valid parameters")
    void testConstructorSuccess(Vertx vertx, VertxTestContext testContext) {
        System.err.println("=== TEST: Constructor Success ===");
        System.err.flush();

        // Should not throw any exception
        assertDoesNotThrow(() -> {
            ReactiveNotificationHandler<String> handler = new ReactiveNotificationHandler<>(
                vertx, connectOptions, objectMapper, String.class, eventRetriever
            );
            assertNotNull(handler, "Handler should be created successfully");
            
            System.err.println("Constructor succeeded with valid parameters");
            System.err.flush();
            testContext.completeNow();
        });
    }

    @Test
    @DisplayName("Subscribe should validate eventType for SQL injection")
    void testSubscribeEventTypeValidation(Vertx vertx, VertxTestContext testContext) {
        System.err.println("=== TEST: EventType Validation ===");
        System.err.flush();

        ReactiveNotificationHandler<String> handler = new ReactiveNotificationHandler<>(
            vertx, connectOptions, objectMapper, String.class, eventRetriever
        );

        // Test invalid eventType with SQL injection attempt
        String maliciousEventType = "valid_type'; DROP TABLE events; --";
        
        MessageHandler<BiTemporalEvent<String>> messageHandler = message -> java.util.concurrent.CompletableFuture.completedFuture(null);
        
        handler.subscribe(maliciousEventType, null, messageHandler)
            .onComplete(testContext.failing(error -> {
                testContext.verify(() -> {
                    assertTrue(error instanceof IllegalArgumentException, 
                        "Should fail with IllegalArgumentException for malicious eventType");
                    assertTrue(error.getMessage().contains("Invalid eventType"), 
                        "Error message should mention invalid eventType");
                    
                    System.err.println("SQL injection attempt properly blocked: " + error.getMessage());
                    System.err.flush();
                });
                testContext.completeNow();
            }));
    }

    @Test
    @DisplayName("Subscribe should accept valid eventType")
    void testSubscribeValidEventType(Vertx vertx, VertxTestContext testContext) {
        System.err.println("=== TEST: Valid EventType ===");
        System.err.flush();

        ReactiveNotificationHandler<String> handler = new ReactiveNotificationHandler<>(
            vertx, connectOptions, objectMapper, String.class, eventRetriever
        );

        // Test valid eventType
        String validEventType = "user_created";
        
        MessageHandler<BiTemporalEvent<String>> messageHandler = message -> java.util.concurrent.CompletableFuture.completedFuture(null);
        
        // Since handler is not started, should fail with state error, not validation error
        handler.subscribe(validEventType, null, messageHandler)
            .onComplete(testContext.failing(error -> {
                testContext.verify(() -> {
                    assertTrue(error instanceof IllegalStateException,
                        "Should fail with IllegalStateException when handler is not active");
                    
                    System.err.println("Valid eventType accepted, failed with expected state error: " + error.getMessage());
                    System.err.flush();
                });
                testContext.completeNow();
            }));
    }

    @Test
    @DisplayName("Subscribe should accept null eventType for all-events subscription")
    void testSubscribeNullEventType(Vertx vertx, VertxTestContext testContext) {
        System.err.println("=== TEST: Null EventType (All Events) ===");
        System.err.flush();

        ReactiveNotificationHandler<String> handler = new ReactiveNotificationHandler<>(
            vertx, connectOptions, objectMapper, String.class, eventRetriever
        );

        MessageHandler<BiTemporalEvent<String>> messageHandler = message -> java.util.concurrent.CompletableFuture.completedFuture(null);
        
        // Test null eventType (should be allowed for "all events" subscription)
        handler.subscribe(null, null, messageHandler)
            .onComplete(testContext.failing(error -> {
                testContext.verify(() -> {
                    assertTrue(error instanceof IllegalStateException,
                        "Should fail with IllegalStateException when handler is not active");
                    
                    System.err.println("Null eventType accepted for all-events subscription: " + error.getMessage());
                    System.err.flush();
                });
                testContext.completeNow();
            }));
    }

    @Test
    @DisplayName("Handler lifecycle should work with real PostgreSQL connection")
    void testHandlerLifecycle(Vertx vertx, VertxTestContext testContext) {
        System.err.println("=== TEST: Handler Lifecycle with Real PostgreSQL ===");
        System.err.flush();

        ReactiveNotificationHandler<String> handler = new ReactiveNotificationHandler<>(
            vertx, connectOptions, objectMapper, String.class, eventRetriever
        );

        // Test start -> stop lifecycle
        handler.start()
            .compose(v -> {
                System.err.println("Handler started successfully");
                System.err.flush();
                return handler.stop();
            })
            .onSuccess(v -> {
                System.err.println("Handler stopped successfully");
                System.err.flush();
                testContext.completeNow();
            })
            .onFailure(error -> {
                System.err.println("Handler lifecycle failed: " + error.getMessage());
                System.err.flush();
                testContext.failNow(error);
            });
    }

    @Test
    @DisplayName("Exact event-type subscription should receive trigger notifications")
    void testExactSubscriptionReceivesNotification(Vertx vertx, VertxTestContext testContext) {
        ReactiveNotificationHandler<String> handler = new ReactiveNotificationHandler<>(
            vertx, connectOptions, objectMapper, String.class, eventRetriever
        );

        String eventType = "order.created";
        String aggregateId = "agg-exact-1";

        MessageHandler<BiTemporalEvent<String>> messageHandler = message -> {
            testContext.verify(() -> {
                assertEquals(eventType, message.getPayload().getEventType());
                assertNotNull(message.getPayload().getEventId());
            });
            handler.stop().onComplete(ar -> {
                if (ar.failed()) {
                    testContext.failNow(ar.cause());
                } else {
                    testContext.completeNow();
                }
            });
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        };

        handler.start()
            .compose(v -> handler.subscribe(eventType, null, messageHandler))
            .compose(v -> insertBiTemporalEvent(vertx, eventType, aggregateId))
            .onFailure(error -> handler.stop().onComplete(ar -> testContext.failNow(error)));
    }

    @Test
    @DisplayName("Wildcard subscription should receive notifications via general channel")
    void testWildcardSubscriptionReceivesNotification(Vertx vertx, VertxTestContext testContext) {
        ReactiveNotificationHandler<String> handler = new ReactiveNotificationHandler<>(
            vertx, connectOptions, objectMapper, String.class, eventRetriever
        );

        MessageHandler<BiTemporalEvent<String>> messageHandler = message -> {
            testContext.verify(() -> {
                assertEquals("order.updated", message.getPayload().getEventType());
            });
            handler.stop().onComplete(ar -> {
                if (ar.failed()) {
                    testContext.failNow(ar.cause());
                } else {
                    testContext.completeNow();
                }
            });
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        };

        handler.start()
            .compose(v -> handler.subscribe("order.*", null, messageHandler))
            .compose(v -> insertBiTemporalEvent(vertx, "order.updated", "agg-wildcard-1"))
            .onFailure(error -> handler.stop().onComplete(ar -> testContext.failNow(error)));
    }

    @Test
    @DisplayName("Exact subscription should not receive different event types")
    void testExactSubscriptionDoesNotReceiveDifferentType(Vertx vertx, VertxTestContext testContext) {
        ReactiveNotificationHandler<String> handler = new ReactiveNotificationHandler<>(
            vertx, connectOptions, objectMapper, String.class, eventRetriever
        );

        AtomicBoolean received = new AtomicBoolean(false);
        MessageHandler<BiTemporalEvent<String>> messageHandler = message -> {
            received.set(true);
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        };

        handler.start()
            .compose(v -> handler.subscribe("order.created", null, messageHandler))
            .compose(v -> insertBiTemporalEvent(vertx, "payment.received", "agg-nomatch-1"))
            .compose(v -> waitForNoNotification(vertx, received, 1200))
            .compose(v -> {
                testContext.verify(() -> assertFalse(received.get(),
                    "Exact subscription must not receive non-matching event types"));
                return handler.stop();
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(error -> handler.stop().onComplete(ar -> testContext.failNow(error)));
    }

    @AfterEach
    void tearDown() {
        System.err.println("=== INTEGRATION TEST TEARDOWN ===");
        System.err.flush();
    }

    /**
     * Test implementation of BiTemporalEvent following established patterns.
     */
    private static class TestBiTemporalEvent implements BiTemporalEvent<String> {
        private final String eventId;
        private final String eventType;
        private final String payload;
        private final java.time.Instant validTime;
        private final java.time.Instant transactionTime;

        TestBiTemporalEvent(String eventId, String eventType, String payload, java.time.Instant validTime) {
            this.eventId = eventId;
            this.eventType = eventType;
            this.payload = payload;
            this.validTime = validTime;
            this.transactionTime = java.time.Instant.now();
        }

        @Override
        public String getEventId() { return eventId; }
        @Override
        public String getEventType() { return eventType; }
        @Override
        public String getPayload() { return payload; }
        @Override
        public java.time.Instant getValidTime() { return validTime; }
        @Override
        public java.time.Instant getTransactionTime() { return transactionTime; }
        @Override
        public long getVersion() { return 1L; }
        @Override
        public String getPreviousVersionId() { return null; }
        @Override
        public java.util.Map<String, String> getHeaders() { return java.util.Map.of(); }
        @Override
        public String getCorrelationId() { return null; }
        @Override
        public String getCausationId() { return null; }
        @Override
        public String getAggregateId() { return "test_aggregate"; }
        @Override
        public boolean isCorrection() { return false; }
        @Override
        public String getCorrectionReason() { return null; }
    }

    private Future<Void> insertBiTemporalEvent(Vertx vertx, String eventType, String aggregateId) {
        String eventId = UUID.randomUUID().toString();
        JsonObject payload = new JsonObject().put("test", "payload");

        Pool pool = Pool.pool(vertx, connectOptions, new PoolOptions().setMaxSize(1));

        String sql = """
            INSERT INTO bitemporal_event_log (event_id, event_type, valid_time, payload, aggregate_id)
            VALUES ($1, $2, NOW(), $3::jsonb, $4)
            """;

        return pool.preparedQuery(sql)
            .execute(Tuple.of(eventId, eventType, payload, aggregateId))
            .map(rows -> {
                eventTypesById.put(eventId, eventType);
                return (Void) null;
            })
            .onComplete(ar -> pool.close());
    }

    private Future<Void> waitForNoNotification(Vertx vertx, AtomicBoolean received, long timeoutMs) {
        return Future.future(promise -> {
            long timerId = vertx.setTimer(timeoutMs, id -> {
                if (received.get()) {
                    promise.fail(new AssertionError("Unexpected notification received during quiet period"));
                } else {
                    promise.complete();
                }
            });

            // Ensure timer is cancelled if future completes externally.
            promise.future().onComplete(ar -> vertx.cancelTimer(timerId));
        });
    }
}
