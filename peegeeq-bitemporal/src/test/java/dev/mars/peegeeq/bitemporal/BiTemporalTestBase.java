package dev.mars.peegeeq.bitemporal;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.performance.SystemInfoCollector;
import dev.mars.peegeeq.api.EventStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Base class for bi-temporal performance tests.
 * 
 * This class provides shared infrastructure for all bi-temporal performance tests:
 * - TestContainers PostgreSQL setup
 * - PeeGeeQ configuration and initialization
 * - Event store factory and management
 * - Common setup and teardown patterns
 * - Shared utility methods
 * 
 * Following PGQ coding principles:
 * - Investigate before implementing: Analyzed existing test patterns
 * - Follow established conventions: Uses same TestContainers setup as other tests
 * - Fix root causes: Provides proper test infrastructure
 * - Validate incrementally: Each test can be run independently
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-11
 * @version 1.0
 */
@Testcontainers
public abstract class BiTemporalTestBase {

    private static final Logger logger = LoggerFactory.getLogger(BiTemporalTestBase.class);

    // Use a shared container that persists across multiple test classes
    // This prevents connection issues when running multiple test classes sequentially
    private static PostgreSQLContainer<?> sharedPostgres;

    static {
        // Initialize shared container only once across all test classes
        if (sharedPostgres == null) {
            @SuppressWarnings("resource") // Container is intentionally kept alive across test classes
            PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
                    .withDatabaseName("peegeeq_test")
                    .withUsername("test")
                    .withPassword("test")
                    .withSharedMemorySize(256 * 1024 * 1024L) // 256MB shared memory
                    .withCommand("postgres", "-c", "max_connections=300", "-c", "fsync=off", "-c", "synchronous_commit=off"); // Performance optimizations for tests
            container.start();
            sharedPostgres = container;

            // Add shutdown hook to properly clean up the container
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (sharedPostgres != null && sharedPostgres.isRunning()) {
                    sharedPostgres.stop();
                }
            }));
        }
    }

    // Expose the shared container as a static getter for consistent access
    protected static PostgreSQLContainer<?> getPostgres() {
        return sharedPostgres;
    }

    protected PeeGeeQManager manager;
    protected BiTemporalEventStoreFactory factory;
    protected EventStore<TestEvent> eventStore;

    @BeforeAll
    static void logSystemInfo() {
        logger.info("=== BITEMPORAL PERFORMANCE TEST SUITE ===");
        logger.info("System Information:");
        logger.info(SystemInfoCollector.formatAsSummary());
        logger.info("=== Starting Performance Tests ===");
    }

    @BeforeEach
    void setUp() throws Exception {
        logger.info("Setting up bi-temporal test...");
        
        setupPerformanceConfiguration();
        initializePeeGeeQ();
        createEventStore();
        performWarmup();
        
        logger.info("✅ Bi-temporal test setup complete");
    }

    @AfterEach
    void tearDown() throws Exception {
        cleanupDatabase();
        closeResources();
        logger.info("Bi-temporal test cleanup completed");
    }

    /**
     * Configure system properties for optimal performance testing.
     * Uses the same configuration patterns found in existing tests.
     */
    protected void setupPerformanceConfiguration() {
        // Set system properties for PeeGeeQ configuration
        System.setProperty("peegeeq.database.host", sharedPostgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(sharedPostgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", sharedPostgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", sharedPostgres.getUsername());
        System.setProperty("peegeeq.database.password", sharedPostgres.getPassword());

        // High-performance configuration for benchmarks
        System.setProperty("peegeeq.queue.batch-size", "100");
        System.setProperty("peegeeq.queue.polling-interval", "PT0.1S");
        System.setProperty("peegeeq.consumer.threads", "8");
        System.setProperty("peegeeq.database.pool.max-size", "100");
        System.setProperty("peegeeq.database.pool.min-size", "10");
        System.setProperty("peegeeq.database.pool.wait-queue-multiplier", "20");
        System.setProperty("peegeeq.metrics.jvm.enabled", "false");

        // CRITICAL PERFORMANCE CONFIGURATION: Enable all Vert.x PostgreSQL optimizations
        // Using September 11th documented configuration that achieved 956 events/sec
        System.setProperty("peegeeq.database.use.pipelined.client", "true");
        System.setProperty("peegeeq.database.pipelining.limit", "1024");  // Restored from 256 to 1024
        System.setProperty("peegeeq.database.event.loop.size", "16");     // Restored from 8 to 16
        System.setProperty("peegeeq.database.worker.pool.size", "32");    // Restored from 16 to 32
        System.setProperty("peegeeq.database.pool.max-size", "100");      // Restored from 50 to 100
        System.setProperty("peegeeq.database.pool.shared", "true");       // Enable shared pool (Sept 11th setting)
        System.setProperty("peegeeq.database.pool.wait-queue-size", "1000"); // Sept 11th documented value
        System.setProperty("peegeeq.metrics.jvm.enabled", "false");       // Disable JVM metrics overhead

        logger.info("🚀 Using COMPLETE Sept 11th performance configuration: batch-size=100, polling=100ms, threads=8, pipelining=1024, event-loops=16, workers=32, shared-pool=true");
    }

    /**
     * Initialize PeeGeeQ manager with configuration.
     */
    protected void initializePeeGeeQ() throws Exception {
        PeeGeeQConfiguration config = new PeeGeeQConfiguration();
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();
    }

    /**
     * Create event store factory and event store instance.
     */
    protected void createEventStore() throws Exception {
        factory = new BiTemporalEventStoreFactory(manager);
        eventStore = factory.createEventStore(TestEvent.class);
    }

    /**
     * Perform warmup operation to ensure reactive notification handler is active.
     * This follows the pattern from working ReactiveNotificationTest.
     */
    protected void performWarmup() throws Exception {
        TestEvent warmupEvent = new TestEvent("warmup", "warmup", 1);
        eventStore.append("WarmupEvent", warmupEvent, Instant.now()).get(5, TimeUnit.SECONDS);
        
        // Give the reactive notification handler time to become active
        Thread.sleep(1000);
    }

    /**
     * Clean up database tables to ensure test isolation using pure Vert.x.
     * Uses the same cleanup pattern found in existing tests.
     */
    protected void cleanupDatabase() {
        if (manager != null) {
            try {
                var dbConfig = manager.getConfiguration().getDatabaseConfig();
                io.vertx.pgclient.PgConnectOptions connectOptions = new io.vertx.pgclient.PgConnectOptions()
                    .setHost(dbConfig.getHost())
                    .setPort(dbConfig.getPort())
                    .setDatabase(dbConfig.getDatabase())
                    .setUser(dbConfig.getUsername())
                    .setPassword(dbConfig.getPassword());

                io.vertx.sqlclient.Pool pool = io.vertx.pgclient.PgBuilder.pool().connectingTo(connectOptions).build();

                pool.withConnection(conn ->
                    conn.query("DELETE FROM bitemporal_event_log").execute()
                ).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

                pool.close();
                logger.info("Database cleanup completed");
            } catch (Exception e) {
                logger.warn("Database cleanup failed (this may be expected): {}", e.getMessage());
            }
        }
    }

    /**
     * Close all resources properly.
     */
    protected void closeResources() {
        if (eventStore != null) eventStore.close();
        if (manager != null) manager.close();
    }
}
