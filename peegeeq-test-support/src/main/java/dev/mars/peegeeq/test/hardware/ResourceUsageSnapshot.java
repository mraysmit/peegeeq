package dev.mars.peegeeq.test.hardware;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Immutable snapshot of system resource usage during a monitoring period.
 * 
 * This class captures comprehensive resource utilization statistics
 * collected during performance test execution, enabling correlation
 * between system resource consumption and test performance metrics.
 * 
 * Key Features:
 * - CPU utilization statistics (peak and average)
 * - Memory usage tracking (system and JVM)
 * - Network I/O statistics
 * - System load metrics
 * - Thread count monitoring
 * - Complete sample history
 * 
 * Usage:
 * ```java
 * ResourceUsageSnapshot snapshot = monitor.stopMonitoring();
 * 
 * if (snapshot.getPeakCpuUsage() > 80.0) {
 *     logger.warn("High CPU usage detected: {:.1f}%", snapshot.getPeakCpuUsage());
 * }
 * 
 * logger.info("Resource summary: {}", snapshot.getSummary());
 * ```
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-19
 * @version 1.0
 */
public class ResourceUsageSnapshot {
    
    private final Instant startTime;
    private final Instant endTime;
    private final int sampleCount;
    
    // CPU metrics
    private final double peakCpuUsage;
    private final double avgCpuUsage;
    
    // Memory metrics
    private final double peakMemoryUsage;
    private final double avgMemoryUsage;
    private final double peakJvmMemoryUsage;
    private final double avgJvmMemoryUsage;
    
    // Disk I/O metrics
    private final long peakDiskReadRate;
    private final long peakDiskWriteRate;
    
    // Network I/O metrics
    private final long peakNetworkReceiveRate;
    private final long peakNetworkSendRate;
    
    // System load metrics
    private final double peakSystemLoad;
    private final double avgSystemLoad;
    
    // Thread metrics
    private final int peakThreadCount;
    
    // Complete sample history
    private final List<SystemResourceMonitor.ResourceSample> samples;
    
    /**
     * Create a resource usage snapshot.
     */
    public ResourceUsageSnapshot(Instant startTime, Instant endTime, int sampleCount,
                               double peakCpuUsage, double avgCpuUsage,
                               double peakMemoryUsage, double avgMemoryUsage,
                               double peakJvmMemoryUsage, double avgJvmMemoryUsage,
                               long peakDiskReadRate, long peakDiskWriteRate,
                               long peakNetworkReceiveRate, long peakNetworkSendRate,
                               double peakSystemLoad, double avgSystemLoad,
                               int peakThreadCount,
                               List<SystemResourceMonitor.ResourceSample> samples) {
        this.startTime = startTime;
        this.endTime = endTime;
        this.sampleCount = sampleCount;
        this.peakCpuUsage = peakCpuUsage;
        this.avgCpuUsage = avgCpuUsage;
        this.peakMemoryUsage = peakMemoryUsage;
        this.avgMemoryUsage = avgMemoryUsage;
        this.peakJvmMemoryUsage = peakJvmMemoryUsage;
        this.avgJvmMemoryUsage = avgJvmMemoryUsage;
        this.peakDiskReadRate = peakDiskReadRate;
        this.peakDiskWriteRate = peakDiskWriteRate;
        this.peakNetworkReceiveRate = peakNetworkReceiveRate;
        this.peakNetworkSendRate = peakNetworkSendRate;
        this.peakSystemLoad = peakSystemLoad;
        this.avgSystemLoad = avgSystemLoad;
        this.peakThreadCount = peakThreadCount;
        this.samples = List.copyOf(samples);
    }
    
    /**
     * Create an empty snapshot for cases where no monitoring occurred.
     */
    public static ResourceUsageSnapshot empty(Instant startTime, Instant endTime) {
        return new ResourceUsageSnapshot(
            startTime, endTime, 0,
            0.0, 0.0, // CPU
            0.0, 0.0, // Memory
            0.0, 0.0, // JVM Memory
            0, 0,     // Disk I/O
            0, 0,     // Network I/O
            0.0, 0.0, // System load
            0,        // Thread count
            List.of() // Empty samples
        );
    }
    
