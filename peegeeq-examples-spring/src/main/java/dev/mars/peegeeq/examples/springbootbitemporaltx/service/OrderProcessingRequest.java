package dev.mars.peegeeq.examples.springbootbitemporaltx.service;

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
import java.util.Objects;

/**
 * Order Processing Request for Multi-Event Store Transaction Coordination.
 * 
 * This class represents a request to process an order through the complete lifecycle
 * using coordinated transactions across multiple bi-temporal event stores.
 * 
 * <h2>Request Structure</h2>
 * <ul>
 *   <li><b>Order Information</b> - Order ID, customer, and basic details</li>
 *   <li><b>Order Items</b> - Products, quantities, and pricing</li>
 *   <li><b>Payment Information</b> - Payment method and billing details</li>
 *   <li><b>Shipping Information</b> - Delivery address and preferences</li>
 * </ul>
 * 
 * <h2>Transaction Coordination</h2>
 * <p>This request will trigger coordinated operations across:</p>
 * <ul>
 *   <li><b>Order Event Store</b> - Record order creation and lifecycle</li>
 *   <li><b>Inventory Event Store</b> - Reserve inventory for order items</li>
 *   <li><b>Payment Event Store</b> - Authorize payment for order total</li>
 *   <li><b>Audit Event Store</b> - Record compliance and audit events</li>
 * </ul>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-03
 * @version 1.0
 */
public class OrderProcessingRequest {
    
    private final String orderId;
    private final String customerId;
    private final List<OrderItem> items;
    private final BigDecimal totalAmount;
    private final String currency;
    private final String paymentMethod;
    private final String shippingAddress;
    private final String billingAddress;
    
    @JsonCreator
    public OrderProcessingRequest(
            @JsonProperty("orderId") String orderId,
            @JsonProperty("customerId") String customerId,
            @JsonProperty("items") List<OrderItem> items,
            @JsonProperty("totalAmount") BigDecimal totalAmount,
            @JsonProperty("currency") String currency,
            @JsonProperty("paymentMethod") String paymentMethod,
            @JsonProperty("shippingAddress") String shippingAddress,
            @JsonProperty("billingAddress") String billingAddress) {
        this.orderId = orderId;
        this.customerId = customerId;
        this.items = items;
        this.totalAmount = totalAmount;
        this.currency = currency;
        this.paymentMethod = paymentMethod;
        this.shippingAddress = shippingAddress;
        this.billingAddress = billingAddress;
    }
    
    // Getters
    
    public String getOrderId() {
        return orderId;
    }
    
    public String getCustomerId() {
        return customerId;
    }
    
    public List<OrderItem> getItems() {
        return items;
    }
    
    public BigDecimal getTotalAmount() {
        return totalAmount;
    }
    
    public String getCurrency() {
        return currency;
    }
    
    public String getPaymentMethod() {
        return paymentMethod;
    }
    
    public String getShippingAddress() {
        return shippingAddress;
    }
    
    public String getBillingAddress() {
        return billingAddress;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        OrderProcessingRequest that = (OrderProcessingRequest) o;
        return Objects.equals(orderId, that.orderId) &&
               Objects.equals(customerId, that.customerId) &&
               Objects.equals(items, that.items) &&
               Objects.equals(totalAmount, that.totalAmount) &&
               Objects.equals(currency, that.currency) &&
               Objects.equals(paymentMethod, that.paymentMethod) &&
               Objects.equals(shippingAddress, that.shippingAddress) &&
               Objects.equals(billingAddress, that.billingAddress);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(orderId, customerId, items, totalAmount, currency,
                          paymentMethod, shippingAddress, billingAddress);
    }
    
    @Override
    public String toString() {
        return "OrderProcessingRequest{" +
                "orderId='" + orderId + '\'' +
                ", customerId='" + customerId + '\'' +
                ", items=" + items +
                ", totalAmount=" + totalAmount +
                ", currency='" + currency + '\'' +
                ", paymentMethod='" + paymentMethod + '\'' +
                ", shippingAddress='" + shippingAddress + '\'' +
                ", billingAddress='" + billingAddress + '\'' +
                '}';
    }
    
    /**
     * Order Item for Order Processing Request.
     */
    public static class OrderItem {
        private final String productId;
        private final String productSku;
        private final String productName;
        private final int quantity;
        private final BigDecimal unitPrice;
        
        @JsonCreator
        public OrderItem(
                @JsonProperty("productId") String productId,
                @JsonProperty("productSku") String productSku,
                @JsonProperty("productName") String productName,
                @JsonProperty("quantity") int quantity,
                @JsonProperty("unitPrice") BigDecimal unitPrice) {
            this.productId = productId;
            this.productSku = productSku;
            this.productName = productName;
            this.quantity = quantity;
            this.unitPrice = unitPrice;
        }
        
        public String getProductId() {
            return productId;
        }
        
        public String getProductSku() {
            return productSku;
        }
        
        public String getProductName() {
            return productName;
        }
        
        public int getQuantity() {
            return quantity;
        }
        
        public BigDecimal getUnitPrice() {
            return unitPrice;
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            OrderItem orderItem = (OrderItem) o;
            return quantity == orderItem.quantity &&
                   Objects.equals(productId, orderItem.productId) &&
                   Objects.equals(productSku, orderItem.productSku) &&
                   Objects.equals(productName, orderItem.productName) &&
                   Objects.equals(unitPrice, orderItem.unitPrice);
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(productId, productSku, productName, quantity, unitPrice);
        }
        
        @Override
        public String toString() {
            return "OrderItem{" +
                    "productId='" + productId + '\'' +
                    ", productSku='" + productSku + '\'' +
                    ", productName='" + productName + '\'' +
                    ", quantity=" + quantity +
                    ", unitPrice=" + unitPrice +
                    '}';
        }
    }
}
