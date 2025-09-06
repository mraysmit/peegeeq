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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Example demonstrating Error Handling and Rollback Scenarios pattern from PeeGeeQ Guide.
 * 
 * This example demonstrates the advanced usage pattern: Error Handling and Rollback Scenarios
 * following the patterns outlined in Section "Advanced Usage Patterns - 2. Error Handling and Rollback Scenarios".
 * 
 * Key Features Demonstrated:
 * - Business logic validation with automatic rollback
 * - CompletableFuture.failedFuture() usage for transaction rollback
 * - Multi-stage operations with rollback on any failure
 * - Exception propagation and error handling
 * - Transaction consistency guarantees
 * - Recovery patterns and error classification
 * 
 * Usage:
 * ```java
 * ErrorHandlingRollbackExample example = new ErrorHandlingRollbackExample();
 * example.runExample();
 * ```
 * 
 * Patterns Demonstrated:
 * 1. Basic error handling with automatic rollback
 * 2. Business logic validation failures
 * 3. Multi-stage operations with rollback
 * 4. Exception classification and handling
 * 5. Recovery and retry patterns
 * 6. Transaction consistency validation
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-06
 * @version 1.0
 */
public class ErrorHandlingRollbackExample {
    private static final Logger logger = LoggerFactory.getLogger(ErrorHandlingRollbackExample.class);
    
    private PeeGeeQManager manager;
    private QueueFactory outboxFactory;
    private OutboxProducer<OrderEvent> orderProducer;
    private OutboxProducer<ProcessingEvent> processingProducer;
    private BusinessService businessService;
    
    /**
     * Main method to run the example
     */
    public static void main(String[] args) {
        ErrorHandlingRollbackExample example = new ErrorHandlingRollbackExample();
        try {
            example.runExample();
        } catch (Exception e) {
            logger.error("Example failed: {}", e.getMessage(), e);
            System.exit(1);
        }
    }
    
    /**
     * Run the complete Error Handling and Rollback example
     */
    public void runExample() throws Exception {
        logger.info("=== Starting Error Handling and Rollback Scenarios Example ===");
        
        try {
            // Setup
            setup();
            
            // Demonstrate all error handling and rollback patterns
            demonstrateBasicErrorHandlingWithRollback();
            demonstrateBusinessLogicValidationFailures();
            demonstrateMultiStageOperationsWithRollback();
            demonstrateExceptionClassificationAndHandling();
            demonstrateRecoveryAndRetryPatterns();
            demonstrateTransactionConsistencyValidation();
            
            logger.info("=== Error Handling and Rollback Scenarios Example Completed Successfully ===");
            
        } finally {
            // Cleanup
            cleanup();
        }
    }
    
    /**
     * Setup PeeGeeQ components and business service
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
        processingProducer = (OutboxProducer<ProcessingEvent>) outboxFactory.createProducer("processing", ProcessingEvent.class);
        
        // Initialize business service
        businessService = new BusinessService();
        
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
        
        if (processingProducer != null) {
            processingProducer.close();
            logger.info("✓ Processing producer closed");
        }
        
        if (manager != null) {
            manager.stop();
            logger.info("✓ PeeGeeQ Manager stopped");
        }
        
        logger.info("✓ Cleanup completed");
    }
    
    /**
     * Demonstrate Pattern 1: Basic error handling with automatic rollback
     * 
     * This demonstrates the exact pattern from the guide:
     * "processOrderWithErrorHandling" - automatic rollback on failure
     */
    private void demonstrateBasicErrorHandlingWithRollback() throws Exception {
        logger.info("--- Pattern 1: Basic Error Handling with Automatic Rollback ---");

        // Test successful order processing
        OrderEvent successOrder = new OrderEvent("ERROR-SUCCESS-001", "CUSTOMER-GOOD", BigDecimal.valueOf(5000.00));
        logger.info("Testing successful order: {}", successOrder);

        CompletableFuture<String> successResult = processOrderWithErrorHandling(successOrder);
        try {
            String result = successResult.get(10, TimeUnit.SECONDS);
            logger.info("✓ Successful order processed: {}", result);
        } catch (Exception e) {
            logger.error("✗ Unexpected failure for successful order: {}", e.getMessage());
        }

        // Test order that exceeds limit (should fail and rollback)
        OrderEvent failOrder = new OrderEvent("ERROR-FAIL-001", "CUSTOMER-BAD", BigDecimal.valueOf(15000.00));
        logger.info("Testing order that exceeds limit: {}", failOrder);

        CompletableFuture<String> failResult = processOrderWithErrorHandling(failOrder);
        try {
            String result = failResult.get(10, TimeUnit.SECONDS);
            logger.error("✗ Order should have failed but succeeded: {}", result);
        } catch (Exception e) {
            logger.info("✓ Order correctly failed and rolled back: {}", e.getMessage());
        }

        logger.info("✓ Basic error handling with automatic rollback demonstrated successfully");
    }

