package dev.mars.peegeeq.pgqueue;

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
 * Adapter that creates a Vert.x Pool from PgClientFactory configuration.
 *
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
public class VertxPoolAdapter {
    private static final Logger logger = LoggerFactory.getLogger(VertxPoolAdapter.class);

    private final Vertx vertx;
    private final PgClientFactory clientFactory;
    private Pool pool;

    public VertxPoolAdapter() {
        this.vertx = Vertx.vertx();
        this.clientFactory = null;
    }

    public VertxPoolAdapter(Vertx vertx) {
        this.vertx = vertx;
        this.clientFactory = null;
    }

    public VertxPoolAdapter(PgClientFactory clientFactory) {
        this.vertx = Vertx.vertx();
        this.clientFactory = clientFactory;
    }

    public VertxPoolAdapter(Vertx vertx, PgClientFactory clientFactory) {
        this.vertx = vertx;
        this.clientFactory = clientFactory;
    }
    
    /**
     * Creates a Vert.x Pool from the configuration used by PgClientFactory.
     *
     * @param clientFactory The PgClientFactory to extract configuration from (can be null if using stored factory)
     * @param clientId The client ID to use for configuration lookup
     * @return A Vert.x Pool
     */
    public Pool createPool(PgClientFactory clientFactory, String clientId) {
        if (pool != null) {
            return pool;
        }

        // Use the provided clientFactory or fall back to the stored one
        PgClientFactory factory = clientFactory != null ? clientFactory : this.clientFactory;

        if (factory != null) {
            // Extract configuration from the client factory
            PgConnectionConfig connectionConfig = factory.getConnectionConfig("peegeeq-main");
            PgPoolConfig poolConfig = factory.getPoolConfig("peegeeq-main");

            if (connectionConfig != null && poolConfig != null) {
                // Use the actual configuration
                return createPool(connectionConfig, poolConfig);
            } else {
                logger.warn("Configuration not found for client '{}', using default values", clientId);
            }
        } else {
            logger.warn("No PgClientFactory available for client '{}', using default values", clientId);
        }

        // Fallback to default values if configuration is not found
        PgConnectOptions connectOptions = new PgConnectOptions()
            .setHost("localhost")
            .setPort(5432)
            .setDatabase("peegeeq")
            .setUser("peegeeq")
            .setPassword("peegeeq");

        PoolOptions poolOptions = new PoolOptions()
            .setMaxSize(10);

        pool = PgBuilder.pool()
            .with(poolOptions)
            .connectingTo(connectOptions)
            .using(vertx)
            .build();
        logger.info("Created Vert.x Pool for client: {} (using defaults)", clientId);

        return pool;
    }
    
    /**
     * Creates a Vert.x Pool with explicit configuration.
     *
     * @param connectionConfig The PostgreSQL connection configuration
     * @param poolConfig The pool configuration
     * @return A Vert.x Pool
     */
    public Pool createPool(PgConnectionConfig connectionConfig, PgPoolConfig poolConfig) {
        if (pool != null) {
            return pool;
        }
        
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
            .setMaxSize(poolConfig.getMaximumPoolSize());

        pool = PgBuilder.pool()
            .with(poolOptions)
            .connectingTo(connectOptions)
            .using(vertx)
            .build();
        logger.info("Created Vert.x Pool with explicit configuration");
        
        return pool;
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
     * Closes the pool and Vert.x instance.
     */
    public void close() {
        if (pool != null) {
            pool.close();
            pool = null;
        }
        
        if (vertx != null) {
            vertx.close();
        }
        
        logger.info("Closed VertxPoolAdapter");
    }
}
