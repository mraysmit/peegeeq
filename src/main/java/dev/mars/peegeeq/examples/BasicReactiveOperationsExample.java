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

import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.QueueFactoryProvider;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.outbox.OutboxFactoryRegistrar;
import dev.mars.peegeeq.outbox.OutboxProducer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Example demonstrating Basic Reactive Operations pattern from PeeGeeQ Guide.
 * 
 * This example demonstrates the first reactive approach: sendReactive() operations
 * following the patterns outlined in Section "1. Basic Reactive Operations".
 * 
 * Key Features Demonstrated:
 * - Simple reactive send (sendReactive)
 * - Reactive send with metadata (headers, correlation ID, message groups)
 * - Non-blocking operations without transaction management
 * - Performance benefits of reactive operations
 * 
 * Usage:
 * ```java
 * BasicReactiveOperationsExample example = new BasicReactiveOperationsExample();
 * example.runExample();
 * ```
 * 
 * Patterns Demonstrated:
 * 1. Simple reactive send
 * 2. Reactive send with headers
 * 3. Reactive send with correlation ID
 * 4. Reactive send with full parameters (headers, correlation ID, message groups)
 * 5. Performance validation
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-06
 * @version 1.0
 */
public class BasicReactiveOperationsExample {
    private static final Logger logger = LoggerFactory.getLogger(BasicReactiveOperationsExample.class);
    
    private PeeGeeQManager manager;
    private QueueFactory outboxFactory;
    private OutboxProducer<OrderEvent> orderProducer;
    
    /**
     * Main method to run the example
     */
    public static void main(String[] args) {
        BasicReactiveOperationsExample example = new BasicReactiveOperationsExample();
        try {
            example.runExample();
        } catch (Exception e) {
            logger.error("Example failed: {}", e.getMessage(), e);
            System.exit(1);
        }
    }
    
    /**
     * Run the complete Basic Reactive Operations example
     */
    public void runExample() throws Exception {
        logger.info("=== Starting Basic Reactive Operations Example ===");
        
        try {
            // Setup
            setup();
            
            // Demonstrate all reactive operations patterns
            demonstrateSimpleReactiveSend();
            demonstrateReactiveSendWithHeaders();
            demonstrateReactiveSendWithCorrelationId();
            demonstrateFullParameterReactiveSend();
            demonstratePerformanceValidation();
            
            logger.info("=== Basic Reactive Operations Example Completed Successfully ===");
            
        } finally {
            // Cleanup
            cleanup();
        }
    }
    
    /**
     * Setup PeeGeeQ components
     */
    private void setup() throws Exception {
        logger.info("Setting up PeeGeeQ components...");
        
        // Note: In a real application, these would come from configuration
        System.setProperty("peegeeq.database.host", "localhost");
        System.setProperty("peegeeq.database.port", "5432");
        System.setProperty("peegeeq.database.name", "peegeeq_examples");
        System.setProperty("peegeeq.database.username", "peegeeq_user");
        System.setProperty("peegeeq.database.password", "peegeeq_password");
        
        // Initialize PeeGeeQ Manager
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());
        manager.start();
        logger.info("✓ PeeGeeQ Manager started");
        
        // Create outbox factory
        DatabaseService databaseService = new PgDatabaseService(manager);
        QueueFactoryProvider provider = new PgQueueFactoryProvider();
        
        // Register outbox factory implementation
        OutboxFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
        
        outboxFactory = provider.createFactory("outbox", databaseService);
        orderProducer = (OutboxProducer<OrderEvent>) outboxFactory.createProducer("orders", OrderEvent.class);
        
