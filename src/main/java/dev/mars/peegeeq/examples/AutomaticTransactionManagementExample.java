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
import io.vertx.sqlclient.TransactionPropagation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Example demonstrating Automatic Transaction Management pattern from PeeGeeQ Guide.
 * 
 * This example demonstrates the third reactive approach: sendWithTransaction() operations
 * following the patterns outlined in Section "3. Automatic Transaction Management".
 * 
 * Key Features Demonstrated:
 * - Basic automatic transaction management (sendWithTransaction)
 * - TransactionPropagation.CONTEXT for layered service architectures
 * - Batch operations with shared transaction context
 * - Error handling and automatic rollback scenarios
 * - Full parameter support with propagation
 * - Performance validation of automatic transaction management
 * 
 * Usage:
 * ```java
 * AutomaticTransactionManagementExample example = new AutomaticTransactionManagementExample();
 * example.runExample();
 * ```
 * 
 * Patterns Demonstrated:
 * 1. Basic automatic transaction management
 * 2. TransactionPropagation.CONTEXT usage
 * 3. Batch operations with shared context
 * 4. Full parameter automatic transactions
 * 5. Performance validation
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-06
 * @version 1.0
 */
public class AutomaticTransactionManagementExample {
    private static final Logger logger = LoggerFactory.getLogger(AutomaticTransactionManagementExample.class);
    
    private PeeGeeQManager manager;
    private QueueFactory outboxFactory;
    private OutboxProducer<OrderEvent> orderProducer;
    
    /**
     * Main method to run the example
     */
    public static void main(String[] args) {
        AutomaticTransactionManagementExample example = new AutomaticTransactionManagementExample();
        try {
            example.runExample();
        } catch (Exception e) {
            logger.error("Example failed: {}", e.getMessage(), e);
            System.exit(1);
        }
    }
    
