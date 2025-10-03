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


import dev.mars.peegeeq.db.SharedPostgresExtension;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.PostgreSQLContainer;

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
@ExtendWith(SharedPostgresExtension.class)
public class PgConnectionManagerTest {

    private PgConnectionManager connectionManager;
    private Vertx vertx;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        connectionManager = new PgConnectionManager(vertx);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connectionManager != null) {
            connectionManager.close();
        }
        if (vertx != null) {
            vertx.close();
        }
    }

    @Test
    void testGetOrCreateReactivePool() {
        PostgreSQLContainer<?> postgres = SharedPostgresExtension.getContainer();

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
                .minimumIdle(1)
                .maximumPoolSize(5)
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
    void testGetReactiveConnection() throws Exception {
        PostgreSQLContainer<?> postgres = SharedPostgresExtension.getContainer();

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
                .minimumIdle(1)
                .maximumPoolSize(5)
                .build();

        // Create reactive pool
        connectionManager.getOrCreateReactivePool("test-service", connectionConfig, poolConfig);

        // Get reactive connection and test it
        connectionManager.getReactiveConnection("test-service")
            .compose(connection -> {
                // Execute a simple query using reactive patterns
                return connection.query("SELECT 1").execute()
                    .compose(rowSet -> {
                        // Verify result
                        assertTrue(rowSet.iterator().hasNext());
                        assertEquals(1, rowSet.iterator().next().getInteger(0));
                        return connection.close();
                    });
            })
            .toCompletionStage()
            .toCompletableFuture()
            .get(10, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Test
    void testGetReactiveConnectionThrowsExceptionForNonExistentService() throws Exception {
        // Test that getting a reactive connection for non-existent service fails
        try {
            connectionManager.getReactiveConnection("non-existent-service")
                .toCompletionStage()
                .toCompletableFuture()
                .get(5, java.util.concurrent.TimeUnit.SECONDS);
            fail("Expected exception for non-existent service");
        } catch (Exception e) {
            // Expected - should fail for non-existent service
            assertTrue(e.getCause() instanceof IllegalStateException);
        }
    }
}