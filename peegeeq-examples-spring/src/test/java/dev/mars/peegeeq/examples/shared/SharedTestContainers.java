package dev.mars.peegeeq.examples.shared;

import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
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
 * @Container
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
            initializeSharedSchema(container);

            sharedPostgres = container;

            logger.info("Shared PostgreSQL container started: host={}, port={}, database={}, username={}",
                container.getHost(), container.getFirstMappedPort(), container.getDatabaseName(), container.getUsername());
        }

        return sharedPostgres;
    }

    /**
     * Configures standard PeeGeeQ properties for the shared container.
     * Use this in @DynamicPropertySource methods to ensure consistent configuration.
     *
     * <p>The container is started (and restarted, if a test class has stopped it) before its
     * properties are read.</p>
     *
     * @param registry the dynamic property registry
     */
    public static void configureSharedProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        PostgreSQLContainer container = getSharedPostgreSQLContainer();

        ensureContainerRunning(container);

        logger.info("Configuring shared properties for container: host={}, port={}, database={}, username={}",
            container.getHost(), container.getFirstMappedPort(), container.getDatabaseName(), container.getUsername());

        // Get container properties once and reuse them (avoid multiple getMappedPort calls)
        String host = container.getHost();
        Integer port = container.getFirstMappedPort();
        String database = container.getDatabaseName();
        String username = container.getUsername();
        String password = container.getPassword();

        // Note: Removed system properties to avoid race conditions in parallel tests
        // System properties are global and can cause race conditions when multiple tests run in parallel
        // Instead, we rely on @DynamicPropertySource which is properly scoped per test

        // Standard PeeGeeQ properties - use higher precedence to override application.yml
        registry.add("peegeeq.database.host", () -> host);
        registry.add("peegeeq.database.port", () -> port.toString());
        registry.add("peegeeq.database.name", () -> database);
        registry.add("peegeeq.database.username", () -> username);
        registry.add("peegeeq.database.password", () -> password);
        registry.add("peegeeq.database.schema", () -> PostgreSQLTestConstants.TEST_SCHEMA);

        // Reactive pool tuning for high-load tests
        // Use peegeeq.pool.* (not peegeeq.database.pool.*) so Spring binds into PeeGeeQProperties.Pool
        registry.add("peegeeq.pool.max-size", () -> "32");
        registry.add("peegeeq.pool.max-wait-queue-size", () -> "4096");
        registry.add("peegeeq.pool.shared", () -> "false");
        registry.add("peegeeq.database.pool.shared", () -> "false");
        registry.add("peegeeq.database.pool.connection-timeout-ms", () -> "30000");
        registry.add("peegeeq.database.pool.idle-timeout-ms", () -> "600000");

        // Bi-temporal properties (for bi-temporal tests)
        registry.add("peegeeq.bitemporal.database.host", () -> host);
        registry.add("peegeeq.bitemporal.database.port", () -> port);
        registry.add("peegeeq.bitemporal.database.name", () -> database);
        registry.add("peegeeq.bitemporal.database.username", () -> username);
        registry.add("peegeeq.bitemporal.database.password", () -> password);
        registry.add("peegeeq.bitemporal.database.schema", () -> PostgreSQLTestConstants.TEST_SCHEMA);
        registry.add("peegeeq.bitemporal.pool.shared", () -> "false");

        // Dead Letter Queue properties (for DLQ tests)
        registry.add("peegeeq.dlq.database.host", () -> host);
        registry.add("peegeeq.dlq.database.port", () -> port);
        registry.add("peegeeq.dlq.database.name", () -> database);
        registry.add("peegeeq.dlq.database.username", () -> username);
        registry.add("peegeeq.dlq.database.password", () -> password);
        registry.add("peegeeq.dlq.database.schema", () -> PostgreSQLTestConstants.TEST_SCHEMA);
        registry.add("peegeeq.dlq.database.pool.shared", () -> "false");

        // Retry properties (for retry tests)
        registry.add("peegeeq.retry.database.host", () -> host);
        registry.add("peegeeq.retry.database.port", () -> port);
        registry.add("peegeeq.retry.database.name", () -> database);
        registry.add("peegeeq.retry.database.username", () -> username);
        registry.add("peegeeq.retry.database.password", () -> password);
        registry.add("peegeeq.retry.database.schema", () -> PostgreSQLTestConstants.TEST_SCHEMA);
        registry.add("peegeeq.retry.database.pool.shared", () -> "false");

        // Consumer properties (for consumer tests)
        registry.add("peegeeq.consumer.database.host", () -> host);
        registry.add("peegeeq.consumer.database.port", () -> port);
        registry.add("peegeeq.consumer.database.name", () -> database);
        registry.add("peegeeq.consumer.database.username", () -> username);
        registry.add("peegeeq.consumer.database.password", () -> password);
        registry.add("peegeeq.consumer.database.schema", () -> PostgreSQLTestConstants.TEST_SCHEMA);

        // Bitemporal properties (for bitemporal tests)
        registry.add("bitemporal.database.host", () -> host);
        registry.add("bitemporal.database.port", () -> port);
        registry.add("bitemporal.database.name", () -> database);
        registry.add("bitemporal.database.username", () -> username);
        registry.add("bitemporal.database.password", () -> password);
        registry.add("bitemporal.database.schema", () -> PostgreSQLTestConstants.TEST_SCHEMA);
        registry.add("bitemporal.database.pool.shared", () -> "false");

        // Reactive Bitemporal properties (for reactive bitemporal tests)
        registry.add("reactive-bitemporal.database.host", () -> host);
        registry.add("reactive-bitemporal.database.port", () -> port);
        registry.add("reactive-bitemporal.database.name", () -> database);
        registry.add("reactive-bitemporal.database.username", () -> username);
        registry.add("reactive-bitemporal.database.password", () -> password);
        registry.add("reactive-bitemporal.database.schema", () -> PostgreSQLTestConstants.TEST_SCHEMA);
        registry.add("reactive-bitemporal.database.pool.shared", () -> "false");

        // Financial Fabric properties (for financial fabric tests)
        registry.add("peegeeq.financial-fabric.database.pool.shared", () -> "false");

        // Integrated properties (for integrated tests)
        registry.add("integrated.database.host", () -> host);
        registry.add("integrated.database.port", () -> port);
        registry.add("integrated.database.name", () -> database);
        registry.add("integrated.database.username", () -> username);
        registry.add("integrated.database.password", () -> password);
        registry.add("integrated.database.schema", () -> PostgreSQLTestConstants.TEST_SCHEMA);
        registry.add("integrated.database.pool.shared", () -> false);

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
        logger.info("Registry properties set (no system properties): DB_HOST={}, DB_PORT={}, DB_NAME={}", host, port, database);
    }

    /**
     * Initializes the full PeeGeeQ DDL in the explicit shared test schema. Called after
     * every container (re)start so classes that do not initialize the schema themselves
     * always see the tables, regardless of class execution order.
     */
    private static void initializeSharedSchema(PostgreSQLContainer container) {
        PeeGeeQTestSchemaInitializer.initializeSchema(
            container, PostgreSQLTestConstants.TEST_SCHEMA,
            PeeGeeQTestSchemaInitializer.SchemaComponent.ALL);
        logger.info("Shared schema initialized in '{}'", PostgreSQLTestConstants.TEST_SCHEMA);
    }

    /**
     * Starts the container again if a test class has stopped it. {@code start()} returns
     * only when the container accepts connections, so no further wait is needed.
     *
     * @param container the container to verify
     */
    private static void ensureContainerRunning(PostgreSQLContainer container) {
        if (!container.isRunning()) {
            logger.warn("Container is not running, starting it now...");
            container.start();
            // A restart produces a fresh, empty database (the @Container annotation on
            // sharing classes stops the container after each class): re-create the DDL
            // in the explicit test schema so free-riding classes see the tables
            initializeSharedSchema(container);
        }
    }
}
