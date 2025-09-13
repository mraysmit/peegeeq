package dev.mars.peegeeq.db.provider;

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


import dev.mars.peegeeq.db.client.PgClientFactory;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.util.function.Function;

/**
 * PostgreSQL implementation of ConnectionProvider.
 * 
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
/**
 * PostgreSQL implementation of ConnectionProvider.
 * This class wraps the existing PgClientFactory and PgConnectionManager
 * to provide a clean interface for connection management.
 */
public class PgConnectionProvider implements dev.mars.peegeeq.api.database.ConnectionProvider {
    
    private static final Logger logger = LoggerFactory.getLogger(PgConnectionProvider.class);
    
    private final PgClientFactory clientFactory;
    private final PgConnectionManager connectionManager;
    
    public PgConnectionProvider(PgClientFactory clientFactory) {
        this.clientFactory = clientFactory;
        this.connectionManager = clientFactory.getConnectionManager();
        logger.info("Initialized PgConnectionProvider");
    }
    
    @Override
    public Future<Pool> getReactivePool(String clientId) {
        try {
            // Get the client configurations from the factory
            var connectionConfig = clientFactory.getConnectionConfig(clientId);
            var poolConfig = clientFactory.getPoolConfig(clientId);

            if (connectionConfig == null || poolConfig == null) {
                return Future.failedFuture(new IllegalArgumentException("Client not found: " + clientId));
            }

            // Get the reactive pool from the connection manager
            Pool pool = connectionManager.getOrCreateReactivePool(
                clientId,
                connectionConfig,
                poolConfig
            );

            logger.debug("Retrieved reactive pool for client: {}", clientId);
            return Future.succeededFuture(pool);
        } catch (Exception e) {
            logger.error("Failed to get reactive pool for client: {}", clientId, e);
            return Future.failedFuture(new IllegalArgumentException("Client not found: " + clientId, e));
        }
    }

    @Override
    public Future<SqlConnection> getConnection(String clientId) {
        logger.debug("Getting reactive connection for client: {}", clientId);
        return getReactivePool(clientId)
            .compose(pool -> pool.getConnection())
            .onSuccess(conn -> logger.debug("Successfully obtained reactive connection for client: {}", clientId))
            .onFailure(error -> logger.error("Failed to get reactive connection for client: {}: {}", clientId, error.getMessage()));
    }

    @Override
    public <T> Future<T> withConnection(String clientId, Function<SqlConnection, Future<T>> operation) {
        logger.debug("Executing operation with connection for client: {}", clientId);
        return getReactivePool(clientId)
            .compose(pool -> pool.withConnection(operation))
            .onSuccess(result -> logger.debug("Successfully executed operation with connection for client: {}", clientId))
            .onFailure(error -> logger.error("Failed to execute operation with connection for client: {}: {}", clientId, error.getMessage()));
    }

    @Override
    public <T> Future<T> withTransaction(String clientId, Function<SqlConnection, Future<T>> operation) {
        logger.debug("Executing operation with transaction for client: {}", clientId);
        return getReactivePool(clientId)
            .compose(pool -> pool.withTransaction(operation))
            .onSuccess(result -> logger.debug("Successfully executed operation with transaction for client: {}", clientId))
            .onFailure(error -> logger.error("Failed to execute operation with transaction for client: {}: {}", clientId, error.getMessage()));
    }

