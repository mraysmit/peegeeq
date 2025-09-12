package dev.mars.peegeeq.db.recovery;

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

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Manages recovery of stuck messages in the outbox pattern.
 * 
 * This class handles the critical issue where consumer crashes can leave messages
 * in "PROCESSING" state indefinitely. It provides a recovery mechanism that:
 * 
 * 1. Identifies messages stuck in PROCESSING state beyond a timeout threshold
 * 2. Resets them back to PENDING state for retry
 * 3. Logs recovery actions for monitoring and debugging
 * 4. Provides metrics on recovery operations
 * 
 * The recovery process is designed to be safe and conservative:
 * - Only processes messages that have been in PROCESSING state for longer than the timeout
 * - Preserves retry counts and error messages
 * - Uses database transactions to ensure consistency
 * - Provides detailed logging for audit trails
 */
public class StuckMessageRecoveryManager {

    private static final Logger logger = LoggerFactory.getLogger(StuckMessageRecoveryManager.class);

    private final DataSource dataSource;
    private final Pool reactivePool;
    private final Duration processingTimeout;
    private final boolean enabled;

    /**
     * Legacy constructor using DataSource.
     * @deprecated Use StuckMessageRecoveryManager(Pool, Duration, boolean) for reactive patterns
     */
    @Deprecated
    public StuckMessageRecoveryManager(DataSource dataSource, Duration processingTimeout, boolean enabled) {
        this.dataSource = dataSource;
        this.reactivePool = null;
        this.processingTimeout = processingTimeout;
        this.enabled = enabled;
        
        logger.info("StuckMessageRecoveryManager initialized - enabled: {}, timeout: {}",
            enabled, processingTimeout);
    }

    /**
     * Modern reactive constructor using Vert.x Pool.
     * This is the preferred constructor for Vert.x 5.x reactive patterns.
     */
    public StuckMessageRecoveryManager(Pool reactivePool, Duration processingTimeout, boolean enabled) {
        this.dataSource = null;
        this.reactivePool = reactivePool;
        this.processingTimeout = processingTimeout;
        this.enabled = enabled;

        logger.info("StuckMessageRecoveryManager initialized (reactive) - enabled: {}, timeout: {}",
            enabled, processingTimeout);
    }

    /**
     * Creates a StuckMessageRecoveryManager with default settings.
     * Default timeout is 5 minutes, enabled by default.
     * @deprecated Use reactive constructor for new code
     */
    @Deprecated
    public StuckMessageRecoveryManager(DataSource dataSource) {
        this(dataSource, Duration.ofMinutes(5), true);
    }

    /**
     * Recovers stuck messages by resetting them from PROCESSING back to PENDING state.
     * 
     * This method identifies messages that have been in PROCESSING state longer than
     * the configured timeout and resets them to PENDING so they can be retried.
     * 
     * @return The number of messages recovered
     */
    public int recoverStuckMessages() {
        if (!enabled) {
            logger.debug("Stuck message recovery is disabled, skipping recovery");
            return 0;
        }

        if (reactivePool != null) {
            // Use reactive approach - block on the result for compatibility with synchronous interface
            try {
                return recoverStuckMessagesReactive()
                    .toCompletionStage().toCompletableFuture().get();
            } catch (Exception e) {
                logger.error("Failed to recover stuck messages (reactive): {}", e.getMessage(), e);
                return 0;
            }
        } else {
            // Use legacy JDBC approach
            logger.debug("Starting stuck message recovery process");

            try (Connection conn = dataSource.getConnection()) {
                // First, identify stuck messages for logging
                int stuckCount = countStuckMessages(conn);
                if (stuckCount == 0) {
                    logger.debug("No stuck messages found");
                    return 0;
                }

                logger.info("Found {} stuck messages in PROCESSING state for longer than {}",
                    stuckCount, processingTimeout);

                // Reset stuck messages back to PENDING
                int recoveredCount = resetStuckMessages(conn);

                // Explicitly commit the transaction since autoCommit is disabled
                conn.commit();

                if (recoveredCount > 0) {
                    logger.info("Successfully recovered {} stuck messages from PROCESSING to PENDING state",
                        recoveredCount);
                }

                return recoveredCount;

            } catch (SQLException e) {
                logger.error("Failed to recover stuck messages: {}", e.getMessage(), e);
                return 0;
            }
        }
    }

