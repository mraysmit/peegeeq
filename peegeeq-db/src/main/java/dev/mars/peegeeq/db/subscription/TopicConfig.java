package dev.mars.peegeeq.db.subscription;

import java.time.Instant;
import java.util.Objects;

/**
 * Configuration for a topic in the PeeGeeQ fan-out system.
 * 
 * <p>This class represents the configuration stored in the outbox_topics table,
 * including semantics (QUEUE vs PUB_SUB), retention policies, and completion tracking mode.</p>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-12
 * @version 1.0
 */
public class TopicConfig {
    
    private final String topic;
    private final TopicSemantics semantics;
    private final int messageRetentionHours;
    private final int zeroSubscriptionRetentionHours;
    private final boolean blockWritesOnZeroSubscriptions;
    private final String completionTrackingMode;
    private final Instant createdAt;
    private final Instant updatedAt;
    
    private TopicConfig(Builder builder) {
        this.topic = builder.topic;
        this.semantics = builder.semantics;
        this.messageRetentionHours = builder.messageRetentionHours;
        this.zeroSubscriptionRetentionHours = builder.zeroSubscriptionRetentionHours;
        this.blockWritesOnZeroSubscriptions = builder.blockWritesOnZeroSubscriptions;
        this.completionTrackingMode = builder.completionTrackingMode;
        this.createdAt = builder.createdAt;
        this.updatedAt = builder.updatedAt;
    }
    
    // Getters
    public String getTopic() { return topic; }
    public TopicSemantics getSemantics() { return semantics; }
    public int getMessageRetentionHours() { return messageRetentionHours; }
    public int getZeroSubscriptionRetentionHours() { return zeroSubscriptionRetentionHours; }
    public boolean isBlockWritesOnZeroSubscriptions() { return blockWritesOnZeroSubscriptions; }
    public String getCompletionTrackingMode() { return completionTrackingMode; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    
    /**
     * Checks if this topic uses PUB_SUB semantics.
     * 
     * @return true if semantics is PUB_SUB
     */
    public boolean isPubSub() {
        return semantics == TopicSemantics.PUB_SUB;
    }
    
    /**
     * Checks if this topic uses QUEUE semantics.
     * 
     * @return true if semantics is QUEUE
     */
    public boolean isQueue() {
        return semantics == TopicSemantics.QUEUE;
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TopicConfig that = (TopicConfig) o;
        return Objects.equals(topic, that.topic);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(topic);
    }
    
    @Override
    public String toString() {
        return "TopicConfig{" +
               "topic='" + topic + '\'' +
               ", semantics=" + semantics +
               ", messageRetentionHours=" + messageRetentionHours +
               ", zeroSubscriptionRetentionHours=" + zeroSubscriptionRetentionHours +
               ", blockWritesOnZeroSubscriptions=" + blockWritesOnZeroSubscriptions +
               ", completionTrackingMode='" + completionTrackingMode + '\'' +
               '}';
    }
    
    /**
     * Builder for TopicConfig.
     */
    public static class Builder {
        private String topic;
        private TopicSemantics semantics = TopicSemantics.QUEUE;
        private int messageRetentionHours = 24;
        private int zeroSubscriptionRetentionHours = 24;
        private boolean blockWritesOnZeroSubscriptions = false;
        private String completionTrackingMode = "REFERENCE_COUNTING";
        private Instant createdAt = Instant.now();
        private Instant updatedAt = Instant.now();
        
        /**
         * Sets the topic name.
         * 
         * @param topic The topic name (required)
         * @return This builder
         */
        public Builder topic(String topic) {
            this.topic = Objects.requireNonNull(topic, "topic cannot be null");
            return this;
        }
        
        /**
         * Sets the topic semantics.
         * 
         * @param semantics The topic semantics (QUEUE or PUB_SUB)
         * @return This builder
         */
        public Builder semantics(TopicSemantics semantics) {
            this.semantics = Objects.requireNonNull(semantics, "semantics cannot be null");
            return this;
        }
        
        /**
         * Sets the message retention period in hours.
         * 
         * @param hours Retention period (must be positive)
         * @return This builder
         */
        public Builder messageRetentionHours(int hours) {
            if (hours <= 0) {
                throw new IllegalArgumentException("messageRetentionHours must be positive");
            }
            this.messageRetentionHours = hours;
            return this;
        }
        
        /**
         * Sets the zero-subscription retention period in hours.
         * 
         * @param hours Retention period for messages with no active subscriptions
         * @return This builder
         */
        public Builder zeroSubscriptionRetentionHours(int hours) {
            if (hours <= 0) {
                throw new IllegalArgumentException("zeroSubscriptionRetentionHours must be positive");
            }
            this.zeroSubscriptionRetentionHours = hours;
            return this;
        }
        
        /**
         * Sets whether to block writes when there are no active subscriptions.
         * 
         * @param block true to block writes, false to allow
         * @return This builder
         */
        public Builder blockWritesOnZeroSubscriptions(boolean block) {
            this.blockWritesOnZeroSubscriptions = block;
            return this;
        }
        
        /**
         * Sets the completion tracking mode.
         * 
         * @param mode Tracking mode (REFERENCE_COUNTING or OFFSET_WATERMARK)
         * @return This builder
         */
        public Builder completionTrackingMode(String mode) {
            this.completionTrackingMode = Objects.requireNonNull(mode, "completionTrackingMode cannot be null");
            return this;
        }
        
        /**
         * Sets the creation timestamp.
         * 
         * @param createdAt Creation timestamp
         * @return This builder
         */
        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }
        
        /**
         * Sets the update timestamp.
         * 
         * @param updatedAt Update timestamp
         * @return This builder
         */
        public Builder updatedAt(Instant updatedAt) {
            this.updatedAt = updatedAt;
            return this;
        }
        
        /**
         * Builds the TopicConfig instance.
         * 
         * @return A new TopicConfig instance
         * @throws NullPointerException if topic is null
         */
        public TopicConfig build() {
            Objects.requireNonNull(topic, "topic cannot be null");
            return new TopicConfig(this);
        }
    }
}

