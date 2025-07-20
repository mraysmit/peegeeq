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
    private final AtomicBoolean subscribed = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    // Consumer group name for tracking which messages this consumer has processed
    private String consumerGroupName;

    private MessageHandler<T> messageHandler;
    private ScheduledExecutorService scheduler;

    public OutboxConsumer(PgClientFactory clientFactory, ObjectMapper objectMapper,
                         String topic, Class<T> payloadType, PeeGeeQMetrics metrics) {
        this.clientFactory = clientFactory;
        this.databaseService = null;
        this.objectMapper = objectMapper;
        this.topic = topic;
        this.payloadType = payloadType;
        this.metrics = metrics;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "outbox-consumer-" + topic);
            t.setDaemon(true);
            return t;
        });
        logger.info("Created outbox consumer for topic: {}", topic);
    }

    public OutboxConsumer(DatabaseService databaseService, ObjectMapper objectMapper,
                         String topic, Class<T> payloadType, PeeGeeQMetrics metrics) {
        this.clientFactory = null;
        this.databaseService = databaseService;
        this.objectMapper = objectMapper;
        this.topic = topic;
        this.payloadType = payloadType;
        this.metrics = metrics;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "outbox-consumer-" + topic);
            t.setDaemon(true);
            return t;
        });
        logger.info("Created outbox consumer for topic: {} (using DatabaseService)", topic);
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
        // Poll for messages more frequently to catch all messages
        scheduler.scheduleWithFixedDelay(this::processAvailableMessages, 0, 500, TimeUnit.MILLISECONDS);
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
                // Queue-based approach with proper locking for competing consumers
                String sql = """
                    UPDATE outbox
                    SET status = 'PROCESSING', processed_at = ?
                    WHERE id = (
                        SELECT id FROM outbox
                        WHERE topic = ? AND status = 'PENDING'
                        ORDER BY created_at ASC
                        LIMIT 1
                        FOR UPDATE SKIP LOCKED
                    )
                    RETURNING id, payload, headers, correlation_id, message_group, created_at
                    """;

                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setTimestamp(1, Timestamp.from(Instant.now()));
                    stmt.setString(2, topic);

                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
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

                                // Process the message and mark as completed
                                processMessageWithCompletion(message, messageId);

                            } catch (Exception e) {
                                logger.error("Failed to deserialize message {}: {}", messageId, e.getMessage());
                                // Reset status back to PENDING for retry
                                resetMessageStatus(conn, messageId);
                            }
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
        Instant processingStart = Instant.now();
        CompletableFuture<Void> processingFuture = messageHandler.handle(message);

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

                // Reset message status for retry
                resetMessageStatusAsync(messageId);

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
            logger.info("Closed outbox consumer for topic: {}", topic);
        }
    }
}
