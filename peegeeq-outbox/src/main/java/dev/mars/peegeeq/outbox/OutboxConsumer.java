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
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.db.client.PgClientFactory;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.metrics.PeeGeeQMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
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
    private final PeeGeeQMetrics metrics;
    private final PeeGeeQConfiguration configuration;
    private final AtomicBoolean subscribed = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    // Consumer group name for tracking which messages this consumer has processed
    private String consumerGroupName;

    private MessageHandler<T> messageHandler;
    private ScheduledExecutorService scheduler;
    private ExecutorService messageProcessingExecutor;

    // Vert.x 5.x reactive pool for non-blocking database operations
    private volatile Pool reactivePool;

    // Shared Vertx instance for proper context management
    private static volatile Vertx sharedVertx;

    public OutboxConsumer(PgClientFactory clientFactory, ObjectMapper objectMapper,
                         String topic, Class<T> payloadType, PeeGeeQMetrics metrics) {
        this(clientFactory, objectMapper, topic, payloadType, metrics, null);
    }

    public OutboxConsumer(PgClientFactory clientFactory, ObjectMapper objectMapper,
                         String topic, Class<T> payloadType, PeeGeeQMetrics metrics,
                         PeeGeeQConfiguration configuration) {
        this.clientFactory = clientFactory;
        this.databaseService = null;
        this.objectMapper = objectMapper;
        this.topic = topic;
        this.payloadType = payloadType;
        this.metrics = metrics;
        this.configuration = configuration;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "outbox-consumer-" + topic);
            t.setDaemon(true);
            return t;
        });

        // Initialize message processing thread pool
        int consumerThreads = configuration != null ?
            configuration.getQueueConfig().getConsumerThreads() : 1;
        this.messageProcessingExecutor = Executors.newFixedThreadPool(consumerThreads, r -> {
            Thread t = new Thread(r, "outbox-processor-" + topic + "-" + System.currentTimeMillis());
            t.setDaemon(true);
            return t;
        });

        logger.info("Created outbox consumer for topic: {} with configuration: {} (threads: {})",
            topic, configuration != null ? "enabled" : "disabled", consumerThreads);
    }

    public OutboxConsumer(DatabaseService databaseService, ObjectMapper objectMapper,
                         String topic, Class<T> payloadType, PeeGeeQMetrics metrics) {
        this(databaseService, objectMapper, topic, payloadType, metrics, null);
    }

    public OutboxConsumer(DatabaseService databaseService, ObjectMapper objectMapper,
                         String topic, Class<T> payloadType, PeeGeeQMetrics metrics,
                         PeeGeeQConfiguration configuration) {
        this.clientFactory = null;
        this.databaseService = databaseService;
        this.objectMapper = objectMapper;
        this.topic = topic;
        this.payloadType = payloadType;
        this.metrics = metrics;
        this.configuration = configuration;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "outbox-consumer-" + topic);
            t.setDaemon(true);
            return t;
        });

        // Initialize message processing thread pool
        int consumerThreads = configuration != null ?
            configuration.getQueueConfig().getConsumerThreads() : 1;
        this.messageProcessingExecutor = Executors.newFixedThreadPool(consumerThreads, r -> {
            Thread t = new Thread(r, "outbox-processor-" + topic + "-" + System.currentTimeMillis());
            t.setDaemon(true);
            return t;
        });

        logger.info("Created outbox consumer for topic: {} (using DatabaseService) with configuration: {} (threads: {})",
            topic, configuration != null ? "enabled" : "disabled", consumerThreads);
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
        // Get polling interval from configuration or use default
        Duration pollingInterval = configuration != null ?
            configuration.getQueueConfig().getPollingInterval() : Duration.ofMillis(500);

        long pollingIntervalMs = pollingInterval.toMillis();

        System.out.println(" Starting polling for topic " + topic + " with interval: " + pollingIntervalMs + " ms");
        logger.info("Starting polling for topic {} with interval: {} ms", topic, pollingIntervalMs);

        System.out.println(" Scheduler state: " + (scheduler != null ? "present" : "null"));
        System.out.println(" Scheduler shutdown: " + (scheduler != null ? scheduler.isShutdown() : "N/A"));
        System.out.println(" Scheduler terminated: " + (scheduler != null ? scheduler.isTerminated() : "N/A"));

        // Poll for messages at configured interval
        try {
            System.out.println(" About to schedule polling task...");
            scheduler.scheduleWithFixedDelay(this::processAvailableMessages, 0, pollingIntervalMs, TimeUnit.MILLISECONDS);
            System.out.println(" Polling task scheduled successfully");
        } catch (Exception e) {
            System.out.println(" Error scheduling polling task: " + e.getMessage());
            e.printStackTrace();
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
        try {
            Pool pool = getOrCreateReactivePool();
            int batchSize = configuration != null ? configuration.getQueueConfig().getBatchSize() : 1;

            String sql = """
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

            Tuple params = Tuple.of(OffsetDateTime.now(), topic, batchSize);

            return pool.preparedQuery(sql)
                .execute(params)
                .compose(rowSet -> {
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
                });

        } catch (Exception e) {
            logger.error("Failed to process messages reactively for topic {}: {}", topic, e.getMessage(), e);
            return Future.failedFuture(e);
        }
    }

    /**
     * Processes a single row from the database reactively.
     */
    private Future<Void> processRowReactive(Row row) {
        try {
            String messageId = String.valueOf(row.getLong("id"));
            String payloadJson = row.getString("payload");
            String headersJson = row.getString("headers");
            String correlationId = row.getString("correlation_id");

            T payload = objectMapper.readValue(payloadJson, payloadType);
            Map<String, String> headers = new HashMap<>();
            if (headersJson != null && !headersJson.trim().isEmpty()) {
                Map<String, String> deserializedHeaders = objectMapper.readValue(headersJson,
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, String.class));
                headers.putAll(deserializedHeaders);
            }

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
            Pool pool = getOrCreateReactivePool();
            String sql = "UPDATE outbox SET status = 'FAILED', processed_at = $1 WHERE id = $2";
            Tuple params = Tuple.of(OffsetDateTime.now(), Long.parseLong(messageId));

            return pool.preparedQuery(sql)
                .execute(params)
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
                if (metrics != null) {
                    Duration processingTime = Duration.between(processingStart, Instant.now());
                    metrics.recordMessageReceived(topic);
                    metrics.recordMessageProcessed(topic, processingTime);
                }

                // Mark message as completed
                markMessageCompleted(messageId);

                logger.debug("Successfully processed message {} for consumer group {}",
                    messageId, consumerGroupName);
            })
            .exceptionally(error -> {
                logger.warn("Message processing failed for {} in consumer group {}: {}",
                    messageId, consumerGroupName, error.getMessage());

                // Record failed message metrics
                if (metrics != null) {
                    metrics.recordMessageFailed(topic, error.getClass().getSimpleName());
                }

                // Handle retry logic with max retries check
                handleMessageFailureWithRetry(messageId, error.getMessage());

                return null;
            });
    }

    /**
     * Marks a message as completed using Vert.x reactive patterns.
     */
    private void markMessageCompleted(String messageId) {
        Pool pool = getOrCreateReactivePool();
        if (pool == null) {
            logger.warn("No reactive pool available to mark message {} as completed", messageId);
            return;
        }

        String sql = "UPDATE outbox SET status = 'COMPLETED' WHERE id = $1";

        pool.preparedQuery(sql)
            .execute(io.vertx.sqlclient.Tuple.of(Long.parseLong(messageId)))
            .onSuccess(result -> {
                logger.debug("Marked message {} as completed", messageId);
            })
            .onFailure(error -> {
                logger.warn("Failed to mark message {} as completed: {}", messageId, error.getMessage());
            });
    }

    /**
     * Resets message status back to PENDING for retry.
     */
    private void resetMessageStatus(Connection conn, String messageId) {
        try {
            String sql = "UPDATE outbox SET status = 'PENDING', processed_at = NULL WHERE id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setLong(1, Long.parseLong(messageId));
                stmt.executeUpdate();
                logger.debug("Reset message {} status to PENDING for retry", messageId);
            }
        } catch (Exception e) {
            logger.warn("Failed to reset message {} status: {}", messageId, e.getMessage());
        }
    }

    /**
     * Handles message failure with proper retry logic and max retries checking using Vert.x reactive patterns.
     */
    private void handleMessageFailureWithRetry(String messageId, String errorMessage) {
        Pool pool = getOrCreateReactivePool();
        if (pool == null) {
            logger.warn("No reactive pool available to handle failure for message {}", messageId);
            return;
        }

        // Get current retry count and max retries
        String selectSql = "SELECT retry_count, max_retries FROM outbox WHERE id = $1";

        pool.preparedQuery(selectSql)
            .execute(io.vertx.sqlclient.Tuple.of(Long.parseLong(messageId)))
            .onSuccess(result -> {
                if (result.size() > 0) {
                    io.vertx.sqlclient.Row row = result.iterator().next();
                    int currentRetryCount = row.getInteger("retry_count") != null ? row.getInteger("retry_count") : 0;

                    // Get max retries from configuration or use default
                    int maxRetries = configuration != null ?
                        configuration.getQueueConfig().getMaxRetries() : 3; // Use configuration or fallback to 3

                    // Configuration takes precedence over database max_retries
                    // Only use database max_retries if no configuration is available
                    if (configuration == null) {
                        Integer dbMaxRetries = row.getInteger("max_retries");
                        if (dbMaxRetries != null && dbMaxRetries > 0) {
                            maxRetries = dbMaxRetries;
                        }
                    }

                    logger.debug("Message {} failure handling: currentRetryCount={}, maxRetries={}",
                        messageId, currentRetryCount, maxRetries);

                    if (currentRetryCount >= maxRetries) {
                        // Move to dead letter queue - currentRetryCount is the actual number of retries attempted
                        moveToDeadLetterQueueReactive(messageId, currentRetryCount, errorMessage);
                    } else {
                        // Increment retry count and reset for retry
                        incrementRetryAndResetReactive(messageId, currentRetryCount, errorMessage);
                    }
                } else {
                    logger.warn("Message {} not found when handling failure", messageId);
                }
            })
            .onFailure(error -> {
                logger.warn("Failed to handle message failure for {}: {}", messageId, error.getMessage());
            });
    }

    /**
     * Resets message status asynchronously.
     */
    private void resetMessageStatusAsync(String messageId) {
        try {
            DataSource dataSource = getDataSource();
            if (dataSource != null) {
                try (Connection conn = dataSource.getConnection()) {
                    resetMessageStatus(conn, messageId);
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to reset message {} status asynchronously: {}", messageId, e.getMessage());
        }
    }

    /**
     * Increments retry count and resets message for retry.
     */
    private void incrementRetryAndReset(Connection conn, String messageId, int currentRetryCount, String errorMessage) {
        try {
            String sql = "UPDATE outbox SET retry_count = ?, status = 'PENDING', processed_at = NULL, error_message = ? WHERE id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, currentRetryCount + 1);
                stmt.setString(2, errorMessage);
                stmt.setLong(3, Long.parseLong(messageId));
                stmt.executeUpdate();

                // Explicitly commit if not auto-commit
                if (!conn.getAutoCommit()) {
                    conn.commit();
                }

                logger.debug("Incremented retry count to {} and reset message {} for retry",
                    currentRetryCount + 1, messageId);
            }
        } catch (Exception e) {
            logger.warn("Failed to increment retry count for message {}: {}", messageId, e.getMessage());
        }
    }

    /**
     * Moves a message to dead letter queue after max retries exceeded.
     */
    private void moveToDeadLetterQueue(Connection conn, String messageId, int retryCount, String errorMessage) {
        try {
            // First get the message details
            String selectSql = "SELECT topic, payload, created_at, headers, correlation_id, message_group FROM outbox WHERE id = ?";
            String topic = null;
            String payload = null;
            java.sql.Timestamp createdAt = null;
            String headers = null;
            String correlationId = null;
            String messageGroup = null;

            try (PreparedStatement selectStmt = conn.prepareStatement(selectSql)) {
                selectStmt.setLong(1, Long.parseLong(messageId));
                try (ResultSet rs = selectStmt.executeQuery()) {
                    if (rs.next()) {
                        topic = rs.getString("topic");
                        payload = rs.getString("payload");
                        createdAt = rs.getTimestamp("created_at");
                        headers = rs.getString("headers");
                        correlationId = rs.getString("correlation_id");
                        messageGroup = rs.getString("message_group");
                    }
                }
            }

            if (topic != null) {
                // Insert into dead letter queue
                String insertSql = """
                    INSERT INTO dead_letter_queue (original_table, original_id, topic, payload,
                                                  original_created_at, failure_reason, retry_count,
                                                  headers, correlation_id, message_group)
                    VALUES ('outbox', ?, ?, ?::jsonb, ?, ?, ?, ?::jsonb, ?, ?)
                    """;
                try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                    insertStmt.setLong(1, Long.parseLong(messageId));
                    insertStmt.setString(2, topic);
                    insertStmt.setString(3, payload);
                    insertStmt.setTimestamp(4, createdAt);
                    insertStmt.setString(5, errorMessage);
                    insertStmt.setInt(6, retryCount);
                    insertStmt.setString(7, headers);
                    insertStmt.setString(8, correlationId);
                    insertStmt.setString(9, messageGroup);

                    insertStmt.executeUpdate();
                }

                // Update original message status
                String updateSql = "UPDATE outbox SET status = 'DEAD_LETTER', error_message = ? WHERE id = ?";
                try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                    updateStmt.setString(1, errorMessage);
                    updateStmt.setLong(2, Long.parseLong(messageId));
                    updateStmt.executeUpdate();
                }

                // Explicitly commit if not auto-commit
                if (!conn.getAutoCommit()) {
                    conn.commit();
                }

                logger.info("Moved message {} to dead letter queue after {} retries", messageId, retryCount);
            }
        } catch (Exception e) {
            logger.error("Failed to move message {} to dead letter queue: {}", messageId, e.getMessage());
        }
    }

    /**
     * Increments retry count and resets message for retry using Vert.x reactive patterns.
     */
    private void incrementRetryAndResetReactive(String messageId, int currentRetryCount, String errorMessage) {
        Pool pool = getOrCreateReactivePool();
        if (pool == null) {
            logger.warn("No reactive pool available to increment retry for message {}", messageId);
            return;
        }

        String sql = "UPDATE outbox SET retry_count = $1, status = 'PENDING', processed_at = NULL, error_message = $2 WHERE id = $3";

        pool.preparedQuery(sql)
            .execute(io.vertx.sqlclient.Tuple.of(currentRetryCount + 1, errorMessage, Long.parseLong(messageId)))
            .onSuccess(result -> {
                logger.debug("Incremented retry count to {} and reset message {} for retry",
                    currentRetryCount + 1, messageId);
            })
            .onFailure(error -> {
                logger.warn("Failed to increment retry count for message {}: {}", messageId, error.getMessage());
            });
    }

    /**
     * Moves a message to dead letter queue after max retries exceeded using Vert.x reactive patterns.
     */
    private void moveToDeadLetterQueueReactive(String messageId, int retryCount, String errorMessage) {
        Pool pool = getOrCreateReactivePool();
        if (pool == null) {
            logger.warn("No reactive pool available to move message {} to dead letter queue", messageId);
            return;
        }

        // First get the message details
        String selectSql = "SELECT topic, payload, created_at, headers, correlation_id, message_group FROM outbox WHERE id = $1";

        pool.preparedQuery(selectSql)
            .execute(io.vertx.sqlclient.Tuple.of(Long.parseLong(messageId)))
            .onSuccess(result -> {
                if (result.size() > 0) {
                    io.vertx.sqlclient.Row row = result.iterator().next();
                    String topic = row.getString("topic");
                    String payload = row.getString("payload");
                    java.time.LocalDateTime createdAtLocal = row.getLocalDateTime("created_at");
                    java.time.OffsetDateTime createdAt = createdAtLocal.atOffset(java.time.ZoneOffset.UTC);
                    String headers = row.getString("headers");
                    String correlationId = row.getString("correlation_id");
                    String messageGroup = row.getString("message_group");

                    if (topic != null) {
                        // Insert into dead letter queue and update original message in a transaction
                        pool.withTransaction(client -> {
                            // Insert into dead letter queue
                            String insertSql = """
                                INSERT INTO dead_letter_queue (original_table, original_id, topic, payload,
                                                              original_created_at, failure_reason, retry_count,
                                                              headers, correlation_id, message_group)
                                VALUES ('outbox', $1, $2, $3::jsonb, $4, $5, $6, $7::jsonb, $8, $9)
                                """;

                            return client.preparedQuery(insertSql)
                                .execute(io.vertx.sqlclient.Tuple.of(
                                    Long.parseLong(messageId), topic, payload, createdAt,
                                    errorMessage, retryCount, headers, correlationId, messageGroup))
                                .compose(insertResult -> {
                                    // Update original message status
                                    String updateSql = "UPDATE outbox SET status = 'DEAD_LETTER', error_message = $1 WHERE id = $2";
                                    return client.preparedQuery(updateSql)
                                        .execute(io.vertx.sqlclient.Tuple.of(errorMessage, Long.parseLong(messageId)));
                                });
                        })
                        .onSuccess(updateResult -> {
                            logger.info("Moved message {} to dead letter queue after {} retries", messageId, retryCount);
                        })
                        .onFailure(error -> {
                            logger.error("Failed to move message {} to dead letter queue: {}", messageId, error.getMessage());
                        });
                    }
                } else {
                    logger.warn("Message {} not found when trying to move to dead letter queue", messageId);
                }
            })
            .onFailure(error -> {
                logger.error("Failed to retrieve message {} details for dead letter queue: {}", messageId, error.getMessage());
            });
    }

    @SuppressWarnings("unused") // Reserved for future message cleanup features
    private void deleteMessage(String messageId) {
        try {
            DataSource dataSource = getDataSource();
            if (dataSource == null) {
                logger.warn("No data source available, cannot delete message {} for topic {}", messageId, topic);
                return;
            }

            try (Connection conn = dataSource.getConnection()) {
                String sql = "DELETE FROM outbox WHERE id = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    // messageId is the database ID as a string, convert it back to long
                    stmt.setLong(1, Long.parseLong(messageId));
                    stmt.executeUpdate();
                    logger.debug("Deleted processed message: {}", messageId);
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to delete message {}: {}", messageId, e.getMessage());
        }
    }

    @SuppressWarnings("unused") // Reserved for future message retry features
    private void resetMessageStatus(String messageId) {
        try {
            DataSource dataSource = getDataSource();
            if (dataSource == null) {
                logger.warn("No data source available, cannot reset message status for {} in topic {}", messageId, topic);
                return;
            }

            try (Connection conn = dataSource.getConnection()) {
                String sql = "UPDATE outbox SET status = 'PENDING', processing_started_at = NULL WHERE id = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    // messageId is the database ID as a string, convert it back to long
                    stmt.setLong(1, Long.parseLong(messageId));
                    stmt.executeUpdate();
                    logger.debug("Reset message status for retry: {}", messageId);
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to reset message status {}: {}", messageId, e.getMessage());
        }
    }



    private DataSource getDataSource() {
        // JDBC DataSource usage has been deprecated in favor of Vert.x 5.x reactive patterns
        // This method now returns null to gracefully handle the transition period
        logger.debug("JDBC DataSource access attempted for topic {} - returning null as JDBC usage has been deprecated", topic);
        logger.debug("Consider migrating to reactive patterns using getOrCreateReactivePool() for better performance");
        return null;

        // Legacy JDBC code commented out - will be removed in future versions
        /*
        try {
            if (clientFactory != null) {
                // Use the client factory approach
                var connectionConfig = clientFactory.getConnectionConfig("peegeeq-main");
                String clientName = "peegeeq-main";
                var poolConfig = clientFactory.getPoolConfig(clientName);

                if (connectionConfig == null) {
                    logger.warn("Connection configuration '{}' not found in PgClientFactory for topic {}, trying to get available clients", clientName, topic);

                    // Try to get any available client as fallback
                    var availableClients = clientFactory.getAvailableClients();
                    if (!availableClients.isEmpty()) {
                        clientName = availableClients.iterator().next();
                        logger.info("Using fallback client '{}' for topic {}", clientName, topic);
                        connectionConfig = clientFactory.getConnectionConfig(clientName);
                        poolConfig = clientFactory.getPoolConfig(clientName);
                        if (connectionConfig == null) {
                            logger.error("Fallback client '{}' also has no connection config for topic {}", clientName, topic);
                            return null;
                        }
                    } else {
                        logger.error("No clients available in PgClientFactory for topic {}", topic);
                        return null;
                    }
                }

                if (poolConfig == null) {
                    logger.warn("Pool configuration '{}' not found in PgClientFactory for topic {}, using default", clientName, topic);
                    poolConfig = new dev.mars.peegeeq.db.config.PgPoolConfig.Builder().build();
                }

                return clientFactory.getConnectionManager().getOrCreateDataSource(
                    "outbox-consumer",
                    connectionConfig,
                    poolConfig
                );
            } else if (databaseService != null) {
                // Use the database service approach
                var connectionProvider = databaseService.getConnectionProvider();
                logger.debug("Checking for 'peegeeq-main' client in connection provider for topic {}", topic);
                if (connectionProvider.hasClient("peegeeq-main")) {
                    logger.debug("Found 'peegeeq-main' client, getting data source for topic {}", topic);
                    return connectionProvider.getDataSource("peegeeq-main");
                } else {
                    logger.warn("Client 'peegeeq-main' not found in connection provider for topic {}", topic);
                    logger.debug("Attempting to create fallback data source for topic {}", topic);

                    // Try to get the data source directly from the manager if available
                    try {
                        // Access the manager's data source directly as a fallback
                        if (databaseService instanceof dev.mars.peegeeq.db.provider.PgDatabaseService) {
                            var pgDbService = (dev.mars.peegeeq.db.provider.PgDatabaseService) databaseService;
                            // Use reflection or a public method to get the manager's data source
                            logger.debug("Attempting to use manager's data source as fallback for topic {}", topic);
                            // For now, return null and let the fallback mechanism in OutboxFactory handle it
                        }
                    } catch (Exception e) {
                        logger.debug("Failed to get fallback data source: {}", e.getMessage());
                    }

                    return null;
                }
            } else {
                logger.error("Both clientFactory and databaseService are null for topic {}", topic);
                return null;
            }
        } catch (Exception e) {
            logger.error("Failed to get data source for topic {}: {}", topic, e.getMessage(), e);
            return null;
        }
        */
    }

    /**
     * Gets or creates a Vert.x reactive pool for non-blocking database operations.
     * Following the established pattern from OutboxProducer.
     *
     * @return Pool for reactive database operations
     */
    private Pool getOrCreateReactivePool() {
        if (reactivePool == null) {
            synchronized (this) {
                if (reactivePool == null) {
                    if (clientFactory != null) {
                        // Get connection configuration from client factory
                        var connectionConfig = clientFactory.getConnectionConfig("peegeeq-main");
                        var poolConfig = clientFactory.getPoolConfig("peegeeq-main");

                        if (connectionConfig == null) {
                            throw new RuntimeException("Connection configuration 'peegeeq-main' not found in PgClientFactory for topic " + topic);
                        }

                        if (poolConfig == null) {
                            logger.warn("Pool configuration 'peegeeq-main' not found in PgClientFactory for topic {}, using default", topic);
                            poolConfig = new dev.mars.peegeeq.db.config.PgPoolConfig.Builder().build();
                        }

                        // Create Vert.x instance if needed
                        if (sharedVertx == null) {
                            synchronized (OutboxConsumer.class) {
                                if (sharedVertx == null) {
                                    sharedVertx = Vertx.vertx();
                                    logger.info("Created shared Vertx instance for OutboxConsumer reactive operations");
                                }
                            }
                        }

                        // Create reactive pool using PgBuilder pattern
                        PgConnectOptions connectOptions = new PgConnectOptions()
                            .setHost(connectionConfig.getHost())
                            .setPort(connectionConfig.getPort())
                            .setDatabase(connectionConfig.getDatabase())
                            .setUser(connectionConfig.getUsername())
                            .setPassword(connectionConfig.getPassword());

                        if (connectionConfig.isSslEnabled()) {
                            connectOptions.setSslMode(io.vertx.pgclient.SslMode.REQUIRE);
                        }

                        PoolOptions poolOptions = new PoolOptions()
                            .setMaxSize(poolConfig.getMaximumPoolSize());

                        reactivePool = PgBuilder.pool()
                            .with(poolOptions)
                            .connectingTo(connectOptions)
                            .using(sharedVertx)
                            .build();

                        logger.info("Created reactive pool for outbox consumer topic: {}", topic);

                    } else if (databaseService != null) {
                        // TODO: Add support for DatabaseService-based reactive pool creation
                        throw new UnsupportedOperationException("Reactive pool creation from DatabaseService not yet implemented for OutboxConsumer");
                    } else {
                        throw new RuntimeException("No client factory or database service available for reactive pool creation");
                    }
                }
            }
        }
        return reactivePool;
    }

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
}
