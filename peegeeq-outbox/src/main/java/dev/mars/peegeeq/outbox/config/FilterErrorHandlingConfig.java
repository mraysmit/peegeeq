package dev.mars.peegeeq.outbox.config;

import java.time.Duration;
import java.util.Set;

/**
 * Configuration for handling filter errors in consumer groups.
 * Provides fine-grained control over how different types of filter failures are handled.
 */
public class FilterErrorHandlingConfig {
    
    /**
     * Strategy for handling filter errors
     */
    public enum FilterErrorStrategy {
        /** Reject message immediately, no retries */
        REJECT_IMMEDIATELY,
        /** Retry with exponential backoff, then reject */
        RETRY_THEN_REJECT,
        /** Retry with exponential backoff, then send to dead letter queue */
        RETRY_THEN_DEAD_LETTER,
        /** Send to dead letter queue immediately */
        DEAD_LETTER_IMMEDIATELY
    }
    
    /**
     * How to classify filter errors
     */
    public enum ErrorClassification {
        /** Temporary error that should be retried */
        TRANSIENT,
        /** Permanent error that should not be retried */
        PERMANENT,
        /** Unknown error type - use default strategy */
        UNKNOWN
    }
    
    private final FilterErrorStrategy defaultStrategy;
    private final int maxRetries;
    private final Duration initialRetryDelay;
    private final Duration maxRetryDelay;
    private final double retryBackoffMultiplier;
    
    // Circuit breaker configuration
    private final boolean circuitBreakerEnabled;
    private final int circuitBreakerFailureThreshold;
    private final Duration circuitBreakerTimeout;
    private final int circuitBreakerMinimumRequests;
    
    // Error classification rules
    private final Set<String> transientErrorPatterns;
    private final Set<String> permanentErrorPatterns;
    private final Set<Class<? extends Exception>> transientExceptionTypes;
    private final Set<Class<? extends Exception>> permanentExceptionTypes;
    
    // Dead letter queue configuration
    private final boolean deadLetterQueueEnabled;
    private final String deadLetterQueueTopic;
    
    private FilterErrorHandlingConfig(Builder builder) {
        this.defaultStrategy = builder.defaultStrategy;
        this.maxRetries = builder.maxRetries;
        this.initialRetryDelay = builder.initialRetryDelay;
        this.maxRetryDelay = builder.maxRetryDelay;
        this.retryBackoffMultiplier = builder.retryBackoffMultiplier;
        this.circuitBreakerEnabled = builder.circuitBreakerEnabled;
        this.circuitBreakerFailureThreshold = builder.circuitBreakerFailureThreshold;
        this.circuitBreakerTimeout = builder.circuitBreakerTimeout;
        this.circuitBreakerMinimumRequests = builder.circuitBreakerMinimumRequests;
        this.transientErrorPatterns = Set.copyOf(builder.transientErrorPatterns);
        this.permanentErrorPatterns = Set.copyOf(builder.permanentErrorPatterns);
        this.transientExceptionTypes = Set.copyOf(builder.transientExceptionTypes);
        this.permanentExceptionTypes = Set.copyOf(builder.permanentExceptionTypes);
        this.deadLetterQueueEnabled = builder.deadLetterQueueEnabled;
        this.deadLetterQueueTopic = builder.deadLetterQueueTopic;
    }
    
    /**
     * Classifies an exception based on configured rules
     */
    public ErrorClassification classifyError(Exception exception) {
        String message = exception.getMessage();
        Class<? extends Exception> exceptionType = exception.getClass();
        
        // Check permanent patterns first (more specific)
        if (permanentExceptionTypes.contains(exceptionType) ||
            (message != null && permanentErrorPatterns.stream().anyMatch(message::contains))) {
            return ErrorClassification.PERMANENT;
        }
        
        // Check transient patterns
        if (transientExceptionTypes.contains(exceptionType) ||
            (message != null && transientErrorPatterns.stream().anyMatch(message::contains))) {
            return ErrorClassification.TRANSIENT;
        }
        
        return ErrorClassification.UNKNOWN;
    }
    
    /**
     * Determines the strategy to use for a given error classification
     */
    public FilterErrorStrategy getStrategyForError(ErrorClassification classification) {
        return switch (classification) {
            case TRANSIENT -> {
                // For transient errors, use the default strategy if it involves retries,
                // otherwise fall back to RETRY_THEN_REJECT
                if (defaultStrategy == FilterErrorStrategy.RETRY_THEN_DEAD_LETTER ||
                    defaultStrategy == FilterErrorStrategy.RETRY_THEN_REJECT) {
                    yield defaultStrategy;
                } else {
                    yield FilterErrorStrategy.RETRY_THEN_REJECT;
                }
            }
            case PERMANENT -> FilterErrorStrategy.REJECT_IMMEDIATELY;
            case UNKNOWN -> defaultStrategy;
        };
    }
    
    // Getters
    public FilterErrorStrategy getDefaultStrategy() { return defaultStrategy; }
    public int getMaxRetries() { return maxRetries; }
    public Duration getInitialRetryDelay() { return initialRetryDelay; }
    public Duration getMaxRetryDelay() { return maxRetryDelay; }
    public double getRetryBackoffMultiplier() { return retryBackoffMultiplier; }
    public boolean isCircuitBreakerEnabled() { return circuitBreakerEnabled; }
    public int getCircuitBreakerFailureThreshold() { return circuitBreakerFailureThreshold; }
    public Duration getCircuitBreakerTimeout() { return circuitBreakerTimeout; }
    public int getCircuitBreakerMinimumRequests() { return circuitBreakerMinimumRequests; }
    public boolean isDeadLetterQueueEnabled() { return deadLetterQueueEnabled; }
    public String getDeadLetterQueueTopic() { return deadLetterQueueTopic; }
    
