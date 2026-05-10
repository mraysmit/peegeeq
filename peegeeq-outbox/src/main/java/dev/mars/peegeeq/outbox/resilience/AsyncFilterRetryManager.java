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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * Advanced asynchronous retry manager for filter operations.
 * Provides non-blocking retry with exponential backoff while maintaining message ordering.
 */
public class AsyncFilterRetryManager {
    private static final Logger logger = LoggerFactory.getLogger(AsyncFilterRetryManager.class);
    
    private final String filterId;
    private final FilterErrorHandlingConfig config;
    private final Vertx vertx;
    private final DeadLetterQueueManager deadLetterQueueManager;
    
    // Metrics
    private final AtomicLong totalRetryAttempts = new AtomicLong(0);
    private final AtomicLong successfulRetries = new AtomicLong(0);
    private final AtomicLong failedRetries = new AtomicLong(0);
    private final AtomicInteger activeRetries = new AtomicInteger(0);
    
    public AsyncFilterRetryManager(String filterId, FilterErrorHandlingConfig config) {
        this(filterId, config, (Vertx) null, new DeadLetterQueueManager(config));
    }

    public AsyncFilterRetryManager(String filterId, FilterErrorHandlingConfig config, Vertx vertx) {
        this(filterId, config, vertx, new DeadLetterQueueManager(config));
    }

    // Visible for testing
    public AsyncFilterRetryManager(String filterId, FilterErrorHandlingConfig config, DeadLetterQueueManager deadLetterQueueManager) {
        this(filterId, config, (Vertx) null, deadLetterQueueManager);
    }

    public AsyncFilterRetryManager(String filterId, FilterErrorHandlingConfig config, Vertx vertx, DeadLetterQueueManager deadLetterQueueManager) {
        this.filterId = filterId;
        this.config = config;
        this.vertx = vertx;
        this.deadLetterQueueManager = deadLetterQueueManager;
    }
    
    /**
     * Executes a filter operation with async retry logic
     */
    public <T> Future<FilterResult> executeFilterWithRetry(
            Message<T> message,
            Predicate<Message<T>> filter,
            FilterCircuitBreaker circuitBreaker) {
        
        return executeFilterWithRetry(message, filter, circuitBreaker, 0, config.getInitialRetryDelay(), Instant.now());
    }
    
