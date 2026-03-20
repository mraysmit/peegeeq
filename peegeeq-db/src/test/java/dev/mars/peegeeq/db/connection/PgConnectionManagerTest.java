package dev.mars.peegeeq.db.connection;

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


import dev.mars.peegeeq.db.SharedPostgresTestExtension;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;


import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Implementation of PgConnectionManagerTest functionality.
 *
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith({SharedPostgresTestExtension.class, VertxExtension.class})
public class PgConnectionManagerTest {
    private static final Logger logger = LoggerFactory.getLogger(PgConnectionManagerTest.class);

    private PgConnectionManager connectionManager;

    @BeforeEach
    void setUp(Vertx vertx) {
        connectionManager = new PgConnectionManager(vertx);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        if (connectionManager != null) {
            connectionManager.closeAsync()
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }
    }

    @Test
    void testGetOrCreateReactivePool() {
        PostgreSQLContainer postgres = SharedPostgresTestExtension.getContainer();

        // Create connection config from TestContainer
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .database(postgres.getDatabaseName())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .build();

        // Create pool config
        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
                .maxSize(5)
                .build();

        // Get reactive pool
        var pool = connectionManager.getOrCreateReactivePool("test-service", connectionConfig, poolConfig);

        // Assert pool is not null
        assertNotNull(pool);

        // Get the same pool again and verify it's the same instance
        var pool2 = connectionManager.getOrCreateReactivePool("test-service", connectionConfig, poolConfig);
        assertSame(pool, pool2);
    }

    @Test
    void testGetReactiveConnection(VertxTestContext testContext) {
        PostgreSQLContainer postgres = SharedPostgresTestExtension.getContainer();

        // Create connection config from TestContainer
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .database(postgres.getDatabaseName())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .build();

        // Create pool config
        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
                .maxSize(5)
                .build();

        // Create reactive pool
        connectionManager.getOrCreateReactivePool("test-service", connectionConfig, poolConfig);

        // Get reactive connection and test it
        connectionManager.getReactiveConnection("test-service")
            .compose(connection -> {
                return connection.query("SELECT 1").execute()
                    .compose(rowSet -> {
                        testContext.verify(() -> {
                            assertTrue(rowSet.iterator().hasNext());
                            assertEquals(1, rowSet.iterator().next().getInteger(0));
                        });
                        return connection.close();
                    });
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testGetReactiveConnectionThrowsExceptionForNonExistentService(VertxTestContext testContext) {
        connectionManager.getReactiveConnection("non-existent-service")
            .onSuccess(conn -> testContext.failNow("Expected exception for non-existent service"))
            .onFailure(err -> testContext.verify(() -> {
                assertTrue(err instanceof IllegalStateException,
                    "Expected IllegalStateException but got " + err.getClass().getName());
                testContext.completeNow();
            }));
    }

    @Test
    void testHealthCheck(VertxTestContext testContext) {
        logger.info("TEST: Health check functionality with real database connectivity");

        String serviceId = "test-service";

        // Create connection config using SharedPostgresTestExtension
        PostgreSQLContainer postgres = SharedPostgresTestExtension.getContainer();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .database(postgres.getDatabaseName())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .build();

        // Create pool config
        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
                .maxSize(5)
                .build();

        // Create a pool
        Pool pool = connectionManager.getOrCreateReactivePool(serviceId, connectionConfig, poolConfig);
        assertNotNull(pool, "Pool should be created");

        // Test health check - should pass with real database
        connectionManager.checkHealth(serviceId)
            .compose(isHealthy -> {
                testContext.verify(() ->
                    assertTrue(isHealthy, "Health check should pass for valid pool with real database"));
                return connectionManager.checkHealth("non-existent");
            })
            .onSuccess(nonExistentHealthy -> testContext.verify(() -> {
                assertFalse(nonExistentHealthy, "Health check should fail for non-existent service");
                logger.info("Health check test passed - database connectivity verified");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }
}