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

package dev.mars.peegeeq.examples.springbootintegrated.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Order event for both outbox and bi-temporal event store.
 * 
 * <p>This event is used in two ways:
 * <ul>
 *   <li><b>Outbox</b> - For immediate real-time processing by consumers</li>
 *   <li><b>Bi-temporal Event Store</b> - For historical queries and audit trail</li>
 * </ul>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-07
 * @version 1.0
 */
public class OrderEvent {
    
    private final String orderId;
    private final String customerId;
    private final BigDecimal amount;
    private final OrderStatus status;
    private final String description;
    private final Instant validTime;
    
    /**
     * Order status enumeration.
     */
    public enum OrderStatus {
        CREATED,
        CONFIRMED,
        SHIPPED,
        DELIVERED,
        CANCELLED
    }
    
    @JsonCreator
    public OrderEvent(
            @JsonProperty("orderId") String orderId,
            @JsonProperty("customerId") String customerId,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("status") OrderStatus status,
            @JsonProperty("description") String description,
            @JsonProperty("validTime") Instant validTime) {
        this.orderId = orderId;
        this.customerId = customerId;
        this.amount = amount;
        this.status = status;
        this.description = description;
        this.validTime = validTime;
    }
    
    public String getOrderId() {
        return orderId;
    }
    
    public String getCustomerId() {
        return customerId;
    }
    
    public BigDecimal getAmount() {
        return amount;
    }
    
    public OrderStatus getStatus() {
        return status;
    }
    
    public String getDescription() {
        return description;
    }
    
    public Instant getValidTime() {
        return validTime;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        OrderEvent that = (OrderEvent) o;
        return Objects.equals(orderId, that.orderId) &&
               Objects.equals(customerId, that.customerId) &&
               Objects.equals(amount, that.amount) &&
               status == that.status &&
               Objects.equals(description, that.description) &&
               Objects.equals(validTime, that.validTime);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(orderId, customerId, amount, status, description, validTime);
    }
    
    @Override
    public String toString() {
        return "OrderEvent{" +
               "orderId='" + orderId + '\'' +
               ", customerId='" + customerId + '\'' +
               ", amount=" + amount +
               ", status=" + status +
               ", description='" + description + '\'' +
               ", validTime=" + validTime +
               '}';
    }
}

