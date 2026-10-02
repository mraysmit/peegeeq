package dev.mars.peegeeq.test.consumer;

import dev.mars.peegeeq.test.base.ParameterizedPerformanceTestBase;
import dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory.PerformanceProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.HashMap;
import java.util.stream.Stream;

/**
 * Base class for consumer mode performance tests that run identical tests 
 * with different PostgreSQL configurations and consumer modes to demonstrate 
 * performance differences across various scenarios.
 * 
 * This class extends ParameterizedPerformanceTestBase to provide:
 * - Consumer mode specific test scenarios combining performance profiles with consumer modes
 * - Standardized test matrix generation for comprehensive performance testing
 * - Consumer mode specific metrics collection and validation
 * - Integration with existing PeeGeeQ consumer infrastructure
 * 
 * Usage:
 * ```java
 * class MyConsumerPerformanceTest extends ConsumerModePerformanceTestBase {
 *     
 *     @ParameterizedTest
 *     @MethodSource("getConsumerModeTestMatrix")
 *     void testConsumerPerformance(ConsumerModeTestScenario scenario) {
 *         ConsumerModeTestResult result = runConsumerModeTest(scenario, () -> {
 *             // Your consumer test logic here
 *             return performConsumerOperation(scenario);
 *         });
 *         
 *         // Validate consumer-specific performance thresholds
 *         validateConsumerPerformanceThresholds(result, scenario);
 *     }
 * }
 * ```
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-19
 * @version 1.0
 */
public abstract class ConsumerModePerformanceTestBase extends ParameterizedPerformanceTestBase {
    
    private static final Logger logger = LoggerFactory.getLogger(ConsumerModePerformanceTestBase.class);
    
    /**
     * Consumer mode test result extending the base performance test result
     * with consumer-specific metrics and information.
     */
    public static class ConsumerModeTestResult extends PerformanceTestResult {
        private final ConsumerModeTestScenario scenario;
        
        public ConsumerModeTestResult(ConsumerModeTestScenario scenario, String testName, 
                                    Instant startTime, Instant endTime, boolean success, 
                                    String errorMessage, Map<String, Object> metrics) {
            super(scenario.getPerformanceProfile(), testName, startTime, endTime, success, errorMessage, metrics);
            this.scenario = scenario;
        }
        
        public ConsumerModeTestScenario getScenario() { return scenario; }
        public TestConsumerMode getConsumerMode() { return scenario.getConsumerMode(); }
        public Duration getPollingInterval() { return scenario.getPollingInterval(); }
        public int getThreadCount() { return scenario.getThreadCount(); }
        public int getMessageCount() { return scenario.getMessageCount(); }
        public int getBatchSize() { return scenario.getBatchSize(); }
        
        // Consumer-specific metric accessors
        public Double getMessagesPerSecond() { 
            return (Double) getMetrics().get("messages_per_second"); 
        }
        
        public Double getAverageProcessingTime() { 
            return (Double) getMetrics().get("average_processing_time"); 
        }
        
        public Double getQueueDepth() { 
            return (Double) getMetrics().get("queue_depth"); 
        }
        
        public Double getConnectionUtilization() { 
            return (Double) getMetrics().get("connection_utilization"); 
        }
    }
    
    /**
     * Functional interface for consumer mode test operations.
     */
    @FunctionalInterface
    public interface ConsumerModeTestOperation {
        Map<String, Object> execute(ConsumerModeTestScenario scenario) throws Exception;
    }
    
    /**
     * Run a consumer mode performance test with a specific scenario and collect metrics.
     * 
     * @param scenario the consumer mode test scenario
     * @param operation the test operation to execute
     * @return consumer mode test result with metrics
     */
    protected ConsumerModeTestResult runConsumerModeTest(ConsumerModeTestScenario scenario, 
                                                       ConsumerModeTestOperation operation) {
        String testName = getTestMethodName();
        Instant startTime = Instant.now();
        
        logger.info("Running consumer mode test '{}' with scenario: {}", testName, scenario.getScenarioName());
        logger.debug("Scenario details: {}", scenario.getDescription());
        
        // Override the profile for this specific test run
        this.currentProfile = scenario.getPerformanceProfile();
        
        try {
            // Setup container with the specified profile
            setupContainerForProfile(scenario.getPerformanceProfile());
            
            // Execute the consumer mode test operation
            Map<String, Object> operationMetrics = operation.execute(scenario);

            // Debug logging to verify operation metrics
            logger.info("Operation metrics from execute(): {}", operationMetrics);
            System.err.println("=== OPERATION METRICS: " + operationMetrics + " ===");
            System.err.flush();

            Instant endTime = Instant.now();

            // Create result with success
            ConsumerModeTestResult result = new ConsumerModeTestResult(
                scenario, testName, startTime, endTime, true, null, operationMetrics);

            // Debug logging to verify result metrics after construction
            logger.info("Result metrics after construction: {}", result.getMetrics());
            System.err.println("=== RESULT METRICS AFTER CONSTRUCTION: " + result.getMetrics() + " ===");
            System.err.flush();
            
            // Record consumer mode specific metrics
            recordConsumerModeMetrics(result);
            
            logger.info("Consumer mode test '{}' completed successfully with scenario: {} (duration: {}ms)", 
                       testName, scenario.getScenarioName(), result.getDurationMs());
            
            return result;
            
        } catch (Exception e) {
            Instant endTime = Instant.now();
            
            logger.error("Consumer mode test '{}' failed with scenario: {}", testName, scenario.getScenarioName(), e);
            
            // Create result with failure
            ConsumerModeTestResult result = new ConsumerModeTestResult(
                scenario, testName, startTime, endTime, false, e.getMessage(), null);
            
            // Still record the failure metrics
            recordConsumerModeMetrics(result);
            
            return result;
        }
    }
    
