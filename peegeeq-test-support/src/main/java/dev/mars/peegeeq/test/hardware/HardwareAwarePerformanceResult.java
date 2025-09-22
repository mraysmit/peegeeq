package dev.mars.peegeeq.test.hardware;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Enhanced performance test result that includes hardware profiling context.
 * 
 * This class extends standard performance test results by incorporating
 * hardware specifications and real-time resource usage monitoring,
 * enabling meaningful comparison and analysis of performance results
 * across different hardware environments.
 * 
 * Key Features:
 * - Hardware profile context (CPU, memory, storage specifications)
 * - Real-time resource usage monitoring during test execution
 * - Performance metrics correlation with resource consumption
 * - Hardware-aware performance analysis and reporting
 * - Regression detection accounting for hardware differences
 * 
 * Usage:
 * ```java
 * HardwareAwarePerformanceResult result = HardwareAwarePerformanceResult.builder()
 *     .testName("ThroughputTest")
 *     .performanceMetrics(metrics)
 *     .hardwareProfile(profile)
 *     .resourceUsage(snapshot)
 *     .build();
 * 
 * logger.info("Test completed on: {}", result.getHardwareContext());
 * if (result.hasResourceConstraints()) {
 *     logger.warn("Performance may be affected by resource constraints");
 * }
 * ```
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-19
 * @version 1.0
 */
public class HardwareAwarePerformanceResult {
    
    private final String testName;
    private final Instant testStartTime;
    private final Instant testEndTime;
    
    // Performance metrics
    private final Map<String, Object> performanceMetrics;
    
    // Hardware context
    private final HardwareProfile hardwareProfile;
    private final ResourceUsageSnapshot resourceUsage;
    
    // Test configuration
    private final String testConfiguration;
    private final Map<String, String> testParameters;
    
    /**
     * Private constructor - use builder pattern.
     */
    private HardwareAwarePerformanceResult(Builder builder) {
        this.testName = builder.testName;
        this.testStartTime = builder.testStartTime;
        this.testEndTime = builder.testEndTime;
        this.performanceMetrics = Map.copyOf(builder.performanceMetrics);
        this.hardwareProfile = builder.hardwareProfile;
        this.resourceUsage = builder.resourceUsage;
        this.testConfiguration = builder.testConfiguration;
        this.testParameters = Map.copyOf(builder.testParameters);
    }
    
    /**
     * Create a new builder for HardwareAwarePerformanceResult.
     */
    public static Builder builder() {
        return new Builder();
    }
    
    // Getters
    public String getTestName() { return testName; }
    public Instant getTestStartTime() { return testStartTime; }
    public Instant getTestEndTime() { return testEndTime; }
    public java.time.Duration getTestDuration() { 
        return java.time.Duration.between(testStartTime, testEndTime); 
    }
    
    public Map<String, Object> getPerformanceMetrics() { return performanceMetrics; }
    public HardwareProfile getHardwareProfile() { return hardwareProfile; }
    public ResourceUsageSnapshot getResourceUsage() { return resourceUsage; }
    public String getTestConfiguration() { return testConfiguration; }
    public Map<String, String> getTestParameters() { return testParameters; }
    
    /**
     * Get a specific performance metric value.
     */
    public Object getMetric(String key) {
        return performanceMetrics.get(key);
    }
    
    /**
     * Get a performance metric as a double value.
     */
    public double getMetricAsDouble(String key) {
        Object value = performanceMetrics.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        throw new IllegalArgumentException("Metric '" + key + "' is not a numeric value: " + value);
    }
    
    /**
     * Check if the test had resource constraints that may have affected performance.
     */
    public boolean hasResourceConstraints() {
        return resourceUsage != null && resourceUsage.hasResourceConstraints();
    }
    
    /**
     * Check if the test had high resource usage.
     */
    public boolean hasHighResourceUsage() {
        return resourceUsage != null && resourceUsage.isHighResourceUsage();
    }
    
    /**
     * Get hardware context summary for reporting.
     */
    public String getHardwareContext() {
        if (hardwareProfile == null) {
            return "Hardware profile not available";
        }
        return hardwareProfile.getSummary();
    }
    
    /**
     * Get resource usage summary for reporting.
     */
    public String getResourceUsageSummary() {
        if (resourceUsage == null) {
            return "Resource usage monitoring not available";
        }
        return resourceUsage.getSummary();
    }
    
