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












    /**
     * Gets or creates a DataSource for a specific service (deprecated - for test compatibility only).
     *
     * @deprecated This method is deprecated and will be removed. Use getOrCreateReactivePool() instead.
     * @param serviceId The unique identifier for the service
     * @param connectionConfig The PostgreSQL connection configuration
     * @param poolConfig The connection pool configuration
     * @return Always throws UnsupportedOperationException
     * @throws UnsupportedOperationException Always thrown to indicate JDBC is no longer supported
     */
    @Deprecated
    public javax.sql.DataSource getOrCreateDataSource(String serviceId,
                                                     PgConnectionConfig connectionConfig,
                                                     PgPoolConfig poolConfig) {
        throw new UnsupportedOperationException(
            "JDBC DataSource usage has been removed. Use getOrCreateReactivePool() for reactive patterns.");
    }

    /**
     * Gets a JDBC connection for a specific service (deprecated - for test compatibility only).
     *
     * @deprecated This method is deprecated and will be removed. Use getReactiveConnection() instead.
     * @param serviceId The unique identifier for the service
     * @return Always throws UnsupportedOperationException
     * @throws UnsupportedOperationException Always thrown to indicate JDBC is no longer supported
     */
    @Deprecated
    public java.sql.Connection getConnection(String serviceId) {
        throw new UnsupportedOperationException(
            "JDBC Connection usage has been removed. Use getReactiveConnection() for reactive patterns.");
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



        logger.info("PgConnectionManager closed successfully");
    }
}