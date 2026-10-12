package dev.mars.peegeeq.api.subscription;

import dev.mars.peegeeq.api.messaging.BackfillScope;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.util.List;

/**
 * Service interface for managing consumer group subscriptions to topics.
 * 
 * <p>This interface provides the core API for subscription lifecycle management including:
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
 * @since 2025-12-05
 * @version 1.0
 */
public interface SubscriptionService {
    
    /**
     * Subscribes a consumer group to a topic with default options.
     *
     * @param topic The topic name
     * @param groupName The consumer group name
     * @param store The message table that the message ids of the subscription belong to
     * @return Future that completes when subscription is created
     */
    Future<Void> subscribe(String topic, String groupName, SubscriptionStore store);

    /**
     * Subscribes a consumer group to a topic with custom options.
     *
     * <p>When the group has no subscription on the topic, this method creates one. The new
     * subscription is ACTIVE and its start bound comes from the options. {@code FROM_NOW} is
     * resolved against the message table of the store, in the transaction that creates the
     * subscription.</p>
     *
     * <p>When a subscription exists, its stored start bound is kept. The start position of
     * the options is not applied and no message table is read. An ACTIVE, STOPPED, or DEAD
     * subscription becomes ACTIVE. A PAUSED subscription stays PAUSED. A CANCELLED
     * subscription is unchanged and the returned Future fails with
     * {@link SubscriptionStateException}. The heartbeat settings of the options are stored.</p>
     *
     * <p>The store has no default. The caller names it: a queue factory or a consumer group
     * names its own store, and a caller that has a queue takes the store from
     * {@link dev.mars.peegeeq.api.messaging.QueueFactory#getSubscriptionStore()}.</p>
     *
     * @param topic The topic name
     * @param groupName The consumer group name
     * @param store The message table that the message ids of the subscription belong to
     * @param options Subscription configuration options
     * @return Future that completes when subscription is created or made active
     */
    Future<Void> subscribe(String topic, String groupName, SubscriptionStore store, SubscriptionOptions options);

    /**
     * Pauses a subscription.
     *
     * <p>An ACTIVE, STOPPED, or PAUSED subscription becomes PAUSED. Only
     * {@link #resume(String, String)} ends a pause. A DEAD or CANCELLED subscription is
     * unchanged and the returned Future fails with {@link SubscriptionStateException}.</p>
     *
     * @param topic The topic name
     * @param groupName The consumer group name
     * @return Future that completes when subscription is paused; it fails when the
     *         group has no subscription on the topic
     */
    Future<Void> pause(String topic, String groupName);

    /**
     * Resumes a subscription.
     *
     * <p>An ACTIVE, PAUSED, or DEAD subscription becomes ACTIVE. A STOPPED or CANCELLED
     * subscription is unchanged and the returned Future fails with
     * {@link SubscriptionStateException}. A STOPPED subscription becomes ACTIVE through
     * {@link #subscribe(String, String, SubscriptionStore, SubscriptionOptions)}.</p>
     *
     * @param topic The topic name
     * @param groupName The consumer group name
     * @return Future that completes when subscription is resumed; it fails when the
     *         group has no subscription on the topic
     */
    Future<Void> resume(String topic, String groupName);
    
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
    Future<Void> cancel(String topic, String groupName);
    
    /**
     * Updates the heartbeat timestamp for a subscription.
     * 
     * <p>Consumer groups must call this method periodically to prevent being marked as DEAD.
     * The heartbeat interval and timeout are configured in SubscriptionOptions.</p>
     *
     * <p>A heartbeat changes no status. On an ACTIVE subscription it sets the heartbeat
     * time and clears the count of missed heartbeats. On a PAUSED subscription it sets the
     * heartbeat time. On a STOPPED, DEAD, or CANCELLED subscription the row is unchanged
     * and the returned Future fails with {@link SubscriptionStateException}, which names
     * the status. A heartbeat does not make a DEAD subscription ACTIVE.</p>
     *
     * @param topic The topic name
     * @param groupName The consumer group name
     * @return Future that completes when heartbeat is updated; it fails when the
     *         group has no subscription on the topic
     */
    Future<Void> updateHeartbeat(String topic, String groupName);
    
    /**
     * Gets a subscription by topic and group name.
     * 
     * @param topic The topic name
     * @param groupName The consumer group name
     * @return Future containing the subscription info, or null if not found
     */
    Future<SubscriptionInfo> getSubscription(String topic, String groupName);
    
