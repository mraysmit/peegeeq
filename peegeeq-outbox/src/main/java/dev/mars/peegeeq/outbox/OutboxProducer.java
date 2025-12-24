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
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.TransactionPropagation;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

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

    private final PgClientFactory clientFactory;
    private final DatabaseService databaseService;
    private final ObjectMapper objectMapper;
    private final String topic;
    @SuppressWarnings("unused") // Reserved for future type safety features
    private final Class<T> payloadType;
    private final MetricsProvider metrics;
    private final PeeGeeQConfiguration configuration;
    private volatile boolean closed = false;

    // Client ID for pool lookup - null means use default pool (resolved by
    // PgClientFactory)
    private final String clientId;

    // Reactive Vert.x components - following PgNativeQueueProducer pattern
    private volatile Pool reactivePool;

    public OutboxProducer(PgClientFactory clientFactory, ObjectMapper objectMapper,
            String topic, Class<T> payloadType, MetricsProvider metrics) {
        this(clientFactory, objectMapper, topic, payloadType, metrics, null, null);
    }

    public OutboxProducer(PgClientFactory clientFactory, ObjectMapper objectMapper,
            String topic, Class<T> payloadType, MetricsProvider metrics,
            PeeGeeQConfiguration configuration, String clientId) {
        this.clientFactory = clientFactory;
        this.databaseService = null;
        this.objectMapper = objectMapper;
        this.topic = topic;
        this.payloadType = payloadType;
        this.metrics = metrics != null ? metrics : NoOpMetricsProvider.INSTANCE;
        this.configuration = configuration;
        this.clientId = clientId; // null means use default pool
        logger.info("Created outbox producer for topic: {} (clientId: {})", topic,
                clientId != null ? clientId : "default");
    }

    public OutboxProducer(DatabaseService databaseService, ObjectMapper objectMapper,
            String topic, Class<T> payloadType, MetricsProvider metrics) {
        this(databaseService, objectMapper, topic, payloadType, metrics, null, null);
    }

    public OutboxProducer(DatabaseService databaseService, ObjectMapper objectMapper,
            String topic, Class<T> payloadType, MetricsProvider metrics,
            PeeGeeQConfiguration configuration, String clientId) {
        this.clientFactory = null;
        this.databaseService = databaseService;
        this.objectMapper = objectMapper;
        this.topic = topic;
        this.payloadType = payloadType;
        this.metrics = metrics != null ? metrics : NoOpMetricsProvider.INSTANCE;
        this.configuration = configuration;
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
     * @return CompletableFuture that completes when the message is stored in the
     *         outbox
     */
    @Override
    public CompletableFuture<Void> send(T payload) {
        return sendInternal(payload);
    }

    /**
     * Sends a message with headers to the outbox table.
     * Now uses Vert.x 5.x reactive patterns by default for better performance and
     * non-blocking operations.
     *
     * @param payload The message payload to send
     * @param headers Optional message headers
     * @return CompletableFuture that completes when the message is stored in the
     *         outbox
     */
    @Override
    public CompletableFuture<Void> send(T payload, Map<String, String> headers) {
        return sendInternal(payload, headers);
    }

    /**
     * Sends a message with headers and correlation ID to the outbox table.
     * Now uses Vert.x 5.x reactive patterns by default for better performance and
     * non-blocking operations.
     *
     * @param payload       The message payload to send
     * @param headers       Optional message headers
     * @param correlationId Optional correlation ID for message tracking
     * @return CompletableFuture that completes when the message is stored in the
     *         outbox
     */
    @Override
    public CompletableFuture<Void> send(T payload, Map<String, String> headers, String correlationId) {
        return sendInternal(payload, headers, correlationId);
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
     * @return CompletableFuture that completes when the message is stored in the
     *         outbox
     */
    @Override
    public CompletableFuture<Void> send(T payload, Map<String, String> headers, String correlationId,
            String messageGroup) {
        return sendInternal(payload, headers, correlationId, messageGroup);
    }

    /**
     * Internal send method using Vert.x SqlClient - following PgNativeQueueProducer
     * pattern.
     * This method provides non-blocking database operations for better performance.
     *
     * @param payload The message payload to send
     * @return CompletableFuture that completes when the message is stored in the
     *         outbox
     */
    public CompletableFuture<Void> sendInternal(T payload) {
        return sendInternal(payload, null, null, null);
    }

    /**
     * Internal send method with headers.
     *
     * @param payload The message payload to send
     * @param headers Optional message headers
     * @return CompletableFuture that completes when the message is stored in the
     *         outbox
     */
    public CompletableFuture<Void> sendInternal(T payload, Map<String, String> headers) {
        return sendInternal(payload, headers, null, null);
    }

    /**
     * Internal send method with headers and correlation ID.
     *
     * @param payload       The message payload to send
     * @param headers       Optional message headers
     * @param correlationId Optional correlation ID for message tracking
     * @return CompletableFuture that completes when the message is stored in the
     *         outbox
     */
    public CompletableFuture<Void> sendInternal(T payload, Map<String, String> headers, String correlationId) {
        return sendInternal(payload, headers, correlationId, null);
    }

    /**
     * Full internal send method with all parameters.
     *
     * @param payload       The message payload to send
     * @param headers       Optional message headers
     * @param correlationId Optional correlation ID for message tracking
     * @param messageGroup  Optional message group for ordering
     * @return CompletableFuture that completes when the message is stored in the
     *         outbox
     */
    public CompletableFuture<Void> sendInternal(T payload, Map<String, String> headers,
            String correlationId, String messageGroup) {
        return sendInternalReactive(payload, headers, correlationId, messageGroup)
                .toCompletionStage().toCompletableFuture();
    }

    /**
     * Internal reactive send method using pure Vert.x Future patterns.
     * This is the core implementation that uses Vert.x 5.x reactive patterns
     * throughout.
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
            String messageId = UUID.randomUUID().toString();
            JsonObject payloadJson = toJsonObject(payload);
            JsonObject headersJson = headersToJsonObject(headers);
            String finalCorrelationId = correlationId != null ? correlationId : messageId;

            // Extract idempotency key from headers (Phase 2: Message Deduplication)
            // Normalize empty strings to null to allow duplicates
            String rawIdempotencyKey = (headers != null && headers.containsKey("idempotencyKey"))
                    ? headers.get("idempotencyKey")
                    : null;
            final String idempotencyKey = (rawIdempotencyKey != null && !rawIdempotencyKey.trim().isEmpty())
                    ? rawIdempotencyKey
                    : null;
            if (idempotencyKey != null) {
                logger.debug("Using idempotency key for outbox: {}", idempotencyKey);
            }

            String sql = """
                    INSERT INTO outbox (topic, payload, headers, correlation_id, message_group, created_at, status, idempotency_key)
                    VALUES ($1, $2::jsonb, $3::jsonb, $4, $5, $6, 'PENDING', $7)
                    """;

            Tuple params = Tuple.of(
                    topic,
                    payloadJson,
                    headersJson,
                    finalCorrelationId,
                    messageGroup,
                    OffsetDateTime.now(),
                    idempotencyKey);

            Future<Void> result = getReactivePoolFuture()
                    .compose(pool -> pool.preparedQuery(sql).execute(params).mapEmpty())
                    .recover(error -> {
                        // Check if this is a duplicate idempotency key error (Phase 2: Message Deduplication)
                        String errorMsg = error.getMessage();
                        if (errorMsg != null && errorMsg.contains("idx_outbox_idempotency_key")) {
                            logger.info("Duplicate idempotency key detected for outbox topic {}: {} - message already exists",
                                    topic, idempotencyKey);
                            // Return success - message already exists with this idempotency key
                            return Future.succeededFuture();
                        }
                        // Re-throw other errors
                        return Future.failedFuture(error);
                    }).mapEmpty();

            return result
                    .onSuccess(v -> {
                        if (idempotencyKey != null) {
                            logger.debug("Message sent to outbox reactively for topic {}: {} (idempotencyKey: {}, correlationId: {})",
                                    topic, messageId, idempotencyKey, finalCorrelationId);
                        } else {
                            logger.debug("Message sent to outbox reactively for topic {}: {} (correlationId: {})",
                                    topic, messageId, finalCorrelationId);
                        }
                        metrics.recordMessageSent(topic);
                    })
                    .onFailure(error -> {
                        if (idempotencyKey != null) {
                            logger.error("Failed to send message reactively to topic {}: {} (idempotencyKey: {})",
                                    topic, error.getMessage(), idempotencyKey);
                        } else {
                            logger.error("Failed to send message reactively to topic {}: {}", topic, error.getMessage());
                        }
                    });

        } catch (Exception e) {
            logger.error("Error preparing reactive message for topic {}: {}", topic, e.getMessage());
            return Future.failedFuture(e);
        }
    }

    /**
     * Production-grade transactional send method using official Vert.x
     * withTransaction API.
     * Uses default transaction propagation behavior.
     *
     * @param payload The message payload to send
     * @return CompletableFuture that completes when the message is stored in the
     *         outbox
     */
    public CompletableFuture<Void> sendWithTransaction(T payload) {
        return sendWithTransactionInternal(payload, null, null, null, null);
    }

    /**
     * Production-grade transactional send method with headers using official Vert.x
     * API.
     * Uses default transaction propagation behavior.
     *
     * @param payload The message payload to send
     * @param headers Optional message headers
     * @return CompletableFuture that completes when the message is stored in the
     *         outbox
     */
    public CompletableFuture<Void> sendWithTransaction(T payload, Map<String, String> headers) {
        return sendWithTransactionInternal(payload, headers, null, null, null);
    }

    /**
     * Production-grade transactional send method with headers, correlation ID using
     * official Vert.x API.
     * Uses default transaction propagation behavior.
     *
     * @param payload       The message payload to send
     * @param headers       Optional message headers
     * @param correlationId Optional correlation ID for message tracking
     * @return CompletableFuture that completes when the message is stored in the
     *         outbox
     */
    public CompletableFuture<Void> sendWithTransaction(T payload, Map<String, String> headers, String correlationId) {
        return sendWithTransactionInternal(payload, headers, correlationId, null, null);
    }

    /**
     * Production-grade transactional send method with TransactionPropagation
     * support.
     * This method uses Vert.x 5 TransactionPropagation for advanced transaction
     * management.
     *
     * @param payload     The message payload to send
     * @param propagation Transaction propagation behavior (e.g., CONTEXT for
     *                    sharing existing transactions)
     * @return CompletableFuture that completes when the message is stored in the
     *         outbox
     */
    public CompletableFuture<Void> sendWithTransaction(T payload, TransactionPropagation propagation) {
        return sendWithTransactionInternal(payload, null, null, null, propagation);
    }

    /**
     * Production-grade transactional send method with headers and
     * TransactionPropagation support.
     *
     * @param payload     The message payload to send
     * @param headers     Optional message headers
     * @param propagation Transaction propagation behavior
     * @return CompletableFuture that completes when the message is stored in the
     *         outbox
     */
    public CompletableFuture<Void> sendWithTransaction(T payload, Map<String, String> headers,
            TransactionPropagation propagation) {
        return sendWithTransactionInternal(payload, headers, null, null, propagation);
    }

    /**
     * Production-grade transactional send method with headers, correlation ID and
     * TransactionPropagation support.
     *
     * @param payload       The message payload to send
     * @param headers       Optional message headers
     * @param correlationId Optional correlation ID for message tracking
     * @param propagation   Transaction propagation behavior
     * @return CompletableFuture that completes when the message is stored in the
     *         outbox
     */
    public CompletableFuture<Void> sendWithTransaction(T payload, Map<String, String> headers, String correlationId,
            TransactionPropagation propagation) {
        return sendWithTransactionInternal(payload, headers, correlationId, null, propagation);
    }

    /**
     * Full production-grade transactional send method with all parameters and
     * TransactionPropagation support.
     *
     * @param payload       The message payload to send
     * @param headers       Optional message headers
     * @param correlationId Optional correlation ID for message tracking
     * @param messageGroup  Optional message group for ordering
     * @param propagation   Transaction propagation behavior
     * @return CompletableFuture that completes when the message is stored in the
     *         outbox
     */
    public CompletableFuture<Void> sendWithTransaction(T payload, Map<String, String> headers,
            String correlationId, String messageGroup,
            TransactionPropagation propagation) {
        return sendWithTransactionInternal(payload, headers, correlationId, messageGroup, propagation);
    }

    /**
     * Internal implementation for production-grade transactional send method.
     * This method uses the official Vert.x Pool.withTransaction() which handles:
     * - Automatic transaction begin/commit/rollback
     * - Connection management
     * - Proper error handling and automatic rollback on failure
     * - TransactionPropagation support for advanced transaction management
     *
     * @param payload       The message payload to send
     * @param headers       Optional message headers
     * @param correlationId Optional correlation ID for message tracking
     * @param messageGroup  Optional message group for ordering
     * @param propagation   Optional transaction propagation behavior (null for
     *                      default)
     * @return CompletableFuture that completes when the message is stored in the
     *         outbox
     */
    private CompletableFuture<Void> sendWithTransactionInternal(T payload, Map<String, String> headers,
            String correlationId, String messageGroup,
            TransactionPropagation propagation) {
        return sendWithTransactionInternalReactive(payload, headers, correlationId, messageGroup, propagation)
                .toCompletionStage().toCompletableFuture();
    }

    /**
     * Internal reactive transactional send method using pure Vert.x Future
     * patterns.
     */
    private Future<Void> sendWithTransactionInternalReactive(T payload, Map<String, String> headers,
            String correlationId, String messageGroup,
            TransactionPropagation propagation) {
        if (closed) {
            return Future.failedFuture(new IllegalStateException("Producer is closed"));
        }

        if (payload == null) {
            return Future.failedFuture(new IllegalArgumentException("Message payload cannot be null"));
        }

        try {
            String messageId = UUID.randomUUID().toString();
            JsonObject payloadJson = toJsonObject(payload);
            JsonObject headersJson = headersToJsonObject(headers);
            String finalCorrelationId = correlationId != null ? correlationId : messageId;

            // Extract idempotency key from headers (Phase 2: Message Deduplication)
            // Normalize empty strings to null to allow duplicates
            String rawIdempotencyKey = (headers != null && headers.containsKey("idempotencyKey"))
                    ? headers.get("idempotencyKey")
                    : null;
            final String idempotencyKey = (rawIdempotencyKey != null && !rawIdempotencyKey.trim().isEmpty())
                    ? rawIdempotencyKey
                    : null;
            if (idempotencyKey != null) {
                logger.debug("Using idempotency key for outbox transaction: {}", idempotencyKey);
            }

            String sql = """
                    INSERT INTO outbox (topic, payload, headers, correlation_id, message_group, created_at, status, idempotency_key)
                    VALUES ($1, $2::jsonb, $3::jsonb, $4, $5, $6, 'PENDING', $7)
                    """;

            Tuple params = Tuple.of(
                    topic,
                    payloadJson,
                    headersJson,
                    finalCorrelationId,
                    messageGroup,
                    OffsetDateTime.now(),
                    idempotencyKey);

            // Vert.x Pool.withTransaction() automatically handles event loop context
            // No explicit executeOnVertxContext wrapper needed - Pool manages this
            // internally
            Future<Void> tx = getReactivePoolFuture().compose(pool -> {
                if (propagation != null) {
                    return pool.withTransaction(propagation,
                            client -> client.preparedQuery(sql).execute(params).mapEmpty())
                            .recover(error -> {
                                // Check if this is a duplicate idempotency key error
                                String errorMsg = error.getMessage();
                                if (errorMsg != null && errorMsg.contains("idx_outbox_idempotency_key")) {
                                    logger.info("Duplicate idempotency key detected for outbox transaction topic {}: {} - message already exists",
                                            topic, idempotencyKey);
                                    return Future.succeededFuture();
                                }
                                return Future.failedFuture(error);
                            }).mapEmpty();
                } else {
                    return pool.withTransaction(client -> client.preparedQuery(sql).execute(params).mapEmpty())
                            .recover(error -> {
                                // Check if this is a duplicate idempotency key error
                                String errorMsg = error.getMessage();
                                if (errorMsg != null && errorMsg.contains("idx_outbox_idempotency_key")) {
                                    logger.info("Duplicate idempotency key detected for outbox transaction topic {}: {} - message already exists",
                                            topic, idempotencyKey);
                                    return Future.succeededFuture();
                                }
                                return Future.failedFuture(error);
                            }).mapEmpty();
                }
            });

            return tx
                    .onSuccess(v -> {
                        if (idempotencyKey != null) {
                            logger.debug("Message sent to outbox with transaction for topic {}: {} (idempotencyKey: {}, correlationId: {})",
                                    topic, messageId, idempotencyKey, finalCorrelationId);
                        } else {
                            logger.debug("Message sent to outbox with transaction for topic {}: {} (correlationId: {})",
                                    topic, messageId, finalCorrelationId);
                        }
                        metrics.recordMessageSent(topic);
                    })
                    .onFailure(error -> {
                        if (idempotencyKey != null) {
                            logger.error("Failed to send message with transaction to topic {}: {} (idempotencyKey: {})",
                                    topic, error.getMessage(), idempotencyKey);
                        } else {
                            logger.error("Failed to send message with transaction to topic {}: {}", topic,
                                    error.getMessage());
                        }
                    });

        } catch (Exception e) {
            logger.error("Error preparing transactional message for topic {}: {}", topic, e.getMessage());
            return Future.failedFuture(e);
        }
    }

    /**
     * Transaction-aware reactive send method using existing Vert.x connection.
     * This method allows the outbox operation to participate in an existing
     * transaction,
     * ensuring transactional consistency with business operations.
     *
     * @param payload    The message payload to send
     * @param connection Existing Vert.x SqlConnection that has an active
     *                   transaction
     * @return CompletableFuture that completes when the message is stored in the
     *         outbox
     */
    public CompletableFuture<Void> sendInTransaction(T payload, io.vertx.sqlclient.SqlConnection connection) {
        return sendInTransaction(payload, null, null, null, connection);
    }

    /**
     * Transaction-aware reactive send method with headers.
     *
     * @param payload    The message payload to send
     * @param headers    Optional message headers
     * @param connection Existing Vert.x SqlConnection that has an active
     *                   transaction
     * @return CompletableFuture that completes when the message is stored in the
     *         outbox
     */
    public CompletableFuture<Void> sendInTransaction(T payload, Map<String, String> headers,
            io.vertx.sqlclient.SqlConnection connection) {
        return sendInTransaction(payload, headers, null, null, connection);
    }

    /**
     * Transaction-aware reactive send method with headers and correlation ID.
     *
     * @param payload       The message payload to send
     * @param headers       Optional message headers
     * @param correlationId Optional correlation ID for message tracking
     * @param connection    Existing Vert.x SqlConnection that has an active
     *                      transaction
     * @return CompletableFuture that completes when the message is stored in the
     *         outbox
     */
    public CompletableFuture<Void> sendInTransaction(T payload, Map<String, String> headers, String correlationId,
            io.vertx.sqlclient.SqlConnection connection) {
        return sendInTransaction(payload, headers, correlationId, null, connection);
    }

    /**
     * Full transaction-aware reactive send method with all parameters.
     * This is the core transactional method that ensures outbox operations
     * participate in the same transaction as business operations.
     *
     * @param payload       The message payload to send
     * @param headers       Optional message headers
     * @param correlationId Optional correlation ID for message tracking
     * @param messageGroup  Optional message group for ordering
     * @param connection    Existing Vert.x SqlConnection that has an active
     *                      transaction
     * @return CompletableFuture that completes when the message is stored in the
     *         outbox
     */
    public CompletableFuture<Void> sendInTransaction(T payload, Map<String, String> headers,
            String correlationId, String messageGroup,
            io.vertx.sqlclient.SqlConnection connection) {
        return sendInTransactionReactive(payload, headers, correlationId, messageGroup, connection)
                .toCompletionStage().toCompletableFuture();
    }

    /**
     * Internal reactive transactional send method using existing connection and
     * pure Vert.x Future patterns.
     */
    private Future<Void> sendInTransactionReactive(T payload, Map<String, String> headers,
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
            String messageId = UUID.randomUUID().toString();
            JsonObject payloadJson = toJsonObject(payload);
            JsonObject headersJson = headersToJsonObject(headers);
            String finalCorrelationId = correlationId != null ? correlationId : messageId;

            // Extract idempotency key from headers (Phase 2: Message Deduplication)
            // Normalize empty strings to null to allow duplicates
            String rawIdempotencyKey = (headers != null && headers.containsKey("idempotencyKey"))
                    ? headers.get("idempotencyKey")
                    : null;
            final String idempotencyKey = (rawIdempotencyKey != null && !rawIdempotencyKey.trim().isEmpty())
                    ? rawIdempotencyKey
                    : null;
            if (idempotencyKey != null) {
                logger.debug("Using idempotency key for outbox in-transaction: {}", idempotencyKey);
            }

            String sql = """
                    INSERT INTO outbox (topic, payload, headers, correlation_id, message_group, created_at, status, idempotency_key)
                    VALUES ($1, $2::jsonb, $3::jsonb, $4, $5, $6, 'PENDING', $7)
                    """;

            Tuple params = Tuple.of(
                    topic,
                    payloadJson,
                    headersJson,
                    finalCorrelationId,
                    messageGroup,
                    OffsetDateTime.now(),
                    idempotencyKey);

            // Use provided connection (which should have an active transaction)
            Future<Void> result = connection.preparedQuery(sql)
                    .execute(params)
                    .mapEmpty()
                    .recover(error -> {
                        // Check if this is a duplicate idempotency key error
                        String errorMsg = error.getMessage();
                        if (errorMsg != null && errorMsg.contains("idx_outbox_idempotency_key")) {
                            logger.info("Duplicate idempotency key detected for outbox in-transaction topic {}: {} - message already exists",
                                    topic, idempotencyKey);
                            return Future.succeededFuture();
                        }
                        return Future.failedFuture(error);
                    }).mapEmpty();

            return result
                    .onSuccess(v -> {
                        if (idempotencyKey != null) {
                            logger.debug("Message sent to outbox transactionally for topic {}: {} (idempotencyKey: {}, correlationId: {})",
                                    topic, messageId, idempotencyKey, finalCorrelationId);
                        } else {
                            logger.debug("Message sent to outbox transactionally for topic {}: {} (correlationId: {})",
                                    topic, messageId, finalCorrelationId);
                        }
                        metrics.recordMessageSent(topic);
                    })
                    .onFailure(error -> {
                        if (idempotencyKey != null) {
                            logger.error("Failed to send message transactionally to topic {}: {} (idempotencyKey: {})",
                                    topic, error.getMessage(), idempotencyKey);
                        } else {
                            logger.error("Failed to send message transactionally to topic {}: {}", topic,
                                    error.getMessage());
                        }
                    });

        } catch (Exception e) {
            logger.error("Error preparing transactional message for topic {}: {}", topic, e.getMessage());
            return Future.failedFuture(e);
        }
    }

    /**
     * Get or create the reactive Vert.x pool for database operations.
     * Following the pattern from PgNativeQueueProducer but using existing
     * PgClientFactory infrastructure.
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
                if (clientFactory != null) {
                    // Use PgConnectionManager to obtain/manage the reactive pool (no ad-hoc Vertx
                    // instances)
                    // clientId can be null - PgClientFactory resolves null to the default pool
                    var connectionConfig = clientFactory.getConnectionConfig(clientId);
                    var poolConfig = clientFactory.getPoolConfig(clientId);

                    if (connectionConfig == null) {
                        String poolName = clientId != null ? clientId : "default";
                        throw new RuntimeException(
                                "Connection configuration '" + poolName + "' not found for reactive pool");
                    }
                    if (poolConfig == null) {
                        poolConfig = new dev.mars.peegeeq.db.config.PgPoolConfig.Builder().build();
                    }

                    // Use clientId for pool creation - null is resolved to default by
                    // PgConnectionManager
                    String resolvedClientId = clientId != null ? clientId
                            : dev.mars.peegeeq.db.PeeGeeQDefaults.DEFAULT_POOL_ID;
                    reactivePool = clientFactory.getConnectionManager()
                            .getOrCreateReactivePool(resolvedClientId, connectionConfig, poolConfig);

                    logger.info("Obtained reactive pool from PgConnectionManager for outbox topic: {} (clientId: {})",
                            topic, clientId != null ? clientId : "default");

                } else if (databaseService != null) {
                    // Obtain reactive pool via DatabaseService ConnectionProvider (blocking only
                    // during initial creation, not on Vert.x event loop)
                    // clientId can be null - ConnectionProvider resolves null to the default pool
                    try {
                        var provider = databaseService.getConnectionProvider();
                        reactivePool = provider.getReactivePool(clientId)
                                .toCompletionStage()
                                .toCompletableFuture()
                                .get(5, java.util.concurrent.TimeUnit.SECONDS);
                        logger.info("Obtained reactive pool from DatabaseService for outbox topic: {} (clientId: {})",
                                topic, clientId != null ? clientId : "default");
                    } catch (Exception e) {
                        logger.error("Failed to obtain reactive pool from DatabaseService for topic {}: {}", topic,
                                e.getMessage());
                        throw new RuntimeException("Failed to obtain reactive pool from DatabaseService", e);
                    }
                } else {
                    throw new RuntimeException(
                            "No client factory or database service available for reactive pool creation");
                }

            } catch (Exception e) {
                logger.error("Failed to create reactive pool for topic {}: {}", topic, e.getMessage());
                throw new RuntimeException("Failed to create reactive pool", e);
            }
        }

        return reactivePool;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;

            // Close reactive pool if it exists
            if (reactivePool != null) {
                reactivePool.close();
                reactivePool = null;
                logger.info("Closed reactive pool for outbox topic: {}", topic);
            }

            logger.info("Closed outbox producer for topic: {}", topic);
        }
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
