package dev.mars.peegeeq.test.hardware;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.NetworkIF;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Real-time system resource monitor for performance test correlation.
 * 
 * This class continuously monitors system resources during performance tests,
 * providing real-time metrics that can be correlated with test performance
 * to identify resource bottlenecks and system constraints.
 * 
 * Key Features:
 * - Real-time CPU utilization monitoring
 * - Memory usage tracking (system and JVM)
 * - Disk I/O monitoring
 * - Network I/O tracking
 * - System load average
 * - Resource usage snapshots
 * 
 * Usage:
 * ```java
 * SystemResourceMonitor monitor = new SystemResourceMonitor();
 * monitor.startMonitoring(Duration.ofSeconds(1));
 * 
 * // Run performance test
 * runPerformanceTest();
 * 
 * ResourceUsageSnapshot snapshot = monitor.stopMonitoring();
 * logger.info("Peak CPU usage: {:.1f}%", snapshot.getPeakCpuUsage());
 * ```
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-19
 * @version 1.0
 */
public class SystemResourceMonitor {
    private static final Logger logger = LoggerFactory.getLogger(SystemResourceMonitor.class);
    
    private final SystemInfo systemInfo;
    private final HardwareAbstractionLayer hardware;
    private final CentralProcessor processor;
    private final GlobalMemory memory;
    private final List<NetworkIF> networkIFs;
    
    private final AtomicBoolean isMonitoring = new AtomicBoolean(false);
    private ScheduledExecutorService scheduler;
    private final List<ResourceSample> samples = new CopyOnWriteArrayList<>();
    
    private Instant monitoringStartTime;
    private Instant monitoringEndTime;
    
    // Previous values for calculating deltas
    private long[] prevTicks;
    private long prevNetworkBytesReceived = 0;
    private long prevNetworkBytesSent = 0;
    
    /**
     * Create a new system resource monitor.
     */
    public SystemResourceMonitor() {
        this.systemInfo = new SystemInfo();
        this.hardware = systemInfo.getHardware();
        this.processor = hardware.getProcessor();
        this.memory = hardware.getMemory();
        this.networkIFs = hardware.getNetworkIFs();
        
        // Initialize previous CPU ticks
        this.prevTicks = processor.getSystemCpuLoadTicks();
        
        logger.debug("SystemResourceMonitor initialized");
    }
    
