package dev.mars.peegeeq.test.metrics;

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

import dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory.PerformanceProfile;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable snapshot of performance metrics for a single test execution.
 * 
 * This class captures all relevant performance data for a test run,
 * including timing information, success status, and additional metrics
 * collected during execution.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-18
 * @version 1.0
 */
public class PerformanceSnapshot {
    private final String testName;
    private final PerformanceProfile profile;
    private final Instant startTime;
    private final Instant endTime;
    private final Duration duration;
    private final boolean success;
    private final Map<String, Object> additionalMetrics;
    
    /**
     * Create a new PerformanceSnapshot.
     * 
     * @param testName the name of the test
     * @param profile the performance profile used
     * @param startTime when the test started
     * @param endTime when the test ended
     * @param duration the test duration
     * @param success whether the test was successful
     * @param additionalMetrics additional metrics collected during the test
     */
    public PerformanceSnapshot(String testName, PerformanceProfile profile, 
                             Instant startTime, Instant endTime, Duration duration,
                             boolean success, Map<String, Object> additionalMetrics) {
        this.testName = Objects.requireNonNull(testName, "testName cannot be null");
        this.profile = Objects.requireNonNull(profile, "profile cannot be null");
        this.startTime = Objects.requireNonNull(startTime, "startTime cannot be null");
        this.endTime = Objects.requireNonNull(endTime, "endTime cannot be null");
        this.duration = Objects.requireNonNull(duration, "duration cannot be null");
        this.success = success;
        this.additionalMetrics = additionalMetrics != null ? 
            new HashMap<>(additionalMetrics) : new HashMap<>();
    }
    
    /**
     * Get the test name.
     * 
     * @return the test name
     */
    public String getTestName() {
        return testName;
    }
    
    /**
     * Get the performance profile.
     * 
     * @return the performance profile
     */
    public PerformanceProfile getProfile() {
        return profile;
    }
    
    /**
     * Get the test start time.
     * 
     * @return the start time
     */
    public Instant getStartTime() {
        return startTime;
    }
    
    /**
     * Get the test end time.
     * 
     * @return the end time
     */
    public Instant getEndTime() {
        return endTime;
    }
    
    /**
     * Get the test duration.
     * 
     * @return the duration
     */
    public Duration getDuration() {
        return duration;
    }
    
    /**
     * Get the duration in milliseconds.
     * 
     * @return the duration in milliseconds
     */
    public long getDurationMs() {
        return duration.toMillis();
    }
    
    /**
     * Check if the test was successful.
     * 
     * @return true if successful, false otherwise
     */
    public boolean isSuccess() {
        return success;
    }
    
    /**
     * Get additional metrics collected during the test.
     * 
     * @return a copy of the additional metrics map
     */
    public Map<String, Object> getAdditionalMetrics() {
        return new HashMap<>(additionalMetrics);
    }
    
    /**
     * Get a specific additional metric.
     * 
     * @param key the metric key
     * @return the metric value, or null if not found
     */
    public Object getAdditionalMetric(String key) {
        return additionalMetrics.get(key);
    }
    
    /**
     * Get a specific additional metric as a double.
     * 
     * @param key the metric key
     * @param defaultValue the default value if not found or not a number
     * @return the metric value as a double
     */
    public double getAdditionalMetricAsDouble(String key, double defaultValue) {
        Object value = additionalMetrics.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return defaultValue;
    }
    
    /**
     * Get a specific additional metric as a long.
     * 
     * @param key the metric key
     * @param defaultValue the default value if not found or not a number
     * @return the metric value as a long
     */
    public long getAdditionalMetricAsLong(String key, long defaultValue) {
        Object value = additionalMetrics.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return defaultValue;
    }
    
    /**
     * Calculate throughput based on operations and duration.
     * 
     * @param operations the number of operations performed
     * @return throughput in operations per second
     */
    public double calculateThroughput(long operations) {
        if (duration.toMillis() == 0) {
            return 0.0;
        }
        return (operations * 1000.0) / duration.toMillis();
    }
    
    /**
     * Get throughput from additional metrics, or calculate it if operations are provided.
     * 
     * @return throughput in operations per second, or 0.0 if not available
     */
    public double getThroughput() {
        // First try to get throughput directly from additional metrics
        Object throughput = additionalMetrics.get("throughput");
        if (throughput instanceof Number) {
            return ((Number) throughput).doubleValue();
        }
        
        // Try to calculate from operations
        Object operations = additionalMetrics.get("operations");
        if (operations instanceof Number) {
            return calculateThroughput(((Number) operations).longValue());
        }
        
        return 0.0;
    }
    
    /**
     * Get latency metrics if available.
     * 
     * @return latency metrics map, or empty map if not available
     */
    public Map<String, Double> getLatencyMetrics() {
        Object latency = additionalMetrics.get("latency");
        if (latency instanceof Map) {
            try {
                return (Map<String, Double>) latency;
            } catch (ClassCastException e) {
                return new HashMap<>();
            }
        }
        return new HashMap<>();
    }
    
    /**
     * Create a summary string of this performance snapshot.
     * 
     * @return a human-readable summary
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("PerformanceSnapshot{");
        sb.append("test='").append(testName).append("'");
        sb.append(", profile=").append(profile.getDisplayName());
        sb.append(", duration=").append(duration.toMillis()).append("ms");
        sb.append(", success=").append(success);
        
        double throughput = getThroughput();
        if (throughput > 0) {
            sb.append(", throughput=").append(String.format("%.2f", throughput)).append(" ops/sec");
        }
        
        Map<String, Double> latency = getLatencyMetrics();
        if (!latency.isEmpty()) {
            sb.append(", latency=").append(latency);
        }
        
        sb.append("}");
        return sb.toString();
    }
    
    @Override
    public String toString() {
        return getSummary();
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PerformanceSnapshot that = (PerformanceSnapshot) o;
        return success == that.success &&
               Objects.equals(testName, that.testName) &&
               Objects.equals(profile, that.profile) &&
               Objects.equals(startTime, that.startTime) &&
               Objects.equals(endTime, that.endTime) &&
               Objects.equals(duration, that.duration) &&
               Objects.equals(additionalMetrics, that.additionalMetrics);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(testName, profile, startTime, endTime, duration, success, additionalMetrics);
    }
}
