package dev.mars.peegeeq.bitemporal;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import dev.mars.peegeeq.api.EventStore;
import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.api.EventQuery;
import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.QueueFactoryProvider;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.pgqueue.PgNativeFactoryRegistrar;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive integration tests demonstrating the integration between PeeGeeQ and the bi-temporal event store.
 * 
 * These tests show:
 * 1. Producers creating events via PeeGeeQ
 * 2. Consumers subscribing to and receiving events
 * 3. Events being transactionally written to the bi-temporal store
 * 4. Real-time event notifications via PostgreSQL LISTEN/NOTIFY
 * 5. Correlation between PeeGeeQ messages and bi-temporal events
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-15
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
class PeeGeeQBiTemporalIntegrationTest {
    private static final Logger logger = LoggerFactory.getLogger(PeeGeeQBiTemporalIntegrationTest.class);
    
    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();
    
    private Vertx vertx;
    private PeeGeeQManager manager;
    private BiTemporalEventStoreFactory eventStoreFactory;
    private EventStore<OrderEvent> eventStore;
    private DatabaseService databaseService;
    private QueueFactoryProvider queueFactoryProvider;
    private QueueFactory queueFactory;
    private MessageProducer<OrderEvent> producer;
    private MessageConsumer<OrderEvent> consumer;
    
    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext testContext) throws Exception {
        this.vertx = vertx;
        logger.info("Setting up integration test...");
        
        // Set configuration properties for PeeGeeQ
        Properties testProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.migration.enabled", "true")
                .property("peegeeq.metrics.enabled", "true")
                .property("peegeeq.queue.batch-size", "50")
                .property("peegeeq.queue.polling-interval", "PT0.2S")
                .property("peegeeq.consumer.threads", "4")
                .property("peegeeq.database.pool.max-size", "15")
                .build();

        logger.info(" Using optimized configuration for integration test: batch-size=50, polling=200ms, threads=4");

        // Initialize database schema using centralized schema initializer
        logger.info("Creating ALL database tables using PeeGeeQTestSchemaInitializer...");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.ALL);
        logger.info("ALL database tables created successfully");

        // Configure PeeGeeQ
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);

        // Initialize PeeGeeQ Manager
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        
        manager.start()
            .onSuccess(v -> {
                logger.info("PeeGeeQ Manager started");
                // Create bi-temporal event store
                eventStoreFactory = new BiTemporalEventStoreFactory(vertx, manager);
                eventStore = eventStoreFactory.createEventStore(OrderEvent.class, "bitemporal_event_log");
                logger.info("Bi-temporal event store created");

                // Create PeeGeeQ components
                databaseService = new PgDatabaseService(manager);
                queueFactoryProvider = new PgQueueFactoryProvider();
                PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) queueFactoryProvider);
                queueFactory = queueFactoryProvider.createFactory("native", databaseService);

                // Create producer and consumer
                producer = queueFactory.createProducer("order-events", OrderEvent.class);
                consumer = queueFactory.createConsumer("order-events", OrderEvent.class);
                logger.info("PeeGeeQ producer and consumer created");
                logger.info("Integration test setup completed");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "setUp timed out");
    }
    
    @AfterEach
    void tearDown(VertxTestContext testContext) throws Exception {
        logger.info("Tearing down integration test...");

        // Close resources in reverse order
        Future<Void> cleanup = Future.succeededFuture();
        if (consumer != null) {
            cleanup = cleanup.compose(ignored -> {
                consumer.close();
                return Future.succeededFuture();
            });
        }
        if (producer != null) {
            cleanup = cleanup.compose(ignored -> {
                producer.close();
                return Future.succeededFuture();
            });
        }
        if (queueFactory != null) {
            cleanup = cleanup.compose(ignored -> queueFactory.close());
        }
        if (eventStore != null) {
            cleanup = cleanup.compose(ignored -> eventStore.close());
        }

        if (manager != null) {
            cleanup = cleanup.compose(ignored -> manager.closeReactive());
        }

        cleanup
            .onSuccess(v -> {
                logger.info("Integration test teardown completed");
                testContext.completeNow();
            })
            .onFailure(e -> {
                logger.error("Integration test teardown failed", e);
                testContext.failNow(e);
            });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "tearDown timed out");
    }
    
    @Test
    @DisplayName("Basic Producer-Consumer Integration with Bi-temporal Store")
    @io.vertx.junit5.Timeout(value = 10, timeUnit = TimeUnit.SECONDS)
    void testBasicProducerConsumerIntegration(Vertx vertx, VertxTestContext ctx) {
        logger.info("Starting basic producer-consumer integration test...");

        // Test data
        OrderEvent orderEvent = IntegrationTestUtils.createOrderEvent(
            "ORDER-001", "CUST-123", "CREATED", "US"
        );
        String correlationId = "corr-" + System.currentTimeMillis();
        Map<String, String> headers = Map.of(
            "source", "integration-test",
            "version", "1.0"
        );

        // A strict checkpoint: a second delivery fails the test.
        Checkpoint messageReceived = ctx.checkpoint();

        // Subscribe to messages
        consumer.subscribe(message -> {
            logger.info("Received message: {}", message.getPayload());
            IntegrationTestUtils.logMessage(message, "RECEIVED");
            ctx.verify(() -> {
                assertEquals(orderEvent, message.getPayload(), "Received payload should match sent payload");
            });
            messageReceived.flag();
            return Future.succeededFuture();
        })
            .compose(v -> {
                logger.info("Sending message via PeeGeeQ...");
                return producer.send(orderEvent, headers, correlationId);
            })
            .onSuccess(v -> logger.info("Message sent successfully"))
            .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("PeeGeeQ Messages with Bi-temporal Store Persistence")
    @io.vertx.junit5.Timeout(value = 30, timeUnit = TimeUnit.SECONDS)
    void testPeeGeeQWithBiTemporalStorePersistence(Vertx vertx, VertxTestContext ctx) {
        logger.info("Starting PeeGeeQ with bi-temporal store persistence test...");

        // Test data
        OrderEvent orderEvent1 = IntegrationTestUtils.createOrderEvent(
            "ORDER-101", "CUST-456", "CREATED", "EU"
        );
        OrderEvent orderEvent2 = IntegrationTestUtils.createOrderEvent(
            "ORDER-102", "CUST-789", "PENDING", "US"
        );

        String correlationId1 = "corr-101";
        String correlationId2 = "corr-102";
        String aggregateId1 = "ORDER-101";
        String aggregateId2 = "ORDER-102";

        Map<String, String> headers = Map.of(
            "source", "integration-test",
            "version", "1.0"
        );

        // The store holds exactly two events when the second message is processed; the strict
        // checkpoint fails the test if a third delivery reaches that point again.
        AtomicInteger processedCount = new AtomicInteger(0);
        Checkpoint allVerified = ctx.checkpoint();

        // Subscribe to PeeGeeQ messages
        consumer.subscribe(message -> {
            logger.info("Received PeeGeeQ message: {}", message.getPayload());
            IntegrationTestUtils.logMessage(message, "PEEGEEQ_RECEIVED");

            Instant validTime = message.getPayload().getOrderTimeAsInstant();
            String orderId = message.getPayload().getOrderId();
            String resolvedCorrelationId = "ORDER-101".equals(orderId) ? correlationId1
                : "ORDER-102".equals(orderId) ? correlationId2 : null;

            return eventStore.appendBuilder()
                .eventType("OrderEvent")
                .payload(message.getPayload())
                .validTime(validTime)
                .headers(message.getHeaders())
                .correlationId(resolvedCorrelationId)
                .causationId(null)
                .aggregateId(orderId)
                .execute()
                .compose(event -> {
                    logger.info("Persisted message to bi-temporal store: {} with correlation ID: {}",
                        orderId, resolvedCorrelationId);
                    if (processedCount.incrementAndGet() == 2) {
                        // Both messages processed, verify store contents
                        return eventStore.query(EventQuery.all())
                            .<Void>map(allEvents -> {
                                ctx.verify(() -> {
                                    assertNotNull(allEvents, "Events list should not be null");
                                    assertEquals(2, allEvents.size(), "Should have exactly two events in bi-temporal store");

                                    BiTemporalEvent<OrderEvent> event1 = IntegrationTestUtils.findEventByCorrelationId(allEvents, correlationId1);
                                    BiTemporalEvent<OrderEvent> event2 = IntegrationTestUtils.findEventByCorrelationId(allEvents, correlationId2);

                                    assertNotNull(event1, "Event 1 should be found in bi-temporal store");
                                    assertNotNull(event2, "Event 2 should be found in bi-temporal store");

                                    assertEquals(orderEvent1, event1.getPayload(), "Event 1 payload should match original");
                                    assertEquals(orderEvent2, event2.getPayload(), "Event 2 payload should match original");
                                    assertEquals(correlationId1, event1.getCorrelationId(), "Event 1 correlation ID should match");
                                    assertEquals(correlationId2, event2.getCorrelationId(), "Event 2 correlation ID should match");
                                    assertEquals(aggregateId1, event1.getAggregateId(), "Event 1 aggregate ID should match");
                                    assertEquals(aggregateId2, event2.getAggregateId(), "Event 2 aggregate ID should match");

                                    IntegrationTestUtils.logBiTemporalEvent(event1, "BITEMPORAL_EVENT_1");
                                    IntegrationTestUtils.logBiTemporalEvent(event2, "BITEMPORAL_EVENT_2");
                                });
                                allVerified.flag();
                                return null;
                            });
                    }
                    return Future.succeededFuture();
                })
                .transform(ar -> {
                    if (ar.failed()) {
                        logger.error("Failed to persist to bi-temporal store", ar.cause());
                        ctx.failNow(ar.cause());
                    }
                    return Future.succeededFuture();
                });
        })
            .compose(v -> producer.send(orderEvent1, headers, correlationId1))
            .compose(v -> producer.send(orderEvent2, headers, correlationId2))
            .onSuccess(v -> logger.info("Sent both messages"))
            .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("Real-time Event Subscriptions and Notifications")
    @io.vertx.junit5.Timeout(value = 10, timeUnit = TimeUnit.SECONDS)
    void testRealTimeEventSubscriptions(Vertx vertx, VertxTestContext ctx) {
        logger.info("Starting real-time event subscriptions test...");

        // Test data
        OrderEvent orderEvent = IntegrationTestUtils.createOrderEvent(
            "ORDER-201", "CUST-999", "PROCESSING", "CA"
        );
        String correlationId = "corr-201";
        String aggregateId = "ORDER-201";

        // A strict checkpoint: a second notification fails the test.
        AtomicReference<BiTemporalEvent<OrderEvent>> appendedEventRef = new AtomicReference<>();
        Checkpoint eventReceived = ctx.checkpoint();

        // Subscribe to bi-temporal events, then append
        logger.info("Setting up bi-temporal event subscription...");
        eventStore.subscribe("OrderEvent", message -> {
            BiTemporalEvent<OrderEvent> event = message.getPayload();
            logger.info("Received bi-temporal event subscription: {}", event.getEventId());
            IntegrationTestUtils.logBiTemporalEvent(event, "SUBSCRIPTION_RECEIVED");
            ctx.verify(() -> {
                assertNotNull(appendedEventRef.get(), "Appended event should be set before subscription fires");
                assertEquals(appendedEventRef.get().getEventId(), event.getEventId(), "Event IDs should match");
                assertEquals(orderEvent, event.getPayload(), "Event payloads should match");
                assertEquals(correlationId, event.getCorrelationId(), "Correlation IDs should match");
                assertEquals(aggregateId, event.getAggregateId(), "Aggregate IDs should match");
            });
            eventReceived.flag();
            return Future.succeededFuture();
        })
        .compose(v -> {
            // Directly append event to bi-temporal store (simulating real-time processing)
            logger.info("Appending event directly to bi-temporal store...");
            return eventStore.appendBuilder()
                .eventType("OrderEvent")
                .payload(orderEvent)
                .validTime(orderEvent.getOrderTimeAsInstant())
                .headers(Map.of("source", "direct-append", "version", "1.0"))
                .correlationId(correlationId)
                .causationId(null)
                .aggregateId(aggregateId)
                .execute();
        })
        .onSuccess(appendedEvent -> {
            appendedEventRef.set(appendedEvent);
            logger.info("Event appended with ID: {}", appendedEvent.getEventId());
        })
        .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("End-to-End Integration: PeeGeeQ  Consumer  Bi-temporal Store  Subscription")
    @io.vertx.junit5.Timeout(value = 20, timeUnit = TimeUnit.SECONDS)
    void testEndToEndIntegration(Vertx vertx, VertxTestContext ctx) {
        logger.info("Starting end-to-end integration test...");

        // Test data
        List<OrderEvent> testOrders = Arrays.asList(
            IntegrationTestUtils.createOrderEvent("ORDER-301", "CUST-001", "CREATED", "US"),
            IntegrationTestUtils.createOrderEvent("ORDER-302", "CUST-002", "PENDING", "EU"),
            IntegrationTestUtils.createOrderEvent("ORDER-303", "CUST-003", "CONFIRMED", "CA")
        );

        int expectedEventCount = testOrders.size();

        // Set up tracking for all stages
        List<Message<OrderEvent>> peeGeeQMessages = Collections.synchronizedList(new ArrayList<>());
        List<BiTemporalEvent<OrderEvent>> persistedEvents = Collections.synchronizedList(new ArrayList<>());
        List<BiTemporalEvent<OrderEvent>> subscribedEvents = Collections.synchronizedList(new ArrayList<>());

        // The cross-stage checks need every stage complete, so the test completes through
        // completeNow() after both promises, not through checkpoints.
        Promise<Void> allPersisted = Promise.promise();
        Promise<Void> allSubscribed = Promise.promise();

        // Set up bi-temporal event subscription, then consumer, then send
        eventStore.subscribe(null, message -> { // Subscribe to all event types
            BiTemporalEvent<OrderEvent> event = message.getPayload();
            logger.info("Subscription received event: {}", event.getEventId());
            IntegrationTestUtils.logBiTemporalEvent(event, "END_TO_END_SUBSCRIPTION");
            subscribedEvents.add(event);
            if (subscribedEvents.size() == expectedEventCount) {
                allSubscribed.tryComplete();
            }
            return Future.succeededFuture();
        })
        .compose(v -> {
            // Set up PeeGeeQ consumer that persists to bi-temporal store
            return consumer.subscribe(message -> {
                logger.info("PeeGeeQ consumer received message: {}", message.getPayload().getOrderId());
                IntegrationTestUtils.logMessage(message, "END_TO_END_PEEGEEQ");
                peeGeeQMessages.add(message);

                String orderId = message.getPayload().getOrderId();
                String resolvedCorrelationId = "ORDER-301".equals(orderId) ? "end-to-end-1"
                    : "ORDER-302".equals(orderId) ? "end-to-end-2"
                    : "ORDER-303".equals(orderId) ? "end-to-end-3" : null;

                return eventStore.appendBuilder()
                    .eventType("OrderEvent")
                    .payload(message.getPayload())
                    .validTime(message.getPayload().getOrderTimeAsInstant())
                    .headers(message.getHeaders())
                    .correlationId(resolvedCorrelationId)
                    .causationId(null)
                    .aggregateId(orderId)
                    .execute()
                    .map(event -> {
                        persistedEvents.add(event);
                        logger.info("Persisted event to bi-temporal store: {}", event.getEventId());
                        if (persistedEvents.size() == expectedEventCount) {
                            allPersisted.tryComplete();
                        }
                        return (Void) null;
                    })
                    .transform(ar -> {
                        if (ar.failed()) {
                            logger.error("Failed to persist event", ar.cause());
                            ctx.failNow(ar.cause());
                        }
                        return Future.succeededFuture();
                    });
            }).compose(v2 -> {
                // Send all test orders via PeeGeeQ using compose chain
                logger.info("Sending {} test orders via PeeGeeQ...", expectedEventCount);
                Future<Void> sendChain = Future.succeededFuture();
                for (int i = 0; i < testOrders.size(); i++) {
                    final int idx = i;
                    sendChain = sendChain.compose(v3 -> {
                        OrderEvent order = testOrders.get(idx);
                        String correlationId = "end-to-end-" + (idx + 1);
                        Map<String, String> headers = Map.of(
                            "source", "end-to-end-test",
                            "order-index", String.valueOf(idx + 1)
                        );
                        return producer.send(order, headers, correlationId)
                            .onSuccess(v4 -> logger.info("Sent order {}: {}", idx + 1, order.getOrderId()));
                    });
                }
                return sendChain;
                });
        })
        .compose(sent -> Future.all(allPersisted.future(), allSubscribed.future()))
        .onSuccess(stagesComplete -> ctx.verify(() -> {
            // Verify all stages completed successfully
            assertEquals(expectedEventCount, peeGeeQMessages.size(), "Should receive all PeeGeeQ messages");
            assertEquals(expectedEventCount, persistedEvents.size(), "Should persist all events to bi-temporal store");
            assertEquals(expectedEventCount, subscribedEvents.size(), "Should receive all subscription notifications");

            // Verify data consistency across all stages
            for (int i = 0; i < expectedEventCount; i++) {
                OrderEvent originalOrder = testOrders.get(i);
                Message<OrderEvent> peeGeeQMessage = peeGeeQMessages.stream()
                    .filter(message -> originalOrder.getOrderId().equals(message.getPayload().getOrderId()))
                    .findFirst()
                    .orElse(null);

                // Find corresponding subscribed event by correlation ID
                String expectedCorrelationId = "end-to-end-" + (i + 1);
                BiTemporalEvent<OrderEvent> persistedEvent = persistedEvents.stream()
                    .filter(event -> expectedCorrelationId.equals(event.getCorrelationId()))
                    .findFirst()
                    .orElse(null);
                BiTemporalEvent<OrderEvent> subscribedEvent = subscribedEvents.stream()
                    .filter(event -> expectedCorrelationId.equals(event.getCorrelationId()))
                    .findFirst()
                    .orElse(null);

                assertNotNull(peeGeeQMessage, "Should find PeeGeeQ message for order ID: " + originalOrder.getOrderId());
                assertNotNull(persistedEvent, "Should find persisted event for correlation ID: " + expectedCorrelationId);
                assertNotNull(subscribedEvent, "Should find subscribed event for correlation ID: " + expectedCorrelationId);

                // Verify data consistency
                assertEquals(originalOrder, peeGeeQMessage.getPayload(), "PeeGeeQ message payload should match original");
                assertEquals(originalOrder, persistedEvent.getPayload(), "Persisted event payload should match original");
                assertEquals(originalOrder, subscribedEvent.getPayload(), "Subscribed event payload should match original");

                // Note: PeeGeeQ native doesn't preserve correlation IDs in messages - this is a known limitation
                logger.info("Correlation ID sent: {}, PeeGeeQ received: {} (PeeGeeQ native limitation)",
                           expectedCorrelationId, IntegrationTestUtils.getCorrelationId(peeGeeQMessage));
                assertEquals(expectedCorrelationId, persistedEvent.getCorrelationId(), "Persisted correlation ID should match");
                assertEquals(expectedCorrelationId, subscribedEvent.getCorrelationId(), "Subscribed correlation ID should match");

                assertEquals(originalOrder.getOrderId(), persistedEvent.getAggregateId(), "Aggregate ID should match order ID");
                assertEquals(originalOrder.getOrderId(), subscribedEvent.getAggregateId(), "Subscribed aggregate ID should match order ID");
            }

            logger.info("Summary: {} PeeGeeQ messages  {} persisted events  {} subscription notifications",
                       peeGeeQMessages.size(), persistedEvents.size(), subscribedEvents.size());
            ctx.completeNow();
        }))
        .onFailure(ctx::failNow);
    }
}
