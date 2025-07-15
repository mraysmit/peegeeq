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

import dev.mars.peegeeq.api.*;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
@Testcontainers
class PeeGeeQBiTemporalIntegrationTest {
    private static final Logger logger = LoggerFactory.getLogger(PeeGeeQBiTemporalIntegrationTest.class);
    
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_integration_test")
            .withUsername("peegeeq_test")
            .withPassword("peegeeq_test")
            .withSharedMemorySize(256 * 1024 * 1024L)
            .withReuse(false);
    
    private PeeGeeQManager manager;
    private BiTemporalEventStoreFactory eventStoreFactory;
    private EventStore<OrderEvent> eventStore;
    private DatabaseService databaseService;
    private QueueFactoryProvider queueFactoryProvider;
    private QueueFactory queueFactory;
    private MessageProducer<OrderEvent> producer;
    private MessageConsumer<OrderEvent> consumer;
    
    @BeforeEach
    void setUp() throws Exception {
        logger.info("Setting up integration test...");
        
        // Set system properties for PeeGeeQ configuration
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.migration.enabled", "true");
        System.setProperty("peegeeq.metrics.enabled", "true");
        
        // Configure PeeGeeQ
        PeeGeeQConfiguration config = new PeeGeeQConfiguration();
        
        // Initialize PeeGeeQ Manager
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();
        logger.info("PeeGeeQ Manager started");
        
        // Create bi-temporal event store
        eventStoreFactory = new BiTemporalEventStoreFactory(manager);
        eventStore = eventStoreFactory.createEventStore(OrderEvent.class);
        logger.info("Bi-temporal event store created");
        
        // Create PeeGeeQ components
        databaseService = new PgDatabaseService(manager);
        queueFactoryProvider = new PgQueueFactoryProvider();
        queueFactory = queueFactoryProvider.createFactory("native", databaseService);
        
        // Create producer and consumer
        producer = queueFactory.createProducer("order-events", OrderEvent.class);
        consumer = queueFactory.createConsumer("order-events", OrderEvent.class);
        logger.info("PeeGeeQ producer and consumer created");
        
        logger.info("Integration test setup completed");
    }
    
    @AfterEach
    void tearDown() throws Exception {
        logger.info("Tearing down integration test...");
        
        // Close resources in reverse order
        if (consumer != null) {
            consumer.close();
        }
        if (producer != null) {
            producer.close();
        }
        if (queueFactory != null) {
            queueFactory.close();
        }
        if (eventStore != null) {
            eventStore.close();
        }
        if (manager != null) {
            manager.stop();
        }
        
        // Clean up system properties
        System.clearProperty("peegeeq.database.host");
        System.clearProperty("peegeeq.database.port");
        System.clearProperty("peegeeq.database.name");
        System.clearProperty("peegeeq.database.username");
        System.clearProperty("peegeeq.database.password");
        System.clearProperty("peegeeq.migration.enabled");
        System.clearProperty("peegeeq.metrics.enabled");
        
        logger.info("Integration test teardown completed");
    }
    
    @Test
    @DisplayName("Basic Producer-Consumer Integration with Bi-temporal Store")
    void testBasicProducerConsumerIntegration() throws Exception {
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
        
        // Set up message tracking
        List<Message<OrderEvent>> receivedMessages = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch messageLatch = new CountDownLatch(1);
        
        // Subscribe to messages
        consumer.subscribe(message -> {
            logger.info("Received message: {}", message.getPayload());
            IntegrationTestUtils.logMessage(message, "RECEIVED");
            receivedMessages.add(message);
            messageLatch.countDown();
            return CompletableFuture.completedFuture(null);
        });
        
        // Send message via PeeGeeQ
        logger.info("Sending message via PeeGeeQ...");
        producer.send(orderEvent, headers, correlationId).join();
        logger.info("Message sent successfully");
        
        // Wait for message to be received
        assertTrue(IntegrationTestUtils.waitForLatch(messageLatch, 10, "message reception"),
                  "Message should be received within 10 seconds");
        
        // Verify message was received
        assertEquals(1, receivedMessages.size(), "Should receive exactly one message");
        Message<OrderEvent> receivedMessage = receivedMessages.get(0);
        assertEquals(orderEvent, receivedMessage.getPayload(), "Received payload should match sent payload");
        assertEquals(correlationId, IntegrationTestUtils.getCorrelationId(receivedMessage), "Correlation IDs should match");
        
        logger.info("Basic producer-consumer integration test completed successfully");
    }

