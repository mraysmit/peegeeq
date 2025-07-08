package dev.mars.peegeeq.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.db.client.PgClientFactory;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.deadletter.DeadLetterQueueManager;
import dev.mars.peegeeq.db.health.HealthCheckManager;
import dev.mars.peegeeq.db.metrics.PeeGeeQMetrics;
import dev.mars.peegeeq.db.migration.SchemaMigrationManager;
import dev.mars.peegeeq.db.resilience.BackpressureManager;
import dev.mars.peegeeq.db.resilience.CircuitBreakerManager;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Central management facade for PeeGeeQ system.
 * Coordinates all components including configuration, metrics, health checks, and resilience features.
 */
public class PeeGeeQManager implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(PeeGeeQManager.class);
    
    private final PeeGeeQConfiguration configuration;
    private final PgClientFactory clientFactory;
    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    
    // Core components
    private final SchemaMigrationManager migrationManager;
    private final PeeGeeQMetrics metrics;
    private final HealthCheckManager healthCheckManager;
    private final CircuitBreakerManager circuitBreakerManager;
    private final BackpressureManager backpressureManager;
    private final DeadLetterQueueManager deadLetterQueueManager;
    
    // Background services
    private final ScheduledExecutorService scheduledExecutor;
    private volatile boolean started = false;
    
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
            // Initialize client factory and data source
            this.clientFactory = new PgClientFactory();
            this.dataSource = clientFactory.getConnectionManager()
                .getOrCreateDataSource("peegeeq-main", 
                    configuration.getDatabaseConfig(), 
                    configuration.getPoolConfig());
            
            // Initialize core components
            this.migrationManager = new SchemaMigrationManager(dataSource);
            this.metrics = new PeeGeeQMetrics(dataSource, configuration.getMetricsConfig().getInstanceId());
            this.healthCheckManager = new HealthCheckManager(dataSource, 
                Duration.ofSeconds(30), Duration.ofSeconds(5));
            this.circuitBreakerManager = new CircuitBreakerManager(
                configuration.getCircuitBreakerConfig(), meterRegistry);
            this.backpressureManager = new BackpressureManager(50, Duration.ofSeconds(30));
            this.deadLetterQueueManager = new DeadLetterQueueManager(dataSource, objectMapper);
            
            // Initialize scheduled executor
            this.scheduledExecutor = new ScheduledThreadPoolExecutor(3, r -> {
                Thread t = new Thread(r, "peegeeq-manager");
                t.setDaemon(true);
                return t;
            });
            
            // Register metrics
            if (configuration.getMetricsConfig().isEnabled()) {
                metrics.bindTo(meterRegistry);
            }
            
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
            
            // Run database migrations if enabled
            if (configuration.getBoolean("peegeeq.migration.enabled", true)) {
                logger.info("Running database migrations...");
                int appliedMigrations = migrationManager.migrate();
                logger.info("Applied {} database migrations", appliedMigrations);
            }
            
            // Start health checks
            healthCheckManager.start();
            
            // Start metrics collection
            if (configuration.getMetricsConfig().isEnabled()) {
                startMetricsCollection();
            }
            
            // Start background cleanup tasks
            startBackgroundTasks();
            
            started = true;
            logger.info("PeeGeeQ Manager started successfully");
            
        } catch (Exception e) {
            logger.error("Failed to start PeeGeeQ Manager", e);
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
        
        try {
            // Stop health checks
            healthCheckManager.stop();
            
            // Stop scheduled tasks
            scheduledExecutor.shutdown();
            if (!scheduledExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduledExecutor.shutdownNow();
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
}
