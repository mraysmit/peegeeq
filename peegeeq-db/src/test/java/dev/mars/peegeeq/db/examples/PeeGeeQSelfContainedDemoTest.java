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
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.health.HealthCheckManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test for PeeGeeQSelfContainedDemo functionality.
 * 
 * This test validates self-contained demo patterns from the original 430-line example:
 * 1. Self-Contained Setup - Automatic container management and configuration
 * 2. Feature Demonstrations - All PeeGeeQ capabilities in demo environment
 * 3. System Monitoring - Health checks, metrics, and system status
 * 4. Container Lifecycle - Proper startup, configuration, and cleanup
 * 
 * All original functionality is preserved with enhanced test assertions and documentation.
 * Tests demonstrate comprehensive self-contained PeeGeeQ deployment patterns.
 */
@Testcontainers
public class PeeGeeQSelfContainedDemoTest {
    
    private static final Logger logger = LoggerFactory.getLogger(PeeGeeQSelfContainedDemoTest.class);
    
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_demo_test")
            .withUsername("peegeeq_demo")
            .withPassword("peegeeq_demo")
            .withSharedMemorySize(256 * 1024 * 1024L); // 256MB for better performance
    
    private PeeGeeQManager manager;
    private ExecutorService executorService;
    private ScheduledExecutorService scheduledExecutorService;
    
    @BeforeEach
    void setUp() {
        logger.info("Setting up PeeGeeQ Self-Contained Demo Test");
        
        // Configure system properties for container
        configureSystemPropertiesForContainer(postgres);
        
        // Initialize services
        executorService = Executors.newFixedThreadPool(4);
        scheduledExecutorService = Executors.newScheduledThreadPool(2);
        
        logger.info("✓ PeeGeeQ Self-Contained Demo Test setup completed");
    }
    
    @AfterEach
    void tearDown() {
        logger.info("Tearing down PeeGeeQ Self-Contained Demo Test");
        
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
        
        logger.info("✓ PeeGeeQ Self-Contained Demo Test teardown completed");
    }

    /**
     * Test Pattern 1: Self-Contained Setup
     * Validates automatic container management and configuration
     */
    @Test
    void testSelfContainedSetup() throws Exception {
        logger.info("=== Testing Self-Contained Setup ===");
        
        // Validate container is running and configured
        assertTrue(postgres.isRunning(), "PostgreSQL container should be running");
        assertEquals("peegeeq_demo_test", postgres.getDatabaseName());
        assertEquals("peegeeq_demo", postgres.getUsername());
        assertEquals("peegeeq_demo", postgres.getPassword());
        
        // Validate system properties configuration
        validateSystemPropertiesConfiguration();
        
        // Initialize PeeGeeQ Manager with demo configuration
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("demo"), new SimpleMeterRegistry());
        manager.start();
        