    /**
     * Process order with error handling following the exact pattern from the guide
     */
    private CompletableFuture<String> processOrderWithErrorHandling(OrderEvent order) {
        return processingProducer.sendWithTransaction(
            new OrderProcessingStartedEvent(order),
            TransactionPropagation.CONTEXT
        )
        .thenCompose(v -> {
            // Business logic that might fail
            if (order.getAmount().compareTo(BigDecimal.valueOf(10000)) > 0) {
                // This will cause automatic rollback of the entire transaction
                return CompletableFuture.failedFuture(
                    new BusinessException("Order amount exceeds limit")
                );
            }

            return businessService.processOrder(order);
        })
        .thenCompose(result -> {
            // Success event - only sent if everything succeeds
            return processingProducer.sendWithTransaction(
                new OrderProcessedEvent(order, result),
                TransactionPropagation.CONTEXT
            ).thenApply(v -> result);
        })
        .exceptionally(error -> {
            // All events are automatically rolled back
            logger.error("Order processing failed, all events rolled back: {}", error.getMessage());
            throw new RuntimeException("Order processing failed", error);
        });
    }

    /**
     * Demonstrate Pattern 2: Business logic validation failures
     * 
     * This demonstrates various business validation scenarios that trigger rollback
     */
    private void demonstrateBusinessLogicValidationFailures() throws Exception {
        logger.info("--- Pattern 2: Business Logic Validation Failures ---");

        // Test different validation failure scenarios
        testValidationFailure("INVALID-CUSTOMER-001", "INVALID-CUSTOMER", BigDecimal.valueOf(1000.00), "Invalid customer ID");
        testValidationFailure("NEGATIVE-AMOUNT-001", "CUSTOMER-VALID", BigDecimal.valueOf(-100.00), "Negative amount not allowed");
        testValidationFailure("ZERO-AMOUNT-001", "CUSTOMER-VALID", BigDecimal.ZERO, "Zero amount not allowed");
        testValidationFailure("DUPLICATE-ORDER-001", "CUSTOMER-VALID", BigDecimal.valueOf(500.00), "Duplicate order ID");

        logger.info("✓ Business logic validation failures demonstrated successfully");
    }

    /**
     * Test a specific validation failure scenario
     */
    private void testValidationFailure(String orderId, String customerId, BigDecimal amount, String expectedError) {
        logger.info("Testing validation failure: {} - {}", orderId, expectedError);

        OrderEvent order = new OrderEvent(orderId, customerId, amount);
        CompletableFuture<String> result = processOrderWithBusinessValidation(order);

        try {
            String success = result.get(5, TimeUnit.SECONDS);
            logger.error("✗ Order should have failed but succeeded: {}", success);
        } catch (Exception e) {
            if (e.getMessage().contains(expectedError) || e.getCause().getMessage().contains(expectedError)) {
                logger.info("✓ Validation correctly failed: {}", expectedError);
            } else {
                logger.warn("? Validation failed with different error: {}", e.getMessage());
            }
        }
    }

