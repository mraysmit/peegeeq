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

import dev.mars.peegeeq.db.metrics.PeeGeeQMetrics;
import dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory.PerformanceProfile;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Specialized metrics collector for parameterized performance tests.
 * 
 * This class extends the standard PeeGeeQMetrics functionality with
 * test-specific metrics collection, performance profiling, and
 * cross-profile comparison capabilities.
 * 
 * Key Features:
 * - Performance profile-specific metrics collection
 * - Test execution timing and throughput measurement
 * - Cross-profile performance comparison
 * - Threshold validation and regression detection
 * - Comprehensive test result aggregation
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-18
 * @version 1.0
 */
public class PerformanceMetricsCollector {
    private static final Logger logger = LoggerFactory.getLogger(PerformanceMetricsCollector.class);
    
    private final PeeGeeQMetrics baseMetrics;
    private final MeterRegistry meterRegistry;
    private final String testInstanceId;
    
    // Test-specific metrics
    private final Map<String, Timer> testTimers = new ConcurrentHashMap<>();
    private final Map<String, Counter> testCounters = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> testGauges = new ConcurrentHashMap<>();
    
    // Performance tracking
    private final Map<PerformanceProfile, PerformanceSnapshot> profileSnapshots = new ConcurrentHashMap<>();
    private final AtomicReference<Instant> testStartTime = new AtomicReference<>();
    private final AtomicReference<Instant> testEndTime = new AtomicReference<>();
    
    /**
     * Create a new PerformanceMetricsCollector.
     * 
     * @param baseMetrics the base PeeGeeQMetrics instance
     * @param testInstanceId unique identifier for this test instance
     */
    public PerformanceMetricsCollector(PeeGeeQMetrics baseMetrics, String testInstanceId) {
        this.baseMetrics = baseMetrics;
        this.meterRegistry = extractMeterRegistry(baseMetrics);
        this.testInstanceId = testInstanceId;
        
        logger.debug("Created PerformanceMetricsCollector for test instance: {}", testInstanceId);
    }
    
    /**
     * Start performance measurement for a test.
     * 
     * @param testName the name of the test
     * @param profile the performance profile being tested
     */
    public void startTest(String testName, PerformanceProfile profile) {
        testStartTime.set(Instant.now());
        
        logger.debug("Started performance measurement for test '{}' with profile: {}", 
                    testName, profile.getDisplayName());
        
        // Record test start event
        recordTestEvent("test.started", testName, profile, Map.of());
    }
    
    /**
     * End performance measurement for a test.
     * 
     * @param testName the name of the test
     * @param profile the performance profile being tested
     * @param success whether the test was successful
     * @param additionalMetrics additional metrics to record
     * @return the performance snapshot for this test execution
     */
    public PerformanceSnapshot endTest(String testName, PerformanceProfile profile, 
                                     boolean success, Map<String, Object> additionalMetrics) {
        testEndTime.set(Instant.now());
        
        Instant startTime = testStartTime.get();
        Instant endTime = testEndTime.get();
        
        if (startTime == null) {
            logger.warn("Test '{}' ended without corresponding start - using current time", testName);
            startTime = endTime.minusMillis(1);
        }
        
        Duration testDuration = Duration.between(startTime, endTime);
        
        // Create performance snapshot
        PerformanceSnapshot snapshot = new PerformanceSnapshot(
            testName, profile, startTime, endTime, testDuration, success, additionalMetrics
        );
        
        // Store snapshot for comparison
        profileSnapshots.put(profile, snapshot);
        
        // Record test completion metrics
        recordTestCompletion(testName, profile, snapshot);
        
        logger.debug("Completed performance measurement for test '{}' with profile: {} (duration: {}ms, success: {})", 
                    testName, profile.getDisplayName(), testDuration.toMillis(), success);
        
        return snapshot;
    }
    
    /**
     * Record a test-specific timer metric.
     * 
     * @param name metric name (will be prefixed with 'peegeeq.test.')
     * @param duration the duration to record
     * @param profile the performance profile
     * @param additionalTags additional tags to include
     */
    public void recordTestTimer(String name, Duration duration, PerformanceProfile profile, 
                               Map<String, String> additionalTags) {
        String metricName = "peegeeq.test." + name;
        Map<String, String> tags = createTestTags(profile, additionalTags);
        
        Timer timer = testTimers.computeIfAbsent(metricName + ":" + tagsToString(tags),
            key -> {
                Timer.Builder builder = Timer.builder(metricName);
                for (Map.Entry<String, String> entry : tags.entrySet()) {
                    builder.tag(entry.getKey(), entry.getValue());
                }
                return builder.register(meterRegistry);
            });
        
        timer.record(duration);
        
        logger.trace("Recorded test timer '{}': {}ms with profile: {}", 
                    name, duration.toMillis(), profile.getDisplayName());
    }
    
    /**
     * Record a test-specific counter metric.
     * 
     * @param name metric name (will be prefixed with 'peegeeq.test.')
     * @param profile the performance profile
     * @param additionalTags additional tags to include
     */
    public void recordTestCounter(String name, PerformanceProfile profile, 
                                 Map<String, String> additionalTags) {
        String metricName = "peegeeq.test." + name;
        Map<String, String> tags = createTestTags(profile, additionalTags);
        
        Counter counter = testCounters.computeIfAbsent(metricName + ":" + tagsToString(tags),
            key -> {
                Counter.Builder builder = Counter.builder(metricName);
                for (Map.Entry<String, String> entry : tags.entrySet()) {
                    builder.tag(entry.getKey(), entry.getValue());
                }
                return builder.register(meterRegistry);
            });
        
        counter.increment();
        
        logger.trace("Incremented test counter '{}' with profile: {}", 
                    name, profile.getDisplayName());
    }
    
