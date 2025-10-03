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
import java.util.Map;
import java.util.Objects;

/**
 * Inventory Event for Bi-Temporal Event Store Transaction Coordination.
 * 
 * This event represents inventory movement events in an enterprise inventory management system
 * that coordinates transactions with order and payment processing.
 * 
 * <h2>Inventory Movement Types</h2>
 * <ul>
 *   <li><b>RESERVED</b> - Inventory reserved for pending order</li>
 *   <li><b>ALLOCATED</b> - Reserved inventory allocated for confirmed order</li>
 *   <li><b>RELEASED</b> - Reserved inventory released (order cancelled)</li>
 *   <li><b>SHIPPED</b> - Allocated inventory shipped to customer</li>
 *   <li><b>RECEIVED</b> - New inventory received from supplier</li>
 *   <li><b>ADJUSTED</b> - Inventory adjusted due to count discrepancy</li>
 *   <li><b>DAMAGED</b> - Inventory marked as damaged/unusable</li>
 *   <li><b>RETURNED</b> - Inventory returned from customer</li>
 * </ul>
 * 
 * <h2>Transaction Coordination</h2>
 * 
 * Inventory events participate in coordinated transactions with:
 * <ul>
 *   <li><b>Order Events</b> - Reserve inventory when orders are created</li>
 *   <li><b>Payment Events</b> - Allocate inventory when payments are captured</li>
 *   <li><b>Audit Events</b> - Record inventory movements for compliance</li>
 * </ul>
 * 
 * <h2>Bi-Temporal Characteristics</h2>
 * <ul>
 *   <li><b>Valid Time</b> - When the inventory movement actually occurred</li>
 *   <li><b>Transaction Time</b> - When the movement was recorded in the system</li>
 *   <li><b>Corrections</b> - Support for correcting historical inventory data</li>
 *   <li><b>Audit Trail</b> - Complete history of all inventory movements</li>
 * </ul>
 * 
 * <h2>Inventory Tracking</h2>
 * <ul>
 *   <li><b>Product Identification</b> - SKU, barcode, and product details</li>
 *   <li><b>Location Tracking</b> - Warehouse, zone, and bin location</li>
 *   <li><b>Quantity Management</b> - Available, reserved, and allocated quantities</li>
 *   <li><b>Cost Tracking</b> - Unit cost and total value calculations</li>
 * </ul>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-03
 * @version 1.0
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class InventoryEvent {
    
    private final String inventoryId;
    private final String productId;
    private final String productSku;
    private final String movementType;
    private final int quantityChange;
    private final int quantityAfter;
    private final String warehouseId;
    private final String locationCode;
    private final BigDecimal unitCost;
    private final BigDecimal totalValue;
    private final String orderId;
    private final String supplierId;
    private final String reasonCode;
    private final LocalDateTime movementDate;
    private final Map<String, String> metadata;
    
    /**
     * Creates a new InventoryEvent.
     *
     * @param inventoryId Unique inventory movement identifier
     * @param productId Product identifier
     * @param productSku Product SKU
     * @param movementType Type of inventory movement
     * @param quantityChange Quantity change (positive or negative)
     * @param quantityAfter Quantity after movement
     * @param warehouseId Warehouse identifier
     * @param locationCode Specific location code within warehouse
     * @param unitCost Unit cost of inventory
     * @param totalValue Total value of movement
     * @param orderId Related order identifier (if applicable)
     * @param supplierId Supplier identifier (if applicable)
     * @param reasonCode Reason code for movement
     * @param movementDate Date and time of movement
     * @param metadata Additional inventory metadata
     */
    @JsonCreator
    public InventoryEvent(
            @JsonProperty("inventoryId") String inventoryId,
            @JsonProperty("productId") String productId,
            @JsonProperty("productSku") String productSku,
            @JsonProperty("movementType") String movementType,
            @JsonProperty("quantityChange") int quantityChange,
            @JsonProperty("quantityAfter") int quantityAfter,
            @JsonProperty("warehouseId") String warehouseId,
            @JsonProperty("locationCode") String locationCode,
            @JsonProperty("unitCost") BigDecimal unitCost,
            @JsonProperty("totalValue") BigDecimal totalValue,
            @JsonProperty("orderId") String orderId,
            @JsonProperty("supplierId") String supplierId,
            @JsonProperty("reasonCode") String reasonCode,
            @JsonProperty("movementDate") LocalDateTime movementDate,
            @JsonProperty("metadata") Map<String, String> metadata) {
        this.inventoryId = inventoryId;
        this.productId = productId;
        this.productSku = productSku;
        this.movementType = movementType;
        this.quantityChange = quantityChange;
        this.quantityAfter = quantityAfter;
        this.warehouseId = warehouseId;
        this.locationCode = locationCode;
        this.unitCost = unitCost;
        this.totalValue = totalValue;
        this.orderId = orderId;
        this.supplierId = supplierId;
        this.reasonCode = reasonCode;
        this.movementDate = movementDate;
        this.metadata = metadata;
    }
    
    // Getters
    
    public String getInventoryId() {
        return inventoryId;
    }
    
    public String getProductId() {
        return productId;
    }
    
    public String getProductSku() {
        return productSku;
    }
    
    public String getMovementType() {
        return movementType;
    }
    
    public int getQuantityChange() {
        return quantityChange;
    }
    
    public int getQuantityAfter() {
        return quantityAfter;
    }
    
    public String getWarehouseId() {
        return warehouseId;
    }
    
    public String getLocationCode() {
        return locationCode;
    }
    
    public BigDecimal getUnitCost() {
        return unitCost;
    }
    
    public BigDecimal getTotalValue() {
        return totalValue;
    }
    
    public String getOrderId() {
        return orderId;
    }
    
    public String getSupplierId() {
        return supplierId;
    }
    
    public String getReasonCode() {
        return reasonCode;
    }
    
    public LocalDateTime getMovementDate() {
        return movementDate;
    }
    
    public Map<String, String> getMetadata() {
        return metadata;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        InventoryEvent that = (InventoryEvent) o;
        return quantityChange == that.quantityChange &&
               quantityAfter == that.quantityAfter &&
               Objects.equals(inventoryId, that.inventoryId) &&
               Objects.equals(productId, that.productId) &&
               Objects.equals(productSku, that.productSku) &&
               Objects.equals(movementType, that.movementType) &&
               Objects.equals(warehouseId, that.warehouseId) &&
               Objects.equals(locationCode, that.locationCode) &&
               Objects.equals(unitCost, that.unitCost) &&
               Objects.equals(totalValue, that.totalValue) &&
               Objects.equals(orderId, that.orderId) &&
               Objects.equals(supplierId, that.supplierId) &&
               Objects.equals(reasonCode, that.reasonCode) &&
               Objects.equals(movementDate, that.movementDate) &&
               Objects.equals(metadata, that.metadata);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(inventoryId, productId, productSku, movementType, quantityChange,
                          quantityAfter, warehouseId, locationCode, unitCost, totalValue,
                          orderId, supplierId, reasonCode, movementDate, metadata);
    }
    
    @Override
    public String toString() {
        return "InventoryEvent{" +
                "inventoryId='" + inventoryId + '\'' +
                ", productId='" + productId + '\'' +
                ", productSku='" + productSku + '\'' +
                ", movementType='" + movementType + '\'' +
                ", quantityChange=" + quantityChange +
                ", quantityAfter=" + quantityAfter +
                ", warehouseId='" + warehouseId + '\'' +
                ", locationCode='" + locationCode + '\'' +
                ", unitCost=" + unitCost +
                ", totalValue=" + totalValue +
                ", orderId='" + orderId + '\'' +
                ", supplierId='" + supplierId + '\'' +
                ", reasonCode='" + reasonCode + '\'' +
                ", movementDate=" + movementDate +
                ", metadata=" + metadata +
                '}';
    }
}
