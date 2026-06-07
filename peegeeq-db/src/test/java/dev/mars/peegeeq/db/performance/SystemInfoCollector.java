package dev.mars.peegeeq.db.performance;

import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;


/**
 * Utility class for collecting comprehensive system information for performance test reports.
 * 
 * Gathers information about:
 * - Operating System details
 * - CPU specifications and core count
 * - Memory configuration
 * - Java runtime environment
 * - Maven version
 * - Database configuration and connection status
 * - PeeGeeQ-specific configuration
 * 
 * This information is automatically included in performance test results to provide
 * context for performance measurements and enable comparison across different environments.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-11
 * @version 1.0
 */
public class SystemInfoCollector {
    private static final Logger logger = LoggerFactory.getLogger(SystemInfoCollector.class);

    /**
     * Immutable typed snapshot of collected system information.
     */
    public record SystemInfoSnapshot(
        String timestamp,
        Map<String, String> systemConfiguration,
        Map<String, String> databaseConfiguration,
        Map<String, String> peeGeeQConfiguration
    ) {
    }
    
    /**
     * Collects comprehensive system information.
     * 
     * @return Typed system information snapshot organized by category
     */
    public static SystemInfoSnapshot collectSystemInfo() {
        return collectSystemInfo(null);
    }

    /**
     * Collects comprehensive system information, reading peegeeq.* values from
     * the supplied configuration when non-null, falling back to System properties
     * when null (acceptable for diagnostics invoked outside a manager context).
     *
     * @param config optional PeeGeeQConfiguration; null enables System-property fallback
     * @return Typed system information snapshot organized by category
     */
    public static SystemInfoSnapshot collectSystemInfo(PeeGeeQConfiguration config) {
        return new SystemInfoSnapshot(
            LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            collectSystemConfiguration(),
            collectDatabaseConfiguration(config),
            collectPeeGeeQConfiguration(config)
        );
    }
    
    /**
     * Collects system configuration information.
     */
    private static Map<String, String> collectSystemConfiguration() {
        Map<String, String> config = new LinkedHashMap<>();
        
        try {
            // Operating System information
            OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            String osName = System.getProperty("os.name");
            String osVersion = System.getProperty("os.version");
            String osArch = System.getProperty("os.arch");
            
            config.put("OS", String.format("%s (%s)", osName, osVersion));
            config.put("Architecture", osArch);
            
            // CPU information
            int availableProcessors = osBean.getAvailableProcessors();
            config.put("CPU Cores", availableProcessors + " logical processors");
            
            // Try to get more detailed CPU information on Windows
            String cpuInfo = getCpuInfo();
            if (cpuInfo != null && !cpuInfo.trim().isEmpty()) {
                config.put("CPU", cpuInfo);
            }
            
            // Memory information
            MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
            long maxMemory = memoryBean.getHeapMemoryUsage().getMax();
            long totalMemory = Runtime.getRuntime().totalMemory();
            
            if (maxMemory > 0) {
                config.put("Total Memory", String.format("%,d MB (%.1f GB)", 
                    maxMemory / (1024 * 1024), maxMemory / (1024.0 * 1024.0 * 1024.0)));
            } else {
                config.put("Total Memory", String.format("%,d MB (%.1f GB)", 
                    totalMemory / (1024 * 1024), totalMemory / (1024.0 * 1024.0 * 1024.0)));
            }
            
            // Java information
            String javaVersion = System.getProperty("java.version");
            String javaVendor = System.getProperty("java.vendor");
            String vmName = System.getProperty("java.vm.name");
            String vmVersion = System.getProperty("java.vm.version");
            
            config.put("Java Version", String.format("%s (%s)", javaVersion, javaVendor));
            config.put("JVM", String.format("%s (%s)", vmName, vmVersion));
            
            // Maven version
            String mavenVersion = getMavenVersion();
            if (mavenVersion != null) {
                config.put("Maven Version", mavenVersion);
            }
            
        } catch (Exception e) {
            logger.warn("Error collecting system configuration: {}", e.getMessage());
            config.put("Error", "Failed to collect some system information: " + e.getMessage());
        }
        
        return config;
    }
    
