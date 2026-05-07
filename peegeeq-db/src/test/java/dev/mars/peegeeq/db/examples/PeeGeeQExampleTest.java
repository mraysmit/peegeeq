package dev.mars.peegeeq.db.examples;

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

import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.SharedPostgresTestExtension;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test for PeeGeeQExample functionality.
 *
 * This test validates production readiness features from the original 484-line example:
 * 1. Production Readiness - Health checks, metrics, and monitoring
 * 2. Configuration Management - System properties and profile handling
 * 3. Container Integration - TestContainers setup and configuration
 * 4. Feature Demonstrations - All PeeGeeQ capabilities
 *
 * All original functionality is preserved with enhanced test assertions and documentation.
 * Tests demonstrate comprehensive PeeGeeQ production deployment patterns.
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith({SharedPostgresTestExtension.class, VertxExtension.class})
public class PeeGeeQExampleTest {

    private static final Logger logger = LoggerFactory.getLogger(PeeGeeQExampleTest.class);

    private PeeGeeQManager manager;
    private Properties containerProps;

    @BeforeEach
    void setUp() {
        logger.info("Setting up PeeGeeQ Example Test");

        PostgreSQLContainer postgres = SharedPostgresTestExtension.getContainer();

        containerProps = buildContainerProperties(postgres);
        
        logger.info("✓ PeeGeeQ Example Test setup completed");
    }
    
    @AfterEach
    void tearDown(VertxTestContext testContext) {
        logger.info("Tearing down PeeGeeQ Example Test");
        
        if (manager != null) {
            manager.closeReactive()
                .onSuccess(v -> {
                    logger.info("\u2713 PeeGeeQ Example Test teardown completed");
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
        } else {
            logger.info("\u2713 PeeGeeQ Example Test teardown completed");
            testContext.completeNow();
        }
    }

    /**
     * Test Pattern 1: Production Readiness Features
     * Validates health checks, metrics, and monitoring capabilities
     */
    @Test
    void testProductionReadinessFeatures(VertxTestContext testContext) {
        logger.info("=== Testing Production Readiness Features ===");
        
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("test", containerProps), new SimpleMeterRegistry());
        manager.start()
            .compose(v -> manager.getSystemStatus())
            .onComplete(testContext.succeeding(systemStatus -> testContext.verify(() -> {
                assertNotNull(systemStatus, "System status should not be null");
                assertNotNull(systemStatus.getHealthStatus(), "Health status should not be null");
                logger.info("Health status retrieved: {}", systemStatus.getHealthStatus().getStatus());

                assertNotNull(systemStatus.getMetricsSummary(), "Metrics summary should not be null");
                logger.info("Metrics system operational");
                
                PeeGeeQConfiguration config = manager.getConfiguration();
                assertNotNull(config, "Configuration should not be null");
                assertEquals("test", config.getProfile());
                logger.info("Configuration validated for profile: {}", config.getProfile());
                
                logger.info("Production readiness features validated successfully");
                testContext.completeNow();
            })));
    }

    /**
     * Test Pattern 2: Configuration Management
     * Validates system properties and profile handling
     */
    @Test
    void testConfigurationManagement() throws Exception {
        logger.info("=== Testing Configuration Management ===");
        
        // Test profile parsing
        String profile = parseProfile(new String[]{"--profile", "test"});
        assertEquals("test", profile);
        logger.info("Profile parsing validated: {}", profile);
        
        // Test default profile
        String defaultProfile = parseProfile(new String[]{});
        assertEquals("default", defaultProfile);
        logger.info("Default profile validated: {}", defaultProfile);
        
        // Test system properties configuration
        validateSystemPropertiesConfiguration();
        logger.info("System properties configuration validated");
        
        logger.info("Configuration management validated successfully");
    }

    /**
     * Test Pattern 3: Container Integration
     * Validates TestContainers setup and configuration
     */
    @Test
    void testContainerIntegration() {
        logger.info("=== Testing Container Integration ===");

        PostgreSQLContainer postgres = SharedPostgresTestExtension.getContainer();

        // Validate container is running
        assertTrue(postgres.isRunning(), "PostgreSQL container should be running");
        logger.info("PostgreSQL container is running");

        // Validate container configuration
        assertEquals("peegeeq_test", postgres.getDatabaseName());
        assertEquals("peegeeq_test", postgres.getUsername());
        assertEquals("peegeeq_test", postgres.getPassword());
        logger.info("Container configuration validated");

        // Validate connection properties
        assertNotNull(postgres.getJdbcUrl());
        assertTrue(postgres.getFirstMappedPort() > 0);
        logger.info("Container connection properties validated");
        logger.info("   Container URL: {}", postgres.getJdbcUrl());
        logger.info("   Host: {}:{}", postgres.getHost(), postgres.getFirstMappedPort());

        logger.info("Container integration validated successfully");
    }

    /**
     * Test Pattern 4: Feature Demonstrations
     * Validates all PeeGeeQ capabilities in a production-like environment
     */
    @Test
    void testFeatureDemonstrations(VertxTestContext testContext) {
        logger.info("=== Testing Feature Demonstrations ===");
        
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("test", containerProps), new SimpleMeterRegistry());
        manager.start()
            .compose(v -> manager.getSystemStatus())
            .onComplete(testContext.succeeding(systemStatus -> testContext.verify(() -> {
                // Health monitoring
                assertNotNull(systemStatus.getHealthStatus());
                logger.info("Health monitoring demonstrated: {}", systemStatus.getHealthStatus().getStatus());
                
                // Metrics collection
                assertNotNull(systemStatus.getMetricsSummary());
                logger.info("Metrics collection demonstrated");
                
                // Configuration
                PeeGeeQConfiguration config = manager.getConfiguration();
                assertNotNull(config);
                logger.info("Configuration features demonstrated for profile: {}", config.getProfile());
                
                logger.info("Feature demonstrations validated successfully");
                testContext.completeNow();
            })));
    }

