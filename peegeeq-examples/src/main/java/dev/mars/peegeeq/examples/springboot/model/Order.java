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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Domain model representing an order entity.
 */
public class Order {
    private final String id;
    private final String customerId;
    private final BigDecimal amount;
    private final List<OrderItem> items;
    private final Instant createdAt;
    private OrderStatus status;

    public Order(CreateOrderRequest request) {
        this.id = UUID.randomUUID().toString();
        this.customerId = request.getCustomerId();
        this.amount = request.getAmount();
        this.items = request.getItems();
        this.createdAt = Instant.now();
        this.status = OrderStatus.CREATED;
    }

    public Order(String id, String customerId, BigDecimal amount, List<OrderItem> items) {
        this.id = id;
        this.customerId = customerId;
        this.amount = amount;
        this.items = items;
        this.createdAt = Instant.now();
        this.status = OrderStatus.CREATED;
    }

    // Getters
    public String getId() { return id; }
    public String getCustomerId() { return customerId; }
    public BigDecimal getAmount() { return amount; }
    public List<OrderItem> getItems() { return items; }
    public Instant getCreatedAt() { return createdAt; }
    public OrderStatus getStatus() { return status; }

    // Business methods
    public void validate() { this.status = OrderStatus.VALIDATED; }
    public void reserve() { this.status = OrderStatus.RESERVED; }
    public void complete() { this.status = OrderStatus.COMPLETED; }
    public void cancel() { this.status = OrderStatus.CANCELLED; }

    @Override
    public String toString() {
        return String.format("Order{id='%s', customerId='%s', amount=%s, itemCount=%d, status=%s, createdAt=%s}", 
            id, customerId, amount, items.size(), status, createdAt);
    }

    public enum OrderStatus {
        CREATED, VALIDATED, RESERVED, COMPLETED, CANCELLED
    }
}
