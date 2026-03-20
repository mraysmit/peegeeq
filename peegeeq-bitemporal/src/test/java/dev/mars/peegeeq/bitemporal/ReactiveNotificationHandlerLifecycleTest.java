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
import dev.mars.peegeeq.api.messaging.MessageHandler;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgConnection;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Isolated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive lifecycle tests for ReactiveNotificationHandler.
 * 
 * Tests the complete subscription lifecycle including:
 * - Start and stop operations
 * - Subscription management
 * - Event delivery and filtering
 * - Wildcard pattern matching
 * - Connection lifecycle and reconnection
 * - Error handling and recovery
 * - Resource cleanup
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-01-12
 */
@Tag(TestCategories.CORE)
@ExtendWith(VertxExtension.class)
@Testcontainers
@Isolated
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReactiveNotificationHandlerLifecycleTest {
    private static final Logger logger = LoggerFactory.getLogger(ReactiveNotificationHandlerLifecycleTest.class);

    @Container
    static PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer("postgres:15.13-alpine3.20");
        container.withDatabaseName("reactive_lifecycle_test");
        container.withUsername("test_user");
        container.withPassword("test_password");
        container.withSharedMemorySize(256 * 1024 * 1024L);
        container.withReuse(false);
        return container;
    }

    private ObjectMapper objectMapper;
    private PgConnectOptions connectOptions;
    private Map<String, BiTemporalEvent<String>> eventStore;
    private Function<String, Future<BiTemporalEvent<String>>> eventRetriever;

    @BeforeEach
    void setUp() {
        logger.info("Setting up ReactiveNotificationHandler lifecycle test");
        
        // Initialize database schema
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, 
            PeeGeeQTestSchemaInitializer.SchemaComponent.BITEMPORAL);
        ensureBitemporalCompatibilityColumns();

        // Create connection options
        this.connectOptions = new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getFirstMappedPort())
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword());

        this.objectMapper = new ObjectMapper();
        
        // Event store for testing
        this.eventStore = new ConcurrentHashMap<>();
        
        // Event retriever that uses the event store
        this.eventRetriever = eventId -> {
            BiTemporalEvent<String> event = eventStore.get(eventId);
            if (event != null) {
                return Future.succeededFuture(event);
            }
            return Future.failedFuture(new IllegalArgumentException("Event not found: " + eventId));
        };
    }

    private void ensureBitemporalCompatibilityColumns() {
        String sql = """
            ALTER TABLE IF EXISTS public.bitemporal_event_log
            ADD COLUMN IF NOT EXISTS correlation_id VARCHAR(255),
            ADD COLUMN IF NOT EXISTS causation_id VARCHAR(255),
            ADD COLUMN IF NOT EXISTS is_correction BOOLEAN DEFAULT FALSE,
            ADD COLUMN IF NOT EXISTS headers JSONB DEFAULT '{}'::jsonb
            """;

        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (Exception e) {
            throw new RuntimeException("Failed to enforce bitemporal test table compatibility", e);
        }
    }

    @Test
    @Order(1)
    @DisplayName("Handler should start successfully and become active")
    void testHandlerStartSuccess(Vertx vertx, VertxTestContext testContext) {
        ReactiveNotificationHandler<String> handler = new ReactiveNotificationHandler<>(
            vertx, connectOptions, objectMapper, String.class, eventRetriever
        );

        assertFalse(handler.isActive(), "Handler should not be active before start");

        handler.start()
            .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                assertTrue(handler.isActive(), "Handler should be active after successful start");
                logger.info("✓ Handler started successfully and is active");
                
                // Cleanup
                handler.stop().onComplete(ar -> testContext.completeNow());
            })));
    }

    @Test
    @Order(2)
    @DisplayName("Handler should stop successfully and become inactive")
    void testHandlerStopSuccess(Vertx vertx, VertxTestContext testContext) {
        ReactiveNotificationHandler<String> handler = new ReactiveNotificationHandler<>(
            vertx, connectOptions, objectMapper, String.class, eventRetriever
        );

        handler.start()
            .compose(v -> {
                assertTrue(handler.isActive(), "Handler should be active after start");
                return handler.stop();
            })
            .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                assertFalse(handler.isActive(), "Handler should not be active after stop");
                logger.info("✓ Handler stopped successfully and is inactive");
                testContext.completeNow();
            })));
    }

    @Test
    @Order(3)
    @DisplayName("Multiple start calls should be idempotent")
    void testMultipleStartsIdempotent(Vertx vertx, VertxTestContext testContext) {
        ReactiveNotificationHandler<String> handler = new ReactiveNotificationHandler<>(
            vertx, connectOptions, objectMapper, String.class, eventRetriever
        );

        handler.start()
            .compose(v -> handler.start()) // Second start
            .compose(v -> handler.start()) // Third start
            .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                assertTrue(handler.isActive(), "Handler should be active after multiple starts");
                logger.info("✓ Multiple start calls are idempotent");
                
                // Cleanup
                handler.stop().onComplete(ar -> testContext.completeNow());
            })));
    }

    @Test
    @Order(4)
    @DisplayName("Multiple stop calls should be idempotent")
    void testMultipleStopsIdempotent(Vertx vertx, VertxTestContext testContext) {
        ReactiveNotificationHandler<String> handler = new ReactiveNotificationHandler<>(
            vertx, connectOptions, objectMapper, String.class, eventRetriever
        );

        handler.start()
            .compose(v -> handler.stop())
            .compose(v -> handler.stop()) // Second stop
            .compose(v -> handler.stop()) // Third stop
            .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                assertFalse(handler.isActive(), "Handler should be inactive after multiple stops");
                logger.info("✓ Multiple stop calls are idempotent");
                testContext.completeNow();
            })));
    }

    @Test
    @Order(5)
    @DisplayName("Subscribe should fail when handler is not active")
    void testSubscribeWhenNotActive(Vertx vertx, VertxTestContext testContext) {
        ReactiveNotificationHandler<String> handler = new ReactiveNotificationHandler<>(
            vertx, connectOptions, objectMapper, String.class, eventRetriever
        );

        MessageHandler<BiTemporalEvent<String>> messageHandler = 
            message -> Future.<Void>succeededFuture();

        handler.subscribe("test.event", null, messageHandler)
            .onComplete(testContext.failing(error -> testContext.verify(() -> {
                assertTrue(error instanceof IllegalStateException, 
                    "Should fail with IllegalStateException when not active");
                assertTrue(error.getMessage().contains("not active"), 
                    "Error message should mention handler is not active");
                logger.info("✓ Subscribe correctly fails when handler is not active");
                testContext.completeNow();
            })));
    }

    @Test
    @Order(6)
    @DisplayName("Subscribe should succeed with exact event type match")
    void testSubscribeExactEventType(Vertx vertx, VertxTestContext testContext) {
        ReactiveNotificationHandler<String> handler = new ReactiveNotificationHandler<>(
            vertx, connectOptions, objectMapper, String.class, eventRetriever
        );

        MessageHandler<BiTemporalEvent<String>> messageHandler = 
            message -> Future.<Void>succeededFuture();

        handler.start()
            .compose(v -> handler.subscribe("order.created", null, messageHandler))
            .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                logger.info("✓ Subscribe succeeded with exact event type");
                
                // Cleanup
                handler.stop().onComplete(ar -> testContext.completeNow());
            })));
    }

    @Test
    @Order(7)
    @DisplayName("Subscribe should succeed with wildcard event type")
    void testSubscribeWildcardEventType(Vertx vertx, VertxTestContext testContext) {
        ReactiveNotificationHandler<String> handler = new ReactiveNotificationHandler<>(
            vertx, connectOptions, objectMapper, String.class, eventRetriever
        );

        MessageHandler<BiTemporalEvent<String>> messageHandler = 
            message -> Future.<Void>succeededFuture();

        handler.start()
            .compose(v -> handler.subscribe("order.*", null, messageHandler))
            .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                logger.info("✓ Subscribe succeeded with wildcard event type");
                
                // Cleanup
                handler.stop().onComplete(ar -> testContext.completeNow());
            })));
    }

    @Test
    @Order(8)
    @DisplayName("Subscribe should succeed with null event type (all events)")
    void testSubscribeAllEvents(Vertx vertx, VertxTestContext testContext) {
        ReactiveNotificationHandler<String> handler = new ReactiveNotificationHandler<>(
            vertx, connectOptions, objectMapper, String.class, eventRetriever
        );

        MessageHandler<BiTemporalEvent<String>> messageHandler = 
            message -> Future.<Void>succeededFuture();

        handler.start()
            .compose(v -> handler.subscribe(null, null, messageHandler))
            .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                logger.info("✓ Subscribe succeeded with null event type (all events)");
                
                // Cleanup
                handler.stop().onComplete(ar -> testContext.completeNow());
            })));
    }

    @Test
    @Order(9)
    @DisplayName("Should receive notification for matching exact event type")
    void testReceiveNotificationExactMatch(Vertx vertx, VertxTestContext testContext) throws Exception {
        ReactiveNotificationHandler<String> handler = new ReactiveNotificationHandler<>(
            vertx, connectOptions, objectMapper, String.class, eventRetriever
        );

        List<String> receivedEvents = Collections.synchronizedList(new ArrayList<>());

        MessageHandler<BiTemporalEvent<String>> messageHandler = message -> {
            receivedEvents.add(message.getPayload().getEventId());
            return Future.<Void>succeededFuture();
        };

        handler.start()
            .compose(v -> handler.subscribe("order.created", null, messageHandler))
            .compose(v -> {
                // Insert event and send notification
                return insertEventAndNotify(vertx, "evt-001", "order.created", "agg-001", "Test payload");
            })
            .compose(v -> waitForCondition(vertx, 5000, () -> receivedEvents.size() == 1))
            .onComplete(testContext.succeeding(v -> {
                testContext.verify(() -> {
                    assertEquals(1, receivedEvents.size(), "Should receive exactly one event");
                    assertEquals("evt-001", receivedEvents.get(0), "Should receive correct event ID");
                    logger.info("✓ Received notification for exact event type match");
                });
                handler.stop().onComplete(ar -> testContext.completeNow());
            }));
    }

    @Test
    @Order(18)
    @DisplayName("Should notify all handlers subscribed to the same key")
    void testMultipleHandlersForSameSubscriptionKey(Vertx vertx, VertxTestContext testContext) {
        ReactiveNotificationHandler<String> handler = new ReactiveNotificationHandler<>(
            vertx, connectOptions, objectMapper, String.class, eventRetriever
        );

        AtomicInteger firstHandlerCount = new AtomicInteger(0);
        AtomicInteger secondHandlerCount = new AtomicInteger(0);

        MessageHandler<BiTemporalEvent<String>> firstHandler = message -> {
            firstHandlerCount.incrementAndGet();
            return Future.<Void>succeededFuture();
        };

        MessageHandler<BiTemporalEvent<String>> secondHandler = message -> {
            secondHandlerCount.incrementAndGet();
            return Future.<Void>succeededFuture();
        };

        handler.start()
            .compose(v -> handler.subscribe("order.created", null, firstHandler))
            .compose(v -> handler.subscribe("order.created", null, secondHandler))
            .compose(v -> insertEventAndNotify(vertx, "evt-012-same-key", "order.created", "agg-012", "same-key"))
            .compose(v -> waitForCondition(vertx, 5000,
                () -> firstHandlerCount.get() == 1 && secondHandlerCount.get() == 1))
            .onComplete(testContext.succeeding(v -> {
                testContext.verify(() -> {
                    assertEquals(1, firstHandlerCount.get(), "First handler should be invoked once");
                    assertEquals(1, secondHandlerCount.get(), "Second handler should be invoked once");
                    logger.info("✓ Multiple handlers for same key are all notified");
                });
                handler.stop().onComplete(ar -> testContext.completeNow());
            }));
    }

    @Test
    @Order(10)
    @DisplayName("Should receive notification for wildcard pattern match")
    void testReceiveNotificationWildcardMatch(Vertx vertx, VertxTestContext testContext) throws Exception {
        ReactiveNotificationHandler<String> handler = new ReactiveNotificationHandler<>(
            vertx, connectOptions, objectMapper, String.class, eventRetriever
        );

        List<String> receivedEvents = Collections.synchronizedList(new ArrayList<>());

        MessageHandler<BiTemporalEvent<String>> messageHandler = message -> {
            receivedEvents.add(message.getPayload().getEventType());
            return Future.<Void>succeededFuture();
        };

        handler.start()
            .compose(v -> handler.subscribe("order.*", null, messageHandler))
            .compose(v -> insertEventAndNotify(vertx, "evt-002", "order.created", "agg-001", "Payload 1"))
            .compose(v -> insertEventAndNotify(vertx, "evt-003", "order.updated", "agg-001", "Payload 2"))
            .compose(v -> waitForCondition(vertx, 5000, () -> receivedEvents.size() == 2))
            .onComplete(testContext.succeeding(v -> {
                testContext.verify(() -> {
                    assertEquals(2, receivedEvents.size(), "Should receive two events");
                    assertTrue(receivedEvents.contains("order.created"), "Should receive order.created");
                    assertTrue(receivedEvents.contains("order.updated"), "Should receive order.updated");
                    logger.info("✓ Received notifications for wildcard pattern match");
                });
                handler.stop().onComplete(ar -> testContext.completeNow());
            }));
    }

    @Test
    @Order(11)
    @DisplayName("Should not receive notification for non-matching event type")
    void testNoNotificationForNonMatch(Vertx vertx, VertxTestContext testContext) throws Exception {
        ReactiveNotificationHandler<String> handler = new ReactiveNotificationHandler<>(
            vertx, connectOptions, objectMapper, String.class, eventRetriever
        );

        List<String> receivedEvents = Collections.synchronizedList(new ArrayList<>());

        MessageHandler<BiTemporalEvent<String>> messageHandler = message -> {
            receivedEvents.add(message.getPayload().getEventType());
            return Future.<Void>succeededFuture();
        };

        handler.start()
            .compose(v -> handler.subscribe("order.created", null, messageHandler))
            .compose(v -> insertEventAndNotify(vertx, "evt-004", "payment.completed", "agg-002", "Wrong type"))
            .compose(v -> {
                // Wait a bit to ensure no notification is received
                return Future.future(promise -> 
                    vertx.setTimer(2000, id -> promise.complete()));
            })
            .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                assertEquals(0, receivedEvents.size(), 
                    "Should not receive notification for non-matching event type");
                logger.info("✓ Correctly filtered out non-matching event type");
                
                // Cleanup
                handler.stop().onComplete(ar -> testContext.completeNow());
            })));
    }

    @Test
    @Order(12)
    @DisplayName("Should handle multiple simultaneous subscriptions")
    void testMultipleSubscriptions(Vertx vertx, VertxTestContext testContext) throws Exception {
        ReactiveNotificationHandler<String> handler = new ReactiveNotificationHandler<>(
            vertx, connectOptions, objectMapper, String.class, eventRetriever
        );

        AtomicInteger orderCount = new AtomicInteger(0);
        AtomicInteger paymentCount = new AtomicInteger(0);
        AtomicInteger allCount = new AtomicInteger(0);

        MessageHandler<BiTemporalEvent<String>> orderHandler = message -> {
            orderCount.incrementAndGet();
            return Future.<Void>succeededFuture();
        };

        MessageHandler<BiTemporalEvent<String>> paymentHandler = message -> {
            paymentCount.incrementAndGet();
            return Future.<Void>succeededFuture();
        };

        MessageHandler<BiTemporalEvent<String>> allHandler = message -> {
            allCount.incrementAndGet();
            return Future.<Void>succeededFuture();
        };

        handler.start()
            .compose(v -> handler.subscribe("order.*", null, orderHandler))
            .compose(v -> handler.subscribe("payment.*", null, paymentHandler))
            .compose(v -> handler.subscribe(null, null, allHandler))
            .compose(v -> insertEventAndNotify(vertx, "evt-005", "order.created", "agg-003", "Order"))
            .compose(v -> waitForCondition(vertx, 5000,
                () -> orderCount.get() == 1 && allCount.get() == 1))
            .onComplete(testContext.succeeding(v -> {
                testContext.verify(() -> {
                    assertEquals(1, orderCount.get(), "Order handler should receive one event");
                    assertEquals(0, paymentCount.get(), "Payment handler should not receive events");
                    assertEquals(1, allCount.get(), "All-events handler should receive one event");
                    logger.info("✓ Multiple subscriptions handled correctly");
                });
                handler.stop().onComplete(ar -> testContext.completeNow());
            }));
    }

    @Test
    @Order(13)
    @DisplayName("Should handle subscription with aggregate ID filter")
    void testSubscriptionWithAggregateFilter(Vertx vertx, VertxTestContext testContext) throws Exception {
        ReactiveNotificationHandler<String> handler = new ReactiveNotificationHandler<>(
            vertx, connectOptions, objectMapper, String.class, eventRetriever
        );

        List<String> receivedAggregates = Collections.synchronizedList(new ArrayList<>());

        MessageHandler<BiTemporalEvent<String>> messageHandler = message -> {
            receivedAggregates.add(message.getHeaders().get("aggregate_id"));
            return Future.<Void>succeededFuture();
        };

        handler.start()
            .compose(v -> handler.subscribe("order.created", "specific-agg", messageHandler))
            .compose(v -> insertEventAndNotify(vertx, "evt-006", "order.created", "specific-agg", "Match"))
            .compose(v -> insertEventAndNotify(vertx, "evt-007", "order.created", "other-agg", "No match"))
            .compose(v -> waitForCondition(vertx, 5000, () -> receivedAggregates.size() == 1))
            .onComplete(testContext.succeeding(v -> {
                testContext.verify(() -> {
                    assertEquals(1, receivedAggregates.size(), "Should receive one event");
                    assertEquals("specific-agg", receivedAggregates.get(0),
                        "Should receive event with correct aggregate ID");
                    logger.info("✓ Aggregate ID filtering works correctly");
                });
                handler.stop().onComplete(ar -> testContext.completeNow());
            }));
    }

    @Test
    @Order(14)
    @DisplayName("Should handle handler errors gracefully")
    void testHandlerErrorHandling(Vertx vertx, VertxTestContext testContext) throws Exception {
        ReactiveNotificationHandler<String> handler = new ReactiveNotificationHandler<>(
            vertx, connectOptions, objectMapper, String.class, eventRetriever
        );

        AtomicInteger errorCount = new AtomicInteger(0);

        MessageHandler<BiTemporalEvent<String>> faultyHandler = message -> {
            errorCount.incrementAndGet();
            // Simulate handler error
            return Future.failedFuture(new RuntimeException("Handler error"));
        };

        handler.start()
            .compose(v -> handler.subscribe("error.test", null, faultyHandler))
            .compose(v -> insertEventAndNotify(vertx, "evt-008", "error.test", "agg-004", "Error test"))
            .compose(v -> waitForCondition(vertx, 5000, () -> errorCount.get() == 1))
            .onComplete(testContext.succeeding(v -> {
                testContext.verify(() -> {
                    assertEquals(1, errorCount.get(), "Handler should be invoked once");
                    logger.info("✓ Handler errors are caught and logged gracefully");
                });
                handler.stop().onComplete(ar -> testContext.completeNow());
            }));
    }

    @Test
    @Order(15)
    @DisplayName("Should clean up resources on stop")
    void testResourceCleanupOnStop(Vertx vertx, VertxTestContext testContext) {
        ReactiveNotificationHandler<String> handler = new ReactiveNotificationHandler<>(
            vertx, connectOptions, objectMapper, String.class, eventRetriever
        );

        MessageHandler<BiTemporalEvent<String>> messageHandler = 
            message -> Future.<Void>succeededFuture();

        handler.start()
            .compose(v -> handler.subscribe("test.event", null, messageHandler))
            .compose(v -> {
                assertTrue(handler.isActive(), "Handler should be active");
                return handler.stop();
            })
            .compose(v -> {
                assertFalse(handler.isActive(), "Handler should be inactive after stop");
                // Try to subscribe again - should fail since handler is stopped
                return handler.subscribe("another.event", null, messageHandler);
            })
            .onComplete(testContext.failing(error -> testContext.verify(() -> {
                assertTrue(error instanceof IllegalStateException, 
                    "Should not allow subscription after stop");
                logger.info("✓ Resources cleaned up correctly on stop");
                testContext.completeNow();
            })));
    }

    @Test
    @Order(16)
    @DisplayName("Stop then restart should not retain stale subscriptions")
    void testStopAndRestartClearsSubscriptions(Vertx vertx, VertxTestContext testContext) throws Exception {
        ReactiveNotificationHandler<String> handler = new ReactiveNotificationHandler<>(
            vertx, connectOptions, objectMapper, String.class, eventRetriever
        );

        AtomicInteger receivedCount = new AtomicInteger(0);
        MessageHandler<BiTemporalEvent<String>> messageHandler = message -> {
            receivedCount.incrementAndGet();
            return Future.<Void>succeededFuture();
        };

        handler.start()
            .compose(v -> handler.subscribe("order.created", null, messageHandler))
            .compose(v -> handler.stop())
            .compose(v -> handler.start())
            .compose(v -> insertEventAndNotify(vertx, "evt-009", "order.created", "agg-005", "After restart"))
            .compose(v -> {
                // Wait to ensure no stale handler is invoked after restart.
                return Future.future(promise -> vertx.setTimer(1500, id -> promise.complete()));
            })
            .onComplete(testContext.succeeding(v -> {
                testContext.verify(() -> {
                    assertEquals(0, receivedCount.get(),
                        "Stale subscriptions from before stop() should not receive notifications after restart");
                    logger.info("✓ Stop/start does not retain stale subscriptions");
                });
                handler.stop().onComplete(ar -> testContext.completeNow());
            }));
    }

    @Test
    @Order(17)
    @DisplayName("Wildcard subscription should match aggregate IDs with underscores")
    void testWildcardSubscriptionWithUnderscoreAggregateId(Vertx vertx, VertxTestContext testContext) throws Exception {
        ReactiveNotificationHandler<String> handler = new ReactiveNotificationHandler<>(
            vertx, connectOptions, objectMapper, String.class, eventRetriever
        );

        AtomicInteger receivedCount = new AtomicInteger(0);
        MessageHandler<BiTemporalEvent<String>> messageHandler = message -> {
            receivedCount.incrementAndGet();
            return Future.<Void>succeededFuture();
        };

        handler.start()
            .compose(v -> handler.subscribe("order.*", "customer_1", messageHandler))
            .compose(v -> insertEventAndNotify(vertx, "evt-010", "order.created", "customer_1", "Match"))
            .compose(v -> insertEventAndNotify(vertx, "evt-011", "order.updated", "customer_2", "No match"))
            .compose(v -> waitForCondition(vertx, 5000, () -> receivedCount.get() == 1))
            .onComplete(testContext.succeeding(v -> {
                testContext.verify(() -> {
                    assertEquals(1, receivedCount.get(),
                        "Wildcard subscription should correctly match underscore aggregate IDs");
                    logger.info("✓ Wildcard aggregate matching works with underscores");
                });
                handler.stop().onComplete(ar -> testContext.completeNow());
            }));
    }

    /**
     * Helper method to insert an event into the database and trigger a notification.
     * The INSERT will automatically trigger the database trigger which sends pg_notify.
     */
    private Future<Void> insertEventAndNotify(Vertx vertx, String eventId, String eventType, 
                                              String aggregateId, String payload) {
        // Add event to in-memory store so event retriever can find it
        BiTemporalEvent<String> event = new TestBiTemporalEvent(
            eventId, eventType, payload, Instant.now(), aggregateId
        );
        eventStore.put(eventId, event);

        String generalChannel = "public_bitemporal_events_bitemporal_event_log";
        String typeChannel = generalChannel + "_" + eventType.replace('.', '_');
        String notificationPayload = new JsonObject()
            .put("event_id", eventId)
            .put("event_type", eventType)
            .put("aggregate_id", aggregateId)
            .put("correlation_id", null)
            .put("causation_id", null)
            .put("is_correction", false)
            .encode();

        return PgConnection.connect(vertx, connectOptions)
            .compose(conn -> {
                // Publish deterministic notifications used by lifecycle tests.
                return conn.preparedQuery("SELECT pg_notify($1, $2)")
                    .execute(Tuple.of(generalChannel, notificationPayload))
                    .compose(v -> conn.preparedQuery("SELECT pg_notify($1, $2)")
                        .execute(Tuple.of(typeChannel, notificationPayload)))
                    .onComplete(ar -> conn.close())
                    .mapEmpty();
            });
    }

    private Future<Void> waitForCondition(Vertx vertx, long timeoutMs, Supplier<Boolean> condition) {
        return Future.future(promise -> {
            long start = System.currentTimeMillis();
            long timerId = vertx.setPeriodic(25, id -> {
                if (promise.future().isComplete()) {
                    vertx.cancelTimer(id);
                    return;
                }

                if (condition.get()) {
                    vertx.cancelTimer(id);
                    promise.complete();
                    return;
                }

                if ((System.currentTimeMillis() - start) >= timeoutMs) {
                    vertx.cancelTimer(id);
                    promise.fail(new AssertionError("Condition was not met within timeout: " + timeoutMs + "ms"));
                }
            });

            // Ensure timer is cancelled if future completes externally.
            promise.future().onComplete(ar -> vertx.cancelTimer(timerId));
        });
    }

    /**
     * Test implementation of BiTemporalEvent.
     */
    private static class TestBiTemporalEvent implements BiTemporalEvent<String> {
        private final String eventId;
        private final String eventType;
        private final String payload;
        private final Instant validTime;
        private final String aggregateId;
        private final Map<String, String> headers;

        public TestBiTemporalEvent(String eventId, String eventType, String payload, 
                                   Instant validTime) {
            this(eventId, eventType, payload, validTime, null);
        }

        public TestBiTemporalEvent(String eventId, String eventType, String payload, 
                                   Instant validTime, String aggregateId) {
            this.eventId = eventId;
            this.eventType = eventType;
            this.payload = payload;
            this.validTime = validTime;
            this.aggregateId = aggregateId;
            this.headers = new HashMap<>();
            if (aggregateId != null) {
                headers.put("aggregate_id", aggregateId);
            }
            headers.put("event_type", eventType);
        }

        @Override public String getEventId() { return eventId; }
        @Override public String getAggregateId() { return aggregateId; }
        @Override public String getEventType() { return eventType; }
        @Override public String getPayload() { return payload; }
        @Override public Map<String, String> getHeaders() { return headers; }
        @Override public String getCorrelationId() { return null; }
        @Override public String getCausationId() { return null; }
        @Override public Instant getValidTime() { return validTime; }
        @Override public Instant getTransactionTime() { return Instant.now(); }
        @Override public long getVersion() { return 1L; }
        @Override public String getPreviousVersionId() { return null; }
        @Override public boolean isCorrection() { return false; }
        @Override public String getCorrectionReason() { return null; }
    }
}