        logger.info("✓ Setup completed successfully");
    }
    
    /**
     * Cleanup resources
     */
    private void cleanup() throws Exception {
        logger.info("Cleaning up resources...");
        
        if (orderProducer != null) {
            orderProducer.close();
            logger.info("✓ Order producer closed");
        }
        
        if (manager != null) {
            manager.stop();
            logger.info("✓ PeeGeeQ Manager stopped");
        }
        
        logger.info("✓ Cleanup completed");
    }
    
    /**
     * Demonstrate Pattern 1: Simple reactive send operation
     * 
     * This demonstrates the basic sendReactive() method from the guide:
     * "Simple Reactive Send" - sendReactive(payload)
     */
    private void demonstrateSimpleReactiveSend() throws Exception {
        logger.info("--- Pattern 1: Simple Reactive Send ---");

        // Create a test order event
        OrderEvent testOrder = new OrderEvent("ORDER-001", "CUSTOMER-123", 99.99);
        logger.info("Created order: {}", testOrder);

        // Simple reactive send - non-blocking operation
        CompletableFuture<Void> sendFuture = orderProducer.sendReactive(testOrder);

        // Wait for completion
        sendFuture.get(10, TimeUnit.SECONDS);
        logger.info("✓ Simple reactive send completed successfully");
    }

    /**
     * Demonstrate Pattern 2: Reactive send with headers
     * 
     * This demonstrates sendReactive() with metadata from the guide:
     * "Reactive send with headers" - sendReactive(payload, headers)
     */
    private void demonstrateReactiveSendWithHeaders() throws Exception {
        logger.info("--- Pattern 2: Reactive Send with Headers ---");

        // Create a test order event
        OrderEvent testOrder = new OrderEvent("ORDER-002", "CUSTOMER-456", 149.99);
        logger.info("Created order: {}", testOrder);

        // Create headers map
        Map<String, String> headers = new HashMap<>();
        headers.put("source", "order-service");
        headers.put("version", "1.0");
        headers.put("priority", "high");
        logger.info("Created headers: {}", headers);

        // Reactive send with headers
        CompletableFuture<Void> sendFuture = orderProducer.sendReactive(testOrder, headers);

        // Wait for completion
        sendFuture.get(10, TimeUnit.SECONDS);
        logger.info("✓ Reactive send with headers completed successfully");
    }

    /**
     * Demonstrate Pattern 3: Reactive send with correlation ID
     * 
     * This demonstrates sendReactive() with correlation tracking from the guide:
     * "Reactive send with correlation ID" - sendReactive(payload, headers, correlationId)
     */
    private void demonstrateReactiveSendWithCorrelationId() throws Exception {
        logger.info("--- Pattern 3: Reactive Send with Correlation ID ---");

        // Create a test order event
        OrderEvent testOrder = new OrderEvent("ORDER-003", "CUSTOMER-789", 199.99);
        logger.info("Created order: {}", testOrder);

        // Create headers and correlation ID
        Map<String, String> headers = Map.of("source", "order-service", "version", "1.0");
        String correlationId = "CORR-" + UUID.randomUUID().toString().substring(0, 8);
        logger.info("Created correlation ID: {}", correlationId);

        // Reactive send with correlation ID
        CompletableFuture<Void> sendFuture = orderProducer.sendReactive(testOrder, headers, correlationId);

        // Wait for completion
        sendFuture.get(10, TimeUnit.SECONDS);
        logger.info("✓ Reactive send with correlation ID completed successfully");
    }

    /**
     * Demonstrate Pattern 4: Full parameter reactive send
     * 
     * This demonstrates the complete sendReactive() method from the guide:
     * "Full reactive send with all parameters" - sendReactive(payload, headers, correlationId, messageGroup)
     */
    private void demonstrateFullParameterReactiveSend() throws Exception {
        logger.info("--- Pattern 4: Full Parameter Reactive Send ---");

        // Create a test order event
        OrderEvent testOrder = new OrderEvent("ORDER-004", "CUSTOMER-999", 299.99);
        logger.info("Created order: {}", testOrder);

        // Create full parameters
        Map<String, String> headers = Map.of(
            "source", "order-service",
            "version", "1.0",
            "priority", "high",
            "region", "us-east-1"
        );
        String correlationId = "CORR-" + UUID.randomUUID().toString().substring(0, 8);
        String messageGroup = "order-group-1";
        
        logger.info("Using correlation ID: {}", correlationId);
        logger.info("Using message group: {}", messageGroup);

        // Full parameter reactive send
        CompletableFuture<Void> sendFuture = orderProducer.sendReactive(
            testOrder, headers, correlationId, messageGroup);

        // Wait for completion
        sendFuture.get(10, TimeUnit.SECONDS);
        logger.info("✓ Full parameter reactive send completed successfully");
    }

    /**
     * Demonstrate Pattern 5: Performance validation
     * 
     * This demonstrates the performance benefits mentioned in the guide
     */
    private void demonstratePerformanceValidation() throws Exception {
        logger.info("--- Pattern 5: Performance Validation ---");

        int messageCount = 10;
        OrderEvent[] testOrders = new OrderEvent[messageCount];
        
        // Prepare test data
        for (int i = 0; i < messageCount; i++) {
            testOrders[i] = new OrderEvent("PERF-" + String.format("%03d", i), "CUSTOMER-PERF", 10.0 + i);
        }
        logger.info("Prepared {} test orders for performance validation", messageCount);

        // Test reactive send performance
        long startTime = System.currentTimeMillis();
        CompletableFuture<Void>[] futures = new CompletableFuture[messageCount];
        
        for (int i = 0; i < messageCount; i++) {
            futures[i] = orderProducer.sendReactive(testOrders[i]);
        }
        
        // Wait for all to complete
        CompletableFuture<Void> allFutures = CompletableFuture.allOf(futures);
        allFutures.get(30, TimeUnit.SECONDS);
        
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        double messagesPerSecond = (messageCount * 1000.0) / duration;
        
        logger.info("✓ Performance validation completed");
        logger.info("✓ Sent {} messages in {} ms", messageCount, duration);
        logger.info("✓ Performance: {:.2f} messages/second", messagesPerSecond);
    }

    /**
     * Simple event class for demonstration
     */
    public static class OrderEvent {
        private final String orderId;
        private final String customerId;
        private final double amount;
        private final Instant timestamp;
        
        public OrderEvent(String orderId, String customerId, double amount) {
            this.orderId = orderId;
            this.customerId = customerId;
            this.amount = amount;
            this.timestamp = Instant.now();
        }
        
        // Getters
        public String getOrderId() { return orderId; }
        public String getCustomerId() { return customerId; }
        public double getAmount() { return amount; }
        public Instant getTimestamp() { return timestamp; }
        
        @Override
        public String toString() {
            return String.format("OrderEvent{orderId='%s', customerId='%s', amount=%.2f, timestamp=%s}", 
                orderId, customerId, amount, timestamp);
        }
    }
}
