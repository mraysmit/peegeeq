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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Repository implementation for order persistence.
 * 
 * This is a simple in-memory implementation for demonstration purposes.
 * In a real application, this would be replaced with JPA, MyBatis, or another persistence framework.
 */
@Repository
public class OrderRepository {
    private static final Logger log = LoggerFactory.getLogger(OrderRepository.class);
    
    private final Map<String, Order> orders = new ConcurrentHashMap<>();

    /**
     * Saves an order to the repository.
     *
     * @param order The order to save
     * @return The saved order
     * @throws RuntimeException if database constraints are violated (for demonstration)
     */
    public Order save(Order order) {
        log.debug("Saving order: {}", order.getId());

        // Simulate database constraint violations for demonstration
        if (order.getCustomerId().equals("DUPLICATE_ORDER")) {
            log.error("Simulating database constraint violation: Duplicate order for customer {}", order.getCustomerId());
            throw new RuntimeException("Database constraint violation: Duplicate order ID");
        }

        // Simulate other database-level failures
        if (order.getCustomerId().equals("DB_CONNECTION_FAILED")) {
            log.error("Simulating database connection failure for customer {}", order.getCustomerId());
            throw new RuntimeException("Database connection failed");
        }

        if (order.getCustomerId().equals("DB_TIMEOUT")) {
            log.error("Simulating database timeout for customer {}", order.getCustomerId());
            throw new RuntimeException("Database operation timeout");
        }

        // Check for duplicate ID (simulating unique constraint)
        if (orders.containsKey(order.getId())) {
            log.error("Order with ID {} already exists", order.getId());
            throw new RuntimeException("Duplicate order ID: " + order.getId());
        }

        orders.put(order.getId(), order);
        log.info("Order saved successfully: {}", order.getId());
        return order;
    }

    public Optional<Order> findById(String id) {
        log.debug("Finding order by ID: {}", id);
        Order order = orders.get(id);
        if (order != null) {
            log.debug("Order found: {}", id);
        } else {
            log.debug("Order not found: {}", id);
        }
        return Optional.ofNullable(order);
    }

    public Optional<Order> findByCustomerId(String customerId) {
        log.debug("Finding order by customer ID: {}", customerId);
        Order order = orders.values().stream()
            .filter(o -> o.getCustomerId().equals(customerId))
            .findFirst()
            .orElse(null);
        
        if (order != null) {
            log.debug("Order found for customer {}: {}", customerId, order.getId());
        } else {
            log.debug("No order found for customer: {}", customerId);
        }
        return Optional.ofNullable(order);
    }

    public boolean deleteById(String id) {
        log.debug("Deleting order: {}", id);
        Order removed = orders.remove(id);
        boolean deleted = removed != null;
        if (deleted) {
            log.info("Order deleted successfully: {}", id);
        } else {
            log.debug("Order not found for deletion: {}", id);
        }
        return deleted;
    }

    public boolean existsById(String id) {
        boolean exists = orders.containsKey(id);
        log.debug("Order {} exists: {}", id, exists);
        return exists;
    }

    public long count() {
        long count = orders.size();
        log.debug("Total orders in repository: {}", count);
        return count;
    }

    public void clear() {
        log.info("Clearing all orders from repository");
        orders.clear();
    }
}