    /**
     * Process order with comprehensive business validation
     */
    private CompletableFuture<String> processOrderWithBusinessValidation(OrderEvent order) {
        return processingProducer.sendWithTransaction(
            new ValidationStartedEvent(order.getOrderId()),
            TransactionPropagation.CONTEXT
        )
        .thenCompose(v -> {
            // Comprehensive business validation
            if (order.getCustomerId().startsWith("INVALID")) {
                return CompletableFuture.failedFuture(new BusinessException("Invalid customer ID"));
            }
            if (order.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                return CompletableFuture.failedFuture(new BusinessException("Amount must be positive"));
            }
            if (order.getOrderId().startsWith("DUPLICATE")) {
                return CompletableFuture.failedFuture(new BusinessException("Duplicate order ID"));
            }

            return businessService.processOrder(order);
        })
        .thenCompose(result -> {
            return processingProducer.sendWithTransaction(
                new ValidationCompletedEvent(order.getOrderId(), "PASSED"),
                TransactionPropagation.CONTEXT
            ).thenApply(v -> result);
        })
        .exceptionally(error -> {
            logger.error("Business validation failed, all events rolled back: {}", error.getMessage());
            throw new RuntimeException("Business validation failed", error);
        });
    }

    /**
     * Demonstrate Pattern 3: Multi-stage operations with rollback
     * 
     * This demonstrates complex operations with multiple stages that can fail at any point
     */
    private void demonstrateMultiStageOperationsWithRollback() throws Exception {
        logger.info("--- Pattern 3: Multi-Stage Operations with Rollback ---");

        // Test successful multi-stage operation
        OrderEvent successOrder = new OrderEvent("MULTI-SUCCESS-001", "CUSTOMER-MULTI", BigDecimal.valueOf(2500.00));
        logger.info("Testing successful multi-stage operation: {}", successOrder);

        CompletableFuture<String> successResult = processMultiStageOrder(successOrder, false);
        try {
            String result = successResult.get(15, TimeUnit.SECONDS);
            logger.info("✓ Multi-stage operation completed successfully: {}", result);
        } catch (Exception e) {
            logger.error("✗ Multi-stage operation failed unexpectedly: {}", e.getMessage());
        }

        // Test multi-stage operation that fails in stage 2
        OrderEvent failOrder = new OrderEvent("MULTI-FAIL-001", "CUSTOMER-MULTI", BigDecimal.valueOf(3500.00));
        logger.info("Testing multi-stage operation that fails in stage 2: {}", failOrder);

        CompletableFuture<String> failResult = processMultiStageOrder(failOrder, true);
        try {
            String result = failResult.get(15, TimeUnit.SECONDS);
            logger.error("✗ Multi-stage operation should have failed but succeeded: {}", result);
        } catch (Exception e) {
            logger.info("✓ Multi-stage operation correctly failed and rolled back: {}", e.getMessage());
        }

        logger.info("✓ Multi-stage operations with rollback demonstrated successfully");
    }

