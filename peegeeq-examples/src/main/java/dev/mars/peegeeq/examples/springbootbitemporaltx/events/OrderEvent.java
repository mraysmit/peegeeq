package dev.mars.peegeeq.examples.springbootbitemporaltx.events;

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
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Order Event for Bi-Temporal Event Store Transaction Coordination.
 * 
 * This event represents order lifecycle events in an enterprise order management system
 * that coordinates transactions across multiple bi-temporal event stores.
 * 
 * <h2>Order Lifecycle States</h2>
 * <ul>
 *   <li><b>CREATED</b> - Order initially created by customer</li>
 *   <li><b>VALIDATED</b> - Order validated for inventory and payment</li>
 *   <li><b>INVENTORY_RESERVED</b> - Inventory reserved for order items</li>
 *   <li><b>PAYMENT_AUTHORIZED</b> - Payment authorized but not captured</li>
 *   <li><b>CONFIRMED</b> - Order confirmed and ready for fulfillment</li>
 *   <li><b>SHIPPED</b> - Order shipped to customer</li>
 *   <li><b>DELIVERED</b> - Order delivered to customer</li>
 *   <li><b>CANCELLED</b> - Order cancelled (with compensation)</li>
 *   <li><b>REFUNDED</b> - Order refunded (with inventory release)</li>
 * </ul>
 * 
 * <h2>Transaction Coordination</h2>
 * 
 * Order events participate in coordinated transactions across multiple event stores:
 * <ul>
 *   <li><b>Inventory Coordination</b> - Reserve/release inventory items</li>
 *   <li><b>Payment Coordination</b> - Authorize/capture/refund payments</li>
 *   <li><b>Audit Coordination</b> - Record compliance and regulatory events</li>
 * </ul>
 * 
 * <h2>Bi-Temporal Characteristics</h2>
 * <ul>
 *   <li><b>Valid Time</b> - When the order state change actually occurred</li>
 *   <li><b>Transaction Time</b> - When the event was recorded in the system</li>
 *   <li><b>Corrections</b> - Support for correcting historical order data</li>
 *   <li><b>Audit Trail</b> - Complete history of all order state changes</li>
 * </ul>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-03
 * @version 1.0
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrderEvent {
    
    private final String orderId;
    private final String customerId;
    private final String orderStatus;
    private final List<OrderItem> items;
    private final BigDecimal totalAmount;
    private final String currency;
    private final LocalDateTime orderDate;
    private final LocalDateTime expectedDeliveryDate;
    private final String shippingAddress;
    private final String billingAddress;
    private final String paymentMethod;
    private final Map<String, String> metadata;
    
    /**
     * Creates a new OrderEvent.
     *
     * @param orderId Unique order identifier
     * @param customerId Customer identifier
     * @param orderStatus Current order status
     * @param items List of order items
     * @param totalAmount Total order amount
     * @param currency Order currency
     * @param orderDate Order creation date
     * @param expectedDeliveryDate Expected delivery date
     * @param shippingAddress Shipping address
     * @param billingAddress Billing address
     * @param paymentMethod Payment method
     * @param metadata Additional order metadata
     */
    @JsonCreator
    public OrderEvent(
            @JsonProperty("orderId") String orderId,
            @JsonProperty("customerId") String customerId,
            @JsonProperty("orderStatus") String orderStatus,
            @JsonProperty("items") List<OrderItem> items,
            @JsonProperty("totalAmount") BigDecimal totalAmount,
            @JsonProperty("currency") String currency,
            @JsonProperty("orderDate") LocalDateTime orderDate,
            @JsonProperty("expectedDeliveryDate") LocalDateTime expectedDeliveryDate,
            @JsonProperty("shippingAddress") String shippingAddress,
            @JsonProperty("billingAddress") String billingAddress,
            @JsonProperty("paymentMethod") String paymentMethod,
            @JsonProperty("metadata") Map<String, String> metadata) {
        this.orderId = orderId;
        this.customerId = customerId;
        this.orderStatus = orderStatus;
        this.items = items;
        this.totalAmount = totalAmount;
        this.currency = currency;
        this.orderDate = orderDate;
        this.expectedDeliveryDate = expectedDeliveryDate;
        this.shippingAddress = shippingAddress;
        this.billingAddress = billingAddress;
        this.paymentMethod = paymentMethod;
        this.metadata = metadata;
    }
    
    // Getters
    
    public String getOrderId() {
        return orderId;
    }
    
    public String getCustomerId() {
        return customerId;
    }
    
    public String getOrderStatus() {
        return orderStatus;
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
    
    public LocalDateTime getOrderDate() {
        return orderDate;
    }
    
    public LocalDateTime getExpectedDeliveryDate() {
        return expectedDeliveryDate;
    }
    
    public String getShippingAddress() {
        return shippingAddress;
    }
    
    public String getBillingAddress() {
        return billingAddress;
    }
    
    public String getPaymentMethod() {
        return paymentMethod;
    }
    
    public Map<String, String> getMetadata() {
        return metadata;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        OrderEvent that = (OrderEvent) o;
        return Objects.equals(orderId, that.orderId) &&
               Objects.equals(customerId, that.customerId) &&
               Objects.equals(orderStatus, that.orderStatus) &&
               Objects.equals(items, that.items) &&
               Objects.equals(totalAmount, that.totalAmount) &&
               Objects.equals(currency, that.currency) &&
               Objects.equals(orderDate, that.orderDate) &&
               Objects.equals(expectedDeliveryDate, that.expectedDeliveryDate) &&
               Objects.equals(shippingAddress, that.shippingAddress) &&
               Objects.equals(billingAddress, that.billingAddress) &&
               Objects.equals(paymentMethod, that.paymentMethod) &&
               Objects.equals(metadata, that.metadata);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(orderId, customerId, orderStatus, items, totalAmount, currency,
                          orderDate, expectedDeliveryDate, shippingAddress, billingAddress,
                          paymentMethod, metadata);
    }
    
    @Override
    public String toString() {
        return "OrderEvent{" +
                "orderId='" + orderId + '\'' +
                ", customerId='" + customerId + '\'' +
                ", orderStatus='" + orderStatus + '\'' +
                ", items=" + items +
                ", totalAmount=" + totalAmount +
                ", currency='" + currency + '\'' +
                ", orderDate=" + orderDate +
                ", expectedDeliveryDate=" + expectedDeliveryDate +
                ", shippingAddress='" + shippingAddress + '\'' +
                ", billingAddress='" + billingAddress + '\'' +
                ", paymentMethod='" + paymentMethod + '\'' +
                ", metadata=" + metadata +
                '}';
    }
    
    /**
     * Order Item for Order Events.
     */
    public static class OrderItem {
        private final String productId;
        private final String productName;
        private final int quantity;
        private final BigDecimal unitPrice;
        private final BigDecimal totalPrice;
        
        @JsonCreator
        public OrderItem(
                @JsonProperty("productId") String productId,
                @JsonProperty("productName") String productName,
                @JsonProperty("quantity") int quantity,
                @JsonProperty("unitPrice") BigDecimal unitPrice,
                @JsonProperty("totalPrice") BigDecimal totalPrice) {
            this.productId = productId;
            this.productName = productName;
            this.quantity = quantity;
            this.unitPrice = unitPrice;
            this.totalPrice = totalPrice;
        }
        
        public String getProductId() {
            return productId;
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
        
        public BigDecimal getTotalPrice() {
            return totalPrice;
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            OrderItem orderItem = (OrderItem) o;
            return quantity == orderItem.quantity &&
                   Objects.equals(productId, orderItem.productId) &&
                   Objects.equals(productName, orderItem.productName) &&
                   Objects.equals(unitPrice, orderItem.unitPrice) &&
                   Objects.equals(totalPrice, orderItem.totalPrice);
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(productId, productName, quantity, unitPrice, totalPrice);
        }
        
        @Override
        public String toString() {
            return "OrderItem{" +
                    "productId='" + productId + '\'' +
                    ", productName='" + productName + '\'' +
                    ", quantity=" + quantity +
                    ", unitPrice=" + unitPrice +
                    ", totalPrice=" + totalPrice +
                    '}';
        }
    }
}
