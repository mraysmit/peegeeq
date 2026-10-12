package dev.mars.peegeeq.db.subscription;

import dev.mars.peegeeq.api.messaging.BackfillScope;
import dev.mars.peegeeq.api.messaging.StartPosition;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.api.subscription.ForceRemoveResult;
import dev.mars.peegeeq.api.subscription.PartitionAssignmentInfo;
import dev.mars.peegeeq.api.subscription.SubscriptionBoundConflictException;
import dev.mars.peegeeq.api.subscription.SubscriptionInfo;
import dev.mars.peegeeq.api.subscription.SubscriptionService;
import dev.mars.peegeeq.api.subscription.SubscriptionSettingsUpdate;
import dev.mars.peegeeq.api.subscription.SubscriptionState;
import dev.mars.peegeeq.api.subscription.SubscriptionStateException;
import dev.mars.peegeeq.api.subscription.SubscriptionStore;
import dev.mars.peegeeq.db.consumer.OutboxMessage;
import dev.mars.peegeeq.db.consumer.PartitionAssignment;
import dev.mars.peegeeq.api.tracing.TraceCtx;
import dev.mars.peegeeq.api.tracing.TraceContextUtil;
import dev.mars.peegeeq.db.cleanup.DeadConsumerDetector;
import dev.mars.peegeeq.db.cleanup.DeadConsumerGroupCleanup;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service for managing consumer group subscriptions to topics.
 *
 * <p>This class provides the core API for subscription lifecycle management including:
 * <ul>
 *   <li>Creating new subscriptions with configurable start positions</li>
 *   <li>Pausing and resuming subscriptions</li>
 *   <li>Cancelling subscriptions</li>
 *   <li>Updating heartbeats to prevent dead consumer detection</li>
 *   <li>Querying subscription status</li>
 * </ul>
 *
 * <p>All methods return Vert.x Future for composable asynchronous operations
 * following modern Vert.x 5.x patterns.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-12
 * @version 1.0
 */
public class SubscriptionManager implements SubscriptionService {
    
    private static final Logger logger = LoggerFactory.getLogger(SubscriptionManager.class);