    @Test
    @DisplayName("PeeGeeQ Messages with Bi-temporal Store Persistence")
    void testPeeGeeQWithBiTemporalStorePersistence() throws Exception {
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

        // Set up message tracking
        List<Message<OrderEvent>> receivedMessages = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch messageLatch = new CountDownLatch(2);

        // Subscribe to PeeGeeQ messages
        consumer.subscribe(message -> {
            logger.info("Received PeeGeeQ message: {}", message.getPayload());
            IntegrationTestUtils.logMessage(message, "PEEGEEQ_RECEIVED");
            receivedMessages.add(message);

            // Also persist to bi-temporal store
            try {
                Instant validTime = message.getPayload().getOrderTimeAsInstant();
                eventStore.append(
                    "OrderEvent",
                    message.getPayload(),
                    validTime,
                    message.getHeaders(),
                    IntegrationTestUtils.getCorrelationId(message),
                    message.getPayload().getOrderId()
                ).join();
                logger.info("Persisted message to bi-temporal store: {}", message.getPayload().getOrderId());
            } catch (Exception e) {
                logger.error("Failed to persist to bi-temporal store", e);
            }

            messageLatch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Send messages via PeeGeeQ
        logger.info("Sending messages via PeeGeeQ...");
        producer.send(orderEvent1, headers, correlationId1).join();
        producer.send(orderEvent2, headers, correlationId2).join();
        logger.info("Messages sent successfully");

        // Wait for messages to be received and persisted
        assertTrue(IntegrationTestUtils.waitForLatch(messageLatch, 15, "message reception and persistence"),
                  "Messages should be received and persisted within 15 seconds");

        // Verify messages were received
        assertEquals(2, receivedMessages.size(), "Should receive exactly two messages");

        // Verify events were persisted to bi-temporal store
        logger.info("Querying bi-temporal store for persisted events...");
        List<BiTemporalEvent<OrderEvent>> allEvents = eventStore.query(EventQuery.all()).join();

        assertNotNull(allEvents, "Events list should not be null");
        assertEquals(2, allEvents.size(), "Should have exactly two events in bi-temporal store");

        // Find events by correlation ID
        BiTemporalEvent<OrderEvent> event1 = IntegrationTestUtils.findEventByCorrelationId(allEvents, correlationId1);
        BiTemporalEvent<OrderEvent> event2 = IntegrationTestUtils.findEventByCorrelationId(allEvents, correlationId2);

        assertNotNull(event1, "Event 1 should be found in bi-temporal store");
        assertNotNull(event2, "Event 2 should be found in bi-temporal store");

        // Verify event data matches original messages
        assertEquals(orderEvent1, event1.getPayload(), "Event 1 payload should match original");
        assertEquals(orderEvent2, event2.getPayload(), "Event 2 payload should match original");
        assertEquals(correlationId1, event1.getCorrelationId(), "Event 1 correlation ID should match");
        assertEquals(correlationId2, event2.getCorrelationId(), "Event 2 correlation ID should match");
        assertEquals(aggregateId1, event1.getAggregateId(), "Event 1 aggregate ID should match");
        assertEquals(aggregateId2, event2.getAggregateId(), "Event 2 aggregate ID should match");

        IntegrationTestUtils.logBiTemporalEvent(event1, "BITEMPORAL_EVENT_1");
        IntegrationTestUtils.logBiTemporalEvent(event2, "BITEMPORAL_EVENT_2");

        logger.info("PeeGeeQ with bi-temporal store persistence test completed successfully");
    }

    @Test
    @DisplayName("Real-time Event Subscriptions and Notifications")
    void testRealTimeEventSubscriptions() throws Exception {
        logger.info("Starting real-time event subscriptions test...");

        // Test data
        OrderEvent orderEvent = IntegrationTestUtils.createOrderEvent(
            "ORDER-201", "CUST-999", "PROCESSING", "CA"
        );
        String correlationId = "corr-201";
        String aggregateId = "ORDER-201";

        // Set up bi-temporal event subscription tracking
        List<BiTemporalEvent<OrderEvent>> subscribedEvents = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch subscriptionLatch = new CountDownLatch(1);

        // Subscribe to bi-temporal events
        logger.info("Setting up bi-temporal event subscription...");
        eventStore.subscribe("OrderEvent", message -> {
            BiTemporalEvent<OrderEvent> event = message.getPayload();
            logger.info("Received bi-temporal event subscription: {}", event.getEventId());
            IntegrationTestUtils.logBiTemporalEvent(event, "SUBSCRIPTION_RECEIVED");
            subscribedEvents.add(event);
            subscriptionLatch.countDown();
            return CompletableFuture.completedFuture(null);
        }).join();

        // Directly append event to bi-temporal store (simulating real-time processing)
        logger.info("Appending event directly to bi-temporal store...");
        BiTemporalEvent<OrderEvent> appendedEvent = eventStore.append(
            "OrderEvent",
            orderEvent,
            orderEvent.getOrderTimeAsInstant(),
            Map.of("source", "direct-append", "version", "1.0"),
            correlationId,
            aggregateId
        ).join();

        logger.info("Event appended with ID: {}", appendedEvent.getEventId());

        // Wait for subscription notification
        assertTrue(IntegrationTestUtils.waitForLatch(subscriptionLatch, 10, "bi-temporal event subscription"),
                  "Bi-temporal event subscription should be triggered within 10 seconds");

        // Verify subscription received the event
        assertEquals(1, subscribedEvents.size(), "Should receive exactly one subscribed event");
        BiTemporalEvent<OrderEvent> subscribedEvent = subscribedEvents.get(0);

        assertEquals(appendedEvent.getEventId(), subscribedEvent.getEventId(), "Event IDs should match");
        assertEquals(orderEvent, subscribedEvent.getPayload(), "Event payloads should match");
        assertEquals(correlationId, subscribedEvent.getCorrelationId(), "Correlation IDs should match");
        assertEquals(aggregateId, subscribedEvent.getAggregateId(), "Aggregate IDs should match");

        logger.info("Real-time event subscriptions test completed successfully");
    }

    @Test
    @DisplayName("End-to-End Integration: PeeGeeQ → Consumer → Bi-temporal Store → Subscription")
    void testEndToEndIntegration() throws Exception {
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

        CountDownLatch peeGeeQLatch = new CountDownLatch(expectedEventCount);
        CountDownLatch subscriptionLatch = new CountDownLatch(expectedEventCount);

        // Set up bi-temporal event subscription
        eventStore.subscribe(null, message -> { // Subscribe to all event types
            BiTemporalEvent<OrderEvent> event = message.getPayload();
            logger.info("Subscription received event: {}", event.getEventId());
            IntegrationTestUtils.logBiTemporalEvent(event, "END_TO_END_SUBSCRIPTION");
            subscribedEvents.add(event);
            subscriptionLatch.countDown();
            return CompletableFuture.completedFuture(null);
        }).join();

        // Set up PeeGeeQ consumer that persists to bi-temporal store
        consumer.subscribe(message -> {
            logger.info("PeeGeeQ consumer received message: {}", message.getPayload().getOrderId());
            IntegrationTestUtils.logMessage(message, "END_TO_END_PEEGEEQ");
            peeGeeQMessages.add(message);

            // Persist to bi-temporal store
            try {
                BiTemporalEvent<OrderEvent> event = eventStore.append(
                    "OrderEvent",
                    message.getPayload(),
                    message.getPayload().getOrderTimeAsInstant(),
                    message.getHeaders(),
                    IntegrationTestUtils.getCorrelationId(message),
                    message.getPayload().getOrderId()
                ).join();

                persistedEvents.add(event);
                logger.info("Persisted event to bi-temporal store: {}", event.getEventId());
            } catch (Exception e) {
                logger.error("Failed to persist event", e);
            }

            peeGeeQLatch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Send all test orders via PeeGeeQ
        logger.info("Sending {} test orders via PeeGeeQ...", expectedEventCount);
        for (int i = 0; i < testOrders.size(); i++) {
            OrderEvent order = testOrders.get(i);
            String correlationId = "end-to-end-" + (i + 1);
            Map<String, String> headers = Map.of(
                "source", "end-to-end-test",
                "order-index", String.valueOf(i + 1)
            );

            producer.send(order, headers, correlationId).join();
            logger.info("Sent order {}: {}", i + 1, order.getOrderId());
        }

        // Wait for all stages to complete
        assertTrue(IntegrationTestUtils.waitForLatch(peeGeeQLatch, 20, "PeeGeeQ message processing"),
                  "All PeeGeeQ messages should be processed within 20 seconds");

        assertTrue(IntegrationTestUtils.waitForLatch(subscriptionLatch, 20, "bi-temporal event subscriptions"),
                  "All bi-temporal event subscriptions should be triggered within 20 seconds");

        // Verify all stages completed successfully
        assertEquals(expectedEventCount, peeGeeQMessages.size(), "Should receive all PeeGeeQ messages");
        assertEquals(expectedEventCount, persistedEvents.size(), "Should persist all events to bi-temporal store");
        assertEquals(expectedEventCount, subscribedEvents.size(), "Should receive all subscription notifications");

        // Verify data consistency across all stages
        for (int i = 0; i < expectedEventCount; i++) {
            OrderEvent originalOrder = testOrders.get(i);
            Message<OrderEvent> peeGeeQMessage = peeGeeQMessages.get(i);
            BiTemporalEvent<OrderEvent> persistedEvent = persistedEvents.get(i);

            // Find corresponding subscribed event by correlation ID
            String expectedCorrelationId = "end-to-end-" + (i + 1);
            BiTemporalEvent<OrderEvent> subscribedEvent = subscribedEvents.stream()
                .filter(event -> expectedCorrelationId.equals(event.getCorrelationId()))
                .findFirst()
                .orElse(null);

            assertNotNull(subscribedEvent, "Should find subscribed event for correlation ID: " + expectedCorrelationId);

            // Verify data consistency
            assertEquals(originalOrder, peeGeeQMessage.getPayload(), "PeeGeeQ message payload should match original");
            assertEquals(originalOrder, persistedEvent.getPayload(), "Persisted event payload should match original");
            assertEquals(originalOrder, subscribedEvent.getPayload(), "Subscribed event payload should match original");

            assertEquals(expectedCorrelationId, IntegrationTestUtils.getCorrelationId(peeGeeQMessage), "PeeGeeQ correlation ID should match");
            assertEquals(expectedCorrelationId, persistedEvent.getCorrelationId(), "Persisted correlation ID should match");
            assertEquals(expectedCorrelationId, subscribedEvent.getCorrelationId(), "Subscribed correlation ID should match");

            assertEquals(originalOrder.getOrderId(), persistedEvent.getAggregateId(), "Aggregate ID should match order ID");
            assertEquals(originalOrder.getOrderId(), subscribedEvent.getAggregateId(), "Subscribed aggregate ID should match order ID");
        }

        // Query bi-temporal store to verify persistence
        List<BiTemporalEvent<OrderEvent>> allStoredEvents = eventStore.query(EventQuery.all()).join();
        assertTrue(allStoredEvents.size() >= expectedEventCount,
                  "Bi-temporal store should contain at least " + expectedEventCount + " events");

        logger.info("End-to-end integration test completed successfully");
        logger.info("Summary: {} PeeGeeQ messages → {} persisted events → {} subscription notifications",
                   peeGeeQMessages.size(), persistedEvents.size(), subscribedEvents.size());
    }
}
