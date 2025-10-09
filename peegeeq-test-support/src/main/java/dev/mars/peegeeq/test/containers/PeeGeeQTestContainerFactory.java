package dev.mars.peegeeq.test.containers;

import org.testcontainers.containers.PostgreSQLContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.HashMap;

/**
 * Standardized TestContainers factory for PeeGeeQ tests.
 * 
 * This factory provides consistent PostgreSQL container configurations across all PeeGeeQ modules,
 * supporting different performance profiles for parameterized testing. It builds upon the existing
 * patterns found in BaseIntegrationTest and PostgreSQLTestConstants.
 * 
 * Usage:
 * ```java
 * // Basic testing
 * PostgreSQLContainer<?> container = PeeGeeQTestContainerFactory.createContainer(BASIC);
 * 
 * // High-performance testing
 * PostgreSQLContainer<?> container = PeeGeeQTestContainerFactory.createContainer(HIGH_PERFORMANCE);
 * 
 * // Custom configuration
 * Map<String, String> custom = Map.of("max_connections", "500");
 * PostgreSQLContainer<?> container = PeeGeeQTestContainerFactory.createContainer(CUSTOM, custom);
 * ```
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-18
 * @version 1.0
 */
public class PeeGeeQTestContainerFactory {
    
    private static final Logger logger = LoggerFactory.getLogger(PeeGeeQTestContainerFactory.class);
    
    // Consistent image version across all tests
    private static final String POSTGRES_IMAGE = "postgres:15.13-alpine3.20";
    
    // Standard database configuration
    private static final String DEFAULT_DATABASE_NAME = "peegeeq_test";
    private static final String DEFAULT_USERNAME = "peegeeq_test";
    private static final String DEFAULT_PASSWORD = "peegeeq_test";
    private static final long DEFAULT_SHARED_MEMORY_SIZE = 256 * 1024 * 1024L; // 256MB
    
    /**
     * Performance profiles for different testing scenarios.
     * Each profile represents a different PostgreSQL configuration optimized for specific use cases.
     */
    public enum PerformanceProfile {
        /**
         * Basic configuration for standard unit and integration tests.
         * Minimal performance optimizations, suitable for most test scenarios.
         */
        BASIC("Basic Testing", 
              "Standard configuration for unit and integration tests"),
        
        /**
         * Standard performance configuration with basic optimizations.
         * Includes fsync=off and synchronous_commit=off for faster test execution.
         */
        STANDARD("Standard Performance", 
                 "Basic performance optimizations for faster test execution"),
        
        /**
         * High-performance configuration for performance testing.
         * Includes memory optimizations and connection tuning.
         */
        HIGH_PERFORMANCE("High Performance", 
                        "Advanced performance optimizations for performance tests"),
        
        /**
         * Maximum performance configuration for benchmark testing.
         * All performance optimizations enabled, suitable for throughput testing.
         */
        MAXIMUM_PERFORMANCE("Maximum Performance", 
                           "Maximum performance optimizations for benchmark tests"),
        
        /**
         * Custom configuration profile.
         * Allows specifying custom PostgreSQL parameters.
         */
        CUSTOM("Custom Configuration", 
               "Custom PostgreSQL configuration with user-specified parameters");
        
        private final String displayName;
        private final String description;
        
        PerformanceProfile(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }
        
        public String getDisplayName() { return displayName; }
        public String getDescription() { return description; }
    }
    
    /**
     * Create a PostgreSQL container with the specified performance profile.
     * 
     * @param profile the performance profile to use
     * @return configured PostgreSQL container
     */
    public static PostgreSQLContainer<?> createContainer(PerformanceProfile profile) {
        return createContainer(profile, null, null, null, null);
    }
    
