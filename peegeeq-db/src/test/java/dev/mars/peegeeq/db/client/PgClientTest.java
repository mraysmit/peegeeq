package dev.mars.peegeeq.db.client;

import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.connection.PgListenerConnection;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
public class PgClientTest {

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass");

    private PgClientFactory clientFactory;
    private PgClient pgClient;

    @BeforeEach
    void setUp() {
        clientFactory = new PgClientFactory();

        // Create connection config from TestContainer
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .database(postgres.getDatabaseName())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .build();

        // Create client
        pgClient = clientFactory.createClient("test-client", connectionConfig);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (clientFactory != null) {
            clientFactory.close();
        }
    }

    @Test
    void testGetConnection() throws SQLException {
        // Get connection
        try (Connection connection = pgClient.getConnection()) {
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
    void testCreateListenerConnection() throws Exception {
        // Create listener connection
        try (PgListenerConnection listenerConnection = pgClient.createListenerConnection()) {
            // Verify listener connection is created
            assertNotNull(listenerConnection);
        }
    }

    @Test
    void testWithConnection() throws SQLException {
        // Use withConnection to execute a query
        AtomicInteger result = new AtomicInteger();
        pgClient.withConnection(connection -> {
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT 1")) {
                if (rs.next()) {
                    result.set(rs.getInt(1));
                }
            }
        });

        assertEquals(1, result.get());
    }

    @Test
    void testWithConnectionResult() throws SQLException {
        // Use withConnectionResult to execute a query and return a result
        Integer result = pgClient.withConnectionResult(connection -> {
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT 1")) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
                return null;
            }
        });

        assertEquals(1, result);
    }
}