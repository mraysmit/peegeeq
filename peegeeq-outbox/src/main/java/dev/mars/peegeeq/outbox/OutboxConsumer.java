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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
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
        
        this.messageHandler = handler;
        if (subscribed.compareAndSet(false, true)) {
            startPolling();
            logger.info("Subscribed to topic: {}", topic);
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

        // Poll for messages at configured interval
        scheduler.scheduleWithFixedDelay(this::processAvailableMessages, 0, pollingIntervalMs, TimeUnit.MILLISECONDS);

        logger.debug("Started polling for topic {} with interval: {}", topic, pollingInterval);
    }

    private void processAvailableMessages() {
        if (!subscribed.get() || closed.get()) {
            return;
        }

        try {
            DataSource dataSource = getDataSource();
            if (dataSource == null) {
                logger.warn("No data source available for topic {}", topic);
                return;
            }

            try (Connection conn = dataSource.getConnection()) {
                // Get batch size from configuration
                int batchSize = configuration != null ?
                    configuration.getQueueConfig().getBatchSize() : 1;

                // Queue-based approach with proper locking for competing consumers
                // Modified to fetch multiple messages in a batch
                String sql = """
                    UPDATE outbox
                    SET status = 'PROCESSING', processed_at = ?
                    WHERE id IN (
                        SELECT id FROM outbox
                        WHERE topic = ? AND status = 'PENDING'
                        ORDER BY created_at ASC
                        LIMIT ?
                        FOR UPDATE SKIP LOCKED
                    )
                    RETURNING id, payload, headers, correlation_id, message_group, created_at
                    """;

                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setTimestamp(1, Timestamp.from(Instant.now()));
                    stmt.setString(2, topic);
                    stmt.setInt(3, batchSize);

                    try (ResultSet rs = stmt.executeQuery()) {
                        int messageCount = 0;
                        while (rs.next()) {
                            messageCount++;
                            String messageId = rs.getString("id");
                            String payloadJson = rs.getString("payload");
                            String headersJson = rs.getString("headers");
                            String correlationId = rs.getString("correlation_id");
                            Timestamp createdAt = rs.getTimestamp("created_at");

                            try {
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

                                Message<T> message = new OutboxMessage<>(messageId, payload,
                                    createdAt.toInstant(), headers, correlationId);

                                logger.debug("Processing message {} from topic {} for consumer group {}",
                                    messageId, topic, consumerGroupName);

                                // Record metrics
                                if (metrics != null) {
                                    metrics.recordMessageReceived(topic);
                                }

                                // Process the message and mark as completed (submit to thread pool)
                                messageProcessingExecutor.submit(() -> processMessageWithCompletion(message, messageId));

                            } catch (Exception e) {
                                logger.error("Failed to deserialize message {}: {}", messageId, e.getMessage());
                                // Reset status back to PENDING for retry
                                resetMessageStatus(conn, messageId);
                            }
                        }

                        if (messageCount > 0) {
                            logger.debug("Processing batch of {} messages for topic {}", messageCount, topic);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Error processing outbox messages for topic {} and consumer group {}: {}",
                topic, consumerGroupName, e.getMessage());
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
        CompletableFuture<Void> processingFuture;
        try {
            processingFuture = messageHandler.handle(message);
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
     * Marks a message as completed.
     */
    private void markMessageCompleted(String messageId) {
        try {
            DataSource dataSource = getDataSource();
            if (dataSource == null) {
                logger.warn("No data source available to mark message {} as completed", messageId);
                return;
            }

            try (Connection conn = dataSource.getConnection()) {
                String sql = "UPDATE outbox SET status = 'COMPLETED' WHERE id = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setLong(1, Long.parseLong(messageId));
                    stmt.executeUpdate();
                    logger.debug("Marked message {} as completed", messageId);
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to mark message {} as completed: {}", messageId, e.getMessage());
        }
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
     * Handles message failure with proper retry logic and max retries checking.
     */
    private void handleMessageFailureWithRetry(String messageId, String errorMessage) {
        try {
            DataSource dataSource = getDataSource();
            if (dataSource == null) {
                logger.warn("No data source available to handle failure for message {}", messageId);
                return;
            }

            try (Connection conn = dataSource.getConnection()) {
                // Get current retry count and max retries
                String selectSql = "SELECT retry_count, max_retries FROM outbox WHERE id = ?";
                int currentRetryCount = 0;
                int maxRetries = configuration != null ?
                    configuration.getQueueConfig().getMaxRetries() : 3; // Use configuration or fallback to 3

                try (PreparedStatement selectStmt = conn.prepareStatement(selectSql)) {
                    selectStmt.setLong(1, Long.parseLong(messageId));
                    try (ResultSet rs = selectStmt.executeQuery()) {
                        if (rs.next()) {
                            currentRetryCount = rs.getInt("retry_count");
                            // Use the max_retries from the database if it's set, otherwise use configuration
                            int dbMaxRetries = rs.getInt("max_retries");
                            if (dbMaxRetries > 0) {
                                maxRetries = dbMaxRetries;
                            }
                        }
                    }
                }

                // Check if we should retry or move to dead letter
                if (currentRetryCount >= maxRetries) {
                    // Move to dead letter queue
                    moveToDeadLetterQueue(conn, messageId, currentRetryCount, errorMessage);
                } else {
                    // Increment retry count and reset for retry
                    incrementRetryAndReset(conn, messageId, currentRetryCount, errorMessage);
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to handle message failure for {}: {}", messageId, e.getMessage());
        }
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

                logger.info("Moved message {} to dead letter queue after {} retries", messageId, retryCount);
            }
        } catch (Exception e) {
            logger.error("Failed to move message {} to dead letter queue: {}", messageId, e.getMessage());
        }
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
                if (connectionProvider.hasClient("peegeeq-main")) {
                    return connectionProvider.getDataSource("peegeeq-main");
                } else {
                    logger.warn("Client 'peegeeq-main' not found in connection provider for topic {}", topic);
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

            logger.info("Closed outbox consumer for topic: {}", topic);
        }
    }
}
