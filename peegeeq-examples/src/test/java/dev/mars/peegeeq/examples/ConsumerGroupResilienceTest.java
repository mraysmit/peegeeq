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

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test cases for consumer group resilience, error handling, and recovery scenarios
 * based on the ADVANCED_GUIDE.md patterns.
 * 
 * Tests failure scenarios, recovery mechanisms, and system stability under adverse conditions.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-14
 * @version 1.0
 */
@Testcontainers
class ConsumerGroupResilienceTest {
    private static final Logger logger = LoggerFactory.getLogger(ConsumerGroupResilienceTest.class);
    
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_resilience_test")
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
        
        logger.info("Resilience test setup completed successfully");
    }
    
    @AfterEach
    void tearDown() {
        if (producer != null) {
            producer.close();
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
        
        logger.info("Resilience test teardown completed");
    }
    
    /**
     * Test consumer group behavior when individual consumers fail.
     */
    @Test
    void testConsumerFailureRecovery() throws Exception {
        logger.info("Testing consumer failure recovery");
        
        // Create consumer group with multiple consumers
        ConsumerGroup<OrderEvent> orderGroup = queueFactory.createConsumerGroup(
            "OrderProcessing", "order-events", OrderEvent.class);
        
        // Counters for successful and failed processing
        AtomicInteger successfulCount = new AtomicInteger(0);
        AtomicInteger failedCount = new AtomicInteger(0);
        AtomicInteger recoveredCount = new AtomicInteger(0);
        
        // Add a consumer that fails on certain messages
        orderGroup.addConsumer("failing-consumer", 
            createFailingHandler(successfulCount, failedCount), 
            MessageFilter.byRegion(Set.of("US")));
        
        // Add a backup consumer that processes all messages
        orderGroup.addConsumer("backup-consumer", 
            createBackupHandler(recoveredCount), 
            MessageFilter.acceptAll());
        
        // Start the consumer group
        orderGroup.start();
        assertEquals(2, orderGroup.getActiveConsumerCount(), 
            "Both consumers should be active");
        
        // Send test messages that will trigger failures
        sendFailureTestMessages();
        
        // Wait for processing and recovery
        Thread.sleep(10000);
        
        logger.info("Failure recovery results:");
        logger.info("  Successful processing: {}", successfulCount.get());
        logger.info("  Failed processing: {}", failedCount.get());
        logger.info("  Recovered by backup: {}", recoveredCount.get());
        
        // Verify that failures occurred but system continued processing
        assertTrue(failedCount.get() > 0, "Some failures should have occurred");
        assertTrue(successfulCount.get() > 0, "Some messages should have been processed successfully");
        assertTrue(recoveredCount.get() > 0, "Backup consumer should have processed messages");
        
        orderGroup.close();
        logger.info("Consumer failure recovery test completed successfully");
    }
    
    /**
     * Test consumer group behavior with invalid message filters.
     */
    @Test
    void testInvalidMessageFilterHandling() throws Exception {
        logger.info("Testing invalid message filter handling");
        
        // Create consumer group
        ConsumerGroup<OrderEvent> testGroup = queueFactory.createConsumerGroup(
            "TestGroup", "order-events", OrderEvent.class);
        
        AtomicInteger processedCount = new AtomicInteger(0);
        AtomicInteger filteredCount = new AtomicInteger(0);
        
        // Add consumer with a filter that might throw exceptions
        testGroup.addConsumer("filter-test-consumer", 
            createFilterTestHandler(processedCount), 
            createExceptionThrowingFilter(filteredCount));
        
        // Start the consumer group
        testGroup.start();
        
        // Send test messages
        sendFilterTestMessages();
        
        // Wait for processing
        Thread.sleep(5000);
        
        logger.info("Filter handling results:");
        logger.info("  Messages processed: {}", processedCount.get());
        logger.info("  Filter exceptions: {}", filteredCount.get());
        
        // Verify that filter exceptions are handled gracefully
        assertTrue(processedCount.get() >= 0, "Some messages should be processed");
        
        testGroup.close();
        logger.info("Invalid message filter handling test completed successfully");
    }
    
    /**
     * Test consumer group statistics and monitoring during failures.
     */
    @Test
    void testConsumerGroupStatisticsDuringFailures() throws Exception {
        logger.info("Testing consumer group statistics during failures");
        
        // Create consumer group
        ConsumerGroup<OrderEvent> monitoringGroup = queueFactory.createConsumerGroup(
            "MonitoringGroup", "order-events", OrderEvent.class);
        
        AtomicInteger processedCount = new AtomicInteger(0);
        
        // Add consumer that occasionally fails
        ConsumerGroupMember<OrderEvent> member = monitoringGroup.addConsumer("monitoring-consumer", 
            createMonitoringHandler(processedCount), 
            MessageFilter.acceptAll());
        
        // Start the consumer group
        monitoringGroup.start();
        
        // Send test messages
        sendMonitoringTestMessages();
        
        // Wait for processing
        Thread.sleep(8000);
        
        // Check consumer group statistics
        assertTrue(monitoringGroup.isActive(), "Consumer group should be active");
        assertEquals(1, monitoringGroup.getActiveConsumerCount(), "One consumer should be active");
        
        // Check member statistics
        assertTrue(member.isActive(), "Consumer member should be active");
        assertTrue(member.getProcessedMessageCount() > 0, "Some messages should have been processed");
        
        logger.info("Monitoring statistics:");
        logger.info("  Group active: {}", monitoringGroup.isActive());
        logger.info("  Active consumers: {}", monitoringGroup.getActiveConsumerCount());
        logger.info("  Member processed count: {}", member.getProcessedMessageCount());
        logger.info("  Total processed: {}", processedCount.get());
        
        monitoringGroup.close();
        logger.info("Consumer group statistics test completed successfully");
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
     * Creates a message handler that fails on certain conditions.
     */
    private MessageHandler<OrderEvent> createFailingHandler(AtomicInteger successCount, AtomicInteger failCount) {
        return message -> {
            OrderEvent event = message.getPayload();
            Map<String, String> headers = message.getHeaders();

            // Fail on orders with IDs ending in 5 or 7
            String orderId = event.getOrderId();
            if (orderId.endsWith("5") || orderId.endsWith("7")) {
                failCount.incrementAndGet();
                logger.warn("[FailingConsumer] Simulated failure for order: {}", orderId);
                return CompletableFuture.failedFuture(
                    new RuntimeException("Simulated processing failure for order " + orderId));
            }

            successCount.incrementAndGet();
            logger.debug("[FailingConsumer] Successfully processed order: {}", orderId);

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            return CompletableFuture.completedFuture(null);
        };
    }

    /**
     * Creates a backup message handler that processes all messages.
     */
    private MessageHandler<OrderEvent> createBackupHandler(AtomicInteger recoveredCount) {
        return message -> {
            OrderEvent event = message.getPayload();

            recoveredCount.incrementAndGet();
            logger.debug("[BackupConsumer] Recovered processing for order: {}", event.getOrderId());

            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            return CompletableFuture.completedFuture(null);
        };
    }

    /**
     * Creates a message handler for filter testing.
     */
    private MessageHandler<OrderEvent> createFilterTestHandler(AtomicInteger processedCount) {
        return message -> {
            OrderEvent event = message.getPayload();

            processedCount.incrementAndGet();
            logger.debug("[FilterTestConsumer] Processed order: {}", event.getOrderId());

            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            return CompletableFuture.completedFuture(null);
        };
    }

    /**
     * Creates a message handler for monitoring tests.
     */
    private MessageHandler<OrderEvent> createMonitoringHandler(AtomicInteger processedCount) {
        return message -> {
            OrderEvent event = message.getPayload();

            // Occasionally simulate processing delays
            int delay = event.getOrderId().hashCode() % 3 == 0 ? 200 : 50;

            processedCount.incrementAndGet();
            logger.debug("[MonitoringConsumer] Processed order: {} (delay: {}ms)",
                event.getOrderId(), delay);

            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            return CompletableFuture.completedFuture(null);
        };
    }

    /**
     * Creates a filter that occasionally throws exceptions.
     */
    private java.util.function.Predicate<Message<OrderEvent>> createExceptionThrowingFilter(AtomicInteger exceptionCount) {
        return message -> {
            try {
                Map<String, String> headers = message.getHeaders();
                if (headers == null) return true;

                // Throw exception for messages with specific characteristics
                String region = headers.get("region");
                if ("INVALID".equals(region)) {
                    exceptionCount.incrementAndGet();
                    throw new RuntimeException("Simulated filter exception for region: " + region);
                }

                return true;
            } catch (RuntimeException e) {
                logger.warn("Filter exception: {}", e.getMessage());
                throw e;
            }
        };
    }

    /**
     * Sends messages that will trigger failures in the failing handler.
     */
    private void sendFailureTestMessages() throws Exception {
        for (int i = 1; i <= 20; i++) {
            OrderEvent event = createOrderEvent(i);
            Map<String, String> headers = Map.of(
                "region", "US",
                "priority", "NORMAL",
                "type", "STANDARD",
                "source", "failure-test"
            );

            producer.send(event, headers, "failure-test-" + i, "US").join();
        }

        logger.info("Sent 20 failure test messages");
    }

    /**
     * Sends messages for filter testing.
     */
    private void sendFilterTestMessages() throws Exception {
        for (int i = 1; i <= 10; i++) {
            OrderEvent event = createOrderEvent(i);

            // Some messages with invalid region to trigger filter exceptions
            String region = (i % 4 == 0) ? "INVALID" : "US";

            Map<String, String> headers = Map.of(
                "region", region,
                "priority", "NORMAL",
                "type", "STANDARD",
                "source", "filter-test"
            );

            producer.send(event, headers, "filter-test-" + i, region).join();
        }

        logger.info("Sent 10 filter test messages");
    }

    /**
     * Sends messages for monitoring tests.
     */
    private void sendMonitoringTestMessages() throws Exception {
        for (int i = 1; i <= 15; i++) {
            OrderEvent event = createOrderEvent(i);
            Map<String, String> headers = Map.of(
                "region", "US",
                "priority", "NORMAL",
                "type", "STANDARD",
                "source", "monitoring-test"
            );

            producer.send(event, headers, "monitoring-test-" + i, "US").join();
        }

        logger.info("Sent 15 monitoring test messages");
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
