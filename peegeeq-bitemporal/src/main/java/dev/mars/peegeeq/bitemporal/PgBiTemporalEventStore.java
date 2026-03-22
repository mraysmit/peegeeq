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
import dev.mars.peegeeq.db.util.PostgreSqlIdentifierValidator;

import dev.mars.peegeeq.api.tracing.TraceContextUtil;
import dev.mars.peegeeq.api.tracing.TraceCtx;

import io.vertx.core.Context;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.ThreadingModel;
import io.vertx.core.Vertx;
import io.vertx.core.VerticleBase;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;

import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

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
    private static final int MAX_METADATA_LENGTH = 255;
    private static final long MAX_FUTURE_SECONDS = 86400L * 365L;
    private static final String DEFAULT_EVENT_BUS_CLIENT_KEY = "__default__";
    private static final String DB_OPERATION_ADDRESS_PREFIX = "peegeeq.database.operations.";

    private final Vertx vertx;
    private final PeeGeeQManager peeGeeQManager;
    private final ObjectMapper objectMapper;
    private final Class<T> payloadType;
    private final String tableName;
    private final String quotedTableName;
    private volatile boolean closed = false;

    // Client ID for pool lookup - null means use default pool (resolved by
    // PgClientFactory)
    private final String clientId;
    private final String eventBusInstanceKey;

    // Pure Vert.x reactive infrastructure with caching
    private volatile Pool reactivePool;
    private volatile SqlClient pipelinedClient; // High-performance pipelined client for maximum throughput
    private final ReactiveNotificationHandler<T> reactiveNotificationHandler;

    // Performance monitoring
    private final SimplePerformanceMonitor performanceMonitor;

    // Event-bus worker operations are routed by store instance key to avoid
    // cross-instance contamination when multiple event stores exist in one JVM.
    private static final Map<String, PgBiTemporalEventStore<?>> eventBusInstanceRegistry = new ConcurrentHashMap<>();

    // Notification handling (now handled by ReactiveNotificationHandler)

    /**
     * Creates a new PgBiTemporalEventStore with default pool.
     *
     * @param vertx          The Vert.x instance (caller-owned, not closed by this class)
     * @param peeGeeQManager The PeeGeeQ manager for database access
     * @param payloadType    The class type of the event payload
     * @param tableName      The name of the database table to use for event storage
     * @param objectMapper   The JSON object mapper
     */
    public PgBiTemporalEventStore(Vertx vertx, PeeGeeQManager peeGeeQManager, Class<T> payloadType,
            String tableName, ObjectMapper objectMapper) {
        this(vertx, peeGeeQManager, payloadType, tableName, objectMapper, null);
    }

    /**
     * Creates a new PgBiTemporalEventStore with specified pool.
     *
     * @param vertx          The Vert.x instance (caller-owned, not closed by this class)
     * @param peeGeeQManager The PeeGeeQ manager for database access
     * @param payloadType    The class type of the event payload
     * @param tableName      The name of the database table to use for event storage
     * @param objectMapper   The JSON object mapper
     * @param clientId       The client ID for pool lookup, or null for default pool
     */
    public PgBiTemporalEventStore(Vertx vertx, PeeGeeQManager peeGeeQManager, Class<T> payloadType,
            String tableName, ObjectMapper objectMapper, String clientId) {
        logger.debug("PgBiTemporalEventStore constructor starting for table: {}", tableName);
        this.vertx = Objects.requireNonNull(vertx, "Vertx instance cannot be null");
        this.peeGeeQManager = Objects.requireNonNull(peeGeeQManager, "PeeGeeQ manager cannot be null");
        this.payloadType = Objects.requireNonNull(payloadType, "Payload type cannot be null");
        this.tableName = validateTableName(Objects.requireNonNull(tableName, "Table name cannot be null"));
        this.quotedTableName = "\"" + this.tableName + "\"";
        this.objectMapper = Objects.requireNonNull(objectMapper, "Object mapper cannot be null");
        this.clientId = clientId; // null means use default pool
        this.eventBusInstanceKey = createEventBusInstanceKey(this.clientId, this.tableName);

        // Initialize performance monitoring
        this.performanceMonitor = new SimplePerformanceMonitor();

        // Initialize pure Vert.x reactive infrastructure - pool will be created lazily
        this.reactivePool = null; // Will be created on first use
        logger.debug("About to create ReactiveNotificationHandler");

        try {
            // Create connection options first for immutable construction
            PgConnectOptions connectOptions = createConnectOptionsFromPeeGeeQManager();

            // Initialize reactive notification handler with immutable construction
            // Following peegeeq-native patterns - all dependencies required at construction
            String schema = peeGeeQManager.getConfiguration().getDatabaseConfig().getSchema();
            this.reactiveNotificationHandler = new ReactiveNotificationHandler<T>(
                    this.vertx,
                    connectOptions, // Now passed at construction time
                    objectMapper,
                    payloadType,
                    this::getById, // Use pure Vert.x Future method for reactive patterns
                    schema,
                    this.tableName);
        } catch (Exception e) {
            // Ensure no leak if construction fails after partial init
            logger.error("Failed to construct PgBiTemporalEventStore: {}", e.getMessage(), e);
            throw e;
        }

        // Register this instance only after full construction succeeds.
        eventBusInstanceRegistry.put(eventBusInstanceKey, this);

        logger.debug("Deferring reactive notification handler startup until first use");
        // : Defer notification handler startup until first use to avoid
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

    String eventBusInstanceKey() {
        return eventBusInstanceKey;
    }

    @Override
    public Future<BiTemporalEvent<T>> append(String eventType, T payload, Instant validTime) {
        logger.debug("BITEMPORAL-DEBUG: Appending event - type: {}, validTime: {}", eventType, validTime);
        return append(eventType, payload, validTime, Map.of(), null, null, null);
    }

    @Override
    public Future<BiTemporalEvent<T>> append(String eventType, T payload, Instant validTime,
            Map<String, String> headers) {
        logger.debug("BITEMPORAL-DEBUG: Appending event with headers - type: {}, validTime: {}, headers: {}",
                eventType, validTime, headers);
        return append(eventType, payload, validTime, headers, null, null, null);
    }

    @Override
    public Future<BiTemporalEvent<T>> append(String eventType, T payload, Instant validTime,
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
                .onSuccess(event -> {
                    timing.recordAsQuery();
                    logger.debug("Append operation completed in {}ms", timing.getElapsed().toMillis());
                })
                .onFailure(error -> {
                    timing.recordAsQuery();
                    logger.warn("Append operation failed after {}ms: {}", timing.getElapsed().toMillis(),
                            error.getMessage());
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
    public Future<List<BiTemporalEvent<T>>> appendBatch(List<BatchEventData<T>> events) {
        if (events == null || events.isEmpty()) {
            return Future.succeededFuture(List.of());
        }

        logger.debug("BITEMPORAL-BATCH: Appending {} events in batch for maximum throughput", events.size());

        // Performance monitoring: Track batch operation timing
        var timing = performanceMonitor.startTiming();

        return executeBatchAppend(events)
                .onSuccess(result -> {
                    timing.recordAsQuery();
                    logger.info("Batch append operation ({} events) completed in {}ms - throughput: {} events/sec",
                            events.size(), timing.getElapsed().toMillis(),
                            String.format("%.1f", events.size() * 1000.0 / timing.getElapsed().toMillis()));
                })
                .onFailure(throwable -> {
                    timing.recordAsQuery();
                    logger.warn("Batch append operation ({} events) failed after {}ms: {}",
                            events.size(), timing.getElapsed().toMillis(), throwable.getMessage());
                });
    }

    private Future<List<BiTemporalEvent<T>>> executeBatchAppend(List<BatchEventData<T>> events) {
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
                payloadJson = toJsonObject(eventData.payload());
                headersJson = headersToJsonObject(eventData.headers());
            } catch (Exception e) {
                return Future.failedFuture(new RuntimeException("Failed to serialize event data", e));
            }

            OffsetDateTime validTime = eventData.validTime().atOffset(ZoneOffset.UTC);

            Tuple params = Tuple.of(
                    eventId, eventData.eventType(), validTime, transactionTime,
                    payloadJson, headersJson, 1L, eventData.correlationId(), null, eventData.aggregateId(), false,
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
                """.formatted(quotedTableName);

        Pool pool = getOrCreateReactivePool();

        // Execute on Vert.x context for consistency with other operations
        return executeOnVertxContext(this.vertx, () -> pool.preparedQuery(sql).executeBatch(batchParams)
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
                                eventData.eventType(),
                                eventData.payload(),
                                eventData.validTime(),
                                transactionTime.toInstant(),
                                1L, // version
                                null, // previousVersionId
                                eventData.headers(),
                                eventData.correlationId(),
                                null, // causationId
                                eventData.aggregateId(),
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
     * Appends a new event in its own transaction.
     * This method starts a fresh database transaction — it does not join or
     * participate in any externally-started transaction.
     *
     * For genuine transaction participation (where the event rolls back with
     * the caller's transaction), use {@link #appendInTransaction(String, Object, Instant, io.vertx.sqlclient.SqlConnection)}.
     *
     * @param eventType     The type of the event
     * @param payload       The event payload
     * @param validTime     The valid time for the event
     * @param headers       Optional event headers
     * @param correlationId Optional correlation ID for event tracking
     * @param causationId   Optional causation ID identifying which event caused this event
     * @param aggregateId   Optional aggregate ID for event grouping
     * @return Future that completes when the event is stored
     */
    public Future<BiTemporalEvent<T>> appendWithTransaction(String eventType, T payload, Instant validTime,
            Map<String, String> headers, String correlationId,
            String causationId, String aggregateId) {
        return appendWithTransactionInternal(eventType, payload, validTime, headers, correlationId, causationId,
                aggregateId);
    }

    /**
     * Internal implementation for transactional append.
     * This method starts its own transaction via Pool.withTransaction() — it does
     * not participate in any externally-started transaction. For genuine
     * transaction participation, callers must use
     * {@link #appendInTransaction(String, Object, Instant, io.vertx.sqlclient.SqlConnection)}.
     *
     * @param eventType     The type of the event
     * @param payload       The event payload
     * @param validTime     The valid time for the event
     * @param headers       Optional event headers
     * @param correlationId Optional correlation ID for event tracking
     * @param causationId   Optional causation ID identifying which event caused this event
     * @param aggregateId   Optional aggregate ID for event grouping
     * @return Future that completes when the event is stored
     */
    private Future<BiTemporalEvent<T>> appendWithTransactionInternal(String eventType, T payload,
            Instant validTime,
            Map<String, String> headers, String correlationId,
            String causationId, String aggregateId) {
        if (closed) {
            return Future.failedFuture(new IllegalStateException("Event store is closed"));
        }

        try {
            validateAppendParameters(eventType, payload, validTime, headers, correlationId, causationId, aggregateId);
        } catch (IllegalArgumentException e) {
            return Future.failedFuture(e);
        }

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
                        causationId, aggregateId, eventId, payloadJson, headersJson, finalCorrelationId,
                        transactionTime);
            }

            // Use cached reactive infrastructure
            Pool pool = getOrCreateReactivePool();

            // Own-transaction: starts a fresh transaction on the internal pool.
            // This does NOT participate in any externally-started transaction.
            Future<BiTemporalEvent<T>> transactionFuture = executeOnVertxContext(this.vertx,
                    () -> pool.withTransaction(client -> {
                        String sql = """
                                INSERT INTO %s
                                (event_id, event_type, valid_time, transaction_time, payload, headers,
                                 version, correlation_id, causation_id, aggregate_id, is_correction, created_at)
                                VALUES ($1, $2, $3, $4, $5::jsonb, $6::jsonb, $7, $8, $9, $10, $11, $12)
                                RETURNING event_id, transaction_time
                                """.formatted(quotedTableName);

                        Tuple params = Tuple.of(
                                eventId, eventType, validTime.atOffset(java.time.ZoneOffset.UTC),
                                transactionTime, payloadJson, headersJson,
                                1L, finalCorrelationId, causationId, aggregateId, false, transactionTime);

                        return client.preparedQuery(sql).execute(params).map(rows -> {
                            Row row = rows.iterator().next();
                            Instant actualTransactionTime = row.getOffsetDateTime("transaction_time").toInstant();

                            BiTemporalEvent<T> event = new SimpleBiTemporalEvent<>(
                                    eventId, eventType, payload, validTime, actualTransactionTime,
                                    headers != null ? headers : Map.of(), finalCorrelationId, causationId, aggregateId);
                            return event;
                        });
                    }));

            // Handle success and failure following peegeeq-outbox patterns
            return transactionFuture
                    .onSuccess(event -> {
                        logger.debug("Successfully appended bi-temporal event: eventId={}, eventType={}, validTime={}",
                                eventId, eventType, validTime);
                    })
                    .onFailure(throwable -> {
                        logger.error("Failed to append bi-temporal event: eventId={}, eventType={}, error={}",
                                eventId, eventType, throwable.getMessage(), throwable);
                    });

        } catch (Exception e) {
            logger.error("Failed to append event of type {}: {}", eventType, e.getMessage(), e);
            return Future.failedFuture(new RuntimeException("Failed to append bi-temporal event", e));
        }
    }

    @Override
    public Future<BiTemporalEvent<T>> appendCorrection(String originalEventId, String eventType,
            T payload, Instant validTime,
            String correctionReason) {
        return appendCorrection(originalEventId, eventType, payload, validTime, Map.of(),
                null, null, correctionReason);
    }

    @Override
    public Future<BiTemporalEvent<T>> appendCorrection(String originalEventId, String eventType,
            T payload, Instant validTime,
            Map<String, String> headers,
            String correlationId, String aggregateId,
            String correctionReason) {
        return appendCorrectionWithTransaction(originalEventId, eventType, payload, validTime, headers,
                correlationId, aggregateId, correctionReason);
    }

    /**
     * Pure Vert.x 5.x implementation of correction append using
     * Pool.withTransaction().
     * Following PGQ coding principles: use modern Vert.x 5.x composable Future
     * patterns.
     */
    private Future<BiTemporalEvent<T>> appendCorrectionWithTransaction(String originalEventId,
            String eventType, T payload,
            Instant validTime, Map<String, String> headers,
            String correlationId, String aggregateId,
            String correctionReason) {
        if (closed) {
            return Future.failedFuture(new IllegalStateException("Event store is closed"));
        }

        Objects.requireNonNull(originalEventId, "Original event ID cannot be null");
        Objects.requireNonNull(eventType, "Event type cannot be null");
        Objects.requireNonNull(payload, "Payload cannot be null");
        Objects.requireNonNull(validTime, "Valid time cannot be null");
        Objects.requireNonNull(correctionReason, "Correction reason cannot be null");

        try {
            String eventId = UUID.randomUUID().toString();
            JsonObject payloadJson = toJsonObject(payload);
            JsonObject headersJson = headersToJsonObject(headers);
            OffsetDateTime transactionTime = OffsetDateTime.now();

            // Use Pool.withTransaction for proper transaction management - following
            // peegeeq-outbox patterns
            return getOrCreateReactivePool().withTransaction(sqlConnection -> {
                // Acquire advisory lock to serialize corrections for the same event family.
                // This prevents the read-modify-write race where two concurrent corrections
                // both read the same MAX(version) under READ COMMITTED isolation.
                // The lock is automatically released when the transaction commits/rolls back.
                String lockSql = "SELECT pg_advisory_xact_lock(hashtext($1))";

                return sqlConnection.preparedQuery(lockSql)
                        .execute(Tuple.of(originalEventId))
                        .compose(lockResult -> {
                // Verify the original event exists before proceeding
                String existsSql = "SELECT 1 FROM %s WHERE event_id = $1 LIMIT 1".formatted(quotedTableName);

                return sqlConnection.preparedQuery(existsSql)
                        .execute(Tuple.of(originalEventId))
                        .compose(existsRows -> {
                            if (existsRows.size() == 0) {
                                return Future.failedFuture(new IllegalArgumentException(
                                        "Cannot correct non-existent event: " + originalEventId));
                            }

                // Get the max version across the entire correction family.
                // Must walk up to the root and then down to all descendants
                // to avoid duplicate version numbers in fan-out topologies.
                String getVersionSql = """
                        WITH RECURSIVE ancestors AS (
                            SELECT event_id, previous_version_id
                            FROM %1$s WHERE event_id = $1
                            UNION ALL
                            SELECT p.event_id, p.previous_version_id
                            FROM %1$s p JOIN ancestors a ON a.previous_version_id = p.event_id
                        ),
                        root AS (
                            SELECT COALESCE(
                                (SELECT event_id FROM ancestors WHERE previous_version_id IS NULL LIMIT 1),
                                $1
                            ) AS root_event_id
                        ),
                        family AS (
                            SELECT root_event_id AS event_id FROM root
                            UNION ALL
                            SELECT c.event_id FROM %1$s c JOIN family f ON c.previous_version_id = f.event_id
                        )
                        SELECT COALESCE(MAX(e.version), 0) as max_version
                        FROM %1$s e WHERE e.event_id IN (SELECT event_id FROM family)
                        """.formatted(quotedTableName);

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
                                    """.formatted(quotedTableName);

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
                                    BiTemporalEvent<T> correctionEvent = new SimpleBiTemporalEvent<>(
                                                eventId, eventType, payload, validTime, transactionTime.toInstant(),
                                                nextVersion, originalEventId, headers != null ? headers : Map.of(),
                                                correlationId, null, aggregateId, true, correctionReason);
                                    return correctionEvent;
                                    });
                        });
                        }); // end exists check compose
                        }); // end advisory lock compose
            }).onFailure(throwable -> logger.error("Failed to append correction event for {}: {}", originalEventId,
                    throwable.getMessage(), throwable));

        } catch (Exception e) {
            logger.error("Failed to serialize correction event: {}", e.getMessage(), e);
            return Future.failedFuture(e);
        }
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
     * @return Future that completes when the event is stored
     */
    public Future<BiTemporalEvent<T>> appendInTransaction(String eventType, T payload, Instant validTime,
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
     * @return Future that completes when the event is stored
     */
    public Future<BiTemporalEvent<T>> appendInTransaction(String eventType, T payload, Instant validTime,
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
     * @return Future that completes when the event is stored
     */
    public Future<BiTemporalEvent<T>> appendInTransaction(String eventType, T payload, Instant validTime,
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
     * @return Future that completes when the event is stored
     */
    public Future<BiTemporalEvent<T>> appendInTransaction(String eventType, T payload, Instant validTime,
            Map<String, String> headers, String correlationId,
            String aggregateId,
            io.vertx.sqlclient.SqlConnection connection) {
        return appendInTransaction(eventType, payload, validTime, headers, correlationId, null, aggregateId,
                connection);
    }

    /**
     * Full transaction-aware append method with causation ID support.
     * This method supports event causality tracking in transactional contexts.
     *
     * @param eventType     The type of the event
     * @param payload       The event payload
     * @param validTime     When the event actually happened (business time)
     * @param headers       Additional metadata for the event
     * @param correlationId Correlation ID for tracking related events
     * @param causationId   Causation ID identifying which event caused this event
     * @param aggregateId   Aggregate ID for grouping related events
     * @param connection    Existing Vert.x SqlConnection that has an active
     *                      transaction
     * @return Future that completes when the event is stored
     */
    public Future<BiTemporalEvent<T>> appendInTransaction(String eventType, T payload, Instant validTime,
            Map<String, String> headers, String correlationId,
            String causationId, String aggregateId,
            io.vertx.sqlclient.SqlConnection connection) {
        // Comprehensive parameter validation following outbox module patterns
        if (closed) {
            return Future.failedFuture(new IllegalStateException("Event store is closed"));
        }

        if (connection == null) {
            return Future.failedFuture(new IllegalArgumentException("Vert.x connection cannot be null"));
        }

        try {
            validateAppendParameters(eventType, payload, validTime, headers, correlationId, causationId, aggregateId);
        } catch (IllegalArgumentException e) {
            return Future.failedFuture(e);
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
                    "appendInTransaction: eventType={}, correlationId={}, finalCorrelationId={}, causationId={}, aggregateId={}",
                    eventType, correlationId, finalCorrelationId, causationId, aggregateId);

            String sql = """
                    INSERT INTO %s
                    (event_id, event_type, valid_time, transaction_time, payload, headers,
                     version, correlation_id, causation_id, aggregate_id, is_correction, created_at)
                    VALUES ($1, $2, $3, $4, $5::jsonb, $6::jsonb, $7, $8, $9, $10, $11, $12)
                    RETURNING event_id, transaction_time
                    """.formatted(quotedTableName);

            Tuple params = Tuple.of(
                    eventId, eventType, validTime.atOffset(java.time.ZoneOffset.UTC), transactionTime,
                    payloadJson, headersJson, 1L, finalCorrelationId, causationId, aggregateId, false, transactionTime);

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
                                headers != null ? headers : Map.of(), finalCorrelationId, causationId, aggregateId);
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
    public Future<List<BiTemporalEvent<T>>> query(EventQuery query) {
        // Pure Vert.x 5.x implementation with transaction support
        try {
            StringBuilder sql = new StringBuilder("""
                    SELECT event_id, event_type, valid_time, transaction_time, payload, headers,
                           version, previous_version_id, correlation_id, causation_id, aggregate_id,
                           is_correction, correction_reason, created_at
                    FROM %s WHERE 1=1""");
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
            String finalSql = sql.toString().formatted(quotedTableName);

            // Use pure Vert.x 5.x reactive query execution with optimal read client for
            // maximum throughput
            return getOptimalReadClient().preparedQuery(finalSql)
                    .execute(tuple)
                    .map(rows -> {
                        try {
                            List<BiTemporalEvent<T>> events = new ArrayList<>();
                            for (Row row : rows) {
                                events.add(mapRowToEvent(row));
                            }
                            return events;
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to map row to event", e);
                        }
                    });
        } catch (Exception e) {
            return Future.failedFuture(e);
        }
    }

    @Override
    public Future<BiTemporalEvent<T>> getById(String eventId) {
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
                """.formatted(quotedTableName);

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
    public Future<List<BiTemporalEvent<T>>> getAllVersions(String eventId) {
        if (closed) {
            return Future.failedFuture(new IllegalStateException("Event store is closed"));
        }

        Objects.requireNonNull(eventId, "Event ID cannot be null");

        // Walk up the correction chain to find the root, then walk down to collect all descendants.
        // Uses RECURSIVE CTE to handle chains of arbitrary depth (A→B→C→D...).
        String sql = """
                WITH RECURSIVE ancestors AS (
                    SELECT event_id, previous_version_id
                    FROM %1$s
                    WHERE event_id = $1

                    UNION ALL

                    SELECT p.event_id, p.previous_version_id
                    FROM %1$s p
                    JOIN ancestors a ON a.previous_version_id = p.event_id
                ),
                root AS (
                    SELECT COALESCE(
                        (SELECT event_id FROM ancestors WHERE previous_version_id IS NULL LIMIT 1),
                        $1
                    ) AS root_event_id
                ),
                family AS (
                    SELECT root_event_id AS event_id
                    FROM root

                    UNION ALL

                    SELECT c.event_id
                    FROM %1$s c
                    JOIN family f ON c.previous_version_id = f.event_id
                )
                SELECT event_id, event_type, valid_time, transaction_time, payload, headers,
                       version, previous_version_id, correlation_id, causation_id, aggregate_id,
                       is_correction, correction_reason, created_at
                FROM %1$s
                WHERE event_id IN (SELECT event_id FROM family)
                ORDER BY version ASC
                """.formatted(quotedTableName);

        return getOptimalReadClient().preparedQuery(sql)
                .execute(Tuple.of(eventId))
                .map(rows -> {
                    try {
                        List<BiTemporalEvent<T>> events = new ArrayList<>();
                        for (Row row : rows) {
                            events.add(mapRowToEvent(row));
                        }
                        return events;
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to map row to event", e);
                    }
                });
    }

    @Override
    public Future<BiTemporalEvent<T>> getAsOfTransactionTime(String eventId, Instant asOfTransactionTime) {
        if (closed) {
            return Future.failedFuture(new IllegalStateException("Event store is closed"));
        }

        Objects.requireNonNull(eventId, "Event ID cannot be null");
        Objects.requireNonNull(asOfTransactionTime, "As-of transaction time cannot be null");

        String sql = """
                WITH RECURSIVE ancestors AS (
                    SELECT event_id, previous_version_id
                    FROM %1$s
                    WHERE event_id = $1

                    UNION ALL

                    SELECT p.event_id, p.previous_version_id
                    FROM %1$s p
                    JOIN ancestors a ON a.previous_version_id = p.event_id
                ),
                root AS (
                    SELECT COALESCE(
                        (SELECT event_id FROM ancestors WHERE previous_version_id IS NULL LIMIT 1),
                        $1
                    ) AS root_event_id
                ),
                family_ids AS (
                    SELECT root_event_id AS event_id
                    FROM root

                    UNION

                    SELECT c.event_id
                    FROM %1$s c
                    JOIN family_ids f ON c.previous_version_id = f.event_id
                )
                SELECT event_id, event_type, valid_time, transaction_time, payload, headers,
                       version, previous_version_id, correlation_id, causation_id, aggregate_id,
                       is_correction, correction_reason, created_at
                FROM %1$s
                WHERE event_id IN (SELECT event_id FROM family_ids)
                  AND transaction_time <= $2
                ORDER BY version DESC, transaction_time DESC
                LIMIT 1
                """.formatted(quotedTableName);

        return getOptimalReadClient().preparedQuery(sql)
                .execute(Tuple.of(eventId, asOfTransactionTime.atOffset(ZoneOffset.UTC)))
                .map(rows -> {
                    if (rows.size() == 0) {
                        return null;
                    }
                    try {
                        return mapRowToEvent(rows.iterator().next());
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to map as-of transaction time row", e);
                    }
                });
    }

    @Override
    public Future<Void> subscribe(String eventType, MessageHandler<BiTemporalEvent<T>> handler) {
        return subscribe(eventType, null, handler);
    }

    @Override
    public Future<Void> subscribe(String eventType, String aggregateId,
            MessageHandler<BiTemporalEvent<T>> handler) {
        if (closed) {
            return Future.failedFuture(new IllegalStateException("Event store is closed"));
        }

        // : Ensure notification handler is started before subscription
        return ensureNotificationHandlerStarted().compose(v -> reactiveNotificationHandler.subscribe(eventType,
                aggregateId, handler));
    }

    @Override
    public Future<Void> unsubscribe() {
        // Pure Vert.x 5.x reactive notification unsubscribe
        return reactiveNotificationHandler.stop();
    }

    @Override
    public Future<List<String>> getUniqueAggregates(String eventType) {
        if (closed) {
            return Future.failedFuture(new IllegalStateException("Event store is closed"));
        }

        StringBuilder sql = new StringBuilder("SELECT DISTINCT aggregate_id FROM %s");
        List<Object> params = new ArrayList<>();
        
        if (eventType != null) {
            sql.append(" WHERE event_type = $1");
            params.add(eventType);
        }
        
        sql.append(" ORDER BY aggregate_id LIMIT 1000"); // Safety limit

        String finalSql = sql.toString().formatted(quotedTableName);
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
                });
    }

    @Override
    public Future<EventStore.EventStoreStats> getStats() {
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
                    pg_total_relation_size($1::regclass) as storage_size_bytes,
                    COUNT(DISTINCT aggregate_id) as unique_aggregate_count
                FROM %s
                """.formatted(quotedTableName);

        // Get event counts by type
        String typeCountsSql = """
                SELECT event_type, COUNT(*) as event_count
                FROM %s
                GROUP BY event_type
                """.formatted(quotedTableName);

        return getOptimalReadClient().preparedQuery(basicStatsSql)
                .execute(Tuple.of(tableName))
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
    public Future<Void> close() {
        if (closed) {
            return Future.succeededFuture();
        }

        logger.info("Closing bi-temporal event store");
        closed = true;

        // Remove this instance from event-bus routing registry if still mapped.
        eventBusInstanceRegistry.computeIfPresent(eventBusInstanceKey,
            (key, existing) -> existing == this ? null : existing);

        // Compose close operations sequentially: notification handler → reactive pool → pipelined client
        // Best-effort: attempt all closes even if one fails, but propagate the first error to the caller.
        Future<Void> chain = Future.succeededFuture();
        java.util.concurrent.atomic.AtomicReference<Throwable> firstError = new java.util.concurrent.atomic.AtomicReference<>();

        if (reactiveNotificationHandler != null) {
            chain = chain.compose(v -> reactiveNotificationHandler.stop()
                    .recover(e -> {
                        logger.warn("Error closing reactive notification handler: {}", e.getMessage(), e);
                        firstError.compareAndSet(null, e);
                        return Future.succeededFuture();
                    }));
        }

        if (reactivePool != null) {
            Pool poolRef = reactivePool;
            reactivePool = null;
            chain = chain.compose(v -> poolRef.close()
                    .onSuccess(x -> logger.debug("Closed reactive pool"))
                    .recover(e -> {
                        logger.warn("Error closing reactive pool: {}", e.getMessage(), e);
                        firstError.compareAndSet(null, e);
                        return Future.succeededFuture();
                    }));
        }

        if (pipelinedClient != null) {
            SqlClient clientRef = pipelinedClient;
            pipelinedClient = null;
            chain = chain.compose(v -> clientRef.close()
                    .onSuccess(x -> logger.debug("Closed pipelined client"))
                    .recover(e -> {
                        logger.warn("Error closing pipelined client: {}", e.getMessage(), e);
                        firstError.compareAndSet(null, e);
                        return Future.succeededFuture();
                    }));
        }

        return chain.compose(v -> {
            Throwable error = firstError.get();
            if (error != null) {
                logger.warn("Bi-temporal event store closed with errors");
                return Future.failedFuture(error);
            }
            logger.info("Bi-temporal event store closed");
            return Future.<Void>succeededFuture();
        });
    }

    /**
     * Delegates to {@link #close()}.
     *
     * @return a Future that completes when all resources are released
     * @deprecated Use {@link #close()} instead
     */
    @Deprecated
    public Future<Void> closeFuture() {
        return close();
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

        // Deserialize payload using native/outbox-compatible wrapper semantics.
        T payload = parsePayloadFromJsonString(payloadJson);

        // Deserialize headers, filtering out null-valued entries from JSONB
        @SuppressWarnings("unchecked")
        Map<String, String> headers = objectMapper.readValue(headersJson, Map.class);
        headers.values().removeIf(Objects::isNull);

        return new SimpleBiTemporalEvent<>(
                eventId, eventType, payload, validTime, transactionTime,
                version, previousVersionId, headers, correlationId, causationId, aggregateId,
                isCorrection, correctionReason);
    }

    /**
     * Parse payload JSON back to the expected type.
     *
     * Supports the queue/event-store wrapper convention where simple scalar
     * payloads are stored as {"value": ...}. This keeps Object.class behavior
     * aligned with peegeeq-native and peegeeq-outbox consumers.
     */
    private T parsePayloadFromJsonString(String payloadJson) throws Exception {
        if (payloadJson == null || payloadJson.isBlank()) {
            return null;
        }

        // Legacy/double-unwrapped rows may contain plain string content without JSON quotes.
        // Preserve compatibility by returning raw string for Object/String payload types.
        if (!payloadJson.startsWith("{")
                && !payloadJson.startsWith("[")
                && !payloadJson.startsWith("\"")
                && !"true".equals(payloadJson)
                && !"false".equals(payloadJson)
                && !"null".equals(payloadJson)
                && !(payloadJson.startsWith("-") || Character.isDigit(payloadJson.charAt(0)))) {
            if (payloadType == Object.class || payloadType == String.class) {
                @SuppressWarnings("unchecked")
                T result = (T) payloadJson;
                return result;
            }
        }

        // Only attempt wrapper-unwrapping for object payloads.
        // For scalar/array JSON, rely on ObjectMapper directly.
        if (payloadJson.startsWith("{") && payloadJson.endsWith("}")) {
            JsonObject payload = new JsonObject(payloadJson);

            // Unwrap scalar wrapper payloads: {"value": ...}
            if (payload.size() == 1 && payload.containsKey("value")) {
                Object value = payload.getValue("value");
                if (payloadType.isInstance(value)) {
                    @SuppressWarnings("unchecked")
                    T result = (T) value;
                    return result;
                }

                // If the wrapped value is scalar but payloadType differs, ask Jackson to coerce.
                if (value instanceof Number || value instanceof CharSequence || value instanceof Boolean) {
                    return objectMapper.convertValue(value, payloadType);
                }
            }
        }

        return objectMapper.readValue(payloadJson, payloadType);
    }

    /**
     * Creates PgConnectOptions from PeeGeeQManager configuration.
     * This follows the pure Vert.x 5.x pattern for proper connection configuration.
     * Uses clientId for pool lookup - null clientId is resolved to the default pool
     * by PgClientFactory.
     */
    private PgConnectOptions createConnectOptionsFromPeeGeeQManager() {
        try {
            dev.mars.peegeeq.db.client.PgClientFactory clientFactory = peeGeeQManager.getClientFactory();

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

                    // : Set search_path at connection level so all connections from the pool
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
                int pipeliningLimit;
                try {
                    pipeliningLimit = Integer.parseInt(System.getProperty("peegeeq.database.pipelining.limit", "1024"));
                } catch (NumberFormatException e) {
                    logger.warn("Invalid peegeeq.database.pipelining.limit value, using default 1024");
                    pipeliningLimit = 1024;
                }
                connectOptions.setPipeliningLimit(pipeliningLimit);

                logger.info("Configured PostgreSQL pipelining limit: {}", pipeliningLimit);

                // Configure pool options following Vert.x performance checklist
                PoolOptions poolOptions = new PoolOptions();

                // CRITICAL: Use research-based optimized pool size (100 for high-concurrency
                // bitemporal workloads)
                int maxPoolSize = getConfiguredPoolSize();
                poolOptions.setMaxSize(maxPoolSize);

                // : Use unique pool name per event store to avoid cross-database
                // connection issues
                // When multiple setups with different databases exist, shared pools with the
                // same name
                // would return the wrong connection (connected to a different database).
                // Include table name in pool name to ensure each event store gets its own pool.
                poolOptions.setShared(false); // Disable sharing to ensure correct database connection
                poolOptions.setName("peegeeq-bitemporal-pool-" + tableName.replace(".", "-")); // Unique pool name for
                                                                                               // monitoring

                // : Set wait queue size to 10x pool size to handle high-concurrency
                // scenarios
                // Based on performance test failures, bitemporal workloads need larger wait
                // queues
                int waitQueueMultiplier;
                try {
                    waitQueueMultiplier = Integer
                            .parseInt(System.getProperty("peegeeq.database.pool.wait-queue-multiplier", "10"));
                } catch (NumberFormatException e) {
                    logger.warn("Invalid peegeeq.database.pool.wait-queue-multiplier value, using default 10");
                    waitQueueMultiplier = 10;
                }
                poolOptions.setMaxWaitQueueSize(maxPoolSize * waitQueueMultiplier);

                // Connection timeout and idle timeout for reliability
                poolOptions.setConnectionTimeout(30000); // 30 seconds
                poolOptions.setIdleTimeout(600000); // 10 minutes

                // PERFORMANCE OPTIMIZATION: Configure event loop size for better concurrency
                // By default, Vert.x uses 2 * CPU cores event loops, but we can optimize for
                // database workloads
                int eventLoopSize;
                try {
                    eventLoopSize = Integer.parseInt(System.getProperty("peegeeq.database.event.loop.size", "0"));
                } catch (NumberFormatException e) {
                    logger.warn("Invalid peegeeq.database.event.loop.size value, using default 0");
                    eventLoopSize = 0;
                }
                if (eventLoopSize > 0) {
                    poolOptions.setEventLoopSize(eventLoopSize);
                    logger.info("Configured event loop size: {}", eventLoopSize);
                }

                // Create the Pool for transaction operations (not pipelined)
                reactivePool = PgBuilder.pool()
                        .with(poolOptions)
                        .connectingTo(connectOptions)
                        .using(this.vertx)
                        .build();

                // CRITICAL PERFORMANCE OPTIMIZATION: Create pooled SqlClient for pipelined
                // operations
                // This provides 4x performance improvement according to Vert.x research
                // Pool operations are NOT pipelined, but pooled client operations ARE pipelined
                pipelinedClient = PgBuilder.client()
                        .with(poolOptions)
                        .connectingTo(connectOptions)
                        .using(this.vertx)
                        .build();

                logger.info(
                        "CRITICAL: Created optimized Vert.x infrastructure: pool(size={}, shared={}, waitQueue={}, eventLoops={}), pipelinedClient(limit={})",
                        maxPoolSize, poolOptions.isShared(), poolOptions.getMaxWaitQueueSize(),
                        eventLoopSize > 0 ? eventLoopSize : "default", pipeliningLimit);

                // Start performance monitoring with periodic logging
                performanceMonitor.startPeriodicLogging(this.vertx, 10000); // Log every 10 seconds

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
     * Gets the configured pool size from PeeGeeQManager configuration.
     * Falls back to a reasonable default if not configured.
     *
     * @return The maximum pool size to use
     */
    private int getConfiguredPoolSize() {
        // Check system property first (allows runtime tuning)
        String systemPoolSize = System.getProperty("peegeeq.database.pool.max-size");
        if (systemPoolSize != null) {
            try {
                int size = Integer.parseInt(systemPoolSize);
                logger.info("Using system property pool size: {}", size);
                return size;
            } catch (NumberFormatException e) {
                logger.warn("Invalid peegeeq.database.pool.max-size value '{}', falling back to configuration/default", systemPoolSize);
            }
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

        // Default based on PostgreSQL best practices: connections ≈ (2 × CPU cores) + disk spindles.
        // For most deployments 20 is a safe default; override via PeeGeeQManager config.
        int defaultSize = 20;
        logger.info("Using default pool size: {} (override via PeeGeeQManager configuration)", defaultSize);
        return defaultSize;
    }

    /**
     * Returns the Vertx instance used by this event store.
     *
     * @return The Vertx instance
     */
    public Vertx getVertx() {
        return vertx;
    }

    /**
     * High-performance append method using Event Bus distribution across multiple
     * event loops.
     * This method distributes database operations to worker verticles for maximum
     * throughput.
     */
    private Future<BiTemporalEvent<T>> appendWithEventBusDistribution(
            String eventType, T payload, Instant validTime, Map<String, String> headers,
            String correlationId, String causationId, String aggregateId, String eventId, JsonObject payloadJson,
            JsonObject headersJson, String finalCorrelationId, OffsetDateTime transactionTime) {

        logger.debug("Using Event Bus distribution for high-performance database operation");

        // Create operation request for Event Bus
        JsonObject operation = new JsonObject()
                .put("operation", "append")
                .put("eventType", eventType)
                .put("payload", payloadJson)
                .put("validTime", validTime.toString())
                .put("correlationId", finalCorrelationId)
                .put("causationId", causationId)
                .put("aggregateId", aggregateId)
                .put("instanceKey", eventBusInstanceKey)
                .put("clientKey", resolveEventBusClientKey(clientId))
                .put("headers", headersJson);

        // Send operation to worker verticles via Event Bus
        return sendDatabaseOperation(tableName, operation)
                .map(result -> {
                    // Convert result back to BiTemporalEvent
                    String resultEventId = result.getString("id");
                    String resultTransactionTime = result.getString("transactionTime");
                    Instant actualTransactionTime = Instant.parse(resultTransactionTime);

                    BiTemporalEvent<T> event = new SimpleBiTemporalEvent<>(
                            resultEventId, eventType, payload, validTime, actualTransactionTime,
                            headers != null ? headers : Map.of(), finalCorrelationId, causationId, aggregateId);
                    return event;
                });
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
     * @param vertx     The Vert.x instance to deploy verticles on
     * @param instances Number of verticle instances to deploy (defaults to CPU
     *                  cores if <= 0)
     * @param tableName Table name to use for database operations
     * @return Future that completes when all verticles are deployed
     */
    public static Future<String> deployDatabaseWorkerVerticles(Vertx vertx, int instances, String tableName) {
        Objects.requireNonNull(vertx, "Vertx instance cannot be null");
        String normalizedTableName = validateTableName(tableName);
        String operationAddress = databaseOperationAddress(normalizedTableName);

        final int finalInstances = instances <= 0 ? Runtime.getRuntime().availableProcessors() : instances;

        logger.info(
                "CRITICAL PERFORMANCE: Deploying {} database worker verticle instances to distribute load across event loops",
                finalInstances);

        DeploymentOptions options = new DeploymentOptions()
                .setInstances(finalInstances)
                .setThreadingModel(ThreadingModel.EVENT_LOOP); // Use event loop threads for maximum performance

        return vertx.deployVerticle(() -> new DatabaseWorkerVerticle(normalizedTableName, operationAddress), options)
                .onSuccess(deploymentId -> {
                logger.info(
                    "SUCCESS: Deployed {} database worker verticle instances with deployment ID: {} for table '{}' on address '{}'",
                    finalInstances, deploymentId, normalizedTableName, operationAddress);
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
    private Future<JsonObject> sendDatabaseOperation(String targetTableName, JsonObject operation) {
        String operationAddress = databaseOperationAddress(targetTableName);

        // Generate unique request ID for tracking
        String requestId = UUID.randomUUID().toString();
        operation.put("requestId", requestId);

        logger.debug("Sending database operation '{}' with requestId '{}' to worker address '{}'",
                operation.getString("operation"), requestId, operationAddress);

        // Send operation to worker verticles via Event Bus
        // The Event Bus will automatically distribute requests across available
        // verticle instances
        return this.vertx.eventBus().<JsonObject>request(operationAddress, operation)
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
        private final String tableName;
        private final String quotedTableName;
        private final String operationAddress;

        DatabaseWorkerVerticle(String tableName, String operationAddress) {
            this.tableName = tableName;
            this.quotedTableName = "\"" + tableName + "\"";
            this.operationAddress = operationAddress;
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
            // CRITICAL: Extract traceparent from message headers for distributed tracing
            vertx.eventBus().<JsonObject>consumer(operationAddress, message -> {
                // Extract W3C traceparent from message headers
                String traceparent = message.headers().get("traceparent");
                TraceCtx trace = TraceContextUtil.parseOrCreate(traceparent);
                
                // Store in Vert.x Context (source of truth)
                Context ctx = vertx.getOrCreateContext();
                ctx.put(TraceContextUtil.CONTEXT_TRACE_KEY, trace);
                
                String operationType = message.body().getString("operation");
                String requestId = message.body().getString("requestId");

                // Wrap handler execution in MDC scope for logging
                try (var scope = TraceContextUtil.mdcScope(trace)) {
                    logger.debug("Processing database operation '{}' with requestId '{}' on thread: {}",
                            operationType, requestId, Thread.currentThread().getName());

                    // Process the database operation on this event loop thread
                    processDatabaseOperation(message.body())
                            .onSuccess(result -> {
                                try (var replyScope = TraceContextUtil.mdcScope(trace)) {
                                    logger.debug("Database operation '{}' completed successfully on thread: {}",
                                            operationType, Thread.currentThread().getName());
                                    message.reply(result);
                                }
                            })
                            .onFailure(error -> {
                                try (var replyScope = TraceContextUtil.mdcScope(trace)) {
                                    logger.error("Database operation '{}' failed on thread: {}: {}",
                                            operationType, Thread.currentThread().getName(), error.getMessage(), error);
                                    message.fail(500, error.getMessage());
                                }
                            });
                }
            });

                logger.info("Database worker verticle registered for event bus address '{}' on thread: {}",
                    operationAddress, threadName);
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
            String causationId = operation.getString("causationId");
            String aggregateId = operation.getString("aggregateId");
            String instanceKey = operation.getString("instanceKey");
            String clientKey = operation.getString("clientKey", DEFAULT_EVENT_BUS_CLIENT_KEY);
            JsonObject headers = operation.getJsonObject("headers");

            // Parse validTime from string to OffsetDateTime
            OffsetDateTime validTime = OffsetDateTime.parse(validTimeStr);

            // Resolve the pool from the event-bus instance registry.
            // Prefer explicit instance key; use client key as legacy fallback.
            Pool pool = resolvePoolForEventBusOperation(instanceKey, clientKey);
            if (pool == null) {
                return Future.failedFuture("Database pool not initialized for instance key: " + instanceKey
                        + " (client key fallback: " + clientKey + ")");
            }

            // Execute the database operation on this event loop thread
            // CRITICAL PERFORMANCE FIX: Use pool.preparedQuery() instead of
            // withTransaction()
            // to avoid pinning operations to a single connection and allow true concurrency
            String sql = """
                    INSERT INTO %s
                    (event_id, event_type, valid_time, transaction_time, payload, headers,
                     version, correlation_id, causation_id, aggregate_id, is_correction, created_at)
                    VALUES ($1, $2, $3, $4, $5::jsonb, $6::jsonb, $7, $8, $9, $10, $11, $12)
                    RETURNING event_id, transaction_time
                    """.formatted(quotedTableName);

            // Generate event ID for this operation
            String eventId = UUID.randomUUID().toString();
            OffsetDateTime transactionTime = OffsetDateTime.now();

            Tuple params = Tuple.of(
                    eventId, eventType, validTime, transactionTime,
                    payload, headers, 1L, correlationId, causationId, aggregateId, false, transactionTime);

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
                                .put("causationId", causationId)
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

    private static String resolveEventBusClientKey(String clientId) {
        if (clientId == null || clientId.trim().isEmpty()) {
            return DEFAULT_EVENT_BUS_CLIENT_KEY;
        }
        return clientId;
    }

    private static String createEventBusInstanceKey(String clientId, String tableName) {
        return resolveEventBusClientKey(clientId) + "|" + validateTableName(tableName) + "|" + UUID.randomUUID();
    }

    static String databaseOperationAddress(String tableName) {
        String normalizedTableName = validateTableName(tableName);
        return DB_OPERATION_ADDRESS_PREFIX + normalizedTableName;
    }

    private static String validateTableName(String tableName) {
        String normalized = Objects.requireNonNull(tableName, "Table name cannot be null").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Table name cannot be blank");
        }

        if (normalized.contains(".")) {
            throw new IllegalArgumentException(
                    "Table name must be unqualified (no schema). Configure schema via database search_path instead: "
                            + normalized);
        }

        PostgreSqlIdentifierValidator.validate(normalized, "Bi-temporal event store table");
        return normalized;
    }

    private static Pool resolvePoolForEventBusOperation(String instanceKey, String clientKey) {
        if (instanceKey != null && !instanceKey.trim().isEmpty()) {
            PgBiTemporalEventStore<?> instance = eventBusInstanceRegistry.get(instanceKey);
            if (instance == null) {
                return null;
            }
            return instance.getOrCreateReactivePool();
        }

        // Legacy fallback path: resolve by client key if no instance key was provided.
        String resolvedClientKey = resolveEventBusClientKey(clientKey);
        PgBiTemporalEventStore<?> matched = null;
        for (PgBiTemporalEventStore<?> candidate : eventBusInstanceRegistry.values()) {
            if (resolveEventBusClientKey(candidate.clientId).equals(resolvedClientKey)) {
                if (matched != null && matched != candidate) {
                    logger.warn(
                            "Ambiguous legacy event-bus client key '{}' matched multiple stores; rejecting operation",
                            resolvedClientKey);
                    return null;
                }
                matched = candidate;
            }
        }

        return matched != null ? matched.getOrCreateReactivePool() : null;
    }

    private void validateAppendParameters(String eventType, T payload, Instant validTime,
            Map<String, String> headers, String correlationId, String causationId, String aggregateId) {
        if (payload == null) {
            throw new IllegalArgumentException("Event payload cannot be null");
        }

        if (eventType == null || eventType.trim().isEmpty()) {
            throw new IllegalArgumentException("Event type cannot be null or empty");
        }

        if (validTime == null) {
            throw new IllegalArgumentException("Valid time cannot be null");
        }

        if (eventType.length() > MAX_METADATA_LENGTH) {
            throw new IllegalArgumentException("Event type cannot exceed 255 characters");
        }

        if (correlationId != null && correlationId.length() > MAX_METADATA_LENGTH) {
            throw new IllegalArgumentException("Correlation ID cannot exceed 255 characters");
        }

        if (causationId != null && causationId.length() > MAX_METADATA_LENGTH) {
            throw new IllegalArgumentException("Causation ID cannot exceed 255 characters");
        }

        if (aggregateId != null && aggregateId.length() > MAX_METADATA_LENGTH) {
            throw new IllegalArgumentException("Aggregate ID cannot exceed 255 characters");
        }

        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey() == null || entry.getKey().trim().isEmpty()) {
                    throw new IllegalArgumentException("Header keys cannot be null or empty");
                }
                if (entry.getValue() == null) {
                    throw new IllegalArgumentException("Header values cannot be null");
                }
            }
        }

        Instant maxFutureTime = Instant.now().plusSeconds(MAX_FUTURE_SECONDS);
        if (validTime.isAfter(maxFutureTime)) {
            throw new IllegalArgumentException("Valid time cannot be more than 1 year in the future");
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
            eventBusInstanceRegistry.clear();
        }
    }

    /**
     * Clears this instance's cached connection pools.
     * This forces recreation of pools on next access with current configuration.
     *
     * IMPORTANT: This method should only be called during testing when you need to
     * force recreation of connection pools with updated configuration.
     */
    public Future<Void> clearInstancePools() {
        Future<Void> chain = Future.succeededFuture();
        synchronized (this) {
            if (reactivePool != null) {
                Pool poolRef = reactivePool;
                reactivePool = null;
                chain = chain.compose(v -> poolRef.close());
            }
            if (pipelinedClient != null) {
                SqlClient clientRef = pipelinedClient;
                pipelinedClient = null;
                chain = chain.compose(v -> clientRef.close());
            }
        }
        return chain;
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
            // Capture Trace Context from current thread to propagate to the Vert.x context
            var traceCtx = TraceContextUtil.captureTraceContext();

            // Execute on Vert.x context using runOnContext
            io.vertx.core.Promise<T> promise = io.vertx.core.Promise.promise();
            context.runOnContext(v -> {
                // Restore Trace Context inside the Vert.x event loop execution
                try (var scope = TraceContextUtil.mdcScope(traceCtx)) {
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
     * Data record for batch event operations.
     */
    public record BatchEventData<T>(String eventType, T payload, Instant validTime, Map<String, String> headers,
            String correlationId, String aggregateId) {
    }

}
