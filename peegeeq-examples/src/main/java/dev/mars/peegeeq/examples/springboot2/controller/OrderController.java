package dev.mars.peegeeq.examples.springboot2.controller;

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
import dev.mars.peegeeq.examples.springboot2.model.Order;
import dev.mars.peegeeq.examples.springboot2.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;


/**
 * Reactive REST Controller for order management using PeeGeeQ Transactional Outbox Pattern.
 * 
 * This controller uses Spring WebFlux for fully reactive, non-blocking HTTP endpoints.
 * All operations return Mono or Flux for reactive stream processing.
 * 
 * Key Features:
 * - Fully reactive RESTful API using WebFlux
 * - Non-blocking operations with Mono and Flux
 * - Server-Sent Events (SSE) for streaming
 * - Comprehensive error handling
 * - Proper HTTP status codes
 * - Request/response logging
 * 
 * Endpoints:
 * - POST /api/orders - Create a new order
 * - POST /api/orders/with-validation - Create order with business validation
 * - POST /api/orders/with-constraints - Create order with database constraints
 * - POST /api/orders/with-multiple-events - Create order with multiple events
 * - GET /api/orders/{id} - Get order by ID
 * - GET /api/orders/customer/{customerId} - Get orders by customer
 * - GET /api/orders/stream - Stream recent orders (SSE)
 * - POST /api/orders/{id}/validate - Validate an existing order
 * - GET /api/orders/health - Health check
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-01
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
     * This endpoint demonstrates the complete Spring WebFlux integration with PeeGeeQ:
     * 1. Receives the order creation request
     * 2. Delegates to the reactive service layer
     * 3. Returns a reactive response with proper error handling
     * 
     * @param request The order creation request
     * @return Mono containing the response entity
     */
    @PostMapping
    public Mono<ResponseEntity<CreateOrderResponse>> createOrder(@RequestBody CreateOrderRequest request) {
        log.info("Received order creation request for customer: {}", request.getCustomerId());
        log.debug("Order details: {}", request);

        return orderService.createOrder(request)
            .map(orderId -> {
                log.info("Order created successfully: {}", orderId);
                CreateOrderResponse response = new CreateOrderResponse(orderId);
                return ResponseEntity.ok(response);
            })
            .onErrorResume(error -> {
                log.error("Order creation failed for customer {}: {}", 
                    request.getCustomerId(), error.getMessage(), error);
                CreateOrderResponse response = new CreateOrderResponse(null, 
                    "Order creation failed: " + error.getMessage());
                return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response));
            });
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
     * @return Mono containing the response entity
     */
    @PostMapping("/with-validation")
    public Mono<ResponseEntity<CreateOrderResponse>> createOrderWithValidation(
            @RequestBody CreateOrderRequest request) {

        log.info("Received order creation request with validation for customer: {}", request.getCustomerId());
        log.debug("Order details: {}", request);

        return orderService.createOrderWithBusinessValidation(request)
            .map(orderId -> {
                log.info("Order created successfully with validation: {}", orderId);
                CreateOrderResponse response = new CreateOrderResponse(orderId);
                return ResponseEntity.ok(response);
            })
            .onErrorResume(IllegalArgumentException.class, error -> {
                log.warn("Order validation failed for customer {}: {}", 
                    request.getCustomerId(), error.getMessage());
                CreateOrderResponse response = new CreateOrderResponse(null, 
                    "Validation failed: " + error.getMessage());
                return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response));
            })
            .onErrorResume(error -> {
                log.error("Order creation failed for customer {}: {}", 
                    request.getCustomerId(), error.getMessage(), error);
                CreateOrderResponse response = new CreateOrderResponse(null, 
                    "Order creation failed: " + error.getMessage());
                return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response));
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
     * @return Mono containing the response entity
     */
    @PostMapping("/with-constraints")
    public Mono<ResponseEntity<CreateOrderResponse>> createOrderWithConstraints(
            @RequestBody CreateOrderRequest request) {

        log.info("Received order creation request with constraints for customer: {}", request.getCustomerId());
        log.debug("Order details: {}", request);

        return orderService.createOrderWithDatabaseConstraints(request)
            .map(orderId -> {
                log.info("✅ TRANSACTION SUCCESS: Order {} created and committed with database constraints", orderId);
                CreateOrderResponse response = new CreateOrderResponse(orderId,
                    "Order created successfully with database constraints");
                return ResponseEntity.ok(response);
            })
            .onErrorResume(error -> {
                log.error("❌ TRANSACTION ROLLBACK: Order creation with constraints failed for customer {}: {}",
                    request.getCustomerId(), error.getMessage());
                CreateOrderResponse response = new CreateOrderResponse(null,
                    "Database operation failed and was rolled back: " + error.getMessage());
                return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response));
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
     * @return Mono containing the response entity
     */
    @PostMapping("/with-multiple-events")
    public Mono<ResponseEntity<CreateOrderResponse>> createOrderWithMultipleEvents(
            @RequestBody CreateOrderRequest request) {

        log.info("Received order creation request with multiple events for customer: {}", request.getCustomerId());
        log.debug("Order details: {}", request);

        return orderService.createOrderWithMultipleEvents(request)
            .map(orderId -> {
                log.info("✅ TRANSACTION SUCCESS: Order {} and all events committed together", orderId);
                CreateOrderResponse response = new CreateOrderResponse(orderId,
                    "Order created successfully with multiple events");
                return ResponseEntity.ok(response);
            })
            .onErrorResume(error -> {
                log.error("❌ TRANSACTION ROLLBACK: Order creation with multiple events failed for customer {}: {}",
                    request.getCustomerId(), error.getMessage());
                CreateOrderResponse response = new CreateOrderResponse(null,
                    "Order creation with multiple events failed and was rolled back: " + error.getMessage());
                return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response));
            });
    }

    /**
     * Gets an order by ID.
     *
     * @param id The order ID
     * @return Mono containing the order or 404 if not found
     */
    @GetMapping("/{id}")
    public Mono<ResponseEntity<Order>> getOrder(@PathVariable String id) {
        log.info("Received request to get order: {}", id);

        return orderService.findById(id)
            .map(order -> {
                log.info("Order found: {}", id);
                return ResponseEntity.ok(order);
            })
            .defaultIfEmpty(ResponseEntity.notFound().build())
            .doOnError(error -> log.error("Error retrieving order {}: {}", id, error.getMessage()));
    }

    /**
     * Gets order for a customer.
     *
     * @param customerId The customer ID
     * @return Mono of order
     */
    // TODO: Implement using ConnectionProvider.withConnection()
    /*
    @GetMapping("/customer/{customerId}")
    public Mono<ResponseEntity<Order>> getOrdersByCustomer(@PathVariable String customerId) {
        log.info("Received request to get order for customer: {}", customerId);

        return orderService.findByCustomerId(customerId)
            .map(order -> {
                log.info("Found order for customer: {}", customerId);
                return ResponseEntity.ok(order);
            })
            .defaultIfEmpty(ResponseEntity.notFound().build())
            .doOnError(error -> log.error("Error retrieving order for customer {}: {}",
                customerId, error.getMessage()));
    }
    */

    /**
     * Streams recent orders using Server-Sent Events (SSE).
     * 
     * This endpoint demonstrates reactive streaming with WebFlux.
     * Orders created in the last hour are streamed to the client.
     * 
     * @return Flux of orders as Server-Sent Events
     */
    // TODO: Implement using ConnectionProvider.withConnection()
    /*
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<Order> streamRecentOrders() {
        log.info("Received request to stream recent orders");

        Instant oneHourAgo = Instant.now().minus(Duration.ofHours(1));

        return orderService.streamRecentOrders(oneHourAgo)
            .delayElements(Duration.ofMillis(100))  // Add small delay for demo purposes
            .doOnNext(order -> log.debug("Streaming order: {}", order.getId()))
            .doOnComplete(() -> log.info("Completed streaming recent orders"))
            .doOnError(error -> log.error("Error streaming orders: {}", error.getMessage()));
    }
    */

    /**
     * Validates an existing order.
     *
     * @param id The order ID to validate
     * @return Mono containing the response
     */
    @PostMapping("/{id}/validate")
    public Mono<ResponseEntity<String>> validateOrder(@PathVariable String id) {
        log.info("Received request to validate order: {}", id);

        return orderService.validateOrder(id)
            .then(Mono.just(ResponseEntity.ok("Order validated successfully: " + id)))
            .onErrorResume(IllegalArgumentException.class, error -> {
                log.warn("Order validation failed for {}: {}", id, error.getMessage());
                return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("Validation failed: " + error.getMessage()));
            })
            .onErrorResume(error -> {
                log.error("Error validating order {}: {}", id, error.getMessage(), error);
                return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Validation error: " + error.getMessage()));
            });
    }

    /**
     * Health check endpoint.
     * 
     * @return Mono containing health status
     */
    @GetMapping("/health")
    public Mono<ResponseEntity<String>> health() {
        log.debug("Health check requested");
        return Mono.just(ResponseEntity.ok("Reactive Order Service is healthy"));
    }
}

