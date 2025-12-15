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
package dev.mars.peegeeq.pgqueue;
import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.MessageHandler;
import dev.mars.peegeeq.api.messaging.SimpleMessage;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.metrics.PeeGeeQMetrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgConnection;
import io.vertx.pgclient.PgException;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;

import io.vertx.sqlclient.Tuple;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
    private final ConsumerConfig consumerConfig;
    private final AtomicBoolean subscribed = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicInteger pendingLockOperations = new AtomicInteger(0);
    private final AtomicInteger inFlightOperations = new AtomicInteger(0);
    private final AtomicInteger processingInFlight = new AtomicInteger(0);


    private MessageHandler<T> messageHandler;
    private PgConnection subscriber;
    // Reconnect/backoff state for LISTEN connection (dedicated, non-pooled)
    private long listenReconnectTimerId = -1;
    private int listenBackoffMs = 1000;
    private long pollingTimerId = -1;
    private long cleanupTimerId = -1;

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
        this.consumerConfig = null;
        // Determine consumer threads for logging (no dedicated executor; async operations)
        int consumerThreads = configuration != null ?
            configuration.getQueueConfig().getConsumerThreads() : 1;

        logger.debug("NATIVE-DEBUG: Created native queue consumer for topic: {} with configuration: {} (threads: {})",
            topic, configuration != null ? "enabled" : "disabled", consumerThreads);
        logger.info("Created native queue consumer for topic: {} with configuration: {} (threads: {})",
            topic, configuration != null ? "enabled" : "disabled", consumerThreads);
        logger.debug("NATIVE-DEBUG: Native queue consumer ready for subscription on topic: {}", topic);
        logger.info("Native queue consumer ready for subscription on topic: {}", topic);
    }

    public PgNativeQueueConsumer(VertxPoolAdapter poolAdapter, ObjectMapper objectMapper,
                                String topic, Class<T> payloadType, PeeGeeQMetrics metrics,
                                PeeGeeQConfiguration configuration, ConsumerConfig consumerConfig) {
        this.poolAdapter = poolAdapter;
        this.objectMapper = objectMapper;
        this.topic = topic;
        this.payloadType = payloadType;
        this.notifyChannel = "queue_" + topic;
        this.metrics = metrics;
        this.configuration = configuration;
        this.consumerConfig = consumerConfig;
        // Determine consumer threads for logging (no dedicated executor; async operations)
        int consumerThreads = consumerConfig != null ? consumerConfig.getConsumerThreads() :
            (configuration != null ? configuration.getQueueConfig().getConsumerThreads() : 1);

        logger.debug("NATIVE-DEBUG: Created native queue consumer for topic: {} with consumer mode: {} (threads: {})",
            topic, consumerConfig != null ? consumerConfig.getMode() : "default", consumerThreads);
        logger.info("Created native queue consumer for topic: {} with consumer mode: {} (threads: {})",
            topic, consumerConfig != null ? consumerConfig.getMode() : "default", consumerThreads);

        logger.debug("NATIVE-DEBUG: Native queue consumer ready for subscription on topic: {}", topic);
        logger.info("Native queue consumer ready for subscription on topic: {}", topic);
    }

    @Override
    public void subscribe(MessageHandler<T> handler) {
        logger.debug("NATIVE-DEBUG: Subscribe called for topic: {}, closed: {}, subscribed: {}", topic, closed.get(), subscribed.get());
        logger.info("Subscribe called for topic: {}, closed: {}, subscribed: {}", topic, closed.get(), subscribed.get());

        if (closed.get()) {
            logger.debug("NATIVE-DEBUG: Cannot subscribe - consumer is closed for topic: {}", topic);
            logger.error("Cannot subscribe - consumer is closed for topic: {}", topic);
            throw new IllegalStateException("Consumer is closed");
        }

        if (subscribed.compareAndSet(false, true)) {
            logger.debug("NATIVE-DEBUG: Starting subscription for topic: {}", topic);
            logger.info("Starting subscription for topic: {}", topic);
            this.messageHandler = handler;

            try {
                // Determine consumer mode - use ConsumerConfig if available, otherwise default to HYBRID
                ConsumerMode mode = consumerConfig != null ? consumerConfig.getMode() : ConsumerMode.HYBRID;
                logger.debug("NATIVE-DEBUG: Using consumer mode: {} for topic: {}", mode, topic);
                logger.info("Using consumer mode: {} for topic: {}", mode, topic);

                // Start LISTEN/NOTIFY based on mode
                if (mode == ConsumerMode.LISTEN_NOTIFY_ONLY || mode == ConsumerMode.HYBRID) {
                    logger.debug("NATIVE-DEBUG: About to start listening for topic: {}", topic);
                    startListening();
                    logger.debug("NATIVE-DEBUG: Started listening for topic: {}", topic);
                    logger.info("Started listening for topic: {}", topic);
                } else {
                    logger.debug("NATIVE-DEBUG: Skipping LISTEN/NOTIFY setup for POLLING_ONLY mode on topic: {}", topic);
                    logger.info("Skipping LISTEN/NOTIFY setup for POLLING_ONLY mode on topic: {}", topic);
                }

                // Start polling based on mode
                if (mode == ConsumerMode.POLLING_ONLY || mode == ConsumerMode.HYBRID) {
                    logger.debug("NATIVE-DEBUG: About to start polling for topic: {}", topic);
                    startPolling();
                    logger.debug("NATIVE-DEBUG: Started polling for topic: {}", topic);
                    logger.info("Started polling for topic: {}", topic);
                } else {
                    logger.debug("NATIVE-DEBUG: Skipping polling setup for LISTEN_NOTIFY_ONLY mode on topic: {}", topic);
                    logger.info("Skipping polling setup for LISTEN_NOTIFY_ONLY mode on topic: {}", topic);
                }

                logger.debug("NATIVE-DEBUG: Subscribed to topic: {} with mode: {}", topic, mode);
                logger.info("Subscribed to topic: {} with mode: {}", topic, mode);
            } catch (Exception e) {
                logger.debug("NATIVE-DEBUG: Error during subscription for topic: {} - {}", topic, e.getMessage());
                logger.error("Error during subscription for topic: {}", topic, e);
                throw e;
            }
        } else {
            logger.debug("NATIVE-DEBUG: Cannot subscribe - consumer is already subscribed for topic: {}", topic);
            logger.error("Cannot subscribe - consumer is already subscribed for topic: {}", topic);
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
        logger.debug("NATIVE-DEBUG: startListening() called for topic: {}, notifyChannel: {}", topic, notifyChannel);
        if (closed.get()) {
            logger.debug("NATIVE-DEBUG: Consumer is closed, skipping LISTEN setup for topic: {}", topic);
            return;
        }

        Vertx vertx = poolAdapter.getVertx();
        if (vertx == null) {
            logger.error("No Vert.x instance available from pool adapter; cannot start LISTEN for topic: {}", topic);
            return;
        }



        poolAdapter.connectDedicated()
            .compose(conn -> conn.query("LISTEN \"" + notifyChannel + "\"")
                                 .execute()
                                 .map(conn))
            .onSuccess(conn -> {
                if (closed.get()) {
                    conn.close();
                    return;
                }
                // Reset backoff on successful connect
                listenBackoffMs = 1000;
                this.subscriber = conn;
                logger.info("Started listening on channel: {}", notifyChannel);

                conn.notificationHandler(notification -> {
                    if (!closed.get() && notifyChannel.equals(notification.getChannel())) {
                        logger.debug("NATIVE-DEBUG: Received notification on channel: {}", notifyChannel);
                        processAvailableMessages();
                    }
                });
                conn.closeHandler(v -> {
                    if (closed.get()) {
                        logger.debug("LISTEN connection closed during shutdown for channel: {}", notifyChannel);
                    } else {
                        logger.error("LISTEN connection closed unexpectedly for channel: {}", notifyChannel);
                    }
                    this.subscriber = null;
                    scheduleListenReconnect();
                });
                conn.exceptionHandler(err -> {
                    if (closed.get()) {
                        logger.debug("LISTEN error during shutdown on channel {}: {}", notifyChannel, err.getMessage());
                    } else {
                        logger.error("LISTEN error on channel {}: {}", notifyChannel, err.getMessage());
                    }
                    try { conn.close(); } catch (Exception ignore) {}
                });

                ConsumerMode mode = consumerConfig != null ? consumerConfig.getMode() : ConsumerMode.HYBRID;
                if (mode == ConsumerMode.LISTEN_NOTIFY_ONLY) {
                    processAvailableMessages();
                }
            })
            .onFailure(err -> {
                if (closed.get()) {
                    logger.debug("Failed to start LISTEN during shutdown on channel {}: {}", notifyChannel, err.getMessage());
                } else {
                    logger.error("Failed to start LISTEN on channel {}: {}", notifyChannel, err.getMessage());
                }
                scheduleListenReconnect();
            });
    }

    private void stopListening() {
        Vertx vertx = poolAdapter.getVertx();
        if (vertx != null && listenReconnectTimerId != -1) {
            vertx.cancelTimer(listenReconnectTimerId);
            listenReconnectTimerId = -1;
        }
        if (subscriber != null) {
            PgConnection connectionToClose = subscriber;
            subscriber = null; // Clear reference first to prevent new operations

            logger.debug("NATIVE-DEBUG: Executing UNLISTEN for channel: {}", notifyChannel);
            connectionToClose
                .query("UNLISTEN \"" + notifyChannel + "\"")
                .execute()
                .onComplete(ar -> {
                    try { connectionToClose.close(); } catch (Exception ignore) {}
                    if (ar.succeeded()) {
                        logger.info("Stopped listening on channel: {}", notifyChannel);
                    } else {
                        // During shutdown, UNLISTEN errors are expected (connection may already be closed)
                        logger.debug("Error during UNLISTEN for channel {}: {}", notifyChannel, ar.cause().getMessage());
                    }
                });
        }
    }

    private void scheduleListenReconnect() {
        if (closed.get()) return;
        Vertx vertx = poolAdapter.getVertx();
        if (vertx == null) {
            logger.error("Cannot schedule LISTEN reconnect: Vert.x is null");
            return;
        }
        long delay = listenBackoffMs;
        listenReconnectTimerId = vertx.setTimer(delay, id -> {
            if (closed.get()) return;
            logger.info("Reconnecting LISTEN on channel {} after {} ms", notifyChannel, delay);
            startListening();
        });
        // Exponential backoff capped at 30 seconds
        listenBackoffMs = Math.min(listenBackoffMs * 2, 30_000);
    }

    private void startPolling() {
        // Get polling interval from ConsumerConfig first, then PeeGeeQConfiguration, then default
        Duration pollingInterval;
        if (consumerConfig != null) {
            pollingInterval = consumerConfig.getPollingInterval();
        } else if (configuration != null) {
            pollingInterval = configuration.getQueueConfig().getPollingInterval();
        } else {
            pollingInterval = Duration.ofSeconds(5); // Use new default
        }

        long pollingIntervalMs = pollingInterval.toMillis();

        Vertx vertx = poolAdapter.getVertx();
        if (vertx == null && Vertx.currentContext() != null) {
            vertx = Vertx.currentContext().owner();
        }
        if (vertx == null) {
            logger.error("No Vert.x instance available; cannot start polling for topic: {}", topic);
            return;
        }

        // Periodic polling using Vert.x timers
        pollingTimerId = vertx.setPeriodic(pollingIntervalMs, id -> {
            if (closed.get()) return;
            try {
                logger.debug("NATIVE-DEBUG: Polling for messages on topic: {} (interval: {}ms)", topic, pollingIntervalMs);
                processAvailableMessages();
            } catch (Exception e) {
                if (!closed.get()) {
                    logger.error("Error in scheduled message processing for topic {}: {}", topic, e.getMessage());
                }
            }
        });

        // Expired lock cleanup every 10 seconds
        cleanupTimerId = vertx.setPeriodic(10_000, id -> {
            if (closed.get()) return;
            try {
                releaseExpiredLocks();
            } catch (Exception e) {
                if (!closed.get()) {
                    logger.error("Error in scheduled expired locks cleanup for topic {}: {}", topic, e.getMessage());
                }
            }
        });

        logger.info("Started polling for topic {} with interval: {}", topic, pollingInterval);
    }

    private void processAvailableMessages() {
        logger.debug("NATIVE-DEBUG: processAvailableMessages() called for topic: {}", topic);
        // Critical fix: Check if consumer is closed to prevent infinite retry loops during shutdown
        if (!subscribed.get() || messageHandler == null || closed.get()) {
            logger.debug("NATIVE-DEBUG: Skipping message processing - subscribed: {}, messageHandler: {}, closed: {} for topic {}",
                subscribed.get(), (messageHandler != null), closed.get(), topic);
            return;
        }
        logger.debug("NATIVE-DEBUG: Consumer is active, proceeding with message processing for topic: {}", topic);

        try {
            final Pool pool = poolAdapter.getPoolOrThrow();

            // CRITICAL FIX: Check if pool is closed before attempting operations
            // This prevents RejectedExecutionException during shutdown
            if (pool == null) {
                logger.debug("NATIVE-DEBUG: Pool is null, skipping message processing for topic: {}", topic);
                return;
            }

            // Determine batch size: prefer ConsumerConfig, then PeeGeeQConfiguration, else default 1
            int batchSize = (consumerConfig != null) ? consumerConfig.getBatchSize()
                : (configuration != null ? configuration.getQueueConfig().getBatchSize() : 1);

            // Bounded concurrency: do not claim more than remaining processing capacity
            int maxThreads;
            if (consumerConfig != null && consumerConfig.getConsumerThreads() > 0) {
                maxThreads = consumerConfig.getConsumerThreads();
            } else if (configuration != null && configuration.getQueueConfig().getConsumerThreads() > 0) {
                maxThreads = configuration.getQueueConfig().getConsumerThreads();
            } else {
                maxThreads = 1;
            }
            int remainingCapacity = Math.max(0, maxThreads - processingInFlight.get());
            if (remainingCapacity <= 0) {
                logger.debug("NATIVE-DEBUG: No remaining capacity (processingInFlight={} >= maxThreads={}), skip claim",
                    processingInFlight.get(), maxThreads);
                return;
            }
            int effectiveBatch = Math.min(batchSize, remainingCapacity);

            // Batch processing approach following outbox pattern
            // Use IN clause with LIMIT to process multiple messages in batch
            String sql = """
                WITH c AS (
                    SELECT id FROM queue_messages
                    WHERE topic = $1 AND status = 'AVAILABLE' AND visible_at <= now()
                    ORDER BY priority DESC, created_at ASC
                    LIMIT $2
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE queue_messages q
                SET status = 'LOCKED', lock_until = now() + make_interval(secs => $3)
                FROM c
                WHERE q.id = c.id
                RETURNING q.id, q.payload, q.headers, q.correlation_id, q.message_group, q.retry_count, q.created_at
                """;

            // Use injected Vert.x instance; avoid creating new Vert.x inside existing contexts
            Vertx vt = poolAdapter.getVertx();
            if (vt == null && Vertx.currentContext() != null) {
                vt = Vertx.currentContext().owner();
            }
            if (vt == null) {
                logger.debug("NATIVE-DEBUG: No Vert.x instance available, skipping message processing for topic: {}", topic);
                return;
            }
            final Vertx vertx = vt;

            // Execute batch processing with proper parameters and retry for transient DB errors
            executeOnVertxContext(vertx, () -> withRetry(vertx,
                () -> pool.preparedQuery(sql)
                    .execute(Tuple.of(topic, effectiveBatch, 30)),
                3, 50)
                .onSuccess(result -> {
                    // Double-check if consumer is still active after async operation
                    if (closed.get()) {
                        logger.debug("NATIVE-DEBUG: Consumer closed during message processing, ignoring results for topic: {}", topic);
                        return;
                    }

                    if (result.size() > 0) {
                        logger.debug("NATIVE-DEBUG: Processing {} messages for topic {}", result.size(), topic);
                        logger.debug("Processing {} messages for topic {}", result.size(), topic);

                        // CRITICAL FIX: Process messages without transactions since locking is already committed
                        for (Row row : result) {
                            processMessageWithoutTransaction(row);
                        }
                    } else {
                        logger.debug("NATIVE-DEBUG: No messages found for topic {}", topic);
                        logger.debug("No messages found for topic {}", topic);
                    }
                })
                .onFailure(error -> {
                    logger.debug("NATIVE-DEBUG: Error querying messages for topic {}: {}", topic, error.getMessage());
                    // CRITICAL FIX: Handle shutdown-related errors gracefully following established pattern
                    if (closed.get() && (error.getMessage().contains("Pool closed") ||
                                        error.getMessage().contains("event executor terminated") ||
                                        error.getMessage().contains("Connection closed"))) {
                        logger.debug("Expected error during shutdown for topic {}: {}", topic, error.getMessage());
                    } else {
                        logger.error("Error querying messages for topic {}: {}", topic, error.getMessage());
                    }
                }))
            .onFailure(error -> {
                // Critical fix: Handle various error conditions gracefully following established pattern
                String errorMessage = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();

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
                        logger.error("Error processing messages for topic {}: {}", topic, errorMessage);
                    }
                }
            });

        } catch (Exception e) {
            // Critical fix: Handle executor termination and other errors gracefully
            String errorMessage = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();

            if (closed.get()) {
                // During shutdown, errors are expected
                logger.debug("Expected error during shutdown for topic {}: {}", topic, errorMessage);
            } else if (errorMessage.contains("event executor terminated") ||
                       errorMessage.contains("RejectedExecutionException")) {
                // Executor termination - log as warning and stop processing
                logger.warn("Executor terminated for topic {} - stopping message processing: {}", topic, errorMessage);
                // Mark as closed to prevent further processing attempts
                closed.set(true);
            } else {
                logger.error("Error processing available messages for topic {}: {}", topic, errorMessage);
            }
        }
    }

    /**
     * CRITICAL FIX: Process message without transaction since locking is already committed.
     * This prevents transaction rollback from undoing the LOCKED status.
     */
    private void processMessageWithoutTransaction(Row row) {
        Long messageIdLong = row.getLong("id");
        String messageId = messageIdLong.toString();

        // Critical fix: Check if consumer is closed before processing message
        if (closed.get()) {
            logger.debug("Skipping message processing - consumer is closed");
            return;
        }

        try {
            // Parse payload and headers (updated for JSONB objects)
            JsonObject payload = row.getJsonObject("payload");
            JsonObject headers = row.getJsonObject("headers");

            // Parse headers and payload (updated for JSONB objects)
            T parsedPayload = parsePayloadFromJsonObject(payload);
            Map<String, String> headerMap = parseHeadersFromJsonObject(headers);

            // Get message handler
            MessageHandler<T> handler = this.messageHandler;
            if (handler == null) {
                logger.error("Message handler is null for message {}, consumer may have been unsubscribed", messageId);
                return;
            }

            // Create message (following existing pattern)
            Message<T> message = new SimpleMessage<>(
                messageId, topic, parsedPayload, headerMap, null, null, java.time.Instant.now()
            );

            // CRITICAL FIX: Process message asynchronously and wait for CompletableFuture
            try {
                long startTime = System.currentTimeMillis();

                // Increment processing concurrency before invoking handler
                processingInFlight.incrementAndGet();

                // Call handler and get CompletableFuture
                CompletableFuture<Void> processingFuture = handler.handle(message);

                // Wait for completion and handle success/failure
                processingFuture
                    .thenAccept(result -> {
                        long processingTime = System.currentTimeMillis() - startTime;
                        logger.debug("Message {} processed successfully", messageId);

                        // Record metrics for successful message processing
                        if (metrics != null) {
                            metrics.recordMessageReceived(topic);
                            metrics.recordMessageProcessed(topic, java.time.Duration.ofMillis(processingTime));
                        }

                        // Success: Delete message from queue using separate connection
                        deleteMessage(messageIdLong, messageId);
                        // Decrement processing concurrency on success
                        processingInFlight.decrementAndGet();
                    })
                    .exceptionally(processingError -> {
                        logger.error("Error processing message {}: {}", messageId, processingError.getMessage());

                        // Failure: Handle retry logic
                        int retryCount = row.getInteger("retry_count") != null ? row.getInteger("retry_count") : 0;
                        handleProcessingFailure(messageIdLong, messageId, retryCount + 1, processingError);
                        // Decrement processing concurrency on failure
                        processingInFlight.decrementAndGet();
                        return null;
                    });

            } catch (Exception processingError) {
                logger.error("Error calling message handler for {}: {}", messageId, processingError.getMessage());

                // Failure: Handle retry logic
                int retryCount = row.getInteger("retry_count") != null ? row.getInteger("retry_count") : 0;
                handleProcessingFailure(messageIdLong, messageId, retryCount + 1, processingError);
                // Decrement processing concurrency on synchronous failure
                processingInFlight.decrementAndGet();
            }

        } catch (Exception e) {
            logger.error("Error parsing message {}: {}", messageId, e.getMessage());
            int retryCount = row.getInteger("retry_count") != null ? row.getInteger("retry_count") : 0;
            handleProcessingFailure(messageIdLong, messageId, retryCount + 1, e);
        }
    }



    // Removed processMessageInThread - replaced with transaction-based processMessageWithTransaction

    private void deleteMessage(Long messageIdLong, String messageId) {
        // Critical fix: Don't attempt to delete messages if consumer is closed
        if (closed.get()) {
            logger.debug("Skipping message deletion for {} - consumer is closed", messageId);
            // Transaction-level advisory lock will be automatically released when transaction ends
            return;
        }

        // Track in-flight operation
        inFlightOperations.incrementAndGet();

        try {
            final Pool pool = poolAdapter.getPoolOrThrow();

            String sql = "DELETE FROM queue_messages WHERE id = $1";

            // CRITICAL FIX: Use synchronous deletion during shutdown to prevent race conditions
            if (closed.get()) {
                // Double-check after getting pool - if closed, skip deletion
                logger.debug("Consumer closed after getting pool, skipping message deletion for {}", messageId);
                inFlightOperations.decrementAndGet(); // Decrement counter
                return;
            }

            Vertx vt = poolAdapter.getVertx();
            if (vt == null && Vertx.currentContext() != null) {
                vt = Vertx.currentContext().owner();
            }

            if (vt == null) {
                // Fallback: execute without retry when Vert.x is not available
                pool.preparedQuery(sql)
                    .execute(Tuple.of(messageIdLong))
                    .onSuccess(result -> {
                        logger.debug("Deleted processed message: {}", messageId);
                        inFlightOperations.decrementAndGet(); // Decrement counter on success
                    })
                    .onFailure(error -> {
                        inFlightOperations.decrementAndGet(); // Decrement counter on failure
                        String errorMsg = error.getMessage();
                        if (closed.get() && (errorMsg.contains("Pool closed") ||
                                            errorMsg.contains("connection may have been lost") ||
                                            errorMsg.contains("Failed to read any response"))) {
                            logger.debug("Connection closed during message deletion for {} - this is expected during shutdown", messageId);
                        } else {
                            logger.error("Failed to delete message {}: {}", messageId, errorMsg);
                        }
                    });
            } else {
                final Vertx vertx = vt;
                withRetry(vertx,
                    () -> pool.preparedQuery(sql).execute(Tuple.of(messageIdLong)),
                    3, 50)
                    .onSuccess(result -> {
                        logger.debug("Deleted processed message: {}", messageId);
                        inFlightOperations.decrementAndGet();
                    })
                    .onFailure(error -> {
                        inFlightOperations.decrementAndGet();
                        String errorMsg = error.getMessage();
                        if (closed.get() && (errorMsg.contains("Pool closed") ||
                                            errorMsg.contains("connection may have been lost") ||
                                            errorMsg.contains("Failed to read any response"))) {
                            logger.debug("Connection closed during message deletion for {} - this is expected during shutdown", messageId);
                        } else {
                            logger.error("Failed to delete message {}: {}", messageId, errorMsg);
                        }
                    });
            }

        } catch (Exception e) {
            inFlightOperations.decrementAndGet(); // Decrement counter on exception
            // Handle exceptions during shutdown gracefully
            String errorMsg = e.getMessage();
            if (closed.get() && (errorMsg.contains("Pool closed") ||
                                errorMsg.contains("connection may have been lost") ||
                                errorMsg.contains("Failed to read any response"))) {
                logger.debug("Exception during message deletion for {} during shutdown - this is expected: {}", messageId, errorMsg);
            } else {
                logger.error("Error deleting message {}: {}", messageId, errorMsg);
            }
            // Transaction-level advisory lock will be automatically released when transaction ends
        }
    }

    private void handleProcessingFailure(Long messageIdLong, String messageId, int retryCount, Throwable error) {
        try {
            final Pool pool = poolAdapter.getPoolOrThrow();

            // Check if we should retry or move to dead letter queue
            int maxRetries = configuration != null ?
                configuration.getQueueConfig().getMaxRetries() : 3; // Use configuration or fallback to 3

            if (retryCount >= maxRetries) {
                // Move to dead letter queue
                moveToDeadLetterQueue(messageIdLong, messageId, error.getMessage());
            } else {
                // Reset status for retry and increment retry count
                String sql = "UPDATE queue_messages SET status = 'AVAILABLE', lock_until = NULL, retry_count = $2 WHERE id = $1";

                Vertx vt = poolAdapter.getVertx();
                if (vt == null && Vertx.currentContext() != null) {
                    vt = Vertx.currentContext().owner();
                }
                if (vt == null) {
                    pool.preparedQuery(sql)
                        .execute(Tuple.of(messageIdLong, retryCount))
                        .onSuccess(result -> {
                            logger.debug("Reset message {} for retry (attempt {})", messageId, retryCount);
                        })
                        .onFailure(updateError -> {
                            logger.error("Failed to reset message {} for retry: {}", messageId, updateError.getMessage());
                        });
                } else {
                    final Vertx vertx = vt;
                    withRetry(vertx,
                        () -> pool.preparedQuery(sql).execute(Tuple.of(messageIdLong, retryCount)),
                        3, 50)
                        .onSuccess(result -> {
                            logger.debug("Reset message {} for retry (attempt {})", messageId, retryCount);
                        })
                        .onFailure(updateError -> {
                            logger.error("Failed to reset message {} for retry: {}", messageId, updateError.getMessage());
                        });
                }
            }

        } catch (Exception e) {
            logger.error("Error handling processing failure for message {}: {}", messageId, e.getMessage());
            // Transaction-level advisory lock will be automatically released when transaction ends
        }
    }

    private void moveToDeadLetterQueue(Long messageIdLong, String messageId, String errorMessage) {
        try {
            final Pool pool = poolAdapter.getPoolOrThrow();

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
                        // CRITICAL FIX: Use JSONB objects instead of JSON strings for dead letter queue
                        JsonObject payload = row.getJsonObject("payload");
                        JsonObject headers = row.getJsonObject("headers");
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
                            ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, now(), $10)
                            """;

                        Vertx vt = poolAdapter.getVertx();
                        if (vt == null && Vertx.currentContext() != null) {
                            vt = Vertx.currentContext().owner();
                        }
                        final Vertx vertx = vt;
                        if (vertx == null) {
                            pool.preparedQuery(insertSql)
                                .execute(Tuple.of(
                                    "queue_messages", messageIdLong, topic, payload, headers,
                                    correlationId, messageGroup, retryCount, errorMessage,
                                    createdAtOffset
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
                            withRetry(vertx,
                                () -> pool.preparedQuery(insertSql).execute(Tuple.of(
                                    "queue_messages", messageIdLong, topic, payload, headers,
                                    correlationId, messageGroup, retryCount, errorMessage,
                                    createdAtOffset
                                )),
                                3, 50)
                                .onSuccess(insertResult -> {
                                    deleteMessage(messageIdLong, messageId);
                                    logger.info("Moved message {} to dead letter queue", messageId);
                                    if (metrics != null) {
                                        metrics.recordMessageDeadLettered(topic, errorMessage);
                                    }
                                })
                                .onFailure(insertError -> {
                                    logger.error("Failed to insert message {} into dead letter queue: {}", messageId, insertError.getMessage());
                                    deleteMessage(messageIdLong, messageId);
                                });
                        }
                    } else {
                        logger.error("Message {} not found when trying to move to dead letter queue", messageId);
                        // Transaction-level advisory lock will be automatically released when transaction ends
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

    // Advisory lock release method removed - using pg_try_advisory_xact_lock (transaction-level locks)
    // These are automatically released when transactions end, eliminating the need for manual release
    // This completely eliminates ExclusiveLock warnings by letting PostgreSQL handle cleanup

    private void releaseExpiredLocks() {
        // Critical fix: Don't attempt to release expired locks if consumer is closed
        if (closed.get()) {
            return;
        }

        try {
            final Pool pool = poolAdapter.getPoolOrThrow();

            // CRITICAL FIX: Just reset expired locks in database - don't manually release advisory locks
            // Advisory locks will be auto-released when connections are returned to pool
            String updateSql = """
                UPDATE queue_messages
                SET status = 'AVAILABLE', lock_until = NULL
                WHERE topic = $1 AND status = 'LOCKED' AND lock_until < now()
                """;

            Vertx vt = poolAdapter.getVertx();
            if (vt == null && Vertx.currentContext() != null) {
                vt = Vertx.currentContext().owner();
            }
            if (vt == null) {
                pool.preparedQuery(updateSql)
                    .execute(Tuple.of(topic))
                    .onSuccess(updateResult -> {
                        if (updateResult.rowCount() > 0) {
                            logger.debug("Reset {} expired locks for topic: {} - advisory locks will auto-release", updateResult.rowCount(), topic);
                            // Free capacity: consider stuck handlers dead after visibility timeout
                            processingInFlight.set(0);
                            processAvailableMessages();
                        }
                    })
                    .onFailure(error -> {
                        if (closed.get() && error.getMessage().contains("Pool closed")) {
                            logger.debug("Pool closed during shutdown for expired locks cleanup - this is expected");
                        } else {
                            logger.error("Failed to query expired locks for topic {}: {}", topic, error.getMessage());
                        }
                    });
            } else {
                final Vertx vertx = vt;
                withRetry(vertx,
                    () -> pool.preparedQuery(updateSql).execute(Tuple.of(topic)),
                    3, 50)
                    .onSuccess(updateResult -> {
                        if (updateResult.rowCount() > 0) {
                            logger.debug("Reset {} expired locks for topic: {} - advisory locks will auto-release", updateResult.rowCount(), topic);
                            // Free capacity: consider stuck handlers dead after visibility timeout
                            processingInFlight.set(0);
                            processAvailableMessages();
                        }
                    })
                    .onFailure(error -> {
                        if (closed.get() && error.getMessage().contains("Pool closed")) {
                            logger.debug("Pool closed during shutdown for expired locks cleanup - this is expected");
                        } else {
                            logger.error("Failed to query expired locks for topic {}: {}", topic, error.getMessage());
                        }
                    });
            }

        } catch (Exception e) {
            logger.error("Error releasing expired locks for topic {}: {}", topic, e.getMessage());
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            logger.info("Starting graceful shutdown of native queue consumer for topic: {}", topic);

            // Step 1: Stop accepting new work
            unsubscribe();
            stopListening();

            // Step 2: Cancel Vert.x timers for polling and cleanup
            Vertx vertx = poolAdapter.getVertx();
            if (vertx == null && Vertx.currentContext() != null) {
                vertx = Vertx.currentContext().owner();
            }
            if (vertx != null) {
                if (pollingTimerId != -1) {
                    vertx.cancelTimer(pollingTimerId);
                    pollingTimerId = -1;
                }
                if (cleanupTimerId != -1) {
                    vertx.cancelTimer(cleanupTimerId);
                    cleanupTimerId = -1;
                }
            }

            // Step 4: Wait for pending advisory lock operations to complete
            int waitCount = 0;
            while (pendingLockOperations.get() > 0 && waitCount < 50) { // Max 5 seconds
                try {
                    Thread.sleep(100); // Wait 100ms between checks
                    waitCount++;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            if (pendingLockOperations.get() > 0) {
                logger.warn("Shutdown proceeding with {} pending advisory lock operations", pendingLockOperations.get());
            } else {
                logger.debug("All advisory lock operations completed before shutdown");
            }

            // Step 5: Wait for in-flight operations (like message deletion) to complete
            waitCount = 0;
            while (inFlightOperations.get() > 0 && waitCount < 30) { // Max 3 seconds
                try {
                    Thread.sleep(100); // Wait 100ms between checks
                    waitCount++;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            if (inFlightOperations.get() > 0) {
                logger.warn("Shutdown proceeding with {} in-flight operations (message deletions may fail)", inFlightOperations.get());
            } else {
                logger.debug("All in-flight operations completed before shutdown");
            }

            logger.info("Completed graceful shutdown of native queue consumer for topic: {}", topic);
        }
    }

    /**
     * Executes a Future-returning operation on the Vert.x context.
     * This ensures that TransactionPropagation.CONTEXT works correctly by providing
     * the proper execution context for Vert.x operations.
     *
     * Following the exact pattern from peegeeq-outbox OutboxProducer with additional
     * safety checks to prevent RejectedExecutionException during shutdown.
     *
     * @param vertx The Vertx instance
     * @param operation The operation to execute that returns a Future
     * @return Future that completes when the operation completes
     */
    private static <T> Future<T> executeOnVertxContext(Vertx vertx, java.util.function.Supplier<Future<T>> operation) {
        // CRITICAL FIX: Check if Vert.x instance is null or closed before attempting operations
        if (vertx == null) {
            logger.debug("NATIVE-DEBUG: Vert.x instance is null, returning failed future");
            return Future.failedFuture("Vert.x instance is null");
        }

        try {
            Context context = vertx.getOrCreateContext();
            if (context == Vertx.currentContext()) {
                // Already on Vert.x context, execute directly
                try {
                    return operation.get();
                } catch (Exception e) {
                    // CRITICAL FIX: Handle RejectedExecutionException and other errors gracefully
                    if (e.getMessage() != null && (e.getMessage().contains("event executor terminated") ||
                                                  e.getMessage().contains("RejectedExecutionException"))) {
                        logger.debug("NATIVE-DEBUG: Event executor terminated during direct execution: {}", e.getMessage());
                        return Future.failedFuture("Event executor terminated");
                    }
                    return Future.failedFuture(e);
                }
            } else {
                // Execute on Vert.x context using runOnContext
                Promise<T> promise = Promise.promise();
                try {
                    context.runOnContext(v -> {
                        try {
                            operation.get()
                                .onSuccess(promise::complete)
                                .onFailure(promise::fail);
                        } catch (Exception e) {
                            // CRITICAL FIX: Handle exceptions during context execution
                            if (e.getMessage() != null && (e.getMessage().contains("event executor terminated") ||
                                                          e.getMessage().contains("RejectedExecutionException"))) {
                                logger.debug("NATIVE-DEBUG: Event executor terminated during context execution: {}", e.getMessage());
                                promise.fail("Event executor terminated");
                            } else {
                                promise.fail(e);
                            }
                        }
                    });
                } catch (Exception e) {
                    // CRITICAL FIX: Handle RejectedExecutionException when scheduling on context
                    if (e.getMessage() != null && (e.getMessage().contains("event executor terminated") ||
                                                  e.getMessage().contains("RejectedExecutionException"))) {
                        logger.debug("NATIVE-DEBUG: Event executor terminated when scheduling on context: {}", e.getMessage());
                        return Future.failedFuture("Event executor terminated");
                    }
                    return Future.failedFuture(e);
                }
                return promise.future();
            }
        } catch (Exception e) {
            // CRITICAL FIX: Handle any other exceptions during context creation
            if (e.getMessage() != null && (e.getMessage().contains("event executor terminated") ||
                                          e.getMessage().contains("RejectedExecutionException"))) {
                logger.debug("NATIVE-DEBUG: Event executor terminated during context creation: {}", e.getMessage());
                return Future.failedFuture("Event executor terminated");
            }
            return Future.failedFuture(e);
        }
    }
    /**
     * Determine if the failure is retryable based on PostgreSQL SQLSTATE codes.
     * Retries are applied for serialization failures (40001) and deadlocks (40P01).
     */
    private static boolean isRetryable(Throwable t) {
        if (t == null) return false;
        // Unwrap
        Throwable cause = t;
        while (cause.getCause() != null && !(cause instanceof PgException)) {
            cause = cause.getCause();
        }
        if (cause instanceof PgException) {
            String code = ((PgException) cause).getSqlState();
            return "40001".equals(code) || "40P01".equals(code);
        }
        return false;
    }

    /**
     * Execute an async operation with bounded retries and exponential backoff for retryable errors.
     */
    private static <T> Future<T> withRetry(Vertx vertx,
                                           java.util.function.Supplier<Future<T>> operation,
                                           int maxAttempts,
                                           long initialBackoffMs) {
        Promise<T> promise = Promise.promise();
        executeWithRetryAttempt(vertx, operation, maxAttempts, 1, initialBackoffMs, promise);
        return promise.future();
    }

    private static <T> void executeWithRetryAttempt(Vertx vertx,
                                                    java.util.function.Supplier<Future<T>> operation,
                                                    int maxAttempts,
                                                    int attempt,
                                                    long backoffMs,
                                                    Promise<T> promise) {
        try {
            operation.get()
                .onSuccess(promise::complete)
                .onFailure(err -> {
                    if (attempt < maxAttempts && isRetryable(err)) {
                        long nextBackoff = Math.min(backoffMs * 2, 1000L);
                        long delay = backoffMs <= 0 ? 0 : backoffMs;
                        // Schedule next attempt on Vert.x timer
                        vertx.setTimer(delay, id -> executeWithRetryAttempt(vertx, operation, maxAttempts, attempt + 1, nextBackoff, promise));
                    } else {
                        promise.fail(err);
                    }
                });
        } catch (Throwable e) {
            if (attempt < maxAttempts && isRetryable(e)) {
                long nextBackoff = Math.min(backoffMs * 2, 1000L);
                long delay = backoffMs <= 0 ? 0 : backoffMs;
                vertx.setTimer(delay, id -> executeWithRetryAttempt(vertx, operation, maxAttempts, attempt + 1, nextBackoff, promise));
            } else {
                promise.fail(e);
            }
        }
    }


    /**
     * Gets or creates a shared Vertx instance for proper context management.
     * This ensures that TransactionPropagation.CONTEXT works correctly by providing
     * a consistent Vertx context across all PgNativeQueueConsumer instances.
     *
     * Following the exact pattern from peegeeq-outbox OutboxProducer with additional
     * safety checks to prevent usage of closed instances.
     *
     * @return The shared Vertx instance, or null if it has been closed
     */


    /**
     * Parse payload from JsonObject back to the expected type.
     * Handles both simple values (wrapped in {"value": ...}) and complex objects.
     *
     * CRITICAL FIX: Use the same ObjectMapper that was used for serialization
     * instead of JsonObject.mapTo() which uses Vert.x's internal ObjectMapper.
     * This ensures consistent Instant/LocalDateTime serialization/deserialization.
     */
    private T parsePayloadFromJsonObject(JsonObject payload) throws Exception {
        if (payload == null || payload.isEmpty()) return null;

        // Check if this is a simple value wrapped in {"value": ...}
        if (payload.size() == 1 && payload.containsKey("value")) {
            Object value = payload.getValue("value");
            if (payloadType.isInstance(value)) {
                @SuppressWarnings("unchecked")
                T result = (T) value;
                return result;
            }
            // If the inner value is a JSON structure, decode from the inner structure
            if (value instanceof io.vertx.core.json.JsonObject) {
                String inner = ((io.vertx.core.json.JsonObject) value).encode();
                return objectMapper.readValue(inner, payloadType);
            }
            if (value instanceof io.vertx.core.json.JsonArray) {
                String inner = ((io.vertx.core.json.JsonArray) value).encode();
                return objectMapper.readValue(inner, payloadType);
            }
            // If the inner value is a simple scalar, attempt a direct conversion
            if (value instanceof Number || value instanceof CharSequence || value instanceof Boolean) {
                return objectMapper.convertValue(value, payloadType);
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
}
