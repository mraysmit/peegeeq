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
import dev.mars.peegeeq.examples.springboot.model.OrderItem;

import java.util.List;

/**
 * Event published when inventory is reserved for an order.
 * 
 * This event is published as part of the transactional outbox pattern
 * following the patterns outlined in the PeeGeeQ Transactional Outbox Patterns Guide.
 * 
 * The event indicates that inventory has been reserved for the specified order items
 * and is published within the same transaction as the inventory reservation process.
 * 
 * Example JSON:
 * <pre>
 * {
 *   "type": "INVENTORY_RESERVED",
 *   "eventId": "evt-12345-67890",
 *   "timestamp": "2025-09-06T10:30:00Z",
 *   "orderId": "ORDER-12345-67890",
 *   "reservationId": "RES-12345-67890",
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
public class InventoryReservedEvent extends OrderEvent {
    private final String orderId;
    private final String reservationId;
    private final List<OrderItem> items;

    /**
     * Creates an InventoryReservedEvent for the specified order and items.
     * 
     * @param orderId The order ID for which inventory is reserved
     * @param items The items for which inventory is reserved
     */
    public InventoryReservedEvent(String orderId, List<OrderItem> items) {
        super();
        this.orderId = orderId;
        this.reservationId = "RES-" + orderId + "-" + System.currentTimeMillis();
        this.items = items != null ? List.copyOf(items) : List.of();
    }

    /**
     * Creates an InventoryReservedEvent with explicit values.
     * This constructor is primarily used for testing or deserialization.
     * 
     * @param orderId The order ID
     * @param reservationId The reservation ID
     * @param items The reserved items
     */
    @JsonCreator
    public InventoryReservedEvent(
            @JsonProperty("orderId") String orderId,
            @JsonProperty("reservationId") String reservationId,
            @JsonProperty("items") List<OrderItem> items) {
        super();
        this.orderId = orderId;
        this.reservationId = reservationId;
        this.items = items != null ? List.copyOf(items) : List.of();
    }

    // Getters
    public String getOrderId() { return orderId; }
    public String getReservationId() { return reservationId; }
    public List<OrderItem> getItems() { return items; }

    @Override
    public String getEventType() {
        return "INVENTORY_RESERVED";
    }

    @Override
    public String getDescription() {
        return String.format("Inventory reserved for order %s with %d items (reservation: %s)", 
            orderId, items.size(), reservationId);
    }

    @Override
    public void validate() {
        super.validate();
        
        if (orderId == null || orderId.trim().isEmpty()) {
            throw new IllegalArgumentException("Order ID cannot be null or empty");
        }
        if (reservationId == null || reservationId.trim().isEmpty()) {
            throw new IllegalArgumentException("Reservation ID cannot be null or empty");
        }
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("At least one item is required for inventory reservation");
        }
        
        // Validate each item
        for (OrderItem item : items) {
            item.validate();
        }
    }

    /**
     * Gets the total quantity of items reserved.
     * 
     * @return The total quantity across all items
     */
    public int getTotalQuantity() {
        return items.stream().mapToInt(OrderItem::getQuantity).sum();
    }

    /**
     * Gets the number of unique products reserved.
     * 
     * @return The number of unique product IDs
     */
    public int getUniqueProductCount() {
        return (int) items.stream().map(OrderItem::getProductId).distinct().count();
    }

    @Override
    public String toString() {
        return String.format("InventoryReservedEvent{eventId='%s', orderId='%s', reservationId='%s', itemCount=%d, totalQuantity=%d, timestamp=%s}", 
            getEventId(), orderId, reservationId, items.size(), getTotalQuantity(), getTimestamp());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        
        InventoryReservedEvent that = (InventoryReservedEvent) o;
        
        return orderId.equals(that.orderId) &&
               reservationId.equals(that.reservationId) &&
               items.equals(that.items);
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + orderId.hashCode();
        result = 31 * result + reservationId.hashCode();
        result = 31 * result + items.hashCode();
        return result;
    }
}
