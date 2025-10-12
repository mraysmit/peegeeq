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

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;



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

    private final MeterRegistry meter;

    private final Map<String, Pool> reactivePools = new ConcurrentHashMap<>();
    private final Vertx vertx;




    /**
     * Creates a new PgConnectionManager with Vert.x 5.x reactive support.
     *
     * @param vertx The Vert.x instance for reactive operations
     */
    public PgConnectionManager(Vertx vertx) {
        this(vertx, null);
    }

    public PgConnectionManager(Vertx vertx, MeterRegistry meter) {
        this.vertx = Objects.requireNonNull(vertx, "Vertx instance cannot be null");
        this.meter = meter;
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
        Objects.requireNonNull(connectionConfig, "connectionConfig");
        Objects.requireNonNull(poolConfig, "poolConfig");

        return reactivePools.computeIfAbsent(serviceId, id -> {
            try {
                Pool pool = createReactivePool(connectionConfig, poolConfig);
                logger.info("Created reactive pool for service '{}'", id);
                if (meter != null) {
                    Counter.builder("peegeeq.db.pool.created")
                        .tag("service", id)
                        .register(meter)
                        .increment();
                }
                return pool;
            } catch (Exception e) {
                logger.error("Failed to create pool for {}: {}", id, e.getMessage());
                reactivePools.remove(id); // Clean up failed entry
                if (meter != null) {
                    Counter.builder("peegeeq.db.pool.create.failed")
                        .tag("service", id)
                        .register(meter)
                        .increment();
                }
                throw e;
            }
        });
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
     * Gets an existing reactive pool without creating it.
     * Returns null if no pool exists for the given service ID.
     *
     * @param serviceId The unique identifier for the service
     * @return The existing Pool, or null if not found
     */
    public Pool getExistingPool(String serviceId) {
        return reactivePools.get(serviceId);
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
        // Validate connection config early
        Objects.requireNonNull(connectionConfig.getHost(), "host");
        Objects.requireNonNull(connectionConfig.getDatabase(), "database");
        Objects.requireNonNull(connectionConfig.getUsername(), "username");
        Objects.requireNonNull(connectionConfig.getPassword(), "password");

        PgConnectOptions connectOptions = new PgConnectOptions()
            .setHost(connectionConfig.getHost())
            .setPort(connectionConfig.getPort())
            .setDatabase(connectionConfig.getDatabase())
            .setUser(connectionConfig.getUsername())
            .setPassword(connectionConfig.getPassword());

        if (connectionConfig.isSslEnabled()) {
            connectOptions.setSslMode(io.vertx.pgclient.SslMode.REQUIRE);
        } else {
            connectOptions.setSslMode(io.vertx.pgclient.SslMode.DISABLE);
        }

        PoolOptions poolOptions = new PoolOptions()
            .setMaxSize(poolConfig.getMaxSize())
            .setMaxWaitQueueSize(poolConfig.getMaxWaitQueueSize())
            .setConnectionTimeout((int) poolConfig.getConnectionTimeout().toSeconds())
            .setConnectionTimeoutUnit(java.util.concurrent.TimeUnit.SECONDS)
            .setIdleTimeout((int) poolConfig.getIdleTimeout().toSeconds())
            .setIdleTimeoutUnit(java.util.concurrent.TimeUnit.SECONDS)
            .setShared(poolConfig.isShared());

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
     * Checks if a specific pool is healthy by attempting a database connection.
     * This performs an actual database health check using SELECT 1.
     *
     * @param serviceId The unique identifier for the service
     * @return Future<Boolean> that completes with true if healthy, false otherwise
     */
    public Future<Boolean> checkHealth(String serviceId) {
        Pool pool = reactivePools.get(serviceId);
        if (pool == null) {
            return Future.succeededFuture(false);
        }

        return pool.withConnection(conn ->
            conn.query("SELECT 1").execute().map(rs -> true)
        ).recover(err -> {
            logger.warn("Health check failed for {}: {}", serviceId, err.getMessage());
            return Future.succeededFuture(false);
        });
    }

    /**
     * Checks if the connection manager is healthy.
     * This is a simplified check that only verifies pools exist.
     * For real health checks, use checkHealth(serviceId) for specific pools.
     *
     * @return true if all reactive pools exist, false otherwise
     */
    public boolean isHealthy() {
        if (reactivePools.isEmpty()) {
            return true; // No pools to check
        }

        for (Pool pool : reactivePools.values()) {
            if (pool == null) {
                return false;
            }
            // Note: This only checks pool existence, not actual database connectivity
            // Use checkHealth(serviceId) for real database health checks
        }

        return true;
    }



    // ========================================














    /**
     * Closes a specific pool asynchronously.
     * Returns a Future that completes when the pool is closed.
     *
     * @param serviceId The unique identifier for the service
     * @return Future<Void> that completes when the pool is closed
     */
    public Future<Void> closePoolAsync(String serviceId) {
        Pool pool = reactivePools.remove(serviceId);
        if (pool == null) {
            logger.debug("No pool found for service: {}", serviceId);
            return Future.succeededFuture();
        }

        logger.debug("Closing reactive pool for service: {}", serviceId);
        return pool.close()
            .onSuccess(v -> {
                logger.debug("Closed reactive pool for service: {}", serviceId);
                if (meter != null) {
                    Counter.builder("peegeeq.db.pool.closed")
                        .tag("service", serviceId)
                        .register(meter)
                        .increment();
                }
            })
            .onFailure(err -> {
                logger.warn("Failed to close reactive pool for service: {}", serviceId, err);
                if (meter != null) {
                    Counter.builder("peegeeq.db.pool.close.failed")
                        .tag("service", serviceId)
                        .register(meter)
                        .increment();
                }
            });
    }

    /**
     * Closes all pools asynchronously.
     * Returns a Future that completes when all pools are closed.
     *
     * @return Future<Void> that completes when all pools are closed
     */
    public Future<Void> closeAsync() {
        logger.info("Closing PgConnectionManager and all pools asynchronously");

        if (reactivePools.isEmpty()) {
            logger.info("No pools to close");
            return Future.succeededFuture();
        }

        // Create a list of futures for closing all pools
        java.util.List<Future<Void>> closeFutures = new java.util.ArrayList<>();

        for (String serviceId : reactivePools.keySet()) {
            closeFutures.add(closePoolAsync(serviceId));
        }

        return Future.all(closeFutures)
            .compose(v -> {
                logger.info("PgConnectionManager closed successfully");
                return Future.<Void>succeededFuture();
            })
            .recover(throwable -> {
                logger.warn("Some pools failed to close cleanly: {}", throwable.getMessage());
                return Future.<Void>succeededFuture(); // Don't fail the overall close operation
            });
    }

    /**
     * Closes all pools and data sources managed by this connection manager.
     * This is a synchronous wrapper for AutoCloseable compatibility.
     * Prefer using closeAsync() for non-blocking shutdown.
     */
    @Override
    public void close() {
        logger.debug("Closing PgConnectionManager and all pools");
        try {
            closeAsync().toCompletionStage().toCompletableFuture().get();
        } catch (Exception e) {
            logger.error("Error during synchronous close", e);
        }
    }
}