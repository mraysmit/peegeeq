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
import java.util.Set;

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
    private final DataSource dataSource; // Only used by SchemaMigrationManager - all other components use reactive patterns
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

            // Create DataSource for SchemaMigrationManager (the only remaining component that requires JDBC)
            // All other components now use reactive Vert.x 5.x patterns
            DataSource tempDataSource = createTemporaryDataSourceForMigration(
                configuration.getDatabaseConfig(), configuration.getPoolConfig());

            // Store the DataSource for SchemaMigrationManager and legacy test compatibility
            this.dataSource = tempDataSource;

            // Initialize core components
            this.migrationManager = new SchemaMigrationManager(tempDataSource);
            // Use reactive PeeGeeQMetrics with the reactive pool instead of DataSource
            this.metrics = new PeeGeeQMetrics(clientFactory.getConnectionManager().getOrCreateReactivePool("peegeeq-main",
                configuration.getDatabaseConfig(), configuration.getPoolConfig()), configuration.getMetricsConfig().getInstanceId());
            // Use reactive HealthCheckManager with the reactive pool instead of DataSource
            this.healthCheckManager = new HealthCheckManager(clientFactory.getConnectionManager().getOrCreateReactivePool("peegeeq-main",
                configuration.getDatabaseConfig(), configuration.getPoolConfig()), Duration.ofSeconds(30), Duration.ofSeconds(5));
            this.circuitBreakerManager = new CircuitBreakerManager(
                configuration.getCircuitBreakerConfig(), meterRegistry);
            this.backpressureManager = new BackpressureManager(50, Duration.ofSeconds(30));
            // Use reactive DeadLetterQueueManager with the reactive pool instead of DataSource
            this.deadLetterQueueManager = new DeadLetterQueueManager(clientFactory.getConnectionManager().getOrCreateReactivePool("peegeeq-main",
                configuration.getDatabaseConfig(), configuration.getPoolConfig()), objectMapper);
            // Use reactive StuckMessageRecoveryManager with the reactive pool instead of DataSource
            this.stuckMessageRecoveryManager = new StuckMessageRecoveryManager(
                clientFactory.getConnectionManager().getOrCreateReactivePool("peegeeq-main",
                    configuration.getDatabaseConfig(), configuration.getPoolConfig()),
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
        logger.info("PeeGeeQManager.close() called - starting shutdown sequence");

        stop();

        try {
            if (clientFactory != null) {
                logger.info("Closing client factory");
                clientFactory.close();
                logger.info("Client factory closed successfully");
            }
        } catch (Exception e) {
            logger.error("Error closing client factory", e);
        }

        // CRITICAL: Close migration DataSource to stop HikariCP housekeeper thread
        try {
            if (dataSource != null) {
                logger.info("Closing migration DataSource");
                // Use reflection to close HikariDataSource if it's a HikariDataSource
                if (dataSource.getClass().getName().equals("com.zaxxer.hikari.HikariDataSource")) {


                    // First try to shutdown the pool gracefully
                    try {
                        java.lang.reflect.Method shutdownMethod = dataSource.getClass().getMethod("shutdown");
                        shutdownMethod.invoke(dataSource);
                        logger.info("Migration DataSource shutdown initiated");
                    } catch (NoSuchMethodException e) {
                        // shutdown() method not available, proceed with close()
                        logger.debug("shutdown() method not available, using close()");
                    }

                    java.lang.reflect.Method closeMethod = dataSource.getClass().getMethod("close");
                    closeMethod.invoke(dataSource);
                    logger.info("Migration DataSource closed successfully");

                    // Wait for HikariCP threads to actually terminate by checking thread names
                    int maxWaitTime = 10000; // 10 seconds max wait
                    int waitInterval = 500; // Check every 500ms
                    int totalWaitTime = 0;

                    while (totalWaitTime < maxWaitTime) {
                        boolean hikariThreadsFound = false;
                        Set<Thread> allThreads = Thread.getAllStackTraces().keySet();

                        for (Thread thread : allThreads) {
                            String threadName = thread.getName();
                            if (threadName.contains("HikariPool") &&
                                (threadName.contains("housekeeper") ||
                                 threadName.contains("connection adder") ||
                                 threadName.contains("connection closer"))) {
                                hikariThreadsFound = true;
                                break;
                            }
                        }

                        if (!hikariThreadsFound) {
                            logger.info("All HikariCP threads terminated after {}ms", totalWaitTime);
                            break;
                        }

                        Thread.sleep(waitInterval);
                        totalWaitTime += waitInterval;
                    }

                    if (totalWaitTime >= maxWaitTime) {
                        logger.warn("HikariCP threads may still be running after {}ms wait", maxWaitTime);
                    }
                } else if (dataSource instanceof AutoCloseable) {
                    // Try to close as AutoCloseable
                    ((AutoCloseable) dataSource).close();
                    logger.info("Migration DataSource closed successfully");
                }
            }
        } catch (Exception e) {
            logger.error("Error closing migration DataSource", e);
        }

        // CRITICAL: Close shared Vert.x instances from outbox and bi-temporal modules
        // These are static shared instances that need explicit cleanup
        try {
            logger.info("Closing shared Vert.x instances from outbox and bi-temporal modules");

            // Use reflection to avoid compile-time dependencies on optional modules
            closeSharedVertxIfPresent("dev.mars.peegeeq.outbox.OutboxProducer");
            closeSharedVertxIfPresent("dev.mars.peegeeq.outbox.OutboxConsumer");
            closeSharedVertxIfPresent("dev.mars.peegeeq.bitemporal.VertxPoolAdapter");

            logger.info("Shared Vert.x instances cleanup completed");
        } catch (Exception e) {
            logger.warn("Error closing shared Vert.x instances: {}", e.getMessage());
        }

        // CRITICAL: Close Vert.x to stop all event loop threads
        try {
            if (vertx != null) {
                logger.info("Closing Vert.x instance");
                vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
                logger.info("Vert.x instance closed successfully");

                // Wait for Vert.x threads to fully terminate
                Thread.sleep(1000);
            } else {
                logger.warn("Vert.x instance is null, cannot close");
            }
        } catch (Exception e) {
            logger.error("Error closing Vert.x instance", e);
        }

        logger.info("PeeGeeQManager.close() completed");
    }

    /**
     * Helper method to close shared Vert.x instances using reflection.
     * This avoids compile-time dependencies on optional modules.
     */
    private void closeSharedVertxIfPresent(String className) {
        try {
            Class<?> clazz = Class.forName(className);
            java.lang.reflect.Method method = clazz.getMethod("closeSharedVertx");
            method.invoke(null);
            logger.debug("Closed shared Vert.x for: {}", className);
        } catch (ClassNotFoundException e) {
            // Module not present, skip
            logger.debug("Module not present: {}", className);
        } catch (NoSuchMethodException e) {
            logger.warn("closeSharedVertx method not found in: {}", className);
        } catch (Exception e) {
            logger.warn("Error closing shared Vert.x for {}: {}", className, e.getMessage());
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
     * Creates a DataSource for SchemaMigrationManager.
     * This method uses reflection to create a HikariCP DataSource if available (e.g., in test scenarios).
     * All other components use reactive Vert.x 5.x patterns and do not require JDBC DataSource.
     *
     * @param connectionConfig The PostgreSQL connection configuration
     * @param poolConfig The connection pool configuration
     * @return A DataSource for SchemaMigrationManager
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

            // Set pool properties using reflection - use minimal pool size for migrations
            // Migrations only need 1 connection, minimize background threads
            hikariConfigClass.getMethod("setMinimumIdle", int.class).invoke(config, 0); // No idle connections
            hikariConfigClass.getMethod("setMaximumPoolSize", int.class).invoke(config, 1); // Only 1 connection
            hikariConfigClass.getMethod("setConnectionTimeout", long.class).invoke(config, poolConfig.getConnectionTimeout());
            hikariConfigClass.getMethod("setIdleTimeout", long.class).invoke(config, 5000L); // 5 seconds for faster cleanup
            hikariConfigClass.getMethod("setMaxLifetime", long.class).invoke(config, 15000L); // 15 seconds for faster cleanup
            hikariConfigClass.getMethod("setAutoCommit", boolean.class).invoke(config, poolConfig.isAutoCommit());

            // Minimize background threads for faster shutdown
            hikariConfigClass.getMethod("setLeakDetectionThreshold", long.class).invoke(config, 0L); // Disable leak detection
            hikariConfigClass.getMethod("setInitializationFailTimeout", long.class).invoke(config, 1L); // Fast fail

            // Set pool name for monitoring
            hikariConfigClass.getMethod("setPoolName", String.class).invoke(config, "PeeGeeQ-Migration-" + System.currentTimeMillis());

            // Create and return the DataSource
            Object dataSource = hikariDataSourceClass.getDeclaredConstructor(hikariConfigClass).newInstance(config);

            logger.info("Created HikariCP DataSource for SchemaMigrationManager with host: {}, database: {}, autoCommit: {}",
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
            throw new RuntimeException("Failed to create DataSource for SchemaMigrationManager: " + e.getMessage(), e);
        }
    }

}
