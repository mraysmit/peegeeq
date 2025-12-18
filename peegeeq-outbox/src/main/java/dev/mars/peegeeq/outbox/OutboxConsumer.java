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



import dev.mars.peegeeq.api.messaging.MessageHandler;
import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.ServerSideFilter;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.database.MetricsProvider;
import dev.mars.peegeeq.api.database.NoOpMetricsProvider;
import dev.mars.peegeeq.db.client.PgClientFactory;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.json.JsonObject;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Removed JDBC imports - no longer needed after migration to Vert.x 5.x reactive patterns
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Outbox pattern message consumer implementation.
 *
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
public class OutboxConsumer<T> implements dev.mars.peegeeq.api.messaging.MessageConsumer<T> {
    private static final Logger logger = LoggerFactory.getLogger(OutboxConsumer.class);

    private final PgClientFactory clientFactory;
    private final DatabaseService databaseService;
    private final ObjectMapper objectMapper;
    private final String topic;
    private final Class<T> payloadType;
    private final MetricsProvider metrics;
    private final PeeGeeQConfiguration configuration;
    private final OutboxConsumerConfig consumerConfig;
    private final AtomicBoolean subscribed = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    // Client ID for pool lookup - null means use default pool (resolved by PgClientFactory)
    private final String clientId;

    // Consumer group name for tracking which messages this consumer has processed
    private String consumerGroupName;

    private MessageHandler<T> messageHandler;
    private ScheduledExecutorService scheduler;
    private ExecutorService messageProcessingExecutor;

    // Vert.x 5.x reactive pool for non-blocking database operations
    private volatile Pool reactivePool;


    public OutboxConsumer(PgClientFactory clientFactory, ObjectMapper objectMapper,
                         String topic, Class<T> payloadType, MetricsProvider metrics) {
        this(clientFactory, objectMapper, topic, payloadType, metrics, null, null, null);
    }

    public OutboxConsumer(PgClientFactory clientFactory, ObjectMapper objectMapper,
                         String topic, Class<T> payloadType, MetricsProvider metrics,
                         PeeGeeQConfiguration configuration) {
        this(clientFactory, objectMapper, topic, payloadType, metrics, configuration, null, null);
    }

    public OutboxConsumer(PgClientFactory clientFactory, ObjectMapper objectMapper,
                         String topic, Class<T> payloadType, MetricsProvider metrics,
                         PeeGeeQConfiguration configuration, String clientId) {
        this(clientFactory, objectMapper, topic, payloadType, metrics, configuration, clientId, null);
    }

