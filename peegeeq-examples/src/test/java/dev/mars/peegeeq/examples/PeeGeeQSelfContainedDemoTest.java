package dev.mars.peegeeq.examples;

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


import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Test version of the PeeGeeQ Self-Contained Demo that runs in JUnit context
 * 
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
public class PeeGeeQSelfContainedDemoTest {
    private static final Logger logger = LoggerFactory.getLogger(PeeGeeQSelfContainedDemoTest.class);

    @Test
    void runSelfContainedDemo() {
        logger.info("Starting PeeGeeQ Self-Contained Demo (Test Version)");
        logger.info("This demo will start a PostgreSQL container and demonstrate all PeeGeeQ features");
        
        // Start PostgreSQL container
        logger.info("Starting PostgreSQL container...");
        @SuppressWarnings("resource")
        PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
                .withDatabaseName("peegeeq_demo")
                .withUsername("peegeeq_demo")
                .withPassword("peegeeq_demo")
                .withSharedMemorySize(256 * 1024 * 1024L) // 256MB for better performance
                .withReuse(false); // Always start fresh for demo
        
        try {
            postgres.start();
            logger.info(">> PostgreSQL container started successfully");
            logger.info("   > Container URL: {}", postgres.getJdbcUrl());
            logger.info("   > Username: {}", postgres.getUsername());
            logger.info("   > Host: {}:{}", postgres.getHost(), postgres.getFirstMappedPort());

            // Configure PeeGeeQ to use the container
            configureSystemPropertiesForContainer(postgres);

            // Run the demo using the main class
            PeeGeeQSelfContainedDemo.runDemo();

        } catch (Exception e) {
            logger.error("XX Failed to run self-contained demo", e);
            throw new RuntimeException("Demo failed", e);
        } finally {
            // Explicitly stop the container to ensure proper cleanup
            logger.info("...Stopping PostgreSQL container...");
            try {
                postgres.stop();
                logger.info("PostgreSQL container stopped successfully");
            } catch (Exception e) {
                logger.warn("XX Error stopping PostgreSQL container: {}", e.getMessage());
            }
        }

        logger.info("Self-contained demo completed successfully!");
    }
    
    /**
     * Configures system properties to use the TestContainer database.
     */
    private static void configureSystemPropertiesForContainer(PostgreSQLContainer<?> postgres) {
        logger.info("  Configuring PeeGeeQ to use container database...");
        
        // Extract connection details from container
        String host = postgres.getHost();
        Integer port = postgres.getFirstMappedPort();
        String database = postgres.getDatabaseName();
        String username = postgres.getUsername();
        String password = postgres.getPassword();
        
        // Set system properties for PeeGeeQ configuration
        System.setProperty("peegeeq.database.host", host);
        System.setProperty("peegeeq.database.port", port.toString());
        System.setProperty("peegeeq.database.name", database);
        System.setProperty("peegeeq.database.username", username);
        System.setProperty("peegeeq.database.password", password);
        
        // Enable all features for demo
        System.setProperty("peegeeq.metrics.enabled", "true");
        System.setProperty("peegeeq.health.enabled", "true");
        System.setProperty("peegeeq.circuit-breaker.enabled", "true");
        System.setProperty("peegeeq.queue.dead-letter-enabled", "true");
        System.setProperty("peegeeq.migration.enabled", "true");
        System.setProperty("peegeeq.migration.auto-migrate", "true");
        
        logger.info("Configuration complete");
    }
}
