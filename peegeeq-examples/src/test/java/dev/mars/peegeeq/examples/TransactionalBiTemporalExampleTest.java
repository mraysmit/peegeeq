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


import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashMap;
import java.util.Map;

/**
 * Test class for TransactionalBiTemporalExample demonstrating transactional BiTemporal event store functionality.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-26
 * @version 1.0
 */
@Testcontainers
public class TransactionalBiTemporalExampleTest {

    private static final Logger logger = LoggerFactory.getLogger(TransactionalBiTemporalExampleTest.class);

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_bitemporal_test")
            .withUsername("postgres")
            .withPassword("password");

    private final Map<String, String> originalProperties = new HashMap<>();

    @BeforeEach
    void setUp() {
        logger.info("=== Setting up TransactionalBiTemporalExampleTest ===");

        // Save original system properties
        saveOriginalProperties();

        // Configure database connection for TestContainers using standard property names
        System.setProperty("db.host", postgres.getHost());
        System.setProperty("db.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("db.database", postgres.getDatabaseName());
        System.setProperty("db.username", postgres.getUsername());
        System.setProperty("db.password", postgres.getPassword());

        logger.info("Database configured: {}:{}/{}", postgres.getHost(), postgres.getFirstMappedPort(), postgres.getDatabaseName());
    }

    @AfterEach
    void tearDown() {
        logger.info("=== Tearing down TransactionalBiTemporalExampleTest ===");

        // Restore original system properties
        restoreOriginalProperties();
    }

    private void saveOriginalProperties() {
        String[] propertiesToSave = {
            "db.host", "db.port", "db.database", "db.username", "db.password"
        };

        for (String property : propertiesToSave) {
            String value = System.getProperty(property);
            if (value != null) {
                originalProperties.put(property, value);
            }
        }
    }

    private void restoreOriginalProperties() {
        // Clear test properties
        System.clearProperty("db.host");
        System.clearProperty("db.port");
        System.clearProperty("db.database");
        System.clearProperty("db.username");
        System.clearProperty("db.password");

        // Restore original properties
        originalProperties.forEach(System::setProperty);
    }

    @Test
    void testTransactionalOrderProcessing() {
        logger.info("Testing transactional order processing with pure Vert.x 5.x BiTemporal event store");

        try {
            // Run the TransactionalBiTemporalExample main method
            TransactionalBiTemporalExample.main(new String[]{});

            logger.info("✅ TransactionalBiTemporalExample completed successfully");
        } catch (Exception e) {
            logger.error("❌ TransactionalBiTemporalExample failed: {}", e.getMessage(), e);
            throw new RuntimeException("TransactionalBiTemporalExample failed", e);
        }
    }

    @Test
    void testBiTemporalEventStoreIntegration() {
        logger.info("Testing BiTemporal event store integration");

        // Skip test - awaiting BiTemporal conversion to pure Vert.x 5.x
        org.junit.jupiter.api.Assumptions.assumeTrue(false,
            "BiTemporal event store integration test skipped - awaiting BiTemporal conversion to pure Vert.x 5.x");
    }

    @Test
    void testTransactionalConsistency() {
        logger.info("Testing transactional consistency");

        // Skip test - awaiting BiTemporal conversion to pure Vert.x 5.x
        org.junit.jupiter.api.Assumptions.assumeTrue(false,
            "Transactional consistency test skipped - awaiting BiTemporal conversion to pure Vert.x 5.x");
    }

    @Test
    void testEventStoreQueryCapabilities() {
        logger.info("Testing event store query capabilities");

        // Skip test - awaiting BiTemporal conversion to pure Vert.x 5.x
        org.junit.jupiter.api.Assumptions.assumeTrue(false,
            "Event store query capabilities test skipped - awaiting BiTemporal conversion to pure Vert.x 5.x");
    }
}
