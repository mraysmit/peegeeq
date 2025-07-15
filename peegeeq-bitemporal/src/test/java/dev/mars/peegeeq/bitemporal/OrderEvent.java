package dev.mars.peegeeq.bitemporal;

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
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Test event class representing an order event for integration testing.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-15
 * @version 1.0
 */
public class OrderEvent {
    private final String orderId;
    private final String customerId;
    private final BigDecimal amount;
    private final String status;
    private final String orderTime; // Using String for JSON serialization simplicity
    private final String region;
    
    @JsonCreator
    public OrderEvent(@JsonProperty("orderId") String orderId,
                     @JsonProperty("customerId") String customerId,
                     @JsonProperty("amount") BigDecimal amount,
                     @JsonProperty("status") String status,
                     @JsonProperty("orderTime") String orderTime,
                     @JsonProperty("region") String region) {
        this.orderId = orderId;
        this.customerId = customerId;
        this.amount = amount;
        this.status = status;
        this.orderTime = orderTime;
        this.region = region;
    }
    
    public String getOrderId() { return orderId; }
    public String getCustomerId() { return customerId; }
    public BigDecimal getAmount() { return amount; }
    public String getStatus() { return status; }
    public String getOrderTime() { return orderTime; }
    public String getRegion() { return region; }

    /**
     * Gets the order time as an Instant for bi-temporal store operations.
     */
    @JsonIgnore
    public Instant getOrderTimeAsInstant() {
        return Instant.parse(orderTime);
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        OrderEvent that = (OrderEvent) o;
        return Objects.equals(orderId, that.orderId) &&
               Objects.equals(customerId, that.customerId) &&
               Objects.equals(amount, that.amount) &&
               Objects.equals(status, that.status) &&
               Objects.equals(orderTime, that.orderTime) &&
               Objects.equals(region, that.region);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(orderId, customerId, amount, status, orderTime, region);
    }
    
    @Override
    public String toString() {
        return "OrderEvent{" +
                "orderId='" + orderId + '\'' +
                ", customerId='" + customerId + '\'' +
                ", amount=" + amount +
                ", status='" + status + '\'' +
                ", orderTime=" + orderTime +
                ", region='" + region + '\'' +
                '}';
    }
}
