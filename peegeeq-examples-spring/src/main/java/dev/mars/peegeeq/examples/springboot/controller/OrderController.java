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
import dev.mars.peegeeq.examples.springboot.model.OrderValidationException;
import dev.mars.peegeeq.examples.springboot.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;
import io.vertx.core.Future;



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
    public DeferredResult<ResponseEntity<CreateOrderResponse>> createOrder(
            @RequestBody CreateOrderRequest request) {

        log.info("Received order creation request for customer: {}", request.getCustomerId());
        log.debug("Order details: {}", request);

        DeferredResult<ResponseEntity<CreateOrderResponse>> result = new DeferredResult<>();
        orderService.createOrder(request)
            .map(orderId -> {
                log.info("Order created successfully: {}", orderId);
                return (ResponseEntity<CreateOrderResponse>) ResponseEntity.ok(new CreateOrderResponse(orderId));
            })
            .transform(ar -> {
                if (ar.succeeded()) return Future.succeededFuture(ar.result());
                Throwable error = ar.cause();
                log.error("Order creation failed for customer {}: {}", request.getCustomerId(), error.getMessage(), error);
                return Future.succeededFuture(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .<CreateOrderResponse>body(new CreateOrderResponse(null, "Order creation failed: " + error.getMessage())));
            })
            .onSuccess(result::setResult)
            .onFailure(result::setErrorResult);
        return result;
    }

    /**
     * Validates an existing order.
     *
     * This is a placeholder endpoint that demonstrates error handling.
     * Returns BAD_REQUEST to indicate validation is not fully implemented.
     *
     * @param orderId The ID of the order to validate
     * @return Response entity with validation error
     */
    @PostMapping("/{orderId}/validate")
    public ResponseEntity<CreateOrderResponse> validateOrder(@PathVariable String orderId) {
        log.info("Received order validation request for order: {}", orderId);
        log.warn("Validation endpoint not fully implemented - returning error response");

        CreateOrderResponse response = new CreateOrderResponse(
            null,
            "ERROR",
            "Validation error: Order validation endpoint not fully implemented"
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

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
    public DeferredResult<ResponseEntity<CreateOrderResponse>> createOrderWithValidation(
            @RequestBody CreateOrderRequest request) {

        log.info("Received order creation request with validation for customer: {}", request.getCustomerId());
        log.debug("Order details: {}", request);

        DeferredResult<ResponseEntity<CreateOrderResponse>> result = new DeferredResult<>();
        orderService.createOrderWithBusinessValidation(request)
            .map(orderId -> {
                log.info("TRANSACTION SUCCESS: Order {} created and committed with all events", orderId);
                return (ResponseEntity<CreateOrderResponse>) ResponseEntity.ok(
                        new CreateOrderResponse(orderId, "Order created successfully with business validation"));
            })
            .transform(ar -> {
                if (ar.succeeded()) return Future.succeededFuture(ar.result());
                Throwable error = ar.cause();
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                String errorMessage = cause.getMessage();
                if (cause instanceof OrderValidationException) {
                    log.info(" TRANSACTION ROLLBACK: Order creation with validation failed for customer {}: {}",
                        request.getCustomerId(), errorMessage);
                } else {
                    log.error(" TRANSACTION ROLLBACK: Order creation with validation failed for customer {}: {}",
                        request.getCustomerId(), errorMessage, error);
                }
                return Future.succeededFuture(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .<CreateOrderResponse>body(new CreateOrderResponse(null,
                                "Order creation failed and was rolled back: " + errorMessage)));
            })
            .onSuccess(result::setResult)
            .onFailure(result::setErrorResult);
        return result;
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
    public DeferredResult<ResponseEntity<CreateOrderResponse>> createOrderWithConstraints(
            @RequestBody CreateOrderRequest request) {

        log.info("Received order creation request with constraints for customer: {}", request.getCustomerId());
        log.debug("Order details: {}", request);

        DeferredResult<ResponseEntity<CreateOrderResponse>> result = new DeferredResult<>();
        orderService.createOrderWithDatabaseConstraints(request)
            .map(orderId -> {
                log.info("TRANSACTION SUCCESS: Order {} created and committed with database constraints", orderId);
                return (ResponseEntity<CreateOrderResponse>) ResponseEntity.ok(
                        new CreateOrderResponse(orderId, "Order created successfully with database constraints"));
            })
            .transform(ar -> {
                if (ar.succeeded()) return Future.succeededFuture(ar.result());
                Throwable error = ar.cause();
                log.error(" TRANSACTION ROLLBACK: Order creation with constraints failed for customer {}: {}",
                    request.getCustomerId(), error.getMessage());
                return Future.succeededFuture(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .<CreateOrderResponse>body(new CreateOrderResponse(null,
                                "Database operation failed and was rolled back: " + error.getMessage())));
            })
            .onSuccess(result::setResult)
            .onFailure(result::setErrorResult);
        return result;
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
    public DeferredResult<ResponseEntity<CreateOrderResponse>> createOrderWithMultipleEvents(
            @RequestBody CreateOrderRequest request) {

        log.info("Received order creation request with multiple events for customer: {}", request.getCustomerId());
        log.debug("Order details: {}", request);

        DeferredResult<ResponseEntity<CreateOrderResponse>> result = new DeferredResult<>();
        orderService.createOrderWithMultipleEvents(request)
            .map(orderId -> {
                log.info("TRANSACTION SUCCESS: Order {} and all events committed together", orderId);
                return (ResponseEntity<CreateOrderResponse>) ResponseEntity.ok(
                        new CreateOrderResponse(orderId, "Order created successfully with multiple events"));
            })
            .transform(ar -> {
                if (ar.succeeded()) return Future.succeededFuture(ar.result());
                Throwable error = ar.cause();
                log.error(" TRANSACTION ROLLBACK: Order creation with multiple events failed for customer {}: {}",
                    request.getCustomerId(), error.getMessage());
                return Future.succeededFuture(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .<CreateOrderResponse>body(new CreateOrderResponse(null,
                                "Order creation with multiple events failed and was rolled back: " + error.getMessage())));
            })
            .onSuccess(result::setResult)
            .onFailure(result::setErrorResult);
        return result;
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
