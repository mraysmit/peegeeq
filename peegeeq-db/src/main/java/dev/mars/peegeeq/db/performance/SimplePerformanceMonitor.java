package dev.mars.peegeeq.db.performance;

import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Simple performance monitoring utility for tracking PostgreSQL and Vert.x metrics
 * as recommended in the performance checklist:
 * 
 * - Query execution times
 * - Connection acquisition times
 * - Pool utilization
 * - Basic performance warnings
 */
public class SimplePerformanceMonitor {
    private static final Logger logger = LoggerFactory.getLogger(SimplePerformanceMonitor.class);
    
    private final LongAdder queryCount = new LongAdder();
    private final LongAdder connectionCount = new LongAdder();
    private final LongAdder connectionFailures = new LongAdder();
    private final AtomicLong totalQueryTime = new AtomicLong(0);
    private final AtomicLong totalConnectionTime = new AtomicLong(0);
    private final AtomicLong maxQueryTime = new AtomicLong(0);
    private final AtomicLong maxConnectionTime = new AtomicLong(0);
    
    /**
     * Records query execution time.
     * 
     * @param duration Query execution duration
     */
    public void recordQueryTime(Duration duration) {
        long millis = duration.toMillis();
        queryCount.increment();
        totalQueryTime.addAndGet(millis);
        
        // Update max query time
        long currentMax = maxQueryTime.get();
        while (millis > currentMax && !maxQueryTime.compareAndSet(currentMax, millis)) {
            currentMax = maxQueryTime.get();
        }
    }
    
    /**
     * Records connection acquisition time.
     * 
     * @param duration Connection acquisition duration
     */
    public void recordConnectionTime(Duration duration) {
        long millis = duration.toMillis();
        connectionCount.increment();
        totalConnectionTime.addAndGet(millis);
        
        // Update max connection time
        long currentMax = maxConnectionTime.get();
        while (millis > currentMax && !maxConnectionTime.compareAndSet(currentMax, millis)) {
            currentMax = maxConnectionTime.get();
        }
    }
    
    /**
     * Records a connection failure.
     */
    public void recordConnectionFailure() {
        connectionFailures.increment();
    }
    
    /**
     * Gets average query execution time.
     * 
     * @return Average query time in milliseconds
     */
    public double getAverageQueryTime() {
        long count = queryCount.sum();
        return count > 0 ? (double) totalQueryTime.get() / count : 0.0;
    }
    
    /**
     * Gets average connection acquisition time.
     * 
     * @return Average connection time in milliseconds
     */
    public double getAverageConnectionTime() {
        long count = connectionCount.sum();
        return count > 0 ? (double) totalConnectionTime.get() / count : 0.0;
    }
    
    /**
     * Gets maximum query execution time.
     * 
     * @return Maximum query time in milliseconds
     */
    public long getMaxQueryTime() {
        return maxQueryTime.get();
    }
    
    /**
     * Gets maximum connection acquisition time.
     * 
     * @return Maximum connection time in milliseconds
     */
    public long getMaxConnectionTime() {
        return maxConnectionTime.get();
    }
    
    /**
     * Gets total number of queries executed.
     * 
     * @return Total query count
     */
    public long getQueryCount() {
        return queryCount.sum();
    }
    
    /**
     * Gets total number of connections acquired.
     * 
     * @return Total connection count
     */
    public long getConnectionCount() {
        return connectionCount.sum();
    }
    
    /**
     * Gets total number of connection failures.
     * 
     * @return Total connection failure count
     */
    public long getConnectionFailures() {
        return connectionFailures.sum();
    }
    
    /**
     * Gets connection failure rate.
     * 
     * @return Failure rate as a percentage (0.0 to 1.0)
     */
    public double getConnectionFailureRate() {
        long total = connectionCount.sum() + connectionFailures.sum();
        return total > 0 ? (double) connectionFailures.sum() / total : 0.0;
    }
    