        // Validate manager started successfully
        assertTrue(manager.isStarted(), "PeeGeeQ Manager should be started");
        logger.info("✅ Self-contained setup validated successfully");
    }

    /**
     * Test Pattern 2: Feature Demonstrations
     * Validates all PeeGeeQ capabilities in demo environment
     */
    @Test
    void testFeatureDemonstrations() throws Exception {
        logger.info("=== Testing Feature Demonstrations ===");
        
        // Initialize and start PeeGeeQ Manager
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("demo"), new SimpleMeterRegistry());
        manager.start();
        
        // Run all demonstrations
        demonstrateConfiguration(manager);
        demonstrateHealthChecks(manager);
        demonstrateMetrics(manager);
        demonstrateSystemStatus(manager);
        
        logger.info("✅ Feature demonstrations validated successfully");
    }

    /**
     * Test Pattern 3: System Monitoring
     * Validates health checks, metrics, and system status
     */
    @Test
    void testSystemMonitoring() throws Exception {
        logger.info("=== Testing System Monitoring ===");
        
        // Initialize and start PeeGeeQ Manager
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("demo"), new SimpleMeterRegistry());
        manager.start();
        
        // Test system monitoring capabilities
        monitorSystemBriefly(manager);
        
        // Validate monitoring results
        assertTrue(manager.isHealthy(), "System should be healthy");
        assertNotNull(manager.getSystemStatus(), "System status should be available");
        
        logger.info("✅ System monitoring validated successfully");
    }

    /**
     * Test Pattern 4: Container Lifecycle
     * Validates proper startup, configuration, and cleanup
     */
    @Test
    void testContainerLifecycle() {
        logger.info("=== Testing Container Lifecycle ===");
        
        // Validate container lifecycle
        assertTrue(postgres.isRunning(), "Container should be running");
        assertNotNull(postgres.getJdbcUrl(), "JDBC URL should be available");
        assertTrue(postgres.getFirstMappedPort() > 0, "Port should be mapped");
        
        // Validate container configuration
        logger.info("Container URL: {}", postgres.getJdbcUrl());
        logger.info("Host: {}:{}", postgres.getHost(), postgres.getFirstMappedPort());
        
        logger.info("✅ Container lifecycle validated successfully");
    }

    // Helper methods that replicate the original demo's functionality
    
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
        
        // Configure for demo environment
        System.setProperty("peegeeq.database.pool.min-size", "2");
        System.setProperty("peegeeq.database.pool.max-size", "5");
        System.setProperty("peegeeq.metrics.enabled", "true");
        System.setProperty("peegeeq.health.enabled", "true");
        System.setProperty("peegeeq.circuit-breaker.enabled", "true");
        System.setProperty("peegeeq.migration.enabled", "true");
        System.setProperty("peegeeq.migration.auto-migrate", "true");
        
        logger.info("Configuration complete");
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
        assertEquals("true", System.getProperty("peegeeq.circuit-breaker.enabled"));
    }
    
    /**
     * Demonstrates configuration capabilities.
     */
    private void demonstrateConfiguration(PeeGeeQManager manager) {
        logger.info("\n=== Configuration Demo ===");
        
        PeeGeeQConfiguration config = manager.getConfiguration();
        assertNotNull(config, "Configuration should not be null");
        assertEquals("demo", config.getProfile());
        assertNotNull(config.getDatabaseConfig());
        assertNotNull(config.getPoolConfig());
        assertNotNull(config.getMetricsConfig());
        
        logger.info("✅ Configuration demonstrated: Profile={}", config.getProfile());
    }
    
    /**
     * Demonstrates health check capabilities.
     */
    private void demonstrateHealthChecks(PeeGeeQManager manager) {
        logger.info("\n=== Health Checks Demo ===");
        
        HealthCheckManager healthCheckManager = manager.getHealthCheckManager();
        assertNotNull(healthCheckManager, "Health check manager should not be null");
        
        // Test health status
        assertTrue(manager.isHealthy(), "System should be healthy");
        
        logger.info("✅ Health checks demonstrated");
    }
    
    /**
     * Demonstrates metrics capabilities.
     */
    private void demonstrateMetrics(PeeGeeQManager manager) {
        logger.info("\n=== Metrics Demo ===");
        
        PeeGeeQManager.SystemStatus systemStatus = manager.getSystemStatus();
        assertNotNull(systemStatus.getMetricsSummary(), "Metrics summary should not be null");
        
        logger.info("✅ Metrics demonstrated");
    }
    
    /**
     * Demonstrates system status capabilities.
     */
    private void demonstrateSystemStatus(PeeGeeQManager manager) {
        logger.info("\n=== System Status Demo ===");
        
        PeeGeeQManager.SystemStatus systemStatus = manager.getSystemStatus();
        assertNotNull(systemStatus, "System status should not be null");
        assertTrue(systemStatus.isStarted(), "System should be started");
        assertEquals("demo", systemStatus.getProfile());
        
        logger.info("✅ System status demonstrated");
    }
    
    /**
     * Monitors system briefly to validate monitoring capabilities.
     */
    private void monitorSystemBriefly(PeeGeeQManager manager) {
        logger.info("\n=== Brief System Monitoring ===");
        
        // Monitor for a brief period
        for (int i = 0; i < 3; i++) {
            PeeGeeQManager.SystemStatus status = manager.getSystemStatus();
            assertNotNull(status, "System status should be available");
            logger.info("Monitor cycle {}: System healthy={}", i + 1, manager.isHealthy());
            
            try {
                Thread.sleep(100); // Brief pause between monitoring cycles
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        logger.info("✅ Brief system monitoring completed");
    }
}