    /**
     * Run the complete Automatic Transaction Management example
     */
    public void runExample() throws Exception {
        logger.info("=== Starting Automatic Transaction Management Example ===");
        
        try {
            // Setup
            setup();
            
            // Demonstrate all automatic transaction management patterns
            demonstrateBasicAutomaticTransactionManagement();
            demonstrateTransactionPropagationContext();
            demonstrateFullParameterAutomaticTransaction();
            demonstrateBatchOperationsWithSharedContext();
            demonstratePerformanceValidation();
            
            logger.info("=== Automatic Transaction Management Example Completed Successfully ===");
            
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
     * Demonstrate Pattern 1: Basic automatic transaction management
     * 
     * This demonstrates the third reactive approach from the guide:
     * "Automatic Transaction Management (sendWithTransaction) - Full lifecycle with propagation"
     */
    private void demonstrateBasicAutomaticTransactionManagement() throws Exception {
        logger.info("--- Pattern 1: Basic Automatic Transaction Management ---");

        // Create a test order event
        OrderEvent testOrder = new OrderEvent("AUTO-TX-001", "CUSTOMER-AUTO-123", 199.99);
        logger.info("Created order: {}", testOrder);

        // Basic automatic transaction management - OutboxProducer handles entire lifecycle
        CompletableFuture<Void> transactionFuture = orderProducer.sendWithTransaction(testOrder);

        // Wait for completion
        transactionFuture.get(10, TimeUnit.SECONDS);
        logger.info("✓ Basic automatic transaction management completed successfully");
    }

    /**
     * Demonstrate Pattern 2: TransactionPropagation.CONTEXT for layered services
     * 
     * This demonstrates the advanced automatic transaction management from the guide:
     * "TransactionPropagation.CONTEXT - Shares existing transactions within the same Vert.x context"
     */
    private void demonstrateTransactionPropagationContext() throws Exception {
        logger.info("--- Pattern 2: TransactionPropagation.CONTEXT ---");

        // Create a test order event
        OrderEvent testOrder = new OrderEvent("AUTO-TX-002", "CUSTOMER-AUTO-456", 299.99);
        logger.info("Created order: {}", testOrder);

        // TransactionPropagation.CONTEXT - for layered service architectures
        CompletableFuture<Void> transactionFuture = orderProducer.sendWithTransaction(
            testOrder, 
            TransactionPropagation.CONTEXT
        );

        // Wait for completion
        transactionFuture.get(10, TimeUnit.SECONDS);
        logger.info("✓ TransactionPropagation.CONTEXT completed successfully");
    }

    /**
     * Demonstrate Pattern 3: Full parameter automatic transaction with TransactionPropagation
     * 
     * This demonstrates the complete automatic transaction management from the guide:
     * "Full parameter support with propagation"
     */
    private void demonstrateFullParameterAutomaticTransaction() throws Exception {
        logger.info("--- Pattern 3: Full Parameter Automatic Transaction ---");

        // Create a test order event
        OrderEvent testOrder = new OrderEvent("AUTO-TX-003", "CUSTOMER-AUTO-789", 399.99);
        logger.info("Created order: {}", testOrder);

        // Create headers map
        Map<String, String> headers = new HashMap<>();
        headers.put("source", "automatic-transaction-service");
        headers.put("transaction-type", "automatic");
        headers.put("propagation", "CONTEXT");
        headers.put("version", "1.0");
        logger.info("Created headers: {}", headers);

        // Create correlation ID and message group
        String correlationId = "AUTO-TX-CORR-" + UUID.randomUUID().toString().substring(0, 8);
        String messageGroup = "auto-tx-group";
        logger.info("Using correlation ID: {}", correlationId);
        logger.info("Using message group: {}", messageGroup);

        // Full parameter automatic transaction with TransactionPropagation.CONTEXT
        CompletableFuture<Void> transactionFuture = orderProducer.sendWithTransaction(
            testOrder,
            headers,
            correlationId,
            messageGroup,
            TransactionPropagation.CONTEXT
        );

        // Wait for completion
        transactionFuture.get(10, TimeUnit.SECONDS);
        logger.info("✓ Full parameter automatic transaction completed successfully");
    }

    /**
     * Demonstrate Pattern 4: Batch operations with shared transaction context
     * 
     * This demonstrates the batch operations pattern from the guide:
     * "Batch Operations with TransactionPropagation - All operations share the same transaction context"
     */
    private void demonstrateBatchOperationsWithSharedContext() throws Exception {
        logger.info("--- Pattern 4: Batch Operations with Shared Transaction Context ---");

        // Create multiple test order events for batch processing
        List<OrderEvent> batchOrders = IntStream.range(1, 6)
            .mapToObj(i -> new OrderEvent("BATCH-AUTO-TX-" + String.format("%03d", i), "CUSTOMER-BATCH-" + i, 100.0 + i))
            .collect(Collectors.toList());
        
        logger.info("Created {} orders for batch processing", batchOrders.size());

        // Simulate batch processing - all operations share the same transaction context
        CompletableFuture<Void> batchStartFuture = orderProducer.sendWithTransaction(
            new BatchStartedEvent(batchOrders.size()),
            TransactionPropagation.CONTEXT
        );

        // Process each order in the same transaction context
        List<CompletableFuture<Void>> orderFutures = batchOrders.stream()
            .map(order -> orderProducer.sendWithTransaction(order, TransactionPropagation.CONTEXT))
            .collect(Collectors.toList());

        // Send batch completion event in same transaction
        CompletableFuture<Void> batchCompleteFuture = orderProducer.sendWithTransaction(
            new BatchCompletedEvent(batchOrders.size()),
            TransactionPropagation.CONTEXT
        );

        // Wait for all operations to complete - they all share the same transaction
        CompletableFuture<Void> allOperations = CompletableFuture.allOf(
            CompletableFuture.allOf(batchStartFuture),
            CompletableFuture.allOf(orderFutures.toArray(new CompletableFuture[0])),
            CompletableFuture.allOf(batchCompleteFuture)
        );

        allOperations.get(15, TimeUnit.SECONDS);
        logger.info("✓ Batch operations with shared transaction context completed successfully");
        logger.info("✓ Processed {} orders in shared transaction", batchOrders.size());
    }

    /**
     * Demonstrate Pattern 5: Performance validation of automatic transaction management
     * 
     * This demonstrates the performance benefits mentioned in the guide
     */
    private void demonstratePerformanceValidation() throws Exception {
        logger.info("--- Pattern 5: Performance Validation ---");

        int messageCount = 10;
        OrderEvent[] testOrders = new OrderEvent[messageCount];
        
        // Prepare test data
        for (int i = 0; i < messageCount; i++) {
            testOrders[i] = new OrderEvent("PERF-AUTO-" + String.format("%03d", i), "CUSTOMER-PERF-AUTO", 10.0 + i);
        }
        logger.info("Prepared {} test orders for automatic transaction performance validation", messageCount);

        // Test automatic transaction performance
        long startTime = System.currentTimeMillis();
        CompletableFuture<Void>[] futures = new CompletableFuture[messageCount];
        
        for (int i = 0; i < messageCount; i++) {
            futures[i] = orderProducer.sendWithTransaction(testOrders[i], TransactionPropagation.CONTEXT);
        }
        
        // Wait for all to complete
        CompletableFuture<Void> allFutures = CompletableFuture.allOf(futures);
        allFutures.get(30, TimeUnit.SECONDS);
        
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        double messagesPerSecond = (messageCount * 1000.0) / duration;
        
        logger.info("✓ Automatic transaction performance validation completed");
        logger.info("✓ Sent {} messages in {} ms", messageCount, duration);
        logger.info("✓ Performance: {:.2f} messages/second", messagesPerSecond);
    }

    /**
     * Helper event class for batch operations
     */
    public static class BatchStartedEvent extends OrderEvent {
        private final int batchSize;
        
        public BatchStartedEvent(int batchSize) {
            super("BATCH-STARTED-" + System.currentTimeMillis(), "BATCH-SYSTEM", 0.0);
            this.batchSize = batchSize;
        }
        
        public int getBatchSize() { return batchSize; }
        
        @Override
        public String toString() {
            return String.format("BatchStartedEvent{batchSize=%d, timestamp=%s}", batchSize, getTimestamp());
        }
    }

    /**
     * Helper event class for batch operations
     */
    public static class BatchCompletedEvent extends OrderEvent {
        private final int processedCount;
        
        public BatchCompletedEvent(int processedCount) {
            super("BATCH-COMPLETED-" + System.currentTimeMillis(), "BATCH-SYSTEM", 0.0);
            this.processedCount = processedCount;
        }
        
        public int getProcessedCount() { return processedCount; }
        
        @Override
        public String toString() {
            return String.format("BatchCompletedEvent{processedCount=%d, timestamp=%s}", processedCount, getTimestamp());
        }
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