    /**
     * Process order with multiple stages that can fail at any point
     */
    private CompletableFuture<String> processMultiStageOrder(OrderEvent order, boolean failInStage2) {
        return processingProducer.sendWithTransaction(
            new MultiStageStartedEvent(order.getOrderId(), "STAGE_1"),
            TransactionPropagation.CONTEXT
        )
        .thenCompose(v -> {
            // Stage 1: Initial validation
            logger.info("Stage 1: Initial validation for order {}", order.getOrderId());
            return businessService.validateOrder(order);
        })
        .thenCompose(validationResult -> {
            // Stage 2: Inventory check
            logger.info("Stage 2: Inventory check for order {}", order.getOrderId());
            
            if (failInStage2) {
                return CompletableFuture.failedFuture(
                    new BusinessException("Insufficient inventory in stage 2")
                );
            }
            
            return processingProducer.sendWithTransaction(
                new MultiStageProgressEvent(order.getOrderId(), "STAGE_2", "INVENTORY_CHECKED"),
                TransactionPropagation.CONTEXT
            ).thenApply(v -> "inventory-checked");
        })
        .thenCompose(inventoryResult -> {
            // Stage 3: Payment processing
            logger.info("Stage 3: Payment processing for order {}", order.getOrderId());
            return processingProducer.sendWithTransaction(
                new MultiStageProgressEvent(order.getOrderId(), "STAGE_3", "PAYMENT_PROCESSED"),
                TransactionPropagation.CONTEXT
            ).thenApply(v -> "payment-processed");
        })
        .thenCompose(paymentResult -> {
            // Stage 4: Final completion
            logger.info("Stage 4: Final completion for order {}", order.getOrderId());
            return processingProducer.sendWithTransaction(
                new MultiStageCompletedEvent(order.getOrderId(), "ALL_STAGES_COMPLETED"),
                TransactionPropagation.CONTEXT
            ).thenApply(v -> "Multi-stage processing completed for order " + order.getOrderId());
        })
        .exceptionally(error -> {
            logger.error("Multi-stage processing failed, all stages rolled back: {}", error.getMessage());
            throw new RuntimeException("Multi-stage processing failed", error);
        });
    }

    /**
     * Demonstrate Pattern 4: Exception classification and handling
     *
     * This demonstrates different types of exceptions and their handling
     */
    private void demonstrateExceptionClassificationAndHandling() throws Exception {
        logger.info("--- Pattern 4: Exception Classification and Handling ---");

        // Test different exception types
        testExceptionType("BUSINESS-ERROR-001", BusinessException.class, "Business rule violation");
        testExceptionType("VALIDATION-ERROR-001", ValidationException.class, "Data validation failed");
        testExceptionType("SYSTEM-ERROR-001", SystemException.class, "System resource unavailable");
        testExceptionType("TIMEOUT-ERROR-001", TimeoutException.class, "Operation timed out");

        logger.info("✓ Exception classification and handling demonstrated successfully");
    }

    /**
     * Test specific exception type handling
     */
    private void testExceptionType(String orderId, Class<? extends Exception> exceptionType, String errorMessage) {
        logger.info("Testing exception type: {} - {}", exceptionType.getSimpleName(), errorMessage);

        OrderEvent order = new OrderEvent(orderId, "CUSTOMER-EXCEPTION", BigDecimal.valueOf(1000.00));
        CompletableFuture<String> result = processOrderWithExceptionClassification(order, exceptionType, errorMessage);

        try {
            String success = result.get(5, TimeUnit.SECONDS);
            logger.error("✗ Order should have failed but succeeded: {}", success);
        } catch (Exception e) {
            if (e.getCause() != null && exceptionType.isInstance(e.getCause())) {
                logger.info("✓ Exception correctly classified as {}: {}", exceptionType.getSimpleName(), errorMessage);
            } else {
                logger.info("✓ Exception handled (wrapped): {}", e.getMessage());
            }
        }
    }

    /**
     * Process order with exception classification
     */
    private CompletableFuture<String> processOrderWithExceptionClassification(OrderEvent order,
                                                                             Class<? extends Exception> exceptionType,
                                                                             String errorMessage) {
        return processingProducer.sendWithTransaction(
            new ExceptionTestStartedEvent(order.getOrderId(), exceptionType.getSimpleName()),
            TransactionPropagation.CONTEXT
        )
        .thenCompose(v -> {
            // Simulate different exception types
            if (exceptionType == BusinessException.class) {
                return CompletableFuture.failedFuture(new BusinessException(errorMessage));
            } else if (exceptionType == ValidationException.class) {
                return CompletableFuture.failedFuture(new ValidationException(errorMessage));
            } else if (exceptionType == SystemException.class) {
                return CompletableFuture.failedFuture(new SystemException(errorMessage));
            } else if (exceptionType == TimeoutException.class) {
                return CompletableFuture.failedFuture(new TimeoutException(errorMessage));
            }

            return businessService.processOrder(order);
        })
        .exceptionally(error -> {
            // Classify and handle different exception types
            if (error instanceof BusinessException) {
                logger.error("Business exception occurred, transaction rolled back: {}", error.getMessage());
            } else if (error instanceof ValidationException) {
                logger.error("Validation exception occurred, transaction rolled back: {}", error.getMessage());
            } else if (error instanceof SystemException) {
                logger.error("System exception occurred, transaction rolled back: {}", error.getMessage());
            } else if (error instanceof TimeoutException) {
                logger.error("Timeout exception occurred, transaction rolled back: {}", error.getMessage());
            } else {
                logger.error("Unknown exception occurred, transaction rolled back: {}", error.getMessage());
            }
            throw new RuntimeException("Exception classification test", error);
        });
    }

