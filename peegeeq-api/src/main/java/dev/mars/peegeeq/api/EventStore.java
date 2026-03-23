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

import dev.mars.peegeeq.api.messaging.MessageHandler;
import io.vertx.core.Future;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Interface for the bi-temporal event store.
 * 
 * The EventStore provides append-only, bi-temporal event storage with:
 * - Append-only: Events are never deleted, only new versions added
 * - Bi-temporal: Track both when events happened (valid time) and when they were recorded (transaction time)
 * - Real-time: Immediate processing via PeeGeeQ's LISTEN/NOTIFY
 * - Historical: Query any point-in-time view of your data
 * - Type safety: Strongly typed events with JSON storage flexibility
 * 
 * This interface is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities with
 * bi-temporal event sourcing support.
 * 
 * @param <T> The type of event payload
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-15
 * @version 1.0
 */
public interface EventStore<T> {
    
    // ========== BUILDER API (RECOMMENDED) ==========
    
    /**
     * Creates a fluent builder for appending events.
     * This is the recommended way to append events in new code.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * eventStore.appendBuilder()
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
     * }</pre>
     * 
     * @return A new builder instance
     * @since 2.0
     */
    default EventStoreAppendBuilder<T> appendBuilder() {
        return new EventStoreAppendBuilder<>(this);
    }
    
    // ========== LEGACY APPEND METHODS (Deprecated - use appendBuilder() instead) ==========
    
    /**
     * Appends a new event to the store.
     * 
     * @param eventType The type of the event
     * @param payload The event payload
     * @param validTime When the event actually happened (business time)
    * @return A Vert.x Future that completes with the stored event
     * @deprecated Use {@link #appendBuilder()} for cleaner, more discoverable API
     */
    @Deprecated(since = "2.0", forRemoval = false)
    Future<BiTemporalEvent<T>> append(String eventType, T payload, Instant validTime);
    
    /**
     * Appends a new event to the store with headers.
     * 
     * @param eventType The type of the event
     * @param payload The event payload
     * @param validTime When the event actually happened (business time)
     * @param headers Additional metadata for the event
    * @return A Vert.x Future that completes with the stored event
     * @deprecated Use {@link #appendBuilder()} for cleaner, more discoverable API
     */
    @Deprecated(since = "2.0", forRemoval = false)
    Future<BiTemporalEvent<T>> append(String eventType, T payload, Instant validTime,
                              Map<String, String> headers);
    
    /**
     * Appends a new event to the store with full metadata.
     * 
     * @param eventType The type of the event
     * @param payload The event payload
     * @param validTime When the event actually happened (business time)
     * @param headers Additional metadata for the event
     * @param correlationId Correlation ID for tracking related events
     * @param causationId Causation ID identifying which event caused this event
     * @param aggregateId Aggregate ID for grouping related events
    * @return A Vert.x Future that completes with the stored event
     */
    Future<BiTemporalEvent<T>> append(String eventType, T payload, Instant validTime,
                               Map<String, String> headers, String correlationId,
                               String causationId, String aggregateId);

    /**
     * Appends a correction event for a previous event.
     * This creates a new version of an existing event with corrected data.
     *
     * <p><b>Chain model:</b> The new correction's {@code previous_version_id} always points
     * to the current latest version in the lineage, regardless of which event ID the caller
     * passes. The {@code originalEventId} parameter identifies the family (any event ID in
     * the lineage is accepted); the system resolves the root and the latest version internally.</p>
     * 
     * @param originalEventId Any event ID in the correction lineage (root or child);
     *                        used to identify the family, not to set previous_version_id
     * @param eventType The type of the event
     * @param payload The corrected event payload
     * @param validTime The corrected valid time
     * @param correctionReason The reason for the correction
    * @return A Vert.x Future that completes with the correction event
     */
    Future<BiTemporalEvent<T>> appendCorrection(String originalEventId, String eventType,
                                       T payload, Instant validTime,
                                       String correctionReason);
    
