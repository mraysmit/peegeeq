package dev.mars.peegeeq.examples.shared;

import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Shared TestContainers configuration for PeeGeeQ Examples.
 *
 * <p>Provides one PostgreSQL container per test JVM, started on first use and reused by
 * every test class that asks for it. The container starts from
 * {@link PostgreSQLTestConstants#createStandardContainer()}, so the image, database,
 * credentials and reuse policy are the project defaults. This class adds only what the
 * example tests need on top: a larger shared memory segment and PostgreSQL settings for
 * many concurrent connections.</p>
 *
 * Usage:
 * ```java
 * static PostgreSQLContainer postgres = SharedTestContainers.getSharedPostgreSQLContainer();
 * ```
 */
public class SharedTestContainers {

    private static final Logger logger = LoggerFactory.getLogger(SharedTestContainers.class);

    private static PostgreSQLContainer sharedPostgres;

    /**
     * Gets the shared PostgreSQL container, starting it on first use.
     *
     * @return shared PostgreSQL container
     */
    public static synchronized PostgreSQLContainer getSharedPostgreSQLContainer() {
        if (sharedPostgres == null) {
            logger.info("Creating shared PostgreSQL container for the example tests");

            PostgreSQLContainer container = PostgreSQLTestConstants.createStandardContainer()
                    .withSharedMemorySize(PostgreSQLTestConstants.HIGH_PERFORMANCE_SHARED_MEMORY_SIZE)
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

            logger.info("Shared PostgreSQL container started: host={}, port={}, database={}, username={}",
                container.getHost(), container.getFirstMappedPort(), container.getDatabaseName(), container.getUsername());
        }

        return sharedPostgres;
    }
}
