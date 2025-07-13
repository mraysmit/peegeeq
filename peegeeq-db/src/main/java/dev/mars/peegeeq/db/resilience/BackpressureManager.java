package dev.mars.peegeeq.db.resilience;

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


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages backpressure for PeeGeeQ operations to prevent system overload.
 * 
 * This interface is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
public class BackpressureManager {
    private static final Logger logger = LoggerFactory.getLogger(BackpressureManager.class);
    
    private final Semaphore permits;
    private volatile int maxConcurrentOperations;
    private final Duration timeout;
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong rejectedRequests = new AtomicLong(0);
    private final AtomicLong timeoutRequests = new AtomicLong(0);
    private final AtomicReference<Instant> lastResetTime = new AtomicReference<>(Instant.now());
    
    // Adaptive rate limiting
    private final AtomicLong successfulOperations = new AtomicLong(0);
    private final AtomicLong failedOperations = new AtomicLong(0);
    private volatile double currentSuccessRate = 1.0;
    private volatile int adaptiveLimit;
    
    public BackpressureManager(int maxConcurrentOperations, Duration timeout) {
        this.maxConcurrentOperations = maxConcurrentOperations;
        this.timeout = timeout;
        this.permits = new Semaphore(maxConcurrentOperations, true);
        this.adaptiveLimit = maxConcurrentOperations;
        
        logger.info("Backpressure manager initialized with max concurrent operations: {}, timeout: {}", 
            maxConcurrentOperations, timeout);
    }
    
    /**
     * Executes an operation with backpressure control.
     */
    public <T> T execute(String operationName, BackpressureOperation<T> operation) throws BackpressureException {
        totalRequests.incrementAndGet();
        
        // Check if we should reject based on adaptive limiting
        if (shouldRejectRequest()) {
            rejectedRequests.incrementAndGet();
            throw new BackpressureException("Request rejected due to backpressure: " + operationName);
        }
        
        boolean acquired = false;
        try {
            // Try to acquire permit with timeout
            acquired = permits.tryAcquire(timeout.toMillis(), TimeUnit.MILLISECONDS);
            
            if (!acquired) {
                timeoutRequests.incrementAndGet();
                throw new BackpressureException("Request timed out waiting for permit: " + operationName);
            }
            
            // Execute the operation
            Instant startTime = Instant.now();
            try {
                T result = operation.execute();
                
                // Record successful operation
                successfulOperations.incrementAndGet();
                updateSuccessRate();
                
                Duration executionTime = Duration.between(startTime, Instant.now());
                logger.debug("Operation '{}' completed successfully in {}", operationName, executionTime);
                
                return result;
            } catch (Exception e) {
                // Record failed operation
                failedOperations.incrementAndGet();
                updateSuccessRate();
                
                logger.warn("Operation '{}' failed: {}", operationName, e.getMessage());
                throw new BackpressureException("Operation failed: " + operationName, e);
            }
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BackpressureException("Operation interrupted: " + operationName, e);
        } finally {
            if (acquired) {
                permits.release();
            }
        }
    }
    
    /**
     * Executes a void operation with backpressure control.
     */
    public void executeVoid(String operationName, BackpressureVoidOperation operation) throws BackpressureException {
        execute(operationName, () -> {
            operation.execute();
            return null;
        });
    }
    
    private boolean shouldRejectRequest() {
        // Adaptive rejection based on success rate and current load
        double currentLoad = (double) (maxConcurrentOperations - permits.availablePermits()) / maxConcurrentOperations;
        
        // If success rate is low and load is high, start rejecting requests
        if (currentSuccessRate < 0.5 && currentLoad > 0.8) {
            return Math.random() < (1.0 - currentSuccessRate) * currentLoad;
        }
        
        return false;
    }
    
    private void updateSuccessRate() {
        long successful = successfulOperations.get();
        long failed = failedOperations.get();
        long total = successful + failed;
        
        if (total > 0) {
            currentSuccessRate = (double) successful / total;
            
            // Adapt the effective limit based on success rate
            adaptiveLimit = (int) (maxConcurrentOperations * Math.max(0.1, currentSuccessRate));
            
            // Reset counters periodically to adapt to changing conditions
            if (total > 1000) {
                successfulOperations.set(successful / 2);
                failedOperations.set(failed / 2);
            }
        }
    }
    
    /**
     * Gets current backpressure metrics.
     */
    public BackpressureMetrics getMetrics() {
        return new BackpressureMetrics(
            maxConcurrentOperations,
            permits.availablePermits(),
            maxConcurrentOperations - permits.availablePermits(),
            totalRequests.get(),
            rejectedRequests.get(),
            timeoutRequests.get(),
            successfulOperations.get(),
            failedOperations.get(),
            currentSuccessRate,
            adaptiveLimit
        );
    }
    
