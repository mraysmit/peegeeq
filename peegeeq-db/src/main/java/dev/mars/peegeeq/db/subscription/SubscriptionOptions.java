package dev.mars.peegeeq.db.subscription;

import java.time.Instant;
import java.util.Objects;

/**
 * Configuration options for consumer group subscriptions.
 * 
 * <p>This class encapsulates all configuration parameters for subscribing
 * a consumer group to a topic, including start position, heartbeat settings,
 * and backfill options.</p>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-12
 * @version 1.0
 */
public class SubscriptionOptions {
    
    private final StartPosition startPosition;
    private final Long startFromMessageId;
    private final Instant startFromTimestamp;
    private final int heartbeatIntervalSeconds;
    private final int heartbeatTimeoutSeconds;
    
    private SubscriptionOptions(Builder builder) {
        this.startPosition = builder.startPosition;
        this.startFromMessageId = builder.startFromMessageId;
        this.startFromTimestamp = builder.startFromTimestamp;
        this.heartbeatIntervalSeconds = builder.heartbeatIntervalSeconds;
        this.heartbeatTimeoutSeconds = builder.heartbeatTimeoutSeconds;
        
        // Validation
        if (startPosition == StartPosition.FROM_MESSAGE_ID && startFromMessageId == null) {
            throw new IllegalArgumentException("startFromMessageId must be provided when startPosition is FROM_MESSAGE_ID");
        }
        if (startPosition == StartPosition.FROM_TIMESTAMP && startFromTimestamp == null) {
            throw new IllegalArgumentException("startFromTimestamp must be provided when startPosition is FROM_TIMESTAMP");
        }
        if (heartbeatIntervalSeconds <= 0) {
            throw new IllegalArgumentException("heartbeatIntervalSeconds must be positive");
        }
        if (heartbeatTimeoutSeconds <= heartbeatIntervalSeconds) {
            throw new IllegalArgumentException("heartbeatTimeoutSeconds must be greater than heartbeatIntervalSeconds");
        }
    }
    
    public StartPosition getStartPosition() {
        return startPosition;
    }
    
    public Long getStartFromMessageId() {
        return startFromMessageId;
    }
    
    public Instant getStartFromTimestamp() {
        return startFromTimestamp;
    }
    
    public int getHeartbeatIntervalSeconds() {
        return heartbeatIntervalSeconds;
    }
    
    public int getHeartbeatTimeoutSeconds() {
        return heartbeatTimeoutSeconds;
    }
    
    /**
     * Creates a new builder with default values.
     * 
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Creates default subscription options (FROM_NOW with standard heartbeat settings).
     * 
     * @return Default subscription options
     */
    public static SubscriptionOptions defaults() {
        return builder().build();
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SubscriptionOptions that = (SubscriptionOptions) o;
        return heartbeatIntervalSeconds == that.heartbeatIntervalSeconds &&
               heartbeatTimeoutSeconds == that.heartbeatTimeoutSeconds &&
               startPosition == that.startPosition &&
               Objects.equals(startFromMessageId, that.startFromMessageId) &&
               Objects.equals(startFromTimestamp, that.startFromTimestamp);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(startPosition, startFromMessageId, startFromTimestamp,
                          heartbeatIntervalSeconds, heartbeatTimeoutSeconds);
    }
    
    @Override
    public String toString() {
        return "SubscriptionOptions{" +
               "startPosition=" + startPosition +
               ", startFromMessageId=" + startFromMessageId +
               ", startFromTimestamp=" + startFromTimestamp +
               ", heartbeatIntervalSeconds=" + heartbeatIntervalSeconds +
               ", heartbeatTimeoutSeconds=" + heartbeatTimeoutSeconds +
               '}';
    }
    
    /**
     * Builder for SubscriptionOptions.
     */
    public static class Builder {
        private StartPosition startPosition = StartPosition.FROM_NOW;
        private Long startFromMessageId = null;
        private Instant startFromTimestamp = null;
        private int heartbeatIntervalSeconds = 60;  // Default: 60 seconds
        private int heartbeatTimeoutSeconds = 300;  // Default: 5 minutes
        
        /**
         * Sets the start position for the subscription.
         * 
         * @param startPosition The start position
         * @return This builder
         */
        public Builder startPosition(StartPosition startPosition) {
            this.startPosition = Objects.requireNonNull(startPosition, "startPosition cannot be null");
            return this;
        }
        
        /**
         * Sets the message ID to start from (only valid with FROM_MESSAGE_ID).
         * 
         * @param messageId The message ID to start from
         * @return This builder
         */
        public Builder startFromMessageId(long messageId) {
            this.startFromMessageId = messageId;
            this.startPosition = StartPosition.FROM_MESSAGE_ID;
            return this;
        }
        
        /**
         * Sets the timestamp to start from (only valid with FROM_TIMESTAMP).
         * 
         * @param timestamp The timestamp to start from
         * @return This builder
         */
        public Builder startFromTimestamp(Instant timestamp) {
            this.startFromTimestamp = Objects.requireNonNull(timestamp, "timestamp cannot be null");
            this.startPosition = StartPosition.FROM_TIMESTAMP;
            return this;
        }
        
        /**
         * Sets the heartbeat interval in seconds.
         * 
         * @param seconds Heartbeat interval (must be positive)
         * @return This builder
         */
        public Builder heartbeatIntervalSeconds(int seconds) {
            if (seconds <= 0) {
                throw new IllegalArgumentException("heartbeatIntervalSeconds must be positive");
            }
            this.heartbeatIntervalSeconds = seconds;
            return this;
        }
        
        /**
         * Sets the heartbeat timeout in seconds.
         * 
         * @param seconds Heartbeat timeout (must be greater than interval)
         * @return This builder
         */
        public Builder heartbeatTimeoutSeconds(int seconds) {
            if (seconds <= 0) {
                throw new IllegalArgumentException("heartbeatTimeoutSeconds must be positive");
            }
            this.heartbeatTimeoutSeconds = seconds;
            return this;
        }
        
        /**
         * Builds the SubscriptionOptions instance.
         * 
         * @return A new SubscriptionOptions instance
         * @throws IllegalArgumentException if validation fails
         */
        public SubscriptionOptions build() {
            return new SubscriptionOptions(this);
        }
    }
}

