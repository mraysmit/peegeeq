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
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.mars.peegeeq.api.messaging.*;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.pgqueue.PgNativeFactoryRegistrar;
import dev.mars.peegeeq.outbox.OutboxFactoryRegistrar;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Comprehensive example demonstrating message priority handling in PeeGeeQ.
 * 
 * This example shows:
 * - Priority-based message ordering and processing
 * - Different priority levels for different message types
 * - Priority queue configuration and optimization
 * - Consumer behavior with priority messages
 * - Performance characteristics of priority queues
 * - Real-world use cases for message prioritization
 * 
 * Priority Levels:
 * - CRITICAL (10): System alerts, security events
 * - HIGH (7-9): Important business events, urgent notifications
 * - NORMAL (4-6): Regular business operations
 * - LOW (1-3): Background tasks, cleanup operations
 * - BULK (0): Batch processing, analytics
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-29
 * @version 1.0
 */
public class MessagePriorityExample {
    
    private static final Logger logger = LoggerFactory.getLogger(MessagePriorityExample.class);
    
    // Priority levels
    public static final int PRIORITY_CRITICAL = 10;
    public static final int PRIORITY_HIGH = 8;
    public static final int PRIORITY_NORMAL = 5;
    public static final int PRIORITY_LOW = 2;
    public static final int PRIORITY_BULK = 0;
    
    /**
     * Priority message payload with different types and urgency levels.
     */
    public static class PriorityMessage {
        private final String messageId;
        private final String messageType;
        private final String content;
        private final int priority;
        private final Instant timestamp;
        private final Map<String, String> metadata;
        
        @JsonCreator
        public PriorityMessage(@JsonProperty("messageId") String messageId,
                              @JsonProperty("messageType") String messageType,
                              @JsonProperty("content") String content,
                              @JsonProperty("priority") int priority,
                              @JsonProperty("timestamp") Instant timestamp,
                              @JsonProperty("metadata") Map<String, String> metadata) {
            this.messageId = messageId;
            this.messageType = messageType;
            this.content = content;
            this.priority = priority;
            this.timestamp = timestamp;
            this.metadata = metadata != null ? metadata : new HashMap<>();
        }
        
        // Getters
        public String getMessageId() { return messageId; }
        public String getMessageType() { return messageType; }
        public String getContent() { return content; }
        public int getPriority() { return priority; }
        public Instant getTimestamp() { return timestamp; }
        public Map<String, String> getMetadata() { return metadata; }
        
        @JsonIgnore
        public String getPriorityLabel() {
            if (priority >= 10) return "CRITICAL";
            if (priority >= 7) return "HIGH";
            if (priority >= 4) return "NORMAL";
            if (priority >= 1) return "LOW";
            return "BULK";
        }
        
        @Override
        public String toString() {
            return String.format("PriorityMessage{id='%s', type='%s', priority=%d (%s), content='%s'}", 
                messageId, messageType, priority, getPriorityLabel(), content);
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PriorityMessage that = (PriorityMessage) o;
            return Objects.equals(messageId, that.messageId);
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(messageId);
        }
    }
    
    public static void main(String[] args) throws Exception {
        logger.info("=== PeeGeeQ Message Priority Example ===");
        logger.info("This example demonstrates priority-based message processing");

        // Disable Ryuk for examples to avoid shutdown issues
        System.setProperty("testcontainers.ryuk.disabled", "true");

        // Start PostgreSQL container
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
                .withDatabaseName("peegeeq_priority_demo")
                .withUsername("postgres")
                .withPassword("password")) {

            postgres.start();
            logger.info("PostgreSQL container started: {}", postgres.getJdbcUrl());


            
            // Configure PeeGeeQ to use the container
            configureSystemPropertiesForContainer(postgres);
            
            // Run priority demonstrations
            runPriorityDemonstrations();
            
        } catch (Exception e) {
            logger.error("Failed to run Message Priority Example", e);
            throw e;
        }