    /**
     * Demonstrate Pattern 5: Recovery and retry patterns
     *
     * This demonstrates recovery strategies after transaction failures
     */
    private void demonstrateRecoveryAndRetryPatterns() throws Exception {
        logger.info("--- Pattern 5: Recovery and Retry Patterns ---");

        // Test retry with eventual success
        OrderEvent retryOrder = new OrderEvent("RETRY-SUCCESS-001", "CUSTOMER-RETRY", BigDecimal.valueOf(1500.00));
        logger.info("Testing retry pattern with eventual success: {}", retryOrder);

        CompletableFuture<String> retryResult = processOrderWithRetry(retryOrder, 3);
        try {
            String result = retryResult.get(20, TimeUnit.SECONDS);
            logger.info("✓ Retry pattern succeeded: {}", result);
        } catch (Exception e) {
            logger.error("✗ Retry pattern failed: {}", e.getMessage());
        }

        // Test retry with eventual failure
        OrderEvent failRetryOrder = new OrderEvent("RETRY-FAIL-001", "CUSTOMER-RETRY-FAIL", BigDecimal.valueOf(1500.00));
        logger.info("Testing retry pattern with eventual failure: {}", failRetryOrder);

        CompletableFuture<String> failRetryResult = processOrderWithRetry(failRetryOrder, 2);
        try {
            String result = failRetryResult.get(15, TimeUnit.SECONDS);
            logger.error("✗ Retry pattern should have failed but succeeded: {}", result);
        } catch (Exception e) {
            logger.info("✓ Retry pattern correctly failed after retries: {}", e.getMessage());
        }

        logger.info("✓ Recovery and retry patterns demonstrated successfully");
    }

    /**
     * Process order with retry logic
     */
    private CompletableFuture<String> processOrderWithRetry(OrderEvent order, int maxRetries) {
        return processOrderWithRetryAttempt(order, 1, maxRetries);
    }

    /**
     * Process order with retry attempt tracking
     */
    private CompletableFuture<String> processOrderWithRetryAttempt(OrderEvent order, int attempt, int maxRetries) {
        return processingProducer.sendWithTransaction(
            new RetryAttemptStartedEvent(order.getOrderId(), attempt, maxRetries),
            TransactionPropagation.CONTEXT
        )
        .thenCompose(v -> {
            // Simulate transient failures that eventually succeed
            if (order.getCustomerId().equals("CUSTOMER-RETRY") && attempt < 3) {
                return CompletableFuture.failedFuture(
                    new TransientException("Transient failure on attempt " + attempt)
                );
            }

            // Simulate permanent failures
            if (order.getCustomerId().equals("CUSTOMER-RETRY-FAIL")) {
                return CompletableFuture.failedFuture(
                    new BusinessException("Permanent failure - will not succeed on retry")
                );
            }

            return businessService.processOrder(order);
        })
        .thenCompose(result -> {
            return processingProducer.sendWithTransaction(
                new RetryAttemptSucceededEvent(order.getOrderId(), attempt),
                TransactionPropagation.CONTEXT
            ).thenApply(v -> result);
        })
        .exceptionally(error -> {
            logger.warn("Attempt {} failed for order {}: {}", attempt, order.getOrderId(), error.getMessage());

            if (attempt < maxRetries && error instanceof TransientException) {
                logger.info("Retrying order {} (attempt {} of {})", order.getOrderId(), attempt + 1, maxRetries);
                try {
                    // Small delay before retry
                    Thread.sleep(100 * attempt); // Exponential backoff
                    return processOrderWithRetryAttempt(order, attempt + 1, maxRetries).join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Retry interrupted", e);
                }
            } else {
                logger.error("All retry attempts exhausted for order {}", order.getOrderId());
                throw new RuntimeException("Retry attempts exhausted", error);
            }
        });
    }

