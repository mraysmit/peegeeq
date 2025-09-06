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

import java.math.BigDecimal;
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
 * Example demonstrating Advanced Batch Operations with TransactionPropagation pattern from PeeGeeQ Guide.
 * 
 * This example demonstrates the advanced usage pattern: Batch Operations with TransactionPropagation
 * following the patterns outlined in Section "Advanced Usage Patterns - 1. Batch Operations".
 * 
 * Key Features Demonstrated:
 * - Complex batch processing with shared transaction context
 * - Multi-stage batch operations with TransactionPropagation.CONTEXT
 * - Batch validation and error handling
 * - Nested batch operations with different propagation strategies
 * - Performance optimization for large batch processing
 * - Batch completion tracking and reporting
 * 
 * Usage:
 * ```java
 * BatchOperationsWithPropagationExample example = new BatchOperationsWithPropagationExample();
 * example.runExample();
 * ```
 * 
 * Patterns Demonstrated:
 * 1. Simple batch processing with shared transaction context
 * 2. Multi-stage batch operations with validation
 * 3. Nested batch operations with different propagation
 * 4. Large batch processing with chunking
 * 5. Batch error handling and partial completion
 * 6. Performance optimization for batch operations
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-06
 * @version 1.0
 */
public class BatchOperationsWithPropagationExample {
    private static final Logger logger = LoggerFactory.getLogger(BatchOperationsWithPropagationExample.class);
    
    private PeeGeeQManager manager;
    private QueueFactory outboxFactory;
    private OutboxProducer<OrderEvent> orderProducer;
    private OutboxProducer<BatchEvent> batchProducer;
    
    /**
     * Main method to run the example
     */
    public static void main(String[] args) {
        BatchOperationsWithPropagationExample example = new BatchOperationsWithPropagationExample();
        try {
            example.runExample();
        } catch (Exception e) {
            logger.error("Example failed: {}", e.getMessage(), e);
            System.exit(1);
        }
    }
    
