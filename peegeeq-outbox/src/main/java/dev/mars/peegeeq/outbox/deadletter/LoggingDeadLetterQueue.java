package dev.mars.peegeeq.outbox.deadletter;

import dev.mars.peegeeq.api.messaging.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Simple logging-based implementation of DeadLetterQueue for development and testing.
 * In production, this would be replaced with actual message queue integration.
 */
public class LoggingDeadLetterQueue implements DeadLetterQueue {
    private static final Logger logger = LoggerFactory.getLogger(LoggingDeadLetterQueue.class);
    
    private final String topic;
    private final AtomicLong totalSent = new AtomicLong(0);
    private final AtomicLong totalFailed = new AtomicLong(0);
    private final AtomicReference<Instant> lastSentTime = new AtomicReference<>();
    private final AtomicReference<Instant> lastFailureTime = new AtomicReference<>();
    
    public LoggingDeadLetterQueue(String topic) {
        this.topic = topic;
        logger.info("Created logging dead letter queue for topic: {}", topic);
    }
    
    @Override
    public <T> CompletableFuture<Void> sendToDeadLetter(
            Message<T> originalMessage, 
            String reason, 
            int attempts,
            Map<String, String> metadata) {
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Log the dead letter message with full details
                // Check if this is an intentional test failure
                boolean isIntentionalTest = reason != null && reason.contains("INTENTIONAL TEST FAILURE");
                String logPrefix = isIntentionalTest ? "🧪 INTENTIONAL TEST FAILURE - " : "";
                String logSuffix = isIntentionalTest ? " (THIS IS EXPECTED IN TESTS)" : "";

                logger.error("{}DEAD LETTER QUEUE [{}]: Message {} failed after {} attempts{}",
                    logPrefix, topic, originalMessage.getId(), attempts, logSuffix);
                logger.error("{}  Reason: {}{}", logPrefix, reason, logSuffix);
                logger.error("{}  Original Message: {}{}", logPrefix, originalMessage, logSuffix);
                logger.error("{}  Metadata Summary: {}{}",
                    logPrefix, summarizeMetadataForLog(metadata), logSuffix);
                logger.error("{}  Payload Summary: {}{}",
                    logPrefix, summarizePayloadForLog(originalMessage.getPayload()), logSuffix);
                logger.error("{}  Headers Summary: {}{}",
                    logPrefix, summarizeHeadersForLog(originalMessage.getHeaders()), logSuffix);
                
                // In a real implementation, this would send to an actual message queue
                // For now, we simulate success
                totalSent.incrementAndGet();
                lastSentTime.set(Instant.now());
                
                logger.info("Message {} successfully sent to dead letter queue '{}'", 
                    originalMessage.getId(), topic);
                
                return null;
                
            } catch (Exception e) {
                totalFailed.incrementAndGet();
                lastFailureTime.set(Instant.now());
                
                logger.error("Failed to send message {} to dead letter queue '{}': {}", 
                    originalMessage.getId(), topic, e.getMessage(), e);
                
                throw new RuntimeException("Failed to send to dead letter queue", e);
            }
        });
    }
    
    @Override
    public String getDeadLetterTopic() {
        return topic;
    }
    
    @Override
    public DeadLetterMetrics getMetrics() {
        return new DeadLetterMetrics(
            topic,
            totalSent.get(),
            totalFailed.get(),
            lastSentTime.get(),
            lastFailureTime.get()
        );
    }
    
    @Override
    public void close() {
        logger.info("Closing logging dead letter queue for topic: {}", topic);
        // No resources to clean up for logging implementation
    }

    static String summarizePayloadForLog(Object payload) {
        if (payload == null) {
            return "<null>";
        }
        if (payload instanceof CharSequence charSequence) {
            return "type=String,length=" + charSequence.length();
        }
        return "type=" + payload.getClass().getSimpleName();
    }

    static String summarizeHeadersForLog(Map<String, String> headers) {
        if (headers == null) {
            return "<null>";
        }
        return "count=" + headers.size();
    }

    static String summarizeMetadataForLog(Map<String, String> metadata) {
        if (metadata == null) {
            return "<null>";
        }
        List<String> keys = new ArrayList<>(metadata.keySet());
        Collections.sort(keys);
        return "count=" + metadata.size() + ",keys=" + keys;
    }
}
