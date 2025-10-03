package dev.mars.peegeeq.api.database;

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


import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlConnection;


import java.util.function.Function;

/**
 * Abstract interface for providing database connections using Vert.x 5.x reactive patterns.
 *
 * This interface is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities using
 * modern Vert.x 5.x reactive database clients instead of blocking JDBC.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 2.0 - Migrated to Vert.x 5.x reactive patterns
 */
public interface ConnectionProvider extends AutoCloseable {

    /**
     * Gets a reactive pool for the specified client ID.
     * Uses Vert.x 5.x composable Future patterns.
     *
     * @param clientId The unique identifier for the client
     * @return Future that resolves to a reactive Pool for database operations
     * @throws IllegalArgumentException if the client ID is not found
     */
    Future<Pool> getReactivePool(String clientId);

    /**
     * Gets a reactive connection for the specified client ID.
     * Uses Vert.x 5.x composable Future patterns.
     *
     * @param clientId The unique identifier for the client
     * @return Future that resolves to a SqlConnection for reactive database operations
     */
    Future<SqlConnection> getConnection(String clientId);

    /**
     * Executes an operation with a connection from the pool.
     * Automatically handles connection lifecycle and follows Vert.x 5.x patterns.
     *
     * @param clientId The unique identifier for the client
     * @param operation The operation to execute with the connection
     * @return Future that completes when the operation completes
     */
    <T> Future<T> withConnection(String clientId, Function<SqlConnection, Future<T>> operation);

    /**
     * Executes an operation within a transaction.
     * Provides automatic transaction management using Vert.x 5.x patterns.
     *
     * @param clientId The unique identifier for the client
     * @param operation The operation to execute within the transaction
     * @return Future that completes when the transaction completes
     */
    <T> Future<T> withTransaction(String clientId, Function<SqlConnection, Future<T>> operation);

    /**
     * Checks if a client with the given ID exists.
     *
     * @param clientId The client ID to check
     * @return true if the client exists, false otherwise
     */
    boolean hasClient(String clientId);

    /**
     * Gets the health status of the connection provider using reactive patterns.
     *
     * @return Future that resolves to true if healthy, false otherwise
     */
    Future<Boolean> isHealthy();

    /**
     * Gets the health status for a specific client using reactive patterns.
     *
     * @param clientId The client ID to check
     * @return Future that resolves to true if the client's connections are healthy, false otherwise
     */
    Future<Boolean> isClientHealthy(String clientId);



    /**
     * Closes the connection provider and releases all resources.
     */
    @Override
    void close() throws Exception;
}
