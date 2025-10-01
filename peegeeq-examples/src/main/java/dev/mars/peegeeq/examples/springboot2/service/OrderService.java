package dev.mars.peegeeq.examples.springboot2.service;

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
import dev.mars.peegeeq.examples.springboot2.events.InventoryReservedEvent;
import dev.mars.peegeeq.examples.springboot2.events.OrderCreatedEvent;
import dev.mars.peegeeq.examples.springboot2.events.OrderEvent;
import dev.mars.peegeeq.examples.springboot2.events.OrderValidatedEvent;
import dev.mars.peegeeq.examples.springboot2.adapter.ReactiveOutboxAdapter;
import dev.mars.peegeeq.examples.springboot2.model.Order;
import dev.mars.peegeeq.examples.springboot2.model.OrderItem;
import dev.mars.peegeeq.examples.springboot2.repository.OrderItemRepository;
import dev.mars.peegeeq.examples.springboot2.repository.OrderRepository;
import dev.mars.peegeeq.outbox.OutboxProducer;
import io.vertx.sqlclient.TransactionPropagation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Reactive service layer implementation for order management using PeeGeeQ Transactional Outbox Pattern.
 * 
 * This service uses Spring WebFlux reactive types (Mono/Flux) and integrates with PeeGeeQ's
 * CompletableFuture-based API through the ReactiveOutboxAdapter.
 * 
 * Key Features:
 * - Fully reactive operations using Mono and Flux
 * - PeeGeeQ-managed transactional consistency with automatic rollback
 * - Event-driven architecture with outbox pattern
 * - R2DBC for reactive database access
 * - Zero Vert.x exposure to application developers
 * - Comprehensive error handling and logging
 *
 * Transaction Management:
 * - Uses PeeGeeQ's TransactionPropagation.CONTEXT for Vert.x-based transactions
 * - Does NOT use Spring's @Transactional (would conflict with PeeGeeQ transactions)
 * - All operations within sendWithTransaction() participate in the same Vert.x transaction
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-01
 * @version 1.0
 */
