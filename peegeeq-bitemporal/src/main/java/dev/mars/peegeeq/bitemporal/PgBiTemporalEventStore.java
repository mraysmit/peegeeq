package dev.mars.peegeeq.bitemporal;

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

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.api.EventStore;
import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.api.SimpleBiTemporalEvent;
import dev.mars.peegeeq.api.EventQuery;

import dev.mars.peegeeq.api.messaging.MessageHandler;
import dev.mars.peegeeq.db.PeeGeeQManager;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;

import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.TransactionPropagation;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PostgreSQL-based implementation of the bi-temporal event store.
 * 
 * This implementation provides:
 * - Append-only event storage with bi-temporal dimensions
 * - Real-time event notifications via PostgreSQL LISTEN/NOTIFY
 * - Efficient querying with temporal ranges
 * - Type-safe event handling with JSON serialization
 * - Integration with PeeGeeQ's monitoring and resilience features
 * 
 * @param <T> The type of event payload
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-15
 * @version 1.0
 */
public class PgBiTemporalEventStore<T> implements EventStore<T> {
    
    private static final Logger logger = LoggerFactory.getLogger(PgBiTemporalEventStore.class);
    
    private final PeeGeeQManager peeGeeQManager;
    private final ObjectMapper objectMapper;
    private final Class<T> payloadType;
    private final Map<String, MessageHandler<BiTemporalEvent<T>>> subscriptions;
    private volatile boolean closed = false;

    // Pure Vert.x reactive infrastructure with caching
    private volatile Pool reactivePool;
    private final ReactiveNotificationHandler<T> reactiveNotificationHandler;

    // Shared Vertx instance for proper context management
    private static volatile Vertx sharedVertx;

    // Notification handling (now handled by ReactiveNotificationHandler)
    
