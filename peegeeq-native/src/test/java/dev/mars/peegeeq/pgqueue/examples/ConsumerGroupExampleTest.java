package dev.mars.peegeeq.pgqueue.examples;

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
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.*;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.pgqueue.PgNativeFactoryRegistrar;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Map;
import java.util.Properties;
import java.util.Set;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test demonstrating consumer groups with message filtering and routing.
 * Migrated from ConsumerGroupExample.java to proper JUnit test.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-14
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsumerGroupExampleTest {
    
    private static final Logger logger = LoggerFactory.getLogger(ConsumerGroupExampleTest.class);
    
    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();
    
    private PeeGeeQManager manager;
    private QueueFactory nativeFactory;
    private MessageProducer<OrderEvent> producer;
    
    @BeforeEach
    void setUp(VertxTestContext testContext) throws Exception {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        logger.info("=== Setting up Consumer Group Example Test ===");

        // Configure PeeGeeQ to use container database
        Properties testProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .build();

        // Ensure required schema exists before starting PeeGeeQ
        PeeGeeQTestSchemaInitializer.initializeSchema(
                postgres,
                PostgreSQLTestConstants.TEST_SCHEMA,
                SchemaComponent.NATIVE_QUEUE,
                SchemaComponent.OUTBOX,
                SchemaComponent.DEAD_LETTER_QUEUE
        );

        // Initialize PeeGeeQ Manager
        manager = new PeeGeeQManager(
                new PeeGeeQConfiguration("default", testProps),
                new SimpleMeterRegistry());

        manager.start().onSuccess(v -> {
            logger.info("PeeGeeQ Manager started successfully");

            // Create database service and factory provider
            DatabaseService databaseService = new PgDatabaseService(manager);
            QueueFactoryProvider provider = new PgQueueFactoryProvider();

            // Register native queue factory implementation
            PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);

            // Create native queue factory
            nativeFactory = provider.createFactory("native", databaseService);

            // Create producer for sending test messages
            producer = nativeFactory.createProducer("order-events", OrderEvent.class);

            logger.info("Consumer Group Example Test setup completed");
            testContext.completeNow();
        }).onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "setUp timed out");
    }
    
    @AfterEach
    void tearDown(VertxTestContext testContext) throws Exception {
        logger.info("Tearing down: closing resources and manager");
        logger.info(" Cleaning up Consumer Group Example Test");

        Future<Void> factoryClose = nativeFactory != null ? nativeFactory.close() : Future.succeededFuture();
        nativeFactory = null;
        factoryClose.transform(factoryResult -> {
            Future<Void> managerClose = manager != null ? manager.closeReactive() : Future.succeededFuture();
            manager = null;
            return managerClose.transform(managerResult ->
                combinedCloseResult(factoryResult.cause(), managerResult.cause()));
        })
        .onSuccess(v -> testContext.completeNow())
        .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "tearDown timed out");

        logger.info("Consumer Group Example Test cleanup completed");
    }

    private Future<Void> combinedCloseResult(Throwable factoryFailure, Throwable managerFailure) {
        if (factoryFailure != null) {
            if (managerFailure != null) {
                factoryFailure.addSuppressed(managerFailure);
            }
            return Future.failedFuture(factoryFailure);
        }
        return managerFailure == null ? Future.succeededFuture() : Future.failedFuture(managerFailure);
    }
    
    @Test
    void testConsumerGroupsWithMessageFiltering(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== Testing Consumer Groups with Message Filtering ===");
        
        // Counters to track message processing
        AtomicInteger orderProcessingCount = new AtomicInteger(0);
        AtomicInteger paymentProcessingCount = new AtomicInteger(0);
        AtomicInteger analyticsCount = new AtomicInteger(0);
        
        // Create consumer groups
        ConsumerGroup<OrderEvent> orderGroup = createOrderProcessingGroup(nativeFactory, orderProcessingCount, vertx);
        ConsumerGroup<OrderEvent> paymentGroup = createPaymentProcessingGroup(nativeFactory, paymentProcessingCount, vertx);
        ConsumerGroup<OrderEvent> analyticsGroup = createAnalyticsGroup(nativeFactory, analyticsCount, vertx);
        
        int messageCount = 20;
        orderGroup.start()
            .compose(v -> paymentGroup.start())
            .compose(v -> analyticsGroup.start())
            .compose(v -> sendTestMessages(producer, messageCount))
            .compose(v -> waitForAllGroups(
                vertx, orderProcessingCount, paymentProcessingCount, analyticsCount))
            .compose(v -> orderGroup.stopGracefully())
            .compose(v -> paymentGroup.stopGracefully())
            .compose(v -> analyticsGroup.stopGracefully())
            .onSuccess(v -> testContext.verify(() -> {
                    assertTrue(orderProcessingCount.get() > 0, "Order processing group should have processed messages");
                    assertTrue(paymentProcessingCount.get() > 0, "Payment processing group should have processed messages");
                    assertTrue(analyticsCount.get() > 0, "Analytics group should have processed messages");
                    
                    logger.info("Order Processing: {} messages", orderProcessingCount.get());
                    logger.info("Payment Processing: {} messages", paymentProcessingCount.get());
                    logger.info("Analytics: {} messages", analyticsCount.get());
                    
                    logger.info("Consumer Groups with Message Filtering test completed successfully!");
                    testContext.completeNow();
                }))
            .onFailure(testContext::failNow);
        
        assertTrue(testContext.awaitCompletion(20, TimeUnit.SECONDS), "All consumer groups should process messages within 20 seconds");
    }
    
    private ConsumerGroup<OrderEvent> createOrderProcessingGroup(QueueFactory factory, AtomicInteger counter, Vertx vertx) throws Exception {
        logger.info("Creating Order Processing consumer group...");
        
        ConsumerGroup<OrderEvent> orderGroup = factory.createConsumerGroup(
            "OrderProcessing", "order-events", OrderEvent.class);
        
        // Add region-specific consumers
        orderGroup.addConsumer("US-Consumer", 
            createOrderHandler("US", counter, vertx), 
            MessageFilter.byRegion(Set.of("US")));
        
        orderGroup.addConsumer("EU-Consumer", 
            createOrderHandler("EU", counter, vertx), 
            MessageFilter.byRegion(Set.of("EU")));
        
        orderGroup.addConsumer("ASIA-Consumer", 
            createOrderHandler("ASIA", counter, vertx), 
            MessageFilter.byRegion(Set.of("ASIA")));
        
        logger.info("Order Processing group configured with {} consumers", orderGroup.getActiveConsumerCount());
        return orderGroup;
    }
    
    private ConsumerGroup<OrderEvent> createPaymentProcessingGroup(QueueFactory factory, AtomicInteger counter, Vertx vertx) throws Exception {
        logger.info("Creating Payment Processing consumer group...");
        
        ConsumerGroup<OrderEvent> paymentGroup = factory.createConsumerGroup(
            "PaymentProcessing", "order-events", OrderEvent.class);
        
        // Add priority-based consumers
        paymentGroup.addConsumer("HighPriority-Consumer", 
            createPaymentHandler("HIGH", counter, vertx), 
            MessageFilter.byPriority("HIGH"));
        
        paymentGroup.addConsumer("Normal-Consumer", 
            createPaymentHandler("NORMAL", counter, vertx), 
            MessageFilter.byPriority("NORMAL"));
        
        logger.info("Payment Processing group configured with {} consumers", paymentGroup.getActiveConsumerCount());
        return paymentGroup;
    }
    
    private ConsumerGroup<OrderEvent> createAnalyticsGroup(QueueFactory factory, AtomicInteger counter, Vertx vertx) throws Exception {
        logger.info("Creating Analytics consumer group...");
        
        ConsumerGroup<OrderEvent> analyticsGroup = factory.createConsumerGroup(
            "Analytics", "order-events", OrderEvent.class);
        
        // Add consumers for different message types
        analyticsGroup.addConsumer("Premium-Consumer", 
            createAnalyticsHandler("PREMIUM", counter, vertx), 
            MessageFilter.byType(Set.of("PREMIUM")));
        
        analyticsGroup.addConsumer("Standard-Consumer", 
            createAnalyticsHandler("STANDARD", counter, vertx), 
            MessageFilter.byType(Set.of("STANDARD")));
        
        // Add a consumer that accepts all messages for audit
        analyticsGroup.addConsumer("Audit-Consumer", 
            createAnalyticsHandler("ALL", counter, vertx), 
            MessageFilter.acceptAll());
        
        logger.info("Analytics group configured with {} consumers", analyticsGroup.getActiveConsumerCount());
        return analyticsGroup;
    }
    
    private MessageHandler<OrderEvent> createOrderHandler(String region, AtomicInteger counter, Vertx vertx) {
        return message -> {
            OrderEvent event = message.getPayload();
            logger.info("[OrderProcessing-{}] Processing order: {} (amount: ${:.2f})", 
                region, event.getOrderId(), event.getAmount());
            
            // Simulate processing time with Vert.x timer
            Promise<Void> promise = Promise.promise();
            vertx.setTimer(100, id -> {
                counter.incrementAndGet();
                promise.complete();
            });
            return promise.future();
        };
    }
    
    private MessageHandler<OrderEvent> createPaymentHandler(String priority, AtomicInteger counter, Vertx vertx) {
        return message -> {
            OrderEvent event = message.getPayload();
            Map<String, String> headers = message.getHeaders();
            
            logger.info("[PaymentProcessing-{}] Processing payment for order: {} (priority: {})", 
                priority, event.getOrderId(), headers.get("priority"));
            
            // High priority messages process faster
            int processingTime = "HIGH".equals(priority) ? 50 : 200;
            Promise<Void> promise = Promise.promise();
            vertx.setTimer(processingTime, id -> {
                counter.incrementAndGet();
                promise.complete();
            });
            return promise.future();
        };
    }
    
    private MessageHandler<OrderEvent> createAnalyticsHandler(String type, AtomicInteger counter, Vertx vertx) {
        return message -> {
            OrderEvent event = message.getPayload();
            Map<String, String> headers = message.getHeaders();

            logger.info("[Analytics-{}] Analyzing order: {} (type: {}, region: {})",
                type, event.getOrderId(), headers.get("type"), headers.get("region"));

            // Analytics processing with Vert.x timer
            Promise<Void> promise = Promise.promise();
            vertx.setTimer(25, id -> {
                counter.incrementAndGet();
                promise.complete();
            });
            return promise.future();
        };
    }

    private Future<Void> sendTestMessages(MessageProducer<OrderEvent> producer, int messageCount) {
        logger.info("Sending {} test messages with different routing headers...", messageCount);

        String[] regions = {"US", "EU", "ASIA"};
        String[] priorities = {"HIGH", "NORMAL"};
        String[] types = {"PREMIUM", "STANDARD"};

        Future<Void> sends = Future.succeededFuture();
        for (int i = 1; i <= messageCount; i++) {
            int messageNumber = i;
            sends = sends.compose(v -> {
                OrderEvent event = new OrderEvent(
                    "ORDER-" + messageNumber,
                    "CREATED",
                    100.0 + (messageNumber * 10),
                    "customer-" + messageNumber
                );

                // Create routing headers
                String region = regions[messageNumber % regions.length];
                String priority = priorities[messageNumber % priorities.length];
                String type = types[messageNumber % types.length];

                Map<String, String> headers = Map.of(
                    "region", region,
                    "priority", priority,
                    "type", type,
                    "source", "order-service",
                    "version", "1.0"
                );

                return producer.send(event, headers, "correlation-" + messageNumber, region + "-" + priority)
                    .onSuccess(ignored -> logger.debug(
                        "Sent message for order {} with headers: {}", event.getOrderId(), headers));
            });
        }
        return sends.onSuccess(v -> logger.info("Finished sending {} test messages", messageCount));
    }

    private Future<Void> waitForAllGroups(Vertx vertx,
                                          AtomicInteger orderProcessingCount,
                                          AtomicInteger paymentProcessingCount,
                                          AtomicInteger analyticsCount) {
        logger.info("Waiting for message processing...");
        Promise<Void> processed = Promise.promise();
        long periodicId = vertx.setPeriodic(100, id -> {
            if (orderProcessingCount.get() > 0
                && paymentProcessingCount.get() > 0
                && analyticsCount.get() > 0) {
                processed.tryComplete();
            }
        });
        long timeoutId = vertx.setTimer(9_000, id -> processed.tryFail(
            "Timed out waiting for all consumer groups: order=" + orderProcessingCount.get()
                + ", payment=" + paymentProcessingCount.get()
                + ", analytics=" + analyticsCount.get()));
        return processed.future().eventually(() -> {
            vertx.cancelTimer(periodicId);
            vertx.cancelTimer(timeoutId);
            return Future.succeededFuture();
        });
    }

    /**
     * Simple order event class for testing.
     */
    public static class OrderEvent {
        private String orderId;
        private String status;
        private Double amount;
        private String customerId;

        public OrderEvent() {}

        public OrderEvent(String orderId, String status, Double amount, String customerId) {
            this.orderId = orderId;
            this.status = status;
            this.amount = amount;
            this.customerId = customerId;
        }

        // Getters and setters
        public String getOrderId() { return orderId; }
        public void setOrderId(String orderId) { this.orderId = orderId; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }

        public Double getAmount() { return amount; }
        public void setAmount(Double amount) { this.amount = amount; }

        public String getCustomerId() { return customerId; }
        public void setCustomerId(String customerId) { this.customerId = customerId; }
    }
}
