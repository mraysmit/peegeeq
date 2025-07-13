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


import dev.mars.peegeeq.api.Message;
import dev.mars.peegeeq.api.MessageConsumer;
import dev.mars.peegeeq.api.MessageHandler;
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
import java.time.OffsetDateTime;
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
public class OutboxConsumer<T> implements MessageConsumer<T> {
    private static final Logger logger = LoggerFactory.getLogger(OutboxConsumer.class);

    private final PgClientFactory clientFactory;
    private final ObjectMapper objectMapper;
    private final String topic;
    private final Class<T> payloadType;
    private final PeeGeeQMetrics metrics;
    private final AtomicBoolean subscribed = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    
    private MessageHandler<T> messageHandler;
    private ScheduledExecutorService scheduler;

    public OutboxConsumer(PgClientFactory clientFactory, ObjectMapper objectMapper,
                         String topic, Class<T> payloadType, PeeGeeQMetrics metrics) {
        this.clientFactory = clientFactory;
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
        // Poll for messages every 2 seconds
        scheduler.scheduleWithFixedDelay(this::processAvailableMessages, 2, 2, TimeUnit.SECONDS);
    }

    private void processAvailableMessages() {
        if (!subscribed.get() || closed.get()) {
            return;
        }

        try {
            // Get DataSource through connection manager
            DataSource dataSource = clientFactory.getConnectionManager().getOrCreateDataSource(
                "outbox-consumer",
                clientFactory.getConnectionConfig("peegeeq-main"),
                clientFactory.getPoolConfig("peegeeq-main")
            );

            try (Connection conn = dataSource.getConnection()) {
                // Select and lock a message for processing
                String sql = """
                    UPDATE outbox 
                    SET status = 'PROCESSING', processing_started_at = ?
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
                    stmt.setTimestamp(1, Timestamp.from(OffsetDateTime.now().toInstant()));
                    stmt.setString(2, topic);
                    
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            String messageId = rs.getString("id");
                            String payloadJson = rs.getString("payload");
                            Timestamp createdAt = rs.getTimestamp("created_at");
                            
                            try {
                                T payload = objectMapper.readValue(payloadJson, payloadType);
                                Message<T> message = new OutboxMessage<>(messageId, payload, 
                                    createdAt.toInstant(), null);
                                
                                logger.debug("Processing message {} from topic {}", messageId, topic);
                                
                                // Record metrics
                                if (metrics != null) {
                                    metrics.recordMessageReceived(topic);
                                }
                                
                                // Process the message
                                Instant processingStart = Instant.now();
                                CompletableFuture<Void> processingFuture = messageHandler.handle(message);
                                
                                processingFuture
                                    .thenRun(() -> {
                                        // Record successful processing metrics
                                        if (metrics != null) {
                                            Duration processingTime = Duration.between(processingStart, Instant.now());
                                            metrics.recordMessageProcessed(topic, processingTime);
                                        }
                                        
                                        // Message processed successfully - delete it
                                        deleteMessage(messageId);
                                    })
                                    .exceptionally(error -> {
                                        logger.warn("Message processing failed for {}: {}", messageId, error.getMessage());
                                        
                                        // Record failed message metrics
                                        if (metrics != null) {
                                            metrics.recordMessageFailed(topic, error.getClass().getSimpleName());
                                        }
                                        
                                        // Reset message status for retry
                                        resetMessageStatus(messageId);
                                        return null;
                                    });
                                    
                            } catch (Exception e) {
                                logger.error("Failed to deserialize message {}: {}", messageId, e.getMessage());
                                deleteMessage(messageId); // Delete malformed messages
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Error processing outbox messages for topic {}: {}", topic, e.getMessage());
        }
    }

    private void deleteMessage(String messageId) {
        try {
            // Get DataSource through connection manager
            DataSource dataSource = clientFactory.getConnectionManager().getOrCreateDataSource(
                "outbox-consumer",
                clientFactory.getConnectionConfig("peegeeq-main"),
                clientFactory.getPoolConfig("peegeeq-main")
            );

            try (Connection conn = dataSource.getConnection()) {
                String sql = "DELETE FROM outbox WHERE id = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, messageId);
                    stmt.executeUpdate();
                    logger.debug("Deleted processed message: {}", messageId);
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to delete message {}: {}", messageId, e.getMessage());
        }
    }

    private void resetMessageStatus(String messageId) {
        try {
            // Get DataSource through connection manager
            DataSource dataSource = clientFactory.getConnectionManager().getOrCreateDataSource(
                "outbox-consumer",
                clientFactory.getConnectionConfig("peegeeq-main"),
                clientFactory.getPoolConfig("peegeeq-main")
            );

            try (Connection conn = dataSource.getConnection()) {
                String sql = "UPDATE outbox SET status = 'PENDING', processing_started_at = NULL WHERE id = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, messageId);
                    stmt.executeUpdate();
                    logger.debug("Reset message status for retry: {}", messageId);
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to reset message status {}: {}", messageId, e.getMessage());
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
