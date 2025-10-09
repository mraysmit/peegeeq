package dev.mars.peegeeq.examples.shared;

import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared TestContainers configuration for PeeGeeQ Examples.
 * 
 * This class provides reusable PostgreSQL containers to reduce test startup overhead
 * and improve overall test execution performance. Containers are configured with
 * high-performance settings optimized for testing.
 * 
 * Usage:
 * ```java
 * @Container
 * static PostgreSQLContainer<?> postgres = SharedTestContainers.getSharedPostgreSQLContainer();
 * ```
 */
public class SharedTestContainers {
    
    private static final Logger logger = LoggerFactory.getLogger(SharedTestContainers.class);
    
    private static PostgreSQLContainer<?> sharedPostgres;
    
    /**
     * Gets a shared PostgreSQL container with high-performance configuration.
     * The container is started once and reused across multiple test classes.
     *
     * @return shared PostgreSQL container
     */
    public static synchronized PostgreSQLContainer<?> getSharedPostgreSQLContainer() {
        if (sharedPostgres == null) {
            logger.info("Creating shared PostgreSQL container with high-performance configuration");

            PostgreSQLContainer<?> container = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
                    .withDatabaseName("peegeeq_shared_test")
                    .withUsername("peegeeq_test")
                    .withPassword("peegeeq_test")
                    .withSharedMemorySize(512 * 1024 * 1024L) // 512MB for better performance
                    .withReuse(true) // Enable container reuse
                    .withCommand("postgres",
                        // High-performance PostgreSQL settings for testing
                        "-c", "fsync=off",                    // Disable fsync for speed (test only!)
                        "-c", "synchronous_commit=off",       // Async commits for speed
                        "-c", "max_connections=300",          // Higher connection limit
                        "-c", "shared_buffers=128MB",         // Larger shared buffers
                        "-c", "effective_cache_size=256MB",   // Cache size optimization
                        "-c", "work_mem=16MB",                // Work memory per operation
                        "-c", "maintenance_work_mem=64MB",    // Maintenance operations
                        "-c", "checkpoint_completion_target=0.9", // Checkpoint tuning
                        "-c", "wal_buffers=16MB",             // WAL buffer size
                        "-c", "random_page_cost=1.1",         // SSD optimization
                        "-c", "effective_io_concurrency=200", // SSD I/O concurrency
                        "-c", "max_prepared_transactions=100" // For transaction testing
                    );

            // Start the container immediately to ensure it's available for all test classes
            container.start();
            sharedPostgres = container;

            logger.info("Shared PostgreSQL container created and started successfully");
            logger.info("Container details: host={}, port={}, database={}, username={}",
                container.getHost(), container.getFirstMappedPort(), container.getDatabaseName(), container.getUsername());
        }

        return sharedPostgres;
    }

    /**
     * Configures standard PeeGeeQ properties for the shared container.
     * Use this in @DynamicPropertySource methods to ensure consistent configuration.
     *
     * This method ensures the container is fully started and ready before accessing its properties.
     *
     * @param registry the dynamic property registry
     */
    public static void configureSharedProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        PostgreSQLContainer<?> container = getSharedPostgreSQLContainer();

        // Ensure container is fully started and ready before accessing port
        ensureContainerReady(container);

        logger.info("Configuring shared properties for container: host={}, port={}, database={}, username={}",
            container.getHost(), container.getFirstMappedPort(), container.getDatabaseName(), container.getUsername());

        // Get container properties once and reuse them (avoid multiple getMappedPort calls)
        String host = container.getHost();
        Integer port = container.getFirstMappedPort();
        String database = container.getDatabaseName();
        String username = container.getUsername();
        String password = container.getPassword();

        // Set system properties first (for environment variable resolution in YAML)
        System.setProperty("DB_HOST", host);
        System.setProperty("DB_PORT", port.toString());
        System.setProperty("DB_NAME", database);
        System.setProperty("DB_USERNAME", username);
        System.setProperty("DB_PASSWORD", password);

        // Standard PeeGeeQ properties - use higher precedence to override application.yml
        registry.add("peegeeq.database.host", () -> host);
        registry.add("peegeeq.database.port", () -> port.toString());
        registry.add("peegeeq.database.name", () -> database);
        registry.add("peegeeq.database.username", () -> username);
        registry.add("peegeeq.database.password", () -> password);
        registry.add("peegeeq.database.schema", () -> "public");

        // Bi-temporal properties (for bi-temporal tests)
        registry.add("peegeeq.bitemporal.database.host", () -> host);
        registry.add("peegeeq.bitemporal.database.port", () -> port);
        registry.add("peegeeq.bitemporal.database.name", () -> database);
        registry.add("peegeeq.bitemporal.database.username", () -> username);
        registry.add("peegeeq.bitemporal.database.password", () -> password);

        // Dead Letter Queue properties (for DLQ tests)
        registry.add("peegeeq.dlq.database.host", () -> host);
        registry.add("peegeeq.dlq.database.port", () -> port);
        registry.add("peegeeq.dlq.database.name", () -> database);
        registry.add("peegeeq.dlq.database.username", () -> username);
        registry.add("peegeeq.dlq.database.password", () -> password);

        // Retry properties (for retry tests)
        registry.add("peegeeq.retry.database.host", () -> host);
        registry.add("peegeeq.retry.database.port", () -> port);
        registry.add("peegeeq.retry.database.name", () -> database);
        registry.add("peegeeq.retry.database.username", () -> username);
        registry.add("peegeeq.retry.database.password", () -> password);

