package dev.mars.peegeeq.db.subscription;

import dev.mars.peegeeq.db.connection.PgConnectionManager;
import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
public class SubscriptionManager {
    
    private static final Logger logger = LoggerFactory.getLogger(SubscriptionManager.class);
    
    private final PgConnectionManager connectionManager;
    private final String serviceId;
    
    /**
     * Creates a new SubscriptionManager.
     * 
     * @param connectionManager The connection manager for database access
     * @param serviceId The service ID for connection pool selection
     */
    public SubscriptionManager(PgConnectionManager connectionManager, String serviceId) {
        this.connectionManager = Objects.requireNonNull(connectionManager, "connectionManager cannot be null");
        this.serviceId = Objects.requireNonNull(serviceId, "serviceId cannot be null");
        logger.info("SubscriptionManager initialized for service: {}", serviceId);
    }
    
    /**
     * Subscribes a consumer group to a topic with default options.
     * 
     * @param topic The topic name
     * @param groupName The consumer group name
     * @return Future that completes when subscription is created
     */
    public Future<Void> subscribe(String topic, String groupName) {
        return subscribe(topic, groupName, SubscriptionOptions.defaults());
    }
    
    /**
     * Subscribes a consumer group to a topic with custom options.
     * 
     * <p>This method creates a new subscription record in the outbox_topic_subscriptions table.
     * If a subscription already exists, it will be reactivated if it was PAUSED or DEAD.</p>
     * 
     * @param topic The topic name
     * @param groupName The consumer group name
     * @param options Subscription configuration options
     * @return Future that completes when subscription is created
     */
    public Future<Void> subscribe(String topic, String groupName, SubscriptionOptions options) {
        Objects.requireNonNull(topic, "topic cannot be null");
        Objects.requireNonNull(groupName, "groupName cannot be null");
        Objects.requireNonNull(options, "options cannot be null");
        
        logger.info("Subscribing consumer group '{}' to topic '{}' with options: {}", 
                   groupName, topic, options);
        
        return connectionManager.withConnection(serviceId, connection -> 
            subscribeInternal(topic, groupName, options, connection)
        );
    }
    
    /**
     * Internal implementation of subscribe using provided connection.
     */
    private Future<Void> subscribeInternal(String topic, String groupName, 
                                           SubscriptionOptions options, SqlConnection connection) {
        
        String sql = """
            INSERT INTO outbox_topic_subscriptions (
                topic, group_name, subscription_status,
                start_from_message_id, start_from_timestamp,
                heartbeat_interval_seconds, heartbeat_timeout_seconds,
                subscribed_at, last_active_at, last_heartbeat_at
            ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
            ON CONFLICT (topic, group_name) DO UPDATE SET
                subscription_status = CASE
                    WHEN outbox_topic_subscriptions.subscription_status = 'CANCELLED' 
                    THEN outbox_topic_subscriptions.subscription_status
                    ELSE 'ACTIVE'
                END,
                last_active_at = EXCLUDED.last_active_at,
                last_heartbeat_at = EXCLUDED.last_heartbeat_at,
                heartbeat_interval_seconds = EXCLUDED.heartbeat_interval_seconds,
                heartbeat_timeout_seconds = EXCLUDED.heartbeat_timeout_seconds
            """;
        
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Tuple params = Tuple.of(
            topic,
            groupName,
            SubscriptionStatus.ACTIVE.name(),
            options.getStartFromMessageId(),
            options.getStartFromTimestamp(),
            options.getHeartbeatIntervalSeconds(),
            options.getHeartbeatTimeoutSeconds(),
            now,
            now,
            now
        );
        
        return connection.preparedQuery(sql)
            .execute(params)
            .onSuccess(result -> {
                logger.info("Successfully subscribed consumer group '{}' to topic '{}'",
                           groupName, topic);
            })
            .onFailure(error -> {
                logger.error("Failed to subscribe consumer group '{}' to topic '{}': {}",
                            groupName, topic, error.getMessage(), error);
            })
            .mapEmpty();
    }
    
    /**
     * Pauses an active subscription.
     * 
     * <p>Paused subscriptions will not receive new messages until resumed.
     * Existing messages remain in the queue.</p>
     * 
     * @param topic The topic name
     * @param groupName The consumer group name
     * @return Future that completes when subscription is paused
     */
    public Future<Void> pause(String topic, String groupName) {
        Objects.requireNonNull(topic, "topic cannot be null");
        Objects.requireNonNull(groupName, "groupName cannot be null");
        
        logger.info("Pausing subscription for consumer group '{}' on topic '{}'", groupName, topic);
        
        return updateStatus(topic, groupName, SubscriptionStatus.PAUSED);
    }
    
