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
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-14
 * @version 1.0
 */
@Testcontainers
class HighFrequencyProducerConsumerTest {
    private static final Logger logger = LoggerFactory.getLogger(HighFrequencyProducerConsumerTest.class);
    
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_performance_test")
            .withUsername("peegeeq_test")
            .withPassword("peegeeq_test")
            .withSharedMemorySize(512 * 1024 * 1024L) // 512MB for better performance
            .withReuse(false);
    
    private PeeGeeQManager manager;
    private QueueFactory queueFactory;
    private MessageProducer<OrderEvent> producer;
    private ExecutorService executorService;
    
    @BeforeEach
    void setUp() throws Exception {
        // Configure system properties for the container
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        
        // Initialize PeeGeeQ Manager with performance configuration
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());
        manager.start();
        
        // Create queue factory and producer
        DatabaseService databaseService = new PgDatabaseService(manager);
        QueueFactoryProvider provider = new PgQueueFactoryProvider();
        queueFactory = provider.createFactory("outbox", databaseService);
        producer = queueFactory.createProducer("order-events", OrderEvent.class);
        
        // Create thread pool for concurrent operations
        executorService = Executors.newFixedThreadPool(20);
        
        logger.info("Performance test setup completed successfully");
    }
    
    @AfterEach
    void tearDown() {
        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
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
        
        // Create multiple concurrent producers
        for (int producerId = 0; producerId < concurrentProducers; producerId++) {
            final int finalProducerId = producerId;
            executorService.submit(() -> {
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

        // Create multiple consumer groups for load distribution
        ConsumerGroup<OrderEvent> orderGroup = queueFactory.createConsumerGroup(
            "OrderProcessing", "order-events", OrderEvent.class);
        ConsumerGroup<OrderEvent> paymentGroup = queueFactory.createConsumerGroup(
            "PaymentProcessing", "order-events", OrderEvent.class);

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

        // Wait for processing to complete
        Thread.sleep(30000); // 30 seconds for processing

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

        // Create consumer groups with different filtering strategies
        ConsumerGroup<OrderEvent> regionGroup = queueFactory.createConsumerGroup(
            "RegionProcessing", "order-events", OrderEvent.class);
        ConsumerGroup<OrderEvent> priorityGroup = queueFactory.createConsumerGroup(
            "PriorityProcessing", "order-events", OrderEvent.class);
        ConsumerGroup<OrderEvent> analyticsGroup = queueFactory.createConsumerGroup(
            "Analytics", "order-events", OrderEvent.class);

        // Counters for different routing scenarios
        AtomicInteger usCount = new AtomicInteger(0);
        AtomicInteger euCount = new AtomicInteger(0);
        AtomicInteger asiaCount = new AtomicInteger(0);
        AtomicInteger highPriorityCount = new AtomicInteger(0);
        AtomicInteger normalPriorityCount = new AtomicInteger(0);
        AtomicInteger analyticsCount = new AtomicInteger(0);

        // Add region-based consumers
        regionGroup.addConsumer("us-consumer",
            createRoutingHandler("US", usCount),
            MessageFilter.byRegion(Set.of("US")));
        regionGroup.addConsumer("eu-consumer",
            createRoutingHandler("EU", euCount),
            MessageFilter.byRegion(Set.of("EU")));
        regionGroup.addConsumer("asia-consumer",
            createRoutingHandler("ASIA", asiaCount),
            MessageFilter.byRegion(Set.of("ASIA")));

        // Add priority-based consumers
        priorityGroup.addConsumer("high-priority-consumer",
            createRoutingHandler("HIGH", highPriorityCount),
            MessageFilter.byPriority("HIGH"));
        priorityGroup.addConsumer("normal-priority-consumer",
            createRoutingHandler("NORMAL", normalPriorityCount),
            MessageFilter.byPriority("NORMAL"));

        // Add analytics consumer with complex filtering
        analyticsGroup.addConsumer("analytics-consumer",
            createRoutingHandler("ANALYTICS", analyticsCount),
            MessageFilter.and(
                MessageFilter.byType(Set.of("PREMIUM")),
                MessageFilter.byPriority("HIGH")
            ));

        // Start all consumer groups
        regionGroup.start();
        priorityGroup.start();
        analyticsGroup.start();

        // Send messages with various routing characteristics
        final int messageCount = 300;
        Instant startTime = Instant.now();

        sendRoutingPerformanceMessages(messageCount);

        // Wait for processing
        Thread.sleep(20000);

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
        assertTrue(usCount.get() > 0, "US consumer should process some messages");
        assertTrue(euCount.get() > 0, "EU consumer should process some messages");
        assertTrue(asiaCount.get() > 0, "ASIA consumer should process some messages");
        assertTrue(highPriorityCount.get() > 0, "High priority consumer should process some messages");
        assertTrue(normalPriorityCount.get() > highPriorityCount.get(),
            "Normal priority consumer should process more messages");

        // Clean up
        regionGroup.close();
        priorityGroup.close();
        analyticsGroup.close();
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

            OrderEvent event = message.getPayload();
            logger.debug("[{}] Processing order: {}", handlerName, event.getOrderId());

            counter.incrementAndGet();

            // Simulate processing time
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            long processingTime = System.currentTimeMillis() - startTime;
            totalProcessingTime.addAndGet(processingTime);

            return CompletableFuture.completedFuture(null);
        };
    }

    /**
     * Creates a routing-specific message handler.
     */
    private MessageHandler<OrderEvent> createRoutingHandler(String routeName, AtomicInteger counter) {
        return message -> {
            OrderEvent event = message.getPayload();
            Map<String, String> headers = message.getHeaders();

            logger.debug("[{}] Routing order: {} (headers: {})",
                routeName, event.getOrderId(), headers);

            counter.incrementAndGet();

            // Minimal processing time for routing tests
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            return CompletableFuture.completedFuture(null);
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
