package dev.mars.peegeeq.db.subscription;

import java.time.Instant;
import java.util.Objects;

/**
 * Represents a consumer group subscription to a topic.
 * 
 * <p>This class is a data model that maps to the outbox_topic_subscriptions table.
 * It contains all subscription metadata including status, heartbeat information,
 * and backfill tracking.</p>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-12
 * @version 1.0
 */
public class Subscription {
    
    private final Long id;
    private final String topic;
    private final String groupName;
    private final SubscriptionStatus status;
    private final Instant subscribedAt;
    private final Instant lastActiveAt;
    private final Long startFromMessageId;
    private final Instant startFromTimestamp;
    private final int heartbeatIntervalSeconds;
    private final int heartbeatTimeoutSeconds;
    private final Instant lastHeartbeatAt;
    private final String backfillStatus;
    private final Long backfillCheckpointId;
    private final Long backfillProcessedMessages;
    private final Long backfillTotalMessages;
    private final Instant backfillStartedAt;
    private final Instant backfillCompletedAt;
    
    private Subscription(Builder builder) {
        this.id = builder.id;
        this.topic = builder.topic;
        this.groupName = builder.groupName;
        this.status = builder.status;
        this.subscribedAt = builder.subscribedAt;
        this.lastActiveAt = builder.lastActiveAt;
        this.startFromMessageId = builder.startFromMessageId;
        this.startFromTimestamp = builder.startFromTimestamp;
        this.heartbeatIntervalSeconds = builder.heartbeatIntervalSeconds;
        this.heartbeatTimeoutSeconds = builder.heartbeatTimeoutSeconds;
        this.lastHeartbeatAt = builder.lastHeartbeatAt;
        this.backfillStatus = builder.backfillStatus;
        this.backfillCheckpointId = builder.backfillCheckpointId;
        this.backfillProcessedMessages = builder.backfillProcessedMessages;
        this.backfillTotalMessages = builder.backfillTotalMessages;
        this.backfillStartedAt = builder.backfillStartedAt;
        this.backfillCompletedAt = builder.backfillCompletedAt;
    }
    
    // Getters
    public Long getId() { return id; }
    public String getTopic() { return topic; }
    public String getGroupName() { return groupName; }
    public SubscriptionStatus getStatus() { return status; }
    public Instant getSubscribedAt() { return subscribedAt; }
    public Instant getLastActiveAt() { return lastActiveAt; }
    public Long getStartFromMessageId() { return startFromMessageId; }
    public Instant getStartFromTimestamp() { return startFromTimestamp; }
    public int getHeartbeatIntervalSeconds() { return heartbeatIntervalSeconds; }
    public int getHeartbeatTimeoutSeconds() { return heartbeatTimeoutSeconds; }
    public Instant getLastHeartbeatAt() { return lastHeartbeatAt; }
    public String getBackfillStatus() { return backfillStatus; }
    public Long getBackfillCheckpointId() { return backfillCheckpointId; }
    public Long getBackfillProcessedMessages() { return backfillProcessedMessages; }
    public Long getBackfillTotalMessages() { return backfillTotalMessages; }
    public Instant getBackfillStartedAt() { return backfillStartedAt; }
    public Instant getBackfillCompletedAt() { return backfillCompletedAt; }
    
    /**
     * Checks if the subscription is active.
     * 
     * @return true if status is ACTIVE
     */
    public boolean isActive() {
        return status == SubscriptionStatus.ACTIVE;
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
    
    public static Builder builder() {
        return new Builder();
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Subscription that = (Subscription) o;
        return Objects.equals(id, that.id) &&
               Objects.equals(topic, that.topic) &&
               Objects.equals(groupName, that.groupName);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(id, topic, groupName);
    }
    
    @Override
    public String toString() {
        return "Subscription{" +
               "id=" + id +
               ", topic='" + topic + '\'' +
               ", groupName='" + groupName + '\'' +
               ", status=" + status +
               ", subscribedAt=" + subscribedAt +
               ", lastHeartbeatAt=" + lastHeartbeatAt +
               '}';
    }
    
    /**
     * Builder for Subscription.
     */
    public static class Builder {
        private Long id;
        private String topic;
        private String groupName;
        private SubscriptionStatus status = SubscriptionStatus.ACTIVE;
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
        public Builder status(SubscriptionStatus status) { this.status = status; return this; }
        public Builder subscribedAt(Instant subscribedAt) { this.subscribedAt = subscribedAt; return this; }
        public Builder lastActiveAt(Instant lastActiveAt) { this.lastActiveAt = lastActiveAt; return this; }
        public Builder startFromMessageId(Long startFromMessageId) { this.startFromMessageId = startFromMessageId; return this; }
        public Builder startFromTimestamp(Instant startFromTimestamp) { this.startFromTimestamp = startFromTimestamp; return this; }
        public Builder heartbeatIntervalSeconds(int heartbeatIntervalSeconds) { this.heartbeatIntervalSeconds = heartbeatIntervalSeconds; return this; }
        public Builder heartbeatTimeoutSeconds(int heartbeatTimeoutSeconds) { this.heartbeatTimeoutSeconds = heartbeatTimeoutSeconds; return this; }
        public Builder lastHeartbeatAt(Instant lastHeartbeatAt) { this.lastHeartbeatAt = lastHeartbeatAt; return this; }
        public Builder backfillStatus(String backfillStatus) { this.backfillStatus = backfillStatus; return this; }
        public Builder backfillCheckpointId(Long backfillCheckpointId) { this.backfillCheckpointId = backfillCheckpointId; return this; }
        public Builder backfillProcessedMessages(Long backfillProcessedMessages) { this.backfillProcessedMessages = backfillProcessedMessages; return this; }
        public Builder backfillTotalMessages(Long backfillTotalMessages) { this.backfillTotalMessages = backfillTotalMessages; return this; }
        public Builder backfillStartedAt(Instant backfillStartedAt) { this.backfillStartedAt = backfillStartedAt; return this; }
        public Builder backfillCompletedAt(Instant backfillCompletedAt) { this.backfillCompletedAt = backfillCompletedAt; return this; }
        
        public Subscription build() {
            Objects.requireNonNull(topic, "topic cannot be null");
            Objects.requireNonNull(groupName, "groupName cannot be null");
            return new Subscription(this);
        }
    }
}