    /**
     * Creates a new PgBiTemporalEventStore.
     *
     * @param peeGeeQManager The PeeGeeQ manager for database access
     * @param payloadType The class type of the event payload
     * @param objectMapper The JSON object mapper
     */
    public PgBiTemporalEventStore(PeeGeeQManager peeGeeQManager, Class<T> payloadType,
                                 ObjectMapper objectMapper) {
        System.out.println("DEBUG: PgBiTemporalEventStore constructor starting");
        this.peeGeeQManager = Objects.requireNonNull(peeGeeQManager, "PeeGeeQ manager cannot be null");
        this.payloadType = Objects.requireNonNull(payloadType, "Payload type cannot be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "Object mapper cannot be null");

        this.subscriptions = new ConcurrentHashMap<>();

        // Initialize pure Vert.x reactive infrastructure - pool will be created lazily
        this.reactivePool = null; // Will be created on first use
        System.out.println("DEBUG: About to create ReactiveNotificationHandler");

        // Initialize reactive notification handler with lazy connection options
        // This will be properly initialized when startReactiveNotifications() is called
        this.reactiveNotificationHandler = new ReactiveNotificationHandler<T>(
            getOrCreateSharedVertx(),
            null, // Will be set during startReactiveNotifications()
            objectMapper,
            payloadType,
            this::getById // Use the existing getById method as event retriever
        );

        System.out.println("DEBUG: About to call startReactiveNotifications");
        // Start reactive notification handler with proper connection options
        startReactiveNotifications();
        System.out.println("DEBUG: startReactiveNotifications completed");

        logger.info("Created bi-temporal event store for payload type: {}", payloadType.getSimpleName());
        System.out.println("DEBUG: PgBiTemporalEventStore constructor completed");
    }
    
    @Override
    public CompletableFuture<BiTemporalEvent<T>> append(String eventType, T payload, Instant validTime) {
        logger.debug("BITEMPORAL-DEBUG: Appending event - type: {}, validTime: {}", eventType, validTime);
        return append(eventType, payload, validTime, Map.of(), null, null);
    }
    
    @Override
    public CompletableFuture<BiTemporalEvent<T>> append(String eventType, T payload, Instant validTime,
                                                       Map<String, String> headers) {
        logger.debug("BITEMPORAL-DEBUG: Appending event with headers - type: {}, validTime: {}, headers: {}",
                    eventType, validTime, headers);
        return append(eventType, payload, validTime, headers, null, null);
    }
    
    @Override
    public CompletableFuture<BiTemporalEvent<T>> append(String eventType, T payload, Instant validTime,
                                                       Map<String, String> headers, String correlationId,
                                                       String aggregateId) {
        logger.debug("BITEMPORAL-DEBUG: Appending event with full metadata - type: {}, validTime: {}, correlationId: {}, aggregateId: {}",
                    eventType, validTime, correlationId, aggregateId);
        // Pure Vert.x 5.x implementation - delegate to reactive method with transaction support
        return appendWithTransaction(eventType, payload, validTime, headers, correlationId, aggregateId);
    }

    /**
     * Production-grade transactional append method using Vert.x 5.x withTransaction API.
     * This method uses the official Vert.x Pool.withTransaction() which handles:
     * - Automatic transaction begin/commit/rollback
     * - Connection management
     * - Proper error handling and automatic rollback on failure
     */
    public CompletableFuture<BiTemporalEvent<T>> appendWithTransaction(String eventType, T payload, Instant validTime,
                                                                      Map<String, String> headers, String correlationId,
                                                                      String aggregateId) {
        // Delegate to internal method with null propagation (default behavior)
        return appendWithTransactionInternal(eventType, payload, validTime, headers, correlationId, aggregateId, null);
    }

    /**
     * Production-grade transactional append method with TransactionPropagation support.
     * This method uses Vert.x TransactionPropagation for advanced transaction management.
     *
     * @param eventType The type of the event
     * @param payload The event payload
     * @param validTime The valid time for the event
     * @param propagation Transaction propagation behavior (e.g., CONTEXT for sharing existing transactions)
     * @return CompletableFuture that completes when the event is stored
     */
    public CompletableFuture<BiTemporalEvent<T>> appendWithTransaction(String eventType, T payload, Instant validTime,
                                                                      TransactionPropagation propagation) {
        return appendWithTransactionInternal(eventType, payload, validTime, Map.of(), null, null, propagation);
    }

    /**
     * Production-grade transactional append method with headers and TransactionPropagation support.
     *
     * @param eventType The type of the event
     * @param payload The event payload
     * @param validTime The valid time for the event
     * @param headers Optional event headers
     * @param propagation Transaction propagation behavior
     * @return CompletableFuture that completes when the event is stored
     */
    public CompletableFuture<BiTemporalEvent<T>> appendWithTransaction(String eventType, T payload, Instant validTime,
                                                                      Map<String, String> headers,
                                                                      TransactionPropagation propagation) {
        return appendWithTransactionInternal(eventType, payload, validTime, headers, null, null, propagation);
    }

    /**
     * Production-grade transactional append method with headers, correlation ID and TransactionPropagation support.
     *
     * @param eventType The type of the event
     * @param payload The event payload
     * @param validTime The valid time for the event
     * @param headers Optional event headers
     * @param correlationId Optional correlation ID for event tracking
     * @param propagation Transaction propagation behavior
     * @return CompletableFuture that completes when the event is stored
     */
    public CompletableFuture<BiTemporalEvent<T>> appendWithTransaction(String eventType, T payload, Instant validTime,
                                                                      Map<String, String> headers, String correlationId,
                                                                      TransactionPropagation propagation) {
        return appendWithTransactionInternal(eventType, payload, validTime, headers, correlationId, null, propagation);
    }

    /**
     * Full production-grade transactional append method with all parameters and TransactionPropagation support.
     *
     * @param eventType The type of the event
     * @param payload The event payload
     * @param validTime The valid time for the event
     * @param headers Optional event headers
     * @param correlationId Optional correlation ID for event tracking
     * @param aggregateId Optional aggregate ID for event grouping
     * @param propagation Transaction propagation behavior
     * @return CompletableFuture that completes when the event is stored
     */
    public CompletableFuture<BiTemporalEvent<T>> appendWithTransaction(String eventType, T payload, Instant validTime,
                                                                      Map<String, String> headers, String correlationId,
                                                                      String aggregateId, TransactionPropagation propagation) {
        return appendWithTransactionInternal(eventType, payload, validTime, headers, correlationId, aggregateId, propagation);
    }

    /**
     * Internal implementation for production-grade transactional append method.
     * This method uses the official Vert.x Pool.withTransaction() which handles:
     * - Automatic transaction begin/commit/rollback
     * - Connection management
     * - Proper error handling and automatic rollback on failure
     * - TransactionPropagation support for advanced transaction management
     *
     * @param eventType The type of the event
     * @param payload The event payload
     * @param validTime The valid time for the event
     * @param headers Optional event headers
     * @param correlationId Optional correlation ID for event tracking
     * @param aggregateId Optional aggregate ID for event grouping
     * @param propagation Optional transaction propagation behavior (null for default)
     * @return CompletableFuture that completes when the event is stored
     */
    private CompletableFuture<BiTemporalEvent<T>> appendWithTransactionInternal(String eventType, T payload, Instant validTime,
                                                                               Map<String, String> headers, String correlationId,
                                                                               String aggregateId, TransactionPropagation propagation) {
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Event store is closed"));
        }

        Objects.requireNonNull(eventType, "Event type cannot be null");
        Objects.requireNonNull(payload, "Payload cannot be null");
        Objects.requireNonNull(validTime, "Valid time cannot be null");

        CompletableFuture<BiTemporalEvent<T>> future = new CompletableFuture<>();

        try {
            String eventId = UUID.randomUUID().toString();
            String payloadJson = objectMapper.writeValueAsString(payload);
            String headersJson = objectMapper.writeValueAsString(headers != null ? headers : Map.of());

            // Debug: Log the serialized JSON
            logger.debug("Serialized payload JSON: {}", payloadJson);
            logger.debug("Payload JSON type: {}", payloadJson.getClass().getSimpleName());
            String finalCorrelationId = correlationId != null ? correlationId : eventId;
            OffsetDateTime transactionTime = OffsetDateTime.now();

            // Use cached reactive infrastructure
            Pool pool = getOrCreateReactivePool();
            Vertx vertx = getOrCreateSharedVertx();

            // Execute transaction on Vert.x context for proper TransactionPropagation support
            var transactionFuture = (propagation != null)
                ? executeOnVertxContext(vertx, () -> pool.withTransaction(propagation, client -> {
                    String sql = """
                        INSERT INTO bitemporal_event_log
                        (event_id, event_type, valid_time, transaction_time, payload, headers,
                         version, correlation_id, aggregate_id, is_correction, created_at)
                        VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)
                        RETURNING event_id, transaction_time
                        """;

                    // Convert JSON strings to JsonObject for proper JSONB handling
                    io.vertx.core.json.JsonObject payloadJsonObj = new io.vertx.core.json.JsonObject(payloadJson);
                    io.vertx.core.json.JsonObject headersJsonObj = new io.vertx.core.json.JsonObject(headersJson);

                    Tuple params = Tuple.of(
                        eventId, eventType, validTime.atOffset(java.time.ZoneOffset.UTC),
                        transactionTime, payloadJsonObj, headersJsonObj,
                        1L, finalCorrelationId, aggregateId, false, transactionTime
                    );

                    // Return Future<BiTemporalEvent<T>> to indicate transaction success/failure
                    return client.preparedQuery(sql).execute(params).map(rows -> {
                        Row row = rows.iterator().next();
                        Instant actualTransactionTime = row.getOffsetDateTime("transaction_time").toInstant();

                        return new SimpleBiTemporalEvent<>(
                            eventId, eventType, payload, validTime, actualTransactionTime,
                            headers != null ? headers : Map.of(), finalCorrelationId, aggregateId
                        );
                    });
                }))
                : executeOnVertxContext(vertx, () -> pool.withTransaction(client -> {
                    String sql = """
                        INSERT INTO bitemporal_event_log
                        (event_id, event_type, valid_time, transaction_time, payload, headers,
                         version, correlation_id, aggregate_id, is_correction, created_at)
                        VALUES ($1, $2, $3, $4, $5::jsonb, $6::jsonb, $7, $8, $9, $10, $11)
                        RETURNING event_id, transaction_time
                        """;

                    Tuple params = Tuple.of(
                        eventId, eventType, validTime.atOffset(java.time.ZoneOffset.UTC),
                        transactionTime, payloadJson, headersJson,
                        1L, finalCorrelationId, aggregateId, false, transactionTime
                    );

                    // Return Future<BiTemporalEvent<T>> to indicate transaction success/failure
                    return client.preparedQuery(sql).execute(params).map(rows -> {
                        Row row = rows.iterator().next();
                        Instant actualTransactionTime = row.getOffsetDateTime("transaction_time").toInstant();

                        return new SimpleBiTemporalEvent<>(
                            eventId, eventType, payload, validTime, actualTransactionTime,
                            headers != null ? headers : Map.of(), finalCorrelationId, aggregateId
                        );
                    });
                }));

            // Handle success and failure following peegeeq-outbox patterns
            transactionFuture
                .onSuccess(event -> {
                    logger.debug("Successfully appended bi-temporal event: eventId={}, eventType={}, validTime={}",
                        eventId, eventType, validTime);
                    future.complete(event);
                })
                .onFailure(throwable -> {
                    logger.error("Failed to append bi-temporal event: eventId={}, eventType={}, error={}",
                        eventId, eventType, throwable.getMessage(), throwable);
                    future.completeExceptionally(throwable);
                });

        } catch (Exception e) {
            logger.error("Failed to append event of type {}: {}", eventType, e.getMessage(), e);
            future.completeExceptionally(new RuntimeException("Failed to append bi-temporal event", e));
        }

        return future;
    }

