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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnection;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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

    private MessageHandler<T> messageHandler;
    private PgConnection subscriber;
    private ScheduledExecutorService scheduler;
    private ExecutorService messageProcessingExecutor;

    // Shared Vertx instance for proper context management - following peegeeq-outbox pattern
    private static volatile Vertx sharedVertx;

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
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "native-queue-consumer-" + topic);
            t.setDaemon(true);
            return t;
        });

        // Initialize message processing thread pool
        int consumerThreads = configuration != null ?
            configuration.getQueueConfig().getConsumerThreads() : 1;
        this.messageProcessingExecutor = Executors.newFixedThreadPool(consumerThreads, r -> {
            Thread t = new Thread(r, "native-queue-processor-" + topic + "-" + System.currentTimeMillis());
            t.setDaemon(true);
            return t;
        });

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
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "native-queue-consumer-" + topic);
            t.setDaemon(true);
            return t;
        });

        // Initialize message processing thread pool
        int consumerThreads = consumerConfig != null ? consumerConfig.getConsumerThreads() :
            (configuration != null ? configuration.getQueueConfig().getConsumerThreads() : 1);
        this.messageProcessingExecutor = Executors.newFixedThreadPool(consumerThreads, r -> {
            Thread t = new Thread(r, "native-queue-processor-" + topic + "-" + System.currentTimeMillis());
            t.setDaemon(true);
            return t;
        });

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

        // CRITICAL FIX: Check if consumer is closed before starting async operations
        if (closed.get()) {
            logger.debug("NATIVE-DEBUG: Consumer is closed, skipping LISTEN setup for topic: {}", topic);
            return;
        }

        try {
            final Pool pool = poolAdapter.getPool() != null ?
                poolAdapter.getPool() :
                poolAdapter.createPool(null, "native-queue");

            logger.debug("NATIVE-DEBUG: Got pool for listening, about to get connection");

            // CRITICAL FIX: Make LISTEN setup synchronous to prevent race conditions
            pool.getConnection()
                .toCompletionStage()
                .toCompletableFuture()
                .thenCompose(connection -> {
                    // Double-check if consumer is still active after getting connection
                    if (closed.get()) {
                        logger.debug("NATIVE-DEBUG: Consumer closed after getting connection, closing connection for topic: {}", topic);
                        connection.close();
                        return CompletableFuture.completedFuture(null);
                    }

                    logger.debug("NATIVE-DEBUG: Got connection for listening, setting up LISTEN");
                    // Cast to PgConnection for notification support
                    PgConnection pgConnection = (PgConnection) connection;

                    // Execute LISTEN command synchronously
                    return pgConnection.query("LISTEN \"" + notifyChannel + "\"")
                        .execute()
                        .toCompletionStage()
                        .toCompletableFuture()
                        .thenApply(result -> {
                            // Triple-check if consumer is still active after LISTEN
                            if (closed.get()) {
                                logger.debug("NATIVE-DEBUG: Consumer closed after LISTEN, cleaning up for topic: {}", topic);
                                pgConnection.close();
                                return null;
                            }

                            logger.debug("NATIVE-DEBUG: Successfully started listening on channel: {}", notifyChannel);
                            logger.info("Started listening on channel: {}", notifyChannel);

                            // Set up notification handler
                            pgConnection.notificationHandler(notification -> {
                                System.out.println("🔔 CONSUMER: Raw notification received - channel: " + notification.getChannel() + ", payload: " + notification.getPayload());
                                logger.debug("NATIVE-DEBUG: Raw notification received - channel: {}, payload: {}",
                                    notification.getChannel(), notification.getPayload());
                                // Check if consumer is still active when notification arrives
                                if (!closed.get() && notifyChannel.equals(notification.getChannel())) {
                                    System.out.println("✅ CONSUMER: Processing notification on channel: " + notifyChannel);
                                    logger.debug("NATIVE-DEBUG: Received notification on channel: {}", notifyChannel);
                                    logger.info("🔔 Received notification on channel: {} - processing messages", notifyChannel);
                                    // Process messages immediately when notified
                                    processAvailableMessages();
                                } else {
                                    System.out.println("❌ CONSUMER: Ignoring notification - closed: " + closed.get() + ", channel match: " + notifyChannel.equals(notification.getChannel()));
                                    logger.debug("NATIVE-DEBUG: Ignoring notification - closed: {}, channel match: {}",
                                        closed.get(), notifyChannel.equals(notification.getChannel()));
                                }
                            });

                            // Store the connection for cleanup
                            this.subscriber = pgConnection;

                            // CRITICAL: For LISTEN_NOTIFY_ONLY mode, check for existing messages
                            // after setting up LISTEN, since NOTIFY only works for new messages
                            ConsumerMode mode = consumerConfig != null ? consumerConfig.getMode() : ConsumerMode.HYBRID;
                            if (mode == ConsumerMode.LISTEN_NOTIFY_ONLY) {
                                logger.debug("NATIVE-DEBUG: LISTEN_NOTIFY_ONLY mode - checking for existing messages after LISTEN setup");
                                // Process existing messages synchronously to avoid deadlock
                                processAvailableMessages();
                            }

                            return pgConnection;
                        })
                        .exceptionally(error -> {
                            logger.debug("NATIVE-DEBUG: Failed to start listening on channel {}: {}", notifyChannel, error.getMessage());
                            logger.error("Failed to start listening on channel {}: {}", notifyChannel, error.getMessage());
                            pgConnection.close();
                            return null;
                        });
                })
                .exceptionally(error -> {
                    logger.debug("NATIVE-DEBUG: Failed to get connection for LISTEN: {}", error.getMessage());
                    logger.error("Failed to get connection for LISTEN on channel {}: {}", notifyChannel, error.getMessage());
                    return null;
                })
                .join(); // CRITICAL: Wait for completion to make this truly synchronous

        } catch (Exception e) {
            logger.debug("NATIVE-DEBUG: Exception in startListening: {}", e.getMessage());
            logger.error("Error starting listener for topic {}: {}", topic, e.getMessage());
        }
        logger.debug("NATIVE-DEBUG: startListening() completed for topic: {}", topic);
    }
    
    private void stopListening() {
        if (subscriber != null) {
            PgConnection connectionToClose = subscriber;
            subscriber = null; // Clear reference first to prevent new operations

            try {
                // Execute UNLISTEN command synchronously with timeout
                logger.debug("NATIVE-DEBUG: Executing UNLISTEN for channel: {}", notifyChannel);
                connectionToClose.query("UNLISTEN \"" + notifyChannel + "\"")
                    .execute()
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(2, TimeUnit.SECONDS); // Wait up to 2 seconds for UNLISTEN

                logger.debug("NATIVE-DEBUG: UNLISTEN completed for channel: {}", notifyChannel);

                // Close connection after successful UNLISTEN
                connectionToClose.close();
                logger.info("Stopped listening on channel: {}", notifyChannel);

            } catch (Exception e) {
                logger.warn("Error during UNLISTEN for channel {}: {} - forcing connection close",
                           notifyChannel, e.getMessage());
                try {
                    // Force close the connection even if UNLISTEN failed
                    connectionToClose.close();
                } catch (Exception closeEx) {
                    logger.debug("Error closing connection during cleanup: {}", closeEx.getMessage());
                }
            }
        }
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

        logger.info("About to start polling for topic {} with interval: {} ms, scheduler: {}",
            topic, pollingIntervalMs, scheduler != null ? "present" : "null");

        if (scheduler == null) {
            logger.error("Scheduler is null! Cannot start polling for topic: {}", topic);
            return;
        }

        // Poll for messages at configured interval as backup to LISTEN/NOTIFY
        // Wrap in defensive error handling to prevent scheduler termination
        try {
            scheduler.scheduleWithFixedDelay(() -> {
                try {
                    logger.debug("NATIVE-DEBUG: Polling for messages on topic: {} (interval: {}ms)", topic, pollingIntervalMs);
                    processAvailableMessages();
                } catch (Exception e) {
                    // Critical fix: Prevent uncaught exceptions from terminating the scheduler
                    if (!closed.get()) {
                        logger.warn("Error in scheduled message processing for topic {}: {}", topic, e.getMessage());
                    }
                }
            }, pollingIntervalMs, pollingIntervalMs, TimeUnit.MILLISECONDS);
            logger.info("Successfully scheduled polling task for topic: {}", topic);
        } catch (Exception e) {
            logger.error("Failed to schedule polling task for topic {}: {}", topic, e.getMessage(), e);
            throw e;
        }

        // Check for expired locks every 10 seconds (this can remain fixed as it's maintenance)
        // Wrap in defensive error handling to prevent scheduler termination
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                releaseExpiredLocks();
            } catch (Exception e) {
                // Critical fix: Prevent uncaught exceptions from terminating the scheduler
                if (!closed.get()) {
                    logger.warn("Error in scheduled expired locks cleanup for topic {}: {}", topic, e.getMessage());
                }
            }
        }, 10, 10, TimeUnit.SECONDS);

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
            final Pool pool = poolAdapter.getPool() != null ?
                poolAdapter.getPool() :
                poolAdapter.createPool(null, "native-queue");

            // Get batch size from configuration (following outbox pattern)
            int batchSize = configuration != null ?
                configuration.getQueueConfig().getBatchSize() : 1;

            // Batch processing approach following outbox pattern
            // Use IN clause with LIMIT to process multiple messages in batch
            String sql = """
                UPDATE queue_messages
                SET status = 'LOCKED', lock_until = $1
                WHERE id IN (
                    SELECT id FROM queue_messages
                    WHERE topic = $2 AND status = 'AVAILABLE'
                    ORDER BY priority DESC, created_at ASC
                    LIMIT $3
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING id, payload, headers, correlation_id, message_group, retry_count, created_at
                """;

            // Get shared Vertx instance for proper context management
            Vertx vertx = getOrCreateSharedVertx();

            // Execute batch processing with proper parameters
            executeOnVertxContext(vertx, () -> pool.preparedQuery(sql)
                .execute(Tuple.of(OffsetDateTime.now().plusSeconds(30), topic, batchSize))
                .onSuccess(result -> {
                    if (result.size() > 0) {
                        logger.debug("NATIVE-DEBUG: Processing {} messages for topic {}", result.size(), topic);
                        logger.debug("Processing {} messages for topic {}", result.size(), topic);

                        // CRITICAL FIX: Process messages without transactions since locking is already committed
                        for (Row row : result) {
                            messageProcessingExecutor.submit(() -> processMessageWithoutTransaction(row));
                        }
                    } else {
                        logger.debug("NATIVE-DEBUG: No messages found for topic {}", topic);
                        logger.debug("No messages found for topic {}", topic);
                    }
                })
                .onFailure(error -> {
                    logger.debug("NATIVE-DEBUG: Error querying messages for topic {}: {}", topic, error.getMessage());
                    logger.error("Error querying messages for topic {}: {}", topic, error.getMessage());
                }))
            .onFailure(error -> {
                // Critical fix: Handle various error conditions gracefully
                String errorMessage = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();

                if (closed.get()) {
                    // During shutdown, many errors are expected
                    if (errorMessage.contains("Pool closed") ||
                        errorMessage.contains("event executor terminated") ||
                        errorMessage.contains("Connection closed")) {
                        logger.debug("Expected error during shutdown for topic {}: {}", topic, errorMessage);
                    } else {
                        logger.debug("Error during shutdown for topic {}: {}", topic, errorMessage);
                    }
                } else {
                    // During normal operation, log as error but don't let it terminate the executor
                    if (errorMessage.contains("event executor terminated")) {
                        logger.warn("Event executor terminated for topic {} - this may indicate system shutdown", topic);
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
            // Parse payload and headers (following existing pattern)
            String payload = row.getString("payload");
            String headers = row.getString("headers");

            // Parse headers and payload (following existing transaction pattern)
            T parsedPayload = objectMapper.readValue(payload, payloadType);
            Map<String, String> headerMap = headers != null ?
                objectMapper.readValue(headers, new TypeReference<Map<String, String>>() {}) :
                new HashMap<>();

            // Get message handler
            MessageHandler<T> handler = this.messageHandler;
            if (handler == null) {
                logger.warn("Message handler is null for message {}, consumer may have been unsubscribed", messageId);
                return;
            }

            // Create message (following existing pattern)
            Message<T> message = new SimpleMessage<>(
                messageId, topic, parsedPayload, headerMap, null, null, java.time.Instant.now()
            );

            // CRITICAL FIX: Process message asynchronously and wait for CompletableFuture
            try {
                long startTime = System.currentTimeMillis();

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
                    })
                    .exceptionally(processingError -> {
                        logger.error("Error processing message {}: {}", messageId, processingError.getMessage());

                        // Failure: Handle retry logic
                        int retryCount = row.getInteger("retry_count") != null ? row.getInteger("retry_count") : 0;
                        handleProcessingFailure(messageIdLong, messageId, retryCount + 1, processingError);
                        return null;
                    });

            } catch (Exception processingError) {
                logger.error("Error calling message handler for {}: {}", messageId, processingError.getMessage());

                // Failure: Handle retry logic
                int retryCount = row.getInteger("retry_count") != null ? row.getInteger("retry_count") : 0;
                handleProcessingFailure(messageIdLong, messageId, retryCount + 1, processingError);
            }

        } catch (Exception e) {
            logger.error("Error parsing message {}: {}", messageId, e.getMessage());
            int retryCount = row.getInteger("retry_count") != null ? row.getInteger("retry_count") : 0;
            handleProcessingFailure(messageIdLong, messageId, retryCount + 1, e);
        }
    }

    private Future<Void> processMessageWithTransaction(SqlConnection client, Row row) {
        // Critical fix: Check if consumer is closed before processing message
        if (closed.get()) {
            logger.debug("Skipping message processing - consumer is closed");
            return Future.succeededFuture();
        }

        Long messageIdLong = row.getLong("id");
        String messageId = messageIdLong.toString();
        String payload = row.getString("payload");
        String headers = row.getString("headers");

        try {
            // Parse headers and payload
            T parsedPayload = objectMapper.readValue(payload, payloadType);
            Map<String, String> headerMap = headers != null ?
                objectMapper.readValue(headers, new TypeReference<Map<String, String>>() {}) :
                new HashMap<>();

            // Get message handler
            MessageHandler<T> handler = this.messageHandler;
            if (handler == null) {
                logger.warn("Message handler is null for message {}, consumer may have been unsubscribed", messageId);
                // Transaction-level advisory lock will be automatically released when transaction ends
                return Future.succeededFuture();
            }

            // Create message (simplified version for transaction processing)
            Message<T> message = new SimpleMessage<>(
                messageId, topic, parsedPayload, headerMap, null, null, java.time.Instant.now()
            );

            // Process message synchronously within transaction
            try {
                long startTime = System.currentTimeMillis();
                handler.handle(message);
                long processingTime = System.currentTimeMillis() - startTime;

                logger.debug("Message {} processed successfully", messageId);

                // Record metrics for successful message processing
                if (metrics != null) {
                    metrics.recordMessageReceived(topic);
                    metrics.recordMessageProcessed(topic, java.time.Duration.ofMillis(processingTime));
                }

                // Working pattern: DELETE message within transaction
                // Key insight: PostgreSQL handles advisory lock cleanup automatically
                String deleteSql = "DELETE FROM queue_messages WHERE id = $1";

                return client.preparedQuery(deleteSql)
                    .execute(Tuple.of(messageIdLong))
                    .mapEmpty();

            } catch (Exception processingError) {
                logger.error("Error processing message {}: {}", messageId, processingError.getMessage());

                // For now, just fail the transaction - this will cause automatic rollback
                return Future.failedFuture(processingError);
            }

        } catch (Exception e) {
            logger.error("Error parsing message {}: {}", messageId, e.getMessage());
            // Transaction will be automatically rolled back
            return Future.failedFuture(e);
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
            final Pool pool = poolAdapter.getPool() != null ?
                poolAdapter.getPool() :
                poolAdapter.createPool(null, "native-queue");

            String sql = "DELETE FROM queue_messages WHERE id = $1";

            // CRITICAL FIX: Use synchronous deletion during shutdown to prevent race conditions
            if (closed.get()) {
                // Double-check after getting pool - if closed, skip deletion
                logger.debug("Consumer closed after getting pool, skipping message deletion for {}", messageId);
                inFlightOperations.decrementAndGet(); // Decrement counter
                return;
            }

            pool.preparedQuery(sql)
                .execute(Tuple.of(messageIdLong))
                .onSuccess(result -> {
                    logger.debug("Deleted processed message: {}", messageId);
                    inFlightOperations.decrementAndGet(); // Decrement counter on success
                    // Transaction-level advisory lock will be automatically released when transaction ends
                })
                .onFailure(error -> {
                    inFlightOperations.decrementAndGet(); // Decrement counter on failure
                    // Critical fix: Handle connection errors during shutdown gracefully
                    String errorMsg = error.getMessage();
                    if (closed.get() && (errorMsg.contains("Pool closed") ||
                                        errorMsg.contains("connection may have been lost") ||
                                        errorMsg.contains("Failed to read any response"))) {
                        logger.debug("Connection closed during message deletion for {} - this is expected during shutdown", messageId);
                    } else {
                        logger.error("Failed to delete message {}: {}", messageId, errorMsg);
                    }
                    // Transaction-level advisory lock will be automatically released when transaction ends
                });

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
                // Reset status for retry and increment retry count
                String sql = "UPDATE queue_messages SET status = 'AVAILABLE', lock_until = NULL, retry_count = $2 WHERE id = $1";

                pool.preparedQuery(sql)
                    .execute(Tuple.of(messageIdLong, retryCount))
                    .onSuccess(result -> {
                        logger.debug("Reset message {} for retry (attempt {})", messageId, retryCount);
                        // Transaction-level advisory lock will be automatically released when transaction ends
                    })
                    .onFailure(updateError -> {
                        logger.error("Failed to reset message {} for retry: {}", messageId, updateError.getMessage());
                        // Transaction-level advisory lock will be automatically released when transaction ends
                    });
            }
            
        } catch (Exception e) {
            logger.error("Error handling processing failure for message {}: {}", messageId, e.getMessage());
            // Transaction-level advisory lock will be automatically released when transaction ends
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
            final Pool pool = poolAdapter.getPool() != null ?
                poolAdapter.getPool() :
                poolAdapter.createPool(null, "native-queue");

            // CRITICAL FIX: Just reset expired locks in database - don't manually release advisory locks
            // Advisory locks will be auto-released when connections are returned to pool
            String updateSql = """
                UPDATE queue_messages
                SET status = 'AVAILABLE', lock_until = NULL
                WHERE topic = $1 AND status = 'LOCKED' AND lock_until < $2
                """;

            pool.preparedQuery(updateSql)
                .execute(Tuple.of(topic, OffsetDateTime.now()))
                .onSuccess(updateResult -> {
                    if (updateResult.rowCount() > 0) {
                        logger.debug("Reset {} expired locks for topic: {} - advisory locks will auto-release", updateResult.rowCount(), topic);
                        // Process any newly available messages
                        processAvailableMessages();
                    }
                })
                .onFailure(error -> {
                    // Critical fix: Handle "Pool closed" errors during shutdown gracefully
                    if (closed.get() && error.getMessage().contains("Pool closed")) {
                        logger.debug("Pool closed during shutdown for expired locks cleanup - this is expected");
                    } else {
                        logger.warn("Failed to query expired locks for topic {}: {}", topic, error.getMessage());
                    }
                });

        } catch (Exception e) {
            logger.warn("Error releasing expired locks for topic {}: {}", topic, e.getMessage());
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            logger.info("Starting graceful shutdown of native queue consumer for topic: {}", topic);

            // Step 1: Stop accepting new work
            unsubscribe();
            stopListening();

            // Step 2: Shutdown scheduler with proper timeout
            if (scheduler != null) {
                scheduler.shutdown();
                try {
                    if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                        logger.warn("Scheduler did not terminate gracefully, forcing shutdown");
                        scheduler.shutdownNow();
                        // Wait a bit more for forced shutdown
                        if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                            logger.warn("Scheduler did not terminate after forced shutdown");
                        }
                    }
                } catch (InterruptedException e) {
                    logger.warn("Interrupted while waiting for scheduler shutdown");
                    scheduler.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }

            // Step 3: Shutdown message processing executor with longer timeout
            if (messageProcessingExecutor != null) {
                messageProcessingExecutor.shutdown();
                try {
                    if (!messageProcessingExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                        logger.warn("Message processing executor did not terminate gracefully, forcing shutdown");
                        messageProcessingExecutor.shutdownNow();
                        // Wait a bit more for forced shutdown
                        if (!messageProcessingExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                            logger.warn("Message processing executor did not terminate after forced shutdown");
                        }
                    }
                } catch (InterruptedException e) {
                    logger.warn("Interrupted while waiting for message processing executor shutdown");
                    messageProcessingExecutor.shutdownNow();
                    Thread.currentThread().interrupt();
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
     * Following the exact pattern from peegeeq-outbox OutboxProducer.
     *
     * @param vertx The Vertx instance
     * @param operation The operation to execute that returns a Future
     * @return Future that completes when the operation completes
     */
    private static <T> Future<T> executeOnVertxContext(Vertx vertx, java.util.function.Supplier<Future<T>> operation) {
        Context context = vertx.getOrCreateContext();
        if (context == Vertx.currentContext()) {
            // Already on Vert.x context, execute directly
            return operation.get();
        } else {
            // Execute on Vert.x context using runOnContext
            Promise<T> promise = Promise.promise();
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
     * a consistent Vertx context across all PgNativeQueueConsumer instances.
     *
     * Following the exact pattern from peegeeq-outbox OutboxProducer.
     *
     * @return The shared Vertx instance
     */
    private static Vertx getOrCreateSharedVertx() {
        if (sharedVertx == null) {
            synchronized (PgNativeQueueConsumer.class) {
                if (sharedVertx == null) {
                    sharedVertx = Vertx.vertx();
                    logger.info("Created shared Vertx instance for PgNativeQueueConsumer context management");
                }
            }
        }
        return sharedVertx;
    }

    /**
     * Closes the shared Vertx instance. This should only be called during application shutdown.
     * Note: This is a static method that affects all PgNativeQueueConsumer instances.
     */
    public static void closeSharedVertx() {
        if (sharedVertx != null) {
            synchronized (PgNativeQueueConsumer.class) {
                if (sharedVertx != null) {
                    try {
                        sharedVertx.close()
                            .toCompletionStage()
                            .toCompletableFuture()
                            .get(10, java.util.concurrent.TimeUnit.SECONDS);
                        logger.info("Closed shared Vertx instance for PgNativeQueueConsumer");
                    } catch (Exception e) {
                        logger.warn("Error closing shared Vertx instance for PgNativeQueueConsumer: {}", e.getMessage());
                    } finally {
                        sharedVertx = null;
                    }
                }
            }
        }
    }
}
