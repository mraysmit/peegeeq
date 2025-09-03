package dev.mars.peegeeq.outbox.resilience;

import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.outbox.config.FilterErrorHandlingConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Manages retry logic for filter operations with exponential backoff.
 * Handles both transient and permanent errors appropriately.
 */
public class FilterRetryManager {
    private static final Logger logger = LoggerFactory.getLogger(FilterRetryManager.class);
    
    private final String filterId;
    private final FilterErrorHandlingConfig config;
    private final ScheduledExecutorService scheduler;
    
    public FilterRetryManager(String filterId, FilterErrorHandlingConfig config, 
                            ScheduledExecutorService scheduler) {
        this.filterId = filterId;
        this.config = config;
        this.scheduler = scheduler;
    }
    
    /**
     * Executes a filter operation with retry logic based on error classification
     */
    public <T> CompletableFuture<Boolean> executeWithRetry(
            Message<T> message,
            Predicate<Message<T>> filter,
            FilterCircuitBreaker circuitBreaker) {
        
        return executeWithRetry(message, filter, circuitBreaker, 0, config.getInitialRetryDelay());
    }
    
    private <T> CompletableFuture<Boolean> executeWithRetry(
            Message<T> message,
            Predicate<Message<T>> filter,
            FilterCircuitBreaker circuitBreaker,
            int attemptNumber,
            Duration currentDelay) {
        
        // Check circuit breaker
        if (!circuitBreaker.allowRequest()) {
            logger.debug("Filter circuit breaker '{}' is open, rejecting message {}", 
                filterId, message.getId());
            return CompletableFuture.completedFuture(false);
        }
        
        try {
            // Execute the filter
            boolean result = filter.test(message);
            
            // Record success
            circuitBreaker.recordSuccess();
            
            logger.debug("Filter '{}' accepted message {} on attempt {}", 
                filterId, message.getId(), attemptNumber + 1);
            
            return CompletableFuture.completedFuture(result);
            
        } catch (Exception e) {
            // Record failure
            circuitBreaker.recordFailure();
            
            // Classify the error
            FilterErrorHandlingConfig.ErrorClassification classification = config.classifyError(e);
            FilterErrorHandlingConfig.FilterErrorStrategy strategy = config.getStrategyForError(classification);
            
            logger.debug("Filter '{}' failed for message {} on attempt {} - Classification: {}, Strategy: {}, Error: {}", 
                filterId, message.getId(), attemptNumber + 1, classification, strategy, e.getMessage());
            
            // Handle based on strategy and attempt number
            return handleFilterError(message, filter, circuitBreaker, e, classification, strategy, 
                attemptNumber, currentDelay);
        }
    }
    
    private <T> CompletableFuture<Boolean> handleFilterError(
            Message<T> message,
            Predicate<Message<T>> filter,
            FilterCircuitBreaker circuitBreaker,
            Exception error,
            FilterErrorHandlingConfig.ErrorClassification classification,
            FilterErrorHandlingConfig.FilterErrorStrategy strategy,
            int attemptNumber,
            Duration currentDelay) {
        
        switch (strategy) {
            case REJECT_IMMEDIATELY:
                logger.info("Filter '{}' rejecting message {} immediately due to {} error: {}", 
                    filterId, message.getId(), classification.name().toLowerCase(), error.getMessage());
                return CompletableFuture.completedFuture(false);
                
            case RETRY_THEN_REJECT:
                if (attemptNumber >= config.getMaxRetries()) {
                    logger.info("Filter '{}' rejecting message {} after {} attempts. Final error: {}", 
                        filterId, message.getId(), attemptNumber + 1, error.getMessage());
                    return CompletableFuture.completedFuture(false);
                } else {
                    return scheduleRetry(message, filter, circuitBreaker, attemptNumber, currentDelay);
                }
                
            case RETRY_THEN_DEAD_LETTER:
                if (attemptNumber >= config.getMaxRetries()) {
                    logger.warn("Filter '{}' sending message {} to dead letter queue after {} attempts. Final error: {}", 
                        filterId, message.getId(), attemptNumber + 1, error.getMessage());
                    return sendToDeadLetterQueue(message, error, attemptNumber + 1);
                } else {
                    return scheduleRetry(message, filter, circuitBreaker, attemptNumber, currentDelay);
                }
                
            case DEAD_LETTER_IMMEDIATELY:
                logger.warn("Filter '{}' sending message {} to dead letter queue immediately due to {} error: {}", 
                    filterId, message.getId(), classification.name().toLowerCase(), error.getMessage());
                return sendToDeadLetterQueue(message, error, attemptNumber + 1);
                
            default:
                logger.warn("Unknown filter error strategy: {}. Rejecting message {}", strategy, message.getId());
                return CompletableFuture.completedFuture(false);
        }
    }
    