    /**
     * Record consumer mode specific performance metrics for a test result.
     */
    private void recordConsumerModeMetrics(ConsumerModeTestResult result) {
        Map<String, String> tags = Map.of(
            "profile", result.getProfile().name(),
            "consumer_mode", result.getConsumerMode().name(),
            "test_name", result.getTestName(),
            "test_class", this.getClass().getSimpleName(),
            "success", String.valueOf(result.isSuccess()),
            "polling_interval", result.getPollingInterval().toString(),
            "thread_count", String.valueOf(result.getThreadCount()),
            "batch_size", String.valueOf(result.getBatchSize())
        );
        
        // Record execution time
        metrics.recordTimer("peegeeq.test.consumer.execution.time", result.getDurationMs(), tags);
        
        // Record success/failure
        if (result.isSuccess()) {
            metrics.incrementCounter("peegeeq.test.consumer.success", tags);
        } else {
            metrics.incrementCounter("peegeeq.test.consumer.failure", tags);
        }
        
        // Record consumer-specific metrics
        if (result.getMetrics() != null) {
            for (Map.Entry<String, Object> entry : result.getMetrics().entrySet()) {
                String metricName = "peegeeq.test.consumer." + entry.getKey();
                Object value = entry.getValue();
                
                if (value instanceof Number) {
                    metrics.recordGauge(metricName, ((Number) value).doubleValue(), tags);
                }
            }
        }
        
        logger.debug("Consumer mode metrics recorded for test: {} with scenario: {}", 
                    result.getTestName(), result.getScenario().getScenarioName());
    }
    
    /**
     * Get the current test method name for metrics.
     */
    private String getTestMethodName() {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        for (StackTraceElement element : stackTrace) {
            if (element.getClassName().equals(this.getClass().getName()) && 
                !element.getMethodName().equals("getTestMethodName") &&
                !element.getMethodName().equals("runConsumerModeTest")) {
                return element.getMethodName();
            }
        }
        return "unknown_consumer_test";
    }
    
    /**
     * Validate consumer mode performance thresholds for a test result.
     * Override this method to implement custom threshold validation.
     * 
     * @param result the consumer mode test result
     * @param scenario the consumer mode test scenario used
     */
    protected void validateConsumerPerformanceThresholds(ConsumerModeTestResult result, 
                                                        ConsumerModeTestScenario scenario) {
        // Default implementation - subclasses can override
        if (!result.isSuccess()) {
            throw new AssertionError("Consumer mode test failed: " + result.getErrorMessage());
        }
        
        logger.debug("Consumer performance thresholds validation passed for test: {} with scenario: {}", 
                    result.getTestName(), scenario.getScenarioName());
    }
    
    /**
     * Create consumer mode performance metrics with common consumer-specific measurements.
     * 
     * @param messagesPerSecond message processing throughput
     * @param averageProcessingTime average message processing time in milliseconds
     * @param queueDepth current queue depth
     * @param connectionUtilization connection pool utilization percentage
     * @param errorRate error rate as percentage (0.0 to 100.0)
     * @return map of consumer mode metrics
     */
    protected Map<String, Object> createConsumerModeMetrics(double messagesPerSecond, 
                                                           double averageProcessingTime,
                                                           double queueDepth, 
                                                           double connectionUtilization,
                                                           double errorRate) {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("messages_per_second", messagesPerSecond);
        metrics.put("average_processing_time", averageProcessingTime);
        metrics.put("queue_depth", queueDepth);
        metrics.put("connection_utilization", connectionUtilization);
        metrics.put("error_rate", errorRate);
        
        // Also include standard performance metrics
        metrics.put("throughput", messagesPerSecond);
        metrics.put("average_latency", averageProcessingTime);
        
        return metrics;
    }
    
