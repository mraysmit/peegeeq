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
import io.vertx.core.WorkerExecutor;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
    private final WorkerExecutor workerExecutor; // Dedicated worker pool for blocking tasks
    private final boolean vertxOwnedByManager; // Track if we created the Vertx instance
    private final PgClientFactory clientFactory;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final boolean meterRegistryOwnedByManager;

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
        this(configuration, new SimpleMeterRegistry(), null, true);
    }

    public PeeGeeQManager(PeeGeeQConfiguration configuration, MeterRegistry meterRegistry) {
        this(configuration, meterRegistry, null, false);
    }

    /**
     * Constructor that allows external Vert.x instance.
     * In a Vert.x app you almost always want to reuse the application Vert.x and its context.
     */
    public PeeGeeQManager(PeeGeeQConfiguration configuration, MeterRegistry meterRegistry, Vertx vertx) {
        this(configuration, meterRegistry, vertx, false);
    }

    private PeeGeeQManager(PeeGeeQConfiguration configuration, MeterRegistry meterRegistry, Vertx vertx, boolean meterRegistryOwnedByManager) {
        this.configuration = configuration;
        this.meterRegistry = meterRegistry;
        this.meterRegistryOwnedByManager = meterRegistryOwnedByManager;
        this.objectMapper = createDefaultObjectMapper();

        logger.info("Initializing PeeGeeQ Manager with profile: {}", configuration.getProfile());

        try {
            // Use provided Vert.x or create new one
            if (vertx != null) {
                this.vertx = vertx;
                this.vertxOwnedByManager = false; // External Vertx - don't close it
                logger.info("Using provided Vert.x instance (external ownership)");
            } else {
                this.vertx = Vertx.vertx();
                this.vertxOwnedByManager = true; // We created it - we must close it
                logger.info("Created new Vert.x instance (manager ownership)");
            }

            // Initialize dedicated worker executor
            // Use a named pool so we can close it explicitly and avoid leaks
            this.workerExecutor = this.vertx.createSharedWorkerExecutor("peegeeq-worker-pool", 20);

            // Initialize client factory with Vert.x support and notice handling
            this.clientFactory = new PgClientFactory(this.vertx, this.meterRegistry, configuration.getNoticeHandlerConfig());

            // Log and create the client to ensure configuration is stored in the factory
            var dbConfig = configuration.getDatabaseConfig();
            logger.info("DB-DEBUG: Creating client with host={}, port={}, db={}, user={}",
                        dbConfig.getHost(), dbConfig.getPort(), dbConfig.getDatabase(), dbConfig.getUsername());

            clientFactory.createClient(PeeGeeQDefaults.DEFAULT_POOL_ID,
                dbConfig,
                configuration.getPoolConfig());

            // All components now use reactive Vert.x 5.x patterns - no JDBC dependencies

            // Initialize single pool reference using the factory's getPool method
            // This avoids calling getOrCreateReactivePool directly as recommended in the markdown
            // Pass null to use the default pool (resolves to DEFAULT_POOL_ID internally)
            this.pool = clientFactory.getPool(null)
                .orElseThrow(() -> new IllegalStateException("Default pool not found after client creation"));

            // Initialize core components using the single pool reference
            this.metrics = new PeeGeeQMetrics(pool, configuration.getMetricsConfig().getInstanceId());

            // Initialize health check manager with configuration
            PeeGeeQConfiguration.HealthCheckConfig healthCheckConfig = configuration.getHealthCheckConfig();
            this.healthCheckManager = new HealthCheckManager(
                pool,
                healthCheckConfig.getInterval(),
                healthCheckConfig.getTimeout(),
                healthCheckConfig.isQueueChecksEnabled(),
                configuration.getDatabaseConfig().getSchema()
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
                
                // Stop background tasks and health checks on failure to prevent leaks
                stopBackgroundTasks();
                
                return healthCheckManager.stopReactive()
                    .recover(e -> Future.succeededFuture()) // Ignore errors during stop
                    .compose(v -> publishLifecycleEvent("manager.failed"))
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

        // Stop health checks asynchronously to avoid blocking event loop
        logger.debug("DB-DEBUG: Stopping health check manager");
        return healthCheckManager.stopReactive()
            .compose(v -> {
                logger.debug("DB-DEBUG: Health check manager stopped successfully");
                started = false;
                logger.info("PeeGeeQ Manager stopped successfully");
                logger.debug("DB-DEBUG: PeeGeeQ Manager shutdown completed");
                return Future.<Void>succeededFuture();
            })
            .recover(throwable -> {
                logger.error("Error stopping PeeGeeQ Manager", throwable);
                started = false; // Mark as stopped even if there were errors
                return Future.failedFuture(throwable);
            });
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
            healthCheckManager.getOverallHealthInternal(),
            metrics.getSummary(),
            backpressureManager.getMetrics(),
            deadLetterQueueManager.getStatisticsInternal()
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

        // 1. Stop reactive components (background tasks, health checks)
        return stopReactive()
            .recover(e -> {
                logger.warn("stopReactive failed during close, continuing cleanup: {}", e.getMessage());
                return Future.succeededFuture();
            })
            .compose(v -> {
                // 2. Run registered close hooks
                io.vertx.core.Future<Void> chain = io.vertx.core.Future.succeededFuture();
                if (!closeHooks.isEmpty()) {
                    logger.info("Running {} registered close hooks", closeHooks.size());
                    for (dev.mars.peegeeq.api.lifecycle.PeeGeeQCloseHook hook : closeHooks) {
                        chain = chain.compose(ignored -> hook.closeReactive()
                            .onSuccess(v2 -> logger.debug("Close hook '{}' completed", hook.name()))
                            .onFailure(e -> logger.warn("Close hook '{}' failed: {}", hook.name(), e.getMessage()))
                            .recover(e -> Future.succeededFuture()) // Continue chain on error
                        );
                    }
                } else {
                    logger.debug("No registered close hooks to run");
                }
                return chain;
            })
            .compose(v -> {
                // 3. Cancel all background tasks (safety check)
                if (metricsTimerId != 0) {
                    vertx.cancelTimer(metricsTimerId);
                    metricsTimerId = 0;
                }
                if (dlqTimerId != 0) {
                    vertx.cancelTimer(dlqTimerId);
                    dlqTimerId = 0;
                }
                if (recoveryTimerId != 0) {
                    vertx.cancelTimer(recoveryTimerId);
                    recoveryTimerId = 0;
                }

                // 4. Close worker executor (finish pending tasks)
                return workerExecutor.close()
                    .recover(e -> {
                        logger.warn("Failed to close worker executor", e);
                        return Future.succeededFuture();
                    });
            })
            .compose(v -> {
                // 5. Close client factory (DB pools) - AFTER workers are done
                if (clientFactory != null) {
                    logger.info("Closing client factory");
                    return clientFactory.closeAsync()
                        .onSuccess(v2 -> logger.info("Client factory closed successfully"))
                        .onFailure(e -> logger.warn("Error closing client factory: {}", e.getMessage()))
                        .recover(e -> Future.succeededFuture());
                }
                return Future.succeededFuture();
            })
            .compose(v -> {
                // 6. Close manager-owned MeterRegistry
                if (meterRegistryOwnedByManager && meterRegistry instanceof AutoCloseable ac) {
                    try {
                        ac.close();
                        logger.info("Closed manager-owned MeterRegistry");
                    } catch (Exception e) {
                        logger.warn("Failed to close MeterRegistry", e);
                    }
                }
                return Future.succeededFuture();
            })
            .compose(v -> {
                logger.info("PeeGeeQManager.closeReactive() cleanup completed");
                
                // 7. Close Vert.x instance (if owned)
                if (vertx != null && vertxOwnedByManager) {
                    logger.info("Closing Vert.x instance (manager-owned)");
                    // Attempt to close gracefully, but handle the inevitable "executor terminated" error
                    return vertx.close()
                        .onSuccess(v2 -> logger.info("Vert.x instance closed successfully"))
                        .recover(e -> {
                            if (e instanceof java.util.concurrent.RejectedExecutionException || 
                                (e.getCause() != null && e.getCause() instanceof java.util.concurrent.RejectedExecutionException)) {
                                logger.debug("Vert.x event executor terminated during close (expected); treating as closed.");
                                return Future.succeededFuture();
                            }
                            logger.warn("Error closing Vert.x instance", e);
                            return Future.succeededFuture();
                        });
                } else if (vertx != null) {
                    logger.info("Skipping Vert.x close (external ownership)");
                    return Future.succeededFuture();
                } else {
                    return Future.succeededFuture();
                }
            })
            .recover(e -> {
                if (e instanceof java.util.concurrent.RejectedExecutionException || 
                    (e.getCause() != null && e.getCause() instanceof java.util.concurrent.RejectedExecutionException)) {
                    logger.warn("Vert.x event executor already terminated during close; ignoring and treating as closed.");
                    return Future.succeededFuture();
                }
                return Future.failedFuture(e);
            });
    }

    @Override
    public void close() {
        // Guard against calling blocking close() on event-loop thread
        if (Vertx.currentContext() != null && Vertx.currentContext().isEventLoopContext()) {
            logger.warn("Blocking close() called on event loop thread! This will deadlock. Triggering async close and returning immediately.");
            closeReactive(); // Fire and forget
            return;
        }

        // Fire-and-forget close to avoid blocking tests or causing timeouts.
        // Callers who need to wait for completion should use closeReactive().
        logger.info("Initiating async close from AutoCloseable.close()");
        closeReactive();
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

    /**
     * Gets the default connection pool used by this PeeGeeQ manager.
     * Components should use this shared pool instead of creating their own.
     *
     * @return The shared Pool instance
     */
    public Pool getPool() { return pool; }

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
     * Creates a SubscriptionService for managing consumer group subscriptions.
     * This returns the API interface type to maintain proper layering.
     *
     * @return A new SubscriptionService instance
     */
    public dev.mars.peegeeq.api.subscription.SubscriptionService createSubscriptionService() {
        // Pass null to use the default pool
        return new dev.mars.peegeeq.db.subscription.SubscriptionManager(
            clientFactory.getConnectionManager(),
            null
        );
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
            metrics.persistMetricsReactive(meterRegistry)
                .onFailure(e -> logger.warn("Failed to persist metrics", e));
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
            deadLetterQueueManager.cleanupOldMessagesReactive(DEFAULT_DLQ_RETENTION_DAYS)
                .onSuccess(cleaned -> {
                    if (cleaned > 0) {
                        logger.info("Cleaned up {} old dead letter messages (retention: {} days)",
                            cleaned, DEFAULT_DLQ_RETENTION_DAYS);
                    }
                })
                .onFailure(e -> logger.warn("Failed to cleanup old dead letter messages", e));
        });

        // Stuck message recovery
        if (stuckMessageRecoveryManager != null) {
            long recoveryMs = configuration.getQueueConfig().getRecoveryCheckInterval().toMillis();
            recoveryTimerId = vertx.setPeriodic(recoveryMs, id -> {
                stuckMessageRecoveryManager.recoverStuckMessagesReactive()
                    .onSuccess(recovered -> {
                        if (recovered > 0) {
                            logger.info("Recovered {} stuck messages from PROCESSING state", recovered);
                        }
                    })
                    .onFailure(e -> logger.warn("Failed to recover stuck messages", e));
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
