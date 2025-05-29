package dev.mars.peegeeq.db.connection;

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