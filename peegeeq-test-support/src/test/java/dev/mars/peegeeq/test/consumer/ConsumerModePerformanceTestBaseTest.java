package dev.mars.peegeeq.test.consumer;

import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory.PerformanceProfile;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for ConsumerModePerformanceTestBase demonstrating consumer mode
 * performance testing patterns and validating the test infrastructure.
 * 
 * This test serves as both validation of the base class functionality and
 * as an example of how to implement consumer mode performance tests.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-19
 * @version 1.0
 */
@Tag(TestCategories.PERFORMANCE)
class ConsumerModePerformanceTestBaseTest extends ConsumerModePerformanceTestBase {
    
    private static final Logger logger = LoggerFactory.getLogger(ConsumerModePerformanceTestBaseTest.class);
    
    @Test
    void testConsumerModeTestMatrixGeneration() {
        System.err.println("=== TEST METHOD STARTED: testConsumerModeTestMatrixGeneration ===");
        logger.info("Testing consumer mode test matrix generation");
        
        Stream<ConsumerModeTestScenario> scenarios = getConsumerModeTestMatrix();
        long count = scenarios.count();
        
        // We expect 8 scenarios based on our matrix definition
        assertEquals(8, count, "Expected 8 consumer mode test scenarios");
        
        logger.info("✓ Consumer mode test matrix generation test passed: {} scenarios", count);
        System.err.println("=== TEST METHOD COMPLETED: testConsumerModeTestMatrixGeneration ===");
    }
    
    @Test
    void testConsumerModeTestMatrixByProfile() {
        System.err.println("=== TEST METHOD STARTED: testConsumerModeTestMatrixByProfile ===");
        logger.info("Testing consumer mode test matrix filtering by profile");
        
        // Test filtering by BASIC profile
        Stream<ConsumerModeTestScenario> basicScenarios = getConsumerModeTestMatrix(PerformanceProfile.BASIC);
        long basicCount = basicScenarios.count();
        assertEquals(2, basicCount, "Expected 2 scenarios for BASIC profile");
        
        // Test filtering by HIGH_PERFORMANCE profile
        Stream<ConsumerModeTestScenario> highPerfScenarios = getConsumerModeTestMatrix(PerformanceProfile.HIGH_PERFORMANCE);
        long highPerfCount = highPerfScenarios.count();
        assertEquals(2, highPerfCount, "Expected 2 scenarios for HIGH_PERFORMANCE profile");
        
        logger.info("✓ Consumer mode test matrix filtering by profile test passed");
        System.err.println("=== TEST METHOD COMPLETED: testConsumerModeTestMatrixByProfile ===");
    }
    
    @Test
    void testConsumerModeTestMatrixByMode() {
        System.err.println("=== TEST METHOD STARTED: testConsumerModeTestMatrixByMode ===");
        logger.info("Testing consumer mode test matrix filtering by consumer mode");
        
        // Test filtering by LISTEN_NOTIFY_ONLY mode
        Stream<ConsumerModeTestScenario> listenNotifyScenarios = getConsumerModeTestMatrix(TestConsumerMode.LISTEN_NOTIFY_ONLY);
        long listenNotifyCount = listenNotifyScenarios.count();
        assertEquals(3, listenNotifyCount, "Expected 3 scenarios for LISTEN_NOTIFY_ONLY mode");

        // Test filtering by HYBRID mode
        Stream<ConsumerModeTestScenario> hybridScenarios = getConsumerModeTestMatrix(TestConsumerMode.HYBRID);
        long hybridCount = hybridScenarios.count();
        assertEquals(3, hybridCount, "Expected 3 scenarios for HYBRID mode");

        // Test filtering by POLLING_ONLY mode
        Stream<ConsumerModeTestScenario> pollingScenarios = getConsumerModeTestMatrix(TestConsumerMode.POLLING_ONLY);
        long pollingCount = pollingScenarios.count();
        assertEquals(2, pollingCount, "Expected 2 scenarios for POLLING_ONLY mode");

        logger.info("✓ Consumer mode test matrix filtering by mode test passed");
        System.err.println("=== TEST METHOD COMPLETED: testConsumerModeTestMatrixByMode ===");
    }
    
