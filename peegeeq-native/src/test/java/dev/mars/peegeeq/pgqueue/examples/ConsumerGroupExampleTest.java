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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
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
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsumerGroupExampleTest {
    
    private static final Logger logger = LoggerFactory.getLogger(ConsumerGroupExampleTest.class);
    
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
            .withDatabaseName("peegeeq_consumer_demo")
            .withUsername("postgres")
            .withPassword("password")
            .withSharedMemorySize(256 * 1024 * 1024L)
            .withReuse(false);
    
    private PeeGeeQManager manager;
    private QueueFactory nativeFactory;
    private MessageProducer<OrderEvent> producer;
    
    @BeforeEach
    void setUp() throws Exception {
        logger.info("=== Setting up Consumer Group Example Test ===");

        // Configure PeeGeeQ to use container database
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());

        // Ensure required schema exists before starting PeeGeeQ
        PeeGeeQTestSchemaInitializer.initializeSchema(
                postgres,
                SchemaComponent.NATIVE_QUEUE,
                SchemaComponent.OUTBOX,
                SchemaComponent.DEAD_LETTER_QUEUE
        );

        // Initialize PeeGeeQ Manager
        manager = new PeeGeeQManager(
                new PeeGeeQConfiguration("development"),
                new SimpleMeterRegistry());

        manager.start();
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
        
        logger.info("✅ Consumer Group Example Test setup completed");
    }
    
    @AfterEach
    void tearDown() throws Exception {
        logger.info("🧹 Cleaning up Consumer Group Example Test");
        
        if (manager != null) {
            manager.close();
        }
        
        // Clear system properties
        System.clearProperty("peegeeq.database.host");
        System.clearProperty("peegeeq.database.port");
        System.clearProperty("peegeeq.database.name");
        System.clearProperty("peegeeq.database.username");
        System.clearProperty("peegeeq.database.password");
        
        logger.info("✅ Consumer Group Example Test cleanup completed");
    }
    
    @Test
    void testConsumerGroupsWithMessageFiltering() throws Exception {
        logger.info("=== Testing Consumer Groups with Message Filtering ===");
        
        // Counters to track message processing
        AtomicInteger orderProcessingCount = new AtomicInteger(0);
        AtomicInteger paymentProcessingCount = new AtomicInteger(0);
        AtomicInteger analyticsCount = new AtomicInteger(0);
        
        // Create consumer groups
        ConsumerGroup<OrderEvent> orderGroup = createOrderProcessingGroup(nativeFactory, orderProcessingCount);
        ConsumerGroup<OrderEvent> paymentGroup = createPaymentProcessingGroup(nativeFactory, paymentProcessingCount);
        ConsumerGroup<OrderEvent> analyticsGroup = createAnalyticsGroup(nativeFactory, analyticsCount);
        
        // Send test messages
        int messageCount = 20;
        sendTestMessages(producer, messageCount);
        
        // Wait for message processing
        logger.info("Waiting for message processing...");
        Thread.sleep(5000);
        
        // Verify that messages were processed by different consumer groups
        assertTrue(orderProcessingCount.get() > 0, "Order processing group should have processed messages");
        assertTrue(paymentProcessingCount.get() > 0, "Payment processing group should have processed messages");
        assertTrue(analyticsCount.get() > 0, "Analytics group should have processed messages");
        
        logger.info("Order Processing: {} messages", orderProcessingCount.get());
        logger.info("Payment Processing: {} messages", paymentProcessingCount.get());
        logger.info("Analytics: {} messages", analyticsCount.get());
        
        // Stop consumer groups
        orderGroup.stop();
        paymentGroup.stop();
        analyticsGroup.stop();
        
        logger.info("✅ Consumer Groups with Message Filtering test completed successfully!");
    }
    
    private ConsumerGroup<OrderEvent> createOrderProcessingGroup(QueueFactory factory, AtomicInteger counter) throws Exception {
        logger.info("Creating Order Processing consumer group...");
        
        ConsumerGroup<OrderEvent> orderGroup = factory.createConsumerGroup(
            "OrderProcessing", "order-events", OrderEvent.class);
        
        // Add region-specific consumers
        orderGroup.addConsumer("US-Consumer", 
            createOrderHandler("US", counter), 
            MessageFilter.byRegion(Set.of("US")));
        
        orderGroup.addConsumer("EU-Consumer", 
            createOrderHandler("EU", counter), 
            MessageFilter.byRegion(Set.of("EU")));
        
        orderGroup.addConsumer("ASIA-Consumer", 
            createOrderHandler("ASIA", counter), 
            MessageFilter.byRegion(Set.of("ASIA")));
        
        orderGroup.start();
        logger.info("Order Processing group started with {} consumers", orderGroup.getActiveConsumerCount());
        return orderGroup;
    }
    
    private ConsumerGroup<OrderEvent> createPaymentProcessingGroup(QueueFactory factory, AtomicInteger counter) throws Exception {
        logger.info("Creating Payment Processing consumer group...");
        
        ConsumerGroup<OrderEvent> paymentGroup = factory.createConsumerGroup(
            "PaymentProcessing", "order-events", OrderEvent.class);
        
        // Add priority-based consumers
        paymentGroup.addConsumer("HighPriority-Consumer", 
            createPaymentHandler("HIGH", counter), 
            MessageFilter.byPriority("HIGH"));
        
        paymentGroup.addConsumer("Normal-Consumer", 
            createPaymentHandler("NORMAL", counter), 
            MessageFilter.byPriority("NORMAL"));
        
        paymentGroup.start();
        logger.info("Payment Processing group started with {} consumers", paymentGroup.getActiveConsumerCount());
        return paymentGroup;
    }
    
    private ConsumerGroup<OrderEvent> createAnalyticsGroup(QueueFactory factory, AtomicInteger counter) throws Exception {
        logger.info("Creating Analytics consumer group...");
        
        ConsumerGroup<OrderEvent> analyticsGroup = factory.createConsumerGroup(
            "Analytics", "order-events", OrderEvent.class);
        
        // Add consumers for different message types
        analyticsGroup.addConsumer("Premium-Consumer", 
            createAnalyticsHandler("PREMIUM", counter), 
            MessageFilter.byType(Set.of("PREMIUM")));
        
        analyticsGroup.addConsumer("Standard-Consumer", 
            createAnalyticsHandler("STANDARD", counter), 
            MessageFilter.byType(Set.of("STANDARD")));
        
        // Add a consumer that accepts all messages for audit
        analyticsGroup.addConsumer("Audit-Consumer", 
            createAnalyticsHandler("ALL", counter), 
            MessageFilter.acceptAll());
        
        analyticsGroup.start();
        logger.info("Analytics group started with {} consumers", analyticsGroup.getActiveConsumerCount());
        return analyticsGroup;
    }
    
    private MessageHandler<OrderEvent> createOrderHandler(String region, AtomicInteger counter) {
        return message -> {
            OrderEvent event = message.getPayload();
            logger.info("[OrderProcessing-{}] Processing order: {} (amount: ${:.2f})", 
                region, event.getOrderId(), event.getAmount());
            
            counter.incrementAndGet();
            
            // Simulate processing time
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            return CompletableFuture.completedFuture(null);
        };
    }
    
    private MessageHandler<OrderEvent> createPaymentHandler(String priority, AtomicInteger counter) {
        return message -> {
            OrderEvent event = message.getPayload();
            Map<String, String> headers = message.getHeaders();
            
            logger.info("[PaymentProcessing-{}] Processing payment for order: {} (priority: {})", 
                priority, event.getOrderId(), headers.get("priority"));
            
            counter.incrementAndGet();
            
            // High priority messages process faster
            int processingTime = "HIGH".equals(priority) ? 50 : 200;
            try {
                Thread.sleep(processingTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            return CompletableFuture.completedFuture(null);
        };
    }
    
    private MessageHandler<OrderEvent> createAnalyticsHandler(String type, AtomicInteger counter) {
        return message -> {
            OrderEvent event = message.getPayload();
            Map<String, String> headers = message.getHeaders();

            logger.info("[Analytics-{}] Analyzing order: {} (type: {}, region: {})",
                type, event.getOrderId(), headers.get("type"), headers.get("region"));

            counter.incrementAndGet();

            // Analytics processing is typically fast
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            return CompletableFuture.completedFuture(null);
        };
    }

    private void sendTestMessages(MessageProducer<OrderEvent> producer, int messageCount) throws Exception {
        logger.info("Sending {} test messages with different routing headers...", messageCount);

        String[] regions = {"US", "EU", "ASIA"};
        String[] priorities = {"HIGH", "NORMAL"};
        String[] types = {"PREMIUM", "STANDARD"};

        for (int i = 1; i <= messageCount; i++) {
            OrderEvent event = new OrderEvent(
                "ORDER-" + i,
                "CREATED",
                100.0 + (i * 10),
                "customer-" + i
            );

            // Create routing headers
            String region = regions[i % regions.length];
            String priority = priorities[i % priorities.length];
            String type = types[i % types.length];

            Map<String, String> headers = Map.of(
                "region", region,
                "priority", priority,
                "type", type,
                "source", "order-service",
                "version", "1.0"
            );

            producer.send(event, headers, "correlation-" + i, region + "-" + priority)
                .whenComplete((result, error) -> {
                    if (error != null) {
                        logger.error("Failed to send message for order {}: {}", event.getOrderId(), error.getMessage());
                    } else {
                        logger.debug("Sent message for order {} with headers: {}", event.getOrderId(), headers);
                    }
                });

            // Small delay between messages
            Thread.sleep(100);
        }

        logger.info("Finished sending {} test messages", messageCount);
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