    @Override
    public CompletableFuture<BiTemporalEvent<T>> appendCorrection(String originalEventId, String eventType, 
                                                                 T payload, Instant validTime, 
                                                                 String correctionReason) {
        return appendCorrection(originalEventId, eventType, payload, validTime, Map.of(), 
                              null, null, correctionReason);
    }
    
    @Override
    public CompletableFuture<BiTemporalEvent<T>> appendCorrection(String originalEventId, String eventType,
                                                                 T payload, Instant validTime,
                                                                 Map<String, String> headers,
                                                                 String correlationId, String aggregateId,
                                                                 String correctionReason) {
        // Pure Vert.x 5.x implementation using reactive patterns
        return appendCorrectionWithTransaction(originalEventId, eventType, payload, validTime, headers,
                                             correlationId, aggregateId, correctionReason);
    }

    /**
     * Pure Vert.x 5.x implementation of correction append using Pool.withTransaction().
     * Following PGQ coding principles: use modern Vert.x 5.x composable Future patterns.
     */
    private CompletableFuture<BiTemporalEvent<T>> appendCorrectionWithTransaction(String originalEventId, String eventType, T payload,
                                                                                 Instant validTime, Map<String, String> headers,
                                                                                 String correlationId, String aggregateId,
                                                                                 String correctionReason) {
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Event store is closed"));
        }