    /**
     * Get comprehensive test result summary including hardware context.
     */
    public String getComprehensiveSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Performance Test Result ===\n");
        sb.append(String.format("Test: %s\n", testName));
        sb.append(String.format("Duration: %dms\n", getTestDuration().toMillis()));
        sb.append(String.format("Configuration: %s\n", testConfiguration));
        sb.append("\n");
        
        // Hardware context
        sb.append("Hardware Context:\n");
        sb.append(String.format("  %s\n", getHardwareContext()));
        sb.append("\n");
        
        // Performance metrics
        sb.append("Performance Metrics:\n");
        performanceMetrics.forEach((key, value) -> {
            if (key.contains("per_second") || key.contains("throughput")) {
                sb.append(String.format("  %s: %.2f\n", key, value));
            } else if (key.contains("latency") || key.contains("time")) {
                sb.append(String.format("  %s: %.1f ms\n", key, value));
            } else {
                sb.append(String.format("  %s: %s\n", key, value));
            }
        });
        sb.append("\n");
        
        // Resource usage
        if (resourceUsage != null) {
            sb.append("Resource Usage:\n");
            sb.append(String.format("  %s\n", getResourceUsageSummary()));
            
            if (hasResourceConstraints()) {
                sb.append("\n🚨 WARNING: Resource constraints detected!\n");
                sb.append("  Performance results may be affected by system limitations.\n");
            } else if (hasHighResourceUsage()) {
                sb.append("\n⚠️  High resource usage detected.\n");
                sb.append("  Consider monitoring for potential bottlenecks.\n");
            }
        }
        
