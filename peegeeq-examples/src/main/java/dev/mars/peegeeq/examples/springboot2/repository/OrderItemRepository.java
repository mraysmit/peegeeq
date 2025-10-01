package dev.mars.peegeeq.examples.springboot2.repository;

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

import dev.mars.peegeeq.examples.springboot2.model.OrderItem;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Reactive repository for OrderItem entities using R2DBC.
 * 
 * This repository provides reactive database access for order items using Spring Data R2DBC.
 * All operations return Mono or Flux for non-blocking reactive operations.
 * 
 * Key Features:
 * - Reactive CRUD operations (inherited from ReactiveCrudRepository)
 * - Custom query methods for order item management
 * - Non-blocking database access
 * - Automatic transaction management
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-01
 * @version 1.0
 */
@Repository
public interface OrderItemRepository extends ReactiveCrudRepository<OrderItem, String> {

    /**
     * Finds all items for a specific order.
     * 
     * @param orderId The order ID
     * @return Flux of order items
     */
    Flux<OrderItem> findByOrderId(String orderId);

    /**
     * Finds items by product ID across all orders.
     * 
     * @param productId The product ID
     * @return Flux of order items
     */
    Flux<OrderItem> findByProductId(String productId);

    /**
     * Counts items for a specific order.
     * 
     * @param orderId The order ID
     * @return Mono containing the count
     */
    Mono<Long> countByOrderId(String orderId);

    /**
     * Deletes all items for a specific order.
     * 
     * @param orderId The order ID
     * @return Mono<Void> that completes when deletion is done
     */
    @Modifying
    @Query("DELETE FROM order_items WHERE order_id = :orderId")
    Mono<Void> deleteByOrderId(@Param("orderId") String orderId);

    /**
     * Checks if an order has any items.
     * 
     * @param orderId The order ID
     * @return Mono containing true if items exist, false otherwise
     */
    Mono<Boolean> existsByOrderId(String orderId);

    /**
     * Finds items for an order with quantity greater than specified amount.
     * 
     * @param orderId The order ID
     * @param minQuantity The minimum quantity
     * @return Flux of order items
     */
    @Query("SELECT * FROM order_items WHERE order_id = :orderId AND quantity > :minQuantity")
    Flux<OrderItem> findByOrderIdAndQuantityGreaterThan(
        @Param("orderId") String orderId, 
        @Param("minQuantity") int minQuantity
    );
}

