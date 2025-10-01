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

import dev.mars.peegeeq.examples.springboot.model.CreateOrderRequest;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Domain model representing an order entity with R2DBC annotations.
 * 
 * This class uses Spring Data R2DBC annotations for reactive database access.
 * The order items are stored in a separate table and loaded separately.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-01
 * @version 1.0
 */
@Table("orders")
public class Order {
    
    @Id
    private String id;
    
    @Column("customer_id")
    private String customerId;
    
    @Column("amount")
    private BigDecimal amount;
    
    @Column("status")
    private String status;
    
    @Column("created_at")
    private Instant createdAt;
    
    @Transient  // Not persisted directly, managed separately
    private List<OrderItem> items;

    /**
     * Default constructor for R2DBC.
     */
    public Order() {
        this.items = new ArrayList<>();
    }

    /**
     * Constructor from CreateOrderRequest.
     */
    public Order(CreateOrderRequest request) {
        this.id = UUID.randomUUID().toString();
        this.customerId = request.getCustomerId();
        this.amount = request.getAmount();
        this.items = convertToOrderItems(request.getItems());
        this.createdAt = Instant.now();
        this.status = OrderStatus.CREATED.name();
    }

    /**
     * Full constructor.
     */
    public Order(String id, String customerId, BigDecimal amount, List<OrderItem> items) {
        this.id = id;
        this.customerId = customerId;
        this.amount = amount;
        this.items = items != null ? items : new ArrayList<>();
        this.createdAt = Instant.now();
        this.status = OrderStatus.CREATED.name();
    }

    /**
     * Constructor for database loading.
     */
    public Order(String id, String customerId, BigDecimal amount, String status, Instant createdAt) {
        this.id = id;
        this.customerId = customerId;
        this.amount = amount;
        this.status = status;
        this.createdAt = createdAt;
        this.items = new ArrayList<>();
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public List<OrderItem> getItems() { return items; }
    public void setItems(List<OrderItem> items) { this.items = items; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    
    public OrderStatus getOrderStatus() { 
        return status != null ? OrderStatus.valueOf(status) : OrderStatus.CREATED; 
    }
    
    public void setOrderStatus(OrderStatus orderStatus) { 
        this.status = orderStatus.name(); 
    }

    // Business methods
    public void validate() { this.status = OrderStatus.VALIDATED.name(); }
    public void reserve() { this.status = OrderStatus.RESERVED.name(); }
    public void complete() { this.status = OrderStatus.COMPLETED.name(); }
    public void cancel() { this.status = OrderStatus.CANCELLED.name(); }

    /**
     * Converts springboot OrderItems to springboot2 OrderItems.
     */
    private List<OrderItem> convertToOrderItems(List<dev.mars.peegeeq.examples.springboot.model.OrderItem> sourceItems) {
        if (sourceItems == null) {
            return new ArrayList<>();
        }
        
        List<OrderItem> converted = new ArrayList<>();
        for (dev.mars.peegeeq.examples.springboot.model.OrderItem sourceItem : sourceItems) {
            OrderItem item = new OrderItem();
            item.setId(UUID.randomUUID().toString());
            item.setOrderId(this.id);
            item.setProductId(sourceItem.getProductId());
            item.setName(sourceItem.getName());
            item.setQuantity(sourceItem.getQuantity());
            item.setPrice(sourceItem.getPrice());
            converted.add(item);
        }
        return converted;
    }

    @Override
    public String toString() {
        return String.format("Order{id='%s', customerId='%s', amount=%s, itemCount=%d, status=%s, createdAt=%s}", 
            id, customerId, amount, items != null ? items.size() : 0, status, createdAt);
    }

    /**
     * Order status enumeration.
     */
    public enum OrderStatus {
        CREATED, VALIDATED, RESERVED, COMPLETED, CANCELLED
    }
}

