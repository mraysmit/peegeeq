# Testing the peegeeq-db Module with TestContainers

After examining the peegeeq-db module, I can see that TestContainers is already being used effectively to test the connection-related classes (`PgConnectionManager` and `PgListenerConnection`). However, there are several other important classes in the module that would benefit from TestContainers-based integration testing.

## Current Testing Status

The module currently has two test classes using TestContainers:
1. `PgConnectionManagerTest` - Tests connection pooling, data source creation, and basic connectivity
2. `PgListenerConnectionTest` - Tests PostgreSQL LISTEN/NOTIFY functionality

## Untested Components

The following components currently lack tests:
1. Client classes (`PgClient` and `PgClientFactory`)
2. Transaction classes (`PgTransactionManager` and `PgTransaction`)

## Suggested Test Implementations

### 1. Testing PgClient and PgClientFactory

```java
package dev.mars.peegeeq.db.client;

import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
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
```

### 2. Testing PgTransactionManager

```java
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
```

## Additional Testing Considerations

### 1. Testing with Different PostgreSQL Versions

TestContainers allows testing against different PostgreSQL versions by specifying different Docker images:

```java
@Container
private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:14-alpine");
// or
private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:13-alpine");
```

This can help ensure compatibility with different PostgreSQL versions.

### 2. Testing Connection Pooling Under Load

For more advanced testing, you could create tests that simulate multiple concurrent connections:

```java
@Test
void testConnectionPoolingUnderLoad() throws Exception {
    int numThreads = 10;
    ExecutorService executor = Executors.newFixedThreadPool(numThreads);
    CountDownLatch latch = new CountDownLatch(numThreads);
    
    for (int i = 0; i < numThreads; i++) {
        executor.submit(() -> {
            try {
                pgClient.withConnection(connection -> {
                    // Simulate some work
                    Thread.sleep(100);
                    try (Statement stmt = connection.createStatement();
                         ResultSet rs = stmt.executeQuery("SELECT 1")) {
                        assertTrue(rs.next());
                        assertEquals(1, rs.getInt(1));
                    }
                });
            } catch (Exception e) {
                fail("Exception in thread: " + e.getMessage());
            } finally {
                latch.countDown();
            }
        });
    }
    
    assertTrue(latch.await(5, TimeUnit.SECONDS), "Not all threads completed in time");
    executor.shutdown();
}
```

### 3. Testing Error Handling and Edge Cases

It's important to test error handling and edge cases:

```java
@Test
void testTransactionErrorHandling() {
    // Test that SQL errors are properly propagated
    assertThrows(SQLException.class, () -> {
        transactionManager.executeInTransaction(transaction -> {
            try (Statement stmt = transaction.getConnection().createStatement()) {
                // Execute invalid SQL
                stmt.execute("SELECT * FROM non_existent_table");
            }
        });
    });
}
```

## Conclusion

By implementing these tests, you'll have comprehensive test coverage for the peegeeq-db module using TestContainers. This approach provides several benefits:

1. Tests run against a real PostgreSQL database, not mocks or in-memory databases
2. Tests are isolated and don't depend on external infrastructure
3. Tests can be run in CI/CD pipelines without special setup
4. Tests verify the actual behavior of the code against a real database

The existing tests for `PgConnectionManager` and `PgListenerConnection` provide a good foundation, and the suggested tests for `PgClient`, `PgClientFactory`, and `PgTransactionManager` would complete the test coverage for the module.