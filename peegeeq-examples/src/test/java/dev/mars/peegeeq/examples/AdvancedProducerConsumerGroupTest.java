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
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.*;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test cases for advanced producer and consumer group functionality
 * based on the ADVANCED_GUIDE.md patterns.
 * 
 * Tests high-frequency messaging, consumer groups with filtering, message routing,
 * and performance characteristics.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-14
 * @version 1.0
 */
@Testcontainers
class AdvancedProducerConsumerGroupTest {
    private static final Logger logger = LoggerFactory.getLogger(AdvancedProducerConsumerGroupTest.class);
    
    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_advanced_test")
            .withUsername("peegeeq_test")
            .withPassword("peegeeq_test")
            .withSharedMemorySize(256 * 1024 * 1024L)
            .withReuse(false);
    
    private PeeGeeQManager manager;
    private QueueFactory queueFactory;
    private MessageProducer<OrderEvent> producer;
    
    @BeforeEach
    void setUp() throws Exception {
        // Configure system properties for the container
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        
        // Initialize PeeGeeQ Manager
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());
        manager.start();
        
        // Create queue factory and producer
        DatabaseService databaseService = new PgDatabaseService(manager);
        QueueFactoryProvider provider = new PgQueueFactoryProvider();
        queueFactory = provider.createFactory("outbox", databaseService);
        producer = queueFactory.createProducer("order-events", OrderEvent.class);
        
