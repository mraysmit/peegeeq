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
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.mars.peegeeq.api.QueueFactoryProvider;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.api.database.DatabaseService;


import dev.mars.peegeeq.db.client.PgClientFactory;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.deadletter.DeadLetterQueueManager;
import dev.mars.peegeeq.db.health.HealthCheckManager;
import dev.mars.peegeeq.db.metrics.PeeGeeQMetrics;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.db.recovery.StuckMessageRecoveryManager;
import dev.mars.peegeeq.db.resilience.BackpressureManager;
import dev.mars.peegeeq.db.resilience.CircuitBreakerManager;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

// Pure Vert.x 5.x reactive patterns

/**
 * Central management facade for PeeGeeQ system.
 *
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.1
 */
public class PeeGeeQManager implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(PeeGeeQManager.class);

    // Constants for configuration
    private static final String EVENT_BUS_ADDR = "peegeeq.lifecycle";
    private static final int DEFAULT_DLQ_RETENTION_DAYS = 30;
    private static final long DLQ_CLEANUP_INTERVAL_HOURS = 24;

    private final PeeGeeQConfiguration configuration;
    private final Vertx vertx;
    private final PgClientFactory clientFactory;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    // Core components
    private final Pool pool; // Single pool reference - no more re-fetching
    private final PeeGeeQMetrics metrics;
    private final HealthCheckManager healthCheckManager;
    private final CircuitBreakerManager circuitBreakerManager;
    private final BackpressureManager backpressureManager;
    private final DeadLetterQueueManager deadLetterQueueManager;
    private final StuckMessageRecoveryManager stuckMessageRecoveryManager;

    // Background services - using Vert.x timers instead of ScheduledExecutorService
    private long metricsTimerId = 0;
    private long dlqTimerId = 0;
    private long recoveryTimerId = 0;
    private volatile boolean started = false;

    // New provider interfaces
    private final PgDatabaseService databaseService;
    private final PgQueueFactoryProvider queueFactoryProvider;
    // Explicitly registered lifecycle hooks (no reflection)
    private final List<dev.mars.peegeeq.api.lifecycle.PeeGeeQCloseHook> closeHooks = new CopyOnWriteArrayList<>();


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
        this(configuration, meterRegistry, null);
    }

    /**
     * Constructor that allows external Vert.x instance.
     * In a Vert.x app you almost always want to reuse the application Vert.x and its context.
     */
    public PeeGeeQManager(PeeGeeQConfiguration configuration, MeterRegistry meterRegistry, Vertx vertx) {
        this.configuration = configuration;
        this.meterRegistry = meterRegistry;
        this.objectMapper = createDefaultObjectMapper();

        logger.info("Initializing PeeGeeQ Manager with profile: {}", configuration.getProfile());

        try {
            // Use provided Vert.x or create new one
            this.vertx = Objects.requireNonNullElseGet(vertx, Vertx::vertx);
            if (vertx != null) {
                logger.info("Using provided Vert.x instance");
            } else {
                logger.info("Created new Vert.x instance");
            }

            // Initialize client factory with Vert.x support
            this.clientFactory = new PgClientFactory(this.vertx);

            // Create the client to ensure configuration is stored in the factory
            clientFactory.createClient("peegeeq-main",
                configuration.getDatabaseConfig(),
                configuration.getPoolConfig());

            // All components now use reactive Vert.x 5.x patterns - no JDBC dependencies

            // Initialize single pool reference using the factory's getPool method
            // This avoids calling getOrCreateReactivePool directly as recommended in the markdown
            this.pool = clientFactory.getPool("peegeeq-main")
                .orElseThrow(() -> new IllegalStateException("Pool for 'peegeeq-main' not found after client creation"));

            // Initialize core components using the single pool reference
            this.metrics = new PeeGeeQMetrics(pool, configuration.getMetricsConfig().getInstanceId());

            // Initialize health check manager with configuration
            PeeGeeQConfiguration.HealthCheckConfig healthCheckConfig = configuration.getHealthCheckConfig();
            this.healthCheckManager = new HealthCheckManager(
                pool,
                healthCheckConfig.getInterval(),
                healthCheckConfig.getTimeout(),
                healthCheckConfig.isQueueChecksEnabled()
            );

            this.circuitBreakerManager = new CircuitBreakerManager(
                configuration.getCircuitBreakerConfig(), meterRegistry);
            // Make backpressure configurable instead of hardcoded values
            this.backpressureManager = new BackpressureManager(50, Duration.ofSeconds(30));
            this.deadLetterQueueManager = new DeadLetterQueueManager(pool, objectMapper);
            this.stuckMessageRecoveryManager = new StuckMessageRecoveryManager(
                pool,
                configuration.getQueueConfig().getRecoveryProcessingTimeout(),
                configuration.getQueueConfig().isRecoveryEnabled()
            );

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
    /**
     * Starts PeeGeeQ Manager using reactive event-driven lifecycle management.
     * This method orchestrates the startup sequence using Vert.x event bus for
     * proper separation of concerns and reactive patterns.
     */
    public Future<Void> startReactive() {
        if (started) {
            logger.warn("PeeGeeQ Manager is already started");
            return Future.succeededFuture();
        }

        logger.info("Starting PeeGeeQ Manager with reactive lifecycle events...");
        logger.debug("DB-DEBUG: PeeGeeQ Manager reactive start initiated with configuration profile: {}", configuration.getProfile());

        return Future.succeededFuture()
            .compose(v -> publishLifecycleEvent("database.validating"))
            .compose(v -> validateDatabaseConnectivity())
            .compose(v -> publishLifecycleEvent("database.ready"))
            .compose(v -> startAllComponents())
            .compose(v -> publishLifecycleEvent("components.started"))
            .compose(v -> {
                started = true;
                logger.info("PeeGeeQ Manager started successfully");
                logger.debug("DB-DEBUG: PeeGeeQ Manager reactive startup completed, all components initialized");
                return publishLifecycleEvent("manager.ready");
            })
            .recover(throwable -> {
                logger.error("Failed to start PeeGeeQ Manager reactively", throwable);
                logger.debug("DB-DEBUG: PeeGeeQ Manager reactive startup failed, error: {}", throwable.getMessage());
                return publishLifecycleEvent("manager.failed")
                    .compose(v -> Future.failedFuture(new RuntimeException("Failed to start PeeGeeQ Manager", throwable)));
            });
    }

    /**
     * Legacy synchronous start method for backward compatibility.
     * Delegates to reactive implementation and blocks for completion.
     *
     * WARNING: Do not call this on an event loop thread - it will deadlock!
     */
    public synchronized void start() {
        // Guard against calling blocking start() on event-loop thread
        if (Vertx.currentContext() != null && Vertx.currentContext().isEventLoopContext()) {
            throw new IllegalStateException("Do not call blocking start() on event-loop thread - use startReactive() instead");
        }

        try {
            startReactive()
                .toCompletionStage()
                .toCompletableFuture()
                .get(30, TimeUnit.SECONDS); // Reasonable timeout for startup
        } catch (Exception e) {
            throw new RuntimeException("Failed to start PeeGeeQ Manager", e);
        }
    }

    /**
     * Stops all PeeGeeQ services reactively.
     */
    public Future<Void> stopReactive() {
        if (!started) {
            return Future.succeededFuture();
        }

        logger.info("Stopping PeeGeeQ Manager...");
        logger.debug("DB-DEBUG: PeeGeeQ Manager shutdown initiated");

        // Stop background tasks first
        logger.debug("DB-DEBUG: Stopping background tasks");
        stopBackgroundTasks();
        logger.debug("DB-DEBUG: Background tasks stopped successfully");

        // Stop health checks (synchronous for now - HealthCheckManager doesn't have stopReactive)
        try {
            logger.debug("DB-DEBUG: Stopping health check manager");
            healthCheckManager.stop();
            logger.debug("DB-DEBUG: Health check manager stopped successfully");

            started = false;
            logger.info("PeeGeeQ Manager stopped successfully");
            logger.debug("DB-DEBUG: PeeGeeQ Manager shutdown completed");

            return Future.succeededFuture();
        } catch (Exception throwable) {
            logger.error("Error stopping PeeGeeQ Manager", throwable);
            started = false; // Mark as stopped even if there were errors
            return Future.failedFuture(throwable);
        }
    }

    /**
     * Legacy synchronous stop method for backward compatibility.
     * Delegates to reactive implementation and blocks for completion.
     */
    public synchronized void stop() {
        try {
            stopReactive()
                .toCompletionStage()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS); // Reasonable timeout for shutdown
        } catch (Exception e) {
            logger.error("Error during synchronous stop", e);
        }
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
     * Note: Schema validation is now handled reactively during startup.
     */
    public boolean validateConfiguration() {
        // Configuration validation is now handled during reactive schema initialization
        logger.info("Configuration validation delegated to reactive schema initialization");
        return true;
    }

    /**
     * Reactive close method - preferred for non-blocking shutdown.
     */
    public Future<Void> closeReactive() {
        logger.info("PeeGeeQManager.closeReactive() called - starting shutdown sequence");

        return stopReactive()
            .compose(v -> {
                // Close client factory
                try {
                    if (clientFactory != null) {
                        logger.info("Closing client factory");
                        clientFactory.close();
                        logger.info("Client factory closed successfully");
                    }
                } catch (Exception e) {
                    logger.error("Error closing client factory", e);
                }

                // Build chain for registered close hooks (no reflection)
                io.vertx.core.Future<Void> chain = io.vertx.core.Future.succeededFuture();
                if (!closeHooks.isEmpty()) {
                    logger.info("Running {} registered close hooks", closeHooks.size());
                    for (dev.mars.peegeeq.api.lifecycle.PeeGeeQCloseHook hook : closeHooks) {
                        chain = chain.compose(ignored -> hook.closeReactive()
                            .onSuccess(v2 -> logger.debug("Close hook '{}' completed", hook.name()))
                            .onFailure(e -> logger.warn("Close hook '{}' failed: {}", hook.name(), e.getMessage()))
                        );
                    }
                } else {
                    logger.debug("No registered close hooks to run");
                }

                // After hooks, close Vert.x instance reactively
                return chain.compose(ignored -> {
                    if (vertx != null) {
                        logger.info("Closing Vert.x instance");
                        return vertx.close()
                            .onSuccess(v2 -> logger.info("Vert.x instance closed successfully"))
                            .onFailure(e -> logger.error("Error closing Vert.x instance", e));
                    } else {
                        logger.warn("Vert.x instance is null, cannot close");
                        return Future.succeededFuture();
                    }
                });
            })
            .onComplete(ar -> logger.info("PeeGeeQManager.closeReactive() completed"));
    }

    @Override
    public void close() {
        // Best effort: prefer non-blocking, but keep AutoCloseable compatibility
        try {
            closeReactive().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.error("Error during reactive close, falling back to synchronous cleanup", e);

            // Fallback synchronous cleanup
            try {
                if (clientFactory != null) {
                    clientFactory.close();
                }
            } catch (Exception ex) {
                logger.error("Error closing client factory", ex);
            }

            try {
                if (vertx != null) {
                    vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
                }
            } catch (Exception ex) {
                logger.error("Error closing Vert.x instance", ex);
            }
        }
    }



    // Getters for components
    public PeeGeeQConfiguration getConfiguration() { return configuration; }
    public PgClientFactory getClientFactory() { return clientFactory; }
    public ObjectMapper getObjectMapper() { return objectMapper; }
    public MeterRegistry getMeterRegistry() { return meterRegistry; }
    public PeeGeeQMetrics getMetrics() { return metrics; }
    public HealthCheckManager getHealthCheckManager() { return healthCheckManager; }
    public CircuitBreakerManager getCircuitBreakerManager() { return circuitBreakerManager; }
    public BackpressureManager getBackpressureManager() { return backpressureManager; }
    public DeadLetterQueueManager getDeadLetterQueueManager() { return deadLetterQueueManager; }
    public StuckMessageRecoveryManager getStuckMessageRecoveryManager() { return stuckMessageRecoveryManager; }

    /**
     * Gets the Vert.x instance used by this PeeGeeQ manager.
     * Components should use this shared instance instead of creating their own.
     *
     * @return The shared Vert.x instance
     */
    public Vertx getVertx() { return vertx; }

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
     * Allows modules to register a close hook that will be executed during shutdown.
     */
    public void registerCloseHook(dev.mars.peegeeq.api.lifecycle.PeeGeeQCloseHook hook) {
        if (hook != null) {
            closeHooks.add(hook);
            logger.debug("Registered close hook: {}", hook.name());
        }
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
     * Creates a default ObjectMapper with JSR310 support for Java 8 time types and CloudEvents support.
     */
    private static ObjectMapper createDefaultObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());

        // Disable writing dates as timestamps to avoid epoch millis surprises
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // Add CloudEvents Jackson module support if available on classpath
        try {
            Class<?> jsonFormatClass = Class.forName("io.cloudevents.jackson.JsonFormat");
            Object cloudEventModule = jsonFormatClass.getMethod("getCloudEventJacksonModule").invoke(null);
            if (cloudEventModule instanceof com.fasterxml.jackson.databind.Module) {
                mapper.registerModule((com.fasterxml.jackson.databind.Module) cloudEventModule);
                logger.trace("CloudEvents Jackson module registered successfully"); // Use trace instead of debug
            }
        } catch (Exception e) {
            logger.trace("CloudEvents Jackson module not available on classpath, skipping registration: {}", e.getMessage());
        }

        return mapper;
    }

    // ========================================
    // Event-Driven Lifecycle Management
    // ========================================

    /**
     * Publishes a lifecycle event to the Vert.x event bus for reactive component coordination.
     * This enables proper event-driven startup orchestration.
     */
    private Future<Void> publishLifecycleEvent(String event) {
        JsonObject eventData = new JsonObject()
            .put("event", event)
            .put("timestamp", Instant.now().toString())
            .put("manager", "PeeGeeQManager")
            .put("profile", configuration.getProfile());

        vertx.eventBus().publish(EVENT_BUS_ADDR, eventData);
        logger.debug("Published lifecycle event: {}", event);
        return Future.succeededFuture();
    }

    /**
     * Validates database connectivity before starting components.
     * This ensures the database is accessible before health checks and other components start.
     */
    private Future<Void> validateDatabaseConnectivity() {
        logger.info("Validating database connectivity...");

        return pool.withConnection(connection ->
            connection.preparedQuery("SELECT 1").execute()
                .map(rowSet -> {
                    logger.info("Database connectivity validated successfully");
                    return (Void) null;
                })
        ).recover(throwable -> {
            logger.error("Database connectivity validation failed: {}", throwable.getMessage());
            return Future.failedFuture(new RuntimeException("Database startup validation failed", throwable));
        });
    }

    /**
     * Starts all components reactively after database validation.
     * Components can subscribe to lifecycle events for coordinated startup.
     */
    private Future<Void> startAllComponents() {
        logger.info("Starting all PeeGeeQ components...");

        return Future.all(
            startHealthChecksReactive(),
            startMetricsCollectionReactive(),
            startBackgroundTasksReactive()
        ).compose(compositeFuture -> {
            logger.info("All PeeGeeQ components started successfully");
            return Future.succeededFuture();
        });
    }

    /**
     * Starts health checks reactively without blocking operations.
     */
    private Future<Void> startHealthChecksReactive() {
        logger.debug("DB-DEBUG: Starting health check manager reactively");
        return healthCheckManager.startReactive()
            .onSuccess(v -> logger.debug("DB-DEBUG: Health check manager started successfully"))
            .onFailure(throwable -> logger.error("DB-DEBUG: Failed to start health check manager", throwable));
    }

    /**
     * Starts metrics collection reactively using Vert.x timers.
     */
    private Future<Void> startMetricsCollectionReactive() {
        if (!configuration.getMetricsConfig().isEnabled()) {
            logger.debug("DB-DEBUG: Metrics collection disabled by configuration");
            return Future.succeededFuture();
        }

        logger.debug("DB-DEBUG: Starting metrics collection reactively");

        long intervalMs = configuration.getMetricsConfig().getReportingInterval().toMillis();
        metricsTimerId = vertx.setPeriodic(intervalMs, id -> {
            vertx.executeBlocking(() -> {
                try {
                    metrics.persistMetrics(meterRegistry);
                    return null;
                } catch (Exception e) {
                    logger.warn("Failed to persist metrics", e);
                    return null; // don't fail periodic timer
                }
            });
        });

        logger.info("Started metrics collection every {}", configuration.getMetricsConfig().getReportingInterval());
        logger.debug("DB-DEBUG: Metrics collection started successfully");
        return Future.succeededFuture();
    }

    /**
     * Starts background tasks reactively using Vert.x timers.
     */
    private Future<Void> startBackgroundTasksReactive() {
        logger.debug("DB-DEBUG: Starting background cleanup tasks reactively");

        // Dead letter queue cleanup every 24 hours
        dlqTimerId = vertx.setPeriodic(TimeUnit.HOURS.toMillis(DLQ_CLEANUP_INTERVAL_HOURS), id -> {
            vertx.executeBlocking(() -> {
                try {
                    int cleaned = deadLetterQueueManager.cleanupOldMessages(DEFAULT_DLQ_RETENTION_DAYS);
                    if (cleaned > 0) {
                        logger.info("Cleaned up {} old dead letter messages (retention: {} days)",
                            cleaned, DEFAULT_DLQ_RETENTION_DAYS);
                    }
                    return null;
                } catch (Exception e) {
                    logger.warn("Failed to cleanup old dead letter messages", e);
                    return null; // don't fail periodic timer
                }
            });
        });

        // Stuck message recovery
        if (stuckMessageRecoveryManager != null) {
            long recoveryMs = configuration.getQueueConfig().getRecoveryCheckInterval().toMillis();
            recoveryTimerId = vertx.setPeriodic(recoveryMs, id -> {
                vertx.executeBlocking(() -> {
                    try {
                        int recovered = stuckMessageRecoveryManager.recoverStuckMessages();
                        if (recovered > 0) {
                            logger.info("Recovered {} stuck messages from PROCESSING state", recovered);
                        }
                        return null;
                    } catch (Exception e) {
                        logger.warn("Failed to recover stuck messages", e);
                        return null; // don't fail periodic timer
                    }
                });
            });
        }

        logger.debug("DB-DEBUG: Background cleanup tasks started successfully");
        return Future.succeededFuture();
    }

    /**
     * Stops all background tasks by canceling Vert.x timers.
     */
    private void stopBackgroundTasks() {
        if (dlqTimerId != 0) {
            vertx.cancelTimer(dlqTimerId);
            dlqTimerId = 0;
        }
        if (recoveryTimerId != 0) {
            vertx.cancelTimer(recoveryTimerId);
            recoveryTimerId = 0;
        }
        if (metricsTimerId != 0) {
            vertx.cancelTimer(metricsTimerId);
            metricsTimerId = 0;
        }
        logger.debug("DB-DEBUG: All background tasks stopped");
    }

}
