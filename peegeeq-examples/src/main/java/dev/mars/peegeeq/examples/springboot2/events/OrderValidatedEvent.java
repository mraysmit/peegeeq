package dev.mars.peegeeq.examples.springboot2.events;

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

/**
 * Event published when an order is validated.
 * 
 * This event is published as part of the transactional outbox pattern
 * following the patterns outlined in the PeeGeeQ Transactional Outbox Patterns Guide.
 * 
 * This is the reactive version used with Spring WebFlux and R2DBC.
 * 
 * The event indicates that an order has passed validation checks
 * and is published within the same transaction as the validation process.
 * 
 * Example JSON:
 * <pre>
 * {
 *   "type": "ORDER_VALIDATED",
 *   "eventId": "evt-12345-67890",
 *   "timestamp": "2025-10-01T10:30:00Z",
 *   "orderId": "ORDER-12345-67890",
 *   "valid": true,
 *   "validationMessage": "Order validation successful"
 * }
 * </pre>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-01
 * @version 1.0
 */
public class OrderValidatedEvent extends OrderEvent {
    private final String orderId;
    private final boolean valid;
    private final String validationMessage;

    /**
     * Creates an OrderValidatedEvent for a successful validation.
     * 
     * @param orderId The order ID that was validated
     */
    public OrderValidatedEvent(String orderId) {
        this(orderId, true, "Order validation successful");
    }

    /**
     * Creates an OrderValidatedEvent with validation result and message.
     * 
     * @param orderId The order ID that was validated
     * @param valid Whether the validation was successful
     * @param validationMessage The validation message
     */
    @JsonCreator
    public OrderValidatedEvent(
            @JsonProperty("orderId") String orderId,
            @JsonProperty("valid") boolean valid,
            @JsonProperty("validationMessage") String validationMessage) {
        super();
        this.orderId = orderId;
        this.valid = valid;
        this.validationMessage = validationMessage != null ? validationMessage : 
            (valid ? "Order validation successful" : "Order validation failed");
    }

    // Getters
    public String getOrderId() { return orderId; }
    public boolean isValid() { return valid; }
    public String getValidationMessage() { return validationMessage; }

    @Override
    public String getEventType() {
        return "ORDER_VALIDATED";
    }

    @Override
    public String getDescription() {
        return String.format("Order %s validation %s: %s", 
            orderId, valid ? "passed" : "failed", validationMessage);
    }

    @Override
    public void validate() {
        super.validate();
        
        if (orderId == null || orderId.trim().isEmpty()) {
            throw new IllegalArgumentException("Order ID cannot be null or empty");
        }
        if (validationMessage == null || validationMessage.trim().isEmpty()) {
            throw new IllegalArgumentException("Validation message cannot be null or empty");
        }
    }

    /**
     * Checks if the validation was successful.
     * 
     * @return true if the order validation was successful
     */
    public boolean isValidationSuccessful() {
        return valid;
    }

    /**
     * Checks if the validation failed.
     * 
     * @return true if the order validation failed
     */
    public boolean isValidationFailed() {
        return !valid;
    }

    @Override
    public String toString() {
        return String.format("OrderValidatedEvent{eventId='%s', orderId='%s', valid=%s, message='%s', timestamp=%s}", 
            getEventId(), orderId, valid, validationMessage, getTimestamp());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        
        OrderValidatedEvent that = (OrderValidatedEvent) o;
        
        return valid == that.valid &&
               orderId.equals(that.orderId) &&
               validationMessage.equals(that.validationMessage);
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + orderId.hashCode();
        result = 31 * result + (valid ? 1 : 0);
        result = 31 * result + validationMessage.hashCode();
        return result;
    }
}

