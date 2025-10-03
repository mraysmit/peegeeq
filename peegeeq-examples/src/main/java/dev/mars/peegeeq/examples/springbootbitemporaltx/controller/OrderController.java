package dev.mars.peegeeq.examples.springbootbitemporaltx.controller;

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

import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.api.EventQuery;
import dev.mars.peegeeq.api.EventStore;
import dev.mars.peegeeq.examples.springbootbitemporaltx.events.*;
import dev.mars.peegeeq.examples.springbootbitemporaltx.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * REST Controller for Advanced Bi-Temporal Event Store Transaction Coordination.
 * 
 * This controller provides REST endpoints to demonstrate advanced bi-temporal patterns
 * with multi-event store transaction coordination using Vert.x 5.x reactive patterns
 * and PostgreSQL ACID transactions.
 * 
 * <h2>API Endpoints</h2>
 * 
 * <h3>Order Processing</h3>
 * <ul>
 *   <li><b>POST /api/orders</b> - Process complete order with coordinated transactions</li>
 *   <li><b>GET /api/orders/{orderId}/history</b> - Get complete order history across all stores</li>
 *   <li><b>GET /api/orders/{orderId}/status</b> - Get current order status</li>
 * </ul>
 * 
 * <h3>Cross-Store Queries</h3>
 * <ul>
 *   <li><b>GET /api/orders/{orderId}/events</b> - Get all events for an order across stores</li>
 *   <li><b>GET /api/transactions/{transactionId}</b> - Get all events for a transaction</li>
 *   <li><b>GET /api/correlations/{correlationId}</b> - Get all correlated events</li>
 * </ul>
 * 
 * <h3>Demonstration Endpoints</h3>
 * <ul>
 *   <li><b>POST /api/demo/sample-order</b> - Create a sample order for demonstration</li>
 *   <li><b>GET /api/demo/stats</b> - Get statistics across all event stores</li>
 * </ul>
 * 
 * <h2>Transaction Coordination</h2>
 * 
 * All order processing operations coordinate transactions across:
 * <ul>
 *   <li><b>Order Event Store</b> - Order lifecycle events</li>
 *   <li><b>Inventory Event Store</b> - Inventory movements and reservations</li>
 *   <li><b>Payment Event Store</b> - Payment processing events</li>
 *   <li><b>Audit Event Store</b> - Compliance and audit events</li>
 * </ul>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-03
 * @version 1.0
 */
@RestController
@RequestMapping("/api")
public class OrderController {
    
    private static final Logger logger = LoggerFactory.getLogger(OrderController.class);
    
    private final OrderProcessingService orderProcessingService;
    private final EventStore<OrderEvent> orderEventStore;
    private final EventStore<InventoryEvent> inventoryEventStore;
    private final EventStore<PaymentEvent> paymentEventStore;
    private final EventStore<AuditEvent> auditEventStore;
    
    @Autowired
    public OrderController(
            OrderProcessingService orderProcessingService,
            EventStore<OrderEvent> orderEventStore,
            EventStore<InventoryEvent> inventoryEventStore,
            EventStore<PaymentEvent> paymentEventStore,
            EventStore<AuditEvent> auditEventStore) {
        this.orderProcessingService = orderProcessingService;
        this.orderEventStore = orderEventStore;
        this.inventoryEventStore = inventoryEventStore;
        this.paymentEventStore = paymentEventStore;
        this.auditEventStore = auditEventStore;
    }
    
    /**
     * Processes a complete order using coordinated transactions across multiple
     * bi-temporal event stores.
     * 
     * This endpoint demonstrates the core pattern of multi-event store transaction
     * coordination with automatic rollback on any failure.
     */
    @PostMapping("/orders")
    public CompletableFuture<ResponseEntity<OrderProcessingResult>> processOrder(
            @RequestBody OrderProcessingRequest request) {
        logger.info("Processing order request: {}", request.getOrderId());
        
        return orderProcessingService.processCompleteOrder(request)
            .thenApply(result -> {
                if (result.isSuccess()) {
                    logger.info("Order processing successful: {}", result.getOrderId());
                    return ResponseEntity.ok(result);
                } else {
                    logger.error("Order processing failed: {} - {}", result.getOrderId(), result.getMessage());
                    return ResponseEntity.badRequest().body(result);
                }
            })
            .exceptionally(throwable -> {
                logger.error("Order processing exception: {}", throwable.getMessage(), throwable);
                return ResponseEntity.internalServerError().body(
                    new OrderProcessingResult(
                        request.getOrderId(),
                        "FAILED",
                        "Internal server error: " + throwable.getMessage(),
                        null,
                        null,
                        null
                    )
                );
            });
    }
    
