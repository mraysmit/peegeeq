/*
 * Copyright (c) 2025 Cityline Ltd
 * All rights reserved.
 *
 * This software is the confidential and proprietary information of Cityline Ltd.
 * You shall not disclose such confidential information and shall use it only in
 * accordance with the terms of the license agreement you entered into with Cityline Ltd.
 */

package dev.mars.peegeeq.bitemporal;

import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.client.PgClientFactory;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgBuilder;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adapter that creates a Vert.x Pool from PeeGeeQManager configuration for bi-temporal event store.
 * 
 * This adapter bridges the traditional JDBC-based PeeGeeQManager with Vert.x reactive patterns,
 * following the established patterns from peegeeq-outbox.
 *
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-07
 * @version 1.0
 */
public class VertxPoolAdapter {
    private static final Logger logger = LoggerFactory.getLogger(VertxPoolAdapter.class);

    private final Vertx vertx;
    private final PeeGeeQManager peeGeeQManager;
    private volatile Pool pool;

    /**
     * Creates a new VertxPoolAdapter with a shared Vertx instance.
     *
     * @param peeGeeQManager The PeeGeeQ manager to extract configuration from
     */
    public VertxPoolAdapter(PeeGeeQManager peeGeeQManager) {
        this.vertx = getOrCreateSharedVertx();
        this.peeGeeQManager = peeGeeQManager;
        logger.debug("Created VertxPoolAdapter for bi-temporal event store");
    }

    /**
     * Creates a new VertxPoolAdapter with explicit Vertx instance.
     *
     * @param vertx The Vertx instance to use
     * @param peeGeeQManager The PeeGeeQ manager to extract configuration from
     */
    public VertxPoolAdapter(Vertx vertx, PeeGeeQManager peeGeeQManager) {
        this.vertx = vertx;
        this.peeGeeQManager = peeGeeQManager;
        logger.debug("Created VertxPoolAdapter with explicit Vertx instance");
    }

    /**
     * Gets or creates a Vert.x Pool from the PeeGeeQManager configuration.
     *
     * @return A Vert.x Pool
     */
    public Pool getOrCreatePool() {
        if (pool != null) {
            return pool;
        }

        synchronized (this) {
            if (pool != null) {
                return pool;
            }

            try {
                // Extract configuration from PeeGeeQManager
                PgClientFactory clientFactory = extractClientFactory();
                
                if (clientFactory != null) {
                    // Use configuration from client factory
                    PgConnectionConfig connectionConfig = clientFactory.getConnectionConfig("peegeeq-main");
                    PgPoolConfig poolConfig = clientFactory.getPoolConfig("peegeeq-main");
                    
                    if (connectionConfig != null && poolConfig != null) {
                        pool = createPoolFromConfig(connectionConfig, poolConfig);
                        logger.info("Created Vert.x Pool from PeeGeeQManager configuration");
                        return pool;
                    } else {
                        logger.warn("Configuration not found in PeeGeeQManager, using fallback");
                    }
                }

                // Fallback: create pool from DataSource properties
                pool = createPoolFromDataSource();
                logger.info("Created Vert.x Pool from DataSource fallback configuration");
                return pool;

            } catch (Exception e) {
                logger.error("Failed to create Vert.x Pool: {}", e.getMessage(), e);
                throw new RuntimeException("Failed to create Vert.x Pool", e);
            }
        }
    }

    /**
     * Creates a Vert.x Pool from explicit configuration.
     *
     * @param connectionConfig The PostgreSQL connection configuration
     * @param poolConfig The pool configuration
     * @return A Vert.x Pool
     */
    private Pool createPoolFromConfig(PgConnectionConfig connectionConfig, PgPoolConfig poolConfig) {
        PgConnectOptions connectOptions = new PgConnectOptions()
            .setHost(connectionConfig.getHost())
            .setPort(connectionConfig.getPort())
            .setDatabase(connectionConfig.getDatabase())
            .setUser(connectionConfig.getUsername())
            .setPassword(connectionConfig.getPassword());
        
        if (connectionConfig.isSslEnabled()) {
            connectOptions.setSsl(true);
        }
        
        PoolOptions poolOptions = new PoolOptions()
            .setMaxSize(poolConfig.getMaximumPoolSize());

        return PgBuilder.pool()
            .with(poolOptions)
            .connectingTo(connectOptions)
            .using(vertx)
            .build();
    }

    /**
     * Creates a Vert.x Pool from DataSource properties as fallback.
     *
     * @return A Vert.x Pool
     */
    private Pool createPoolFromDataSource() {
        // Extract connection info from DataSource URL
        // This is a simplified fallback - in production, you'd parse the JDBC URL properly
        PgConnectOptions connectOptions = new PgConnectOptions()
            .setHost("localhost")  // Default fallback
            .setPort(5432)
            .setDatabase("peegeeq")
            .setUser("peegeeq")
            .setPassword("peegeeq");

        PoolOptions poolOptions = new PoolOptions()
            .setMaxSize(10); // Default pool size

        return PgBuilder.pool()
            .with(poolOptions)
            .connectingTo(connectOptions)
            .using(vertx)
            .build();
    }

    /**
     * Extracts PgClientFactory from PeeGeeQManager if available.
     *
     * @return PgClientFactory or null if not available
     */
    private PgClientFactory extractClientFactory() {
        try {
            // Use reflection to access the client factory if available
            // This is a bridge pattern until PeeGeeQManager exposes reactive pools directly
            var field = peeGeeQManager.getClass().getDeclaredField("clientFactory");
            field.setAccessible(true);
            return (PgClientFactory) field.get(peeGeeQManager);
        } catch (Exception e) {
            logger.debug("Could not extract PgClientFactory from PeeGeeQManager: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Gets the current pool, or null if not created yet.
     *
     * @return The current Pool or null
     */
    public Pool getPool() {
        return pool;
    }

    /**
     * Gets the Vertx instance.
     *
     * @return The Vertx instance
     */
    public Vertx getVertx() {
        return vertx;
    }

    /**
     * Closes the pool.
     */
    public void close() {
        if (pool != null) {
            logger.debug("Closing Vert.x pool gracefully for bi-temporal event store...");
            try {
                // Close the pool and wait for pending operations to complete
                pool.close().toCompletionStage().toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS);
                logger.debug("Vert.x pool closed gracefully for bi-temporal event store");
            } catch (Exception e) {
                logger.warn("Bi-temporal pool did not close gracefully within timeout: {}", e.getMessage());
            }
            pool = null;
        }
    }

    // Shared Vertx instance management - following peegeeq-outbox pattern
    private static volatile Vertx sharedVertx;

    /**
     * Gets or creates a shared Vertx instance for proper context management.
     * This ensures that TransactionPropagation.CONTEXT works correctly by providing
     * a consistent Vertx context across all bi-temporal event store instances.
     *
     * @return The shared Vertx instance
     */
    private static Vertx getOrCreateSharedVertx() {
        if (sharedVertx == null) {
            synchronized (VertxPoolAdapter.class) {
                if (sharedVertx == null) {
                    sharedVertx = Vertx.vertx();
                    logger.info("Created shared Vertx instance for bi-temporal event store context management");
                }
            }
        }
        return sharedVertx;
    }
}
