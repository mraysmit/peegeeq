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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.mars.peegeeq.api.*;

import dev.mars.peegeeq.api.messaging.*;
import dev.mars.peegeeq.bitemporal.BiTemporalEventStoreFactory;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.outbox.OutboxFactoryRegistrar;
import dev.mars.peegeeq.pgqueue.PgNativeFactoryRegistrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Comprehensive example demonstrating transactional integration between PG queues and bi-temporal event stores.
 * 
 * This example shows:
 * - Two PG queues (orders and payments) with producers and consumers
 * - Two corresponding bi-temporal event stores for audit and history
 * - Transactional writes ensuring consistency between queues and event stores
 * - Real-time processing with bi-temporal tracking
 * - Cross-system correlation and event ordering
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-15
 * @version 1.0
 */
public class TransactionalBiTemporalExample {
    
    private static final Logger logger = LoggerFactory.getLogger(TransactionalBiTemporalExample.class);
    
    /**
     * Order event payload for the orders queue and event store.
     */
    public static class OrderEvent {
        private final String orderId;
        private final String customerId;
        private final BigDecimal amount;
        private final String status;
        private final Instant orderTime;
        
        @JsonCreator
        public OrderEvent(@JsonProperty("orderId") String orderId,
                         @JsonProperty("customerId") String customerId,
                         @JsonProperty("amount") BigDecimal amount,
                         @JsonProperty("status") String status,
                         @JsonProperty("orderTime") Instant orderTime) {
            this.orderId = orderId;
            this.customerId = customerId;
            this.amount = amount;
            this.status = status;
            this.orderTime = orderTime;
        }
        
        // Getters
        public String getOrderId() { return orderId; }
        public String getCustomerId() { return customerId; }
        public BigDecimal getAmount() { return amount; }
        public String getStatus() { return status; }
        public Instant getOrderTime() { return orderTime; }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            OrderEvent that = (OrderEvent) o;
            return Objects.equals(orderId, that.orderId) &&
                   Objects.equals(customerId, that.customerId) &&
                   Objects.equals(amount, that.amount) &&
                   Objects.equals(status, that.status) &&
                   Objects.equals(orderTime, that.orderTime);
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(orderId, customerId, amount, status, orderTime);
        }
        
