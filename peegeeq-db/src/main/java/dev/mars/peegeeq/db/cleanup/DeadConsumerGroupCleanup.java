package dev.mars.peegeeq.db.cleanup;

import dev.mars.peegeeq.api.tracing.TraceCtx;
import dev.mars.peegeeq.api.tracing.TraceContextUtil;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Cleans up messages blocked by dead consumer groups by decrementing
 * {@code required_consumer_groups} and removing orphaned tracking rows.
 *
 * <p>When a consumer group is marked DEAD, its unprocessed messages remain stuck
 * because {@code completed_consumer_groups} can never reach {@code required_consumer_groups}.
 * This service resolves that by:</p>
 *
 * <ol>
 *   <li>Decrementing {@code required_consumer_groups} on messages the dead group
 *       has NOT completed (PENDING or PROCESSING status)</li>
 *   <li>Removing orphaned tracking rows from {@code outbox_consumer_groups} for the dead group</li>
 *   <li>Auto-completing messages where {@code completed_consumer_groups >= required_consumer_groups}
 *       after the decrement</li>
 * </ol>
 *
 * <h3>Idempotency:</h3>
 * <p>Running cleanup twice for the same dead group is safe. The SQL uses
 * {@code NOT EXISTS} to check for already-completed tracking rows and
 * {@code required_consumer_groups > 0} guards to prevent going negative.
 * The second run will find zero rows to update.</p>
 *
 * <h3>Usage:</h3>
 * <pre>{@code
 * DeadConsumerGroupCleanup cleanup = new DeadConsumerGroupCleanup(connectionManager, "peegeeq-main");
 *
 * // Clean up after a specific dead group
 * cleanup.cleanupDeadGroup("orders", "payment-processor")
 *     .onSuccess(result -> log.info("Cleaned: {}", result));
 *
 * // Clean up all dead groups across all topics
 * cleanup.cleanupAllDeadGroups()
 *     .onSuccess(results -> log.info("Cleaned {} groups", results.size()));
 * }</pre>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-03-01
 * @version 1.0
 */
public class DeadConsumerGroupCleanup {

    private static final Logger logger = LoggerFactory.getLogger(DeadConsumerGroupCleanup.class);

    private final PgConnectionManager connectionManager;
    private final String serviceId;

    /**
     * Result of cleaning up messages for a single dead consumer group.
     *
     * @param topic                  The topic that was cleaned up
     * @param groupName              The dead consumer group name
     * @param messagesDecremented    Number of messages whose required_consumer_groups was decremented
     * @param orphanRowsRemoved      Number of orphaned outbox_consumer_groups rows deleted
     * @param messagesAutoCompleted  Number of messages auto-completed after decrement
     *                               (completed_consumer_groups now >= required_consumer_groups)
     */
    public record CleanupResult(
            String topic,
            String groupName,
            int messagesDecremented,
            int orphanRowsRemoved,
            int messagesAutoCompleted
    ) {
        /** Total cleanup actions performed. */
        public int totalActions() {
            return messagesDecremented + orphanRowsRemoved + messagesAutoCompleted;
        }

        /** True if any cleanup work was done. */
        public boolean hadWork() {
            return totalActions() > 0;
        }
    }

    /**
     * Creates a new DeadConsumerGroupCleanup.
     *
     * @param connectionManager The connection manager for database access
     * @param serviceId         The service ID for connection pool selection
     */
    public DeadConsumerGroupCleanup(PgConnectionManager connectionManager, String serviceId) {
        this.connectionManager = Objects.requireNonNull(connectionManager, "connectionManager cannot be null");
        this.serviceId = Objects.requireNonNull(serviceId, "serviceId cannot be null");
        logger.info("DeadConsumerGroupCleanup initialized for service: {}", serviceId);
    }