    /**
     * Resets all metrics counters.
     */
    public void resetMetrics() {
        totalRequests.set(0);
        rejectedRequests.set(0);
        timeoutRequests.set(0);
        successfulOperations.set(0);
        failedOperations.set(0);
        currentSuccessRate = 1.0;
        adaptiveLimit = maxConcurrentOperations;
        lastResetTime.set(Instant.now());
        
        logger.info("Backpressure metrics reset");
    }
    
    /**
     * Adjusts the maximum concurrent operations limit.
     */
    public void adjustLimit(int newLimit) {
        if (newLimit <= 0) {
            throw new IllegalArgumentException("Limit must be positive");
        }

        int currentPermits = permits.availablePermits();
        int difference = newLimit - maxConcurrentOperations;

        if (difference > 0) {
            // Increase permits
            permits.release(difference);
            logger.info("Increased backpressure limit from {} to {}", maxConcurrentOperations, newLimit);
        } else if (difference < 0) {
            // Decrease permits (drain excess)
            try {
                permits.acquire(-difference);
                logger.info("Decreased backpressure limit from {} to {}", maxConcurrentOperations, newLimit);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("Failed to adjust backpressure limit", e);
            }
        }

        // Update the max concurrent operations field
        maxConcurrentOperations = newLimit;
        adaptiveLimit = newLimit;
    }
    
    /**
     * Functional interface for operations that return a value.
     */
    @FunctionalInterface
    public interface BackpressureOperation<T> {
        T execute() throws Exception;
    }
    
    /**
     * Functional interface for void operations.
     */
    @FunctionalInterface
    public interface BackpressureVoidOperation {
        void execute() throws Exception;
    }
    
    /**
     * Exception thrown when backpressure limits are exceeded.
     */
    public static class BackpressureException extends Exception {
        public BackpressureException(String message) {
            super(message);
        }
        
        public BackpressureException(String message, Throwable cause) {
            super(message, cause);
        }
    }
    
    /**
     * Backpressure metrics data class.
     */
    public static class BackpressureMetrics {
        private final int maxConcurrentOperations;
        private final int availablePermits;
        private final int activeOperations;
        private final long totalRequests;
        private final long rejectedRequests;
        private final long timeoutRequests;
        private final long successfulOperations;
        private final long failedOperations;
        private final double currentSuccessRate;
        private final int adaptiveLimit;
        
        public BackpressureMetrics(int maxConcurrentOperations, int availablePermits, int activeOperations,
                                 long totalRequests, long rejectedRequests, long timeoutRequests,
                                 long successfulOperations, long failedOperations, double currentSuccessRate,
                                 int adaptiveLimit) {
            this.maxConcurrentOperations = maxConcurrentOperations;
            this.availablePermits = availablePermits;
            this.activeOperations = activeOperations;
            this.totalRequests = totalRequests;
            this.rejectedRequests = rejectedRequests;
            this.timeoutRequests = timeoutRequests;
            this.successfulOperations = successfulOperations;
            this.failedOperations = failedOperations;
            this.currentSuccessRate = currentSuccessRate;
            this.adaptiveLimit = adaptiveLimit;
        }
        
        // Getters
        public int getMaxConcurrentOperations() { return maxConcurrentOperations; }
        public int getAvailablePermits() { return availablePermits; }
        public int getActiveOperations() { return activeOperations; }
        public long getTotalRequests() { return totalRequests; }
        public long getRejectedRequests() { return rejectedRequests; }
        public long getTimeoutRequests() { return timeoutRequests; }
        public long getSuccessfulOperations() { return successfulOperations; }
        public long getFailedOperations() { return failedOperations; }
        public double getCurrentSuccessRate() { return currentSuccessRate; }
        public int getAdaptiveLimit() { return adaptiveLimit; }
        
        public double getRejectionRate() {
            return totalRequests > 0 ? (double) rejectedRequests / totalRequests : 0.0;
        }
        
        public double getTimeoutRate() {
            return totalRequests > 0 ? (double) timeoutRequests / totalRequests : 0.0;
        }
        
        public double getUtilization() {
            return (double) activeOperations / maxConcurrentOperations;
        }
        
        @Override
        public String toString() {
            return "BackpressureMetrics{" +
                    "maxConcurrentOperations=" + maxConcurrentOperations +
                    ", availablePermits=" + availablePermits +
                    ", activeOperations=" + activeOperations +
                    ", totalRequests=" + totalRequests +
                    ", rejectedRequests=" + rejectedRequests +
                    ", timeoutRequests=" + timeoutRequests +
                    ", successfulOperations=" + successfulOperations +
                    ", failedOperations=" + failedOperations +
                    ", currentSuccessRate=" + String.format("%.2f", currentSuccessRate) +
                    ", adaptiveLimit=" + adaptiveLimit +
                    ", rejectionRate=" + String.format("%.2f%%", getRejectionRate() * 100) +
                    ", utilization=" + String.format("%.2f%%", getUtilization() * 100) +
                    '}';
        }
    }
}