    /**
     * Appends a correction event with full metadata.
     *
     * <p><b>Chain model:</b> The new correction's {@code previous_version_id} always points
     * to the current latest version in the lineage, regardless of which event ID the caller
     * passes. The {@code originalEventId} parameter identifies the family (any event ID in
     * the lineage is accepted); the system resolves the root and the latest version internally.</p>
     * 
     * @param originalEventId Any event ID in the correction lineage (root or child);
     *                        used to identify the family, not to set previous_version_id
     * @param eventType The type of the event
     * @param payload The corrected event payload
     * @param validTime The corrected valid time
     * @param headers Additional metadata for the correction
     * @param correlationId Correlation ID for tracking related events
     * @param aggregateId Aggregate ID for grouping related events
     * @param correctionReason The reason for the correction
    * @return A Vert.x Future that completes with the correction event
     */
    Future<BiTemporalEvent<T>> appendCorrection(String originalEventId, String eventType,
                                       T payload, Instant validTime,
                                       Map<String, String> headers,
                                       String correlationId, String aggregateId,
                                       String correctionReason);

    // ========== TRANSACTION PARTICIPATION METHODS ==========

    /**
     * Appends a new event to the store within an existing transaction.
     * This method allows the bitemporal event to participate in an existing database transaction,
     * ensuring ACID guarantees between business operations and event logging.
     *
     * @param eventType The type of the event
     * @param payload The event payload
     * @param validTime When the event actually happened (business time)
     * @param connection Existing Vert.x SqlConnection that has an active transaction
    * @return A Vert.x Future that completes with the stored event
     * @deprecated Use {@link #appendBuilder()}.inTransaction(connection) for cleaner API
     */
    @Deprecated(since = "2.0", forRemoval = false)
    Future<BiTemporalEvent<T>> appendInTransaction(String eventType, T payload, Instant validTime,
                                         io.vertx.sqlclient.SqlConnection connection);

    /**
     * Appends a new event to the store within an existing transaction with headers.
     *
     * @param eventType The type of the event
     * @param payload The event payload
     * @param validTime When the event actually happened (business time)
     * @param headers Additional metadata for the event
     * @param connection Existing Vert.x SqlConnection that has an active transaction
    * @return A Vert.x Future that completes with the stored event
     * @deprecated Use {@link #appendBuilder()}.headers(headers).inTransaction(connection) for cleaner API
     */
    @Deprecated(since = "2.0", forRemoval = false)
    Future<BiTemporalEvent<T>> appendInTransaction(String eventType, T payload, Instant validTime,
                                         Map<String, String> headers,
                                         io.vertx.sqlclient.SqlConnection connection);

    /**
     * Appends a new event to the store within an existing transaction with headers and correlation ID.
     *
     * @param eventType The type of the event
     * @param payload The event payload
     * @param validTime When the event actually happened (business time)
     * @param headers Additional metadata for the event
     * @param correlationId Correlation ID for tracking related events
     * @param connection Existing Vert.x SqlConnection that has an active transaction
    * @return A Vert.x Future that completes with the stored event
     * @deprecated Use {@link #appendBuilder()}.correlationId(correlationId).inTransaction(connection) for cleaner API
     */
    @Deprecated(since = "2.0", forRemoval = false)
    Future<BiTemporalEvent<T>> appendInTransaction(String eventType, T payload, Instant validTime,
                                         Map<String, String> headers, String correlationId,
                                         io.vertx.sqlclient.SqlConnection connection);

    /**
     * Appends a new event to the store within an existing transaction with full metadata.
     * This is the core transactional method that ensures bitemporal events
     * participate in the same transaction as business operations.
     *
     * @param eventType The type of the event
     * @param payload The event payload
     * @param validTime When the event actually happened (business time)
     * @param headers Additional metadata for the event
     * @param correlationId Correlation ID for tracking related events
     * @param aggregateId Aggregate ID for grouping related events
     * @param connection Existing Vert.x SqlConnection that has an active transaction
    * @return A Vert.x Future that completes with the stored event
     * @deprecated Use {@link #appendBuilder()}.aggregateId(aggregateId).inTransaction(connection) for cleaner API
     */
    @Deprecated(since = "2.0", forRemoval = false)
    Future<BiTemporalEvent<T>> appendInTransaction(String eventType, T payload, Instant validTime,
                                         Map<String, String> headers, String correlationId,
                                         String aggregateId,
                                         io.vertx.sqlclient.SqlConnection connection);

