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

package dev.mars.peegeeq.examples.springbootintegrated.service;

import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.api.EventQuery;
import dev.mars.peegeeq.api.EventStore;
import dev.mars.peegeeq.api.database.ConnectionProvider;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.outbox.OutboxProducer;
import dev.mars.peegeeq.examples.springbootintegrated.events.OrderEvent;
import dev.mars.peegeeq.examples.springbootintegrated.model.CreateOrderRequest;
import dev.mars.peegeeq.examples.springbootintegrated.model.Order;
import dev.mars.peegeeq.examples.springbootintegrated.model.OrderResponse;
import dev.mars.peegeeq.examples.springbootintegrated.repository.OrderRepository;
import io.vertx.core.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Order service demonstrating integrated outbox + bi-temporal pattern.
 * 
 * <p>This service shows the CORRECT way to coordinate:
 * <ul>
 *   <li><b>Database operations</b> - Save order data</li>
 *   <li><b>Outbox events</b> - For immediate real-time processing</li>
 *   <li><b>Bi-temporal events</b> - For historical queries and audit trail</li>
 * </ul>
 * 
 * <p>All three operations participate in a SINGLE database transaction using
 * ConnectionProvider.withTransaction() and passing the SAME SqlConnection to
 * all operations.
 * 
 * <h3>Key Pattern:</h3>
 * <pre>
 * connectionProvider.withTransaction("client-id", connection -> {
 *     // 1. Save to database (connection)
 *     // 2. Send to outbox (connection)
 *     // 3. Append to event store (connection)
 *     // All use SAME connection = SAME transaction
 * });
 * </pre>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-07
 * @version 1.0
 */
@Service
public class OrderService {
    
    private static final Logger logger = LoggerFactory.getLogger(OrderService.class);
    
    private final DatabaseService databaseService;
    private final OrderRepository orderRepository;
    private final OutboxProducer<OrderEvent> orderEventProducer;
    private final EventStore<OrderEvent> orderEventStore;

    public OrderService(
            DatabaseService databaseService,
            OrderRepository orderRepository,
            OutboxProducer<OrderEvent> orderEventProducer,
            EventStore<OrderEvent> orderEventStore) {
        this.databaseService = databaseService;
        this.orderRepository = orderRepository;
        this.orderEventProducer = orderEventProducer;
        this.orderEventStore = orderEventStore;
    }
    
    /**
     * Creates a new order with integrated outbox + bi-temporal pattern.
     * 
     * <p>This method demonstrates the COMPLETE pattern:
     * <ol>
     *   <li>Save order to database</li>
     *   <li>Send event to outbox (for immediate consumer processing)</li>
     *   <li>Append event to bi-temporal store (for historical queries)</li>
     * </ol>
     * 
     * <p>All three operations are in a SINGLE transaction. If any fails,
     * ALL rollback. If all succeed, ALL commit together.
     * 
     * @param request Order creation request
     * @return CompletableFuture with the order ID
     */
    public CompletableFuture<String> createOrder(CreateOrderRequest request) {
        String orderId = UUID.randomUUID().toString();
        Instant validTime = request.getValidTime() != null ? request.getValidTime() : Instant.now();
        
        logger.info("Creating order: {} for customer: {} amount: {}", 
            orderId, request.getCustomerId(), request.getAmount());
        
        // Create domain objects
        Order order = new Order(
            orderId,
            request.getCustomerId(),
            request.getAmount(),
            OrderEvent.OrderStatus.CREATED,
            request.getDescription(),
            Instant.now()
        );
        
        OrderEvent event = new OrderEvent(
            orderId,
            request.getCustomerId(),
            request.getAmount(),
            OrderEvent.OrderStatus.CREATED,
            request.getDescription(),
            validTime
        );
        
        // Get connection provider
        ConnectionProvider cp = databaseService.getConnectionProvider();
        
        // Execute all operations in SINGLE transaction
        return cp.withTransaction("peegeeq-main", connection -> {
            logger.info("Starting integrated transaction for order: {}", orderId);
            
            // Step 1: Save order to database
            return orderRepository.save(order, connection)
                .onSuccess(v -> logger.info("Order saved to database: {}", orderId))
                
                // Step 2: Send to outbox (for immediate processing)
                .compose(v -> Future.fromCompletionStage(
                    orderEventProducer.sendInTransaction(event, connection)
                ))
                .onSuccess(v -> logger.info("Order event sent to outbox: {}", orderId))
                
                // Step 3: Append to bi-temporal event store (for historical queries)
                .compose(v -> Future.fromCompletionStage(
                    orderEventStore.appendInTransaction(
                        "OrderCreated",        // Event type
                        event,                 // Event payload
                        validTime,             // Valid time
                        connection             // SAME connection
                    )
                ))
                .onSuccess(v -> logger.info("Order event appended to event store: {}", orderId))
                
                // Return order ID
                .map(v -> {
                    logger.info("Integrated transaction completed successfully for order: {}", orderId);
                    return orderId;
                });
                
        }).toCompletionStage().toCompletableFuture()
            .whenComplete((result, error) -> {
                if (error != null) {
                    logger.error("Failed to create order: {} - ALL operations rolled back", orderId, error);
                } else {
                    logger.info("Order created successfully: {} - ALL operations committed", orderId);
                }
            });
    }
    
    /**
     * Retrieves order history from bi-temporal event store.
     * 
     * @param orderId Order identifier
     * @return CompletableFuture with order history
     */
    public CompletableFuture<OrderResponse> getOrderHistory(String orderId) {
        logger.info("Retrieving order history: {}", orderId);
        
        return orderEventStore.query(EventQuery.all())
            .thenApply(events -> {
                List<BiTemporalEvent<OrderEvent>> orderEvents = events.stream()
                    .filter(event -> orderId.equals(event.getPayload().getOrderId()))
                    .collect(Collectors.toList());
                
                logger.info("Found {} events for order: {}", orderEvents.size(), orderId);
                return new OrderResponse(orderId, orderEvents);
            });
    }
    
    /**
     * Retrieves all orders for a customer from bi-temporal event store.
     * 
     * @param customerId Customer identifier
     * @return CompletableFuture with list of order events
     */
    public CompletableFuture<List<BiTemporalEvent<OrderEvent>>> getCustomerOrders(String customerId) {
        logger.info("Retrieving orders for customer: {}", customerId);
        
        return orderEventStore.query(EventQuery.all())
            .thenApply(events -> {
                List<BiTemporalEvent<OrderEvent>> customerOrders = events.stream()
                    .filter(event -> customerId.equals(event.getPayload().getCustomerId()))
                    .collect(Collectors.toList());
                
                logger.info("Found {} orders for customer: {}", customerOrders.size(), customerId);
                return customerOrders;
            });
    }
    
    /**
     * Queries orders as of a specific point in time.
     * 
     * @param validTime Point in time to query
     * @return CompletableFuture with events as of that time
     */
    public CompletableFuture<List<BiTemporalEvent<OrderEvent>>> getOrdersAsOfTime(Instant validTime) {
        logger.info("Querying orders as of time: {}", validTime);
        
        return orderEventStore.query(EventQuery.asOfValidTime(validTime))
            .whenComplete((events, error) -> {
                if (error != null) {
                    logger.error("Failed to query orders as of time: {}", validTime, error);
                } else {
                    logger.info("Found {} orders as of time: {}", events.size(), validTime);
                }
            });
    }
}

