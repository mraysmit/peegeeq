package dev.mars.peegeeq.test.base;

import dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory.PerformanceProfile;
import org.testcontainers.containers.PostgreSQLContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.HashMap;
import java.util.stream.Stream;

/**
 * Base class for parameterized performance tests that run identical tests 
 * with different PostgreSQL configurations to demonstrate performance differences.
 * 
 * This class extends PeeGeeQTestBase to provide:
 * - Parameterized testing across different performance profiles
 * - Performance metrics collection and comparison
 * - Standardized performance test result recording
 * - Integration with the existing PeeGeeQ metrics framework
 * 
 * Usage:
 * ```java
 * class MyPerformanceTest extends ParameterizedPerformanceTestBase {
 *     
 *     @ParameterizedTest
 *     @EnumSource(PerformanceProfile.class)
 *     void testPerformanceAcrossProfiles(PerformanceProfile profile) {
 *         PerformanceTestResult result = runTestWithProfile(profile, () -> {
 *             // Your test logic here
 *             return performSomeOperation();
 *         });
 *         
 *         // Validate performance thresholds
 *         validatePerformanceThresholds(result, profile);
 *     }
 * }
 * ```
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-18
 * @version 1.0
 */
public abstract class ParameterizedPerformanceTestBase extends PeeGeeQTestBase {
    
    private static final Logger logger = LoggerFactory.getLogger(ParameterizedPerformanceTestBase.class);
    
    /**
     * Performance test context containing test execution information.
     */
    public static class PerformanceTestContext {
        private final PostgreSQLContainer<?> container;
        private final PerformanceProfile profile;
        private final String testName;
        private final Instant startTime;
        
        public PerformanceTestContext(PostgreSQLContainer<?> container, PerformanceProfile profile, String testName) {
            this.container = container;
            this.profile = profile;
            this.testName = testName;
            this.startTime = Instant.now();
        }
        
        public PostgreSQLContainer<?> getContainer() { return container; }
        public PerformanceProfile getProfile() { return profile; }
        public String getTestName() { return testName; }
        public Instant getStartTime() { return startTime; }
    }
    
    /**
     * Performance test result containing metrics and execution information.
     */
    public static class PerformanceTestResult {
        private final PerformanceProfile profile;
        private final String testName;
        private final Instant startTime;
        private final Instant endTime;
        private final Duration duration;
        private final boolean success;
        private final String errorMessage;
        private final Map<String, Object> metrics;
        
        public PerformanceTestResult(PerformanceProfile profile, String testName, Instant startTime, 
                                   Instant endTime, boolean success, String errorMessage, Map<String, Object> metrics) {
            this.profile = profile;
            this.testName = testName;
            this.startTime = startTime;
            this.endTime = endTime;
            this.duration = Duration.between(startTime, endTime);
            this.success = success;
            this.errorMessage = errorMessage;
            this.metrics = metrics != null ? new HashMap<>(metrics) : new HashMap<>();
        }
        
        // Getters
        public PerformanceProfile getProfile() { return profile; }
        public String getTestName() { return testName; }
        public Instant getStartTime() { return startTime; }
        public Instant getEndTime() { return endTime; }
        public Duration getDuration() { return duration; }
        public long getDurationMs() { return duration.toMillis(); }
        public boolean isSuccess() { return success; }
        public String getErrorMessage() { return errorMessage; }
        public Map<String, Object> getMetrics() { return new HashMap<>(metrics); }
        
        // Convenience methods for common metrics
        public Double getThroughput() { 
            return (Double) metrics.get("throughput"); 
        }
        
        public Double getAverageLatency() { 
            return (Double) metrics.get("average_latency"); 
        }
        
        public Double getP95Latency() { 
            return (Double) metrics.get("p95_latency"); 
        }
        
        public Double getErrorRate() { 
            return (Double) metrics.get("error_rate"); 
        }
        
        public boolean hasErrors() {
            Double errorRate = getErrorRate();
            return errorRate != null && errorRate > 0.0;
        }
    }
    
    /**
     * Functional interface for performance test operations.
     */
    @FunctionalInterface
    public interface PerformanceTestOperation {
        Map<String, Object> execute() throws Exception;
    }
    
    /**
     * Run a performance test with a specific profile and collect metrics.
     * 
     * @param profile the performance profile to test with
     * @param operation the test operation to execute
     * @return performance test result with metrics
     */
    protected PerformanceTestResult runTestWithProfile(PerformanceProfile profile, PerformanceTestOperation operation) {
        String testName = getTestMethodName();
        Instant startTime = Instant.now();
        
        logger.info("Running performance test '{}' with profile: {}", testName, profile.getDisplayName());
        
        // Override the profile for this specific test run
        this.currentProfile = profile;
        
        try {
            // Setup container with the specified profile
            setupContainerForProfile(profile);
            
            // Execute the test operation
            Map<String, Object> operationMetrics = operation.execute();
            
            Instant endTime = Instant.now();
            
            // Create result with success
            PerformanceTestResult result = new PerformanceTestResult(
                profile, testName, startTime, endTime, true, null, operationMetrics);
            
            // Record performance metrics
            recordPerformanceMetrics(result);
            
            logger.info("Performance test '{}' completed successfully with profile: {} (duration: {}ms)", 
                       testName, profile.getDisplayName(), result.getDurationMs());
            
            return result;
            
        } catch (Exception e) {
            Instant endTime = Instant.now();
            
            logger.error("Performance test '{}' failed with profile: {}", testName, profile.getDisplayName(), e);
            
            // Create result with failure
            PerformanceTestResult result = new PerformanceTestResult(
                profile, testName, startTime, endTime, false, e.getMessage(), null);
            
            // Still record the failure metrics
            recordPerformanceMetrics(result);
            
            return result;
        }
    }
    