    public OutboxConsumer(PgClientFactory clientFactory, ObjectMapper objectMapper,
                         String topic, Class<T> payloadType, MetricsProvider metrics,
                         PeeGeeQConfiguration configuration, String clientId, OutboxConsumerConfig consumerConfig) {
        this.clientFactory = clientFactory;
        this.databaseService = null;
        this.objectMapper = objectMapper;
        this.topic = topic;
        this.payloadType = payloadType;
        this.metrics = metrics != null ? metrics : NoOpMetricsProvider.INSTANCE;
        this.configuration = configuration;
        this.consumerConfig = consumerConfig;
        this.clientId = clientId; // null means use default pool
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "outbox-consumer-" + topic);
            t.setDaemon(true);
            return t;
        });

        // Initialize message processing thread pool - consumerConfig takes precedence
        int consumerThreads = getEffectiveConsumerThreads();
        this.messageProcessingExecutor = Executors.newFixedThreadPool(consumerThreads, r -> {
            Thread t = new Thread(r, "outbox-processor-" + topic + "-" + System.currentTimeMillis());
            t.setDaemon(true);
            return t;
        });

        logger.info("Created outbox consumer for topic: {} with configuration: {}, consumerConfig: {} (threads: {}, clientId: {})",
            topic, configuration != null ? "enabled" : "disabled",
            consumerConfig != null ? consumerConfig : "default",
            consumerThreads, clientId != null ? clientId : "default");
    }

    public OutboxConsumer(DatabaseService databaseService, ObjectMapper objectMapper,
                         String topic, Class<T> payloadType, MetricsProvider metrics) {
        this(databaseService, objectMapper, topic, payloadType, metrics, null, null, null);
    }

    public OutboxConsumer(DatabaseService databaseService, ObjectMapper objectMapper,
                         String topic, Class<T> payloadType, MetricsProvider metrics,
                         PeeGeeQConfiguration configuration) {
        this(databaseService, objectMapper, topic, payloadType, metrics, configuration, null, null);
    }

    public OutboxConsumer(DatabaseService databaseService, ObjectMapper objectMapper,
                         String topic, Class<T> payloadType, MetricsProvider metrics,
                         PeeGeeQConfiguration configuration, String clientId) {
        this(databaseService, objectMapper, topic, payloadType, metrics, configuration, clientId, null);
    }

    public OutboxConsumer(DatabaseService databaseService, ObjectMapper objectMapper,
                         String topic, Class<T> payloadType, MetricsProvider metrics,
                         PeeGeeQConfiguration configuration, String clientId, OutboxConsumerConfig consumerConfig) {
        this.clientFactory = null;
        this.databaseService = databaseService;
        this.objectMapper = objectMapper;
        this.topic = topic;
        this.payloadType = payloadType;
        this.metrics = metrics != null ? metrics : NoOpMetricsProvider.INSTANCE;
        this.configuration = configuration;
        this.consumerConfig = consumerConfig;
        this.clientId = clientId; // null means use default pool
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "outbox-consumer-" + topic);
            t.setDaemon(true);
            return t;
        });

        // Initialize message processing thread pool - consumerConfig takes precedence
        int consumerThreads = getEffectiveConsumerThreads();
        this.messageProcessingExecutor = Executors.newFixedThreadPool(consumerThreads, r -> {
            Thread t = new Thread(r, "outbox-processor-" + topic + "-" + System.currentTimeMillis());
            t.setDaemon(true);
            return t;
        });

        logger.info("Created outbox consumer for topic: {} (using DatabaseService) with configuration: {}, consumerConfig: {} (threads: {}, clientId: {})",
            topic, configuration != null ? "enabled" : "disabled",
            consumerConfig != null ? consumerConfig : "default",
            consumerThreads, clientId != null ? clientId : "default");
    }

    /**
     * Sets the consumer group name for this consumer.
     * This is used to track which messages have been processed by which consumer groups.
     *
     * @param consumerGroupName The name of the consumer group
     */
    public void setConsumerGroupName(String consumerGroupName) {
        this.consumerGroupName = consumerGroupName;
        logger.info("Set consumer group name to '{}' for topic '{}'", consumerGroupName, topic);
    }

    @Override
    public void subscribe(MessageHandler<T> handler) {
        if (closed.get()) {
            throw new IllegalStateException("Consumer is closed");
        }

        logger.info("Subscribing to topic: {} with handler: {}", topic, handler.getClass().getSimpleName());

        this.messageHandler = handler;
        boolean wasSubscribed = subscribed.compareAndSet(false, true);

        if (wasSubscribed) {
            logger.info("Starting polling for topic: {}", topic);
            try {
                startPolling();
            } catch (Exception e) {
                throw e;
            }
            logger.info("Subscribed to topic: {}", topic);
        } else {
            logger.warn("Already subscribed to topic: {}", topic);
        }
    }

    @Override
    public void unsubscribe() {
        if (subscribed.compareAndSet(true, false)) {
            logger.info("Unsubscribed from topic: {}", topic);
        }
    }

    private void startPolling() {
        // Get polling interval - consumerConfig takes precedence over configuration
        Duration pollingInterval = getEffectivePollingInterval();

        long pollingIntervalMs = pollingInterval.toMillis();

        logger.info("Starting polling for topic {} with interval: {} ms", topic, pollingIntervalMs);
        logger.debug("Scheduler state: {}", scheduler != null ? "present" : "null");
        logger.debug("Scheduler shutdown: {}", scheduler != null ? scheduler.isShutdown() : "N/A");
        logger.debug("Scheduler terminated: {}", scheduler != null ? scheduler.isTerminated() : "N/A");

        // Poll for messages at configured interval
        try {
            logger.debug("About to schedule polling task for topic {}", topic);
            scheduler.scheduleWithFixedDelay(this::processAvailableMessages, 0, pollingIntervalMs, TimeUnit.MILLISECONDS);
            logger.debug("Polling task scheduled successfully for topic {}", topic);
        } catch (Exception e) {
            logger.error("Error scheduling polling task for topic {}: {}", topic, e.getMessage(), e);
            throw e;
        }

        logger.info("Scheduled polling task for topic {} with interval: {}", topic, pollingInterval);

    }

    private void processAvailableMessages() {
        if (!subscribed.get() || closed.get()) {
            logger.debug("Skipping message processing - subscribed: {}, closed: {} for topic {}",
                subscribed.get(), closed.get(), topic);
            return;
        }
        logger.debug("Processing available messages for topic {}", topic);

        // Use reactive processing for Vert.x 5.x compliance
        try {
            processAvailableMessagesReactive()
                .onSuccess(result -> logger.debug("Successfully processed messages for topic {}", topic))
                .onFailure(error -> {
                    logger.error("Reactive message processing failed for topic {}: {}", topic, error.getMessage(), error);
                });
        } catch (Exception e) {
            logger.error("Failed to start reactive message processing for topic {}: {}", topic, e.getMessage(), e);
        }
    }

    /**
     * Reactive message processing using Vert.x 5.x patterns.
     * This is the preferred method for non-blocking database operations.
     */
    private Future<Void> processAvailableMessagesReactive() {
        logger.debug("OUTBOX-DEBUG: processAvailableMessagesReactive() called for topic: {}", topic);
        // CRITICAL FIX: Check if consumer is closed to prevent infinite retry loops during shutdown
        if (closed.get()) {
            logger.debug("OUTBOX-DEBUG: Skipping message processing - consumer closed for topic {}", topic);
            return Future.succeededFuture();
        }
        logger.debug("OUTBOX-DEBUG: Consumer is active, proceeding with message processing for topic: {}", topic);

        try {
            int batchSize = getEffectiveBatchSize();

            // Build SQL dynamically based on whether server-side filter is present
            ServerSideFilter filter = consumerConfig != null ? consumerConfig.getServerSideFilter() : null;
            String sql;
            Tuple params;

            if (filter != null) {
                // Server-side filtering: add filter condition to WHERE clause
                String filterCondition = filter.toSqlCondition(4); // $4 onwards for filter params
                sql = """
                    UPDATE outbox
                    SET status = 'PROCESSING', processed_at = $1
                    WHERE id IN (
                        SELECT id FROM outbox
                        WHERE topic = $2 AND status = 'PENDING'
                          AND """ + filterCondition + """
                        ORDER BY created_at ASC
                        LIMIT $3
                        FOR UPDATE SKIP LOCKED
                    )
                    RETURNING id, payload, headers, correlation_id, message_group, created_at
                    """;

                // Build tuple with base params + filter params
                Object[] baseParams = new Object[]{OffsetDateTime.now(), topic, batchSize};
                java.util.List<Object> filterParams = filter.getParameters();
                Object[] allParams = new Object[baseParams.length + filterParams.size()];
                System.arraycopy(baseParams, 0, allParams, 0, baseParams.length);
                for (int i = 0; i < filterParams.size(); i++) {
                    allParams[baseParams.length + i] = filterParams.get(i);
                }
                params = Tuple.from(allParams);

                logger.debug("OUTBOX-DEBUG: Using server-side filter: {}, SQL filter: {}", filter, filterCondition);
            } else {
                // No filter: use original SQL
                sql = """
                    UPDATE outbox
                    SET status = 'PROCESSING', processed_at = $1
                    WHERE id IN (
                        SELECT id FROM outbox
                        WHERE topic = $2 AND status = 'PENDING'
                        ORDER BY created_at ASC
                        LIMIT $3
                        FOR UPDATE SKIP LOCKED
                    )
                    RETURNING id, payload, headers, correlation_id, message_group, created_at
                    """;
                params = Tuple.of(OffsetDateTime.now(), topic, batchSize);
            }

            return getReactivePoolFuture()
                .compose(pool -> pool.preparedQuery(sql).execute(params))
                .compose(rowSet -> {
                    // Double-check if consumer is still active after async operation
                    if (closed.get()) {
                        logger.debug("OUTBOX-DEBUG: Consumer closed during message processing, ignoring results for topic: {}", topic);
                        return Future.succeededFuture();
                    }

                    if (rowSet.size() == 0) {
                        logger.debug("No pending messages found for topic {}", topic);
                        return Future.succeededFuture();
                    }

                    logger.debug("Found {} messages to process for topic {}", rowSet.size(), topic);

                    // Process each message
                    Future<Void> processingChain = Future.succeededFuture();
                    for (Row row : rowSet) {
                        processingChain = processingChain.compose(v -> processRowReactive(row));
                    }

                    return processingChain;
                })
                .onFailure(error -> {
                    // CRITICAL FIX: Handle shutdown-related errors gracefully following established pattern
                    if (closed.get() && (error.getMessage().contains("Pool closed") ||
                                        error.getMessage().contains("event executor terminated") ||
                                        error.getMessage().contains("Connection closed"))) {
                        logger.debug("Expected error during shutdown for topic {}: {}", topic, error.getMessage());
                    } else {
                        logger.error("Error querying messages for topic {}: {}", topic, error.getMessage());
                    }
                });

        } catch (Exception e) {
            // Critical fix: Handle various error conditions gracefully following established pattern
            String errorMessage = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();

            if (closed.get()) {
                // During shutdown, many errors are expected
                if (errorMessage.contains("Pool closed") ||
                    errorMessage.contains("event executor terminated") ||
                    errorMessage.contains("Connection closed") ||
                    errorMessage.contains("RejectedExecutionException")) {
                    logger.debug("Expected error during shutdown for topic {}: {}", topic, errorMessage);
                } else {
                    logger.debug("Error during shutdown for topic {}: {}", topic, errorMessage);
                }
            } else {
                // During normal operation, log as error but don't let it terminate the executor
                if (errorMessage.contains("event executor terminated") ||
                    errorMessage.contains("RejectedExecutionException")) {
                    logger.warn("Event executor terminated for topic {} - this may indicate system shutdown: {}", topic, errorMessage);
                    // Mark as closed to prevent further processing attempts
                    closed.set(true);
                } else {
                    logger.error("Failed to process messages reactively for topic {}: {}", topic, errorMessage, e);
                }
            }
            return Future.failedFuture(e);
        }
    }

    /**
     * Processes a single row from the database reactively.
     */
    private Future<Void> processRowReactive(Row row) {
        try {
            String messageId = String.valueOf(row.getLong("id"));
            JsonObject payloadJson = row.getJsonObject("payload");
            JsonObject headersJson = row.getJsonObject("headers");
            String correlationId = row.getString("correlation_id");

            T payload = parsePayloadFromJsonObject(payloadJson);
            Map<String, String> headers = parseHeadersFromJsonObject(headersJson);

            // Add correlation ID to headers if present
            if (correlationId != null) {
                headers.put("correlationId", correlationId);
            }

            Message<T> message = new OutboxMessage<>(messageId, payload, row.getLocalDateTime("created_at").toInstant(java.time.ZoneOffset.UTC), headers);

            // Check if executor is shut down before submitting tasks
            if (messageProcessingExecutor.isShutdown()) {
                logger.debug("Message processing executor is shut down, skipping message {} for topic {}", messageId, topic);
                return Future.succeededFuture();
            }

            // Process message asynchronously using dedicated thread pool
            return Future.fromCompletionStage(
                CompletableFuture.runAsync(() -> {
                    try {
                        processMessageWithCompletion(message, messageId);
                    } catch (Exception e) {
                        logger.error("Failed to process message {} for topic {}: {}", messageId, topic, e.getMessage(), e);
                        // Mark message as failed
                        markMessageFailedReactive(messageId, e.getMessage());
                    }
                }, messageProcessingExecutor)
            );

        } catch (Exception e) {
            logger.error("Failed to process row for topic {}: {}", topic, e.getMessage(), e);
            return Future.failedFuture(e);
        }
    }

    /**
     * Marks a message as failed using reactive patterns.
     */
    private Future<Void> markMessageFailedReactive(String messageId, String errorMessage) {
        try {
            String sql = "UPDATE outbox SET status = 'FAILED', processed_at = $1 WHERE id = $2";
            Tuple params = Tuple.of(OffsetDateTime.now(), Long.parseLong(messageId));

            return getReactivePoolFuture()
                .compose(pool -> pool.preparedQuery(sql).execute(params))
                .mapEmpty();
        } catch (Exception e) {
            logger.error("Failed to mark message {} as failed: {}", messageId, e.getMessage(), e);
            return Future.failedFuture(e);
        }
    }





    /**
     * Processes a message and marks it as completed when done.
     */
    private void processMessageWithCompletion(Message<T> message, String messageId) {
        logger.debug("Processing message {} from topic {} in thread {}",
            messageId, topic, Thread.currentThread().getName());

        Instant processingStart = Instant.now();

        // Wrap the message handler call in try-catch to handle both:
        // 1. Direct exceptions thrown from the handler method
        // 2. Exceptions returned in failed CompletableFutures
        // 3. Null returns from the handler method
        CompletableFuture<Void> processingFuture;
        try {
            processingFuture = messageHandler.handle(message);

            // Handle null return from message handler
            if (processingFuture == null) {
                logger.warn("Message handler returned null CompletableFuture for message {}: treating as failure",
                    messageId);
                processingFuture = CompletableFuture.failedFuture(
                    new IllegalStateException("Message handler returned null CompletableFuture")
                );
            }
        } catch (Exception directException) {
            // Convert direct exceptions to failed CompletableFutures
            logger.debug("Message handler threw direct exception for message {}: {}",
                messageId, directException.getMessage());
            processingFuture = CompletableFuture.failedFuture(directException);
        }

        processingFuture
            .thenRun(() -> {
                // Record successful processing metrics
                Duration processingTime = Duration.between(processingStart, Instant.now());
                metrics.recordMessageReceived(topic);
                metrics.recordMessageProcessed(topic, processingTime);

                // Mark message as completed
                markMessageCompleted(messageId);

                logger.debug("Successfully processed message {} for consumer group {}",
                    messageId, consumerGroupName);
            })
            .exceptionally(error -> {
                logger.warn("Message processing failed for {} in consumer group {}: {}",
                    messageId, consumerGroupName, error.getMessage());

                // Record failed message metrics
                metrics.recordMessageFailed(topic, error.getClass().getSimpleName());

                // Handle retry logic with max retries check
                handleMessageFailureWithRetry(messageId, error.getMessage());

                return null;
            });
    }

    /**
     * Marks a message as completed using Vert.x reactive patterns.
     * CRITICAL: For financial systems, completion MUST be guaranteed or message reprocessed.
     */
    private void markMessageCompleted(String messageId) {
        if (closed.get()) {
            logger.debug("Consumer is closed, skipping completion operation for message {}", messageId);
            return;
        }

        String sql = "UPDATE outbox SET status = 'COMPLETED', processed_at = $1 WHERE id = $2";

        getReactivePoolFuture()
            .compose(pool -> pool.preparedQuery(sql)
                .execute(Tuple.of(OffsetDateTime.now(), Long.parseLong(messageId))))
            .onSuccess(result -> {
                if (result.rowCount() == 0) {
                    logger.error("CRITICAL: Message {} completion update affected 0 rows - message may not exist or already processed", messageId);
                } else {
                    logger.debug("Successfully marked message {} as completed", messageId);
                }
            })
            .onFailure(error -> {
                if (closed.get() && (error.getMessage().contains("Connection is not active") ||
                                     error.getMessage().contains("Pool closed") ||
                                     error.getMessage().contains("CLOSING"))) {
                    logger.debug("Pool/connection closed during shutdown for message {} completion - this is expected during shutdown", messageId);
                } else {
                    logger.error("CRITICAL: Failed to mark message {} as completed: {} - MESSAGE MAY BE REPROCESSED",
                        messageId, error.getMessage());
                    metrics.recordMessageFailed(topic, "COMPLETION_FAILURE");
                }
            });
    }

    // Removed deprecated resetMessageStatus(Connection, String) method - JDBC usage has been deprecated
    // Message status operations should now use reactive patterns with getReactivePoolFuture()

    /**
     * Handles message failure with proper retry logic and max retries checking using Vert.x reactive patterns.
     */
    private void handleMessageFailureWithRetry(String messageId, String errorMessage) {
        if (closed.get()) {
            logger.debug("Consumer is closed, skipping failure handling for message {}", messageId);
            return;
        }

        String selectSql = "SELECT retry_count, max_retries FROM outbox WHERE id = $1";

        getReactivePoolFuture()
            .compose(pool -> pool.preparedQuery(selectSql)
                .execute(Tuple.of(Long.parseLong(messageId))))
            .onSuccess(result -> {
                if (closed.get()) {
                    logger.debug("Consumer closed after retrieving retry info for message {}, skipping failure handling", messageId);
                    return;
                }

                if (result.size() > 0) {
                    io.vertx.sqlclient.Row row = result.iterator().next();
                    int currentRetryCount = row.getInteger("retry_count") != null ? row.getInteger("retry_count") : 0;

                    int maxRetries = getEffectiveMaxRetries();

                    // If no config provided, check database for max_retries
                    if (consumerConfig == null && configuration == null) {
                        Integer dbMaxRetries = row.getInteger("max_retries");
                        if (dbMaxRetries != null && dbMaxRetries > 0) {
                            maxRetries = dbMaxRetries;
                        }
                    }

                    logger.debug("Message {} failure handling: currentRetryCount={}, maxRetries={}",
                        messageId, currentRetryCount, maxRetries);

                    if (currentRetryCount >= maxRetries) {
                        moveToDeadLetterQueueReactive(messageId, currentRetryCount, errorMessage);
                    } else {
                        incrementRetryAndResetReactive(messageId, currentRetryCount, errorMessage);
                    }
                } else {
                    logger.warn("Message {} not found when handling failure", messageId);
                }
            })
            .onFailure(error -> {
                if (closed.get() && (error.getMessage().contains("Connection is not active") ||
                                     error.getMessage().contains("Pool closed") ||
                                     error.getMessage().contains("CLOSING"))) {
                    logger.debug("Pool/connection closed during shutdown for message {} failure handling - this is expected during shutdown", messageId);
                } else {
                    logger.warn("Failed to handle message failure for {}: {}", messageId, error.getMessage());
                }
            });
    }

    // Removed deprecated resetMessageStatusAsync() method - JDBC usage has been deprecated
    // Message status operations should now use reactive patterns with getReactivePoolFuture()

    // Removed deprecated incrementRetryAndReset(Connection, String, int, String) method - JDBC usage has been deprecated
    // Retry operations should now use reactive patterns with getReactivePoolFuture()

    // Removed deprecated moveToDeadLetterQueue(Connection, String, int, String) method - JDBC usage has been deprecated
    // Dead letter queue operations should now use reactive patterns with getReactivePoolFuture()

    /**
     * Increments retry count and resets message for retry using Vert.x reactive patterns.
     */
    private void incrementRetryAndResetReactive(String messageId, int currentRetryCount, String errorMessage) {
        if (closed.get()) {
            logger.debug("Consumer is closed, skipping retry increment for message {}", messageId);
            return;
        }

        String sql = "UPDATE outbox SET retry_count = $1, status = 'PENDING', processed_at = NULL, error_message = $2 WHERE id = $3";

        getReactivePoolFuture()
            .compose(pool -> pool.preparedQuery(sql)
                .execute(Tuple.of(currentRetryCount + 1, errorMessage, Long.parseLong(messageId))))
            .onSuccess(result -> {
                logger.debug("Incremented retry count to {} and reset message {} for retry",
                    currentRetryCount + 1, messageId);
            })
            .onFailure(error -> {
                if (closed.get() && (error.getMessage().contains("Connection is not active") ||
                                     error.getMessage().contains("Pool closed") ||
                                     error.getMessage().contains("CLOSING"))) {
                    logger.debug("Pool/connection closed during shutdown for message {} retry increment - this is expected during shutdown", messageId);
                } else {
                    logger.warn("Failed to increment retry count for message {}: {}", messageId, error.getMessage());
                }
            });
    }

    /**
     * Moves a message to dead letter queue after max retries exceeded using Vert.x reactive patterns.
     */
    private void moveToDeadLetterQueueReactive(String messageId, int retryCount, String errorMessage) {
        if (closed.get()) {
            logger.debug("Consumer is closed, skipping dead letter queue operation for message {}", messageId);
            return;
        }

        String selectSql = "SELECT topic, payload, created_at, headers, correlation_id, message_group FROM outbox WHERE id = $1";

        getReactivePoolFuture()
            .compose(pool -> pool.preparedQuery(selectSql)
                .execute(Tuple.of(Long.parseLong(messageId)))
                .compose(result -> {
                    if (closed.get()) {
                        logger.debug("Consumer closed after retrieving message {} details, skipping dead letter queue operation", messageId);
                        return Future.succeededFuture();
                    }

                    if (result.size() > 0) {
                        io.vertx.sqlclient.Row row = result.iterator().next();
                        String topic = row.getString("topic");
                        JsonObject payload = row.getJsonObject("payload");
                        java.time.LocalDateTime createdAtLocal = row.getLocalDateTime("created_at");
                        java.time.OffsetDateTime createdAt = createdAtLocal.atOffset(java.time.ZoneOffset.UTC);
                        JsonObject headers = row.getJsonObject("headers");
                        String correlationId = row.getString("correlation_id");
                        String messageGroup = row.getString("message_group");

                        if (topic != null) {
                            // Vert.x Pool.withTransaction() automatically handles event loop context
                            // No explicit executeOnVertxContext wrapper needed - Pool manages this internally
                            return pool.withTransaction(client -> {
                                if (closed.get()) {
                                    logger.debug("Consumer closed before transaction start for message {}, aborting dead letter queue operation", messageId);
                                    return Future.failedFuture(new IllegalStateException("Consumer is closed"));
                                }

                                String insertSql = """
                                    INSERT INTO dead_letter_queue (original_table, original_id, topic, payload,
                                                                  original_created_at, failure_reason, retry_count,
                                                                  headers, correlation_id, message_group)
                                    VALUES ('outbox', $1, $2, $3::jsonb, $4, $5, $6, $7::jsonb, $8, $9)
                                    """;

                                return client.preparedQuery(insertSql)
                                    .execute(Tuple.of(
                                        Long.parseLong(messageId), topic, payload, createdAt,
                                        errorMessage, retryCount, headers, correlationId, messageGroup))
                                    .compose(insertResult -> {
                                        String updateSql = "UPDATE outbox SET status = 'DEAD_LETTER', error_message = $1 WHERE id = $2";
                                        return client.preparedQuery(updateSql)
                                            .execute(Tuple.of(errorMessage, Long.parseLong(messageId)));
                                    });
                            })
                            .onSuccess(updateResult -> {
                                logger.info("Moved message {} to dead letter queue after {} retries", messageId, retryCount);
                            })
                            .onFailure(error -> {
                                if (closed.get() && (error.getMessage().contains("Connection is not active") ||
                                                     error.getMessage().contains("Pool closed") ||
                                                     error.getMessage().contains("CLOSING"))) {
                                    logger.debug("Pool/connection closed during shutdown for message {} dead letter queue operation - this is expected during shutdown", messageId);
                                } else {
                                    logger.error("Failed to move message {} to dead letter queue: {}", messageId, error.getMessage());
                                }
                            })
                            .mapEmpty();
                        }
                    } else {
                        logger.warn("Message {} not found when trying to move to dead letter queue", messageId);
                    }
                    return Future.succeededFuture();
                }))
            .onFailure(error -> {
                if (closed.get() && (error.getMessage().contains("Connection is not active") ||
                                     error.getMessage().contains("Pool closed") ||
                                     error.getMessage().contains("CLOSING"))) {
                    logger.debug("Pool/connection closed during shutdown for message {} details retrieval - this is expected during shutdown", messageId);
                } else {
                    logger.error("Failed to retrieve message {} details for dead letter queue: {}", messageId, error.getMessage());
                }
            });
    }

    // Removed deprecated deleteMessage() method - JDBC usage has been deprecated
    // Message deletion should now use reactive patterns with getReactivePoolFuture().compose(...)

    // Removed deprecated resetMessageStatus() method - JDBC usage has been deprecated
    // Message status operations should now use reactive patterns with getReactivePoolFuture().compose(...)



    // Removed deprecated getDataSource() method - JDBC usage has been deprecated in favor of Vert.x 5.x reactive patterns
    // All database operations should now use getReactivePoolFuture().compose(...) for better performance and consistency


    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            unsubscribe();
            if (scheduler != null) {
                scheduler.shutdown();
                try {
                    if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                        scheduler.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    scheduler.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }

            if (messageProcessingExecutor != null) {
                messageProcessingExecutor.shutdown();
                try {
                    if (!messageProcessingExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                        messageProcessingExecutor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    messageProcessingExecutor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }

            // Close reactive pool
            if (reactivePool != null) {
                reactivePool.close();
                logger.debug("Closed reactive pool for topic: {}", topic);
            }

            logger.info("Closed outbox consumer for topic: {}", topic);
        }
    }

    /**
     * Reactive acquisition of the pool without blocking.
     * Uses clientId for pool lookup - null clientId is resolved to the default pool by PgClientFactory.
     */
    private Future<Pool> getReactivePoolFuture() {
        // clientId can be null - PgClientFactory/ConnectionProvider resolves null to the default pool
        if (databaseService != null) {
            return databaseService.getConnectionProvider().getReactivePool(clientId);
        }
        if (clientFactory != null) {
            try {
                var connectionConfig = clientFactory.getConnectionConfig(clientId);
                var poolConfig = clientFactory.getPoolConfig(clientId);
                if (connectionConfig == null) {
                    String poolName = clientId != null ? clientId : "default";
                    return Future.failedFuture(new IllegalStateException("Connection configuration '" + poolName + "' not found"));
                }
                if (poolConfig == null) {
                    poolConfig = new dev.mars.peegeeq.db.config.PgPoolConfig.Builder().build();
                }
                // Use clientId for pool creation - null is resolved to default by PgConnectionManager
                String resolvedClientId = clientId != null ? clientId : dev.mars.peegeeq.db.PeeGeeQDefaults.DEFAULT_POOL_ID;
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
     * Parse payload from JsonObject back to the expected type.
     * Handles both simple values (wrapped in {"value": ...}) and complex objects.
     *
     * CRITICAL FIX: Use the same ObjectMapper that was used for serialization
     * instead of JsonObject.mapTo() which uses Vert.x's internal ObjectMapper.
     * This ensures consistent Instant/LocalDateTime serialization/deserialization.
     */
    private T parsePayloadFromJsonObject(JsonObject payload) throws Exception {
        if (payload == null) return null;

        // Check if this is a simple value wrapped in {"value": ...}
        if (payload.size() == 1 && payload.containsKey("value")) {
            Object value = payload.getValue("value");
            if (payloadType.isInstance(value)) {
                @SuppressWarnings("unchecked")
                T result = (T) value;
                return result;
            }
        }

        // CRITICAL FIX: For complex objects, use the configured ObjectMapper
        // instead of JsonObject.mapTo() to ensure consistent serialization/deserialization
        // This fixes the Instant deserialization issue with Vert.x's InstantDeserializer
        try {
            String jsonString = payload.encode();
            return objectMapper.readValue(jsonString, payloadType);
        } catch (Exception e) {
            logger.error("Failed to deserialize payload using ObjectMapper for type {}: {}",
                        payloadType.getSimpleName(), e.getMessage());
            logger.debug("Payload JSON: {}", payload.encode());
            throw e;
        }
    }

    /**
     * Parse headers from JsonObject to Map<String, String>.
     */
    private Map<String, String> parseHeadersFromJsonObject(JsonObject headers) {
        if (headers == null || headers.isEmpty()) return new HashMap<>();

        Map<String, String> result = new HashMap<>();
        for (String key : headers.fieldNames()) {
            Object value = headers.getValue(key);
            result.put(key, value != null ? value.toString() : null);
        }
        return result;
    }

    // Helper methods to get effective configuration values
    // OutboxConsumerConfig takes precedence over PeeGeeQConfiguration

    private int getEffectiveConsumerThreads() {
        if (consumerConfig != null) {
            return consumerConfig.getConsumerThreads();
        }
        if (configuration != null) {
            return configuration.getQueueConfig().getConsumerThreads();
        }
        return 1; // default
    }

    private Duration getEffectivePollingInterval() {
        if (consumerConfig != null) {
            return consumerConfig.getPollingInterval();
        }
        if (configuration != null) {
            return configuration.getQueueConfig().getPollingInterval();
        }
        return Duration.ofMillis(500); // default
    }

    private int getEffectiveBatchSize() {
        if (consumerConfig != null) {
            return consumerConfig.getBatchSize();
        }
        if (configuration != null) {
            return configuration.getQueueConfig().getBatchSize();
        }
        return 1; // default
    }

    private int getEffectiveMaxRetries() {
        if (consumerConfig != null) {
            return consumerConfig.getMaxRetries();
        }
        if (configuration != null) {
            return configuration.getQueueConfig().getMaxRetries();
        }
        return 3; // default
    }
}