        @Override
        public String toString() {
            return "OrderEvent{" +
                    "orderId='" + orderId + '\'' +
                    ", customerId='" + customerId + '\'' +
                    ", amount=" + amount +
                    ", status='" + status + '\'' +
                    ", orderTime=" + orderTime +
                    '}';
        }
    }
    
    /**
     * Payment event payload for the payments queue and event store.
     */
    public static class PaymentEvent {
        private final String paymentId;
        private final String orderId;
        private final String customerId;
        private final BigDecimal amount;
        private final String method;
        private final String status;
        private final Instant paymentTime;
        
        @JsonCreator
        public PaymentEvent(@JsonProperty("paymentId") String paymentId,
                           @JsonProperty("orderId") String orderId,
                           @JsonProperty("customerId") String customerId,
                           @JsonProperty("amount") BigDecimal amount,
                           @JsonProperty("method") String method,
                           @JsonProperty("status") String status,
                           @JsonProperty("paymentTime") Instant paymentTime) {
            this.paymentId = paymentId;
            this.orderId = orderId;
            this.customerId = customerId;
            this.amount = amount;
            this.method = method;
            this.status = status;
            this.paymentTime = paymentTime;
        }
        
        // Getters
        public String getPaymentId() { return paymentId; }
        public String getOrderId() { return orderId; }
        public String getCustomerId() { return customerId; }
        public BigDecimal getAmount() { return amount; }
        public String getMethod() { return method; }
        public String getStatus() { return status; }
        public Instant getPaymentTime() { return paymentTime; }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PaymentEvent that = (PaymentEvent) o;
            return Objects.equals(paymentId, that.paymentId) &&
                   Objects.equals(orderId, that.orderId) &&
                   Objects.equals(customerId, that.customerId) &&
                   Objects.equals(amount, that.amount) &&
                   Objects.equals(method, that.method) &&
                   Objects.equals(status, that.status) &&
                   Objects.equals(paymentTime, that.paymentTime);
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(paymentId, orderId, customerId, amount, method, status, paymentTime);
        }
        
        @Override
        public String toString() {
            return "PaymentEvent{" +
                    "paymentId='" + paymentId + '\'' +
                    ", orderId='" + orderId + '\'' +
                    ", customerId='" + customerId + '\'' +
                    ", amount=" + amount +
                    ", method='" + method + '\'' +
                    ", status='" + status + '\'' +
                    ", paymentTime=" + paymentTime +
                    '}';
        }
    }
    
    public static void main(String[] args) {
        logger.info("Starting Transactional Bi-Temporal Example");
        
        // Start PostgreSQL container
        try (@SuppressWarnings("resource") PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
                .withDatabaseName("peegeeq_transactional")
                .withUsername("test")
                .withPassword("test")) {
            
            postgres.start();
            logger.info("PostgreSQL container started on port: {}", postgres.getFirstMappedPort());
            
            // Configure system properties
            configureSystemProperties(postgres);
            
            // Initialize PeeGeeQ
            try (PeeGeeQManager manager = new PeeGeeQManager(new PeeGeeQConfiguration())) {
                manager.start();
                logger.info("PeeGeeQ Manager started successfully");
                
                // Run the transactional bi-temporal example
                runTransactionalExample(manager).join();
                
            } catch (Exception e) {
                logger.error("Error with PeeGeeQ Manager: {}", e.getMessage(), e);
            }
            
        } catch (Exception e) {
            logger.error("Error with PostgreSQL container: {}", e.getMessage(), e);
        }
        
        logger.info("Transactional Bi-Temporal Example completed");
    }
    
    private static void configureSystemProperties(PostgreSQLContainer<?> postgres) {
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
    }
    
    private static CompletableFuture<Void> runTransactionalExample(PeeGeeQManager manager) {
        return CompletableFuture.runAsync(() -> {
            try {
                logger.info("=== Transactional Bi-Temporal Example ===");
                
                // Create database service and queue factory
                PgDatabaseService databaseService = new PgDatabaseService(manager);
                PgQueueFactoryProvider provider = new PgQueueFactoryProvider();

                // Register queue factory implementations
                PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
                OutboxFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);

                // Create bi-temporal event store factory
                BiTemporalEventStoreFactory factory = new BiTemporalEventStoreFactory(manager);

                // Create event stores for orders and payments
                try (EventStore<OrderEvent> orderEventStore = factory.createEventStore(OrderEvent.class);
                     EventStore<PaymentEvent> paymentEventStore = factory.createEventStore(PaymentEvent.class);
                     QueueFactory queueFactory = provider.createFactory("outbox", databaseService)) {

                    // Create PG queues for orders and payments
                    try (MessageProducer<OrderEvent> orderProducer = queueFactory.createProducer("orders", OrderEvent.class);
                         MessageProducer<PaymentEvent> paymentProducer = queueFactory.createProducer("payments", PaymentEvent.class);
                         MessageConsumer<OrderEvent> orderConsumer = queueFactory.createConsumer("orders", OrderEvent.class);
                         MessageConsumer<PaymentEvent> paymentConsumer = queueFactory.createConsumer("payments", PaymentEvent.class)) {
                        
                        // Set up transactional consumers
                        setupTransactionalConsumers(orderConsumer, paymentConsumer, 
                                                   orderEventStore, paymentEventStore);
                        
                        // Generate and process sample data
                        generateSampleData(orderProducer, paymentProducer);
                        
                        // Wait for processing to complete
                        Thread.sleep(5000);
                        
                        // Demonstrate bi-temporal queries
                        demonstrateBiTemporalQueries(orderEventStore, paymentEventStore);
                        
                    }
                }
                
                logger.info("=== Transactional Bi-Temporal Example Completed Successfully ===");

            } catch (Exception e) {
                logger.error("Error in transactional bi-temporal example: {}", e.getMessage(), e);
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Sets up transactional consumers that write to both PG queues and bi-temporal event stores.
     */
    private static void setupTransactionalConsumers(MessageConsumer<OrderEvent> orderConsumer,
                                                   MessageConsumer<PaymentEvent> paymentConsumer,
                                                   EventStore<OrderEvent> orderEventStore,
                                                   EventStore<PaymentEvent> paymentEventStore) {

        logger.info("Setting up transactional consumers");

        // Order consumer with bi-temporal event store integration
        orderConsumer.subscribe(message -> {
            try {
                logger.info("Processing order message: {}", message.getPayload());

                // Extract order event
                OrderEvent orderEvent = message.getPayload();

                // Write to bi-temporal event store transactionally
                BiTemporalEvent<OrderEvent> storedEvent = orderEventStore.append(
                    "OrderProcessed",
                    orderEvent,
                    orderEvent.getOrderTime(), // Valid time = when order actually occurred
                    Map.of(
                        "messageId", message.getId(),
                        "source", "order-queue",
                        "processor", "order-consumer"
                    ),
                    message.getHeaders().get("correlationId"),
                    orderEvent.getOrderId()
                ).join();

                logger.info("Order event stored in bi-temporal store: {}", storedEvent.getEventId());

                // Simulate order processing logic
                processOrder(orderEvent);

                // Message is automatically acknowledged on successful completion
                return CompletableFuture.completedFuture(null);

            } catch (Exception e) {
                logger.error("Error processing order message: {}", e.getMessage(), e);
                throw new RuntimeException(e);
            }
        });

        // Payment consumer with bi-temporal event store integration
        paymentConsumer.subscribe(message -> {
            try {
                logger.info("Processing payment message: {}", message.getPayload());

                // Extract payment event
                PaymentEvent paymentEvent = message.getPayload();

                // Write to bi-temporal event store transactionally
                BiTemporalEvent<PaymentEvent> storedEvent = paymentEventStore.append(
                    "PaymentProcessed",
                    paymentEvent,
                    paymentEvent.getPaymentTime(), // Valid time = when payment actually occurred
                    Map.of(
                        "messageId", message.getId(),
                        "source", "payment-queue",
                        "processor", "payment-consumer",
                        "relatedOrderId", paymentEvent.getOrderId()
                    ),
                    message.getHeaders().get("correlationId"),
                    paymentEvent.getPaymentId()
                ).join();

                logger.info("Payment event stored in bi-temporal store: {}", storedEvent.getEventId());

                // Simulate payment processing logic
                processPayment(paymentEvent);

                // Message is automatically acknowledged on successful completion
                return CompletableFuture.completedFuture(null);

            } catch (Exception e) {
                logger.error("Error processing payment message: {}", e.getMessage(), e);
                throw new RuntimeException(e);
            }
        });

        logger.info("Transactional consumers set up successfully");
    }

    /**
     * Generates sample order and payment data to demonstrate the system.
     */
    private static void generateSampleData(MessageProducer<OrderEvent> orderProducer,
                                         MessageProducer<PaymentEvent> paymentProducer) throws Exception {

        logger.info("Generating sample data");

        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch latch = new CountDownLatch(10); // 5 orders + 5 payments
        AtomicInteger orderCounter = new AtomicInteger(1);
        AtomicInteger paymentCounter = new AtomicInteger(1);

        try {
            // Generate orders
            for (int i = 0; i < 5; i++) {
                executor.submit(() -> {
                    try {
                        String orderId = "ORDER-" + String.format("%03d", orderCounter.getAndIncrement());
                        String customerId = "CUST-" + (100 + (orderCounter.get() % 10));

                        OrderEvent orderEvent = new OrderEvent(
                            orderId,
                            customerId,
                            new BigDecimal("99.99").add(new BigDecimal(orderCounter.get() * 10)),
                            "CREATED",
                            Instant.now().minus(orderCounter.get(), ChronoUnit.MINUTES)
                        );

                        String correlationId = UUID.randomUUID().toString();

                        orderProducer.send(
                            orderEvent,
                            Map.of("source", "order-generator", "type", "new-order"),
                            correlationId
                        ).join();

                        logger.info("Generated order: {}", orderEvent);

                    } catch (Exception e) {
                        logger.error("Error generating order: {}", e.getMessage(), e);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            // Generate payments (with slight delay to simulate real-world timing)
            Thread.sleep(1000);

            for (int i = 0; i < 5; i++) {
                final int paymentIndex = i;
                executor.submit(() -> {
                    try {
                        String paymentId = "PAY-" + String.format("%03d", paymentCounter.getAndIncrement());
                        String orderId = "ORDER-" + String.format("%03d", paymentIndex + 1);
                        String customerId = "CUST-" + (100 + (paymentIndex % 10));

                        PaymentEvent paymentEvent = new PaymentEvent(
                            paymentId,
                            orderId,
                            customerId,
                            new BigDecimal("99.99").add(new BigDecimal((paymentIndex + 1) * 10)),
                            paymentIndex % 2 == 0 ? "CREDIT_CARD" : "BANK_TRANSFER",
                            "COMPLETED",
                            Instant.now().minus(paymentIndex, ChronoUnit.MINUTES)
                        );

                        String correlationId = UUID.randomUUID().toString();

                        paymentProducer.send(
                            paymentEvent,
                            Map.of("source", "payment-generator", "type", "payment", "orderId", orderId),
                            correlationId
                        ).join();

                        logger.info("Generated payment: {}", paymentEvent);

                    } catch (Exception e) {
                        logger.error("Error generating payment: {}", e.getMessage(), e);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            // Wait for all data generation to complete
            latch.await(30, TimeUnit.SECONDS);
            logger.info("Sample data generation completed");

        } finally {
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    /**
     * Simulates order processing logic.
     */
    private static void processOrder(OrderEvent orderEvent) {
        logger.info("Processing order: {} for customer: {} amount: {}",
                   orderEvent.getOrderId(), orderEvent.getCustomerId(), orderEvent.getAmount());

        // Simulate processing time
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        logger.info("Order processed successfully: {}", orderEvent.getOrderId());
    }

    /**
     * Simulates payment processing logic.
     */
    private static void processPayment(PaymentEvent paymentEvent) {
        logger.info("Processing payment: {} for order: {} method: {} amount: {}",
                   paymentEvent.getPaymentId(), paymentEvent.getOrderId(),
                   paymentEvent.getMethod(), paymentEvent.getAmount());

        // Simulate processing time
        try {
            Thread.sleep(150);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        logger.info("Payment processed successfully: {}", paymentEvent.getPaymentId());
    }

    /**
     * Demonstrates bi-temporal queries across both event stores.
     */
    private static void demonstrateBiTemporalQueries(EventStore<OrderEvent> orderEventStore,
                                                   EventStore<PaymentEvent> paymentEventStore) {

        logger.info("\n=== Demonstrating Bi-Temporal Queries ===");

        try {
            // 1. Query all order events
            logger.info("\n1. Querying all order events...");
            var orderEvents = orderEventStore.query(EventQuery.all()).join();
            logger.info("Found {} order events:", orderEvents.size());
            orderEvents.forEach(event ->
                logger.info("  Order Event: {} - {} - Valid Time: {} - Transaction Time: {}",
                          event.getEventId(), event.getPayload().getOrderId(),
                          event.getValidTime(), event.getTransactionTime()));

            // 2. Query all payment events
            logger.info("\n2. Querying all payment events...");
            var paymentEvents = paymentEventStore.query(EventQuery.all()).join();
            logger.info("Found {} payment events:", paymentEvents.size());
            paymentEvents.forEach(event ->
                logger.info("  Payment Event: {} - {} - Valid Time: {} - Transaction Time: {}",
                          event.getEventId(), event.getPayload().getPaymentId(),
                          event.getValidTime(), event.getTransactionTime()));

            // 3. Query events by type
            logger.info("\n3. Querying by event type...");
            var processedOrders = orderEventStore.query(
                EventQuery.forEventType("OrderProcessed")
            ).join();
            logger.info("Found {} processed orders", processedOrders.size());

            var processedPayments = paymentEventStore.query(
                EventQuery.forEventType("PaymentProcessed")
            ).join();
            logger.info("Found {} processed payments", processedPayments.size());

            // 4. Temporal range queries
            logger.info("\n4. Temporal range queries...");
            Instant rangeStart = Instant.now().minus(10, ChronoUnit.MINUTES);
            Instant rangeEnd = Instant.now();

            var recentOrders = orderEventStore.query(
                EventQuery.builder()
                    .validTimeRange(new TemporalRange(rangeStart, rangeEnd))
                    .sortOrder(EventQuery.SortOrder.VALID_TIME_ASC)
                    .build()
            ).join();

            logger.info("Found {} orders in the last 10 minutes:", recentOrders.size());
            recentOrders.forEach(event ->
                logger.info("  Recent Order: {} at {}",
                          event.getPayload().getOrderId(), event.getValidTime()));

            // 5. Cross-system correlation
            logger.info("\n5. Cross-system correlation...");
            if (!orderEvents.isEmpty()) {
                String sampleOrderId = orderEvents.get(0).getPayload().getOrderId();

                // Find related payment
                var relatedPayments = paymentEvents.stream()
                    .filter(event -> sampleOrderId.equals(event.getPayload().getOrderId()))
                    .toList();

                logger.info("Order {} has {} related payments:", sampleOrderId, relatedPayments.size());
                relatedPayments.forEach(event ->
                    logger.info("  Related Payment: {} - {} - Amount: {}",
                              event.getPayload().getPaymentId(),
                              event.getPayload().getMethod(),
                              event.getPayload().getAmount()));
            }

            // 6. Statistics
            logger.info("\n6. Event store statistics...");

            var orderStats = orderEventStore.getStats().join();
            logger.info("Order Event Store Stats:");
            logger.info("  Total events: {}", orderStats.getTotalEvents());
            logger.info("  Event types: {}", orderStats.getEventCountsByType());
            logger.info("  Oldest event: {}", orderStats.getOldestEventTime());
            logger.info("  Newest event: {}", orderStats.getNewestEventTime());

            var paymentStats = paymentEventStore.getStats().join();
            logger.info("Payment Event Store Stats:");
            logger.info("  Total events: {}", paymentStats.getTotalEvents());
            logger.info("  Event types: {}", paymentStats.getEventCountsByType());
            logger.info("  Oldest event: {}", paymentStats.getOldestEventTime());
            logger.info("  Newest event: {}", paymentStats.getNewestEventTime());

        } catch (Exception e) {
            logger.error("Error in bi-temporal queries: {}", e.getMessage(), e);
        }

        logger.info("=== Bi-Temporal Queries Completed ===");
    }
}
