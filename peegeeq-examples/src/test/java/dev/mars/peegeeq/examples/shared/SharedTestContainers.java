package dev.mars.peegeeq.examples.shared;

import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

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
 * static PostgreSQLContainer postgres = SharedTestContainers.getSharedPostgreSQLContainer();
 * ```
 */
public class SharedTestContainers {

    private static final Logger logger = LoggerFactory.getLogger(SharedTestContainers.class);

    private static PostgreSQLContainer sharedPostgres;

    /**
     * Gets a shared PostgreSQL container with high-performance configuration.
     * The container is started once and reused across multiple test classes.
     *
     * @return shared PostgreSQL container
     */
    public static synchronized PostgreSQLContainer getSharedPostgreSQLContainer() {
        if (sharedPostgres == null) {
            logger.info("Creating shared PostgreSQL container with high-performance configuration");

            PostgreSQLContainer container = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE)
                    .withDatabaseName("peegeeq_shared_test")
                    .withUsername("peegeeq_test")
                    .withPassword("peegeeq_test")
                    .withSharedMemorySize(512 * 1024 * 1024L) // 512MB for better performance
                    .withReuse(true) // Enable container reuse
                    .withCommand("postgres",
                        // High-performance PostgreSQL settings for testing
                        "-c", "fsync=off",                    // Disable fsync for speed (test only!)
                        "-c", "synchronous_commit=off",       // Async commits for speed
                        "-c", "max_connections=2000",         // Much higher connection limit for concurrent tests
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
     * Gets a dedicated PostgreSQL container for tests that need isolation.
     * Use this for tests that modify global state or need specific configurations.
     *
     * @param databaseName unique database name for isolation
     * @return dedicated PostgreSQL container
     */
    public static PostgreSQLContainer getDedicatedPostgreSQLContainer(String databaseName) {
        logger.info("Creating dedicated PostgreSQL container for database: {}", databaseName);

        PostgreSQLContainer container = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE)
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
    public static PostgreSQLContainer getHighPerformanceContainer() {
        logger.info("Creating high-performance PostgreSQL container for benchmarks");

        PostgreSQLContainer container = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE)
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
