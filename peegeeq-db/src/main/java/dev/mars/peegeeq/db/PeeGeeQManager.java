package dev.mars.peegeeq.db;

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


import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.api.QueueFactoryProvider;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.api.database.DatabaseService;

import dev.mars.peegeeq.db.client.PgClientFactory;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.deadletter.DeadLetterQueueManager;
import dev.mars.peegeeq.db.health.HealthCheckManager;
import dev.mars.peegeeq.db.metrics.PeeGeeQMetrics;
import dev.mars.peegeeq.db.migration.SchemaMigrationManager;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.db.recovery.StuckMessageRecoveryManager;
import dev.mars.peegeeq.db.resilience.BackpressureManager;
import dev.mars.peegeeq.db.resilience.CircuitBreakerManager;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

// HikariCP imports removed - using pure Vert.x 5.x patterns only

/**
 * Central management facade for PeeGeeQ system.
 * 
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
public class PeeGeeQManager implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(PeeGeeQManager.class);
    
    private final PeeGeeQConfiguration configuration;
    private final Vertx vertx;
    private final PgClientFactory clientFactory;
    private final DataSource dataSource; // TODO: Remove when all components use reactive patterns
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    
    // Core components
    private final SchemaMigrationManager migrationManager;
    private final PeeGeeQMetrics metrics;
    private final HealthCheckManager healthCheckManager;
    private final CircuitBreakerManager circuitBreakerManager;
    private final BackpressureManager backpressureManager;
    private final DeadLetterQueueManager deadLetterQueueManager;
    private final StuckMessageRecoveryManager stuckMessageRecoveryManager;
    
    // Background services
    private final ScheduledExecutorService scheduledExecutor;
    private volatile boolean started = false;

    // New provider interfaces
    private final PgDatabaseService databaseService;
    private final PgQueueFactoryProvider queueFactoryProvider;
    
    public PeeGeeQManager() {
        this(new PeeGeeQConfiguration());
    }
    
    public PeeGeeQManager(String profile) {
        this(new PeeGeeQConfiguration(profile));
    }
    
    public PeeGeeQManager(PeeGeeQConfiguration configuration) {
        this(configuration, new SimpleMeterRegistry());
    }
    
    public PeeGeeQManager(PeeGeeQConfiguration configuration, MeterRegistry meterRegistry) {
        this.configuration = configuration;
        this.meterRegistry = meterRegistry;
        this.objectMapper = new ObjectMapper();

        logger.info("Initializing PeeGeeQ Manager with profile: {}", configuration.getProfile());

        try {
            // Initialize Vert.x for reactive operations
            this.vertx = Vertx.vertx();

            // Initialize client factory with Vert.x support
            this.clientFactory = new PgClientFactory(vertx);

            // Create the client to ensure configuration is stored in the factory
            clientFactory.createClient("peegeeq-main",
                configuration.getDatabaseConfig(),
                configuration.getPoolConfig());

            // DataSource usage removed - using pure Vert.x 5.x reactive patterns only
            this.dataSource = null; // Deprecated field, will be removed in future versions

            // Create temporary DataSource for migration and legacy components that haven't been migrated yet
            // This will be removed once all components are migrated to reactive patterns
            DataSource tempDataSource = createTemporaryDataSourceForMigration(
                configuration.getDatabaseConfig(), configuration.getPoolConfig());

            // Initialize core components
            this.migrationManager = new SchemaMigrationManager(tempDataSource);
            this.metrics = new PeeGeeQMetrics(tempDataSource, configuration.getMetricsConfig().getInstanceId());
            this.healthCheckManager = new HealthCheckManager(tempDataSource,
                Duration.ofSeconds(30), Duration.ofSeconds(5));
            this.circuitBreakerManager = new CircuitBreakerManager(
                configuration.getCircuitBreakerConfig(), meterRegistry);
            this.backpressureManager = new BackpressureManager(50, Duration.ofSeconds(30));
            this.deadLetterQueueManager = new DeadLetterQueueManager(tempDataSource, objectMapper);
            this.stuckMessageRecoveryManager = new StuckMessageRecoveryManager(
                tempDataSource,
                configuration.getQueueConfig().getRecoveryProcessingTimeout(),
                configuration.getQueueConfig().isRecoveryEnabled()
            );
            
            // Initialize scheduled executor
            this.scheduledExecutor = new ScheduledThreadPoolExecutor(3, r -> {
                Thread t = new Thread(r, "peegeeq-manager");
                t.setDaemon(false); // Changed to false to ensure proper shutdown
                return t;
            });
            
            // Register metrics
            if (configuration.getMetricsConfig().isEnabled()) {
                metrics.bindTo(meterRegistry);
            }

            // Initialize new provider interfaces
            this.databaseService = new PgDatabaseService(this);
            this.queueFactoryProvider = new PgQueueFactoryProvider(configuration);

            logger.info("PeeGeeQ Manager initialized successfully");
            
        } catch (Exception e) {
            logger.error("Failed to initialize PeeGeeQ Manager", e);
            throw new RuntimeException("Failed to initialize PeeGeeQ Manager", e);
        }
    }
    
    /**
     * Starts all PeeGeeQ services.
     */
    public synchronized void start() {
        if (started) {
            logger.warn("PeeGeeQ Manager is already started");
            return;
        }
        
        try {
            logger.info("Starting PeeGeeQ Manager...");
            logger.debug("DB-DEBUG: PeeGeeQ Manager start initiated with configuration profile: {}", configuration.getProfile());

            // Run database migrations if enabled
            if (configuration.getBoolean("peegeeq.migration.enabled", true)) {
                logger.info("Running database migrations...");
                logger.debug("DB-DEBUG: Starting database migration process");
                int appliedMigrations = migrationManager.migrate();
                logger.info("Applied {} database migrations", appliedMigrations);
                logger.debug("DB-DEBUG: Database migrations completed successfully, applied: {}", appliedMigrations);
            } else {
                logger.debug("DB-DEBUG: Database migrations disabled by configuration");
            }

            // Start health checks
            logger.debug("DB-DEBUG: Starting health check manager");
            healthCheckManager.start();
            logger.debug("DB-DEBUG: Health check manager started successfully");

            // Start metrics collection
            if (configuration.getMetricsConfig().isEnabled()) {
                logger.debug("DB-DEBUG: Starting metrics collection");
                startMetricsCollection();
                logger.debug("DB-DEBUG: Metrics collection started successfully");
            } else {
                logger.debug("DB-DEBUG: Metrics collection disabled by configuration");
            }

            // Start background cleanup tasks
            logger.debug("DB-DEBUG: Starting background cleanup tasks");
            startBackgroundTasks();
            logger.debug("DB-DEBUG: Background cleanup tasks started successfully");

            started = true;
            logger.info("PeeGeeQ Manager started successfully");
            logger.debug("DB-DEBUG: PeeGeeQ Manager startup completed, all components initialized");

        } catch (Exception e) {
            logger.error("Failed to start PeeGeeQ Manager", e);
            logger.debug("DB-DEBUG: PeeGeeQ Manager startup failed, error: {}", e.getMessage());
            throw new RuntimeException("Failed to start PeeGeeQ Manager", e);
        }
    }
    
    /**
     * Stops all PeeGeeQ services.
     */
    public synchronized void stop() {
        if (!started) {
            return;
        }

        logger.info("Stopping PeeGeeQ Manager...");
        logger.debug("DB-DEBUG: PeeGeeQ Manager shutdown initiated");

        try {
            // Stop health checks
            logger.debug("DB-DEBUG: Stopping health check manager");
            healthCheckManager.stop();
            logger.debug("DB-DEBUG: Health check manager stopped successfully");

            // Stop scheduled tasks gracefully
            scheduledExecutor.shutdown();
            try {
                if (!scheduledExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    logger.warn("Scheduled executor did not terminate gracefully, forcing shutdown");
                    scheduledExecutor.shutdownNow();

                    // Wait a bit more for forced shutdown
                    if (!scheduledExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                        logger.error("Scheduled executor did not terminate after forced shutdown");
                    } else {
                        logger.debug("Scheduled executor terminated after forced shutdown");
                    }
                } else {
                    logger.debug("Scheduled executor terminated gracefully");
                }
            } catch (InterruptedException e) {
                logger.warn("Interrupted while stopping scheduled executor");
                scheduledExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }

            started = false;
            logger.info("PeeGeeQ Manager stopped successfully");

        } catch (Exception e) {
            logger.error("Error stopping PeeGeeQ Manager", e);
        }
    }
    
    private void startMetricsCollection() {
        Duration reportingInterval = configuration.getMetricsConfig().getReportingInterval();
        
        scheduledExecutor.scheduleAtFixedRate(() -> {
            try {
                metrics.persistMetrics(meterRegistry);
            } catch (Exception e) {
                logger.warn("Failed to persist metrics", e);
            }
        }, reportingInterval.toMillis(), reportingInterval.toMillis(), TimeUnit.MILLISECONDS);
        
        logger.info("Started metrics collection with interval: {}", reportingInterval);
    }
    
    private void startBackgroundTasks() {
        // Dead letter queue cleanup
        scheduledExecutor.scheduleAtFixedRate(() -> {
            try {
                int cleaned = deadLetterQueueManager.cleanupOldMessages(30);
                if (cleaned > 0) {
                    logger.info("Cleaned up {} old dead letter messages", cleaned);
                }
            } catch (Exception e) {
                logger.warn("Failed to cleanup old dead letter messages", e);
            }
        }, 1, 24, TimeUnit.HOURS);

        // Stuck message recovery
        Duration recoveryInterval = configuration.getQueueConfig().getRecoveryCheckInterval();
        scheduledExecutor.scheduleAtFixedRate(() -> {
            try {
                int recovered = stuckMessageRecoveryManager.recoverStuckMessages();
                if (recovered > 0) {
                    logger.info("Recovered {} stuck messages from PROCESSING state", recovered);
                }
            } catch (Exception e) {
                logger.warn("Failed to recover stuck messages", e);
            }
        }, recoveryInterval.toMinutes(), recoveryInterval.toMinutes(), TimeUnit.MINUTES);
        
        // Connection pool metrics update
        scheduledExecutor.scheduleAtFixedRate(() -> {
            try {
                // This would typically get actual connection pool metrics
                // For now, we'll use placeholder values
                metrics.updateConnectionPoolMetrics(5, 3, 0);
            } catch (Exception e) {
                logger.warn("Failed to update connection pool metrics", e);
            }
        }, 0, 30, TimeUnit.SECONDS);
        
        logger.info("Started background maintenance tasks");
    }
    
    /**
     * Performs a comprehensive health check of the system.
     */
    public boolean isHealthy() {
        return healthCheckManager.isHealthy();
    }

    /**
     * Checks if the PeeGeeQ Manager is started.
     *
     * @return true if started, false otherwise
     */
    public boolean isStarted() {
        return started;
    }
    
    /**
     * Gets system status information.
     */
    public SystemStatus getSystemStatus() {
        return new SystemStatus(
            started,
            configuration.getProfile(),
            healthCheckManager.getOverallHealth(),
            metrics.getSummary(),
            backpressureManager.getMetrics(),
            deadLetterQueueManager.getStatistics()
        );
    }
    
    /**
     * Validates the current configuration.
     */
    public boolean validateConfiguration() {
        try {
            return migrationManager.validateMigrations();
        } catch (SQLException e) {
            logger.error("Configuration validation failed", e);
            return false;
        }
    }
    
    @Override
    public void close() {
        stop();
        
        try {
            if (clientFactory != null) {
                clientFactory.close();
            }
        } catch (Exception e) {
            logger.error("Error closing client factory", e);
        }
    }
    
    // Getters for components
    public PeeGeeQConfiguration getConfiguration() { return configuration; }
    public PgClientFactory getClientFactory() { return clientFactory; }
    public DataSource getDataSource() { return dataSource; }
    public ObjectMapper getObjectMapper() { return objectMapper; }
    public MeterRegistry getMeterRegistry() { return meterRegistry; }
    public SchemaMigrationManager getMigrationManager() { return migrationManager; }
    public PeeGeeQMetrics getMetrics() { return metrics; }
    public HealthCheckManager getHealthCheckManager() { return healthCheckManager; }
    public CircuitBreakerManager getCircuitBreakerManager() { return circuitBreakerManager; }
    public BackpressureManager getBackpressureManager() { return backpressureManager; }
    public DeadLetterQueueManager getDeadLetterQueueManager() { return deadLetterQueueManager; }
    public StuckMessageRecoveryManager getStuckMessageRecoveryManager() { return stuckMessageRecoveryManager; }

    // Getters for new provider interfaces
    public DatabaseService getDatabaseService() { return databaseService; }
    public QueueFactoryProvider getQueueFactoryProvider() { return queueFactoryProvider; }

    /**
     * Gets the queue factory registrar for registering new factory implementations.
     * This allows implementation modules to register themselves without circular dependencies.
     *
     * @return The queue factory registrar
     */
    public QueueFactoryRegistrar getQueueFactoryRegistrar() {
        return (QueueFactoryRegistrar) queueFactoryProvider;
    }
    
    /**
     * System status data class.
     */
    public static class SystemStatus {
        private final boolean started;
        private final String profile;
        private final dev.mars.peegeeq.db.health.OverallHealthStatus healthStatus;
        private final PeeGeeQMetrics.MetricsSummary metricsSummary;
        private final BackpressureManager.BackpressureMetrics backpressureMetrics;
        private final dev.mars.peegeeq.db.deadletter.DeadLetterQueueStats deadLetterStats;
        
        public SystemStatus(boolean started, String profile, 
                          dev.mars.peegeeq.db.health.OverallHealthStatus healthStatus,
                          PeeGeeQMetrics.MetricsSummary metricsSummary,
                          BackpressureManager.BackpressureMetrics backpressureMetrics,
                          dev.mars.peegeeq.db.deadletter.DeadLetterQueueStats deadLetterStats) {
            this.started = started;
            this.profile = profile;
            this.healthStatus = healthStatus;
            this.metricsSummary = metricsSummary;
            this.backpressureMetrics = backpressureMetrics;
            this.deadLetterStats = deadLetterStats;
        }
        
        // Getters
        public boolean isStarted() { return started; }
        public String getProfile() { return profile; }
        public dev.mars.peegeeq.db.health.OverallHealthStatus getHealthStatus() { return healthStatus; }
        public PeeGeeQMetrics.MetricsSummary getMetricsSummary() { return metricsSummary; }
        public BackpressureManager.BackpressureMetrics getBackpressureMetrics() { return backpressureMetrics; }
        public dev.mars.peegeeq.db.deadletter.DeadLetterQueueStats getDeadLetterStats() { return deadLetterStats; }
        
        @Override
        public String toString() {
            return "SystemStatus{" +
                    "started=" + started +
                    ", profile='" + profile + '\'' +
                    ", healthy=" + (healthStatus != null ? healthStatus.isHealthy() : "unknown") +
                    ", messagesProcessed=" + (metricsSummary != null ? metricsSummary.getMessagesProcessed() : 0) +
                    ", deadLetterMessages=" + (deadLetterStats != null ? deadLetterStats.getTotalMessages() : 0) +
                    '}';
        }
    }

    /**
     * Creates a temporary DataSource for migration and legacy components.
     * This method uses reflection to create a HikariCP DataSource if available (e.g., in test scenarios).
     *
     * @param connectionConfig The PostgreSQL connection configuration
     * @param poolConfig The connection pool configuration
     * @return A temporary DataSource for migration purposes
     * @throws RuntimeException if HikariCP is not available
     */
    private DataSource createTemporaryDataSourceForMigration(
            dev.mars.peegeeq.db.config.PgConnectionConfig connectionConfig,
            dev.mars.peegeeq.db.config.PgPoolConfig poolConfig) {
        try {
            // Use reflection to create HikariCP DataSource if available
            Class<?> hikariConfigClass = Class.forName("com.zaxxer.hikari.HikariConfig");
            Class<?> hikariDataSourceClass = Class.forName("com.zaxxer.hikari.HikariDataSource");

            Object config = hikariConfigClass.getDeclaredConstructor().newInstance();

            // Set connection properties using reflection
            hikariConfigClass.getMethod("setJdbcUrl", String.class).invoke(config, connectionConfig.getJdbcUrl());
            hikariConfigClass.getMethod("setUsername", String.class).invoke(config, connectionConfig.getUsername());
            hikariConfigClass.getMethod("setPassword", String.class).invoke(config, connectionConfig.getPassword());

            // Set pool properties using reflection
            hikariConfigClass.getMethod("setMinimumIdle", int.class).invoke(config, poolConfig.getMinimumIdle());
            hikariConfigClass.getMethod("setMaximumPoolSize", int.class).invoke(config, poolConfig.getMaximumPoolSize());
            hikariConfigClass.getMethod("setConnectionTimeout", long.class).invoke(config, poolConfig.getConnectionTimeout());
            hikariConfigClass.getMethod("setIdleTimeout", long.class).invoke(config, poolConfig.getIdleTimeout());
            hikariConfigClass.getMethod("setMaxLifetime", long.class).invoke(config, poolConfig.getMaxLifetime());
            hikariConfigClass.getMethod("setAutoCommit", boolean.class).invoke(config, poolConfig.isAutoCommit());

            // Set pool name for monitoring
            hikariConfigClass.getMethod("setPoolName", String.class).invoke(config, "PeeGeeQ-Migration-" + System.currentTimeMillis());

            // Create and return the DataSource
            Object dataSource = hikariDataSourceClass.getDeclaredConstructor(hikariConfigClass).newInstance(config);

            logger.info("Created temporary HikariCP DataSource for migration with host: {}, database: {}, autoCommit: {}",
                       connectionConfig.getHost(), connectionConfig.getDatabase(), poolConfig.isAutoCommit());

            return (DataSource) dataSource;

        } catch (ClassNotFoundException e) {
            throw new RuntimeException(
                "HikariCP not found on classpath. For migration support, add HikariCP as a dependency:\n" +
                "<dependency>\n" +
                "    <groupId>com.zaxxer</groupId>\n" +
                "    <artifactId>HikariCP</artifactId>\n" +
                "    <scope>test</scope> <!-- or compile for production use -->\n" +
                "</dependency>\n" +
                "Alternatively, disable migrations with peegeeq.migration.enabled=false", e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create temporary DataSource for migration: " + e.getMessage(), e);
        }
    }

}