    /**
     * Provide comprehensive consumer mode test matrix for parameterized testing.
     * This method generates test scenarios combining different performance profiles
     * with appropriate consumer modes to demonstrate performance characteristics.
     * 
     * @return stream of consumer mode test scenarios
     */
    protected static Stream<ConsumerModeTestScenario> getConsumerModeTestMatrix() {
        return Stream.of(
            // Basic profile scenarios - simple configurations for baseline testing
            ConsumerModeTestScenario.builder()
                .performanceProfile(PerformanceProfile.BASIC)
                .consumerMode(TestConsumerMode.POLLING_ONLY)
                .pollingInterval(Duration.ofSeconds(1))
                .threadCount(1)
                .messageCount(100)
                .batchSize(10)
                .description("Basic polling scenario for baseline performance")
                .build(),
                
            ConsumerModeTestScenario.builder()
                .performanceProfile(PerformanceProfile.BASIC)
                .consumerMode(TestConsumerMode.HYBRID)
                .pollingInterval(Duration.ofSeconds(2))
                .threadCount(1)
                .messageCount(100)
                .batchSize(10)
                .description("Basic hybrid scenario with fallback polling")
                .build(),

            // Standard profile scenarios - balanced configurations
            ConsumerModeTestScenario.builder()
                .performanceProfile(PerformanceProfile.STANDARD)
                .consumerMode(TestConsumerMode.LISTEN_NOTIFY_ONLY)
                .threadCount(2)
                .messageCount(500)
                .batchSize(20)
                .description("Standard LISTEN/NOTIFY for real-time processing")
                .build(),
                
            ConsumerModeTestScenario.builder()
                .performanceProfile(PerformanceProfile.STANDARD)
                .consumerMode(TestConsumerMode.HYBRID)
                .pollingInterval(Duration.ofMillis(500))
                .threadCount(2)
                .messageCount(500)
                .batchSize(20)
                .description("Standard hybrid with fast polling fallback")
                .build(),

            // High performance scenarios - optimized for throughput
            ConsumerModeTestScenario.builder()
                .performanceProfile(PerformanceProfile.HIGH_PERFORMANCE)
                .consumerMode(TestConsumerMode.LISTEN_NOTIFY_ONLY)
                .threadCount(4)
                .messageCount(1000)
                .batchSize(50)
                .description("High-performance LISTEN/NOTIFY for maximum throughput")
                .build(),
                
            ConsumerModeTestScenario.builder()
                .performanceProfile(PerformanceProfile.HIGH_PERFORMANCE)
                .consumerMode(TestConsumerMode.POLLING_ONLY)
                .pollingInterval(Duration.ofMillis(100))
                .threadCount(4)
                .messageCount(1000)
                .batchSize(50)
                .description("High-performance polling with aggressive intervals")
                .build(),

            // Maximum performance scenarios - extreme configurations
            ConsumerModeTestScenario.builder()
                .performanceProfile(PerformanceProfile.MAXIMUM_PERFORMANCE)
                .consumerMode(TestConsumerMode.LISTEN_NOTIFY_ONLY)
                .threadCount(8)
                .messageCount(2000)
                .batchSize(100)
                .description("Maximum performance LISTEN/NOTIFY for extreme throughput")
                .build(),
                
            ConsumerModeTestScenario.builder()
                .performanceProfile(PerformanceProfile.MAXIMUM_PERFORMANCE)
                .consumerMode(TestConsumerMode.HYBRID)
                .pollingInterval(Duration.ofMillis(50))
                .threadCount(8)
                .messageCount(2000)
                .batchSize(100)
                .description("Maximum performance hybrid with ultra-fast polling")
                .build()
        );
    }
    
    /**
     * Provide consumer mode test scenarios for specific performance profiles.
     * 
     * @param profile the performance profile to filter by
     * @return stream of consumer mode test scenarios for the specified profile
     */
    protected static Stream<ConsumerModeTestScenario> getConsumerModeTestMatrix(PerformanceProfile profile) {
        return getConsumerModeTestMatrix()
                .filter(scenario -> scenario.getPerformanceProfile() == profile);
    }
    
    /**
     * Provide consumer mode test scenarios for specific consumer modes.
     *
     * @param mode the consumer mode to filter by
     * @return stream of consumer mode test scenarios for the specified mode
     */
    protected static Stream<ConsumerModeTestScenario> getConsumerModeTestMatrix(TestConsumerMode mode) {
        return getConsumerModeTestMatrix()
                .filter(scenario -> scenario.getConsumerMode() == mode);
    }
}