    /**
     * Resumes a paused subscription.
     * 
     * <p>Resumed subscriptions will start receiving new messages again.</p>
     * 
     * @param topic The topic name
     * @param groupName The consumer group name
     * @return Future that completes when subscription is resumed
     */
    public Future<Void> resume(String topic, String groupName) {
        Objects.requireNonNull(topic, "topic cannot be null");
        Objects.requireNonNull(groupName, "groupName cannot be null");
        
        logger.info("Resuming subscription for consumer group '{}' on topic '{}'", groupName, topic);
        
        return updateStatus(topic, groupName, SubscriptionStatus.ACTIVE);
    }
    
    /**
     * Cancels a subscription.
     * 
     * <p>Cancelled subscriptions cannot be reactivated. This is a terminal state.
     * The consumer group will not receive any messages from this topic.</p>
     * 
     * @param topic The topic name
     * @param groupName The consumer group name
     * @return Future that completes when subscription is cancelled
     */
    public Future<Void> cancel(String topic, String groupName) {
        Objects.requireNonNull(topic, "topic cannot be null");
        Objects.requireNonNull(groupName, "groupName cannot be null");
        
        logger.info("Cancelling subscription for consumer group '{}' on topic '{}'", groupName, topic);
        
        return updateStatus(topic, groupName, SubscriptionStatus.CANCELLED);
    }
    
    /**
     * Updates the heartbeat timestamp for a subscription.
     * 
     * <p>Consumer groups must call this method periodically to prevent being marked as DEAD.
     * The heartbeat interval and timeout are configured in SubscriptionOptions.</p>
     * 
     * @param topic The topic name
     * @param groupName The consumer group name
     * @return Future that completes when heartbeat is updated
     */
    public Future<Void> updateHeartbeat(String topic, String groupName) {
        Objects.requireNonNull(topic, "topic cannot be null");
        Objects.requireNonNull(groupName, "groupName cannot be null");
        
        logger.debug("Updating heartbeat for consumer group '{}' on topic '{}'", groupName, topic);
        
        return connectionManager.withConnection(serviceId, connection -> {
            String sql = """
                UPDATE outbox_topic_subscriptions
                SET last_heartbeat_at = $1, last_active_at = $1
                WHERE topic = $2 AND group_name = $3
                """;
            
            Tuple params = Tuple.of(OffsetDateTime.now(ZoneOffset.UTC), topic, groupName);
            
            return connection.preparedQuery(sql)
                .execute(params)
                .onSuccess(result -> {
                    if (result.rowCount() == 0) {
                        logger.warn("No subscription found for heartbeat update: topic='{}', group='{}'",
                                   topic, groupName);
                    }
                })
                .mapEmpty();
        });
    }
    
    /**
     * Gets a subscription by topic and group name.
     * 
     * @param topic The topic name
     * @param groupName The consumer group name
     * @return Future containing the subscription, or null if not found
     */
    public Future<Subscription> getSubscription(String topic, String groupName) {
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
     * Lists all subscriptions for a topic.
     * 
     * @param topic The topic name
     * @return Future containing list of subscriptions
     */
    public Future<List<Subscription>> listSubscriptions(String topic) {
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
    
    private Future<Void> updateStatus(String topic, String groupName, SubscriptionStatus newStatus) {
        return connectionManager.withConnection(serviceId, connection -> {
            String sql = """
                UPDATE outbox_topic_subscriptions
                SET subscription_status = $1, last_active_at = $2
                WHERE topic = $3 AND group_name = $4
                """;
            
            Tuple params = Tuple.of(newStatus.name(), OffsetDateTime.now(ZoneOffset.UTC), topic, groupName);
            
            return connection.preparedQuery(sql)
                .execute(params)
                .compose(result -> {
                    if (result.rowCount() == 0) {
                        String msg = String.format("Subscription not found: topic='%s', group='%s'",
                                                  topic, groupName);
                        logger.error(msg);
                        return Future.failedFuture(new IllegalStateException(msg));
                    }
                    logger.info("Updated subscription status to {} for group '{}' on topic '{}'",
                               newStatus, groupName, topic);
                    return Future.succeededFuture();
                });
        });
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
}

