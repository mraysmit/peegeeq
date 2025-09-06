package dev.mars.peegeeq.examples.springboot.service;

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

import dev.mars.peegeeq.examples.springboot.events.InventoryReservedEvent;
import dev.mars.peegeeq.examples.springboot.events.OrderCreatedEvent;
import dev.mars.peegeeq.examples.springboot.events.OrderEvent;
import dev.mars.peegeeq.examples.springboot.events.OrderValidatedEvent;
import dev.mars.peegeeq.examples.springboot.model.CreateOrderRequest;
import dev.mars.peegeeq.examples.springboot.model.Order;
import dev.mars.peegeeq.examples.springboot.repository.OrderRepository;
import dev.mars.peegeeq.outbox.OutboxProducer;
import io.vertx.sqlclient.TransactionPropagation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * Service layer implementation for order management using PeeGeeQ Transactional Outbox Pattern.
 * 
 * This service follows the patterns outlined in the PeeGeeQ Transactional Outbox Patterns Guide
 * and demonstrates proper service layer design for Spring Boot applications.
 * 
 * Key Features:
 * - PeeGeeQ-managed transactional consistency with automatic rollback
 * - Event-driven architecture with outbox pattern
 * - Reactive operations abstracted behind Spring Boot patterns
 * - Zero Vert.x exposure to application developers
 * - Comprehensive error handling and logging
 *
 * Transaction Management:
 * - Uses PeeGeeQ's TransactionPropagation.CONTEXT for Vert.x-based transactions
 * - Does NOT use Spring's @Transactional (would conflict with PeeGeeQ transactions)
 * - All operations within sendWithTransaction() participate in the same Vert.x transaction
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-06
 * @version 1.0
 */
