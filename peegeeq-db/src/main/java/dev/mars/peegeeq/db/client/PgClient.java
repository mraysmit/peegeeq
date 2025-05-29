package dev.mars.peegeeq.db.client;

import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.connection.PgListenerConnection;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Client for interacting with PostgreSQL databases.
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