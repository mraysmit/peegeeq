
# Adding TestContainers to PeeGeeQ for Integration Testing

To properly test the PostgreSQL connectivity in our PeeGeeQ project, we need to add TestContainers to enable integration testing with a real PostgreSQL database. TestContainers is a Java library that provides lightweight, throwaway instances of common databases, Selenium web browsers, or anything else that can run in a Docker container.

## 1. Update the Parent POM

First, we need to add TestContainers dependencies to the parent `pom.xml` in the `dependencyManagement` section:

```xml
<!-- In parent pom.xml -->
<properties>
    <!-- Existing properties -->
    <testcontainers.version>1.18.3</testcontainers.version>
</properties>

<dependencyManagement>
    <dependencies>
        <!-- Existing dependencies -->
        
        <!-- TestContainers -->
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers</artifactId>
            <version>${testcontainers.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>${testcontainers.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>postgresql</artifactId>
            <version>${testcontainers.version}</version>
            <scope>test</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

## 2. Update the peegeeq-db Module POM

Next, we need to add the TestContainers dependencies to the `peegeeq-db` module:

```xml
<!-- In peegeeq-db/pom.xml -->
<dependencies>
    <!-- Existing dependencies -->
    
    <!-- Testing -->
    <dependency>
        <groupId>org.junit.jupiter</groupId>
        <artifactId>junit-jupiter</artifactId>
        <scope>test</scope>
    </dependency>
    
    <!-- TestContainers -->
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>testcontainers</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>junit-jupiter</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>postgresql</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

## 3. Create a Test Class for PgConnectionManager

Now, let's create a test class for the `PgConnectionManager` using TestContainers:

```java
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
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:14-alpine")
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
```

## 4. Create a Test Class for PgListenerConnection

Let's also create a test for the `PgListenerConnection` class:

```java
package dev.mars.peegeeq.db.connection;

import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.PGNotification;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
public class PgListenerConnectionTest {

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:14-alpine")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass");

    private PgConnectionManager connectionManager;
    private PgListenerConnection listenerConnection;
    private Connection notifierConnection;

    @BeforeEach
    void setUp() throws SQLException {
        connectionManager = new PgConnectionManager();
        
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
        
        // Create listener connection
        listenerConnection = new PgListenerConnection(connectionManager.getConnection("test-service"));
        
        // Create notifier connection
        notifierConnection = connectionManager.getConnection("test-service");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (listenerConnection != null) {
            listenerConnection.close();
        }
        if (notifierConnection != null && !notifierConnection.isClosed()) {
            notifierConnection.close();
        }
        if (connectionManager != null) {
            connectionManager.close();
        }
    }

    @Test
    void testListenAndNotify() throws Exception {
        // Set up a latch to wait for notification
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> receivedChannel = new AtomicReference<>();
        AtomicReference<String> receivedPayload = new AtomicReference<>();
        
        // Add notification listener
        listenerConnection.addNotificationListener(notification -> {
            receivedChannel.set(notification.getName());
            receivedPayload.set(notification.getParameter());
            latch.countDown();
        });
        
        // Start listening
        listenerConnection.start();
        listenerConnection.listen("test_channel");
        
        // Send notification
        try (Statement stmt = notifierConnection.createStatement()) {
            stmt.execute("NOTIFY test_channel, 'test_payload'");
        }
        
        // Wait for notification
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Notification not received within timeout");
        
        // Verify notification
        assertEquals("test_channel", receivedChannel.get());
        assertEquals("test_payload", receivedPayload.get());
    }
}
```

## 5. Setup Requirements

To run these tests, you need:

1. **Docker**: TestContainers requires Docker to be installed and running on your machine.
2. **Docker Compose**: Some TestContainers features may require Docker Compose.
3. **JDK 21**: The project is configured to use Java 21.
4. **Maven**: For building and running tests.

## 6. Running the Tests

You can run the tests using Maven:

```bash
mvn clean test
```

Or run specific tests:

```bash
mvn -Dtest=PgConnectionManagerTest test
```

## 7. Continuous Integration Considerations

For CI environments:

1. Ensure Docker is available in your CI environment
2. Consider using TestContainers' Ryuk container for cleanup
3. Set appropriate timeouts for container startup
4. Consider using TestContainers' reusable containers feature for faster tests

## Conclusion

By adding TestContainers to our project, we can perform proper integration testing of our PostgreSQL connectivity code against a real PostgreSQL database. This approach provides more reliable tests than using mocks or in-memory databases, as it tests against the actual database technology we'll be using in production.