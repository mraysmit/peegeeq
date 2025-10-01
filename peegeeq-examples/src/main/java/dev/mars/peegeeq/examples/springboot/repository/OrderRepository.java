package dev.mars.peegeeq.examples.springboot.repository;

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

import dev.mars.peegeeq.examples.springboot.model.Order;
import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository implementation for order persistence using Vert.x SQL Client.
 * 
 * This repository uses Vert.x SQL Client to execute SQL operations within the same
 * transaction as outbox events, ensuring transactional consistency.
 * 
 * Key Features:
 * - Uses SqlConnection for transaction participation
 * - All operations can be executed within a pool.withTransaction() block
 * - Simulates database constraint violations for testing
 * - Returns Future for async operations
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-01
 * @version 2.0
 */
@Repository
public class OrderRepository {
    private static final Logger log = LoggerFactory.getLogger(OrderRepository.class);

    /**
     * Saves an order to the database using the provided connection.
     * This method is designed to be called within a transaction.
     *
     * @param order The order to save
     * @param connection The SQL connection (part of a transaction)
     * @return Future containing the saved order
     * @throws RuntimeException if database constraints are violated (for demonstration)
     */
    public Future<Order> save(Order order, SqlConnection connection) {
        log.debug("Saving order: {}", order.getId());

        // Simulate database constraint violations for demonstration
        if (order.getCustomerId().equals("DUPLICATE_ORDER")) {
            log.error("Simulating database constraint violation: Duplicate order for customer {}", order.getCustomerId());
            return Future.failedFuture(new RuntimeException("Database constraint violation: Duplicate order ID"));
        }

        // Simulate other database-level failures
        if (order.getCustomerId().equals("DB_CONNECTION_FAILED")) {
            log.error("Simulating database connection failure for customer {}", order.getCustomerId());
            return Future.failedFuture(new RuntimeException("Database connection failed"));
        }

        if (order.getCustomerId().equals("DB_TIMEOUT")) {
            log.error("Simulating database timeout for customer {}", order.getCustomerId());
            return Future.failedFuture(new RuntimeException("Database operation timeout"));
        }

        String sql = "INSERT INTO orders (id, customer_id, amount, status, created_at) VALUES ($1, $2, $3, $4, $5)";
        Tuple params = Tuple.of(
            order.getId(),
            order.getCustomerId(),
            order.getAmount(),
            order.getStatus().toString(),
            LocalDateTime.now()
        );

        return connection.preparedQuery(sql)
            .execute(params)
            .map(result -> {
                log.info("Order saved successfully: {}", order.getId());
                return order;
            })
            .onFailure(error -> {
                log.error("Failed to save order {}: {}", order.getId(), error.getMessage());
            });
    }

    /**
     * Finds an order by ID using the provided connection.
     *
     * @param id The order ID
     * @param connection The SQL connection
     * @return Future containing Optional of the order
     */
    public Future<Optional<Order>> findById(String id, SqlConnection connection) {
        log.debug("Finding order by ID: {}", id);
        
        String sql = "SELECT id, customer_id, amount, status, created_at FROM orders WHERE id = $1";
        
        return connection.preparedQuery(sql)
            .execute(Tuple.of(id))
            .map(rowSet -> {
                if (rowSet.size() == 0) {
                    log.debug("Order not found: {}", id);
                    return Optional.empty();
                }
                
                Row row = rowSet.iterator().next();
                Order order = mapRowToOrder(row);
                log.debug("Order found: {}", id);
                return Optional.of(order);
            });
    }

    /**
     * Finds an order by customer ID using the provided connection.
     *
     * @param customerId The customer ID
     * @param connection The SQL connection
     * @return Future containing Optional of the order
     */
    public Future<Optional<Order>> findByCustomerId(String customerId, SqlConnection connection) {
        log.debug("Finding order by customer ID: {}", customerId);
        
        String sql = "SELECT id, customer_id, amount, status, created_at FROM orders WHERE customer_id = $1 LIMIT 1";
        
        return connection.preparedQuery(sql)
            .execute(Tuple.of(customerId))
            .map(rowSet -> {
                if (rowSet.size() == 0) {
                    log.debug("No order found for customer: {}", customerId);
                    return Optional.empty();
                }
                
                Row row = rowSet.iterator().next();
                Order order = mapRowToOrder(row);
                log.debug("Order found for customer {}: {}", customerId, order.getId());
                return Optional.of(order);
            });
    }

    /**
     * Maps a database row to an Order object.
     *
     * @param row The database row
     * @return The Order object
     */
    private Order mapRowToOrder(Row row) {
        // Note: Order is immutable, so we create it with constructor
        // We don't have items here, so we pass an empty list
        return new Order(
            row.getString("id"),
            row.getString("customer_id"),
            row.getBigDecimal("amount"),
            List.of()  // Items will be loaded separately if needed
        );
    }
}

