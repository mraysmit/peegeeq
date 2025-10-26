package dev.mars.peegeeq.examples.springbootretry.service;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import dev.mars.peegeeq.examples.springbootretry.config.PeeGeeQRetryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Circuit Breaker Service.
 * 
 * Implements circuit breaker pattern to prevent cascading failures:
 * - CLOSED: Normal operation, requests pass through
 * - OPEN: Too many failures, requests are rejected
 * - HALF_OPEN: Testing if service has recovered
 * 
 * Key Principles:
 * 1. Track failure rate in a sliding window
 * 2. Open circuit when threshold is exceeded
 * 3. Automatically attempt to close after reset period
 * 4. Provide health indicators for monitoring
 */
@Service
public class CircuitBreakerService {
    
    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerService.class);
    
    public enum State {
        CLOSED,      // Normal operation
        OPEN,        // Circuit is open, rejecting requests
        HALF_OPEN    // Testing if service has recovered
    }
    
    private final PeeGeeQRetryProperties properties;
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicLong windowStartTime = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong circuitOpenedTime = new AtomicLong(0);
    
    public CircuitBreakerService(PeeGeeQRetryProperties properties) {
        this.properties = properties;
    }
    
    /**
     * Check if circuit breaker allows the request.
     */
    public boolean allowRequest() {
        State currentState = state.get();
        
        switch (currentState) {
            case CLOSED:
                return true;
                
            case OPEN:
                // Check if reset period has elapsed
                long openDuration = System.currentTimeMillis() - circuitOpenedTime.get();
                if (openDuration >= properties.getCircuitBreakerResetMs()) {
                    log.info("Circuit breaker transitioning to HALF_OPEN");
                    state.set(State.HALF_OPEN);
                    return true;
                }
                return false;
                
            case HALF_OPEN:
                // Allow limited requests to test recovery
                return true;
                
            default:
                return true;
        }
    }
    
    /**
     * Record a successful request.
     */
    public void recordSuccess() {
        resetWindowIfNeeded();
        successCount.incrementAndGet();
        
        State currentState = state.get();
        if (currentState == State.HALF_OPEN) {
            log.info("Circuit breaker transitioning to CLOSED after successful request");
            state.set(State.CLOSED);
            resetMetrics();
        }
    }
    
    /**
     * Record a failed request.
     */
    public void recordFailure() {
        resetWindowIfNeeded();
        int failures = failureCount.incrementAndGet();
        
        State currentState = state.get();
        
        if (currentState == State.HALF_OPEN) {
            log.warn("Circuit breaker transitioning to OPEN after failure in HALF_OPEN state");
            openCircuit();
            return;
        }
        
        if (currentState == State.CLOSED && failures >= properties.getCircuitBreakerThreshold()) {
            log.warn("Circuit breaker threshold exceeded: {} failures", failures);
            openCircuit();
        }
    }
    
    /**
     * Open the circuit breaker.
     */
    private void openCircuit() {
        state.set(State.OPEN);
        circuitOpenedTime.set(System.currentTimeMillis());
        log.error("⚠️ Circuit breaker OPENED - rejecting requests for {} ms", 
            properties.getCircuitBreakerResetMs());
    }
    
    /**
     * Reset the sliding window if needed.
     */
    private void resetWindowIfNeeded() {
        long currentTime = System.currentTimeMillis();
        long windowStart = windowStartTime.get();
        
        if (currentTime - windowStart >= properties.getCircuitBreakerWindowMs()) {
            windowStartTime.set(currentTime);
            failureCount.set(0);
            successCount.set(0);
        }
    }
    
    /**
     * Reset all metrics.
     */
    private void resetMetrics() {
        failureCount.set(0);
        successCount.set(0);
        windowStartTime.set(System.currentTimeMillis());
    }
    
    /**
     * Get current circuit breaker state.
     */
    public State getState() {
        return state.get();
    }
    
    /**
     * Get failure count in current window.
     */
    public int getFailureCount() {
        resetWindowIfNeeded();
        return failureCount.get();
    }
    
    /**
     * Get success count in current window.
     */
    public int getSuccessCount() {
        resetWindowIfNeeded();
        return successCount.get();
    }
    
    /**
     * Manually reset the circuit breaker.
     */
    public void reset() {
        log.info("Manually resetting circuit breaker");
        state.set(State.CLOSED);
        resetMetrics();
    }
}

