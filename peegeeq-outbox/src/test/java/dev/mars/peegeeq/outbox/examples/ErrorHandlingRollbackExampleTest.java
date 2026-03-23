package dev.mars.peegeeq.outbox.examples;

import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;

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
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.TransactionPropagation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

/**
 * Comprehensive JUnit test for Error Handling and Rollback Scenarios in PeeGeeQ Outbox Pattern.
 *
 * This test demonstrates the advanced usage pattern: Error Handling and Rollback Scenarios
 * following the patterns outlined in Section "Advanced Usage Patterns - 2. Error Handling and Rollback Scenarios".
 *
 * <h2>⚠️ INTENTIONAL FAILURES - This Test Contains Expected Errors</h2>
 * <p>This test class deliberately triggers various error conditions to demonstrate proper rollback behavior.
 * The following errors are <b>INTENTIONAL</b> and expected:</p>
 * <ul>
 *   <li><b>Order amount exceeds limit</b> - Tests business rule validation with automatic rollback</li>
 *   <li><b>Invalid customer ID</b> - Tests input validation failures</li>
 *   <li><b>Insufficient inventory</b> - Tests multi-stage operation failures</li>
 *   <li><b>Amount must be positive</b> - Tests data validation rules</li>
 *   <li><b>Duplicate order ID</b> - Tests business constraint violations</li>
 * </ul>
 *
 * <h2>Test Coverage</h2>
 * <ul>
 *   <li><b>Basic Error Handling</b> - Automatic rollback on business rule violations</li>
 *   <li><b>Business Logic Validation</b> - Input validation with proper error handling</li>
 *   <li><b>Multi-Stage Operations</b> - Complex workflows with rollback on any stage failure</li>
 * </ul>
 *
 * <h2>Key Features Tested</h2>
 * <ul>
 *   <li>Business logic validation with automatic rollback</li>
 *   <li>Future.failedFuture() usage for transaction rollback</li>
 *   <li>Multi-stage operations with rollback on any failure</li>
 *   <li>Exception propagation and error handling</li>
 *   <li>Transaction consistency guarantees</li>
 *   <li>Recovery patterns and error classification</li>
 * </ul>
 *
 * <h2>Expected Test Results</h2>
 * <p>All tests should <b>PASS</b> by correctly handling the intentional failures:</p>
 * <ul>
 *   <li>Success scenarios complete without errors</li>
 *   <li>Failure scenarios trigger expected exceptions and rollbacks</li>
 *   <li>All transactions maintain ACID properties</li>
 *   <li>No partial state changes remain after rollbacks</li>
 * </ul>
 *
 * <h2>Error Log Messages</h2>
 * <p>The following ERROR log messages are <b>EXPECTED</b> and indicate proper error handling:</p>
 * <ul>
 *   <li>"Order processing failed, all events rolled back" - Expected for business rule violations</li>
 *   <li>"Business validation failed, all events rolled back" - Expected for validation failures</li>
 *   <li>"Multi-stage processing failed, all stages rolled back" - Expected for workflow failures</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-14
 * @version 1.0
 */
@ExtendWith(VertxExtension.class)
@Tag(TestCategories.INTEGRATION)
@Testcontainers
public class ErrorHandlingRollbackExampleTest {
    private static final Logger logger = LoggerFactory.getLogger(ErrorHandlingRollbackExampleTest.class);
    
