package dev.mars.peegeeq.api;

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

import io.vertx.core.Future;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.TransactionPropagation;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Fluent builder for appending events to the EventStore.
 * This builder provides a cleaner, more discoverable API compared to the many overloaded methods.
 * 
 * <p>Example usage:</p>
 * <pre>{@code
 * // Simple append
 * BiTemporalEvent<Order> event = eventStore.appendBuilder()
 *     .eventType("OrderPlaced")
 *     .payload(order)
 *     .validTime(Instant.now())
 *     .execute()
 *     .get();
 * 
 * // Full metadata with transaction
 * BiTemporalEvent<Order> event = eventStore.appendBuilder()
 *     .eventType("OrderPlaced")
 *     .payload(order)
 *     .validTime(Instant.now())
 *     .correlationId(correlationId)
 *     .causationId(causationId)
 *     .aggregateId(aggregateId)
 *     .header("userId", userId)
 *     .inTransaction(connection)
 *     .execute()
 *     .get();
 * 
 * // Correction event
 * BiTemporalEvent<Order> correction = eventStore.appendBuilder()
 *     .correction(originalEventId, "Price correction")
 *     .eventType("OrderPlaced")
 *     .payload(correctedOrder)
 *     .validTime(correctedTime)
 *     .execute()
 *     .get();
 * }</pre>
 * 
 * @param <T> The type of event payload
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-01-12
 * @version 2.0
 */
public class EventStoreAppendBuilder<T> {
    private final EventStore<T> eventStore;
    
    // Required fields
    private String eventType;
    private T payload;
    private Instant validTime;
    
    // Optional fields
    private Map<String, String> headers;
    private String correlationId;
    private String causationId;
    private String aggregateId;
    
    // Correction fields
    private String originalEventId;
    private String correctionReason;
    
    // Transaction management
    private SqlConnection connection;
    private TransactionPropagation propagation;
    
    /**
     * Creates a new builder for the given event store.
     * Package-private - use EventStore.appendBuilder() instead.
     */
    EventStoreAppendBuilder(EventStore<T> eventStore) {
        this.eventStore = eventStore;
    }
    
    /**
     * Sets the event type (required).
     * 
     * @param eventType The type of the event (e.g., "OrderPlaced", "PaymentProcessed")
     * @return This builder for chaining
     */
    public EventStoreAppendBuilder<T> eventType(String eventType) {
        this.eventType = eventType;
        return this;
    }
    
    /**
     * Sets the event payload (required).
     * 
     * @param payload The event data
     * @return This builder for chaining
     */
    public EventStoreAppendBuilder<T> payload(T payload) {
        this.payload = payload;
        return this;
    }
    
    /**
     * Sets the valid time (required).
     * 
     * @param validTime When the event actually happened (business time)
     * @return This builder for chaining
     */
    public EventStoreAppendBuilder<T> validTime(Instant validTime) {
        this.validTime = validTime;
        return this;
    }
    
    /**
     * Sets the correlation ID for tracking related events.
     * 
     * @param correlationId Correlation ID (typically from incoming request/message)
     * @return This builder for chaining
     */
    public EventStoreAppendBuilder<T> correlationId(String correlationId) {
        this.correlationId = correlationId;
        return this;
    }
    
    /**
     * Sets the causation ID identifying which event caused this event.
     * 
     * @param causationId The ID of the event that caused this event
     * @return This builder for chaining
     */
    public EventStoreAppendBuilder<T> causationId(String causationId) {
        this.causationId = causationId;
        return this;
    }
    
    /**
     * Sets the aggregate ID for grouping related events.
     * 
     * @param aggregateId The aggregate/entity ID (e.g., order ID, customer ID)
     * @return This builder for chaining
     */
    public EventStoreAppendBuilder<T> aggregateId(String aggregateId) {
        this.aggregateId = aggregateId;
        return this;
    }
    
