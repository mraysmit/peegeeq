package dev.mars.peegeeq.test.hardware;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.NetworkIF;
import oshi.software.os.OperatingSystem;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.RuntimeMXBean;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hardware profiler that captures comprehensive system specifications.
 * 
 * This class uses OSHI (Operating System and Hardware Information) library
 * to gather detailed hardware and system information for performance test
 * context and analysis.
 * 
 * Key Features:
 * - Cross-platform hardware detection
 * - CPU specifications and capabilities
 * - Memory configuration and availability
 * - Storage characteristics
 * - Network interface information
 * - JVM configuration details
 * - Container environment detection
 * 
 * Usage:
 * ```java
 * HardwareProfile profile = HardwareProfiler.captureProfile();
 * logger.info("System: {}", profile.getSummary());
 * ```
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-19
 * @version 1.0
 */
public class HardwareProfiler {
    private static final Logger logger = LoggerFactory.getLogger(HardwareProfiler.class);
    
    private static volatile HardwareProfile cachedProfile;
    private static volatile long lastCaptureTime = 0;
    private static final long CACHE_DURATION_MS = 60_000; // Cache for 1 minute
    
    /**
     * Capture a comprehensive hardware profile of the current system.
     * 
     * This method performs a full system scan and may take several hundred
     * milliseconds to complete. Results are cached for 1 minute to avoid
     * repeated expensive operations.
     * 
     * @return immutable HardwareProfile containing system specifications
     */
    public static HardwareProfile captureProfile() {
        long now = System.currentTimeMillis();
        
        // Return cached profile if still valid
        if (cachedProfile != null && (now - lastCaptureTime) < CACHE_DURATION_MS) {
            logger.debug("Returning cached hardware profile (age: {}ms)", now - lastCaptureTime);
            return cachedProfile;
        }
        
        logger.debug("Capturing new hardware profile...");
        long startTime = System.currentTimeMillis();
        
        try {
            HardwareProfile.Builder builder = new HardwareProfile.Builder();
            
            // Initialize OSHI
            SystemInfo systemInfo = new SystemInfo();
            HardwareAbstractionLayer hardware = systemInfo.getHardware();
            OperatingSystem os = systemInfo.getOperatingSystem();
            
            // Capture system information
            captureSystemInfo(builder, os);
            
            // Capture CPU information
            captureCpuInfo(builder, hardware.getProcessor());
            
            // Capture memory information
            captureMemoryInfo(builder, hardware.getMemory());
            
            // Capture storage information
            captureStorageInfo(builder, hardware.getDiskStores());
            
            // Capture network information
            captureNetworkInfo(builder, hardware.getNetworkIFs());
            
            // Capture JVM information
            captureJvmInfo(builder);
            
            // Capture container information
            captureContainerInfo(builder);
            
            // Build the profile
            cachedProfile = builder.build();
            lastCaptureTime = now;
            
            long duration = System.currentTimeMillis() - startTime;
            logger.info("Hardware profile captured in {}ms: {}", duration, cachedProfile.getSummary());
            
            return cachedProfile;
            
        } catch (Exception e) {
            logger.error("Failed to capture hardware profile", e);
            
            // Return a minimal profile with basic information
            return createFallbackProfile();
        }
    }
    
    /**
     * Force a fresh capture of the hardware profile, bypassing cache.
     */
    public static HardwareProfile captureProfileFresh() {
        cachedProfile = null;
        lastCaptureTime = 0;
        return captureProfile();
    }
    
    /**
     * Get the cached hardware profile if available, otherwise capture a new one.
     */
    public static HardwareProfile getCachedProfile() {
        return captureProfile(); // Will return cached if available
    }
    
    private static void captureSystemInfo(HardwareProfile.Builder builder, OperatingSystem os) {
        try {
            builder.hostname = os.getNetworkParams().getHostName();
            builder.osName = os.getFamily();
            builder.osVersion = os.getVersionInfo().getVersion();
            builder.osArchitecture = System.getProperty("os.arch");
            
            builder.systemDescription = String.format("%s %s (%s) on %s",
                builder.osName, builder.osVersion, builder.osArchitecture, builder.hostname);
                
            logger.debug("Captured system info: {}", builder.systemDescription);
        } catch (Exception e) {
            logger.warn("Failed to capture system info", e);
            builder.hostname = "unknown";
            builder.osName = System.getProperty("os.name", "unknown");
            builder.osVersion = System.getProperty("os.version", "unknown");
            builder.osArchitecture = System.getProperty("os.arch", "unknown");
            builder.systemDescription = "Unknown System";
        }
    }
    
