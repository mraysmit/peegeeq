package dev.mars.peegeeq.outbox.deadletter;

import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.outbox.config.FilterErrorHandlingConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages dead letter queue operations for filter error handling.
 * Provides routing, metadata enrichment, and monitoring capabilities.
 */
public class DeadLetterQueueManager {
    private static final Logger logger = LoggerFactory.getLogger(DeadLetterQueueManager.class);
    
    private final FilterErrorHandlingConfig config;
    private final Map<String, DeadLetterQueue> deadLetterQueues = new ConcurrentHashMap<>();
    private final AtomicLong totalDeadLetterMessages = new AtomicLong(0);
    
    public DeadLetterQueueManager(FilterErrorHandlingConfig config) {
        this.config = config;
        
        // Initialize default dead letter queue if enabled
        if (config.isDeadLetterQueueEnabled()) {
            String defaultTopic = config.getDeadLetterQueueTopic();
            deadLetterQueues.put(defaultTopic, new LoggingDeadLetterQueue(defaultTopic));
            logger.info("Initialized dead letter queue manager with default topic: {}", defaultTopic);
        }
    }
    
    /**
     * Sends a message to the appropriate dead letter queue
     */
    public <T> CompletableFuture<Void> sendToDeadLetter(
            Message<T> originalMessage,
            String filterId,
            String reason,
            int attempts,
            FilterErrorHandlingConfig.ErrorClassification errorClassification,
            Exception originalException) {
        
        if (!config.isDeadLetterQueueEnabled()) {
            logger.warn("Dead letter queue is disabled, cannot send message {}", originalMessage.getId());
            return CompletableFuture.failedFuture(
                new IllegalStateException("Dead letter queue is disabled"));
        }
        
        // Determine the appropriate dead letter queue topic
        String topic = determineDeadLetterTopic(originalMessage, errorClassification);
        
        // Get or create the dead letter queue
        DeadLetterQueue dlq = deadLetterQueues.computeIfAbsent(topic, 
            topicName -> new LoggingDeadLetterQueue(topicName));
        
        // Enrich metadata
        Map<String, String> metadata = createDeadLetterMetadata(
            filterId, errorClassification, originalException, attempts);
        
        // Send to dead letter queue
        totalDeadLetterMessages.incrementAndGet();
        
        logger.warn("Sending message {} to dead letter queue '{}' after {} attempts. Reason: {}", 
            originalMessage.getId(), topic, attempts, reason);
        
        return dlq.sendToDeadLetter(originalMessage, reason, attempts, metadata)
            .whenComplete((result, throwable) -> {
                if (throwable != null) {
                    logger.error("Failed to send message {} to dead letter queue '{}': {}", 
                        originalMessage.getId(), topic, throwable.getMessage());
                } else {
                    logger.info("Successfully sent message {} to dead letter queue '{}'", 
                        originalMessage.getId(), topic);
                }
            });
    }
    
    /**
     * Determines the appropriate dead letter topic based on message and error classification
     */
    private <T> String determineDeadLetterTopic(Message<T> message, 
                                               FilterErrorHandlingConfig.ErrorClassification classification) {
        // For now, use the default topic. In a more sophisticated implementation,
        // this could route to different topics based on error type, message type, etc.
        String baseTopic = config.getDeadLetterQueueTopic();
        
        // Could add classification-based routing:
        // return baseTopic + "-" + classification.name().toLowerCase();
        
        return baseTopic;
    }
    
    /**
     * Creates enriched metadata for dead letter messages
     */
    private Map<String, String> createDeadLetterMetadata(
            String filterId,
            FilterErrorHandlingConfig.ErrorClassification errorClassification,
            Exception originalException,
            int attempts) {
        
        Map<String, String> metadata = new HashMap<>();
        metadata.put("filterId", filterId);
        metadata.put("errorClassification", errorClassification.name());
        metadata.put("attempts", String.valueOf(attempts));
        metadata.put("deadLetterTime", Instant.now().toString());
        metadata.put("exceptionType", originalException.getClass().getSimpleName());
        metadata.put("exceptionMessage", originalException.getMessage());
        
        // Add stack trace for debugging (first few lines only)
        String stackTrace = getStackTracePreview(originalException);
        metadata.put("stackTracePreview", stackTrace);
        
        return metadata;
    }
    