@Service
public class OrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OutboxProducer<OrderEvent> orderEventProducer;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ReactiveOutboxAdapter adapter;

    public OrderService(OutboxProducer<OrderEvent> orderEventProducer,
                       OrderRepository orderRepository,
                       OrderItemRepository orderItemRepository,
                       ReactiveOutboxAdapter adapter) {
        this.orderEventProducer = orderEventProducer;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.adapter = adapter;
    }

    /**
     * Creates an order and publishes events using the transactional outbox pattern.
     * 
     * This method demonstrates the complete reactive transactional outbox pattern:
     * 1. Publish OrderCreatedEvent
     * 2. Save order to database using R2DBC
     * 3. Save order items to database
     * 4. Publish additional events (OrderValidated, InventoryReserved)
     * 5. All operations commit/rollback together
     * 
     * @param request The order creation request
     * @return Mono containing the created order ID
     */
    public Mono<String> createOrder(CreateOrderRequest request) {
        log.info("Creating order for customer: {}", request.getCustomerId());

        // Step 1: Publish OrderCreatedEvent using PeeGeeQ outbox
        return adapter.toMonoVoid(
            orderEventProducer.sendWithTransaction(
                new OrderCreatedEvent(request),
                TransactionPropagation.CONTEXT
            )
        )
        .then(Mono.defer(() -> {
            log.debug("Order created event published, proceeding with business logic");
            
            // Step 2: Create and save order entity using R2DBC
            Order order = new Order(request);
            return orderRepository.save(order);
        }))
        .flatMap(savedOrder -> {
            log.info("Order saved to database: {}", savedOrder.getId());
            
            // Step 3: Save order items
            return saveOrderItems(savedOrder)
                .thenReturn(savedOrder);
        })
        .flatMap(savedOrder -> {
            // Step 4: Publish additional events in the same transaction
            return publishOrderEvents(savedOrder, request)
                .thenReturn(savedOrder.getId());
        })
        .doOnSuccess(orderId -> log.info("Order created successfully: {}", orderId))
        .doOnError(error -> log.error("Order creation failed for customer {}: {}", 
            request.getCustomerId(), error.getMessage(), error))
        .onErrorMap(error -> new RuntimeException("Order creation failed", error));
    }

    /**
     * Saves order items to the database.
     * 
     * @param order The order containing items to save
     * @return Mono<Void> that completes when all items are saved
     */
    private Mono<Void> saveOrderItems(Order order) {
        if (order.getItems() == null || order.getItems().isEmpty()) {
            log.debug("No items to save for order: {}", order.getId());
            return Mono.empty();
        }

        log.debug("Saving {} items for order: {}", order.getItems().size(), order.getId());
        
        return Flux.fromIterable(order.getItems())
            .flatMap(item -> {
                item.setOrderId(order.getId());
                return orderItemRepository.save(item);
            })
            .then()
            .doOnSuccess(v -> log.debug("All items saved for order: {}", order.getId()));
    }

    /**
     * Publishes order validation and inventory reservation events.
     * 
     * @param order The saved order
     * @param request The original request
     * @return Mono<Void> that completes when all events are published
     */
    private Mono<Void> publishOrderEvents(Order order, CreateOrderRequest request) {
        log.debug("Publishing additional events for order: {}", order.getId());
        
        // Publish OrderValidatedEvent and InventoryReservedEvent in parallel
        Mono<Void> validatedEvent = adapter.toMonoVoid(
            orderEventProducer.sendWithTransaction(
                new OrderValidatedEvent(order.getId()),
                TransactionPropagation.CONTEXT
            )
        );
        
        Mono<Void> inventoryEvent = adapter.toMonoVoid(
            orderEventProducer.sendWithTransaction(
                new InventoryReservedEvent(order.getId(), request.getItems()),
                TransactionPropagation.CONTEXT
            )
        );
        
        return Mono.when(validatedEvent, inventoryEvent)
            .doOnSuccess(v -> log.info("All order events published successfully for order: {}", order.getId()));
    }

    /**
     * Creates an order with business validation that may trigger rollback.
     *
     * This demonstrates transactional rollback scenarios:
     * - If amount > $10,000: Business validation fails, entire transaction rolls back
     * - If customerId = "INVALID_CUSTOMER": Customer validation fails, transaction rolls back
     * - Otherwise: Order and events are committed together
     *
     * @param request The order creation request
     * @return Mono containing the created order ID
     */
    public Mono<String> createOrderWithValidation(CreateOrderRequest request) {
        log.info("Creating order with business validation for customer: {}", request.getCustomerId());

        return adapter.toMonoVoid(
            orderEventProducer.sendWithTransaction(
                new OrderCreatedEvent(request),
                TransactionPropagation.CONTEXT
            )
        )
        .then(Mono.defer(() -> {
            log.debug("Order created event published, proceeding with business logic");

            // Business validation that might fail
            if (request.getAmount().compareTo(new BigDecimal("10000")) > 0) {
                log.info("🧪 INTENTIONAL TEST FAILURE: Order amount {} exceeds maximum limit of $10,000 (THIS IS EXPECTED)", request.getAmount());
                return Mono.error(new RuntimeException("🧪 INTENTIONAL TEST FAILURE: Order amount exceeds maximum limit of $10,000"));
            }

            if (request.getCustomerId().equals("INVALID_CUSTOMER")) {
                log.info("🧪 INTENTIONAL TEST FAILURE: Invalid customer ID: {} (THIS IS EXPECTED)", request.getCustomerId());
                return Mono.error(new RuntimeException("🧪 INTENTIONAL TEST FAILURE: Invalid customer ID: " + request.getCustomerId()));
            }

            // Create and save order entity using R2DBC
            Order order = new Order(request);
            return orderRepository.save(order);
        }))
        .flatMap(savedOrder -> {
            log.info("Order saved to database: {}", savedOrder.getId());
            return saveOrderItems(savedOrder).thenReturn(savedOrder.getId());
        })
        .doOnSuccess(orderId -> log.info("✅ TRANSACTION SUCCESS: Order {} created and committed with all events", orderId))
        .doOnError(error -> {
            String errorMessage = error.getMessage();
            if (errorMessage != null && errorMessage.contains("🧪 INTENTIONAL TEST FAILURE:")) {
                log.info("❌ TRANSACTION ROLLBACK: Order creation with business validation failed for customer {}: {}",
                    request.getCustomerId(), errorMessage);
            } else {
                log.error("❌ TRANSACTION ROLLBACK: Order creation with business validation failed for customer {}: {}",
                    request.getCustomerId(), errorMessage, error);
            }
        })
        .onErrorMap(error -> new RuntimeException("Order creation failed: " + error.getMessage(), error));
    }

    /**
     * Creates an order with database constraints that may trigger rollback.
     *
     * This demonstrates database-level rollback scenarios:
     * - If customerId = "DUPLICATE_ORDER": Database constraint violation, transaction rolls back
     * - Otherwise: Order and events are committed together
     *
     * @param request The order creation request
     * @return Mono containing the created order ID
     */
    public Mono<String> createOrderWithDatabaseConstraints(CreateOrderRequest request) {
        log.info("Creating order with database constraints for customer: {}", request.getCustomerId());

        return adapter.toMonoVoid(
            orderEventProducer.sendWithTransaction(
                new OrderCreatedEvent(request),
                TransactionPropagation.CONTEXT
            )
        )
        .then(Mono.defer(() -> {
            log.debug("Order created event published, proceeding with database operations");

            // Simulate database constraint violation
            if (request.getCustomerId().equals("DUPLICATE_ORDER")) {
                log.info("🧪 INTENTIONAL TEST FAILURE: Simulating database constraint violation for customer: {} (THIS IS EXPECTED)", request.getCustomerId());
                return Mono.error(new RuntimeException("🧪 INTENTIONAL TEST FAILURE: Database constraint violation: Duplicate order ID"));
            }

            // Create and save order entity using R2DBC
            Order order = new Order(request);
            return orderRepository.save(order);
        }))
        .flatMap(savedOrder -> {
            log.info("Order saved to database: {}", savedOrder.getId());
            return saveOrderItems(savedOrder).thenReturn(savedOrder.getId());
        })
        .doOnSuccess(orderId -> log.info("✅ TRANSACTION SUCCESS: Order {} created and committed with database constraints", orderId))
        .doOnError(error -> {
            String errorMessage = error.getMessage();
            if (errorMessage != null && errorMessage.contains("🧪 INTENTIONAL TEST FAILURE:")) {
                log.info("❌ TRANSACTION ROLLBACK: Order creation with database constraints failed for customer {}: {}",
                    request.getCustomerId(), errorMessage);
            } else {
                log.error("❌ TRANSACTION ROLLBACK: Order creation with database constraints failed for customer {}: {}",
                    request.getCustomerId(), errorMessage, error);
            }
        })
        .onErrorMap(error -> new RuntimeException("Database operation failed: " + error.getMessage(), error));
    }

    /**
     * Creates an order with multiple events to demonstrate successful transaction.
     *
     * This demonstrates successful multi-event transactions:
     * - Creates order record in database
     * - Publishes OrderCreatedEvent to outbox
     * - Publishes OrderValidatedEvent to outbox
     * - Publishes InventoryReservedEvent to outbox
     * - All operations commit together or all roll back together
     *
     * @param request The order creation request
     * @return Mono containing the created order ID
     */
    public Mono<String> createOrderWithMultipleEvents(CreateOrderRequest request) {
        log.info("Creating order with multiple events for customer: {}", request.getCustomerId());

        return adapter.toMonoVoid(
            orderEventProducer.sendWithTransaction(
                new OrderCreatedEvent(request),
                TransactionPropagation.CONTEXT
            )
        )
        .then(Mono.defer(() -> {
            log.debug("Order created event published, proceeding with business logic");

            // Create and save order entity using R2DBC
            Order order = new Order(request);
            return orderRepository.save(order);
        }))
        .flatMap(savedOrder -> {
            log.info("Order saved to database: {}", savedOrder.getId());

            // Save order items
            return saveOrderItems(savedOrder)
                .thenReturn(savedOrder);
        })
        .flatMap(savedOrder -> {
            // Publish multiple additional events in the same transaction
            return publishOrderEvents(savedOrder, request)
                .thenReturn(savedOrder.getId());
        })
        .doOnSuccess(orderId -> {
            log.info("All order events published successfully for order: {}", orderId);
            log.info("✅ TRANSACTION SUCCESS: Order {} and all events committed together", orderId);
        })
        .doOnError(error -> {
            log.error("❌ TRANSACTION ROLLBACK: Order creation with multiple events failed for customer {}: {}",
                request.getCustomerId(), error.getMessage(), error);
            log.error("❌ TRANSACTION ROLLBACK: All operations rolled back due to failure");
        })
        .onErrorMap(error -> new RuntimeException("Order creation failed", error));
    }

    /**
     * Finds an order by ID and loads its items.
     * 
     * @param orderId The order ID
     * @return Mono containing the order with items, or empty if not found
     */
    public Mono<Order> findById(String orderId) {
        log.debug("Finding order by ID: {}", orderId);
        
        return orderRepository.findById(orderId)
            .flatMap(order -> 
                orderItemRepository.findByOrderId(orderId)
                    .collectList()
                    .map(items -> {
                        order.setItems(items);
                        return order;
                    })
            )
            .doOnSuccess(order -> log.debug("Order found: {}", orderId))
            .doOnError(error -> log.error("Error finding order {}: {}", orderId, error.getMessage()));
    }

    /**
     * Finds orders by customer ID.
     *
     * @param customerId The customer ID
     * @return Mono of order for the customer (single order per customer in this example)
     */
    public Mono<Order> findByCustomerId(String customerId) {
        log.debug("Finding orders for customer: {}", customerId);

        return orderRepository.findByCustomerId(customerId)
            .flatMap(order ->
                orderItemRepository.findByOrderId(order.getId())
                    .collectList()
                    .map(items -> {
                        order.setItems(items);
                        return order;
                    })
            );
    }

    /**
     * Streams recent orders created after a specific timestamp.
     * 
     * @param since The timestamp to filter from
     * @return Flux of recent orders
     */
    public Flux<Order> streamRecentOrders(Instant since) {
        log.debug("Streaming orders created after: {}", since);
        
        return orderRepository.findRecentOrders(since)
            .flatMap(order -> 
                orderItemRepository.findByOrderId(order.getId())
                    .collectList()
                    .map(items -> {
                        order.setItems(items);
                        return order;
                    })
            );
    }

    /**
     * Validates an existing order.
     * 
     * @param orderId The order ID to validate
     * @return Mono<Void> that completes when validation is done
     */
    public Mono<Void> validateOrder(String orderId) {
        log.info("Validating order: {}", orderId);
        
        return orderRepository.findById(orderId)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Order not found: " + orderId)))
            .flatMap(order -> {
                // Perform validation logic
                order.validate();
                return orderRepository.save(order);
            })
            .flatMap(order -> 
                adapter.toMonoVoid(
                    orderEventProducer.sendWithTransaction(
                        new OrderValidatedEvent(order.getId()),
                        TransactionPropagation.CONTEXT
                    )
                )
            )
            .doOnSuccess(v -> log.info("Order validated successfully: {}", orderId))
            .doOnError(error -> log.error("Order validation failed for {}: {}", orderId, error.getMessage()));
    }

    /**
     * Publishes a single order event.
     * 
     * @param event The order event to publish
     * @return Mono<Void> that completes when the event is published
     */
    public Mono<Void> publishOrderEvent(OrderEvent event) {
        log.info("Publishing order event: {}", event.getClass().getSimpleName());
        
        return adapter.toMonoVoid(orderEventProducer.send(event))
            .doOnSuccess(v -> log.info("Order event published successfully: {}", event.getClass().getSimpleName()))
            .doOnError(error -> log.error("Failed to publish order event {}: {}", 
                event.getClass().getSimpleName(), error.getMessage(), error));
    }
}

