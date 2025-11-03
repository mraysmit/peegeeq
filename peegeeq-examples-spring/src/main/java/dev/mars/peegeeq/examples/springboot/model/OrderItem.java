package dev.mars.peegeeq.examples.springboot.model;

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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * Represents an item within an order.
 * 
 * This class represents individual items in an order
 * following the patterns outlined in the PeeGeeQ Transactional Outbox Patterns Guide.
 * 
 * Example JSON:
 * <pre>
 * {
 *   "productId": "PROD-001",
 *   "name": "Premium Widget",
 *   "quantity": 2,
 *   "price": 49.99
 * }
 * </pre>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-06
 * @version 1.0
 */
public class OrderItem {
    private final String productId;
    private final String name;
    private final int quantity;
    private final BigDecimal price;

    @JsonCreator
    public OrderItem(
            @JsonProperty("productId") String productId,
            @JsonProperty("name") String name,
            @JsonProperty("quantity") int quantity,
            @JsonProperty("price") BigDecimal price) {
        
        // Basic validation in constructor
        if (productId == null || productId.trim().isEmpty()) {
            throw new IllegalArgumentException("Product ID is required");
        }
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Product name is required");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Price must be positive");
        }
        
        this.productId = productId.trim();
        this.name = name.trim();
        this.quantity = quantity;
        this.price = price;
    }

    // Getters
    public String getProductId() { return productId; }
    public String getName() { return name; }
    public int getQuantity() { return quantity; }
    public BigDecimal getPrice() { return price; }

    /**
     * Calculates the total price for this item (price * quantity).
     * 
     * @return The total price for this item
     */
    public BigDecimal getTotalPrice() {
        return price.multiply(BigDecimal.valueOf(quantity));
    }

    /**
     * Validates the order item data.
     * 
     * @throws IllegalArgumentException if validation fails
     */
    public void validate() {
        if (productId.isEmpty()) {
            throw new IllegalArgumentException("Product ID cannot be empty");
        }
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Product name cannot be empty");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Price must be positive");
        }
        if (quantity > 1000) {
            throw new IllegalArgumentException("Quantity cannot exceed 1000");
        }
        if (price.compareTo(new BigDecimal("10000.00")) > 0) {
            throw new IllegalArgumentException("Price cannot exceed $10,000.00");
        }
    }

    @Override
    public String toString() {
        return String.format("OrderItem{productId='%s', name='%s', quantity=%d, price=%s, total=%s}", 
            productId, name, quantity, price, getTotalPrice());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        
        OrderItem orderItem = (OrderItem) o;
        
        return quantity == orderItem.quantity &&
               productId.equals(orderItem.productId) &&
               name.equals(orderItem.name) &&
               price.equals(orderItem.price);
    }

    @Override
    public int hashCode() {
        int result = productId.hashCode();
        result = 31 * result + name.hashCode();
        result = 31 * result + quantity;
        result = 31 * result + price.hashCode();
        return result;
    }
}
