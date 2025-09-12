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
import dev.mars.peegeeq.db.connection.PgListenerConnection;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlConnection;
import java.sql.Connection;
import java.sql.SQLException;

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
     * @return The reactive Pool for this client
     */
    public Pool getReactivePool() {
        // We need to get the stored configurations to retrieve the pool
        // This is a bit of a workaround since we don't store the pool directly in PgClient
        throw new UnsupportedOperationException("getReactivePool() is not yet implemented. Use getReactiveConnection() instead.");
    }


    
    /**
     * Gets a JDBC connection for legacy compatibility.
     *
     * @deprecated This method is deprecated and will be removed in a future version.
     * Use getReactiveConnection() for new implementations.
     * @return A JDBC connection
     * @throws SQLException If a connection cannot be obtained
     */
    @Deprecated
    public Connection getConnection() throws SQLException {
        throw new UnsupportedOperationException(
            "JDBC connections are no longer supported. Use getReactiveConnection() for reactive patterns.");
    }

    /**
     * Creates a listener connection for LISTEN/NOTIFY functionality (deprecated - for test compatibility only).
     *
     * @deprecated This method is deprecated and will be removed. Use reactive patterns instead.
     * @return Always throws UnsupportedOperationException
     * @throws UnsupportedOperationException Always thrown to indicate JDBC is no longer supported
     */
    @Deprecated
    public PgListenerConnection createListenerConnection() throws SQLException {
        throw new UnsupportedOperationException(
            "PgListenerConnection requires JDBC patterns. Use reactive Pool.getConnection() for LISTEN/NOTIFY operations.");
    }

    /**
     * Executes a function with a connection (deprecated - for test compatibility only).
     *
     * @deprecated This method is deprecated and will be removed. Use withReactiveConnection() instead.
     * @param connectionConsumer The function to execute with the connection
     * @throws UnsupportedOperationException Always thrown to indicate JDBC is no longer supported
     */
    @Deprecated
    public void withConnection(ConnectionConsumer connectionConsumer) throws SQLException {
        throw new UnsupportedOperationException(
            "JDBC Connection usage has been removed. Use withReactiveConnection() for reactive patterns.");
    }

    /**
     * Executes a function with a connection and returns a result (deprecated - for test compatibility only).
     *
     * @deprecated This method is deprecated and will be removed. Use withReactiveConnectionResult() instead.
     * @param connectionFunction The function to execute with the connection
     * @param <T> The type of the result
     * @return Always throws UnsupportedOperationException
     * @throws UnsupportedOperationException Always thrown to indicate JDBC is no longer supported
     */
    @Deprecated
    public <T> T withConnectionResult(ConnectionFunction<T> connectionFunction) throws SQLException {
        throw new UnsupportedOperationException(
            "JDBC Connection usage has been removed. Use withReactiveConnectionResult() for reactive patterns.");
    }

    /**
     * Creates a reactive listener connection for LISTEN/NOTIFY functionality.
     * Note: This method is deprecated as PgListenerConnection requires JDBC.
     * Use reactive patterns with Pool.getConnection() for new implementations.
     *
     * @return A Future that completes with a listener connection
     * @deprecated Use reactive patterns instead of JDBC-based listener connections
     */
    @Deprecated
    public Future<PgListenerConnection> createReactiveListenerConnection() {
        return Future.failedFuture(new UnsupportedOperationException(
            "PgListenerConnection requires JDBC patterns. Use reactive Pool.getConnection() for LISTEN/NOTIFY operations."));
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

    /**
     * Functional interface for consuming a JDBC connection (deprecated - for test compatibility only).
     *
     * @deprecated This interface is deprecated and will be removed. Use ReactiveConnectionConsumer instead.
     */
    @Deprecated
    @FunctionalInterface
    public interface ConnectionConsumer {
        void accept(Connection connection) throws SQLException;
    }

    /**
     * Functional interface for applying a function to a JDBC connection (deprecated - for test compatibility only).
     *
     * @deprecated This interface is deprecated and will be removed. Use ReactiveConnectionFunction instead.
     * @param <T> The type of the result
     */
    @Deprecated
    @FunctionalInterface
    public interface ConnectionFunction<T> {
        T apply(Connection connection) throws SQLException;
    }
}