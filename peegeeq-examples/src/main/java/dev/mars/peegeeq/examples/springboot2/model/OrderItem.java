package dev.mars.peegeeq.examples.springboot2.model;

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

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Represents an item within an order with R2DBC annotations.
 * 
 * This class uses Spring Data R2DBC annotations for reactive database access.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-01
 * @version 1.0
 */
@Table("order_items")
public class OrderItem {
    
    @Id
    private String id;
    
    @Column("order_id")
    private String orderId;
    
    @Column("product_id")
    private String productId;
    
    @Column("name")
    private String name;
    
    @Column("quantity")
    private int quantity;
    
    @Column("price")
    private BigDecimal price;

    /**
     * Default constructor for R2DBC.
     */
    public OrderItem() {
    }

    /**
     * Constructor for creating a new order item.
     */
    public OrderItem(String orderId, String productId, String name, int quantity, BigDecimal price) {
        this.id = UUID.randomUUID().toString();
        this.orderId = orderId;
        this.productId = productId;
        this.name = name;
        this.quantity = quantity;
        this.price = price;
        validate();
    }

    /**
     * Constructor from springboot OrderItem.
     */
    public OrderItem(String orderId, dev.mars.peegeeq.examples.springboot.model.OrderItem sourceItem) {
        this.id = UUID.randomUUID().toString();
        this.orderId = orderId;
        this.productId = sourceItem.getProductId();
        this.name = sourceItem.getName();
        this.quantity = sourceItem.getQuantity();
        this.price = sourceItem.getPrice();
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }

    public String getProductId() { return productId; }
    public void setProductId(String productId) { this.productId = productId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }

    /**
     * Calculates the total price for this item (price * quantity).
     * 
     * @return The total price for this item
     */
    public BigDecimal getTotalPrice() {
        return price != null ? price.multiply(BigDecimal.valueOf(quantity)) : BigDecimal.ZERO;
    }

    /**
     * Validates the order item data.
     * 
     * @throws IllegalArgumentException if validation fails
     */
    public void validate() {
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
        if (quantity > 1000) {
            throw new IllegalArgumentException("Quantity cannot exceed 1000");
        }
        if (price.compareTo(new BigDecimal("10000.00")) > 0) {
            throw new IllegalArgumentException("Price cannot exceed $10,000.00");
        }
    }

    @Override
    public String toString() {
        return String.format("OrderItem{id='%s', orderId='%s', productId='%s', name='%s', quantity=%d, price=%s, total=%s}", 
            id, orderId, productId, name, quantity, price, getTotalPrice());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        
        OrderItem orderItem = (OrderItem) o;
        
        if (id != null && orderItem.id != null) {
            return id.equals(orderItem.id);
        }
        
        return quantity == orderItem.quantity &&
               productId.equals(orderItem.productId) &&
               name.equals(orderItem.name) &&
               price.equals(orderItem.price);
    }

    @Override
    public int hashCode() {
        if (id != null) {
            return id.hashCode();
        }
        
        int result = productId != null ? productId.hashCode() : 0;
        result = 31 * result + (name != null ? name.hashCode() : 0);
        result = 31 * result + quantity;
        result = 31 * result + (price != null ? price.hashCode() : 0);
        return result;
    }
}

