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
import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.api.setup.DatabaseSetupStatus;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.junit.jupiter.api.Tag;

import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for RuntimeDatabaseSetupService using TestContainers.
 * Verifies the full wiring of native, outbox, and bitemporal modules.
 */
@ExtendWith(VertxExtension.class)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("integration")
class RuntimeDatabaseSetupServiceIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(RuntimeDatabaseSetupServiceIntegrationTest.class);

    @Container
    static PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        return PostgreSQLTestConstants.createContainer(
                "peegeeq_runtime_test",
                "peegeeq_test",
                "peegeeq_test"
        );
    }

    private DatabaseSetupService setupService;
    private String testSetupId;

    @BeforeAll
    void setUp(VertxTestContext testContext) {
        testSetupId = "runtime-integration-test-" + System.currentTimeMillis();
        setupService = PeeGeeQRuntime.createDatabaseSetupService();
        logger.info("=== Starting Runtime Integration Tests ===");
        logger.info("Test Setup ID: {}", testSetupId);
        logger.info("PostgreSQL: {}:{}", postgres.getHost(), postgres.getFirstMappedPort());
        testContext.completeNow();
    }

    @AfterAll
    void tearDown(VertxTestContext testContext) {
        if (setupService != null) {
            setupService.close()
                    .onSuccess(v -> {
                        setupService = null;
                        testContext.completeNow();
                    })
                    .onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }
    }

    @Test
    @Order(1)
    @DisplayName("createCompleteSetup - creates setup with native and outbox factories")
    void createCompleteSetup_createsSetupWithFactories(VertxTestContext ctx) {
        // Given
        DatabaseConfig dbConfig = new DatabaseConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .databaseName("test_db_" + System.currentTimeMillis())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
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

        // When / Then
        setupService.createCompleteSetup(request)
                .onSuccess(result -> {
                    ctx.verify(() -> {
                        assertNotNull(result, "Setup result should not be null");
                        assertEquals(testSetupId, result.getSetupId(), "Setup ID should match");
                        assertEquals(DatabaseSetupStatus.ACTIVE, result.getStatus(), "Status should be ACTIVE");

                        Map<String, QueueFactory> factories = result.getQueueFactories();
                        assertNotNull(factories, "Queue factories should not be null");
                        assertFalse(factories.isEmpty(), "Should have at least one queue factory");
                        assertTrue(factories.containsKey("testqueue"), "Should have testqueue factory");

                        QueueFactory factory = factories.get("testqueue");
                        assertNotNull(factory, "Queue factory should not be null");
                        logger.info("Queue factory type: {}", factory.getImplementationType());
                        logger.info("Setup created successfully with {} queue factories", factories.size());
                    });
                    ctx.completeNow();
                })
                .onFailure(ctx::failNow);
    }

    @Test
    @Order(2)
    @DisplayName("getSetupStatus - returns ACTIVE for existing setup")
    void getSetupStatus_returnsActiveForExistingSetup(VertxTestContext ctx) {
        setupService.getSetupStatus(testSetupId)
                .onSuccess(status -> {
                    ctx.verify(() -> assertEquals(DatabaseSetupStatus.ACTIVE, status, "Status should be ACTIVE"));
                    ctx.completeNow();
                })
                .onFailure(ctx::failNow);
    }

    @Test
    @Order(3)
    @DisplayName("getAllActiveSetupIds - includes test setup")
    void getAllActiveSetupIds_includesTestSetup(VertxTestContext ctx) {
        setupService.getAllActiveSetupIds()
                .onSuccess(activeIds -> {
                    ctx.verify(() -> {
                        assertNotNull(activeIds, "Active IDs should not be null");
                        assertTrue(activeIds.contains(testSetupId), "Should contain test setup ID");
                    });
                    ctx.completeNow();
                })
                .onFailure(ctx::failNow);
    }
}

