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

package dev.mars.peegeeq.examples.springbootintegrated.repository;

import dev.mars.peegeeq.examples.springbootintegrated.events.OrderEvent;
import dev.mars.peegeeq.examples.springbootintegrated.model.Order;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Repository for order database operations.
 * 
 * <p>This repository demonstrates the correct pattern for database operations
 * in PeeGeeQ applications:
 * <ul>
 *   <li>All methods accept SqlConnection parameter</li>
 *   <li>No separate connection pool or R2DBC</li>
 *   <li>Returns Vert.x Future for reactive composition</li>
 * </ul>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-07
 * @version 1.0
 */
@Repository
public class OrderRepository {
    
    private static final Logger logger = LoggerFactory.getLogger(OrderRepository.class);
    
    /**
     * Saves an order to the database using the provided connection.
     * 
     * <p>IMPORTANT: This method participates in the caller's transaction
     * by using the provided SqlConnection.
     * 
     * @param order Order to save
     * @param connection SqlConnection from ConnectionProvider.withTransaction()
     * @return Future that completes when order is saved
     */
    public Future<Void> save(Order order, SqlConnection connection) {
        logger.info("Saving order: {} for customer: {} amount: {}", 
            order.getId(), order.getCustomerId(), order.getAmount());
        
        String sql = """
            INSERT INTO orders (id, customer_id, amount, status, description, created_at)
            VALUES ($1, $2, $3, $4, $5, $6)
            ON CONFLICT (id) DO UPDATE SET
                status = EXCLUDED.status,
                description = EXCLUDED.description
            """;
        
        // Convert Instant to OffsetDateTime for PostgreSQL TIMESTAMPTZ
        OffsetDateTime createdAt = order.getCreatedAt().atOffset(ZoneOffset.UTC);

        return connection.preparedQuery(sql)
            .execute(Tuple.of(
                order.getId(),
                order.getCustomerId(),
                order.getAmount(),
                order.getStatus().name(),
                order.getDescription(),
                createdAt
            ))
            .map(result -> {
                logger.info("Order saved successfully: {}", order.getId());
                return (Void) null;
            })
            .onFailure(error ->
                logger.error("Failed to save order: {}", order.getId(), error)
            );
    }
    
    /**
     * Finds an order by ID using the provided connection.
     * 
     * @param orderId Order identifier
     * @param connection SqlConnection from ConnectionProvider
     * @return Future with the order, or null if not found
     */
    public Future<Order> findById(String orderId, SqlConnection connection) {
        logger.info("Finding order: {}", orderId);
        
        String sql = "SELECT id, customer_id, amount, status, description, created_at FROM orders WHERE id = $1";
        
        return connection.preparedQuery(sql)
            .execute(Tuple.of(orderId))
            .map(rows -> {
                if (rows.size() == 0) {
                    logger.info("Order not found: {}", orderId);
                    return null;
                }
                
                var row = rows.iterator().next();
                Order order = new Order(
                    row.getString("id"),
                    row.getString("customer_id"),
                    row.getBigDecimal("amount"),
                    OrderEvent.OrderStatus.valueOf(row.getString("status")),
                    row.getString("description"),
                    row.getLocalDateTime("created_at").toInstant(java.time.ZoneOffset.UTC)
                );
                
                logger.info("Order found: {}", orderId);
                return order;
            })
            .onFailure(error -> 
                logger.error("Failed to find order: {}", orderId, error)
            );
    }
}