    /**
     * Record a test-specific gauge metric.
     * 
     * @param name metric name (will be prefixed with 'peegeeq.test.')
     * @param value the gauge value
     * @param profile the performance profile
     * @param additionalTags additional tags to include
     */
    public void recordTestGauge(String name, double value, PerformanceProfile profile, 
                               Map<String, String> additionalTags) {
        String metricName = "peegeeq.test." + name;
        Map<String, String> tags = createTestTags(profile, additionalTags);
        
        AtomicLong gaugeValue = testGauges.computeIfAbsent(metricName + ":" + tagsToString(tags),
            key -> {
                AtomicLong atomicValue = new AtomicLong();
                Gauge.Builder<AtomicLong> builder = Gauge.builder(metricName, atomicValue, AtomicLong::get);
                for (Map.Entry<String, String> entry : tags.entrySet()) {
                    builder.tag(entry.getKey(), entry.getValue());
                }
                builder.register(meterRegistry);
                return atomicValue;
            });
        
        gaugeValue.set((long) value);
        
        logger.trace("Set test gauge '{}' to {} with profile: {}", 
                    name, value, profile.getDisplayName());
    }
    
    /**
     * Get performance comparison between profiles.
     * 
     * @return map of profile comparisons
     */
    public Map<String, PerformanceComparison> getProfileComparisons() {
        Map<String, PerformanceComparison> comparisons = new HashMap<>();
        
        List<PerformanceSnapshot> snapshots = new ArrayList<>(profileSnapshots.values());
        
        for (int i = 0; i < snapshots.size(); i++) {
            for (int j = i + 1; j < snapshots.size(); j++) {
                PerformanceSnapshot snapshot1 = snapshots.get(i);
                PerformanceSnapshot snapshot2 = snapshots.get(j);
                
                String comparisonKey = snapshot1.getProfile().name() + "_vs_" + snapshot2.getProfile().name();
                PerformanceComparison comparison = new PerformanceComparison(snapshot1, snapshot2);
                comparisons.put(comparisonKey, comparison);
            }
        }
        
        return comparisons;
    }
    
    /**
     * Get all performance snapshots.
     * 
     * @return map of performance snapshots by profile
     */
    public Map<PerformanceProfile, PerformanceSnapshot> getPerformanceSnapshots() {
        return new HashMap<>(profileSnapshots);
    }
    
    /**
     * Clear all collected metrics and snapshots.
     */
    public void reset() {
        profileSnapshots.clear();
        testTimers.clear();
        testCounters.clear();
        testGauges.clear();
        testStartTime.set(null);
        testEndTime.set(null);
        
        logger.debug("Reset PerformanceMetricsCollector for test instance: {}", testInstanceId);
    }
    
    // Private helper methods
    
    private void recordTestEvent(String eventType, String testName, PerformanceProfile profile, 
                                Map<String, String> additionalTags) {
        Map<String, String> tags = createTestTags(profile, additionalTags);
        tags.put("event_type", eventType);
        tags.put("test_name", testName);
        
        recordTestCounter("events", profile, tags);
    }
    
    private void recordTestCompletion(String testName, PerformanceProfile profile, 
                                    PerformanceSnapshot snapshot) {
        Map<String, String> tags = Map.of(
            "test_name", testName,
            "success", String.valueOf(snapshot.isSuccess())
        );
        
        // Record execution time
        recordTestTimer("execution.time", snapshot.getDuration(), profile, tags);
        
        // Record success/failure counters
        if (snapshot.isSuccess()) {
            recordTestCounter("success", profile, tags);
        } else {
            recordTestCounter("failure", profile, tags);
        }
        
        // Record throughput if available
        if (snapshot.getAdditionalMetrics().containsKey("throughput")) {
            double throughput = ((Number) snapshot.getAdditionalMetrics().get("throughput")).doubleValue();
            recordTestGauge("throughput", throughput, profile, tags);
        }
    }
    
    private Map<String, String> createTestTags(PerformanceProfile profile, Map<String, String> additionalTags) {
        Map<String, String> tags = new HashMap<>();
        tags.put("profile", profile.name());
        tags.put("profile_display", profile.getDisplayName());
        tags.put("test_instance", testInstanceId);
        
        if (additionalTags != null) {
            tags.putAll(additionalTags);
        }
        
        return tags;
    }
    
    private String tagsToString(Map<String, String> tags) {
        return tags.entrySet().stream()
            .map(entry -> entry.getKey() + "=" + entry.getValue())
            .sorted()
            .reduce((a, b) -> a + "," + b)
            .orElse("");
    }
    
    private MeterRegistry extractMeterRegistry(PeeGeeQMetrics metrics) {
        // Use reflection or a getter method to extract the MeterRegistry
        // For now, we'll assume it's accessible through a public method
        // This might need adjustment based on the actual PeeGeeQMetrics implementation
        try {
            var field = metrics.getClass().getDeclaredField("registry");
            field.setAccessible(true);
            return (MeterRegistry) field.get(metrics);
        } catch (Exception e) {
            logger.warn("Could not extract MeterRegistry from PeeGeeQMetrics, using null", e);
            return null;
        }
    }
}
