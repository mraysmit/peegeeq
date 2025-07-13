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


import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

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
@Testcontainers
public class PgConnectionManagerTest {

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass");

    private PgConnectionManager connectionManager;

    @BeforeEach
    void setUp() {
        connectionManager = new PgConnectionManager();
    }

    @AfterEach
    void tearDown() throws Exception {
        connectionManager.close();
    }

    @Test
    void testGetOrCreateDataSource() {
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

        // Get data source
        var dataSource = connectionManager.getOrCreateDataSource("test-service", connectionConfig, poolConfig);
        
        // Assert data source is not null
        assertNotNull(dataSource);
        
        // Get the same data source again and verify it's the same instance
        var dataSource2 = connectionManager.getOrCreateDataSource("test-service", connectionConfig, poolConfig);
        assertSame(dataSource, dataSource2);
    }

    @Test
    void testGetConnection() throws SQLException {
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

        // Create data source
        connectionManager.getOrCreateDataSource("test-service", connectionConfig, poolConfig);
        
        // Get connection
        try (Connection connection = connectionManager.getConnection("test-service")) {
            // Verify connection is valid
            assertTrue(connection.isValid(1));
            
            // Execute a simple query
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT 1")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
        }
    }

    @Test
    void testGetConnectionThrowsExceptionForNonExistentService() {
        assertThrows(IllegalStateException.class, () -> {
            connectionManager.getConnection("non-existent-service");
        });
    }
}