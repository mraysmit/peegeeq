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

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.Instant;
import java.util.UUID;

/**
 * Base class for all order-related events in the PeeGeeQ Transactional Outbox Pattern.
 * 
 * This abstract class provides common functionality for all order events
 * following the patterns outlined in the PeeGeeQ Transactional Outbox Patterns Guide.
 * 
 * This is the reactive version used with Spring WebFlux and R2DBC.
 * 
 * Key Features:
 * - Automatic event ID generation
 * - Timestamp tracking
 * - Jackson polymorphic serialization support
 * - Immutable event data
 * 
 * Supported Event Types:
 * - OrderCreatedEvent - Published when an order is created
 * - OrderValidatedEvent - Published when an order is validated
 * - InventoryReservedEvent - Published when inventory is reserved
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-01
 * @version 1.0
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = OrderCreatedEvent.class, name = "ORDER_CREATED"),
    @JsonSubTypes.Type(value = OrderValidatedEvent.class, name = "ORDER_VALIDATED"),
    @JsonSubTypes.Type(value = InventoryReservedEvent.class, name = "INVENTORY_RESERVED")
})
public abstract class OrderEvent {
    private final String eventId;
    private final Instant timestamp;

    /**
     * Creates a new order event with automatic ID and timestamp generation.
     */
    protected OrderEvent() {
        this.eventId = UUID.randomUUID().toString();
        this.timestamp = Instant.now();
    }

    /**
     * Creates a new order event with the specified ID and automatic timestamp generation.
     * This constructor is primarily used for testing or when you need to control the event ID.
     * 
     * @param eventId The event ID
     */
    protected OrderEvent(String eventId) {
        this.eventId = eventId != null ? eventId : UUID.randomUUID().toString();
        this.timestamp = Instant.now();
    }

    // Getters
    public String getEventId() { return eventId; }
    public Instant getTimestamp() { return timestamp; }

    /**
     * Gets the event type name for this event.
     * This is used for routing and processing logic.
     * 
     * @return The event type name
     */
    public abstract String getEventType();

    /**
     * Gets a human-readable description of this event.
     * This is useful for logging and debugging.
     * 
     * @return The event description
     */
    public abstract String getDescription();

    /**
     * Validates the event data.
     * Subclasses should override this method to provide specific validation logic.
     * 
     * @throws IllegalArgumentException if validation fails
     */
    public void validate() {
        if (eventId == null || eventId.trim().isEmpty()) {
            throw new IllegalArgumentException("Event ID cannot be null or empty");
        }
        if (timestamp == null) {
            throw new IllegalArgumentException("Event timestamp cannot be null");
        }
    }

    @Override
    public String toString() {
        return String.format("%s{eventId='%s', timestamp=%s}", 
            getClass().getSimpleName(), eventId, timestamp);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        
        OrderEvent that = (OrderEvent) o;
        
        return eventId.equals(that.eventId) && timestamp.equals(that.timestamp);
    }

    @Override
    public int hashCode() {
        int result = eventId.hashCode();
        result = 31 * result + timestamp.hashCode();
        return result;
    }
}

