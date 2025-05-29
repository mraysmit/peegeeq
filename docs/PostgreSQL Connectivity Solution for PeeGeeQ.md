
# PostgreSQL Connectivity Solution for PeeGeeQ

Based on the project structure and requirements, I recommend creating a new module called `peegeeq-db` to handle PostgreSQL connectivity for both the `peegeeq-pg` and `peegeeq-outbox` modules. This approach will separate client connectivity from the service implementations and allow for proper scaling with many clients and few service instances.

## Proposed Solution

### 1. Create a New Module: `peegeeq-db`

Add a new module to the parent pom.xml:

```xml
<modules>
    <module>peegeeq-api</module>
    <module>peegeeq-outbox</module>
    <module>peegeeq-pg</module>
    <module>peegeeq-db</module>
</modules>
```

### 2. Module Structure

The `peegeeq-db` module should contain:

```
peegeeq-db/
├── src/
│   ├── main/
│   │   └── java/
│   │       └── dev/
│   │           └── mars/
│   │               └── peegeeq/
│   │                   └── db/
│   │                       ├── config/
│   │                       │   ├── PgConnectionConfig.java
│   │                       │   └── PgPoolConfig.java
│   │                       ├── client/
│   │                       │   ├── PgClient.java
│   │                       │   └── PgClientFactory.java
│   │                       ├── connection/
│   │                       │   ├── PgConnectionManager.java
│   │                       │   ├── PgConnectionPool.java
│   │                       │   └── PgListenerConnection.java
│   │                       └── transaction/
│   │                           ├── PgTransaction.java
│   │                           └── PgTransactionManager.java
│   └── test/
└── pom.xml
```

### 3. Key Classes

#### PgConnectionConfig

```java
package dev.mars.peegeeq.db.config;

/**
 * Configuration for PostgreSQL database connections.
 */
public class PgConnectionConfig {
    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;
    private final String schema;
    private final boolean sslEnabled;
    
    // Constructor, getters, and builder pattern implementation
    
    public static class Builder {
        // Builder implementation
    }
}
```

#### PgPoolConfig

```java
package dev.mars.peegeeq.db.config;

/**
 * Configuration for PostgreSQL connection pools.
 */
public class PgPoolConfig {
    private final int minimumIdle;
    private final int maximumPoolSize;
    private final long connectionTimeout;
    private final long idleTimeout;
    private final long maxLifetime;
    private final boolean autoCommit;
    
    // Constructor, getters, and builder pattern implementation
    
    public static class Builder {
        // Builder implementation
    }
}
```

#### PgConnectionManager

```java
package dev.mars.peegeeq.db.connection;

import com.zaxxer.hikari.HikariDataSource;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages PostgreSQL connections for different services.
 */
public class PgConnectionManager implements AutoCloseable {
    private final Map<String, HikariDataSource> dataSources = new ConcurrentHashMap<>();
    
    /**
     * Creates or retrieves a connection pool for a specific service.
     *
     * @param serviceId The unique identifier for the service
     * @param connectionConfig The PostgreSQL connection configuration
     * @param poolConfig The connection pool configuration
     * @return A HikariDataSource for the service
     */
    public HikariDataSource getOrCreateDataSource(String serviceId, 
                                                 PgConnectionConfig connectionConfig,
                                                 PgPoolConfig poolConfig) {
        return dataSources.computeIfAbsent(serviceId, id -> createDataSource(connectionConfig, poolConfig));
    }
    
    /**
     * Gets a connection from a specific service's connection pool.
     *
     * @param serviceId The unique identifier for the service
     * @return A database connection
     * @throws SQLException If a connection cannot be obtained
     */
    public Connection getConnection(String serviceId) throws SQLException {
        HikariDataSource dataSource = dataSources.get(serviceId);
        if (dataSource == null) {
            throw new IllegalStateException("No data source found for service: " + serviceId);
        }
        return dataSource.getConnection();
    }
    
    private HikariDataSource createDataSource(PgConnectionConfig connectionConfig, PgPoolConfig poolConfig) {
        // Implementation to create and configure HikariDataSource
    }
    
    @Override
    public void close() {
        // Close all data sources
    }
}
```

#### PgListenerConnection

```java
package dev.mars.peegeeq.db.connection;

import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Specialized connection for PostgreSQL LISTEN/NOTIFY functionality.
 */
public class PgListenerConnection implements AutoCloseable {
    private final Connection connection;
    private final PGConnection pgConnection;
    private final ScheduledExecutorService pollingExecutor;
    private final CopyOnWriteArrayList<Consumer<PGNotification>> notificationListeners = new CopyOnWriteArrayList<>();
    
    /**
     * Creates a new PgListenerConnection.
     *
     * @param connection The database connection
     * @throws SQLException If the connection cannot be unwrapped
     */
    public PgListenerConnection(Connection connection) throws SQLException {
        this.connection = connection;
        this.pgConnection = connection.unwrap(PGConnection.class);
        this.pollingExecutor = Executors.newSingleThreadScheduledExecutor();
        
        // Start polling for notifications
        pollingExecutor.scheduleAtFixedRate(this::pollNotifications, 0, 100, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Adds a listener for notifications.
     *
     * @param listener The notification listener
     */
    public void addNotificationListener(Consumer<PGNotification> listener) {
        notificationListeners.add(listener);
    }
    
    /**
     * Listens for notifications on a specific channel.
     *
     * @param channel The channel to listen on
     * @throws SQLException If the LISTEN command fails
     */
    public void listen(String channel) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("LISTEN " + channel);
        }
    }
    
    private void pollNotifications() {
        try {
            PGNotification[] notifications = pgConnection.getNotifications();
            if (notifications != null) {
                for (PGNotification notification : notifications) {
                    for (Consumer<PGNotification> listener : notificationListeners) {
                        listener.accept(notification);
                    }
                }
            }
        } catch (SQLException e) {
            // Log error
        }
    }
    
    @Override
    public void close() throws Exception {
        pollingExecutor.shutdown();
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }
}
```