    /**
     * Gets the complete order history across all event stores.
     * 
     * This endpoint demonstrates cross-store queries to reconstruct
     * the complete order lifecycle from multiple bi-temporal event stores.
     */
    @GetMapping("/orders/{orderId}/history")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> getOrderHistory(@PathVariable String orderId) {
        logger.info("Getting order history for: {}", orderId);
        
        // Query all event stores for events related to this order
        CompletableFuture<List<BiTemporalEvent<OrderEvent>>> orderEvents = 
            orderEventStore.query(EventQuery.forAggregate(orderId));
        
        CompletableFuture<List<BiTemporalEvent<InventoryEvent>>> inventoryEvents =
            inventoryEventStore.query(EventQuery.builder()
                .headerFilters(Map.of("orderId", orderId))
                .build());

        CompletableFuture<List<BiTemporalEvent<PaymentEvent>>> paymentEvents =
            paymentEventStore.query(EventQuery.builder()
                .headerFilters(Map.of("orderId", orderId))
                .build());

        CompletableFuture<List<BiTemporalEvent<AuditEvent>>> auditEvents =
            auditEventStore.query(EventQuery.builder()
                .headerFilters(Map.of("entityId", orderId))
                .build());
        
        return CompletableFuture.allOf(orderEvents, inventoryEvents, paymentEvents, auditEvents)
            .thenApply(v -> {
                Map<String, Object> history = Map.of(
                    "orderId", orderId,
                    "orderEvents", orderEvents.join(),
                    "inventoryEvents", inventoryEvents.join(),
                    "paymentEvents", paymentEvents.join(),
                    "auditEvents", auditEvents.join()
                );
                
                logger.info("Retrieved order history for: {} - {} total events", orderId,
                    orderEvents.join().size() + inventoryEvents.join().size() + 
                    paymentEvents.join().size() + auditEvents.join().size());
                
                return ResponseEntity.ok(history);
            })
            .exceptionally(throwable -> {
                logger.error("Failed to get order history for: {} - {}", orderId, throwable.getMessage(), throwable);
                return ResponseEntity.internalServerError().body(
                    Map.of("error", "Failed to retrieve order history: " + throwable.getMessage())
                );
            });
    }
    
    /**
     * Creates a sample order for demonstration purposes.
     * 
     * This endpoint creates a realistic sample order that demonstrates
     * the multi-event store transaction coordination patterns.
     */
    @PostMapping("/demo/sample-order")
    public CompletableFuture<ResponseEntity<OrderProcessingResult>> createSampleOrder() {
        logger.info("Creating sample order for demonstration");
        
        String orderId = "ORDER-" + UUID.randomUUID().toString().substring(0, 8);
        String customerId = "CUSTOMER-" + UUID.randomUUID().toString().substring(0, 8);
        
        OrderProcessingRequest sampleRequest = new OrderProcessingRequest(
            orderId,
            customerId,
            List.of(
                new OrderProcessingRequest.OrderItem(
                    "PROD-001",
                    "SKU-LAPTOP-001",
                    "Gaming Laptop",
                    1,
                    new BigDecimal("1299.99")
                ),
                new OrderProcessingRequest.OrderItem(
                    "PROD-002",
                    "SKU-MOUSE-001",
                    "Wireless Gaming Mouse",
                    2,
                    new BigDecimal("79.99")
                )
            ),
            new BigDecimal("1459.97"),
            "USD",
            "CREDIT_CARD",
            "123 Main St, Anytown, ST 12345",
            "123 Main St, Anytown, ST 12345"
        );
        
        logger.info("Sample order created: {}", orderId);
        return processOrder(sampleRequest);
    }
    
    /**
     * Gets statistics across all event stores.
     * 
     * This endpoint demonstrates querying statistics from multiple
     * bi-temporal event stores to provide operational insights.
     */
    @GetMapping("/demo/stats")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> getStats() {
        logger.info("Getting statistics across all event stores");
        
        CompletableFuture<EventStore.EventStoreStats> orderStats = orderEventStore.getStats();
        CompletableFuture<EventStore.EventStoreStats> inventoryStats = inventoryEventStore.getStats();
        CompletableFuture<EventStore.EventStoreStats> paymentStats = paymentEventStore.getStats();
        CompletableFuture<EventStore.EventStoreStats> auditStats = auditEventStore.getStats();
        
        return CompletableFuture.allOf(orderStats, inventoryStats, paymentStats, auditStats)
            .thenApply(v -> {
                Map<String, Object> stats = Map.of(
                    "orderStore", Map.of(
                        "totalEvents", orderStats.join().getTotalEvents(),
                        "totalCorrections", orderStats.join().getTotalCorrections(),
                        "eventCountsByType", orderStats.join().getEventCountsByType()
                    ),
                    "inventoryStore", Map.of(
                        "totalEvents", inventoryStats.join().getTotalEvents(),
                        "totalCorrections", inventoryStats.join().getTotalCorrections(),
                        "eventCountsByType", inventoryStats.join().getEventCountsByType()
                    ),
                    "paymentStore", Map.of(
                        "totalEvents", paymentStats.join().getTotalEvents(),
                        "totalCorrections", paymentStats.join().getTotalCorrections(),
                        "eventCountsByType", paymentStats.join().getEventCountsByType()
                    ),
                    "auditStore", Map.of(
                        "totalEvents", auditStats.join().getTotalEvents(),
                        "totalCorrections", auditStats.join().getTotalCorrections(),
                        "eventCountsByType", auditStats.join().getEventCountsByType()
                    )
                );
                
                logger.info("Retrieved statistics across all event stores");
                return ResponseEntity.ok(stats);
            })
            .exceptionally(throwable -> {
                logger.error("Failed to get statistics: {}", throwable.getMessage(), throwable);
                return ResponseEntity.internalServerError().body(
                    Map.of("error", "Failed to retrieve statistics: " + throwable.getMessage())
                );
            });
    }
}
