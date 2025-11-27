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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.UUID;

/**
 * Base class for integration tests that provides proper database connection management,
 * TestContainers lifecycle management, and resource cleanup.
 *
 * <p>This class uses {@link SharedPostgresExtension} to provide a SHARED PostgreSQL
 * container across ALL tests in the module, ensuring database schema is created ONCE
 * even with parallel test execution.</p>
 *
 * <p>Key features:</p>
 * <ul>
 *   <li>Single shared TestContainer across all tests (via extension)</li>
 *   <li>Database schema created once globally, thread-safe for parallel execution</li>
 *   <li>Tests can run in parallel without schema conflicts</li>
 *   <li>Proper resource cleanup after each test</li>
 * </ul>
 *
 * <p>Tests extending this class can safely run in parallel as configured in
 * junit-platform.properties.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 2.0
 */
@ExtendWith(SharedPostgresExtension.class)
public abstract class BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(BaseIntegrationTest.class);

    protected PeeGeeQManager manager;
    protected PeeGeeQConfiguration configuration;
    protected String testProfile;

    /**
     * Get the shared PostgreSQL container from the extension.
     */
    protected PostgreSQLContainer<?> getPostgres() {
        return SharedPostgresExtension.getContainer();
    }

    @BeforeEach
    protected void setUpBaseIntegration() throws Exception {
        // Generate unique test profile to avoid conflicts
        testProfile = "test-" + UUID.randomUUID().toString().substring(0, 8);
        
        logger.info("Setting up integration test with profile: {}", testProfile);
        
        // DO NOT clear system properties here - causes race conditions in parallel execution
        // Properties will be overwritten by setupDatabaseProperties() anyway
        
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
        
        // DO NOT clear test system properties in @AfterEach - causes race conditions in parallel execution
        // Each test's @BeforeEach will overwrite them anyway
        
        // Force garbage collection to help with cleanup
        System.gc();
        
        logger.info("Integration test teardown completed for profile: {}", testProfile);
    }
    
    /**
     * Set up database connection properties from TestContainer
     */
    private void setupDatabaseProperties() {
        PostgreSQLContainer<?> postgres = getPostgres();
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());

        // CRITICAL: Disable migrations - tables are created once by SharedPostgresExtension
        // Running migrations on every test causes duplicate key violations with shared TestContainer
        System.setProperty("peegeeq.migration.enabled", "false");

        logger.debug("Database properties set: {}:{}/{} (migrations disabled)",
            postgres.getHost(), postgres.getFirstMappedPort(), postgres.getDatabaseName());
    }
    
    /**
     * Set up test-specific configuration with conservative settings.
     *
     * <p>Pool size is kept small (max 3) to avoid exhausting PostgreSQL connections
     * when tests run in parallel. With 4 parallel test threads and multiple test classes,
     * connection usage can spike quickly.</p>
     */
    private void setupTestConfiguration() {
        // Database pool settings - very conservative for parallel tests
        // With 4 parallel threads and max 3 connections per pool, we use at most 12 connections per wave
        // PostgreSQL container is configured with max_connections=200
        System.setProperty("peegeeq.database.pool.min-size", "1");
        System.setProperty("peegeeq.database.pool.max-size", "3");
        System.setProperty("peegeeq.database.pool.connection-timeout", "PT10S");
        System.setProperty("peegeeq.database.pool.idle-timeout", "PT10S");  // Shorter idle timeout for faster cleanup
        System.setProperty("peegeeq.database.pool.max-lifetime", "PT2M");   // Shorter lifetime for faster recycling

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

        // Migration settings - keep disabled because schema is created once by SharedPostgresExtension
        // Avoid enabling migrations here to prevent duplicate DDL when tests run in parallel
        System.setProperty("peegeeq.migration.enabled", "false");
        System.setProperty("peegeeq.migration.auto-migrate", "false");

        logger.debug("Test configuration properties set");
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