        return sb.toString();
    }
    
    /**
     * Convert to a comprehensive metrics map including hardware and resource data.
     */
    public Map<String, Object> toComprehensiveMetricsMap() {
        Map<String, Object> allMetrics = new java.util.HashMap<>();
        
        // Test metadata
        allMetrics.put("test.name", testName);
        allMetrics.put("test.start_time", testStartTime.toString());
        allMetrics.put("test.end_time", testEndTime.toString());
        allMetrics.put("test.duration_ms", getTestDuration().toMillis());
        allMetrics.put("test.configuration", testConfiguration);
        
        // Performance metrics
        performanceMetrics.forEach((key, value) -> 
            allMetrics.put("performance." + key, value));
        
        // Hardware profile
        if (hardwareProfile != null) {
            allMetrics.put("hardware.cpu.model", hardwareProfile.getCpuModel());
            allMetrics.put("hardware.cpu.cores", hardwareProfile.getCpuCores());
            allMetrics.put("hardware.cpu.frequency_ghz", hardwareProfile.getCpuMaxFrequencyGHz());
            allMetrics.put("hardware.memory.total_gb", hardwareProfile.getTotalMemoryGB());
            allMetrics.put("hardware.memory.type", hardwareProfile.getMemoryType());
            allMetrics.put("hardware.storage.type", hardwareProfile.getStorageType());
            allMetrics.put("hardware.jvm.max_heap_gb", hardwareProfile.getJvmMaxHeapGB());
            allMetrics.put("hardware.jvm.gc_algorithm", hardwareProfile.getGcAlgorithm());
            allMetrics.put("hardware.os.name", hardwareProfile.getOsName());
            allMetrics.put("hardware.os.architecture", hardwareProfile.getOsArchitecture());
            allMetrics.put("hardware.is_containerized", hardwareProfile.isContainerized());
        }
        
        // Resource usage
        if (resourceUsage != null) {
            allMetrics.putAll(resourceUsage.toMetricsMap());
        }
        
        // Test parameters
        testParameters.forEach((key, value) -> 
            allMetrics.put("parameter." + key, value));
        
        return allMetrics;
    }
    
    /**
     * Compare this result with another for performance regression analysis.
     */
    public PerformanceComparison compareWith(HardwareAwarePerformanceResult other, String metricKey) {
        if (!performanceMetrics.containsKey(metricKey) || !other.performanceMetrics.containsKey(metricKey)) {
            throw new IllegalArgumentException("Metric '" + metricKey + "' not found in one or both results");
        }
        
        double thisValue = getMetricAsDouble(metricKey);
        double otherValue = other.getMetricAsDouble(metricKey);
        double percentChange = ((thisValue - otherValue) / otherValue) * 100.0;
        
        boolean sameHardware = Objects.equals(this.hardwareProfile, other.hardwareProfile);
        
        return new PerformanceComparison(
            metricKey, thisValue, otherValue, percentChange, sameHardware,
            this.getHardwareContext(), other.getHardwareContext()
        );
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        HardwareAwarePerformanceResult that = (HardwareAwarePerformanceResult) o;
        return Objects.equals(testName, that.testName) &&
               Objects.equals(testStartTime, that.testStartTime) &&
               Objects.equals(testEndTime, that.testEndTime);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(testName, testStartTime, testEndTime);
    }
    
    @Override
    public String toString() {
        return String.format("HardwareAwarePerformanceResult{test='%s', duration=%dms, hardware='%s'}",
                           testName, getTestDuration().toMillis(), getHardwareContext());
    }
    
    /**
     * Builder for creating HardwareAwarePerformanceResult instances.
     */
    public static class Builder {
        private String testName;
        private Instant testStartTime = Instant.now();
        private Instant testEndTime = Instant.now();
        private Map<String, Object> performanceMetrics = Map.of();
        private HardwareProfile hardwareProfile;
        private ResourceUsageSnapshot resourceUsage;
        private String testConfiguration = "default";
        private Map<String, String> testParameters = Map.of();
        
        public Builder testName(String testName) {
            this.testName = testName;
            return this;
        }
        
        public Builder testStartTime(Instant testStartTime) {
            this.testStartTime = testStartTime;
            return this;
        }
        
        public Builder testEndTime(Instant testEndTime) {
            this.testEndTime = testEndTime;
            return this;
        }
        
        public Builder performanceMetrics(Map<String, Object> performanceMetrics) {
            this.performanceMetrics = performanceMetrics;
            return this;
        }
        
        public Builder hardwareProfile(HardwareProfile hardwareProfile) {
            this.hardwareProfile = hardwareProfile;
            return this;
        }
        
        public Builder resourceUsage(ResourceUsageSnapshot resourceUsage) {
            this.resourceUsage = resourceUsage;
            return this;
        }
        
        public Builder testConfiguration(String testConfiguration) {
            this.testConfiguration = testConfiguration;
            return this;
        }
        
        public Builder testParameters(Map<String, String> testParameters) {
            this.testParameters = testParameters;
            return this;
        }
        
        public HardwareAwarePerformanceResult build() {
            Objects.requireNonNull(testName, "testName is required");
            Objects.requireNonNull(testStartTime, "testStartTime is required");
            Objects.requireNonNull(testEndTime, "testEndTime is required");
            
            return new HardwareAwarePerformanceResult(this);
        }
    }
    
    /**
     * Performance comparison result.
     */
    public static class PerformanceComparison {
        private final String metricKey;
        private final double thisValue;
        private final double otherValue;
        private final double percentChange;
        private final boolean sameHardware;
        private final String thisHardware;
        private final String otherHardware;
        
        public PerformanceComparison(String metricKey, double thisValue, double otherValue,
                                   double percentChange, boolean sameHardware,
                                   String thisHardware, String otherHardware) {
            this.metricKey = metricKey;
            this.thisValue = thisValue;
            this.otherValue = otherValue;
            this.percentChange = percentChange;
            this.sameHardware = sameHardware;
            this.thisHardware = thisHardware;
            this.otherHardware = otherHardware;
        }
        
        public String getMetricKey() { return metricKey; }
        public double getThisValue() { return thisValue; }
        public double getOtherValue() { return otherValue; }
        public double getPercentChange() { return percentChange; }
        public boolean isSameHardware() { return sameHardware; }
        public String getThisHardware() { return thisHardware; }
        public String getOtherHardware() { return otherHardware; }
        
        public boolean isRegression(double threshold) {
            return percentChange < -threshold;
        }
        
        public boolean isImprovement(double threshold) {
            return percentChange > threshold;
        }
        
        @Override
        public String toString() {
            String direction = percentChange > 0 ? "improved" : "degraded";
            String hardwareNote = sameHardware ? " (same hardware)" : " (different hardware)";
            return String.format("%s: %.2f -> %.2f (%.1f%% %s)%s",
                               metricKey, otherValue, thisValue, Math.abs(percentChange), direction, hardwareNote);
        }
    }
}