    private static void captureCpuInfo(HardwareProfile.Builder builder, CentralProcessor processor) {
        try {
            builder.cpuModel = processor.getProcessorIdentifier().getName();
            builder.cpuCores = processor.getPhysicalProcessorCount();
            builder.cpuLogicalProcessors = processor.getLogicalProcessorCount();
            builder.cpuMaxFrequencyHz = processor.getMaxFreq();
            
            // Cache information (may not be available on all systems)
            var caches = processor.getProcessorCaches();
            if (!caches.isEmpty()) {
                builder.cpuL1CacheSize = caches.stream()
                    .filter(cache -> cache.getLevel() == 1)
                    .mapToLong(cache -> cache.getCacheSize())
                    .sum();
                builder.cpuL2CacheSize = caches.stream()
                    .filter(cache -> cache.getLevel() == 2)
                    .mapToLong(cache -> cache.getCacheSize())
                    .sum();
                builder.cpuL3CacheSize = caches.stream()
                    .filter(cache -> cache.getLevel() == 3)
                    .mapToLong(cache -> cache.getCacheSize())
                    .sum();
            }
            
            logger.debug("Captured CPU info: {} ({} cores, {} logical, {:.1f} GHz)",
                builder.cpuModel, builder.cpuCores, builder.cpuLogicalProcessors,
                builder.cpuMaxFrequencyHz / 1_000_000_000.0);
        } catch (Exception e) {
            logger.warn("Failed to capture CPU info", e);
            builder.cpuModel = "Unknown CPU";
            builder.cpuCores = Runtime.getRuntime().availableProcessors();
            builder.cpuLogicalProcessors = builder.cpuCores;
        }
    }
    
    private static void captureMemoryInfo(HardwareProfile.Builder builder, GlobalMemory memory) {
        try {
            builder.totalMemoryBytes = memory.getTotal();
            builder.availableMemoryBytes = memory.getAvailable();
            
            // Memory modules information
            var physicalMemory = memory.getPhysicalMemory();
            if (!physicalMemory.isEmpty()) {
                builder.memoryModules = physicalMemory.size();
                // Get memory type and speed from first module
                var firstModule = physicalMemory.get(0);
                builder.memoryType = firstModule.getMemoryType();
                builder.memorySpeedMHz = firstModule.getClockSpeed() / 1_000_000;
            } else {
                builder.memoryType = "Unknown";
                builder.memoryModules = 1;
            }
            
            logger.debug("Captured memory info: {:.1f} GB total, {:.1f} GB available, {} modules",
                builder.totalMemoryBytes / (1024.0 * 1024.0 * 1024.0),
                builder.availableMemoryBytes / (1024.0 * 1024.0 * 1024.0),
                builder.memoryModules);
        } catch (Exception e) {
            logger.warn("Failed to capture memory info", e);
            Runtime runtime = Runtime.getRuntime();
            builder.totalMemoryBytes = runtime.maxMemory();
            builder.availableMemoryBytes = runtime.freeMemory();
            builder.memoryType = "Unknown";
            builder.memoryModules = 1;
        }
    }
    
    private static void captureStorageInfo(HardwareProfile.Builder builder, List<oshi.hardware.HWDiskStore> diskStores) {
        try {
            if (!diskStores.isEmpty()) {
                // Use the first/primary disk store
                var primaryDisk = diskStores.get(0);
                builder.totalStorageBytes = primaryDisk.getSize();
                
                // Determine storage type based on disk model/name
                String model = primaryDisk.getModel().toLowerCase();
                if (model.contains("ssd") || model.contains("nvme")) {
                    builder.storageType = "SSD";
                } else if (model.contains("hdd") || model.contains("hard")) {
                    builder.storageType = "HDD";
                } else {
                    builder.storageType = "Unknown";
                }
                
                // Try to determine interface
                if (model.contains("nvme")) {
                    builder.storageInterface = "NVMe";
                } else if (model.contains("sata")) {
                    builder.storageInterface = "SATA";
                } else {
                    builder.storageInterface = "Unknown";
                }
                
                // Available space is harder to determine at disk level
                builder.availableStorageBytes = builder.totalStorageBytes; // Placeholder
                
                logger.debug("Captured storage info: {} {} ({:.1f} GB)",
                    builder.storageType, builder.storageInterface,
                    builder.totalStorageBytes / (1024.0 * 1024.0 * 1024.0));
            } else {
                builder.storageType = "Unknown";
                builder.storageInterface = "Unknown";
                builder.totalStorageBytes = 0;
                builder.availableStorageBytes = 0;
            }
        } catch (Exception e) {
            logger.warn("Failed to capture storage info", e);
            builder.storageType = "Unknown";
            builder.storageInterface = "Unknown";
        }
    }
    
    private static void captureNetworkInfo(HardwareProfile.Builder builder, List<NetworkIF> networkIFs) {
        try {
            // Find the first active network interface
            for (NetworkIF netIF : networkIFs) {
                if (netIF.getBytesRecv() > 0 || netIF.getBytesSent() > 0) {
                    builder.networkInterface = netIF.getDisplayName();
                    builder.networkSpeedBps = netIF.getSpeed();
                    
                    logger.debug("Captured network info: {} ({:.1f} Mbps)",
                        builder.networkInterface, builder.networkSpeedBps / 1_000_000.0);
                    break;
                }
            }
            
            if (builder.networkInterface == null) {
                builder.networkInterface = "Unknown";
                builder.networkSpeedBps = 0;
            }
        } catch (Exception e) {
            logger.warn("Failed to capture network info", e);
            builder.networkInterface = "Unknown";
            builder.networkSpeedBps = 0;
        }
    }
    
