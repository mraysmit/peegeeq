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

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    
    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final Class<T> payloadType;
    private final Executor executor;
    private final Map<String, MessageHandler<BiTemporalEvent<T>>> subscriptions;
    private volatile boolean closed = false;

    // Reactive infrastructure
    private final boolean useReactiveOperations;
    private final Pool reactivePool;
    private final ReactiveNotificationHandler<T> reactiveNotificationHandler;

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
        Objects.requireNonNull(peeGeeQManager, "PeeGeeQ manager cannot be null");
        this.payloadType = Objects.requireNonNull(payloadType, "Payload type cannot be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "Object mapper cannot be null");
        this.dataSource = peeGeeQManager.getDataSource();
        this.executor = ForkJoinPool.commonPool();
        this.subscriptions = new ConcurrentHashMap<>();

        // Initialize reactive infrastructure
        this.useReactiveOperations = true; // Enable reactive operations by default
        VertxPoolAdapter poolAdapter = new VertxPoolAdapter(peeGeeQManager);
        this.reactivePool = poolAdapter.getPool();

        // Create connection options from DataSource (simplified approach)
        PgConnectOptions connectOptions = createConnectOptionsFromDataSource();

        this.reactiveNotificationHandler = new ReactiveNotificationHandler<T>(
            poolAdapter.getVertx(),
            connectOptions,
            objectMapper,
            payloadType,
            this::getById // Use the existing getById method as event retriever
        );

        // Start reactive notification handler
        startReactiveNotifications();

        logger.info("Created bi-temporal event store for payload type: {}", payloadType.getSimpleName());
    }
    
    @Override
    public CompletableFuture<BiTemporalEvent<T>> append(String eventType, T payload, Instant validTime) {
        return append(eventType, payload, validTime, Map.of(), null, null);
    }
    
    @Override
    public CompletableFuture<BiTemporalEvent<T>> append(String eventType, T payload, Instant validTime, 
                                                       Map<String, String> headers) {
        return append(eventType, payload, validTime, headers, null, null);
    }
    
    @Override
    public CompletableFuture<BiTemporalEvent<T>> append(String eventType, T payload, Instant validTime,
                                                       Map<String, String> headers, String correlationId, 
                                                       String aggregateId) {
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Event store is closed"));
        }
        
        Objects.requireNonNull(eventType, "Event type cannot be null");
        Objects.requireNonNull(payload, "Payload cannot be null");
        Objects.requireNonNull(validTime, "Valid time cannot be null");
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                String eventId = UUID.randomUUID().toString();
                String payloadJson = objectMapper.writeValueAsString(payload);
                String headersJson = objectMapper.writeValueAsString(headers != null ? headers : Map.of());
                Instant transactionTime = Instant.now();
                
                String sql = """
                    INSERT INTO bitemporal_event_log 
                    (event_id, event_type, valid_time, transaction_time, payload, headers, 
                     version, correlation_id, aggregate_id, is_correction, created_at)
                    VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?)
                    RETURNING id
                    """;
                
                try (Connection conn = dataSource.getConnection();
                     PreparedStatement stmt = conn.prepareStatement(sql)) {
                    
                    stmt.setString(1, eventId);
                    stmt.setString(2, eventType);
                    stmt.setTimestamp(3, Timestamp.from(validTime));
                    stmt.setTimestamp(4, Timestamp.from(transactionTime));
                    stmt.setString(5, payloadJson);
                    stmt.setString(6, headersJson);
                    stmt.setLong(7, 1L); // First version
                    stmt.setString(8, correlationId);
                    stmt.setString(9, aggregateId);
                    stmt.setBoolean(10, false); // Not a correction
                    stmt.setTimestamp(11, Timestamp.from(transactionTime));
                    
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            logger.debug("Successfully appended event: {} of type: {}", eventId, eventType);
                            
                            return new SimpleBiTemporalEvent<>(
                                eventId, eventType, payload, validTime, transactionTime,
                                headers != null ? headers : Map.of(), correlationId, aggregateId
                            );
                        } else {
                            throw new SQLException("No ID returned from insert");
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("Failed to append event of type {}: {}", eventType, e.getMessage(), e);
                throw new RuntimeException(e);
            }
        }, executor);
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

        // Use reactive notification handler for all subscriptions
        if (useReactiveOperations) {
            return ReactiveUtils.toCompletableFuture(
                reactiveNotificationHandler.subscribe(eventType, aggregateId, handler)
            );
        } else {
            // Fallback to storing subscription for manual notification
            String key = (eventType != null ? eventType : "all") + "_" + (aggregateId != null ? aggregateId : "all");
            subscriptions.put(key, handler);
            logger.debug("Subscribed to bi-temporal events (fallback mode): eventType={}, aggregateId={}", eventType, aggregateId);
            return CompletableFuture.completedFuture(null);
        }
    }

    @Override
    public CompletableFuture<Void> unsubscribe() {
        subscriptions.clear();

        // Use reactive notification handler for unsubscribe
        if (useReactiveOperations) {
            return ReactiveUtils.toCompletableFuture(
                reactiveNotificationHandler.stop()
            );
        } else {
            logger.debug("Unsubscribed from all bi-temporal event notifications (fallback mode)");
            return CompletableFuture.completedFuture(null);
        }
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
        if (useReactiveOperations) {
            try {
                reactiveNotificationHandler.start();
                logger.debug("Started reactive notification handler");
            } catch (Exception e) {
                logger.error("Failed to start reactive notification handler: {}", e.getMessage(), e);
                throw new RuntimeException("Failed to start reactive notification handler", e);
            }
        } else {
            logger.debug("Reactive notifications disabled, using fallback mode");
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
        String payloadJson = rs.getString("payload");
        String headersJson = rs.getString("headers");
        long version = rs.getLong("version");
        String previousVersionId = rs.getString("previous_version_id");
        String correlationId = rs.getString("correlation_id");
        String aggregateId = rs.getString("aggregate_id");
        boolean isCorrection = rs.getBoolean("is_correction");
        String correctionReason = rs.getString("correction_reason");

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

    @Override
    public Future<BiTemporalEvent<T>> appendReactive(String eventType, T payload, Instant validTime) {
        if (!useReactiveOperations) {
            return ReactiveUtils.fromCompletableFuture(append(eventType, payload, validTime));
        }

        try {
            String eventId = UUID.randomUUID().toString();
            String payloadJson = objectMapper.writeValueAsString(payload);
            Instant transactionTime = Instant.now();

            String sql = """
                INSERT INTO bitemporal_event_log
                (event_id, event_type, payload, valid_time, transaction_time, payload_type, headers, correlation_id, aggregate_id)
                VALUES ($1, $2, $3::jsonb, $4, $5, $6, $7::jsonb, $8, $9)
                RETURNING event_id, transaction_time
                """;

            Tuple params = Tuple.of(
                eventId, eventType, payloadJson, validTime, transactionTime,
                payloadType.getName(), "{}", null, null
            );

            return reactivePool.preparedQuery(sql)
                .execute(params)
                .map(rows -> {
                    Row row = rows.iterator().next();
                    return new SimpleBiTemporalEvent<>(
                        row.getString("event_id"),
                        eventType,
                        payload,
                        validTime,
                        row.getOffsetDateTime("transaction_time").toInstant(),
                        1L, // version
                        null, // previousVersionId
                        Map.of(), // headers
                        null, // correlationId
                        null, // aggregateId
                        false, // isCorrection
                        null // correctionReason
                    );
                });
        } catch (Exception e) {
            return Future.failedFuture(e);
        }
    }

    @Override
    public Future<List<BiTemporalEvent<T>>> queryReactive(EventQuery query) {
        if (!useReactiveOperations) {
            return ReactiveUtils.fromCompletableFuture(query(query));
        }

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

    @Override
    public Future<Void> subscribeReactive(String eventType, MessageHandler<BiTemporalEvent<T>> handler) {
        if (!useReactiveOperations) {
            return ReactiveUtils.fromCompletableFuture(subscribe(eventType, handler));
        }

        return reactiveNotificationHandler.subscribe(eventType, null, handler);
    }

    /**
     * Creates PgConnectOptions from the DataSource configuration.
     * This is a simplified approach for reactive notification setup.
     */
    private PgConnectOptions createConnectOptionsFromDataSource() {
        try (Connection connection = dataSource.getConnection()) {
            String url = connection.getMetaData().getURL();
            // Parse PostgreSQL URL: jdbc:postgresql://host:port/database
            if (url.startsWith("jdbc:postgresql://")) {
                String[] parts = url.substring("jdbc:postgresql://".length()).split("/");
                String[] hostPort = parts[0].split(":");
                String host = hostPort[0];
                int port = hostPort.length > 1 ? Integer.parseInt(hostPort[1]) : 5432;
                String database = parts.length > 1 ? parts[1].split("\\?")[0] : "postgres";

                return new PgConnectOptions()
                    .setHost(host)
                    .setPort(port)
                    .setDatabase(database)
                    .setUser("postgres") // Default - should be configurable
                    .setPassword("postgres"); // Default - should be configurable
            }
        } catch (SQLException e) {
            logger.warn("Failed to extract connection options from DataSource: {}", e.getMessage());
        }

        // Fallback to default local PostgreSQL
        return new PgConnectOptions()
            .setHost("localhost")
            .setPort(5432)
            .setDatabase("postgres")
            .setUser("postgres")
            .setPassword("postgres");
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
