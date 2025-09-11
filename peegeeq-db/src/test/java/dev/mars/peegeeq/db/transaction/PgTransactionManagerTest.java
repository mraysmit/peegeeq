package dev.mars.peegeeq.db.transaction;

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


import dev.mars.peegeeq.db.client.PgClient;
import dev.mars.peegeeq.db.client.PgClientFactory;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import io.vertx.core.Vertx;
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
 * Implementation of PgTransactionManagerTest functionality.
 * 
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
@Testcontainers
public class PgTransactionManagerTest {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass");

    private PgClientFactory clientFactory;
    private PgClient pgClient;
    private PgTransactionManager transactionManager;

    @BeforeEach
    void setUp() throws SQLException {
        clientFactory = new PgClientFactory(Vertx.vertx());

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
                .maximumPoolSize(5)
                .build();

        // Create client and ensure DataSource is set up for JDBC operations
        pgClient = clientFactory.createClient("test-client", connectionConfig, poolConfig);

        // Create transaction manager
        transactionManager = new PgTransactionManager(pgClient);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (clientFactory != null) {
            clientFactory.close();
        }
    }

    /**
     * Helper method to create the test table for each test.
     * This ensures the table exists in the same connection context as the test.
     */
    private void createTestTable() throws SQLException {
        try (Connection connection = pgClient.getConnection();
             Statement stmt = connection.createStatement()) {
            // Ensure autocommit is enabled for DDL operations
            connection.setAutoCommit(true);
            stmt.execute("CREATE TABLE IF NOT EXISTS test_transactions (id SERIAL PRIMARY KEY, value VARCHAR(255))");
        }
    }

    /**
     * Helper method to drop the test table after each test.
     */
    private void dropTestTable() throws SQLException {
        try (Connection connection = pgClient.getConnection();
             Statement stmt = connection.createStatement()) {
            connection.setAutoCommit(true);
            stmt.execute("DROP TABLE IF EXISTS test_transactions");
        }
    }

    @Test
    void testBeginTransaction() throws Exception {
        // Create test table for this test
        createTestTable();

        try {
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
        } finally {
            // Clean up test table
            dropTestTable();
        }
    }

    @Test
    void testRollbackTransaction() throws Exception {
        // Create test table for this test
        createTestTable();

        try {
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
        } finally {
            // Clean up test table
            dropTestTable();
        }
    }

    @Test
    void testSavepoints() throws Exception {
        // Create test table for this test
        createTestTable();

        try {
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
        } finally {
            // Clean up test table
            dropTestTable();
        }
    }

    @Test
    void testExecuteInTransaction() throws Exception {
        // Create test table for this test
        createTestTable();

        try {
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
        } finally {
            // Clean up test table
            dropTestTable();
        }
    }

    @Test
    void testExecuteInTransactionWithResult() throws Exception {
        // Create test table for this test
        createTestTable();

        try {
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
        } finally {
            // Clean up test table
            dropTestTable();
        }
    }
}