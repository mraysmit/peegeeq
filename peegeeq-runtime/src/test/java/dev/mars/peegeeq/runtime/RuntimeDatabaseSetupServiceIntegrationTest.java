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

package dev.mars.peegeeq.runtime;

import dev.mars.peegeeq.api.database.DatabaseConfig;
import dev.mars.peegeeq.api.database.QueueConfig;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.setup.DatabaseSetupRequest;
import dev.mars.peegeeq.api.setup.DatabaseSetupResult;
import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.api.setup.DatabaseSetupStatus;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for RuntimeDatabaseSetupService using TestContainers.
 * Verifies the full wiring of native, outbox, and bitemporal modules.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RuntimeDatabaseSetupServiceIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(RuntimeDatabaseSetupServiceIntegrationTest.class);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_runtime_test")
            .withUsername("peegeeq_test")
            .withPassword("peegeeq_test")
            .withSharedMemorySize(256 * 1024 * 1024L)
            .withReuse(false);

    private DatabaseSetupService setupService;
    private String testSetupId;

    @BeforeAll
    void setUp() {
        testSetupId = "runtime-integration-test-" + System.currentTimeMillis();
        setupService = PeeGeeQRuntime.createDatabaseSetupService();
        logger.info("=== Starting Runtime Integration Tests ===");
        logger.info("Test Setup ID: {}", testSetupId);
        logger.info("PostgreSQL: {}:{}", postgres.getHost(), postgres.getFirstMappedPort());
    }

    @AfterAll
    void tearDown() {
        if (setupService != null && testSetupId != null) {
            try {
                setupService.destroySetup(testSetupId).get(10, TimeUnit.SECONDS);
                logger.info("Test setup destroyed");
            } catch (Exception e) {
                logger.warn("Failed to destroy test setup: {}", e.getMessage());
            }
        }
    }

    @Test
    @Order(1)
    @DisplayName("createCompleteSetup - creates setup with native and outbox factories")
    void createCompleteSetup_createsSetupWithFactories() throws Exception {
        // Given
        DatabaseConfig dbConfig = new DatabaseConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .databaseName("test_db_" + System.currentTimeMillis())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .schema("public")
                .templateDatabase("template0")
                .encoding("UTF8")
                .build();

        QueueConfig queueConfig = new QueueConfig.Builder()
                .queueName("testqueue")
                .maxRetries(3)
                .visibilityTimeoutSeconds(30)
                .build();

        DatabaseSetupRequest request = new DatabaseSetupRequest(
                testSetupId,
                dbConfig,
                List.of(queueConfig),
                List.of(),
                Map.of()
        );

        // When
        DatabaseSetupResult result = setupService.createCompleteSetup(request)
                .get(30, TimeUnit.SECONDS);

        // Then
        assertNotNull(result, "Setup result should not be null");
        assertEquals(testSetupId, result.getSetupId(), "Setup ID should match");
        assertEquals(DatabaseSetupStatus.ACTIVE, result.getStatus(), "Status should be ACTIVE");

        // Verify queue factories are registered
        Map<String, QueueFactory> factories = result.getQueueFactories();
        assertNotNull(factories, "Queue factories should not be null");
        assertFalse(factories.isEmpty(), "Should have at least one queue factory");
        assertTrue(factories.containsKey("testqueue"), "Should have testqueue factory");

        QueueFactory factory = factories.get("testqueue");
        assertNotNull(factory, "Queue factory should not be null");
        logger.info("Queue factory type: {}", factory.getImplementationType());

        logger.info("Setup created successfully with {} queue factories", factories.size());
    }

    @Test
    @Order(2)
    @DisplayName("getSetupStatus - returns ACTIVE for existing setup")
    void getSetupStatus_returnsActiveForExistingSetup() throws Exception {
        // When
        DatabaseSetupStatus status = setupService.getSetupStatus(testSetupId)
                .get(10, TimeUnit.SECONDS);

        // Then
        assertEquals(DatabaseSetupStatus.ACTIVE, status, "Status should be ACTIVE");
    }

    @Test
    @Order(3)
    @DisplayName("getAllActiveSetupIds - includes test setup")
    void getAllActiveSetupIds_includesTestSetup() throws Exception {
        // When
        Set<String> activeIds = setupService.getAllActiveSetupIds()
                .get(10, TimeUnit.SECONDS);

        // Then
        assertNotNull(activeIds, "Active IDs should not be null");
        assertTrue(activeIds.contains(testSetupId), "Should contain test setup ID");
    }
}

