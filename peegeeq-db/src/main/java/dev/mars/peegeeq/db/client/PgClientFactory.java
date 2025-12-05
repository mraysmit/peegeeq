package dev.mars.peegeeq.db.client;

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


import dev.mars.peegeeq.db.PeeGeeQDefaults;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;


import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Factory for creating PgClient instances.
 *
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
public class PgClientFactory implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(PgClientFactory.class);

    private final PgConnectionManager connectionManager;

    private final MeterRegistry meter;

    private final Map<String, PgConnectionConfig> connectionConfigs = new ConcurrentHashMap<>();
    private final Map<String, PgPoolConfig> poolConfigs = new ConcurrentHashMap<>();
    private final Map<String, PgClient> clients = new ConcurrentHashMap<>();

    /**
     * Creates a new PgClientFactory with a Vert.x instance for reactive operations.
     * This is the preferred constructor for Vert.x 5.x applications.
     *
     * @param vertx The Vert.x instance for reactive database operations
     */
    public PgClientFactory(Vertx vertx) {
        this(new PgConnectionManager(Objects.requireNonNull(vertx, "vertx")));
    }

    /**
     * Creates a new PgClientFactory with Vert.x and a MeterRegistry for metrics.
     */
    public PgClientFactory(Vertx vertx, MeterRegistry meter) {
        this(new PgConnectionManager(Objects.requireNonNull(vertx, "vertx"), meter), meter);
    }

    /**
     * Creates a new PgClientFactory with the given connection manager.
     *
     * @param connectionManager The connection manager to use
     */
    public PgClientFactory(PgConnectionManager connectionManager) {
        this(connectionManager, null);
    }

    public PgClientFactory(PgConnectionManager connectionManager, MeterRegistry meter) {
        this.connectionManager = Objects.requireNonNull(connectionManager, "connectionManager");
        this.meter = meter;
    }

    /**
     * Creates a new PgClient with the given client ID and configurations.
     * Idempotent: get existing client or create it (and its pool) atomically.
     *
     * @param clientId The unique identifier for the client
     * @param connectionConfig The PostgreSQL connection configuration
     * @param poolConfig The connection pool configuration
     * @return A PgClient (existing or newly created)
     */
    public PgClient createClient(String clientId, PgConnectionConfig connectionConfig, PgPoolConfig poolConfig) {
        validate(clientId, connectionConfig, poolConfig);

        // Guard against inconsistent re-creation with different configs
        PgConnectionConfig existingConn = connectionConfigs.get(clientId);
        if (existingConn != null && !existingConn.equals(connectionConfig)) {
            throw new IllegalStateException("Attempted to recreate client with different connection config: " + clientId);
        }
        PgPoolConfig existingPool = poolConfigs.get(clientId);
        if (existingPool != null && !existingPool.equals(poolConfig)) {
            throw new IllegalStateException("Attempted to recreate client with different pool config: " + clientId);
        }

        // Keep configs for observability/rehydration; treat them as immutable
        connectionConfigs.putIfAbsent(clientId, connectionConfig);
        poolConfigs.putIfAbsent(clientId, poolConfig);

        // Build or return cached client atomically
        return clients.computeIfAbsent(clientId, id -> {
            try {
                // Ensure the reactive pool exists
                connectionManager.getOrCreateReactivePool(id, connectionConfig, poolConfig);
                logger.info("Initialized reactive PG pool for client '{}'", id);
                if (meter != null) {
                    Counter.builder("peegeeq.db.client.created")
                        .tag("client", id)
                        .register(meter)
                        .increment();
                }
                return new PgClient(id, connectionManager);
            } catch (Exception e) {
                logger.warn("Failed to create reactive pool for client '{}': {}", id, e.getMessage());
                if (meter != null) {
                    Counter.builder("peegeeq.db.client.create.failed")
                        .tag("client", id)
                        .register(meter)
                        .increment();
                }
                // Clean partial state
                connectionConfigs.remove(id);
                poolConfigs.remove(id);
                throw new RuntimeException("Failed to create reactive pool for client: " + id, e);
            }
        });
    }

    /**
     * Creates a new PgClient with the given client ID and default pool configuration.
     *
     * @param clientId The unique identifier for the client
     * @param connectionConfig The PostgreSQL connection configuration
     * @return A PgClient (existing or newly created)
     */
    public PgClient createClient(String clientId, PgConnectionConfig connectionConfig) {
        return createClient(clientId, connectionConfig, new PgPoolConfig.Builder().build());
    }

    /**
     * Creates a PgClient using previously registered configurations.
     * Useful for rehydration from configuration files.
     *
     * @param clientId The unique identifier for the client
     * @return A PgClient (existing or newly created)
     */
    public PgClient createClient(String clientId) {
        PgConnectionConfig conn = connectionConfigs.get(clientId);
        PgPoolConfig pool = poolConfigs.get(clientId);
        if (conn == null || pool == null) {
            throw new IllegalStateException("No configuration for client: " + clientId);
        }
        return createClient(clientId, conn, pool);
    }

    /**
     * Return existing client if present.
     *
     * @param clientId The client ID
     * @return Optional containing the client, or empty if not found
     */
    public Optional<PgClient> getClient(String clientId) {
        return Optional.ofNullable(clients.get(clientId));
    }

    /**
     * Gets the pool for a specific client.
     * Handy for diagnostics or advanced usage.
     *
     * @param clientId The client ID, or null/blank for the default pool
     * @return Optional containing the pool, or empty if not found
     */
    public Optional<Pool> getPool(String clientId) {
        String resolvedId = resolveClientId(clientId);
        return Optional.ofNullable(connectionManager.getExistingPool(resolvedId));
    }

    /**
     * Gets the connection configuration for a specific client.
     *
     * @param clientId The client ID, or null/blank for the default pool
     * @return The connection configuration, or null if not found
     */
    public PgConnectionConfig getConnectionConfig(String clientId) {
        String resolvedId = resolveClientId(clientId);
        return connectionConfigs.get(resolvedId);
    }

    /**
     * Gets the pool configuration for a specific client.
     *
     * @param clientId The client ID, or null/blank for the default pool
     * @return The pool configuration, or null if not found
     */
    public PgPoolConfig getPoolConfig(String clientId) {
        String resolvedId = resolveClientId(clientId);
        return poolConfigs.get(resolvedId);
    }

    /**
     * Resolves a client ID, returning the default pool ID if null or blank.
     *
     * @param clientId The client ID to resolve
     * @return The resolved client ID (never null)
     */
    private String resolveClientId(String clientId) {
        return (clientId == null || clientId.isBlank())
            ? PeeGeeQDefaults.DEFAULT_POOL_ID
            : clientId;
    }

    /**
     * Gets the connection manager used by this factory.
     *
     * @return The connection manager
     */
    public PgConnectionManager getConnectionManager() {
        return connectionManager;
    }

    /**
     * Gets the set of available client IDs that have been configured.
     *
     * @return A set of client IDs
     */
    public Set<String> getAvailableClients() {
        return Collections.unmodifiableSet(clients.keySet());
    }

    /**
     * Remove one client: closes its pool and drops cached configs.
     *
     * @param clientId The client ID to remove
     * @return Future<Void> that completes when the client is removed
     */
    public Future<Void> removeClientAsync(String clientId) {
        boolean removed = clients.remove(clientId) != null;
        connectionConfigs.remove(clientId);
        poolConfigs.remove(clientId);
        if (removed && meter != null) {
            Counter.builder("peegeeq.db.client.removed")
                .tag("client", clientId)
                .register(meter)
                .increment();
        }
        return connectionManager.closePoolAsync(clientId)
            .onSuccess(v -> logger.info("Closed pool for client '{}'", clientId))
            .onFailure(err -> logger.warn("Error closing pool for client '{}': {}", clientId, err.getMessage()));
    }

    /**
     * Closes all clients and pools asynchronously.
     *
     * @return Future<Void> that completes when all resources are closed
     */
    public Future<Void> closeAsync() {
        clients.clear();
        connectionConfigs.clear();
        poolConfigs.clear();
        return connectionManager.closeAsync()
            .onSuccess(v -> logger.info("PgClientFactory closed"))
            .onFailure(err -> logger.warn("PgClientFactory close encountered errors: {}", err.toString()));
    }

    @Override
    public void close() throws Exception {
        // Not event-loop safe: this is a blocking call. Guard accordingly.
        if (Vertx.currentContext() != null && Vertx.currentContext().isEventLoopContext()) {
            throw new IllegalStateException("Do not call blocking close() on event-loop thread");
        }
        closeAsync().toCompletionStage().toCompletableFuture().get();
    }

    private static void validate(String clientId, PgConnectionConfig conn, PgPoolConfig pool) {
        if (clientId == null || clientId.isBlank())
            throw new IllegalArgumentException("clientId must be non-blank");
        Objects.requireNonNull(conn, "connectionConfig");
        Objects.requireNonNull(pool, "poolConfig");
    }
}