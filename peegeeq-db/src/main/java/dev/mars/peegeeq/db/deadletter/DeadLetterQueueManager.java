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
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
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

    private final DataSource dataSource;
    private final Pool reactivePool;
    private final ObjectMapper objectMapper;

    /**
     * Legacy constructor using DataSource.
     * @deprecated Use DeadLetterQueueManager(Pool, ObjectMapper) for reactive patterns
     */
    @Deprecated
    public DeadLetterQueueManager(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.reactivePool = null;
        this.objectMapper = objectMapper;
    }

    /**
     * Modern reactive constructor using Vert.x Pool.
     * This is the preferred constructor for Vert.x 5.x reactive patterns.
     */
    public DeadLetterQueueManager(Pool reactivePool, ObjectMapper objectMapper) {
        this.dataSource = null;
        this.reactivePool = reactivePool;
        this.objectMapper = objectMapper;
    }

    /**
     * Moves a message to the dead letter queue.
     */
    public void moveToDeadLetterQueue(String originalTable, long originalId, String topic,
                                    Object payload, Instant originalCreatedAt, String failureReason,
                                    int retryCount, Map<String, String> headers, String correlationId,
                                    String messageGroup) {
        if (reactivePool != null) {
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
        } else {
            // Use legacy JDBC approach
            String sql = """
                INSERT INTO dead_letter_queue
                (original_table, original_id, topic, payload, original_created_at, failure_reason,
                 retry_count, headers, correlation_id, message_group)
                VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?::jsonb, ?, ?)
                """;

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setString(1, originalTable);
                stmt.setLong(2, originalId);
                stmt.setString(3, topic);
                stmt.setString(4, objectMapper.writeValueAsString(payload));
                stmt.setTimestamp(5, Timestamp.from(originalCreatedAt));
                stmt.setString(6, failureReason);
                stmt.setInt(7, retryCount);
                if (headers != null) {
                    stmt.setString(8, objectMapper.writeValueAsString(headers));
                } else {
                    stmt.setNull(8, java.sql.Types.VARCHAR);
                }
                stmt.setString(9, correlationId);
                stmt.setString(10, messageGroup);

                int affected = stmt.executeUpdate();
                if (affected > 0) {
                    logger.info("Moved message to dead letter queue: table={}, id={}, topic={}, reason={}",
                        originalTable, originalId, topic, failureReason);
                }

            } catch (Exception e) {
                logger.error("Failed to move message to dead letter queue: table={}, id={}",
                    originalTable, originalId, e);
                throw new RuntimeException("Failed to move message to dead letter queue", e);
            }
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
            String payloadJson = objectMapper.writeValueAsString(payload);
            String headersJson = headers != null ? objectMapper.writeValueAsString(headers) : "{}";

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
        logger.debug("🔧 DEBUG: reactivePool is null: {}", reactivePool == null);
        logger.debug("🔧 DEBUG: dataSource is null: {}", dataSource == null);

        if (reactivePool != null) {
            logger.debug("🔧 DEBUG: Using reactive approach for getDeadLetterMessages");
            // Use reactive approach - block on the result for compatibility with synchronous interface
            try {
                return getDeadLetterMessagesReactive(topic, limit, offset)
                    .toCompletionStage().toCompletableFuture().get();
            } catch (Exception e) {
                logger.error("Failed to retrieve dead letter messages for topic (reactive): {}", topic, e);
                throw new RuntimeException("Failed to retrieve dead letter messages", e);
            }
        } else {
            logger.debug("🔧 DEBUG: Using JDBC fallback approach for getDeadLetterMessages");
            // Use legacy JDBC approach
            String sql = """
                SELECT id, original_table, original_id, topic, payload, original_created_at,
                       failed_at, failure_reason, retry_count, headers, correlation_id, message_group
                FROM dead_letter_queue
                WHERE topic = ?
                ORDER BY failed_at DESC
                LIMIT ? OFFSET ?
                """;

            List<DeadLetterMessage> messages = new ArrayList<>();

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setString(1, topic);
                stmt.setInt(2, limit);
                stmt.setInt(3, offset);

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        messages.add(mapResultSetToDeadLetterMessage(rs));
                    }
                }

            } catch (SQLException e) {
                logger.error("Failed to retrieve dead letter messages for topic: {}", topic, e);
                throw new RuntimeException("Failed to retrieve dead letter messages", e);
            }

            return messages;
        }
    }

    /**
     * Retrieves all dead letter messages with pagination.
     */
    public List<DeadLetterMessage> getAllDeadLetterMessages(int limit, int offset) {
        if (reactivePool != null) {
            // Use reactive approach - block on the result for compatibility with synchronous interface
            try {
                return getAllDeadLetterMessagesReactive(limit, offset)
                    .toCompletionStage().toCompletableFuture().get();
            } catch (Exception e) {
                logger.error("Failed to retrieve all dead letter messages (reactive)", e);
                throw new RuntimeException("Failed to retrieve dead letter messages", e);
            }
        } else {
            // Use legacy JDBC approach
            String sql = """
                SELECT id, original_table, original_id, topic, payload, original_created_at,
                       failed_at, failure_reason, retry_count, headers, correlation_id, message_group
                FROM dead_letter_queue
                ORDER BY failed_at DESC
                LIMIT ? OFFSET ?
                """;

            List<DeadLetterMessage> messages = new ArrayList<>();

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setInt(1, limit);
                stmt.setInt(2, offset);

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        messages.add(mapResultSetToDeadLetterMessage(rs));
                    }
                }

            } catch (SQLException e) {
                logger.error("Failed to retrieve all dead letter messages", e);
                throw new RuntimeException("Failed to retrieve dead letter messages", e);
            }

            return messages;
        }
    }

    /**
     * Gets a specific dead letter message by ID.
     */
    public Optional<DeadLetterMessage> getDeadLetterMessage(long id) {
        if (reactivePool != null) {
            // Use reactive approach - block on the result for compatibility with synchronous interface
            try {
                return getDeadLetterMessageReactive(id)
                    .toCompletionStage().toCompletableFuture().get();
            } catch (Exception e) {
                logger.error("Failed to retrieve dead letter message with id (reactive): {}", id, e);
                throw new RuntimeException("Failed to retrieve dead letter message", e);
            }
        } else {
            // Use legacy JDBC approach
            String sql = """
                SELECT id, original_table, original_id, topic, payload, original_created_at,
                       failed_at, failure_reason, retry_count, headers, correlation_id, message_group
                FROM dead_letter_queue
                WHERE id = ?
                """;

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setLong(1, id);

                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapResultSetToDeadLetterMessage(rs));
                    }
                }

            } catch (SQLException e) {
                logger.error("Failed to retrieve dead letter message with id: {}", id, e);
                throw new RuntimeException("Failed to retrieve dead letter message", e);
            }

            return Optional.empty();
        }
    }

    /**
     * Reprocesses a dead letter message by moving it back to the original queue.
     */
    public boolean reprocessDeadLetterMessage(long deadLetterMessageId, String reason) {
        if (reactivePool != null) {
            // Use reactive approach - block on the result for compatibility with synchronous interface
            try {
                return reprocessDeadLetterMessageReactive(deadLetterMessageId, reason)
                    .toCompletionStage().toCompletableFuture().get();
            } catch (Exception e) {
                logger.error("Failed to reprocess dead letter message (reactive): {}", deadLetterMessageId, e);
                throw new RuntimeException("Failed to reprocess dead letter message", e);
            }
        } else {
            // Use legacy JDBC approach
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);

                try {
                    // Get the dead letter message
                    Optional<DeadLetterMessage> dlmOpt = getDeadLetterMessage(deadLetterMessageId);
                    if (dlmOpt.isEmpty()) {
                        logger.warn("Dead letter message not found: {}", deadLetterMessageId);
                        return false;
                    }

                    DeadLetterMessage dlm = dlmOpt.get();

                    // Insert back into original table
                    String insertSql = getInsertSqlForTable(dlm.getOriginalTable());
                    try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                        populateInsertStatement(insertStmt, dlm);
                        insertStmt.executeUpdate();
                    }

                    // Delete from dead letter queue
                    String deleteSql = "DELETE FROM dead_letter_queue WHERE id = ?";
                    try (PreparedStatement deleteStmt = conn.prepareStatement(deleteSql)) {
                        deleteStmt.setLong(1, deadLetterMessageId);
                        deleteStmt.executeUpdate();
                    }

                    conn.commit();

                    logger.info("Reprocessed dead letter message: id={}, originalTable={}, reason={}",
                        deadLetterMessageId, dlm.getOriginalTable(), reason);

                    return true;

                } catch (Exception e) {
                    conn.rollback();
                    logger.error("Failed to reprocess dead letter message: {}", deadLetterMessageId, e);
                    throw new RuntimeException("Failed to reprocess dead letter message", e);
                }

            } catch (SQLException e) {
                logger.error("Database error while reprocessing dead letter message: {}", deadLetterMessageId, e);
                throw new RuntimeException("Database error during reprocessing", e);
            }
        }
    }

    /**
     * Deletes a dead letter message permanently.
     */
    public boolean deleteDeadLetterMessage(long id, String reason) {
        if (reactivePool != null) {
            // Use reactive approach - block on the result for compatibility with synchronous interface
            try {
                return deleteDeadLetterMessageReactive(id, reason)
                    .toCompletionStage().toCompletableFuture().get();
            } catch (Exception e) {
                logger.error("Failed to delete dead letter message (reactive): {}", id, e);
                throw new RuntimeException("Failed to delete dead letter message", e);
            }
        } else {
            // Use legacy JDBC approach
            String sql = "DELETE FROM dead_letter_queue WHERE id = ?";

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setLong(1, id);
                int affected = stmt.executeUpdate();

                if (affected > 0) {
                    logger.info("Deleted dead letter message: id={}, reason={}", id, reason);
                    return true;
                } else {
                    logger.warn("Dead letter message not found for deletion: {}", id);
                    return false;
                }

            } catch (SQLException e) {
                logger.error("Failed to delete dead letter message: {}", id, e);
                throw new RuntimeException("Failed to delete dead letter message", e);
            }
        }
    }

    /**
     * Gets dead letter queue statistics.
     */
    public DeadLetterQueueStats getStatistics() {
        if (reactivePool != null) {
            // Use reactive approach - block on the result for compatibility with synchronous interface
            try {
                return getStatisticsReactive()
                    .toCompletionStage().toCompletableFuture().get();
            } catch (Exception e) {
                logger.error("Failed to get dead letter queue statistics (reactive)", e);
                throw new RuntimeException("Failed to get statistics", e);
            }
        } else {
            // Use legacy JDBC approach
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

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {

                if (rs.next()) {
                    return new DeadLetterQueueStats(
                        rs.getLong("total_messages"),
                        rs.getInt("unique_topics"),
                        rs.getInt("unique_tables"),
                        rs.getTimestamp("oldest_failure") != null ? rs.getTimestamp("oldest_failure").toInstant() : null,
                        rs.getTimestamp("newest_failure") != null ? rs.getTimestamp("newest_failure").toInstant() : null,
                        rs.getDouble("avg_retry_count")
                    );
                }

            } catch (SQLException e) {
                logger.error("Failed to get dead letter queue statistics", e);
                throw new RuntimeException("Failed to get statistics", e);
            }

            return new DeadLetterQueueStats(0, 0, 0, null, null, 0.0);
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
        if (reactivePool != null) {
            // Use reactive approach - block on the result for compatibility with synchronous interface
            try {
                return cleanupOldMessagesReactive(retentionDays)
                    .toCompletionStage().toCompletableFuture().get();
            } catch (Exception e) {
                logger.error("Failed to cleanup old dead letter messages (reactive)", e);
                throw new RuntimeException("Failed to cleanup old messages", e);
            }
        } else {
            // Use legacy JDBC approach
            String sql = "DELETE FROM dead_letter_queue WHERE failed_at < NOW() - INTERVAL '" + retentionDays + " days'";

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {

                int deleted = stmt.executeUpdate();

                if (deleted > 0) {
                    logger.info("Cleaned up {} old dead letter messages (retention: {} days)", deleted, retentionDays);
                }

                return deleted;

            } catch (SQLException e) {
                logger.error("Failed to cleanup old dead letter messages", e);
                throw new RuntimeException("Failed to cleanup old messages", e);
            }
        }
    }

    private DeadLetterMessage mapResultSetToDeadLetterMessage(ResultSet rs) throws SQLException {
        try {
            Map<String, String> headers = null;
            String headersJson = rs.getString("headers");

            // Handle headers deserialization with robust error handling
            if (headersJson != null && !headersJson.trim().isEmpty()) {
                try {
                    headers = objectMapper.readValue(headersJson, new TypeReference<Map<String, String>>() {});
                } catch (Exception jsonException) {
                    // If JSON deserialization fails, log the error and continue with null headers
                    logger.warn("Failed to deserialize headers JSON '{}': {}. Using null headers.",
                        headersJson, jsonException.getMessage());
                    headers = null;
                }
            }

            return new DeadLetterMessage(
                rs.getLong("id"),
                rs.getString("original_table"),
                rs.getLong("original_id"),
                rs.getString("topic"),
                rs.getString("payload"),
                rs.getTimestamp("original_created_at").toInstant(),
                rs.getTimestamp("failed_at").toInstant(),
                rs.getString("failure_reason"),
                rs.getInt("retry_count"),
                headers,
                rs.getString("correlation_id"),
                rs.getString("message_group")
            );
        } catch (Exception e) {
            throw new SQLException("Failed to map result set to DeadLetterMessage", e);
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

    private String getInsertSqlForTable(String tableName) {
        return switch (tableName) {
            case "outbox" -> """
                INSERT INTO outbox (topic, payload, status, retry_count, headers, correlation_id, message_group)
                VALUES (?, ?::jsonb, 'PENDING', 0, ?::jsonb, ?, ?)
                """;
            case "queue_messages" -> """
                INSERT INTO queue_messages (topic, payload, status, retry_count, headers, correlation_id, message_group)
                VALUES (?, ?::jsonb, 'AVAILABLE', 0, ?::jsonb, ?, ?)
                """;
            default -> throw new IllegalArgumentException("Unknown table: " + tableName);
        };
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

    private void populateInsertStatement(PreparedStatement stmt, DeadLetterMessage dlm) throws SQLException {
        try {
            stmt.setString(1, dlm.getTopic());
            stmt.setString(2, dlm.getPayload());
            stmt.setString(3, dlm.getHeaders() != null ? 
                objectMapper.writeValueAsString(dlm.getHeaders()) : "{}");
            stmt.setString(4, dlm.getCorrelationId());
            stmt.setString(5, dlm.getMessageGroup());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new SQLException("Failed to serialize headers to JSON", e);
        }
    }

    private Tuple createInsertTuple(DeadLetterMessage dlm) {
        try {
            String headersJson = dlm.getHeaders() != null ?
                objectMapper.writeValueAsString(dlm.getHeaders()) : "{}";

            return Tuple.of(
                dlm.getTopic(),
                dlm.getPayload(),
                headersJson,
                dlm.getCorrelationId(),
                dlm.getMessageGroup()
            );
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize headers to JSON", e);
        }
    }
}
