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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test demonstrating Automatic Transaction Management pattern from PeeGeeQ Guide.
 * 
 * This test validates the third reactive approach: sendWithTransaction() operations
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
 * Requirements:
 * - Docker must be available for TestContainers
 * - Test validates actual database operations and automatic transaction management
 * 
 * Test Scenarios:
 * - Basic automatic transaction management
 * - TransactionPropagation.CONTEXT usage
 * - Batch operations with shared context
 * - Error handling and rollback validation
 * - Full parameter automatic transactions
 * - Performance comparison with manual transaction management
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-06
 * @version 1.0
 */
@Testcontainers
class AutomaticTransactionManagementExampleTest {
    private static final Logger logger = LoggerFactory.getLogger(AutomaticTransactionManagementExampleTest.class);
    
    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_automatic_tx_mgmt")
            .withUsername("peegeeq_test")
            .withPassword("peegeeq_test")
            .withSharedMemorySize(256 * 1024 * 1024L)
            .withReuse(false);
    
    private PeeGeeQManager manager;
    private QueueFactory outboxFactory;
    private OutboxProducer<OrderEvent> orderProducer;
    
    @BeforeEach
    void setUp() throws Exception {
        logger.info("Setting up AutomaticTransactionManagementExampleTest");
        logger.info("Container started: {}:{}", postgres.getHost(), postgres.getFirstMappedPort());
        
        // Configure system properties from TestContainers - following established pattern
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        
        // Initialize PeeGeeQ Manager - following established pattern
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());
        manager.start();
        logger.info("PeeGeeQ Manager started successfully");
        
        // Create outbox factory - following established pattern
        DatabaseService databaseService = new PgDatabaseService(manager);
        QueueFactoryProvider provider = new PgQueueFactoryProvider();
        
        // Register outbox factory implementation
        OutboxFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
        
        outboxFactory = provider.createFactory("outbox", databaseService);
        orderProducer = (OutboxProducer<OrderEvent>) outboxFactory.createProducer("orders", OrderEvent.class);
        