    // The statuses from which each lifecycle call is accepted. A call from any other
    // status fails and leaves the row unchanged.
    private static final Set<SubscriptionStatus> PAUSE_FROM =
        Set.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.STOPPED, SubscriptionStatus.PAUSED);
    private static final Set<SubscriptionStatus> RESUME_FROM =
        Set.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.PAUSED, SubscriptionStatus.DEAD);
    private static final Set<SubscriptionStatus> MARK_DEAD_FROM =
        Set.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.STOPPED, SubscriptionStatus.PAUSED,
               SubscriptionStatus.DEAD);
    private static final Set<SubscriptionStatus> CANCEL_FROM = Set.of(SubscriptionStatus.values());

    private final PgConnectionManager connectionManager;
    private final String serviceId;
    private BackfillService backfillService; // optional enables auto-backfill on FROM_BEGINNING subscribe
    private DeadConsumerGroupCleanup deadConsumerGroupCleanup; // optional required for forceRemove

    // Partitioned consumption services (optional enables OFFSET_WATERMARK mode)
    private dev.mars.peegeeq.db.consumer.PartitionAssignmentService partitionAssignmentService;
    private dev.mars.peegeeq.db.consumer.PartitionedFetcher partitionedFetcher;
    private dev.mars.peegeeq.db.consumer.PartitionedOffsetManager partitionedOffsetManager;
    
    /**
     * Creates a new SubscriptionManager using the default pool.
     *
     * @param connectionManager The connection manager for database access
     */
    public SubscriptionManager(PgConnectionManager connectionManager) {
        this(connectionManager, null);
    }

    /**
     * Creates a new SubscriptionManager.
     *
     * @param connectionManager The connection manager for database access
     * @param serviceId The service ID for connection pool selection, or null for the default pool
     */
    public SubscriptionManager(PgConnectionManager connectionManager, String serviceId) {
        this.connectionManager = Objects.requireNonNull(connectionManager, "connectionManager cannot be null");
        this.serviceId = serviceId;  // null is allowed - means use default pool
        logger.info("SubscriptionManager initialized for service: {}", serviceId != null ? serviceId : "(default)");
    }

    /**
     * Sets the BackfillService for automatic backfill on FROM_BEGINNING subscriptions.
     *
     * <p>When set, subscribing with {@link StartPosition#FROM_BEGINNING} will automatically
     * trigger a backfill of existing messages to the consumer group, provided that the stored
     * start bound of the subscription is the beginning. If the backfill fails, the subscription
     * is still created the backfill can be retried manually.</p>
     *
     * @param backfillService The backfill service, or null to disable auto-backfill
     */
    public void setBackfillService(BackfillService backfillService) {
        this.backfillService = backfillService;
        logger.info("BackfillService {} for SubscriptionManager",
                   backfillService != null ? "configured" : "cleared");
    }

    /**
     * Sets the DeadConsumerGroupCleanup for force-remove operations.
     *
     * <p>When set, {@link #forceRemoveConsumerGroup(String, String)} can mark a consumer group
     * as dead, run cleanup to unblock stuck messages, and then cancel the subscription.</p>
     *
     * @param cleanup The cleanup service (must not be null)
     */
    public void setDeadConsumerGroupCleanup(DeadConsumerGroupCleanup cleanup) {
        this.deadConsumerGroupCleanup = Objects.requireNonNull(cleanup, "cleanup cannot be null");
        logger.info("DeadConsumerGroupCleanup configured for SubscriptionManager");
    }

    /**
     * Sets the partitioned consumption services for OFFSET_WATERMARK mode.
     *
     * @param assignmentService The partition assignment service
     * @param fetcher The partitioned fetcher
     * @param offsetManager The partitioned offset manager
     */
    public void setPartitionedConsumptionServices(
            dev.mars.peegeeq.db.consumer.PartitionAssignmentService assignmentService,
            dev.mars.peegeeq.db.consumer.PartitionedFetcher fetcher,
            dev.mars.peegeeq.db.consumer.PartitionedOffsetManager offsetManager) {
        this.partitionAssignmentService = Objects.requireNonNull(assignmentService, "assignmentService cannot be null");
        this.partitionedFetcher = Objects.requireNonNull(fetcher, "fetcher cannot be null");
        this.partitionedOffsetManager = Objects.requireNonNull(offsetManager, "offsetManager cannot be null");
        logger.info("Partitioned consumption services configured for SubscriptionManager");
    }
    
    /**
     * Subscribes a consumer group to a topic with default options.
     *
     * @param topic The topic name
     * @param groupName The consumer group name
     * @param store The message table that the message ids of the subscription belong to
     * @return Future that completes when subscription is created
     */
    @Override
    public Future<Void> subscribe(String topic, String groupName, SubscriptionStore store) {
        return subscribe(topic, groupName, store, SubscriptionOptions.defaults());
    }

    /**
     * Subscribes a consumer group to a topic with custom options.
     *
     * <p>When the group has no subscription on the topic, this method creates a record in the
     * outbox_topic_subscriptions table. The new subscription is ACTIVE and its start bound comes
     * from the options. FROM_NOW is resolved against the message table of the store, in the
     * transaction that creates the record.</p>
     *
     * <p>When a subscription exists, its stored start bound is kept. The start position of the
     * options is not resolved and no message table is read. An ACTIVE, STOPPED, or DEAD
     * subscription becomes ACTIVE. A PAUSED subscription stays PAUSED. A CANCELLED subscription
     * is unchanged and the returned Future fails with {@link SubscriptionStateException}.</p>
     *
     * <p>When a BackfillService is configured and the options name FROM_BEGINNING, the automatic
     * backfill starts only if the stored start bound is the beginning.</p>
     *
     * @param topic The topic name
     * @param groupName The consumer group name
     * @param store The message table that the message ids of the subscription belong to
     * @param options Subscription configuration options
     * @return Future that completes when subscription is created or made active
     */
    @Override
    public Future<Void> subscribe(String topic, String groupName, SubscriptionStore store, SubscriptionOptions options) {
        Objects.requireNonNull(topic, "topic cannot be null");
        Objects.requireNonNull(groupName, "groupName cannot be null");
        Objects.requireNonNull(store, "store cannot be null");
        Objects.requireNonNull(options, "options cannot be null");

        TraceCtx trace = TraceCtx.createNew();
        try (var scope = TraceContextUtil.mdcScope(trace)) {
            logger.info("Subscribing consumer group '{}' to topic '{}' with store {} and options: {}",
                       groupName, topic, store, options);
        }

        Future<Subscription> subscribeFuture = connectionManager.withTransaction(serviceId, connection ->
            subscribeInternal(trace, topic, groupName, store, options, connection)
        );

        // Auto-trigger backfill for FROM_BEGINNING subscriptions when BackfillService is configured
        if (backfillService != null && options.getStartPosition() == StartPosition.FROM_BEGINNING) {
            return subscribeFuture.compose(stored -> {
                // The stored start bound is authoritative. An existing subscription that starts
                // later keeps its bound, and the messages before it are not given to the group.
                if (!isBeginning(stored.getStartFromMessageId(), stored.getStartFromTimestamp())) {
                    try (var scope = TraceContextUtil.mdcScope(trace)) {
                        logger.info("Automatic backfill not started: the stored start bound is not the beginning: "
                                       + "topic='{}', group='{}', start_from_message_id={}, start_from_timestamp={}",
                                   topic, groupName, stored.getStartFromMessageId(), stored.getStartFromTimestamp());
                    }
                    return Future.<Void>succeededFuture();
                }
                try (var scope = TraceContextUtil.mdcScope(trace)) {
                    logger.info("Auto-triggering backfill for FROM_BEGINNING subscription: topic='{}', group='{}'",
                               topic, groupName);
                }
                return backfillService.startBackfill(topic, groupName,
                                options.getBackfillScope())
                    .map(result -> {
                        try (var scope = TraceContextUtil.mdcScope(trace)) {
                            logger.info("Auto-backfill completed: topic='{}', group='{}', status={}, processed={}",
                                       topic, groupName, result.status(), result.processedMessages());
                        }
                        return (Void) null;
                    })
                    .onFailure(error -> {
                        try (var scope = TraceContextUtil.mdcScope(trace)) {
                            logger.warn("Auto-backfill failed for topic='{}', group='{}': {}",
                                       topic, groupName, error.getMessage());
                        }
                    });
            });
        }

        return subscribeFuture.mapEmpty();
    }

    /** A start bound: a message id, or a timestamp. */
    private record StartBound(Long messageId, Instant timestamp) {
    }

    /**
     * Internal implementation of subscribe using provided connection.
     *
     * <p>The row is locked before anything else. An existing subscription keeps its stored start
     * bound, so the start position of the options is resolved only when no row exists. The
     * message table of the store is read only then.</p>
     *
     * @return Future containing the subscription as stored after the call
     */
    private Future<Subscription> subscribeInternal(TraceCtx trace, String topic, String groupName,
                                                   SubscriptionStore store, SubscriptionOptions options,
                                                   SqlConnection connection) {
        return lockSubscriptionStatus(connection, topic, groupName)
            .compose(lockedStatus -> {
                if (lockedStatus != null) {
                    return reactivateSubscription(trace, topic, groupName, options, connection, lockedStatus);
                }
                return resolveStartBound(topic, groupName, store, options, connection)
                    .compose(bound -> insertSubscription(trace, topic, groupName, options, connection, bound))
                    .compose(created -> {
                        if (created != null) {
                            return Future.succeededFuture(created);
                        }
                        // Another transaction created the subscription first. Its start bound is kept.
                        return lockSubscriptionStatus(connection, topic, groupName)
                            .compose(existingStatus -> reactivateSubscription(
                                trace, topic, groupName, options, connection, existingStatus));
                    });
            });
    }

    /**
     * Locks the subscription row and reads its status.
     *
     * @return Future containing the status, or null when the group has no subscription on the topic
     */
    private Future<String> lockSubscriptionStatus(SqlConnection connection, String topic, String groupName) {
        String sql = """
            SELECT subscription_status
            FROM outbox_topic_subscriptions
            WHERE topic = $1 AND group_name = $2
            FOR UPDATE
            """;

        return connection.preparedQuery(sql)
            .execute(Tuple.of(topic, groupName))
            .map(rows -> rows.size() == 0 ? null : rows.iterator().next().getString("subscription_status"));
    }

    /**
     * Returns the message table that the message ids of the store belong to.
     */
    private static String messageTable(SubscriptionStore store) {
        return switch (store) {
            case OUTBOX -> "outbox";
            case NATIVE -> "queue_messages";
        };
    }

    /**
     * Resolves the start bound that a new subscription stores. FROM_NOW is resolved against the
     * message table of the store, on the connection of the transaction that creates the
     * subscription.
     */
    private Future<StartBound> resolveStartBound(String topic, String groupName, SubscriptionStore store,
                                                 SubscriptionOptions options, SqlConnection connection) {
        switch (options.getStartPosition()) {
            case FROM_NOW:
                // Get current max message ID and start from next message
                if (options.getStartFromMessageId() == null) {
                    String maxIdSql = "SELECT COALESCE(MAX(id), 0) AS max_id FROM " + messageTable(store)
                        + " WHERE topic = $1";
                    return connection.preparedQuery(maxIdSql)
                        .execute(Tuple.of(topic))
                        .map(rows -> {
                            Long maxId = rows.iterator().next().getLong("max_id");
                            Long startFromMessageId = maxId + 1; // Start from next message
                            logger.debug("FROM_NOW: Setting start_from_message_id={} from {} for topic='{}', group='{}'",
                                startFromMessageId, messageTable(store), topic, groupName);
                            return new StartBound(startFromMessageId, null);
                        });
                } else {
                    return Future.succeededFuture(new StartBound(options.getStartFromMessageId(), null));
                }

            case FROM_BEGINNING:
                // Start from message ID 1
                logger.debug("FROM_BEGINNING: Setting start_from_message_id=1 for topic='{}', group='{}'",
                    topic, groupName);
                return Future.succeededFuture(new StartBound(1L, null));

            case FROM_MESSAGE_ID:
                // Use the provided message ID
                return Future.succeededFuture(new StartBound(options.getStartFromMessageId(), null));

            case FROM_TIMESTAMP:
                // Use the provided timestamp
                return Future.succeededFuture(new StartBound(null, options.getStartFromTimestamp()));

            default:
                return Future.failedFuture("Unknown start position: " + options.getStartPosition());
        }
    }

    /**
     * Inserts the subscription record with its start bound.
     *
     * @return Future containing the created subscription, or null when another transaction
     *         created the subscription first
     */
    private Future<Subscription> insertSubscription(TraceCtx trace, String topic, String groupName,
                                                    SubscriptionOptions options, SqlConnection connection,
                                                    StartBound startBound) {
        String sql = """
            INSERT INTO outbox_topic_subscriptions (
                topic, group_name, subscription_status,
                start_from_message_id, start_from_timestamp,
                heartbeat_interval_seconds, heartbeat_timeout_seconds,
                subscribed_at, last_active_at, last_heartbeat_at,
                consecutive_misses, dead_after_misses
            ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, 0, $11)
            ON CONFLICT (topic, group_name) DO NOTHING
            RETURNING id, topic, group_name, subscription_status, subscribed_at, last_active_at,
                   start_from_message_id, start_from_timestamp,
                   heartbeat_interval_seconds, heartbeat_timeout_seconds, last_heartbeat_at,
                   backfill_status, backfill_checkpoint_id, backfill_processed_messages,
                   backfill_total_messages, backfill_started_at, backfill_completed_at
            """;

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        // Convert Instant to OffsetDateTime for database storage
        OffsetDateTime startFromTimestampOdt = startBound.timestamp() != null
            ? startBound.timestamp().atOffset(ZoneOffset.UTC)
            : null;

        Tuple params = Tuple.of(
            topic,
            groupName,
            SubscriptionStatus.ACTIVE.name(),
            startBound.messageId(),
            startFromTimestampOdt,
            options.getHeartbeatIntervalSeconds(),
            options.getHeartbeatTimeoutSeconds(),
            now,
            now,
            now,
            options.getDeadAfterMisses()
        );

        return connection.preparedQuery(sql)
            .execute(params)
            .onFailure(error -> logSubscribeFailure(trace, topic, groupName, error))
            .map(result -> {
                if (result.size() == 0) {
                    return null;
                }
                Subscription created = mapRowToSubscription(result.iterator().next());
                logSubscribed(trace, created);
                return created;
            });
    }

    /**
     * Makes an existing subscription active. The row is already locked.
     *
     * <p>The stored start bound is kept. A PAUSED subscription stays PAUSED. A CANCELLED
     * subscription is left unchanged and fails the call.</p>
     *
     * @return Future containing the subscription as stored after the call
     */
    private Future<Subscription> reactivateSubscription(TraceCtx trace, String topic, String groupName,
                                                        SubscriptionOptions options, SqlConnection connection,
                                                        String lockedStatus) {
        if (lockedStatus == null) {
            return Future.failedFuture(new IllegalStateException(String.format(
                "Subscription not found after a conflicting insert: topic='%s', group='%s'", topic, groupName)));
        }

        // CANCELLED is terminal.
        if (SubscriptionStatus.CANCELLED.name().equals(lockedStatus)) {
            String msg = String.format(
                "Cannot subscribe: the subscription is CANCELLED: topic='%s', group='%s'",
                topic, groupName);
            try (var scope = TraceContextUtil.mdcScope(trace)) {
                logger.warn("{}", msg);
            }
            return Future.failedFuture(new SubscriptionStateException(msg, SubscriptionState.CANCELLED));
        }

        String sql = """
            UPDATE outbox_topic_subscriptions
            SET subscription_status = CASE
                    WHEN subscription_status = 'PAUSED' THEN subscription_status
                    ELSE 'ACTIVE'
                END,
                last_active_at = $3,
                last_heartbeat_at = $3,
                heartbeat_interval_seconds = $4,
                heartbeat_timeout_seconds = $5,
                dead_after_misses = $6,
                -- Intentional reset: stale miss history must not survive resubscription.
                -- Write cost is driven by heartbeat cadence, not this counter.
                consecutive_misses = 0
            WHERE topic = $1 AND group_name = $2
            RETURNING id, topic, group_name, subscription_status, subscribed_at, last_active_at,
                   start_from_message_id, start_from_timestamp,
                   heartbeat_interval_seconds, heartbeat_timeout_seconds, last_heartbeat_at,
                   backfill_status, backfill_checkpoint_id, backfill_processed_messages,
                   backfill_total_messages, backfill_started_at, backfill_completed_at
            """;

        Tuple params = Tuple.of(
            topic,
            groupName,
            OffsetDateTime.now(ZoneOffset.UTC),
            options.getHeartbeatIntervalSeconds(),
            options.getHeartbeatTimeoutSeconds(),
            options.getDeadAfterMisses()
        );

        return connection.preparedQuery(sql)
            .execute(params)
            .onFailure(error -> logSubscribeFailure(trace, topic, groupName, error))
            .map(result -> {
                Subscription stored = mapRowToSubscription(result.iterator().next());
                logSubscribed(trace, stored);
                return stored;
            });
    }

    private void logSubscribeFailure(TraceCtx trace, String topic, String groupName, Throwable error) {
        try (var scope = TraceContextUtil.mdcScope(trace)) {
            logger.error("Failed to subscribe consumer group '{}' to topic '{}': {}",
                        groupName, topic, error.getMessage(), error);
        }
    }

    private void logSubscribed(TraceCtx trace, Subscription subscription) {
        try (var scope = TraceContextUtil.mdcScope(trace)) {
            logger.info("Successfully subscribed consumer group '{}' to topic '{}' "
                           + "(status={}, start_from_message_id={}, start_from_timestamp={})",
                       subscription.getGroupName(), subscription.getTopic(), subscription.getStatus(),
                       subscription.getStartFromMessageId(), subscription.getStartFromTimestamp());
        }
    }

    /**
     * Pauses a subscription.
     *
     * <p>An ACTIVE, STOPPED, or PAUSED subscription becomes PAUSED. A DEAD or CANCELLED
     * subscription is unchanged and the returned Future fails with
     * {@link SubscriptionStateException}. Existing messages remain in the queue.</p>
     *
     * @param topic The topic name
     * @param groupName The consumer group name
     * @return Future that completes when subscription is paused
     */
    @Override
    public Future<Void> pause(String topic, String groupName) {
        Objects.requireNonNull(topic, "topic cannot be null");
        Objects.requireNonNull(groupName, "groupName cannot be null");

        TraceCtx trace = TraceCtx.createNew();
        try (var scope = TraceContextUtil.mdcScope(trace)) {
            logger.info("Pausing subscription for consumer group '{}' on topic '{}'", groupName, topic);
        }

        return updateStatus(trace, topic, groupName, SubscriptionStatus.PAUSED, PAUSE_FROM, "pause");
    }

    /**
     * Resumes a subscription.
     *
     * <p>An ACTIVE, PAUSED, or DEAD subscription becomes ACTIVE. A STOPPED or CANCELLED
     * subscription is unchanged and the returned Future fails with
     * {@link SubscriptionStateException}.</p>
     *
     * @param topic The topic name
     * @param groupName The consumer group name
     * @return Future that completes when subscription is resumed
     */
    @Override
    public Future<Void> resume(String topic, String groupName) {
        Objects.requireNonNull(topic, "topic cannot be null");
        Objects.requireNonNull(groupName, "groupName cannot be null");

        TraceCtx trace = TraceCtx.createNew();
        try (var scope = TraceContextUtil.mdcScope(trace)) {
            logger.info("Resuming subscription for consumer group '{}' on topic '{}'", groupName, topic);
        }

        return updateStatus(trace, topic, groupName, SubscriptionStatus.ACTIVE, RESUME_FROM, "resume");
    }

    /**
     * Stops a subscription for a planned outage of the whole consumer group.
     *
     * <p>An ACTIVE subscription becomes STOPPED. A subscription in any other status is
     * left unchanged. The start position and the committed offsets are kept. No cleanup
     * runs. A later subscribe makes a STOPPED subscription ACTIVE again.</p>
     *
     * @param topic The topic name
     * @param groupName The consumer group name
     * @return Future that completes when the status is stored; it fails when the
     *         group has no subscription on the topic
     */
    @Override
    public Future<Void> stopGroup(String topic, String groupName) {
        Objects.requireNonNull(topic, "topic cannot be null");
        Objects.requireNonNull(groupName, "groupName cannot be null");

        TraceCtx trace = TraceCtx.createNew();
        try (var scope = TraceContextUtil.mdcScope(trace)) {
            logger.info("Stopping subscription for consumer group '{}' on topic '{}'", groupName, topic);
        }

        return connectionManager.withTransaction(serviceId, connection -> {
            String sql = """
                WITH old AS (
                    SELECT subscription_status
                    FROM outbox_topic_subscriptions
                    WHERE topic = $2 AND group_name = $3
                ),
                updated AS (
                UPDATE outbox_topic_subscriptions
                SET subscription_status = CASE
                        WHEN subscription_status = 'ACTIVE' THEN 'STOPPED'
                        ELSE subscription_status
                    END,
                    last_active_at = CASE
                        WHEN subscription_status = 'ACTIVE' THEN $1
                        ELSE last_active_at
                    END
                WHERE topic = $2 AND group_name = $3
                RETURNING subscription_status AS new_status
                )
                SELECT updated.new_status, old.subscription_status AS old_status
                FROM updated, old
                """;

            Tuple params = Tuple.of(OffsetDateTime.now(ZoneOffset.UTC), topic, groupName);

            return connection.preparedQuery(sql)
                .execute(params)
                .compose(result -> {
                    if (result.rowCount() == 0) {
                        String msg = String.format("Subscription not found: topic='%s', group='%s'",
                                                  topic, groupName);
                        try (var scope = TraceContextUtil.mdcScope(trace)) {
                            logger.debug(msg);
                        }
                        return Future.failedFuture(new IllegalStateException(msg));
                    }

                    Row row = result.iterator().next();
                    try (var scope = TraceContextUtil.mdcScope(trace)) {
                        logger.info("Stopped subscription for group '{}' on topic '{}' (oldStatus={}, newStatus={})",
                                   groupName, topic, row.getString("old_status"), row.getString("new_status"));
                    }
                    return Future.succeededFuture();
                });
        });
    }

    /**
     * Updates the settings of an existing subscription.
     *
     * <p>Only the heartbeat settings that the update supplies are changed. The lifecycle status
     * and the stored start bound are never changed. When the update names a start position, the
     * call succeeds only if that position is the stored start bound; otherwise it fails with
     * {@link SubscriptionBoundConflictException} and changes nothing.</p>
     *
     * @param topic The topic name
     * @param groupName The consumer group name
     * @param update The settings to change and the start bound the caller names, if any
     * @return Future containing the subscription as stored after the update; it fails when the
     *         group has no subscription on the topic
     */
    @Override
    public Future<SubscriptionInfo> updateSubscriptionSettings(String topic, String groupName,
                                                               SubscriptionSettingsUpdate update) {
        Objects.requireNonNull(topic, "topic cannot be null");
        Objects.requireNonNull(groupName, "groupName cannot be null");
        Objects.requireNonNull(update, "update cannot be null");

        TraceCtx trace = TraceCtx.createNew();
        try (var scope = TraceContextUtil.mdcScope(trace)) {
            logger.info("Updating subscription settings for consumer group '{}' on topic '{}': {}",
                       groupName, topic, update);
        }

        return connectionManager.withTransaction(serviceId, connection -> {
            // The row is locked so that the checks and the update see the same stored settings.
            String selectSql = """
                SELECT id, topic, group_name, subscription_status, subscribed_at, last_active_at,
                       start_from_message_id, start_from_timestamp,
                       heartbeat_interval_seconds, heartbeat_timeout_seconds, last_heartbeat_at,
                       backfill_status, backfill_checkpoint_id, backfill_processed_messages,
                       backfill_total_messages, backfill_started_at, backfill_completed_at
                FROM outbox_topic_subscriptions
                WHERE topic = $1 AND group_name = $2
                FOR UPDATE
                """;

            return connection.preparedQuery(selectSql)
                .execute(Tuple.of(topic, groupName))
                .compose(rows -> {
                    if (rows.size() == 0) {
                        String msg = String.format("Subscription not found: topic='%s', group='%s'",
                                                  topic, groupName);
                        try (var scope = TraceContextUtil.mdcScope(trace)) {
                            logger.debug(msg);
                        }
                        return Future.failedFuture(new IllegalStateException(msg));
                    }

                    Subscription stored = mapRowToSubscription(rows.iterator().next());
                    return validateSettingsUpdate(stored, update)
                        .onFailure(refusal -> {
                            try (var scope = TraceContextUtil.mdcScope(trace)) {
                                logger.warn("Subscription settings update refused for group '{}' on topic '{}': {}",
                                           groupName, topic, refusal.getMessage());
                            }
                        })
                        .compose(v -> {
                            String updateSql = """
                                UPDATE outbox_topic_subscriptions
                                SET heartbeat_interval_seconds = COALESCE($3, heartbeat_interval_seconds),
                                    heartbeat_timeout_seconds = COALESCE($4, heartbeat_timeout_seconds)
                                WHERE topic = $1 AND group_name = $2
                                RETURNING id, topic, group_name, subscription_status, subscribed_at, last_active_at,
                                       start_from_message_id, start_from_timestamp,
                                       heartbeat_interval_seconds, heartbeat_timeout_seconds, last_heartbeat_at,
                                       backfill_status, backfill_checkpoint_id, backfill_processed_messages,
                                       backfill_total_messages, backfill_started_at, backfill_completed_at
                                """;

                            Tuple params = Tuple.of(topic, groupName,
                                update.heartbeatIntervalSeconds(), update.heartbeatTimeoutSeconds());

                            return connection.preparedQuery(updateSql)
                                .execute(params)
                                .map(updated -> toSubscriptionInfo(mapRowToSubscription(updated.iterator().next())));
                        });
                });
        });
    }

    /**
     * Checks a settings update against the stored subscription. The returned Future fails when
     * the update names a start bound that is not the stored bound, or when the heartbeat timeout
     * would not exceed the heartbeat interval.
     */
    private Future<Void> validateSettingsUpdate(Subscription stored, SubscriptionSettingsUpdate update) {
        if (update.namesBound() && !namesStoredBound(stored, update)) {
            return Future.failedFuture(new SubscriptionBoundConflictException(String.format(
                "The start position %s does not name the stored start bound "
                    + "(start_from_message_id=%s, start_from_timestamp=%s): topic='%s', group='%s'. "
                    + "A settings update cannot move the start bound.",
                update.startPosition(), stored.getStartFromMessageId(), stored.getStartFromTimestamp(),
                stored.getTopic(), stored.getGroupName())));
        }

        boolean heartbeatSupplied =
            update.heartbeatIntervalSeconds() != null || update.heartbeatTimeoutSeconds() != null;
        int interval = update.heartbeatIntervalSeconds() != null
            ? update.heartbeatIntervalSeconds()
            : stored.getHeartbeatIntervalSeconds();
        int timeout = update.heartbeatTimeoutSeconds() != null
            ? update.heartbeatTimeoutSeconds()
            : stored.getHeartbeatTimeoutSeconds();
        if (heartbeatSupplied && timeout <= interval) {
            return Future.failedFuture(new IllegalArgumentException(
                "heartbeatTimeoutSeconds must be greater than heartbeatIntervalSeconds"));
        }

        return Future.succeededFuture();
    }

    /**
     * Returns true when the start position named by the update is the stored start bound.
     */
    private static boolean namesStoredBound(Subscription stored, SubscriptionSettingsUpdate update) {
        Long storedMessageId = stored.getStartFromMessageId();
        Instant storedTimestamp = stored.getStartFromTimestamp();
        return switch (update.startPosition()) {
            // FROM_NOW is resolved to a message id when the subscription is created.
            // It does not identify the stored bound.
            case FROM_NOW -> false;
            case FROM_BEGINNING -> isBeginning(storedMessageId, storedTimestamp);
            case FROM_MESSAGE_ID -> storedTimestamp == null && update.startFromMessageId().equals(storedMessageId);
            // PostgreSQL stores microseconds.
            case FROM_TIMESTAMP -> storedMessageId == null && storedTimestamp != null
                && update.startFromTimestamp().truncatedTo(ChronoUnit.MICROS)
                    .equals(storedTimestamp.truncatedTo(ChronoUnit.MICROS));
        };
    }

    /**
     * Returns true when the start bound is the beginning of the topic: message id 1.
     */
    private static boolean isBeginning(Long startFromMessageId, Instant startFromTimestamp) {
        return startFromTimestamp == null && Long.valueOf(1L).equals(startFromMessageId);
    }

    /**
     * Cancels a subscription.
     *
     * <p>Cancelled subscriptions cannot be reactivated. This is a terminal state.
     * The consumer group will not receive any messages from this topic.</p>
     *
     * <p>If a {@link DeadConsumerGroupCleanup} is configured, cancel also runs
     * cleanup to decrement {@code required_consumer_groups}, remove orphaned
     * tracking rows, and auto-complete unblocked messages. Cleanup failure
     * does not fail the cancel operation.</p>
     *
     * @param topic The topic name
     * @param groupName The consumer group name
     * @return Future that completes when subscription is cancelled
     */
    @Override
    public Future<Void> cancel(String topic, String groupName) {
        Objects.requireNonNull(topic, "topic cannot be null");
        Objects.requireNonNull(groupName, "groupName cannot be null");

        TraceCtx trace = TraceCtx.createNew();
        try (var scope = TraceContextUtil.mdcScope(trace)) {
            logger.info("Cancelling subscription for consumer group '{}' on topic '{}'", groupName, topic);
        }

        return updateStatus(trace, topic, groupName, SubscriptionStatus.CANCELLED, CANCEL_FROM, "cancel")
                .compose(v -> {
                    if (deadConsumerGroupCleanup == null) {
                        try (var scope = TraceContextUtil.mdcScope(trace)) {
                            logger.debug("No DeadConsumerGroupCleanup configured skipping cancel cleanup " +
                                    "for group='{}' on topic='{}'", groupName, topic);
                        }
                        return Future.succeededFuture();
                    }

                    return deadConsumerGroupCleanup.cleanupDeadGroup(topic, groupName)
                            .map(result -> {
                                try (var scope = TraceContextUtil.mdcScope(trace)) {
                                    logger.info("Cancel cleanup for group='{}' on topic='{}': " +
                                                    "{} messages decremented, {} orphan rows removed, " +
                                                    "{} messages auto-completed",
                                            groupName, topic,
                                            result.messagesDecremented(),
                                            result.orphanRowsRemoved(),
                                            result.messagesAutoCompleted());
                                }
                                return (Void) null;
                            })
                            .onFailure(err -> {
                                try (var scope = TraceContextUtil.mdcScope(trace)) {
                                    logger.warn("Cancel cleanup failed for group='{}' on topic='{}': {}",
                                            groupName, topic, err.getMessage());
                                }
                            });
                });
    }

    /**
     * Force-removes a consumer group from a topic.
     *
     * <p>This administrative operation:</p>
     * <ol>
     *   <li>Reads the current subscription status (must not be CANCELLED)</li>
     *   <li>Marks the subscription as DEAD</li>
     *   <li>Runs dead-group cleanup (decrement required_consumer_groups,
     *       remove orphaned tracking rows, auto-complete unblocked messages)</li>
     *   <li>Marks the subscription as CANCELLED (terminal state)</li>
     * </ol>
     *
     * @param topic The topic name
     * @param groupName The consumer group name to force-remove
     * @return Future containing the cleanup result details
     */
    @Override
    public Future<ForceRemoveResult> forceRemoveConsumerGroup(String topic, String groupName) {
        Objects.requireNonNull(topic, "topic cannot be null");
        Objects.requireNonNull(groupName, "groupName cannot be null");

        if (deadConsumerGroupCleanup == null) {
            return Future.failedFuture(new IllegalStateException(
                    "DeadConsumerGroupCleanup is not configured. " +
                    "Call setDeadConsumerGroupCleanup() before using forceRemoveConsumerGroup()."));
        }

        TraceCtx trace = TraceCtx.createNew();
        try (var scope = TraceContextUtil.mdcScope(trace)) {
            logger.info("Force-removing consumer group '{}' on topic '{}'", groupName, topic);
        }

        // Step 1: Get current subscription status
        return getSubscription(topic, groupName)
                .compose(info -> {
                    if (info == null) {
                        return Future.failedFuture(new IllegalArgumentException(
                                "No subscription found for group '" + groupName + "' on topic '" + topic + "'"));
                    }
                    String previousStatus = info.state().name();
                    if (info.state() == SubscriptionState.CANCELLED) {
                        return Future.failedFuture(new IllegalStateException(
                                "Subscription for group '" + groupName + "' on topic '" + topic +
                                "' is already CANCELLED and cannot be force-removed"));
                    }

                    // Step 2: Mark DEAD (if not already)
                    Future<Void> markDeadFuture;
                    if (info.state() == SubscriptionState.DEAD) {
                        markDeadFuture = Future.succeededFuture();
                    } else {
                        markDeadFuture = updateStatus(trace, topic, groupName, SubscriptionStatus.DEAD,
                                MARK_DEAD_FROM, "mark dead");
                    }

                    // Step 3: Run cleanup, then Step 4: Mark CANCELLED
                    return markDeadFuture
                            .compose(v -> deadConsumerGroupCleanup.cleanupDeadGroup(topic, groupName))
                            .compose(cleanupResult -> {
                                try (var scope = TraceContextUtil.mdcScope(trace)) {
                                    logger.info("Force-remove cleanup for group '{}' on topic '{}': " +
                                                    "decremented={}, orphans={}, autoCompleted={}",
                                            groupName, topic,
                                            cleanupResult.messagesDecremented(),
                                            cleanupResult.orphanRowsRemoved(),
                                            cleanupResult.messagesAutoCompleted());
                                }
                                return updateStatus(trace, topic, groupName, SubscriptionStatus.CANCELLED,
                                                CANCEL_FROM, "cancel")
                                        .map(v -> new ForceRemoveResult(
                                                topic, groupName, previousStatus,
                                                cleanupResult.messagesDecremented(),
                                                cleanupResult.orphanRowsRemoved(),
                                                cleanupResult.messagesAutoCompleted()));
                            });
                });
    }

    /**
     * Updates the heartbeat timestamp for a subscription.
     *
     * <p>Consumer groups must call this method periodically to prevent being marked as DEAD.
     * The heartbeat interval and timeout are configured in SubscriptionOptions.</p>
     *
     * <p>A heartbeat changes no status. On an ACTIVE subscription it sets the heartbeat time
     * and clears the miss count. On a PAUSED subscription it sets the heartbeat time. On a
     * STOPPED, DEAD, or CANCELLED subscription the row is unchanged and the returned Future
     * fails with {@link SubscriptionStateException}, which names the status. A heartbeat does
     * not make a DEAD subscription ACTIVE: that takes an explicit resume or start.</p>
     *
     * @param topic The topic name
     * @param groupName The consumer group name
     * @return Future that completes when heartbeat is updated
     */
    @Override
    public Future<Void> updateHeartbeat(String topic, String groupName) {
        Objects.requireNonNull(topic, "topic cannot be null");
        Objects.requireNonNull(groupName, "groupName cannot be null");

        TraceCtx trace = TraceCtx.createNew();
        try (var scope = TraceContextUtil.mdcScope(trace)) {
            logger.debug("Updating heartbeat for consumer group '{}' on topic '{}'", groupName, topic);
        }

        return connectionManager.withTransaction(serviceId, connection -> {
            String sql = """
                WITH old AS (
                    SELECT subscription_status
                    FROM outbox_topic_subscriptions
                    WHERE topic = $2 AND group_name = $3
                ),
                updated AS (
                    UPDATE outbox_topic_subscriptions
                    SET last_heartbeat_at = $1,
                        last_active_at = $1,
                        -- Intentional reset: a real heartbeat of an ACTIVE group clears stale
                        -- miss history. Moderate heartbeat cadence (30-60s) matters more than
                        -- this counter.
                        consecutive_misses = CASE
                            WHEN subscription_status = 'ACTIVE' THEN 0
                            ELSE consecutive_misses
                        END
                    WHERE topic = $2 AND group_name = $3
                      AND subscription_status IN ('ACTIVE', 'PAUSED')
                    RETURNING subscription_status AS new_status
                )
                SELECT old.subscription_status AS old_status, updated.new_status
                FROM old LEFT JOIN updated ON TRUE
                """;

            Tuple params = Tuple.of(OffsetDateTime.now(ZoneOffset.UTC), topic, groupName);

            return connection.preparedQuery(sql)
                .execute(params)
                .compose(result -> settleTransition(trace, result, topic, groupName, "send a heartbeat for"))
                .mapEmpty();
        });
    }

    /**
     * Starts or resumes a backfill operation for a consumer group subscription.
     *
     * <p>Delegates to the configured {@link BackfillService}. If no BackfillService
     * is configured, returns a failed future.</p>
     *
     * @param topic The topic name
     * @param groupName The consumer group name
     * @return Future containing a JSON object with backfill result
     */
    @Override
    public Future<JsonObject> startBackfill(String topic, String groupName) {
        return startBackfill(topic, groupName, BackfillScope.PENDING_ONLY);
    }

    @Override
    public Future<JsonObject> startBackfill(String topic, String groupName, BackfillScope scope) {
        Objects.requireNonNull(topic, "topic cannot be null");
        Objects.requireNonNull(groupName, "groupName cannot be null");
        Objects.requireNonNull(scope, "scope cannot be null");
        if (backfillService == null) {
            return Future.failedFuture(new UnsupportedOperationException(
                    "BackfillService not configured on this SubscriptionManager"));
        }
        return backfillService.startBackfill(topic, groupName, scope)
            .map(result -> new JsonObject()
                .put("status", result.status().name())
                .put("processedMessages", result.processedMessages())
                .put("scope", scope.name())
                .put("message", result.message()));
    }

    /**
     * Cancels an in-progress backfill operation.
     *
     * <p>Delegates to the configured {@link BackfillService}.</p>
     *
     * @param topic The topic name
     * @param groupName The consumer group name
     * @return Future that completes when cancellation is recorded
     */
    @Override
    public Future<Void> cancelBackfill(String topic, String groupName) {
        Objects.requireNonNull(topic, "topic cannot be null");
        Objects.requireNonNull(groupName, "groupName cannot be null");
        if (backfillService == null) {
            return Future.failedFuture(new UnsupportedOperationException(
                    "BackfillService not configured on this SubscriptionManager"));
        }
        return backfillService.cancelBackfill(topic, groupName);
    }

    /**
     * Gets a subscription by topic and group name.
     *
     * @param topic The topic name
     * @param groupName The consumer group name
     * @return Future containing the subscription info, or null if not found
     */
    @Override
    public Future<SubscriptionInfo> getSubscription(String topic, String groupName) {
        Objects.requireNonNull(topic, "topic cannot be null");
        Objects.requireNonNull(groupName, "groupName cannot be null");

        return connectionManager.withConnection(serviceId, connection -> {
            String sql = """
                SELECT id, topic, group_name, subscription_status, subscribed_at, last_active_at,
                       start_from_message_id, start_from_timestamp,
                       heartbeat_interval_seconds, heartbeat_timeout_seconds, last_heartbeat_at,
                       backfill_status, backfill_checkpoint_id, backfill_processed_messages,
                       backfill_total_messages, backfill_started_at, backfill_completed_at
                FROM outbox_topic_subscriptions
                WHERE topic = $1 AND group_name = $2
                """;

            return connection.preparedQuery(sql)
                .execute(Tuple.of(topic, groupName))
                .map(rows -> {
                    if (rows.size() == 0) {
                        return null;
                    }
                    return toSubscriptionInfo(mapRowToSubscription(rows.iterator().next()));
                });
        });
    }

    /**
     * Lists all subscriptions for a topic.
     *
     * @param topic The topic name
     * @return Future containing list of subscription info
     */
    @Override
    public Future<List<SubscriptionInfo>> listSubscriptions(String topic) {
        Objects.requireNonNull(topic, "topic cannot be null");

        return connectionManager.withConnection(serviceId, connection -> {
            String sql = """
                SELECT id, topic, group_name, subscription_status, subscribed_at, last_active_at,
                       start_from_message_id, start_from_timestamp,
                       heartbeat_interval_seconds, heartbeat_timeout_seconds, last_heartbeat_at,
                       backfill_status, backfill_checkpoint_id, backfill_processed_messages,
                       backfill_total_messages, backfill_started_at, backfill_completed_at
                FROM outbox_topic_subscriptions
                WHERE topic = $1
                ORDER BY subscribed_at
                """;

            return connection.preparedQuery(sql)
                .execute(Tuple.of(topic))
                .map(rows -> {
                    List<SubscriptionInfo> subscriptions = new ArrayList<>();
                    for (Row row : rows) {
                        subscriptions.add(toSubscriptionInfo(mapRowToSubscription(row)));
                    }
                    logger.debug("Found {} subscriptions for topic '{}'", subscriptions.size(), topic);
                    return subscriptions;
                });
        });
    }

    /**
     * Gets a subscription by topic and group name (internal method returning internal type).
     *
     * @param topic The topic name
     * @param groupName The consumer group name
     * @return Future containing the subscription, or null if not found
     */
    public Future<Subscription> getSubscriptionInternal(String topic, String groupName) {
        Objects.requireNonNull(topic, "topic cannot be null");
        Objects.requireNonNull(groupName, "groupName cannot be null");

        return connectionManager.withConnection(serviceId, connection -> {
            String sql = """
                SELECT id, topic, group_name, subscription_status, subscribed_at, last_active_at,
                       start_from_message_id, start_from_timestamp,
                       heartbeat_interval_seconds, heartbeat_timeout_seconds, last_heartbeat_at,
                       backfill_status, backfill_checkpoint_id, backfill_processed_messages,
                       backfill_total_messages, backfill_started_at, backfill_completed_at
                FROM outbox_topic_subscriptions
                WHERE topic = $1 AND group_name = $2
                """;

            return connection.preparedQuery(sql)
                .execute(Tuple.of(topic, groupName))
                .map(rows -> {
                    if (rows.size() == 0) {
                        return null;
                    }
                    return mapRowToSubscription(rows.iterator().next());
                });
        });
    }

    /**
     * Lists all subscriptions for a topic (internal method returning internal types).
     *
     * @param topic The topic name
     * @return Future containing list of subscriptions
     */
    public Future<List<Subscription>> listSubscriptionsInternal(String topic) {
        Objects.requireNonNull(topic, "topic cannot be null");

        return connectionManager.withConnection(serviceId, connection -> {
            String sql = """
                SELECT id, topic, group_name, subscription_status, subscribed_at, last_active_at,
                       start_from_message_id, start_from_timestamp,
                       heartbeat_interval_seconds, heartbeat_timeout_seconds, last_heartbeat_at,
                       backfill_status, backfill_checkpoint_id, backfill_processed_messages,
                       backfill_total_messages, backfill_started_at, backfill_completed_at
                FROM outbox_topic_subscriptions
                WHERE topic = $1
                ORDER BY subscribed_at
                """;

            return connection.preparedQuery(sql)
                .execute(Tuple.of(topic))
                .map(rows -> {
                    List<Subscription> subscriptions = new ArrayList<>();
                    for (Row row : rows) {
                        subscriptions.add(mapRowToSubscription(row));
                    }
                    logger.debug("Found {} subscriptions for topic '{}'", subscriptions.size(), topic);
                    return subscriptions;
                });
        });
    }

                                    

    // Helper methods

    /**
     * Sets the status when the current status is one of {@code allowedFrom}. From any other
     * status the row is unchanged and the returned Future fails with
     * {@link SubscriptionStateException}.
     */
    private Future<Void> updateStatus(TraceCtx trace, String topic, String groupName, SubscriptionStatus newStatus,
                                      Set<SubscriptionStatus> allowedFrom, String callName) {
        return connectionManager.withTransaction(serviceId, connection -> {
            String sql = """
                WITH old AS (
                    SELECT subscription_status
                    FROM outbox_topic_subscriptions
                    WHERE topic = $3 AND group_name = $4
                ),
                updated AS (
                UPDATE outbox_topic_subscriptions
                SET subscription_status = $1,
                    last_active_at = $2
                WHERE topic = $3 AND group_name = $4
                  AND subscription_status = ANY($5::text[])
                RETURNING subscription_status AS new_status
                )
                SELECT old.subscription_status AS old_status, updated.new_status
                FROM old LEFT JOIN updated ON TRUE
                """;

            String[] allowedNames = allowedFrom.stream().map(SubscriptionStatus::name).toArray(String[]::new);
            Tuple params = Tuple.of(newStatus.name(), OffsetDateTime.now(ZoneOffset.UTC), topic, groupName,
                                    allowedNames);

            return connection.preparedQuery(sql)
                .execute(params)
                .compose(result -> settleTransition(trace, result, topic, groupName, callName))
                .map(row -> {
                    try (var scope = TraceContextUtil.mdcScope(trace)) {
                        logger.info("Updated subscription status to {} for group '{}' on topic '{}' (oldStatus={}, newStatus={})",
                                   newStatus, groupName, topic, row.getString("old_status"), row.getString("new_status"));
                    }
                    return (Void) null;
                });
        });
    }

    /**
     * Reads the result of a guarded status statement.
     *
     * <p>No row: the group has no subscription on the topic. A row without a new status: the
     * current status refused the call and the subscription is unchanged.</p>
     *
     * @return Future containing the row with {@code old_status} and {@code new_status}
     */
    private Future<Row> settleTransition(TraceCtx trace, RowSet<Row> result, String topic, String groupName,
                                         String callName) {
        if (result.size() == 0) {
            String msg = String.format("Subscription not found: topic='%s', group='%s'",
                                      topic, groupName);
            try (var scope = TraceContextUtil.mdcScope(trace)) {
                logger.debug(msg);
            }
            return Future.failedFuture(new IllegalStateException(msg));
        }

        Row row = result.iterator().next();
        String oldStatus = row.getString("old_status");
        if (row.getString("new_status") == null) {
            String msg = String.format("Cannot %s a %s subscription: topic='%s', group='%s'",
                                      callName, oldStatus, topic, groupName);
            try (var scope = TraceContextUtil.mdcScope(trace)) {
                logger.warn("{}", msg);
            }
            return Future.failedFuture(new SubscriptionStateException(
                msg, toSubscriptionState(SubscriptionStatus.valueOf(oldStatus))));
        }

        return Future.succeededFuture(row);
    }

    private Subscription mapRowToSubscription(Row row) {
        // Convert OffsetDateTime from database to Instant
        OffsetDateTime subscribedAt = row.get(OffsetDateTime.class, row.getColumnIndex("subscribed_at"));
        OffsetDateTime lastActiveAt = row.get(OffsetDateTime.class, row.getColumnIndex("last_active_at"));
        OffsetDateTime startFromTimestamp = row.get(OffsetDateTime.class, row.getColumnIndex("start_from_timestamp"));
        OffsetDateTime lastHeartbeatAt = row.get(OffsetDateTime.class, row.getColumnIndex("last_heartbeat_at"));
        OffsetDateTime backfillStartedAt = row.get(OffsetDateTime.class, row.getColumnIndex("backfill_started_at"));
        OffsetDateTime backfillCompletedAt = row.get(OffsetDateTime.class, row.getColumnIndex("backfill_completed_at"));

        return Subscription.builder()
            .id(row.getLong("id"))
            .topic(row.getString("topic"))
            .groupName(row.getString("group_name"))
            .status(SubscriptionStatus.valueOf(row.getString("subscription_status")))
            .subscribedAt(subscribedAt != null ? subscribedAt.toInstant() : null)
            .lastActiveAt(lastActiveAt != null ? lastActiveAt.toInstant() : null)
            .startFromMessageId(row.getLong("start_from_message_id"))
            .startFromTimestamp(startFromTimestamp != null ? startFromTimestamp.toInstant() : null)
            .heartbeatIntervalSeconds(row.getInteger("heartbeat_interval_seconds"))
            .heartbeatTimeoutSeconds(row.getInteger("heartbeat_timeout_seconds"))
            .lastHeartbeatAt(lastHeartbeatAt != null ? lastHeartbeatAt.toInstant() : null)
            .backfillStatus(row.getString("backfill_status"))
            .backfillCheckpointId(row.getLong("backfill_checkpoint_id"))
            .backfillProcessedMessages(row.getLong("backfill_processed_messages"))
            .backfillTotalMessages(row.getLong("backfill_total_messages"))
            .backfillStartedAt(backfillStartedAt != null ? backfillStartedAt.toInstant() : null)
            .backfillCompletedAt(backfillCompletedAt != null ? backfillCompletedAt.toInstant() : null)
            .build();
    }

    /**
     * Converts internal Subscription to API SubscriptionInfo.
     */
    private SubscriptionInfo toSubscriptionInfo(Subscription subscription) {
        if (subscription == null) {
            return null;
        }
        return SubscriptionInfo.builder()
            .id(subscription.getId())
            .topic(subscription.getTopic())
            .groupName(subscription.getGroupName())
            .state(toSubscriptionState(subscription.getStatus()))
            .subscribedAt(subscription.getSubscribedAt())
            .lastActiveAt(subscription.getLastActiveAt())
            .startFromMessageId(subscription.getStartFromMessageId())
            .startFromTimestamp(subscription.getStartFromTimestamp())
            .heartbeatIntervalSeconds(subscription.getHeartbeatIntervalSeconds())
            .heartbeatTimeoutSeconds(subscription.getHeartbeatTimeoutSeconds())
            .lastHeartbeatAt(subscription.getLastHeartbeatAt())
            .backfillStatus(subscription.getBackfillStatus())
            .backfillCheckpointId(subscription.getBackfillCheckpointId())
            .backfillProcessedMessages(subscription.getBackfillProcessedMessages())
            .backfillTotalMessages(subscription.getBackfillTotalMessages())
            .backfillStartedAt(subscription.getBackfillStartedAt())
            .backfillCompletedAt(subscription.getBackfillCompletedAt())
            .build();
    }

    /**
     * Converts internal SubscriptionStatus to API SubscriptionState.
     */
    private SubscriptionState toSubscriptionState(SubscriptionStatus status) {
        return switch (status) {
            case ACTIVE -> SubscriptionState.ACTIVE;
            case PAUSED -> SubscriptionState.PAUSED;
            case CANCELLED -> SubscriptionState.CANCELLED;
            case DEAD -> SubscriptionState.DEAD;
            case STOPPED -> SubscriptionState.STOPPED;
        };
    }

    @Override
    public Future<List<SubscriptionInfo>> listDeadSubscriptions() {
        return connectionManager.withConnection(serviceId, connection -> {
            String sql = """
                SELECT *
                FROM outbox_topic_subscriptions
                WHERE subscription_status = 'DEAD'
                ORDER BY last_active_at DESC
                """;

            return connection.preparedQuery(sql)
                    .execute()
                    .map(rows -> {
                        List<SubscriptionInfo> result = new ArrayList<>();
                        for (Row row : rows) {
                            result.add(toSubscriptionInfo(mapRowToSubscription(row)));
                        }
                        return result;
                    });
        });
    }

    @Override
    public Future<JsonObject> getSubscriptionHealthSummary() {
        DeadConsumerDetector detector = new DeadConsumerDetector(connectionManager, serviceId);
        return detector.getSubscriptionSummary()
                .map(summary -> new JsonObject()
                        .put("activeCount", summary.activeCount())
                        .put("pausedCount", summary.pausedCount())
                        .put("deadCount", summary.deadCount())
                        .put("cancelledCount", summary.cancelledCount())
                        .put("topicCount", summary.topicCount())
                        .put("totalCount", summary.totalCount())
                        .put("hasDeadSubscriptions", summary.hasDeadSubscriptions()));
    }

    @Override
    public Future<JsonObject> getBlockedMessageStats() {
        DeadConsumerDetector detector = new DeadConsumerDetector(connectionManager, serviceId);
        return detector.getBlockedMessageStats()
                .map(statsList -> {
                    JsonArray statsArray = new JsonArray();
                    for (DeadConsumerDetector.BlockedMessageStats stats : statsList) {
                        JsonObject stat = new JsonObject()
                                .put("topic", stats.topic())
                                .put("groupName", stats.groupName())
                                .put("blockedPending", stats.blockedPending())
                                .put("blockedProcessing", stats.blockedProcessing())
                                .put("totalBlocked", stats.totalBlocked());
                        if (stats.oldestBlockedAge() != null) {
                            stat.put("oldestBlockedAgeSeconds", stats.oldestBlockedAge().getSeconds());
                        }
                        statsArray.add(stat);
                    }
                    return new JsonObject()
                            .put("blockedStats", statsArray)
                            .put("totalBlockedGroups", statsArray.size());
                });
    }

    // ========================================================================
    // Partitioned Consumption (OFFSET_WATERMARK mode)
    // ========================================================================

    @Override
    public Future<List<PartitionAssignmentInfo>> joinPartitionedGroup(String topic, String groupName, String instanceId) {
        return requireOffsetWatermarkMode(topic)
                .compose(v -> {
                    requirePartitionedServices();
                    return partitionAssignmentService.joinGroup(topic, groupName, instanceId);
                })
                .compose(assignments -> {
                    // Initialize offset rows for all assigned partitions (idempotent)
                    Future<Void> init = Future.succeededFuture();
                    for (PartitionAssignment a : assignments) {
                        init = init.compose(v ->
                                partitionedOffsetManager.initializeOffset(
                                        a.topic(), a.groupName(), a.partitionKey(), a.generation())
                                        .map(offset -> (Void) null));
                    }
                    return init.map(v -> toAssignmentInfoList(assignments));
                });
    }

    @Override
    public Future<Void> leavePartitionedGroup(String topic, String groupName, String instanceId) {
        return requireOffsetWatermarkMode(topic)
                .compose(v -> {
                    requirePartitionedServices();
                    return partitionAssignmentService.leaveGroup(topic, groupName, instanceId);
                });
    }

    @Override
    public Future<List<JsonObject>> fetchPartitioned(String topic, String groupName,
                                                      String partitionKey, int batchSize, int generation) {
        return requireOffsetWatermarkMode(topic)
                .compose(v -> {
                    requirePartitionedServices();
                    return verifyPartitionAssignment(topic, groupName, partitionKey, generation);
                })
                .compose(v -> partitionedFetcher.fetch(topic, groupName, partitionKey, batchSize, generation))
                .map(messages -> messages.stream()
                        .map(this::toMessageJson)
                        .collect(Collectors.toList()));
    }

    @Override
    public Future<Boolean> commitOffset(String topic, String groupName,
                                         String partitionKey, long offset, int generation) {
        return requireOffsetWatermarkMode(topic)
                .compose(v -> {
                    requirePartitionedServices();
                    return partitionedOffsetManager.commitOffset(topic, groupName, partitionKey, offset, generation);
                });
    }

    @Override
    public Future<List<PartitionAssignmentInfo>> getPartitionAssignments(String topic, String groupName, String instanceId) {
        return requireOffsetWatermarkMode(topic)
                .compose(v -> {
                    requirePartitionedServices();
                    return partitionAssignmentService.getAssignments(topic, groupName, instanceId);
                })
                .map(this::toAssignmentInfoList);
    }

    private Future<Void> requireOffsetWatermarkMode(String topic) {
        return connectionManager.withConnection(serviceId, connection ->
                connection.preparedQuery(
                        "SELECT completion_tracking_mode FROM outbox_topics WHERE topic = $1"
                ).execute(Tuple.of(topic))
                .compose(rows -> {
                    if (rows.size() == 0) {
                        return Future.failedFuture(new IllegalArgumentException(
                                "Topic not found: " + topic));
                    }
                    String mode = rows.iterator().next().getString("completion_tracking_mode");
                    if (!"OFFSET_WATERMARK".equals(mode)) {
                        return Future.failedFuture(new IllegalArgumentException(
                                "Topic '" + topic + "' requires OFFSET_WATERMARK mode but has " + mode));
                    }
                    return Future.succeededFuture();
                })
        );
    }

    private Future<Void> verifyPartitionAssignment(String topic, String groupName,
                                                    String partitionKey, int generation) {
        return connectionManager.withConnection(serviceId, connection ->
                connection.preparedQuery("""
                    SELECT 1 FROM outbox_partition_assignments
                    WHERE topic = $1 AND group_name = $2 AND partition_key = $3 AND generation = $4
                    """)
                .execute(Tuple.of(topic, groupName, partitionKey, generation))
                .compose(rows -> {
                    if (rows.size() == 0) {
                        return Future.failedFuture(new IllegalArgumentException(
                                "Partition '" + partitionKey + "' is not assigned for group '"
                                + groupName + "' at generation " + generation));
                    }
                    return Future.succeededFuture();
                })
        );
    }

    private void requirePartitionedServices() {
        if (partitionAssignmentService == null || partitionedFetcher == null || partitionedOffsetManager == null) {
            throw new IllegalStateException("Partitioned consumption services not configured");
        }
    }

    private List<PartitionAssignmentInfo> toAssignmentInfoList(List<PartitionAssignment> assignments) {
        return assignments.stream()
                .map(a -> new PartitionAssignmentInfo(
                        a.topic(), a.groupName(), a.partitionKey(),
                        a.assignedInstanceId(), a.generation()))
                .collect(Collectors.toList());
    }

    private JsonObject toMessageJson(OutboxMessage msg) {
        JsonObject json = new JsonObject()
                .put("id", msg.getId())
                .put("topic", msg.getTopic())
                .put("messageGroup", msg.getMessageGroup());
        if (msg.getPayload() != null) {
            json.put("payload", msg.getPayload());
        }
        if (msg.getHeaders() != null) {
            json.put("headers", msg.getHeaders());
        }
        if (msg.getCorrelationId() != null) {
            json.put("correlationId", msg.getCorrelationId());
        }
        if (msg.getCreatedAt() != null) {
            json.put("createdAt", msg.getCreatedAt().toString());
        }
        return json;
    }
}
