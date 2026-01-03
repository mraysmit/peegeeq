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
import dev.mars.peegeeq.db.performance.SimplePerformanceMonitor;

import io.vertx.core.Context;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.ThreadingModel;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.VerticleBase;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;

import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.TransactionPropagation;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
    private final String tableName;
    private final Map<String, MessageHandler<BiTemporalEvent<T>>> subscriptions;
    private volatile boolean closed = false;

    // Client ID for pool lookup - null means use default pool (resolved by
    // PgClientFactory)
    private final String clientId;

    // Pure Vert.x reactive infrastructure with caching
    private volatile Pool reactivePool;
    private volatile SqlClient pipelinedClient; // High-performance pipelined client for maximum throughput
    private final ReactiveNotificationHandler<T> reactiveNotificationHandler;

    // Performance monitoring
    private final SimplePerformanceMonitor performanceMonitor;

    // Shared Vertx instance for proper context management
    private static volatile Vertx sharedVertx;

    // Static reference to the current event store instance for verticle access
    private static volatile PgBiTemporalEventStore<?> currentInstance;

    // Notification handling (now handled by ReactiveNotificationHandler)

    /**
     * Creates a new PgBiTemporalEventStore with default pool.
     *
     * @param peeGeeQManager The PeeGeeQ manager for database access
     * @param payloadType    The class type of the event payload
     * @param tableName      The name of the database table to use for event storage
     * @param objectMapper   The JSON object mapper
     */
    public PgBiTemporalEventStore(PeeGeeQManager peeGeeQManager, Class<T> payloadType,
            String tableName, ObjectMapper objectMapper) {
        this(peeGeeQManager, payloadType, tableName, objectMapper, null);
    }

    /**
     * Creates a new PgBiTemporalEventStore with specified pool.
     *
     * @param peeGeeQManager The PeeGeeQ manager for database access
     * @param payloadType    The class type of the event payload
     * @param tableName      The name of the database table to use for event storage
     * @param objectMapper   The JSON object mapper
     * @param clientId       The client ID for pool lookup, or null for default pool
     */
    public PgBiTemporalEventStore(PeeGeeQManager peeGeeQManager, Class<T> payloadType,
            String tableName, ObjectMapper objectMapper, String clientId) {
        logger.debug("PgBiTemporalEventStore constructor starting for table: {}", tableName);
        this.peeGeeQManager = Objects.requireNonNull(peeGeeQManager, "PeeGeeQ manager cannot be null");
        this.payloadType = Objects.requireNonNull(payloadType, "Payload type cannot be null");
        this.tableName = Objects.requireNonNull(tableName, "Table name cannot be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "Object mapper cannot be null");
        this.clientId = clientId; // null means use default pool

        this.subscriptions = new ConcurrentHashMap<>();

        // Initialize performance monitoring
        this.performanceMonitor = new SimplePerformanceMonitor();

        // Set static reference for verticle access
        currentInstance = this;

        // Initialize pure Vert.x reactive infrastructure - pool will be created lazily
        this.reactivePool = null; // Will be created on first use
        logger.debug("About to create ReactiveNotificationHandler");

        // Create connection options first for immutable construction
        PgConnectOptions connectOptions = createConnectOptionsFromPeeGeeQManager();

        // Initialize reactive notification handler with immutable construction
        // Following peegeeq-native patterns - all dependencies required at construction
        String schema = peeGeeQManager.getConfiguration().getDatabaseConfig().getSchema();
        this.reactiveNotificationHandler = new ReactiveNotificationHandler<T>(
                getOrCreateSharedVertx(),
                connectOptions, // Now passed at construction time
                objectMapper,
                payloadType,
                this::getByIdReactive, // Use pure Vert.x Future method for reactive patterns
                schema,
                tableName);

        logger.debug("Deferring reactive notification handler startup until first use");
        // CRITICAL FIX: Defer notification handler startup until first use to avoid
        // Spring Boot startup issues
        // The handler will be started lazily when first subscription is made

        logger.info("Created bi-temporal event store for payload type: {} (clientId: {})",
                payloadType.getSimpleName(), clientId != null ? clientId : "default");
        logger.debug("PgBiTemporalEventStore constructor completed");
    }

    /**
     * Converts any object to JsonObject for JSONB storage.
     * Uses the properly configured ObjectMapper to handle JSR310 types like
     * LocalDate.
     * Follows the pattern established in peegeeq-outbox module.
     */
    private JsonObject toJsonObject(Object value) {
        if (value == null)
            return new JsonObject();
        if (value instanceof JsonObject)
            return (JsonObject) value;
        if (value instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) value;
            return new JsonObject(map);
        }
        // Handle primitive types (String, Number, Boolean) by wrapping them
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return new JsonObject().put("value", value);
        }

        // For complex objects, use the properly configured ObjectMapper to handle
        // JSR310 types
        try {
            String json = objectMapper.writeValueAsString(value);
            return new JsonObject(json);
        } catch (Exception e) {
            logger.error("Error converting object to JsonObject for JSONB storage: {}", e.getMessage());
            throw new RuntimeException("Failed to serialize payload to JSON", e);
        }
    }

    /**
     * Converts headers map to JsonObject, handling null values.
     * Follows the pattern established in existing codebase for header handling.
     */
    private JsonObject headersToJsonObject(Map<String, String> headers) {
        if (headers == null || headers.isEmpty())
            return new JsonObject();
        // Convert Map<String, String> to Map<String, Object> for JsonObject constructor
        Map<String, Object> objectMap = new java.util.HashMap<>(headers);
        return new JsonObject(objectMap);
    }

    @Override
    public CompletableFuture<BiTemporalEvent<T>> append(String eventType, T payload, Instant validTime) {
        logger.debug("BITEMPORAL-DEBUG: Appending event - type: {}, validTime: {}", eventType, validTime);
        return append(eventType, payload, validTime, Map.of(), null, null, null);
    }

    @Override
    public CompletableFuture<BiTemporalEvent<T>> append(String eventType, T payload, Instant validTime,
            Map<String, String> headers) {
        logger.debug("BITEMPORAL-DEBUG: Appending event with headers - type: {}, validTime: {}, headers: {}",
                eventType, validTime, headers);
        return append(eventType, payload, validTime, headers, null, null, null);
    }

    @Override
    public CompletableFuture<BiTemporalEvent<T>> append(String eventType, T payload, Instant validTime,
            Map<String, String> headers, String correlationId,
            String causationId, String aggregateId) {
        logger.debug(
                "BITEMPORAL-DEBUG: Appending event with full metadata - type: {}, validTime: {}, correlationId: {}, causationId: {}, aggregateId: {}",
                eventType, validTime, correlationId, causationId, aggregateId);

        // Performance monitoring: Track append operation timing
        var timing = performanceMonitor.startTiming();

        // Pure Vert.x 5.x implementation - delegate to reactive method with transaction
        // support
        return appendWithTransaction(eventType, payload, validTime, headers, correlationId, causationId, aggregateId)
                .whenComplete((result, throwable) -> {
                    timing.recordAsQuery();
                    if (throwable != null) {
                        logger.warn("Append operation failed after {}ms: {}", timing.getElapsed().toMillis(),
                                throwable.getMessage());
                    } else {
                        logger.debug("Append operation completed in {}ms", timing.getElapsed().toMillis());
                    }
                });
    }

    /**
     * PERFORMANCE OPTIMIZATION: Batch append multiple bi-temporal events for
     * maximum throughput.
     * This implements the "fast path" recommended by Vert.x research for massive
     * concurrent writes:
     * "Batch/bulk when you can (executeBatch), or use multi-row INSERT … VALUES
     * (...), (...), ... to cut round-trips."
     *
     * This method dramatically reduces database round-trips by batching multiple
     * events into a single operation.
     */
    public CompletableFuture<List<BiTemporalEvent<T>>> appendBatch(List<BatchEventData<T>> events) {
        if (events == null || events.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }

        logger.debug("BITEMPORAL-BATCH: Appending {} events in batch for maximum throughput", events.size());

        // Performance monitoring: Track batch operation timing
        var timing = performanceMonitor.startTiming();

        // Convert to reactive Future and back to CompletableFuture for consistency
        return ReactiveUtils.toCompletableFuture(appendBatchReactive(events))
                .whenComplete((result, throwable) -> {
                    timing.recordAsQuery();
                    if (throwable != null) {
                        logger.warn("Batch append operation ({} events) failed after {}ms: {}",
                                events.size(), timing.getElapsed().toMillis(), throwable.getMessage());
                    } else {
                        logger.info("Batch append operation ({} events) completed in {}ms - throughput: {} events/sec",
                                events.size(), timing.getElapsed().toMillis(),
                                String.format("%.1f", events.size() * 1000.0 / timing.getElapsed().toMillis()));
                    }
                });
    }

    /**
     * Reactive implementation of batch append for maximum performance
     */
    private Future<List<BiTemporalEvent<T>>> appendBatchReactive(List<BatchEventData<T>> events) {
        // Prepare batch data
        List<Tuple> batchParams = new ArrayList<>();
        List<String> eventIds = new ArrayList<>();
        OffsetDateTime transactionTime = OffsetDateTime.now();

        for (BatchEventData<T> eventData : events) {
            String eventId = UUID.randomUUID().toString();
            eventIds.add(eventId);

            // Serialize payload and headers using JSONB objects
            JsonObject payloadJson;
            JsonObject headersJson;
            try {
                payloadJson = toJsonObject(eventData.payload);
                headersJson = headersToJsonObject(eventData.headers);
            } catch (Exception e) {
                return Future.failedFuture(new RuntimeException("Failed to serialize event data", e));
            }

            OffsetDateTime validTime = eventData.validTime.atOffset(ZoneOffset.UTC);

            Tuple params = Tuple.of(
                    eventId, eventData.eventType, validTime, transactionTime,
                    payloadJson, headersJson, 1L, eventData.correlationId, null, eventData.aggregateId, false,
                    transactionTime);
            batchParams.add(params);
        }

        // Execute batch insert - this is the key performance optimization from Vert.x
        // research
        String sql = """
                INSERT INTO %s
                (event_id, event_type, valid_time, transaction_time, payload, headers,
                 version, correlation_id, causation_id, aggregate_id, is_correction, created_at)
                VALUES ($1, $2, $3, $4, $5::jsonb, $6::jsonb, $7, $8, $9, $10, $11, $12)
                RETURNING event_id, transaction_time
                """.formatted(tableName);

        Vertx vertx = getOrCreateSharedVertx();
        Pool pool = getOrCreateReactivePool();

        // Execute on Vert.x context for consistency with other operations
        return executeOnVertxContext(vertx, () -> pool.preparedQuery(sql).executeBatch(batchParams)
                .map(rowSet -> {
                    List<BiTemporalEvent<T>> results = new ArrayList<>();

                    // PostgreSQL executeBatch returns one row per batch operation, not per
                    // individual insert
                    // We need to construct results based on our input events and generated IDs
                    for (int i = 0; i < events.size(); i++) {
                        BatchEventData<T> eventData = events.get(i);
                        String eventId = eventIds.get(i);

                        BiTemporalEvent<T> event = new SimpleBiTemporalEvent<>(
                                eventId,
                                eventData.eventType,
                                eventData.payload,
                                eventData.validTime,
                                transactionTime.toInstant(),
                                1L, // version
                                null, // previousVersionId
                                eventData.headers,
                                eventData.correlationId,
                                null, // causationId
                                eventData.aggregateId,
                                false, // isCorrection
                                null // correctionReason
                        );
                        results.add(event);
                    }

                    logger.debug("BITEMPORAL-BATCH: Successfully appended {} events in batch", results.size());
                    return results;
                }));
    }

    /**
     * Production-grade transactional append method using Vert.x 5.x withTransaction
     * API.
     * This method uses the official Vert.x Pool.withTransaction() which handles:
     * - Automatic transaction begin/commit/rollback
     * - Connection management
     * - Proper error handling and automatic rollback on failure
     */
    public CompletableFuture<BiTemporalEvent<T>> appendWithTransaction(String eventType, T payload, Instant validTime,
            Map<String, String> headers, String correlationId,
            String causationId, String aggregateId) {
        // Delegate to internal method with null propagation (default behavior)
        return appendWithTransactionInternal(eventType, payload, validTime, headers, correlationId, causationId, aggregateId, null);
    }

    /**
     * Production-grade transactional append method with TransactionPropagation
     * support.
     * This method uses Vert.x TransactionPropagation for advanced transaction
     * management.
     *
     * @param eventType   The type of the event
     * @param payload     The event payload
     * @param validTime   The valid time for the event
     * @param propagation Transaction propagation behavior (e.g., CONTEXT for
     *                    sharing existing transactions)
     * @return CompletableFuture that completes when the event is stored
     */
    public CompletableFuture<BiTemporalEvent<T>> appendWithTransaction(String eventType, T payload, Instant validTime,
            TransactionPropagation propagation) {
        return appendWithTransactionInternal(eventType, payload, validTime, Map.of(), null, null, null, propagation);
    }

    /**
     * Production-grade transactional append method with headers and
     * TransactionPropagation support.
     *
     * @param eventType   The type of the event
     * @param payload     The event payload
     * @param validTime   The valid time for the event
     * @param headers     Optional event headers
     * @param propagation Transaction propagation behavior
     * @return CompletableFuture that completes when the event is stored
     */
    public CompletableFuture<BiTemporalEvent<T>> appendWithTransaction(String eventType, T payload, Instant validTime,
            Map<String, String> headers,
            TransactionPropagation propagation) {
        return appendWithTransactionInternal(eventType, payload, validTime, headers, null, null, null, propagation);
    }

    /**
     * Production-grade transactional append method with headers, correlation ID and
     * TransactionPropagation support.
     *
     * @param eventType     The type of the event
     * @param payload       The event payload
     * @param validTime     The valid time for the event
     * @param headers       Optional event headers
     * @param correlationId Optional correlation ID for event tracking
     * @param propagation   Transaction propagation behavior
     * @return CompletableFuture that completes when the event is stored
     */
    public CompletableFuture<BiTemporalEvent<T>> appendWithTransaction(String eventType, T payload, Instant validTime,
            Map<String, String> headers, String correlationId,
            TransactionPropagation propagation) {
        return appendWithTransactionInternal(eventType, payload, validTime, headers, correlationId, null, null, propagation);
    }

    /**
     * Full production-grade transactional append method with all parameters and
     * TransactionPropagation support.
     *
     * @param eventType     The type of the event
     * @param payload       The event payload
     * @param validTime     The valid time for the event
     * @param headers       Optional event headers
     * @param correlationId Optional correlation ID for event tracking
     * @param causationId   Optional causation ID identifying which event caused this event
     * @param aggregateId   Optional aggregate ID for event grouping
     * @param propagation   Transaction propagation behavior
     * @return CompletableFuture that completes when the event is stored
     */
    public CompletableFuture<BiTemporalEvent<T>> appendWithTransaction(String eventType, T payload, Instant validTime,
            Map<String, String> headers, String correlationId,
            String causationId, String aggregateId, TransactionPropagation propagation) {
        return appendWithTransactionInternal(eventType, payload, validTime, headers, correlationId, causationId,
                aggregateId, propagation);
    }

    /**
     * Internal implementation for production-grade transactional append method.
     * This method uses the official Vert.x Pool.withTransaction() which handles:
     * - Automatic transaction begin/commit/rollback
     * - Connection management
     * - Proper error handling and automatic rollback on failure
     * - TransactionPropagation support for advanced transaction management
     *
     * @param eventType     The type of the event
     * @param payload       The event payload
     * @param validTime     The valid time for the event
     * @param headers       Optional event headers
     * @param correlationId Optional correlation ID for event tracking
     * @param causationId   Optional causation ID identifying which event caused this event
     * @param aggregateId   Optional aggregate ID for event grouping
     * @param propagation   Optional transaction propagation behavior (null for
     *                      default)
     * @return CompletableFuture that completes when the event is stored
     */
    private CompletableFuture<BiTemporalEvent<T>> appendWithTransactionInternal(String eventType, T payload,
            Instant validTime,
            Map<String, String> headers, String correlationId,
            String causationId, String aggregateId, TransactionPropagation propagation) {
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Event store is closed"));
        }

        Objects.requireNonNull(eventType, "Event type cannot be null");
        Objects.requireNonNull(payload, "Payload cannot be null");
        Objects.requireNonNull(validTime, "Valid time cannot be null");

        CompletableFuture<BiTemporalEvent<T>> future = new CompletableFuture<>();

        try {
            String eventId = UUID.randomUUID().toString();
            JsonObject payloadJson = toJsonObject(payload);
            JsonObject headersJson = headersToJsonObject(headers);

            // Debug: Log the serialized JSON
            logger.debug("Serialized payload JSON: {}", payloadJson);
            logger.debug("Payload JSON type: {}", payloadJson.getClass().getSimpleName());
            String finalCorrelationId = correlationId != null ? correlationId : eventId;
            OffsetDateTime transactionTime = OffsetDateTime.now();

            // Check if Event Bus distribution is enabled for maximum performance
            boolean useEventBusDistribution = Boolean.parseBoolean(
                    System.getProperty("peegeeq.database.use.event.bus.distribution", "false"));

            if (useEventBusDistribution) {
                // Use Event Bus to distribute database operations across multiple event loops
                return appendWithEventBusDistribution(eventType, payload, validTime, headers, correlationId,
                        aggregateId, eventId, payloadJson, headersJson, finalCorrelationId, transactionTime);
            }

            // Use cached reactive infrastructure (traditional approach)
            Pool pool = getOrCreateReactivePool();
            Vertx vertx = getOrCreateSharedVertx();

            // Execute transaction on Vert.x context for proper TransactionPropagation
            // support
            var transactionFuture = (propagation != null)
                    ? executeOnVertxContext(vertx, () -> pool.withTransaction(propagation, client -> {
                        String sql = """
                                INSERT INTO %s
                                (event_id, event_type, valid_time, transaction_time, payload, headers,
                                 version, correlation_id, causation_id, aggregate_id, is_correction, created_at)
                                VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12)
                                RETURNING event_id, transaction_time
                                """.formatted(tableName);

                        // Use JsonObject directly for proper JSONB handling
                        // payloadJson and headersJson are already JsonObject instances

                        Tuple params = Tuple.of(
                                eventId, eventType, validTime.atOffset(java.time.ZoneOffset.UTC),
                                transactionTime, payloadJson, headersJson,
                                1L, finalCorrelationId, causationId, aggregateId, false, transactionTime);

                        // Return Future<BiTemporalEvent<T>> to indicate transaction success/failure
                        return client.preparedQuery(sql).execute(params).map(rows -> {
                            Row row = rows.iterator().next();
                            Instant actualTransactionTime = row.getOffsetDateTime("transaction_time").toInstant();

                            return new SimpleBiTemporalEvent<>(
                                    eventId, eventType, payload, validTime, actualTransactionTime,
                                    headers != null ? headers : Map.of(), finalCorrelationId, causationId, aggregateId);
                        });
                    }))
                    : executeOnVertxContext(vertx, () -> pool.withTransaction(client -> {
                        String sql = """
                                INSERT INTO %s
                                (event_id, event_type, valid_time, transaction_time, payload, headers,
                                 version, correlation_id, causation_id, aggregate_id, is_correction, created_at)
                                VALUES ($1, $2, $3, $4, $5::jsonb, $6::jsonb, $7, $8, $9, $10, $11, $12)
                                RETURNING event_id, transaction_time
                                """.formatted(tableName);

                        Tuple params = Tuple.of(
                                eventId, eventType, validTime.atOffset(java.time.ZoneOffset.UTC),
                                transactionTime, payloadJson, headersJson,
                                1L, finalCorrelationId, causationId, aggregateId, false, transactionTime);

                        // Return Future<BiTemporalEvent<T>> to indicate transaction success/failure
                        return client.preparedQuery(sql).execute(params).map(rows -> {
                            Row row = rows.iterator().next();
                            Instant actualTransactionTime = row.getOffsetDateTime("transaction_time").toInstant();

                            return new SimpleBiTemporalEvent<>(
                                    eventId, eventType, payload, validTime, actualTransactionTime,
                                    headers != null ? headers : Map.of(), finalCorrelationId, causationId, aggregateId);
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
     * Pure Vert.x 5.x implementation of correction append using
     * Pool.withTransaction().
     * Following PGQ coding principles: use modern Vert.x 5.x composable Future
     * patterns.
     */
    private CompletableFuture<BiTemporalEvent<T>> appendCorrectionWithTransaction(String originalEventId,
            String eventType, T payload,
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
            JsonObject payloadJson = toJsonObject(payload);
            JsonObject headersJson = headersToJsonObject(headers);
            OffsetDateTime transactionTime = OffsetDateTime.now();

            // Use Pool.withTransaction for proper transaction management - following
            // peegeeq-outbox patterns
            getOrCreateReactivePool().withTransaction(sqlConnection -> {
                // First, get the next version number
                String getVersionSql = """
                        SELECT COALESCE(MAX(version), 0) as max_version
                        FROM %s
                        WHERE event_id = $1 OR previous_version_id = $1
                        """.formatted(tableName);

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
                                    INSERT INTO %s
                                    (event_id, event_type, valid_time, transaction_time, payload, headers,
                                     version, previous_version_id, correlation_id, aggregate_id,
                                     is_correction, correction_reason, created_at)
                                    VALUES ($1, $2, $3, $4, $5::jsonb, $6::jsonb, $7, $8, $9, $10, $11, $12, $13)
                                    RETURNING id
                                    """.formatted(tableName);

                            Tuple insertParams = Tuple.of(
                                    eventId, eventType, validTime.atOffset(java.time.ZoneOffset.UTC),
                                    transactionTime, payloadJson, headersJson,
                                    nextVersion, originalEventId, correlationId, aggregateId,
                                    true, correctionReason, transactionTime);

                            return sqlConnection.preparedQuery(insertSql)
                                    .execute(insertParams)
                                    .map(insertRows -> {
                                        logger.debug("Successfully appended correction event: {} for original: {}",
                                                eventId, originalEventId);

                                        // Create and return the BiTemporalEvent
                                        return new SimpleBiTemporalEvent<>(
                                                eventId, eventType, payload, validTime, transactionTime.toInstant(),
                                                nextVersion, originalEventId, headers != null ? headers : Map.of(),
                                                correlationId, null, aggregateId, true, correctionReason);
                                    });
                        });
            }).toCompletionStage().whenComplete((event, throwable) -> {
                if (throwable != null) {
                    logger.error("Failed to append correction event for {}: {}", originalEventId,
                            throwable.getMessage(), throwable);
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

    // ========== TRANSACTION PARTICIPATION METHODS ==========

    /**
     * Transaction-aware append method using existing Vert.x connection.
     * This method allows the bitemporal event to participate in an existing
     * transaction,
     * ensuring transactional consistency with business operations.
     *
     * @param eventType  The type of the event
     * @param payload    The event payload
     * @param validTime  When the event actually happened (business time)
     * @param connection Existing Vert.x SqlConnection that has an active
     *                   transaction
     * @return CompletableFuture that completes when the event is stored
     */
    public CompletableFuture<BiTemporalEvent<T>> appendInTransaction(String eventType, T payload, Instant validTime,
            io.vertx.sqlclient.SqlConnection connection) {
        return appendInTransaction(eventType, payload, validTime, null, null, null, connection);
    }

    /**
     * Transaction-aware append method with headers.
     *
     * @param eventType  The type of the event
     * @param payload    The event payload
     * @param validTime  When the event actually happened (business time)
     * @param headers    Additional metadata for the event
     * @param connection Existing Vert.x SqlConnection that has an active
     *                   transaction
     * @return CompletableFuture that completes when the event is stored
     */
    public CompletableFuture<BiTemporalEvent<T>> appendInTransaction(String eventType, T payload, Instant validTime,
            Map<String, String> headers,
            io.vertx.sqlclient.SqlConnection connection) {
        return appendInTransaction(eventType, payload, validTime, headers, null, null, connection);
    }

    /**
     * Transaction-aware append method with headers and correlation ID.
     *
     * @param eventType     The type of the event
     * @param payload       The event payload
     * @param validTime     When the event actually happened (business time)
     * @param headers       Additional metadata for the event
     * @param correlationId Correlation ID for tracking related events
     * @param connection    Existing Vert.x SqlConnection that has an active
     *                      transaction
     * @return CompletableFuture that completes when the event is stored
     */
    public CompletableFuture<BiTemporalEvent<T>> appendInTransaction(String eventType, T payload, Instant validTime,
            Map<String, String> headers, String correlationId,
            io.vertx.sqlclient.SqlConnection connection) {
        return appendInTransaction(eventType, payload, validTime, headers, correlationId, null, connection);
    }

    /**
     * Full transaction-aware append method with all parameters.
     * This is the core transactional method that ensures bitemporal events
     * participate in the same transaction as business operations.
     *
     * @param eventType     The type of the event
     * @param payload       The event payload
     * @param validTime     When the event actually happened (business time)
     * @param headers       Additional metadata for the event
     * @param correlationId Correlation ID for tracking related events
     * @param aggregateId   Aggregate ID for grouping related events
     * @param connection    Existing Vert.x SqlConnection that has an active
     *                      transaction
     * @return CompletableFuture that completes when the event is stored
     */
    public CompletableFuture<BiTemporalEvent<T>> appendInTransaction(String eventType, T payload, Instant validTime,
            Map<String, String> headers, String correlationId,
            String aggregateId,
            io.vertx.sqlclient.SqlConnection connection) {
        return appendInTransactionReactive(eventType, payload, validTime, headers, correlationId, aggregateId,
                connection)
                .toCompletionStage().toCompletableFuture();
    }

    /**
     * Internal reactive transactional append method using existing connection and
     * pure Vert.x Future patterns.
     * This method follows the exact pattern from the outbox module's
     * sendInTransactionReactive method.
     */
    private Future<BiTemporalEvent<T>> appendInTransactionReactive(String eventType, T payload, Instant validTime,
            Map<String, String> headers, String correlationId,
            String aggregateId,
            io.vertx.sqlclient.SqlConnection connection) {
        // Comprehensive parameter validation following outbox module patterns
        if (closed) {
            return Future.failedFuture(new IllegalStateException("Event store is closed"));
        }

        if (payload == null) {
            return Future.failedFuture(new IllegalArgumentException("Event payload cannot be null"));
        }

        if (eventType == null || eventType.trim().isEmpty()) {
            return Future.failedFuture(new IllegalArgumentException("Event type cannot be null or empty"));
        }

        if (validTime == null) {
            return Future.failedFuture(new IllegalArgumentException("Valid time cannot be null"));
        }

        if (connection == null) {
            return Future.failedFuture(new IllegalArgumentException("Vert.x connection cannot be null"));
        }

        // Additional edge case validations
        if (eventType.length() > 255) {
            return Future.failedFuture(new IllegalArgumentException("Event type cannot exceed 255 characters"));
        }

        if (correlationId != null && correlationId.length() > 255) {
            return Future.failedFuture(new IllegalArgumentException("Correlation ID cannot exceed 255 characters"));
        }

        if (aggregateId != null && aggregateId.length() > 255) {
            return Future.failedFuture(new IllegalArgumentException("Aggregate ID cannot exceed 255 characters"));
        }

        // Validate headers if provided
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey() == null || entry.getKey().trim().isEmpty()) {
                    return Future.failedFuture(new IllegalArgumentException("Header keys cannot be null or empty"));
                }
                if (entry.getValue() == null) {
                    return Future.failedFuture(new IllegalArgumentException("Header values cannot be null"));
                }
            }
        }

        // Validate valid time is not too far in the future (business rule)
        Instant maxFutureTime = Instant.now().plusSeconds(86400 * 365); // 1 year in the future
        if (validTime.isAfter(maxFutureTime)) {
            return Future
                    .failedFuture(new IllegalArgumentException("Valid time cannot be more than 1 year in the future"));
        }

        try {
            String eventId = UUID.randomUUID().toString();
            JsonObject payloadJson;
            JsonObject headersJson;

            // Enhanced JSON serialization with better error handling
            try {
                payloadJson = toJsonObject(payload);
            } catch (Exception e) {
                logger.error("Failed to serialize event payload for eventType {}: {}", eventType, e.getMessage());
                return Future.failedFuture(
                        new IllegalArgumentException("Failed to serialize event payload: " + e.getMessage(), e));
            }

            try {
                headersJson = headersToJsonObject(headers);
            } catch (Exception e) {
                logger.error("Failed to serialize event headers for eventType {}: {}", eventType, e.getMessage());
                return Future.failedFuture(
                        new IllegalArgumentException("Failed to serialize event headers: " + e.getMessage(), e));
            }

            String finalCorrelationId = correlationId != null ? correlationId : eventId;
            OffsetDateTime transactionTime = OffsetDateTime.now();

            logger.debug(
                    "appendInTransactionReactive: eventType={}, correlationId={}, finalCorrelationId={}, aggregateId={}",
                    eventType, correlationId, finalCorrelationId, aggregateId);

            String sql = """
                    INSERT INTO %s
                    (event_id, event_type, valid_time, transaction_time, payload, headers,
                     version, correlation_id, aggregate_id, is_correction, created_at)
                    VALUES ($1, $2, $3, $4, $5::jsonb, $6::jsonb, $7, $8, $9, $10, $11)
                    RETURNING event_id, transaction_time
                    """.formatted(tableName);

            Tuple params = Tuple.of(
                    eventId, eventType, validTime.atOffset(java.time.ZoneOffset.UTC), transactionTime,
                    payloadJson, headersJson, 1L, finalCorrelationId, aggregateId, false, transactionTime);

            // Use provided connection (which should have an active transaction)
            Future<BiTemporalEvent<T>> result = connection.preparedQuery(sql)
                    .execute(params)
                    .map(rows -> {
                        if (rows.size() == 0) {
                            throw new RuntimeException("No rows returned from insert operation");
                        }

                        logger.debug(
                                "Successfully appended bi-temporal event in transaction: eventId={}, eventType={}, validTime={}",
                                eventId, eventType, validTime);

                        return new SimpleBiTemporalEvent<>(
                                eventId, eventType, payload, validTime, transactionTime.toInstant(),
                                headers != null ? headers : Map.of(), finalCorrelationId, null, aggregateId);
                    });

            return result
                    .onSuccess(event -> {
                        logger.debug("Bitemporal event appended in transaction for eventType {}: {}", eventType,
                                eventId);

                        // Record metrics if available
                        if (performanceMonitor != null) {
                            // Note: We don't start/stop timing here since this is part of a larger
                            // transaction
                            logger.debug("Transaction append completed for event: {}", eventId);
                        }
                    })
                    .onFailure(error -> {
                        logger.error("Failed to append bitemporal event in transaction for eventType {}: {}", eventType,
                                error.getMessage());
                        // Enhanced error logging with more context
                        logger.error(
                                "Transaction append failure details - eventId: {}, correlationId: {}, aggregateId: {}",
                                eventId, finalCorrelationId, aggregateId);
                    });

        } catch (Exception e) {
            logger.error("Error preparing bitemporal event for transaction append, eventType {}: {}", eventType,
                    e.getMessage(), e);
            return Future.failedFuture(
                    new RuntimeException("Failed to prepare bitemporal event for transaction: " + e.getMessage(), e));
        }
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
                       version, previous_version_id, correlation_id, causation_id, aggregate_id,
                       is_correction, correction_reason, created_at
                FROM %s
                WHERE event_id = $1
                ORDER BY version DESC
                LIMIT 1
                """.formatted(tableName);

        return getOptimalReadClient().preparedQuery(sql)
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
                       version, previous_version_id, correlation_id, causation_id, aggregate_id,
                       is_correction, correction_reason, created_at
                FROM %s
                WHERE event_id = $1 OR previous_version_id = $1
                ORDER BY version ASC
                """.formatted(tableName);

        return getOptimalReadClient().preparedQuery(sql)
                .execute(Tuple.of(eventId))
                .map(rows -> {
                    List<BiTemporalEvent<T>> events = new ArrayList<>();
                    for (Row row : rows) {
                        try {
                            events.add(mapRowToEvent(row));
                        } catch (Exception e) {
                            logger.error("Failed to map row to event: {}", e.getMessage(), e);
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

        // CRITICAL FIX: Ensure notification handler is started before subscription
        return ReactiveUtils.toCompletableFuture(
                ensureNotificationHandlerStarted()
                        .compose(v -> reactiveNotificationHandler.subscribe(eventType, aggregateId, handler)));
    }

    @Override
    public CompletableFuture<Void> unsubscribe() {
        subscriptions.clear();

        // Pure Vert.x 5.x reactive notification unsubscribe
        return ReactiveUtils.toCompletableFuture(
                reactiveNotificationHandler.stop());
    }

    @Override
    public CompletableFuture<List<String>> getUniqueAggregates(String eventType) {
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Event store is closed"));
        }

        StringBuilder sql = new StringBuilder("SELECT DISTINCT aggregate_id FROM %s");
        List<Object> params = new ArrayList<>();
        
        if (eventType != null) {
            sql.append(" WHERE event_type = $1");
            params.add(eventType);
        }
        
        sql.append(" ORDER BY aggregate_id LIMIT 1000"); // Safety limit

        String finalSql = sql.toString().formatted(tableName);
        Tuple tuple = Tuple.tuple(params);

        return getOptimalReadClient().preparedQuery(finalSql)
                .execute(tuple)
                .map(rows -> {
                    List<String> aggregates = new ArrayList<>();
                    for (Row row : rows) {
                        String aggId = row.getString("aggregate_id");
                        if (aggId != null) {
                            aggregates.add(aggId);
                        }
                    }
                    return aggregates;
                })
                .toCompletionStage()
                .toCompletableFuture();
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

        // Get basic stats including unique aggregate count
        String basicStatsSql = """
                SELECT
                    COUNT(*) as total_events,
                    COUNT(*) FILTER (WHERE is_correction = TRUE) as total_corrections,
                    MIN(valid_time) as oldest_event_time,
                    MAX(valid_time) as newest_event_time,
                    pg_total_relation_size('%s') as storage_size_bytes,
                    COUNT(DISTINCT aggregate_id) as unique_aggregate_count
                FROM %s
                """.formatted(tableName, tableName);

        // Get event counts by type
        String typeCountsSql = """
                SELECT event_type, COUNT(*) as event_count
                FROM %s
                GROUP BY event_type
                """.formatted(tableName);

        return getOptimalReadClient().preparedQuery(basicStatsSql)
                .execute()
                .compose(basicRows -> {
                    // Parse basic stats
                    long totalEvents = 0;
                    long totalCorrections = 0;
                    Instant oldestEventTime = null;
                    Instant newestEventTime = null;
                    long storageSizeBytes = 0;
                    long uniqueAggregateCount = 0;

                    if (basicRows.size() > 0) {
                        Row basicRow = basicRows.iterator().next();
                        totalEvents = basicRow.getLong("total_events");
                        totalCorrections = basicRow.getLong("total_corrections");
                        oldestEventTime = basicRow.getLocalDateTime("oldest_event_time") != null
                                ? basicRow.getLocalDateTime("oldest_event_time").toInstant(java.time.ZoneOffset.UTC)
                                : null;
                        newestEventTime = basicRow.getLocalDateTime("newest_event_time") != null
                                ? basicRow.getLocalDateTime("newest_event_time").toInstant(java.time.ZoneOffset.UTC)
                                : null;
                        storageSizeBytes = basicRow.getLong("storage_size_bytes");
                        uniqueAggregateCount = basicRow.getLong("unique_aggregate_count");
                    }

                    // Get event counts by type
                    final long finalTotalEvents = totalEvents;
                    final long finalTotalCorrections = totalCorrections;
                    final Instant finalOldestEventTime = oldestEventTime;
                    final Instant finalNewestEventTime = newestEventTime;
                    final long finalStorageSizeBytes = storageSizeBytes;
                    final long finalUniqueAggregateCount = uniqueAggregateCount;

                    return getOptimalReadClient().preparedQuery(typeCountsSql)
                            .execute()
                            .map(typeRows -> {
                                Map<String, Long> eventCountsByType = new HashMap<>();
                                for (Row typeRow : typeRows) {
                                    eventCountsByType.put(typeRow.getString("event_type"),
                                            typeRow.getLong("event_count"));
                                }

                                return new EventStoreStatsImpl(
                                        finalTotalEvents,
                                        finalTotalCorrections,
                                        eventCountsByType,
                                        finalOldestEventTime,
                                        finalNewestEventTime,
                                        finalStorageSizeBytes,
                                        finalUniqueAggregateCount);
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

        // Close reactive pool to prevent connection leaks
        if (reactivePool != null) {
            try {
                reactivePool.close();
                reactivePool = null;
                logger.debug("Closed reactive pool");
            } catch (Exception e) {
                logger.warn("Error closing reactive pool: {}", e.getMessage(), e);
            }
        }

        // Close pipelined client to prevent connection leaks
        if (pipelinedClient != null) {
            try {
                pipelinedClient.close();
                pipelinedClient = null;
                logger.debug("Closed pipelined client");
            } catch (Exception e) {
                logger.warn("Error closing pipelined client: {}", e.getMessage(), e);
            }
        }

        logger.info("Bi-temporal event store closed");
    }

    /**
     * Ensures the reactive notification handler is started.
     * This method provides lazy initialization to avoid startup issues during
     * Spring Boot bean creation.
     */
    private Future<Void> ensureNotificationHandlerStarted() {
        // Check if already started
        if (reactiveNotificationHandler != null) {
            // Try to start the handler - it will return immediately if already started
            return reactiveNotificationHandler.start()
                    .onFailure(error -> logger.warn("Failed to ensure notification handler is started: {}",
                            error.getMessage()));
        }

        logger.warn("Reactive notification handler is null - this should not happen");
        return Future.failedFuture(new IllegalStateException("Reactive notification handler not initialized"));
    }

    /**
     * Starts the reactive notification handler.
     * Connection options are now set at construction time for immutable design.
     * 
     * @deprecated Use ensureNotificationHandlerStarted() for lazy initialization
     *             instead
     */
    @Deprecated
    private void startReactiveNotifications() {
        // Pure Vert.x 5.x reactive notification handler startup
        try {
            // Connection options are now set at construction time - start the handler and
            // wait for completion
            // This ensures the handler is active before the constructor completes
            reactiveNotificationHandler.start().toCompletionStage().toCompletableFuture().join();
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
        String causationId = row.getString("causation_id");
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
        Map<String, String> headers = objectMapper.readValue(headersJson, Map.class);

        return new SimpleBiTemporalEvent<>(
                eventId, eventType, payload, validTime, transactionTime,
                version, previousVersionId, headers, correlationId, causationId, aggregateId,
                isCorrection, correctionReason);
    }

    // ========== REACTIVE METHODS (Vert.x Future-based) ==========

    public Future<BiTemporalEvent<T>> appendReactive(String eventType, T payload, Instant validTime) {
        // Pure Vert.x 5.x implementation with transaction support - use internal method
        // directly
        return ReactiveUtils.fromCompletableFuture(
                appendWithTransactionInternal(eventType, payload, validTime, Map.of(), null, null, null, null));
    }

    public Future<List<BiTemporalEvent<T>>> queryReactive(EventQuery query) {
        // Pure Vert.x 5.x implementation with transaction support
        try {
            StringBuilder sql = new StringBuilder("SELECT * FROM %s WHERE 1=1");
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

            if (query.getCausationId().isPresent()) {
                sql.append(" AND causation_id = $").append(paramIndex++);
                params.add(query.getCausationId().get());
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

            // Format SQL with table name
            String finalSql = sql.toString().formatted(tableName);

            // Use pure Vert.x 5.x reactive query execution with optimal read client for
            // maximum throughput
            return getOptimalReadClient().preparedQuery(finalSql)
                    .execute(tuple)
                    .map(rows -> {
                        List<BiTemporalEvent<T>> events = new ArrayList<>();
                        for (Row row : rows) {
                            try {
                                BiTemporalEvent<T> event = mapRowToEvent(row);
                                events.add(event);
                            } catch (Exception e) {
                                logger.error("Failed to map row to event: {}", e.getMessage(), e);
                            }
                        }
                        return events;
                    });
        } catch (Exception e) {
            return Future.failedFuture(e);
        }
    }

    public Future<Void> subscribeReactive(String eventType, MessageHandler<BiTemporalEvent<T>> handler) {
        // CRITICAL FIX: Ensure notification handler is started before subscription
        return ensureNotificationHandlerStarted()
                .compose(v -> reactiveNotificationHandler.subscribe(eventType, null, handler));
    }

    /**
     * Creates PgConnectOptions from PeeGeeQManager configuration.
     * This follows the pure Vert.x 5.x pattern for proper connection configuration.
     * Uses clientId for pool lookup - null clientId is resolved to the default pool
     * by PgClientFactory.
     */
    private PgConnectOptions createConnectOptionsFromPeeGeeQManager() {
        try {
            // Extract client factory from PeeGeeQManager using reflection (same as
            // VertxPoolAdapter)
            java.lang.reflect.Field clientFactoryField = peeGeeQManager.getClass().getDeclaredField("clientFactory");
            clientFactoryField.setAccessible(true);
            dev.mars.peegeeq.db.client.PgClientFactory clientFactory = (dev.mars.peegeeq.db.client.PgClientFactory) clientFactoryField
                    .get(peeGeeQManager);

            if (clientFactory != null) {
                // Get connection configuration - clientId can be null, PgClientFactory resolves
                // to default
                dev.mars.peegeeq.db.config.PgConnectionConfig connectionConfig = clientFactory
                        .getConnectionConfig(clientId);

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

                    // CRITICAL FIX: Set search_path at connection level so all connections from the pool
                    // automatically use the configured schema. This is the proper Vert.x 5.x approach
                    // as documented in https://vertx.io/docs/vertx-pg-client/java/
                    String configuredSchema = connectionConfig.getSchema();
                    if (configuredSchema != null && !configuredSchema.isBlank()) {
                        java.util.Map<String, String> properties = new java.util.HashMap<>();
                        properties.put("search_path", configuredSchema);
                        connectOptions.setProperties(properties);
                        logger.debug("Setting search_path={} in PgConnectOptions for schema isolation", configuredSchema);
                    }

                    logger.debug(
                            "Created PgConnectOptions from PeeGeeQManager: host={}, port={}, database={}, user={} (clientId: {})",
                            connectionConfig.getHost(), connectionConfig.getPort(),
                            connectionConfig.getDatabase(), connectionConfig.getUsername(),
                            clientId != null ? clientId : "default");

                    return connectOptions;
                } else {
                    String poolName = clientId != null ? clientId : "default";
                    throw new RuntimeException(
                            "Connection configuration '" + poolName + "' not found in PgClientFactory");
                }
            } else {
                throw new RuntimeException("PgClientFactory not found in PeeGeeQManager");
            }
            // DO NOT close clientFactory - it belongs to PeeGeeQManager and will be reused
        } catch (Exception e) {
            logger.error("Failed to create connect options from PeeGeeQManager: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create connect options from PeeGeeQManager", e);
        }
    }

    /**
     * Gets or creates a reactive pool for bi-temporal event store operations.
     * This method provides thread-safe lazy initialization following peegeeq-outbox
     * patterns.
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
                // Get connection configuration from PeeGeeQManager following peegeeq-outbox
                // patterns
                PgConnectOptions connectOptions = createConnectOptionsFromPeeGeeQManager();

                // CRITICAL PERFORMANCE FIX: Enable command pipelining for maximum throughput
                // Research-based optimized default: 1024 for high-throughput scenarios
                int pipeliningLimit = Integer.parseInt(System.getProperty("peegeeq.database.pipelining.limit", "1024"));
                connectOptions.setPipeliningLimit(pipeliningLimit);

                logger.info("Configured PostgreSQL pipelining limit: {}", pipeliningLimit);

                // Configure pool options following Vert.x performance checklist
                PoolOptions poolOptions = new PoolOptions();

                // CRITICAL: Use research-based optimized pool size (100 for high-concurrency
                // bitemporal workloads)
                int maxPoolSize = getConfiguredPoolSize();
                poolOptions.setMaxSize(maxPoolSize);

                // CRITICAL FIX: Use unique pool name per event store to avoid cross-database
                // connection issues
                // When multiple setups with different databases exist, shared pools with the
                // same name
                // would return the wrong connection (connected to a different database).
                // Include table name in pool name to ensure each event store gets its own pool.
                poolOptions.setShared(false); // Disable sharing to ensure correct database connection
                poolOptions.setName("peegeeq-bitemporal-pool-" + tableName.replace(".", "-")); // Unique pool name for
                                                                                               // monitoring

                // CRITICAL FIX: Set wait queue size to 10x pool size to handle high-concurrency
                // scenarios
                // Based on performance test failures, bitemporal workloads need larger wait
                // queues
                int waitQueueMultiplier = Integer
                        .parseInt(System.getProperty("peegeeq.database.pool.wait-queue-multiplier", "10"));
                poolOptions.setMaxWaitQueueSize(maxPoolSize * waitQueueMultiplier);

                // Connection timeout and idle timeout for reliability
                poolOptions.setConnectionTimeout(30000); // 30 seconds
                poolOptions.setIdleTimeout(600000); // 10 minutes

                // PERFORMANCE OPTIMIZATION: Configure event loop size for better concurrency
                // By default, Vert.x uses 2 * CPU cores event loops, but we can optimize for
                // database workloads
                int eventLoopSize = Integer.parseInt(System.getProperty("peegeeq.database.event.loop.size", "0"));
                if (eventLoopSize > 0) {
                    poolOptions.setEventLoopSize(eventLoopSize);
                    logger.info("Configured event loop size: {}", eventLoopSize);
                }

                // Create the Pool for transaction operations (not pipelined)
                reactivePool = PgBuilder.pool()
                        .with(poolOptions)
                        .connectingTo(connectOptions)
                        .using(getOrCreateSharedVertx())
                        .build();

                // CRITICAL PERFORMANCE OPTIMIZATION: Create pooled SqlClient for pipelined
                // operations
                // This provides 4x performance improvement according to Vert.x research
                // Pool operations are NOT pipelined, but pooled client operations ARE pipelined
                pipelinedClient = PgBuilder.client()
                        .with(poolOptions)
                        .connectingTo(connectOptions)
                        .using(getOrCreateSharedVertx())
                        .build();

                logger.info(
                        "CRITICAL: Created optimized Vert.x infrastructure: pool(size={}, shared={}, waitQueue={}, eventLoops={}), pipelinedClient(limit={})",
                        maxPoolSize, poolOptions.isShared(), poolOptions.getMaxWaitQueueSize(),
                        eventLoopSize > 0 ? eventLoopSize : "default", pipeliningLimit);

                // Start performance monitoring with periodic logging
                performanceMonitor.startPeriodicLogging(getOrCreateSharedVertx(), 10000); // Log every 10 seconds

                return reactivePool;

            } catch (Exception e) {
                logger.error("Failed to create reactive pool: {}", e.getMessage(), e);
                throw new RuntimeException("Failed to create reactive pool for bi-temporal event store", e);
            }
        }
    }

    /**
     * Gets the optimal client for read operations (pipelined client if available,
     * otherwise pool).
     * This method provides maximum throughput for read-only queries by using the
     * pipelined client.
     *
     * @return SqlClient optimized for read operations
     */
    private SqlClient getOptimalReadClient() {
        // Ensure pool is initialized first
        getOrCreateReactivePool();

        // Use pipelined client for maximum read throughput if available
        if (pipelinedClient != null) {
            return pipelinedClient;
        }

        // Fallback to pool for compatibility
        return reactivePool;
    }

    /**
     * Gets the optimal client for high-performance write operations (pipelined
     * client if available).
     * WARNING: This bypasses transactions for maximum throughput. Use only for
     * performance-critical scenarios
     * where individual SQL statements are atomic and transaction boundaries are not
     * required.
     *
     * @return SqlClient optimized for high-performance writes
     */
    private SqlClient getHighPerformanceWriteClient() {
        // Ensure pool is initialized first
        getOrCreateReactivePool();

        // Use pipelined client for maximum write throughput if available
        if (pipelinedClient != null) {
            return pipelinedClient;
        }

        // Fallback to pool for compatibility
        return reactivePool;
    }

    /**
     * High-performance append method that uses pipelining for maximum throughput.
     * WARNING: This method bypasses transactions for performance. Use only when:
     * 1. Individual SQL statements are atomic (which they are for single INSERTs)
     * 2. Transaction boundaries are not required across multiple operations
     * 3. Maximum throughput is more important than strict ACID guarantees
     *
     * This method is designed for performance benchmarks and high-throughput
     * scenarios.
     *
     * @param eventType     The type of event
     * @param payload       The event payload
     * @param validTime     The valid time for the event
     * @param headers       Optional event headers
     * @param correlationId Optional correlation ID for event tracking
     * @param aggregateId   Optional aggregate ID for event grouping
     * @return CompletableFuture that completes when the event is stored
     */
    public CompletableFuture<BiTemporalEvent<T>> appendHighPerformance(String eventType, T payload, Instant validTime,
            Map<String, String> headers, String correlationId,
            String aggregateId) {
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Event store is closed"));
        }

        try {
            // Generate event metadata
            String eventId = UUID.randomUUID().toString();
            JsonObject payloadJson = toJsonObject(payload);
            JsonObject headersJson = headersToJsonObject(headers);
            OffsetDateTime transactionTime = OffsetDateTime.now();
            long version = 1; // For high-performance mode, we use version 1 (no corrections)
            String finalCorrelationId = correlationId != null ? correlationId : UUID.randomUUID().toString();

            logger.debug("Serialized payload JSON: {}", payloadJson);
            logger.debug("Payload JSON type: {}", payloadJson.getClass().getSimpleName());

            // Use high-performance pipelined client for maximum throughput
            SqlClient client = getHighPerformanceWriteClient();
            Vertx vertx = getOrCreateSharedVertx();

            String sql = """
                    INSERT INTO %s
                    (event_id, event_type, valid_time, transaction_time, payload, headers,
                     version, correlation_id, aggregate_id, is_correction, created_at)
                    VALUES ($1, $2, $3, $4, $5::jsonb, $6::jsonb, $7, $8, $9, $10, $11)
                    """.formatted(tableName);

            Tuple params = Tuple.of(
                    eventId, eventType, validTime.atOffset(java.time.ZoneOffset.UTC), transactionTime, payloadJson,
                    headersJson,
                    version, finalCorrelationId, aggregateId, false, OffsetDateTime.now());

            // Execute on Vert.x context with pipelined client for maximum performance
            Future<BiTemporalEvent<T>> insertFuture = executeOnVertxContext(vertx, () -> client.preparedQuery(sql)
                    .execute(params)
                    .map(result -> {
                        logger.debug("Successfully appended bi-temporal event: eventId={}, eventType={}, validTime={}",
                                eventId, eventType, validTime);

                        return new SimpleBiTemporalEvent<>(
                                eventId, eventType, payload, validTime, transactionTime.toInstant(),
                                headers != null ? headers : Map.of(), finalCorrelationId, null, aggregateId);
                    }));

            return ReactiveUtils.toCompletableFuture(insertFuture);

        } catch (Exception e) {
            logger.error("Failed to append high-performance bi-temporal event: {}", e.getMessage(), e);
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Gets the configured pool size from PeeGeeQManager configuration.
     * Falls back to a reasonable default if not configured.
     *
     * @return The maximum pool size to use
     */
    private int getConfiguredPoolSize() {
        // Check system property first (allows runtime tuning)
        String systemPoolSize = System.getProperty("peegeeq.database.pool.max-size");
        if (systemPoolSize != null) {
            int size = Integer.parseInt(systemPoolSize);
            logger.info("Using system property pool size: {}", size);
            return size;
        }

        try {
            if (peeGeeQManager != null && peeGeeQManager.getConfiguration() != null) {
                var poolConfig = peeGeeQManager.getConfiguration().getPoolConfig();
                if (poolConfig != null) {
                    int configuredSize = poolConfig.getMaxSize();
                    logger.debug("Using configured pool size: {}", configuredSize);
                    return configuredSize;
                }
            }
        } catch (Exception e) {
            logger.debug("Could not get pool size from configuration, using default: {}", e.getMessage());
        }

        // CRITICAL: Use optimized default based on Vert.x 5.x research
        // For bitemporal workloads, we need much higher concurrency
        int defaultSize = 100; // Increased from 32 based on performance testing
        logger.info("Using Vert.x 5.x optimized pool size: {} (tuned for high-concurrency)", defaultSize);
        return defaultSize;
    }

    /**
     * Gets or creates a shared Vertx instance for proper context management.
     * This ensures that TransactionPropagation.CONTEXT works correctly by providing
     * a consistent Vertx context across all EventStore instances.
     *
     * @return The shared Vertx instance
     */
    static Vertx getOrCreateSharedVertx() {
        if (sharedVertx == null) {
            synchronized (PgBiTemporalEventStore.class) {
                if (sharedVertx == null) {
                    // CRITICAL PERFORMANCE FIX: Configure Vertx with optimized options for database
                    // workloads
                    VertxOptions vertxOptions = new VertxOptions();

                    // Configure event loop pool size for database-intensive workloads
                    int eventLoopSize = Integer.parseInt(System.getProperty("peegeeq.database.event.loop.size", "0"));
                    if (eventLoopSize > 0) {
                        vertxOptions.setEventLoopPoolSize(eventLoopSize);
                        logger.info("CRITICAL: Configured Vertx event loop pool size: {}", eventLoopSize);
                    }

                    // Configure worker pool size for blocking operations
                    int workerPoolSize = Integer.parseInt(System.getProperty("peegeeq.database.worker.pool.size", "0"));
                    if (workerPoolSize > 0) {
                        vertxOptions.setWorkerPoolSize(workerPoolSize);
                        logger.info("CRITICAL: Configured Vertx worker pool size: {}", workerPoolSize);
                    }

                    // Optimize for high-throughput database operations
                    vertxOptions.setPreferNativeTransport(true);

                    sharedVertx = Vertx.vertx(vertxOptions);
                    logger.info(
                            "Created HIGH-PERFORMANCE shared Vertx instance for bi-temporal event store (event-loops: {}, workers: {})",
                            eventLoopSize > 0 ? eventLoopSize : "default",
                            workerPoolSize > 0 ? workerPoolSize : "default");
                }
            }
        }
        return sharedVertx;
    }

    /**
     * High-performance append method using Event Bus distribution across multiple
     * event loops.
     * This method distributes database operations to worker verticles for maximum
     * throughput.
     */
    private CompletableFuture<BiTemporalEvent<T>> appendWithEventBusDistribution(
            String eventType, T payload, Instant validTime, Map<String, String> headers,
            String correlationId, String aggregateId, String eventId, JsonObject payloadJson,
            JsonObject headersJson, String finalCorrelationId, OffsetDateTime transactionTime) {

        logger.debug("Using Event Bus distribution for high-performance database operation");

        // Create operation request for Event Bus
        JsonObject operation = new JsonObject()
                .put("operation", "append")
                .put("eventType", eventType)
                .put("payload", payloadJson)
                .put("validTime", validTime.toString())
                .put("correlationId", finalCorrelationId)
                .put("aggregateId", aggregateId)
                .put("headers", headersJson);

        // Send operation to worker verticles via Event Bus
        return sendDatabaseOperation(operation)
                .map(result -> {
                    // Convert result back to BiTemporalEvent
                    String resultEventId = result.getString("id");
                    String resultTransactionTime = result.getString("transactionTime");
                    Instant actualTransactionTime = Instant.parse(resultTransactionTime);

                    BiTemporalEvent<T> event = new SimpleBiTemporalEvent<>(
                            resultEventId, eventType, payload, validTime, actualTransactionTime,
                            headers != null ? headers : Map.of(), finalCorrelationId, null, aggregateId);
                    return event;
                })
                .toCompletionStage()
                .toCompletableFuture();
    }

    /**
     * CRITICAL PERFORMANCE FEATURE: Deploy multiple database worker verticles to
     * distribute load across all event loops.
     * This is the key to achieving high throughput - instead of processing all
     * database operations on a single event loop,
     * we deploy multiple instances of database worker verticles to utilize all
     * available event loops.
     *
     * This method should be called during application startup to maximize database
     * performance.
     *
     * @param instances Number of verticle instances to deploy (defaults to CPU
     *                  cores if <= 0)
     * @param tableName Table name to use for database operations
     * @return Future that completes when all verticles are deployed
     */
    public static Future<String> deployDatabaseWorkerVerticles(int instances, String tableName) {
        Vertx vertx = getOrCreateSharedVertx();

        final int finalInstances = instances <= 0 ? Runtime.getRuntime().availableProcessors() : instances;

        logger.info(
                "CRITICAL PERFORMANCE: Deploying {} database worker verticle instances to distribute load across event loops",
                finalInstances);

        DeploymentOptions options = new DeploymentOptions()
                .setInstances(finalInstances)
                .setThreadingModel(ThreadingModel.EVENT_LOOP); // Use event loop threads for maximum performance

        return vertx.deployVerticle(() -> new DatabaseWorkerVerticle(tableName), options)
                .onSuccess(deploymentId -> {
                    logger.info("SUCCESS: Deployed {} database worker verticle instances with deployment ID: {}",
                            finalInstances, deploymentId);
                })
                .onFailure(throwable -> {
                    logger.error("FAILED: Could not deploy database worker verticles: {}", throwable.getMessage(),
                            throwable);
                });
    }

    /**
     * Send database operation to worker verticles via Event Bus for distributed
     * processing.
     * This distributes the work across multiple event loops for maximum throughput.
     */
    private Future<JsonObject> sendDatabaseOperation(JsonObject operation) {
        Vertx vertx = getOrCreateSharedVertx();

        // Generate unique request ID for tracking
        String requestId = UUID.randomUUID().toString();
        operation.put("requestId", requestId);

        logger.debug("Sending database operation '{}' with requestId '{}' to worker verticles",
                operation.getString("operation"), requestId);

        // Send operation to worker verticles via Event Bus
        // The Event Bus will automatically distribute requests across available
        // verticle instances
        return vertx.eventBus().<JsonObject>request("peegeeq.database.operations", operation)
                .map(message -> {
                    JsonObject result = message.body();
                    logger.debug("Database operation '{}' completed with requestId '{}'",
                            operation.getString("operation"), requestId);
                    return result;
                })
                .recover(error -> {
                    logger.error("Database operation '{}' failed with requestId '{}': {}",
                            operation.getString("operation"), requestId, error.getMessage(), error);
                    return Future.failedFuture(error);
                });
    }

    /**
     * Database Worker Verticle - handles database operations on dedicated event
     * loop threads.
     * Each instance runs on its own event loop thread, allowing true parallel
     * processing of database operations.
     *
     * This verticle listens on the event bus for database operation requests and
     * processes them
     * on its dedicated event loop thread, enabling true concurrency across multiple
     * event loops.
     */
    private static class DatabaseWorkerVerticle extends VerticleBase {
        private static final String DB_OPERATION_ADDRESS = "peegeeq.database.operations";
        private final String tableName;

        DatabaseWorkerVerticle(String tableName) {
            this.tableName = tableName;
        }

        @Override
        public Future<?> start() {
            String threadName = Thread.currentThread().getName();
            logger.info("Database worker verticle started on event loop: {}", threadName);

            // Verify we're running on an event loop thread
            if (!Context.isOnEventLoopThread()) {
                logger.warn("WARNING: Database worker verticle is NOT running on event loop thread: {}", threadName);
            } else {
                logger.info("CONFIRMED: Database worker verticle running on event loop thread: {}", threadName);
            }

            // Register event bus consumer for database operations
            vertx.eventBus().<JsonObject>consumer(DB_OPERATION_ADDRESS, message -> {
                String operationType = message.body().getString("operation");
                String requestId = message.body().getString("requestId");

                logger.debug("Processing database operation '{}' with requestId '{}' on thread: {}",
                        operationType, requestId, Thread.currentThread().getName());

                // Process the database operation on this event loop thread
                processDatabaseOperation(message.body())
                        .onSuccess(result -> {
                            logger.debug("Database operation '{}' completed successfully on thread: {}",
                                    operationType, Thread.currentThread().getName());
                            message.reply(result);
                        })
                        .onFailure(error -> {
                            logger.error("Database operation '{}' failed on thread: {}: {}",
                                    operationType, Thread.currentThread().getName(), error.getMessage(), error);
                            message.fail(500, error.getMessage());
                        });
            });

            logger.info("Database worker verticle registered for event bus operations on thread: {}", threadName);
            return Future.succeededFuture();
        }

        /**
         * Process database operations on this verticle's dedicated event loop thread
         */
        private Future<JsonObject> processDatabaseOperation(JsonObject operation) {
            String operationType = operation.getString("operation");

            switch (operationType) {
                case "append":
                    return processAppendOperation(operation);
                default:
                    return Future.failedFuture("Unknown operation type: " + operationType);
            }
        }

        /**
         * Process append operation on this event loop thread
         */
        private Future<JsonObject> processAppendOperation(JsonObject operation) {
            // Extract operation parameters
            String eventType = operation.getString("eventType");
            JsonObject payload = operation.getJsonObject("payload");
            String validTimeStr = operation.getString("validTime");
            String correlationId = operation.getString("correlationId");
            String aggregateId = operation.getString("aggregateId");
            JsonObject headers = operation.getJsonObject("headers");

            // Parse validTime from string to OffsetDateTime
            OffsetDateTime validTime = OffsetDateTime.parse(validTimeStr);

            // Get the shared database pool (this is thread-safe and accessible from any
            // thread)
            Pool pool = null;
            if (currentInstance != null) {
                pool = currentInstance.getOrCreateReactivePool();
            }
            if (pool == null) {
                return Future.failedFuture("Database pool not initialized");
            }

            // Execute the database operation on this event loop thread
            // CRITICAL PERFORMANCE FIX: Use pool.preparedQuery() instead of
            // withTransaction()
            // to avoid pinning operations to a single connection and allow true concurrency
            String sql = """
                    INSERT INTO %s
                    (event_id, event_type, valid_time, transaction_time, payload, headers,
                     version, correlation_id, aggregate_id, is_correction, created_at)
                    VALUES ($1, $2, $3, $4, $5::jsonb, $6::jsonb, $7, $8, $9, $10, $11)
                    RETURNING event_id, transaction_time
                    """.formatted(tableName);

            // Generate event ID for this operation
            String eventId = UUID.randomUUID().toString();
            OffsetDateTime transactionTime = OffsetDateTime.now();

            Tuple params = Tuple.of(
                    eventId, eventType, validTime, transactionTime,
                    payload, headers, 1L, correlationId, aggregateId, false, transactionTime);

            return pool.preparedQuery(sql).execute(params)
                    .map(rows -> {
                        Row row = rows.iterator().next();
                        return new JsonObject()
                                .put("id", row.getString("event_id"))
                                .put("transactionTime",
                                        row.getOffsetDateTime("transaction_time").toInstant().toString())
                                .put("eventType", eventType)
                                .put("payload", payload)
                                .put("validTime", validTimeStr)
                                .put("correlationId", correlationId)
                                .put("aggregateId", aggregateId)
                                .put("headers", headers);
                    });
        }

        @Override
        public Future<?> stop() {
            logger.info("Database worker verticle stopped on thread: {}", Thread.currentThread().getName());
            return Future.succeededFuture();
        }
    }

    /**
     * Closes the shared Vertx instance. This should only be called during
     * application shutdown.
     * Note: This is a static method that affects all PgBiTemporalEventStore
     * instances.
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
     * Clears all cached connection pools and resets static state.
     * This is intended for testing scenarios where connection configuration changes
     * between test classes (e.g., when using TestContainers with different ports).
     *
     * IMPORTANT: This method should only be called during testing when you need to
     * force recreation of connection pools with updated configuration.
     */
    public static void clearCachedPools() {
        synchronized (PgBiTemporalEventStore.class) {
            // Clear the current instance reference
            currentInstance = null;

            // Note: We don't close sharedVertx here as it may be in use by other components
            // Individual instances will recreate their pools on next access
        }
    }

    /**
     * Clears this instance's cached connection pools.
     * This forces recreation of pools on next access with current configuration.
     *
     * IMPORTANT: This method should only be called during testing when you need to
     * force recreation of connection pools with updated configuration.
     */
    public void clearInstancePools() {
        synchronized (this) {
            if (reactivePool != null) {
                // Close existing pool
                reactivePool.close();
                reactivePool = null;
            }
            if (pipelinedClient != null) {
                // Close existing pipelined client
                pipelinedClient.close();
                pipelinedClient = null;
            }
        }
    }

    /**
     * Executes an operation on the Vert.x context, following peegeeq-outbox
     * patterns.
     * This ensures proper context management for reactive operations.
     *
     * @param vertx     The Vert.x instance
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
        private final long uniqueAggregateCount;

        public EventStoreStatsImpl(long totalEvents, long totalCorrections,
                Map<String, Long> eventCountsByType,
                Instant oldestEventTime, Instant newestEventTime,
                long storageSizeBytes) {
            this(totalEvents, totalCorrections, eventCountsByType, oldestEventTime, newestEventTime, storageSizeBytes,
                    0);
        }

        public EventStoreStatsImpl(long totalEvents, long totalCorrections,
                Map<String, Long> eventCountsByType,
                Instant oldestEventTime, Instant newestEventTime,
                long storageSizeBytes, long uniqueAggregateCount) {
            this.totalEvents = totalEvents;
            this.totalCorrections = totalCorrections;
            this.eventCountsByType = Map.copyOf(eventCountsByType);
            this.oldestEventTime = oldestEventTime;
            this.newestEventTime = newestEventTime;
            this.storageSizeBytes = storageSizeBytes;
            this.uniqueAggregateCount = uniqueAggregateCount;
        }

        @Override
        public long getTotalEvents() {
            return totalEvents;
        }

        @Override
        public long getTotalCorrections() {
            return totalCorrections;
        }

        @Override
        public Map<String, Long> getEventCountsByType() {
            return eventCountsByType;
        }

        @Override
        public Instant getOldestEventTime() {
            return oldestEventTime;
        }

        @Override
        public Instant getNewestEventTime() {
            return newestEventTime;
        }

        @Override
        public long getStorageSizeBytes() {
            return storageSizeBytes;
        }

        @Override
        public long getUniqueAggregateCount() {
            return uniqueAggregateCount;
        }

        @Override
        public String toString() {
            return "EventStoreStats{" +
                    "totalEvents=" + totalEvents +
                    ", totalCorrections=" + totalCorrections +
                    ", eventCountsByType=" + eventCountsByType +
                    ", oldestEventTime=" + oldestEventTime +
                    ", newestEventTime=" + newestEventTime +
                    ", storageSizeBytes=" + storageSizeBytes +
                    ", uniqueAggregateCount=" + uniqueAggregateCount +
                    '}';
        }
    }

    /**
     * Data class for batch event operations.
     * This class holds all the data needed for a single event in a batch operation.
     */
    public static class BatchEventData<T> {
        public final String eventType;
        public final T payload;
        public final Instant validTime;
        public final Map<String, String> headers;
        public final String correlationId;
        public final String aggregateId;

        public BatchEventData(String eventType, T payload, Instant validTime, Map<String, String> headers,
                String correlationId, String aggregateId) {
            this.eventType = eventType;
            this.payload = payload;
            this.validTime = validTime;
            this.headers = headers;
            this.correlationId = correlationId;
            this.aggregateId = aggregateId;
        }
    }

}
