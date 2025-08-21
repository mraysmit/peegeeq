package dev.mars.peegeeq.pgqueue;

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


import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.MessageHandler;

import dev.mars.peegeeq.api.messaging.SimpleMessage;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.metrics.PeeGeeQMetrics;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.pgclient.PgConnection;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Native PostgreSQL queue message consumer.
 * 
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
public class PgNativeQueueConsumer<T> implements dev.mars.peegeeq.api.messaging.MessageConsumer<T> {
    private static final Logger logger = LoggerFactory.getLogger(PgNativeQueueConsumer.class);

    private final VertxPoolAdapter poolAdapter;
    private final ObjectMapper objectMapper;
    private final String topic;
    private final Class<T> payloadType;
    private final String notifyChannel;
    private final PeeGeeQMetrics metrics;
    private final PeeGeeQConfiguration configuration;
    private final AtomicBoolean subscribed = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private MessageHandler<T> messageHandler;
    private PgConnection subscriber;
    private ScheduledExecutorService scheduler;

    public PgNativeQueueConsumer(VertxPoolAdapter poolAdapter, ObjectMapper objectMapper,
                                String topic, Class<T> payloadType, PeeGeeQMetrics metrics) {
        this(poolAdapter, objectMapper, topic, payloadType, metrics, null);
    }