    /**
     * Logs current performance metrics.
     */
    public void logPerformanceMetrics() {
        logger.info("=== PostgreSQL Performance Metrics ===");
        logger.info("Queries: {} (avg: {}ms, max: {}ms)",
                   getQueryCount(), String.format("%.2f", getAverageQueryTime()), getMaxQueryTime());
        logger.info("Connections: {} (avg: {}ms, max: {}ms)",
                   getConnectionCount(), String.format("%.2f", getAverageConnectionTime()), getMaxConnectionTime());
        logger.info("Connection Failures: {} (rate: {}%)",
                   getConnectionFailures(), String.format("%.2f", getConnectionFailureRate() * 100));
        logger.info("=====================================");
    }
    
    /**
     * Starts periodic performance metric logging.
     * 
     * @param vertx Vertx instance
     * @param intervalMs Logging interval in milliseconds
     */
    public void startPeriodicLogging(Vertx vertx, long intervalMs) {
        vertx.setPeriodic(intervalMs, id -> {
            logPerformanceMetrics();
            checkPerformanceThresholds();
        });
        
        logger.info("Started periodic performance logging every {}ms", intervalMs);
    }
    
    /**
     * Checks performance thresholds and logs warnings.
     */
    private void checkPerformanceThresholds() {
        double avgQueryTime = getAverageQueryTime();
        double avgConnectionTime = getAverageConnectionTime();
        long maxQueryTime = getMaxQueryTime();
        long maxConnectionTime = getMaxConnectionTime();
        double failureRate = getConnectionFailureRate();
        
        // Check average query time threshold (warn if > 50ms)
        if (avgQueryTime > 50.0) {
            logger.warn("⚠️  High average query time: {}ms (threshold: 50ms)", String.format("%.2f", avgQueryTime));
        }

        // Check max query time threshold (warn if > 200ms)
        if (maxQueryTime > 200) {
            logger.warn("⚠️  High maximum query time: {}ms (threshold: 200ms)", maxQueryTime);
        }

        // Check connection acquisition time threshold (warn if > 20ms)
        if (avgConnectionTime > 20.0) {
            logger.warn("⚠️  High connection acquisition time: {}ms (threshold: 20ms)", String.format("%.2f", avgConnectionTime));
            logger.warn("Consider increasing pool size or optimizing connection management");
        }

        // Check max connection time threshold (warn if > 100ms)
        if (maxConnectionTime > 100) {
            logger.warn("⚠️  High maximum connection time: {}ms (threshold: 100ms)", maxConnectionTime);
        }

        // Check connection failure rate
        if (failureRate > 0.05) { // 5% failure rate
            logger.warn("⚠️  High connection failure rate: {}%", String.format("%.2f", failureRate * 100));
        }
    }
    
    /**
     * Resets all metrics.
     */
    public void reset() {
        queryCount.reset();
        connectionCount.reset();
        connectionFailures.reset();
        totalQueryTime.set(0);
        totalConnectionTime.set(0);
        maxQueryTime.set(0);
        maxConnectionTime.set(0);
        logger.info("Performance metrics reset");
    }
    
    /**
     * Creates a timing context for measuring operation duration.
     * 
     * @return TimingContext for measuring duration
     */
    public TimingContext startTiming() {
        return new TimingContext();
    }
    
    /**
     * Helper class for measuring operation duration.
     */
    public class TimingContext {
        private final Instant start = Instant.now();
        
        /**
         * Records the elapsed time as a query execution.
         */
        public void recordAsQuery() {
            Duration duration = Duration.between(start, Instant.now());
            recordQueryTime(duration);
        }
        
        /**
         * Records the elapsed time as a connection acquisition.
         */
        public void recordAsConnection() {
            Duration duration = Duration.between(start, Instant.now());
            recordConnectionTime(duration);
        }
        
        /**
         * Gets the elapsed duration.
         * 
         * @return Elapsed duration
         */
        public Duration getElapsed() {
            return Duration.between(start, Instant.now());
        }
    }
}
