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


import dev.mars.peegeeq.api.database.ConnectOptionsProvider;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgConnection;
import io.vertx.sqlclient.Pool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adapter that provides access to Vert.x Pool and dedicated connections.
 *
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 2.0 - Refactored to use interfaces instead of PgClientFactory
 */
public class VertxPoolAdapter {
    private static final Logger logger = LoggerFactory.getLogger(VertxPoolAdapter.class);

    private final Vertx vertx;
    private final Pool pool;
    private final ConnectOptionsProvider connectOptionsProvider;

    /**
     * Primary constructor using interfaces from DatabaseService.
     *
     * @param vertx The Vert.x instance
     * @param pool The connection pool
     * @param connectOptionsProvider Provider for connection options (for dedicated connections)
     */
    public VertxPoolAdapter(Vertx vertx, Pool pool, ConnectOptionsProvider connectOptionsProvider) {
        this.vertx = vertx;
        this.pool = pool;
        this.connectOptionsProvider = connectOptionsProvider;
        logger.debug("Initialized VertxPoolAdapter with Vertx, Pool, and ConnectOptionsProvider");
    }

    /**
     * Gets the current pool.
     *
     * @return The Pool instance
     */
    public Pool getPool() {
        return this.pool;
    }

    /**
     * Gets the pool or throws if not available.
     *
     * @return The Pool instance
     * @throws IllegalStateException if pool is not available
     */
    public Pool getPoolOrThrow() {
        if (this.pool == null) {
            throw new IllegalStateException("Pool is not available");
        }
        return this.pool;
    }

    /**
     * Closes the adapter (no-op as adapter does not own resources).
     */
    public void close() {
        // No-op: adapter does not own Vert.x or the pool
        logger.debug("Closed VertxPoolAdapter (no-op)");
    }

    /**
     * Async close (no-op as adapter does not own resources).
     *
     * @return A succeeded future
     */
    public Future<Void> closeAsync() {
        return Future.succeededFuture();
    }

    /**
     * Gets the Vert.x instance.
     *
     * @return The Vert.x instance
     */
    public Vertx getVertx() {
        return vertx;
    }

    /**
     * Gets the connection options for dedicated connections.
     *
     * @return The PgConnectOptions from the provider
     */
    public PgConnectOptions getConnectOptions() {
        if (connectOptionsProvider == null) {
            return null;
        }
        return connectOptionsProvider.getConnectOptions();
    }

    /**
     * Creates a dedicated, non-pooled PgConnection (useful for LISTEN/UNLISTEN).
     *
     * @return A Future containing the dedicated connection
     */
    public Future<PgConnection> connectDedicated() {
        if (connectOptionsProvider == null) {
            return Future.failedFuture(new IllegalStateException("No ConnectOptionsProvider available for dedicated connection"));
        }

        PgConnectOptions opts = connectOptionsProvider.getConnectOptions();
        if (opts == null) {
            return Future.failedFuture(new IllegalStateException("ConnectOptionsProvider returned null options"));
        }

        Vertx vt = this.vertx;
        if (vt == null) {
            // Try to get from current context as fallback
            vt = io.vertx.core.Vertx.currentContext() != null ? io.vertx.core.Vertx.currentContext().owner() : null;
        }
        if (vt == null) {
            return Future.failedFuture(new IllegalStateException("No Vert.x instance available for dedicated connection"));
        }

        return PgConnection.connect(vt, opts);
    }
}