    private Future<Integer> recoverStuckMessagesReactive() {
        logger.debug("Starting stuck message recovery process (reactive)");

        return countStuckMessagesReactive()
            .compose(stuckCount -> {
                if (stuckCount == 0) {
                    logger.debug("No stuck messages found");
                    return Future.succeededFuture(0);
                }

                logger.info("Found {} stuck messages in PROCESSING state for longer than {}",
                    stuckCount, processingTimeout);

                return resetStuckMessagesReactive()
                    .map(recoveredCount -> {
                        if (recoveredCount > 0) {
                            logger.info("Successfully recovered {} stuck messages from PROCESSING to PENDING state",
                                recoveredCount);
                        }
                        return recoveredCount;
                    });
            })
            .recover(throwable -> {
                logger.error("Failed to recover stuck messages (reactive): {}", throwable.getMessage(), throwable);
                return Future.succeededFuture(0);
            });
    }

    /**
     * Counts the number of stuck messages without modifying them.
     */
    private int countStuckMessages(Connection conn) throws SQLException {
        String countSql = """
            SELECT COUNT(*) 
            FROM outbox 
            WHERE status = 'PROCESSING' 
            AND processed_at < ?
            """;
        
        try (PreparedStatement stmt = conn.prepareStatement(countSql)) {
            stmt.setTimestamp(1, java.sql.Timestamp.from(Instant.now().minus(processingTimeout)));
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
                return 0;
            }
        }
    }

    private Future<Integer> countStuckMessagesReactive() {
        String countSql = """
            SELECT COUNT(*)
            FROM outbox
            WHERE status = 'PROCESSING'
            AND processed_at < $1
            """;

        OffsetDateTime cutoffTime = Instant.now().minus(processingTimeout).atOffset(ZoneOffset.UTC);
        Tuple params = Tuple.of(cutoffTime);

        return reactivePool.withConnection(connection -> {
            return connection.preparedQuery(countSql).execute(params)
                .map(rowSet -> {
                    if (rowSet.iterator().hasNext()) {
                        return rowSet.iterator().next().getInteger(0);
                    }
                    return 0;
                });
        }).recover(throwable -> {
            logger.error("Failed to count stuck messages (reactive)", throwable);
            return Future.succeededFuture(0);
        });
    }

    /**
     * Resets stuck messages from PROCESSING back to PENDING state.
     */
    private int resetStuckMessages(Connection conn) throws SQLException {
        String resetSql = """
            UPDATE outbox 
            SET status = 'PENDING', processed_at = NULL 
            WHERE status = 'PROCESSING' 
            AND processed_at < ?
            """;
        
        try (PreparedStatement stmt = conn.prepareStatement(resetSql)) {
            stmt.setTimestamp(1, java.sql.Timestamp.from(Instant.now().minus(processingTimeout)));
            
            int updatedCount = stmt.executeUpdate();
            
            // Log details of what was recovered if there were updates
            if (updatedCount > 0) {
                logRecoveredMessages(conn);
            }
            
            return updatedCount;
        }
    }

    private Future<Integer> resetStuckMessagesReactive() {
        String resetSql = """
            UPDATE outbox
            SET status = 'PENDING', processed_at = NULL
            WHERE status = 'PROCESSING'
            AND processed_at < $1
            """;

        OffsetDateTime cutoffTime = Instant.now().minus(processingTimeout).atOffset(ZoneOffset.UTC);
        Tuple params = Tuple.of(cutoffTime);

        return reactivePool.withConnection(connection -> {
            return connection.preparedQuery(resetSql).execute(params)
                .compose(rowSet -> {
                    int updatedCount = rowSet.rowCount();

                    // Log details of what was recovered if there were updates
                    if (updatedCount > 0) {
                        return logRecoveredMessagesReactive()
                            .map(v -> updatedCount);
                    } else {
                        return Future.succeededFuture(updatedCount);
                    }
                });
        }).recover(throwable -> {
            logger.error("Failed to reset stuck messages (reactive)", throwable);
            return Future.succeededFuture(0);
        });
    }

    /**
     * Logs details about the messages that were recovered for audit purposes.
     */
    private void logRecoveredMessages(Connection conn) throws SQLException {
        // Query recently recovered messages (those that were just reset to PENDING)
        String logSql = """
            SELECT id, topic, retry_count, created_at, error_message
            FROM outbox 
            WHERE status = 'PENDING' 
            AND processed_at IS NULL
            AND created_at < ?
            ORDER BY created_at ASC
            LIMIT 10
            """;
        
        try (PreparedStatement stmt = conn.prepareStatement(logSql)) {
            // Look for messages created before now (to avoid logging newly created messages)
            stmt.setTimestamp(1, java.sql.Timestamp.from(Instant.now().minusSeconds(1)));
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    long messageId = rs.getLong("id");
                    String topic = rs.getString("topic");
                    int retryCount = rs.getInt("retry_count");
                    String errorMessage = rs.getString("error_message");
                    
                    logger.info("Recovered stuck message: id={}, topic={}, retryCount={}, lastError={}", 
                        messageId, topic, retryCount, 
                        errorMessage != null ? errorMessage.substring(0, Math.min(100, errorMessage.length())) : "none");
                }
            }
        }
    }

    private Future<Void> logRecoveredMessagesReactive() {
        // Query recently recovered messages (those that were just reset to PENDING)
        String logSql = """
            SELECT id, topic, retry_count, created_at, error_message
            FROM outbox
            WHERE status = 'PENDING'
            AND processed_at IS NULL
            AND created_at < $1
            ORDER BY created_at ASC
            LIMIT 10
            """;

        OffsetDateTime cutoffTime = Instant.now().minusSeconds(1).atOffset(ZoneOffset.UTC);
        Tuple params = Tuple.of(cutoffTime);

        return reactivePool.withConnection(connection -> {
            return connection.preparedQuery(logSql).execute(params)
                .map(rowSet -> {
                    for (Row row : rowSet) {
                        long messageId = row.getLong("id");
                        String topic = row.getString("topic");
                        int retryCount = row.getInteger("retry_count");
                        String errorMessage = row.getString("error_message");

                        logger.info("Recovered stuck message: id={}, topic={}, retryCount={}, lastError={}",
                            messageId, topic, retryCount,
                            errorMessage != null ? errorMessage.substring(0, Math.min(100, errorMessage.length())) : "none");
                    }
                    return (Void) null;
                });
        }).recover(throwable -> {
            logger.warn("Failed to log recovered messages (reactive): {}", throwable.getMessage());
            return Future.succeededFuture((Void) null);
        });
    }

    /**
     * Gets recovery statistics for monitoring purposes.
     */
    public RecoveryStats getRecoveryStats() {
        if (!enabled) {
            return new RecoveryStats(0, 0, false);
        }

        if (reactivePool != null) {
            // Use reactive approach - block on the result for compatibility with synchronous interface
            try {
                return getRecoveryStatsReactive()
                    .toCompletionStage().toCompletableFuture().get();
            } catch (Exception e) {
                logger.warn("Failed to get recovery stats (reactive): {}", e.getMessage());
                return new RecoveryStats(0, 0, true);
            }
        } else {
            // Use legacy JDBC approach
            try (Connection conn = dataSource.getConnection()) {
                int stuckCount = countStuckMessages(conn);
                int totalProcessingCount = countTotalProcessingMessages(conn);

                return new RecoveryStats(stuckCount, totalProcessingCount, true);

            } catch (SQLException e) {
                logger.warn("Failed to get recovery stats: {}", e.getMessage());
                return new RecoveryStats(0, 0, true);
            }
        }
    }

    private Future<RecoveryStats> getRecoveryStatsReactive() {
        return countStuckMessagesReactive()
            .compose(stuckCount -> {
                return countTotalProcessingMessagesReactive()
                    .map(totalProcessingCount -> new RecoveryStats(stuckCount, totalProcessingCount, true));
            })
            .recover(throwable -> {
                logger.warn("Failed to get recovery stats (reactive): {}", throwable.getMessage());
                return Future.succeededFuture(new RecoveryStats(0, 0, true));
            });
    }

    /**
     * Counts total messages currently in PROCESSING state.
     */
    private int countTotalProcessingMessages(Connection conn) throws SQLException {
        String countSql = "SELECT COUNT(*) FROM outbox WHERE status = 'PROCESSING'";
        
        try (PreparedStatement stmt = conn.prepareStatement(countSql)) {
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
                return 0;
            }
        }
    }

    private Future<Integer> countTotalProcessingMessagesReactive() {
        String countSql = "SELECT COUNT(*) FROM outbox WHERE status = 'PROCESSING'";

        return reactivePool.withConnection(connection -> {
            return connection.preparedQuery(countSql).execute()
                .map(rowSet -> {
                    if (rowSet.iterator().hasNext()) {
                        return rowSet.iterator().next().getInteger(0);
                    }
                    return 0;
                });
        }).recover(throwable -> {
            logger.error("Failed to count total processing messages (reactive)", throwable);
            return Future.succeededFuture(0);
        });
    }

    /**
     * Recovery statistics for monitoring.
     */
    public static class RecoveryStats {
        private final int stuckMessagesCount;
        private final int totalProcessingCount;
        private final boolean enabled;

        public RecoveryStats(int stuckMessagesCount, int totalProcessingCount, boolean enabled) {
            this.stuckMessagesCount = stuckMessagesCount;
            this.totalProcessingCount = totalProcessingCount;
            this.enabled = enabled;
        }

        public int getStuckMessagesCount() { return stuckMessagesCount; }
        public int getTotalProcessingCount() { return totalProcessingCount; }
        public boolean isEnabled() { return enabled; }

        @Override
        public String toString() {
            return String.format("RecoveryStats{stuck=%d, totalProcessing=%d, enabled=%s}", 
                stuckMessagesCount, totalProcessingCount, enabled);
        }
    }
}
