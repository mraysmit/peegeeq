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

package dev.mars.peegeeq.examples.springbootintegrated.model;

import dev.mars.peegeeq.examples.springbootintegrated.events.OrderEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Order domain model representing an order in the database.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-07
 * @version 1.0
 */
public class Order {
    
    private final String id;
    private final String customerId;
    private final BigDecimal amount;
    private final OrderEvent.OrderStatus status;
    private final String description;
    private final Instant createdAt;
    
    public Order(String id, String customerId, BigDecimal amount, 
                 OrderEvent.OrderStatus status, String description, Instant createdAt) {
        this.id = id;
        this.customerId = customerId;
        this.amount = amount;
        this.status = status;
        this.description = description;
        this.createdAt = createdAt;
    }
    
    public String getId() {
        return id;
    }
    
    public String getCustomerId() {
        return customerId;
    }
    
    public BigDecimal getAmount() {
        return amount;
    }
    
    public OrderEvent.OrderStatus getStatus() {
        return status;
    }
    
    public String getDescription() {
        return description;
    }
    
    public Instant getCreatedAt() {
        return createdAt;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Order order = (Order) o;
        return Objects.equals(id, order.id);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
    
    @Override
    public String toString() {
        return "Order{" +
               "id='" + id + '\'' +
               ", customerId='" + customerId + '\'' +
               ", amount=" + amount +
               ", status=" + status +
               ", description='" + description + '\'' +
               ", createdAt=" + createdAt +
               '}';
    }
}