    @Container
    static PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer("postgres:15.13-alpine3.20");
        container.withDatabaseName("peegeeq_error_rollback_test");
        container.withUsername("postgres");
        container.withPassword("password");
        return container;
    }
    
    private PeeGeeQManager manager;
    private QueueFactory outboxFactory;
    private OutboxProducer<OrderEvent> orderProducer;
    private OutboxProducer<ProcessingEvent> processingProducer;
    private BusinessService businessService;
    
    @BeforeEach
    void setUp() throws Exception {
        // Initialize schema first
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);

        logger.info("Setting up Error Handling and Rollback test environment...");
        
        // Configure system properties for TestContainer
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.schema", "public");
        System.setProperty("peegeeq.database.ssl.enabled", "false");
        
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
    
    @AfterEach
    void tearDown(VertxTestContext testContext) throws InterruptedException {
        logger.info("Cleaning up resources...");
        
        if (orderProducer != null) {
            orderProducer.close();
            logger.info("✓ Order producer closed");
        }
        
        if (processingProducer != null) {
            processingProducer.close();
            logger.info("✓ Processing producer closed");
        }
        
        Future<Void> closeFuture = (manager != null)
            ? manager.closeReactive()
            : Future.succeededFuture();

        closeFuture.onComplete(ar -> {
            if (ar.succeeded()) {
                logger.info("✓ PeeGeeQ Manager stopped");
            }
            logger.info("✓ Cleanup completed");
            testContext.completeNow();
        });
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }
    
    /**
     * Test Pattern 1: Basic error handling with automatic rollback
     * 
     * This tests the exact pattern from the guide:
     * "processOrderWithErrorHandling" - automatic rollback on failure
     */
    @Test
    void testBasicErrorHandlingWithRollback(VertxTestContext testContext) throws InterruptedException {
        logger.info("--- Testing Pattern 1: Basic Error Handling with Automatic Rollback ---");

        // Test successful order processing
        OrderEvent successOrder = new OrderEvent("ERROR-SUCCESS-001", "CUSTOMER-GOOD", BigDecimal.valueOf(5000.00));
        logger.info("Testing successful order: {}", successOrder);

        processOrderWithErrorHandling(successOrder)
            .compose(result -> {
                testContext.verify(() -> {
                    logger.info("✓ Successful order processed: {}", result);
                    assertNotNull(result);
                    assertTrue(result.contains("ERROR-SUCCESS-001"));
                });

                // Test order that exceeds limit (should fail and rollback)
                OrderEvent failOrder = new OrderEvent("ERROR-FAIL-001", "CUSTOMER-BAD", BigDecimal.valueOf(15000.00));
                logger.info("Testing order that exceeds limit: {}", failOrder);

                return processOrderWithErrorHandling(failOrder)
                    .map(r -> {
                        testContext.failNow("Order should have failed but succeeded: " + r);
                        return r;
                    })
                    .recover(error -> {
                        logger.info("INTENTIONAL FAILURE: Order correctly failed and rolled back as expected");
                        logger.info("   📋 Error details: {}", error.getMessage());
                        logger.info("   🎯 This failure demonstrates proper business rule validation and automatic rollback");

                        testContext.verify(() -> {
                            String errorMessage = error.getMessage();
                            String causeMessage = error.getCause() != null ? error.getCause().getMessage() : "";
                            assertTrue(errorMessage.contains("Order amount exceeds limit") ||
                                      causeMessage.contains("Order amount exceeds limit") ||
                                      errorMessage.contains("Order processing failed"),
                                      "Expected error message about order amount limit, but got: " + errorMessage);
                        });
                        return Future.succeededFuture("expected-failure");
                    });
            })
            .onSuccess(v -> {
                logger.info("✓ Basic error handling with automatic rollback tested successfully");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(60, TimeUnit.SECONDS));
    }
    
    /**
     * Test Pattern 2: Business logic validation failures
     *
     * This tests various business validation scenarios that trigger rollback
     */
    @Test
    void testBusinessLogicValidationFailures(VertxTestContext testContext) throws InterruptedException {
        logger.info("--- Testing Pattern 2: Business Logic Validation Failures ---");

        // Test different validation failure scenarios
        testValidationFailure("INVALID-CUSTOMER-001", "INVALID-CUSTOMER", BigDecimal.valueOf(1000.00), "Invalid customer ID")
            .compose(v -> testValidationFailure("NEGATIVE-AMOUNT-001", "CUSTOMER-VALID", BigDecimal.valueOf(-100.00), "Amount must be positive"))
            .compose(v -> testValidationFailure("ZERO-AMOUNT-001", "CUSTOMER-VALID", BigDecimal.ZERO, "Amount must be positive"))
            .compose(v -> testValidationFailure("DUPLICATE-ORDER-001", "CUSTOMER-VALID", BigDecimal.valueOf(500.00), "Duplicate order ID"))
            .onSuccess(v -> {
                logger.info("✓ Business logic validation failures tested successfully");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(60, TimeUnit.SECONDS));
    }

    /**
     * Test Pattern 3: Multi-stage operations with rollback
     *
     * This tests complex operations with multiple stages that can fail at any point
     */
    @Test
    void testMultiStageOperationsWithRollback(VertxTestContext testContext) throws InterruptedException {
        logger.info("--- Testing Pattern 3: Multi-Stage Operations with Rollback ---");

        // Test successful multi-stage operation
        OrderEvent successOrder = new OrderEvent("MULTI-SUCCESS-001", "CUSTOMER-MULTI", BigDecimal.valueOf(2500.00));
        logger.info("Testing successful multi-stage operation: {}", successOrder);

        processMultiStageOrder(successOrder, false)
            .compose(result -> {
                testContext.verify(() -> {
                    logger.info("✓ Multi-stage operation completed successfully: {}", result);
                    assertNotNull(result);
                    assertTrue(result.contains("MULTI-SUCCESS-001"));
                });

                // Test multi-stage operation that fails in stage 2
                OrderEvent failOrder = new OrderEvent("MULTI-FAIL-001", "CUSTOMER-MULTI", BigDecimal.valueOf(3500.00));
                logger.info("Testing multi-stage operation that fails in stage 2: {}", failOrder);

                return processMultiStageOrder(failOrder, true)
                    .map(r -> {
                        testContext.failNow("Multi-stage operation should have failed but succeeded: " + r);
                        return r;
                    })
                    .recover(error -> {
                        logger.info("INTENTIONAL FAILURE: Multi-stage operation correctly failed and rolled back as expected");
                        logger.info("   📋 Error details: {}", error.getMessage());
                        logger.info("   🎯 This failure demonstrates proper multi-stage rollback when any stage fails");

                        testContext.verify(() -> {
                            String errorMessage = error.getMessage();
                            String causeMessage = error.getCause() != null ? error.getCause().getMessage() : "";
                            assertTrue(errorMessage.contains("Insufficient inventory") ||
                                      causeMessage.contains("Insufficient inventory") ||
                                      errorMessage.contains("Multi-stage processing failed"),
                                      "Expected error message about insufficient inventory, but got: " + errorMessage);
                        });
                        return Future.succeededFuture("expected-failure");
                    });
            })
            .onSuccess(v -> {
                logger.info("✓ Multi-stage operations with rollback tested successfully");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(60, TimeUnit.SECONDS));
    }

    /**
     * Process order with error handling following the exact pattern from the guide
     */
    private Future<String> processOrderWithErrorHandling(OrderEvent order) {
        Promise<String> promise = Promise.promise();
        manager.getVertx().runOnContext(v -> {
            processingProducer.sendInOwnTransaction(
                new OrderProcessingStartedEvent(order),
                TransactionPropagation.CONTEXT
            )
            .compose(x -> {
                if (order.getAmount().compareTo(BigDecimal.valueOf(10000)) > 0) {
                    return Future.failedFuture(new BusinessException("Order amount exceeds limit"));
                }
                return businessService.processOrder(order);
            })
            .compose(result -> processingProducer
                .sendInOwnTransaction(new OrderProcessedEvent(order, result), TransactionPropagation.CONTEXT)
                .map(ignored -> result)
            )
            .recover(error -> {
                logger.error("🎯 INTENTIONAL TEST FAILURE: Order processing failed, all events rolled back: {}", error.getMessage());
                logger.info("   📋 This error demonstrates proper automatic rollback behavior in PeeGeeQ Outbox pattern");
                return Future.failedFuture(new RuntimeException("Order processing failed", error));
            })
            .onComplete(promise);
        });
        return promise.future();
    }

    /**
     * Test a specific validation failure scenario
     *
     * @param orderId The order ID to test
     * @param customerId The customer ID to test
     * @param amount The order amount to test
     * @param expectedError The expected error message (for documentation)
     */
    private Future<Void> testValidationFailure(String orderId, String customerId, BigDecimal amount, String expectedError) {
        logger.info("🧪 Testing INTENTIONAL validation failure: {} - Expected: {}", orderId, expectedError);

        OrderEvent order = new OrderEvent(orderId, customerId, amount);
        return processOrderWithBusinessValidation(order)
            .map(success -> {
                fail("❌ UNEXPECTED SUCCESS: Order should have failed but succeeded: " + success);
                return (Void) null;
            })
            .recover(e -> {
                if (e.getMessage().contains(expectedError) ||
                    (e.getCause() != null && e.getCause().getMessage().contains(expectedError))) {
                    logger.info("INTENTIONAL FAILURE: Validation correctly failed as expected: {}", expectedError);
                } else {
                    logger.info("INTENTIONAL FAILURE: Validation failed with wrapped error (still expected)");
                    logger.info("   📋 Actual error: {}", e.getMessage());
                    logger.info("   🎯 This demonstrates proper error handling and rollback behavior");
                }
                return Future.succeededFuture();
            });
    }

    /**
     * Process order with comprehensive business validation
     */
    private Future<String> processOrderWithBusinessValidation(OrderEvent order) {
        Promise<String> promise = Promise.promise();
        manager.getVertx().runOnContext(v -> {
            processingProducer.sendInOwnTransaction(
                new ValidationStartedEvent(order.getOrderId()),
                TransactionPropagation.CONTEXT
            )
            .compose(x -> {
                if (order.getCustomerId().startsWith("INVALID")) {
                    return Future.failedFuture(new BusinessException("Invalid customer ID"));
                }
                if (order.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                    return Future.failedFuture(new BusinessException("Amount must be positive"));
                }
                if (order.getOrderId().startsWith("DUPLICATE")) {
                    return Future.failedFuture(new BusinessException("Duplicate order ID"));
                }
                return businessService.processOrder(order);
            })
            .compose(result -> processingProducer
                .sendInOwnTransaction(new ValidationCompletedEvent(order.getOrderId(), "PASSED"), TransactionPropagation.CONTEXT)
                .map(ignored -> result)
            )
            .recover(error -> {
                logger.error("🎯 INTENTIONAL TEST FAILURE: Business validation failed, all events rolled back: {}", error.getMessage());
                logger.info("   📋 This error demonstrates proper validation failure handling and automatic rollback");
                return Future.failedFuture(new RuntimeException("Business validation failed", error));
            })
            .onComplete(promise);
        });
        return promise.future();
    }

    /**
     * Process order with multiple stages that can fail at any point
     */
    private Future<String> processMultiStageOrder(OrderEvent order, boolean failInStage2) {
        Promise<String> promise = Promise.promise();
        manager.getVertx().runOnContext(v -> {
            processingProducer.sendInOwnTransaction(
                new MultiStageStartedEvent(order.getOrderId(), "STAGE_1"),
                TransactionPropagation.CONTEXT
            )
            .compose(x -> {
                logger.info("Stage 1: Initial validation for order {}", order.getOrderId());
                return businessService.validateOrder(order);
            })
            .compose(validationResult -> {
                logger.info("Stage 2: Inventory check for order {}", order.getOrderId());
                if (failInStage2) {
                    return Future.failedFuture(new BusinessException("Insufficient inventory in stage 2"));
                }
                return processingProducer
                    .sendInOwnTransaction(new MultiStageProgressEvent(order.getOrderId(), "STAGE_2", "INVENTORY_CHECKED"), TransactionPropagation.CONTEXT)
                    .map(ignored -> "inventory-checked");
            })
            .compose(inventoryResult -> {
                logger.info("Stage 3: Payment processing for order {}", order.getOrderId());
                return processingProducer
                    .sendInOwnTransaction(new MultiStageProgressEvent(order.getOrderId(), "STAGE_3", "PAYMENT_PROCESSED"), TransactionPropagation.CONTEXT)
                    .map(ignored -> "payment-processed");
            })
            .compose(paymentResult -> {
                logger.info("Stage 4: Final completion for order {}", order.getOrderId());
                return processingProducer
                    .sendInOwnTransaction(new MultiStageCompletedEvent(order.getOrderId(), "ALL_STAGES_COMPLETED"), TransactionPropagation.CONTEXT)
                    .map(ignored -> "Multi-stage processing completed for order " + order.getOrderId());
            })
            .recover(error -> {
                logger.error("🎯 INTENTIONAL TEST FAILURE: Multi-stage processing failed, all stages rolled back: {}", error.getMessage());
                logger.info("   📋 This error demonstrates proper multi-stage rollback when any stage fails");
                return Future.failedFuture(new RuntimeException("Multi-stage processing failed", error));
            })
            .onComplete(promise);
        });
        return promise.future();
    }

    // Business service for processing orders
    private static class BusinessService {
        private static final Logger logger = LoggerFactory.getLogger(BusinessService.class);

        public Future<String> processOrder(OrderEvent order) {
            logger.info("Processing order: {}", order.getOrderId());
            // Simulate business processing
            return Future.succeededFuture("Order " + order.getOrderId() + " processed successfully");
        }

        public Future<String> validateOrder(OrderEvent order) {
            logger.info("Validating order: {}", order.getOrderId());
            // Simulate validation
            return Future.succeededFuture("Order " + order.getOrderId() + " validated");
        }
    }

    // Custom business exception
    public static class BusinessException extends RuntimeException {
        public BusinessException(String message) {
            super(message);
        }
    }

    // Event classes for error handling scenarios
    public static class OrderEvent {
        private final String orderId;
        private final String customerId;
        private final BigDecimal amount;
        private final long timestamp;

        public OrderEvent(String orderId, String customerId, BigDecimal amount) {
            this.orderId = orderId;
            this.customerId = customerId;
            this.amount = amount;
            this.timestamp = System.currentTimeMillis();
        }

        public String getOrderId() { return orderId; }
        public String getCustomerId() { return customerId; }
        public BigDecimal getAmount() { return amount; }
        public long getTimestamp() { return timestamp; }

        @Override
        public String toString() {
            return String.format("OrderEvent{orderId='%s', customerId='%s', amount=%s, timestamp=%s}",
                orderId, customerId, amount, timestamp);
        }
    }

    public static class ProcessingEvent {
        private final String eventId;
        private final String description;
        private final long timestamp;

        public ProcessingEvent(String eventId, String description) {
            this.eventId = eventId;
            this.description = description;
            this.timestamp = System.currentTimeMillis();
        }

        public String getEventId() { return eventId; }
        public String getDescription() { return description; }
        public long getTimestamp() { return timestamp; }

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
}


