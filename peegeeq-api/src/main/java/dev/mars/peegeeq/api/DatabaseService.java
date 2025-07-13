package dev.mars.peegeeq.api;

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


import java.util.concurrent.CompletableFuture;

/**
 * Abstract interface for database operations.
 * 
 * This interface is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
/**
 * Abstract interface for database operations.
 * This interface provides a consistent way to perform database operations
 * without exposing implementation-specific details.
 */
public interface DatabaseService extends AutoCloseable {
    
    /**
     * Initializes the database service.
     * This may include running migrations, setting up connections, etc.
     * 
     * @return A CompletableFuture that completes when initialization is done
     */
    CompletableFuture<Void> initialize();
    
    /**
     * Starts the database service.
     * This may include starting background tasks, health checks, etc.
     * 
     * @return A CompletableFuture that completes when the service is started
     */
    CompletableFuture<Void> start();
    
    /**
     * Stops the database service.
     * This should gracefully shut down all background tasks.
     * 
     * @return A CompletableFuture that completes when the service is stopped
     */
    CompletableFuture<Void> stop();
    
    /**
     * Checks if the database service is running.
     * 
     * @return true if the service is running, false otherwise
     */
    boolean isRunning();
    
    /**
     * Checks if the database service is healthy.
     * 
     * @return true if the service is healthy, false otherwise
     */
    boolean isHealthy();
    
    /**
     * Gets the connection provider for this database service.
     * 
     * @return The connection provider
     */
    ConnectionProvider getConnectionProvider();
    
    /**
     * Gets the metrics provider for this database service.
     * 
     * @return The metrics provider
     */
    MetricsProvider getMetricsProvider();
    
    /**
     * Runs database migrations if needed.
     * 
     * @return A CompletableFuture that completes when migrations are done
     */
    CompletableFuture<Void> runMigrations();
    
    /**
     * Performs a health check on the database.
     * 
     * @return A CompletableFuture that completes with the health status
     */
    CompletableFuture<Boolean> performHealthCheck();
    
    /**
     * Closes the database service and releases all resources.
     */
    @Override
    void close() throws Exception;
}
