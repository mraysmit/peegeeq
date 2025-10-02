package dev.mars.peegeeq.db;

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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Base class for integration tests that provides proper database connection management,
 * TestContainers lifecycle management, and resource cleanup.
 *
 * <p>This class provides a SHARED PostgreSQL container and ensures database schema
 * is created ONCE at the class level, not per-test. Individual tests should only
 * clean up data, not recreate tables.</p>
 *
 * <p>Key improvements:</p>
 * <ul>
 *   <li>Single shared TestContainer across all tests</li>
 *   <li>Database schema created once in @BeforeAll</li>
 *   <li>Tests only clean data in @BeforeEach, not recreate tables</li>
 *   <li>Proper resource cleanup in @AfterAll</li>
 * </ul>
 */
@Testcontainers
public abstract class BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(BaseIntegrationTest.class);
    private static final AtomicBoolean schemaInitialized = new AtomicBoolean(false);

    @Container
    @SuppressWarnings("resource")
    protected static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_test")
            .withUsername("peegeeq_test")
            .withPassword("peegeeq_test")
            .withSharedMemorySize(256 * 1024 * 1024L) // 256MB
            .withReuse(true) // Reuse container across ALL tests for performance
            .withCommand("postgres", "-c", "fsync=off", "-c", "synchronous_commit=off"); // Faster for tests
    
    protected PeeGeeQManager manager;
    protected PeeGeeQConfiguration configuration;
    protected String testProfile;

    /**
     * Initialize database schema ONCE for all tests.
     * This runs before any test in any subclass.
     */
    @BeforeAll
    static void initializeSharedDatabase() throws Exception {
        if (schemaInitialized.compareAndSet(false, true)) {
            logger.info("Initializing shared database schema (ONE TIME ONLY)");

            // Create all tables using JDBC (not Vert.x) to ensure synchronous execution
            String jdbcUrl = postgres.getJdbcUrl();
            try (Connection conn = DriverManager.getConnection(jdbcUrl, postgres.getUsername(), postgres.getPassword());
                 Statement stmt = conn.createStatement()) {

                // Create all tables that tests need
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS outbox (
                        id BIGSERIAL PRIMARY KEY,
                        topic VARCHAR(255) NOT NULL,
                        payload JSONB NOT NULL,
                        created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                        processed_at TIMESTAMP WITH TIME ZONE,
                        processing_started_at TIMESTAMP WITH TIME ZONE,
                        status VARCHAR(50) DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'DEAD_LETTER')),
                        retry_count INT DEFAULT 0,
                        max_retries INT DEFAULT 3,
                        next_retry_at TIMESTAMP WITH TIME ZONE,
                        version INT DEFAULT 0,
                        headers JSONB DEFAULT '{}',
                        error_message TEXT,
                        correlation_id VARCHAR(255),
                        message_group VARCHAR(255),
                        priority INT DEFAULT 5 CHECK (priority BETWEEN 1 AND 10)
                    )
                    """);

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS queue_messages (
                        id BIGSERIAL PRIMARY KEY,
                        topic VARCHAR(255) NOT NULL,
                        payload JSONB NOT NULL,
                        visible_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                        created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                        lock_id BIGINT,
                        lock_until TIMESTAMP WITH TIME ZONE,
                        retry_count INT DEFAULT 0,
                        max_retries INT DEFAULT 3,
                        status VARCHAR(50) DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE', 'LOCKED', 'PROCESSED', 'FAILED', 'DEAD_LETTER')),
                        headers JSONB DEFAULT '{}',
                        correlation_id VARCHAR(255),
                        message_group VARCHAR(255),
                        priority INT DEFAULT 5 CHECK (priority BETWEEN 1 AND 10)
                    )
                    """);

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS dead_letter_queue (
                        id BIGSERIAL PRIMARY KEY,
                        original_table VARCHAR(50) NOT NULL,
                        original_id BIGINT NOT NULL,
                        topic VARCHAR(255) NOT NULL,
                        payload JSONB NOT NULL,
                        original_created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                        failed_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                        failure_reason TEXT NOT NULL,
                        retry_count INT NOT NULL,
                        headers JSONB DEFAULT '{}',
                        correlation_id VARCHAR(255),
                        message_group VARCHAR(255)
                    )
                    """);

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS queue_metrics (
                        id BIGSERIAL PRIMARY KEY,
                        metric_name VARCHAR(100) NOT NULL,
                        metric_value DOUBLE PRECISION NOT NULL,
                        tags JSONB DEFAULT '{}',
                        timestamp TIMESTAMP WITH TIME ZONE DEFAULT NOW()
                    )
                    """);

                logger.info("✅ Shared database schema initialized successfully");
            }
        } else {
            logger.info("Shared database schema already initialized - skipping");
        }
    }

    @BeforeEach
    protected void setUpBaseIntegration() throws Exception {
        // Generate unique test profile to avoid conflicts
        testProfile = "test-" + UUID.randomUUID().toString().substring(0, 8);
        
        logger.info("Setting up integration test with profile: {}", testProfile);
        
        // Clear any existing system properties that might interfere
        clearTestSystemProperties();
        
        // Set up database connection properties
        setupDatabaseProperties();
        
        // Set up test-specific configuration
        setupTestConfiguration();
        
        // Create and start manager
        configuration = new PeeGeeQConfiguration(testProfile);
        manager = new PeeGeeQManager(configuration, new SimpleMeterRegistry());
        
        // Start manager with proper error handling
        try {
            manager.start();
            logger.info("PeeGeeQ Manager started successfully for profile: {}", testProfile);
        } catch (Exception e) {
            logger.error("Failed to start PeeGeeQ Manager for profile: {}", testProfile, e);
            // Clean up on failure
            if (manager != null) {
                try {
                    manager.close();
                } catch (Exception closeException) {
                    logger.warn("Error closing manager after startup failure", closeException);
                }
            }
            throw e;
        }
    }
    
    @AfterEach
    void tearDownBaseIntegration() throws Exception {
        logger.info("Tearing down integration test for profile: {}", testProfile);
        
        // Close manager with proper error handling and timeout
        if (manager != null) {
            try {
                // Give manager time to complete any ongoing operations
                Thread.sleep(100);
                
                // Close manager (this should close all HikariCP pools)
                manager.close();
                logger.info("PeeGeeQ Manager closed successfully for profile: {}", testProfile);
                
                // Wait a bit for cleanup to complete
                Thread.sleep(100);
                
            } catch (Exception e) {
                logger.error("Error closing PeeGeeQ Manager for profile: {}", testProfile, e);
                // Don't rethrow - we want other cleanup to continue
            } finally {
                manager = null;
            }
        }
        
        // Clear test system properties
        clearTestSystemProperties();
        
        // Force garbage collection to help with cleanup
        System.gc();
        
        logger.info("Integration test teardown completed for profile: {}", testProfile);
    }
    
    /**
     * Set up database connection properties from TestContainer
     */
    private void setupDatabaseProperties() {
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());

        // CRITICAL: Disable migrations - tables are created once in @BeforeAll
        // Running migrations on every test causes duplicate key violations with shared TestContainer
        System.setProperty("peegeeq.migration.enabled", "false");

        logger.debug("Database properties set: {}:{}/{} (migrations disabled)",
            postgres.getHost(), postgres.getFirstMappedPort(), postgres.getDatabaseName());
    }
    
    /**
     * Set up test-specific configuration with conservative settings
     */
    private void setupTestConfiguration() {
        // Database pool settings - conservative for tests
        System.setProperty("peegeeq.database.pool.min-size", "1");
        System.setProperty("peegeeq.database.pool.max-size", "5");
        System.setProperty("peegeeq.database.pool.connection-timeout", "PT10S");
        System.setProperty("peegeeq.database.pool.idle-timeout", "PT30S");
        System.setProperty("peegeeq.database.pool.max-lifetime", "PT5M");
        
        // Health check settings - faster for tests
        System.setProperty("peegeeq.health.check-interval", "PT10S");
        System.setProperty("peegeeq.health.timeout", "PT5S");
        
        // Metrics settings - faster for tests
        System.setProperty("peegeeq.metrics.reporting-interval", "PT30S");
        System.setProperty("peegeeq.metrics.enabled", "true");
        
        // Circuit breaker settings
        System.setProperty("peegeeq.circuit-breaker.enabled", "true");
        System.setProperty("peegeeq.circuit-breaker.failure-rate-threshold", "50.0");
        System.setProperty("peegeeq.circuit-breaker.minimum-number-of-calls", "3");
        
        // Migration settings
        System.setProperty("peegeeq.migration.enabled", "true");
        System.setProperty("peegeeq.migration.auto-migrate", "true");
        
        logger.debug("Test configuration properties set");
    }
    
    /**
     * Clear test-specific system properties to avoid interference between tests
     */
    private void clearTestSystemProperties() {
        String[] propertiesToClear = {
            "peegeeq.database.host",
            "peegeeq.database.port", 
            "peegeeq.database.name",
            "peegeeq.database.username",
            "peegeeq.database.password",
            "peegeeq.database.pool.min-size",
            "peegeeq.database.pool.max-size",
            "peegeeq.database.pool.connection-timeout",
            "peegeeq.database.pool.idle-timeout",
            "peegeeq.database.pool.max-lifetime",
            "peegeeq.health.check-interval",
            "peegeeq.health.timeout",
            "peegeeq.metrics.reporting-interval",
            "peegeeq.metrics.enabled",
            "peegeeq.circuit-breaker.enabled",
            "peegeeq.circuit-breaker.failure-rate-threshold",
            "peegeeq.circuit-breaker.minimum-number-of-calls",
            "peegeeq.migration.enabled",
            "peegeeq.migration.auto-migrate"
        };
        
        for (String property : propertiesToClear) {
            System.clearProperty(property);
        }
    }
    
    /**
     * Wait for manager to be fully started and healthy
     */
    protected void waitForManagerReady() throws InterruptedException {
        if (manager == null) {
            throw new IllegalStateException("Manager is not initialized");
        }
        
        // Wait for health checks to stabilize
        Thread.sleep(1000);
        
        // Verify manager is healthy
        var healthStatus = manager.getHealthCheckManager().getOverallHealth();
        if (!healthStatus.isHealthy()) {
            logger.warn("Manager is not healthy after startup: {}", healthStatus.getComponents());
        }
    }
    
    /**
     * Get the test profile name for this test instance
     */
    protected String getTestProfile() {
        return testProfile;
    }
}
