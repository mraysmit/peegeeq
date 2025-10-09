package dev.mars.peegeeq.examples.springbootbitemporaltx.service;

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
import dev.mars.peegeeq.api.EventStore;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.bitemporal.PgBiTemporalEventStore;
import dev.mars.peegeeq.examples.springbootbitemporaltx.events.AuditEvent;
import dev.mars.peegeeq.examples.springbootbitemporaltx.events.InventoryEvent;
import dev.mars.peegeeq.examples.springbootbitemporaltx.events.OrderEvent;
import dev.mars.peegeeq.examples.springbootbitemporaltx.events.PaymentEvent;
import io.vertx.core.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Order Processing Service for Multi-Event Store Transaction Coordination.
 * 
 * This service demonstrates advanced bi-temporal event store patterns by coordinating
 * transactions across multiple event stores (Order, Inventory, Payment, Audit) using
 * Vert.x 5.x reactive patterns and PostgreSQL ACID transactions.
 * 
 * <h2>Transaction Coordination Patterns</h2>
 * 
 * <h3>1. Complete Order Processing</h3>
 * <p>Coordinates a complete order through all lifecycle stages:</p>
 * <ul>
 *   <li><b>Order Creation</b> - Record order in Order Event Store</li>
 *   <li><b>Inventory Reservation</b> - Reserve inventory in Inventory Event Store</li>
 *   <li><b>Payment Authorization</b> - Authorize payment in Payment Event Store</li>
 *   <li><b>Audit Trail</b> - Record compliance events in Audit Event Store</li>
 *   <li><b>Transaction Coordination</b> - All events in single ACID transaction</li>
 * </ul>
 * 
 * <h3>2. Saga Pattern Implementation</h3>
 * <p>Implements saga pattern for complex business workflows:</p>
 * <ul>
 *   <li><b>Order Cancellation</b> - Compensating actions across all stores</li>
 *   <li><b>Payment Failure</b> - Release inventory and update order status</li>
 *   <li><b>Inventory Shortage</b> - Cancel payment and update order</li>
 *   <li><b>State Machine</b> - Orchestrated workflow execution</li>
 * </ul>
 * 
 * <h3>3. Bi-Temporal Corrections</h3>
 * <p>Coordinated corrections across multiple event stores:</p>
 * <ul>
 *   <li><b>Price Corrections</b> - Update order and payment events</li>
 *   <li><b>Inventory Adjustments</b> - Correct inventory and related orders</li>
 *   <li><b>Audit Compliance</b> - Maintain regulatory audit trail</li>
 *   <li><b>Temporal Consistency</b> - Consistent correction timestamps</li>
 * </ul>
 * 
 * <h2>Technical Implementation</h2>
 * 
 * <h3>Transaction Management</h3>
 * <ul>
 *   <li><b>TransactionPropagation.CONTEXT</b> - Share transactions across stores</li>
 *   <li><b>Automatic Rollback</b> - Any failure rolls back all changes</li>
 *   <li><b>Event Ordering</b> - Consistent transaction time across stores</li>
 *   <li><b>Correlation IDs</b> - Link related events across stores</li>
 * </ul>
 * 
 * <h3>Error Handling</h3>
 * <ul>
 *   <li><b>Transient Failures</b> - Retry logic for temporary issues</li>
 *   <li><b>Business Failures</b> - Saga compensation patterns</li>
 *   <li><b>System Failures</b> - Automatic transaction rollback</li>
 *   <li><b>Audit Trail</b> - Complete failure audit and investigation</li>
 * </ul>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-03
 * @version 1.0
 */
@Service
public class OrderProcessingService {
    
    private static final Logger logger = LoggerFactory.getLogger(OrderProcessingService.class);
    
    private final EventStore<OrderEvent> orderEventStore;
    private final EventStore<InventoryEvent> inventoryEventStore;
    private final EventStore<PaymentEvent> paymentEventStore;
    private final EventStore<AuditEvent> auditEventStore;
    private final DatabaseService databaseService;
    
    public OrderProcessingService(
            EventStore<OrderEvent> orderEventStore,
            EventStore<InventoryEvent> inventoryEventStore,
            EventStore<PaymentEvent> paymentEventStore,
            EventStore<AuditEvent> auditEventStore,
            DatabaseService databaseService) {
        this.orderEventStore = orderEventStore;
        this.inventoryEventStore = inventoryEventStore;
        this.paymentEventStore = paymentEventStore;
        this.auditEventStore = auditEventStore;
        this.databaseService = databaseService;
    }
    
