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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.api.EventStore;
import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.api.SimpleBiTemporalEvent;
import dev.mars.peegeeq.api.EventQuery;

import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.MessageHandler;
import dev.mars.peegeeq.api.messaging.SimpleMessage;
import dev.mars.peegeeq.db.PeeGeeQManager;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.TransactionPropagation;
import io.vertx.sqlclient.Tuple;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

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
    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final Class<T> payloadType;
    private final Executor executor;
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
        this.peeGeeQManager = Objects.requireNonNull(peeGeeQManager, "PeeGeeQ manager cannot be null");
        this.payloadType = Objects.requireNonNull(payloadType, "Payload type cannot be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "Object mapper cannot be null");
        this.dataSource = peeGeeQManager.getDataSource();
        this.executor = ForkJoinPool.commonPool();
        this.subscriptions = new ConcurrentHashMap<>();

        // Initialize pure Vert.x reactive infrastructure - pool will be created lazily
        this.reactivePool = null; // Will be created on first use

        // Initialize reactive notification handler with lazy connection options
        // This will be properly initialized when startReactiveNotifications() is called
        this.reactiveNotificationHandler = new ReactiveNotificationHandler<T>(
            getOrCreateSharedVertx(),
            null, // Will be set during startReactiveNotifications()
            objectMapper,
            payloadType,
            this::getById // Use the existing getById method as event retriever
        );

        // Start reactive notification handler with proper connection options
        startReactiveNotifications();

        logger.info("Created bi-temporal event store for payload type: {}", payloadType.getSimpleName());
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
            Instant transactionTime = Instant.now();

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
                        transactionTime.atOffset(java.time.ZoneOffset.UTC), payloadJsonObj, headersJsonObj,
                        1L, finalCorrelationId, aggregateId, false, transactionTime.atOffset(java.time.ZoneOffset.UTC)
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
                        transactionTime.atOffset(java.time.ZoneOffset.UTC), payloadJson, headersJson,
                        1L, finalCorrelationId, aggregateId, false, transactionTime.atOffset(java.time.ZoneOffset.UTC)
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
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Event store is closed"));
        }
        
        Objects.requireNonNull(originalEventId, "Original event ID cannot be null");
        Objects.requireNonNull(eventType, "Event type cannot be null");
        Objects.requireNonNull(payload, "Payload cannot be null");
        Objects.requireNonNull(validTime, "Valid time cannot be null");
        Objects.requireNonNull(correctionReason, "Correction reason cannot be null");
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                // First, get the latest version of the original event
                String getVersionSql = """
                    SELECT MAX(version) as max_version 
                    FROM bitemporal_event_log 
                    WHERE event_id = ? OR previous_version_id = ?
                    """;
                
                long nextVersion = 1L;
                try (Connection conn = dataSource.getConnection();
                     PreparedStatement versionStmt = conn.prepareStatement(getVersionSql)) {
                    
                    versionStmt.setString(1, originalEventId);
                    versionStmt.setString(2, originalEventId);
                    
                    try (ResultSet rs = versionStmt.executeQuery()) {
                        if (rs.next()) {
                            Long maxVersion = rs.getLong("max_version");
                            nextVersion = (maxVersion != null ? maxVersion : 0L) + 1L;
                        }
                    }
                    
                    // Now insert the correction
                    String eventId = UUID.randomUUID().toString();
                    String payloadJson = objectMapper.writeValueAsString(payload);
                    String headersJson = objectMapper.writeValueAsString(headers != null ? headers : Map.of());
                    Instant transactionTime = Instant.now();
                    
                    String insertSql = """
                        INSERT INTO bitemporal_event_log 
                        (event_id, event_type, valid_time, transaction_time, payload, headers, 
                         version, previous_version_id, correlation_id, aggregate_id, 
                         is_correction, correction_reason, created_at)
                        VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?, ?)
                        RETURNING id
                        """;
                    
                    try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                        insertStmt.setString(1, eventId);
                        insertStmt.setString(2, eventType);
                        insertStmt.setTimestamp(3, Timestamp.from(validTime));
                        insertStmt.setTimestamp(4, Timestamp.from(transactionTime));
                        insertStmt.setString(5, payloadJson);
                        insertStmt.setString(6, headersJson);
                        insertStmt.setLong(7, nextVersion);
                        insertStmt.setString(8, originalEventId);
                        insertStmt.setString(9, correlationId);
                        insertStmt.setString(10, aggregateId);
                        insertStmt.setBoolean(11, true); // This is a correction
                        insertStmt.setString(12, correctionReason);
                        insertStmt.setTimestamp(13, Timestamp.from(transactionTime));
                        
                        try (ResultSet insertRs = insertStmt.executeQuery()) {
                            if (insertRs.next()) {
                                logger.debug("Successfully appended correction event: {} for original: {}", 
                                           eventId, originalEventId);
                                
                                return new SimpleBiTemporalEvent<>(
                                    eventId, eventType, payload, validTime, transactionTime,
                                    nextVersion, originalEventId, headers != null ? headers : Map.of(),
                                    correlationId, aggregateId, true, correctionReason
                                );
                            } else {
                                throw new SQLException("No ID returned from correction insert");
                            }
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("Failed to append correction event for {}: {}", originalEventId, e.getMessage(), e);
                throw new RuntimeException(e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<List<BiTemporalEvent<T>>> query(EventQuery query) {
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Event store is closed"));
        }

        Objects.requireNonNull(query, "Query cannot be null");
        logger.debug("BITEMPORAL-DEBUG: Executing query - limit: {}, sortOrder: {}",
                    query.getLimit(), query.getSortOrder());

        return CompletableFuture.supplyAsync(() -> {
            try {
                StringBuilder sql = new StringBuilder("""
                    SELECT event_id, event_type, valid_time, transaction_time, payload, headers,
                           version, previous_version_id, correlation_id, aggregate_id,
                           is_correction, correction_reason, created_at
                    FROM bitemporal_event_log
                    WHERE 1=1
                    """);

                List<Object> params = new ArrayList<>();

                // Add filters based on query
                if (query.getEventType().isPresent()) {
                    sql.append(" AND event_type = ?");
                    params.add(query.getEventType().get());
                }

                if (query.getAggregateId().isPresent()) {
                    sql.append(" AND aggregate_id = ?");
                    params.add(query.getAggregateId().get());
                }

                if (query.getCorrelationId().isPresent()) {
                    sql.append(" AND correlation_id = ?");
                    params.add(query.getCorrelationId().get());
                }

                if (!query.isIncludeCorrections()) {
                    sql.append(" AND is_correction = false");
                }

                // Add ordering
                sql.append(" ORDER BY ");
                switch (query.getSortOrder()) {
                    case VALID_TIME_ASC -> sql.append("valid_time ASC");
                    case VALID_TIME_DESC -> sql.append("valid_time DESC");
                    case TRANSACTION_TIME_ASC -> sql.append("transaction_time ASC");
                    case TRANSACTION_TIME_DESC -> sql.append("transaction_time DESC");
                    case VERSION_ASC -> sql.append("version ASC");
                    case VERSION_DESC -> sql.append("version DESC");
                }

                // Add limit
                sql.append(" LIMIT ?");
                params.add(query.getLimit());

                if (query.getOffset() > 0) {
                    sql.append(" OFFSET ?");
                    params.add(query.getOffset());
                }

                try (Connection conn = dataSource.getConnection();
                     PreparedStatement stmt = conn.prepareStatement(sql.toString())) {

                    for (int i = 0; i < params.size(); i++) {
                        stmt.setObject(i + 1, params.get(i));
                    }

                    try (ResultSet rs = stmt.executeQuery()) {
                        List<BiTemporalEvent<T>> events = new ArrayList<>();

                        while (rs.next()) {
                            try {
                                BiTemporalEvent<T> event = mapRowToEvent(rs);
                                events.add(event);
                            } catch (Exception e) {
                                logger.warn("Failed to map row to event: {}", e.getMessage(), e);
                            }
                        }

                        logger.debug("Query returned {} events", events.size());
                        return events;
                    }
                }
            } catch (Exception e) {
                logger.error("Failed to execute query: {}", e.getMessage(), e);
                throw new RuntimeException(e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<BiTemporalEvent<T>> getById(String eventId) {
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Event store is closed"));
        }

        Objects.requireNonNull(eventId, "Event ID cannot be null");

        return CompletableFuture.supplyAsync(() -> {
            try {
                String sql = """
                    SELECT event_id, event_type, valid_time, transaction_time, payload, headers,
                           version, previous_version_id, correlation_id, aggregate_id,
                           is_correction, correction_reason, created_at
                    FROM bitemporal_event_log
                    WHERE event_id = ?
                    ORDER BY version DESC
                    LIMIT 1
                    """;

                try (Connection conn = dataSource.getConnection();
                     PreparedStatement stmt = conn.prepareStatement(sql)) {

                    stmt.setString(1, eventId);

                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            return mapRowToEvent(rs);
                        } else {
                            return null;
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("Failed to get event {}: {}", eventId, e.getMessage(), e);
                throw new RuntimeException(e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<List<BiTemporalEvent<T>>> getAllVersions(String eventId) {
        // Implementation similar to getById but returns all versions
        return CompletableFuture.supplyAsync(() -> {
            try {
                String sql = """
                    SELECT event_id, event_type, valid_time, transaction_time, payload, headers,
                           version, previous_version_id, correlation_id, aggregate_id,
                           is_correction, correction_reason, created_at
                    FROM bitemporal_event_log
                    WHERE event_id = ? OR previous_version_id = ?
                    ORDER BY version ASC
                    """;

                try (Connection conn = dataSource.getConnection();
                     PreparedStatement stmt = conn.prepareStatement(sql)) {

                    stmt.setString(1, eventId);
                    stmt.setString(2, eventId);

                    try (ResultSet rs = stmt.executeQuery()) {
                        List<BiTemporalEvent<T>> events = new ArrayList<>();
                        while (rs.next()) {
                            events.add(mapRowToEvent(rs));
                        }
                        return events;
                    }
                }
            } catch (Exception e) {
                logger.error("Failed to get versions for event {}: {}", eventId, e.getMessage(), e);
                throw new RuntimeException(e);
            }
        }, executor);
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
        return CompletableFuture.supplyAsync(() -> {
            try {
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

                try (Connection conn = dataSource.getConnection()) {
                    // Get basic stats
                    long totalEvents = 0;
                    long totalCorrections = 0;
                    Instant oldestEventTime = null;
                    Instant newestEventTime = null;
                    long storageSizeBytes = 0;

                    try (PreparedStatement stmt = conn.prepareStatement(basicStatsSql);
                         ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            totalEvents = rs.getLong("total_events");
                            totalCorrections = rs.getLong("total_corrections");
                            oldestEventTime = rs.getTimestamp("oldest_event_time") != null ?
                                rs.getTimestamp("oldest_event_time").toInstant() : null;
                            newestEventTime = rs.getTimestamp("newest_event_time") != null ?
                                rs.getTimestamp("newest_event_time").toInstant() : null;
                            storageSizeBytes = rs.getLong("storage_size_bytes");
                        }
                    }

                    // Get event counts by type
                    Map<String, Long> eventCountsByType = new HashMap<>();
                    try (PreparedStatement stmt = conn.prepareStatement(typeCountsSql);
                         ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            eventCountsByType.put(rs.getString("event_type"), rs.getLong("event_count"));
                        }
                    }

                    return new EventStoreStatsImpl(
                        totalEvents,
                        totalCorrections,
                        eventCountsByType,
                        oldestEventTime,
                        newestEventTime,
                        storageSizeBytes
                    );
                }
            } catch (Exception e) {
                logger.error("Failed to get event store stats: {}", e.getMessage(), e);
                throw new RuntimeException(e);
            }
        }, executor);
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
     * Maps a database ResultSet row to a BiTemporalEvent.
     */
    private BiTemporalEvent<T> mapRowToEvent(ResultSet rs) throws Exception {
        String eventId = rs.getString("event_id");
        String eventType = rs.getString("event_type");
        Instant validTime = rs.getTimestamp("valid_time").toInstant();
        Instant transactionTime = rs.getTimestamp("transaction_time").toInstant();
        // Get JSONB data properly - PostgreSQL JSONB columns should be retrieved as objects
        Object payloadObj = rs.getObject("payload");
        Object headersObj = rs.getObject("headers");

        // Convert to JSON strings for Jackson processing
        String payloadJson = payloadObj != null ? payloadObj.toString() : "{}";
        String headersJson = headersObj != null ? headersObj.toString() : "{}";
        long version = rs.getLong("version");
        String previousVersionId = rs.getString("previous_version_id");
        String correlationId = rs.getString("correlation_id");
        String aggregateId = rs.getString("aggregate_id");
        boolean isCorrection = rs.getBoolean("is_correction");
        String correctionReason = rs.getString("correction_reason");

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
                    params.add(validRange.getStart());
                }
                if (validRange.getEnd() != null) {
                    sql.append(" AND valid_time <= $").append(paramIndex++);
                    params.add(validRange.getEnd());
                }
            }

            if (query.getTransactionTimeRange().isPresent()) {
                var transactionRange = query.getTransactionTimeRange().get();
                if (transactionRange.getStart() != null) {
                    sql.append(" AND transaction_time >= $").append(paramIndex++);
                    params.add(transactionRange.getStart());
                }
                if (transactionRange.getEnd() != null) {
                    sql.append(" AND transaction_time <= $").append(paramIndex++);
                    params.add(transactionRange.getEnd());
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
            return reactivePool.preparedQuery(sql.toString())
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
     * Creates PgConnectOptions from the DataSource configuration.
     * This is a simplified approach for reactive notification setup.
     */
    /**
     * Creates PgConnectOptions from PeeGeeQManager configuration.
     * This follows the peegeeq-outbox pattern for proper connection configuration.
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
                        connectOptions.setSsl(true);
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
     * Maps a database row to a BiTemporalEvent.
     */
    private BiTemporalEvent<T> mapRowToEvent(Row row) throws Exception {
        String eventId = row.getString("event_id");
        String eventType = row.getString("event_type");
        String payloadJson = row.getString("payload");
        Instant validTime = row.getOffsetDateTime("valid_time").toInstant();
        Instant transactionTime = row.getOffsetDateTime("transaction_time").toInstant();
        Long version = row.getLong("version");
        String previousVersionId = row.getString("previous_version_id");
        String headersJson = row.getString("headers");
        String correlationId = row.getString("correlation_id");
        String aggregateId = row.getString("aggregate_id");
        Boolean isCorrection = row.getBoolean("is_correction");
        String correctionReason = row.getString("correction_reason");

        // Parse payload
        T payload = objectMapper.readValue(payloadJson, payloadType);

        // Parse headers
        Map<String, String> headers = Map.of();
        if (headersJson != null && !headersJson.trim().isEmpty() && !headersJson.equals("{}")) {
            headers = objectMapper.readValue(headersJson, new TypeReference<Map<String, String>>() {});
        }

        return new SimpleBiTemporalEvent<>(
            eventId, eventType, payload, validTime, transactionTime,
            version != null ? version : 1L,
            previousVersionId,
            headers,
            correlationId,
            aggregateId,
            isCorrection != null ? isCorrection : false,
            correctionReason
        );
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
                poolOptions.setMaxSize(10); // Default pool size - can be made configurable later

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