    private static void captureJvmInfo(HardwareProfile.Builder builder) {
        try {
            RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
            MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
            
            builder.javaVersion = System.getProperty("java.version");
            builder.javaVendor = System.getProperty("java.vendor");
            builder.jvmName = runtime.getVmName();
            builder.jvmVersion = runtime.getVmVersion();
            
            builder.jvmMaxHeapBytes = memory.getHeapMemoryUsage().getMax();
            builder.jvmInitialHeapBytes = memory.getHeapMemoryUsage().getInit();
            
            // Get GC algorithm
            List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
            if (!gcBeans.isEmpty()) {
                builder.gcAlgorithm = gcBeans.get(0).getName();
            } else {
                builder.gcAlgorithm = "Unknown";
            }
            
            logger.debug("Captured JVM info: {} {} (max heap: {:.1f} GB, GC: {})",
                builder.jvmName, builder.javaVersion,
                builder.jvmMaxHeapBytes / (1024.0 * 1024.0 * 1024.0),
                builder.gcAlgorithm);
        } catch (Exception e) {
            logger.warn("Failed to capture JVM info", e);
            builder.javaVersion = "Unknown";
            builder.javaVendor = "Unknown";
            builder.jvmName = "Unknown";
            builder.jvmVersion = "Unknown";
            builder.gcAlgorithm = "Unknown";
        }
    }
    
    private static void captureContainerInfo(HardwareProfile.Builder builder) {
        try {
            // Check for container environment indicators
            builder.isContainerized = isRunningInContainer();
            
            if (builder.isContainerized) {
                builder.containerRuntime = detectContainerRuntime();
                
                // Try to detect container resource limits
                builder.containerMemoryLimitBytes = getContainerMemoryLimit();
                builder.containerCpuLimit = getContainerCpuLimit();
                
                logger.debug("Detected container environment: {} (memory limit: {:.1f} GB, CPU limit: {})",
                    builder.containerRuntime,
                    builder.containerMemoryLimitBytes / (1024.0 * 1024.0 * 1024.0),
                    builder.containerCpuLimit);
            } else {
                builder.containerRuntime = "None";
                builder.containerMemoryLimitBytes = 0;
                builder.containerCpuLimit = 0;
            }
        } catch (Exception e) {
            logger.warn("Failed to capture container info", e);
            builder.isContainerized = false;
            builder.containerRuntime = "Unknown";
        }
    }
    
    private static boolean isRunningInContainer() {
        // Check for common container indicators
        return System.getenv("CONTAINER") != null ||
               System.getenv("DOCKER_CONTAINER") != null ||
               System.getProperty("java.class.path", "").contains("testcontainers") ||
               java.nio.file.Files.exists(java.nio.file.Paths.get("/.dockerenv"));
    }
    
    private static String detectContainerRuntime() {
        if (System.getenv("DOCKER_CONTAINER") != null) {
            return "Docker";
        } else if (System.getProperty("java.class.path", "").contains("testcontainers")) {
            return "TestContainers";
        } else {
            return "Unknown";
        }
    }
    
    private static long getContainerMemoryLimit() {
        // This is a simplified implementation
        // In a real container, you'd read from /sys/fs/cgroup/memory/memory.limit_in_bytes
        return 0; // Placeholder
    }
    
    private static double getContainerCpuLimit() {
        // This is a simplified implementation
        // In a real container, you'd read from cgroup CPU settings
        return 0.0; // Placeholder
    }
    
    private static HardwareProfile createFallbackProfile() {
        logger.warn("Creating fallback hardware profile with minimal information");
        
        HardwareProfile.Builder builder = new HardwareProfile.Builder();
        Runtime runtime = Runtime.getRuntime();
        
        builder.systemDescription = "Fallback Profile";
        builder.hostname = "unknown";
        builder.osName = System.getProperty("os.name", "unknown");
        builder.osVersion = System.getProperty("os.version", "unknown");
        builder.osArchitecture = System.getProperty("os.arch", "unknown");
        
        builder.cpuModel = "Unknown CPU";
        builder.cpuCores = runtime.availableProcessors();
        builder.cpuLogicalProcessors = builder.cpuCores;
        
        builder.totalMemoryBytes = runtime.maxMemory();
        builder.availableMemoryBytes = runtime.freeMemory();
        builder.memoryType = "Unknown";
        builder.memoryModules = 1;
        
        builder.storageType = "Unknown";
        builder.storageInterface = "Unknown";
        builder.networkInterface = "Unknown";
        
        builder.javaVersion = System.getProperty("java.version", "unknown");
        builder.javaVendor = System.getProperty("java.vendor", "unknown");
        builder.jvmName = "Unknown JVM";
        builder.jvmVersion = "unknown";
        builder.jvmMaxHeapBytes = runtime.maxMemory();
        builder.jvmInitialHeapBytes = runtime.totalMemory();
        builder.gcAlgorithm = "Unknown";
        
        builder.isContainerized = false;
        builder.containerRuntime = "None";
        
        return builder.build();
    }
}
