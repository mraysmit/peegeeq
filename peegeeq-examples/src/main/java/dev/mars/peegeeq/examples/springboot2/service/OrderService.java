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

import dev.mars.peegeeq.api.database.ConnectionProvider;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.examples.springboot.model.CreateOrderRequest;
import dev.mars.peegeeq.examples.springboot2.events.InventoryReservedEvent;
import dev.mars.peegeeq.examples.springboot2.events.OrderCreatedEvent;
import dev.mars.peegeeq.examples.springboot2.events.OrderEvent;
import dev.mars.peegeeq.examples.springboot2.events.OrderValidatedEvent;
import dev.mars.peegeeq.examples.springboot2.adapter.ReactiveOutboxAdapter;
import dev.mars.peegeeq.examples.springboot2.model.Order;
import dev.mars.peegeeq.examples.springboot2.repository.OrderItemRepository;
import dev.mars.peegeeq.examples.springboot2.repository.OrderRepository;
import dev.mars.peegeeq.outbox.OutboxProducer;
import io.vertx.core.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;

/**
 * Reactive service layer implementation for order management using PeeGeeQ Transactional Outbox Pattern.
 *
 * This service demonstrates the CORRECT pattern for transactional consistency in reactive Spring Boot:
 * - Uses DatabaseService and ConnectionProvider (PeeGeeQ's public API)
 * - Uses ConnectionProvider.withTransaction() to create a single transaction
 * - Uses sendInTransaction() to include outbox events in the same transaction
 * - All database operations (orders, order_items, outbox) share the same connection
 * - Rollback affects ALL operations together
 * - Wraps CompletableFuture results in Mono for reactive Spring Boot compatibility
 *
 * Key Features:
 * - True transactional consistency with PeeGeeQ's API
 * - Reactive Spring Boot integration via Mono/Flux wrappers
 * - Event-driven architecture with outbox pattern
 * - Comprehensive error handling and logging
 * - Database constraint violation simulation for testing
 *
 * Transaction Management:
 * - Uses ConnectionProvider.withTransaction(clientId, connection -> {...}) for transaction boundaries
 * - Does NOT use Spring's @Transactional (would conflict with Vert.x transactions)
 * - All operations within the same connection participate in the same transaction
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-01
 * @version 2.0
 */