        // Consumer properties (for consumer tests)
        registry.add("peegeeq.consumer.database.host", () -> host);
        registry.add("peegeeq.consumer.database.port", () -> port);
        registry.add("peegeeq.consumer.database.name", () -> database);
        registry.add("peegeeq.consumer.database.username", () -> username);
        registry.add("peegeeq.consumer.database.password", () -> password);

        // R2DBC properties (for reactive tests) - override application.yml settings
        String r2dbcUrl = String.format("r2dbc:postgresql://%s:%d/%s", host, port, database);
        registry.add("spring.r2dbc.url", () -> r2dbcUrl);
        registry.add("spring.r2dbc.username", () -> username);
        registry.add("spring.r2dbc.password", () -> password);

        // Environment variable overrides (highest precedence)
        registry.add("DB_HOST", () -> host);
        registry.add("DB_PORT", () -> port.toString());
        registry.add("DB_NAME", () -> database);
        registry.add("DB_USERNAME", () -> username);
        registry.add("DB_PASSWORD", () -> password);

        // Test settings
        registry.add("peegeeq.profile", () -> "test");
        registry.add("peegeeq.migration.enabled", () -> "true");
        registry.add("peegeeq.migration.auto-migrate", () -> "true");

        logger.info("Shared properties configured successfully for database: {}", database);
        logger.info("System properties set: DB_HOST={}, DB_PORT={}, DB_NAME={}",
            System.getProperty("DB_HOST"), System.getProperty("DB_PORT"), System.getProperty("DB_NAME"));
    }

    /**
     * Ensures the container is fully started and ready for use.
     * This method blocks until the container is confirmed to be running and accessible.
     *
     * @param container the container to verify
     */
    private static void ensureContainerReady(PostgreSQLContainer<?> container) {
        if (!container.isRunning()) {
            logger.warn("Container is not running, starting it now...");
            container.start();
        }

        // Wait for container to be fully ready with timeout
        int maxAttempts = 30; // 30 seconds timeout
        int attempt = 0;

        while (attempt < maxAttempts) {
            try {
                // Try to get the mapped port - this will throw if container isn't ready
                Integer port = container.getFirstMappedPort();
                String host = container.getHost();

                logger.debug("Container ready check attempt {}: host={}, port={}", attempt + 1, host, port);

                // If we get here, container is ready
                logger.info("Container is ready: host={}, port={}", host, port);
                return;

            } catch (IllegalStateException e) {
                if (e.getMessage().contains("Mapped port can only be obtained after the container is started")) {
                    logger.debug("Container not ready yet (attempt {}), waiting...", attempt + 1);
                    try {
                        Thread.sleep(1000); // Wait 1 second
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted while waiting for container to be ready", ie);
                    }
                    attempt++;
                } else {
                    throw e; // Re-throw other IllegalStateExceptions
                }
            }
        }

        throw new RuntimeException("Container failed to become ready within " + maxAttempts + " seconds");
    }
    
    /**
     * Gets a dedicated PostgreSQL container for tests that need isolation.
     * Use this for tests that modify global state or need specific configurations.
     * 
     * @param databaseName unique database name for isolation
     * @return dedicated PostgreSQL container
     */
    public static PostgreSQLContainer<?> getDedicatedPostgreSQLContainer(String databaseName) {
        logger.info("Creating dedicated PostgreSQL container for database: {}", databaseName);
        
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
                .withDatabaseName(databaseName)
                .withUsername("peegeeq_test")
                .withPassword("peegeeq_test")
                .withSharedMemorySize(256 * 1024 * 1024L) // 256MB for dedicated containers
                .withReuse(false) // No reuse for dedicated containers
                .withCommand("postgres",
                    // Performance settings for dedicated containers
                    "-c", "fsync=off",
                    "-c", "synchronous_commit=off",
                    "-c", "max_connections=200",
                    "-c", "shared_buffers=64MB",
                    "-c", "effective_cache_size=128MB",
                    "-c", "work_mem=8MB"
                );
        
        logger.info("Dedicated PostgreSQL container created for: {}", databaseName);
        return container;
    }
    
    /**
     * Creates a high-performance container for performance benchmarks.
     * 
     * @return high-performance PostgreSQL container
     */
    public static PostgreSQLContainer<?> getHighPerformanceContainer() {
        logger.info("Creating high-performance PostgreSQL container for benchmarks");
        
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
                .withDatabaseName("peegeeq_perf_test")
                .withUsername("peegeeq_test")
                .withPassword("peegeeq_test")
                .withSharedMemorySize(1024 * 1024 * 1024L) // 1GB for maximum performance
                .withReuse(false) // Fresh container for each benchmark
                .withCommand("postgres",
                    // Maximum performance settings (test only!)
                    "-c", "fsync=off",
                    "-c", "synchronous_commit=off",
                    "-c", "full_page_writes=off",
                    "-c", "max_connections=500",
                    "-c", "shared_buffers=256MB",
                    "-c", "effective_cache_size=512MB",
                    "-c", "work_mem=32MB",
                    "-c", "maintenance_work_mem=128MB",
                    "-c", "checkpoint_completion_target=0.9",
                    "-c", "wal_buffers=32MB",
                    "-c", "random_page_cost=1.0",
                    "-c", "effective_io_concurrency=300",
                    "-c", "max_prepared_transactions=200"
                );
        
        logger.info("High-performance PostgreSQL container created");
        return container;
    }
}