    /**
     * Lists all subscriptions for a topic.
     * 
     * @param topic The topic name
     * @return Future containing list of subscription info
     */
    Future<List<SubscriptionInfo>> listSubscriptions(String topic);

    /**
     * Starts or resumes a backfill operation for a consumer group subscription.
     *
     * <p>If a backfill is already IN_PROGRESS, it will resume from the last checkpoint.
     * Returns a JSON-encodable result with status and processed message count.</p>
     *
     * @param topic The topic name
     * @param groupName The consumer group name
     * @return Future containing a JSON object with backfill result
     * @throws UnsupportedOperationException if backfill is not supported by this implementation
     */
    default Future<JsonObject> startBackfill(String topic, String groupName) {
        return Future.failedFuture(new UnsupportedOperationException("Backfill not supported"));
    }

    /**
     * Starts or resumes a backfill operation for a consumer group subscription
     * using an explicit scope.
     *
     * @param topic The topic name
     * @param groupName The consumer group name
     * @param messageScope Message scope to include in backfill
     * @return Future containing a JSON object with backfill result
     * @throws UnsupportedOperationException if backfill is not supported by this implementation
     */
    default Future<JsonObject> startBackfill(String topic, String groupName, BackfillScope messageScope) {
        return startBackfill(topic, groupName);
    }

    /**
     * Cancels an in-progress backfill operation.
     *
     * <p>The cancellation is cooperative: the current batch will complete but
     * no further batches will be processed.</p>
     *
     * @param topic The topic name
     * @param groupName The consumer group name
     * @return Future that completes when cancellation is recorded
     * @throws UnsupportedOperationException if backfill is not supported by this implementation
     */
    default Future<Void> cancelBackfill(String topic, String groupName) {
        return Future.failedFuture(new UnsupportedOperationException("Backfill not supported"));
    }

    /**
     * Stops a consumer group's subscription for a planned outage of the whole group.
     *
     * <p>An ACTIVE subscription becomes STOPPED. A subscription in any other state is
     * left unchanged. The start position and the committed offsets are kept. A later
     * {@link #subscribe(String, String, SubscriptionStore, SubscriptionOptions)} makes a STOPPED
     * subscription ACTIVE again.</p>
     *
     * @param topic The topic name
     * @param groupName The consumer group name
     * @return Future that completes when the status is stored; it fails when the
     *         group has no subscription on the topic
     * @throws UnsupportedOperationException if stopping a group is not supported by this implementation
     */
    default Future<Void> stopGroup(String topic, String groupName) {
        return Future.failedFuture(new UnsupportedOperationException("Stop group not supported"));
    }

    /**
     * Updates the settings of an existing subscription.
     *
     * <p>Only the heartbeat settings that the update supplies are changed. The lifecycle
     * status is never changed. The stored start bound is never changed.</p>
     *
     * <p>When the update names a start position, the call succeeds only if that position
     * is the stored start bound: FROM_BEGINNING for a stored message id of 1,
     * FROM_MESSAGE_ID for an equal stored message id, FROM_TIMESTAMP for an equal stored
     * timestamp. A different bound, and FROM_NOW, fail with
     * {@link SubscriptionBoundConflictException} and change nothing.</p>
     *
     * @param topic The topic name
     * @param groupName The consumer group name
     * @param update The settings to change and the start bound the caller names, if any
     * @return Future containing the subscription as stored after the update; it fails when
     *         the group has no subscription on the topic
     * @throws UnsupportedOperationException if a settings update is not supported by this implementation
     */
    default Future<SubscriptionInfo> updateSubscriptionSettings(String topic, String groupName,
                                                                SubscriptionSettingsUpdate update) {
        return Future.failedFuture(new UnsupportedOperationException("Subscription settings update not supported"));
    }

    /**
     * Force-removes a consumer group from a topic.
     *
     * <p>This is an administrative operation that:</p>
     * <ol>
     *   <li>Marks the subscription as DEAD</li>
     *   <li>Runs dead-group cleanup (decrement required_consumer_groups,
     *       remove orphaned tracking rows, auto-complete unblocked messages)</li>
     *   <li>Marks the subscription as CANCELLED (terminal state)</li>
     * </ol>
     *
     * <p>Use this when a consumer group is permanently gone and its presence is
     * blocking message delivery to other groups.</p>
     *
     * @param topic The topic name
     * @param groupName The consumer group name to force-remove
     * @return Future containing the cleanup result details
     * @throws UnsupportedOperationException if force-remove is not supported by this implementation
     * @since 1.2.0
     */
    default Future<ForceRemoveResult> forceRemoveConsumerGroup(String topic, String groupName) {
        return Future.failedFuture(new UnsupportedOperationException("Force-remove not supported"));
    }

