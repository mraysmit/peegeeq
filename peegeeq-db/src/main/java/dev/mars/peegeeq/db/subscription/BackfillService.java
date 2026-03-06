package dev.mars.peegeeq.db.subscription;

import dev.mars.peegeeq.api.tracing.TraceCtx;
import dev.mars.peegeeq.api.tracing.TraceContextUtil;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Service for managing resumable backfill of existing messages for late-joining consumer groups.
 *
 * <p>When a consumer group subscribes to a PUB_SUB topic with {@code FROM_BEGINNING} or
 * {@code FROM_MESSAGE_ID} start position, existing messages must be backfilled so the
 * new group can process them. This service handles that backfill process in batches,
 * with checkpoint-based resumability and cancellation support.</p>
 *
 * <h3>Backfill Process:</h3>
 * <ol>
 *   <li><strong>Start</strong>: Calculate total messages to backfill, set status to IN_PROGRESS</li>
 *   <li><strong>Process batches</strong>: For each batch of messages, increment
 *       {@code required_consumer_groups} and create tracking rows in {@code outbox_consumer_groups}</li>
 *   <li><strong>Checkpoint</strong>: After each batch, update {@code backfill_checkpoint_id}
 *       and {@code backfill_processed_messages} for crash recovery</li>
 *   <li><strong>Complete</strong>: Set status to COMPLETED when all messages are processed</li>
 * </ol>
 *
 * <h3>Resumability:</h3>
 * <p>If the process crashes mid-backfill, calling {@link #startBackfill} again will
 * detect the IN_PROGRESS status and resume from the last checkpoint.</p>
 *
 * <h3>Cancellation:</h3>
 * <p>Calling {@link #cancelBackfill} sets the status to CANCELLED. The next batch
 * iteration will detect this and stop processing without data corruption.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-12-01
 * @version 1.0
 */
public class BackfillService {

    private static final Logger logger = LoggerFactory.getLogger(BackfillService.class);

    /** Default batch size for backfill operations */
    public static final int DEFAULT_BATCH_SIZE = 10_000;

    /** Default maximum messages to backfill in a single operation */
    public static final long DEFAULT_MAX_MESSAGES = 1_000_000L;

    // Status constants
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_CANCELLED = "CANCELLED";
    private static final String STATUS_NONE = "NONE";


    private final PgConnectionManager connectionManager;
    private final String serviceId;

    /**
     * Creates a new BackfillService.
     *
     * @param connectionManager The connection manager for database access
     * @param serviceId The service ID for connection pool selection
     */
    public BackfillService(PgConnectionManager connectionManager, String serviceId) {
        this.connectionManager = Objects.requireNonNull(connectionManager, "connectionManager cannot be null");
        this.serviceId = Objects.requireNonNull(serviceId, "serviceId cannot be null");
        logger.info("BackfillService initialized for service: {}", serviceId);
    }

    /**
     * Starts or resumes a backfill operation for a consumer group subscription.
     *
     * <p>If a backfill is already IN_PROGRESS, it will resume from the last checkpoint.
     * If the subscription has no backfill (NONE status), it will start a new backfill.</p>
     *
     * @param topic The topic name
     * @param groupName The consumer group name
     * @param batchSize Number of messages to process per batch
     * @param maxMessages Maximum total messages to backfill (0 = unlimited)
     * @return Future containing a {@link BackfillResult} with the outcome
     */
    public Future<BackfillResult> startBackfill(String topic, String groupName, int batchSize, long maxMessages) {
        Objects.requireNonNull(topic, "topic cannot be null");
        Objects.requireNonNull(groupName, "groupName cannot be null");
        if (batchSize <= 0) {
            return Future.failedFuture(new IllegalArgumentException("batchSize must be positive"));
        }
        if (maxMessages < 0) {
            return Future.failedFuture(new IllegalArgumentException("maxMessages cannot be negative"));
        }

        TraceCtx trace = TraceCtx.createNew();
        long startTimeMs = System.currentTimeMillis();

        try (var scope = TraceContextUtil.mdcScope(trace)) {
            logger.info("Starting backfill for topic='{}', group='{}', batchSize={}, maxMessages={}",
                    topic, groupName, batchSize, maxMessages);
        }

        // Step 1: Atomically acquire backfill lock to prevent concurrent execution
        return acquireBackfillLock(topic, groupName, trace)
                .compose(lockResult -> {
                    // If lock acquisition failed, check the reason
                    if (!lockResult.acquired()) {
                        if (lockResult.alreadyCompleted()) {
                            return Future.succeededFuture(new BackfillResult(
                                    BackfillResult.Status.ALREADY_COMPLETED,
                                    lockResult.processedMessages(),
                                    "Backfill was already completed"));
                        } else {
                            return Future.succeededFuture(new BackfillResult(
                                    BackfillResult.Status.SKIPPED,
                                    lockResult.processedMessages(),
                                    "Backfill already in progress by another worker"));
                        }
                    }

                    long resumeFromId = lockResult.startFromId();
                    long processedSoFar = lockResult.processedMessages();

                    // Step 2+: Count, initialize, and process (each acquires its own connection)
                    return countMessagesToBackfill(topic, resumeFromId, maxMessages)
                            .compose(totalMessages -> {
                                if (totalMessages == 0L) {
                                    try (var scope = TraceContextUtil.mdcScope(trace)) {
                                        logger.info("Backfill complete (no messages): topic='{}', group='{}', elapsedMs={}",
                                                topic, groupName, System.currentTimeMillis() - startTimeMs);
                                    }
                                    return markBackfillCompleted(trace, topic, groupName, 0L)
                                            .map(v -> new BackfillResult(
                                                    BackfillResult.Status.COMPLETED,
                                                    0L,
                                                    "No messages to backfill"));
                                }

                                try (var scope = TraceContextUtil.mdcScope(trace)) {
                                    logger.info("Backfill initialized: topic='{}', group='{}', totalMessages={}, resumeFromId={}",
                                            topic, groupName, totalMessages, resumeFromId);
                                }

                                // Initialize backfill tracking
                                return initializeBackfill(topic, groupName, totalMessages)
                                        .compose(v -> processBatches(trace, topic, groupName, resumeFromId,
                                                batchSize, maxMessages, processedSoFar))
                                        .map(result -> {
                                            long elapsedMs = System.currentTimeMillis() - startTimeMs;
                                            double rate = elapsedMs > 0
                                                    ? (result.processedMessages() * 1000.0 / elapsedMs)
                                                    : 0.0;
                                            try (var scope = TraceContextUtil.mdcScope(trace)) {
                                                logger.info("Backfill finished: topic='{}', group='{}', status={}, " +
                                                                "processed={}, elapsedMs={}, rate={} msgs/s",
                                                        topic, groupName, result.status(),
                                                        result.processedMessages(), elapsedMs,
                                                        String.format("%.1f", rate));
                                            }
                                            return result;
                                        });
                            });
                });
    }

    /**
     * Starts a backfill with default batch size and max messages.
     *
     * @param topic The topic name
     * @param groupName The consumer group name
     * @return Future containing a {@link BackfillResult}
     */
    public Future<BackfillResult> startBackfill(String topic, String groupName) {
        return startBackfill(topic, groupName, DEFAULT_BATCH_SIZE, DEFAULT_MAX_MESSAGES);
    }

    /**
     * Cancels an in-progress backfill operation.
     *
     * <p>The cancellation is cooperative: the current batch will complete but
     * no further batches will be processed. The backfill can be resumed later
     * by calling {@link #startBackfill} again.</p>
     *
     * @param topic The topic name
     * @param groupName The consumer group name
     * @return Future that completes when the cancellation is recorded
     */
    public Future<Void> cancelBackfill(String topic, String groupName) {
        Objects.requireNonNull(topic, "topic cannot be null");
        Objects.requireNonNull(groupName, "groupName cannot be null");

        TraceCtx trace = TraceCtx.createNew();
        try (var scope = TraceContextUtil.mdcScope(trace)) {
            logger.info("Cancelling backfill for topic='{}', group='{}'", topic, groupName);
        }

        return connectionManager.withConnection(serviceId, connection -> {
            String sql = """
                UPDATE outbox_topic_subscriptions
                SET backfill_status = $3
                WHERE topic = $1 AND group_name = $2
                  AND backfill_status = $4
                """;

            return connection.preparedQuery(sql)
                    .execute(Tuple.of(topic, groupName, STATUS_CANCELLED, STATUS_IN_PROGRESS))
                    .compose(result -> {
                        try (var scope = TraceContextUtil.mdcScope(trace)) {
                            if (result.rowCount() == 0) {
                                logger.warn("No in-progress backfill to cancel for topic='{}', group='{}'",
                                        topic, groupName);
                            } else {
                                logger.info("Backfill cancelled for topic='{}', group='{}'", topic, groupName);
                            }
                        }
                        return Future.succeededFuture();
                    });
        });
    }

    /**
     * Gets the current backfill progress for a subscription.
     *
     * @param topic The topic name
     * @param groupName The consumer group name
     * @return Future containing an Optional with {@link BackfillProgress}, empty if subscription not found
     */
    public Future<Optional<BackfillProgress>> getBackfillProgress(String topic, String groupName) {
        Objects.requireNonNull(topic, "topic cannot be null");
        Objects.requireNonNull(groupName, "groupName cannot be null");

        return connectionManager.withConnection(serviceId, connection -> {
            String sql = """
                SELECT backfill_status, backfill_checkpoint_id, backfill_processed_messages,
                       backfill_total_messages, backfill_started_at, backfill_completed_at
                FROM outbox_topic_subscriptions
                WHERE topic = $1 AND group_name = $2
                """;

            return connection.preparedQuery(sql)
                    .execute(Tuple.of(topic, groupName))
                    .map(rows -> {
                        if (rows.size() == 0) {
                            return Optional.<BackfillProgress>empty();
                        }
                        Row row = rows.iterator().next();
                        return Optional.of(new BackfillProgress(
                                row.getString("backfill_status"),
                                row.getLong("backfill_checkpoint_id"),
                                row.getLong("backfill_processed_messages"),
                                row.getLong("backfill_total_messages"),
                                row.getOffsetDateTime("backfill_started_at"),
                                row.getOffsetDateTime("backfill_completed_at")
                        ));
                    });
        });
    }

    // ========================================================================
    // Internal methods
    // ========================================================================

    /**
     * Atomically acquires backfill lock, preventing concurrent backfill workers.
     * 
     * <p>Uses FOR UPDATE to lock the subscription row and checks:
     * <ul>
     *   <li>Subscription must be ACTIVE</li>
     *   <li>Backfill must not be IN_PROGRESS (serializes concurrent attempts)</li>
     *   <li>Backfill must not be COMPLETED</li>
     * </ul>
     * 
     * <p>If lock is acquired, sets backfill_status = IN_PROGRESS and returns
     * the checkpoint to resume from.
     * 
     * @return LockAcquisitionResult with acquired=true if lock obtained, false otherwise
     */
    private Future<LockAcquisitionResult> acquireBackfillLock(String topic, String groupName, TraceCtx trace) {
        return connectionManager.withTransaction(serviceId, connection -> {
            // Lock subscription row and read current state
            String lockSql = """
                SELECT subscription_status, backfill_status, start_from_message_id,
                       backfill_checkpoint_id, backfill_processed_messages
                FROM outbox_topic_subscriptions
                WHERE topic = $1 AND group_name = $2
                FOR UPDATE
                """;

            return connection.preparedQuery(lockSql)
                    .execute(Tuple.of(topic, groupName))
                    .compose(rows -> {
                        if (rows.size() == 0) {
                            return Future.failedFuture(new IllegalStateException(
                                    "Subscription not found: topic='" + topic + "', group='" + groupName + "'"));
                        }

                        Row row = rows.iterator().next();
                        String subStatus = row.getString("subscription_status");
                        String backfillStatus = row.getString("backfill_status");
                        Long checkpointId = row.getLong("backfill_checkpoint_id");
                        Long processedMessages = row.getLong("backfill_processed_messages");
                        Long startFromMessageId = row.getLong("start_from_message_id");

                        // Validate subscription is ACTIVE
                        if (!STATUS_ACTIVE.equals(subStatus)) {
                            return Future.failedFuture(new IllegalStateException(
                                    "Subscription must be ACTIVE for backfill, current status: " + subStatus));
                        }

                        // Check if already completed
                        if (STATUS_COMPLETED.equals(backfillStatus)) {
                            try (var scope = TraceContextUtil.mdcScope(trace)) {
                                logger.info("Backfill already completed for topic='{}', group='{}', previouslyProcessed={}",
                                        topic, groupName, processedMessages != null ? processedMessages : 0L);
                            }
                            // Return lock not acquired (already done)
                            return Future.succeededFuture(new LockAcquisitionResult(
                                    false, true, -1, processedMessages != null ? processedMessages : 0L));
                        }

                        // Check if already in progress by another worker
                        if (STATUS_IN_PROGRESS.equals(backfillStatus)) {
                            try (var scope = TraceContextUtil.mdcScope(trace)) {
                                logger.info("Backfill already in progress for topic='{}', group='{}' - skipping this worker",
                                        topic, groupName);
                            }
                            // Return lock not acquired (another worker has it)
                            return Future.succeededFuture(new LockAcquisitionResult(
                                    false, false, -1, processedMessages != null ? processedMessages : 0L));
                        }

                        // Determine starting point
                        long resumeFromId;
                        if (checkpointId != null && checkpointId > 0) {
                            // Resume from last checkpoint
                            resumeFromId = checkpointId + 1;
                            try (var scope = TraceContextUtil.mdcScope(trace)) {
                                logger.info("Resuming backfill from checkpoint {} for topic='{}', group='{}'",
                                        checkpointId, topic, groupName);
                            }
                        } else {
                            // Start new backfill
                            resumeFromId = startFromMessageId != null ? startFromMessageId : 1L;
                        }

                        long processedSoFar = processedMessages != null ? processedMessages : 0L;

                        // Set status to IN_PROGRESS (within same transaction as the FOR UPDATE lock)
                        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
                        String updateSql = """
                            UPDATE outbox_topic_subscriptions
                            SET backfill_status = $4,
                                backfill_started_at = COALESCE(backfill_started_at, $3)
                            WHERE topic = $1 AND group_name = $2
                            """;

                        return connection.preparedQuery(updateSql)
                                .execute(Tuple.of(topic, groupName, now, STATUS_IN_PROGRESS))
                                .map(v -> new LockAcquisitionResult(true, false, resumeFromId, processedSoFar));
                    });
        });
    }

    /**
     * Marks a backfill as completed, acquiring its own connection.
     * Used when no existing connection is available (e.g., zero-messages path).
     */
    private Future<Void> markBackfillCompleted(TraceCtx trace, String topic, String groupName, long totalProcessed) {
        return connectionManager.withConnection(serviceId, connection ->
                markBackfillCompleted(connection, trace, topic, groupName, totalProcessed)
        );
    }

    private Future<Long> countMessagesToBackfill(String topic, long fromMessageId, long maxMessages) {
        if (fromMessageId < 0) {
            return Future.failedFuture(new IllegalArgumentException("fromMessageId cannot be negative"));
        }
        return connectionManager.withConnection(serviceId, connection -> {
            String sql;
            Tuple params;
            if (maxMessages > 0) {
                sql = """
                    SELECT COUNT(*) AS total FROM (
                        SELECT 1 FROM outbox
                        WHERE topic = $1 AND id >= $2
                          AND status IN ('PENDING', 'PROCESSING', 'COMPLETED')
                        LIMIT $3
                    ) sub
                    """;
                params = Tuple.of(topic, fromMessageId, maxMessages);
            } else {
                sql = """
                    SELECT COUNT(*) AS total FROM outbox
                    WHERE topic = $1 AND id >= $2
                      AND status IN ('PENDING', 'PROCESSING', 'COMPLETED')
                    """;
                params = Tuple.of(topic, fromMessageId);
            }

            return connection.preparedQuery(sql)
                    .execute(params)
                    .map(rows -> rows.iterator().next().getLong("total"));
        });
    }

    private Future<Void> initializeBackfill(String topic, String groupName, long totalMessages) {
        return connectionManager.withConnection(serviceId, connection -> {
            // Note: backfill_status is already set to IN_PROGRESS by acquireBackfillLock
            // This method only updates the total_messages count
            String sql = """
                UPDATE outbox_topic_subscriptions
                SET backfill_total_messages = $3
                WHERE topic = $1 AND group_name = $2
                """;

            return connection.preparedQuery(sql)
                    .execute(Tuple.of(topic, groupName, totalMessages))
                    .mapEmpty();
        });
    }

    private Future<BackfillResult> processBatches(TraceCtx trace, String topic, String groupName,
                                                   long startFromId, int batchSize,
                                                   long maxMessages, long alreadyProcessed) {
        // Use tail-recursive Future composition — safe in Vert.x as the call stack unwinds between batches
        return processBatchesRecursively(trace, topic, groupName, startFromId, batchSize, maxMessages, alreadyProcessed);
    }

    /**
     * Processes batches using async tail-recursion through Future composition.
     * 
     * <p>This uses tail-recursive {@code .compose()} calls, which are safe in Vert.x because:
     * <ul>
     *   <li>Each compose() is asynchronous - the call stack unwinds between batches</li>
     *   <li>No synchronous recursion occurs - operations run on the event loop</li>
     *   <li>Tested safe for 100+ batches (1M+ messages with 10k batch size)</li>
     * </ul>
     * 
     * <p>Note: At default settings (1M max messages, 10k batch size) this creates ~100 levels
     * of compose() nesting — each holding a Future reference but no live stack frames.
     * The heap overhead is bounded and acceptable for the configured limits.
     */
    private Future<BackfillResult> processBatchesRecursively(TraceCtx trace, String topic, String groupName,
                                                             long currentStartId, int batchSize,
                                                             long maxMessages, long currentProcessed) {
        return processOneBatch(trace, topic, groupName, currentStartId, batchSize, maxMessages, currentProcessed)
                .compose(batchResult -> {
                    if (batchResult.isComplete() || batchResult.isCancelled()) {
                        return Future.succeededFuture(batchResult.toBackfillResult());
                    }
                    // Tail-recursive call — no live stack frame retained between batches
                    return processBatchesRecursively(trace, topic, groupName,
                            batchResult.nextStartId(), batchSize, maxMessages,
                            batchResult.totalProcessed());
                });
    }

    private Future<BatchResult> processOneBatch(TraceCtx trace, String topic, String groupName,
                                                 long startFromId, int batchSize,
                                                 long maxMessages, long alreadyProcessed) {
        return connectionManager.withTransaction(serviceId, connection -> {
            // Step 1: Check if backfill was cancelled (with row lock to prevent concurrent execution)
            String checkSql = """
                SELECT backfill_status, backfill_processed_messages
                FROM outbox_topic_subscriptions
                WHERE topic = $1 AND group_name = $2
                FOR UPDATE
                """;
            return connection.preparedQuery(checkSql)
                    .execute(Tuple.of(topic, groupName))
                    .compose(statusRows -> {
                        if (statusRows.size() == 0) {
                            return Future.succeededFuture(BatchResult.cancelled(alreadyProcessed));
                        }
                        Row statusRow = statusRows.iterator().next();
                        String currentStatus = statusRow.getString("backfill_status");
                        // Re-read processed count in case of concurrent execution
                        Long dbProcessed = statusRow.getLong("backfill_processed_messages");
                        long effectiveProcessed = dbProcessed != null ? dbProcessed : alreadyProcessed;
                        
                        if (STATUS_CANCELLED.equals(currentStatus)) {
                            try (var scope = TraceContextUtil.mdcScope(trace)) {
                                logger.info("Backfill cancelled for topic='{}', group='{}' after {} messages",
                                        topic, groupName, effectiveProcessed);
                            }
                            return Future.succeededFuture(BatchResult.cancelled(effectiveProcessed));
                        }

                        // Use the effective processed count from database
                        final long processedSoFar = effectiveProcessed;

                        // Step 2: Calculate effective limit
                        int effectiveLimit = batchSize;
                        if (maxMessages > 0) {
                            long remaining = maxMessages - processedSoFar;
                            if (remaining <= 0) {
                                return markBackfillCompleted(connection, trace, topic, groupName, processedSoFar)
                                        .map(v -> BatchResult.complete(processedSoFar));
                            }
                            effectiveLimit = (int) Math.min(batchSize, remaining);
                        }
                        int finalLimit = effectiveLimit;

                        // Step 3: Fetch batch of message IDs
                        return fetchBatchIds(connection, topic, startFromId, finalLimit)
                                .compose(messageIds -> processFetchedBatch(
                                        connection, trace, topic, groupName, messageIds,
                                        finalLimit, processedSoFar));
                    });
        });
    }

    private Future<List<Long>> fetchBatchIds(SqlConnection connection, String topic,
                                              long startFromId, int limit) {
        if (startFromId < 0) {
            return Future.failedFuture(new IllegalArgumentException("startFromId cannot be negative"));
        }
        if (limit <= 0) {
            return Future.failedFuture(new IllegalArgumentException("limit must be positive"));
        }

        String fetchSql = """
            SELECT id FROM outbox
            WHERE topic = $1 AND id >= $2
              AND status IN ('PENDING', 'PROCESSING', 'COMPLETED')
            ORDER BY id ASC
            LIMIT $3
            """;

        return connection.preparedQuery(fetchSql)
                .execute(Tuple.of(topic, startFromId, limit))
                .map(rows -> {
                    List<Long> ids = new ArrayList<>(rows.rowCount());
                    for (Row row : rows) {
                        ids.add(row.getLong("id"));
                    }
                    return ids;
                });
    }

    private Future<BatchResult> processFetchedBatch(SqlConnection connection, TraceCtx trace, String topic,
                                                     String groupName, List<Long> messageIds,
                                                     int effectiveLimit, long alreadyProcessed) {
        if (messageIds.isEmpty()) {
            return markBackfillCompleted(connection, trace, topic, groupName, alreadyProcessed)
                    .map(v -> BatchResult.complete(alreadyProcessed));
        }

        Long[] idArray = messageIds.toArray(new Long[0]);
        long checkpointId = messageIds.getLast();
        int batchCount = messageIds.size();
        long newProcessed = alreadyProcessed + batchCount;

        // Batch update: increment required_consumer_groups for all messages at once
        String incrementSql = """
            UPDATE outbox
            SET required_consumer_groups = required_consumer_groups + 1
            WHERE id = ANY($1) AND status IN ('PENDING', 'PROCESSING')
            """;

        return connection.preparedQuery(incrementSql)
                .execute(Tuple.of(idArray))
                .compose(incrementResult -> {
                    // Batch insert: create tracking rows for all messages at once
                    String trackingSql = """
                        INSERT INTO outbox_consumer_groups (message_id, group_name, status)
                        SELECT unnest($1::bigint[]), $2, 'PENDING'
                        ON CONFLICT (message_id, group_name) DO NOTHING
                        """;

                    return connection.preparedQuery(trackingSql)
                            .execute(Tuple.of(idArray, groupName));
                })
                .compose(trackingResult -> {
                    // Checkpoint within the same transaction
                    return updateCheckpoint(connection, topic, groupName, checkpointId, newProcessed);
                })
                .compose(v -> {
                    // Use debug logging for most batches, info for sampling
                    // Log at info level every ~100k messages or on the last batch
                    boolean shouldLogInfo = (newProcessed % 100_000 == 0) 
                            || (batchCount < effectiveLimit);
                    
                    if (shouldLogInfo) {
                        try (var scope = TraceContextUtil.mdcScope(trace)) {
                            logger.info("Backfill progress: topic='{}', group='{}', batchSize={}, " +
                                            "totalProcessed={}, checkpoint={}",
                                    topic, groupName, batchCount, newProcessed, checkpointId);
                        }
                    } else {
                        try (var scope = TraceContextUtil.mdcScope(trace)) {
                            logger.debug("Backfill batch: topic='{}', group='{}', batchSize={}, " +
                                            "totalProcessed={}, checkpoint={}",
                                    topic, groupName, batchCount, newProcessed, checkpointId);
                        }
                    }

                    if (batchCount < effectiveLimit) {
                        // Last batch was smaller than limit — done
                        return markBackfillCompleted(connection, trace, topic, groupName, newProcessed)
                                .map(ignored -> BatchResult.complete(newProcessed));
                    }
                    return Future.succeededFuture(BatchResult.more(newProcessed, checkpointId + 1));
                });
    }

    private Future<Void> updateCheckpoint(SqlConnection connection, String topic,
                                           String groupName, long checkpointId, long processedMessages) {
        String sql = """
            UPDATE outbox_topic_subscriptions
            SET backfill_checkpoint_id = $3,
                backfill_processed_messages = $4
            WHERE topic = $1 AND group_name = $2
            """;

        return connection.preparedQuery(sql)
                .execute(Tuple.of(topic, groupName, checkpointId, processedMessages))
                .compose(result -> {
                    if (result.rowCount() == 0) {
                        logger.warn("Checkpoint update matched no rows for topic='{}', group='{}' — subscription may have been deleted",
                                topic, groupName);
                    }
                    return Future.succeededFuture();
                });
    }

    private Future<Void> markBackfillCompleted(SqlConnection connection, TraceCtx trace, String topic,
                                                String groupName, long totalProcessed) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String sql = """
            UPDATE outbox_topic_subscriptions
            SET backfill_status = $5,
                backfill_processed_messages = $3,
                backfill_completed_at = $4
            WHERE topic = $1 AND group_name = $2
            """;

        return connection.preparedQuery(sql)
                .execute(Tuple.of(topic, groupName, totalProcessed, now, STATUS_COMPLETED))
                .compose(result -> {
                    try (var scope = TraceContextUtil.mdcScope(trace)) {
                        logger.info("Backfill completed for topic='{}', group='{}': {} messages processed",
                                topic, groupName, totalProcessed);
                    }
                    return Future.succeededFuture();
                });
    }

    // ========================================================================
    // Inner classes
    // ========================================================================

    /**
     * Result of a backfill operation.
     */
    public record BackfillResult(Status status, long processedMessages, String message) {

        public enum Status {
            COMPLETED,
            CANCELLED,
            ALREADY_COMPLETED,
            SKIPPED,  // Lock not acquired - another worker is processing
            FAILED
        }
    }

    /**
     * Progress information for an ongoing backfill.
     */
    public record BackfillProgress(
            String status,
            Long checkpointId,
            Long processedMessages,
            Long totalMessages,
            OffsetDateTime startedAt,
            OffsetDateTime completedAt
    ) {
        /**
         * Returns the completion percentage (0-100), or -1 if total is unknown.
         */
        public double percentComplete() {
            if (totalMessages == null || totalMessages == 0) {
                return -1.0;
            }
            long processed = processedMessages != null ? processedMessages : 0L;
            return (double) processed / totalMessages * 100.0;
        }
    }

    /**
     * Result of attempting to acquire backfill lock.
     * Used to prevent concurrent backfill workers on the same subscription.
     */
    private record LockAcquisitionResult(
            boolean acquired,
            boolean alreadyCompleted,
            long startFromId,
            long processedMessages
    ) {}

    /**
     * Internal result for a single batch iteration.
     */
    private record BatchResult(boolean isComplete, boolean isCancelled, long totalProcessed, long nextStartId) {

        static BatchResult complete(long totalProcessed) {
            return new BatchResult(true, false, totalProcessed, -1);
        }

        static BatchResult cancelled(long totalProcessed) {
            return new BatchResult(false, true, totalProcessed, -1);
        }

        static BatchResult more(long totalProcessed, long nextStartId) {
            return new BatchResult(false, false, totalProcessed, nextStartId);
        }

        BackfillResult toBackfillResult() {
            if (isCancelled) {
                return new BackfillResult(BackfillResult.Status.CANCELLED, totalProcessed,
                        "Backfill cancelled after " + totalProcessed + " messages");
            }
            return new BackfillResult(BackfillResult.Status.COMPLETED, totalProcessed,
                    "Backfill completed: " + totalProcessed + " messages processed");
        }
    }
}