@Service
public class OrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OutboxProducer<OrderEvent> orderEventProducer;
    private final OrderRepository orderRepository;

    public OrderService(OutboxProducer<OrderEvent> orderEventProducer,
                       OrderRepository orderRepository) {
        this.orderEventProducer = orderEventProducer;
        this.orderRepository = orderRepository;
    }

    /**
     * Creates an order and publishes events using the transactional outbox pattern.
     * The reactive operations are handled internally by PeeGeeQ.
     * 
     * This method demonstrates the complete transactional outbox pattern:
     * 1. Create and save the order entity
     * 2. Publish multiple events within the same transaction
     * 3. All operations commit/rollback together
     * 
     * @param request The order creation request
     * @return CompletableFuture containing the created order ID
     */
    public CompletableFuture<String> createOrder(CreateOrderRequest request) {
        log.info("Creating order for customer: {}", request.getCustomerId());

        return orderEventProducer.sendWithTransaction(
            new OrderCreatedEvent(request),
            TransactionPropagation.CONTEXT  // Uses Vert.x context internally
        )
        .thenCompose(v -> {
            log.debug("Order created event published, proceeding with business logic");
            
            // Business logic - save order to database
            Order order = new Order(request);
            Order savedOrder = orderRepository.save(order);
            log.info("Order saved to database: {}", savedOrder.getId());

            // Send additional events in the same transaction
            return CompletableFuture.allOf(
                orderEventProducer.sendWithTransaction(
                    new OrderValidatedEvent(savedOrder.getId()),
                    TransactionPropagation.CONTEXT
                ),
                orderEventProducer.sendWithTransaction(
                    new InventoryReservedEvent(savedOrder.getId(), request.getItems()),
                    TransactionPropagation.CONTEXT
                )
            ).thenApply(ignored -> {
                log.info("All order events published successfully for order: {}", savedOrder.getId());
                return savedOrder.getId();
            });
        })
        .exceptionally(error -> {
            log.error("Order creation failed for customer {}: {}", request.getCustomerId(), error.getMessage(), error);
            throw new RuntimeException("Order creation failed", error);
        });
    }

    /**
     * Alternative approach using the basic reactive method.
     * This demonstrates the first reactive approach from the guide.
     * 
     * @param event The order event to publish
     * @return CompletableFuture for the publish operation
     */
    public CompletableFuture<Void> publishOrderEvent(OrderEvent event) {
        log.info("Publishing order event: {}", event.getClass().getSimpleName());
        
        return orderEventProducer.send(event)
            .whenComplete((result, error) -> {
                if (error != null) {
                    log.error("Failed to publish order event {}: {}", event.getClass().getSimpleName(), error.getMessage(), error);
                } else {
                    log.info("Order event published successfully: {}", event.getClass().getSimpleName());
                }
            });
    }

    /**
     * Demonstrates transaction propagation with existing transaction context.
     * This shows how to use the third reactive approach from the guide.
     * 
     * @param orderId The order ID to validate
     * @return CompletableFuture for the validation operation
     */
    public CompletableFuture<Void> validateOrder(String orderId) {
        log.info("Validating order: {}", orderId);
        
        // Simulate validation logic
        boolean isValid = performOrderValidation(orderId);
        
        OrderValidatedEvent event = new OrderValidatedEvent(orderId, isValid, 
            isValid ? "Order validation successful" : "Order validation failed");
        
        return orderEventProducer.sendWithTransaction(event, TransactionPropagation.CONTEXT)
            .whenComplete((result, error) -> {
                if (error != null) {
                    log.error("Failed to publish order validation event for order {}: {}", orderId, error.getMessage(), error);
                } else {
                    log.info("Order validation event published for order: {}", orderId);
                }
            });
    }

    /**
     * Demonstrates transactional rollback when business validation fails.
     * This method shows that if business logic fails AFTER the outbox event is sent,
     * the entire transaction (including the outbox event) will be rolled back.
     *
     * @param request The order creation request
     * @return CompletableFuture containing the created order ID
     */
    public CompletableFuture<String> createOrderWithBusinessValidation(CreateOrderRequest request) {
        log.info("Creating order with business validation for customer: {}", request.getCustomerId());

        return orderEventProducer.sendWithTransaction(
            new OrderCreatedEvent(request),
            TransactionPropagation.CONTEXT
        )
        .thenCompose(v -> {
            log.debug("Order created event published, proceeding with business logic");

            // Business logic - save order to database
            Order order = new Order(request);
            Order savedOrder = orderRepository.save(order);
            log.info("Order saved to database: {}", savedOrder.getId());

            // Simulate business validation that might fail
            if (request.getAmount().compareTo(new java.math.BigDecimal("10000")) > 0) {
                log.error("Order amount {} exceeds maximum limit of $10,000", request.getAmount());
                // This will cause the entire transaction to rollback
                // Both the database record AND the outbox event will be rolled back
                throw new RuntimeException("Order amount exceeds maximum limit of $10,000");
            }

            // Simulate inventory check that might fail
            if (request.getCustomerId().equals("INVALID_CUSTOMER")) {
                log.error("Invalid customer ID: {}", request.getCustomerId());
                // This will also cause complete transaction rollback
                throw new RuntimeException("Invalid customer ID: " + request.getCustomerId());
            }

            return CompletableFuture.completedFuture(savedOrder.getId());
        })
        .exceptionally(error -> {
            log.error("Order creation with business validation failed for customer {}: {}",
                request.getCustomerId(), error.getMessage(), error);
            // The transaction has already been rolled back automatically
            // Both the order record and outbox event are gone
            throw new RuntimeException("Order creation failed: " + error.getMessage(), error);
        });
    }

    /**
     * Demonstrates transactional rollback when database constraint violations occur.
     * This method shows that database-level failures also trigger complete rollback.
     *
     * @param request The order creation request
     * @return CompletableFuture containing the created order ID
     */
    public CompletableFuture<String> createOrderWithDatabaseConstraints(CreateOrderRequest request) {
        log.info("Creating order with database constraints for customer: {}", request.getCustomerId());

        return orderEventProducer.sendWithTransaction(
            new OrderCreatedEvent(request),
            TransactionPropagation.CONTEXT
        )
        .thenCompose(v -> {
            log.debug("Order created event published, proceeding with database operations");

            // Simulate database constraint violation
            if (request.getCustomerId().equals("DUPLICATE_ORDER")) {
                log.error("Simulating database constraint violation for customer: {}", request.getCustomerId());
                // This simulates a database constraint violation (e.g., duplicate key)
                // In a real scenario, this would be thrown by the database
                throw new RuntimeException("Database constraint violation: Duplicate order ID");
            }

            // Business logic - save order to database
            Order order = new Order(request);
            Order savedOrder = orderRepository.save(order);
            log.info("Order saved to database: {}", savedOrder.getId());

            return CompletableFuture.completedFuture(savedOrder.getId());
        })
        .exceptionally(error -> {
            log.error("Order creation with database constraints failed for customer {}: {}",
                request.getCustomerId(), error.getMessage(), error);
            // The transaction has been automatically rolled back
            // No partial data exists in either the database or outbox
            throw new RuntimeException("Database operation failed: " + error.getMessage(), error);
        });
    }

    /**
     * Demonstrates successful transaction with multiple events.
     * This method shows that when everything succeeds, all operations are committed together.
     *
     * @param request The order creation request
     * @return CompletableFuture containing the created order ID
     */
    public CompletableFuture<String> createOrderWithMultipleEvents(CreateOrderRequest request) {
        log.info("Creating order with multiple events for customer: {}", request.getCustomerId());

        return orderEventProducer.sendWithTransaction(
            new OrderCreatedEvent(request),
            TransactionPropagation.CONTEXT
        )
        .thenCompose(v -> {
            log.debug("Order created event published, proceeding with business logic");

            // Business logic - save order to database
            Order order = new Order(request);
            Order savedOrder = orderRepository.save(order);
            log.info("Order saved to database: {}", savedOrder.getId());

            // Send multiple additional events in the same transaction
            return CompletableFuture.allOf(
                orderEventProducer.sendWithTransaction(
                    new OrderValidatedEvent(savedOrder.getId()),
                    TransactionPropagation.CONTEXT
                ),
                orderEventProducer.sendWithTransaction(
                    new InventoryReservedEvent(savedOrder.getId(), request.getItems()),
                    TransactionPropagation.CONTEXT
                )
            ).thenApply(ignored -> {
                log.info("All order events published successfully for order: {}", savedOrder.getId());
                log.info("✅ TRANSACTION SUCCESS: Order {} and all events committed together", savedOrder.getId());
                return savedOrder.getId();
            });
        })
        .exceptionally(error -> {
            log.error("Order creation with multiple events failed for customer {}: {}",
                request.getCustomerId(), error.getMessage(), error);
            log.error("❌ TRANSACTION ROLLBACK: All operations rolled back due to failure");
            throw new RuntimeException("Order creation failed", error);
        });
    }

    /**
     * Simulates order validation logic.
     * In a real application, this would perform actual business validation.
     *
     * @param orderId The order ID to validate
     * @return true if the order is valid, false otherwise
     */
    private boolean performOrderValidation(String orderId) {
        // Simulate validation logic
        log.debug("Performing validation for order: {}", orderId);
        return true; // Always valid for demo purposes
    }
}
