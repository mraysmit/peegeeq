package dev.mars.peegeeq.db.deadletter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Manages dead letter queue operations for failed messages.
 * Provides functionality to store, retrieve, and reprocess failed messages.
 */
public class DeadLetterQueueManager {
    private static final Logger logger = LoggerFactory.getLogger(DeadLetterQueueManager.class);

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public DeadLetterQueueManager(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
    }

    /**
     * Moves a message to the dead letter queue.
     */
    public void moveToDeadLetterQueue(String originalTable, long originalId, String topic, 
                                    Object payload, Instant originalCreatedAt, String failureReason, 
                                    int retryCount, Map<String, String> headers, String correlationId, 
                                    String messageGroup) {
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
            stmt.setString(8, headers != null ? objectMapper.writeValueAsString(headers) : "{}");
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

    /**
     * Retrieves dead letter messages by topic.
     */
    public List<DeadLetterMessage> getDeadLetterMessages(String topic, int limit, int offset) {
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

    /**
     * Retrieves all dead letter messages with pagination.
     */
    public List<DeadLetterMessage> getAllDeadLetterMessages(int limit, int offset) {
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

    /**
     * Gets a specific dead letter message by ID.
     */
    public Optional<DeadLetterMessage> getDeadLetterMessage(long id) {
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

    /**
     * Reprocesses a dead letter message by moving it back to the original queue.
     */
    public boolean reprocessDeadLetterMessage(long deadLetterMessageId, String reason) {
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

    /**
     * Deletes a dead letter message permanently.
     */
    public boolean deleteDeadLetterMessage(long id, String reason) {
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

    /**
     * Gets dead letter queue statistics.
     */
    public DeadLetterQueueStats getStatistics() {
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

    /**
     * Cleans up old dead letter messages based on retention policy.
     */
    public int cleanupOldMessages(int retentionDays) {
        String sql = "DELETE FROM dead_letter_queue WHERE failed_at < NOW() - INTERVAL '? days'";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setInt(1, retentionDays);
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

    private DeadLetterMessage mapResultSetToDeadLetterMessage(ResultSet rs) throws SQLException {
        try {
            Map<String, String> headers = null;
            String headersJson = rs.getString("headers");
            if (headersJson != null && !headersJson.isEmpty()) {
                headers = objectMapper.readValue(headersJson, Map.class);
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
}
