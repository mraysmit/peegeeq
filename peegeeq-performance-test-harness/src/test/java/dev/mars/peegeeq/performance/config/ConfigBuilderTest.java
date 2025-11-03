package dev.mars.peegeeq.performance.config;

import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Core tests for PerformanceTestConfig builder functionality.
 */
@Tag(TestCategories.CORE)
class ConfigBuilderTest {

    @Test
    void testBuilderCreation() {
        // Test that builder can be created
        PerformanceTestConfig.Builder builder = PerformanceTestConfig.builder();
        assertNotNull(builder, "Builder should not be null");
    }

    @Test
    void testBuilderWithTestSuite() {
        // Test builder with test suite
        PerformanceTestConfig.Builder builder = PerformanceTestConfig.builder()
                .testSuite("test-suite");
        assertNotNull(builder, "Builder should not be null after setting test suite");
    }

    @Test
    void testBuilderWithDuration() {
        // Test builder with duration
        Duration testDuration = Duration.ofMinutes(5);
        PerformanceTestConfig.Builder builder = PerformanceTestConfig.builder()
                .testDuration(testDuration);
        assertNotNull(builder, "Builder should not be null after setting duration");
    }

    @Test
    void testBuilderWithConcurrentThreads() {
        // Test builder with concurrent threads
        PerformanceTestConfig.Builder builder = PerformanceTestConfig.builder()
                .concurrentThreads(4);
        assertNotNull(builder, "Builder should not be null after setting concurrent threads");
    }

    @Test
    void testBuilderBuild() {
        // Test that builder can build a config
        PerformanceTestConfig config = PerformanceTestConfig.builder()
                .testSuite("build-test")
                .testDuration(Duration.ofMinutes(1))
                .concurrentThreads(2)
                .build();
        
        assertNotNull(config, "Built config should not be null");
    }
}