    @Test
    void testCreateConsumerModeMetrics() {
        System.err.println("=== TEST METHOD STARTED: testCreateConsumerModeMetrics ===");
        logger.info("Testing consumer mode metrics creation");
        
        Map<String, Object> metrics = createConsumerModeMetrics(
            1000.0,  // messages per second
            25.0,    // average processing time
            50.0,    // queue depth
            75.0,    // connection utilization
            2.5      // error rate
        );
        
        assertNotNull(metrics);
        assertEquals(1000.0, metrics.get("messages_per_second"));
        assertEquals(25.0, metrics.get("average_processing_time"));
        assertEquals(50.0, metrics.get("queue_depth"));
        assertEquals(75.0, metrics.get("connection_utilization"));
        assertEquals(2.5, metrics.get("error_rate"));
        
        // Check that standard metrics are also included
        assertEquals(1000.0, metrics.get("throughput"));
        assertEquals(25.0, metrics.get("average_latency"));
        
        logger.info("✓ Consumer mode metrics creation test passed");
        System.err.println("=== TEST METHOD COMPLETED: testCreateConsumerModeMetrics ===");
    }
    
    @ParameterizedTest
    @MethodSource("getConsumerModeTestMatrix")
    void testConsumerModePerformanceAcrossScenarios(ConsumerModeTestScenario scenario) {
        System.err.println("=== TEST METHOD STARTED: testConsumerModePerformanceAcrossScenarios(" + scenario.getScenarioName() + ") ===");
        logger.info("Testing consumer mode performance with scenario: {}", scenario.getScenarioName());
        logger.debug("Scenario details: {}", scenario.getDescription());
        
        ConsumerModeTestResult result = runConsumerModeTest(scenario, (testScenario) -> {
            // Simulate consumer mode operation based on scenario
            simulateConsumerModeWork(testScenario);
            
            // Generate realistic metrics based on the scenario
            return generateRealisticMetrics(testScenario);
        });
        
        // Validate the result
        assertNotNull(result);
        assertTrue(result.isSuccess(), "Consumer mode test should succeed");
        assertEquals(scenario, result.getScenario());
        assertEquals(scenario.getConsumerMode(), result.getConsumerMode());
        assertEquals(scenario.getPerformanceProfile(), result.getProfile());
        
        // Validate metrics are present
        assertNotNull(result.getMetrics());
        assertNotNull(result.getMessagesPerSecond());
        assertNotNull(result.getAverageProcessingTime());
        
        // Validate consumer performance thresholds
        validateConsumerPerformanceThresholds(result, scenario);
        
        logger.info("✓ Consumer mode performance test passed for scenario: {} (duration: {}ms, throughput: {} msg/s)", 
                   scenario.getScenarioName(), result.getDurationMs(), result.getMessagesPerSecond());
        System.err.println("=== TEST METHOD COMPLETED: testConsumerModePerformanceAcrossScenarios(" + scenario.getScenarioName() + ") ===");
    }
    
    @Test
    void testConsumerModeTestResultAccessors() {
        System.err.println("=== TEST METHOD STARTED: testConsumerModeTestResultAccessors ===");
        logger.info("Testing consumer mode test result accessor methods");
        
        ConsumerModeTestScenario scenario = ConsumerModeTestScenario.builder()
            .performanceProfile(PerformanceProfile.STANDARD)
            .consumerMode(TestConsumerMode.HYBRID)
            .pollingInterval(Duration.ofMillis(500))
            .threadCount(2)
            .messageCount(200)
            .batchSize(20)
            .description("Test scenario for accessor validation")
            .build();
        
        ConsumerModeTestResult result = runConsumerModeTest(scenario, (testScenario) -> {
            return createConsumerModeMetrics(500.0, 20.0, 10.0, 60.0, 1.0);
        });
        
        // Test all accessor methods
        assertEquals(scenario, result.getScenario());
        assertEquals(TestConsumerMode.HYBRID, result.getConsumerMode());
        assertEquals(Duration.ofMillis(500), result.getPollingInterval());
        assertEquals(2, result.getThreadCount());
        assertEquals(200, result.getMessageCount());
        assertEquals(20, result.getBatchSize());
        assertEquals(500.0, result.getMessagesPerSecond());
        assertEquals(20.0, result.getAverageProcessingTime());
        assertEquals(10.0, result.getQueueDepth());
        assertEquals(60.0, result.getConnectionUtilization());
        
        logger.info("✓ Consumer mode test result accessor methods test passed");
        System.err.println("=== TEST METHOD COMPLETED: testConsumerModeTestResultAccessors ===");
    }
    
