package dev.mars.peegeeq.test.hardware;

import dev.mars.peegeeq.test.base.PeeGeeQTestBase;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory.PerformanceProfile;
import dev.mars.peegeeq.test.metrics.PerformanceMetricsCollector;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for hardware profiling infrastructure.
 * 
 * This test validates the complete hardware profiling pipeline including:
 * - Hardware profile capture
 * - Real-time resource monitoring
 * - Integration with performance metrics collection
 * - Hardware-aware performance result generation
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-19
 * @version 1.0
 */
@Tag(TestCategories.PERFORMANCE)
class HardwareProfilingIntegrationTest extends PeeGeeQTestBase {
    private static final Logger logger = LoggerFactory.getLogger(HardwareProfilingIntegrationTest.class);
    
    @Test
    @DisplayName("Hardware Profile Capture - Should capture comprehensive system specifications")
    void testHardwareProfileCapture() {
        logger.info("=== Testing Hardware Profile Capture ===");
        
        // Capture hardware profile
        HardwareProfile profile = HardwareProfiler.captureProfile();
        
        // Validate basic system information
        assertNotNull(profile, "Hardware profile should not be null");
        assertNotNull(profile.getSystemDescription(), "System description should not be null");
        assertNotNull(profile.getCaptureTime(), "Capture time should not be null");
        assertNotNull(profile.getHostname(), "Hostname should not be null");
        assertNotNull(profile.getOsName(), "OS name should not be null");
        
        // Validate CPU information
        assertNotNull(profile.getCpuModel(), "CPU model should not be null");
        assertTrue(profile.getCpuCores() > 0, "CPU cores should be positive");
        assertTrue(profile.getCpuLogicalProcessors() >= profile.getCpuCores(), 
                  "Logical processors should be >= physical cores");
        
        // Validate memory information
        assertTrue(profile.getTotalMemoryBytes() > 0, "Total memory should be positive");
        assertTrue(profile.getAvailableMemoryBytes() >= 0, "Available memory should be non-negative");
        assertTrue(profile.getAvailableMemoryBytes() <= profile.getTotalMemoryBytes(),
                  "Available memory should not exceed total memory");
        
        // Validate JVM information
        assertNotNull(profile.getJavaVersion(), "Java version should not be null");
        assertNotNull(profile.getJavaVendor(), "Java vendor should not be null");
        assertTrue(profile.getJvmMaxHeapBytes() > 0, "JVM max heap should be positive");
        
        // Log comprehensive information
        logger.info("Hardware Profile Summary: {}", profile.getSummary());
        logger.info("System: {} cores @ {:.1f} GHz, {:.1f} GB RAM", 
                   profile.getCpuCores(), profile.getCpuMaxFrequencyGHz(), profile.getTotalMemoryGB());
        logger.info("JVM: {} {}, max heap: {:.1f} GB, GC: {}", 
                   profile.getJvmName(), profile.getJavaVersion(), profile.getJvmMaxHeapGB(), profile.getGcAlgorithm());
        
        // Test caching behavior
        HardwareProfile cachedProfile = HardwareProfiler.getCachedProfile();
        assertEquals(profile.getCaptureTime(), cachedProfile.getCaptureTime(), 
                    "Cached profile should have same capture time");
    }
    
    @Test
    @DisplayName("System Resource Monitoring - Should monitor real-time resource usage")
    void testSystemResourceMonitoring() throws InterruptedException {
        logger.info("=== Testing System Resource Monitoring ===");
        
        SystemResourceMonitor monitor = new SystemResourceMonitor();
        assertFalse(monitor.isMonitoring(), "Monitor should not be active initially");
        
        // Start monitoring
        monitor.startMonitoring(Duration.ofMillis(100)); // Fast sampling for test
        assertTrue(monitor.isMonitoring(), "Monitor should be active after start");
        
        // Simulate some work to generate resource usage
        logger.info("Simulating workload for resource monitoring...");
        simulateWorkload(Duration.ofSeconds(2));
        
        // Stop monitoring and get results
        ResourceUsageSnapshot snapshot = monitor.stopMonitoring();
        assertFalse(monitor.isMonitoring(), "Monitor should not be active after stop");
        
        // Validate snapshot
        assertNotNull(snapshot, "Resource usage snapshot should not be null");
        assertTrue(snapshot.getSampleCount() > 0, "Should have collected samples");
        assertTrue(snapshot.getDuration().toMillis() >= 1000, "Monitoring duration should be at least 1 second");
        
        // Validate CPU metrics
        assertTrue(snapshot.getPeakCpuUsage() >= 0.0, "Peak CPU usage should be non-negative");
        assertTrue(snapshot.getPeakCpuUsage() <= 100.0, "Peak CPU usage should not exceed 100%");
        assertTrue(snapshot.getAvgCpuUsage() >= 0.0, "Average CPU usage should be non-negative");
        assertTrue(snapshot.getAvgCpuUsage() <= snapshot.getPeakCpuUsage(), 
                  "Average CPU usage should not exceed peak");
        
        // Validate memory metrics
        assertTrue(snapshot.getPeakMemoryUsage() >= 0.0, "Peak memory usage should be non-negative");
        assertTrue(snapshot.getPeakMemoryUsage() <= 100.0, "Peak memory usage should not exceed 100%");
        assertTrue(snapshot.getPeakJvmMemoryUsage() >= 0.0, "Peak JVM memory usage should be non-negative");
        
        // Log monitoring results
        logger.info("Resource Monitoring Results:");
        logger.info("  Duration: {}ms, Samples: {}", snapshot.getDuration().toMillis(), snapshot.getSampleCount());
        logger.info("  CPU: {:.1f}% peak ({:.1f}% avg)", snapshot.getPeakCpuUsage(), snapshot.getAvgCpuUsage());
        logger.info("  Memory: {:.1f}% peak ({:.1f}% avg)", snapshot.getPeakMemoryUsage(), snapshot.getAvgMemoryUsage());
        logger.info("  JVM: {:.1f}% peak ({:.1f}% avg)", snapshot.getPeakJvmMemoryUsage(), snapshot.getAvgJvmMemoryUsage());
        logger.info("  System Load: {:.2f} peak ({:.2f} avg)", snapshot.getPeakSystemLoad(), snapshot.getAvgSystemLoad());
        
        // Test resource usage analysis
        if (snapshot.isHighResourceUsage()) {
            logger.warn("High resource usage detected during test");
        }
        if (snapshot.hasResourceConstraints()) {
            logger.warn("Resource constraints detected during test");
        }
    }
    