    private <T> Future<FilterResult> executeFilterWithRetry(
            Message<T> message,
            Predicate<Message<T>> filter,
            FilterCircuitBreaker circuitBreaker,
            int attemptNumber,
            Duration currentDelay,
            Instant startTime) {
        
        // Check circuit breaker
        if (!circuitBreaker.allowRequest()) {
            logger.debug("Filter circuit breaker '{}' is open, rejecting message {}", 
                filterId, message.getId());
            return Future.succeededFuture(
                FilterResult.rejected("Circuit breaker open", attemptNumber + 1, 
                    Duration.between(startTime, Instant.now())));
        }
        
        // Execute filter asynchronously via executeBlocking
        Future<FilterResult> filterFuture;
        if (vertx != null) {
            filterFuture = vertx.executeBlocking(() -> {
                totalRetryAttempts.incrementAndGet();
                activeRetries.incrementAndGet();
                try {
                    logger.debug("Filter '{}' executing attempt {} for message {}", 
                        filterId, attemptNumber + 1, message.getId());
                    
                    boolean result = filter.test(message);
                    
                    // Record success
                    circuitBreaker.recordSuccess();
                    successfulRetries.incrementAndGet();
                    
                    Duration totalTime = Duration.between(startTime, Instant.now());
                    logger.debug("Filter '{}' succeeded on attempt {} for message {} (total time: {})", 
                        filterId, attemptNumber + 1, message.getId(), totalTime);
                    
                    return FilterResult.accepted(result, attemptNumber + 1, totalTime);
                } catch (Exception e) {
                    circuitBreaker.recordFailure();
                    FilterErrorHandlingConfig.ErrorClassification classification = config.classifyError(e);
                    FilterErrorHandlingConfig.FilterErrorStrategy strategy = config.getStrategyForError(classification);
                    
                    logger.debug("Filter '{}' failed on attempt {} for message {} - Classification: {}, Strategy: {}, Error: {}", 
                        filterId, attemptNumber + 1, message.getId(), classification, strategy, e.getMessage());
                    
                    throw new FilterException(e, classification, strategy, attemptNumber + 1);
                } finally {
                    activeRetries.decrementAndGet();
                }
            });
        } else {
            // No Vertx execute inline (for tests without Vert.x context)
            try {
                totalRetryAttempts.incrementAndGet();
                activeRetries.incrementAndGet();
                
                boolean result = filter.test(message);
                circuitBreaker.recordSuccess();
                successfulRetries.incrementAndGet();
                
                Duration totalTime = Duration.between(startTime, Instant.now());
                filterFuture = Future.succeededFuture(FilterResult.accepted(result, attemptNumber + 1, totalTime));
            } catch (Exception e) {
                circuitBreaker.recordFailure();
                FilterErrorHandlingConfig.ErrorClassification classification = config.classifyError(e);
                FilterErrorHandlingConfig.FilterErrorStrategy strategy = config.getStrategyForError(classification);
                filterFuture = Future.failedFuture(new FilterException(e, classification, strategy, attemptNumber + 1));
            } finally {
                activeRetries.decrementAndGet();
            }
        }
        
        // Handle the result or retry
        return filterFuture.compose(
            result -> Future.succeededFuture(result),
            throwable -> {
                // Extract the filter exception
                FilterException filterException;
                if (throwable instanceof FilterException) {
                    filterException = (FilterException) throwable;
                } else {
                    // Unexpected exception
                    Duration totalTime = Duration.between(startTime, Instant.now());
                    return Future.succeededFuture(
                        FilterResult.rejected("Unexpected error: " + throwable.getMessage(), 
                            attemptNumber + 1, totalTime));
                }
                
                return handleFilterException(message, filter, circuitBreaker, filterException, 
                    attemptNumber, currentDelay, startTime);
            }
        );
    }
    
    private <T> Future<FilterResult> handleFilterException(
            Message<T> message,
            Predicate<Message<T>> filter,
            FilterCircuitBreaker circuitBreaker,
            FilterException filterException,
            int attemptNumber,
            Duration currentDelay,
            Instant startTime) {
        
        FilterErrorHandlingConfig.FilterErrorStrategy strategy = filterException.getStrategy();
        Exception originalError = filterException.getOriginalException();
        
        switch (strategy) {
            case REJECT_IMMEDIATELY:
                failedRetries.incrementAndGet();
                Duration totalTime = Duration.between(startTime, Instant.now());
                logger.info("Filter '{}' rejecting message {} immediately due to {} error: {}",
                    filterId, message.getId(), filterException.getClassification().name().toLowerCase(),
                    originalError.getMessage());
                return Future.succeededFuture(
                    FilterResult.rejected(originalError.getMessage(), attemptNumber + 1, totalTime));
                
            case RETRY_THEN_REJECT:
                // attemptNumber starts at 0, so maxRetries=2 means: initial(0) + retry(1) + retry(2) = 3 total attempts
                if (attemptNumber >= config.getMaxRetries()) {
                    failedRetries.incrementAndGet();
                    Duration finalTime = Duration.between(startTime, Instant.now());
                    logger.info("Filter '{}' rejecting message {} after {} attempts. Final error: {}",
                        filterId, message.getId(), attemptNumber + 1, originalError.getMessage());
                    return Future.succeededFuture(
                        FilterResult.rejected(originalError.getMessage(), attemptNumber + 1, finalTime));
                } else {
                    return scheduleAsyncRetry(message, filter, circuitBreaker, attemptNumber, currentDelay, startTime);
                }
                
            case RETRY_THEN_DEAD_LETTER:
                // attemptNumber starts at 0, so maxRetries=2 means: initial(0) + retry(1) + retry(2) = 3 total attempts
                if (attemptNumber >= config.getMaxRetries()) {
                    failedRetries.incrementAndGet();
                    Duration finalTime = Duration.between(startTime, Instant.now());
                    logger.error("Filter '{}' sending message {} to dead letter queue after {} attempts. Final error: {}",
                        filterId, message.getId(), attemptNumber + 1, originalError.getMessage());

                    // Send to dead letter queue asynchronously
                    return sendToDeadLetterQueue(message, filterException, attemptNumber + 1, finalTime);
                } else {
                    return scheduleAsyncRetry(message, filter, circuitBreaker, attemptNumber, currentDelay, startTime);
                }

            case DEAD_LETTER_IMMEDIATELY:
                failedRetries.incrementAndGet();
                Duration dlqTime = Duration.between(startTime, Instant.now());
                logger.error("Filter '{}' sending message {} to dead letter queue immediately due to {} error: {}",
                    filterId, message.getId(), filterException.getClassification().name().toLowerCase(),
                    originalError.getMessage());

                // Send to dead letter queue asynchronously
                return sendToDeadLetterQueue(message, filterException, attemptNumber + 1, dlqTime);
                
            default:
                failedRetries.incrementAndGet();
                Duration unknownTime = Duration.between(startTime, Instant.now());
                logger.error("Unknown filter error strategy: {}. Rejecting message {}", strategy, message.getId());
                return Future.succeededFuture(
                    FilterResult.rejected("Unknown strategy: " + strategy, attemptNumber + 1, unknownTime));
        }
    }
    
