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

package dev.mars.peegeeq.examples.springbootintegrated.controller;

import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.examples.springbootintegrated.events.OrderEvent;
import dev.mars.peegeeq.examples.springbootintegrated.model.CreateOrderRequest;
import dev.mars.peegeeq.examples.springbootintegrated.model.OrderResponse;
import dev.mars.peegeeq.examples.springbootintegrated.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * REST controller for order operations.
 * 
 * <p>Provides endpoints for:
 * <ul>
 *   <li>Creating orders (with integrated outbox + bi-temporal)</li>
 *   <li>Querying order history</li>
 *   <li>Querying customer orders</li>
 *   <li>Point-in-time queries</li>
 * </ul>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-07
 * @version 1.0
 */
@RestController
@RequestMapping("/api")
public class OrderController {
    
    private static final Logger logger = LoggerFactory.getLogger(OrderController.class);
    
    private final OrderService orderService;
    
    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }
    
    /**
     * Creates a new order.
     * 
     * <p>This endpoint demonstrates the integrated pattern where:
     * <ol>
     *   <li>Order is saved to database</li>
     *   <li>Event is sent to outbox (for immediate processing)</li>
     *   <li>Event is appended to bi-temporal store (for historical queries)</li>
     * </ol>
     * 
     * <p>All three operations are in a SINGLE transaction.
     * 
     * @param request Order creation request
     * @return CompletableFuture with order ID
     */
    @PostMapping("/orders")
    public CompletableFuture<ResponseEntity<String>> createOrder(@RequestBody CreateOrderRequest request) {
        logger.info("REST: Creating order for customer: {} amount: {}", 
            request.getCustomerId(), request.getAmount());
        
        return orderService.createOrder(request)
            .thenApply(orderId -> {
                logger.info("REST: Order created successfully: {}", orderId);
                return ResponseEntity.ok(orderId);
            })
            .exceptionally(error -> {
                logger.error("REST: Failed to create order", error);
                return ResponseEntity.internalServerError().body("Failed to create order: " + error.getMessage());
            });
    }
    
    /**
     * Retrieves order history from bi-temporal event store.
     * 
     * @param orderId Order identifier
     * @return CompletableFuture with order history
     */
    @GetMapping("/orders/{orderId}/history")
    public CompletableFuture<ResponseEntity<OrderResponse>> getOrderHistory(@PathVariable String orderId) {
        logger.info("REST: Retrieving order history: {}", orderId);
        
        return orderService.getOrderHistory(orderId)
            .thenApply(response -> {
                logger.info("REST: Order history retrieved: {} events", response.getHistory().size());
                return ResponseEntity.ok(response);
            })
            .exceptionally(error -> {
                logger.error("REST: Failed to retrieve order history: {}", orderId, error);
                return ResponseEntity.internalServerError().build();
            });
    }
    
    /**
     * Retrieves all orders for a customer.
     * 
     * @param customerId Customer identifier
     * @return CompletableFuture with list of order events
     */
    @GetMapping("/customers/{customerId}/orders")
    public CompletableFuture<ResponseEntity<List<BiTemporalEvent<OrderEvent>>>> getCustomerOrders(
            @PathVariable String customerId) {
        logger.info("REST: Retrieving orders for customer: {}", customerId);
        
        return orderService.getCustomerOrders(customerId)
            .thenApply(orders -> {
                logger.info("REST: Found {} orders for customer: {}", orders.size(), customerId);
                return ResponseEntity.ok(orders);
            })
            .exceptionally(error -> {
                logger.error("REST: Failed to retrieve customer orders: {}", customerId, error);
                return ResponseEntity.internalServerError().build();
            });
    }
    
    /**
     * Queries orders as of a specific point in time.
     * 
     * @param asOf Point in time (ISO-8601 format)
     * @return CompletableFuture with events as of that time
     */
    @GetMapping("/orders")
    public CompletableFuture<ResponseEntity<List<BiTemporalEvent<OrderEvent>>>> getOrdersAsOfTime(
            @RequestParam(required = false) String asOf) {
        
        if (asOf != null) {
            Instant validTime = Instant.parse(asOf);
            logger.info("REST: Querying orders as of time: {}", validTime);
            
            return orderService.getOrdersAsOfTime(validTime)
                .thenApply(orders -> {
                    logger.info("REST: Found {} orders as of time: {}", orders.size(), validTime);
                    return ResponseEntity.ok(orders);
                })
                .exceptionally(error -> {
                    logger.error("REST: Failed to query orders as of time: {}", validTime, error);
                    return ResponseEntity.internalServerError().build();
                });
        } else {
            logger.info("REST: Querying all orders");
            return orderService.getOrdersAsOfTime(Instant.now())
                .thenApply(ResponseEntity::ok)
                .exceptionally(error -> {
                    logger.error("REST: Failed to query all orders", error);
                    return ResponseEntity.internalServerError().build();
                });
        }
    }
}

