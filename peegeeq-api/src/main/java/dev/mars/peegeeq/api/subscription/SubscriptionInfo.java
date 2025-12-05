package dev.mars.peegeeq.api.subscription;

import java.time.Instant;

/**
 * Represents subscription information for a consumer group subscription to a topic.
 * 
 * <p>This record provides an immutable view of subscription metadata including status,
 * heartbeat information, and backfill tracking.</p>
 * 
 * @param id The unique subscription ID
 * @param topic The topic name
 * @param groupName The consumer group name
 * @param state The current subscription state
 * @param subscribedAt When the subscription was created
 * @param lastActiveAt When the subscription was last active
 * @param startFromMessageId The message ID to start consuming from (may be null)
 * @param startFromTimestamp The timestamp to start consuming from (may be null)
 * @param heartbeatIntervalSeconds The expected heartbeat interval in seconds
 * @param heartbeatTimeoutSeconds The heartbeat timeout in seconds before marking as DEAD
 * @param lastHeartbeatAt When the last heartbeat was received
 * @param backfillStatus The current backfill status (NONE, IN_PROGRESS, COMPLETED, FAILED)
 * @param backfillCheckpointId The last processed message ID during backfill
 * @param backfillProcessedMessages Number of messages processed during backfill
 * @param backfillTotalMessages Total messages to process during backfill
 * @param backfillStartedAt When backfill started
 * @param backfillCompletedAt When backfill completed
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-12-05
 * @version 1.0
 */
public record SubscriptionInfo(
    Long id,
    String topic,
    String groupName,
    SubscriptionState state,
    Instant subscribedAt,
    Instant lastActiveAt,
    Long startFromMessageId,
    Instant startFromTimestamp,
    int heartbeatIntervalSeconds,
    int heartbeatTimeoutSeconds,
    Instant lastHeartbeatAt,
    String backfillStatus,
    Long backfillCheckpointId,
    Long backfillProcessedMessages,
    Long backfillTotalMessages,
    Instant backfillStartedAt,
    Instant backfillCompletedAt
) {
    
    /**
     * Checks if the subscription is active.
     * 
     * @return true if state is ACTIVE
     */
    public boolean isActive() {
        return state == SubscriptionState.ACTIVE;
    }
    
    /**
     * Checks if the subscription heartbeat has timed out.
     * 
     * @return true if heartbeat has timed out
     */
    public boolean isHeartbeatTimedOut() {
        if (lastHeartbeatAt == null) {
            return false;
        }
        Instant timeout = lastHeartbeatAt.plusSeconds(heartbeatTimeoutSeconds);
        return Instant.now().isAfter(timeout);
    }
    
    /**
     * Creates a builder for SubscriptionInfo.
     * 
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Builder for SubscriptionInfo.
     */
    public static class Builder {
        private Long id;
        private String topic;
        private String groupName;
        private SubscriptionState state = SubscriptionState.ACTIVE;
        private Instant subscribedAt = Instant.now();
        private Instant lastActiveAt = Instant.now();
        private Long startFromMessageId;
        private Instant startFromTimestamp;
        private int heartbeatIntervalSeconds = 60;
        private int heartbeatTimeoutSeconds = 300;
        private Instant lastHeartbeatAt = Instant.now();
        private String backfillStatus = "NONE";
        private Long backfillCheckpointId;
        private Long backfillProcessedMessages = 0L;
        private Long backfillTotalMessages;
        private Instant backfillStartedAt;
        private Instant backfillCompletedAt;
        
        public Builder id(Long id) { this.id = id; return this; }
        public Builder topic(String topic) { this.topic = topic; return this; }
        public Builder groupName(String groupName) { this.groupName = groupName; return this; }
        public Builder state(SubscriptionState state) { this.state = state; return this; }
        public Builder subscribedAt(Instant subscribedAt) { this.subscribedAt = subscribedAt; return this; }
        public Builder lastActiveAt(Instant lastActiveAt) { this.lastActiveAt = lastActiveAt; return this; }
        public Builder startFromMessageId(Long id) { this.startFromMessageId = id; return this; }
        public Builder startFromTimestamp(Instant ts) { this.startFromTimestamp = ts; return this; }
        public Builder heartbeatIntervalSeconds(int s) { this.heartbeatIntervalSeconds = s; return this; }
        public Builder heartbeatTimeoutSeconds(int s) { this.heartbeatTimeoutSeconds = s; return this; }
        public Builder lastHeartbeatAt(Instant at) { this.lastHeartbeatAt = at; return this; }
        public Builder backfillStatus(String status) { this.backfillStatus = status; return this; }
        public Builder backfillCheckpointId(Long id) { this.backfillCheckpointId = id; return this; }
        public Builder backfillProcessedMessages(Long n) { this.backfillProcessedMessages = n; return this; }
        public Builder backfillTotalMessages(Long n) { this.backfillTotalMessages = n; return this; }
        public Builder backfillStartedAt(Instant at) { this.backfillStartedAt = at; return this; }
        public Builder backfillCompletedAt(Instant at) { this.backfillCompletedAt = at; return this; }
        
        public SubscriptionInfo build() {
            return new SubscriptionInfo(
                id, topic, groupName, state, subscribedAt, lastActiveAt,
                startFromMessageId, startFromTimestamp,
                heartbeatIntervalSeconds, heartbeatTimeoutSeconds, lastHeartbeatAt,
                backfillStatus, backfillCheckpointId, backfillProcessedMessages,
                backfillTotalMessages, backfillStartedAt, backfillCompletedAt
            );
        }
    }
}