    @Override
    public boolean hasClient(String clientId) {
        try {
            var connectionConfig = clientFactory.getConnectionConfig(clientId);
            return connectionConfig != null;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public Future<Boolean> isHealthy() {
        try {
            boolean healthy = connectionManager.isHealthy();
            return Future.succeededFuture(healthy);
        } catch (Exception e) {
            logger.warn("Health check failed", e);
            return Future.succeededFuture(false);
        }
    }

    @Override
    public Future<Boolean> isClientHealthy(String clientId) {
        if (!hasClient(clientId)) {
            return Future.succeededFuture(false);
        }

        // Use reactive connection to test health
        return getConnection(clientId)
            .compose(connection -> {
                // Simple health check query
                return connection.query("SELECT 1").execute()
                    .map(rowSet -> true)
                    .onComplete(ar -> connection.close()); // Always close the connection
            })
            .recover(error -> {
                logger.warn("Health check failed for client: {}: {}", clientId, error.getMessage());
                return Future.succeededFuture(false);
            });
    }

    @Override
    @Deprecated
    public DataSource getDataSource(String clientId) {
        logger.warn("getDataSource() is deprecated. Use reactive patterns with getReactivePool() or getConnection() instead.");

        try {
            // Get the client configurations from the factory
            var connectionConfig = clientFactory.getConnectionConfig(clientId);
            var poolConfig = clientFactory.getPoolConfig(clientId);

            if (connectionConfig == null || poolConfig == null) {
                throw new IllegalArgumentException("Client not found: " + clientId);
            }

            // Create DataSource using the same pattern as PeeGeeQManager.createTemporaryDataSourceForMigration()
            return createDataSourceForClient(connectionConfig, poolConfig, clientId);

        } catch (Exception e) {
            logger.error("Failed to create DataSource for client: {}", clientId, e);
            throw new RuntimeException("Failed to create DataSource for client: " + clientId, e);
        }
    }

    /**
     * Creates a DataSource for a specific client using HikariCP.
     * This method follows the same pattern as PeeGeeQManager.createTemporaryDataSourceForMigration().
     *
     * @param connectionConfig The PostgreSQL connection configuration
     * @param poolConfig The connection pool configuration
     * @param clientId The client ID for naming the pool
     * @return A DataSource for the specified client
     * @throws RuntimeException if HikariCP is not available
     */
    private DataSource createDataSourceForClient(
            dev.mars.peegeeq.db.config.PgConnectionConfig connectionConfig,
            dev.mars.peegeeq.db.config.PgPoolConfig poolConfig,
            String clientId) {
        try {
            // Use reflection to create HikariCP DataSource if available
            Class<?> hikariConfigClass = Class.forName("com.zaxxer.hikari.HikariConfig");
            Class<?> hikariDataSourceClass = Class.forName("com.zaxxer.hikari.HikariDataSource");

            Object config = hikariConfigClass.getDeclaredConstructor().newInstance();

            // Set connection properties using reflection
            hikariConfigClass.getMethod("setJdbcUrl", String.class).invoke(config, connectionConfig.getJdbcUrl());
            hikariConfigClass.getMethod("setUsername", String.class).invoke(config, connectionConfig.getUsername());
            hikariConfigClass.getMethod("setPassword", String.class).invoke(config, connectionConfig.getPassword());

            // Set pool properties using reflection
            hikariConfigClass.getMethod("setMinimumIdle", int.class).invoke(config, poolConfig.getMinimumIdle());
            hikariConfigClass.getMethod("setMaximumPoolSize", int.class).invoke(config, poolConfig.getMaximumPoolSize());
            hikariConfigClass.getMethod("setConnectionTimeout", long.class).invoke(config, poolConfig.getConnectionTimeout());
            hikariConfigClass.getMethod("setIdleTimeout", long.class).invoke(config, poolConfig.getIdleTimeout());
            hikariConfigClass.getMethod("setMaxLifetime", long.class).invoke(config, poolConfig.getMaxLifetime());
            hikariConfigClass.getMethod("setAutoCommit", boolean.class).invoke(config, poolConfig.isAutoCommit());

            // Set pool name for monitoring
            hikariConfigClass.getMethod("setPoolName", String.class).invoke(config, "PeeGeeQ-Client-" + clientId + "-" + System.currentTimeMillis());

            // Create and return the DataSource
            Object dataSource = hikariDataSourceClass.getDeclaredConstructor(hikariConfigClass).newInstance(config);

            logger.info("Created HikariCP DataSource for client: {} with host: {}, database: {}, autoCommit: {}",
                       clientId, connectionConfig.getHost(), connectionConfig.getDatabase(), poolConfig.isAutoCommit());

            return (DataSource) dataSource;

        } catch (ClassNotFoundException e) {
            throw new RuntimeException(
                "HikariCP not found on classpath. For JDBC DataSource support, add HikariCP as a dependency:\n" +
                "<dependency>\n" +
                "    <groupId>com.zaxxer</groupId>\n" +
                "    <artifactId>HikariCP</artifactId>\n" +
                "    <scope>test</scope> <!-- or compile for production use -->\n" +
                "</dependency>\n" +
                "Alternatively, use reactive patterns with getReactivePool() or getConnection().", e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create DataSource for client: " + clientId + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void close() throws Exception {
        logger.info("Closing PgConnectionProvider");
        if (clientFactory != null) {
            clientFactory.close();
        }
    }
}