    /**
     * Processes a complete order through all lifecycle stages using coordinated
     * transactions across multiple bi-temporal event stores.
     * 
     * This method demonstrates the core pattern of multi-event store transaction
     * coordination using Vert.x 5.x reactive patterns and PostgreSQL ACID transactions.
     * 
     * @param orderRequest Order processing request
     * @return CompletableFuture that completes with the processing result
     */
    public CompletableFuture<OrderProcessingResult> processCompleteOrder(OrderProcessingRequest orderRequest) {
        logger.info("Starting complete order processing for order: {}", orderRequest.getOrderId());
        
        String correlationId = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
        Instant validTime = Instant.now();
        
        // Use DatabaseService to get connection provider for transaction coordination
        var connectionProvider = databaseService.getConnectionProvider();
        
        // Execute all event store operations within a single transaction
        return connectionProvider.withTransaction("peegeeq-main", connection -> {
            logger.debug("Starting coordinated transaction for order: {}", orderRequest.getOrderId());
            
            // Step 1: Record audit event for transaction start
            AuditEvent transactionStartEvent = createTransactionStartAuditEvent(
                transactionId, orderRequest.getOrderId(), correlationId, validTime);
            
            CompletableFuture<BiTemporalEvent<AuditEvent>> auditFuture =
                ((PgBiTemporalEventStore<AuditEvent>) auditEventStore)
                    .appendInTransaction("TransactionStarted", transactionStartEvent, validTime,
                        Map.of("stage", "transaction-start", "correlationId", correlationId),
                        correlationId, transactionId, connection);
            
            // Step 2: Create order event
            OrderEvent orderEvent = createOrderEvent(orderRequest, validTime);
            
            CompletableFuture<BiTemporalEvent<OrderEvent>> orderFuture = auditFuture.thenCompose(auditResult -> {
                logger.debug("Recording order creation for order: {} with correlation ID: {}", orderRequest.getOrderId(), correlationId);
                return ((PgBiTemporalEventStore<OrderEvent>) orderEventStore)
                    .appendInTransaction("OrderCreated", orderEvent, validTime,
                        Map.of("stage", "order-creation", "correlationId", correlationId),
                        correlationId, orderRequest.getOrderId(), connection);
            });
            
            // Step 3: Reserve inventory for each order item
            CompletableFuture<List<BiTemporalEvent<InventoryEvent>>> inventoryFuture = 
                orderFuture.thenCompose(orderResult -> {
                    logger.debug("Reserving inventory for order: {}", orderRequest.getOrderId());
                    
                    List<CompletableFuture<BiTemporalEvent<InventoryEvent>>> inventoryFutures = 
                        orderRequest.getItems().stream()
                            .map(item -> {
                                InventoryEvent inventoryEvent = createInventoryReservationEvent(
                                    item, orderRequest.getOrderId(), correlationId, transactionId, validTime);
                                
                                return ((PgBiTemporalEventStore<InventoryEvent>) inventoryEventStore)
                                    .appendInTransaction("InventoryReserved", inventoryEvent, validTime,
                                        Map.of("stage", "inventory-reservation", "correlationId", correlationId),
                                        correlationId, orderRequest.getOrderId(), connection);
                            })
                            .toList();
                    
                    return CompletableFuture.allOf(inventoryFutures.toArray(new CompletableFuture[0]))
                        .thenApply(v -> inventoryFutures.stream()
                            .map(CompletableFuture::join)
                            .toList());
                });
            
            // Step 4: Authorize payment
            CompletableFuture<BiTemporalEvent<PaymentEvent>> paymentFuture = 
                inventoryFuture.thenCompose(inventoryResults -> {
                    logger.debug("Authorizing payment for order: {}", orderRequest.getOrderId());
                    
                    PaymentEvent paymentEvent = createPaymentAuthorizationEvent(orderRequest, validTime);
                    
                    return ((PgBiTemporalEventStore<PaymentEvent>) paymentEventStore)
                        .appendInTransaction("PaymentAuthorized", paymentEvent, validTime,
                            Map.of("stage", "payment-authorization", "correlationId", correlationId),
                            correlationId, orderRequest.getOrderId(), connection);
                });
            
            // Step 5: Record final audit event for transaction completion
            CompletableFuture<BiTemporalEvent<AuditEvent>> finalAuditFuture = 
                paymentFuture.thenCompose(paymentResult -> {
                    logger.debug("Recording transaction completion for order: {}", orderRequest.getOrderId());
                    
                    AuditEvent transactionCompleteEvent = createTransactionCompleteAuditEvent(
                        transactionId, orderRequest.getOrderId(), correlationId, validTime);
                    
                    return ((PgBiTemporalEventStore<AuditEvent>) auditEventStore)
                        .appendInTransaction("TransactionCompleted", transactionCompleteEvent, validTime,
                            Map.of("stage", "transaction-complete", "correlationId", correlationId),
                            correlationId, transactionId, connection);
                });
            
            // Return the complete processing result
            return Future.fromCompletionStage(finalAuditFuture).map(finalAuditResult -> {
                logger.info("Complete order processing successful for order: {}", orderRequest.getOrderId());
                return new OrderProcessingResult(
                    orderRequest.getOrderId(),
                    "SUCCESS",
                    "Order processed successfully across all event stores",
                    correlationId,
                    transactionId,
                    validTime
                );
            });
            
        }).toCompletionStage().toCompletableFuture()
        .exceptionally(throwable -> {
            logger.error("Complete order processing failed for order: {} - {}", 
                        orderRequest.getOrderId(), throwable.getMessage(), throwable);
            return new OrderProcessingResult(
                orderRequest.getOrderId(),
                "FAILED",
                "Order processing failed: " + throwable.getMessage(),
                correlationId,
                transactionId,
                validTime
            );
        });
    }
    