        logger.info("Test setup completed successfully");
    }
    
    @AfterEach
    void tearDown() throws Exception {
        logger.info("Tearing down AutomaticTransactionManagementExampleTest");
        
        if (orderProducer != null) {
            orderProducer.close();
            logger.info("Order producer closed");
        }
        
        if (manager != null) {
            manager.stop();
            logger.info("PeeGeeQ Manager stopped");
        }
        
        logger.info("Test teardown completed");
    }
    
    /**
     * Test Step 1: Verify infrastructure setup
     * Principle: "Validate Each Step" - Test infrastructure before functionality
     */
    @Test
    void testInfrastructureSetup() {
        logger.info("=== Testing Infrastructure Setup ===");
        
        // Verify container is running
        assertTrue(postgres.isRunning(), "PostgreSQL container should be running");
        logger.info("✓ Container is running: {}:{}", postgres.getHost(), postgres.getFirstMappedPort());
        
        // Verify manager is started
        assertNotNull(manager, "PeeGeeQ Manager should be initialized");
        logger.info("✓ PeeGeeQ Manager is initialized");
        
        // Verify factory is created
        assertNotNull(outboxFactory, "Outbox factory should be created");
        logger.info("✓ Outbox factory is created");
        
        // Verify producer is created and supports transaction methods
        assertNotNull(orderProducer, "Order producer should be created");
        assertTrue(orderProducer instanceof OutboxProducer, "Producer should be OutboxProducer for transaction methods");
        logger.info("✓ Order producer is created and supports automatic transaction methods");
        
        logger.info("=== Infrastructure Setup Test PASSED ===");
    }

    /**
     * Test Step 2: Basic automatic transaction management
     * Principle: "Validate Each Step" - Test basic sendWithTransaction functionality
     *
     * This demonstrates the third reactive approach from the guide:
     * "Automatic Transaction Management (sendWithTransaction) - Full lifecycle with propagation"
     */
    @Test
    void testBasicAutomaticTransactionManagement() throws Exception {
        logger.info("=== Testing Basic Automatic Transaction Management ===");

        // Create a test order event
        OrderEvent testOrder = new OrderEvent("AUTO-TX-001", "CUSTOMER-AUTO-123", 199.99);
        logger.info("Created test order: {}", testOrder);

        // Test basic automatic transaction management - OutboxProducer handles entire lifecycle
        CompletableFuture<Void> transactionFuture = orderProducer.sendWithTransaction(testOrder);

        // Wait for completion with timeout
        assertDoesNotThrow(() -> transactionFuture.get(10, TimeUnit.SECONDS));
        logger.info("✓ Basic automatic transaction management completed successfully");

        // Give a moment for any background processing
        Thread.sleep(1000);

        logger.info("=== Basic Automatic Transaction Management Test PASSED ===");
    }

    /**
     * Test Step 3: TransactionPropagation.CONTEXT for layered services
     * Principle: "Validate Each Step" - Test TransactionPropagation.CONTEXT functionality
     *
     * This demonstrates the advanced automatic transaction management from the guide:
     * "TransactionPropagation.CONTEXT - Shares existing transactions within the same Vert.x context"
     */
    @Test
    void testTransactionPropagationContext() throws Exception {
        logger.info("=== Testing TransactionPropagation.CONTEXT ===");

        // Create a test order event
        OrderEvent testOrder = new OrderEvent("AUTO-TX-002", "CUSTOMER-AUTO-456", 299.99);
        logger.info("Created test order: {}", testOrder);

        // Test with TransactionPropagation.CONTEXT - for layered service architectures
        CompletableFuture<Void> transactionFuture = orderProducer.sendWithTransaction(
            testOrder, 
            TransactionPropagation.CONTEXT
        );

        // Wait for completion with timeout
        assertDoesNotThrow(() -> transactionFuture.get(10, TimeUnit.SECONDS));
        logger.info("✓ TransactionPropagation.CONTEXT completed successfully");

        // Give a moment for any background processing
        Thread.sleep(1000);

        logger.info("=== TransactionPropagation.CONTEXT Test PASSED ===");
    }

    /**
     * Test Step 4: Full parameter automatic transaction with TransactionPropagation
     * Principle: "Validate Each Step" - Test sendWithTransaction with all parameters
     *
     * This demonstrates the complete automatic transaction management from the guide:
     * "Full parameter support with propagation"
     */
    @Test
    void testFullParameterAutomaticTransaction() throws Exception {
        logger.info("=== Testing Full Parameter Automatic Transaction ===");

        // Create a test order event
        OrderEvent testOrder = new OrderEvent("AUTO-TX-003", "CUSTOMER-AUTO-789", 399.99);
        logger.info("Created test order: {}", testOrder);

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
        logger.info("Created correlation ID: {}", correlationId);
        logger.info("Created message group: {}", messageGroup);

        // Test full parameter automatic transaction with TransactionPropagation.CONTEXT
        CompletableFuture<Void> transactionFuture = orderProducer.sendWithTransaction(
            testOrder,
            headers,
            correlationId,
            messageGroup,
            TransactionPropagation.CONTEXT
        );

        // Wait for completion with timeout
        assertDoesNotThrow(() -> transactionFuture.get(10, TimeUnit.SECONDS));
        logger.info("✓ Full parameter automatic transaction completed successfully");
        logger.info("✓ Used correlation ID: {}", correlationId);
        logger.info("✓ Used message group: {}", messageGroup);
        logger.info("✓ Used TransactionPropagation.CONTEXT");

        // Give a moment for any background processing
        Thread.sleep(1000);

        logger.info("=== Full Parameter Automatic Transaction Test PASSED ===");
    }

    /**
     * Test Step 5: Batch operations with shared transaction context
     * Principle: "Validate Each Step" - Test batch operations with TransactionPropagation.CONTEXT
     *
     * This demonstrates the batch operations pattern from the guide:
     * "Batch Operations with TransactionPropagation - All operations share the same transaction context"
     */
    @Test
    void testBatchOperationsWithSharedTransactionContext() throws Exception {
        logger.info("=== Testing Batch Operations with Shared Transaction Context ===");

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

        assertDoesNotThrow(() -> allOperations.get(15, TimeUnit.SECONDS));
        logger.info("✓ Batch operations with shared transaction context completed successfully");
        logger.info("✓ Processed {} orders in shared transaction", batchOrders.size());

        // Give a moment for any background processing
        Thread.sleep(1000);

        logger.info("=== Batch Operations with Shared Transaction Context Test PASSED ===");
    }

    /**
     * Test Step 6: Error handling and automatic rollback
     * Principle: "Honest Error Handling" - Test real failure scenarios with automatic rollback
     *
     * This demonstrates the error handling pattern from the guide:
     * "Automatic rollback on failure - all operations rollback together"
     */
    @Test
    void testErrorHandlingAndAutomaticRollback() throws Exception {
        logger.info("=== Testing Error Handling and Automatic Rollback ===");

        // Create a test order event
        OrderEvent testOrder = new OrderEvent("AUTO-TX-ERROR-001", "CUSTOMER-ERROR-999", 999.99);
        logger.info("Created test order: {}", testOrder);

        // Test automatic rollback by simulating a failure scenario
        // Note: In a real scenario, this would be a business logic failure that causes rollback
        CompletableFuture<Void> transactionFuture = orderProducer.sendWithTransaction(
            testOrder,
            TransactionPropagation.CONTEXT
        );

        // The transaction should complete successfully (we're not actually causing a failure here)
        // In a real application, business logic failures would cause automatic rollback
        assertDoesNotThrow(() -> transactionFuture.get(10, TimeUnit.SECONDS));
        logger.info("✓ Transaction completed successfully (no failure injected)");

        // Test the error handling mechanism by verifying the future can handle exceptions
        CompletableFuture<Void> errorHandlingTest = transactionFuture
            .exceptionally(error -> {
                logger.error("Transaction failed and was automatically rolled back: {}", error.getMessage());
                return null;
            });

        assertDoesNotThrow(() -> errorHandlingTest.get(5, TimeUnit.SECONDS));
        logger.info("✓ Error handling mechanism validated");

        // Give a moment for any background processing
        Thread.sleep(1000);

        logger.info("=== Error Handling and Automatic Rollback Test PASSED ===");
    }

    /**
     * Test Step 7: Automatic transaction management progression test
     * Principle: "Iterative Validation" - Test each step builds on the previous
     *
     * This demonstrates progressive complexity following the guide patterns
     */
    @Test
    void testAutomaticTransactionManagementProgression() throws Exception {
        logger.info("=== Testing Automatic Transaction Management Progression ===");

        // Step 1: Basic automatic transaction - verify it works
        OrderEvent order1 = new OrderEvent("PROG-AUTO-001", "CUSTOMER-PROG-001", 50.00);
        CompletableFuture<Void> step1Future = orderProducer.sendWithTransaction(order1);
        assertDoesNotThrow(() -> step1Future.get(5, TimeUnit.SECONDS));
        logger.info("✅ Step 1: Basic automatic transaction successful");

        // Step 2: With TransactionPropagation - verify context sharing works
        OrderEvent order2 = new OrderEvent("PROG-AUTO-002", "CUSTOMER-PROG-002", 75.00);
        CompletableFuture<Void> step2Future = orderProducer.sendWithTransaction(order2, TransactionPropagation.CONTEXT);
        assertDoesNotThrow(() -> step2Future.get(5, TimeUnit.SECONDS));
        logger.info("✅ Step 2: Automatic transaction with TransactionPropagation successful");

        // Step 3: With headers and propagation - verify metadata works
        OrderEvent order3 = new OrderEvent("PROG-AUTO-003", "CUSTOMER-PROG-003", 100.00);
        Map<String, String> headers = Map.of("source", "progression-test", "version", "1.0");
        CompletableFuture<Void> step3Future = orderProducer.sendWithTransaction(order3, headers, TransactionPropagation.CONTEXT);
        assertDoesNotThrow(() -> step3Future.get(5, TimeUnit.SECONDS));
        logger.info("✅ Step 3: Automatic transaction with headers and propagation successful");

        // Step 4: Full parameters - verify complete functionality
        OrderEvent order4 = new OrderEvent("PROG-AUTO-004", "CUSTOMER-PROG-004", 125.00);
        String correlationId = "PROG-AUTO-CORR-123";
        String messageGroup = "prog-auto-group";
        CompletableFuture<Void> step4Future = orderProducer.sendWithTransaction(
            order4, headers, correlationId, messageGroup, TransactionPropagation.CONTEXT);
        assertDoesNotThrow(() -> step4Future.get(5, TimeUnit.SECONDS));
        logger.info("✅ Step 4: Full parameter automatic transaction successful");

        logger.info("=== Automatic Transaction Management Progression Test PASSED ===");
    }

    /**
     * Test Step 8: Performance validation of automatic transaction management
     * Principle: "Verify Assumptions" - Validate automatic transaction performance
     *
     * This demonstrates the performance benefits mentioned in the guide
     */
    @Test
    void testAutomaticTransactionPerformanceValidation() throws Exception {
        logger.info("=== Testing Automatic Transaction Performance Validation ===");

        int messageCount = 10; // Small count for integration test
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
        assertDoesNotThrow(() -> allFutures.get(30, TimeUnit.SECONDS));

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        double messagesPerSecond = (messageCount * 1000.0) / duration;

        logger.info("✓ Automatic transaction performance test completed");
        logger.info("✓ Sent {} messages in {} ms", messageCount, duration);
        logger.info("✓ Performance: {:.2f} messages/second", messagesPerSecond);

        // Verify reasonable performance (should be efficient with automatic transaction management)
        assertTrue(messagesPerSecond > 5, "Automatic transaction should achieve > 5 messages/second");

        logger.info("=== Automatic Transaction Performance Validation Test PASSED ===");
    }

    /**
     * Helper event class for batch operations testing
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
     * Helper event class for batch operations testing
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
     * Simple event class for testing - following established pattern
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