#### PgClient

```java
package dev.mars.peegeeq.db.client;

import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.connection.PgListenerConnection;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Client for interacting with PostgreSQL databases.
 */
public class PgClient implements AutoCloseable {
    private final String clientId;
    private final PgConnectionManager connectionManager;
    
    /**
     * Creates a new PgClient.
     *
     * @param clientId The unique identifier for the client
     * @param connectionManager The connection manager to use
     */
    public PgClient(String clientId, PgConnectionManager connectionManager) {
        this.clientId = clientId;
        this.connectionManager = connectionManager;
    }
    
    /**
     * Gets a database connection.
     *
     * @return A database connection
     * @throws SQLException If a connection cannot be obtained
     */
    public Connection getConnection() throws SQLException {
        return connectionManager.getConnection(clientId);
    }
    
    /**
     * Creates a listener connection for LISTEN/NOTIFY functionality.
     *
     * @return A listener connection
     * @throws SQLException If a connection cannot be obtained
     */
    public PgListenerConnection createListenerConnection() throws SQLException {
        return new PgListenerConnection(getConnection());
    }
    
    @Override
    public void close() throws Exception {
        // No need to close connections as they are managed by the connection manager
    }
}
```

### 4. Module Dependencies

The `peegeeq-db` module's pom.xml:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    
    <parent>
        <groupId>dev.mars</groupId>
        <artifactId>peegeeq</artifactId>
        <version>1.0-SNAPSHOT</version>
    </parent>

    <artifactId>peegeeq-db</artifactId>
    <packaging>jar</packaging>
    
    <name>PeeGeeQ DB</name>
    <description>PostgreSQL connectivity for PeeGeeQ services</description>
    
    <dependencies>
        <!-- PostgreSQL Driver -->
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
        </dependency>
        
        <!-- Connection Pooling -->
        <dependency>
            <groupId>com.zaxxer</groupId>
            <artifactId>HikariCP</artifactId>
        </dependency>
        
        <!-- Concurrency Utilities -->
        <dependency>
            <groupId>io.projectreactor</groupId>
            <artifactId>reactor-core</artifactId>
        </dependency>
        
        <!-- Testing -->
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

### 5. Update Existing Modules

Update the `peegeeq-outbox` and `peegeeq-pg` modules to depend on the new `peegeeq-db` module:

```xml
<dependency>
    <groupId>dev.mars</groupId>
    <artifactId>peegeeq-db</artifactId>
</dependency>
```

## Usage Examples

### Using in peegeeq-outbox

```java
// Create connection configuration
PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
    .host("localhost")
    .port(5432)
    .database("peegeeq")
    .username("postgres")
    .password("password")
    .build();

// Create pool configuration
PgPoolConfig poolConfig = new PgPoolConfig.Builder()
    .minimumIdle(5)
    .maximumPoolSize(20)
    .build();

// Create connection manager
PgConnectionManager connectionManager = new PgConnectionManager();

// Create data source for outbox service
HikariDataSource dataSource = connectionManager.getOrCreateDataSource(
    "outbox-service", connectionConfig, poolConfig);

// Create OutboxQueue using the data source
OutboxQueue<String> queue = new OutboxQueue<>(
    dataSource, new ObjectMapper(), "outbox_messages", String.class);
```

### Using in peegeeq-pg

```java
// Create connection configuration
PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
    .host("localhost")
    .port(5432)
    .database("peegeeq")
    .username("postgres")
    .password("password")
    .build();

// Create pool configuration
PgPoolConfig poolConfig = new PgPoolConfig.Builder()
    .minimumIdle(5)
    .maximumPoolSize(20)
    .build();

// Create connection manager
PgConnectionManager connectionManager = new PgConnectionManager();

// Create data source for pg service
HikariDataSource dataSource = connectionManager.getOrCreateDataSource(
    "pg-service", connectionConfig, poolConfig);

// Create PgNativeQueue using the data source
PgNativeQueue<String> queue = new PgNativeQueue<>(
    dataSource, new ObjectMapper(), "pg_channel", String.class);
```

## Benefits of This Approach

1. **Separation of Concerns**: Database connectivity is separated from queue implementation
2. **Reusability**: Both modules can use the same connection management code
3. **Configurability**: Each service can have its own connection pool configuration
4. **Scalability**: Supports many clients with few service instances
5. **Maintainability**: Database-related code is centralized in one module

This solution provides a clean, modular approach to PostgreSQL connectivity that supports both the outbox pattern and native PostgreSQL queue implementations while keeping client connectivity separate from service implementations.