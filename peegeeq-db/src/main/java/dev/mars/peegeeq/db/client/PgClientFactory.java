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


import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import java.util.Map;
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
    private final PgConnectionManager connectionManager;
    private final Map<String, PgConnectionConfig> connectionConfigs = new ConcurrentHashMap<>();
    private final Map<String, PgPoolConfig> poolConfigs = new ConcurrentHashMap<>();

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
        // Store the configurations for later retrieval
        connectionConfigs.put(clientId, connectionConfig);
        poolConfigs.put(clientId, poolConfig);

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
     * Gets the connection configuration for a specific client.
     *
     * @param clientId The client ID
     * @return The connection configuration, or null if not found
     */
    public PgConnectionConfig getConnectionConfig(String clientId) {
        return connectionConfigs.get(clientId);
    }

    /**
     * Gets the pool configuration for a specific client.
     *
     * @param clientId The client ID
     * @return The pool configuration, or null if not found
     */
    public PgPoolConfig getPoolConfig(String clientId) {
        return poolConfigs.get(clientId);
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
    public java.util.Set<String> getAvailableClients() {
        return connectionConfigs.keySet();
    }

    @Override
    public void close() throws Exception {
        connectionManager.close();
        connectionConfigs.clear();
        poolConfigs.clear();
    }
}