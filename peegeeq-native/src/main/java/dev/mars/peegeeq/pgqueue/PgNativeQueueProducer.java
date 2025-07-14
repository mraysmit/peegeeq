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


import dev.mars.peegeeq.api.MessageProducer;
import dev.mars.peegeeq.db.metrics.PeeGeeQMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Future;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Native PostgreSQL queue message producer.
 * 
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
public class PgNativeQueueProducer<T> implements MessageProducer<T> {
    private static final Logger logger = LoggerFactory.getLogger(PgNativeQueueProducer.class);

    private final VertxPoolAdapter poolAdapter;
    private final ObjectMapper objectMapper;
    private final String topic;
    private final Class<T> payloadType;
    private final PeeGeeQMetrics metrics;
    private volatile boolean closed = false;

    public PgNativeQueueProducer(VertxPoolAdapter poolAdapter, ObjectMapper objectMapper,
                                String topic, Class<T> payloadType, PeeGeeQMetrics metrics) {
        this.poolAdapter = poolAdapter;
        this.objectMapper = objectMapper;
        this.topic = topic;
        this.payloadType = payloadType;
        this.metrics = metrics;
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
                (topic, payload, headers, correlation_id, status, created_at, priority)
                VALUES ($1, $2::jsonb, $3::jsonb, $4, 'AVAILABLE', $5, $6)
                RETURNING id
                """;

            Tuple params = Tuple.of(
                topic,
                payloadJson,
                headersJson,
                finalCorrelationId,
                OffsetDateTime.now(),
                5 // Default priority
            );
            
            pool.preparedQuery(sql)
                .execute(params)
                .onSuccess(result -> {
                    // Get the auto-generated ID from the database
                    Long generatedId = result.iterator().next().getLong("id");
                    logger.debug("Message sent to topic {}: {} (DB ID: {})", topic, messageId, generatedId);

                    // Record metrics
                    if (metrics != null) {
                        metrics.recordMessageSent(topic);
                    }

                    // Send NOTIFY to wake up consumers using the database-generated ID
                    String notifyChannel = "queue_" + topic;
                    pool.query("SELECT pg_notify('\"" + notifyChannel + "\"', '" + generatedId + "')")
                        .execute()
                        .onSuccess(notifyResult -> {
                            logger.debug("Notification sent for message: {} (DB ID: {})", messageId, generatedId);
                            future.complete(null);
                        })
                        .onFailure(notifyError -> {
                            logger.warn("Failed to send notification for message {} (DB ID: {}): {}",
                                messageId, generatedId, notifyError.getMessage());
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
                (topic, payload, headers, correlation_id, message_group, status, created_at, priority)
                VALUES ($1, $2::jsonb, $3::jsonb, $4, $5, 'AVAILABLE', $6, $7)
                RETURNING id
                """;

            Tuple params = Tuple.of(
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
                    // Get the auto-generated ID from the database
                    Long generatedId = result.iterator().next().getLong("id");
                    logger.debug("Message sent to topic {} with group {}: {} (DB ID: {})", topic, messageGroup, messageId, generatedId);

                    // Record metrics
                    if (metrics != null) {
                        metrics.recordMessageSent(topic);
                    }

                    // Send NOTIFY to wake up consumers using the database-generated ID
                    String notifyChannel = "queue_" + topic;
                    pool.query("SELECT pg_notify('\"" + notifyChannel + "\"', '" + generatedId + "')")
                        .execute()
                        .onSuccess(notifyResult -> {
                            logger.debug("Notification sent for message: {} (DB ID: {})", messageId, generatedId);
                            future.complete(null);
                        })
                        .onFailure(notifyError -> {
                            logger.warn("Failed to send notification for message {} (DB ID: {}): {}",
                                messageId, generatedId, notifyError.getMessage());
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
