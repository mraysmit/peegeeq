package dev.mars.peegeeq.api.messaging;

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
    private final int deadAfterMisses;
    private final BackfillScope backfillScope;
    private final boolean durableEnabled;
    private final String subscriptionName;
    private final String consumerId;
    private final int replayBatchSize;
    
    private SubscriptionOptions(Builder builder) {
        this.startPosition = builder.startPosition;
        this.startFromMessageId = builder.startFromMessageId;
        this.startFromTimestamp = builder.startFromTimestamp;
        this.heartbeatIntervalSeconds = builder.heartbeatIntervalSeconds;
        this.heartbeatTimeoutSeconds = builder.heartbeatTimeoutSeconds;
        this.deadAfterMisses = builder.deadAfterMisses;
        this.backfillScope = builder.backfillScope;
        this.durableEnabled = builder.durableEnabled;
        this.subscriptionName = builder.subscriptionName;
        this.consumerId = builder.consumerId;
        this.replayBatchSize = builder.replayBatchSize;
        
        // Validation
        if (startPosition == StartPosition.FROM_MESSAGE_ID && startFromMessageId == null) {
            throw new IllegalArgumentException("startFromMessageId must be provided when startPosition is FROM_MESSAGE_ID");
        }
        if (startPosition == StartPosition.FROM_TIMESTAMP && startFromTimestamp == null) {
            throw new IllegalArgumentException("startFromTimestamp must be provided when startPosition is FROM_TIMESTAMP");
        }
        // heartbeatIntervalSeconds is guaranteed positive by Builder
        if (heartbeatTimeoutSeconds <= heartbeatIntervalSeconds) {
            throw new IllegalArgumentException("heartbeatTimeoutSeconds must be greater than heartbeatIntervalSeconds");
        }
        if (durableEnabled && (subscriptionName == null || subscriptionName.isBlank())) {
            throw new IllegalArgumentException(
                "subscriptionName must be provided when durableEnabled is true");
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

    public int getDeadAfterMisses() {
        return deadAfterMisses;
    }

    public BackfillScope getBackfillScope() {
        return backfillScope;
    }

    public boolean isDurableEnabled() {
        return durableEnabled;
    }

    public String getSubscriptionName() {
        return subscriptionName;
    }

    public String getConsumerId() {
        return consumerId;
    }

    public int getReplayBatchSize() {
        return replayBatchSize;
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
    
    /**
     * Creates subscription options that start consuming from the beginning of available messages.
     *
     * <p>Uses default heartbeat settings (60s interval, 300s timeout).</p>
     *
     * @return Subscription options with {@link StartPosition#FROM_BEGINNING}
     */
    public static SubscriptionOptions fromBeginning() {
        return builder().startPosition(StartPosition.FROM_BEGINNING).build();
    }

    /**
     * Creates subscription options that start consuming from the beginning of available
     * messages and uses the provided backfill scope.
     *
     * @param backfillScope Scope of messages to include in backfill
     * @return Subscription options with {@link StartPosition#FROM_BEGINNING}
     */
    public static SubscriptionOptions fromBeginning(BackfillScope backfillScope) {
        return builder()
                .startPosition(StartPosition.FROM_BEGINNING)
                .backfillScope(backfillScope)
                .build();
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SubscriptionOptions that = (SubscriptionOptions) o;
        return heartbeatIntervalSeconds == that.heartbeatIntervalSeconds &&
               heartbeatTimeoutSeconds == that.heartbeatTimeoutSeconds &&
               deadAfterMisses == that.deadAfterMisses &&
               durableEnabled == that.durableEnabled &&
               replayBatchSize == that.replayBatchSize &&
               startPosition == that.startPosition &&
               backfillScope == that.backfillScope &&
               Objects.equals(startFromMessageId, that.startFromMessageId) &&
               Objects.equals(startFromTimestamp, that.startFromTimestamp) &&
               Objects.equals(subscriptionName, that.subscriptionName) &&
               Objects.equals(consumerId, that.consumerId);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(startPosition, startFromMessageId, startFromTimestamp,
                          heartbeatIntervalSeconds, heartbeatTimeoutSeconds, deadAfterMisses,
                          backfillScope, durableEnabled, subscriptionName, consumerId,
                          replayBatchSize);
    }
    
    @Override
    public String toString() {
        return "SubscriptionOptions{" +
               "startPosition=" + startPosition +
               ", startFromMessageId=" + startFromMessageId +
               ", startFromTimestamp=" + startFromTimestamp +
               ", heartbeatIntervalSeconds=" + heartbeatIntervalSeconds +
               ", heartbeatTimeoutSeconds=" + heartbeatTimeoutSeconds +
               ", deadAfterMisses=" + deadAfterMisses +
               ", backfillScope=" + backfillScope +
               ", durableEnabled=" + durableEnabled +
               ", subscriptionName=" + subscriptionName +
               ", consumerId=" + consumerId +
               ", replayBatchSize=" + replayBatchSize +
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
        private int deadAfterMisses = 3;             // Default: 3 consecutive misses
        private BackfillScope backfillScope = BackfillScope.PENDING_ONLY;
        private boolean durableEnabled;
        private String subscriptionName;
        private String consumerId;
        private int replayBatchSize = 500;
        
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
         * Sets the number of consecutive heartbeat misses required before
         * marking the subscription DEAD. Must be at least 1.
         *
         * @param misses Number of consecutive misses (default: 3)
         * @return This builder
         */
        public Builder deadAfterMisses(int misses) {
            if (misses < 1) {
                throw new IllegalArgumentException("deadAfterMisses must be at least 1");
            }
            this.deadAfterMisses = misses;
            return this;
        }

        /**
         * Sets the backfill scope used when start position requires backfill
         * (for example, FROM_BEGINNING).
         *
         * @param backfillScope scope to use for backfill operations
         * @return This builder
         */
        public Builder backfillScope(BackfillScope backfillScope) {
            this.backfillScope = Objects.requireNonNull(backfillScope, "backfillScope cannot be null");
            return this;
        }

        /**
         * Enables persistence and replay for this subscription.
         *
         * @param enabled whether durable delivery is enabled
         * @return This builder
         */
        public Builder durableEnabled(boolean enabled) {
            this.durableEnabled = enabled;
            return this;
        }

        /**
         * Sets the stable subscription name used as part of the durable identity.
         *
         * @param name stable subscription name
         * @return This builder
         */
        public Builder subscriptionName(String name) {
            this.subscriptionName = name;
            return this;
        }

        /**
         * Sets the identifier of the consumer instance currently attaching a handler.
         *
         * @param id consumer instance identifier, or null when not applicable
         * @return This builder
         */
        public Builder consumerId(String id) {
            if (id != null && id.isBlank()) {
                throw new IllegalArgumentException("consumerId must not be blank");
            }
            this.consumerId = id;
            return this;
        }

        /**
         * Sets the maximum number of historical events fetched per replay query.
         *
         * @param size positive replay batch size
         * @return This builder
         */
        public Builder replayBatchSize(int size) {
            if (size <= 0) {
                throw new IllegalArgumentException("replayBatchSize must be positive");
            }
            this.replayBatchSize = size;
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
