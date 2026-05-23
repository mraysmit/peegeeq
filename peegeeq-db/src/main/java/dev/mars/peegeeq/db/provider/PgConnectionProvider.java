package dev.mars.peegeeq.db.provider;

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
import dev.mars.peegeeq.db.client.PgClientFactory;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.util.function.Function;

/**
 * PostgreSQL implementation of ConnectionProvider.
 * 
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
/**
 * PostgreSQL implementation of ConnectionProvider.
 * This class wraps the existing PgClientFactory and PgConnectionManager
 * to provide a clean interface for connection management.
 */
public class PgConnectionProvider implements dev.mars.peegeeq.api.database.ConnectionProvider {
    
    private static final Logger logger = LoggerFactory.getLogger(PgConnectionProvider.class);
    
    private final PgClientFactory clientFactory;
    private final PgConnectionManager connectionManager;
    
    public PgConnectionProvider(PgClientFactory clientFactory) {
        this.clientFactory = clientFactory;
        this.connectionManager = clientFactory.getConnectionManager();
        logger.info("PgConnectionManager@{}: Initialized PgConnectionProvider@{}",
                   connectionManager.getInstanceId(),
                   Integer.toHexString(System.identityHashCode(this)));
    }
    
    @Override
    public Future<Pool> getReactivePool(String clientId) {
        String resolvedClientId = clientId != null ? clientId : PeeGeeQDefaults.DEFAULT_POOL_ID;
        try {
            // Get the client configurations from the factory
            var connectionConfig = clientFactory.getConnectionConfig(resolvedClientId);
            var poolConfig = clientFactory.getPoolConfig(resolvedClientId);

            if (connectionConfig == null || poolConfig == null) {
                if (clientFactory.getAvailableClients().isEmpty()) {
                    return Future.failedFuture(new IllegalStateException(
                            "Connection provider is closed or shutting down"));
                }
                return Future.failedFuture(new IllegalArgumentException("Client not found: " + resolvedClientId));
            }

            // Get the reactive pool from the connection manager
            Pool pool = connectionManager.getOrCreateReactivePool(
                resolvedClientId,
                connectionConfig,
                poolConfig
            );

            logger.debug("Retrieved reactive pool for client: {}", resolvedClientId);
            return Future.succeededFuture(pool);
        } catch (Exception e) {
            logger.error("Failed to get reactive pool for client: {}", resolvedClientId, e);
            return Future.failedFuture(e);
        }
    }

    @Override
    public Future<SqlConnection> getConnection(String clientId) {
        logger.debug("Getting reactive connection for client: {}", clientId);
        return connectionManager.getReactiveConnection(clientId)
            .onSuccess(conn -> logger.debug("Successfully obtained reactive connection for client: {}", clientId))
            .onFailure(error -> logger.error("Failed to get reactive connection for client: {}: {}", clientId, error.getMessage()));
    }

    @Override
    public <T> Future<T> withConnection(String clientId, Function<SqlConnection, Future<T>> operation) {
        logger.debug("Executing operation with connection for client: {}", clientId);
        return connectionManager.withConnection(clientId, operation)
            .onSuccess(result -> logger.debug("Successfully executed operation with connection for client: {}", clientId))
            .onFailure(error -> logger.error("Failed to execute operation with connection for client: {}: {}", clientId, error.getMessage()));
    }

    @Override
    public <T> Future<T> withTransaction(String clientId, Function<SqlConnection, Future<T>> operation) {
        logger.debug("Executing operation with transaction for client: {}", clientId);
        return connectionManager.withTransaction(clientId, operation)
            .onSuccess(result -> logger.debug("Successfully executed operation with transaction for client: {}", clientId))
            .onFailure(error -> logger.error("Failed to execute operation with transaction for client: {}: {}", clientId, error.getMessage()));
    }

    @Override
    public boolean hasClient(String clientId) {
        try {
            var connectionConfig = clientFactory.getConnectionConfig(clientId);
            return connectionConfig != null;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public Future<Boolean> isHealthy() {
        try {
            boolean healthy = connectionManager.hasPoolsConfigured();
            return Future.succeededFuture(healthy);
        } catch (Exception e) {
            logger.warn("Health check failed", e);
            return Future.succeededFuture(false);
        }
    }

    @Override
    public Future<Boolean> isClientHealthy(String clientId) {
        if (!hasClient(clientId)) {
            return Future.succeededFuture(false);
        }

        // Use reactive connection to test health
        return getConnection(clientId)
            .compose(connection -> {
                // Simple health check query
                return connection.query("SELECT 1").execute()
                    .map(rowSet -> true)
                    .eventually(connection::close); // Always close the connection
            })
            .transform(ar -> {
                if (ar.failed()) {
                    logger.warn("Health check failed for client: {}: {}", clientId, ar.cause().getMessage());
                    return Future.succeededFuture(false);
                }
                return Future.succeededFuture(ar.result());
            });
    }



    @Override
    public void close() throws Exception {
        logger.info("Closing PgConnectionProvider");
        if (clientFactory != null) {
            clientFactory.close()
                .onSuccess(v -> logger.info("Successfully closed PgConnectionProvider and client factory"))
                .onFailure(e -> logger.error("Failed to close client factory", e));
        } else {
            logger.debug("Client factory was null, nothing to close");
        }
    }
}