    /**
     * Setup container for a specific performance profile.
     * This recreates the container if the profile is different from the current one.
     */
    protected void setupContainerForProfile(PerformanceProfile profile) {
        if (container != null && container.isRunning() && currentProfile == profile) {
            // Container is already running with the correct profile
            return;
        }
        
        // Stop existing container if running
        if (container != null && container.isRunning()) {
            logger.debug("Stopping existing container to switch profiles");
            container.stop();
        }
        
        // Create new container with the specified profile
        logger.debug("Creating container with profile: {}", profile.getDisplayName());
        container = dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory.createContainer(
            profile, getDatabaseName(), getUsername(), getPassword(), getCustomSettings());
        
        container.start();
        
        // Update database properties
        setupDatabaseProperties();
        
        logger.debug("Container ready with profile: {}", profile.getDisplayName());
    }
    
    /**
     * Record performance metrics for a test result.
     */
    private void recordPerformanceMetrics(PerformanceTestResult result) {
        Map<String, String> tags = Map.of(
            "profile", result.getProfile().name(),
            "test_name", result.getTestName(),
            "test_class", this.getClass().getSimpleName(),
            "success", String.valueOf(result.isSuccess())
        );
        
        // Record execution time
        metrics.recordTimer("peegeeq.test.execution.time", result.getDurationMs(), tags);
        
        // Record success/failure
        if (result.isSuccess()) {
            metrics.incrementCounter("peegeeq.test.success", tags);
        } else {
            metrics.incrementCounter("peegeeq.test.failure", tags);
        }
        
        // Record operation-specific metrics
        if (result.getMetrics() != null) {
            for (Map.Entry<String, Object> entry : result.getMetrics().entrySet()) {
                String metricName = "peegeeq.test." + entry.getKey();
                Object value = entry.getValue();
                
                if (value instanceof Number) {
                    metrics.recordGauge(metricName, ((Number) value).doubleValue(), tags);
                }
            }
        }
        
        logger.debug("Performance metrics recorded for test: {} with profile: {}", 
                    result.getTestName(), result.getProfile().getDisplayName());
    }
    
    /**
     * Get the current test method name for metrics.
     */
    private String getTestMethodName() {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        for (StackTraceElement element : stackTrace) {
            if (element.getClassName().equals(this.getClass().getName()) && 
                !element.getMethodName().equals("getTestMethodName") &&
                !element.getMethodName().equals("runTestWithProfile")) {
                return element.getMethodName();
            }
        }
        return "unknown_test";
    }
    
    /**
     * Setup database properties for the current container.
     */
    private void setupDatabaseProperties() {
        if (container != null) {
            System.setProperty("test.database.host", container.getHost());
            System.setProperty("test.database.port", String.valueOf(container.getFirstMappedPort()));
            System.setProperty("test.database.name", container.getDatabaseName());
            System.setProperty("test.database.username", container.getUsername());
            System.setProperty("test.database.password", container.getPassword());
            System.setProperty("test.database.url", container.getJdbcUrl());
        }
    }
    
    /**
     * Validate performance thresholds for a test result.
     * Override this method to implement custom threshold validation.
     * 
     * @param result the performance test result
     * @param profile the performance profile used
     */
    protected void validatePerformanceThresholds(PerformanceTestResult result, PerformanceProfile profile) {
        // Default implementation - subclasses can override
        if (!result.isSuccess()) {
            throw new AssertionError("Performance test failed: " + result.getErrorMessage());
        }
        
        logger.debug("Performance thresholds validation passed for test: {} with profile: {}", 
                    result.getTestName(), profile.getDisplayName());
    }
    
    /**
     * Create a performance test result with common metrics.
     * 
     * @param throughput operations per second
     * @param averageLatency average latency in milliseconds
     * @param p95Latency 95th percentile latency in milliseconds
     * @param errorRate error rate as percentage (0.0 to 100.0)
     * @return map of metrics
     */
    protected Map<String, Object> createPerformanceMetrics(double throughput, double averageLatency, 
                                                          double p95Latency, double errorRate) {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("throughput", throughput);
        metrics.put("average_latency", averageLatency);
        metrics.put("p95_latency", p95Latency);
        metrics.put("error_rate", errorRate);
        return metrics;
    }
    
    /**
     * Provide all performance profiles for parameterized testing.
     * 
     * @return stream of performance profiles
     */
    protected static Stream<PerformanceProfile> getAllPerformanceProfiles() {
        return Stream.of(PerformanceProfile.values());
    }
    
    /**
     * Provide performance profiles excluding CUSTOM for standard testing.
     * 
     * @return stream of performance profiles excluding CUSTOM
     */
    protected static Stream<PerformanceProfile> getStandardPerformanceProfiles() {
        return Stream.of(PerformanceProfile.values())
                .filter(profile -> profile != PerformanceProfile.CUSTOM);
    }
}
