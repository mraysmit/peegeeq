package dev.mars.peegeeq.db.connection;

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