    private <T> CompletableFuture<Boolean> scheduleRetry(
            Message<T> message,
            Predicate<Message<T>> filter,
            FilterCircuitBreaker circuitBreaker,
            int attemptNumber,
            Duration currentDelay) {
        
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        
        logger.debug("Filter '{}' scheduling retry {} for message {} after delay of {}", 
            filterId, attemptNumber + 2, message.getId(), currentDelay);
        
        scheduler.schedule(() -> {
            try {
                // Calculate next delay with exponential backoff
                Duration nextDelay = calculateNextDelay(currentDelay);
                
                executeWithRetry(message, filter, circuitBreaker, attemptNumber + 1, nextDelay)
                    .whenComplete((result, throwable) -> {
                        if (throwable != null) {
                            future.completeExceptionally(throwable);
                        } else {
                            future.complete(result);
                        }
                    });
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        }, currentDelay.toMillis(), TimeUnit.MILLISECONDS);
        
        return future;
    }
    
    private Duration calculateNextDelay(Duration currentDelay) {
        long nextDelayMs = (long) (currentDelay.toMillis() * config.getRetryBackoffMultiplier());
        Duration nextDelay = Duration.ofMillis(nextDelayMs);
        
        // Cap at maximum delay
        if (nextDelay.compareTo(config.getMaxRetryDelay()) > 0) {
            nextDelay = config.getMaxRetryDelay();
        }
        
        return nextDelay;
    }
    
    private <T> CompletableFuture<Boolean> sendToDeadLetterQueue(Message<T> message, Exception error, int attempts) {
        if (!config.isDeadLetterQueueEnabled()) {
            logger.warn("Dead letter queue is disabled, rejecting message {} instead", message.getId());
            return CompletableFuture.completedFuture(false);
        }
        
        // TODO: Implement actual dead letter queue integration
        // For now, we'll log the action and reject the message
        logger.warn("DEAD LETTER QUEUE: Message {} would be sent to topic '{}' after {} filter attempts. Error: {}", 
            message.getId(), config.getDeadLetterQueueTopic(), attempts, error.getMessage());
        
        // Return false to indicate the message was rejected (but handled)
        return CompletableFuture.completedFuture(false);
    }
    
    /**
     * Creates a retry context for tracking retry attempts
     */
    public static class RetryContext {
        private final String messageId;
        private final String filterId;
        private final int attemptNumber;
        private final Instant startTime;
        private final Duration totalDelay;
        
        public RetryContext(String messageId, String filterId, int attemptNumber, 
                          Instant startTime, Duration totalDelay) {
            this.messageId = messageId;
            this.filterId = filterId;
            this.attemptNumber = attemptNumber;
            this.startTime = startTime;
            this.totalDelay = totalDelay;
        }
        
        public String getMessageId() { return messageId; }
        public String getFilterId() { return filterId; }
        public int getAttemptNumber() { return attemptNumber; }
        public Instant getStartTime() { return startTime; }
        public Duration getTotalDelay() { return totalDelay; }
        
        @Override
        public String toString() {
            return String.format("RetryContext{messageId='%s', filterId='%s', attempt=%d, totalDelay=%s}", 
                messageId, filterId, attemptNumber, totalDelay);
        }
    }
}
