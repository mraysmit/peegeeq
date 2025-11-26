package dev.mars.peegeeq.db.performance;

import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SimplePerformanceMonitor.
 */
@Tag(TestCategories.CORE)
class SimplePerformanceMonitorTest {
    
    private SimplePerformanceMonitor monitor;
    
    @BeforeEach
    void setUp() {
        monitor = new SimplePerformanceMonitor();
    }
    
    @Test
    @DisplayName("Should track query execution times")
    void shouldTrackQueryTimes() {
        // Given
        Duration query1 = Duration.ofMillis(50);
        Duration query2 = Duration.ofMillis(100);
        
        // When
        monitor.recordQueryTime(query1);
        monitor.recordQueryTime(query2);
        
        // Then
        assertEquals(2, monitor.getQueryCount());
        assertEquals(75.0, monitor.getAverageQueryTime(), 0.1);
        assertEquals(100, monitor.getMaxQueryTime());
    }
    
    @Test
    @DisplayName("Should track connection acquisition times")
    void shouldTrackConnectionTimes() {
        // Given
        Duration conn1 = Duration.ofMillis(10);
        Duration conn2 = Duration.ofMillis(30);
        
        // When
        monitor.recordConnectionTime(conn1);
        monitor.recordConnectionTime(conn2);
        
        // Then
        assertEquals(2, monitor.getConnectionCount());
        assertEquals(20.0, monitor.getAverageConnectionTime(), 0.1);
        assertEquals(30, monitor.getMaxConnectionTime());
    }
    
    @Test
    @DisplayName("Should track connection failures")
    void shouldTrackConnectionFailures() {
        // Given & When
        monitor.recordConnectionTime(Duration.ofMillis(10));
        monitor.recordConnectionFailure();
        monitor.recordConnectionFailure();
        
        // Then
        assertEquals(1, monitor.getConnectionCount());
        assertEquals(2, monitor.getConnectionFailures());
        assertEquals(0.67, monitor.getConnectionFailureRate(), 0.01);
    }
    
    @Test
    @DisplayName("Should handle zero counts gracefully")
    void shouldHandleZeroCounts() {
        // When & Then
        assertEquals(0, monitor.getQueryCount());
        assertEquals(0, monitor.getConnectionCount());
        assertEquals(0.0, monitor.getAverageQueryTime());
        assertEquals(0.0, monitor.getAverageConnectionTime());
        assertEquals(0.0, monitor.getConnectionFailureRate());
    }
    
    @Test
    @DisplayName("Should reset metrics correctly")
    void shouldResetMetrics() {
        // Given
        monitor.recordQueryTime(Duration.ofMillis(100));
        monitor.recordConnectionTime(Duration.ofMillis(50));
        monitor.recordConnectionFailure();
        
        // When
        monitor.reset();
        
        // Then
        assertEquals(0, monitor.getQueryCount());
        assertEquals(0, monitor.getConnectionCount());
        assertEquals(0, monitor.getConnectionFailures());
        assertEquals(0.0, monitor.getAverageQueryTime());
        assertEquals(0.0, monitor.getAverageConnectionTime());
        assertEquals(0, monitor.getMaxQueryTime());
        assertEquals(0, monitor.getMaxConnectionTime());
    }
    
    @Test
    @DisplayName("Should provide timing context for measurements")
    void shouldProvideTimingContext() throws InterruptedException {
        // Given
        SimplePerformanceMonitor.TimingContext timing = monitor.startTiming();
        
        // When
        Thread.sleep(10); // Small delay
        timing.recordAsQuery();
        
        // Then
        assertEquals(1, monitor.getQueryCount());
        assertTrue(monitor.getAverageQueryTime() >= 10, "Should record at least 10ms");
        assertTrue(timing.getElapsed().toMillis() >= 10, "Should track elapsed time");
    }
    
    @Test
    @DisplayName("Should update max values correctly")
    void shouldUpdateMaxValues() {
        // Given & When
        monitor.recordQueryTime(Duration.ofMillis(50));
        monitor.recordQueryTime(Duration.ofMillis(200)); // New max
        monitor.recordQueryTime(Duration.ofMillis(100)); // Lower than max
        
        monitor.recordConnectionTime(Duration.ofMillis(20));
        monitor.recordConnectionTime(Duration.ofMillis(80)); // New max
        monitor.recordConnectionTime(Duration.ofMillis(40)); // Lower than max
        
        // Then
        assertEquals(200, monitor.getMaxQueryTime());
        assertEquals(80, monitor.getMaxConnectionTime());
    }
    
    @Test
    @DisplayName("Should log performance metrics without errors")
    void shouldLogPerformanceMetrics() {
        // Given
        monitor.recordQueryTime(Duration.ofMillis(100));
        monitor.recordConnectionTime(Duration.ofMillis(50));
        
        // When & Then - Should not throw any exceptions
        assertDoesNotThrow(() -> monitor.logPerformanceMetrics());
    }
}