    /**
     * Lists all subscriptions currently in DEAD state.
     *
     * @return Future containing the list of dead subscriptions
     * @throws UnsupportedOperationException if alerting is not supported by this implementation
     */
    default Future<List<SubscriptionInfo>> listDeadSubscriptions() {
        return Future.failedFuture(new UnsupportedOperationException("Dead subscription alerting not supported"));
    }

    /**
     * Returns a health summary of all subscriptions grouped by status.
     *
     * @return Future containing the summary as a JSON object
     * @throws UnsupportedOperationException if alerting is not supported by this implementation
     */
    default Future<JsonObject> getSubscriptionHealthSummary() {
        return Future.failedFuture(new UnsupportedOperationException("Subscription health summary not supported"));
    }

    /**
     * Returns statistics about messages blocked by dead consumer groups.
     *
     * @return Future containing the blocked message stats as a JSON object
     * @throws UnsupportedOperationException if alerting is not supported by this implementation
     */
    default Future<JsonObject> getBlockedMessageStats() {
        return Future.failedFuture(new UnsupportedOperationException("Blocked message stats not supported"));
    }

    // ========================================================================
    // Partitioned Consumption (OFFSET_WATERMARK mode)
    // ========================================================================

    /**
     * Joins a consumer instance to a partitioned group, triggering a rebalance
     * that assigns partitions using consistent hashing.
     *
     * <p>Only valid for topics with completion_tracking_mode = OFFSET_WATERMARK.</p>
     *
     * @param topic The topic name
     * @param groupName The consumer group name
     * @param instanceId The consumer instance identifier
     * @return Future containing the list of partitions assigned to this instance
     * @throws UnsupportedOperationException if partitioned consumption is not supported
     */
    default Future<List<PartitionAssignmentInfo>> joinPartitionedGroup(String topic, String groupName, String instanceId) {
        return Future.failedFuture(new UnsupportedOperationException("Partitioned consumption not supported"));
    }

    /**
     * Removes a consumer instance from a partitioned group, triggering a rebalance
     * that redistributes its partitions to remaining instances.
     *
     * @param topic The topic name
     * @param groupName The consumer group name
     * @param instanceId The consumer instance identifier
     * @return Future completing when rebalance is done
     * @throws UnsupportedOperationException if partitioned consumption is not supported
     */
    default Future<Void> leavePartitionedGroup(String topic, String groupName, String instanceId) {
        return Future.failedFuture(new UnsupportedOperationException("Partitioned consumption not supported"));
    }

    /**
     * Fetches a batch of messages for a specific partition, starting after the
     * committed offset. Uses FOR UPDATE SKIP LOCKED for concurrent safety.
     *
     * @param topic The topic name
     * @param groupName The consumer group name
     * @param partitionKey The partition key (message_group or '__default__')
     * @param batchSize Maximum number of messages to return
     * @param generation The current rebalance generation for fencing
     * @return Future containing the fetched messages as JSON objects in id order
     * @throws UnsupportedOperationException if partitioned consumption is not supported
     */
    default Future<List<JsonObject>> fetchPartitioned(String topic, String groupName,
                                                       String partitionKey, int batchSize, int generation) {
        return Future.failedFuture(new UnsupportedOperationException("Partitioned consumption not supported"));
    }

    /**
     * Commits an offset for a partition using compare-and-swap with generation fencing.
     *
     * @param topic The topic name
     * @param groupName The consumer group name
     * @param partitionKey The partition key
     * @param offset The offset to commit (must be greater than current committed_offset)
     * @param generation The generation for fencing
     * @return Future containing true if commit succeeded, false if rejected (stale generation)
     * @throws UnsupportedOperationException if partitioned consumption is not supported
     */
    default Future<Boolean> commitOffset(String topic, String groupName,
                                          String partitionKey, long offset, int generation) {
        return Future.failedFuture(new UnsupportedOperationException("Partitioned consumption not supported"));
    }

    /**
     * Returns the current partition assignments for a consumer instance.
     *
     * @param topic The topic name
     * @param groupName The consumer group name
     * @param instanceId The consumer instance identifier
     * @return Future containing the list of partitions assigned to this instance
     * @throws UnsupportedOperationException if partitioned consumption is not supported
     */
    default Future<List<PartitionAssignmentInfo>> getPartitionAssignments(String topic, String groupName, String instanceId) {
        return Future.failedFuture(new UnsupportedOperationException("Partitioned consumption not supported"));
    }
}

