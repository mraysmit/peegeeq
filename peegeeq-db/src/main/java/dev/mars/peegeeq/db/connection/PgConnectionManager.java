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


import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages PostgreSQL connections for different services.
 * 
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
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

    /**
     * Gets the data source for a specific service.
     *
     * @param serviceId The unique identifier for the service
     * @return The HikariDataSource for the service
     * @throws IllegalStateException If no data source is found for the service
     */
    public HikariDataSource getDataSource(String serviceId) {
        HikariDataSource dataSource = dataSources.get(serviceId);
        if (dataSource == null) {
            throw new IllegalStateException("No data source found for service: " + serviceId);
        }
        return dataSource;
    }

    /**
     * Checks if the connection manager is healthy.
     * This checks if all data sources are healthy and not closed.
     *
     * @return true if all data sources are healthy, false otherwise
     */
    public boolean isHealthy() {
        if (dataSources.isEmpty()) {
            return true; // No data sources to check
        }

        for (HikariDataSource dataSource : dataSources.values()) {
            if (dataSource == null || dataSource.isClosed()) {
                return false;
            }

            try (Connection connection = dataSource.getConnection()) {
                if (!connection.isValid(5)) { // 5 second timeout
                    return false;
                }
            } catch (SQLException e) {
                return false;
            }
        }

        return true;
    }
    
    private HikariDataSource createDataSource(PgConnectionConfig connectionConfig, PgPoolConfig poolConfig) {
        HikariConfig config = new HikariConfig();
        
        // Set connection properties
        config.setJdbcUrl(connectionConfig.getJdbcUrl());
        config.setUsername(connectionConfig.getUsername());
        config.setPassword(connectionConfig.getPassword());
        
        // Set pool properties
        config.setMinimumIdle(poolConfig.getMinimumIdle());
        config.setMaximumPoolSize(poolConfig.getMaximumPoolSize());
        config.setConnectionTimeout(poolConfig.getConnectionTimeout());
        config.setIdleTimeout(poolConfig.getIdleTimeout());
        config.setMaxLifetime(poolConfig.getMaxLifetime());
        config.setAutoCommit(poolConfig.isAutoCommit());
        
        // Set additional properties
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        
        return new HikariDataSource(config);
    }
    
    /**
     * Closes all data sources managed by this connection manager.
     */
    @Override
    public void close() {
        for (HikariDataSource dataSource : dataSources.values()) {
            if (dataSource != null && !dataSource.isClosed()) {
                dataSource.close();
            }
        }
        dataSources.clear();
    }
}