    @Test
    @DisplayName("Hardware-Aware Performance Integration - Should integrate with performance metrics")
    void testHardwareAwarePerformanceIntegration() {
        logger.info("=== Testing Hardware-Aware Performance Integration ===");
        
        // Create performance metrics collector with hardware profiling enabled
        PerformanceMetricsCollector collector = new PerformanceMetricsCollector(
            getMetrics(), "hardware-integration-test", true);
        
        assertTrue(collector.isHardwareProfilingEnabled(), "Hardware profiling should be enabled");
        assertNotNull(collector.getHardwareProfile(), "Hardware profile should be available");
        
        String testName = "HardwareIntegrationTest";
        PerformanceProfile profile = PerformanceProfile.BASIC;
        
        // Start test with hardware monitoring
        collector.startTest(testName, profile);
        
        // Simulate test workload
        logger.info("Simulating test workload...");
        simulateWorkload(Duration.ofSeconds(1));
        
        // End test and capture results
        Map<String, Object> testMetrics = Map.of(
            "messages_processed", 100,
            "throughput_per_second", 50.0,
            "avg_latency_ms", 20.0
        );
        
        collector.endTest(testName, profile, true, testMetrics);
        
        // Create hardware-aware performance result
        HardwareAwarePerformanceResult result = collector.createHardwareAwareResult(
            testName, profile, "Basic performance test with hardware profiling",
            Map.of("workload_duration", "1000ms", "message_count", "100")
        );
        
        // Validate result
        assertNotNull(result, "Hardware-aware result should not be null");
        assertEquals(testName, result.getTestName(), "Test name should match");
        assertNotNull(result.getHardwareProfile(), "Hardware profile should be included");
        assertNotNull(result.getResourceUsage(), "Resource usage should be included");
        
        // Validate performance metrics
        Map<String, Object> allMetrics = result.getPerformanceMetrics();
        assertTrue(allMetrics.containsKey("messages_processed"), "Should contain test metrics");
        assertTrue(allMetrics.containsKey("throughput_per_second"), "Should contain throughput metric");
        assertTrue(allMetrics.containsKey("test.duration_ms"), "Should contain test duration");
        
        // Log comprehensive results
        logger.info("Hardware-Aware Performance Result:");
        logger.info("  Test: {} ({}ms)", result.getTestName(), result.getTestDuration().toMillis());
        logger.info("  Hardware: {}", result.getHardwareContext());
        logger.info("  Resource Usage: {}", result.getResourceUsageSummary());
        logger.info("  Performance: {} msg/sec, {:.1f}ms avg latency", 
                   result.getMetric("throughput_per_second"), result.getMetric("avg_latency_ms"));
        
        // Test comprehensive metrics map
        Map<String, Object> comprehensiveMetrics = result.toComprehensiveMetricsMap();
        assertTrue(comprehensiveMetrics.size() > 20, "Should have comprehensive metrics");
        assertTrue(comprehensiveMetrics.containsKey("hardware.cpu.cores"), "Should include hardware metrics");
        assertTrue(comprehensiveMetrics.containsKey("resource.cpu.peak_usage_percent"), "Should include resource metrics");
        assertTrue(comprehensiveMetrics.containsKey("performance.throughput_per_second"), "Should include performance metrics");
        
        // Test resource constraint detection
        if (result.hasResourceConstraints()) {
            logger.warn("Resource constraints detected - performance may be affected");
        }
        if (result.hasHighResourceUsage()) {
            logger.info("High resource usage detected during test");
        }
        
        logger.info("✅ Hardware profiling integration test completed successfully");
    }
    
    /**
     * Simulate CPU and memory workload for testing resource monitoring.
     */
    private void simulateWorkload(Duration duration) {
        long endTime = System.currentTimeMillis() + duration.toMillis();
        
        while (System.currentTimeMillis() < endTime) {
            // CPU-intensive work
            for (int i = 0; i < 10000; i++) {
                Math.sqrt(i * Math.PI);
            }
            
            // Memory allocation
            byte[] buffer = new byte[1024 * 1024]; // 1MB allocation
            buffer[0] = 1; // Touch the memory
            
            // Brief pause to allow monitoring
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
