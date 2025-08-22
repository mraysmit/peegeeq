package dev.mars.peegeeq.examples;

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

import dev.mars.peegeeq.api.messaging.*;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.bitemporal.BiTemporalEventStoreFactory;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.pgqueue.PgNativeFactoryRegistrar;
import dev.mars.peegeeq.outbox.OutboxFactoryRegistrar;
import dev.mars.peegeeq.examples.TransactionalBiTemporalExample.OrderEvent;
import dev.mars.peegeeq.examples.TransactionalBiTemporalExample.PaymentEvent;
import dev.mars.peegeeq.outbox.OutboxMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the TransactionalBiTemporalExample class.
 * 
 * This test verifies the integration between PG queues and bi-temporal event stores,
 * ensuring transactional consistency and proper event correlation.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-15
 * @version 1.0
 */
@Testcontainers
class TransactionalBiTemporalExampleTest {
    
    private static final Logger logger = LoggerFactory.getLogger(TransactionalBiTemporalExampleTest.class);
    
    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("peegeeq_transactional_test")
            .withUsername("test")
            .withPassword("test");
    
    private final ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errContent = new ByteArrayOutputStream();
    private final PrintStream originalOut = System.out;
    private final PrintStream originalErr = System.err;
    
    private PeeGeeQManager manager;
    private QueueFactory queueFactory;
    private EventStore<OrderEvent> orderEventStore;
    private EventStore<PaymentEvent> paymentEventStore;
    private MessageProducer<OrderEvent> orderProducer;
    private MessageProducer<PaymentEvent> paymentProducer;
    private MessageConsumer<OrderEvent> orderConsumer;
    private MessageConsumer<PaymentEvent> paymentConsumer;
    
    @BeforeEach
    void setUp() {
        logger.info("=== Setting up TransactionalBiTemporalExampleTest ===");
        System.setOut(new PrintStream(outContent));
        System.setErr(new PrintStream(errContent));
        
        // Configure PeeGeeQ to use the TestContainer
        logger.info("Configuring PeeGeeQ to use TestContainer database");
        logger.info("Database URL: jdbc:postgresql://{}:{}/{}", 
                   postgres.getHost(), postgres.getFirstMappedPort(), postgres.getDatabaseName());
        
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        
        // Initialize PeeGeeQ manager
        logger.info("Initializing PeeGeeQ manager and starting services");
        PeeGeeQConfiguration config = new PeeGeeQConfiguration();
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();
        logger.info("PeeGeeQ manager started successfully");
        
        // Create database service and queue factory
        logger.info("Creating database service and queue factory");
        PgDatabaseService databaseService = new PgDatabaseService(manager);
        PgQueueFactoryProvider provider = new PgQueueFactoryProvider();

        // Register queue factory implementations
        PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
        OutboxFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);

        queueFactory = provider.createFactory("outbox", databaseService);
        logger.info("Queue factory created successfully");

        // Create bi-temporal event stores
        logger.info("Creating bi-temporal event stores");
        BiTemporalEventStoreFactory factory = new BiTemporalEventStoreFactory(manager);
        orderEventStore = factory.createEventStore(OrderEvent.class);
        paymentEventStore = factory.createEventStore(PaymentEvent.class);
        logger.info("Bi-temporal event stores created successfully");

        // Create producers and consumers
        logger.info("Creating producers and consumers");
        orderProducer = queueFactory.createProducer("test-orders", OrderEvent.class);
        paymentProducer = queueFactory.createProducer("test-payments", PaymentEvent.class);
        orderConsumer = queueFactory.createConsumer("test-orders", OrderEvent.class);
        paymentConsumer = queueFactory.createConsumer("test-payments", PaymentEvent.class);
        logger.info("Producers and consumers created successfully");
        