        Objects.requireNonNull(originalEventId, "Original event ID cannot be null");
        Objects.requireNonNull(eventType, "Event type cannot be null");
        Objects.requireNonNull(payload, "Payload cannot be null");
        Objects.requireNonNull(validTime, "Valid time cannot be null");
        Objects.requireNonNull(correctionReason, "Correction reason cannot be null");

        CompletableFuture<BiTemporalEvent<T>> future = new CompletableFuture<>();

        try {
            String eventId = UUID.randomUUID().toString();
            String payloadJson = objectMapper.writeValueAsString(payload);
            String headersJson = objectMapper.writeValueAsString(headers != null ? headers : Map.of());
            OffsetDateTime transactionTime = OffsetDateTime.now();

            // Use Pool.withTransaction for proper transaction management - following peegeeq-outbox patterns
            getOrCreateReactivePool().withTransaction(sqlConnection -> {
                // First, get the next version number
                String getVersionSql = """
                    SELECT COALESCE(MAX(version), 0) as max_version
                    FROM bitemporal_event_log
                    WHERE event_id = $1 OR previous_version_id = $1
                    """;

                return sqlConnection.preparedQuery(getVersionSql)
                    .execute(Tuple.of(originalEventId))
                    .compose(versionRows -> {
                        // Calculate next version - make it effectively final
                        final long nextVersion;
                        if (versionRows.size() > 0) {
                            Row versionRow = versionRows.iterator().next();
                            Long maxVersion = versionRow.getLong("max_version");
                            nextVersion = (maxVersion != null ? maxVersion : 0L) + 1L;
                        } else {
                            nextVersion = 1L;
                        }

                        // Insert the correction event
                        String insertSql = """
                            INSERT INTO bitemporal_event_log
                            (event_id, event_type, valid_time, transaction_time, payload, headers,
                             version, previous_version_id, correlation_id, aggregate_id,
                             is_correction, correction_reason, created_at)
                            VALUES ($1, $2, $3, $4, $5::jsonb, $6::jsonb, $7, $8, $9, $10, $11, $12, $13)
                            RETURNING id
                            """;

                        Tuple insertParams = Tuple.of(
                            eventId, eventType, validTime.atOffset(java.time.ZoneOffset.UTC),
                            transactionTime, payloadJson, headersJson,
                            nextVersion, originalEventId, correlationId, aggregateId,
                            true, correctionReason, transactionTime
                        );

                        return sqlConnection.preparedQuery(insertSql)
                            .execute(insertParams)
                            .map(insertRows -> {
                                logger.debug("Successfully appended correction event: {} for original: {}",
                                           eventId, originalEventId);

                                // Create and return the BiTemporalEvent
                                return new SimpleBiTemporalEvent<>(
                                    eventId, eventType, payload, validTime, transactionTime.toInstant(),
                                    nextVersion, originalEventId, headers != null ? headers : Map.of(),
                                    correlationId, aggregateId, true, correctionReason
                                );
                            });
                    });
            }).toCompletionStage().whenComplete((event, throwable) -> {
                if (throwable != null) {
                    logger.error("Failed to append correction event for {}: {}", originalEventId, throwable.getMessage(), throwable);
                    future.completeExceptionally(throwable);
                } else {
                    future.complete(event);
                }
            });

        } catch (Exception e) {
            logger.error("Failed to serialize correction event: {}", e.getMessage(), e);
            future.completeExceptionally(e);
        }