@Service
public class OrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final String CLIENT_ID = "peegeeq-main";

    private final DatabaseService databaseService;
    private final OutboxProducer<OrderEvent> orderEventProducer;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ReactiveOutboxAdapter adapter;

    public OrderService(DatabaseService databaseService,
                       OutboxProducer<OrderEvent> orderEventProducer,
                       OrderRepository orderRepository,
                       OrderItemRepository orderItemRepository,
                       ReactiveOutboxAdapter adapter) {
        this.databaseService = databaseService;
        this.orderEventProducer = orderEventProducer;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.adapter = adapter;
    }

    /**
     * Creates an order and publishes events using the transactional outbox pattern.
     *
     * This method demonstrates the CORRECT reactive transactional outbox pattern:
     * 1. Get ConnectionProvider from DatabaseService
     * 2. Create a single transaction with ConnectionProvider.withTransaction()
     * 3. Send outbox event using sendInTransaction() with the connection
     * 4. Save order to database using the same connection
     * 5. Save order items to database using the same connection
     * 6. All operations commit/rollback together
     * 7. Wrap CompletableFuture in Mono for reactive Spring Boot
     *
     * @param request The order creation request
     * @return Mono containing the created order ID
     */
    public Mono<String> createOrder(CreateOrderRequest request) {
        log.info("Creating order for customer: {}", request.getCustomerId());

        ConnectionProvider connectionProvider = databaseService.getConnectionProvider();

        return adapter.toMono(
            connectionProvider.withTransaction(CLIENT_ID, connection -> {
                Order order = new Order(request);
                String orderId = order.getId();

                // Step 1: Send outbox event using sendInTransaction()
                return Future.fromCompletionStage(
                    orderEventProducer.sendInTransaction(
                        new OrderCreatedEvent(request),
                        connection
                    )
                )
                .compose(v -> {
                    log.debug("Order created event sent, saving order to database");
                    // Step 2: Save order using same connection
                    return orderRepository.save(order, connection);
                })
                .compose(savedOrder -> {
                    log.debug("Order saved, saving order items");
                    // Step 3: Save order items using same connection
                    // Convert springboot.model.OrderItem to springboot2.model.OrderItem
                    java.util.List<dev.mars.peegeeq.examples.springboot2.model.OrderItem> items =
                        request.getItems().stream()
                            .map(item -> new dev.mars.peegeeq.examples.springboot2.model.OrderItem(orderId, item))
                            .collect(java.util.stream.Collectors.toList());
                    return orderItemRepository.saveAll(orderId, items, connection)
                        .map(v -> orderId);
                })
                .compose(id -> {
                    // Step 4: Send additional events using same connection
                    return Future.fromCompletionStage(
                        orderEventProducer.sendInTransaction(
                            new OrderValidatedEvent(id),
                            connection
                        )
                    ).compose(v ->
                        Future.fromCompletionStage(
                            orderEventProducer.sendInTransaction(
                                new InventoryReservedEvent(id, request.getItems()),
                                connection
                            )
                        )
                    ).map(v -> id);
                })
                .onSuccess(id -> log.info("✅ Order {} created successfully with all events", id))
                .onFailure(error -> log.error("❌ Order creation failed, transaction will rollback: {}", error.getMessage()));

            }).toCompletionStage().toCompletableFuture()
        );
    }

    /**
     * Alternative approach using the basic reactive method.
     * This demonstrates publishing a single event without transaction coordination.
     *
     * @param event The order event to publish
     * @return Mono for the publish operation
     */
    public Mono<Void> publishOrderEvent(OrderEvent event) {
        log.info("Publishing order event: {}", event.getClass().getSimpleName());

        return adapter.toMonoVoid(orderEventProducer.send(event))
            .doOnSuccess(v -> log.info("Order event published successfully: {}", event.getClass().getSimpleName()))
            .doOnError(error -> log.error("Failed to publish order event {}: {}",
                event.getClass().getSimpleName(), error.getMessage(), error));
    }

    /**
     * Demonstrates transactional rollback when business validation fails.
     * This method shows that if business logic fails AFTER the outbox event is sent,
     * the entire transaction (including the outbox event) will be rolled back.
     *
     * @param request The order creation request
     * @return Mono containing the created order ID
     */
    public Mono<String> createOrderWithBusinessValidation(CreateOrderRequest request) {
        log.info("Creating order with business validation for customer: {}", request.getCustomerId());

        ConnectionProvider connectionProvider = databaseService.getConnectionProvider();

        return adapter.toMono(
            connectionProvider.withTransaction(CLIENT_ID, connection -> {
                Order order = new Order(request);
                String orderId = order.getId();

                // Step 1: Send outbox event
                return Future.fromCompletionStage(
                    orderEventProducer.sendInTransaction(
                        new OrderCreatedEvent(request),
                        connection
                    )
                )
                .compose(v -> {
                    log.debug("Order created event sent, performing business validation");

                    // Business validation that might fail
                    if (request.getAmount().compareTo(new BigDecimal("10000")) > 0) {
                        log.info("🧪 INTENTIONAL TEST FAILURE: Order amount {} exceeds maximum limit of $10,000 (THIS IS EXPECTED)", request.getAmount());
                        return Future.failedFuture(
                            new RuntimeException("🧪 INTENTIONAL TEST FAILURE: Order amount exceeds maximum limit of $10,000"));
                    }

                    if (request.getCustomerId().equals("INVALID_CUSTOMER")) {
                        log.info("🧪 INTENTIONAL TEST FAILURE: Invalid customer ID: {} (THIS IS EXPECTED)", request.getCustomerId());
                        return Future.failedFuture(
                            new RuntimeException("🧪 INTENTIONAL TEST FAILURE: Invalid customer ID: " + request.getCustomerId()));
                    }

                    // Step 2: Save order
                    return orderRepository.save(order, connection);
                })
                .compose(savedOrder -> {
                    // Step 3: Save order items
                    // Convert springboot.model.OrderItem to springboot2.model.OrderItem
                    java.util.List<dev.mars.peegeeq.examples.springboot2.model.OrderItem> items =
                        request.getItems().stream()
                            .map(item -> new dev.mars.peegeeq.examples.springboot2.model.OrderItem(orderId, item))
                            .collect(java.util.stream.Collectors.toList());
                    return orderItemRepository.saveAll(orderId, items, connection)
                        .map(v -> orderId);
                })
                .onSuccess(id -> log.info("✅ Order {} created successfully with business validation", id))
                .onFailure(error -> {
                    String errorMessage = error.getMessage();
                    if (errorMessage != null && errorMessage.contains("🧪 INTENTIONAL TEST FAILURE:")) {
                        log.info("❌ TRANSACTION ROLLBACK: {}", errorMessage);
                    } else {
                        log.error("❌ TRANSACTION ROLLBACK: Order creation failed: {}", errorMessage);
                    }
                });

            }).toCompletionStage().toCompletableFuture()
        );
    }

    /**
     * Demonstrates transactional rollback when database constraint violations occur.
     * This method shows that database-level failures also trigger complete rollback.
     *
     * @param request The order creation request
     * @return Mono containing the created order ID
     */
    public Mono<String> createOrderWithDatabaseConstraints(CreateOrderRequest request) {
        log.info("Creating order with database constraints for customer: {}", request.getCustomerId());

        ConnectionProvider connectionProvider = databaseService.getConnectionProvider();

        return adapter.toMono(
            connectionProvider.withTransaction(CLIENT_ID, connection -> {
                Order order = new Order(request);
                String orderId = order.getId();

                // Step 1: Send outbox event
                return Future.fromCompletionStage(
                    orderEventProducer.sendInTransaction(
                        new OrderCreatedEvent(request),
                        connection
                    )
                )
                .compose(v -> {
                    log.debug("Order created event sent, saving order to database");
                    // Step 2: Save order (this will fail for certain customer IDs)
                    return orderRepository.save(order, connection);
                })
                .compose(savedOrder -> {
                    // Step 3: Save order items
                    // Convert springboot.model.OrderItem to springboot2.model.OrderItem
                    java.util.List<dev.mars.peegeeq.examples.springboot2.model.OrderItem> items =
                        request.getItems().stream()
                            .map(item -> new dev.mars.peegeeq.examples.springboot2.model.OrderItem(orderId, item))
                            .collect(java.util.stream.Collectors.toList());
                    return orderItemRepository.saveAll(orderId, items, connection)
                        .map(v -> orderId);
                })
                .onSuccess(id -> log.info("✅ Order {} created successfully with database constraints", id))
                .onFailure(error -> {
                    String errorMessage = error.getMessage();
                    if (errorMessage != null && errorMessage.contains("🧪 INTENTIONAL TEST FAILURE:")) {
                        log.info("❌ TRANSACTION ROLLBACK: {}", errorMessage);
                    } else if (errorMessage != null && errorMessage.contains("Database constraint violation")) {
                        log.info("❌ TRANSACTION ROLLBACK: {}", errorMessage);
                    } else {
                        log.error("❌ TRANSACTION ROLLBACK: Database operation failed: {}", errorMessage);
                    }
                });

            }).toCompletionStage().toCompletableFuture()
        );
    }

    /**
     * Demonstrates successful transaction with multiple events.
     * This method shows that when everything succeeds, all operations are committed together.
     *
     * @param request The order creation request
     * @return Mono containing the created order ID
     */
    public Mono<String> createOrderWithMultipleEvents(CreateOrderRequest request) {
        log.info("Creating order with multiple events for customer: {}", request.getCustomerId());

        ConnectionProvider connectionProvider = databaseService.getConnectionProvider();

        return adapter.toMono(
            connectionProvider.withTransaction(CLIENT_ID, connection -> {
                Order order = new Order(request);
                String orderId = order.getId();

                // All operations in the same transaction
                return Future.fromCompletionStage(
                    orderEventProducer.sendInTransaction(new OrderCreatedEvent(request), connection)
                )
                .compose(v -> orderRepository.save(order, connection))
                .compose(savedOrder -> {
                    // Convert springboot.model.OrderItem to springboot2.model.OrderItem
                    java.util.List<dev.mars.peegeeq.examples.springboot2.model.OrderItem> items =
                        request.getItems().stream()
                            .map(item -> new dev.mars.peegeeq.examples.springboot2.model.OrderItem(orderId, item))
                            .collect(java.util.stream.Collectors.toList());
                    return orderItemRepository.saveAll(orderId, items, connection);
                })
                .compose(v -> Future.fromCompletionStage(
                    orderEventProducer.sendInTransaction(new OrderValidatedEvent(orderId), connection)
                ))
                .compose(v -> Future.fromCompletionStage(
                    orderEventProducer.sendInTransaction(new InventoryReservedEvent(orderId, request.getItems()), connection)
                ))
                .map(v -> orderId)
                .onSuccess(id -> log.info("✅ TRANSACTION SUCCESS: Order {} and all events committed together", id))
                .onFailure(error -> log.error("❌ TRANSACTION ROLLBACK: All operations rolled back due to failure: {}", error.getMessage()));

            }).toCompletionStage().toCompletableFuture()
        );
    }

}

