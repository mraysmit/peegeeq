package dev.mars.peegeeq.db.transaction;

import dev.mars.peegeeq.db.client.PgClient;
import dev.mars.peegeeq.db.client.PgClientFactory;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
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
public class PgTransactionManagerTest {

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass");

    private PgClientFactory clientFactory;
    private PgClient pgClient;
    private PgTransactionManager transactionManager;

    @BeforeEach
    void setUp() throws SQLException {
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

        // Create transaction manager
        transactionManager = new PgTransactionManager(pgClient);

        // Create a test table
        try (Connection connection = pgClient.getConnection();
             Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS test_transactions (id SERIAL PRIMARY KEY, value VARCHAR(255))");
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        // Drop the test table
        try (Connection connection = pgClient.getConnection();
             Statement stmt = connection.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS test_transactions");
        }

        if (clientFactory != null) {
            clientFactory.close();
        }
    }

    @Test
    void testBeginTransaction() throws Exception {
        // Begin a transaction
        try (PgTransaction transaction = transactionManager.beginTransaction()) {
            // Verify transaction is active
            assertTrue(transaction.isActive());

            // Insert a record
            try (Statement stmt = transaction.getConnection().createStatement()) {
                stmt.execute("INSERT INTO test_transactions (value) VALUES ('test1')");
            }

            // Commit the transaction
            transaction.commit();
        }

        // Verify the record was inserted
        try (Connection connection = pgClient.getConnection();
             Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM test_transactions WHERE value = 'test1'")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    void testRollbackTransaction() throws Exception {
        // Begin a transaction
        try (PgTransaction transaction = transactionManager.beginTransaction()) {
            // Insert a record
            try (Statement stmt = transaction.getConnection().createStatement()) {
                stmt.execute("INSERT INTO test_transactions (value) VALUES ('test2')");
            }

            // Rollback the transaction
            transaction.rollback();
        }

        // Verify the record was not inserted
        try (Connection connection = pgClient.getConnection();
             Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM test_transactions WHERE value = 'test2'")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1));
        }
    }

    @Test
    void testSavepoints() throws Exception {
        // Begin a transaction
        try (PgTransaction transaction = transactionManager.beginTransaction()) {
            // Insert first record
            try (Statement stmt = transaction.getConnection().createStatement()) {
                stmt.execute("INSERT INTO test_transactions (value) VALUES ('test3')");
            }

            // Set a savepoint
            transaction.setSavepoint("sp1");

            // Insert second record
            try (Statement stmt = transaction.getConnection().createStatement()) {
                stmt.execute("INSERT INTO test_transactions (value) VALUES ('test4')");
            }

            // Rollback to savepoint
            transaction.rollbackToSavepoint("sp1");

            // Commit the transaction
            transaction.commit();
        }

        // Verify only the first record was inserted
        try (Connection connection = pgClient.getConnection();
             Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM test_transactions WHERE value = 'test3'")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }

        try (Connection connection = pgClient.getConnection();
             Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM test_transactions WHERE value = 'test4'")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1));
        }
    }

    @Test
    void testExecuteInTransaction() throws Exception {
        // Execute code in a transaction
        transactionManager.executeInTransaction(transaction -> {
            try (Statement stmt = transaction.getConnection().createStatement()) {
                stmt.execute("INSERT INTO test_transactions (value) VALUES ('test5')");
            }
        });

        // Verify the record was inserted
        try (Connection connection = pgClient.getConnection();
             Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM test_transactions WHERE value = 'test5'")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    void testExecuteInTransactionWithResult() throws Exception {
        // Execute code in a transaction and return a result
        Integer result = transactionManager.executeInTransactionWithResult(transaction -> {
            try (Statement stmt = transaction.getConnection().createStatement()) {
                stmt.execute("INSERT INTO test_transactions (value) VALUES ('test6')");
                try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM test_transactions WHERE value = 'test6'")) {
                    if (rs.next()) {
                        return rs.getInt(1);
                    }
                    return 0;
                }
            }
        });

        // Verify the result
        assertEquals(1, result);

        // Verify the record was inserted
        try (Connection connection = pgClient.getConnection();
             Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM test_transactions WHERE value = 'test6'")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
    }
}