    /**
     * Adds a single header to the event metadata.
     * 
     * @param key Header key
     * @param value Header value
     * @return This builder for chaining
     */
    public EventStoreAppendBuilder<T> header(String key, String value) {
        if (this.headers == null) {
            this.headers = new HashMap<>();
        }
        this.headers.put(key, value);
        return this;
    }
    
    /**
     * Sets all headers for the event metadata.
     * 
     * @param headers Map of header key-value pairs
     * @return This builder for chaining
     */
    public EventStoreAppendBuilder<T> headers(Map<String, String> headers) {
        this.headers = headers;
        return this;
    }
    
    /**
     * Marks this as a correction event for a previous event.
     * 
     * @param originalEventId The ID of the event being corrected
     * @param correctionReason The reason for the correction
     * @return This builder for chaining
     */
    public EventStoreAppendBuilder<T> correction(String originalEventId, String correctionReason) {
        this.originalEventId = originalEventId;
        this.correctionReason = correctionReason;
        return this;
    }
    
    /**
     * Appends this event within an existing transaction.
     * Use this when you have an active SqlConnection with a transaction.
     * 
     * @param connection Existing Vert.x SqlConnection with an active transaction
     * @return This builder for chaining
     */
    public EventStoreAppendBuilder<T> inTransaction(SqlConnection connection) {
        this.connection = connection;
        return this;
    }
    
    /**
     * Appends this event using TransactionPropagation for advanced transaction management.
     * Use this with Vert.x Pool.withTransaction() for automatic transaction handling.
     * 
     * @param propagation Transaction propagation behavior (e.g., CONTEXT for sharing existing transactions)
     * @return This builder for chaining
     */
    public EventStoreAppendBuilder<T> withTransactionPropagation(TransactionPropagation propagation) {
        this.propagation = propagation;
        return this;
    }
    
    /**
     * Executes the append operation and returns a CompletableFuture.
     * 
     * @return A CompletableFuture that completes with the stored event
     * @throws IllegalStateException if required fields are missing
     */
    public CompletableFuture<BiTemporalEvent<T>> execute() {
        validate();
        
        // Correction event
        if (originalEventId != null) {
            if (headers != null) {
                return eventStore.appendCorrection(originalEventId, eventType, payload, validTime,
                    headers, correlationId, aggregateId, correctionReason);
            } else {
                return eventStore.appendCorrection(originalEventId, eventType, payload, validTime,
                    correctionReason);
            }
        }
        
        // Transaction propagation (modern approach)
        if (propagation != null) {
            return eventStore.appendWithTransaction(eventType, payload, validTime,
                headers, correlationId, causationId, aggregateId, propagation);
        }
        
        // In transaction (legacy approach with SqlConnection)
        if (connection != null) {
            return eventStore.appendInTransaction(eventType, payload, validTime,
                headers, correlationId, causationId, aggregateId, connection);
        }
        
        // Standard append (auto-commit)
        return eventStore.append(eventType, payload, validTime, headers, correlationId, causationId, aggregateId);
    }
    
    /**
     * Executes the append operation and returns a Vert.x Future (reactive version).
     * 
     * @return A Vert.x Future that completes with the stored event
     * @throws IllegalStateException if required fields are missing
     */
    public Future<BiTemporalEvent<T>> executeReactive() {
        return Future.fromCompletionStage(execute());
    }
    
    /**
     * Validates that all required fields are set.
     * 
     * @throws IllegalStateException if required fields are missing
     */
    private void validate() {
        if (eventType == null) {
            throw new IllegalStateException("eventType is required");
        }
        if (payload == null) {
            throw new IllegalStateException("payload is required");
        }
        if (validTime == null) {
            throw new IllegalStateException("validTime is required");
        }
        if (connection != null && propagation != null) {
            throw new IllegalStateException("Cannot use both inTransaction() and withTransactionPropagation()");
        }
    }
}