    /**
     * Run the complete Batch Operations with Propagation example
     */
    public void runExample() throws Exception {
        logger.info("=== Starting Batch Operations with TransactionPropagation Example ===");
        
        try {
            // Setup
            setup();
            
            // Demonstrate all batch operations patterns
            demonstrateSimpleBatchProcessing();
            demonstrateMultiStageBatchOperations();
            demonstrateNestedBatchOperations();
            demonstrateLargeBatchProcessingWithChunking();
            demonstrateBatchErrorHandlingAndPartialCompletion();
            demonstratePerformanceOptimizedBatchProcessing();
            
            logger.info("=== Batch Operations with TransactionPropagation Example Completed Successfully ===");
            
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
        batchProducer = (OutboxProducer<BatchEvent>) outboxFactory.createProducer("batches", BatchEvent.class);
        
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
        
        if (batchProducer != null) {
            batchProducer.close();
            logger.info("✓ Batch producer closed");
        }
        
        if (manager != null) {
            manager.stop();
            logger.info("✓ PeeGeeQ Manager stopped");
        }
        
        logger.info("✓ Cleanup completed");
    }
    
    /**
     * Demonstrate Pattern 1: Simple batch processing with shared transaction context
     * 
     * This demonstrates the basic batch operations pattern from the guide:
     * "All operations share the same transaction context"
     */
    private void demonstrateSimpleBatchProcessing() throws Exception {
        logger.info("--- Pattern 1: Simple Batch Processing with Shared Transaction Context ---");

        // Create a batch of orders
        List<OrderEvent> orders = IntStream.range(1, 6)
            .mapToObj(i -> new OrderEvent("SIMPLE-BATCH-" + String.format("%03d", i), "CUSTOMER-" + i, 100.0 + i))
            .collect(Collectors.toList());
        
        logger.info("Created batch of {} orders", orders.size());

        // Process batch following the guide pattern
        CompletableFuture<List<String>> batchResult = processBatchOrders(orders);

        // Wait for completion
        List<String> results = batchResult.get(15, TimeUnit.SECONDS);
        logger.info("✓ Simple batch processing completed successfully");
        logger.info("✓ Processed {} orders: {}", results.size(), results);
    }

    /**
     * Process batch orders following the exact pattern from the guide
     */
    private CompletableFuture<List<String>> processBatchOrders(List<OrderEvent> orders) {
        // All operations share the same transaction context
        return batchProducer.sendWithTransaction(
            new BatchStartedEvent(orders.size()),
            TransactionPropagation.CONTEXT
        )
        .thenCompose(v -> {
            // Process each order in the same transaction
            List<CompletableFuture<String>> futures = orders.stream()
                .map(this::processOrder) // Uses CONTEXT propagation
                .collect(Collectors.toList());

            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(ignored -> futures.stream()
                    .map(CompletableFuture::join)
                    .collect(Collectors.toList()));
        })
        .thenCompose(results -> {
            // Send completion event in same transaction
            return batchProducer.sendWithTransaction(
                new BatchCompletedEvent(results),
                TransactionPropagation.CONTEXT
            ).thenApply(v -> results);
        });
    }

    /**
     * Process individual order with CONTEXT propagation
     */
    private CompletableFuture<String> processOrder(OrderEvent order) {
        return orderProducer.sendWithTransaction(order, TransactionPropagation.CONTEXT)
            .thenApply(v -> "Order " + order.getOrderId() + " processed successfully");
    }

    /**
     * Demonstrate Pattern 2: Multi-stage batch operations with validation
     * 
     * This demonstrates complex batch processing with multiple stages
     */
    private void demonstrateMultiStageBatchOperations() throws Exception {
        logger.info("--- Pattern 2: Multi-Stage Batch Operations with Validation ---");

        // Create a batch of orders with different amounts for validation
        List<OrderEvent> orders = List.of(
            new OrderEvent("MULTI-STAGE-001", "CUSTOMER-A", 150.00),
            new OrderEvent("MULTI-STAGE-002", "CUSTOMER-B", 250.00),
            new OrderEvent("MULTI-STAGE-003", "CUSTOMER-C", 350.00),
            new OrderEvent("MULTI-STAGE-004", "CUSTOMER-D", 450.00)
        );
        
        logger.info("Created multi-stage batch of {} orders", orders.size());

        // Process with validation stages
        CompletableFuture<List<String>> multiStageResult = processMultiStageBatch(orders);

        // Wait for completion
        List<String> results = multiStageResult.get(20, TimeUnit.SECONDS);
        logger.info("✓ Multi-stage batch processing completed successfully");
        logger.info("✓ Processed {} orders through validation stages", results.size());
    }

    /**
     * Process batch with multiple validation stages
     */
    private CompletableFuture<List<String>> processMultiStageBatch(List<OrderEvent> orders) {
        return batchProducer.sendWithTransaction(
            new BatchValidationStartedEvent(orders.size()),
            TransactionPropagation.CONTEXT
        )
        .thenCompose(v -> {
            // Stage 1: Validation
            logger.info("Stage 1: Validating {} orders", orders.size());
            List<CompletableFuture<OrderEvent>> validationFutures = orders.stream()
                .map(this::validateOrder)
                .collect(Collectors.toList());

            return CompletableFuture.allOf(validationFutures.toArray(new CompletableFuture[0]))
                .thenApply(ignored -> validationFutures.stream()
                    .map(CompletableFuture::join)
                    .collect(Collectors.toList()));
        })
        .thenCompose(validatedOrders -> {
            // Stage 2: Processing
            logger.info("Stage 2: Processing {} validated orders", validatedOrders.size());
            List<CompletableFuture<String>> processingFutures = validatedOrders.stream()
                .map(this::processOrder)
                .collect(Collectors.toList());

            return CompletableFuture.allOf(processingFutures.toArray(new CompletableFuture[0]))
                .thenApply(ignored -> processingFutures.stream()
                    .map(CompletableFuture::join)
                    .collect(Collectors.toList()));
        })
        .thenCompose(results -> {
            // Stage 3: Completion
            return batchProducer.sendWithTransaction(
                new BatchValidationCompletedEvent(results),
                TransactionPropagation.CONTEXT
            ).thenApply(v -> results);
        });
    }

    /**
     * Validate individual order
     */
    private CompletableFuture<OrderEvent> validateOrder(OrderEvent order) {
        return batchProducer.sendWithTransaction(
            new OrderValidationEvent(order.getOrderId(), "VALIDATED"),
            TransactionPropagation.CONTEXT
        ).thenApply(v -> order);
    }

    /**
     * Demonstrate Pattern 3: Nested batch operations with different propagation
     * 
     * This demonstrates nested batches with different propagation strategies
     */
    private void demonstrateNestedBatchOperations() throws Exception {
        logger.info("--- Pattern 3: Nested Batch Operations with Different Propagation ---");

        // Create parent batch
        List<List<OrderEvent>> nestedBatches = List.of(
            List.of(
                new OrderEvent("NESTED-A-001", "CUSTOMER-A1", 100.00),
                new OrderEvent("NESTED-A-002", "CUSTOMER-A2", 200.00)
            ),
            List.of(
                new OrderEvent("NESTED-B-001", "CUSTOMER-B1", 150.00),
                new OrderEvent("NESTED-B-002", "CUSTOMER-B2", 250.00)
            )
        );
        
        logger.info("Created nested batch structure with {} sub-batches", nestedBatches.size());

        // Process nested batches
        CompletableFuture<List<List<String>>> nestedResult = processNestedBatches(nestedBatches);

        // Wait for completion
        List<List<String>> results = nestedResult.get(25, TimeUnit.SECONDS);
        logger.info("✓ Nested batch operations completed successfully");
        logger.info("✓ Processed {} sub-batches with total {} orders", 
            results.size(), results.stream().mapToInt(List::size).sum());
    }

    /**
     * Process nested batches with different propagation strategies
     */
    private CompletableFuture<List<List<String>>> processNestedBatches(List<List<OrderEvent>> nestedBatches) {
        return batchProducer.sendWithTransaction(
            new NestedBatchStartedEvent(nestedBatches.size()),
            TransactionPropagation.CONTEXT
        )
        .thenCompose(v -> {
            // Process each sub-batch independently but within parent transaction
            List<CompletableFuture<List<String>>> subBatchFutures = nestedBatches.stream()
                .map(subBatch -> processBatchOrders(subBatch)) // Each sub-batch uses CONTEXT
                .collect(Collectors.toList());

            return CompletableFuture.allOf(subBatchFutures.toArray(new CompletableFuture[0]))
                .thenApply(ignored -> subBatchFutures.stream()
                    .map(CompletableFuture::join)
                    .collect(Collectors.toList()));
        })
        .thenCompose(results -> {
            return batchProducer.sendWithTransaction(
                new NestedBatchCompletedEvent(results.size()),
                TransactionPropagation.CONTEXT
            ).thenApply(v -> results);
        });
    }

    /**
     * Demonstrate Pattern 4: Large batch processing with chunking
     * 
     * This demonstrates performance optimization for large batches
     */
    private void demonstrateLargeBatchProcessingWithChunking() throws Exception {
        logger.info("--- Pattern 4: Large Batch Processing with Chunking ---");

        // Create a large batch
        List<OrderEvent> largeBatch = IntStream.range(1, 21) // 20 orders
            .mapToObj(i -> new OrderEvent("LARGE-BATCH-" + String.format("%03d", i), "CUSTOMER-LARGE-" + i, 50.0 + i))
            .collect(Collectors.toList());
        
        logger.info("Created large batch of {} orders", largeBatch.size());

        // Process in chunks for better performance
        CompletableFuture<List<String>> chunkResult = processLargeBatchInChunks(largeBatch, 5);

        // Wait for completion
        List<String> results = chunkResult.get(30, TimeUnit.SECONDS);
        logger.info("✓ Large batch processing with chunking completed successfully");
        logger.info("✓ Processed {} orders in chunks", results.size());
    }

    /**
     * Process large batch in chunks for performance optimization
     */
    private CompletableFuture<List<String>> processLargeBatchInChunks(List<OrderEvent> orders, int chunkSize) {
        return batchProducer.sendWithTransaction(
            new LargeBatchStartedEvent(orders.size(), chunkSize),
            TransactionPropagation.CONTEXT
        )
        .thenCompose(v -> {
            // Split into chunks
            List<List<OrderEvent>> chunks = IntStream.range(0, (orders.size() + chunkSize - 1) / chunkSize)
                .mapToObj(i -> orders.subList(i * chunkSize, Math.min((i + 1) * chunkSize, orders.size())))
                .collect(Collectors.toList());
            
            logger.info("Split large batch into {} chunks", chunks.size());

            // Process chunks sequentially to avoid overwhelming the system
            CompletableFuture<List<String>> result = CompletableFuture.completedFuture(List.of());
            
            for (List<OrderEvent> chunk : chunks) {
                result = result.thenCompose(previousResults -> 
                    processBatchOrders(chunk).thenApply(chunkResults -> {
                        List<String> combined = new java.util.ArrayList<>(previousResults);
                        combined.addAll(chunkResults);
                        return combined;
                    })
                );
            }
            
            return result;
        })
        .thenCompose(results -> {
            return batchProducer.sendWithTransaction(
                new LargeBatchCompletedEvent(results.size()),
                TransactionPropagation.CONTEXT
            ).thenApply(v -> results);
        });
    }

    /**
     * Demonstrate Pattern 5: Batch error handling and partial completion
     *
     * This demonstrates error handling in batch operations with partial completion
     */
    private void demonstrateBatchErrorHandlingAndPartialCompletion() throws Exception {
        logger.info("--- Pattern 5: Batch Error Handling and Partial Completion ---");

        // Create a batch with some orders that will cause validation errors
        List<OrderEvent> mixedBatch = List.of(
            new OrderEvent("ERROR-BATCH-001", "CUSTOMER-GOOD-1", 100.00),
            new OrderEvent("ERROR-BATCH-002", "CUSTOMER-GOOD-2", 200.00),
            new OrderEvent("ERROR-BATCH-003", "CUSTOMER-BAD-1", 15000.00), // Will exceed limit
            new OrderEvent("ERROR-BATCH-004", "CUSTOMER-GOOD-3", 300.00)
        );

        logger.info("Created mixed batch of {} orders (some will fail validation)", mixedBatch.size());

        // Process with error handling
        CompletableFuture<List<String>> errorHandlingResult = processBatchWithErrorHandling(mixedBatch);

        try {
            List<String> results = errorHandlingResult.get(20, TimeUnit.SECONDS);
            logger.info("✓ Batch error handling completed successfully");
            logger.info("✓ Successfully processed {} orders", results.size());
        } catch (Exception e) {
            logger.info("✓ Batch error handling demonstrated - some orders failed as expected");
            logger.info("✓ Error: {}", e.getMessage());
        }
    }

    /**
     * Process batch with error handling following the guide pattern
     */
    private CompletableFuture<List<String>> processBatchWithErrorHandling(List<OrderEvent> orders) {
        return batchProducer.sendWithTransaction(
            new BatchStartedEvent(orders.size()),
            TransactionPropagation.CONTEXT
        )
        .thenCompose(v -> {
            // Process each order with individual error handling
            List<CompletableFuture<String>> futures = orders.stream()
                .map(this::processOrderWithErrorHandling)
                .collect(Collectors.toList());

            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(ignored -> futures.stream()
                    .map(future -> {
                        try {
                            return future.join();
                        } catch (Exception e) {
                            return "FAILED: " + e.getMessage();
                        }
                    })
                    .collect(Collectors.toList()));
        })
        .thenCompose(results -> {
            // Check if any orders failed
            long failedCount = results.stream().filter(r -> r.startsWith("FAILED")).count();
            if (failedCount > 0) {
                // This will cause automatic rollback of the entire transaction
                return CompletableFuture.failedFuture(
                    new RuntimeException("Batch processing failed: " + failedCount + " orders failed")
                );
            }

            return batchProducer.sendWithTransaction(
                new BatchCompletedEvent(results),
                TransactionPropagation.CONTEXT
            ).thenApply(v -> results);
        })
        .exceptionally(error -> {
            // All events are automatically rolled back
            logger.error("Batch processing failed, all events rolled back: {}", error.getMessage());
            throw new RuntimeException("Batch processing failed", error);
        });
    }

    /**
     * Process individual order with error handling following the guide pattern
     */
    private CompletableFuture<String> processOrderWithErrorHandling(OrderEvent order) {
        return orderProducer.sendWithTransaction(
            order,
            TransactionPropagation.CONTEXT
        )
        .thenCompose(v -> {
            // Business logic that might fail
            if (order.getAmount() > 10000.0) {
                // This will cause automatic rollback of the entire transaction
                return CompletableFuture.failedFuture(
                    new RuntimeException("Order amount exceeds limit: " + order.getAmount())
                );
            }

            return CompletableFuture.completedFuture("Order " + order.getOrderId() + " processed successfully");
        })
        .exceptionally(error -> {
            logger.error("Order processing failed: {}", error.getMessage());
            throw new RuntimeException("Order processing failed", error);
        });
    }

    /**
     * Demonstrate Pattern 6: Performance optimization for batch operations
     *
     * This demonstrates performance optimization techniques for batch processing
     */
    private void demonstratePerformanceOptimizedBatchProcessing() throws Exception {
        logger.info("--- Pattern 6: Performance Optimization for Batch Operations ---");

        // Create a performance test batch
        List<OrderEvent> performanceBatch = IntStream.range(1, 16) // 15 orders
            .mapToObj(i -> new OrderEvent("PERF-BATCH-" + String.format("%03d", i), "CUSTOMER-PERF-" + i, 75.0 + i))
            .collect(Collectors.toList());

        logger.info("Created performance test batch of {} orders", performanceBatch.size());

        // Measure performance
        long startTime = System.currentTimeMillis();

        CompletableFuture<List<String>> perfResult = processPerformanceOptimizedBatch(performanceBatch);

        // Wait for completion and measure time
        List<String> results = perfResult.get(25, TimeUnit.SECONDS);

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        double ordersPerSecond = (performanceBatch.size() * 1000.0) / duration;

        logger.info("✓ Performance optimized batch processing completed successfully");
        logger.info("✓ Processed {} orders in {} ms", results.size(), duration);
        logger.info("✓ Performance: {:.2f} orders/second", ordersPerSecond);
    }

    /**
     * Process batch with performance optimizations
     */
    private CompletableFuture<List<String>> processPerformanceOptimizedBatch(List<OrderEvent> orders) {
        return batchProducer.sendWithTransaction(
            new PerformanceBatchStartedEvent(orders.size()),
            TransactionPropagation.CONTEXT
        )
        .thenCompose(v -> {
            // Use parallel processing for better performance
            List<CompletableFuture<String>> futures = orders.parallelStream()
                .map(this::processOrder)
                .collect(Collectors.toList());

            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(ignored -> futures.stream()
                    .map(CompletableFuture::join)
                    .collect(Collectors.toList()));
        })
        .thenCompose(results -> {
            return batchProducer.sendWithTransaction(
                new PerformanceBatchCompletedEvent(results.size()),
                TransactionPropagation.CONTEXT
            ).thenApply(v -> results);
        });
    }

    // Additional event classes for error handling and performance patterns
    public static class PerformanceBatchStartedEvent extends BatchStartedEvent {
        public PerformanceBatchStartedEvent(int batchSize) { super(batchSize); }

        @Override
        public String toString() {
            return String.format("PerformanceBatchStartedEvent{batchSize=%d, timestamp=%s}", getBatchSize(), getTimestamp());
        }
    }

    public static class PerformanceBatchCompletedEvent extends BatchCompletedEvent {
        public PerformanceBatchCompletedEvent(int resultCount) {
            super(IntStream.range(0, resultCount).mapToObj(i -> "Result-" + i).collect(Collectors.toList()));
        }

        @Override
        public String toString() {
            return String.format("PerformanceBatchCompletedEvent{resultCount=%d, timestamp=%s}", getResults().size(), getTimestamp());
        }
    }

    // Event classes for batch operations
    public static class BatchStartedEvent {
        private final int batchSize;
        private final Instant timestamp;
        
        public BatchStartedEvent(int batchSize) {
            this.batchSize = batchSize;
            this.timestamp = Instant.now();
        }
        
        public int getBatchSize() { return batchSize; }
        public Instant getTimestamp() { return timestamp; }
        
        @Override
        public String toString() {
            return String.format("BatchStartedEvent{batchSize=%d, timestamp=%s}", batchSize, timestamp);
        }
    }

    public static class BatchCompletedEvent {
        private final List<String> results;
        private final Instant timestamp;
        
        public BatchCompletedEvent(List<String> results) {
            this.results = results;
            this.timestamp = Instant.now();
        }
        
        public List<String> getResults() { return results; }
        public Instant getTimestamp() { return timestamp; }
        
        @Override
        public String toString() {
            return String.format("BatchCompletedEvent{resultCount=%d, timestamp=%s}", results.size(), timestamp);
        }
    }

    // Additional event classes for advanced patterns
    public static class BatchValidationStartedEvent extends BatchStartedEvent {
        public BatchValidationStartedEvent(int batchSize) { super(batchSize); }
    }

    public static class BatchValidationCompletedEvent extends BatchCompletedEvent {
        public BatchValidationCompletedEvent(List<String> results) { super(results); }
    }

    public static class OrderValidationEvent {
        private final String orderId;
        private final String status;
        private final Instant timestamp;
        
        public OrderValidationEvent(String orderId, String status) {
            this.orderId = orderId;
            this.status = status;
            this.timestamp = Instant.now();
        }
        
        public String getOrderId() { return orderId; }
        public String getStatus() { return status; }
        public Instant getTimestamp() { return timestamp; }
    }

    public static class NestedBatchStartedEvent {
        private final int subBatchCount;
        private final Instant timestamp;
        
        public NestedBatchStartedEvent(int subBatchCount) {
            this.subBatchCount = subBatchCount;
            this.timestamp = Instant.now();
        }
        
        public int getSubBatchCount() { return subBatchCount; }
        public Instant getTimestamp() { return timestamp; }
    }

    public static class NestedBatchCompletedEvent {
        private final int completedSubBatches;
        private final Instant timestamp;
        
        public NestedBatchCompletedEvent(int completedSubBatches) {
            this.completedSubBatches = completedSubBatches;
            this.timestamp = Instant.now();
        }
        
        public int getCompletedSubBatches() { return completedSubBatches; }
        public Instant getTimestamp() { return timestamp; }
    }

    public static class LargeBatchStartedEvent {
        private final int totalOrders;
        private final int chunkSize;
        private final Instant timestamp;
        
        public LargeBatchStartedEvent(int totalOrders, int chunkSize) {
            this.totalOrders = totalOrders;
            this.chunkSize = chunkSize;
            this.timestamp = Instant.now();
        }
        
        public int getTotalOrders() { return totalOrders; }
        public int getChunkSize() { return chunkSize; }
        public Instant getTimestamp() { return timestamp; }
    }

    public static class LargeBatchCompletedEvent {
        private final int processedOrders;
        private final Instant timestamp;
        
        public LargeBatchCompletedEvent(int processedOrders) {
            this.processedOrders = processedOrders;
            this.timestamp = Instant.now();
        }
        
        public int getProcessedOrders() { return processedOrders; }
        public Instant getTimestamp() { return timestamp; }
    }

    // Base event classes
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

    public static class BatchEvent {
        private final String batchId;
        private final String description;
        private final Instant timestamp;
        
        public BatchEvent(String batchId, String description) {
            this.batchId = batchId;
            this.description = description;
            this.timestamp = Instant.now();
        }
        
        public String getBatchId() { return batchId; }
        public String getDescription() { return description; }
        public Instant getTimestamp() { return timestamp; }
        
        @Override
        public String toString() {
            return String.format("BatchEvent{batchId='%s', description='%s', timestamp=%s}", 
                batchId, description, timestamp);
        }
    }
}
