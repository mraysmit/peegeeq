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
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnection;
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
    private PgConnectOptions connectOptions;

    private String clientId = "native-queue";

    public VertxPoolAdapter() {
        this.vertx = null; // Do not create Vert.x implicitly
        this.clientFactory = null;
        this.clientId = "native-queue";
    }

    public VertxPoolAdapter(Vertx vertx) {
        this.vertx = vertx;
        this.clientFactory = null;
        this.clientId = "native-queue";
    }

    public VertxPoolAdapter(PgClientFactory clientFactory) {
        this.vertx = null; // Do not create Vert.x implicitly
        this.clientFactory = clientFactory;
        this.clientId = "native-queue";
    }

    public VertxPoolAdapter(Vertx vertx, PgClientFactory clientFactory) {
        this.vertx = vertx;
        this.clientFactory = clientFactory;
        this.clientId = "native-queue";
    }
    public VertxPoolAdapter(PgClientFactory clientFactory, String clientId) {
        this.vertx = null;
        this.clientFactory = clientFactory;
        this.clientId = clientId;
    }

    public VertxPoolAdapter(Vertx vertx, PgClientFactory clientFactory, String clientId) {
        this.vertx = vertx;
        this.clientFactory = clientFactory;
        this.clientId = clientId;
    }

    /**
     * Creates a Vert.x Pool from the configuration used by PgClientFactory.
     *
     * @param clientFactory The PgClientFactory to extract configuration from (can be null if using stored factory)
     * @param clientId The client ID to use for configuration lookup
     * @return A Vert.x Pool
     */
    public Pool createPool(PgClientFactory clientFactory, String clientId) {
        // Deprecated: do not create new pools here; reuse factory-managed pool
        PgClientFactory factory = clientFactory != null ? clientFactory : this.clientFactory;
        String cid = clientId != null ? clientId : this.clientId;
        if (factory == null || cid == null) {
            throw new IllegalStateException("PgClientFactory and clientId are required to obtain a pool");
        }
        PgConnectionConfig connectionConfig = factory.getConnectionConfig(cid);
        PgPoolConfig poolConfig = factory.getPoolConfig(cid);
        if (connectionConfig == null || poolConfig == null) {
            throw new IllegalStateException("Missing configuration for clientId=" + cid);
        }
        // Lazily prepare connect options for dedicated connections
        this.connectOptions = new PgConnectOptions()
            .setHost(connectionConfig.getHost())
            .setPort(connectionConfig.getPort())
            .setDatabase(connectionConfig.getDatabase())
            .setUser(connectionConfig.getUsername())
            .setPassword(connectionConfig.getPassword())
            .setSslMode(connectionConfig.isSslEnabled() ? io.vertx.pgclient.SslMode.REQUIRE : io.vertx.pgclient.SslMode.DISABLE);
        return factory.getPool(cid).orElseThrow(() -> new IllegalStateException("No pool available for clientId=" + cid));
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
        this.connectOptions = connectOptions;

        if (connectionConfig.isSslEnabled()) {
            connectOptions.setSslMode(io.vertx.pgclient.SslMode.REQUIRE);
        }

        PoolOptions poolOptions = new PoolOptions()
            .setMaxSize(poolConfig.getMaxSize());

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
        if (this.pool != null) return this.pool;
        if (clientFactory == null || clientId == null) return null;
        return clientFactory.getPool(clientId).orElse(null);
    }

    public Pool getPoolOrThrow() {
        if (this.pool != null) return this.pool;
        if (clientFactory == null || clientId == null) {
            throw new IllegalStateException("PgClientFactory and clientId are required to obtain a pool");
        }
        return clientFactory.getPool(clientId).orElseThrow(() -> new IllegalStateException("No pool available for clientId=" + clientId));
    }
    public void close() {
        // No-op: adapter does not own Vert.x or the pool; use closeAsync() if needed
        logger.info("Closed VertxPoolAdapter (no-op)");
    }


    public Future<Void> closeAsync() {
        // No resources owned here
        return Future.succeededFuture();
    }

    /**
     * Expose the Vert.x instance used by this adapter.
     */
    public Vertx getVertx() {
        return vertx;
    }

    /**
     * Expose the PgConnectOptions used to create the pool, for dedicated connections (LISTEN).
     */
    public PgConnectOptions getConnectOptions() {
        return connectOptions;
    }

    /**
     * Create a dedicated, non-pooled PgConnection (useful for LISTEN/UNLISTEN).
     */
    public Future<PgConnection> connectDedicated() {
        PgConnectOptions opts = this.connectOptions;
        if (opts == null) {
            if (clientFactory == null || clientId == null) {
                return Future.failedFuture(new IllegalStateException("No configuration available to build PgConnectOptions"));
            }
            PgConnectionConfig c = clientFactory.getConnectionConfig(clientId);
            if (c == null) {
                return Future.failedFuture(new IllegalStateException("Missing connection config for clientId=" + clientId));
            }
            opts = new PgConnectOptions()
                .setHost(c.getHost())
                .setPort(c.getPort())
                .setDatabase(c.getDatabase())
                .setUser(c.getUsername())
                .setPassword(c.getPassword())
                .setSslMode(c.isSslEnabled() ? io.vertx.pgclient.SslMode.REQUIRE : io.vertx.pgclient.SslMode.DISABLE);
            this.connectOptions = opts;
        }
        Vertx vt = this.vertx != null ? this.vertx : (io.vertx.core.Vertx.currentContext() != null ? io.vertx.core.Vertx.currentContext().owner() : null);
        if (vt == null) {
            return Future.failedFuture(new IllegalStateException("No Vert.x instance available for dedicated connection"));
        }
        return PgConnection.connect(vt, opts);
    }
}