    private <T> Future<FilterResult> scheduleAsyncRetry(
            Message<T> message,
            Predicate<Message<T>> filter,
            FilterCircuitBreaker circuitBreaker,
            int attemptNumber,
            Duration currentDelay,
            Instant startTime) {
        
        Promise<FilterResult> promise = Promise.promise();
        
        logger.debug("Filter '{}' scheduling async retry {} for message {} after delay of {}", 
            filterId, attemptNumber + 1, message.getId(), currentDelay);
        
        if (vertx != null) {
            vertx.setTimer(currentDelay.toMillis(), id -> {
                try {
                    // Calculate next delay with exponential backoff
                    Duration nextDelay = calculateNextDelay(currentDelay);
                    
                    executeFilterWithRetry(message, filter, circuitBreaker, attemptNumber + 1, nextDelay, startTime)
                        .onSuccess(promise::complete)
                        .onFailure(promise::fail);
                } catch (Exception e) {
                    promise.fail(e);
                }
            });
        } else {
            // No Vertx execute inline after delay (for tests without Vert.x context)
            try {
                Duration nextDelay = calculateNextDelay(currentDelay);
                
                executeFilterWithRetry(message, filter, circuitBreaker, attemptNumber + 1, nextDelay, startTime)
                    .onSuccess(promise::complete)
                    .onFailure(promise::fail);
            } catch (Exception e) {
                promise.fail(e);
            }
        }
        
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

    /**
     * Sends a message to the dead letter queue
     */
    private <T> Future<FilterResult> sendToDeadLetterQueue(
            Message<T> message,
            FilterException filterException,
            int attempts,
            Duration totalTime) {

        return deadLetterQueueManager.sendToDeadLetter(
                message,
                filterId,
                filterException.getOriginalException().getMessage(),
                attempts,
                filterException.getClassification(),
                filterException.getOriginalException()
            )
            .map(v -> FilterResult.deadLetter(
                filterException.getOriginalException().getMessage(), attempts, totalTime))
            .otherwise(throwable -> {
                logger.error("Failed to send message {} to dead letter queue: {}",
                    message.getId(), throwable.getMessage());
                // Even if DLQ fails, we still consider the message as dead lettered
                // to prevent infinite retry loops
                return FilterResult.deadLetter(
                    "DLQ failed: " + throwable.getMessage(), attempts, totalTime);
            });
    }

    /**
     * Gets current retry metrics
     */
    public RetryMetrics getMetrics() {
        return new RetryMetrics(
            filterId,
            totalRetryAttempts.get(),
            successfulRetries.get(),
            failedRetries.get(),
            activeRetries.get()
        );
    }
    
    /**
     * Shuts down the retry manager
     */
    public void shutdown() {
        logger.info("Shutting down async filter retry manager '{}'", filterId);

        // Close dead letter queue manager
        deadLetterQueueManager.close();
    }
    
    // Inner classes for results and exceptions
    public static class FilterResult {
        public enum Status { ACCEPTED, REJECTED, DEAD_LETTER }
        
        private final Status status;
        private final boolean accepted;
        private final String reason;
        private final int attempts;
        private final Duration totalTime;
        
        private FilterResult(Status status, boolean accepted, String reason, int attempts, Duration totalTime) {
            this.status = status;
            this.accepted = accepted;
            this.reason = reason;
            this.attempts = attempts;
            this.totalTime = totalTime;
        }
        
        public static FilterResult accepted(boolean result, int attempts, Duration totalTime) {
            return new FilterResult(Status.ACCEPTED, result, null, attempts, totalTime);
        }
        
        public static FilterResult rejected(String reason, int attempts, Duration totalTime) {
            return new FilterResult(Status.REJECTED, false, reason, attempts, totalTime);
        }
        
        public static FilterResult deadLetter(String reason, int attempts, Duration totalTime) {
            return new FilterResult(Status.DEAD_LETTER, false, reason, attempts, totalTime);
        }
        
        // Getters
        public Status getStatus() { return status; }
        public boolean isAccepted() { return accepted; }
        public String getReason() { return reason; }
        public int getAttempts() { return attempts; }
        public Duration getTotalTime() { return totalTime; }
        
        @Override
        public String toString() {
            return String.format("FilterResult{status=%s, accepted=%s, attempts=%d, totalTime=%s, reason='%s'}", 
                status, accepted, attempts, totalTime, reason);
        }
    }
    
    private static class FilterException extends RuntimeException {
        private final FilterErrorHandlingConfig.ErrorClassification classification;
        private final FilterErrorHandlingConfig.FilterErrorStrategy strategy;
        private final Exception originalException;
        
        public FilterException(Exception originalException, 
                             FilterErrorHandlingConfig.ErrorClassification classification,
                             FilterErrorHandlingConfig.FilterErrorStrategy strategy,
                             int attemptNumber) {
            super(originalException);
            this.originalException = originalException;
            this.classification = classification;
            this.strategy = strategy;
        }
        
        public FilterErrorHandlingConfig.ErrorClassification getClassification() { return classification; }
        public FilterErrorHandlingConfig.FilterErrorStrategy getStrategy() { return strategy; }
        public Exception getOriginalException() { return originalException; }
    }
    
    public static class RetryMetrics {
        private final String filterId;
        private final long totalAttempts;
        private final long successfulRetries;
        private final long failedRetries;
        private final int activeRetries;
        
        public RetryMetrics(String filterId, long totalAttempts, long successfulRetries, 
                          long failedRetries, int activeRetries) {
            this.filterId = filterId;
            this.totalAttempts = totalAttempts;
            this.successfulRetries = successfulRetries;
            this.failedRetries = failedRetries;
            this.activeRetries = activeRetries;
        }
        
        public String getFilterId() { return filterId; }
        public long getTotalAttempts() { return totalAttempts; }
        public long getSuccessfulRetries() { return successfulRetries; }
        public long getFailedRetries() { return failedRetries; }
        public int getActiveRetries() { return activeRetries; }
        
        public double getSuccessRate() {
            return totalAttempts > 0 ? (double) successfulRetries / totalAttempts : 0.0;
        }
        
        @Override
        public String toString() {
            return String.format("RetryMetrics{filterId='%s', total=%d, success=%d, failed=%d, active=%d, successRate=%.2f%%}", 
                filterId, totalAttempts, successfulRetries, failedRetries, activeRetries, getSuccessRate() * 100);
        }
    }
}
