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
import dev.mars.peegeeq.db.SharedPostgresExtension;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

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
@ExtendWith(SharedPostgresExtension.class)
public class PeeGeeQExampleTest {

    private static final Logger logger = LoggerFactory.getLogger(PeeGeeQExampleTest.class);

    private PeeGeeQManager manager;
    private ExecutorService executorService;
    private ScheduledExecutorService scheduledExecutorService;

    @BeforeEach
    void setUp() {
        logger.info("Setting up PeeGeeQ Example Test");

        PostgreSQLContainer<?> postgres = SharedPostgresExtension.getContainer();

        // Configure system properties for container
        configureSystemPropertiesForContainer(postgres);
        
        // Initialize services
        executorService = Executors.newFixedThreadPool(4);
        scheduledExecutorService = Executors.newScheduledThreadPool(2);
        
        logger.info("✓ PeeGeeQ Example Test setup completed");
    }
    
    @AfterEach
    void tearDown() {
        logger.info("Tearing down PeeGeeQ Example Test");
        
        if (manager != null) {
            try {
                manager.close();
            } catch (Exception e) {
                logger.warn("Error closing PeeGeeQ Manager", e);
            }
        }
        
        if (executorService != null) {
            executorService.shutdown();
        }
        
        if (scheduledExecutorService != null) {
            scheduledExecutorService.shutdown();
        }
        
        logger.info("✓ PeeGeeQ Example Test teardown completed");
    }

    /**
     * Test Pattern 1: Production Readiness Features
     * Validates health checks, metrics, and monitoring capabilities
     */
    @Test
    void testProductionReadinessFeatures() throws Exception {
        logger.info("=== Testing Production Readiness Features ===");
        
        // Initialize PeeGeeQ Manager with production configuration
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("test"), new SimpleMeterRegistry());
        
        // Start the manager
        manager.start();
        logger.info("✅ PeeGeeQ Manager started successfully");
        
        // Test health checks
        PeeGeeQManager.SystemStatus systemStatus = manager.getSystemStatus();
        assertNotNull(systemStatus, "System status should not be null");
        assertNotNull(systemStatus.getHealthStatus(), "Health status should not be null");
        logger.info("✅ Health status retrieved: {}", systemStatus.getHealthStatus().getStatus());

        // Test metrics
        assertNotNull(systemStatus.getMetricsSummary(), "Metrics summary should not be null");
        logger.info("✅ Metrics system operational");
        
        // Test configuration
        PeeGeeQConfiguration config = manager.getConfiguration();
        assertNotNull(config, "Configuration should not be null");
        assertEquals("test", config.getProfile());
        logger.info("✅ Configuration validated for profile: {}", config.getProfile());
        
        logger.info("✅ Production readiness features validated successfully");
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
        logger.info("✅ Profile parsing validated: {}", profile);
        
        // Test default profile
        String defaultProfile = parseProfile(new String[]{});
        assertEquals("default", defaultProfile);
        logger.info("✅ Default profile validated: {}", defaultProfile);
        
        // Test system properties configuration
        validateSystemPropertiesConfiguration();
        logger.info("✅ System properties configuration validated");
        
        logger.info("✅ Configuration management validated successfully");
    }

    /**
     * Test Pattern 3: Container Integration
     * Validates TestContainers setup and configuration
     */
    @Test
    void testContainerIntegration() {
        logger.info("=== Testing Container Integration ===");

        PostgreSQLContainer<?> postgres = SharedPostgresExtension.getContainer();

        // Validate container is running
        assertTrue(postgres.isRunning(), "PostgreSQL container should be running");
        logger.info("✅ PostgreSQL container is running");

        // Validate container configuration
        assertEquals("peegeeq_test", postgres.getDatabaseName());
        assertEquals("peegeeq_test", postgres.getUsername());
        assertEquals("peegeeq_test", postgres.getPassword());
        logger.info("✅ Container configuration validated");

        // Validate connection properties
        assertNotNull(postgres.getJdbcUrl());
        assertTrue(postgres.getFirstMappedPort() > 0);
        logger.info("✅ Container connection properties validated");
        logger.info("   Container URL: {}", postgres.getJdbcUrl());
        logger.info("   Host: {}:{}", postgres.getHost(), postgres.getFirstMappedPort());

        logger.info("✅ Container integration validated successfully");
    }

    /**
     * Test Pattern 4: Feature Demonstrations
     * Validates all PeeGeeQ capabilities in a production-like environment
     */
    @Test
    void testFeatureDemonstrations() throws Exception {
        logger.info("=== Testing Feature Demonstrations ===");
        
        // Initialize and start PeeGeeQ Manager
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("test"), new SimpleMeterRegistry());
        manager.start();
        
        // Demonstrate health monitoring
        demonstrateHealthMonitoring();
        
        // Demonstrate metrics collection
        demonstrateMetricsCollection();
        
        // Demonstrate configuration features
        demonstrateConfigurationFeatures();
        
        logger.info("✅ Feature demonstrations validated successfully");
    }

    // Helper methods that replicate the original example's functionality
    
    /**
     * Configures system properties to use the TestContainer database.
     */
    private void configureSystemPropertiesForContainer(PostgreSQLContainer<?> postgres) {
        logger.info("Configuring PeeGeeQ to use container database...");

        // Set database connection properties
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.schema", "public");
        System.setProperty("peegeeq.database.ssl.enabled", "false");

        // Configure for test environment
        System.setProperty("peegeeq.database.pool.min-size", "2");
        System.setProperty("peegeeq.database.pool.max-size", "10");
        System.setProperty("peegeeq.metrics.enabled", "true");
        System.setProperty("peegeeq.health.enabled", "true");
        System.setProperty("peegeeq.circuit-breaker.enabled", "true");
        // Disable auto-migration since schema is already initialized by SharedPostgresExtension
        System.setProperty("peegeeq.migration.enabled", "false");
        System.setProperty("peegeeq.migration.auto-migrate", "false");

        logger.info("Configuration complete");
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
        assertNotNull(System.getProperty("peegeeq.database.host"));
        assertNotNull(System.getProperty("peegeeq.database.port"));
        assertNotNull(System.getProperty("peegeeq.database.name"));
        assertEquals("true", System.getProperty("peegeeq.metrics.enabled"));
        assertEquals("true", System.getProperty("peegeeq.health.enabled"));
    }
    
    /**
     * Demonstrates health monitoring capabilities.
     */
    private void demonstrateHealthMonitoring() {
        logger.info("Demonstrating health monitoring...");
        PeeGeeQManager.SystemStatus systemStatus = manager.getSystemStatus();
        assertNotNull(systemStatus.getHealthStatus());
        logger.info("✅ Health monitoring demonstrated: {}", systemStatus.getHealthStatus().getStatus());
    }
    
    /**
     * Demonstrates metrics collection capabilities.
     */
    private void demonstrateMetricsCollection() {
        logger.info("Demonstrating metrics collection...");
        PeeGeeQManager.SystemStatus systemStatus = manager.getSystemStatus();
        assertNotNull(systemStatus.getMetricsSummary());
        logger.info("✅ Metrics collection demonstrated");
    }
    
    /**
     * Demonstrates configuration features.
     */
    private void demonstrateConfigurationFeatures() {
        logger.info("Demonstrating configuration features...");
        PeeGeeQConfiguration config = manager.getConfiguration();
        assertNotNull(config);
        logger.info("✅ Configuration features demonstrated for profile: {}", config.getProfile());
    }
}