    /**
     * Start monitoring system resources at the specified interval.
     * 
     * @param interval monitoring interval
     * @throws IllegalStateException if monitoring is already active
     */
    public void startMonitoring(Duration interval) {
        if (isMonitoring.get()) {
            throw new IllegalStateException("Monitoring is already active");
        }
        
        logger.info("Starting system resource monitoring (interval: {}ms)", interval.toMillis());
        
        monitoringStartTime = Instant.now();
        samples.clear();
        
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "system-resource-monitor");
            t.setDaemon(true);
            return t;
        });
        
        isMonitoring.set(true);
        
        // Schedule monitoring task
        scheduler.scheduleAtFixedRate(this::collectSample, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
        
        logger.debug("System resource monitoring started");
    }
    
    /**
     * Stop monitoring and return a summary of resource usage.
     * 
     * @return ResourceUsageSnapshot containing monitoring results
     * @throws IllegalStateException if monitoring is not active
     */
    public ResourceUsageSnapshot stopMonitoring() {
        if (!isMonitoring.get()) {
            throw new IllegalStateException("Monitoring is not active");
        }
        
        logger.info("Stopping system resource monitoring");
        
        monitoringEndTime = Instant.now();
        isMonitoring.set(false);
        
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                scheduler.shutdownNow();
            }
        }
        
        // Create summary snapshot
        ResourceUsageSnapshot snapshot = createSnapshot();
        
        logger.info("System resource monitoring stopped. Collected {} samples over {}ms",
                   samples.size(), Duration.between(monitoringStartTime, monitoringEndTime).toMillis());
        
        return snapshot;
    }
    
    /**
     * Check if monitoring is currently active.
     */
    public boolean isMonitoring() {
        return isMonitoring.get();
    }
    
    /**
     * Get the current number of collected samples.
     */
    public int getSampleCount() {
        return samples.size();
    }
    
    /**
     * Get a copy of all collected samples.
     */
    public List<ResourceSample> getSamples() {
        return List.copyOf(samples);
    }
    
    private void collectSample() {
        try {
            Instant timestamp = Instant.now();
            
            // CPU utilization
            long[] ticks = processor.getSystemCpuLoadTicks();
            double cpuUsage = processor.getSystemCpuLoadBetweenTicks(prevTicks) * 100.0;
            prevTicks = ticks;
            
            // Memory usage
            long totalMemory = memory.getTotal();
            long availableMemory = memory.getAvailable();
            long usedMemory = totalMemory - availableMemory;
            double memoryUsage = (usedMemory * 100.0) / totalMemory;
            
            // JVM memory
            Runtime runtime = Runtime.getRuntime();
            long jvmTotalMemory = runtime.totalMemory();
            long jvmFreeMemory = runtime.freeMemory();
            long jvmUsedMemory = jvmTotalMemory - jvmFreeMemory;
            long jvmMaxMemory = runtime.maxMemory();
            double jvmMemoryUsage = (jvmUsedMemory * 100.0) / jvmMaxMemory;
            
            // Network I/O
            long networkBytesReceived = 0;
            long networkBytesSent = 0;
            for (NetworkIF netIF : networkIFs) {
                networkBytesReceived += netIF.getBytesRecv();
                networkBytesSent += netIF.getBytesSent();
            }
            
            // Calculate network rates (bytes per second since last sample)
            long networkReceiveRate = 0;
            long networkSendRate = 0;
            if (prevNetworkBytesReceived > 0) {
                long timeDeltaMs = samples.isEmpty() ? 1000 : 
                    Duration.between(samples.get(samples.size() - 1).getTimestamp(), timestamp).toMillis();
                if (timeDeltaMs > 0) {
                    networkReceiveRate = ((networkBytesReceived - prevNetworkBytesReceived) * 1000) / timeDeltaMs;
                    networkSendRate = ((networkBytesSent - prevNetworkBytesSent) * 1000) / timeDeltaMs;
                }
            }
            prevNetworkBytesReceived = networkBytesReceived;
            prevNetworkBytesSent = networkBytesSent;
            
            // System load (simplified - using CPU usage as proxy)
            double systemLoad = cpuUsage / 100.0;
            
            // Create sample
            ResourceSample sample = new ResourceSample(
                timestamp,
                cpuUsage,
                memoryUsage,
                usedMemory,
                totalMemory,
                jvmMemoryUsage,
                jvmUsedMemory,
                jvmMaxMemory,
                0, // diskReadRate - placeholder
                0, // diskWriteRate - placeholder
                networkReceiveRate,
                networkSendRate,
                systemLoad,
                Thread.activeCount()
            );
            
            samples.add(sample);
            
            logger.trace("Collected resource sample: CPU={:.1f}%, Memory={:.1f}%, JVM={:.1f}%",
                        cpuUsage, memoryUsage, jvmMemoryUsage);
            
        } catch (Exception e) {
            logger.warn("Failed to collect resource sample", e);
        }
    }
    
    private ResourceUsageSnapshot createSnapshot() {
        if (samples.isEmpty()) {
            logger.warn("No samples collected during monitoring");
            return ResourceUsageSnapshot.empty(monitoringStartTime, monitoringEndTime);
        }
        
        // Calculate statistics
        double peakCpuUsage = samples.stream().mapToDouble(ResourceSample::getCpuUsage).max().orElse(0.0);
        double avgCpuUsage = samples.stream().mapToDouble(ResourceSample::getCpuUsage).average().orElse(0.0);
        
        double peakMemoryUsage = samples.stream().mapToDouble(ResourceSample::getMemoryUsage).max().orElse(0.0);
        double avgMemoryUsage = samples.stream().mapToDouble(ResourceSample::getMemoryUsage).average().orElse(0.0);
        
        double peakJvmMemoryUsage = samples.stream().mapToDouble(ResourceSample::getJvmMemoryUsage).max().orElse(0.0);
        double avgJvmMemoryUsage = samples.stream().mapToDouble(ResourceSample::getJvmMemoryUsage).average().orElse(0.0);
        
        long peakNetworkReceiveRate = samples.stream().mapToLong(ResourceSample::getNetworkReceiveRate).max().orElse(0L);
        long peakNetworkSendRate = samples.stream().mapToLong(ResourceSample::getNetworkSendRate).max().orElse(0L);
        
        double peakSystemLoad = samples.stream().mapToDouble(ResourceSample::getSystemLoad).max().orElse(0.0);
        double avgSystemLoad = samples.stream().mapToDouble(ResourceSample::getSystemLoad).average().orElse(0.0);
        
        int peakThreadCount = samples.stream().mapToInt(ResourceSample::getThreadCount).max().orElse(0);
        
        return new ResourceUsageSnapshot(
            monitoringStartTime,
            monitoringEndTime,
            samples.size(),
            peakCpuUsage,
            avgCpuUsage,
            peakMemoryUsage,
            avgMemoryUsage,
            peakJvmMemoryUsage,
            avgJvmMemoryUsage,
            0, // peakDiskReadRate - placeholder
            0, // peakDiskWriteRate - placeholder
            peakNetworkReceiveRate,
            peakNetworkSendRate,
            peakSystemLoad,
            avgSystemLoad,
            peakThreadCount,
            List.copyOf(samples)
        );
    }

    /**
     * Get current CPU usage percentage.
     * Returns the most recent sample or 0.0 if no samples available.
     */
    public double getCurrentCpuUsage() {
        if (samples.isEmpty()) {
            return 0.0;
        }
        return samples.get(samples.size() - 1).getCpuUsage();
    }

    /**
     * Get current memory usage percentage.
     * Returns the most recent sample or 0.0 if no samples available.
     */
    public double getCurrentMemoryUsage() {
        if (samples.isEmpty()) {
            return 0.0;
        }
        return samples.get(samples.size() - 1).getMemoryUsage();
    }

    /**
     * Get current JVM memory usage percentage.
     * Returns the most recent sample or 0.0 if no samples available.
     */
    public double getCurrentJvmMemoryUsage() {
        if (samples.isEmpty()) {
            return 0.0;
        }
        return samples.get(samples.size() - 1).getJvmMemoryUsage();
    }

    /**
     * Get current system load average.
     * Returns the most recent sample or 0.0 if no samples available.
     */
    public double getCurrentSystemLoad() {
        if (samples.isEmpty()) {
            return 0.0;
        }
        return samples.get(samples.size() - 1).getSystemLoad();
    }

    /**
     * Get current thread count.
     * Returns the most recent sample or 0 if no samples available.
     */
    public int getCurrentThreadCount() {
        if (samples.isEmpty()) {
            return 0;
        }
        return samples.get(samples.size() - 1).getThreadCount();
    }

    /**
     * Individual resource sample captured at a specific point in time.
     */
    public static class ResourceSample {
        private final Instant timestamp;
        private final double cpuUsage;
        private final double memoryUsage;
        private final long usedMemoryBytes;
        private final long totalMemoryBytes;
        private final double jvmMemoryUsage;
        private final long jvmUsedMemoryBytes;
        private final long jvmMaxMemoryBytes;
        private final long diskReadRate;
        private final long diskWriteRate;
        private final long networkReceiveRate;
        private final long networkSendRate;
        private final double systemLoad;
        private final int threadCount;
        
        public ResourceSample(Instant timestamp, double cpuUsage, double memoryUsage,
                            long usedMemoryBytes, long totalMemoryBytes,
                            double jvmMemoryUsage, long jvmUsedMemoryBytes, long jvmMaxMemoryBytes,
                            long diskReadRate, long diskWriteRate,
                            long networkReceiveRate, long networkSendRate,
                            double systemLoad, int threadCount) {
            this.timestamp = timestamp;
            this.cpuUsage = cpuUsage;
            this.memoryUsage = memoryUsage;
            this.usedMemoryBytes = usedMemoryBytes;
            this.totalMemoryBytes = totalMemoryBytes;
            this.jvmMemoryUsage = jvmMemoryUsage;
            this.jvmUsedMemoryBytes = jvmUsedMemoryBytes;
            this.jvmMaxMemoryBytes = jvmMaxMemoryBytes;
            this.diskReadRate = diskReadRate;
            this.diskWriteRate = diskWriteRate;
            this.networkReceiveRate = networkReceiveRate;
            this.networkSendRate = networkSendRate;
            this.systemLoad = systemLoad;
            this.threadCount = threadCount;
        }
        
        // Getters
        public Instant getTimestamp() { return timestamp; }
        public double getCpuUsage() { return cpuUsage; }
        public double getMemoryUsage() { return memoryUsage; }
        public long getUsedMemoryBytes() { return usedMemoryBytes; }
        public long getTotalMemoryBytes() { return totalMemoryBytes; }
        public double getJvmMemoryUsage() { return jvmMemoryUsage; }
        public long getJvmUsedMemoryBytes() { return jvmUsedMemoryBytes; }
        public long getJvmMaxMemoryBytes() { return jvmMaxMemoryBytes; }
        public long getDiskReadRate() { return diskReadRate; }
        public long getDiskWriteRate() { return diskWriteRate; }
        public long getNetworkReceiveRate() { return networkReceiveRate; }
        public long getNetworkSendRate() { return networkSendRate; }
        public double getSystemLoad() { return systemLoad; }
        public int getThreadCount() { return threadCount; }
        
        @Override
        public String toString() {
            return String.format("ResourceSample{time=%s, cpu=%.1f%%, mem=%.1f%%, jvm=%.1f%%, load=%.2f}",
                               timestamp, cpuUsage, memoryUsage, jvmMemoryUsage, systemLoad);
        }
    }
}