    // Getters
    public Instant getStartTime() { return startTime; }
    public Instant getEndTime() { return endTime; }
    public Duration getDuration() { return Duration.between(startTime, endTime); }
    public int getSampleCount() { return sampleCount; }
    
    public double getPeakCpuUsage() { return peakCpuUsage; }
    public double getAvgCpuUsage() { return avgCpuUsage; }
    
    public double getPeakMemoryUsage() { return peakMemoryUsage; }
    public double getAvgMemoryUsage() { return avgMemoryUsage; }
    public double getPeakJvmMemoryUsage() { return peakJvmMemoryUsage; }
    public double getAvgJvmMemoryUsage() { return avgJvmMemoryUsage; }
    
    public long getPeakDiskReadRate() { return peakDiskReadRate; }
    public long getPeakDiskWriteRate() { return peakDiskWriteRate; }
    
    public long getPeakNetworkReceiveRate() { return peakNetworkReceiveRate; }
    public long getPeakNetworkSendRate() { return peakNetworkSendRate; }
    
    public double getPeakSystemLoad() { return peakSystemLoad; }
    public double getAvgSystemLoad() { return avgSystemLoad; }
    
    public int getPeakThreadCount() { return peakThreadCount; }
    
    public List<SystemResourceMonitor.ResourceSample> getSamples() { return samples; }
    
    /**
     * Check if this snapshot indicates high resource usage.
     */
    public boolean isHighResourceUsage() {
        return peakCpuUsage > 80.0 || 
               peakMemoryUsage > 85.0 || 
               peakJvmMemoryUsage > 90.0 ||
               peakSystemLoad > 0.8;
    }
    
    /**
     * Check if this snapshot indicates resource constraints.
     */
    public boolean hasResourceConstraints() {
        return peakCpuUsage > 95.0 || 
               peakMemoryUsage > 95.0 || 
               peakJvmMemoryUsage > 95.0 ||
               peakSystemLoad > 1.0;
    }
    
    /**
     * Get a human-readable summary of resource usage.
     */
    public String getSummary() {
        if (sampleCount == 0) {
            return "No resource monitoring data available";
        }
        
        return String.format(
            "Resource Usage Summary (%d samples over %dms): " +
            "CPU: %.1f%% peak (%.1f%% avg), " +
            "Memory: %.1f%% peak (%.1f%% avg), " +
            "JVM: %.1f%% peak (%.1f%% avg), " +
            "Load: %.2f peak (%.2f avg), " +
            "Threads: %d peak",
            sampleCount, getDuration().toMillis(),
            peakCpuUsage, avgCpuUsage,
            peakMemoryUsage, avgMemoryUsage,
            peakJvmMemoryUsage, avgJvmMemoryUsage,
            peakSystemLoad, avgSystemLoad,
            peakThreadCount
        );
    }
    
    /**
     * Get detailed resource usage information.
     */
    public String getDetailedSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Resource Usage Snapshot ===\n");
        sb.append(String.format("Duration: %dms (%d samples)\n", getDuration().toMillis(), sampleCount));
        sb.append(String.format("Period: %s to %s\n", startTime, endTime));
        sb.append("\n");
        
