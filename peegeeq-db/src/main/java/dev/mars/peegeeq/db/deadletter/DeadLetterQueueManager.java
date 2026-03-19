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
import dev.mars.peegeeq.api.deadletter.DeadLetterMessageInfo;
import dev.mars.peegeeq.api.deadletter.DeadLetterService;
import dev.mars.peegeeq.api.deadletter.DeadLetterStatsInfo;
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
import java.util.Objects;
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
public class DeadLetterQueueManager implements DeadLetterService {
    private static final Logger logger = LoggerFactory.getLogger(DeadLetterQueueManager.class);

    private final Pool reactivePool;
    private final ObjectMapper objectMapper;

    /**
     * Modern reactive constructor using Vert.x Pool.
     * This is the only constructor - pure Vert.x reactive implementation.
     */
    public DeadLetterQueueManager(Pool reactivePool, ObjectMapper objectMapper) {
        this.reactivePool = Objects.requireNonNull(reactivePool, "Reactive pool cannot be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "Object mapper cannot be null");
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
     * Moves a message to the dead letter queue asynchronously.
     */
    public Future<Void> moveToDeadLetterQueue(String originalTable, long originalId, String topic,
                                              Object payload, Instant originalCreatedAt, String failureReason,
                                              int retryCount, Map<String, String> headers, String correlationId,
                                              String messageGroup) {
        validateMoveToDeadLetterArgs(originalTable, topic, originalCreatedAt, failureReason, retryCount);
        return storeDeadLetterMessage(originalTable, originalId, topic, payload, originalCreatedAt,
            failureReason, retryCount, headers, correlationId, messageGroup);
    }

    Future<Void> storeDeadLetterMessage(String originalTable, long originalId, String topic,
                                               Object payload, Instant originalCreatedAt, String failureReason,
                                               int retryCount, Map<String, String> headers, String correlationId,
                                               String messageGroup) {
        validateMoveToDeadLetterArgs(originalTable, topic, originalCreatedAt, failureReason, retryCount);

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

    Future<DeadLetterQueueStats> fetchStatistics() {
        String sql = """
            SELECT
                COUNT(*) as total_messages,
                COUNT(DISTINCT topic)::INT as unique_topics,
                COUNT(DISTINCT original_table)::INT as unique_tables,
                MIN(failed_at) as oldest_failure,
                MAX(failed_at) as newest_failure,
                AVG(retry_count)::DOUBLE PRECISION as avg_retry_count
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
            return Future.failedFuture(throwable);
        });
    }

    Future<List<DeadLetterMessage>> fetchDeadLetterMessagesByTopic(String topic, int limit, int offset) {
        validatePagination(limit, offset);

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
            return Future.failedFuture(throwable);
        });
    }

    Future<List<DeadLetterMessage>> fetchAllDeadLetterMessages(int limit, int offset) {
        validatePagination(limit, offset);

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
            return Future.failedFuture(throwable);
        });
    }

    Future<Optional<DeadLetterMessage>> fetchDeadLetterMessage(long id) {
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
            return Future.failedFuture(throwable);
        });
    }

    private Future<Optional<DeadLetterMessage>> fetchDeadLetterMessage(long id, io.vertx.sqlclient.SqlConnection connection) {
        String sql = """
            SELECT id, original_table, original_id, topic, payload, original_created_at,
                   failed_at, failure_reason, retry_count, headers, correlation_id, message_group
            FROM dead_letter_queue
            WHERE id = $1
            FOR UPDATE
            """;

        return connection.preparedQuery(sql)
            .execute(Tuple.of(id))
            .map(rowSet -> {
                if (rowSet.iterator().hasNext()) {
                    Row row = rowSet.iterator().next();
                    return Optional.of(mapRowToDeadLetterMessage(row));
                }
                return Optional.<DeadLetterMessage>empty();
            });
    }

    Future<Boolean> reprocessDeadLetterMessageRecord(long deadLetterMessageId, String reason) {
        return reactivePool.withTransaction(connection -> {
            // Get the dead letter message first
            return fetchDeadLetterMessage(deadLetterMessageId, connection)
                .compose(dlmOpt -> {
                    if (dlmOpt.isEmpty()) {
                        logger.debug("Dead letter message not found: {}", deadLetterMessageId);
                        return Future.succeededFuture(false);
                    }

                    DeadLetterMessage dlm = dlmOpt.get();

                    // Insert back into original table
                    String insertSql = getInsertSqlForTable(dlm.getOriginalTable());
                    Tuple insertParams = createInsertTuple(dlm);

                    return connection.preparedQuery(insertSql)
                        .execute(insertParams)
                        .compose(insertResult -> {
                            // Delete from dead letter queue
                            String deleteSql = "DELETE FROM dead_letter_queue WHERE id = $1";
                            return connection.preparedQuery(deleteSql)
                                .execute(Tuple.of(deadLetterMessageId));
                        })
                        .compose(deleteResult -> {
                            if (deleteResult.rowCount() != 1) {
                                return Future.failedFuture(new IllegalStateException(
                                    "Concurrent reprocess detected for dead letter message " + deadLetterMessageId));
                            }
                            logger.info("Reprocessed dead letter message: id={}, originalTable={}, reason={}",
                                deadLetterMessageId, dlm.getOriginalTable(), reason);
                            return Future.succeededFuture(true);
                        });
                });
        }).recover(throwable -> {
            logger.error("Failed to reprocess dead letter message (reactive): {}", deadLetterMessageId, throwable);
            return Future.failedFuture(throwable);
        });
    }

    Future<Boolean> removeDeadLetterMessage(long id, String reason) {
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
            return Future.failedFuture(throwable);
        });
    }

    public Future<Integer> purgeOldDeadLetterMessages(int retentionDays) {
        if (retentionDays <= 0) {
            return Future.failedFuture(new IllegalArgumentException("retentionDays must be > 0"));
        }
        String sql = "DELETE FROM dead_letter_queue WHERE failed_at < NOW() - ($1 * INTERVAL '1 day')";

        return reactivePool.withTransaction(connection -> {
            return connection.preparedQuery(sql)
                .execute(Tuple.of(retentionDays))
                .map(result -> {
                    int deleted = result.rowCount();
                    if (deleted > 0) {
                        logger.info("Cleaned up {} old dead letter messages (retention: {} days)", deleted, retentionDays);
                    }
                    return deleted;
                });
        }).recover(throwable -> {
            logger.error("Failed to cleanup old dead letter messages (reactive)", throwable);
            return Future.failedFuture(throwable);
        });
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
                    // If JSON deserialization fails, store raw JSON as single-entry fallback map
                    logger.warn("Failed to deserialize headers for dead letter message: {}",
                        jsonException.getMessage());
                    headers = Map.of("_raw_headers", headersObj.toString());
                }
            }

            OffsetDateTime originalCreatedAt = row.getOffsetDateTime("original_created_at");
            OffsetDateTime failedAt = row.getOffsetDateTime("failed_at");

            return new DeadLetterMessage(
                row.getLong("id"),
                row.getString("original_table"),
                row.getLong("original_id"),
                row.getString("topic"),
                row.getValue("payload").toString(),
                originalCreatedAt.toInstant(),
                (failedAt != null ? failedAt : originalCreatedAt).toInstant(),
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

    private String getInsertSqlForTable(String tableName) {
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

    // ========================================
    // DeadLetterService API Interface Implementation
    // ========================================

    /**
     * Converts internal DeadLetterMessage to API DeadLetterMessageInfo.
     */
    private DeadLetterMessageInfo toDeadLetterMessageInfo(DeadLetterMessage msg) {
        return DeadLetterMessageInfo.builder()
            .id(msg.getId())
            .originalTable(msg.getOriginalTable())
            .originalId(msg.getOriginalId())
            .topic(msg.getTopic())
            .payload(msg.getPayload())
            .originalCreatedAt(msg.getOriginalCreatedAt())
            .failedAt(msg.getFailedAt())
            .failureReason(msg.getFailureReason())
            .retryCount(msg.getRetryCount())
            .headers(msg.getHeaders())
            .correlationId(msg.getCorrelationId())
            .messageGroup(msg.getMessageGroup())
            .build();
    }

    /**
     * Converts internal DeadLetterQueueStats to API DeadLetterStatsInfo.
     */
    private DeadLetterStatsInfo toDeadLetterStatsInfo(DeadLetterQueueStats stats) {
        return DeadLetterStatsInfo.builder()
            .totalMessages(stats.getTotalMessages())
            .uniqueTopics(stats.getUniqueTopics())
            .uniqueTables(stats.getUniqueTables())
            .oldestFailure(stats.getOldestFailure())
            .newestFailure(stats.getNewestFailure())
            .averageRetryCount(stats.getAverageRetryCount())
            .build();
    }

    @Override
    public io.vertx.core.Future<List<DeadLetterMessageInfo>> getDeadLetterMessages(String topic, int limit, int offset) {
        validatePagination(limit, offset);
        return fetchDeadLetterMessagesByTopic(topic, limit, offset)
            .map(list -> list.stream().map(this::toDeadLetterMessageInfo).toList());
    }

    @Override
    public io.vertx.core.Future<List<DeadLetterMessageInfo>> getAllDeadLetterMessages(int limit, int offset) {
        validatePagination(limit, offset);
        return fetchAllDeadLetterMessages(limit, offset)
            .map(list -> list.stream().map(this::toDeadLetterMessageInfo).toList());
    }

    @Override
    public io.vertx.core.Future<Optional<DeadLetterMessageInfo>> getDeadLetterMessage(long id) {
        return fetchDeadLetterMessage(id)
            .map(opt -> opt.map(this::toDeadLetterMessageInfo));
    }

    @Override
    public io.vertx.core.Future<Boolean> reprocessDeadLetterMessage(long id, String reason) {
        return reprocessDeadLetterMessageRecord(id, reason);
    }

    @Override
    public io.vertx.core.Future<Boolean> deleteDeadLetterMessage(long id, String reason) {
        return removeDeadLetterMessage(id, reason);
    }

    @Override
    public io.vertx.core.Future<DeadLetterStatsInfo> getStatistics() {
        return fetchStatistics()
            .map(this::toDeadLetterStatsInfo);
    }

    @Override
    public io.vertx.core.Future<Integer> cleanupOldMessages(int retentionDays) {
        return purgeOldDeadLetterMessages(retentionDays);
    }

    private void validateMoveToDeadLetterArgs(String originalTable, String topic, Instant originalCreatedAt,
                                              String failureReason, int retryCount) {
        Objects.requireNonNull(originalTable, "originalTable cannot be null");
        Objects.requireNonNull(topic, "topic cannot be null");
        Objects.requireNonNull(originalCreatedAt, "originalCreatedAt cannot be null");
        Objects.requireNonNull(failureReason, "failureReason cannot be null");
        if (retryCount < 0) {
            throw new IllegalArgumentException("retryCount must be >= 0");
        }
    }

    private void validatePagination(int limit, int offset) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be > 0");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0");
        }
    }

    // ========================================
    // End DeadLetterService API Interface
    // ========================================
}
