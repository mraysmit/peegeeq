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


import dev.mars.peegeeq.api.subscription.SubscriptionService;
import io.vertx.core.Future;

/**
 * Abstract interface for database operations using Vert.x 5.x reactive patterns.
 *
 * This interface is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities using
 * modern Vert.x 5.x reactive database clients instead of blocking JDBC.
 *
 * Extends VertxProvider, PoolProvider, and ConnectOptionsProvider to provide
 * clean access to the underlying Vert.x instance, connection pool, and
 * connection options without requiring reflection.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 2.2 - Added getSubscriptionService() for consumer group subscription management
 */
public interface DatabaseService extends AutoCloseable, VertxProvider, PoolProvider, ConnectOptionsProvider {

    /**
     * Initializes the database service.
     *
     * @return A Future that completes when initialization is done
     */
    Future<Void> initialize();

    /**
     * Starts the database service.
     *
     * @return A Future that completes when the service is started
     */
    Future<Void> start();

    /**
     * Stops the database service.
     *
     * @return A Future that completes when the service is stopped
     */
    Future<Void> stop();
    
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
     * Gets the subscription service for managing consumer group subscriptions.
     *
     * @return The subscription service
     * @since 1.1.0
     */
    SubscriptionService getSubscriptionService();

    /**
     * Runs database migrations if needed.
     *
     * @return A Future that completes when migrations are done
     */
    Future<Void> runMigrations();

    /**
     * Performs a health check on the database.
     *
     * @return A Future that completes with the health status
     */
    Future<Boolean> performHealthCheck();
    
    /**
     * Closes the database service and releases all resources.
     */
    @Override
    void close() throws Exception;
}