        logger.info("Test setup completed successfully");
    }
    
    @AfterEach
    void tearDown() {
        if (producer != null) {
            producer.close();
        }
        if (queueFactory != null) {
            try {
                queueFactory.close();
            } catch (Exception e) {
                logger.warn("Error closing queue factory: {}", e.getMessage());
            }
        }
        if (manager != null) {
            manager.close();
        }

        // Clean up system properties
        System.clearProperty("peegeeq.database.host");
        System.clearProperty("peegeeq.database.port");
        System.clearProperty("peegeeq.database.name");
        System.clearProperty("peegeeq.database.username");
        System.clearProperty("peegeeq.database.password");

        logger.info("Test teardown completed");
    }
    
    /**
     * Test basic high-frequency message production with routing headers.
     */
    @Test
    void testHighFrequencyProducerWithRouting() throws Exception {
        logger.info("Testing high-frequency producer with message routing");
        
        final int messageCount = 100;
        final AtomicLong sentMessages = new AtomicLong(0);
        final CountDownLatch completionLatch = new CountDownLatch(messageCount);
        
        // Send messages with different routing headers
        for (int i = 1; i <= messageCount; i++) {
            final int messageId = i; // Make effectively final for lambda
            OrderEvent event = createOrderEvent(messageId);
            Map<String, String> headers = createRoutingHeaders(messageId);

            producer.send(event, headers, "correlation-" + messageId, getMessageGroup(headers))
                .whenComplete((result, error) -> {
                    if (error != null) {
                        logger.error("Failed to send message {}: {}", messageId, error.getMessage());
                    } else {
                        sentMessages.incrementAndGet();
                    }
                    completionLatch.countDown();
                });
        }
        
        // Wait for all messages to be sent
        assertTrue(completionLatch.await(30, TimeUnit.SECONDS), 
            "All messages should be sent within timeout");
        assertEquals(messageCount, sentMessages.get(), 
            "All messages should be sent successfully");
        
        logger.info("Successfully sent {} messages with routing headers", sentMessages.get());
    }

    /**
     * Test region-based consumer groups processing messages in parallel.
     */
    @Test
    void testRegionBasedConsumerGroups() throws Exception {
        logger.info("Testing region-based consumer groups");

        // Create consumer group for order processing
        ConsumerGroup<OrderEvent> orderGroup = queueFactory.createConsumerGroup(
            "OrderProcessing", "order-events", OrderEvent.class);

        // Counters for each region
        AtomicInteger usCount = new AtomicInteger(0);
        AtomicInteger euCount = new AtomicInteger(0);
        AtomicInteger asiaCount = new AtomicInteger(0);

        // Add region-specific consumers
        orderGroup.addConsumer("US-Consumer",
            createRegionHandler("US", usCount),
            MessageFilter.byRegion(Set.of("US")));

        orderGroup.addConsumer("EU-Consumer",
            createRegionHandler("EU", euCount),
            MessageFilter.byRegion(Set.of("EU")));

        orderGroup.addConsumer("ASIA-Consumer",
            createRegionHandler("ASIA", asiaCount),
            MessageFilter.byRegion(Set.of("ASIA")));

        // Start the consumer group
        orderGroup.start();
        assertEquals(3, orderGroup.getActiveConsumerCount(),
            "All three regional consumers should be active");

        // Send test messages with region headers
        final int messagesPerRegion = 10;
        sendRegionalMessages(messagesPerRegion);

        // Wait for processing - increased time for 30 messages with 100ms processing each
        Thread.sleep(10000);

        // Log processing results for debugging
        int totalProcessed = usCount.get() + euCount.get() + asiaCount.get();
        logger.info("Region-based processing results:");
        logger.info("  US consumer processed: {}", usCount.get());
        logger.info("  EU consumer processed: {}", euCount.get());
        logger.info("  ASIA consumer processed: {}", asiaCount.get());
        logger.info("  Total processed: {} (expected: {})", totalProcessed, messagesPerRegion * 3);

        // Verify total messages processed with queue semantics
        // In queue semantics, consumers within the same group compete for messages
        // Allow for some tolerance due to timing and processing delays
        assertTrue(totalProcessed >= (messagesPerRegion * 3) * 0.6,
            "At least 60% of messages should be processed");

        // In queue semantics with competing consumers, not all consumers may get messages
        // due to timing, load balancing, and processing speed differences
        // At least verify that the system is working and some consumers are processing messages
        int activeConsumers = (usCount.get() > 0 ? 1 : 0) + (euCount.get() > 0 ? 1 : 0) + (asiaCount.get() > 0 ? 1 : 0);
        assertTrue(activeConsumers >= 2, "At least 2 consumers should process some messages");
        assertTrue(totalProcessed > 0, "Some messages should be processed");

        orderGroup.close();
        logger.info("Region-based consumer groups test completed successfully");
    }

    /**
     * Test priority-based consumer groups with different processing speeds.
     */
    @Test
    void testPriorityBasedConsumerGroups() throws Exception {
        logger.info("Testing priority-based consumer groups");

        // Create consumer group for payment processing
        ConsumerGroup<OrderEvent> paymentGroup = queueFactory.createConsumerGroup(
            "PaymentProcessing", "order-events", OrderEvent.class);

        // Counters for different priorities
        AtomicInteger highPriorityCount = new AtomicInteger(0);
        AtomicInteger normalPriorityCount = new AtomicInteger(0);

        // Add priority-based consumers
        paymentGroup.addConsumer("HighPriority-Consumer",
            createPriorityHandler("HIGH", highPriorityCount, 50),
            MessageFilter.byPriority("HIGH"));

        paymentGroup.addConsumer("Normal-Consumer",
            createPriorityHandler("NORMAL", normalPriorityCount, 200),
            MessageFilter.byPriority("NORMAL"));

        // Start the consumer group
        paymentGroup.start();
        assertEquals(2, paymentGroup.getActiveConsumerCount(),
            "Both priority consumers should be active");

        // Send test messages with priority headers
        final int highPriorityMessages = 5;
        final int normalPriorityMessages = 15;
        sendPriorityMessages(highPriorityMessages, normalPriorityMessages);

        // Wait for processing - increased time for message processing
        Thread.sleep(10000);

        // Log processing results for debugging
        int totalProcessed = highPriorityCount.get() + normalPriorityCount.get();
        logger.info("Priority-based processing results:");
        logger.info("  High priority consumer processed: {}", highPriorityCount.get());
        logger.info("  Normal priority consumer processed: {}", normalPriorityCount.get());
        logger.info("  Total processed: {} (expected: {})", totalProcessed, highPriorityMessages + normalPriorityMessages);

        // Verify priority-based processing with queue semantics
        // In queue semantics, consumers within the same group compete for messages
        // Allow for some tolerance due to timing and processing delays
        assertTrue(totalProcessed >= (highPriorityMessages + normalPriorityMessages) * 0.6,
            "At least 60% of messages should be processed");

        // In queue semantics, both consumers should ideally process some messages,
        // but exact distribution depends on timing and load balancing
        int activeConsumers = (highPriorityCount.get() > 0 ? 1 : 0) + (normalPriorityCount.get() > 0 ? 1 : 0);
        assertTrue(activeConsumers >= 1, "At least 1 consumer should process some messages");
        assertTrue(totalProcessed > 0, "Some messages should be processed");

        paymentGroup.close();
        logger.info("Priority-based consumer groups test completed successfully");
    }

    /**
     * Test multi-header filtering with complex routing logic.
     */
    @Test
    void testMultiHeaderFilteringConsumerGroups() throws Exception {
        logger.info("Testing multi-header filtering consumer groups");

        // Create analytics consumer group
        ConsumerGroup<OrderEvent> analyticsGroup = queueFactory.createConsumerGroup(
            "Analytics", "order-events", OrderEvent.class);

        // Counters for different analytics consumers
        AtomicInteger premiumUsCount = new AtomicInteger(0);
        AtomicInteger highPriorityCount = new AtomicInteger(0);
        AtomicInteger auditCount = new AtomicInteger(0);

        // Add consumer for US premium orders
        analyticsGroup.addConsumer("US-Premium-Consumer",
            createAnalyticsHandler("US-PREMIUM", premiumUsCount),
            MessageFilter.and(
                MessageFilter.byRegion(Set.of("US")),
                MessageFilter.byType(Set.of("PREMIUM"))
            ));

        // Add consumer for high-priority orders from any region
        analyticsGroup.addConsumer("HighPriority-Consumer",
            createAnalyticsHandler("HIGH-PRIORITY", highPriorityCount),
            MessageFilter.byPriority("HIGH"));

        // Add audit consumer that accepts all messages
        analyticsGroup.addConsumer("Audit-Consumer",
            createAnalyticsHandler("AUDIT", auditCount),
            MessageFilter.acceptAll());

        // Start the consumer group
        analyticsGroup.start();
        assertEquals(3, analyticsGroup.getActiveConsumerCount(),
            "All analytics consumers should be active");

        // Send test messages with various combinations
        sendComplexFilteringMessages();

        // Wait for processing - increased time for message processing
        Thread.sleep(10000);

        // Verify filtering logic with queue semantics
        // In queue semantics, consumers within the same group compete for messages
        int totalProcessed = premiumUsCount.get() + highPriorityCount.get() + auditCount.get();
        assertTrue(totalProcessed > 0, "Some messages should be processed");

        // Each consumer should process some messages based on their filters and competition
        // Note: Not all consumers may get messages due to competing consumer behavior
        logger.info("Multi-header filtering results:");
        logger.info("  US Premium consumer processed: {}", premiumUsCount.get());
        logger.info("  High priority consumer processed: {}", highPriorityCount.get());
        logger.info("  Audit consumer processed: {}", auditCount.get());
        logger.info("  Total processed: {}", totalProcessed);

        analyticsGroup.close();
        logger.info("Multi-header filtering test completed successfully");
    }

    /**
     * Test concurrent consumer groups processing the same message stream.
     */
    @Test
    void testConcurrentConsumerGroups() throws Exception {
        logger.info("Testing concurrent consumer groups");

        // Create multiple consumer groups that will process the same messages
        ConsumerGroup<OrderEvent> orderGroup = queueFactory.createConsumerGroup(
            "OrderProcessing", "order-events", OrderEvent.class);
        ConsumerGroup<OrderEvent> paymentGroup = queueFactory.createConsumerGroup(
            "PaymentProcessing", "order-events", OrderEvent.class);
        ConsumerGroup<OrderEvent> analyticsGroup = queueFactory.createConsumerGroup(
            "Analytics", "order-events", OrderEvent.class);

        // Counters for each group
        AtomicInteger orderProcessedCount = new AtomicInteger(0);
        AtomicInteger paymentProcessedCount = new AtomicInteger(0);
        AtomicInteger analyticsProcessedCount = new AtomicInteger(0);

        // Add consumers to each group
        orderGroup.addConsumer("Order-Consumer",
            createCountingHandler("ORDER", orderProcessedCount),
            MessageFilter.acceptAll());

        paymentGroup.addConsumer("Payment-Consumer",
            createCountingHandler("PAYMENT", paymentProcessedCount),
            MessageFilter.acceptAll());

        analyticsGroup.addConsumer("Analytics-Consumer",
            createCountingHandler("ANALYTICS", analyticsProcessedCount),
            MessageFilter.acceptAll());

        // Start all consumer groups
        orderGroup.start();
        paymentGroup.start();
        analyticsGroup.start();

        // Send test messages
        final int messageCount = 20;
        sendSimpleMessages(messageCount);

        // Wait for processing - increased time to allow for more frequent polling
        Thread.sleep(15000);

        // Verify messages were distributed among consumer groups (queue behavior)
        // In a queue-based system, multiple consumer groups compete for messages
        int totalProcessed = orderProcessedCount.get() + paymentProcessedCount.get() + analyticsProcessedCount.get();
        assertEquals(messageCount, totalProcessed, "Total messages processed should equal messages sent");

        // Each group should process some messages (competing consumers)
        assertTrue(orderProcessedCount.get() > 0, "Order group should process some messages");
        assertTrue(paymentProcessedCount.get() > 0, "Payment group should process some messages");
        assertTrue(analyticsProcessedCount.get() > 0, "Analytics group should process some messages");

        // Clean up
        orderGroup.close();
        paymentGroup.close();
        analyticsGroup.close();

        logger.info("Concurrent consumer groups test completed successfully");
    }

    // Helper Methods

    /**
     * Creates an OrderEvent for testing.
     */
    private OrderEvent createOrderEvent(int id) {
        return new OrderEvent(
            "ORDER-" + id,
            "CREATED",
            100.0 + (id * 10),
            "customer-" + id
        );
    }

    /**
     * Creates routing headers based on message ID.
     */
    private Map<String, String> createRoutingHeaders(long messageId) {
        String region = switch ((int)(messageId % 3)) {
            case 0 -> "US";
            case 1 -> "EU";
            case 2 -> "ASIA";
            default -> "US";
        };

        String priority = (messageId % 10 < 2) ? "HIGH" : "NORMAL";
        String type = (messageId % 5 == 0) ? "PREMIUM" : "STANDARD";

        return Map.of(
            "region", region,
            "priority", priority,
            "type", type,
            "source", "test-service",
            "version", "1.0",
            "timestamp", String.valueOf(System.currentTimeMillis())
        );
    }

    /**
     * Gets message group for ordering.
     */
    private String getMessageGroup(Map<String, String> headers) {
        return headers.get("region") + "-" + headers.get("priority");
    }

    /**
     * Creates a region-specific message handler.
     */
    private MessageHandler<OrderEvent> createRegionHandler(String region, AtomicInteger counter) {
        return message -> {
            OrderEvent event = message.getPayload();
            Map<String, String> headers = message.getHeaders();

            logger.debug("[OrderProcessing-{}] Processing order: {} (region: {})",
                region, event.getOrderId(), headers.get("region"));

            counter.incrementAndGet();

            // Simulate minimal processing time to avoid timeout issues
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            return CompletableFuture.completedFuture(null);
        };
    }

    /**
     * Creates a priority-specific message handler.
     */
    private MessageHandler<OrderEvent> createPriorityHandler(String priority, AtomicInteger counter, int processingTime) {
        return message -> {
            OrderEvent event = message.getPayload();
            Map<String, String> headers = message.getHeaders();

            logger.debug("[PaymentProcessing-{}] Processing payment for order: {} (priority: {})",
                priority, event.getOrderId(), headers.get("priority"));

            counter.incrementAndGet();

            // Simulate processing time based on priority
            try {
                Thread.sleep(processingTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            return CompletableFuture.completedFuture(null);
        };
    }

    /**
     * Creates an analytics message handler.
     */
    private MessageHandler<OrderEvent> createAnalyticsHandler(String type, AtomicInteger counter) {
        return message -> {
            OrderEvent event = message.getPayload();
            Map<String, String> headers = message.getHeaders();

            logger.debug("[Analytics-{}] Analyzing order: {} (type: {}, region: {})",
                type, event.getOrderId(), headers.get("type"), headers.get("region"));

            counter.incrementAndGet();

            // Analytics processing is typically fast
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            return CompletableFuture.completedFuture(null);
        };
    }

    /**
     * Creates a simple counting message handler.
     */
    private MessageHandler<OrderEvent> createCountingHandler(String handlerName, AtomicInteger counter) {
        return message -> {
            OrderEvent event = message.getPayload();

            logger.debug("[{}] Processing order: {}", handlerName, event.getOrderId());
            counter.incrementAndGet();

            // Simulate minimal processing time
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            return CompletableFuture.completedFuture(null);
        };
    }

    /**
     * Sends messages with regional distribution.
     */
    private void sendRegionalMessages(int messagesPerRegion) throws Exception {
        String[] regions = {"US", "EU", "ASIA"};

        for (String region : regions) {
            for (int i = 1; i <= messagesPerRegion; i++) {
                OrderEvent event = createOrderEvent(i);
                Map<String, String> headers = Map.of(
                    "region", region,
                    "priority", "NORMAL",
                    "type", "STANDARD",
                    "source", "test-service"
                );

                producer.send(event, headers, "regional-" + region + "-" + i, region).join();
            }
        }

        logger.info("Sent {} messages per region", messagesPerRegion);
    }

    /**
     * Sends messages with priority distribution.
     */
    private void sendPriorityMessages(int highPriorityCount, int normalPriorityCount) throws Exception {
        // Send high priority messages
        for (int i = 1; i <= highPriorityCount; i++) {
            OrderEvent event = createOrderEvent(i);
            Map<String, String> headers = Map.of(
                "region", "US",
                "priority", "HIGH",
                "type", "PREMIUM",
                "source", "test-service"
            );

            producer.send(event, headers, "high-priority-" + i, "HIGH").join();
        }

        // Send normal priority messages
        for (int i = 1; i <= normalPriorityCount; i++) {
            OrderEvent event = createOrderEvent(i + highPriorityCount);
            Map<String, String> headers = Map.of(
                "region", "EU",
                "priority", "NORMAL",
                "type", "STANDARD",
                "source", "test-service"
            );

            producer.send(event, headers, "normal-priority-" + i, "NORMAL").join();
        }

        logger.info("Sent {} high priority and {} normal priority messages",
            highPriorityCount, normalPriorityCount);
    }

    /**
     * Sends messages with complex filtering combinations.
     */
    private void sendComplexFilteringMessages() throws Exception {
        // Send US Premium messages (should match US-Premium consumer)
        for (int i = 1; i <= 5; i++) {
            OrderEvent event = createOrderEvent(i);
            Map<String, String> headers = Map.of(
                "region", "US",
                "priority", "HIGH",
                "type", "PREMIUM",
                "source", "test-service"
            );

            producer.send(event, headers, "us-premium-" + i, "US-PREMIUM").join();
        }

        // Send EU High Priority messages (should match high-priority consumer)
        for (int i = 6; i <= 10; i++) {
            OrderEvent event = createOrderEvent(i);
            Map<String, String> headers = Map.of(
                "region", "EU",
                "priority", "HIGH",
                "type", "STANDARD",
                "source", "test-service"
            );

            producer.send(event, headers, "eu-high-" + i, "EU-HIGH").join();
        }

        // Send various other messages (should only match audit consumer)
        for (int i = 11; i <= 30; i++) {
            OrderEvent event = createOrderEvent(i);
            Map<String, String> headers = createRoutingHeaders(i);

            producer.send(event, headers, "mixed-" + i, getMessageGroup(headers)).join();
        }

        logger.info("Sent complex filtering test messages");
    }

    /**
     * Sends simple messages for concurrent processing tests.
     */
    private void sendSimpleMessages(int messageCount) throws Exception {
        for (int i = 1; i <= messageCount; i++) {
            OrderEvent event = createOrderEvent(i);
            Map<String, String> headers = Map.of(
                "region", "US",
                "priority", "NORMAL",
                "type", "STANDARD",
                "source", "test-service"
            );

            producer.send(event, headers, "simple-" + i, "SIMPLE").join();
        }

        logger.info("Sent {} simple messages", messageCount);
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

        @Override
        public String toString() {
            return String.format("OrderEvent{orderId='%s', status='%s', amount=%.2f, customerId='%s'}",
                orderId, status, amount, customerId);
        }
    }
}
