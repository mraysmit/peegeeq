package dev.mars.peegeeq.examples.springbootdlq.service;

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

import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.examples.springbootdlq.config.PeeGeeQDlqProperties;
import io.vertx.sqlclient.Row;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * DLQ Management Service.
 * 
 * Demonstrates the CORRECT way to manage Dead Letter Queue:
 * - Monitor DLQ depth
 * - Retrieve DLQ messages for inspection
 * - Reprocess DLQ messages manually
 * - Delete DLQ messages after resolution
 * - Alert when DLQ threshold is exceeded
 * 
 * Key Principles:
 * 1. Use PeeGeeQ's DeadLetterQueueManager for DLQ operations
 * 2. Monitor DLQ depth regularly
 * 3. Provide admin interface for DLQ inspection
 * 4. Allow manual reprocessing of DLQ messages
 * 5. Track DLQ metrics for alerting
 */
@Service
public class DlqManagementService {
    
    private static final Logger log = LoggerFactory.getLogger(DlqManagementService.class);
    private static final String CLIENT_ID = "peegeeq-main";
    
    private final PeeGeeQManager manager;
    private final DatabaseService databaseService;
    private final PeeGeeQDlqProperties properties;
    
    public DlqManagementService(
            PeeGeeQManager manager,
            DatabaseService databaseService,
            PeeGeeQDlqProperties properties) {
        this.manager = manager;
        this.databaseService = databaseService;
        this.properties = properties;
    }
    
    /**
     * Get DLQ depth (number of messages in DLQ).
     */
    public CompletableFuture<Long> getDlqDepth() {
        return databaseService.getConnectionProvider()
            .withTransaction(CLIENT_ID, connection -> {
                String sql = "SELECT COUNT(*) FROM dead_letter_queue WHERE topic = $1";
                return connection.preparedQuery(sql)
                    .execute(io.vertx.sqlclient.Tuple.of(properties.getQueueName()))
                    .map(rows -> {
                        Row row = rows.iterator().next();
                        return row.getLong(0);
                    });
            })
            .toCompletionStage()
            .toCompletableFuture();
    }
    
    /**
     * Get all DLQ messages for inspection.
     */
    public CompletableFuture<List<Map<String, Object>>> getDlqMessages() {
        return databaseService.getConnectionProvider()
            .withTransaction(CLIENT_ID, connection -> {
                String sql = "SELECT id, topic, payload, failure_reason, retry_count, failed_at " +
                            "FROM dead_letter_queue WHERE topic = $1 ORDER BY failed_at DESC LIMIT 100";
                return connection.preparedQuery(sql)
                    .execute(io.vertx.sqlclient.Tuple.of(properties.getQueueName()))
                    .map(rows -> {
                        List<Map<String, Object>> messages = new ArrayList<>();
                        for (Row row : rows) {
                            Map<String, Object> message = new HashMap<>();
                            message.put("id", row.getLong("id"));
                            message.put("topic", row.getString("topic"));
                            message.put("payload", row.getString("payload"));
                            message.put("failureReason", row.getString("failure_reason"));
                            message.put("retryCount", row.getInteger("retry_count"));
                            message.put("failedAt", row.getOffsetDateTime("failed_at"));
                            messages.add(message);
                        }
                        return messages;
                    });
            })
            .toCompletionStage()
            .toCompletableFuture();
    }
    
    /**
     * Reprocess a DLQ message by ID.
     *
     * This moves the message back to the main queue for reprocessing.
     */
    public CompletableFuture<Boolean> reprocessDlqMessage(Long messageId) {
        log.info("Reprocessing DLQ message: id={}", messageId);

        return databaseService.getConnectionProvider()
            .withTransaction(CLIENT_ID, connection -> {
                // Get the message from DLQ
                String selectSql = "SELECT topic, payload FROM dead_letter_queue WHERE id = $1";
                return connection.preparedQuery(selectSql)
                    .execute(io.vertx.sqlclient.Tuple.of(messageId))
                    .compose(rows -> {
                        if (!rows.iterator().hasNext()) {
                            log.warn("DLQ message not found: id={}", messageId);
                            return io.vertx.core.Future.succeededFuture(false);
                        }

                        Row row = rows.iterator().next();
                        String topic = row.getString("topic");
                        String payload = row.getString("payload");

                        // Insert back into main queue (peegeeq_outbox table)
                        String insertSql = "INSERT INTO peegeeq_outbox (topic, payload, status) VALUES ($1, $2, 'PENDING')";
                        return connection.preparedQuery(insertSql)
                            .execute(io.vertx.sqlclient.Tuple.of(topic, payload))
                            .compose(result -> {
                                // Delete from DLQ
                                String deleteSql = "DELETE FROM dead_letter_queue WHERE id = $1";
                                return connection.preparedQuery(deleteSql)
                                    .execute(io.vertx.sqlclient.Tuple.of(messageId))
                                    .map(deleteResult -> {
                                        log.info("✅ DLQ message reprocessed successfully: id={}", messageId);
                                        return true;
                                    });
                            });
                    });
            })
            .toCompletionStage()
            .toCompletableFuture();
    }
    
    /**
     * Delete a DLQ message by ID.
     */
    public CompletableFuture<Boolean> deleteDlqMessage(Long messageId) {
        log.info("Deleting DLQ message: id={}", messageId);

        return databaseService.getConnectionProvider()
            .withTransaction(CLIENT_ID, connection -> {
                String sql = "DELETE FROM dead_letter_queue WHERE id = $1";
                return connection.preparedQuery(sql)
                    .execute(io.vertx.sqlclient.Tuple.of(messageId))
                    .map(result -> {
                        boolean deleted = result.rowCount() > 0;
                        if (deleted) {
                            log.info("✅ DLQ message deleted successfully: id={}", messageId);
                        } else {
                            log.warn("DLQ message not found: id={}", messageId);
                        }
                        return deleted;
                    });
            })
            .toCompletionStage()
            .toCompletableFuture();
    }
    
    /**
     * Check if DLQ depth exceeds alert threshold.
     */
    public CompletableFuture<Boolean> shouldAlert() {
        return getDlqDepth().thenApply(depth -> {
            boolean alert = depth >= properties.getDlqAlertThreshold();
            if (alert) {
                log.warn("⚠️ DLQ alert threshold exceeded: depth={}, threshold={}", 
                    depth, properties.getDlqAlertThreshold());
            }
            return alert;
        });
    }
    
    /**
     * Get DLQ statistics.
     */
    public CompletableFuture<Map<String, Object>> getDlqStats() {
        return getDlqDepth().thenApply(depth -> {
            Map<String, Object> stats = new HashMap<>();
            stats.put("depth", depth);
            stats.put("threshold", properties.getDlqAlertThreshold());
            stats.put("alerting", depth >= properties.getDlqAlertThreshold());
            stats.put("autoReprocessEnabled", properties.isAutoReprocessEnabled());
            return stats;
        });
    }
}

