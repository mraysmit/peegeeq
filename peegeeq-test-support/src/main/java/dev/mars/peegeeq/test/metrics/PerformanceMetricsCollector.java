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
import dev.mars.peegeeq.test.hardware.HardwareAwarePerformanceResult;
import dev.mars.peegeeq.test.hardware.HardwareProfile;
import dev.mars.peegeeq.test.hardware.HardwareProfiler;
import dev.mars.peegeeq.test.hardware.ResourceUsageSnapshot;
import dev.mars.peegeeq.test.hardware.SystemResourceMonitor;
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

    // Hardware profiling
    private final AtomicReference<HardwareProfile> hardwareProfile = new AtomicReference<>();
    private final AtomicReference<SystemResourceMonitor> resourceMonitor = new AtomicReference<>();
    private final AtomicReference<ResourceUsageSnapshot> resourceUsage = new AtomicReference<>();
    private final boolean hardwareProfilingEnabled;

    /**
     * Create a new PerformanceMetricsCollector.
     *
     * @param baseMetrics the base PeeGeeQMetrics instance
     * @param testInstanceId unique identifier for this test instance
     */
    public PerformanceMetricsCollector(PeeGeeQMetrics baseMetrics, String testInstanceId) {
        this(baseMetrics, testInstanceId, true);
    }

    /**
     * Create a new PerformanceMetricsCollector with optional hardware profiling.
     *
     * @param baseMetrics the base PeeGeeQMetrics instance
     * @param testInstanceId unique identifier for this test instance
     * @param enableHardwareProfiling whether to enable hardware profiling
     */
    public PerformanceMetricsCollector(PeeGeeQMetrics baseMetrics, String testInstanceId, boolean enableHardwareProfiling) {
        this.meterRegistry = extractMeterRegistry(baseMetrics);
        this.testInstanceId = testInstanceId;
        this.hardwareProfilingEnabled = enableHardwareProfiling;

        if (hardwareProfilingEnabled) {
            // Capture hardware profile once during initialization
            try {
                this.hardwareProfile.set(HardwareProfiler.captureProfile());
                logger.info("Hardware profiling enabled for test instance: {} on {}",
                           testInstanceId, this.hardwareProfile.get().getSummary());

                // Register hardware profile metrics for Prometheus export
                registerHardwareProfileMetrics();
            } catch (Exception e) {
                logger.warn("Failed to capture hardware profile for test instance: {}", testInstanceId, e);
            }
        } else {
            logger.debug("Hardware profiling disabled for test instance: {}", testInstanceId);
        }

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

        // Start hardware resource monitoring if enabled
        if (hardwareProfilingEnabled) {
            try {
                SystemResourceMonitor monitor = new SystemResourceMonitor();
                resourceMonitor.set(monitor);
                monitor.startMonitoring(Duration.ofSeconds(1)); // Monitor every second

                // Register real-time resource usage metrics for Prometheus
                registerResourceUsageMetrics(testName);

                logger.debug("Started hardware resource monitoring for test: {}", testName);
            } catch (Exception e) {
                logger.warn("Failed to start hardware resource monitoring for test: {}", testName, e);
            }
        }

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

        // Stop hardware resource monitoring if enabled
        if (hardwareProfilingEnabled && resourceMonitor.get() != null) {
            try {
                ResourceUsageSnapshot usage = resourceMonitor.get().stopMonitoring();
                resourceUsage.set(usage);
                logger.debug("Stopped hardware resource monitoring for test: {} - {}", testName, usage.getSummary());
            } catch (Exception e) {
                logger.warn("Failed to stop hardware resource monitoring for test: {}", testName, e);
            }
        }

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

    /**
     * Create a hardware-aware performance result from the latest test execution.
     *
     * @param testName the name of the test
     * @param profile the performance profile used
     * @param testConfiguration description of the test configuration
     * @param testParameters additional test parameters
     * @return HardwareAwarePerformanceResult containing comprehensive test results
     */
    public HardwareAwarePerformanceResult createHardwareAwareResult(String testName, PerformanceProfile profile,
                                                                   String testConfiguration, Map<String, String> testParameters) {
        PerformanceSnapshot snapshot = profileSnapshots.get(profile);
        if (snapshot == null) {
            throw new IllegalStateException("No performance snapshot available for profile: " + profile);
        }

        // Combine performance metrics with hardware context
        Map<String, Object> allMetrics = new HashMap<>(snapshot.getAdditionalMetrics());
        allMetrics.put("test.duration_ms", snapshot.getDuration().toMillis());
        allMetrics.put("test.success", snapshot.isSuccess());
        allMetrics.put("test.profile", profile.getDisplayName());

        return HardwareAwarePerformanceResult.builder()
            .testName(testName)
            .testStartTime(snapshot.getStartTime())
            .testEndTime(snapshot.getEndTime())
            .performanceMetrics(allMetrics)
            .hardwareProfile(hardwareProfile.get())
            .resourceUsage(resourceUsage.get())
            .testConfiguration(testConfiguration)
            .testParameters(testParameters)
            .build();
    }

    /**
     * Get the current hardware profile.
     */
    public HardwareProfile getHardwareProfile() {
        return hardwareProfile.get();
    }

    /**
     * Get the latest resource usage snapshot.
     */
    public ResourceUsageSnapshot getResourceUsage() {
        return resourceUsage.get();
    }

    /**
     * Check if hardware profiling is enabled.
     */
    public boolean isHardwareProfilingEnabled() {
        return hardwareProfilingEnabled;
    }

    /**
     * Register hardware profile metrics for Prometheus export.
     * Following PGQ patterns from PeeGeeQMetrics.java
     */
    private void registerHardwareProfileMetrics() {
        if (meterRegistry == null || hardwareProfile.get() == null) {
            logger.debug("Cannot register hardware metrics - registry or profile is null");
            return;
        }

        HardwareProfile profile = hardwareProfile.get();
        String profileHash = calculateProfileHash(profile);

        try {
            // Hardware specification gauges
            registerHardwareGauge("peegeeq.hardware.cpu.cores", profile.getCpuCores(), profileHash);
            registerHardwareGauge("peegeeq.hardware.cpu.frequency_ghz", profile.getCpuMaxFrequencyHz() / 1_000_000_000.0, profileHash);
            registerHardwareGauge("peegeeq.hardware.memory.total_gb", profile.getTotalMemoryBytes() / (1024.0 * 1024.0 * 1024.0), profileHash);
            registerHardwareGauge("peegeeq.hardware.memory.speed_mhz", profile.getMemorySpeedMHz(), profileHash);
            registerHardwareGauge("peegeeq.hardware.jvm.max_heap_gb", profile.getJvmMaxHeapBytes() / (1024.0 * 1024.0 * 1024.0), profileHash);

            // Container metrics if applicable
            if (profile.isContainerized()) {
                registerHardwareGauge("peegeeq.hardware.container.memory_limit_gb",
                    profile.getContainerMemoryLimitBytes() / (1024.0 * 1024.0 * 1024.0), profileHash);
                registerHardwareGauge("peegeeq.hardware.container.cpu_limit", profile.getContainerCpuLimit(), profileHash);
            }

            logger.debug("Registered hardware profile metrics for test instance: {} (profile hash: {})",
                        testInstanceId, profileHash);
        } catch (Exception e) {
            logger.warn("Failed to register hardware profile metrics", e);
        }
    }

    /**
     * Register real-time resource usage metrics during test execution.
     */
    private void registerResourceUsageMetrics(String testName) {
        if (meterRegistry == null || !hardwareProfilingEnabled) {
            return;
        }

        try {
            // Real-time resource usage gauges
            registerResourceGauge("peegeeq.test.cpu.usage_percent", testName, this::getCurrentCpuUsage);
            registerResourceGauge("peegeeq.test.memory.usage_percent", testName, this::getCurrentMemoryUsage);
            registerResourceGauge("peegeeq.test.jvm.memory.usage_percent", testName, this::getCurrentJvmMemoryUsage);
            registerResourceGauge("peegeeq.test.system.load", testName, this::getCurrentSystemLoad);
            registerResourceGauge("peegeeq.test.thread.count", testName, this::getCurrentThreadCount);

            logger.debug("Registered real-time resource usage metrics for test: {}", testName);
        } catch (Exception e) {
            logger.warn("Failed to register resource usage metrics for test: {}", testName, e);
        }
    }

    /**
     * Register hardware-aware performance result metrics.
     */
    public void registerPerformanceResultMetrics(HardwareAwarePerformanceResult result) {
        if (meterRegistry == null) {
            return;
        }

        try {
            String profileHash = calculateProfileHash(result.getHardwareProfile());

            // Performance efficiency metrics
            double throughputPerCore = result.getPerformanceMetrics().containsKey("throughput_per_second") ?
                ((Number) result.getPerformanceMetrics().get("throughput_per_second")).doubleValue() / result.getHardwareProfile().getCpuCores() : 0.0;

            double latencyPerGbRam = result.getPerformanceMetrics().containsKey("avg_latency_ms") ?
                ((Number) result.getPerformanceMetrics().get("avg_latency_ms")).doubleValue() / (result.getHardwareProfile().getTotalMemoryBytes() / (1024.0 * 1024.0 * 1024.0)) : 0.0;

            registerPerformanceGauge("peegeeq.performance.throughput_per_cpu_core", throughputPerCore, result.getTestName(), profileHash);
            registerPerformanceGauge("peegeeq.performance.latency_per_gb_ram", latencyPerGbRam, result.getTestName(), profileHash);

            // Resource constraint indicators
            registerPerformanceGauge("peegeeq.performance.has_resource_constraints",
                result.hasResourceConstraints() ? 1.0 : 0.0, result.getTestName(), profileHash);
            registerPerformanceGauge("peegeeq.performance.has_high_resource_usage",
                result.hasHighResourceUsage() ? 1.0 : 0.0, result.getTestName(), profileHash);

            logger.debug("Registered performance result metrics for test: {} (profile hash: {})",
                        result.getTestName(), profileHash);
        } catch (Exception e) {
            logger.warn("Failed to register performance result metrics", e);
        }
    }

    // Helper methods for metric registration
    private void registerHardwareGauge(String name, double value, String profileHash) {
        if (meterRegistry != null) {
            Gauge.builder(name, () -> value)
                .description("Hardware specification metric")
                .tag("instance", testInstanceId)
                .tag("hardware_profile_hash", profileHash)
                .register(meterRegistry);
        }
    }

    private void registerResourceGauge(String name, String testName, java.util.function.Supplier<Number> valueSupplier) {
        if (meterRegistry != null) {
            Gauge.builder(name, valueSupplier)
                .description("Real-time resource usage metric")
                .tag("instance", testInstanceId)
                .tag("test_name", testName)
                .register(meterRegistry);
        }
    }

    private void registerPerformanceGauge(String name, double value, String testName, String profileHash) {
        if (meterRegistry != null) {
            Gauge.builder(name, () -> value)
                .description("Hardware-aware performance metric")
                .tag("instance", testInstanceId)
                .tag("test_name", testName)
                .tag("hardware_profile_hash", profileHash)
                .register(meterRegistry);
        }
    }

    // Helper methods for current resource usage
    private Number getCurrentCpuUsage() {
        SystemResourceMonitor monitor = resourceMonitor.get();
        return monitor != null ? monitor.getCurrentCpuUsage() : 0.0;
    }

    private Number getCurrentMemoryUsage() {
        SystemResourceMonitor monitor = resourceMonitor.get();
        return monitor != null ? monitor.getCurrentMemoryUsage() : 0.0;
    }

    private Number getCurrentJvmMemoryUsage() {
        SystemResourceMonitor monitor = resourceMonitor.get();
        return monitor != null ? monitor.getCurrentJvmMemoryUsage() : 0.0;
    }

    private Number getCurrentSystemLoad() {
        SystemResourceMonitor monitor = resourceMonitor.get();
        return monitor != null ? monitor.getCurrentSystemLoad() : 0.0;
    }

    private Number getCurrentThreadCount() {
        SystemResourceMonitor monitor = resourceMonitor.get();
        return monitor != null ? monitor.getCurrentThreadCount() : 0;
    }

    private String calculateProfileHash(HardwareProfile profile) {
        if (profile == null) return "unknown";

        // Simple hash based on key hardware characteristics
        return String.format("%s_%d_%d_%d",
            profile.getCpuModel().replaceAll("[^a-zA-Z0-9]", "").toLowerCase(),
            profile.getCpuCores(),
            profile.getTotalMemoryBytes() / (1024 * 1024 * 1024), // GB
            profile.getCpuMaxFrequencyHz() / 1_000_000 // MHz
        );
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
