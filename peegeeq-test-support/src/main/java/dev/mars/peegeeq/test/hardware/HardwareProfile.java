package dev.mars.peegeeq.test.hardware;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable hardware profile capturing system specifications and capabilities.
 * 
 * This class provides a comprehensive snapshot of the hardware environment
 * where performance tests are executed, enabling meaningful comparison and
 * analysis of performance results across different systems.
 * 
 * Key Features:
 * - CPU specifications (model, cores, frequency, cache)
 * - Memory configuration (type, size, speed)
 * - Storage characteristics (type, capacity, interface)
 * - Network capabilities
 * - Operating system information
 * - JVM configuration details
 * 
 * Usage:
 * ```java
 * HardwareProfile profile = HardwareProfiler.captureProfile();
 * logger.info("Running tests on: {}", profile.getSystemDescription());
 * ```
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-19
 * @version 1.0
 */
public class HardwareProfile {
    
    // System identification
    private final String systemDescription;
    private final Instant captureTime;
    private final String hostname;
    private final String osName;
    private final String osVersion;
    private final String osArchitecture;
    
    // CPU specifications
    private final String cpuModel;
    private final int cpuCores;
    private final int cpuLogicalProcessors;
    private final long cpuMaxFrequencyHz;
    private final long cpuL1CacheSize;
    private final long cpuL2CacheSize;
    private final long cpuL3CacheSize;
    
    // Memory configuration
    private final long totalMemoryBytes;
    private final long availableMemoryBytes;
    private final String memoryType;
    private final long memorySpeedMHz;
    private final int memoryModules;
    
    // Storage characteristics
    private final String storageType; // SSD, HDD, NVMe, etc.
    private final long totalStorageBytes;
    private final long availableStorageBytes;
    private final String storageInterface; // SATA, NVMe, etc.
    
    // Network capabilities
    private final String networkInterface;
    private final long networkSpeedBps;
    
    // JVM configuration
    private final String javaVersion;
    private final String javaVendor;
    private final String jvmName;
    private final String jvmVersion;
    private final long jvmMaxHeapBytes;
    private final long jvmInitialHeapBytes;
    private final String gcAlgorithm;
    
    // Container environment (if applicable)
    private final boolean isContainerized;
    private final String containerRuntime;
    private final long containerMemoryLimitBytes;
    private final double containerCpuLimit;
    
    // Additional metadata
    private final Map<String, String> additionalProperties;
    
    /**
     * Private constructor - use HardwareProfiler.captureProfile() to create instances.
     */
    HardwareProfile(Builder builder) {
        this.systemDescription = builder.systemDescription;
        this.captureTime = builder.captureTime;
        this.hostname = builder.hostname;
        this.osName = builder.osName;
        this.osVersion = builder.osVersion;
        this.osArchitecture = builder.osArchitecture;
        
        this.cpuModel = builder.cpuModel;
        this.cpuCores = builder.cpuCores;
        this.cpuLogicalProcessors = builder.cpuLogicalProcessors;
        this.cpuMaxFrequencyHz = builder.cpuMaxFrequencyHz;
        this.cpuL1CacheSize = builder.cpuL1CacheSize;
        this.cpuL2CacheSize = builder.cpuL2CacheSize;
        this.cpuL3CacheSize = builder.cpuL3CacheSize;
        
        this.totalMemoryBytes = builder.totalMemoryBytes;
        this.availableMemoryBytes = builder.availableMemoryBytes;
        this.memoryType = builder.memoryType;
        this.memorySpeedMHz = builder.memorySpeedMHz;
        this.memoryModules = builder.memoryModules;
        
        this.storageType = builder.storageType;
        this.totalStorageBytes = builder.totalStorageBytes;
        this.availableStorageBytes = builder.availableStorageBytes;
        this.storageInterface = builder.storageInterface;
        
        this.networkInterface = builder.networkInterface;
        this.networkSpeedBps = builder.networkSpeedBps;
        
        this.javaVersion = builder.javaVersion;
        this.javaVendor = builder.javaVendor;
        this.jvmName = builder.jvmName;
        this.jvmVersion = builder.jvmVersion;
        this.jvmMaxHeapBytes = builder.jvmMaxHeapBytes;
        this.jvmInitialHeapBytes = builder.jvmInitialHeapBytes;
        this.gcAlgorithm = builder.gcAlgorithm;
        
        this.isContainerized = builder.isContainerized;
        this.containerRuntime = builder.containerRuntime;
        this.containerMemoryLimitBytes = builder.containerMemoryLimitBytes;
        this.containerCpuLimit = builder.containerCpuLimit;
        
        this.additionalProperties = Map.copyOf(builder.additionalProperties);
    }
    
    // Getters
    public String getSystemDescription() { return systemDescription; }
    public Instant getCaptureTime() { return captureTime; }
    public String getHostname() { return hostname; }
    public String getOsName() { return osName; }
    public String getOsVersion() { return osVersion; }
    public String getOsArchitecture() { return osArchitecture; }
    