    public PgNativeQueueConsumer(VertxPoolAdapter poolAdapter, ObjectMapper objectMapper,
                                String topic, Class<T> payloadType, PeeGeeQMetrics metrics,
                                PeeGeeQConfiguration configuration) {
        this.poolAdapter = poolAdapter;
        this.objectMapper = objectMapper;
        this.topic = topic;
        this.payloadType = payloadType;
        this.notifyChannel = "queue_" + topic;
        this.metrics = metrics;
        this.configuration = configuration;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "native-queue-consumer-" + topic);
            t.setDaemon(true);
            return t;
        });
        logger.info("Created native queue consumer for topic: {} with configuration: {}",
            topic, configuration != null ? "enabled" : "disabled");
    }
    
    @Override
    public void subscribe(MessageHandler<T> handler) {
        if (closed.get()) {
            throw new IllegalStateException("Consumer is closed");
        }
        
        if (subscribed.compareAndSet(false, true)) {
            this.messageHandler = handler;
            startListening();
            startPolling();
            logger.info("Subscribed to topic: {}", topic);
        } else {
            throw new IllegalStateException("Already subscribed");
        }
    }
    
    @Override
    public void unsubscribe() {
        if (subscribed.compareAndSet(true, false)) {
            stopListening();
            this.messageHandler = null;
            logger.info("Unsubscribed from topic: {}", topic);
        }
    }
    
    private void startListening() {
        try {
            final Pool pool = poolAdapter.getPool() != null ?
                poolAdapter.getPool() :
                poolAdapter.createPool(null, "native-queue");

            // Create a dedicated connection for LISTEN/NOTIFY
            pool.getConnection()
                .onSuccess(connection -> {
                    // Cast to PgConnection for notification support
                    PgConnection pgConnection = (PgConnection) connection;

                    // Execute LISTEN command (quote channel name to handle special characters)
                    pgConnection.query("LISTEN \"" + notifyChannel + "\"")
                        .execute()
                        .onSuccess(result -> {
                            logger.info("Started listening on channel: {}", notifyChannel);

                            // Set up notification handler
                            pgConnection.notificationHandler(notification -> {
                                if (notifyChannel.equals(notification.getChannel())) {
                                    logger.debug("Received notification on channel: {}", notifyChannel);
                                    // Process messages immediately when notified
                                    processAvailableMessages();
                                }
                            });

                            // Store the connection for cleanup
                            this.subscriber = pgConnection;
                        })
                        .onFailure(error -> {
                            logger.error("Failed to start listening on channel {}: {}", notifyChannel, error.getMessage());
                            pgConnection.close();
                            // Fall back to polling only
                        });
                })
                .onFailure(error -> {
                    logger.error("Failed to get connection for LISTEN on channel {}: {}", notifyChannel, error.getMessage());
                    // Fall back to polling only
                });

        } catch (Exception e) {
            logger.error("Error starting listener for topic {}: {}", topic, e.getMessage());
        }
    }
    
    private void stopListening() {
        if (subscriber != null) {
            try {
                // Execute UNLISTEN command before closing (quote channel name)
                subscriber.query("UNLISTEN \"" + notifyChannel + "\"")
                    .execute()
                    .onComplete(result -> {
                        if (subscriber != null) {
                            subscriber.close();
                            logger.info("Stopped listening on channel: {}", notifyChannel);
                        }
                    });
                subscriber = null;
            } catch (Exception e) {
                logger.warn("Error stopping listener: {}", e.getMessage());
                if (subscriber != null) {
                    subscriber.close();
                    subscriber = null;
                }
            }
        }
    }
    
    private void startPolling() {
        // Poll for messages every 1 second as backup to LISTEN/NOTIFY
        scheduler.scheduleWithFixedDelay(this::processAvailableMessages, 1, 1, TimeUnit.SECONDS);

        // Check for expired locks every 10 seconds
        scheduler.scheduleWithFixedDelay(this::releaseExpiredLocks, 10, 10, TimeUnit.SECONDS);
    }
    
    private void processAvailableMessages() {
        if (!subscribed.get() || messageHandler == null) {
            return;
        }
        
        try {
            final Pool pool = poolAdapter.getPool() != null ?
                poolAdapter.getPool() :
                poolAdapter.createPool(null, "native-queue");
            
            // Use advisory lock to ensure only one consumer processes a message
            String sql = """
                UPDATE queue_messages
                SET status = 'LOCKED',
                    lock_until = $1,
                    retry_count = retry_count + 1
                WHERE id = (
                    SELECT id FROM queue_messages
                    WHERE topic = $2 AND status = 'AVAILABLE'
                    AND pg_try_advisory_lock(hashtext(id::text))
                    ORDER BY priority DESC, created_at ASC
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING id, payload, headers, correlation_id, message_group, retry_count, created_at
                """;

            pool.preparedQuery(sql)
                .execute(Tuple.of(OffsetDateTime.now().plusSeconds(30), topic))
                .onSuccess(result -> {
                    if (result.size() > 0) {
                        Row row = result.iterator().next();
                        processMessage(row);
                    }
                })
                .onFailure(error -> {
                    logger.error("Error querying for messages in topic {}: {}", topic, error.getMessage());
                });
                
        } catch (Exception e) {
            logger.error("Error processing available messages for topic {}: {}", topic, e.getMessage());
        }
    }
    
    private void processMessage(Row row) {
        Long messageIdLong = row.getLong("id");
        String messageId = messageIdLong.toString();
        String payloadJson = row.getString("payload");
        String headersJson = row.getString("headers");
        String correlationId = row.getString("correlation_id");
        String messageGroup = row.getString("message_group");
        int retryCount = row.getInteger("retry_count");
        OffsetDateTime createdAtOffset = row.get(OffsetDateTime.class, "created_at");
        Instant createdAt = createdAtOffset.toInstant();
        
        try {
            T payload = objectMapper.readValue(payloadJson, payloadType);
            Map<String, String> headers = objectMapper.readValue(headersJson, new TypeReference<Map<String, String>>() {});
            
            Message<T> message = new SimpleMessage<>(
                messageId, topic, payload, headers, correlationId, messageGroup, createdAt
            );
            
            logger.debug("Processing message {} from topic {}", messageId, topic);

            // Record metrics
            if (metrics != null) {
                metrics.recordMessageReceived(topic);
            }

            // Get a local reference to the handler to avoid race conditions
            MessageHandler<T> handler = this.messageHandler;
            if (handler == null) {
                logger.warn("Message handler is null for message {}, consumer may have been unsubscribed", messageId);
                // Release the lock and return
                releaseAdvisoryLock(messageIdLong, messageId);
                return;
            }

            // Process the message
            Instant processingStart = Instant.now();
            CompletableFuture<Void> processingFuture = handler.handle(message);

            processingFuture
                .thenRun(() -> {
                    // Record successful processing metrics
                    if (metrics != null) {
                        Duration processingTime = Duration.between(processingStart, Instant.now());
                        metrics.recordMessageProcessed(topic, processingTime);
                    }

                    // Message processed successfully - delete it
                    deleteMessage(messageIdLong, messageId);
                })
                .exceptionally(error -> {
                    logger.warn("Message processing failed for {}: {}", messageId, error.getMessage());

                    // Record failed message metrics
                    if (metrics != null) {
                        metrics.recordMessageFailed(topic, error.getClass().getSimpleName());
                    }

                    handleProcessingFailure(messageIdLong, messageId, retryCount, error);
                    return null;
                });

        } catch (Exception e) {
            logger.error("Error deserializing message {}: {}", messageId, e.getMessage());
            handleProcessingFailure(messageIdLong, messageId, retryCount, e);
        }
    }

    private void deleteMessage(Long messageIdLong, String messageId) {
        try {
            final Pool pool = poolAdapter.getPool() != null ?
                poolAdapter.getPool() :
                poolAdapter.createPool(null, "native-queue");
            
            String sql = "DELETE FROM queue_messages WHERE id = $1";
            
            pool.preparedQuery(sql)
                .execute(Tuple.of(messageIdLong))
                .onSuccess(result -> {
                    logger.debug("Deleted processed message: {}", messageId);
                    // Release advisory lock
                    releaseAdvisoryLock(messageIdLong, messageId);
                })
                .onFailure(error -> {
                    logger.error("Failed to delete message {}: {}", messageId, error.getMessage());
                    releaseAdvisoryLock(messageIdLong, messageId);
                });

        } catch (Exception e) {
            logger.error("Error deleting message {}: {}", messageId, e.getMessage());
            releaseAdvisoryLock(messageIdLong, messageId);
        }
    }

    private void handleProcessingFailure(Long messageIdLong, String messageId, int retryCount, Throwable error) {
        try {
            final Pool pool = poolAdapter.getPool() != null ?
                poolAdapter.getPool() :
                poolAdapter.createPool(null, "native-queue");
            
            // Check if we should retry or move to dead letter queue
            int maxRetries = configuration != null ?
                configuration.getQueueConfig().getMaxRetries() : 3; // Use configuration or fallback to 3
            
            if (retryCount >= maxRetries) {
                // Move to dead letter queue
                moveToDeadLetterQueue(messageIdLong, messageId, error.getMessage());
            } else {
                // Reset status for retry
                String sql = "UPDATE queue_messages SET status = 'AVAILABLE', lock_until = NULL WHERE id = $1";
                
                pool.preparedQuery(sql)
                    .execute(Tuple.of(messageIdLong))
                    .onSuccess(result -> {
                        logger.debug("Reset message {} for retry (attempt {})", messageId, retryCount);
                        releaseAdvisoryLock(messageIdLong, messageId);
                    })
                    .onFailure(updateError -> {
                        logger.error("Failed to reset message {} for retry: {}", messageId, updateError.getMessage());
                        releaseAdvisoryLock(messageIdLong, messageId);
                    });
            }
            
        } catch (Exception e) {
            logger.error("Error handling processing failure for message {}: {}", messageId, e.getMessage());
            releaseAdvisoryLock(messageIdLong, messageId);
        }
    }

    private void moveToDeadLetterQueue(Long messageIdLong, String messageId, String errorMessage) {
        try {
            final Pool pool = poolAdapter.getPool() != null ?
                poolAdapter.getPool() :
                poolAdapter.createPool(null, "native-queue");

            logger.warn("Message {} exceeded retry limit, moving to dead letter queue: {}", messageId, errorMessage);

            // First, get the message details to move to dead letter queue table
            String selectSql = """
                SELECT payload, headers, correlation_id, message_group, retry_count, created_at
                FROM queue_messages
                WHERE id = $1
                """;

            pool.preparedQuery(selectSql)
                .execute(Tuple.of(messageIdLong))
                .onSuccess(selectResult -> {
                    if (selectResult.size() > 0) {
                        Row row = selectResult.iterator().next();
                        String payload = row.getString("payload");
                        String headers = row.getString("headers");
                        String correlationId = row.getString("correlation_id");
                        String messageGroup = row.getString("message_group");
                        int retryCount = row.getInteger("retry_count");
                        OffsetDateTime createdAtOffset = row.get(OffsetDateTime.class, "created_at");

                        // Insert into dead_letter_queue table
                        String insertSql = """
                            INSERT INTO dead_letter_queue (
                                original_table, original_id, topic, payload, headers,
                                correlation_id, message_group, retry_count, failure_reason,
                                failed_at, original_created_at
                            ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)
                            """;

                        pool.preparedQuery(insertSql)
                            .execute(Tuple.of(
                                "queue_messages", messageIdLong, topic, payload, headers,
                                correlationId, messageGroup, retryCount, errorMessage,
                                OffsetDateTime.now(), createdAtOffset
                            ))
                            .onSuccess(insertResult -> {
                                // Now delete from queue_messages
                                deleteMessage(messageIdLong, messageId);

                                logger.info("Moved message {} to dead letter queue", messageId);

                                // Record dead letter metrics
                                if (metrics != null) {
                                    metrics.recordMessageDeadLettered(topic, errorMessage);
                                }
                            })
                            .onFailure(insertError -> {
                                logger.error("Failed to insert message {} into dead letter queue: {}", messageId, insertError.getMessage());
                                // As fallback, just delete the message
                                deleteMessage(messageIdLong, messageId);
                            });
                    } else {
                        logger.warn("Message {} not found when trying to move to dead letter queue", messageId);
                        releaseAdvisoryLock(messageIdLong, messageId);
                    }
                })
                .onFailure(selectError -> {
                    logger.error("Failed to select message {} for dead letter queue: {}", messageId, selectError.getMessage());
                    // As fallback, just delete the message
                    deleteMessage(messageIdLong, messageId);
                });

        } catch (Exception e) {
            logger.error("Error moving message {} to dead letter queue: {}", messageId, e.getMessage());
            // As fallback, just delete the message
            deleteMessage(messageIdLong, messageId);
        }
    }
    
    private void releaseAdvisoryLock(Long messageIdLong, String messageId) {
        // Don't attempt to release locks if the consumer is closed
        if (closed.get()) {
            logger.debug("Skipping advisory lock release for message {} - consumer is closed", messageId);
            return;
        }

        try {
            final Pool pool = poolAdapter.getPool() != null ?
                poolAdapter.getPool() :
                poolAdapter.createPool(null, "native-queue");

            String sql = "SELECT pg_advisory_unlock(hashtext($1))";

            pool.preparedQuery(sql)
                .execute(Tuple.of(messageId))
                .onSuccess(result -> {
                    logger.debug("Released advisory lock for message: {}", messageId);
                })
                .onFailure(error -> {
                    // Only log as debug if consumer is closed to reduce noise during shutdown
                    if (closed.get()) {
                        logger.debug("Failed to release advisory lock for message {} during shutdown: {}", messageId, error.getMessage());
                    } else {
                        logger.warn("Failed to release advisory lock for message {}: {}", messageId, error.getMessage());
                    }
                });

        } catch (Exception e) {
            // Only log as debug if consumer is closed to reduce noise during shutdown
            if (closed.get()) {
                logger.debug("Error releasing advisory lock for message {} during shutdown: {}", messageId, e.getMessage());
            } else {
                logger.warn("Error releasing advisory lock for message {}: {}", messageId, e.getMessage());
            }
        }
    }

    private void releaseExpiredLocks() {
        try {
            final Pool pool = poolAdapter.getPool() != null ?
                poolAdapter.getPool() :
                poolAdapter.createPool(null, "native-queue");

            // Release messages where lock_until has expired
            String sql = """
                UPDATE queue_messages
                SET status = 'AVAILABLE', lock_until = NULL
                WHERE topic = $1 AND status = 'LOCKED' AND lock_until < $2
                """;

            pool.preparedQuery(sql)
                .execute(Tuple.of(topic, OffsetDateTime.now()))
                .onSuccess(result -> {
                    if (result.rowCount() > 0) {
                        logger.debug("Released {} expired locks for topic: {}", result.rowCount(), topic);
                        // Process any newly available messages
                        processAvailableMessages();
                    }
                })
                .onFailure(error -> {
                    logger.warn("Failed to release expired locks for topic {}: {}", topic, error.getMessage());
                });

        } catch (Exception e) {
            logger.warn("Error releasing expired locks for topic {}: {}", topic, e.getMessage());
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            unsubscribe();
            stopListening();
            
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
            
            logger.info("Closed native queue consumer for topic: {}", topic);
        }
    }
}
