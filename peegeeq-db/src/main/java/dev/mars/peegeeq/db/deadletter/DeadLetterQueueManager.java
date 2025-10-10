package dev.mars.peegeeq.db.deadletter;

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


import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Manages dead letter queue operations for failed messages.
 * 
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
public class DeadLetterQueueManager {
    private static final Logger logger = LoggerFactory.getLogger(DeadLetterQueueManager.class);

    private final Pool reactivePool;
    private final ObjectMapper objectMapper;

    /**
     * Modern reactive constructor using Vert.x Pool.
     * This is the only constructor - pure Vert.x reactive implementation.
     */
    public DeadLetterQueueManager(Pool reactivePool, ObjectMapper objectMapper) {
        this.reactivePool = reactivePool;
        this.objectMapper = objectMapper;
    }

    /**
     * Converts any object to JsonObject for JSONB storage.
     * Follows the pattern established in peegeeq-native and peegeeq-outbox modules.
     */
    private JsonObject toJsonObject(Object value) {
        if (value == null) return new JsonObject();
        if (value instanceof JsonObject) return (JsonObject) value;
        if (value instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) value;
            return new JsonObject(map);
        }
        // Handle primitive types (String, Number, Boolean) by wrapping them
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return new JsonObject().put("value", value);
        }
        // For complex objects, use mapFrom
        return JsonObject.mapFrom(value);
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

    /**
     * Moves a message to the dead letter queue.
     */
    public void moveToDeadLetterQueue(String originalTable, long originalId, String topic,
                                    Object payload, Instant originalCreatedAt, String failureReason,
                                    int retryCount, Map<String, String> headers, String correlationId,
                                    String messageGroup) {
        // Use reactive approach - block on the result for compatibility with synchronous interface
        try {
            moveToDeadLetterQueueReactive(originalTable, originalId, topic, payload, originalCreatedAt,
                failureReason, retryCount, headers, correlationId, messageGroup)
                .toCompletionStage().toCompletableFuture().get();
        } catch (Exception e) {
            logger.error("Failed to move message to dead letter queue (reactive): table={}, id={}",
                originalTable, originalId, e);
            throw new RuntimeException("Failed to move message to dead letter queue", e);
        }
    }

    private Future<Void> moveToDeadLetterQueueReactive(String originalTable, long originalId, String topic,
                                                      Object payload, Instant originalCreatedAt, String failureReason,
                                                      int retryCount, Map<String, String> headers, String correlationId,
                                                      String messageGroup) {
        String sql = """
            INSERT INTO dead_letter_queue
            (original_table, original_id, topic, payload, original_created_at, failure_reason,
             retry_count, headers, correlation_id, message_group)
            VALUES ($1, $2, $3, $4::jsonb, $5, $6, $7, $8::jsonb, $9, $10)
            """;

        try {
            JsonObject payloadJson = toJsonObject(payload);
            JsonObject headersJson = headersToJsonObject(headers);

            // Convert Instant to OffsetDateTime for Vert.x PostgreSQL client
            OffsetDateTime originalCreatedAtOffset = originalCreatedAt.atOffset(ZoneOffset.UTC);

            Tuple params = Tuple.of(originalTable, originalId, topic, payloadJson, originalCreatedAtOffset,
                failureReason, retryCount, headersJson, correlationId, messageGroup);

            return reactivePool.withTransaction(connection -> {
                return connection.preparedQuery(sql).execute(params)
                    .map(rowSet -> {
                        if (rowSet.rowCount() > 0) {
                            logger.info("Moved message to dead letter queue: table={}, id={}, topic={}, reason={}",
                                originalTable, originalId, topic, failureReason);
                        }
                        return (Void) null;
                    });
            }).recover(throwable -> {
                logger.error("Failed to move message to dead letter queue (reactive): table={}, id={}",
                    originalTable, originalId, throwable);
                return Future.failedFuture(throwable);
            });
        } catch (Exception e) {
            return Future.failedFuture(e);
        }
    }

    /**
     * Retrieves dead letter messages by topic.
     */
    public List<DeadLetterMessage> getDeadLetterMessages(String topic, int limit, int offset) {
        logger.debug("🔧 DEBUG: getDeadLetterMessages called with topic: {}, limit: {}, offset: {}", topic, limit, offset);
        logger.debug("🔧 DEBUG: Using reactive approach for getDeadLetterMessages");

        // Use reactive approach - block on the result for compatibility with synchronous interface
        try {
            return getDeadLetterMessagesReactive(topic, limit, offset)
                .toCompletionStage().toCompletableFuture().get();
        } catch (Exception e) {
            logger.error("Failed to retrieve dead letter messages for topic (reactive): {}", topic, e);
            throw new RuntimeException("Failed to retrieve dead letter messages", e);
        }
    }

    /**
     * Retrieves all dead letter messages with pagination.
     */
    public List<DeadLetterMessage> getAllDeadLetterMessages(int limit, int offset) {
        // Use reactive approach - block on the result for compatibility with synchronous interface
        try {
            return getAllDeadLetterMessagesReactive(limit, offset)
                .toCompletionStage().toCompletableFuture().get();
        } catch (Exception e) {
            logger.error("Failed to retrieve all dead letter messages (reactive)", e);
            throw new RuntimeException("Failed to retrieve dead letter messages", e);
        }
    }

    /**
     * Gets a specific dead letter message by ID.
     */
    public Optional<DeadLetterMessage> getDeadLetterMessage(long id) {
        // Use reactive approach - block on the result for compatibility with synchronous interface
        try {
            return getDeadLetterMessageReactive(id)
                .toCompletionStage().toCompletableFuture().get();
        } catch (Exception e) {
            logger.error("Failed to retrieve dead letter message with id (reactive): {}", id, e);
            throw new RuntimeException("Failed to retrieve dead letter message", e);
        }
    }

    /**
     * Reprocesses a dead letter message by moving it back to the original queue.
     */
    public boolean reprocessDeadLetterMessage(long deadLetterMessageId, String reason) {
        // Use reactive approach - block on the result for compatibility with synchronous interface
        try {
            return reprocessDeadLetterMessageReactive(deadLetterMessageId, reason)
                .toCompletionStage().toCompletableFuture().get();
        } catch (Exception e) {
            logger.error("Failed to reprocess dead letter message (reactive): {}", deadLetterMessageId, e);
            throw new RuntimeException("Failed to reprocess dead letter message", e);
        }
    }

    /**
     * Deletes a dead letter message permanently.
     */
    public boolean deleteDeadLetterMessage(long id, String reason) {
        // Use reactive approach - block on the result for compatibility with synchronous interface
        try {
            return deleteDeadLetterMessageReactive(id, reason)
                .toCompletionStage().toCompletableFuture().get();
        } catch (Exception e) {
            logger.error("Failed to delete dead letter message (reactive): {}", id, e);
            throw new RuntimeException("Failed to delete dead letter message", e);
        }
    }

    /**
     * Gets dead letter queue statistics.
     */
    public DeadLetterQueueStats getStatistics() {
        // Use reactive approach - block on the result for compatibility with synchronous interface
        try {
            return getStatisticsReactive()
                .toCompletionStage().toCompletableFuture().get();
        } catch (Exception e) {
            logger.error("Failed to get dead letter queue statistics (reactive)", e);
            throw new RuntimeException("Failed to get statistics", e);
        }
    }

    private Future<DeadLetterQueueStats> getStatisticsReactive() {
        String sql = """
            SELECT
                COUNT(*) as total_messages,
                COUNT(DISTINCT topic) as unique_topics,
                COUNT(DISTINCT original_table) as unique_tables,
                MIN(failed_at) as oldest_failure,
                MAX(failed_at) as newest_failure,
                AVG(retry_count) as avg_retry_count
            FROM dead_letter_queue
            """;

        return reactivePool.withConnection(connection -> {
            return connection.preparedQuery(sql).execute()
                .map(rowSet -> {
                    if (rowSet.iterator().hasNext()) {
                        Row row = rowSet.iterator().next();
                        return new DeadLetterQueueStats(
                            row.getLong("total_messages"),
                            row.getInteger("unique_topics"),
                            row.getInteger("unique_tables"),
                            row.getOffsetDateTime("oldest_failure") != null ? row.getOffsetDateTime("oldest_failure").toInstant() : null,
                            row.getOffsetDateTime("newest_failure") != null ? row.getOffsetDateTime("newest_failure").toInstant() : null,
                            row.getDouble("avg_retry_count") != null ? row.getDouble("avg_retry_count") : 0.0
                        );
                    } else {
                        return new DeadLetterQueueStats(0, 0, 0, null, null, 0.0);
                    }
                });
        }).recover(throwable -> {
            logger.error("Failed to get dead letter queue statistics (reactive)", throwable);
            return Future.succeededFuture(new DeadLetterQueueStats(0, 0, 0, null, null, 0.0));
        });
    }

    private Future<List<DeadLetterMessage>> getDeadLetterMessagesReactive(String topic, int limit, int offset) {
        String sql = """
            SELECT id, original_table, original_id, topic, payload, original_created_at,
                   failed_at, failure_reason, retry_count, headers, correlation_id, message_group
            FROM dead_letter_queue
            WHERE topic = $1
            ORDER BY failed_at DESC
            LIMIT $2 OFFSET $3
            """;

        return reactivePool.withConnection(connection -> {
            return connection.preparedQuery(sql)
                .execute(Tuple.of(topic, limit, offset))
                .map(rowSet -> {
                    List<DeadLetterMessage> messages = new ArrayList<>();
                    for (Row row : rowSet) {
                        messages.add(mapRowToDeadLetterMessage(row));
                    }
                    return messages;
                });
        }).recover(throwable -> {
            logger.error("Failed to retrieve dead letter messages for topic (reactive): {}", topic, throwable);
            return Future.succeededFuture(new ArrayList<>());
        });
    }

    private Future<List<DeadLetterMessage>> getAllDeadLetterMessagesReactive(int limit, int offset) {
        String sql = """
            SELECT id, original_table, original_id, topic, payload, original_created_at,
                   failed_at, failure_reason, retry_count, headers, correlation_id, message_group
            FROM dead_letter_queue
            ORDER BY failed_at DESC
            LIMIT $1 OFFSET $2
            """;

        return reactivePool.withConnection(connection -> {
            return connection.preparedQuery(sql)
                .execute(Tuple.of(limit, offset))
                .map(rowSet -> {
                    List<DeadLetterMessage> messages = new ArrayList<>();
                    for (Row row : rowSet) {
                        messages.add(mapRowToDeadLetterMessage(row));
                    }
                    return messages;
                });
        }).recover(throwable -> {
            logger.error("Failed to retrieve all dead letter messages (reactive)", throwable);
            return Future.succeededFuture(new ArrayList<>());
        });
    }

    private Future<Optional<DeadLetterMessage>> getDeadLetterMessageReactive(long id) {
        String sql = """
            SELECT id, original_table, original_id, topic, payload, original_created_at,
                   failed_at, failure_reason, retry_count, headers, correlation_id, message_group
            FROM dead_letter_queue
            WHERE id = $1
            """;

        return reactivePool.withConnection(connection -> {
            return connection.preparedQuery(sql)
                .execute(Tuple.of(id))
                .map(rowSet -> {
                    if (rowSet.iterator().hasNext()) {
                        Row row = rowSet.iterator().next();
                        return Optional.of(mapRowToDeadLetterMessage(row));
                    } else {
                        return Optional.<DeadLetterMessage>empty();
                    }
                });
        }).recover(throwable -> {
            logger.error("Failed to retrieve dead letter message with id (reactive): {}", id, throwable);
            return Future.succeededFuture(Optional.empty());
        });
    }

    private Future<Boolean> reprocessDeadLetterMessageReactive(long deadLetterMessageId, String reason) {
        return reactivePool.withTransaction(connection -> {
            // Get the dead letter message first
            return getDeadLetterMessageReactive(deadLetterMessageId)
                .compose(dlmOpt -> {
                    if (dlmOpt.isEmpty()) {
                        logger.warn("Dead letter message not found: {}", deadLetterMessageId);
                        return Future.succeededFuture(false);
                    }

                    DeadLetterMessage dlm = dlmOpt.get();

                    // Insert back into original table
                    String insertSql = getInsertSqlForTableReactive(dlm.getOriginalTable());
                    Tuple insertParams = createInsertTuple(dlm);

                    return connection.preparedQuery(insertSql)
                        .execute(insertParams)
                        .compose(insertResult -> {
                            // Delete from dead letter queue
                            String deleteSql = "DELETE FROM dead_letter_queue WHERE id = $1";
                            return connection.preparedQuery(deleteSql)
                                .execute(Tuple.of(deadLetterMessageId));
                        })
                        .map(deleteResult -> {
                            logger.info("Reprocessed dead letter message: id={}, originalTable={}, reason={}",
                                deadLetterMessageId, dlm.getOriginalTable(), reason);
                            return true;
                        });
                });
        }).recover(throwable -> {
            logger.error("Failed to reprocess dead letter message (reactive): {}", deadLetterMessageId, throwable);
            return Future.succeededFuture(false);
        });
    }

    private Future<Boolean> deleteDeadLetterMessageReactive(long id, String reason) {
        String sql = "DELETE FROM dead_letter_queue WHERE id = $1";

        return reactivePool.withTransaction(connection -> {
            return connection.preparedQuery(sql)
                .execute(Tuple.of(id))
                .map(result -> {
                    int affected = result.rowCount();
                    if (affected > 0) {
                        logger.info("Deleted dead letter message: id={}, reason={}", id, reason);
                        return true;
                    } else {
                        logger.warn("Dead letter message not found for deletion: {}", id);
                        return false;
                    }
                });
        }).recover(throwable -> {
            logger.error("Failed to delete dead letter message (reactive): {}", id, throwable);
            return Future.succeededFuture(false);
        });
    }

    private Future<Integer> cleanupOldMessagesReactive(int retentionDays) {
        String sql = "DELETE FROM dead_letter_queue WHERE failed_at < NOW() - INTERVAL '" + retentionDays + " days'";

        return reactivePool.withTransaction(connection -> {
            return connection.query(sql)
                .execute()
                .map(result -> {
                    int deleted = result.rowCount();
                    if (deleted > 0) {
                        logger.info("Cleaned up {} old dead letter messages (retention: {} days)", deleted, retentionDays);
                    }
                    return deleted;
                });
        }).recover(throwable -> {
            logger.error("Failed to cleanup old dead letter messages (reactive)", throwable);
            return Future.succeededFuture(0);
        });
    }

    /**
     * Cleans up old dead letter messages based on retention policy.
     */
    public int cleanupOldMessages(int retentionDays) {
        // Use reactive approach - block on the result for compatibility with synchronous interface
        try {
            return cleanupOldMessagesReactive(retentionDays)
                .toCompletionStage().toCompletableFuture().get();
        } catch (Exception e) {
            logger.error("Failed to cleanup old dead letter messages (reactive)", e);
            throw new RuntimeException("Failed to cleanup old messages", e);
        }
    }



    private DeadLetterMessage mapRowToDeadLetterMessage(Row row) {
        try {
            Map<String, String> headers = null;
            Object headersObj = row.getValue("headers");

            // Handle headers deserialization with robust error handling
            if (headersObj != null) {
                try {
                    String headersJson = headersObj.toString();
                    if (!headersJson.trim().isEmpty()) {
                        headers = objectMapper.readValue(headersJson, new TypeReference<Map<String, String>>() {});
                    }
                } catch (Exception jsonException) {
                    // If JSON deserialization fails, log the error and continue with null headers
                    logger.warn("Failed to deserialize headers JSON '{}': {}. Using null headers.",
                        headersObj, jsonException.getMessage());
                    headers = null;
                }
            }

            return new DeadLetterMessage(
                row.getLong("id"),
                row.getString("original_table"),
                row.getLong("original_id"),
                row.getString("topic"),
                row.getValue("payload").toString(),
                row.getOffsetDateTime("original_created_at").toInstant(),
                row.getOffsetDateTime("failed_at").toInstant(),
                row.getString("failure_reason"),
                row.getInteger("retry_count"),
                headers,
                row.getString("correlation_id"),
                row.getString("message_group")
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to map row to DeadLetterMessage", e);
        }
    }

    private String getInsertSqlForTableReactive(String tableName) {
        return switch (tableName) {
            case "outbox" -> """
                INSERT INTO outbox (topic, payload, status, retry_count, headers, correlation_id, message_group)
                VALUES ($1, $2::jsonb, 'PENDING', 0, $3::jsonb, $4, $5)
                """;
            case "queue_messages" -> """
                INSERT INTO queue_messages (topic, payload, status, retry_count, headers, correlation_id, message_group)
                VALUES ($1, $2::jsonb, 'AVAILABLE', 0, $3::jsonb, $4, $5)
                """;
            default -> throw new IllegalArgumentException("Unknown table: " + tableName);
        };
    }



    private Tuple createInsertTuple(DeadLetterMessage dlm) {
        JsonObject headersJson = headersToJsonObject(dlm.getHeaders());

        return Tuple.of(
            dlm.getTopic(),
            dlm.getPayload(),
            headersJson,
            dlm.getCorrelationId(),
            dlm.getMessageGroup()
        );
    }
}