    /**
     * Cleans up messages blocked by a specific dead consumer group on a topic.
     *
     * <p>This method runs all three cleanup steps within a single transaction:</p>
     * <ol>
     *   <li>Decrement {@code required_consumer_groups} on messages the dead group hasn't completed</li>
     *   <li>Delete orphaned PENDING/PROCESSING tracking rows for the dead group</li>
     *   <li>Auto-complete messages where completed now meets/exceeds required</li>
     * </ol>
     *
     * @param topic     The topic to clean up
     * @param groupName The dead consumer group name
     * @return Future containing the cleanup result with counts
     */
    public Future<CleanupResult> cleanupDeadGroup(String topic, String groupName) {
        Objects.requireNonNull(topic, "topic cannot be null");
        Objects.requireNonNull(groupName, "groupName cannot be null");

        TraceCtx trace = TraceCtx.createNew();
        try (var scope = TraceContextUtil.mdcScope(trace)) {
            logger.info("Starting cleanup for dead group='{}' on topic='{}'", groupName, topic);
        }

        return connectionManager.withTransaction(serviceId, connection -> {
            // Step 1: Decrement required_consumer_groups on messages the dead group hasn't completed
            return decrementRequiredGroups(connection, trace, topic, groupName)
                    .compose(decremented -> {
                        // Step 2: Remove orphaned tracking rows
                        return removeOrphanedTrackingRows(connection, trace, topic, groupName)
                                .compose(orphansRemoved -> {
                                    // Step 3: Auto-complete messages where completed >= required
                                    return autoCompleteMessages(connection, trace, topic)
                                            .map(autoCompleted -> {
                                                CleanupResult result = new CleanupResult(
                                                        topic, groupName,
                                                        decremented, orphansRemoved, autoCompleted);

                                                try (var scope = TraceContextUtil.mdcScope(trace)) {
                                                    if (result.hadWork()) {
                                                        logger.info("Cleanup complete for group='{}' on topic='{}': " +
                                                                        "{} messages decremented, {} orphan rows removed, " +
                                                                        "{} messages auto-completed",
                                                                groupName, topic,
                                                                decremented, orphansRemoved, autoCompleted);
                                                    } else {
                                                        logger.debug("Cleanup for group='{}' on topic='{}': " +
                                                                "no work needed (idempotent)", groupName, topic);
                                                    }
                                                }

                                                return result;
                                            });
                                });
                    });
        });
    }

    /**
     * Finds all DEAD subscriptions and cleans up their blocked messages.
     *
     * <p>This method queries {@code outbox_topic_subscriptions} for all DEAD subscriptions,
     * then runs {@link #cleanupDeadGroup} for each one. Each group is cleaned in its own
     * transaction so that a failure for one group does not block cleanup of others.</p>
     *
     * @return Future containing a list of cleanup results, one per dead group
     */
    public Future<List<CleanupResult>> cleanupAllDeadGroups() {
        TraceCtx trace = TraceCtx.createNew();
        try (var scope = TraceContextUtil.mdcScope(trace)) {
            logger.info("Starting cleanup for all dead consumer groups");
        }

        return connectionManager.withConnection(serviceId, connection -> {
            String sql = """
                SELECT topic, group_name
                FROM outbox_topic_subscriptions
                WHERE subscription_status = 'DEAD'
                ORDER BY topic, group_name
                """;

            return connection.preparedQuery(sql)
                    .execute()
                    .map(rows -> {
                        List<TopicGroup> deadGroups = new ArrayList<>();
                        for (Row row : rows) {
                            deadGroups.add(new TopicGroup(
                                    row.getString("topic"),
                                    row.getString("group_name")));
                        }
                        return deadGroups;
                    });
        }).compose(deadGroups -> {
            if (deadGroups.isEmpty()) {
                try (var scope = TraceContextUtil.mdcScope(trace)) {
                    logger.debug("No dead consumer groups found \u2014 nothing to clean up");
                }
                return Future.succeededFuture(Collections.<CleanupResult>emptyList());
            }

            try (var scope = TraceContextUtil.mdcScope(trace)) {
                logger.info("Found {} dead consumer group(s) to clean up", deadGroups.size());
            }

            // Chain cleanup calls sequentially (each has its own transaction)
            Future<List<CleanupResult>> chain = Future.succeededFuture(new ArrayList<>());

            for (TopicGroup tg : deadGroups) {
                chain = chain.compose(results ->
                        cleanupDeadGroup(tg.topic(), tg.groupName())
                                .map(result -> {
                                    results.add(result);
                                    return results;
                                })
                                .recover(error -> {
                                    // Log but don't fail the whole batch
                                    try (var scope = TraceContextUtil.mdcScope(trace)) {
                                        logger.error("Cleanup failed for group='{}' on topic='{}': {}",
                                                tg.groupName(), tg.topic(), error.getMessage(), error);
                                    }
                                    results.add(new CleanupResult(
                                            tg.topic(), tg.groupName(), 0, 0, 0));
                                    return Future.succeededFuture(results);
                                })
                );
            }

            return chain.map(results -> {
                int totalDecremented = results.stream().mapToInt(CleanupResult::messagesDecremented).sum();
                int totalOrphans = results.stream().mapToInt(CleanupResult::orphanRowsRemoved).sum();
                int totalAutoCompleted = results.stream().mapToInt(CleanupResult::messagesAutoCompleted).sum();

                try (var scope = TraceContextUtil.mdcScope(trace)) {
                    logger.info("Cleanup complete for all {} dead group(s): " +
                                    "{} messages decremented, {} orphan rows removed, {} messages auto-completed",
                            results.size(), totalDecremented, totalOrphans, totalAutoCompleted);
                }

                return Collections.unmodifiableList(results);
            });
        });
    }