    /**
     * Attempts to get detailed CPU information on Windows systems.
     */
    private static String getCpuInfo() {
        try {
            if (System.getProperty("os.name").toLowerCase().contains("windows")) {
                ProcessBuilder pb = new ProcessBuilder("wmic", "cpu", "get", "name", "/format:value");
                Process process = pb.start();
                
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("Name=") && line.length() > 5) {
                            return line.substring(5).trim();
                        }
                    }
                }
                
                process.waitFor();
            }
        } catch (Exception e) {
            logger.debug("Could not retrieve detailed CPU information: {}", e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Attempts to get Maven version.
     */
    private static String getMavenVersion() {
        try {
            ProcessBuilder pb = new ProcessBuilder("mvn", "--version");
            Process process = pb.start();
            
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                if (line != null && line.contains("Apache Maven")) {
                    // Extract version from line like "Apache Maven 3.9.6 (bc0240f3c744dd6b6ec2920b3cd08dcc295161ae)"
                    String[] parts = line.split(" ");
                    if (parts.length >= 3) {
                        return parts[2];
                    }
                }
            }
            
            process.waitFor();
        } catch (Exception e) {
            logger.debug("Could not retrieve Maven version: {}", e.getMessage());
        }
        
        return "Unknown";
    }

    /**
     * Collects database configuration information.
     */
    private static Map<String, String> collectDatabaseConfiguration(PeeGeeQConfiguration config) {
        Map<String, String> info = new LinkedHashMap<>();

        try {
            // Try to get database information from configuration, system properties, or environment
            String dbUrl = (config != null)
                    ? config.getString("peegeeq.database.url", null)
                    : System.getProperty("peegeeq.database.url",
                      System.getenv("PEEGEEQ_DATABASE_URL"));

            if (dbUrl != null) {
                if (dbUrl.contains("postgresql")) {
                    info.put("Database", "PostgreSQL");

                    // Extract host and port from URL
                    if (dbUrl.contains("://")) {
                        String[] parts = dbUrl.split("://")[1].split("/")[0].split(":");
                        info.put("Host", parts[0]);
                        if (parts.length > 1) {
                            info.put("Port", parts[1]);
                        }
                    }
                } else {
                    info.put("Database", "Unknown database type");
                }

                info.put("Connection Status", "Configuration available");
            } else {
                info.put("Database", "PostgreSQL (default)");
                info.put("Connection Status", "Not available during test execution");
            }

            // Pool configuration
            String poolSize = (config != null)
                    ? String.valueOf(config.getInt("peegeeq.database.pool.max-size", 100))
                    : System.getProperty("peegeeq.database.pool.max-size", "100");
            String waitQueue = (config != null)
                    ? String.valueOf(config.getInt("peegeeq.database.pool.wait-queue-multiplier", 10))
                    : System.getProperty("peegeeq.database.pool.wait-queue-multiplier", "10");
            String pipelining = (config != null)
                    ? String.valueOf(config.getInt("peegeeq.database.pipelining.limit", 1024))
                    : System.getProperty("peegeeq.database.pipelining.limit", "1024");

            info.put("Pool Configuration", String.format("Optimized (%s connections, %s wait queue)",
                poolSize, Integer.parseInt(poolSize) * Integer.parseInt(waitQueue)));
            info.put("Pipelining", String.format("Enabled (%s limit)", pipelining));

        } catch (Exception e) {
            logger.warn("Error collecting database configuration: {}", e.getMessage());
            info.put("Error", "Failed to collect database information: " + e.getMessage());
        }

        return info;
    }



    /**
     * Collects PeeGeeQ-specific configuration information.
     */
    private static Map<String, String> collectPeeGeeQConfiguration(PeeGeeQConfiguration config) {
        Map<String, String> info = new LinkedHashMap<>();

        try {
            if (config != null) {
                // Read from the isolated configuration instance
                Properties configProps = config.getProperties();
                for (String key : configProps.stringPropertyNames()) {
                    if (key.startsWith("peegeeq.")) {
                        info.put(key, configProps.getProperty(key));
                    }
                }
            } else {
                // Fallback: sweep System properties (acceptable outside a manager context)
                Properties systemProps = System.getProperties();
                for (String key : systemProps.stringPropertyNames()) {
                    if (key.startsWith("peegeeq.")) {
                        info.put(key, systemProps.getProperty(key));
                    }
                }
            }

            // If no PeeGeeQ properties found, add defaults
            if (info.isEmpty()) {
                info.put("Configuration", "Default settings");
                info.put("Profile", "Not specified");
            }

        } catch (Exception e) {
            logger.warn("Error collecting PeeGeeQ configuration: {}", e.getMessage());
            info.put("Error", "Failed to collect PeeGeeQ configuration: " + e.getMessage());
        }

        return info;
    }

    /**
     * Formats system information as markdown for inclusion in performance test reports.
     *
     * @return Formatted markdown string
     */
    public static String formatAsMarkdown() {
        return formatAsMarkdown(collectSystemInfo());
    }

    /**
     * Formats system information as markdown for inclusion in performance test reports.
     *
     * @param systemInfo System information map
     * @return Formatted markdown string
     */
    public static String formatAsMarkdown(SystemInfoSnapshot systemInfo) {
        StringBuilder md = new StringBuilder();

        md.append("## System Configuration\n\n");

        // System Configuration section
        Map<String, String> sysConfig = systemInfo.systemConfiguration();
        if (sysConfig != null) {
            for (Map.Entry<String, String> entry : sysConfig.entrySet()) {
                md.append("- **").append(entry.getKey()).append(":** ").append(entry.getValue()).append("\n");
            }
        }

        md.append("\n## Database Configuration\n\n");

        // Database Configuration section
        Map<String, String> dbConfig = systemInfo.databaseConfiguration();
        if (dbConfig != null) {
            for (Map.Entry<String, String> entry : dbConfig.entrySet()) {
                md.append("- **").append(entry.getKey()).append(":** ").append(entry.getValue()).append("\n");
            }
        }

        // PeeGeeQ Configuration section (only if there are custom properties)
        Map<String, String> peeGeeQConfig = systemInfo.peeGeeQConfiguration();
        if (peeGeeQConfig != null && !peeGeeQConfig.isEmpty() &&
            !peeGeeQConfig.containsKey("Configuration")) {
            md.append("\n## PeeGeeQ Configuration\n\n");
            for (Map.Entry<String, String> entry : peeGeeQConfig.entrySet()) {
                md.append("- **").append(entry.getKey()).append(":** ").append(entry.getValue()).append("\n");
            }
        }

        return md.toString();
    }

    /**
     * Formats system information as a compact summary for test output.
     *
     * @return Compact summary string
     */
    public static String formatAsSummary() {
        SystemInfoSnapshot systemInfo = collectSystemInfo();
        return formatAsSummary(systemInfo);
    }

    /**
     * Formats system information as a compact summary for test output.
     *
     * @param systemInfo System information map
     * @return Compact summary string
     */
    public static String formatAsSummary(SystemInfoSnapshot systemInfo) {
        StringBuilder summary = new StringBuilder();

        Map<String, String> sysConfig = systemInfo.systemConfiguration();
        Map<String, String> dbConfig = systemInfo.databaseConfiguration();

        if (sysConfig != null) {
            summary.append("System: ").append(sysConfig.get("OS")).append(", ");
            summary.append("CPU: ").append(sysConfig.get("CPU Cores")).append(", ");
            summary.append("Memory: ").append(sysConfig.get("Total Memory")).append(", ");
            summary.append("Java: ").append(sysConfig.get("Java Version"));
        }

        if (dbConfig != null) {
            summary.append(", DB: ").append(dbConfig.get("Database"));
        }

        return summary.toString();
    }
}
