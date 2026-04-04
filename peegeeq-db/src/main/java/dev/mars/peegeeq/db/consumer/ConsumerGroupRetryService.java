package dev.mars.peegeeq.db.consumer;

import dev.mars.peegeeq.api.tracing.TraceCtx;
import dev.mars.peegeeq.api.tracing.TraceContextUtil;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.deadletter.DeadLetterQueueManager;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Automates retry and dead-letter-queue processing for consumer group fanout.
 *
 * <p>This service periodically scans {@code outbox_consumer_groups} rows in FAILED
 * status and takes one of two actions:</p>
 * <ul>
 *   <li><strong>Retry</strong>: If {@code retry_count < outbox.max_retries}, reset
 *       the tracking row to PENDING so the consumer group can re-process it.</li>
 *   <li><strong>DLQ</strong>: If {@code retry_count >= outbox.max_retries}, move the
 *       message to the {@code dead_letter_queue} table and mark the tracking row
 *       as DEAD_LETTER.</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-12-01
 * @version 1.0
 */
public class ConsumerGroupRetryService {

    private static final Logger logger = LoggerFactory.getLogger(ConsumerGroupRetryService.class);

    private final PgConnectionManager connectionManager;
    private final DeadLetterQueueManager deadLetterQueueManager;
    private final String serviceId;

    /**
     * Creates a new ConsumerGroupRetryService.
     *
     * @param connectionManager The connection manager for database operations
     * @param deadLetterQueueManager The dead letter queue manager for DLQ operations
     * @param serviceId The service ID for connection tracking
     */
    public ConsumerGroupRetryService(PgConnectionManager connectionManager,
                                     DeadLetterQueueManager deadLetterQueueManager,
                                     String serviceId) {
        this.connectionManager = Objects.requireNonNull(connectionManager, "connectionManager cannot be null");
        this.deadLetterQueueManager = Objects.requireNonNull(deadLetterQueueManager, "deadLetterQueueManager cannot be null");
        this.serviceId = Objects.requireNonNull(serviceId, "serviceId cannot be null");
    }

    /**
     * Result of a retry scan operation.
     */
    public record RetryResult(int retriedCount, int dlqCount) {}

    /**
     * Scans for FAILED consumer group tracking rows and retries those that
     * have not yet exhausted their retry budget.
     *
     * <p>Resets matching rows from FAILED to PENDING and clears error_message.</p>
     *
     * @return Future containing the number of rows retried
     */
    public Future<Integer> retryFailedMessages() {
        TraceCtx trace = TraceCtx.createNew();
        try (var scope = TraceContextUtil.mdcScope(trace)) {
            logger.debug("Starting retry scan for failed consumer group messages");
        }

        String sql = """
            UPDATE outbox_consumer_groups ocg
            SET status = 'PENDING', error_message = NULL
            FROM outbox o
            WHERE ocg.message_id = o.id
              AND ocg.status = 'FAILED'
              AND ocg.retry_count < o.max_retries
            """;

        return connectionManager.withConnection(serviceId, connection ->
            connection.preparedQuery(sql).execute()
                .map(rows -> {
                    int count = rows.rowCount();
                    try (var innerScope = TraceContextUtil.mdcScope(trace)) {
                        if (count > 0) {
                            logger.info("Retried {} failed consumer group messages (reset to PENDING)", count);
                        } else {
                            logger.debug("No failed consumer group messages eligible for retry");
                        }
                    }
                    return count;
                })
        );
    }