    // ========================================================================
    // Internal SQL operations — each takes an existing connection/transaction
    // ========================================================================

    /**
     * Step 1: Decrement {@code required_consumer_groups} on messages where the dead
     * group has NOT completed processing.
     *
     * <p>The NOT EXISTS clause ensures we only decrement for messages the dead group
     * hasn't already completed — making this idempotent.</p>
     */
    private Future<Integer> decrementRequiredGroups(SqlConnection connection, TraceCtx trace, String topic, String groupName) {
        String sql = """
            UPDATE outbox
            SET required_consumer_groups = required_consumer_groups - 1
            WHERE topic = $1
              AND status IN ('PENDING', 'PROCESSING')
              AND required_consumer_groups > 0
              AND NOT EXISTS (
                  SELECT 1 FROM outbox_consumer_groups cg
                  WHERE cg.message_id = outbox.id
                    AND cg.group_name = $2
                    AND cg.status = 'COMPLETED'
              )
            """;

        return connection.preparedQuery(sql)
                .execute(Tuple.of(topic, groupName))
                .map(result -> {
                    int count = result.rowCount();
                    try (var scope = TraceContextUtil.mdcScope(trace)) {
                        if (count > 0) {
                            logger.info("  Step 1: Decremented required_consumer_groups on {} message(s) " +
                                    "for dead group='{}' on topic='{}'", count, groupName, topic);
                        } else {
                            logger.debug("  Step 1: No messages to decrement for group='{}' on topic='{}'",
                                    groupName, topic);
                        }
                    }
                    return count;
                });
    }

    /**
     * Step 2: Remove orphaned tracking rows from {@code outbox_consumer_groups} for the
     * dead group. Only removes non-COMPLETED rows — COMPLETED rows are kept for audit.
     */
    private Future<Integer> removeOrphanedTrackingRows(SqlConnection connection, TraceCtx trace, String topic, String groupName) {
        String sql = """
            DELETE FROM outbox_consumer_groups
            WHERE group_name = $2
              AND status != 'COMPLETED'
              AND message_id IN (
                  SELECT id FROM outbox
                  WHERE topic = $1
                    AND status IN ('PENDING', 'PROCESSING')
              )
            """;

        return connection.preparedQuery(sql)
                .execute(Tuple.of(topic, groupName))
                .map(result -> {
                    int count = result.rowCount();
                    try (var scope = TraceContextUtil.mdcScope(trace)) {
                        if (count > 0) {
                            logger.info("  Step 2: Removed {} orphaned tracking row(s) " +
                                    "for dead group='{}' on topic='{}'", count, groupName, topic);
                        } else {
                            logger.debug("  Step 2: No orphan rows to remove for group='{}' on topic='{}'",
                                    groupName, topic);
                        }
                    }
                    return count;
                });
    }

    /**
     * Step 3: Auto-complete messages where {@code completed_consumer_groups >= required_consumer_groups}
     * after decrement. This handles the case where decrementing was the last thing needed to
     * make the message eligible for cleanup.
     */
    private Future<Integer> autoCompleteMessages(SqlConnection connection, TraceCtx trace, String topic) {
        String sql = """
            UPDATE outbox
            SET status = 'COMPLETED',
                processed_at = NOW()
            WHERE topic = $1
              AND status IN ('PENDING', 'PROCESSING')
              AND required_consumer_groups > 0
              AND completed_consumer_groups >= required_consumer_groups
            """;

        return connection.preparedQuery(sql)
                .execute(Tuple.of(topic))
                .map(result -> {
                    int count = result.rowCount();
                    try (var scope = TraceContextUtil.mdcScope(trace)) {
                        if (count > 0) {
                            logger.info("  Step 3: Auto-completed {} message(s) on topic='{}' " +
                                    "(completed_consumer_groups now meets required)", count, topic);
                        } else {
                            logger.debug("  Step 3: No messages to auto-complete on topic='{}'", topic);
                        }
                    }
                    return count;
                });
    }

    /** Internal helper to pair topic + group name for iteration. */
    private record TopicGroup(String topic, String groupName) {}
}
