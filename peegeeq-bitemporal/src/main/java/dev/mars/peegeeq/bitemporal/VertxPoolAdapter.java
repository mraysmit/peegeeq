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
 * This adapter creates Vert.x reactive pools from PeeGeeQManager configuration,
 * following the established pure Vert.x 5.x patterns from peegeeq-native.
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
        this.vertx = PgBiTemporalEventStore.getOrCreateSharedVertx();
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
                    // Use null to request the default pool configuration
                    PgConnectionConfig connectionConfig = clientFactory.getConnectionConfig(null);
                    PgPoolConfig poolConfig = clientFactory.getPoolConfig(null);

                    if (connectionConfig != null && poolConfig != null) {
                        pool = createPoolFromConfig(connectionConfig, poolConfig);
                        logger.info("Created Vert.x Pool from PeeGeeQManager configuration");
                        return pool;
                    } else {
                        logger.warn("Configuration not found in PeeGeeQManager, cannot create pool");
                    }
                } else {
                    logger.warn("PgClientFactory not found in PeeGeeQManager, cannot create pool");
                }

                // No fallback — explicit configuration is required
                throw new IllegalStateException(
                        "Cannot create Vert.x Pool: PeeGeeQManager configuration is missing or incomplete. "
                        + "Provide a valid PgClientFactory with connection and pool configuration.");

            } catch (IllegalStateException e) {
                throw e;
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
            connectOptions.setSslMode(io.vertx.pgclient.SslMode.REQUIRE);
        }
        
        PoolOptions poolOptions = new PoolOptions()
            .setMaxSize(poolConfig.getMaxSize());

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
            return peeGeeQManager.getClientFactory();
        } catch (Exception e) {
            logger.debug("Could not get PgClientFactory from PeeGeeQManager: {}", e.getMessage());
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
        Pool poolToClose = pool;
        pool = null;
        if (poolToClose != null) {
            logger.debug("Closing Vert.x pool gracefully for bi-temporal event store...");
            poolToClose.close()
                    .onSuccess(v -> logger.debug("Vert.x pool closed gracefully for bi-temporal event store"))
                    .onFailure(error -> logger.warn("Bi-temporal pool close failed: {}", error.getMessage()));
        }
    }

    /**
     * Closes the shared Vertx instance. Delegates to PgBiTemporalEventStore.
     * This should only be called during application shutdown.
     */
    public static void closeSharedVertx() {
        PgBiTemporalEventStore.closeSharedVertx();
    }
}
