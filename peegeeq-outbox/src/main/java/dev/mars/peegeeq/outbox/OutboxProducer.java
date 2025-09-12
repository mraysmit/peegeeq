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
import dev.mars.peegeeq.db.client.PgClientFactory;
import dev.mars.peegeeq.db.metrics.PeeGeeQMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Vertx;
import io.vertx.core.Context;
import io.vertx.core.Future;
import java.util.function.Supplier;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
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
    private final PeeGeeQMetrics metrics;
    private volatile boolean closed = false;

    // Reactive Vert.x components - following PgNativeQueueProducer pattern
    private volatile Pool reactivePool;

    // Shared Vertx instance for proper context management
    private static volatile Vertx sharedVertx;

    public OutboxProducer(PgClientFactory clientFactory, ObjectMapper objectMapper,
                         String topic, Class<T> payloadType, PeeGeeQMetrics metrics) {
        this.clientFactory = clientFactory;
        this.databaseService = null;
        this.objectMapper = objectMapper;
        this.topic = topic;
        this.payloadType = payloadType;
        this.metrics = metrics;
        logger.info("Created outbox producer for topic: {}", topic);
    }

    public OutboxProducer(DatabaseService databaseService, ObjectMapper objectMapper,
                         String topic, Class<T> payloadType, PeeGeeQMetrics metrics) {
        this.clientFactory = null;
        this.databaseService = databaseService;
        this.objectMapper = objectMapper;
        this.topic = topic;
        this.payloadType = payloadType;
        this.metrics = metrics;
        logger.info("Created outbox producer for topic: {} (using DatabaseService)", topic);
    }

    /**
     * Sends a message to the outbox table for eventual delivery.
     * Now uses Vert.x 5.x reactive patterns by default for better performance and non-blocking operations.
     *
     * @param payload The message payload to send
     * @return CompletableFuture that completes when the message is stored in the outbox
     */
    @Override
    public CompletableFuture<Void> send(T payload) {
        return sendInternal(payload);
    }

    /**
     * Sends a message with headers to the outbox table.
     * Now uses Vert.x 5.x reactive patterns by default for better performance and non-blocking operations.
     *
     * @param payload The message payload to send
     * @param headers Optional message headers
     * @return CompletableFuture that completes when the message is stored in the outbox
     */
    @Override
    public CompletableFuture<Void> send(T payload, Map<String, String> headers) {
        return sendInternal(payload, headers);
    }

    /**
     * Sends a message with headers and correlation ID to the outbox table.
     * Now uses Vert.x 5.x reactive patterns by default for better performance and non-blocking operations.
     *
     * @param payload The message payload to send
     * @param headers Optional message headers
     * @param correlationId Optional correlation ID for message tracking
     * @return CompletableFuture that completes when the message is stored in the outbox
     */
    @Override
    public CompletableFuture<Void> send(T payload, Map<String, String> headers, String correlationId) {
        return sendInternal(payload, headers, correlationId);
    }

    /**
     * Sends a message with all parameters to the outbox table.
     * Now uses Vert.x 5.x reactive patterns by default for better performance and non-blocking operations.
     *
     * @param payload The message payload to send
     * @param headers Optional message headers
     * @param correlationId Optional correlation ID for message tracking
     * @param messageGroup Optional message group for ordering
     * @return CompletableFuture that completes when the message is stored in the outbox
     */
    @Override
    public CompletableFuture<Void> send(T payload, Map<String, String> headers, String correlationId, String messageGroup) {
        return sendInternal(payload, headers, correlationId, messageGroup);
    }



    /**
     * Internal send method using Vert.x SqlClient - following PgNativeQueueProducer pattern.
     * This method provides non-blocking database operations for better performance.
     *
     * @param payload The message payload to send
     * @return CompletableFuture that completes when the message is stored in the outbox
     */
    public CompletableFuture<Void> sendInternal(T payload) {
        return sendInternal(payload, null, null, null);
    }

    /**
     * Internal send method with headers.
     *
     * @param payload The message payload to send
     * @param headers Optional message headers
     * @return CompletableFuture that completes when the message is stored in the outbox
     */
    public CompletableFuture<Void> sendInternal(T payload, Map<String, String> headers) {
        return sendInternal(payload, headers, null, null);
    }

    /**
     * Internal send method with headers and correlation ID.
     *
     * @param payload The message payload to send
     * @param headers Optional message headers
     * @param correlationId Optional correlation ID for message tracking
     * @return CompletableFuture that completes when the message is stored in the outbox
     */
    public CompletableFuture<Void> sendInternal(T payload, Map<String, String> headers, String correlationId) {
        return sendInternal(payload, headers, correlationId, null);
    }

    /**
     * Full internal send method with all parameters.
     *
     * @param payload The message payload to send
     * @param headers Optional message headers
     * @param correlationId Optional correlation ID for message tracking
     * @param messageGroup Optional message group for ordering
     * @return CompletableFuture that completes when the message is stored in the outbox
     */
    public CompletableFuture<Void> sendInternal(T payload, Map<String, String> headers,
                                               String correlationId, String messageGroup) {
        return sendInternalReactive(payload, headers, correlationId, messageGroup)
            .toCompletionStage().toCompletableFuture();
    }

    /**
     * Internal reactive send method using pure Vert.x Future patterns.
     * This is the core implementation that uses Vert.x 5.x reactive patterns throughout.
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
            String payloadJson = objectMapper.writeValueAsString(payload);
            String headersJson = headers != null ? objectMapper.writeValueAsString(headers) : "{}";
            String finalCorrelationId = correlationId != null ? correlationId : messageId;

            // Get or create reactive pool - following PgNativeQueueProducer pattern
            Pool pool = getOrCreateReactivePool();

            String sql = """
                INSERT INTO outbox (topic, payload, headers, correlation_id, message_group, created_at, status)
                VALUES ($1, $2::jsonb, $3::jsonb, $4, $5, $6, 'PENDING')
                """;

            Tuple params = Tuple.of(
                topic,
                payloadJson,
                headersJson,
                finalCorrelationId,
                messageGroup,
                OffsetDateTime.now()
            );

            Future<Void> result = pool.preparedQuery(sql)
                .execute(params)
                .mapEmpty();

            return result
                .onSuccess(v -> {
                    logger.debug("Message sent to outbox reactively for topic {}: {}", topic, messageId);

                    // Record metrics
                    if (metrics != null) {
                        metrics.recordMessageSent(topic);
                    }
                })
                .onFailure(error -> {
                    logger.error("Failed to send message reactively to topic {}: {}", topic, error.getMessage());
                });

        } catch (Exception e) {
            logger.error("Error preparing reactive message for topic {}: {}", topic, e.getMessage());
            return Future.failedFuture(e);
        }
    }

    /**
     * Production-grade transactional send method using official Vert.x withTransaction API.
     * Uses default transaction propagation behavior.
     *
     * @param payload The message payload to send
     * @return CompletableFuture that completes when the message is stored in the outbox
     */
    public CompletableFuture<Void> sendWithTransaction(T payload) {
        return sendWithTransactionInternal(payload, null, null, null, null);
    }

    /**
     * Production-grade transactional send method with headers using official Vert.x API.
     * Uses default transaction propagation behavior.
     *
     * @param payload The message payload to send
     * @param headers Optional message headers
     * @return CompletableFuture that completes when the message is stored in the outbox
     */
    public CompletableFuture<Void> sendWithTransaction(T payload, Map<String, String> headers) {
        return sendWithTransactionInternal(payload, headers, null, null, null);
    }

    /**
     * Production-grade transactional send method with headers, correlation ID using official Vert.x API.
     * Uses default transaction propagation behavior.
     *
     * @param payload The message payload to send
     * @param headers Optional message headers
     * @param correlationId Optional correlation ID for message tracking
     * @return CompletableFuture that completes when the message is stored in the outbox
     */
    public CompletableFuture<Void> sendWithTransaction(T payload, Map<String, String> headers, String correlationId) {
        return sendWithTransactionInternal(payload, headers, correlationId, null, null);
    }



    /**
     * Production-grade transactional send method with TransactionPropagation support.
     * This method uses Vert.x 5 TransactionPropagation for advanced transaction management.
     *
     * @param payload The message payload to send
     * @param propagation Transaction propagation behavior (e.g., CONTEXT for sharing existing transactions)
     * @return CompletableFuture that completes when the message is stored in the outbox
     */
    public CompletableFuture<Void> sendWithTransaction(T payload, TransactionPropagation propagation) {
        return sendWithTransactionInternal(payload, null, null, null, propagation);
    }

    /**
     * Production-grade transactional send method with headers and TransactionPropagation support.
     *
     * @param payload The message payload to send
     * @param headers Optional message headers
     * @param propagation Transaction propagation behavior
     * @return CompletableFuture that completes when the message is stored in the outbox
     */
    public CompletableFuture<Void> sendWithTransaction(T payload, Map<String, String> headers,
                                                      TransactionPropagation propagation) {
        return sendWithTransactionInternal(payload, headers, null, null, propagation);
    }

    /**
     * Production-grade transactional send method with headers, correlation ID and TransactionPropagation support.
     *
     * @param payload The message payload to send
     * @param headers Optional message headers
     * @param correlationId Optional correlation ID for message tracking
     * @param propagation Transaction propagation behavior
     * @return CompletableFuture that completes when the message is stored in the outbox
     */
    public CompletableFuture<Void> sendWithTransaction(T payload, Map<String, String> headers, String correlationId,
                                                      TransactionPropagation propagation) {
        return sendWithTransactionInternal(payload, headers, correlationId, null, propagation);
    }

    /**
     * Full production-grade transactional send method with all parameters and TransactionPropagation support.
     *
     * @param payload The message payload to send
     * @param headers Optional message headers
     * @param correlationId Optional correlation ID for message tracking
     * @param messageGroup Optional message group for ordering
     * @param propagation Transaction propagation behavior
     * @return CompletableFuture that completes when the message is stored in the outbox
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
     * @param payload The message payload to send
     * @param headers Optional message headers
     * @param correlationId Optional correlation ID for message tracking
     * @param messageGroup Optional message group for ordering
     * @param propagation Optional transaction propagation behavior (null for default)
     * @return CompletableFuture that completes when the message is stored in the outbox
     */
    private CompletableFuture<Void> sendWithTransactionInternal(T payload, Map<String, String> headers,
                                                               String correlationId, String messageGroup,
                                                               TransactionPropagation propagation) {
        return sendWithTransactionInternalReactive(payload, headers, correlationId, messageGroup, propagation)
            .toCompletionStage().toCompletableFuture();
    }

    /**
     * Internal reactive transactional send method using pure Vert.x Future patterns.
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
            String payloadJson = objectMapper.writeValueAsString(payload);
            String headersJson = headers != null ? objectMapper.writeValueAsString(headers) : "{}";
            String finalCorrelationId = correlationId != null ? correlationId : messageId;

            // Use official Vert.x withTransaction API for proper transaction handling
            Pool pool = getOrCreateReactivePool();
            Vertx vertx = getOrCreateSharedVertx();

            // Execute transaction on Vert.x context for proper TransactionPropagation support
            Future<Void> transactionFuture = (propagation != null)
                ? executeOnVertxContext(vertx, () -> pool.withTransaction(propagation, client -> {
                    String sql = """
                        INSERT INTO outbox (topic, payload, headers, correlation_id, message_group, created_at, status)
                        VALUES ($1, $2::jsonb, $3::jsonb, $4, $5, $6, 'PENDING')
                        """;

                    Tuple params = Tuple.of(
                        topic,
                        payloadJson,
                        headersJson,
                        finalCorrelationId,
                        messageGroup,
                        OffsetDateTime.now()
                    );

                    // Return Future<Void> to indicate transaction success/failure
                    return client.preparedQuery(sql).execute(params).mapEmpty();
                }))
                : executeOnVertxContext(vertx, () -> pool.withTransaction(client -> {
                    String sql = """
                        INSERT INTO outbox (topic, payload, headers, correlation_id, message_group, created_at, status)
                        VALUES ($1, $2::jsonb, $3::jsonb, $4, $5, $6, 'PENDING')
                        """;

                    Tuple params = Tuple.of(
                        topic,
                        payloadJson,
                        headersJson,
                        finalCorrelationId,
                        messageGroup,
                        OffsetDateTime.now()
                    );

                    // Return Future<Void> to indicate transaction success/failure
                    return client.preparedQuery(sql).execute(params).mapEmpty();
                }));

            return transactionFuture
                .onSuccess(v -> {
                    logger.debug("Message sent to outbox with transaction for topic {}: {}", topic, messageId);

                    // Record metrics
                    if (metrics != null) {
                        metrics.recordMessageSent(topic);
                    }
                })
                .onFailure(error -> {
                    logger.error("Failed to send message with transaction to topic {}: {}", topic, error.getMessage());
                });

        } catch (Exception e) {
            logger.error("Error preparing transactional message for topic {}: {}", topic, e.getMessage());
            return Future.failedFuture(e);
        }
    }

    /**
     * Transaction-aware reactive send method using existing Vert.x connection.
     * This method allows the outbox operation to participate in an existing transaction,
     * ensuring transactional consistency with business operations.
     *
     * @param payload The message payload to send
     * @param connection Existing Vert.x SqlConnection that has an active transaction
     * @return CompletableFuture that completes when the message is stored in the outbox
     */
    public CompletableFuture<Void> sendInTransaction(T payload, io.vertx.sqlclient.SqlConnection connection) {
        return sendInTransaction(payload, null, null, null, connection);
    }

    /**
     * Transaction-aware reactive send method with headers.
     *
     * @param payload The message payload to send
     * @param headers Optional message headers
     * @param connection Existing Vert.x SqlConnection that has an active transaction
     * @return CompletableFuture that completes when the message is stored in the outbox
     */
    public CompletableFuture<Void> sendInTransaction(T payload, Map<String, String> headers,
                                                    io.vertx.sqlclient.SqlConnection connection) {
        return sendInTransaction(payload, headers, null, null, connection);
    }

    /**
     * Transaction-aware reactive send method with headers and correlation ID.
     *
     * @param payload The message payload to send
     * @param headers Optional message headers
     * @param correlationId Optional correlation ID for message tracking
     * @param connection Existing Vert.x SqlConnection that has an active transaction
     * @return CompletableFuture that completes when the message is stored in the outbox
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
     * @param payload The message payload to send
     * @param headers Optional message headers
     * @param correlationId Optional correlation ID for message tracking
     * @param messageGroup Optional message group for ordering
     * @param connection Existing Vert.x SqlConnection that has an active transaction
     * @return CompletableFuture that completes when the message is stored in the outbox
     */
    public CompletableFuture<Void> sendInTransaction(T payload, Map<String, String> headers,
                                                    String correlationId, String messageGroup,
                                                    io.vertx.sqlclient.SqlConnection connection) {
        return sendInTransactionReactive(payload, headers, correlationId, messageGroup, connection)
            .toCompletionStage().toCompletableFuture();
    }

    /**
     * Internal reactive transactional send method using existing connection and pure Vert.x Future patterns.
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
            String payloadJson = objectMapper.writeValueAsString(payload);
            String headersJson = headers != null ? objectMapper.writeValueAsString(headers) : "{}";
            String finalCorrelationId = correlationId != null ? correlationId : messageId;

            String sql = """
                INSERT INTO outbox (topic, payload, headers, correlation_id, message_group, created_at, status)
                VALUES ($1, $2::jsonb, $3::jsonb, $4, $5, $6, 'PENDING')
                """;

            Tuple params = Tuple.of(
                topic,
                payloadJson,
                headersJson,
                finalCorrelationId,
                messageGroup,
                OffsetDateTime.now()
            );

            // Use provided connection (which should have an active transaction)
            Future<Void> result = connection.preparedQuery(sql)
                .execute(params)
                .mapEmpty();

            return result
                .onSuccess(v -> {
                    logger.debug("Message sent to outbox transactionally for topic {}: {}", topic, messageId);

                    // Record metrics
                    if (metrics != null) {
                        metrics.recordMessageSent(topic);
                    }
                })
                .onFailure(error -> {
                    logger.error("Failed to send message transactionally to topic {}: {}", topic, error.getMessage());
                });

        } catch (Exception e) {
            logger.error("Error preparing transactional message for topic {}: {}", topic, e.getMessage());
            return Future.failedFuture(e);
        }
    }



    /**
     * Get or create the reactive Vert.x pool for database operations.
     * Following the pattern from PgNativeQueueProducer but using existing PgClientFactory infrastructure.
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
                    // Use existing PgClientFactory configuration
                    var connectionConfig = clientFactory.getConnectionConfig("peegeeq-main");
                    var poolConfig = clientFactory.getPoolConfig("peegeeq-main");

                    if (connectionConfig == null) {
                        throw new RuntimeException("Connection configuration 'peegeeq-main' not found for reactive pool");
                    }

                    // Create Vert.x pool using existing configuration
                    PgConnectOptions connectOptions = new PgConnectOptions()
                        .setHost(connectionConfig.getHost())
                        .setPort(connectionConfig.getPort())
                        .setDatabase(connectionConfig.getDatabase())
                        .setUser(connectionConfig.getUsername())
                        .setPassword(connectionConfig.getPassword());

                    if (connectionConfig.isSslEnabled()) {
                        connectOptions.setSslMode(io.vertx.pgclient.SslMode.REQUIRE);
                    }

                    PoolOptions poolOptions = new PoolOptions();
                    if (poolConfig != null) {
                        poolOptions.setMaxSize(poolConfig.getMaximumPoolSize());
                    } else {
                        poolOptions.setMaxSize(10); // Default pool size
                    }

                    reactivePool = PgBuilder.pool()
                        .with(poolOptions)
                        .connectingTo(connectOptions)
                        .using(getOrCreateSharedVertx())
                        .build();

                    logger.info("Created reactive pool for outbox topic: {}", topic);

                } else if (databaseService != null) {
                    // TODO: Add support for DatabaseService-based reactive pool creation
                    throw new UnsupportedOperationException("Reactive pool creation from DatabaseService not yet implemented");
                } else {
                    throw new RuntimeException("No client factory or database service available for reactive pool creation");
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
     * Executes a Future-returning operation on the Vert.x context.
     * This ensures that TransactionPropagation.CONTEXT works correctly by providing
     * the proper execution context for Vert.x operations.
     *
     * @param vertx The Vertx instance
     * @param operation The operation to execute that returns a Future
     * @return Future that completes when the operation completes
     */
    private static <T> Future<T> executeOnVertxContext(Vertx vertx, Supplier<Future<T>> operation) {
        Context context = vertx.getOrCreateContext();
        if (context == Vertx.currentContext()) {
            // Already on Vert.x context, execute directly
            return operation.get();
        } else {
            // Execute on Vert.x context using runOnContext
            io.vertx.core.Promise<T> promise = io.vertx.core.Promise.promise();
            context.runOnContext(v -> {
                operation.get()
                    .onSuccess(promise::complete)
                    .onFailure(promise::fail);
            });
            return promise.future();
        }
    }

    /**
     * Gets or creates a shared Vertx instance for proper context management.
     * This ensures that TransactionPropagation.CONTEXT works correctly by providing
     * a consistent Vertx context across all OutboxProducer instances.
     *
     * @return The shared Vertx instance
     */
    private static Vertx getOrCreateSharedVertx() {
        if (sharedVertx == null) {
            synchronized (OutboxProducer.class) {
                if (sharedVertx == null) {
                    sharedVertx = Vertx.vertx();
                    logger.info("Created shared Vertx instance for OutboxProducer context management");
                }
            }
        }
        return sharedVertx;
    }

    /**
     * Closes the shared Vertx instance. This should only be called during application shutdown.
     * Note: This is a static method that affects all OutboxProducer instances.
     */
    public static void closeSharedVertx() {
        if (sharedVertx != null) {
            synchronized (OutboxProducer.class) {
                if (sharedVertx != null) {
                    sharedVertx.close();
                    sharedVertx = null;
                    logger.info("Closed shared Vertx instance for OutboxProducer");
                }
            }
        }
    }
}