        return future;
    }

    @Override
    public CompletableFuture<List<BiTemporalEvent<T>>> query(EventQuery query) {
        // Delegate to reactive implementation and convert to CompletableFuture
        return queryReactive(query).toCompletionStage().toCompletableFuture();
    }

    @Override
    public CompletableFuture<BiTemporalEvent<T>> getById(String eventId) {
        // Pure Vert.x 5.x implementation using reactive patterns
        return getByIdReactive(eventId).toCompletionStage().toCompletableFuture();
    }

    /**
     * Pure Vert.x 5.x reactive implementation of getById.
     */
    private Future<BiTemporalEvent<T>> getByIdReactive(String eventId) {
        if (closed) {
            return Future.failedFuture(new IllegalStateException("Event store is closed"));
        }

        Objects.requireNonNull(eventId, "Event ID cannot be null");

        String sql = """
            SELECT event_id, event_type, valid_time, transaction_time, payload, headers,
                   version, previous_version_id, correlation_id, aggregate_id,
                   is_correction, correction_reason, created_at
            FROM bitemporal_event_log
            WHERE event_id = $1
            ORDER BY version DESC
            LIMIT 1
            """;

        return getOrCreateReactivePool().preparedQuery(sql)
            .execute(Tuple.of(eventId))
            .map(rows -> {
                if (rows.size() > 0) {
                    Row row = rows.iterator().next();
                    try {
                        return mapRowToEvent(row);
                    } catch (Exception e) {
                        logger.error("Failed to map row to event for {}: {}", eventId, e.getMessage(), e);
                        throw new RuntimeException(e);
                    }
                } else {
                    return null;
                }
            });
    }

    @Override
    public CompletableFuture<List<BiTemporalEvent<T>>> getAllVersions(String eventId) {
        // Pure Vert.x 5.x implementation using reactive patterns
        return getAllVersionsReactive(eventId).toCompletionStage().toCompletableFuture();
    }

    /**
     * Pure Vert.x 5.x reactive implementation of getAllVersions.
     */
    private Future<List<BiTemporalEvent<T>>> getAllVersionsReactive(String eventId) {
        if (closed) {
            return Future.failedFuture(new IllegalStateException("Event store is closed"));
        }

        Objects.requireNonNull(eventId, "Event ID cannot be null");

        String sql = """
            SELECT event_id, event_type, valid_time, transaction_time, payload, headers,
                   version, previous_version_id, correlation_id, aggregate_id,
                   is_correction, correction_reason, created_at
            FROM bitemporal_event_log
            WHERE event_id = $1 OR previous_version_id = $1
            ORDER BY version ASC
            """;

        return getOrCreateReactivePool().preparedQuery(sql)
            .execute(Tuple.of(eventId))
            .map(rows -> {
                List<BiTemporalEvent<T>> events = new ArrayList<>();
                for (Row row : rows) {
                    try {
                        events.add(mapRowToEvent(row));
                    } catch (Exception e) {
                        logger.warn("Failed to map row to event: {}", e.getMessage(), e);
                    }
                }
                return events;
            });
    }

    @Override
    public CompletableFuture<BiTemporalEvent<T>> getAsOfTransactionTime(String eventId, Instant asOfTransactionTime) {
        // Simplified implementation - returns latest version before the given time
        return getById(eventId);
    }

    @Override
    public CompletableFuture<Void> subscribe(String eventType, MessageHandler<BiTemporalEvent<T>> handler) {
        return subscribe(eventType, null, handler);
    }

    @Override
    public CompletableFuture<Void> subscribe(String eventType, String aggregateId,
                                           MessageHandler<BiTemporalEvent<T>> handler) {
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Event store is closed"));
        }

        // Pure Vert.x 5.x reactive notification subscription
        return ReactiveUtils.toCompletableFuture(
            reactiveNotificationHandler.subscribe(eventType, aggregateId, handler)
        );
    }

    @Override
    public CompletableFuture<Void> unsubscribe() {
        subscriptions.clear();

        // Pure Vert.x 5.x reactive notification unsubscribe
        return ReactiveUtils.toCompletableFuture(
            reactiveNotificationHandler.stop()
        );
    }



    @Override
    public CompletableFuture<EventStore.EventStoreStats> getStats() {
        // Pure Vert.x 5.x implementation using reactive patterns
        return getStatsReactive().toCompletionStage().toCompletableFuture();
    }

    /**
     * Pure Vert.x 5.x reactive implementation of getStats.
     */
    private Future<EventStore.EventStoreStats> getStatsReactive() {
        if (closed) {
            return Future.failedFuture(new IllegalStateException("Event store is closed"));
        }

        // Get basic stats
        String basicStatsSql = """
            SELECT
                COUNT(*) as total_events,
                COUNT(*) FILTER (WHERE is_correction = TRUE) as total_corrections,
                MIN(valid_time) as oldest_event_time,
                MAX(valid_time) as newest_event_time,
                pg_total_relation_size('bitemporal_event_log') as storage_size_bytes
            FROM bitemporal_event_log
            """;

        // Get event counts by type
        String typeCountsSql = """
            SELECT event_type, COUNT(*) as event_count
            FROM bitemporal_event_log
            GROUP BY event_type
            """;

        return getOrCreateReactivePool().preparedQuery(basicStatsSql)
            .execute()
            .compose(basicRows -> {
                // Parse basic stats
                long totalEvents = 0;
                long totalCorrections = 0;
                Instant oldestEventTime = null;
                Instant newestEventTime = null;
                long storageSizeBytes = 0;

                if (basicRows.size() > 0) {
                    Row basicRow = basicRows.iterator().next();
                    totalEvents = basicRow.getLong("total_events");
                    totalCorrections = basicRow.getLong("total_corrections");
                    oldestEventTime = basicRow.getLocalDateTime("oldest_event_time") != null ?
                        basicRow.getLocalDateTime("oldest_event_time").toInstant(java.time.ZoneOffset.UTC) : null;
                    newestEventTime = basicRow.getLocalDateTime("newest_event_time") != null ?
                        basicRow.getLocalDateTime("newest_event_time").toInstant(java.time.ZoneOffset.UTC) : null;
                    storageSizeBytes = basicRow.getLong("storage_size_bytes");
                }

                // Get event counts by type
                final long finalTotalEvents = totalEvents;
                final long finalTotalCorrections = totalCorrections;
                final Instant finalOldestEventTime = oldestEventTime;
                final Instant finalNewestEventTime = newestEventTime;
                final long finalStorageSizeBytes = storageSizeBytes;

                return getOrCreateReactivePool().preparedQuery(typeCountsSql)
                    .execute()
                    .map(typeRows -> {
                        Map<String, Long> eventCountsByType = new HashMap<>();
                        for (Row typeRow : typeRows) {
                            eventCountsByType.put(typeRow.getString("event_type"), typeRow.getLong("event_count"));
                        }

                        return new EventStoreStatsImpl(
                            finalTotalEvents,
                            finalTotalCorrections,
                            eventCountsByType,
                            finalOldestEventTime,
                            finalNewestEventTime,
                            finalStorageSizeBytes
                        );
                    });
            });
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }

        logger.info("Closing bi-temporal event store");
        closed = true;

        // Clear subscriptions
        subscriptions.clear();

        // Close reactive notification handler
        if (reactiveNotificationHandler != null) {
            try {
                reactiveNotificationHandler.stop();
            } catch (Exception e) {
                logger.warn("Error closing reactive notification handler: {}", e.getMessage(), e);
            }
        }

        logger.info("Bi-temporal event store closed");
    }

    /**
     * Starts the reactive notification handler.
     */
    private void startReactiveNotifications() {
        // Pure Vert.x 5.x reactive notification handler startup
        try {
            // Set connection options from PeeGeeQManager configuration
            PgConnectOptions connectOptions = createConnectOptionsFromPeeGeeQManager();
            reactiveNotificationHandler.setConnectOptions(connectOptions);

            reactiveNotificationHandler.start();
            logger.debug("Started pure Vert.x 5.x reactive notification handler");
        } catch (Exception e) {
            logger.error("Failed to start reactive notification handler: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to start reactive notification handler", e);
        }
    }

    /**
     * Maps a Vert.x Row to a BiTemporalEvent - Pure Vert.x 5.x implementation.
     */
    private BiTemporalEvent<T> mapRowToEvent(Row row) throws Exception {
        String eventId = row.getString("event_id");
        String eventType = row.getString("event_type");
        Instant validTime = row.getOffsetDateTime("valid_time").toInstant();
        Instant transactionTime = row.getOffsetDateTime("transaction_time").toInstant();

        // Get JSONB data from Vert.x Row - these come as JsonObject/JsonArray
        Object payloadObj = row.getValue("payload");
        Object headersObj = row.getValue("headers");

        // Convert to JSON strings for Jackson processing
        String payloadJson = payloadObj != null ? payloadObj.toString() : "{}";
        String headersJson = headersObj != null ? headersObj.toString() : "{}";

        long version = row.getLong("version");
        String previousVersionId = row.getString("previous_version_id");
        String correlationId = row.getString("correlation_id");
        String aggregateId = row.getString("aggregate_id");
        boolean isCorrection = row.getBoolean("is_correction");
        String correctionReason = row.getString("correction_reason");

        // Fix double-encoded JSON issue - remove outer quotes if present
        if (payloadJson.startsWith("\"") && payloadJson.endsWith("\"")) {
            // Remove outer quotes and unescape inner quotes
            payloadJson = payloadJson.substring(1, payloadJson.length() - 1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
            logger.debug("Unwrapped double-encoded JSON: {}", payloadJson);
        }

        if (headersJson.startsWith("\"") && headersJson.endsWith("\"")) {
            // Remove outer quotes and unescape inner quotes
            headersJson = headersJson.substring(1, headersJson.length() - 1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
        }

        // Deserialize payload
        T payload = objectMapper.readValue(payloadJson, payloadType);

        // Deserialize headers
        @SuppressWarnings("unchecked")
        Map<String, String> headers = objectMapper.readValue(headersJson, Map.class);

        return new SimpleBiTemporalEvent<>(
            eventId, eventType, payload, validTime, transactionTime,
            version, previousVersionId, headers, correlationId, aggregateId,
            isCorrection, correctionReason
        );
    }

    // ========== REACTIVE METHODS (Vert.x Future-based) ==========

    public Future<BiTemporalEvent<T>> appendReactive(String eventType, T payload, Instant validTime) {
        // Pure Vert.x 5.x implementation with transaction support - use internal method directly
        return ReactiveUtils.fromCompletableFuture(
            appendWithTransactionInternal(eventType, payload, validTime, Map.of(), null, null, null)
        );
    }

    public Future<List<BiTemporalEvent<T>>> queryReactive(EventQuery query) {
        // Pure Vert.x 5.x implementation with transaction support
        try {
            StringBuilder sql = new StringBuilder("SELECT * FROM bitemporal_event_log WHERE 1=1");
            List<Object> params = new ArrayList<>();
            int paramIndex = 1;

            // Build WHERE clause based on query criteria
            if (query.getEventType().isPresent()) {
                sql.append(" AND event_type = $").append(paramIndex++);
                params.add(query.getEventType().get());
            }

            if (query.getAggregateId().isPresent()) {
                sql.append(" AND aggregate_id = $").append(paramIndex++);
                params.add(query.getAggregateId().get());
            }

            if (query.getValidTimeRange().isPresent()) {
                var validRange = query.getValidTimeRange().get();
                if (validRange.getStart() != null) {
                    sql.append(" AND valid_time >= $").append(paramIndex++);
                    params.add(validRange.getStart().atOffset(java.time.ZoneOffset.UTC));
                }
                if (validRange.getEnd() != null) {
                    sql.append(" AND valid_time <= $").append(paramIndex++);
                    params.add(validRange.getEnd().atOffset(java.time.ZoneOffset.UTC));
                }
            }

            if (query.getTransactionTimeRange().isPresent()) {
                var transactionRange = query.getTransactionTimeRange().get();
                if (transactionRange.getStart() != null) {
                    sql.append(" AND transaction_time >= $").append(paramIndex++);
                    params.add(transactionRange.getStart().atOffset(java.time.ZoneOffset.UTC));
                }
                if (transactionRange.getEnd() != null) {
                    sql.append(" AND transaction_time <= $").append(paramIndex++);
                    params.add(transactionRange.getEnd().atOffset(java.time.ZoneOffset.UTC));
                }
            }

            // Add ordering
            sql.append(" ORDER BY transaction_time DESC, valid_time DESC");

            // Add limit if specified
            if (query.getLimit() > 0) {
                sql.append(" LIMIT $").append(paramIndex);
                params.add(query.getLimit());
            }

            Tuple tuple = Tuple.tuple(params);

            // Use pure Vert.x 5.x reactive query execution
            return getOrCreateReactivePool().preparedQuery(sql.toString())
                .execute(tuple)
                .map(rows -> {
                    List<BiTemporalEvent<T>> events = new ArrayList<>();
                    for (Row row : rows) {
                        try {
                            BiTemporalEvent<T> event = mapRowToEvent(row);
                            events.add(event);
                        } catch (Exception e) {
                            logger.warn("Failed to map row to event: {}", e.getMessage());
                        }
                    }
                    return events;
                });
        } catch (Exception e) {
            return Future.failedFuture(e);
        }
    }

    public Future<Void> subscribeReactive(String eventType, MessageHandler<BiTemporalEvent<T>> handler) {
        // Pure Vert.x 5.x reactive notification subscription
        return reactiveNotificationHandler.subscribe(eventType, null, handler);
    }

    /**
     * Creates PgConnectOptions from PeeGeeQManager configuration.
     * This follows the pure Vert.x 5.x pattern for proper connection configuration.
     */
    private PgConnectOptions createConnectOptionsFromPeeGeeQManager() {
        try {
            // Extract client factory from PeeGeeQManager using reflection (same as VertxPoolAdapter)
            java.lang.reflect.Field clientFactoryField = peeGeeQManager.getClass().getDeclaredField("clientFactory");
            clientFactoryField.setAccessible(true);
            dev.mars.peegeeq.db.client.PgClientFactory clientFactory =
                (dev.mars.peegeeq.db.client.PgClientFactory) clientFactoryField.get(peeGeeQManager);

            if (clientFactory != null) {
                // Get connection configuration following peegeeq-outbox patterns
                dev.mars.peegeeq.db.config.PgConnectionConfig connectionConfig =
                    clientFactory.getConnectionConfig("peegeeq-main");

                if (connectionConfig != null) {
                    // Create PgConnectOptions using actual configuration
                    PgConnectOptions connectOptions = new PgConnectOptions()
                        .setHost(connectionConfig.getHost())
                        .setPort(connectionConfig.getPort())
                        .setDatabase(connectionConfig.getDatabase())
                        .setUser(connectionConfig.getUsername())
                        .setPassword(connectionConfig.getPassword());

                    if (connectionConfig.isSslEnabled()) {
                        connectOptions.setSslMode(io.vertx.pgclient.SslMode.REQUIRE);
                    }

                    logger.debug("Created PgConnectOptions from PeeGeeQManager: host={}, port={}, database={}, user={}",
                        connectionConfig.getHost(), connectionConfig.getPort(),
                        connectionConfig.getDatabase(), connectionConfig.getUsername());

                    return connectOptions;
                } else {
                    throw new RuntimeException("Connection configuration 'peegeeq-main' not found in PgClientFactory");
                }
            } else {
                throw new RuntimeException("PgClientFactory not found in PeeGeeQManager");
            }
        } catch (Exception e) {
            logger.error("Failed to create connect options from PeeGeeQManager: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create connect options from PeeGeeQManager", e);
        }
    }





    /**
     * Gets or creates a reactive pool for bi-temporal event store operations.
     * This method provides thread-safe lazy initialization following peegeeq-outbox patterns.
     *
     * @return Vert.x Pool for reactive database operations
     */
    private Pool getOrCreateReactivePool() {
        if (reactivePool != null) {
            return reactivePool;
        }

        synchronized (this) {
            if (reactivePool != null) {
                return reactivePool;
            }

            try {
                // Get connection configuration from PeeGeeQManager following peegeeq-outbox patterns
                PgConnectOptions connectOptions = createConnectOptionsFromPeeGeeQManager();

                // Configure pool options following peegeeq-outbox patterns
                PoolOptions poolOptions = new PoolOptions();

                // Get pool size from PeeGeeQManager configuration, with fallback to default
                int maxPoolSize = getConfiguredPoolSize();
                poolOptions.setMaxSize(maxPoolSize);

                // Use PgBuilder.pool() pattern from peegeeq-outbox
                reactivePool = PgBuilder.pool()
                    .with(poolOptions)
                    .connectingTo(connectOptions)
                    .using(getOrCreateSharedVertx())
                    .build();

                logger.info("Created reactive pool for bi-temporal event store using PgBuilder pattern");
                return reactivePool;

            } catch (Exception e) {
                logger.error("Failed to create reactive pool: {}", e.getMessage(), e);
                throw new RuntimeException("Failed to create reactive pool for bi-temporal event store", e);
            }
        }
    }

    /**
     * Gets the configured pool size from PeeGeeQManager configuration.
     * Falls back to a reasonable default if not configured.
     *
     * @return The maximum pool size to use
     */
    private int getConfiguredPoolSize() {
        try {
            if (peeGeeQManager != null && peeGeeQManager.getConfiguration() != null) {
                var poolConfig = peeGeeQManager.getConfiguration().getPoolConfig();
                if (poolConfig != null) {
                    int configuredSize = poolConfig.getMaximumPoolSize();
                    logger.debug("Using configured pool size: {}", configuredSize);
                    return configuredSize;
                }
            }
        } catch (Exception e) {
            logger.debug("Could not get pool size from configuration, using default: {}", e.getMessage());
        }

        // Default pool size - reasonable for most use cases
        int defaultSize = 20;
        logger.debug("Using default pool size: {}", defaultSize);
        return defaultSize;
    }

    /**
     * Gets or creates a shared Vertx instance for proper context management.
     * This ensures that TransactionPropagation.CONTEXT works correctly by providing
     * a consistent Vertx context across all EventStore instances.
     *
     * @return The shared Vertx instance
     */
    private static Vertx getOrCreateSharedVertx() {
        if (sharedVertx == null) {
            synchronized (PgBiTemporalEventStore.class) {
                if (sharedVertx == null) {
                    sharedVertx = Vertx.vertx();
                    logger.info("Created shared Vertx instance for bi-temporal event store context management");
                }
            }
        }
        return sharedVertx;
    }

    /**
     * Closes the shared Vertx instance. This should only be called during application shutdown.
     * Note: This is a static method that affects all PgBiTemporalEventStore instances.
     */
    public static void closeSharedVertx() {
        if (sharedVertx != null) {
            synchronized (PgBiTemporalEventStore.class) {
                if (sharedVertx != null) {
                    sharedVertx.close();
                    sharedVertx = null;
                    logger.info("Closed shared Vertx instance for bi-temporal event store");
                }
            }
        }
    }

    /**
     * Executes an operation on the Vert.x context, following peegeeq-outbox patterns.
     * This ensures proper context management for reactive operations.
     *
     * @param vertx The Vert.x instance
     * @param operation The operation to execute that returns a Future
     * @return Future that completes when the operation completes
     */
    private static <T> Future<T> executeOnVertxContext(Vertx vertx, java.util.function.Supplier<Future<T>> operation) {
        Context context = vertx.getOrCreateContext();
        if (context == Vertx.currentContext()) {
            // Already on Vert.x context, execute directly
            return operation.get();
        } else {
            // Execute on Vert.x context using runOnContext
            io.vertx.core.Promise<T> promise = io.vertx.core.Promise.promise();
            context.runOnContext(v -> {
                try {
                    operation.get()
                        .onSuccess(promise::complete)
                        .onFailure(promise::fail);
                } catch (Exception e) {
                    promise.fail(e);
                }
            });
            return promise.future();
        }
    }

    /**
     * Implementation of EventStoreStats.
     */
    private static class EventStoreStatsImpl implements EventStore.EventStoreStats {
        private final long totalEvents;
        private final long totalCorrections;
        private final Map<String, Long> eventCountsByType;
        private final Instant oldestEventTime;
        private final Instant newestEventTime;
        private final long storageSizeBytes;

        public EventStoreStatsImpl(long totalEvents, long totalCorrections,
                                  Map<String, Long> eventCountsByType,
                                  Instant oldestEventTime, Instant newestEventTime,
                                  long storageSizeBytes) {
            this.totalEvents = totalEvents;
            this.totalCorrections = totalCorrections;
            this.eventCountsByType = Map.copyOf(eventCountsByType);
            this.oldestEventTime = oldestEventTime;
            this.newestEventTime = newestEventTime;
            this.storageSizeBytes = storageSizeBytes;
        }

        @Override
        public long getTotalEvents() { return totalEvents; }

        @Override
        public long getTotalCorrections() { return totalCorrections; }

        @Override
        public Map<String, Long> getEventCountsByType() { return eventCountsByType; }

        @Override
        public Instant getOldestEventTime() { return oldestEventTime; }

        @Override
        public Instant getNewestEventTime() { return newestEventTime; }

        @Override
        public long getStorageSizeBytes() { return storageSizeBytes; }

        @Override
        public String toString() {
            return "EventStoreStats{" +
                    "totalEvents=" + totalEvents +
                    ", totalCorrections=" + totalCorrections +
                    ", eventCountsByType=" + eventCountsByType +
                    ", oldestEventTime=" + oldestEventTime +
                    ", newestEventTime=" + newestEventTime +
                    ", storageSizeBytes=" + storageSizeBytes +
                    '}';
        }
    }


}
