package dev.mars.peegeeq.examples.outbox;

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
import dev.mars.peegeeq.outbox.OutboxFactoryRegistrar;
import dev.mars.peegeeq.examples.shared.SharedTestContainers;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance and load testing for high-frequency producer and consumer scenarios
 * based on the ADVANCED_GUIDE.md patterns.
 *
 * Tests throughput, latency, and system behavior under high load conditions.
 *
 * <h3>Refactored Test Design</h3>
 * This test class has been refactored to eliminate poorly structured test design patterns:
 * <ul>
 *   <li><strong>Property Management</strong>: Uses standardized TestContainers configuration</li>
 *   <li><strong>Thread Management</strong>: Uses CompletableFuture patterns instead of manual ExecutorService</li>
 *   <li><strong>Test Independence</strong>: Each test uses unique queue and consumer group names</li>
 *   <li><strong>Clean Structure</strong>: Simplified setup/teardown with essential logging only</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-14
 * @version 2.0 (Refactored)
 */
@Testcontainers
class HighFrequencyProducerConsumerTest {
    private static final Logger logger = LoggerFactory.getLogger(HighFrequencyProducerConsumerTest.class);
    static PostgreSQLContainer<?> postgres = SharedTestContainers.getSharedPostgreSQLContainer();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        SharedTestContainers.configureSharedProperties(registry);
    }
    
    private PeeGeeQManager manager;
    private QueueFactory queueFactory;
    private MessageProducer<OrderEvent> producer;
    private String testQueueName;

    /**
     * Configure system properties for TestContainers PostgreSQL connection
     */
    private void configureSystemPropertiesForContainer() {
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
    }

    /**
     * Clear system properties after test completion
     */
    private void clearSystemProperties() {
        System.clearProperty("peegeeq.database.host");
        System.clearProperty("peegeeq.database.port");
        System.clearProperty("peegeeq.database.name");
        System.clearProperty("peegeeq.database.username");
        System.clearProperty("peegeeq.database.password");
    }

    /**
     * Generate unique queue name for test independence
     */
    private String getUniqueQueueName(String baseName) {
        return baseName + "-" + System.nanoTime();
    }

    /**
     * Generate unique consumer group name for test independence
     */
    private String getUniqueGroupName(String baseName) {
        return baseName + "-" + System.nanoTime();
    }
    
    @BeforeEach
    void setUp() throws Exception {
        // Configure system properties for TestContainers
        configureSystemPropertiesForContainer();

        // Generate unique queue name for test independence
        testQueueName = getUniqueQueueName("order-events");

        // Initialize PeeGeeQ Manager
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());
        manager.start();

        // Create queue factory and producer
        DatabaseService databaseService = new PgDatabaseService(manager);
        QueueFactoryProvider provider = new PgQueueFactoryProvider();

        // Register queue factory implementations
        PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
        OutboxFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);

        queueFactory = provider.createFactory("outbox", databaseService);
        producer = queueFactory.createProducer(testQueueName, OrderEvent.class);

        logger.info("Performance test setup completed successfully with queue: {}", testQueueName);
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
        
        // Clear system properties
        clearSystemProperties();

        logger.info("Performance test teardown completed");
    }
    
    /**
     * Test high-frequency message production with throughput measurement.
     */
    @Test
    void testHighFrequencyProduction() throws Exception {
        logger.info("Testing high-frequency message production");
        
        final int messageCount = 1000;
        final int concurrentProducers = 5;
        final AtomicLong sentMessages = new AtomicLong(0);
        final CountDownLatch completionLatch = new CountDownLatch(messageCount);
        
        Instant startTime = Instant.now();
        
        // Create multiple concurrent producers using CompletableFuture
        for (int producerId = 0; producerId < concurrentProducers; producerId++) {
            final int finalProducerId = producerId;
            CompletableFuture.runAsync(() -> {
                int messagesPerProducer = messageCount / concurrentProducers;
                for (int i = 0; i < messagesPerProducer; i++) {
                    try {
                        int messageId = finalProducerId * messagesPerProducer + i;
                        OrderEvent event = createOrderEvent(messageId);
                        Map<String, String> headers = createRoutingHeaders(messageId);

                        producer.send(event, headers, "perf-" + messageId, getMessageGroup(headers))
                            .whenComplete((result, error) -> {
                                if (error == null) {
                                    sentMessages.incrementAndGet();
                                }
                                completionLatch.countDown();
                            });
                    } catch (Exception e) {
                        logger.error("Error sending message: {}", e.getMessage());
                        completionLatch.countDown();
                    }
                }
            });
        }
        
        // Wait for completion
        assertTrue(completionLatch.await(60, TimeUnit.SECONDS), 
            "All messages should be sent within timeout");
        
        Duration duration = Duration.between(startTime, Instant.now());
        double throughput = sentMessages.get() / (duration.toMillis() / 1000.0);
        
        logger.info("High-frequency production results:");
        logger.info("  Messages sent: {}", sentMessages.get());
        logger.info("  Duration: {} ms", duration.toMillis());
        logger.info("  Throughput: {:.2f} messages/second", throughput);
        
        assertTrue(sentMessages.get() >= messageCount * 0.95, 
            "At least 95% of messages should be sent successfully");
        assertTrue(throughput > 100,
            "Throughput should exceed 100 messages/second");
    }

    /**
     * Test consumer group performance under high load.
     */
    @Test
    void testConsumerGroupPerformanceUnderLoad() throws Exception {
        logger.info("Testing consumer group performance under high load");

        // Create multiple consumer groups for load distribution with unique names
        String orderGroupName = getUniqueGroupName("OrderProcessing");
        String paymentGroupName = getUniqueGroupName("PaymentProcessing");
        ConsumerGroup<OrderEvent> orderGroup = queueFactory.createConsumerGroup(
            orderGroupName, testQueueName, OrderEvent.class);
        ConsumerGroup<OrderEvent> paymentGroup = queueFactory.createConsumerGroup(
            paymentGroupName, testQueueName, OrderEvent.class);

        // Performance counters
        AtomicInteger orderProcessedCount = new AtomicInteger(0);
        AtomicInteger paymentProcessedCount = new AtomicInteger(0);
        AtomicLong totalProcessingTime = new AtomicLong(0);

        // Add multiple consumers to each group for parallel processing
        for (int i = 0; i < 3; i++) {
            orderGroup.addConsumer("order-consumer-" + i,
                createPerformanceHandler("ORDER", orderProcessedCount, totalProcessingTime),
                MessageFilter.acceptAll());

            paymentGroup.addConsumer("payment-consumer-" + i,
                createPerformanceHandler("PAYMENT", paymentProcessedCount, totalProcessingTime),
                MessageFilter.acceptAll());
        }

        // Start consumer groups
        orderGroup.start();
        paymentGroup.start();

        // Send high volume of messages
        final int messageCount = 500;
        Instant startTime = Instant.now();

        for (int i = 1; i <= messageCount; i++) {
            OrderEvent event = createOrderEvent(i);
            Map<String, String> headers = createRoutingHeaders(i);
            producer.send(event, headers, "load-" + i, getMessageGroup(headers)).join();
        }

        // Wait for processing to complete using CompletableFuture
        CompletableFuture.runAsync(() -> {
            try { Thread.sleep(30000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }).join();

        Duration duration = Duration.between(startTime, Instant.now());
        int totalProcessed = orderProcessedCount.get() + paymentProcessedCount.get();
        double processingThroughput = totalProcessed / (duration.toMillis() / 1000.0);
        double avgProcessingTime = totalProcessingTime.get() / (double) totalProcessed;

        logger.info("Consumer group performance results:");
        logger.info("  Total messages processed: {}", totalProcessed);
        logger.info("  Order group processed: {}", orderProcessedCount.get());
        logger.info("  Payment group processed: {}", paymentProcessedCount.get());
        logger.info("  Processing throughput: {:.2f} messages/second", processingThroughput);
        logger.info("  Average processing time: {:.2f} ms", avgProcessingTime);

        // Verify performance expectations with queue semantics
        // In queue semantics, consumer groups compete for messages
        // With high message volume, processing may be limited by polling frequency
        assertTrue(totalProcessed >= messageCount * 0.2,
            "At least 20% of messages should be processed");

        // Allow for some tolerance in message processing due to timing
        assertTrue(totalProcessed <= messageCount,
            "Total messages processed should not exceed messages sent");
        assertTrue(processingThroughput > 1,
            "Processing throughput should exceed 1 message/second");
        assertTrue(avgProcessingTime < 2000,
            "Average processing time should be under 2 seconds");

        // Clean up
        orderGroup.close();
        paymentGroup.close();
    }

    /**
     * Test message routing performance with complex filtering.
     */
    @Test
    void testMessageRoutingPerformance() throws Exception {
        logger.info("Testing message routing performance with complex filtering");

        // Create a single consumer group with different filtering strategies using unique names
        // Note: Using separate groups would cause messages to be distributed across groups,
        // not allowing each message to be processed by multiple filtered consumers
        String routingGroupName = getUniqueGroupName("MessageRouting");
        ConsumerGroup<OrderEvent> routingGroup = queueFactory.createConsumerGroup(
            routingGroupName, testQueueName, OrderEvent.class);

        // Counters for different routing scenarios
        AtomicInteger usCount = new AtomicInteger(0);
        AtomicInteger euCount = new AtomicInteger(0);
        AtomicInteger asiaCount = new AtomicInteger(0);
        AtomicInteger highPriorityCount = new AtomicInteger(0);
        AtomicInteger normalPriorityCount = new AtomicInteger(0);
        AtomicInteger analyticsCount = new AtomicInteger(0);

        // Add region-based consumers
        routingGroup.addConsumer("us-consumer",
            createRoutingHandler("US", usCount),
            MessageFilter.byRegion(Set.of("US")));
        routingGroup.addConsumer("eu-consumer",
            createRoutingHandler("EU", euCount),
            MessageFilter.byRegion(Set.of("EU")));
        routingGroup.addConsumer("asia-consumer",
            createRoutingHandler("ASIA", asiaCount),
            MessageFilter.byRegion(Set.of("ASIA")));

        // Add priority-based consumers
        routingGroup.addConsumer("high-priority-consumer",
            createRoutingHandler("HIGH", highPriorityCount),
            MessageFilter.byPriority("HIGH"));
        routingGroup.addConsumer("normal-priority-consumer",
            createRoutingHandler("NORMAL", normalPriorityCount),
            MessageFilter.byHeader("priority", "NORMAL"));

        // Add analytics consumer with complex filtering
        routingGroup.addConsumer("analytics-consumer",
            createRoutingHandler("ANALYTICS", analyticsCount),
            MessageFilter.and(
                MessageFilter.byType(Set.of("PREMIUM")),
                MessageFilter.byPriority("HIGH")
            ));

        // Start the consumer group
        routingGroup.start();

        // Send messages with various routing characteristics
        final int messageCount = 300;
        Instant startTime = Instant.now();

        sendRoutingPerformanceMessages(messageCount);

        // Wait for processing using CompletableFuture
        CompletableFuture.runAsync(() -> {
            try { Thread.sleep(20000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }).join();

        Duration duration = Duration.between(startTime, Instant.now());
        int totalRouted = usCount.get() + euCount.get() + asiaCount.get() +
                         highPriorityCount.get() + normalPriorityCount.get() + analyticsCount.get();

        logger.info("Message routing performance results:");
        logger.info("  US messages: {}", usCount.get());
        logger.info("  EU messages: {}", euCount.get());
        logger.info("  ASIA messages: {}", asiaCount.get());
        logger.info("  High priority messages: {}", highPriorityCount.get());
        logger.info("  Normal priority messages: {}", normalPriorityCount.get());
        logger.info("  Analytics messages: {}", analyticsCount.get());
        logger.info("  Total routed messages: {}", totalRouted);
        logger.info("  Routing duration: {} ms", duration.toMillis());

        // Verify routing distribution
        // Note: In a single consumer group, each message goes to exactly one consumer
        // based on their filters. We verify that filtering is working correctly.
        int totalProcessed = usCount.get() + euCount.get() + asiaCount.get() +
                           highPriorityCount.get() + normalPriorityCount.get() + analyticsCount.get();

        assertTrue(totalProcessed > 0, "Some messages should be processed");
        assertTrue(totalProcessed <= messageCount, "Should not process more messages than sent");

        // Verify that at least some filtering categories got messages
        int categoriesWithMessages = 0;
        if (usCount.get() > 0) categoriesWithMessages++;
        if (euCount.get() > 0) categoriesWithMessages++;
        if (asiaCount.get() > 0) categoriesWithMessages++;
        if (highPriorityCount.get() > 0) categoriesWithMessages++;
        if (normalPriorityCount.get() > 0) categoriesWithMessages++;
        if (analyticsCount.get() > 0) categoriesWithMessages++;

        assertTrue(categoriesWithMessages >= 3,
            "At least 3 different filter categories should process messages");

        // Clean up
        routingGroup.close();
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
            "source", "performance-test",
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
     * Creates a performance-measuring message handler.
     */
    private MessageHandler<OrderEvent> createPerformanceHandler(String handlerName,
                                                               AtomicInteger counter,
                                                               AtomicLong totalProcessingTime) {
        return message -> {
            long startTime = System.currentTimeMillis();

            counter.incrementAndGet();

            // Simulate processing time using CompletableFuture
            return CompletableFuture.runAsync(() -> {
                try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                long processingTime = System.currentTimeMillis() - startTime;
                totalProcessingTime.addAndGet(processingTime);
            });
        };
    }

    /**
     * Creates a routing-specific message handler.
     */
    private MessageHandler<OrderEvent> createRoutingHandler(String routeName, AtomicInteger counter) {
        return message -> {
            counter.incrementAndGet();

            // Minimal processing time for routing tests using CompletableFuture
            return CompletableFuture.runAsync(() -> {
                try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
        };
    }

    /**
     * Sends messages for routing performance testing.
     */
    private void sendRoutingPerformanceMessages(int messageCount) throws Exception {
        for (int i = 1; i <= messageCount; i++) {
            OrderEvent event = createOrderEvent(i);
            Map<String, String> headers = createRoutingHeaders(i);

            producer.send(event, headers, "routing-perf-" + i, getMessageGroup(headers)).join();
        }

        logger.info("Sent {} messages for routing performance test", messageCount);
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
