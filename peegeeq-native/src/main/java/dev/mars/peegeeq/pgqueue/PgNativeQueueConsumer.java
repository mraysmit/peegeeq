package dev.mars.peegeeq.pgqueue;

import dev.mars.peegeeq.api.Message;
import dev.mars.peegeeq.api.MessageConsumer;
import dev.mars.peegeeq.api.MessageHandler;
import dev.mars.peegeeq.api.SimpleMessage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnection;
import io.vertx.pgclient.PgPool;
import io.vertx.pgclient.pubsub.PgSubscriber;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Native PostgreSQL queue message consumer.
 * Uses LISTEN/NOTIFY for real-time delivery and advisory locks for message processing.
 */
public class PgNativeQueueConsumer<T> implements MessageConsumer<T> {
    private static final Logger logger = LoggerFactory.getLogger(PgNativeQueueConsumer.class);

    private final VertxPoolAdapter poolAdapter;
    private final ObjectMapper objectMapper;
    private final String topic;
    private final Class<T> payloadType;
    private final String notifyChannel;
    private final AtomicBoolean subscribed = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private MessageHandler<T> messageHandler;
    private PgSubscriber subscriber;
    private ScheduledExecutorService scheduler;

    public PgNativeQueueConsumer(VertxPoolAdapter poolAdapter, ObjectMapper objectMapper,
                                String topic, Class<T> payloadType) {
        this.poolAdapter = poolAdapter;
        this.objectMapper = objectMapper;
        this.topic = topic;
        this.payloadType = payloadType;
        this.notifyChannel = "queue_" + topic;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "native-queue-consumer-" + topic);
            t.setDaemon(true);
            return t;
        });
        logger.info("Created native queue consumer for topic: {}", topic);
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
            // For now, we'll skip the LISTEN/NOTIFY functionality and rely on polling
            // In a full implementation, you'd need to properly configure PgSubscriber
            // with the correct Vertx instance and connection options
            logger.info("LISTEN/NOTIFY not implemented yet, relying on polling for topic: {}", topic);

        } catch (Exception e) {
            logger.error("Error starting listener for topic {}: {}", topic, e.getMessage());
        }
    }
    
    private void stopListening() {
        if (subscriber != null) {
            try {
                subscriber.close();
                subscriber = null;
                logger.info("Stopped listening on channel: {}", notifyChannel);
            } catch (Exception e) {
                logger.warn("Error stopping listener: {}", e.getMessage());
            }
        }
    }
    
    private void startPolling() {
        // Poll for messages every 5 seconds as backup to LISTEN/NOTIFY
        scheduler.scheduleWithFixedDelay(this::processAvailableMessages, 5, 5, TimeUnit.SECONDS);
    }
    
    private void processAvailableMessages() {
        if (!subscribed.get() || messageHandler == null) {
            return;
        }
        
        try {
            final PgPool pool = poolAdapter.getPool() != null ?
                poolAdapter.getPool() :
                poolAdapter.createPool(null, "native-queue");
            
            // Use advisory lock to ensure only one consumer processes a message
            String sql = """
                UPDATE queue_messages 
                SET status = 'PROCESSING', 
                    processing_started_at = $1,
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
                .execute(Tuple.of(Instant.now(), topic))
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
        String messageId = row.getString("id");
        String payloadJson = row.getString("payload");
        String headersJson = row.getString("headers");
        String correlationId = row.getString("correlation_id");
        String messageGroup = row.getString("message_group");
        int retryCount = row.getInteger("retry_count");
        Instant createdAt = row.get(Instant.class, "created_at");
        
        try {
            T payload = objectMapper.readValue(payloadJson, payloadType);
            Map<String, String> headers = objectMapper.readValue(headersJson, new TypeReference<Map<String, String>>() {});
            
            Message<T> message = new SimpleMessage<>(
                messageId, topic, payload, headers, correlationId, messageGroup, createdAt
            );
            
            logger.debug("Processing message {} from topic {}", messageId, topic);
            
            // Process the message
            CompletableFuture<Void> processingFuture = messageHandler.handle(message);
            
            processingFuture
                .thenRun(() -> {
                    // Message processed successfully - delete it
                    deleteMessage(messageId);
                })
                .exceptionally(error -> {
                    logger.warn("Message processing failed for {}: {}", messageId, error.getMessage());
                    handleProcessingFailure(messageId, retryCount, error);
                    return null;
                });
                
        } catch (Exception e) {
            logger.error("Error deserializing message {}: {}", messageId, e.getMessage());
            handleProcessingFailure(messageId, retryCount, e);
        }
    }
    
    private void deleteMessage(String messageId) {
        try {
            final PgPool pool = poolAdapter.getPool() != null ?
                poolAdapter.getPool() :
                poolAdapter.createPool(null, "native-queue");
            
            String sql = "DELETE FROM queue_messages WHERE id = $1";
            
            pool.preparedQuery(sql)
                .execute(Tuple.of(messageId))
                .onSuccess(result -> {
                    logger.debug("Deleted processed message: {}", messageId);
                    // Release advisory lock
                    releaseAdvisoryLock(messageId);
                })
                .onFailure(error -> {
                    logger.error("Failed to delete message {}: {}", messageId, error.getMessage());
                    releaseAdvisoryLock(messageId);
                });
                
        } catch (Exception e) {
            logger.error("Error deleting message {}: {}", messageId, e.getMessage());
            releaseAdvisoryLock(messageId);
        }
    }
    
    private void handleProcessingFailure(String messageId, int retryCount, Throwable error) {
        try {
            final PgPool pool = poolAdapter.getPool() != null ?
                poolAdapter.getPool() :
                poolAdapter.createPool(null, "native-queue");
            
            // Check if we should retry or move to dead letter queue
            int maxRetries = 3; // TODO: Make configurable
            
            if (retryCount >= maxRetries) {
                // Move to dead letter queue
                moveToDeadLetterQueue(messageId, error.getMessage());
            } else {
                // Reset status for retry
                String sql = "UPDATE queue_messages SET status = 'AVAILABLE', processing_started_at = NULL WHERE id = $1";
                
                pool.preparedQuery(sql)
                    .execute(Tuple.of(messageId))
                    .onSuccess(result -> {
                        logger.debug("Reset message {} for retry (attempt {})", messageId, retryCount);
                        releaseAdvisoryLock(messageId);
                    })
                    .onFailure(updateError -> {
                        logger.error("Failed to reset message {} for retry: {}", messageId, updateError.getMessage());
                        releaseAdvisoryLock(messageId);
                    });
            }
            
        } catch (Exception e) {
            logger.error("Error handling processing failure for message {}: {}", messageId, e.getMessage());
            releaseAdvisoryLock(messageId);
        }
    }
    
    private void moveToDeadLetterQueue(String messageId, String errorMessage) {
        // TODO: Implement dead letter queue integration
        logger.warn("Message {} exceeded retry limit, should move to dead letter queue: {}", messageId, errorMessage);
        
        // For now, just delete the message
        deleteMessage(messageId);
    }
    
    private void releaseAdvisoryLock(String messageId) {
        try {
            final PgPool pool = poolAdapter.getPool() != null ?
                poolAdapter.getPool() :
                poolAdapter.createPool(null, "native-queue");
            
            String sql = "SELECT pg_advisory_unlock(hashtext($1))";
            
            pool.preparedQuery(sql)
                .execute(Tuple.of(messageId))
                .onSuccess(result -> {
                    logger.debug("Released advisory lock for message: {}", messageId);
                })
                .onFailure(error -> {
                    logger.warn("Failed to release advisory lock for message {}: {}", messageId, error.getMessage());
                });
                
        } catch (Exception e) {
            logger.warn("Error releasing advisory lock for message {}: {}", messageId, e.getMessage());
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
