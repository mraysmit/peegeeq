package dev.mars.peegeeq.db.provider;

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

import dev.mars.peegeeq.api.database.ConnectionProvider;
import dev.mars.peegeeq.api.database.MetricsProvider;
import dev.mars.peegeeq.db.PeeGeeQManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * PostgreSQL implementation of DatabaseService.
 * 
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
/**
 * PostgreSQL implementation of DatabaseService.
 * This class wraps the existing PeeGeeQManager to provide
 * a clean interface for database operations.
 */
public class PgDatabaseService implements dev.mars.peegeeq.api.database.DatabaseService {
    
    private static final Logger logger = LoggerFactory.getLogger(PgDatabaseService.class);
    
    private final PeeGeeQManager manager;
    private final PgConnectionProvider connectionProvider;
    private final PgMetricsProvider metricsProvider;
    
    public PgDatabaseService(PeeGeeQManager manager) {
        this.manager = manager;
        this.connectionProvider = new PgConnectionProvider(manager.getClientFactory());
        this.metricsProvider = new PgMetricsProvider(manager.getMetrics());
        logger.info("Initialized PgDatabaseService");
    }
    
    @Override
    public CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            try {
                logger.info("Initializing database service");
                logger.debug("DB-DEBUG: Database service initialization started");
                // The PeeGeeQManager handles initialization in its constructor
                logger.info("Database service initialized successfully");
                logger.debug("DB-DEBUG: Database service initialization completed");
            } catch (Exception e) {
                logger.error("Failed to initialize database service", e);
                logger.debug("DB-DEBUG: Database service initialization failed: {}", e.getMessage());
                throw new RuntimeException("Database service initialization failed", e);
            }
        });
    }
    
    @Override
    public CompletableFuture<Void> start() {
        return CompletableFuture.runAsync(() -> {
            try {
                logger.info("Starting database service");
                logger.debug("DB-DEBUG: Database service start initiated");
                manager.start();
                logger.info("Database service started successfully");
                logger.debug("DB-DEBUG: Database service start completed");
            } catch (Exception e) {
                logger.error("Failed to start database service", e);
                logger.debug("DB-DEBUG: Database service start failed: {}", e.getMessage());
                throw new RuntimeException("Database service start failed", e);
            }
        });
    }
    
    @Override
    public CompletableFuture<Void> stop() {
        return CompletableFuture.runAsync(() -> {
            try {
                logger.info("Stopping database service");
                manager.stop();
                logger.info("Database service stopped successfully");
            } catch (Exception e) {
                logger.error("Failed to stop database service", e);
                throw new RuntimeException("Database service stop failed", e);
            }
        });
    }
    
    @Override
    public boolean isRunning() {
        return manager.isStarted();
    }
    
    @Override
    public boolean isHealthy() {
        try {
            return manager.getHealthCheckManager().getOverallHealth().isHealthy();
        } catch (Exception e) {
            logger.warn("Health check failed", e);
            return false;
        }
    }
    
    @Override
    public ConnectionProvider getConnectionProvider() {
        return connectionProvider;
    }
    
    @Override
    public MetricsProvider getMetricsProvider() {
        return metricsProvider;
    }
    
    @Override
    public CompletableFuture<Void> runMigrations() {
        return CompletableFuture.runAsync(() -> {
            try {
                logger.info("Running database migrations");
                manager.getMigrationManager().migrate();
                logger.info("Database migrations completed successfully");
            } catch (Exception e) {
                logger.error("Failed to run database migrations", e);
                throw new RuntimeException("Database migration failed", e);
            }
        });
    }
    
    @Override
    public CompletableFuture<Boolean> performHealthCheck() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return manager.getHealthCheckManager().isHealthy();
            } catch (Exception e) {
                logger.warn("Health check failed", e);
                return false;
            }
        });
    }
    
    @Override
    public void close() throws Exception {
        logger.info("Closing database service");
        try {
            if (connectionProvider != null) {
                connectionProvider.close();
            }
        } finally {
            if (manager != null) {
                manager.close();
            }
        }
        logger.info("Database service closed successfully");
    }
}
