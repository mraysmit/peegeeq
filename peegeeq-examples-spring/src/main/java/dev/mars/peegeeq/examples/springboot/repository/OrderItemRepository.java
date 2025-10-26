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

import dev.mars.peegeeq.examples.springboot.model.OrderItem;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Repository implementation for order item persistence using Vert.x SQL Client.
 * 
 * This repository uses Vert.x SQL Client to execute SQL operations within the same
 * transaction as outbox events and order operations, ensuring transactional consistency.
 * 
 * Key Features:
 * - Uses SqlConnection for transaction participation
 * - All operations can be executed within a pool.withTransaction() block
 * - Batch insert support for multiple items
 * - Returns Future for async operations
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-01
 * @version 2.0
 */
@Repository
public class OrderItemRepository {
    private static final Logger log = LoggerFactory.getLogger(OrderItemRepository.class);

    /**
     * Saves a list of order items to the database using the provided connection.
     * This method is designed to be called within a transaction.
     * Uses batch insert for efficiency.
     *
     * @param orderId The order ID these items belong to
     * @param items The list of order items to save
     * @param connection The SQL connection (part of a transaction)
     * @return Future that completes when all items are saved
     */
    public Future<Void> saveAll(String orderId, List<OrderItem> items, SqlConnection connection) {
        if (items == null || items.isEmpty()) {
            log.debug("No items to save for order: {}", orderId);
            return Future.succeededFuture();
        }

        log.debug("Saving {} items for order: {}", items.size(), orderId);

        String sql = "INSERT INTO order_items (id, order_id, product_id, name, quantity, price) VALUES ($1, $2, $3, $4, $5, $6)";
        
        // Create batch of tuples
        List<Tuple> batch = new ArrayList<>();
        for (OrderItem item : items) {
            batch.add(Tuple.of(
                UUID.randomUUID().toString(),  // Generate ID for each item
                orderId,
                item.getProductId(),
                item.getName(),
                item.getQuantity(),
                item.getPrice()
            ));
        }

        return connection.preparedQuery(sql)
            .executeBatch(batch)
            .map(result -> (Void) null)
            .onSuccess(v -> log.info("Saved {} items for order: {}", items.size(), orderId))
            .onFailure(error -> log.error("Failed to save items for order {}: {}", orderId, error.getMessage()));
    }

    /**
     * Finds all items for a specific order using the provided connection.
     *
     * @param orderId The order ID
     * @param connection The SQL connection
     * @return Future containing list of order items
     */
    public Future<List<OrderItem>> findByOrderId(String orderId, SqlConnection connection) {
        log.debug("Finding items for order: {}", orderId);
        
        String sql = "SELECT id, order_id, product_id, name, quantity, price FROM order_items WHERE order_id = $1";
        
        return connection.preparedQuery(sql)
            .execute(Tuple.of(orderId))
            .map(rowSet -> {
                List<OrderItem> items = new ArrayList<>();
                rowSet.forEach(row -> {
                    OrderItem item = new OrderItem(
                        row.getString("product_id"),
                        row.getString("name"),
                        row.getInteger("quantity"),
                        row.getBigDecimal("price")
                    );
                    items.add(item);
                });
                log.debug("Found {} items for order: {}", items.size(), orderId);
                return items;
            });
    }

    /**
     * Counts items for a specific order using the provided connection.
     *
     * @param orderId The order ID
     * @param connection The SQL connection
     * @return Future containing the count
     */
    public Future<Long> countByOrderId(String orderId, SqlConnection connection) {
        log.debug("Counting items for order: {}", orderId);
        
        String sql = "SELECT COUNT(*) as count FROM order_items WHERE order_id = $1";
        
        return connection.preparedQuery(sql)
            .execute(Tuple.of(orderId))
            .map(rowSet -> {
                long count = rowSet.iterator().next().getLong("count");
                log.debug("Order {} has {} items", orderId, count);
                return count;
            });
    }

    /**
     * Deletes all items for a specific order using the provided connection.
     *
     * @param orderId The order ID
     * @param connection The SQL connection
     * @return Future that completes when deletion is done
     */
    public Future<Void> deleteByOrderId(String orderId, SqlConnection connection) {
        log.debug("Deleting items for order: {}", orderId);
        
        String sql = "DELETE FROM order_items WHERE order_id = $1";
        
        return connection.preparedQuery(sql)
            .execute(Tuple.of(orderId))
            .map(result -> (Void) null)
            .onSuccess(v -> log.info("Deleted items for order: {}", orderId))
            .onFailure(error -> log.error("Failed to delete items for order {}: {}", orderId, error.getMessage()));
    }
}

