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


import dev.mars.peegeeq.db.connection.PgConnectionManager;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlConnection;


/**
 * Client for interacting with PostgreSQL databases.
 * 
 * This interface is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
public class PgClient implements AutoCloseable {
    private final String clientId;
    private final PgConnectionManager connectionManager;
    
    /**
     * Creates a new PgClient.
     *
     * @param clientId The unique identifier for the client
     * @param connectionManager The connection manager to use
     */
    public PgClient(String clientId, PgConnectionManager connectionManager) {
        this.clientId = clientId;
        this.connectionManager = connectionManager;
    }
    
    /**
     * Gets a reactive database connection.
     *
     * @return A Future that completes with a SqlConnection for reactive operations
     */
    public Future<SqlConnection> getReactiveConnection() {
        return connectionManager.getReactiveConnection(clientId);
    }

    /**
     * Gets the reactive pool for this client.
     * This is useful for operations that need direct access to the Pool, such as Pool.withTransaction().
     *
     * IMPORTANT: This method resolves the pool per operation through the connection manager
     * to avoid stale pool references if pools are ever rotated. The pool reference is NOT cached
     * in PgClient to prevent issues with pool lifecycle management.
     *
     * @return The reactive Pool for this client
     * @throws IllegalStateException if no pool exists for this client
     */
    public Pool getReactivePool() {
        Pool pool = connectionManager.getExistingPool(clientId);
        if (pool == null) {
            throw new IllegalStateException("No reactive pool found for client: " + clientId);
        }
        return pool;
    }


    



    
    /**
     * Executes a function with a reactive database connection.
     *
     * @param connectionConsumer The function to execute with the connection
     * @return A Future that completes when the operation is done
     */
    public Future<Void> withReactiveConnection(ReactiveConnectionConsumer connectionConsumer) {
        return getReactiveConnection()
            .compose(connection -> {
                return connectionConsumer.accept(connection)
                    .onComplete(ar -> connection.close());
            });
    }

    /**
     * Executes a function with a reactive database connection and returns a result.
     *
     * @param connectionFunction The function to execute with the connection
     * @param <T> The type of the result
     * @return A Future that completes with the result of the function
     */
    public <T> Future<T> withReactiveConnectionResult(ReactiveConnectionFunction<T> connectionFunction) {
        return getReactiveConnection()
            .compose(connection -> {
                return connectionFunction.apply(connection)
                    .onComplete(ar -> connection.close());
            });
    }

    @Override
    public void close() {
        // No need to close connections as they are managed by the connection manager
    }

    /**
     * Functional interface for consuming a reactive database connection.
     */
    @FunctionalInterface
    public interface ReactiveConnectionConsumer {
        Future<Void> accept(SqlConnection connection);
    }

    /**
     * Functional interface for applying a function to a reactive database connection.
     *
     * @param <T> The type of the result
     */
    @FunctionalInterface
    public interface ReactiveConnectionFunction<T> {
        Future<T> apply(SqlConnection connection);
    }


}