        logger.info("=== Setup completed ===");
    }
    
    @AfterEach
    void tearDown() {
        logger.info("=== Tearing down TransactionalBiTemporalExampleTest ===");
        System.setOut(originalOut);
        System.setErr(originalErr);
        
        // Clean up resources
        closeResource("order consumer", orderConsumer);
        closeResource("payment consumer", paymentConsumer);
        closeResource("order producer", orderProducer);
        closeResource("payment producer", paymentProducer);
        closeResource("queue factory", queueFactory);
        closeResource("order event store", orderEventStore);
        closeResource("payment event store", paymentEventStore);
        closeResource("PeeGeeQ manager", manager);
        
        // Clear system properties
        logger.info("Clearing system properties");
        System.clearProperty("peegeeq.database.host");
        System.clearProperty("peegeeq.database.port");
        System.clearProperty("peegeeq.database.name");
        System.clearProperty("peegeeq.database.username");
        System.clearProperty("peegeeq.database.password");
        logger.info("=== Teardown completed ===");
    }
    
    private void closeResource(String name, AutoCloseable resource) {
        if (resource != null) {
            try {
                logger.info("Closing {}", name);
                resource.close();
                logger.info("{} closed successfully", name);
            } catch (Exception e) {
                logger.error("Error closing {}", name, e);
            }
        }
    }
    
    @Test
    void testMainMethodExecutesWithoutErrors() {
        logger.info("=== Testing main method execution ===");
        
        // This test verifies that the main method runs without throwing exceptions
        logger.info("Executing TransactionalBiTemporalExample.main()");
        assertDoesNotThrow(() -> TransactionalBiTemporalExample.main(new String[]{}));
        logger.info("Main method executed successfully without exceptions");
        
        // Verify output contains expected messages
        String output = outContent.toString();
        logger.info("Captured output length: {} characters", output.length());
        
        assertTrue(output.contains("Starting Transactional Bi-Temporal Example"), 
                  "Output should contain startup message");
        assertTrue(output.contains("Transactional Bi-Temporal Example completed"), 
                  "Output should contain completion message");
        
        logger.info("Main method test completed successfully");
    }
    
    @Test
    void testTransactionalOrderProcessing() throws Exception {
        logger.info("=== Testing transactional order processing ===");
        
        CountDownLatch processedLatch = new CountDownLatch(1);
        AtomicInteger processedCount = new AtomicInteger(0);
        
        // Set up consumer to write to event store transactionally
        orderConsumer.subscribe(message -> {
            try {
                logger.info("Processing order message: {}", message.getPayload());

                // Get correlation ID from OutboxMessage if available
                String correlationId = null;
                if (message instanceof OutboxMessage) {
                    correlationId = ((OutboxMessage<?>) message).getCorrelationId();
                } else {
                    correlationId = message.getHeaders().get("correlationId");
                }

                OrderEvent orderEvent = message.getPayload();

                // Write to bi-temporal event store transactionally
                BiTemporalEvent<OrderEvent> storedEvent = orderEventStore.append(
                    "OrderProcessed",
                    orderEvent,
                    orderEvent.getOrderTime(),
                    Map.of(
                        "messageId", message.getId(),
                        "source", "test-order-queue",
                        "processor", "test-order-consumer"
                    ),
                    correlationId,
                    orderEvent.getOrderId()
                ).join();

                logger.info("Order event stored in bi-temporal store: {}", storedEvent.getEventId());

                processedCount.incrementAndGet();
                processedLatch.countDown();

                return CompletableFuture.completedFuture(null);

            } catch (Exception e) {
                logger.error("Error processing order message: {}", e.getMessage(), e);
                throw new RuntimeException(e);
            }
        });
        
        // Send test order
        String testId = String.valueOf(System.currentTimeMillis());
        OrderEvent testOrder = new OrderEvent(
            "TEST-ORDER-" + testId,
            "TEST-CUST-123",
            new BigDecimal("199.99"),
            "CREATED",
            Instant.now().minus(1, ChronoUnit.HOURS)
        );
        
        String correlationId = UUID.randomUUID().toString();

        logger.info("Sending test order: {}", testOrder);
        orderProducer.send(
            testOrder,
            Map.of("source", "test", "type", "test-order"),
            correlationId
        ).join();

        // Wait for processing
        assertTrue(processedLatch.await(10, TimeUnit.SECONDS),
                  "Order should be processed within 10 seconds");
        assertEquals(1, processedCount.get(), "Exactly one order should be processed");
        
        // Verify event was stored in bi-temporal store
        List<BiTemporalEvent<OrderEvent>> storedEvents = orderEventStore.query(
            EventQuery.forAggregate("TEST-ORDER-" + testId)
        ).join();
        
        assertEquals(1, storedEvents.size(), "Should have exactly one stored event");
        
        BiTemporalEvent<OrderEvent> storedEvent = storedEvents.get(0);
        assertEquals("OrderProcessed", storedEvent.getEventType());
        assertEquals("TEST-ORDER-" + testId, storedEvent.getPayload().getOrderId());
        assertEquals(correlationId, storedEvent.getCorrelationId());
        
        logger.info("Transactional order processing test completed successfully");
    }

    @Test
    void testTransactionalPaymentProcessing() throws Exception {
        logger.info("=== Testing transactional payment processing ===");

        CountDownLatch processedLatch = new CountDownLatch(1);
        AtomicInteger processedCount = new AtomicInteger(0);

        // Set up consumer to write to event store transactionally
        paymentConsumer.subscribe(message -> {
            try {
                logger.info("Processing payment message: {}", message.getPayload());

                // Get correlation ID from OutboxMessage if available
                String correlationId = null;
                if (message instanceof OutboxMessage) {
                    correlationId = ((OutboxMessage<?>) message).getCorrelationId();
                } else {
                    correlationId = message.getHeaders().get("correlationId");
                }

                PaymentEvent paymentEvent = message.getPayload();

                // Write to bi-temporal event store transactionally
                BiTemporalEvent<PaymentEvent> storedEvent = paymentEventStore.append(
                    "PaymentProcessed",
                    paymentEvent,
                    paymentEvent.getPaymentTime(),
                    Map.of(
                        "messageId", message.getId(),
                        "source", "test-payment-queue",
                        "processor", "test-payment-consumer",
                        "relatedOrderId", paymentEvent.getOrderId()
                    ),
                    correlationId,
                    paymentEvent.getPaymentId()
                ).join();

                logger.info("Payment event stored in bi-temporal store: {}", storedEvent.getEventId());

                processedCount.incrementAndGet();
                processedLatch.countDown();

                return CompletableFuture.completedFuture(null);

            } catch (Exception e) {
                logger.error("Error processing payment message: {}", e.getMessage(), e);
                throw new RuntimeException(e);
            }
        });

        // Send test payment
        String testId = String.valueOf(System.currentTimeMillis());
        PaymentEvent testPayment = new PaymentEvent(
            "TEST-PAY-" + testId,
            "TEST-ORDER-123",
            "TEST-CUST-456",
            new BigDecimal("299.99"),
            "CREDIT_CARD",
            "COMPLETED",
            Instant.now().minus(30, ChronoUnit.MINUTES)
        );

        String correlationId = UUID.randomUUID().toString();

        logger.info("Sending test payment: {}", testPayment);
        paymentProducer.send(
            testPayment,
            Map.of("source", "test", "type", "test-payment", "orderId", "TEST-ORDER-123"),
            correlationId
        ).join();

        // Wait for processing
        assertTrue(processedLatch.await(10, TimeUnit.SECONDS),
                  "Payment should be processed within 10 seconds");
        assertEquals(1, processedCount.get(), "Exactly one payment should be processed");

        // Verify event was stored in bi-temporal store
        List<BiTemporalEvent<PaymentEvent>> storedEvents = paymentEventStore.query(
            EventQuery.forAggregate("TEST-PAY-" + testId)
        ).join();

        assertEquals(1, storedEvents.size(), "Should have exactly one stored event");

        BiTemporalEvent<PaymentEvent> storedEvent = storedEvents.get(0);
        assertEquals("PaymentProcessed", storedEvent.getEventType());
        assertEquals("TEST-PAY-" + testId, storedEvent.getPayload().getPaymentId());
        assertEquals("TEST-ORDER-123", storedEvent.getPayload().getOrderId());
        assertEquals(correlationId, storedEvent.getCorrelationId());

        logger.info("Transactional payment processing test completed successfully");
    }

    @Test
    void testCrossSystemCorrelation() throws Exception {
        logger.info("=== Testing cross-system correlation ===");

        CountDownLatch orderLatch = new CountDownLatch(1);
        CountDownLatch paymentLatch = new CountDownLatch(1);

        String testOrderId = "CORR-ORDER-" + System.currentTimeMillis();
        String testPaymentId = "CORR-PAY-" + System.currentTimeMillis();
        String correlationId = UUID.randomUUID().toString();

        // Set up order consumer
        orderConsumer.subscribe(message -> {
            try {
                OrderEvent orderEvent = message.getPayload();
                orderEventStore.append(
                    "OrderProcessed",
                    orderEvent,
                    orderEvent.getOrderTime(),
                    Map.of("messageId", message.getId(), "source", "test"),
                    message.getHeaders().get("correlationId"),
                    orderEvent.getOrderId()
                ).join();

                orderLatch.countDown();
                return CompletableFuture.completedFuture(null);

            } catch (Exception e) {
                logger.error("Error processing order", e);
                throw new RuntimeException(e);
            }
        });

        // Set up payment consumer
        paymentConsumer.subscribe(message -> {
            try {
                // Get correlation ID from OutboxMessage if available
                String messageCorrelationId = null;
                if (message instanceof OutboxMessage) {
                    messageCorrelationId = ((OutboxMessage<?>) message).getCorrelationId();
                } else {
                    messageCorrelationId = message.getHeaders().get("correlationId");
                }

                PaymentEvent paymentEvent = message.getPayload();
                paymentEventStore.append(
                    "PaymentProcessed",
                    paymentEvent,
                    paymentEvent.getPaymentTime(),
                    Map.of("messageId", message.getId(), "source", "test",
                          "relatedOrderId", paymentEvent.getOrderId()),
                    messageCorrelationId,
                    paymentEvent.getPaymentId()
                ).join();

                paymentLatch.countDown();
                return CompletableFuture.completedFuture(null);

            } catch (Exception e) {
                logger.error("Error processing payment", e);
                throw new RuntimeException(e);
            }
        });

        // Send correlated order and payment
        OrderEvent testOrder = new OrderEvent(
            testOrderId, "CORR-CUST-789", new BigDecimal("399.99"), "CREATED",
            Instant.now().minus(2, ChronoUnit.HOURS)
        );

        PaymentEvent testPayment = new PaymentEvent(
            testPaymentId, testOrderId, "CORR-CUST-789", new BigDecimal("399.99"),
            "BANK_TRANSFER", "COMPLETED", Instant.now().minus(1, ChronoUnit.HOURS)
        );

        logger.info("Sending correlated order and payment");
        orderProducer.send(testOrder, Map.of("source", "test"), correlationId).join();
        paymentProducer.send(testPayment, Map.of("source", "test", "orderId", testOrderId), correlationId).join();

        // Wait for processing
        assertTrue(orderLatch.await(10, TimeUnit.SECONDS), "Order should be processed");
        assertTrue(paymentLatch.await(10, TimeUnit.SECONDS), "Payment should be processed");

        // Verify correlation in bi-temporal stores
        List<BiTemporalEvent<OrderEvent>> orderEvents = orderEventStore.query(
            EventQuery.forAggregate(testOrderId)
        ).join();

        List<BiTemporalEvent<PaymentEvent>> paymentEvents = paymentEventStore.query(
            EventQuery.forAggregate(testPaymentId)
        ).join();

        assertEquals(1, orderEvents.size(), "Should have one order event");
        assertEquals(1, paymentEvents.size(), "Should have one payment event");

        // Verify correlation
        BiTemporalEvent<OrderEvent> orderEvent = orderEvents.get(0);
        BiTemporalEvent<PaymentEvent> paymentEvent = paymentEvents.get(0);

        assertEquals(correlationId, orderEvent.getCorrelationId());
        assertEquals(correlationId, paymentEvent.getCorrelationId());
        assertEquals(testOrderId, paymentEvent.getPayload().getOrderId());

        logger.info("Cross-system correlation test completed successfully");
    }

    @Test
    void testEventStoreStatistics() throws Exception {
        logger.info("=== Testing event store statistics ===");

        // Add some test events
        String testId = String.valueOf(System.currentTimeMillis());

        OrderEvent order1 = new OrderEvent("STATS-ORDER-1-" + testId, "CUST-1",
                                          new BigDecimal("100.00"), "CREATED", Instant.now());
        OrderEvent order2 = new OrderEvent("STATS-ORDER-2-" + testId, "CUST-2",
                                          new BigDecimal("200.00"), "CREATED", Instant.now());

        PaymentEvent payment1 = new PaymentEvent("STATS-PAY-1-" + testId, "STATS-ORDER-1-" + testId, "CUST-1",
                                                new BigDecimal("100.00"), "CREDIT_CARD", "COMPLETED", Instant.now());

        // Store events directly
        orderEventStore.append("TestOrder", order1, Instant.now(), Map.of(), "test-corr-1", order1.getOrderId()).join();
        orderEventStore.append("TestOrder", order2, Instant.now(), Map.of(), "test-corr-2", order2.getOrderId()).join();
        paymentEventStore.append("TestPayment", payment1, Instant.now(), Map.of(), "test-corr-3", payment1.getPaymentId()).join();

        // Get statistics
        EventStore.EventStoreStats orderStats = orderEventStore.getStats().join();
        EventStore.EventStoreStats paymentStats = paymentEventStore.getStats().join();

        // Verify statistics
        assertTrue(orderStats.getTotalEvents() >= 2, "Should have at least 2 order events");
        assertTrue(paymentStats.getTotalEvents() >= 1, "Should have at least 1 payment event");

        assertNotNull(orderStats.getEventCountsByType());
        assertNotNull(paymentStats.getEventCountsByType());

        logger.info("Order store stats: {} events", orderStats.getTotalEvents());
        logger.info("Payment store stats: {} events", paymentStats.getTotalEvents());

        logger.info("Event store statistics test completed successfully");
    }
}
