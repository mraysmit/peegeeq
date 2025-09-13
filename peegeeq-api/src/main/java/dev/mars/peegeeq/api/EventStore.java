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
import java.util.concurrent.CompletableFuture;

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
public interface EventStore<T> extends AutoCloseable {
    
    /**
     * Appends a new event to the store.
     * 
     * @param eventType The type of the event
     * @param payload The event payload
     * @param validTime When the event actually happened (business time)
     * @return A CompletableFuture that completes with the stored event
     */
    CompletableFuture<BiTemporalEvent<T>> append(String eventType, T payload, Instant validTime);
    
    /**
     * Appends a new event to the store with headers.
     * 
     * @param eventType The type of the event
     * @param payload The event payload
     * @param validTime When the event actually happened (business time)
     * @param headers Additional metadata for the event
     * @return A CompletableFuture that completes with the stored event
     */
    CompletableFuture<BiTemporalEvent<T>> append(String eventType, T payload, Instant validTime, 
                                                Map<String, String> headers);
    
    /**
     * Appends a new event to the store with full metadata.
     * 
     * @param eventType The type of the event
     * @param payload The event payload
     * @param validTime When the event actually happened (business time)
     * @param headers Additional metadata for the event
     * @param correlationId Correlation ID for tracking related events
     * @param aggregateId Aggregate ID for grouping related events
     * @return A CompletableFuture that completes with the stored event
     */
    CompletableFuture<BiTemporalEvent<T>> append(String eventType, T payload, Instant validTime,
                                                Map<String, String> headers, String correlationId, 
                                                String aggregateId);
    
    /**
     * Appends a correction event for a previous event.
     * This creates a new version of an existing event with corrected data.
     * 
     * @param originalEventId The ID of the event being corrected
     * @param eventType The type of the event
     * @param payload The corrected event payload
     * @param validTime The corrected valid time
     * @param correctionReason The reason for the correction
     * @return A CompletableFuture that completes with the correction event
     */
    CompletableFuture<BiTemporalEvent<T>> appendCorrection(String originalEventId, String eventType, 
                                                          T payload, Instant validTime, 
                                                          String correctionReason);
    
    /**
     * Appends a correction event with full metadata.
     * 
     * @param originalEventId The ID of the event being corrected
     * @param eventType The type of the event
     * @param payload The corrected event payload
     * @param validTime The corrected valid time
     * @param headers Additional metadata for the correction
     * @param correlationId Correlation ID for tracking related events
     * @param aggregateId Aggregate ID for grouping related events
     * @param correctionReason The reason for the correction
     * @return A CompletableFuture that completes with the correction event
     */
    CompletableFuture<BiTemporalEvent<T>> appendCorrection(String originalEventId, String eventType,
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
     * @return A CompletableFuture that completes with the stored event
     */
    CompletableFuture<BiTemporalEvent<T>> appendInTransaction(String eventType, T payload, Instant validTime,
                                                             io.vertx.sqlclient.SqlConnection connection);

    /**
     * Appends a new event to the store within an existing transaction with headers.
     *
     * @param eventType The type of the event
     * @param payload The event payload
     * @param validTime When the event actually happened (business time)
     * @param headers Additional metadata for the event
     * @param connection Existing Vert.x SqlConnection that has an active transaction
     * @return A CompletableFuture that completes with the stored event
     */
    CompletableFuture<BiTemporalEvent<T>> appendInTransaction(String eventType, T payload, Instant validTime,
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
     * @return A CompletableFuture that completes with the stored event
     */
    CompletableFuture<BiTemporalEvent<T>> appendInTransaction(String eventType, T payload, Instant validTime,
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
     * @return A CompletableFuture that completes with the stored event
     */
    CompletableFuture<BiTemporalEvent<T>> appendInTransaction(String eventType, T payload, Instant validTime,
                                                             Map<String, String> headers, String correlationId,
                                                             String aggregateId,
                                                             io.vertx.sqlclient.SqlConnection connection);

    /**
     * Queries events based on the provided criteria.
     *
     * @param query The query criteria
     * @return A CompletableFuture that completes with the list of matching events
     */
    CompletableFuture<List<BiTemporalEvent<T>>> query(EventQuery query);

    // ========== REACTIVE METHODS (Vert.x Future-based) ==========

    /**
     * Appends a new event to the store (reactive version).
     *
     * @param eventType The type of the event
     * @param payload The event payload
     * @param validTime When the event actually happened (business time)
     * @return A Vert.x Future that completes with the stored event
     */
    Future<BiTemporalEvent<T>> appendReactive(String eventType, T payload, Instant validTime);

    /**
     * Queries events based on the provided criteria (reactive version).
     *
     * @param query The query criteria
     * @return A Vert.x Future that completes with the list of matching events
     */
    Future<List<BiTemporalEvent<T>>> queryReactive(EventQuery query);

    /**
     * Subscribes to real-time event notifications (reactive version).
     *
     * @param eventType The type of events to subscribe to (null for all types)
     * @param handler The handler to process incoming events
     * @return A Vert.x Future that completes when the subscription is established
     */
    Future<Void> subscribeReactive(String eventType, MessageHandler<BiTemporalEvent<T>> handler);
    
    /**
     * Gets a specific event by its ID.
     * 
     * @param eventId The event ID
     * @return A CompletableFuture that completes with the event, or empty if not found
     */
    CompletableFuture<BiTemporalEvent<T>> getById(String eventId);
    
    /**
     * Gets all versions of an event (original and corrections).
     * 
     * @param eventId The original event ID
     * @return A CompletableFuture that completes with all versions of the event
     */
    CompletableFuture<List<BiTemporalEvent<T>>> getAllVersions(String eventId);
    
    /**
     * Gets the latest version of an event as of a specific transaction time.
     * 
     * @param eventId The original event ID
     * @param asOfTransactionTime The transaction time to query as of
     * @return A CompletableFuture that completes with the latest version as of the given time
     */
    CompletableFuture<BiTemporalEvent<T>> getAsOfTransactionTime(String eventId, Instant asOfTransactionTime);
    
    /**
     * Subscribes to real-time event notifications.
     * 
     * @param eventType The type of events to subscribe to (null for all types)
     * @param handler The handler to process incoming events
     * @return A CompletableFuture that completes when the subscription is established
     */
    CompletableFuture<Void> subscribe(String eventType, MessageHandler<BiTemporalEvent<T>> handler);
    
    /**
     * Subscribes to real-time event notifications for a specific aggregate.
     * 
     * @param eventType The type of events to subscribe to (null for all types)
     * @param aggregateId The aggregate ID to filter by
     * @param handler The handler to process incoming events
     * @return A CompletableFuture that completes when the subscription is established
     */
    CompletableFuture<Void> subscribe(String eventType, String aggregateId, 
                                     MessageHandler<BiTemporalEvent<T>> handler);
    
    /**
     * Unsubscribes from event notifications.
     * 
     * @return A CompletableFuture that completes when unsubscribed
     */
    CompletableFuture<Void> unsubscribe();
    
    /**
     * Gets statistics about the event store.
     * 
     * @return A CompletableFuture that completes with store statistics
     */
    CompletableFuture<EventStoreStats> getStats();
    
    /**
     * Closes the event store and releases any resources.
     */
    @Override
    void close();
    
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
    }
}
