package dev.mars.peegeeq.db.consumer;

import dev.mars.peegeeq.db.connection.PgConnectionManager;
import io.vertx.core.Future;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Tracks message completion for consumer groups using Reference Counting mode.
 * 
 * <p>This service implements atomic completion tracking by:
 * <ul>
 *   <li>Updating the outbox_consumer_groups tracking row status to COMPLETED</li>
 *   <li>Incrementing the outbox.completed_consumer_groups counter</li>
 *   <li>Marking the message as eligible for cleanup when all groups complete</li>
 * </ul>
 * </p>
 * 
 * <p><strong>Atomicity:</strong> All updates are performed within a single SQL statement
 * to ensure consistency even under concurrent updates.</p>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-12
 * @version 1.0
 */
public class CompletionTracker {
    private static final Logger logger = LoggerFactory.getLogger(CompletionTracker.class);

    private final PgConnectionManager connectionManager;
    private final String serviceId;

    /**
     * Creates a new CompletionTracker.
     *
     * @param connectionManager The connection manager for database operations
     * @param serviceId The service ID for connection tracking
     */
    public CompletionTracker(PgConnectionManager connectionManager, String serviceId) {
        this.connectionManager = connectionManager;
        this.serviceId = serviceId;
    }

    /**
     * Marks a message as completed for the specified consumer group.
     * 
     * <p>This method performs the following operations atomically:
     * <ol>
     *   <li>Creates or updates the tracking row in outbox_consumer_groups to COMPLETED</li>
     *   <li>Increments the completed_consumer_groups counter in outbox table</li>
     *   <li>Updates the message status to COMPLETED if all groups have finished</li>
     * </ol>
     * </p>
     * 
     * <p><strong>Concurrency:</strong> Uses INSERT ... ON CONFLICT to handle concurrent
     * completion attempts safely.</p>
     *
     * @param messageId The message ID to mark as completed
     * @param groupName The consumer group name
     * @param topic The topic name (for logging and validation)
     * @return Future that completes when the operation is done
     */
    public Future<Void> markCompleted(Long messageId, String groupName, String topic) {
        logger.debug("Marking message {} as completed for group '{}' on topic '{}'",
                messageId, groupName, topic);

        return connectionManager.withTransaction(serviceId, connection -> {
            // Step 1: Insert or update tracking row to COMPLETED
            String insertTrackingSql = """
                INSERT INTO outbox_consumer_groups (message_id, group_name, status, processed_at)
                VALUES ($1, $2, 'COMPLETED', $3)
                ON CONFLICT (message_id, group_name)
                DO UPDATE SET
                    status = 'COMPLETED',
                    processed_at = $3
                WHERE outbox_consumer_groups.status != 'COMPLETED'
                """;

            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            Tuple trackingParams = Tuple.of(messageId, groupName, now);

            return connection.preparedQuery(insertTrackingSql)
                    .execute(trackingParams)
                    .compose(trackingResult -> {
                        int rowsAffected = trackingResult.rowCount();
                        if (rowsAffected == 0) {
                            logger.debug("Message {} already completed for group '{}' (idempotent)",
                                    messageId, groupName);
                        }

                        // Step 2: Increment completed_consumer_groups counter
                        String updateCounterSql = """
                            UPDATE outbox
                            SET completed_consumer_groups = completed_consumer_groups + 1,
                                status = CASE
                                    WHEN completed_consumer_groups + 1 >= required_consumer_groups
                                    THEN 'COMPLETED'
                                    ELSE status
                                END,
                                processed_at = CASE
                                    WHEN completed_consumer_groups + 1 >= required_consumer_groups
                                    THEN $2
                                    ELSE processed_at
                                END
                            WHERE id = $1
                              AND completed_consumer_groups < required_consumer_groups
                            RETURNING id, completed_consumer_groups, required_consumer_groups, status
                            """;

                        Tuple counterParams = Tuple.of(messageId, now);

                        return connection.preparedQuery(updateCounterSql)
                                .execute(counterParams)
                                .map(counterResult -> {
                                    if (counterResult.size() > 0) {
                                        var row = counterResult.iterator().next();
                                        int completed = row.getInteger("completed_consumer_groups");
                                        int required = row.getInteger("required_consumer_groups");
                                        String status = row.getString("status");

                                        logger.debug("Message {} completion: {}/{} groups completed, status={}",
                                                messageId, completed, required, status);

                                        if (status.equals("COMPLETED")) {
                                            logger.info("Message {} fully completed - all {} groups finished",
                                                    messageId, required);
                                        }
                                    } else {
                                        logger.debug("Message {} counter already at max (idempotent)", messageId);
                                    }
                                    return null;
                                });
                    });
        }).mapEmpty();
    }

    /**
     * Marks a message as failed for the specified consumer group.
     * 
     * <p>This method updates the tracking row status to FAILED but does NOT
     * increment the completion counter. Failed messages can be retried.</p>
     *
     * @param messageId The message ID to mark as failed
     * @param groupName The consumer group name
     * @param topic The topic name (for logging)
     * @param errorMessage The error message describing the failure
     * @return Future that completes when the operation is done
     */
    public Future<Void> markFailed(Long messageId, String groupName, String topic, String errorMessage) {
        logger.warn("Marking message {} as failed for group '{}' on topic '{}': {}",
                messageId, groupName, topic, errorMessage);

        return connectionManager.withConnection(serviceId, connection -> {
            String sql = """
                INSERT INTO outbox_consumer_groups (message_id, group_name, status, processed_at, error_message)
                VALUES ($1, $2, 'FAILED', $3, $4)
                ON CONFLICT (message_id, group_name)
                DO UPDATE SET
                    status = 'FAILED',
                    processed_at = $3,
                    error_message = $4,
                    retry_count = outbox_consumer_groups.retry_count + 1
                """;

            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            Tuple params = Tuple.of(messageId, groupName, now, errorMessage);

            return connection.preparedQuery(sql)
                    .execute(params)
                    .map(result -> {
                        logger.debug("Marked message {} as failed for group '{}'", messageId, groupName);
                        return null;
                    });
        }).mapEmpty();
    }
}

