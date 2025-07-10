package dev.mars.peegeeq.pgqueue;

import dev.mars.peegeeq.api.MessageProducer;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Future;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Native PostgreSQL queue message producer.
 * Uses direct database inserts with NOTIFY for real-time delivery.
 */
public class PgNativeQueueProducer<T> implements MessageProducer<T> {
    private static final Logger logger = LoggerFactory.getLogger(PgNativeQueueProducer.class);

    private final VertxPoolAdapter poolAdapter;
    private final ObjectMapper objectMapper;
    private final String topic;
    private final Class<T> payloadType;
    private volatile boolean closed = false;

    public PgNativeQueueProducer(VertxPoolAdapter poolAdapter, ObjectMapper objectMapper,
                                String topic, Class<T> payloadType) {
        this.poolAdapter = poolAdapter;
        this.objectMapper = objectMapper;
        this.topic = topic;
        this.payloadType = payloadType;
        logger.info("Created native queue producer for topic: {}", topic);
    }
    
    @Override
    public CompletableFuture<Void> send(T payload) {
        return send(payload, Map.of());
    }
    
    @Override
    public CompletableFuture<Void> send(T payload, Map<String, String> headers) {
        return send(payload, headers, null);
    }
    
    @Override
    public CompletableFuture<Void> send(T payload, Map<String, String> headers, String correlationId) {
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Producer is closed"));
        }
        
        CompletableFuture<Void> future = new CompletableFuture<>();
        
        try {
            String messageId = UUID.randomUUID().toString();
            String payloadJson = objectMapper.writeValueAsString(payload);
            String headersJson = objectMapper.writeValueAsString(headers);
            String finalCorrelationId = correlationId != null ? correlationId : messageId;
            
            final PgPool pool = poolAdapter.getPool() != null ?
                poolAdapter.getPool() :
                poolAdapter.createPool(null, "native-queue");
            
            String sql = """
                INSERT INTO queue_messages 
                (id, topic, payload, headers, correlation_id, status, created_at, priority)
                VALUES ($1, $2, $3::jsonb, $4::jsonb, $5, 'AVAILABLE', $6, $7)
                """;
            
            Tuple params = Tuple.of(
                messageId,
                topic,
                payloadJson,
                headersJson,
                finalCorrelationId,
                Instant.now(),
                5 // Default priority
            );
            
            pool.preparedQuery(sql)
                .execute(params)
                .onSuccess(result -> {
                    logger.debug("Message sent to topic {}: {}", topic, messageId);
                    
                    // Send NOTIFY to wake up consumers
                    String notifyChannel = "queue_" + topic;
                    pool.query("SELECT pg_notify('" + notifyChannel + "', '" + messageId + "')")
                        .execute()
                        .onSuccess(notifyResult -> {
                            logger.debug("Notification sent for message: {}", messageId);
                            future.complete(null);
                        })
                        .onFailure(notifyError -> {
                            logger.warn("Failed to send notification for message {}: {}", 
                                messageId, notifyError.getMessage());
                            // Complete anyway since message was stored
                            future.complete(null);
                        });
                })
                .onFailure(error -> {
                    logger.error("Failed to send message to topic {}: {}", topic, error.getMessage());
                    future.completeExceptionally(error);
                });
                
        } catch (Exception e) {
            logger.error("Error preparing message for topic {}: {}", topic, e.getMessage());
            future.completeExceptionally(e);
        }
        
        return future;
    }
    
    @Override
    public CompletableFuture<Void> send(T payload, Map<String, String> headers, String correlationId, String messageGroup) {
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Producer is closed"));
        }
        
        CompletableFuture<Void> future = new CompletableFuture<>();
        
        try {
            String messageId = UUID.randomUUID().toString();
            String payloadJson = objectMapper.writeValueAsString(payload);
            String headersJson = objectMapper.writeValueAsString(headers);
            String finalCorrelationId = correlationId != null ? correlationId : messageId;
            
            final PgPool pool = poolAdapter.getPool() != null ?
                poolAdapter.getPool() :
                poolAdapter.createPool(null, "native-queue");
            
            String sql = """
                INSERT INTO queue_messages 
                (id, topic, payload, headers, correlation_id, message_group, status, created_at, priority)
                VALUES ($1, $2, $3::jsonb, $4::jsonb, $5, $6, 'AVAILABLE', $7, $8)
                """;
            
            Tuple params = Tuple.of(
                messageId,
                topic,
                payloadJson,
                headersJson,
                finalCorrelationId,
                messageGroup,
                Instant.now(),
                5 // Default priority
            );
            
            pool.preparedQuery(sql)
                .execute(params)
                .onSuccess(result -> {
                    logger.debug("Message sent to topic {} with group {}: {}", topic, messageGroup, messageId);
                    
                    // Send NOTIFY to wake up consumers
                    String notifyChannel = "queue_" + topic;
                    pool.query("SELECT pg_notify('" + notifyChannel + "', '" + messageId + "')")
                        .execute()
                        .onSuccess(notifyResult -> {
                            logger.debug("Notification sent for message: {}", messageId);
                            future.complete(null);
                        })
                        .onFailure(notifyError -> {
                            logger.warn("Failed to send notification for message {}: {}", 
                                messageId, notifyError.getMessage());
                            // Complete anyway since message was stored
                            future.complete(null);
                        });
                })
                .onFailure(error -> {
                    logger.error("Failed to send message to topic {}: {}", topic, error.getMessage());
                    future.completeExceptionally(error);
                });
                
        } catch (Exception e) {
            logger.error("Error preparing message for topic {}: {}", topic, e.getMessage());
            future.completeExceptionally(e);
        }
        
        return future;
    }
    
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            logger.info("Closed native queue producer for topic: {}", topic);
        }
    }
}