    public String getCpuModel() { return cpuModel; }
    public int getCpuCores() { return cpuCores; }
    public int getCpuLogicalProcessors() { return cpuLogicalProcessors; }
    public long getCpuMaxFrequencyHz() { return cpuMaxFrequencyHz; }
    public long getCpuL1CacheSize() { return cpuL1CacheSize; }
    public long getCpuL2CacheSize() { return cpuL2CacheSize; }
    public long getCpuL3CacheSize() { return cpuL3CacheSize; }
    
    public long getTotalMemoryBytes() { return totalMemoryBytes; }
    public long getAvailableMemoryBytes() { return availableMemoryBytes; }
    public String getMemoryType() { return memoryType; }
    public long getMemorySpeedMHz() { return memorySpeedMHz; }
    public int getMemoryModules() { return memoryModules; }
    
    public String getStorageType() { return storageType; }
    public long getTotalStorageBytes() { return totalStorageBytes; }
    public long getAvailableStorageBytes() { return availableStorageBytes; }
    public String getStorageInterface() { return storageInterface; }
    
    public String getNetworkInterface() { return networkInterface; }
    public long getNetworkSpeedBps() { return networkSpeedBps; }
    
    public String getJavaVersion() { return javaVersion; }
    public String getJavaVendor() { return javaVendor; }
    public String getJvmName() { return jvmName; }
    public String getJvmVersion() { return jvmVersion; }
    public long getJvmMaxHeapBytes() { return jvmMaxHeapBytes; }
    public long getJvmInitialHeapBytes() { return jvmInitialHeapBytes; }
    public String getGcAlgorithm() { return gcAlgorithm; }
    
    public boolean isContainerized() { return isContainerized; }
    public String getContainerRuntime() { return containerRuntime; }
    public long getContainerMemoryLimitBytes() { return containerMemoryLimitBytes; }
    public double getContainerCpuLimit() { return containerCpuLimit; }
    
    public Map<String, String> getAdditionalProperties() { return additionalProperties; }
    
    /**
     * Get a human-readable summary of the hardware configuration.
     */
    public String getSummary() {
        return String.format("%s | %d cores @ %.1f GHz | %.1f GB RAM | %s storage",
            cpuModel,
            cpuCores,
            cpuMaxFrequencyHz / 1_000_000_000.0,
            totalMemoryBytes / (1024.0 * 1024.0 * 1024.0),
            storageType);
    }
    
    /**
     * Get memory information in GB for easier reading.
     */
    public double getTotalMemoryGB() {
        return totalMemoryBytes / (1024.0 * 1024.0 * 1024.0);
    }
    
    public double getAvailableMemoryGB() {
        return availableMemoryBytes / (1024.0 * 1024.0 * 1024.0);
    }
    
    public double getJvmMaxHeapGB() {
        return jvmMaxHeapBytes / (1024.0 * 1024.0 * 1024.0);
    }
    
    /**
     * Get CPU frequency in GHz for easier reading.
     */
    public double getCpuMaxFrequencyGHz() {
        return cpuMaxFrequencyHz / 1_000_000_000.0;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        HardwareProfile that = (HardwareProfile) o;
        return Objects.equals(systemDescription, that.systemDescription) &&
               Objects.equals(captureTime, that.captureTime);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(systemDescription, captureTime);
    }
    
    @Override
    public String toString() {
        return String.format("HardwareProfile{%s, captured at %s}", 
                           systemDescription, captureTime);
    }
    
    /**
     * Builder class for creating HardwareProfile instances.
     * Package-private - use HardwareProfiler.captureProfile().
     */
    static class Builder {
        String systemDescription;
        Instant captureTime = Instant.now();
        String hostname;
        String osName;
        String osVersion;
        String osArchitecture;
        
        String cpuModel;
        int cpuCores;
        int cpuLogicalProcessors;
        long cpuMaxFrequencyHz;
        long cpuL1CacheSize;
        long cpuL2CacheSize;
        long cpuL3CacheSize;
        
        long totalMemoryBytes;
        long availableMemoryBytes;
        String memoryType;
        long memorySpeedMHz;
        int memoryModules;
        
        String storageType;
        long totalStorageBytes;
        long availableStorageBytes;
        String storageInterface;
        
        String networkInterface;
        long networkSpeedBps;
        
        String javaVersion;
        String javaVendor;
        String jvmName;
        String jvmVersion;
        long jvmMaxHeapBytes;
        long jvmInitialHeapBytes;
        String gcAlgorithm;
        
        boolean isContainerized;
        String containerRuntime;
        long containerMemoryLimitBytes;
        double containerCpuLimit;
        
        Map<String, String> additionalProperties = Map.of();
        
        HardwareProfile build() {
            return new HardwareProfile(this);
        }
    }
}
