package dev.mars.peegeeq.outbox.config;

import java.time.Duration;

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
    
    /**
     * Creates a default configuration suitable for most use cases
     */
    public static FilterErrorHandlingConfig defaultConfig() {
        return builder().build();
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
        
        public FilterErrorHandlingConfig build() {
            return new FilterErrorHandlingConfig(this);
        }
    }
}
