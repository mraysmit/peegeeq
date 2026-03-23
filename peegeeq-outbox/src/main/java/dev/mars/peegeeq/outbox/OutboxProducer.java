package dev.mars.peegeeq.outbox;

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

import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.database.MetricsProvider;
import dev.mars.peegeeq.api.database.NoOpMetricsProvider;
import dev.mars.peegeeq.db.client.PgClientFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.TransactionPropagation;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;


/**
 * Outbox pattern message producer implementation.
 *
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
public class OutboxProducer<T> implements dev.mars.peegeeq.api.messaging.MessageProducer<T> {
    private static final Logger logger = LoggerFactory.getLogger(OutboxProducer.class);

    private static final String OUTBOX_INSERT_SQL = """
            INSERT INTO outbox (topic, payload, headers, correlation_id, message_group, created_at, status, idempotency_key)
            VALUES ($1, $2::jsonb, $3::jsonb, $4, $5, $6, 'PENDING', $7)
            ON CONFLICT (topic, idempotency_key) WHERE idempotency_key IS NOT NULL DO NOTHING
            """;

    private final PgClientFactory clientFactory;
    private final DatabaseService databaseService;
    private final ObjectMapper objectMapper;
    private final String topic;
    @SuppressWarnings("unused") // Reserved for future type safety features
    private final Class<T> payloadType;
    private final MetricsProvider metrics;
    // Best-effort close flag: in-flight sends may still complete after close() is called
    private volatile boolean closed = false;

    // Client ID for pool lookup - null means use default pool (resolved by
    // PgClientFactory)
    private final String clientId;

    public OutboxProducer(PgClientFactory clientFactory, ObjectMapper objectMapper,
            String topic, Class<T> payloadType, MetricsProvider metrics) {
        this(clientFactory, objectMapper, topic, payloadType, metrics, null);
    }

    public OutboxProducer(PgClientFactory clientFactory, ObjectMapper objectMapper,
            String topic, Class<T> payloadType, MetricsProvider metrics,
            String clientId) {
        this.clientFactory = clientFactory;
        this.databaseService = null;
        this.objectMapper = objectMapper;
        this.topic = topic;
        this.payloadType = payloadType;
        this.metrics = metrics != null ? metrics : NoOpMetricsProvider.INSTANCE;
        this.clientId = clientId; // null means use default pool
        logger.info("Created outbox producer for topic: {} (clientId: {})", topic,
                clientId != null ? clientId : "default");
    }

    public OutboxProducer(DatabaseService databaseService, ObjectMapper objectMapper,
            String topic, Class<T> payloadType, MetricsProvider metrics) {
        this(databaseService, objectMapper, topic, payloadType, metrics, null);
    }

    public OutboxProducer(DatabaseService databaseService, ObjectMapper objectMapper,
            String topic, Class<T> payloadType, MetricsProvider metrics,
            String clientId) {
        this.clientFactory = null;
        this.databaseService = databaseService;
        this.objectMapper = objectMapper;
        this.topic = topic;
        this.payloadType = payloadType;
        this.metrics = metrics != null ? metrics : NoOpMetricsProvider.INSTANCE;
        this.clientId = clientId; // null means use default pool
        logger.info("Created outbox producer for topic: {} (using DatabaseService, clientId: {})", topic,
                clientId != null ? clientId : "default");
    }

    /**
     * Sends a message to the outbox table for eventual delivery.
     * Now uses Vert.x 5.x reactive patterns by default for better performance and
     * non-blocking operations.
     *
     * @param payload The message payload to send
     * @return Future that completes when the message is stored in the
     *         outbox
     */
    @Override
    public Future<Void> send(T payload) {
        return sendInternalReactive(payload, null, null, null);
    }

    /**
     * Sends a message with headers to the outbox table.
     * Now uses Vert.x 5.x reactive patterns by default for better performance and
     * non-blocking operations.
     *
     * @param payload The message payload to send
     * @param headers Optional message headers
     * @return Future that completes when the message is stored in the
     *         outbox
     */
    @Override
    public Future<Void> send(T payload, Map<String, String> headers) {
        return sendInternalReactive(payload, headers, null, null);
    }

    /**
     * Sends a message with headers and correlation ID to the outbox table.
     * Now uses Vert.x 5.x reactive patterns by default for better performance and
     * non-blocking operations.
     *
     * @param payload       The message payload to send
     * @param headers       Optional message headers
     * @param correlationId Optional correlation ID for message tracking
     * @return Future that completes when the message is stored in the
     *         outbox
     */
    @Override
    public Future<Void> send(T payload, Map<String, String> headers, String correlationId) {
        return sendInternalReactive(payload, headers, correlationId, null);
    }

    /**
     * Sends a message with all parameters to the outbox table.
     * Now uses Vert.x 5.x reactive patterns by default for better performance and
     * non-blocking operations.
     *
     * @param payload       The message payload to send
     * @param headers       Optional message headers
     * @param correlationId Optional correlation ID for message tracking
     * @param messageGroup  Optional message group for ordering
     * @return Future that completes when the message is stored in the
     *         outbox
     */
    @Override
    public Future<Void> send(T payload, Map<String, String> headers, String correlationId,
            String messageGroup) {
        return sendInternalReactive(payload, headers, correlationId, messageGroup);
    }

    /**
     * Core send implementation using pure Vert.x Future patterns.
     * Uses ON CONFLICT for SQL-native idempotency handling.
     */
    private Future<Void> sendInternalReactive(T payload, Map<String, String> headers,
            String correlationId, String messageGroup) {
        if (closed) {
            return Future.failedFuture(new IllegalStateException("Producer is closed"));
        }

        if (payload == null) {
            return Future.failedFuture(new IllegalArgumentException("Message payload cannot be null"));
        }

        try {
            JsonObject payloadJson = toJsonObject(payload);
            JsonObject headersJson = headersToJsonObject(headers);
            String finalCorrelationId = correlationId != null ? correlationId : UUID.randomUUID().toString();

            // Normalize empty idempotency keys to null to allow duplicates
            String rawIdempotencyKey = (headers != null && headers.containsKey("idempotencyKey"))
                    ? headers.get("idempotencyKey")
                    : null;
            final String idempotencyKey = (rawIdempotencyKey != null && !rawIdempotencyKey.trim().isEmpty())
                    ? rawIdempotencyKey
                    : null;

            Tuple params = Tuple.of(
                    topic,
                    payloadJson,
                    headersJson,
                    finalCorrelationId,
                    messageGroup,
                    OffsetDateTime.now(),
                    idempotencyKey);

            return getReactivePoolFuture()
                    .compose(pool -> pool.preparedQuery(OUTBOX_INSERT_SQL).execute(params))
                    .map(rows -> {
                        logSendOutcome(rows, finalCorrelationId, idempotencyKey);
                        return (Void) null;
                    })
                    .onFailure(error -> logger.error("Failed to send message to outbox topic {}", topic, error));

        } catch (Exception e) {
            logger.error("Error preparing message for outbox topic {}", topic, e);
            return Future.failedFuture(e);
        }
    }

    /**
     * Sends a message inside its own dedicated transaction using Pool.withTransaction().
     * This wraps the outbox insert in an isolated transaction — it does NOT participate
     * in any caller-initiated transaction. For true transactional outbox semantics
     * (atomicity with business writes), use {@link #sendInExistingTransaction} instead.
     *
     * @param payload The message payload to send
     * @return Future that completes when the message is stored in the outbox
     */
    public Future<Void> sendInOwnTransaction(T payload) {
        return sendInOwnTransactionInternal(payload, null, null, null, null);
    }

    /**
     * Sends a message with headers inside its own dedicated transaction.
     * See {@link #sendInOwnTransaction(Object)} for transaction semantics.
     */
    public Future<Void> sendInOwnTransaction(T payload, Map<String, String> headers) {
        return sendInOwnTransactionInternal(payload, headers, null, null, null);
    }

    /**
     * Sends a message with headers and correlation ID inside its own dedicated transaction.
     * See {@link #sendInOwnTransaction(Object)} for transaction semantics.
     */
    public Future<Void> sendInOwnTransaction(T payload, Map<String, String> headers, String correlationId) {
        return sendInOwnTransactionInternal(payload, headers, correlationId, null, null);
    }

    /**
     * Sends a message inside its own dedicated transaction with TransactionPropagation support.
     * See {@link #sendInOwnTransaction(Object)} for transaction semantics.
     */
    public Future<Void> sendInOwnTransaction(T payload, TransactionPropagation propagation) {
        return sendInOwnTransactionInternal(payload, null, null, null, propagation);
    }

    /**
     * Sends a message with headers inside its own dedicated transaction with TransactionPropagation.
     * See {@link #sendInOwnTransaction(Object)} for transaction semantics.
     */
    public Future<Void> sendInOwnTransaction(T payload, Map<String, String> headers,
            TransactionPropagation propagation) {
        return sendInOwnTransactionInternal(payload, headers, null, null, propagation);
    }

    /**
     * Sends a message with headers, correlation ID inside its own dedicated transaction
     * with TransactionPropagation.
     * See {@link #sendInOwnTransaction(Object)} for transaction semantics.
     */
    public Future<Void> sendInOwnTransaction(T payload, Map<String, String> headers, String correlationId,
            TransactionPropagation propagation) {
        return sendInOwnTransactionInternal(payload, headers, correlationId, null, propagation);
    }

    /**
     * Full own-transaction send with all parameters and TransactionPropagation.
     * See {@link #sendInOwnTransaction(Object)} for transaction semantics.
     */
    public Future<Void> sendInOwnTransaction(T payload, Map<String, String> headers,
            String correlationId, String messageGroup,
            TransactionPropagation propagation) {
        return sendInOwnTransactionInternal(payload, headers, correlationId, messageGroup, propagation);
    }

    /**
     * Core own-transaction implementation using Pool.withTransaction().
     * Uses ON CONFLICT for SQL-native idempotency handling.
     */
    private Future<Void> sendInOwnTransactionInternal(T payload, Map<String, String> headers,
            String correlationId, String messageGroup,
            TransactionPropagation propagation) {
        if (closed) {
            return Future.failedFuture(new IllegalStateException("Producer is closed"));
        }

        if (payload == null) {
            return Future.failedFuture(new IllegalArgumentException("Message payload cannot be null"));
        }

        try {
            JsonObject payloadJson = toJsonObject(payload);
            JsonObject headersJson = headersToJsonObject(headers);
            String finalCorrelationId = correlationId != null ? correlationId : UUID.randomUUID().toString();

            // Normalize empty idempotency keys to null to allow duplicates
            String rawIdempotencyKey = (headers != null && headers.containsKey("idempotencyKey"))
                    ? headers.get("idempotencyKey")
                    : null;
            final String idempotencyKey = (rawIdempotencyKey != null && !rawIdempotencyKey.trim().isEmpty())
                    ? rawIdempotencyKey
                    : null;

            Tuple params = Tuple.of(
                    topic,
                    payloadJson,
                    headersJson,
                    finalCorrelationId,
                    messageGroup,
                    OffsetDateTime.now(),
                    idempotencyKey);

            return getReactivePoolFuture().compose(pool -> {
                if (propagation != null) {
                    return pool.withTransaction(propagation,
                            client -> client.preparedQuery(OUTBOX_INSERT_SQL).execute(params));
                } else {
                    return pool.withTransaction(
                            client -> client.preparedQuery(OUTBOX_INSERT_SQL).execute(params));
                }
            })
            .map(rows -> {
                logSendOutcome(rows, finalCorrelationId, idempotencyKey);
                return (Void) null;
            })
            .onFailure(error -> logger.error("Failed to send message in own transaction to outbox topic {}", topic, error));

        } catch (Exception e) {
            logger.error("Error preparing own-transaction message for outbox topic {}", topic, e);
            return Future.failedFuture(e);
        }
    }

    /**
     * Sends a message using an existing caller-provided connection/transaction.
     * This is the real outbox-pattern API: it participates in the caller's business
     * transaction, ensuring atomicity between business writes and the outbox insert.
     *
     * <p><b>Contract:</b> This method assumes the caller has already begun a transaction
     * on the provided connection. It does not verify transaction state — if no transaction
     * is active, the insert will still execute but will auto-commit independently.</p>
     *
     * @param payload    The message payload to send
     * @param connection Existing Vert.x SqlConnection (caller must manage transaction lifecycle)
     * @return Future that completes when the message is stored in the outbox
     */
    public Future<Void> sendInExistingTransaction(T payload, io.vertx.sqlclient.SqlConnection connection) {
        return sendInExistingTransaction(payload, null, null, null, connection);
    }

    /**
     * Sends a message with headers using an existing caller-provided connection/transaction.
     * See {@link #sendInExistingTransaction(Object, io.vertx.sqlclient.SqlConnection)} for contract.
     */
    public Future<Void> sendInExistingTransaction(T payload, Map<String, String> headers,
            io.vertx.sqlclient.SqlConnection connection) {
        return sendInExistingTransaction(payload, headers, null, null, connection);
    }

    /**
     * Sends a message with headers and correlation ID using an existing connection/transaction.
     * See {@link #sendInExistingTransaction(Object, io.vertx.sqlclient.SqlConnection)} for contract.
     */
    public Future<Void> sendInExistingTransaction(T payload, Map<String, String> headers, String correlationId,
            io.vertx.sqlclient.SqlConnection connection) {
        return sendInExistingTransaction(payload, headers, correlationId, null, connection);
    }

    /**
     * Full existing-transaction send with all parameters.
     * See {@link #sendInExistingTransaction(Object, io.vertx.sqlclient.SqlConnection)} for contract.
     */
    public Future<Void> sendInExistingTransaction(T payload, Map<String, String> headers,
            String correlationId, String messageGroup,
            io.vertx.sqlclient.SqlConnection connection) {
        if (closed) {
            return Future.failedFuture(new IllegalStateException("Producer is closed"));
        }

        if (payload == null) {
            return Future.failedFuture(new IllegalArgumentException("Message payload cannot be null"));
        }

        if (connection == null) {
            return Future.failedFuture(new IllegalArgumentException("Vert.x connection cannot be null"));
        }

        try {
            JsonObject payloadJson = toJsonObject(payload);
            JsonObject headersJson = headersToJsonObject(headers);
            String finalCorrelationId = correlationId != null ? correlationId : UUID.randomUUID().toString();

            // Normalize empty idempotency keys to null to allow duplicates
            String rawIdempotencyKey = (headers != null && headers.containsKey("idempotencyKey"))
                    ? headers.get("idempotencyKey")
                    : null;
            final String idempotencyKey = (rawIdempotencyKey != null && !rawIdempotencyKey.trim().isEmpty())
                    ? rawIdempotencyKey
                    : null;

            Tuple params = Tuple.of(
                    topic,
                    payloadJson,
                    headersJson,
                    finalCorrelationId,
                    messageGroup,
                    OffsetDateTime.now(),
                    idempotencyKey);

            return connection.preparedQuery(OUTBOX_INSERT_SQL)
                    .execute(params)
                    .map(rows -> {
                        logSendOutcome(rows, finalCorrelationId, idempotencyKey);
                        return (Void) null;
                    })
                    .onFailure(error -> logger.error("Failed to send message in existing transaction to outbox topic {}", topic, error));

        } catch (Exception e) {
            logger.error("Error preparing existing-transaction message for outbox topic {}", topic, e);
            return Future.failedFuture(e);
        }
    }

    @Override
    public void close() {
        // Best-effort close: in-flight sends may still complete.
        // Pool is a shared resource managed by PgConnectionManager/ConnectionProvider — not owned by this producer.
        closed = true;
        logger.info("Closed outbox producer for topic: {}", topic);
    }

    /**
     * Reactive acquisition of the pool without blocking.
     * Uses clientId for pool lookup - null clientId is resolved to the default pool
     * by PgClientFactory.
     *
     * @return Future that completes with the Pool instance
     */
    private Future<Pool> getReactivePoolFuture() {
        // clientId can be null - PgClientFactory/ConnectionProvider resolves null to
        // the default pool
        if (databaseService != null) {
            return databaseService.getConnectionProvider().getReactivePool(clientId);
        }
        if (clientFactory != null) {
            try {
                var connectionConfig = clientFactory.getConnectionConfig(clientId);
                var poolConfig = clientFactory.getPoolConfig(clientId);
                if (connectionConfig == null) {
                    String poolName = clientId != null ? clientId : "default";
                    return Future.failedFuture(
                            new IllegalStateException("Connection configuration '" + poolName + "' not found"));
                }
                if (poolConfig == null) {
                    poolConfig = new dev.mars.peegeeq.db.config.PgPoolConfig.Builder().build();
                }
                // Use clientId for pool creation - null is resolved to default by
                // PgConnectionManager
                String resolvedClientId = clientId != null ? clientId
                        : dev.mars.peegeeq.db.PeeGeeQDefaults.DEFAULT_POOL_ID;
                Pool pool = clientFactory.getConnectionManager()
                        .getOrCreateReactivePool(resolvedClientId, connectionConfig, poolConfig);
                return Future.succeededFuture(pool);
            } catch (Exception e) {
                return Future.failedFuture(e);
            }
        }
        return Future.failedFuture(new IllegalStateException("No client factory or database service available"));
    }

    /**
     * Logs the outcome of an outbox insert, distinguishing new inserts from idempotent duplicates.
     */
    private void logSendOutcome(RowSet<Row> rows, String correlationId, String idempotencyKey) {
        if (rows.rowCount() == 0 && idempotencyKey != null) {
            logger.debug("Duplicate idempotency key for outbox topic {}: {} (correlationId: {}) - message already exists",
                    topic, idempotencyKey, correlationId);
        } else {
            logger.debug("Message sent to outbox for topic {} (correlationId: {}, idempotencyKey: {})",
                    topic, correlationId, idempotencyKey);
            metrics.recordMessageSent(topic);
        }
    }

    /**
     * Convert payload to JsonObject for proper JSONB storage.
     * Handles both simple values (wrapped in {"value": ...}) and complex objects.
     * Uses the properly configured ObjectMapper to handle JSR310 types like
     * LocalDateTime.
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
            logger.error("Error preparing reactive message for topic {}: {}", topic, e.getMessage());
            throw new RuntimeException("Failed to serialize payload to JSON", e);
        }
    }

    /**
     * Convert headers map to JsonObject for proper JSONB storage.
     */
    private JsonObject headersToJsonObject(Map<String, String> headers) {
        if (headers == null || headers.isEmpty())
            return new JsonObject();
        // Convert Map<String, String> to Map<String, Object> for JsonObject constructor
        Map<String, Object> objectMap = new java.util.HashMap<>(headers);
        return new JsonObject(objectMap);
    }
}