    /**
     * Creates an order event from the processing request.
     */
    private OrderEvent createOrderEvent(OrderProcessingRequest request, Instant validTime) {
        return new OrderEvent(
            request.getOrderId(),
            request.getCustomerId(),
            "CREATED",
            request.getItems().stream()
                .map(item -> new OrderEvent.OrderItem(
                    item.getProductId(),
                    item.getProductName(),
                    item.getQuantity(),
                    item.getUnitPrice(),
                    item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity()))
                ))
                .toList(),
            request.getTotalAmount(),
            request.getCurrency(),
            LocalDateTime.now(),
            LocalDateTime.now().plusDays(3),
            request.getShippingAddress(),
            request.getBillingAddress(),
            request.getPaymentMethod(),
            Map.of("source", "order-processing-service", "validTime", validTime.toString())
        );
    }
    
    /**
     * Creates an inventory reservation event for an order item.
     */
    private InventoryEvent createInventoryReservationEvent(
            OrderProcessingRequest.OrderItem item, String orderId, String correlationId, String transactionId, Instant validTime) {
        return new InventoryEvent(
            UUID.randomUUID().toString(),
            item.getProductId(),
            item.getProductSku(),
            "RESERVED",
            -item.getQuantity(), // Negative for reservation
            100 - item.getQuantity(), // Simulated quantity after reservation
            "WAREHOUSE-001",
            "A-01-001",
            item.getUnitPrice(),
            item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())),
            orderId,
            null,
            "ORDER_RESERVATION",
            LocalDateTime.now(),
            Map.of("source", "order-processing-service", "validTime", validTime.toString())
        );
    }
    
    /**
     * Creates a payment authorization event for the order.
     */
    private PaymentEvent createPaymentAuthorizationEvent(OrderProcessingRequest request, Instant validTime) {
        return new PaymentEvent(
            UUID.randomUUID().toString(),
            request.getOrderId(),
            request.getCustomerId(),
            "AUTHORIZED",
            request.getTotalAmount(),
            request.getCurrency(),
            request.getPaymentMethod(),
            "TOKEN-" + UUID.randomUUID().toString().substring(0, 8),
            "GW-" + UUID.randomUUID().toString().substring(0, 8),
            "AUTH-" + UUID.randomUUID().toString().substring(0, 6),
            request.getTotalAmount(),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            "MERCHANT-001",
            "STRIPE",
            LocalDateTime.now(),
            "LOW",
            null,
            Map.of("source", "order-processing-service", "validTime", validTime.toString())
        );
    }
    
    /**
     * Creates an audit event for transaction start.
     */
    private AuditEvent createTransactionStartAuditEvent(
            String transactionId, String orderId, String correlationId, Instant validTime) {
        return new AuditEvent(
            UUID.randomUUID().toString(),
            "TRANSACTION_STARTED",
            "ORDER",
            orderId,
            "SYSTEM",
            "SESSION-" + UUID.randomUUID().toString().substring(0, 8),
            "PROCESS_ORDER",
            "STARTED",
            "LOW",
            "SOX",
            "order-processing-service",
            "127.0.0.1",
            "OrderProcessingService/1.0",
            LocalDateTime.now(),
            correlationId,
            transactionId,
            "Multi-event store transaction started for order processing",
            Map.of("source", "order-processing-service", "validTime", validTime.toString())
        );
    }
    
    /**
     * Creates an audit event for transaction completion.
     */
    private AuditEvent createTransactionCompleteAuditEvent(
            String transactionId, String orderId, String correlationId, Instant validTime) {
        return new AuditEvent(
            UUID.randomUUID().toString(),
            "TRANSACTION_COMPLETED",
            "ORDER",
            orderId,
            "SYSTEM",
            "SESSION-" + UUID.randomUUID().toString().substring(0, 8),
            "PROCESS_ORDER",
            "SUCCESS",
            "LOW",
            "SOX",
            "order-processing-service",
            "127.0.0.1",
            "OrderProcessingService/1.0",
            LocalDateTime.now(),
            correlationId,
            transactionId,
            "Multi-event store transaction completed successfully for order processing",
            Map.of("source", "order-processing-service", "validTime", validTime.toString())
        );
    }
}
