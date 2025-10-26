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
import java.util.List;

/**
 * Request object for creating a new order.
 * 
 * This class represents the incoming request data for order creation
 * following the patterns outlined in the PeeGeeQ Transactional Outbox Patterns Guide.
 * 
 * Example JSON:
 * <pre>
 * {
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
public class CreateOrderRequest {
    private final String customerId;
    private final BigDecimal amount;
    private final List<OrderItem> items;

    @JsonCreator
    public CreateOrderRequest(
            @JsonProperty("customerId") String customerId,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("items") List<OrderItem> items) {
        
        // Validation
        if (customerId == null || customerId.trim().isEmpty()) {
            throw new IllegalArgumentException("Customer ID is required");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("At least one item is required");
        }
        
        this.customerId = customerId.trim();
        this.amount = amount;
        this.items = List.copyOf(items); // Immutable copy
    }

    // Getters
    public String getCustomerId() { return customerId; }
    public BigDecimal getAmount() { return amount; }
    public List<OrderItem> getItems() { return items; }

    /**
     * Validates the request data.
     * 
     * @throws IllegalArgumentException if validation fails
     */
    public void validate() {
        // Validate items
        for (OrderItem item : items) {
            item.validate();
        }
        
        // Validate total amount matches sum of items
        BigDecimal calculatedTotal = items.stream()
            .map(item -> item.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
            
        if (amount.compareTo(calculatedTotal) != 0) {
            throw new IllegalArgumentException(
                String.format("Total amount %.2f does not match calculated total %.2f", 
                    amount, calculatedTotal));
        }
    }

    @Override
    public String toString() {
        return String.format("CreateOrderRequest{customerId='%s', amount=%s, itemCount=%d}", 
            customerId, amount, items.size());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        
        CreateOrderRequest that = (CreateOrderRequest) o;
        
        return customerId.equals(that.customerId) &&
               amount.equals(that.amount) &&
               items.equals(that.items);
    }

    @Override
    public int hashCode() {
        int result = customerId.hashCode();
        result = 31 * result + amount.hashCode();
        result = 31 * result + items.hashCode();
        return result;
    }
}