    /**
     * Create a PostgreSQL container with custom database settings.
     * 
     * @param profile the performance profile to use
     * @param databaseName custom database name (null to use default)
     * @param username custom username (null to use default)
     * @param password custom password (null to use default)
     * @param customSettings custom PostgreSQL settings (only used with CUSTOM profile)
     * @return configured PostgreSQL container
     */
    public static PostgreSQLContainer<?> createContainer(PerformanceProfile profile,
                                                        String databaseName,
                                                        String username,
                                                        String password,
                                                        Map<String, String> customSettings) {
        
        // Use defaults if not specified
        String dbName = databaseName != null ? databaseName : DEFAULT_DATABASE_NAME;
        String dbUser = username != null ? username : DEFAULT_USERNAME;
        String dbPassword = password != null ? password : DEFAULT_PASSWORD;
        
        logger.debug("Creating PostgreSQL container with profile: {} for database: {}", 
                    profile.getDisplayName(), dbName);
        
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withDatabaseName(dbName)
                .withUsername(dbUser)
                .withPassword(dbPassword)
                .withSharedMemorySize(DEFAULT_SHARED_MEMORY_SIZE)
                .withReuse(false); // Always start fresh for consistent test results
        
        // Apply profile-specific configuration
        switch (profile) {
            case BASIC:
                // No additional configuration - basic PostgreSQL setup
                break;
                
            case STANDARD:
                container = container.withCommand("postgres",
                    "-c", "fsync=off",                    // Faster for tests
                    "-c", "synchronous_commit=off"        // Faster for tests
                );
                break;
                
            case HIGH_PERFORMANCE:
                container = container.withCommand("postgres",
                    // Basic performance optimizations
                    "-c", "fsync=off",
                    "-c", "synchronous_commit=off",
                    
                    // Connection and memory settings
                    "-c", "max_connections=300",          // Increased from default ~100
                    "-c", "shared_buffers=128MB",         // Better memory usage
                    "-c", "effective_cache_size=256MB",   // Cache optimization
                    "-c", "work_mem=8MB",                 // Per-operation memory
                    "-c", "maintenance_work_mem=32MB"     // Maintenance operations
                );
                break;
                
            case MAXIMUM_PERFORMANCE:
                container = container.withCommand("postgres",
                    // All performance optimizations
                    "-c", "fsync=off",
                    "-c", "synchronous_commit=off",
                    
                    // Connection settings
                    "-c", "max_connections=500",
                    
                    // Memory settings
                    "-c", "shared_buffers=256MB",
                    "-c", "effective_cache_size=512MB",
                    "-c", "work_mem=16MB",
                    "-c", "maintenance_work_mem=64MB",
                    
                    // WAL and checkpoint settings
                    "-c", "checkpoint_completion_target=0.9",
                    "-c", "wal_buffers=16MB",
                    "-c", "max_prepared_transactions=100",
                    
                    // Additional optimizations
                    "-c", "random_page_cost=1.1",         // SSD optimization
                    "-c", "effective_io_concurrency=200"  // SSD optimization
                );
                break;
                
            case CUSTOM:
                if (customSettings != null && !customSettings.isEmpty()) {
                    container = applyCustomSettings(container, customSettings);
                } else {
                    logger.warn("CUSTOM profile specified but no custom settings provided, using STANDARD profile");
                    return createContainer(PerformanceProfile.STANDARD, databaseName, username, password, null);
                }
                break;
        }
        
        logger.info("Created PostgreSQL container with profile: {} ({})", 
                   profile.getDisplayName(), profile.getDescription());
        
        return container;
    }
    
    /**
     * Apply custom PostgreSQL settings to a container.
     * 
     * @param container the container to configure
     * @param customSettings map of PostgreSQL parameter name to value
     * @return configured container
     */
    private static PostgreSQLContainer<?> applyCustomSettings(PostgreSQLContainer<?> container, 
                                                             Map<String, String> customSettings) {
        
        logger.debug("Applying {} custom PostgreSQL settings", customSettings.size());
        
        // Convert custom settings to command line arguments
        String[] commands = new String[customSettings.size() * 2 + 1];
        commands[0] = "postgres";
        
        int index = 1;
        for (Map.Entry<String, String> entry : customSettings.entrySet()) {
            commands[index++] = "-c";
            commands[index++] = entry.getKey() + "=" + entry.getValue();
            logger.debug("Applied custom setting: {}={}", entry.getKey(), entry.getValue());
        }
        
        return container.withCommand(commands);
    }
    
    /**
     * Create a container with the same configuration as BaseIntegrationTest.
     * This method provides backward compatibility with existing tests.
     * 
     * @return PostgreSQL container configured like BaseIntegrationTest
     */
    public static PostgreSQLContainer<?> createBaseIntegrationTestContainer() {
        return createContainer(PerformanceProfile.STANDARD)
                .withReuse(true); // BaseIntegrationTest uses reuse=true
    }
    
    /**
     * Get performance profile information for logging and reporting.
     * 
     * @param profile the performance profile
     * @return map containing profile information
     */
    public static Map<String, Object> getProfileInfo(PerformanceProfile profile) {
        Map<String, Object> info = new HashMap<>();
        info.put("name", profile.name());
        info.put("displayName", profile.getDisplayName());
        info.put("description", profile.getDescription());
        info.put("postgresImage", POSTGRES_IMAGE);
        info.put("sharedMemorySize", DEFAULT_SHARED_MEMORY_SIZE);
        return info;
    }
}
