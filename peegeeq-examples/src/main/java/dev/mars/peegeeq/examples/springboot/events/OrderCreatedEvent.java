package dev.mars.peegeeq.examples.springboot.events;

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
import dev.mars.peegeeq.examples.springboot.model.CreateOrderRequest;
import dev.mars.peegeeq.examples.springboot.model.OrderItem;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Event published when an order is created.
 * 
 * This event is published as part of the transactional outbox pattern
 * following the patterns outlined in the PeeGeeQ Transactional Outbox Patterns Guide.
 * 
 * The event contains all the necessary information about the created order
 * and is published within the same transaction as the order creation.
 * 
 * Example JSON:
 * <pre>
 * {
 *   "type": "ORDER_CREATED",
 *   "eventId": "evt-12345-67890",
 *   "timestamp": "2025-09-06T10:30:00Z",
 *   "orderId": "ORDER-12345-67890",
 *   "customerId": "CUST-12345",
 *   "amount": 99.99,
 *   "items": [
 *     {
 *       "productId": "PROD-001",
 *       "name": "Premium Widget",
 *       "quantity": 2,
 *       "price": 49.99
 *     }
 *   ]
 * }
 * </pre>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-06
 * @version 1.0
 */
public class OrderCreatedEvent extends OrderEvent {
    private final String orderId;
    private final String customerId;
    private final BigDecimal amount;
    private final List<OrderItem> items;

    /**
     * Creates an OrderCreatedEvent from a CreateOrderRequest.
     * This is the primary constructor used in the service layer.
     * 
     * @param request The order creation request
     */
    public OrderCreatedEvent(CreateOrderRequest request) {
        super();
        this.orderId = UUID.randomUUID().toString();
        this.customerId = request.getCustomerId();
        this.amount = request.getAmount();
        this.items = List.copyOf(request.getItems()); // Immutable copy
    }

    /**
     * Creates an OrderCreatedEvent with explicit values.
     * This constructor is primarily used for testing or deserialization.
     * 
     * @param orderId The order ID
     * @param customerId The customer ID
     * @param amount The order amount
     * @param items The order items
     */
    @JsonCreator
    public OrderCreatedEvent(
            @JsonProperty("orderId") String orderId,
            @JsonProperty("customerId") String customerId,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("items") List<OrderItem> items) {
        super();
        this.orderId = orderId;
        this.customerId = customerId;
        this.amount = amount;
        this.items = items != null ? List.copyOf(items) : List.of();
    }

    // Getters
    public String getOrderId() { return orderId; }
    public String getCustomerId() { return customerId; }
    public BigDecimal getAmount() { return amount; }
    public List<OrderItem> getItems() { return items; }

    @Override
    public String getEventType() {
        return "ORDER_CREATED";
    }

    @Override
    public String getDescription() {
        return String.format("Order created for customer %s with amount %s", customerId, amount);
    }

    @Override
    public void validate() {
        super.validate();
        
        if (orderId == null || orderId.trim().isEmpty()) {
            throw new IllegalArgumentException("Order ID cannot be null or empty");
        }
        if (customerId == null || customerId.trim().isEmpty()) {
            throw new IllegalArgumentException("Customer ID cannot be null or empty");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("At least one item is required");
        }
        
        // Validate each item
        for (OrderItem item : items) {
            item.validate();
        }
        
        // Validate total amount matches sum of items
        BigDecimal calculatedTotal = items.stream()
            .map(OrderItem::getTotalPrice)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
            
        if (amount.compareTo(calculatedTotal) != 0) {
            throw new IllegalArgumentException(
                String.format("Total amount %.2f does not match calculated total %.2f", 
                    amount, calculatedTotal));
        }
    }

    @Override
    public String toString() {
        return String.format("OrderCreatedEvent{eventId='%s', orderId='%s', customerId='%s', amount=%s, itemCount=%d, timestamp=%s}", 
            getEventId(), orderId, customerId, amount, items.size(), getTimestamp());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        
        OrderCreatedEvent that = (OrderCreatedEvent) o;
        
        return orderId.equals(that.orderId) &&
               customerId.equals(that.customerId) &&
               amount.equals(that.amount) &&
               items.equals(that.items);
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + orderId.hashCode();
        result = 31 * result + customerId.hashCode();
        result = 31 * result + amount.hashCode();
        result = 31 * result + items.hashCode();
        return result;
    }
}