    /**
     * Gets a preview of the stack trace for debugging
     */
    private String getStackTracePreview(Exception exception) {
        StackTraceElement[] stackTrace = exception.getStackTrace();
        if (stackTrace.length == 0) {
            return "No stack trace available";
        }
        
        StringBuilder preview = new StringBuilder();
        int maxLines = Math.min(3, stackTrace.length);
        
        for (int i = 0; i < maxLines; i++) {
            if (i > 0) preview.append(" -> ");
            preview.append(stackTrace[i].toString());
        }
        
        if (stackTrace.length > maxLines) {
            preview.append(" ... (").append(stackTrace.length - maxLines).append(" more)");
        }
        
        return preview.toString();
    }
    
    /**
     * Gets a specific dead letter queue by topic
     */
    public DeadLetterQueue getDeadLetterQueue(String topic) {
        return deadLetterQueues.get(topic);
    }
    
    /**
     * Gets all configured dead letter queues
     */
    public Map<String, DeadLetterQueue> getAllDeadLetterQueues() {
        return new HashMap<>(deadLetterQueues);
    }
    
    /**
     * Gets aggregated metrics across all dead letter queues
     */
    public DeadLetterManagerMetrics getMetrics() {
        long totalSent = 0;
        long totalFailed = 0;
        Instant lastSentTime = null;
        Instant lastFailureTime = null;
        
        for (DeadLetterQueue dlq : deadLetterQueues.values()) {
            DeadLetterQueue.DeadLetterMetrics metrics = dlq.getMetrics();
            totalSent += metrics.getTotalSent();
            totalFailed += metrics.getTotalFailed();
            
            if (metrics.getLastSentTime() != null && 
                (lastSentTime == null || metrics.getLastSentTime().isAfter(lastSentTime))) {
                lastSentTime = metrics.getLastSentTime();
            }
            
            if (metrics.getLastFailureTime() != null && 
                (lastFailureTime == null || metrics.getLastFailureTime().isAfter(lastFailureTime))) {
                lastFailureTime = metrics.getLastFailureTime();
            }
        }
        
        return new DeadLetterManagerMetrics(
            deadLetterQueues.size(),
            totalDeadLetterMessages.get(),
            totalSent,
            totalFailed,
            lastSentTime,
            lastFailureTime
        );
    }
    
    /**
     * Closes all dead letter queues and releases resources
     */
    public void close() {
        logger.info("Closing dead letter queue manager with {} queues", deadLetterQueues.size());
        
        for (Map.Entry<String, DeadLetterQueue> entry : deadLetterQueues.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Exception e) {
                logger.error("Error closing dead letter queue '{}': {}", entry.getKey(), e.getMessage());
            }
        }
        
        deadLetterQueues.clear();
    }
    
    /**
     * Aggregated metrics for the dead letter queue manager
     */
    public static class DeadLetterManagerMetrics {
        private final int queueCount;
        private final long totalMessages;
        private final long totalSent;
        private final long totalFailed;
        private final Instant lastSentTime;
        private final Instant lastFailureTime;
        
        public DeadLetterManagerMetrics(int queueCount, long totalMessages, long totalSent, 
                                      long totalFailed, Instant lastSentTime, Instant lastFailureTime) {
            this.queueCount = queueCount;
            this.totalMessages = totalMessages;
            this.totalSent = totalSent;
            this.totalFailed = totalFailed;
            this.lastSentTime = lastSentTime;
            this.lastFailureTime = lastFailureTime;
        }
        
        public int getQueueCount() { return queueCount; }
        public long getTotalMessages() { return totalMessages; }
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
            return String.format("DeadLetterManagerMetrics{queues=%d, totalMessages=%d, sent=%d, failed=%d, successRate=%.2f%%}", 
                queueCount, totalMessages, totalSent, totalFailed, getSuccessRate() * 100);
        }
    }
}