    /**
     * Creates a default configuration suitable for most use cases
     */
    public static FilterErrorHandlingConfig defaultConfig() {
        return builder().build();
    }
    
    /**
     * Creates a configuration optimized for testing scenarios
     */
    public static FilterErrorHandlingConfig testingConfig() {
        return builder()
            .defaultStrategy(FilterErrorStrategy.REJECT_IMMEDIATELY)
            .maxRetries(0)
            .circuitBreakerEnabled(false)
            .addTransientErrorPattern("INTENTIONAL TEST FAILURE")
            .addTransientErrorPattern("Simulated")
            .build();
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private FilterErrorStrategy defaultStrategy = FilterErrorStrategy.RETRY_THEN_REJECT;
        private int maxRetries = 3;
        private Duration initialRetryDelay = Duration.ofMillis(100);
        private Duration maxRetryDelay = Duration.ofSeconds(30);
        private double retryBackoffMultiplier = 2.0;
        private boolean circuitBreakerEnabled = true;
        private int circuitBreakerFailureThreshold = 5;
        private Duration circuitBreakerTimeout = Duration.ofMinutes(1);
        private int circuitBreakerMinimumRequests = 10;
        private Set<String> transientErrorPatterns = Set.of(
            "timeout", "connection", "network", "temporary", "retry"
        );
        private Set<String> permanentErrorPatterns = Set.of(
            "invalid", "malformed", "unauthorized", "forbidden", "not found"
        );
        private Set<Class<? extends Exception>> transientExceptionTypes = Set.of(
            java.net.SocketTimeoutException.class,
            java.net.ConnectException.class,
            java.util.concurrent.TimeoutException.class
        );
        private Set<Class<? extends Exception>> permanentExceptionTypes = Set.of(
            IllegalArgumentException.class,
            SecurityException.class,
            java.nio.file.NoSuchFileException.class
        );
        private boolean deadLetterQueueEnabled = true;
        private String deadLetterQueueTopic = "filter-errors";
        
        public Builder defaultStrategy(FilterErrorStrategy strategy) {
            this.defaultStrategy = strategy;
            return this;
        }
        
        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }
        
        public Builder initialRetryDelay(Duration delay) {
            this.initialRetryDelay = delay;
            return this;
        }
        
        public Builder maxRetryDelay(Duration delay) {
            this.maxRetryDelay = delay;
            return this;
        }
        
        public Builder retryBackoffMultiplier(double multiplier) {
            this.retryBackoffMultiplier = multiplier;
            return this;
        }
        
        public Builder circuitBreakerEnabled(boolean enabled) {
            this.circuitBreakerEnabled = enabled;
            return this;
        }
        
        public Builder circuitBreakerFailureThreshold(int threshold) {
            this.circuitBreakerFailureThreshold = threshold;
            return this;
        }
        
        public Builder circuitBreakerTimeout(Duration timeout) {
            this.circuitBreakerTimeout = timeout;
            return this;
        }
        
        public Builder circuitBreakerMinimumRequests(int minimum) {
            this.circuitBreakerMinimumRequests = minimum;
            return this;
        }
        
        public Builder addTransientErrorPattern(String pattern) {
            this.transientErrorPatterns = Set.copyOf(
                java.util.stream.Stream.concat(
                    transientErrorPatterns.stream(),
                    java.util.stream.Stream.of(pattern)
                ).collect(java.util.stream.Collectors.toSet())
            );
            return this;
        }
        
        public Builder addPermanentErrorPattern(String pattern) {
            this.permanentErrorPatterns = Set.copyOf(
                java.util.stream.Stream.concat(
                    permanentErrorPatterns.stream(),
                    java.util.stream.Stream.of(pattern)
                ).collect(java.util.stream.Collectors.toSet())
            );
            return this;
        }

        public Builder addTransientExceptionType(Class<? extends Exception> exceptionType) {
            this.transientExceptionTypes = Set.copyOf(
                java.util.stream.Stream.concat(
                    transientExceptionTypes.stream(),
                    java.util.stream.Stream.of(exceptionType)
                ).collect(java.util.stream.Collectors.toSet())
            );
            return this;
        }

        public Builder addPermanentExceptionType(Class<? extends Exception> exceptionType) {
            this.permanentExceptionTypes = Set.copyOf(
                java.util.stream.Stream.concat(
                    permanentExceptionTypes.stream(),
                    java.util.stream.Stream.of(exceptionType)
                ).collect(java.util.stream.Collectors.toSet())
            );
            return this;
        }
        
        public Builder deadLetterQueueEnabled(boolean enabled) {
            this.deadLetterQueueEnabled = enabled;
            return this;
        }
        
        public Builder deadLetterQueueTopic(String topic) {
            this.deadLetterQueueTopic = topic;
            return this;
        }
        
        public FilterErrorHandlingConfig build() {
            return new FilterErrorHandlingConfig(this);
        }
    }
}