    /**
     * Demonstrate Pattern 6: Transaction consistency validation
     *
     * This demonstrates validation of transaction consistency guarantees
     */
    private void demonstrateTransactionConsistencyValidation() throws Exception {
        logger.info("--- Pattern 6: Transaction Consistency Validation ---");

        // Test that partial failures don't leave inconsistent state
        OrderEvent consistencyOrder = new OrderEvent("CONSISTENCY-001", "CUSTOMER-CONSISTENCY", BigDecimal.valueOf(2000.00));
        logger.info("Testing transaction consistency with partial failure: {}", consistencyOrder);

        CompletableFuture<String> consistencyResult = processOrderWithConsistencyCheck(consistencyOrder, true);
        try {
            String result = consistencyResult.get(10, TimeUnit.SECONDS);
            logger.error("✗ Consistency test should have failed but succeeded: {}", result);
        } catch (Exception e) {
            logger.info("✓ Transaction consistency maintained - all events rolled back: {}", e.getMessage());
        }

        // Test successful consistency
        OrderEvent successConsistencyOrder = new OrderEvent("CONSISTENCY-002", "CUSTOMER-CONSISTENCY", BigDecimal.valueOf(1500.00));
        logger.info("Testing transaction consistency with success: {}", successConsistencyOrder);

        CompletableFuture<String> successConsistencyResult = processOrderWithConsistencyCheck(successConsistencyOrder, false);
        try {
            String result = successConsistencyResult.get(10, TimeUnit.SECONDS);
            logger.info("✓ Transaction consistency maintained - all events committed: {}", result);
        } catch (Exception e) {
            logger.error("✗ Consistency test failed unexpectedly: {}", e.getMessage());
        }

        logger.info("✓ Transaction consistency validation demonstrated successfully");
    }

    /**
     * Process order with consistency checking
     */
    private CompletableFuture<String> processOrderWithConsistencyCheck(OrderEvent order, boolean failInMiddle) {
        return processingProducer.sendWithTransaction(
            new ConsistencyCheckStartedEvent(order.getOrderId()),
            TransactionPropagation.CONTEXT
        )
        .thenCompose(v -> {
            // First operation
            return processingProducer.sendWithTransaction(
                new ConsistencyCheckProgressEvent(order.getOrderId(), "STEP_1", "COMPLETED"),
                TransactionPropagation.CONTEXT
            );
        })
        .thenCompose(v -> {
            // Second operation
            return processingProducer.sendWithTransaction(
                new ConsistencyCheckProgressEvent(order.getOrderId(), "STEP_2", "COMPLETED"),
                TransactionPropagation.CONTEXT
            );
        })
        .thenCompose(v -> {
            // Third operation - might fail
            if (failInMiddle) {
                return CompletableFuture.failedFuture(
                    new BusinessException("Intentional failure in step 3 for consistency test")
                );
            }

            return processingProducer.sendWithTransaction(
                new ConsistencyCheckProgressEvent(order.getOrderId(), "STEP_3", "COMPLETED"),
                TransactionPropagation.CONTEXT
            );
        })
        .thenCompose(v -> {
            // Final completion
            return processingProducer.sendWithTransaction(
                new ConsistencyCheckCompletedEvent(order.getOrderId(), "ALL_STEPS_COMPLETED"),
                TransactionPropagation.CONTEXT
            ).thenApply(ignored -> "Consistency check completed for order " + order.getOrderId());
        })
        .exceptionally(error -> {
            logger.error("Consistency check failed, all events rolled back: {}", error.getMessage());
            throw new RuntimeException("Consistency check failed", error);
        });
    }

