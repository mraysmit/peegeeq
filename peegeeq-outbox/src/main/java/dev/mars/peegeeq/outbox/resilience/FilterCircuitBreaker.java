package dev.mars.peegeeq.outbox.resilience;

import dev.mars.peegeeq.outbox.config.FilterErrorHandlingConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Circuit breaker specifically designed for message filters.
 * Prevents cascading failures when filters consistently fail.
 */
public class FilterCircuitBreaker {
    private static final Logger logger = LoggerFactory.getLogger(FilterCircuitBreaker.class);
    
    public enum State {
        CLOSED,    // Normal operation
        OPEN,      // Circuit is open, failing fast
        HALF_OPEN  // Testing if the circuit can be closed
    }
    
    private final String filterId;
    private final FilterErrorHandlingConfig config;
    
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger requestCount = new AtomicInteger(0);
    private final AtomicLong lastFailureTime = new AtomicLong(0);
    private final AtomicLong stateTransitionTime = new AtomicLong(System.currentTimeMillis());
    
    public FilterCircuitBreaker(String filterId, FilterErrorHandlingConfig config) {
        this.filterId = filterId;
        this.config = config;
    }
    
    /**
     * Checks if the circuit breaker allows the filter operation to proceed
     */
    public boolean allowRequest() {
        if (!config.isCircuitBreakerEnabled()) {
            return true;
        }
        
        State currentState = state.get();
        
        switch (currentState) {
            case CLOSED:
                return true;
                
            case OPEN:
                // Check if timeout has elapsed
                long timeSinceTransition = System.currentTimeMillis() - stateTransitionTime.get();
                if (timeSinceTransition >= config.getCircuitBreakerTimeout().toMillis()) {
                    // Try to transition to HALF_OPEN
                    if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                        stateTransitionTime.set(System.currentTimeMillis());
                        logger.info("Filter circuit breaker '{}' transitioning from OPEN to HALF_OPEN", filterId);
                        return true;
                    }
                }
                return false;
                
            case HALF_OPEN:
                // Allow one request to test the circuit
                return true;
                
            default:
                return true;
        }
    }
    
    /**
     * Records a successful filter operation
     */
    public void recordSuccess() {
        if (!config.isCircuitBreakerEnabled()) {
            return;
        }
        
        requestCount.incrementAndGet();
        
        State currentState = state.get();
        if (currentState == State.HALF_OPEN) {
            // Success in HALF_OPEN state - close the circuit
            if (state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
                reset();
                logger.info("Filter circuit breaker '{}' closed after successful test", filterId);
            }
        } else if (currentState == State.CLOSED) {
            // Reset failure count on success in CLOSED state
            failureCount.set(0);
        }
    }
    
    /**
     * Records a failed filter operation
     */
    public void recordFailure() {
        if (!config.isCircuitBreakerEnabled()) {
            return;
        }
        
        int currentFailures = failureCount.incrementAndGet();
        int currentRequests = requestCount.incrementAndGet();
        lastFailureTime.set(System.currentTimeMillis());
        
        State currentState = state.get();
        
        if (currentState == State.HALF_OPEN) {
            // Failure in HALF_OPEN state - reopen the circuit
            if (state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
                stateTransitionTime.set(System.currentTimeMillis());
                logger.warn("Filter circuit breaker '{}' reopened after failed test", filterId);
            }
        } else if (currentState == State.CLOSED) {
            // Check if we should open the circuit
            if (currentRequests >= config.getCircuitBreakerMinimumRequests() &&
                currentFailures >= config.getCircuitBreakerFailureThreshold()) {
                
                if (state.compareAndSet(State.CLOSED, State.OPEN)) {
                    stateTransitionTime.set(System.currentTimeMillis());
                    logger.warn("Filter circuit breaker '{}' opened after {} failures out of {} requests", 
                        filterId, currentFailures, currentRequests);
                }
            }
        }
    }
    
    /**
     * Resets the circuit breaker to initial state
     */
    public void reset() {
        state.set(State.CLOSED);
        failureCount.set(0);
        requestCount.set(0);
        lastFailureTime.set(0);
        stateTransitionTime.set(System.currentTimeMillis());
        logger.info("Filter circuit breaker '{}' reset", filterId);
    }
    
    /**
     * Gets the current state of the circuit breaker
     */
    public State getState() {
        return state.get();
    }
    
    /**
     * Gets circuit breaker metrics
     */
    public CircuitBreakerMetrics getMetrics() {
        return new CircuitBreakerMetrics(
            filterId,
            state.get(),
            failureCount.get(),
            requestCount.get(),
            lastFailureTime.get() > 0 ? Instant.ofEpochMilli(lastFailureTime.get()) : null,
            Instant.ofEpochMilli(stateTransitionTime.get())
        );
    }
    
    /**
     * Metrics snapshot for the circuit breaker
     */
    public static class CircuitBreakerMetrics {
        private final String filterId;
        private final State state;
        private final int failureCount;
        private final int requestCount;
        private final Instant lastFailureTime;
        private final Instant stateTransitionTime;
        
        public CircuitBreakerMetrics(String filterId, State state, int failureCount, 
                                   int requestCount, Instant lastFailureTime, 
                                   Instant stateTransitionTime) {
            this.filterId = filterId;
            this.state = state;
            this.failureCount = failureCount;
            this.requestCount = requestCount;
            this.lastFailureTime = lastFailureTime;
            this.stateTransitionTime = stateTransitionTime;
        }
        
        public String getFilterId() { return filterId; }
        public State getState() { return state; }
        public int getFailureCount() { return failureCount; }
        public int getRequestCount() { return requestCount; }
        public Instant getLastFailureTime() { return lastFailureTime; }
        public Instant getStateTransitionTime() { return stateTransitionTime; }
        
        public double getFailureRate() {
            return requestCount > 0 ? (double) failureCount / requestCount : 0.0;
        }
        
        @Override
        public String toString() {
            return String.format("FilterCircuitBreaker{id='%s', state=%s, failures=%d/%d (%.1f%%), lastFailure=%s}", 
                filterId, state, failureCount, requestCount, getFailureRate() * 100, lastFailureTime);
        }
    }
}