    /**
     * Scans for FAILED consumer group tracking rows that have exhausted their
     * retry budget and moves them to the dead letter queue.
     *
     * <p>For each exhausted row:</p>
     * <ol>
     *   <li>Reads the original message data from the outbox table</li>
     *   <li>Inserts a record into the dead_letter_queue table</li>
     *   <li>Updates the tracking row status to DEAD_LETTER</li>
     * </ol>
     *
     * @return Future containing the number of rows moved to DLQ
     */
    public Future<Integer> moveExhaustedToDlq() {
        TraceCtx trace = TraceCtx.createNew();
        try (var scope = TraceContextUtil.mdcScope(trace)) {
            logger.debug("Starting DLQ scan for exhausted consumer group messages");
        }

        String selectSql = """
            SELECT ocg.message_id, ocg.group_name, ocg.retry_count, ocg.error_message,
                   o.topic, o.payload, o.created_at, o.headers, o.correlation_id, o.message_group
            FROM outbox_consumer_groups ocg
            JOIN outbox o ON o.id = ocg.message_id
            WHERE ocg.status = 'FAILED'
              AND ocg.retry_count >= o.max_retries
            """;

        return connectionManager.withConnection(serviceId, connection ->
            connection.preparedQuery(selectSql).execute()
                .compose(rows -> {
                    List<Row> exhaustedRows = new ArrayList<>();
                    rows.forEach(exhaustedRows::add);

                    if (exhaustedRows.isEmpty()) {
                        try (var innerScope = TraceContextUtil.mdcScope(trace)) {
                            logger.debug("No exhausted consumer group messages to move to DLQ");
                        }
                        return Future.succeededFuture(0);
                    }

                    try (var innerScope = TraceContextUtil.mdcScope(trace)) {
                        logger.info("Found {} exhausted consumer group messages to move to DLQ",
                                exhaustedRows.size());
                    }

                    // Process each exhausted row sequentially
                    Future<Void> chain = Future.succeededFuture();
                    for (Row row : exhaustedRows) {
                        chain = chain.compose(v -> moveSingleToDlq(row, trace));
                    }

                    return chain.map(exhaustedRows.size());
                })
        );
    }

    /**
     * Processes both retry and DLQ in a single scan pass.
     *
     * @return Future containing the combined result
     */
    public Future<RetryResult> processFailedMessages() {
        TraceCtx trace = TraceCtx.createNew();
        try (var scope = TraceContextUtil.mdcScope(trace)) {
            logger.debug("Starting combined retry/DLQ scan");
        }

        return retryFailedMessages()
                .compose(retriedCount -> moveExhaustedToDlq()
                        .map(dlqCount -> {
                            try (var innerScope = TraceContextUtil.mdcScope(trace)) {
                                logger.info("Retry/DLQ scan complete: retried={}, movedToDlq={}",
                                        retriedCount, dlqCount);
                            }
                            return new RetryResult(retriedCount, dlqCount);
                        }));
    }

    private Future<Void> moveSingleToDlq(Row row, TraceCtx trace) {
        long messageId = row.getLong("message_id");
        String groupName = row.getString("group_name");
        int retryCount = row.getInteger("retry_count");
        String errorMessage = row.getString("error_message");
        String topic = row.getString("topic");
        Object payload = row.getJson("payload");
        Instant createdAt = row.getOffsetDateTime("created_at").toInstant();
        String correlationId = row.getString("correlation_id");
        String messageGroup = row.getString("message_group");

        // Extract headers from JSONB
        Map<String, String> headers = null;
        JsonObject headersJson = row.getJsonObject("headers");
        if (headersJson != null && !headersJson.isEmpty()) {
            headers = headersJson.getMap().entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            Map.Entry::getKey,
                            e -> String.valueOf(e.getValue())));
        }

        String failureReason = String.format(
                "Consumer group '%s' exhausted %d retries for message %d: %s",
                groupName, retryCount, messageId,
                errorMessage != null ? errorMessage : "unknown error");

        try (var scope = TraceContextUtil.mdcScope(trace)) {
            logger.info("Moving message {} for group '{}' to DLQ after {} retries: {}",
                    messageId, groupName, retryCount, failureReason);
        }

        return deadLetterQueueManager.moveToDeadLetterQueue(
                "outbox_consumer_groups", messageId, topic, payload, createdAt,
                failureReason, retryCount, headers, correlationId, messageGroup)
            .compose(v -> {
                // Mark the tracking row as DEAD_LETTER
                String updateSql = """
                    UPDATE outbox_consumer_groups
                    SET status = 'DEAD_LETTER'
                    WHERE message_id = $1 AND group_name = $2
                    """;

                return connectionManager.withConnection(serviceId, connection ->
                    connection.preparedQuery(updateSql)
                        .execute(Tuple.of(messageId, groupName))
                        .map(result -> {
                            try (var scope = TraceContextUtil.mdcScope(trace)) {
                                logger.debug("Marked message {} group '{}' as DEAD_LETTER", messageId, groupName);
                            }
                            return (Void) null;
                        })
                );
            });
    }
}
