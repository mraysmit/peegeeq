package dev.mars.peegeeq.db.subscription;

/**
 * Subscription status enumeration for consumer group subscriptions.
 * 
 * <p>This enum represents the lifecycle states of a consumer group subscription
 * to a topic in the PeeGeeQ fan-out system.</p>
 * 
 * <p><b>State Transitions:</b></p>
 * <ul>
 *   <li>ACTIVE → PAUSED (manual pause)</li>
 *   <li>ACTIVE → DEAD (heartbeat timeout)</li>
 *   <li>ACTIVE → CANCELLED (manual cancellation)</li>
 *   <li>PAUSED → ACTIVE (manual resume)</li>
 *   <li>PAUSED → CANCELLED (manual cancellation)</li>
 *   <li>DEAD → ACTIVE (manual reactivation)</li>
 *   <li>CANCELLED → (terminal state, no transitions)</li>
 * </ul>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-12
 * @version 1.0
 */
public enum SubscriptionStatus {
    /**
     * Subscription is active and receiving messages.
     * Consumer group will receive new messages published to the topic.
     */
    ACTIVE,
    
    /**
     * Subscription is temporarily paused.
     * Consumer group will not receive new messages until resumed.
     * Existing messages remain in the queue.
     */
    PAUSED,
    
    /**
     * Subscription has been cancelled.
     * This is a terminal state - subscription cannot be reactivated.
     * Consumer group will not receive any messages.
     */
    CANCELLED,
    
    /**
     * Subscription is marked as dead due to heartbeat timeout.
     * Consumer group failed to send heartbeats within the configured timeout.
     * Can be manually reactivated if the consumer recovers.
     */
    DEAD
}

