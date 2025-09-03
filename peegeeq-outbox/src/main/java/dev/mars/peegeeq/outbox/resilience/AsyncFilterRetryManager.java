package dev.mars.peegeeq.outbox.resilience;

import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.outbox.config.FilterErrorHandlingConfig;
import dev.mars.peegeeq.outbox.deadletter.DeadLetterQueueManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
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
    private final ScheduledExecutorService retryScheduler;
    private final ExecutorService filterExecutor;
    private final DeadLetterQueueManager deadLetterQueueManager;
    
    // Metrics
    private final AtomicLong totalRetryAttempts = new AtomicLong(0);
    private final AtomicLong successfulRetries = new AtomicLong(0);
    private final AtomicLong failedRetries = new AtomicLong(0);
    private final AtomicInteger activeRetries = new AtomicInteger(0);
    
    public AsyncFilterRetryManager(String filterId, FilterErrorHandlingConfig config) {
        this.filterId = filterId;
        this.config = config;
        this.deadLetterQueueManager = new DeadLetterQueueManager(config);
        this.retryScheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "async-retry-scheduler-" + filterId);
            t.setDaemon(true);
            return t;
        });
        this.filterExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "async-filter-executor-" + filterId);
            t.setDaemon(true);
            return t;
        });
    }
    
    /**
     * Executes a filter operation with async retry logic
     */
    public <T> CompletableFuture<FilterResult> executeFilterWithRetry(
            Message<T> message,
            Predicate<Message<T>> filter,
            FilterCircuitBreaker circuitBreaker) {
        
        return executeFilterWithRetry(message, filter, circuitBreaker, 0, config.getInitialRetryDelay(), Instant.now());
    }
    
    private <T> CompletableFuture<FilterResult> executeFilterWithRetry(
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
            return CompletableFuture.completedFuture(
                FilterResult.rejected("Circuit breaker open", attemptNumber + 1, 
                    Duration.between(startTime, Instant.now())));
        }
        
        // Execute filter asynchronously
        CompletableFuture<FilterResult> filterFuture = CompletableFuture.supplyAsync(() -> {
            try {
                totalRetryAttempts.incrementAndGet();
                activeRetries.incrementAndGet();
                
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
                // Record failure
                circuitBreaker.recordFailure();
                
                // Classify the error
                FilterErrorHandlingConfig.ErrorClassification classification = config.classifyError(e);
                FilterErrorHandlingConfig.FilterErrorStrategy strategy = config.getStrategyForError(classification);
                
                logger.debug("Filter '{}' failed on attempt {} for message {} - Classification: {}, Strategy: {}, Error: {}", 
                    filterId, attemptNumber + 1, message.getId(), classification, strategy, e.getMessage());
                
                throw new FilterException(e, classification, strategy, attemptNumber + 1);
                
            } finally {
                activeRetries.decrementAndGet();
            }
        }, filterExecutor);
        
        // Handle the result or retry
        return filterFuture.handle((result, throwable) -> {
            if (throwable == null) {
                return CompletableFuture.completedFuture(result);
            }
            
            // Extract the filter exception
            FilterException filterException;
            if (throwable instanceof CompletionException && throwable.getCause() instanceof FilterException) {
                filterException = (FilterException) throwable.getCause();
            } else if (throwable instanceof FilterException) {
                filterException = (FilterException) throwable;
            } else {
                // Unexpected exception
                Duration totalTime = Duration.between(startTime, Instant.now());
                return CompletableFuture.completedFuture(
                    FilterResult.rejected("Unexpected error: " + throwable.getMessage(), 
                        attemptNumber + 1, totalTime));
            }
            
            return handleFilterException(message, filter, circuitBreaker, filterException, 
                attemptNumber, currentDelay, startTime);
        }).thenCompose(future -> future);
    }
    
    private <T> CompletableFuture<FilterResult> handleFilterException(
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
                return CompletableFuture.completedFuture(
                    FilterResult.rejected(originalError.getMessage(), attemptNumber + 1, totalTime));
                
            case RETRY_THEN_REJECT:
                // attemptNumber starts at 0, so maxRetries=2 means: initial(0) + retry(1) + retry(2) = 3 total attempts
                if (attemptNumber >= config.getMaxRetries()) {
                    failedRetries.incrementAndGet();
                    Duration finalTime = Duration.between(startTime, Instant.now());
                    logger.info("Filter '{}' rejecting message {} after {} attempts. Final error: {}",
                        filterId, message.getId(), attemptNumber + 1, originalError.getMessage());
                    return CompletableFuture.completedFuture(
                        FilterResult.rejected(originalError.getMessage(), attemptNumber + 1, finalTime));
                } else {
                    return scheduleAsyncRetry(message, filter, circuitBreaker, attemptNumber, currentDelay, startTime);
                }
                
            case RETRY_THEN_DEAD_LETTER:
                // attemptNumber starts at 0, so maxRetries=2 means: initial(0) + retry(1) + retry(2) = 3 total attempts
                if (attemptNumber >= config.getMaxRetries()) {
                    failedRetries.incrementAndGet();
                    Duration finalTime = Duration.between(startTime, Instant.now());
                    logger.warn("Filter '{}' sending message {} to dead letter queue after {} attempts. Final error: {}",
                        filterId, message.getId(), attemptNumber + 1, originalError.getMessage());

                    // Send to dead letter queue asynchronously
                    return sendToDeadLetterQueue(message, filterException, attemptNumber + 1, finalTime);
                } else {
                    return scheduleAsyncRetry(message, filter, circuitBreaker, attemptNumber, currentDelay, startTime);
                }

            case DEAD_LETTER_IMMEDIATELY:
                failedRetries.incrementAndGet();
                Duration dlqTime = Duration.between(startTime, Instant.now());
                logger.warn("Filter '{}' sending message {} to dead letter queue immediately due to {} error: {}",
                    filterId, message.getId(), filterException.getClassification().name().toLowerCase(),
                    originalError.getMessage());

                // Send to dead letter queue asynchronously
                return sendToDeadLetterQueue(message, filterException, attemptNumber + 1, dlqTime);
                
            default:
                failedRetries.incrementAndGet();
                Duration unknownTime = Duration.between(startTime, Instant.now());
                logger.warn("Unknown filter error strategy: {}. Rejecting message {}", strategy, message.getId());
                return CompletableFuture.completedFuture(
                    FilterResult.rejected("Unknown strategy: " + strategy, attemptNumber + 1, unknownTime));
        }
    }
    
    private <T> CompletableFuture<FilterResult> scheduleAsyncRetry(
            Message<T> message,
            Predicate<Message<T>> filter,
            FilterCircuitBreaker circuitBreaker,
            int attemptNumber,
            Duration currentDelay,
            Instant startTime) {
        
        CompletableFuture<FilterResult> retryFuture = new CompletableFuture<>();
        
        logger.debug("Filter '{}' scheduling async retry {} for message {} after delay of {}", 
            filterId, attemptNumber + 1, message.getId(), currentDelay);
        
        retryScheduler.schedule(() -> {
            try {
                // Calculate next delay with exponential backoff
                Duration nextDelay = calculateNextDelay(currentDelay);
                
                executeFilterWithRetry(message, filter, circuitBreaker, attemptNumber + 1, nextDelay, startTime)
                    .whenComplete((result, throwable) -> {
                        if (throwable != null) {
                            retryFuture.completeExceptionally(throwable);
                        } else {
                            retryFuture.complete(result);
                        }
                    });
            } catch (Exception e) {
                retryFuture.completeExceptionally(e);
            }
        }, currentDelay.toMillis(), TimeUnit.MILLISECONDS);
        
        return retryFuture;
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
    private <T> CompletableFuture<FilterResult> sendToDeadLetterQueue(
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
            .thenApply(result -> FilterResult.deadLetter(
                filterException.getOriginalException().getMessage(), attempts, totalTime))
            .exceptionally(throwable -> {
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

        retryScheduler.shutdown();
        filterExecutor.shutdown();

        try {
            if (!retryScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                retryScheduler.shutdownNow();
            }
            if (!filterExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                filterExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            retryScheduler.shutdownNow();
            filterExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

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
        private final int attemptNumber;
        private final Exception originalException;
        
        public FilterException(Exception originalException, 
                             FilterErrorHandlingConfig.ErrorClassification classification,
                             FilterErrorHandlingConfig.FilterErrorStrategy strategy,
                             int attemptNumber) {
            super(originalException);
            this.originalException = originalException;
            this.classification = classification;
            this.strategy = strategy;
            this.attemptNumber = attemptNumber;
        }
        
        public FilterErrorHandlingConfig.ErrorClassification getClassification() { return classification; }
        public FilterErrorHandlingConfig.FilterErrorStrategy getStrategy() { return strategy; }
        public int getAttemptNumber() { return attemptNumber; }
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