    /**
     * Appends a new event to the store within an existing transaction with full metadata including causation ID.
     * This method supports event causality tracking, allowing you to record which event caused this event.
     *
     * @param eventType The type of the event
     * @param payload The event payload
     * @param validTime When the event actually happened (business time)
     * @param headers Additional metadata for the event
     * @param correlationId Correlation ID for tracking related events
     * @param causationId Causation ID identifying which event caused this event
     * @param aggregateId Aggregate ID for grouping related events
     * @param connection Existing Vert.x SqlConnection that has an active transaction
    * @return A Vert.x Future that completes with the stored event
     */
    Future<BiTemporalEvent<T>> appendInTransaction(String eventType, T payload, Instant validTime,
                                         Map<String, String> headers, String correlationId,
                                         String causationId, String aggregateId,
                                         io.vertx.sqlclient.SqlConnection connection);

    /**
     * Queries events based on the provided criteria.
     *
     * @param query The query criteria
    * @return A Vert.x Future that completes with the list of matching events
     */
    Future<List<BiTemporalEvent<T>>> query(EventQuery query);

    /**
     * Gets a specific event by its ID.
     * 
     * @param eventId The event ID
    * @return A Vert.x Future that completes with the event, or empty if not found
     */
    Future<BiTemporalEvent<T>> getById(String eventId);
    
    /**
     * Gets all versions of an event (original and corrections).
     * 
     * @param eventId The original event ID
    * @return A Vert.x Future that completes with all versions of the event
     */
    Future<List<BiTemporalEvent<T>>> getAllVersions(String eventId);
    
    /**
     * Gets the latest version of an event as of a specific transaction time.
     * 
     * @param eventId The original event ID
     * @param asOfTransactionTime The transaction time to query as of
    * @return A Vert.x Future that completes with the latest version as of the given time
     */
    Future<BiTemporalEvent<T>> getAsOfTransactionTime(String eventId, Instant asOfTransactionTime);
    
    /**
     * Subscribes to real-time event notifications.
     * 
     * @param eventType The type of events to subscribe to (null for all types)
     * @param handler The handler to process incoming events
    * @return A Vert.x Future that completes when the subscription is established
     */
    Future<Void> subscribe(String eventType, MessageHandler<BiTemporalEvent<T>> handler);
    
    /**
     * Subscribes to real-time event notifications for a specific aggregate.
     * 
     * @param eventType The type of events to subscribe to (null for all types)
     * @param aggregateId The aggregate ID to filter by
     * @param handler The handler to process incoming events
    * @return A Vert.x Future that completes when the subscription is established
     */
    Future<Void> subscribe(String eventType, String aggregateId,
                      MessageHandler<BiTemporalEvent<T>> handler);
    
    /**
     * Unsubscribes from event notifications.
     * 
    * @return A Vert.x Future that completes when unsubscribed
     */
    Future<Void> unsubscribe();

    /**
     * Gets a list of unique aggregate IDs, optionally filtered by event type.
     * This is useful for discovering aggregates in the system.
     *
     * @param eventType The event type to filter by (optional, can be null)
    * @return A Vert.x Future that completes with a list of unique aggregate IDs
     */
    Future<List<String>> getUniqueAggregates(String eventType);
    
    /**
     * Gets statistics about the event store.
     * 
    * @return A Vert.x Future that completes with store statistics
     */
    Future<EventStoreStats> getStats();
    
    /**
     * Closes the event store and releases any resources.
     *
     * @return a Future that completes when all resources are released
     */
    Future<Void> close();
    
    /**
     * Statistics about the event store.
     */
    interface EventStoreStats {
        long getTotalEvents();
        long getTotalCorrections();
        Map<String, Long> getEventCountsByType();
        Instant getOldestEventTime();
        Instant getNewestEventTime();
        long getStorageSizeBytes();

        /**
         * Gets the count of unique aggregate IDs in the event store.
         * This is useful for understanding how many distinct aggregates have events.
         *
         * @return The count of unique aggregate IDs
         */
        long getUniqueAggregateCount();
    }
}
