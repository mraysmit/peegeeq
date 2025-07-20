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


import javax.sql.DataSource;
import java.sql.Connection;
import java.util.concurrent.CompletableFuture;

/**
 * Abstract interface for providing database connections.
 * 
 * This interface is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
/**
 * Abstract interface for providing database connections.
 * This interface allows different implementations to provide
 * database connectivity without exposing implementation details.
 */
public interface ConnectionProvider extends AutoCloseable {
    
    /**
     * Gets a data source for the specified client ID.
     * 
     * @param clientId The unique identifier for the client
     * @return The data source for the client
     * @throws IllegalArgumentException if the client ID is not found
     */
    DataSource getDataSource(String clientId);
    
    /**
     * Gets a connection for the specified client ID.
     * 
     * @param clientId The unique identifier for the client
     * @return A database connection
     * @throws Exception if unable to get a connection
     */
    Connection getConnection(String clientId) throws Exception;
    
    /**
     * Gets a connection asynchronously for the specified client ID.
     * 
     * @param clientId The unique identifier for the client
     * @return A CompletableFuture that resolves to a database connection
     */
    CompletableFuture<Connection> getConnectionAsync(String clientId);
    
    /**
     * Checks if a client with the given ID exists.
     * 
     * @param clientId The client ID to check
     * @return true if the client exists, false otherwise
     */
    boolean hasClient(String clientId);
    
    /**
     * Gets the health status of the connection provider.
     * 
     * @return true if healthy, false otherwise
     */
    boolean isHealthy();
    
    /**
     * Gets the health status for a specific client.
     * 
     * @param clientId The client ID to check
     * @return true if the client's connections are healthy, false otherwise
     */
    boolean isClientHealthy(String clientId);
    
    /**
     * Closes the connection provider and releases all resources.
     */
    @Override
    void close() throws Exception;
}
