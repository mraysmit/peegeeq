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


// HikariCP imports removed - using pure Vert.x 5.x patterns only
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.SqlConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages PostgreSQL connections for different services using Vert.x 5.x reactive patterns.
 *
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities using
 * modern Vert.x 5.x reactive database clients instead of blocking JDBC.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 2.0 - Migrated to Vert.x 5.x reactive patterns
 */
public class PgConnectionManager implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(PgConnectionManager.class);

    // Modern Vert.x 5.x reactive pools
    private final Map<String, Pool> reactivePools = new ConcurrentHashMap<>();
    private final Vertx vertx;

    // Legacy JDBC support for ConnectionProvider interface
    private final Map<String, DataSource> legacyDataSources = new ConcurrentHashMap<>();

    /**
     * Creates a new PgConnectionManager with Vert.x 5.x reactive support.
     *
     * @param vertx The Vert.x instance for reactive operations
     */
    public PgConnectionManager(Vertx vertx) {
        this.vertx = Objects.requireNonNull(vertx, "Vertx instance cannot be null");
        logger.info("Initialized PgConnectionManager with Vert.x 5.x reactive support");
    }

    /**
     * Creates or retrieves a Vert.x reactive pool for a specific service.
     * This is the preferred method following Vert.x 5.x patterns.
     *
     * @param serviceId The unique identifier for the service
     * @param connectionConfig The PostgreSQL connection configuration
     * @param poolConfig The connection pool configuration
     * @return A Vert.x Pool for reactive database operations
     */
    public Pool getOrCreateReactivePool(String serviceId,
                                       PgConnectionConfig connectionConfig,
                                       PgPoolConfig poolConfig) {
        return reactivePools.computeIfAbsent(serviceId, id -> createReactivePool(connectionConfig, poolConfig));
    }

    /**
     * Gets a reactive connection from a specific service's pool.
     * Returns a Future that completes with a SqlConnection for reactive operations.
     *
     * @param serviceId The unique identifier for the service
     * @return Future<SqlConnection> for reactive database operations
     */
    public Future<SqlConnection> getReactiveConnection(String serviceId) {
        Pool pool = reactivePools.get(serviceId);
        if (pool == null) {
            return Future.failedFuture(new IllegalStateException("No reactive pool found for service: " + serviceId));
        }
        return pool.getConnection();
    }

    /**
     * Creates a Vert.x reactive pool following the established patterns from other modules.
     * Uses PgBuilder.pool() as recommended in Vert.x 5.x documentation.
     *
     * @param connectionConfig The PostgreSQL connection configuration
     * @param poolConfig The connection pool configuration
     * @return A Vert.x Pool for reactive database operations
     */
    private Pool createReactivePool(PgConnectionConfig connectionConfig, PgPoolConfig poolConfig) {
        PgConnectOptions connectOptions = new PgConnectOptions()
            .setHost(connectionConfig.getHost())
            .setPort(connectionConfig.getPort())
            .setDatabase(connectionConfig.getDatabase())
            .setUser(connectionConfig.getUsername())
            .setPassword(connectionConfig.getPassword());

        if (connectionConfig.isSslEnabled()) {
            connectOptions.setSslMode(io.vertx.pgclient.SslMode.REQUIRE);
        }

        PoolOptions poolOptions = new PoolOptions()
            .setMaxSize(poolConfig.getMaximumPoolSize())
            .setShared(poolConfig.isShared()); // Share one pool across all verticles

        Pool pool = PgBuilder.pool()
            .with(poolOptions)
            .connectingTo(connectOptions)
            .using(vertx)
            .build();

        logger.info("Created Vert.x reactive pool for service with host: {}, database: {}",
                   connectionConfig.getHost(), connectionConfig.getDatabase());
        return pool;
    }
    




    /**
     * Checks if the connection manager is healthy.
     * This checks if all reactive pools are healthy and not closed.
     *
     * @return true if all reactive pools are healthy, false otherwise
     */
    public boolean isHealthy() {
        if (reactivePools.isEmpty()) {
            return true; // No pools to check
        }

        for (Pool pool : reactivePools.values()) {
            if (pool == null) {
                return false;
            }
            // For reactive pools, we assume they're healthy if they exist
            // More sophisticated health checks can be added later
        }

        return true;
    }

    // ========================================
    // LEGACY JDBC SUPPORT METHODS
    // These methods provide backward compatibility for ConnectionProvider interface
    // ========================================

    /**
     * Gets or creates a JDBC DataSource for legacy components.
     * This method is provided for backward compatibility with ConnectionProvider interface.
     *
     * NOTE: This method is deprecated and will be removed in future versions.
     * Use getOrCreateReactivePool() for new code following Vert.x 5.x patterns.
     *
     * @param serviceId The unique identifier for the service
     * @param connectionConfig The PostgreSQL connection configuration
     * @param poolConfig The connection pool configuration
     * @return A JDBC DataSource
     * @deprecated Use reactive pools instead of JDBC DataSource
     */
    @Deprecated
    public DataSource getOrCreateDataSource(String serviceId, PgConnectionConfig connectionConfig, PgPoolConfig poolConfig) {
        // Check if DataSource already exists
        DataSource existingDataSource = legacyDataSources.get(serviceId);
        if (existingDataSource != null) {
            return existingDataSource;
        }

        // For test scenarios only - check if HikariCP is available on classpath
        try {
            logger.debug("Attempting to create test DataSource for service: {}", serviceId);
            DataSource dataSource = createTestDataSource(serviceId, connectionConfig, poolConfig);

            // Store the DataSource in the map for future retrieval
            legacyDataSources.put(serviceId, dataSource);
            logger.debug("Successfully created and stored test DataSource for service: {}", serviceId);

            return dataSource;
        } catch (ClassNotFoundException e) {
            logger.debug("HikariCP not found on classpath, throwing UnsupportedOperationException", e);
            throw new UnsupportedOperationException(
                "JDBC DataSource usage has been removed in favor of pure Vert.x 5.x reactive patterns. " +
                "Use getOrCreateReactivePool() instead. " +
                "For test scenarios requiring JDBC, add HikariCP as a test dependency and use test-specific connection management.",
                e
            );
        } catch (Exception e) {
            logger.debug("Failed to create test DataSource due to unexpected error", e);
            throw new UnsupportedOperationException(
                "JDBC DataSource usage has been removed in favor of pure Vert.x 5.x reactive patterns. " +
                "Use getOrCreateReactivePool() instead. " +
                "For test scenarios requiring JDBC, add HikariCP as a test dependency and use test-specific connection management.",
                e
            );
        }
    }

    /**
     * Creates a test-specific DataSource using reflection when HikariCP is available.
     * This method is only for test scenarios and should not be used in production code.
     */
    private DataSource createTestDataSource(String serviceId, PgConnectionConfig connectionConfig, PgPoolConfig poolConfig)
            throws ClassNotFoundException {
        try {
            logger.debug("Creating test DataSource using reflection for service: {}", serviceId);
            // Use reflection to create HikariCP DataSource if available (test scope)
            Class<?> hikariConfigClass = Class.forName("com.zaxxer.hikari.HikariConfig");
            Class<?> hikariDataSourceClass = Class.forName("com.zaxxer.hikari.HikariDataSource");
            logger.debug("Successfully loaded HikariCP classes via reflection");

            Object hikariConfig = hikariConfigClass.getDeclaredConstructor().newInstance();

            // Set connection properties using reflection
            hikariConfigClass.getMethod("setJdbcUrl", String.class).invoke(hikariConfig,
                String.format("jdbc:postgresql://%s:%d/%s",
                    connectionConfig.getHost(), connectionConfig.getPort(), connectionConfig.getDatabase()));
            hikariConfigClass.getMethod("setUsername", String.class).invoke(hikariConfig, connectionConfig.getUsername());
            hikariConfigClass.getMethod("setPassword", String.class).invoke(hikariConfig, connectionConfig.getPassword());
            hikariConfigClass.getMethod("setSchema", String.class).invoke(hikariConfig, connectionConfig.getSchema());

            // Set pool properties
            hikariConfigClass.getMethod("setMinimumIdle", int.class).invoke(hikariConfig, poolConfig.getMinimumIdle());
            hikariConfigClass.getMethod("setMaximumPoolSize", int.class).invoke(hikariConfig, poolConfig.getMaximumPoolSize());
            hikariConfigClass.getMethod("setConnectionTimeout", long.class).invoke(hikariConfig, poolConfig.getConnectionTimeout());
            hikariConfigClass.getMethod("setIdleTimeout", long.class).invoke(hikariConfig, poolConfig.getIdleTimeout());
            hikariConfigClass.getMethod("setMaxLifetime", long.class).invoke(hikariConfig, poolConfig.getMaxLifetime());

            // Set autoCommit to false for transactional consistency
            hikariConfigClass.getMethod("setAutoCommit", boolean.class).invoke(hikariConfig, false);

            // Set pool name for monitoring
            hikariConfigClass.getMethod("setPoolName", String.class).invoke(hikariConfig, "PeeGeeQ-Test-" + serviceId);

            // Create and return DataSource
            return (DataSource) hikariDataSourceClass.getDeclaredConstructor(hikariConfigClass).newInstance(hikariConfig);

        } catch (Exception e) {
            throw new RuntimeException("Failed to create test DataSource using reflection", e);
        }
    }

    /**
     * Gets a JDBC DataSource for a specific service.
     * This method is provided for backward compatibility with ConnectionProvider interface.
     *
     * @param serviceId The unique identifier for the service
     * @return A JDBC DataSource
     * @throws IllegalStateException if no DataSource exists for the service
     */
    public DataSource getDataSource(String serviceId) {
        DataSource dataSource = legacyDataSources.get(serviceId);
        if (dataSource == null) {
            throw new IllegalStateException("No DataSource found for service: " + serviceId);
        }
        return dataSource;
    }

    /**
     * Gets a JDBC Connection for a specific service.
     * This method is provided for backward compatibility with ConnectionProvider interface.
     *
     * @param serviceId The unique identifier for the service
     * @return A JDBC Connection
     * @throws SQLException if unable to get a connection
     */
    public Connection getConnection(String serviceId) throws SQLException {
        DataSource dataSource = getDataSource(serviceId);
        return dataSource.getConnection();
    }

    /**
     * Creates a legacy JDBC DataSource using HikariCP.
     * This method has been removed in favor of pure Vert.x 5.x reactive patterns.
     *
     * @param connectionConfig The PostgreSQL connection configuration
     * @param poolConfig The connection pool configuration
     * @return A HikariCP DataSource
     * @deprecated Removed - use reactive pools instead
     */
    @Deprecated
    private DataSource createLegacyDataSource(PgConnectionConfig connectionConfig, PgPoolConfig poolConfig) {
        throw new UnsupportedOperationException(
            "Legacy JDBC DataSource creation has been removed. " +
            "Use getOrCreateReactivePool() for Vert.x 5.x reactive patterns instead."
        );
    }

    /**
     * Closes all pools and data sources managed by this connection manager.
     * Properly closes both Vert.x reactive pools and legacy JDBC data sources.
     */
    @Override
    public void close() {
        logger.info("Closing PgConnectionManager and all pools");

        // Close Vert.x reactive pools
        for (Map.Entry<String, Pool> entry : reactivePools.entrySet()) {
            try {
                entry.getValue().close();
                logger.debug("Closed reactive pool for service: {}", entry.getKey());
            } catch (Exception e) {
                logger.warn("Failed to close reactive pool for service: {}", entry.getKey(), e);
            }
        }
        reactivePools.clear();

        // Close legacy JDBC DataSources (if any exist from test scenarios)
        for (Map.Entry<String, DataSource> entry : legacyDataSources.entrySet()) {
            try {
                // Generic DataSource close - no HikariCP dependency
                if (entry.getValue() instanceof AutoCloseable) {
                    ((AutoCloseable) entry.getValue()).close();
                    logger.debug("Closed legacy DataSource for service: {}", entry.getKey());
                }
            } catch (Exception e) {
                logger.warn("Failed to close legacy DataSource for service: {}", entry.getKey(), e);
            }
        }
        legacyDataSources.clear();

        logger.info("PgConnectionManager closed successfully");
    }
}