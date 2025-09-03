package dev.mars.peegeeq.outbox.deadletter;

import dev.mars.peegeeq.api.messaging.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Dead Letter Queue interface for handling messages that cannot be processed
 * after all retry attempts have been exhausted.
 */
public interface DeadLetterQueue {
    
    /**
     * Sends a message to the dead letter queue
     * 
     * @param originalMessage The original message that failed processing
     * @param reason The reason for sending to DLQ
     * @param attempts Number of processing attempts made
     * @param metadata Additional metadata about the failure
     * @return CompletableFuture that completes when the message is sent to DLQ
     */
    <T> CompletableFuture<Void> sendToDeadLetter(
        Message<T> originalMessage, 
        String reason, 
        int attempts,
        Map<String, String> metadata
    );
    
    /**
     * Gets the topic/queue name for dead letter messages
     */
    String getDeadLetterTopic();
    
    /**
     * Gets metrics about dead letter queue operations
     */
    DeadLetterMetrics getMetrics();
    
    /**
     * Closes the dead letter queue and releases resources
     */
    void close();
    
    /**
     * Metrics for dead letter queue operations
     */
    class DeadLetterMetrics {
        private final String topic;
        private final long totalSent;
        private final long totalFailed;
        private final Instant lastSentTime;
        private final Instant lastFailureTime;
        
        public DeadLetterMetrics(String topic, long totalSent, long totalFailed, 
                               Instant lastSentTime, Instant lastFailureTime) {
            this.topic = topic;
            this.totalSent = totalSent;
            this.totalFailed = totalFailed;
            this.lastSentTime = lastSentTime;
            this.lastFailureTime = lastFailureTime;
        }
        
        public String getTopic() { return topic; }
        public long getTotalSent() { return totalSent; }
        public long getTotalFailed() { return totalFailed; }
        public Instant getLastSentTime() { return lastSentTime; }
        public Instant getLastFailureTime() { return lastFailureTime; }
        
        public double getSuccessRate() {
            long total = totalSent + totalFailed;
            return total > 0 ? (double) totalSent / total : 0.0;
        }
        
        @Override
        public String toString() {
            return String.format("DeadLetterMetrics{topic='%s', sent=%d, failed=%d, successRate=%.2f%%}", 
                topic, totalSent, totalFailed, getSuccessRate() * 100);
        }
    }
}