        if (sampleCount > 0) {
            sb.append("CPU Usage:\n");
            sb.append(String.format("  Peak: %.1f%%\n", peakCpuUsage));
            sb.append(String.format("  Average: %.1f%%\n", avgCpuUsage));
            sb.append("\n");
            
            sb.append("System Memory:\n");
            sb.append(String.format("  Peak: %.1f%%\n", peakMemoryUsage));
            sb.append(String.format("  Average: %.1f%%\n", avgMemoryUsage));
            sb.append("\n");
            
            sb.append("JVM Memory:\n");
            sb.append(String.format("  Peak: %.1f%%\n", peakJvmMemoryUsage));
            sb.append(String.format("  Average: %.1f%%\n", avgJvmMemoryUsage));
            sb.append("\n");
            
            if (peakNetworkReceiveRate > 0 || peakNetworkSendRate > 0) {
                sb.append("Network I/O:\n");
                sb.append(String.format("  Peak Receive: %.1f KB/s\n", peakNetworkReceiveRate / 1024.0));
                sb.append(String.format("  Peak Send: %.1f KB/s\n", peakNetworkSendRate / 1024.0));
                sb.append("\n");
            }
            
            sb.append("System Load:\n");
            sb.append(String.format("  Peak: %.2f\n", peakSystemLoad));
            sb.append(String.format("  Average: %.2f\n", avgSystemLoad));
            sb.append("\n");
            
            sb.append(String.format("Peak Thread Count: %d\n", peakThreadCount));
            
            // Resource usage warnings
            if (isHighResourceUsage()) {
                sb.append("\n⚠️  HIGH RESOURCE USAGE DETECTED:\n");
                if (peakCpuUsage > 80.0) {
                    sb.append(String.format("  - High CPU usage: %.1f%%\n", peakCpuUsage));
                }
                if (peakMemoryUsage > 85.0) {
                    sb.append(String.format("  - High memory usage: %.1f%%\n", peakMemoryUsage));
                }
                if (peakJvmMemoryUsage > 90.0) {
                    sb.append(String.format("  - High JVM memory usage: %.1f%%\n", peakJvmMemoryUsage));
                }
                if (peakSystemLoad > 0.8) {
                    sb.append(String.format("  - High system load: %.2f\n", peakSystemLoad));
                }
            }
            
            if (hasResourceConstraints()) {
                sb.append("\n🚨 RESOURCE CONSTRAINTS DETECTED:\n");
                sb.append("  Performance results may be affected by resource limitations!\n");
            }
        } else {
            sb.append("No monitoring data collected.\n");
        }
        
        return sb.toString();
    }
    
    /**
     * Convert resource usage to metrics map for integration with performance metrics.
     */
    public java.util.Map<String, Object> toMetricsMap() {
        java.util.Map<String, Object> metrics = new java.util.HashMap<>();
        
        metrics.put("resource.monitoring.duration_ms", getDuration().toMillis());
        metrics.put("resource.monitoring.sample_count", sampleCount);
        
        metrics.put("resource.cpu.peak_usage_percent", peakCpuUsage);
        metrics.put("resource.cpu.avg_usage_percent", avgCpuUsage);
        
        metrics.put("resource.memory.peak_usage_percent", peakMemoryUsage);
        metrics.put("resource.memory.avg_usage_percent", avgMemoryUsage);
        
        metrics.put("resource.jvm.peak_memory_usage_percent", peakJvmMemoryUsage);
        metrics.put("resource.jvm.avg_memory_usage_percent", avgJvmMemoryUsage);
        
        metrics.put("resource.network.peak_receive_rate_bps", peakNetworkReceiveRate);
        metrics.put("resource.network.peak_send_rate_bps", peakNetworkSendRate);
        
        metrics.put("resource.system.peak_load", peakSystemLoad);
        metrics.put("resource.system.avg_load", avgSystemLoad);
        
        metrics.put("resource.threads.peak_count", peakThreadCount);
        
        metrics.put("resource.usage.is_high", isHighResourceUsage());
        metrics.put("resource.usage.has_constraints", hasResourceConstraints());
        
        return metrics;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ResourceUsageSnapshot that = (ResourceUsageSnapshot) o;
        return Objects.equals(startTime, that.startTime) &&
               Objects.equals(endTime, that.endTime) &&
               sampleCount == that.sampleCount;
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(startTime, endTime, sampleCount);
    }
    
    @Override
    public String toString() {
        return String.format("ResourceUsageSnapshot{%s}", getSummary());
    }
}
