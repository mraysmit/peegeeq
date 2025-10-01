package dev.mars.peegeeq.examples.springboot.controller;

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

import dev.mars.peegeeq.examples.springboot.model.CreateOrderRequest;
import dev.mars.peegeeq.examples.springboot.model.CreateOrderResponse;
import dev.mars.peegeeq.examples.springboot.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.CompletableFuture;

/**
 * REST Controller for order management using PeeGeeQ Transactional Outbox Pattern.
 * 
 * This controller follows the patterns outlined in the PeeGeeQ Transactional Outbox Patterns Guide
 * and demonstrates proper REST API design for Spring Boot applications.
 * 
 * Key Features:
 * - RESTful API design
 * - Reactive operations with CompletableFuture
 * - Comprehensive error handling
 * - Proper HTTP status codes
 * - Request/response logging
 * 
 * Endpoints:
 * - POST /api/orders - Create a new order
 * - POST /api/orders/{id}/validate - Validate an existing order
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-06
 * @version 1.0
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * Creates a new order using the transactional outbox pattern.
     * 
     * This endpoint demonstrates the complete Spring Boot integration with PeeGeeQ:
     * 1. Receives the order creation request
     * 2. Delegates to the service layer for business logic
     * 3. Returns a reactive response with proper error handling
     * 
     * @param request The order creation request
     * @return CompletableFuture containing the response entity
     */
    @PostMapping
    public CompletableFuture<ResponseEntity<CreateOrderResponse>> createOrder(
            @RequestBody CreateOrderRequest request) {
        
        log.info("Received order creation request for customer: {}", request.getCustomerId());
        log.debug("Order details: {}", request);

        return orderService.createOrder(request)
            .thenApply(orderId -> {
                log.info("Order created successfully: {}", orderId);
                CreateOrderResponse response = new CreateOrderResponse(orderId);
                return ResponseEntity.ok(response);
            })
            .exceptionally(error -> {
                log.error("Order creation failed for customer {}: {}", request.getCustomerId(), error.getMessage(), error);
                CreateOrderResponse response = new CreateOrderResponse(null, 
                    "Order creation failed: " + error.getMessage());
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
            });
    }

    /**
     * Validates an existing order.
     * 
     * This endpoint demonstrates the transaction propagation approach:
     * 1. Receives the validation request
     * 2. Uses the sendInTransaction method for proper transaction handling
     * 3. Returns appropriate response based on the result
     *
     * @param orderId The ID of the order to validate
     * @return CompletableFuture containing the response entity
     */
    // Commented out - validateOrder method removed from OrderService during refactoring
    // @PostMapping("/{orderId}/validate")
    // public CompletableFuture<ResponseEntity<String>> validateOrder(@PathVariable String orderId) {
    //     log.info("Received order validation request for order: {}", orderId);
    //
    //     return orderService.validateOrder(orderId)
    //         .thenApply(result -> {
    //             log.info("Order validation completed for order: {}", orderId);
    //             return ResponseEntity.ok("Order validation completed successfully");
    //         })
    //         .exceptionally(error -> {
    //             log.error("Order validation failed for order {}: {}", orderId, error.getMessage(), error);
    //             return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
    //                 .body("Order validation failed: " + error.getMessage());
    //         });
    // }

    /**
     * Creates an order with business validation that may trigger rollback.
     *
     * This endpoint demonstrates transactional rollback scenarios:
     * - If amount > $10,000: Business validation fails, entire transaction rolls back
     * - If customerId = "INVALID_CUSTOMER": Customer validation fails, transaction rolls back
     * - Otherwise: Order and events are committed together
     *
     * @param request The order creation request
     * @return CompletableFuture containing the response entity
     */
    @PostMapping("/with-validation")
    public CompletableFuture<ResponseEntity<CreateOrderResponse>> createOrderWithValidation(
            @RequestBody CreateOrderRequest request) {

        log.info("Received order creation request with validation for customer: {}", request.getCustomerId());
        log.debug("Order details: {}", request);

        return orderService.createOrderWithBusinessValidation(request)
            .thenApply(orderId -> {
                log.info("✅ TRANSACTION SUCCESS: Order {} created and committed with all events", orderId);
                CreateOrderResponse response = new CreateOrderResponse(orderId,
                    "Order created successfully with business validation");
                return ResponseEntity.ok(response);
            })
            .exceptionally(error -> {
                // Extract the root cause message
                String errorMessage = error.getCause() != null ? error.getCause().getMessage() : error.getMessage();

                // Check if this is an intentional test failure
                if (errorMessage != null && errorMessage.contains("🧪 INTENTIONAL TEST FAILURE:")) {
                    log.info("❌ TRANSACTION ROLLBACK: Order creation with validation failed for customer {}: {}",
                        request.getCustomerId(), errorMessage);
                } else {
                    log.error("❌ TRANSACTION ROLLBACK: Order creation with validation failed for customer {}: {}",
                        request.getCustomerId(), errorMessage, error);
                }
                CreateOrderResponse response = new CreateOrderResponse(null,
                    "Order creation failed and was rolled back: " + errorMessage);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
            });
    }

    /**
     * Creates an order with database constraints that may trigger rollback.
     *
     * This endpoint demonstrates database-level rollback scenarios:
     * - If customerId = "DUPLICATE_ORDER": Database constraint violation, transaction rolls back
     * - Otherwise: Order and events are committed together
     *
     * @param request The order creation request
     * @return CompletableFuture containing the response entity
     */
    @PostMapping("/with-constraints")
    public CompletableFuture<ResponseEntity<CreateOrderResponse>> createOrderWithConstraints(
            @RequestBody CreateOrderRequest request) {

        log.info("Received order creation request with constraints for customer: {}", request.getCustomerId());
        log.debug("Order details: {}", request);

        return orderService.createOrderWithDatabaseConstraints(request)
            .thenApply(orderId -> {
                log.info("✅ TRANSACTION SUCCESS: Order {} created and committed with database constraints", orderId);
                CreateOrderResponse response = new CreateOrderResponse(orderId,
                    "Order created successfully with database constraints");
                return ResponseEntity.ok(response);
            })
            .exceptionally(error -> {
                log.error("❌ TRANSACTION ROLLBACK: Order creation with constraints failed for customer {}: {}",
                    request.getCustomerId(), error.getMessage());
                CreateOrderResponse response = new CreateOrderResponse(null,
                    "Database operation failed and was rolled back: " + error.getMessage());
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
            });
    }

    /**
     * Creates an order with multiple events to demonstrate successful transaction.
     *
     * This endpoint demonstrates successful multi-event transactions:
     * - Creates order record in database
     * - Publishes OrderCreatedEvent to outbox
     * - Publishes OrderValidatedEvent to outbox
     * - Publishes InventoryReservedEvent to outbox
     * - All operations commit together or all roll back together
     *
     * @param request The order creation request
     * @return CompletableFuture containing the response entity
     */
    @PostMapping("/with-multiple-events")
    public CompletableFuture<ResponseEntity<CreateOrderResponse>> createOrderWithMultipleEvents(
            @RequestBody CreateOrderRequest request) {

        log.info("Received order creation request with multiple events for customer: {}", request.getCustomerId());
        log.debug("Order details: {}", request);

        return orderService.createOrderWithMultipleEvents(request)
            .thenApply(orderId -> {
                log.info("✅ TRANSACTION SUCCESS: Order {} and all events committed together", orderId);
                CreateOrderResponse response = new CreateOrderResponse(orderId,
                    "Order created successfully with multiple events");
                return ResponseEntity.ok(response);
            })
            .exceptionally(error -> {
                log.error("❌ TRANSACTION ROLLBACK: Order creation with multiple events failed for customer {}: {}",
                    request.getCustomerId(), error.getMessage());
                CreateOrderResponse response = new CreateOrderResponse(null,
                    "Order creation with multiple events failed and was rolled back: " + error.getMessage());
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
            });
    }

    /**
     * Health check endpoint to verify the controller is working.
     *
     * @return Simple health check response
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        log.debug("Health check requested");
        return ResponseEntity.ok("Order Controller is healthy");
    }

    /**
     * Exception handler for validation errors.
     * 
     * @param ex The validation exception
     * @return Error response entity
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<CreateOrderResponse> handleValidationError(IllegalArgumentException ex) {
        log.warn("Validation error: {}", ex.getMessage());
        CreateOrderResponse response = new CreateOrderResponse(null, "Validation error: " + ex.getMessage());
        return ResponseEntity.badRequest().body(response);
    }

    /**
     * General exception handler for unexpected errors.
     * 
     * @param ex The exception
     * @return Error response entity
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<CreateOrderResponse> handleGeneralError(Exception ex) {
        log.error("Unexpected error in order controller: {}", ex.getMessage(), ex);
        CreateOrderResponse response = new CreateOrderResponse(null, "An unexpected error occurred");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}