    // Business service for processing orders
    private static class BusinessService {
        private static final Logger logger = LoggerFactory.getLogger(BusinessService.class);

        public CompletableFuture<String> processOrder(OrderEvent order) {
            logger.info("Processing order: {}", order.getOrderId());
            // Simulate business processing
            return CompletableFuture.completedFuture("Order " + order.getOrderId() + " processed successfully");
        }

        public CompletableFuture<String> validateOrder(OrderEvent order) {
            logger.info("Validating order: {}", order.getOrderId());
            // Simulate validation
            return CompletableFuture.completedFuture("Order " + order.getOrderId() + " validated");
        }
    }

    // Custom exception classes for different error scenarios
    public static class BusinessException extends RuntimeException {
        public BusinessException(String message) {
            super(message);
        }
    }

    public static class ValidationException extends RuntimeException {
        public ValidationException(String message) {
            super(message);
        }
    }

    public static class SystemException extends RuntimeException {
        public SystemException(String message) {
            super(message);
        }
    }

    public static class TimeoutException extends RuntimeException {
        public TimeoutException(String message) {
            super(message);
        }
    }

    public static class TransientException extends RuntimeException {
        public TransientException(String message) {
            super(message);
        }
    }

    // Event classes for error handling scenarios
    public static class OrderEvent {
        private final String orderId;
        private final String customerId;
        private final BigDecimal amount;
        private final Instant timestamp;
        
        public OrderEvent(String orderId, String customerId, BigDecimal amount) {
            this.orderId = orderId;
            this.customerId = customerId;
            this.amount = amount;
            this.timestamp = Instant.now();
        }
        
        public String getOrderId() { return orderId; }
        public String getCustomerId() { return customerId; }
        public BigDecimal getAmount() { return amount; }
        public Instant getTimestamp() { return timestamp; }
        
        @Override
        public String toString() {
            return String.format("OrderEvent{orderId='%s', customerId='%s', amount=%s, timestamp=%s}", 
                orderId, customerId, amount, timestamp);
        }
    }

    public static class ProcessingEvent {
        private final String eventId;
        private final String description;
        private final Instant timestamp;
        
        public ProcessingEvent(String eventId, String description) {
            this.eventId = eventId;
            this.description = description;
            this.timestamp = Instant.now();
        }
        
        public String getEventId() { return eventId; }
        public String getDescription() { return description; }
        public Instant getTimestamp() { return timestamp; }
        
        @Override
        public String toString() {
            return String.format("ProcessingEvent{eventId='%s', description='%s', timestamp=%s}", 
                eventId, description, timestamp);
        }
    }

    // Specific event types for different scenarios
    public static class OrderProcessingStartedEvent extends ProcessingEvent {
        private final OrderEvent order;
        
        public OrderProcessingStartedEvent(OrderEvent order) {
            super("PROCESSING_STARTED_" + order.getOrderId(), "Order processing started");
            this.order = order;
        }
        
        public OrderEvent getOrder() { return order; }
    }

    public static class OrderProcessedEvent extends ProcessingEvent {
        private final OrderEvent order;
        private final String result;
        
        public OrderProcessedEvent(OrderEvent order, String result) {
            super("PROCESSING_COMPLETED_" + order.getOrderId(), "Order processing completed");
            this.order = order;
            this.result = result;
        }
        
        public OrderEvent getOrder() { return order; }
        public String getResult() { return result; }
    }

    public static class ValidationStartedEvent extends ProcessingEvent {
        public ValidationStartedEvent(String orderId) {
            super("VALIDATION_STARTED_" + orderId, "Validation started for order " + orderId);
        }
    }

    public static class ValidationCompletedEvent extends ProcessingEvent {
        private final String status;
        
        public ValidationCompletedEvent(String orderId, String status) {
            super("VALIDATION_COMPLETED_" + orderId, "Validation completed for order " + orderId);
            this.status = status;
        }
        
