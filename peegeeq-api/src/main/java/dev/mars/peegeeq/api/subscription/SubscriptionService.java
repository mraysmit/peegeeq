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
     * @return Future that completes when subscription is created
     */
    Future<Void> subscribe(String topic, String groupName);
    
    /**
     * Subscribes a consumer group to a topic with custom options.
     * 
     * <p>This method creates a new subscription record. If a subscription already exists,
     * it will be reactivated if it was PAUSED or DEAD.</p>
     * 
     * @param topic The topic name
     * @param groupName The consumer group name
     * @param options Subscription configuration options
     * @return Future that completes when subscription is created
     */
    Future<Void> subscribe(String topic, String groupName, SubscriptionOptions options);
    
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
    Future<Void> pause(String topic, String groupName);
    
    /**
     * Resumes a paused subscription.
     * 
     * <p>Resumed subscriptions will start receiving new messages again.</p>
     * 
     * @param topic The topic name
     * @param groupName The consumer group name
     * @return Future that completes when subscription is resumed
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
     * @param topic The topic name
     * @param groupName The consumer group name
     * @return Future that completes when heartbeat is updated
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
}

