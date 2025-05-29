package dev.mars.peegeeq.db.client;

import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;

/**
 * Factory for creating PgClient instances.
 */
public class PgClientFactory implements AutoCloseable {
    private final PgConnectionManager connectionManager;
    
    /**
     * Creates a new PgClientFactory with a new connection manager.
     */
    public PgClientFactory() {
        this.connectionManager = new PgConnectionManager();
    }
    
    /**
     * Creates a new PgClientFactory with the given connection manager.
     *
     * @param connectionManager The connection manager to use
     */
    public PgClientFactory(PgConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }
    
    /**
     * Creates a new PgClient with the given client ID and configurations.
     *
     * @param clientId The unique identifier for the client
     * @param connectionConfig The PostgreSQL connection configuration
     * @param poolConfig The connection pool configuration
     * @return A new PgClient
     */
    public PgClient createClient(String clientId, PgConnectionConfig connectionConfig, PgPoolConfig poolConfig) {
        // Create the data source if it doesn't exist
        connectionManager.getOrCreateDataSource(clientId, connectionConfig, poolConfig);
        
        // Create and return the client
        return new PgClient(clientId, connectionManager);
    }
    
    /**
     * Creates a new PgClient with the given client ID and default pool configuration.
     *
     * @param clientId The unique identifier for the client
     * @param connectionConfig The PostgreSQL connection configuration
     * @return A new PgClient
     */
    public PgClient createClient(String clientId, PgConnectionConfig connectionConfig) {
        return createClient(clientId, connectionConfig, new PgPoolConfig.Builder().build());
    }
    
    /**
     * Gets the connection manager used by this factory.
     *
     * @return The connection manager
     */
    public PgConnectionManager getConnectionManager() {
        return connectionManager;
    }
    
    @Override
    public void close() throws Exception {
        connectionManager.close();
    }
}