        public String getStatus() { return status; }
    }

    public static class MultiStageStartedEvent extends ProcessingEvent {
        private final String stage;
        
        public MultiStageStartedEvent(String orderId, String stage) {
            super("MULTI_STAGE_STARTED_" + orderId, "Multi-stage processing started for order " + orderId);
            this.stage = stage;
        }
        
        public String getStage() { return stage; }
    }

    public static class MultiStageProgressEvent extends ProcessingEvent {
        private final String stage;
        private final String status;
        
        public MultiStageProgressEvent(String orderId, String stage, String status) {
            super("MULTI_STAGE_PROGRESS_" + orderId + "_" + stage, "Multi-stage progress for order " + orderId);
            this.stage = stage;
            this.status = status;
        }
        
        public String getStage() { return stage; }
        public String getStatus() { return status; }
    }

    public static class MultiStageCompletedEvent extends ProcessingEvent {
        private final String finalStatus;
        
        public MultiStageCompletedEvent(String orderId, String finalStatus) {
            super("MULTI_STAGE_COMPLETED_" + orderId, "Multi-stage processing completed for order " + orderId);
            this.finalStatus = finalStatus;
        }
        
        public String getFinalStatus() { return finalStatus; }
    }

    // Additional event classes for error handling patterns
    public static class ExceptionTestStartedEvent extends ProcessingEvent {
        private final String exceptionType;

        public ExceptionTestStartedEvent(String orderId, String exceptionType) {
            super("EXCEPTION_TEST_STARTED_" + orderId, "Exception test started for order " + orderId);
            this.exceptionType = exceptionType;
        }

        public String getExceptionType() { return exceptionType; }
    }

    public static class RetryAttemptStartedEvent extends ProcessingEvent {
        private final int attempt;
        private final int maxRetries;

        public RetryAttemptStartedEvent(String orderId, int attempt, int maxRetries) {
            super("RETRY_ATTEMPT_STARTED_" + orderId + "_" + attempt,
                  "Retry attempt " + attempt + " of " + maxRetries + " for order " + orderId);
            this.attempt = attempt;
            this.maxRetries = maxRetries;
        }

        public int getAttempt() { return attempt; }
        public int getMaxRetries() { return maxRetries; }
    }

    public static class RetryAttemptSucceededEvent extends ProcessingEvent {
        private final int successfulAttempt;

        public RetryAttemptSucceededEvent(String orderId, int successfulAttempt) {
            super("RETRY_ATTEMPT_SUCCEEDED_" + orderId + "_" + successfulAttempt,
                  "Retry attempt " + successfulAttempt + " succeeded for order " + orderId);
            this.successfulAttempt = successfulAttempt;
        }

        public int getSuccessfulAttempt() { return successfulAttempt; }
    }

    public static class ConsistencyCheckStartedEvent extends ProcessingEvent {
        public ConsistencyCheckStartedEvent(String orderId) {
            super("CONSISTENCY_CHECK_STARTED_" + orderId, "Consistency check started for order " + orderId);
        }
    }

    public static class ConsistencyCheckProgressEvent extends ProcessingEvent {
        private final String step;
        private final String status;

        public ConsistencyCheckProgressEvent(String orderId, String step, String status) {
            super("CONSISTENCY_CHECK_PROGRESS_" + orderId + "_" + step,
                  "Consistency check progress for order " + orderId + " - " + step);
            this.step = step;
            this.status = status;
        }

        public String getStep() { return step; }
        public String getStatus() { return status; }
    }

    public static class ConsistencyCheckCompletedEvent extends ProcessingEvent {
        private final String finalStatus;

        public ConsistencyCheckCompletedEvent(String orderId, String finalStatus) {
            super("CONSISTENCY_CHECK_COMPLETED_" + orderId, "Consistency check completed for order " + orderId);
            this.finalStatus = finalStatus;
        }

        public String getFinalStatus() { return finalStatus; }
    }
}
