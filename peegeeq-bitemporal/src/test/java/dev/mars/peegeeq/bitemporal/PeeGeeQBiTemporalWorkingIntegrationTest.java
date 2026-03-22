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
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Working integration tests demonstrating the successful integration between PeeGeeQ and the bi-temporal event store.
 * 
 * These tests focus on the currently working functionality:
 * 1. PeeGeeQ producer/consumer integration
 * 2. Bi-temporal store persistence
 * 3. Event correlation and data consistency
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-15
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
class PeeGeeQBiTemporalWorkingIntegrationTest {
    private static final Logger logger = LoggerFactory.getLogger(PeeGeeQBiTemporalWorkingIntegrationTest.class);
    
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
        logger.info("Setting up working integration test...");
        
        // Set system properties for PeeGeeQ configuration
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.migration.enabled", "true");
        System.setProperty("peegeeq.metrics.enabled", "true");

        // Initialize database schema using centralized schema initializer
        logger.info("Creating ALL database tables using PeeGeeQTestSchemaInitializer...");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.ALL);
        logger.info("ALL database tables created successfully");

        // Configure PeeGeeQ
        PeeGeeQConfiguration config = new PeeGeeQConfiguration();
        
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

                // Register the native factory
                PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) queueFactoryProvider);

                queueFactory = queueFactoryProvider.createFactory("native", databaseService);
                
                // Create producer and consumer
                producer = queueFactory.createProducer("order-events", OrderEvent.class);
                consumer = queueFactory.createConsumer("order-events", OrderEvent.class);
                logger.info("PeeGeeQ producer and consumer created");
                
                logger.info("Working integration test setup completed");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }
    
    @AfterEach
    void tearDown(VertxTestContext testContext) throws Exception {
        logger.info("Tearing down working integration test...");
        
        // Close synchronous resources first
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
        
        // Close manager reactively
        Future<Void> closeFuture = (manager != null)
            ? manager.closeReactive()
            : Future.succeededFuture();
        
        closeFuture
            .onSuccess(v -> {
                // Clean up system properties
                System.clearProperty("peegeeq.database.host");
                System.clearProperty("peegeeq.database.port");
                System.clearProperty("peegeeq.database.name");
                System.clearProperty("peegeeq.database.username");
                System.clearProperty("peegeeq.database.password");
                System.clearProperty("peegeeq.migration.enabled");
                System.clearProperty("peegeeq.metrics.enabled");
                
                logger.info("Working integration test teardown completed");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }
    
    @Test
    @DisplayName("PeeGeeQ Producer-Consumer Integration")
    void testPeeGeeQProducerConsumerIntegration(VertxTestContext testContext) {
        logger.info("Starting PeeGeeQ producer-consumer integration test...");
        
        // Test data
        OrderEvent orderEvent = IntegrationTestUtils.createOrderEvent(
            "ORDER-001", "CUST-123", "CREATED", "US"
        );
        String correlationId = "test-correlation-" + System.currentTimeMillis();
        Map<String, String> headers = Map.of(
            "source", "working-integration-test",
            "version", "1.0",
            "test-type", "producer-consumer"
        );
        
        // Subscribe to messages — assertions and completion happen here
        consumer.subscribe(message -> {
            testContext.verify(() -> {
                logger.info("Received PeeGeeQ message: {}", message.getPayload().getOrderId());
                IntegrationTestUtils.logMessage(message, "PRODUCER_CONSUMER_TEST");
                
                // Verify payload
                assertEquals(orderEvent, message.getPayload(), "Received payload should match sent payload");
                
                // Verify headers
                Map<String, String> receivedHeaders = message.getHeaders();
                assertNotNull(receivedHeaders, "Headers should not be null");
                assertEquals("working-integration-test", receivedHeaders.get("source"), "Source header should match");
                assertEquals("1.0", receivedHeaders.get("version"), "Version header should match");
                
                logger.info("PeeGeeQ producer-consumer integration test completed successfully");
                testContext.completeNow();
            });
            return Future.<Void>succeededFuture();
        });
        
        // Send message via PeeGeeQ
        logger.info("Sending message via PeeGeeQ...");
        producer.send(orderEvent, headers, correlationId)
            .onSuccess(v -> logger.info("Message sent successfully"))
            .onFailure(testContext::failNow);
    }
    
    @Test
    @DisplayName("PeeGeeQ to Bi-temporal Store Integration")
    void testPeeGeeQToBiTemporalStoreIntegration(VertxTestContext testContext) {
        logger.info("Starting PeeGeeQ to bi-temporal store integration test...");
        
        // Test data
        List<OrderEvent> testOrders = Arrays.asList(
            IntegrationTestUtils.createOrderEvent("ORDER-201", "CUST-001", "CREATED", "US"),
            IntegrationTestUtils.createOrderEvent("ORDER-202", "CUST-002", "PENDING", "EU"),
            IntegrationTestUtils.createOrderEvent("ORDER-203", "CUST-003", "CONFIRMED", "CA")
        );
        
        int expectedEventCount = testOrders.size();
        
        // Set up tracking
        List<BiTemporalEvent<OrderEvent>> persistedEvents = Collections.synchronizedList(new ArrayList<>());
        io.vertx.junit5.Checkpoint checkpoint = testContext.checkpoint(expectedEventCount);
        
        // Set up PeeGeeQ consumer that persists to bi-temporal store
        consumer.subscribe(message -> {
            logger.info("PeeGeeQ consumer received: {}", message.getPayload().getOrderId());
            IntegrationTestUtils.logMessage(message, "BITEMPORAL_INTEGRATION");
            
            // Determine correlation ID based on order ID
            String correlationId = null;
            String orderId = message.getPayload().getOrderId();
            if ("ORDER-201".equals(orderId)) {
                correlationId = "bitemporal-test-1";
            } else if ("ORDER-202".equals(orderId)) {
                correlationId = "bitemporal-test-2";
            } else if ("ORDER-203".equals(orderId)) {
                correlationId = "bitemporal-test-3";
            }

            return eventStore.appendBuilder()
                .eventType("OrderEvent")
                .payload(message.getPayload())
                .validTime(message.getPayload().getOrderTimeAsInstant())
                .headers(message.getHeaders())
                .correlationId(correlationId)
                .causationId(null)
                .aggregateId(message.getPayload().getOrderId())
                .execute()
                .map(event -> {
                    persistedEvents.add(event);
                    logger.info("Persisted to bi-temporal store: {} (Event ID: {})",
                               message.getPayload().getOrderId(), event.getEventId());
                    checkpoint.flag();
                    return (Void) null;
                });
        });
        
        // Send all test orders via PeeGeeQ sequentially
        logger.info("Sending {} test orders via PeeGeeQ...", expectedEventCount);
        Future<Void> sendChain = Future.succeededFuture();
        for (int i = 0; i < testOrders.size(); i++) {
            final OrderEvent order = testOrders.get(i);
            final String correlationId = "bitemporal-test-" + (i + 1);
            final int index = i;
            final Map<String, String> headers = Map.of(
                "source", "bitemporal-integration-test",
                "order-index", String.valueOf(i + 1),
                "test-batch", "working-integration"
            );
            
            sendChain = sendChain.compose(v -> {
                logger.info("Sending order {}: {}", index + 1, order.getOrderId());
                return producer.send(order, headers, correlationId);
            });
        }
        
        sendChain.onFailure(testContext::failNow);
    }
    
    @Test
    @DisplayName("Event Correlation and Data Consistency")
    void testEventCorrelationAndDataConsistency(VertxTestContext testContext) {
        logger.info("Starting event correlation and data consistency test...");
        
        // Create test event with specific data for validation
        Instant testTime = Instant.now().minusSeconds(3600); // 1 hour ago
        OrderEvent testOrder = IntegrationTestUtils.createOrderEvent(
            "ORDER-CORRELATION-TEST", "CUST-CORRELATION", "PROCESSING", "GLOBAL", testTime
        );
        
        String correlationId = "correlation-consistency-test-" + System.currentTimeMillis();
        Map<String, String> headers = Map.of(
            "source", "correlation-test",
            "test-purpose", "data-consistency",
            "custom-header", "test-value"
        );
        
        // Set up consumer that validates and persists
        consumer.subscribe(message -> {
            logger.info("Validating received message correlation...");
            
            // Validate message data
            testContext.verify(() ->
                assertEquals(testOrder, message.getPayload(), "Message payload should match exactly")
            );
            
            return eventStore.appendBuilder()
                .eventType("OrderEvent")
                .payload(message.getPayload())
                .validTime(testTime)
                .headers(message.getHeaders())
                .correlationId(correlationId)
                .causationId(null)
                .aggregateId(message.getPayload().getOrderId())
                .execute()
                .map(event -> {
                    testContext.verify(() -> {
                        assertEquals(testOrder, event.getPayload(), "Persisted payload should match original");
                        assertEquals(correlationId, event.getCorrelationId(), "Persisted correlation ID should match");
                        assertEquals(testOrder.getOrderId(), event.getAggregateId(), "Aggregate ID should match order ID");
                        assertEquals(testTime, event.getValidTime(), "Valid time should match test time");
                        
                        // Validate headers were preserved
                        Map<String, String> receivedHeaders = message.getHeaders();
                        assertEquals("correlation-test", receivedHeaders.get("source"), "Source header should be preserved");
                        assertEquals("data-consistency", receivedHeaders.get("test-purpose"), "Custom headers should be preserved");
                        
                        logger.info("Event correlation and data consistency test completed successfully");
                        testContext.completeNow();
                    });
                    return (Void) null;
                });
        });
        
        // Send the test message
        logger.info("Sending correlation test message...");
        producer.send(testOrder, headers, correlationId)
            .onFailure(testContext::failNow);
    }
}
