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
public class PgDatabaseService implements dev.mars.peegeeq.api.database.DatabaseService, dev.mars.peegeeq.api.lifecycle.LifecycleHookRegistrar {

    private static final Logger logger = LoggerFactory.getLogger(PgDatabaseService.class);

    private final PeeGeeQManager manager;
    @Override
    public void registerCloseHook(dev.mars.peegeeq.api.lifecycle.PeeGeeQCloseHook hook) {
        manager.registerCloseHook(hook);
    }

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
        try {
            logger.info("Initializing database service");
            logger.debug("DB-DEBUG: Database service initialization started");
            // The PeeGeeQManager handles initialization in its constructor
            logger.info("Database service initialized successfully");
            logger.debug("DB-DEBUG: Database service initialization completed");
            return CompletableFuture.completedFuture(null);
        } catch (Exception e) {
            logger.error("Failed to initialize database service", e);
            logger.debug("DB-DEBUG: Database service initialization failed: {}", e.getMessage());
            return CompletableFuture.failedFuture(new RuntimeException("Database service initialization failed", e));
        }
    }

    @Override
    public CompletableFuture<Void> start() {
        logger.info("Starting database service");
        logger.debug("DB-DEBUG: Database service start initiated");
        // Delegate to reactive start to be safe on event-loop threads
        return manager.startReactive()
            .toCompletionStage()
            .toCompletableFuture()
            .thenRun(() -> {
                logger.info("Database service started successfully");
                logger.debug("DB-DEBUG: Database service start completed");
            })
            .exceptionally(e -> {
                logger.error("Failed to start database service", e);
                logger.debug("DB-DEBUG: Database service start failed: {}", e.getMessage());
                throw new RuntimeException("Database service start failed", e);
            });
    }

    // Reactive override to avoid blocking when used from Vert.x code paths
    public io.vertx.core.Future<Void> startReactive() {
        return manager.startReactive();
    }

    @Override
    public CompletableFuture<Void> stop() {
        logger.info("Stopping database service");
        return manager.stopReactive()
            .toCompletionStage()
            .toCompletableFuture()
            .thenRun(() -> {
                logger.info("Database service stopped successfully");
            })
            .exceptionally(e -> {
                logger.error("Failed to stop database service", e);
                throw new RuntimeException("Database service stop failed", e);
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
        logger.warn("runMigrations() called but migrations have been removed - schema must be initialized in tests");
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Boolean> performHealthCheck() {
        try {
            boolean healthy = manager.getHealthCheckManager().isHealthy();
            return CompletableFuture.completedFuture(healthy);
        } catch (Exception e) {
            logger.warn("Health check failed", e);
            return CompletableFuture.completedFuture(false);
        }
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
