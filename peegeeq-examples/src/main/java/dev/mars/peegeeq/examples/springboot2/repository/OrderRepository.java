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

import dev.mars.peegeeq.examples.springboot2.model.Order;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * Reactive repository for Order entities using R2DBC.
 * 
 * This repository provides reactive database access for orders using Spring Data R2DBC.
 * All operations return Mono or Flux for non-blocking reactive operations.
 * 
 * Key Features:
 * - Reactive CRUD operations (inherited from ReactiveCrudRepository)
 * - Custom query methods for business logic
 * - Non-blocking database access
 * - Automatic transaction management
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-01
 * @version 1.0
 */
@Repository
public interface OrderRepository extends ReactiveCrudRepository<Order, String> {

    /**
     * Finds an order by customer ID.
     * 
     * @param customerId The customer ID to search for
     * @return Mono containing the order, or empty if not found
     */
    Mono<Order> findByCustomerId(String customerId);

    /**
     * Finds all orders with a specific status.
     * 
     * @param status The order status to filter by
     * @return Flux of orders with the specified status
     */
    Flux<Order> findByStatus(String status);

    /**
     * Finds orders created after a specific timestamp.
     * 
     * @param since The timestamp to filter from
     * @return Flux of recent orders
     */
    @Query("SELECT * FROM orders WHERE created_at > :since ORDER BY created_at DESC")
    Flux<Order> findRecentOrders(@Param("since") Instant since);

    /**
     * Finds orders for a customer with a specific status.
     * 
     * @param customerId The customer ID
     * @param status The order status
     * @return Flux of matching orders
     */
    Flux<Order> findByCustomerIdAndStatus(String customerId, String status);

    /**
     * Counts orders by status.
     * 
     * @param status The order status
     * @return Mono containing the count
     */
    Mono<Long> countByStatus(String status);

    /**
     * Checks if an order exists for a customer.
     * 
     * @param customerId The customer ID
     * @return Mono containing true if exists, false otherwise
     */
    Mono<Boolean> existsByCustomerId(String customerId);

    /**
     * Deletes all orders for a specific customer.
     * 
     * @param customerId The customer ID
     * @return Mono<Void> that completes when deletion is done
     */
    Mono<Void> deleteByCustomerId(String customerId);
}

