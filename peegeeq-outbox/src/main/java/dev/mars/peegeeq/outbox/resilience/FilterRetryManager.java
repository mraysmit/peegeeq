package dev.mars.peegeeq.outbox.resilience;

import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.outbox.config.FilterErrorHandlingConfig;
import dev.mars.peegeeq.outbox.deadletter.DeadLetterQueueManager;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Predicate;

/**
 * Manages retry logic for filter operations with exponential backoff.
 * Handles both transient and permanent errors appropriately.
 */
public class FilterRetryManager {
    private static final Logger logger = LoggerFactory.getLogger(FilterRetryManager.class);

    private final String filterId;
    private final FilterErrorHandlingConfig config;
    private final Vertx vertx;
    private final DeadLetterQueueManager deadLetterQueueManager;

    /**
     * Creates a FilterRetryManager with dead letter queue support.
     *
     * @param filterId unique identifier for this filter
     * @param config error handling configuration
     * @param vertx Vert.x instance for scheduling retry timers
     * @param deadLetterQueueManager manager for dead letter queue operations (may be null if DLQ is disabled)
     */
    public FilterRetryManager(String filterId, FilterErrorHandlingConfig config,
                            Vertx vertx,
                            DeadLetterQueueManager deadLetterQueueManager) {
        this.filterId = filterId;
        this.config = config;
        this.vertx = vertx;
        this.deadLetterQueueManager = deadLetterQueueManager;
    }

    /**
     * Creates a FilterRetryManager without dead letter queue support.
     * Messages that would be sent to DLQ will be rejected instead.
     *
     * @param filterId unique identifier for this filter
     * @param config error handling configuration
     * @param vertx Vert.x instance for scheduling retry timers
     */
    public FilterRetryManager(String filterId, FilterErrorHandlingConfig config,
                            Vertx vertx) {
        this(filterId, config, vertx, null);
    }
    
    /**
     * Executes a filter operation with retry logic based on error classification
     */
    public <T> Future<Boolean> executeWithRetry(
            Message<T> message,
            Predicate<Message<T>> filter,
            FilterCircuitBreaker circuitBreaker) {
        
        return executeWithRetry(message, filter, circuitBreaker, 0, config.getInitialRetryDelay());
    }
    
    private <T> Future<Boolean> executeWithRetry(
            Message<T> message,
            Predicate<Message<T>> filter,
            FilterCircuitBreaker circuitBreaker,
            int attemptNumber,
            Duration currentDelay) {
        
        // Check circuit breaker
        if (!circuitBreaker.allowRequest()) {
            logger.debug("Filter circuit breaker '{}' is open, rejecting message {}", 
                filterId, message.getId());
            return Future.succeededFuture(false);
        }
        
        try {
            // Execute the filter
            boolean result = filter.test(message);
            
            // Record success
            circuitBreaker.recordSuccess();
            
            logger.debug("Filter '{}' accepted message {} on attempt {}", 
                filterId, message.getId(), attemptNumber + 1);
            
            return Future.succeededFuture(result);
            
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
    
    private <T> Future<Boolean> handleFilterError(
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
                return Future.succeededFuture(false);
                
            case RETRY_THEN_REJECT:
                if (attemptNumber >= config.getMaxRetries()) {
                    logger.info("Filter '{}' rejecting message {} after {} attempts. Final error: {}", 
                        filterId, message.getId(), attemptNumber + 1, error.getMessage());
                    return Future.succeededFuture(false);
                } else {
                    return scheduleRetry(message, filter, circuitBreaker, attemptNumber, currentDelay);
                }
                
            case RETRY_THEN_DEAD_LETTER:
                if (attemptNumber >= config.getMaxRetries()) {
                    logger.warn("Filter '{}' sending message {} to dead letter queue after {} attempts. Final error: {}",
                        filterId, message.getId(), attemptNumber + 1, error.getMessage());
                    return sendToDeadLetterQueue(message, error, attemptNumber + 1, classification);
                } else {
                    return scheduleRetry(message, filter, circuitBreaker, attemptNumber, currentDelay);
                }

            case DEAD_LETTER_IMMEDIATELY:
                logger.warn("Filter '{}' sending message {} to dead letter queue immediately due to {} error: {}",
                    filterId, message.getId(), classification.name().toLowerCase(), error.getMessage());
                return sendToDeadLetterQueue(message, error, attemptNumber + 1, classification);
                
            default:
                logger.warn("Unknown filter error strategy: {}. Rejecting message {}", strategy, message.getId());
                return Future.succeededFuture(false);
        }
    }
    
    private <T> Future<Boolean> scheduleRetry(
            Message<T> message,
            Predicate<Message<T>> filter,
            FilterCircuitBreaker circuitBreaker,
            int attemptNumber,
            Duration currentDelay) {
        
        Promise<Boolean> promise = Promise.promise();
        
        logger.debug("Filter '{}' scheduling retry {} for message {} after delay of {}", 
            filterId, attemptNumber + 2, message.getId(), currentDelay);
        
        vertx.setTimer(currentDelay.toMillis(), id -> {
            try {
                // Calculate next delay with exponential backoff
                Duration nextDelay = calculateNextDelay(currentDelay);
                
                executeWithRetry(message, filter, circuitBreaker, attemptNumber + 1, nextDelay)
                    .onSuccess(promise::complete)
                    .onFailure(promise::fail);
            } catch (Exception e) {
                promise.fail(e);
            }
        });
        
        return promise.future();
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
    
    private <T> Future<Boolean> sendToDeadLetterQueue(
            Message<T> message,
            Exception error,
            int attempts,
            FilterErrorHandlingConfig.ErrorClassification classification) {

        if (!config.isDeadLetterQueueEnabled()) {
            logger.warn("Dead letter queue is disabled, rejecting message {} instead", message.getId());
            return Future.succeededFuture(false);
        }

        if (deadLetterQueueManager == null) {
            logger.warn("Dead letter queue manager not configured, rejecting message {} instead. " +
                "Use the constructor with DeadLetterQueueManager to enable DLQ support.", message.getId());
            return Future.succeededFuture(false);
        }

        String reason = String.format("Filter '%s' failed after %d attempts: %s",
            filterId, attempts, error.getMessage());

        return deadLetterQueueManager.sendToDeadLetter(
                message,
                filterId,
                reason,
                attempts,
                classification,
                error)
            .map(v -> {
                // Return false to indicate the message was rejected (but handled via DLQ)
                logger.debug("Message {} successfully sent to dead letter queue", message.getId());
                return false;
            })
            .otherwise(throwable -> {
                logger.error("Failed to send message {} to dead letter queue: {}. Rejecting message.",
                    message.getId(), throwable.getMessage());
                return false;
            });
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
