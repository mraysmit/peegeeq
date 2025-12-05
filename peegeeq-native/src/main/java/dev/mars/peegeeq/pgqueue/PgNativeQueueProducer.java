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


import dev.mars.peegeeq.db.metrics.PeeGeeQMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
public class PgNativeQueueProducer<T> implements dev.mars.peegeeq.api.messaging.MessageProducer<T> {
    private static final Logger logger = LoggerFactory.getLogger(PgNativeQueueProducer.class);

    private final VertxPoolAdapter poolAdapter;
    private final ObjectMapper objectMapper;
    private final String topic;
    @SuppressWarnings("unused") // Reserved for future type safety features
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

    /**
     * Converts an object to a JsonObject for proper JSONB storage.
     * Uses the properly configured ObjectMapper to handle JSR310 types like LocalDate.
     * This ensures PostgreSQL can perform native JSON operations on the stored data.
     */
    private JsonObject toJsonObject(Object value) {
        if (value == null) return new JsonObject();
        if (value instanceof JsonObject) return (JsonObject) value;
        if (value instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) value;
            return new JsonObject(map);
        }
        // Handle collections/arrays by wrapping the JsonArray in {"value": [...]}
        if (value instanceof Iterable) {
            JsonArray arr = new JsonArray();
            for (Object o : (Iterable<?>) value) {
                arr.add(o);
            }
            return new JsonObject().put("value", arr);
        }
        if (value != null && value.getClass().isArray()) {
            JsonArray arr = new JsonArray();
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                arr.add(java.lang.reflect.Array.get(value, i));
            }
            return new JsonObject().put("value", arr);
        }
        // Handle primitive types (String, Number, Boolean) by wrapping them
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return new JsonObject().put("value", value);
        }

        // For complex objects, use the properly configured ObjectMapper to handle JSR310 types
        try {
            String json = objectMapper.writeValueAsString(value);
            return new JsonObject(json);
        } catch (Exception e) {
            logger.error("Error converting object to JsonObject for topic {}: {}", topic, e.getMessage());
            throw new RuntimeException("Failed to serialize payload to JSON", e);
        }
    }

    /**
     * Converts headers map to JsonObject, handling null values.
     * Follows the pattern established in existing codebase for header handling.
     */
    private JsonObject headersToJsonObject(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return new JsonObject();
        // Convert Map<String, String> to Map<String, Object> for JsonObject constructor
        Map<String, Object> objectMap = new java.util.HashMap<>(headers);
        return new JsonObject(objectMap);
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
            JsonObject payloadJson = toJsonObject(payload);
            JsonObject headersJson = headersToJsonObject(headers);
            String finalCorrelationId = correlationId != null ? correlationId : messageId;
            
            final Pool pool = poolAdapter.getPoolOrThrow();
            
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
                    System.out.println("🔔 PRODUCER: About to send NOTIFY on channel: " + notifyChannel + " with payload: " + generatedId);
                    pool.query("SELECT pg_notify('" + notifyChannel + "', '" + generatedId + "')")
                        .execute()
                        .onSuccess(notifyResult -> {
                            System.out.println("✅ PRODUCER: NOTIFY sent successfully for message: " + messageId + " (DB ID: " + generatedId + ")");
                            logger.debug("Notification sent for message: {} (DB ID: {})", messageId, generatedId);
                            future.complete(null);
                        })
                        .onFailure(notifyError -> {
                            System.out.println("❌ PRODUCER: NOTIFY failed for message: " + messageId + " (DB ID: " + generatedId + ") - " + notifyError.getMessage());
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
            JsonObject payloadJson = toJsonObject(payload);
            JsonObject headersJson = headersToJsonObject(headers);
            String finalCorrelationId = correlationId != null ? correlationId : messageId;

            final Pool pool = poolAdapter.getPoolOrThrow();

            // Extract priority from headers, default to 5
            int priority = 5;
            if (headers != null && headers.containsKey("priority")) {
                try {
                    String priorityStr = headers.get("priority");
                    logger.info("Extracting priority from headers: '{}' (raw value)", priorityStr);
                    priority = Integer.parseInt(priorityStr);
                    priority = Math.max(1, Math.min(10, priority)); // Clamp to 1-10
                    logger.info("Priority extracted and clamped: {}", priority);
                } catch (NumberFormatException e) {
                    logger.warn("Invalid priority value in headers: {}, using default: 5", headers.get("priority"));
                }
            } else {
                logger.info("No priority in headers (headers={}, containsKey={}), using default: 5",
                    headers != null ? headers.keySet() : "null",
                    headers != null ? headers.containsKey("priority") : "N/A");
            }

            // Extract delaySeconds from headers if present
            long delaySecondsValue = 0;
            if (headers != null && headers.containsKey("delaySeconds")) {
                try {
                    delaySecondsValue = Long.parseLong(headers.get("delaySeconds"));
                } catch (NumberFormatException e) {
                    logger.warn("Invalid delaySeconds value in headers: {}, using 0", headers.get("delaySeconds"));
                }
            }

            // Calculate visible_at based on delaySeconds
            OffsetDateTime now = OffsetDateTime.now();
            OffsetDateTime visibleAt = (delaySecondsValue > 0) 
                ? now.plusSeconds(delaySecondsValue) 
                : now;

            String sql = """
                INSERT INTO queue_messages
                (topic, payload, headers, correlation_id, message_group, status, created_at, visible_at, priority)
                VALUES ($1, $2::jsonb, $3::jsonb, $4, $5, 'AVAILABLE', $6, $7, $8)
                RETURNING id
                """;

            Tuple params = Tuple.of(
                topic,
                payloadJson,
                headersJson,
                finalCorrelationId,
                messageGroup,
                now,
                visibleAt,
                priority
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
                    pool.query("SELECT pg_notify('" + notifyChannel + "', '" + generatedId + "')")
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