    // Helper methods that replicate the original example's functionality
    
    /**
     * Configures system properties to use the TestContainer database.
     */
    private Properties buildContainerProperties(PostgreSQLContainer postgres) {
        logger.info("Configuring PeeGeeQ to use container database...");

        Properties props = new Properties();
        // Set database connection properties
        props.setProperty("peegeeq.database.host", postgres.getHost());
        props.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        props.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        props.setProperty("peegeeq.database.username", postgres.getUsername());
        props.setProperty("peegeeq.database.password", postgres.getPassword());
        props.setProperty("peegeeq.database.schema", "public");
        props.setProperty("peegeeq.database.ssl.enabled", "false");
        // Configure for test environment
        props.setProperty("peegeeq.database.pool.min-size", "2");
        props.setProperty("peegeeq.database.pool.max-size", "3");
        props.setProperty("peegeeq.database.pool.shared", "false");
        props.setProperty("peegeeq.database.pool.idle-timeout-ms", "2000");
        props.setProperty("peegeeq.database.pool.connection-timeout-ms", "5000");
        props.setProperty("peegeeq.metrics.enabled", "true");
        props.setProperty("peegeeq.health.enabled", "true");
        props.setProperty("peegeeq.circuit-breaker.enabled", "true");
        // Disable auto-migration since schema is already initialized by SharedPostgresTestExtension
        props.setProperty("peegeeq.migration.enabled", "false");
        props.setProperty("peegeeq.migration.auto-migrate", "false");

        logger.info("Configuration complete");
        return props;
    }
    
    /**
     * Parses command line arguments for profile selection.
     */
    private String parseProfile(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--profile".equals(args[i])) {
                return args[i + 1];
            }
        }
        return "default";
    }
    
    /**
     * Validates system properties configuration.
     */
    private void validateSystemPropertiesConfiguration() {
        assertNotNull(containerProps.getProperty("peegeeq.database.host"));
        assertNotNull(containerProps.getProperty("peegeeq.database.port"));
        assertNotNull(containerProps.getProperty("peegeeq.database.name"));
        assertEquals("true", containerProps.getProperty("peegeeq.metrics.enabled"));
        assertEquals("true", containerProps.getProperty("peegeeq.health.enabled"));
    }
}