        // Give TestContainers a moment to clean up
        Thread.sleep(1000);
        logger.info("Message Priority Example completed successfully!");
    }
    
    /**
     * Configures system properties to use the TestContainer database.
     */
    private static void configureSystemPropertiesForContainer(PostgreSQLContainer<?> postgres) {
        logger.info("⚙️  Configuring PeeGeeQ for priority queue demonstration...");
        
        // Set database connection properties
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.schema", "public");
        System.setProperty("peegeeq.database.ssl.enabled", "false");
        
        // Configure for priority queue optimization
        System.setProperty("peegeeq.database.pool.min-size", "5");
        System.setProperty("peegeeq.database.pool.max-size", "20");
        System.setProperty("peegeeq.queue.priority.enabled", "true");
        System.setProperty("peegeeq.queue.priority.index-optimization", "true");
        System.setProperty("peegeeq.metrics.enabled", "true");
        System.setProperty("peegeeq.migration.enabled", "true");
        System.setProperty("peegeeq.migration.auto-migrate", "true");
        
        logger.info("Configuration complete - priority queues enabled");
    }
    
    /**
     * Runs comprehensive priority queue demonstrations.
     */
    private static void runPriorityDemonstrations() throws Exception {
        logger.info("Starting priority queue demonstrations...");
        
        try (PeeGeeQManager manager = new PeeGeeQManager(
                new PeeGeeQConfiguration("development"), 
                new SimpleMeterRegistry())) {
            
            manager.start();
            logger.info("PeeGeeQ Manager started successfully");
            
            // Create database service and factory provider
            PgDatabaseService databaseService = new PgDatabaseService(manager);
            PgQueueFactoryProvider provider = new PgQueueFactoryProvider();

            // Register queue factory implementations
            PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
            OutboxFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);

            // Create queue factory (outbox pattern supports priorities better)
            QueueFactory factory = provider.createFactory("outbox", databaseService);
            
            // Run all priority demonstrations
            demonstrateBasicPriorityOrdering(factory);
            demonstratePriorityLevels(factory);
            demonstrateRealWorldScenarios(factory);
            demonstratePriorityPerformance(factory);
            
        } catch (Exception e) {
            logger.error("Error running priority demonstrations", e);
            throw e;
        }
    }
    
    /**
     * Demonstrates basic priority ordering - higher priority messages processed first.
     */
    private static void demonstrateBasicPriorityOrdering(QueueFactory factory) throws Exception {
        logger.info("\n=== BASIC PRIORITY ORDERING DEMONSTRATION ===");

        // Create producer and consumer for priority queue
        try (MessageProducer<PriorityMessage> producer = factory.createProducer("priority-basic", PriorityMessage.class);
             MessageConsumer<PriorityMessage> consumer = factory.createConsumer("priority-basic", PriorityMessage.class)) {

            // Track processing order
            AtomicInteger processedCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(5);

            // Set up consumer to track processing order
            consumer.subscribe(message -> {
                int order = processedCount.incrementAndGet();
                PriorityMessage payload = message.getPayload();
                logger.info("Processed #{}: {} (Priority: {})",
                    order, payload.getContent(), payload.getPriorityLabel());
                latch.countDown();
                return CompletableFuture.completedFuture(null);
            });

            // Send messages in reverse priority order to demonstrate reordering
            logger.info("Sending messages in reverse priority order...");

            sendPriorityMessage(producer, "msg-1", "BULK", "Bulk processing task", PRIORITY_BULK);
            sendPriorityMessage(producer, "msg-2", "LOW", "Background cleanup", PRIORITY_LOW);
            sendPriorityMessage(producer, "msg-3", "NORMAL", "Regular business operation", PRIORITY_NORMAL);
            sendPriorityMessage(producer, "msg-4", "HIGH", "Important notification", PRIORITY_HIGH);
            sendPriorityMessage(producer, "msg-5", "CRITICAL", "Security alert", PRIORITY_CRITICAL);

            // Wait for processing
            boolean completed = latch.await(30, TimeUnit.SECONDS);
            if (!completed) {
                logger.warn("Not all messages were processed within timeout");
            }

            logger.info("Basic priority ordering demonstration completed");
        }
    }

    /**
     * Demonstrates different priority levels and their use cases.
     */
    private static void demonstratePriorityLevels(QueueFactory factory) throws Exception {
        logger.info("\n=== PRIORITY LEVELS DEMONSTRATION ===");

        try (MessageProducer<PriorityMessage> producer = factory.createProducer("priority-levels", PriorityMessage.class);
             MessageConsumer<PriorityMessage> consumer = factory.createConsumer("priority-levels", PriorityMessage.class)) {

            AtomicInteger processedCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(10);

            // Consumer that shows priority level handling
            consumer.subscribe(message -> {
                int order = processedCount.incrementAndGet();
                PriorityMessage payload = message.getPayload();
                logger.info("Processed #{}: [{}] {} - {}",
                    order, payload.getPriorityLabel(), payload.getMessageType(), payload.getContent());
                latch.countDown();
                return CompletableFuture.completedFuture(null);
            });

            // Send messages with different priority levels
            logger.info("Sending messages with various priority levels...");

            // Critical messages (10)
            sendPriorityMessage(producer, "crit-1", "SECURITY_ALERT", "Unauthorized access detected", PRIORITY_CRITICAL);
            sendPriorityMessage(producer, "crit-2", "SYSTEM_FAILURE", "Database connection lost", PRIORITY_CRITICAL);

            // High priority messages (7-9)
            sendPriorityMessage(producer, "high-1", "PAYMENT_FAILED", "Credit card payment failed", PRIORITY_HIGH);
            sendPriorityMessage(producer, "high-2", "ORDER_URGENT", "VIP customer order", 9);

            // Normal priority messages (4-6)
            sendPriorityMessage(producer, "norm-1", "ORDER_CREATED", "New customer order", PRIORITY_NORMAL);
            sendPriorityMessage(producer, "norm-2", "USER_REGISTERED", "New user registration", 6);

            // Low priority messages (1-3)
            sendPriorityMessage(producer, "low-1", "ANALYTICS_UPDATE", "Daily analytics processing", PRIORITY_LOW);
            sendPriorityMessage(producer, "low-2", "CACHE_REFRESH", "Cache refresh task", 3);

            // Bulk messages (0)
            sendPriorityMessage(producer, "bulk-1", "DATA_EXPORT", "Monthly data export", PRIORITY_BULK);
            sendPriorityMessage(producer, "bulk-2", "CLEANUP_TASK", "Old data cleanup", PRIORITY_BULK);

            // Wait for processing
            boolean completed = latch.await(30, TimeUnit.SECONDS);
            if (!completed) {
                logger.warn("Not all messages were processed within timeout");
            }

            logger.info("Priority levels demonstration completed");
        }
    }

    /**
     * Demonstrates real-world scenarios where priority queues are essential.
     */
    private static void demonstrateRealWorldScenarios(QueueFactory factory) throws Exception {
        logger.info("\n=== REAL-WORLD SCENARIOS DEMONSTRATION ===");

        // Scenario 1: E-commerce order processing
        demonstrateECommerceScenario(factory);

        // Scenario 2: Financial transaction processing
        demonstrateFinancialScenario(factory);

        // Scenario 3: System monitoring and alerts
        demonstrateMonitoringScenario(factory);
    }

    /**
     * E-commerce scenario: VIP customers, urgent orders, regular processing.
     */
    private static void demonstrateECommerceScenario(QueueFactory factory) throws Exception {
        logger.info("\n--- E-Commerce Order Processing Scenario ---");

        try (MessageProducer<PriorityMessage> producer = factory.createProducer("ecommerce-orders", PriorityMessage.class);
             MessageConsumer<PriorityMessage> consumer = factory.createConsumer("ecommerce-orders", PriorityMessage.class)) {

            AtomicInteger processedCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(6);

            consumer.subscribe(message -> {
                int order = processedCount.incrementAndGet();
                PriorityMessage payload = message.getPayload();
                String customerType = payload.getMetadata().getOrDefault("customerType", "regular");
                BigDecimal orderValue = new BigDecimal(payload.getMetadata().getOrDefault("orderValue", "0"));

                logger.info("Processing order #{}: {} - Customer: {}, Value: ${}, Priority: {}",
                    order, payload.getContent(), customerType, orderValue, payload.getPriorityLabel());

                // Simulate processing time based on priority
                try {
                    Thread.sleep(payload.getPriority() >= PRIORITY_HIGH ? 100 : 500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                latch.countDown();
                return CompletableFuture.completedFuture(null);
            });

            // Send various e-commerce orders
            Map<String, String> vipMetadata = Map.of("customerType", "VIP", "orderValue", "5000.00");
            Map<String, String> urgentMetadata = Map.of("customerType", "premium", "orderValue", "1200.00", "expedited", "true");
            Map<String, String> regularMetadata = Map.of("customerType", "regular", "orderValue", "89.99");
            Map<String, String> bulkMetadata = Map.of("customerType", "wholesale", "orderValue", "15000.00", "bulk", "true");

            sendPriorityMessageWithMetadata(producer, "order-1", "ORDER", "VIP customer urgent order", PRIORITY_CRITICAL, vipMetadata);
            sendPriorityMessageWithMetadata(producer, "order-2", "ORDER", "Expedited shipping request", PRIORITY_HIGH, urgentMetadata);
            sendPriorityMessageWithMetadata(producer, "order-3", "ORDER", "Regular customer order", PRIORITY_NORMAL, regularMetadata);
            sendPriorityMessageWithMetadata(producer, "order-4", "ORDER", "Another regular order", PRIORITY_NORMAL, regularMetadata);
            sendPriorityMessageWithMetadata(producer, "order-5", "ORDER", "Bulk wholesale order", PRIORITY_LOW, bulkMetadata);
            sendPriorityMessageWithMetadata(producer, "order-6", "ORDER", "Analytics processing", PRIORITY_BULK, Map.of("type", "analytics"));

            latch.await(30, TimeUnit.SECONDS);
            logger.info("E-commerce scenario completed");
        }
    }

    /**
     * Financial scenario: Critical transactions, fraud alerts, regular processing.
     */
    private static void demonstrateFinancialScenario(QueueFactory factory) throws Exception {
        logger.info("\n--- Financial Transaction Processing Scenario ---");

        try (MessageProducer<PriorityMessage> producer = factory.createProducer("financial-transactions", PriorityMessage.class);
             MessageConsumer<PriorityMessage> consumer = factory.createConsumer("financial-transactions", PriorityMessage.class)) {

            AtomicInteger processedCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(5);

            consumer.subscribe(message -> {
                int order = processedCount.incrementAndGet();
                PriorityMessage payload = message.getPayload();
                String transactionType = payload.getMetadata().getOrDefault("transactionType", "unknown");
                String amount = payload.getMetadata().getOrDefault("amount", "0");

                logger.info("Processing transaction #{}: {} - Type: {}, Amount: ${}, Priority: {}",
                    order, payload.getContent(), transactionType, amount, payload.getPriorityLabel());

                latch.countDown();
                return CompletableFuture.completedFuture(null);
            });

            // Send financial transactions with appropriate priorities
            Map<String, String> fraudAlert = Map.of("transactionType", "FRAUD_ALERT", "amount", "10000.00", "riskScore", "95");
            Map<String, String> wireTransfer = Map.of("transactionType", "WIRE_TRANSFER", "amount", "50000.00");
            Map<String, String> payment = Map.of("transactionType", "PAYMENT", "amount", "299.99");
            Map<String, String> refund = Map.of("transactionType", "REFUND", "amount", "45.00");
            Map<String, String> report = Map.of("transactionType", "DAILY_REPORT", "recordCount", "10000");

            sendPriorityMessageWithMetadata(producer, "txn-1", "FRAUD_ALERT", "Suspicious transaction detected", PRIORITY_CRITICAL, fraudAlert);
            sendPriorityMessageWithMetadata(producer, "txn-2", "WIRE_TRANSFER", "Large wire transfer", PRIORITY_HIGH, wireTransfer);
            sendPriorityMessageWithMetadata(producer, "txn-3", "PAYMENT", "Customer payment", PRIORITY_NORMAL, payment);
            sendPriorityMessageWithMetadata(producer, "txn-4", "REFUND", "Customer refund", PRIORITY_NORMAL, refund);
            sendPriorityMessageWithMetadata(producer, "txn-5", "REPORT", "Daily transaction report", PRIORITY_BULK, report);

            latch.await(30, TimeUnit.SECONDS);
            logger.info("Financial scenario completed");
        }
    }

    /**
     * System monitoring scenario: Critical alerts, warnings, regular monitoring.
     */
    private static void demonstrateMonitoringScenario(QueueFactory factory) throws Exception {
        logger.info("\n--- System Monitoring and Alerts Scenario ---");

        try (MessageProducer<PriorityMessage> producer = factory.createProducer("system-monitoring", PriorityMessage.class);
             MessageConsumer<PriorityMessage> consumer = factory.createConsumer("system-monitoring", PriorityMessage.class)) {

            AtomicInteger processedCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(4);

            consumer.subscribe(message -> {
                int order = processedCount.incrementAndGet();
                PriorityMessage payload = message.getPayload();
                String severity = payload.getMetadata().getOrDefault("severity", "INFO");
                String component = payload.getMetadata().getOrDefault("component", "unknown");

                logger.info("Processing alert #{}: {} - Component: {}, Severity: {}, Priority: {}",
                    order, payload.getContent(), component, severity, payload.getPriorityLabel());

                latch.countDown();
                return CompletableFuture.completedFuture(null);
            });

            // Send monitoring alerts with appropriate priorities
            Map<String, String> criticalAlert = Map.of("severity", "CRITICAL", "component", "DATABASE", "impact", "HIGH");
            Map<String, String> warningAlert = Map.of("severity", "WARNING", "component", "API_GATEWAY", "impact", "MEDIUM");
            Map<String, String> infoAlert = Map.of("severity", "INFO", "component", "CACHE", "impact", "LOW");
            Map<String, String> debugAlert = Map.of("severity", "DEBUG", "component", "LOGGER", "impact", "NONE");

            sendPriorityMessageWithMetadata(producer, "alert-1", "SYSTEM_ALERT", "Database connection pool exhausted", PRIORITY_CRITICAL, criticalAlert);
            sendPriorityMessageWithMetadata(producer, "alert-2", "SYSTEM_ALERT", "API response time degraded", PRIORITY_HIGH, warningAlert);
            sendPriorityMessageWithMetadata(producer, "alert-3", "SYSTEM_ALERT", "Cache hit ratio below threshold", PRIORITY_NORMAL, infoAlert);
            sendPriorityMessageWithMetadata(producer, "alert-4", "SYSTEM_ALERT", "Debug log level changed", PRIORITY_BULK, debugAlert);

            latch.await(30, TimeUnit.SECONDS);
            logger.info("Monitoring scenario completed");
        }
    }

    /**
     * Demonstrates performance characteristics of priority queues.
     */
    private static void demonstratePriorityPerformance(QueueFactory factory) throws Exception {
        logger.info("\n=== PRIORITY QUEUE PERFORMANCE DEMONSTRATION ===");

        try (MessageProducer<PriorityMessage> producer = factory.createProducer("priority-performance", PriorityMessage.class);
             MessageConsumer<PriorityMessage> consumer = factory.createConsumer("priority-performance", PriorityMessage.class)) {

            final int MESSAGE_COUNT = 20;
            AtomicInteger processedCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(MESSAGE_COUNT);
            long startTime = System.currentTimeMillis();

            consumer.subscribe(message -> {
                int order = processedCount.incrementAndGet();
                long currentTime = System.currentTimeMillis();
                long processingTime = currentTime - startTime;
                PriorityMessage payload = message.getPayload();

                logger.info("Processed #{}: {} (Priority: {}) - Total time: {}ms",
                    order, payload.getContent(), payload.getPriorityLabel(), processingTime);

                latch.countDown();
                return CompletableFuture.completedFuture(null);
            });

            // Send mixed priority messages to test performance
            logger.info("Sending {} mixed priority messages for performance testing...", MESSAGE_COUNT);

            for (int i = 1; i <= MESSAGE_COUNT; i++) {
                int priority = (i % 5) * 2; // Mix of priorities: 0, 2, 4, 6, 8
                String messageType = priority >= 6 ? "HIGH_PRIORITY" : priority >= 4 ? "NORMAL" : "LOW_PRIORITY";
                sendPriorityMessage(producer, "perf-" + i, messageType, "Performance test message " + i, priority);

                // Small delay to simulate real-world message arrival
                Thread.sleep(10);
            }

            // Wait for all messages to be processed
            boolean completed = latch.await(60, TimeUnit.SECONDS);
            long totalTime = System.currentTimeMillis() - startTime;

            if (completed) {
                logger.info("Performance test completed in {}ms", totalTime);
                logger.info("Average processing time: {}ms per message", totalTime / MESSAGE_COUNT);
            } else {
                logger.warn("Performance test did not complete within timeout");
            }
        }
    }

    /**
     * Helper method to send a priority message.
     */
    private static void sendPriorityMessage(MessageProducer<PriorityMessage> producer,
                                          String messageId, String messageType,
                                          String content, int priority) throws Exception {
        sendPriorityMessageWithMetadata(producer, messageId, messageType, content, priority, new HashMap<>());
    }

    /**
     * Helper method to send a priority message with metadata.
     */
    private static void sendPriorityMessageWithMetadata(MessageProducer<PriorityMessage> producer,
                                                       String messageId, String messageType,
                                                       String content, int priority,
                                                       Map<String, String> metadata) throws Exception {
        PriorityMessage message = new PriorityMessage(
            messageId, messageType, content, priority, Instant.now(), metadata);

        // Send with priority header
        Map<String, String> headers = new HashMap<>();
        headers.put("priority", String.valueOf(priority));
        headers.put("messageType", messageType);
        headers.putAll(metadata);

        producer.send(message, headers).get(5, TimeUnit.SECONDS);
        logger.debug("Sent message: {} with priority {}", messageId, priority);
    }
}