    /**
     * Simulate consumer mode work based on the scenario configuration.
     */
    private void simulateConsumerModeWork(ConsumerModeTestScenario scenario) throws InterruptedException {
        // Simulate work time based on message count and processing complexity
        int baseWorkTime = 10; // Base 10ms per message
        int workTime = baseWorkTime * Math.min(scenario.getMessageCount() / 100, 5); // Cap at 50ms
        
        // Adjust work time based on consumer mode
        switch (scenario.getConsumerMode()) {
            case LISTEN_NOTIFY_ONLY:
                workTime = (int) (workTime * 0.8); // LISTEN/NOTIFY is more efficient
                break;
            case POLLING_ONLY:
                workTime = (int) (workTime * 1.2); // Polling has overhead
                break;
            case HYBRID:
                workTime = (int) (workTime * 1.0); // Balanced approach
                break;
        }
        
        Thread.sleep(workTime);
    }
    
    /**
     * Generate realistic metrics based on the scenario configuration.
     */
    private Map<String, Object> generateRealisticMetrics(ConsumerModeTestScenario scenario) {
        // Base throughput calculation
        double baseThroughput = 100.0; // Base 100 messages per second
        
        // Adjust throughput based on performance profile
        double profileMultiplier = switch (scenario.getPerformanceProfile()) {
            case BASIC -> 1.0;
            case STANDARD -> 2.0;
            case HIGH_PERFORMANCE -> 4.0;
            case MAXIMUM_PERFORMANCE -> 8.0;
            case CUSTOM -> 1.5;
        };
        
        // Adjust throughput based on consumer mode
        double modeMultiplier = switch (scenario.getConsumerMode()) {
            case LISTEN_NOTIFY_ONLY -> 1.2; // Most efficient
            case POLLING_ONLY -> 0.8;       // Less efficient due to polling overhead
            case HYBRID -> 1.0;             // Balanced
        };
        
        // Adjust throughput based on thread count
        double threadMultiplier = Math.min(scenario.getThreadCount() * 0.8, 4.0); // Diminishing returns
        
        double messagesPerSecond = baseThroughput * profileMultiplier * modeMultiplier * threadMultiplier;
        
        // Calculate other metrics
        double averageProcessingTime = 1000.0 / messagesPerSecond; // Inverse relationship
        double queueDepth = Math.max(0, scenario.getMessageCount() - (messagesPerSecond * 2)); // Simulated queue depth
        double connectionUtilization = Math.min(95.0, scenario.getThreadCount() * 15.0); // Based on thread count
        double errorRate = Math.random() * 2.0; // Random error rate 0-2%
        
        return createConsumerModeMetrics(
            messagesPerSecond,
            averageProcessingTime,
            queueDepth,
            connectionUtilization,
            errorRate
        );
    }
    
    @Override
    protected void validateConsumerPerformanceThresholds(ConsumerModeTestResult result, 
                                                        ConsumerModeTestScenario scenario) {
        // Call parent validation first
        super.validateConsumerPerformanceThresholds(result, scenario);
        
        // Add consumer-specific validations
        assertNotNull(result.getMessagesPerSecond(), "Messages per second should be recorded");
        assertTrue(result.getMessagesPerSecond() > 0, "Messages per second should be positive");
        
        assertNotNull(result.getAverageProcessingTime(), "Average processing time should be recorded");
        assertTrue(result.getAverageProcessingTime() > 0, "Average processing time should be positive");
        
        // Validate that higher performance profiles achieve better throughput
        double expectedMinThroughput = switch (scenario.getPerformanceProfile()) {
            case BASIC -> 50.0;
            case STANDARD -> 100.0;
            case HIGH_PERFORMANCE -> 200.0;
            case MAXIMUM_PERFORMANCE -> 400.0;
            case CUSTOM -> 75.0;
        };
        
        assertTrue(result.getMessagesPerSecond() >= expectedMinThroughput,
                  String.format("Throughput %.2f should be at least %.2f for profile %s",
                               result.getMessagesPerSecond(), expectedMinThroughput, scenario.getPerformanceProfile()));
        
        logger.debug("Consumer performance thresholds validation passed for scenario: {}", scenario.getScenarioName());
    }
}
