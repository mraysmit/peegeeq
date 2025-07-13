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
     * Gets a database connection.
     *
     * @return A database connection
     * @throws SQLException If a connection cannot be obtained
     */
    public Connection getConnection() throws SQLException {
        return connectionManager.getConnection(clientId);
    }
    
    /**
     * Creates a listener connection for LISTEN/NOTIFY functionality.
     *
     * @return A listener connection
     * @throws SQLException If a connection cannot be obtained
     */
    public PgListenerConnection createListenerConnection() throws SQLException {
        PgListenerConnection listenerConnection = new PgListenerConnection(getConnection());
        listenerConnection.start();
        return listenerConnection;
    }
    
    /**
     * Executes a function with a connection, automatically closing the connection when done.
     *
     * @param connectionConsumer The function to execute with the connection
     * @throws SQLException If a database error occurs
     */
    public void withConnection(ConnectionConsumer connectionConsumer) throws SQLException {
        try (Connection connection = getConnection()) {
            connectionConsumer.accept(connection);
        }
    }
    
    /**
     * Executes a function with a connection and returns a result, automatically closing the connection when done.
     *
     * @param connectionFunction The function to execute with the connection
     * @param <T> The type of the result
     * @return The result of the function
     * @throws SQLException If a database error occurs
     */
    public <T> T withConnectionResult(ConnectionFunction<T> connectionFunction) throws SQLException {
        try (Connection connection = getConnection()) {
            return connectionFunction.apply(connection);
        }
    }
    
    @Override
    public void close() {
        // No need to close connections as they are managed by the connection manager
    }
    
    /**
     * Functional interface for consuming a connection.
     */
    @FunctionalInterface
    public interface ConnectionConsumer {
        void accept(Connection connection) throws SQLException;
    }
    
    /**
     * Functional interface for applying a function to a connection and returning a result.
     */
    @FunctionalInterface
    public interface ConnectionFunction<T> {
        T apply(Connection connection) throws SQLException;
    }
}