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


import dev.mars.peegeeq.db.cleanup.DeadConsumerDetectionJob;
import dev.mars.peegeeq.db.cleanup.DeadConsumerDetector;
import dev.mars.peegeeq.db.cleanup.DeadConsumerGroupCleanup;
import dev.mars.peegeeq.db.client.PgClientFactory;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.consumer.ConsumerGroupRetryJob;
import dev.mars.peegeeq.db.consumer.ConsumerGroupRetryService;
import dev.mars.peegeeq.db.deadletter.DeadLetterQueueManager;
import dev.mars.peegeeq.db.health.HealthCheckManager;
import dev.mars.peegeeq.db.metrics.PeeGeeQMetrics;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.db.recovery.StuckMessageRecoveryManager;
import dev.mars.peegeeq.db.resilience.BackpressureManager;
import dev.mars.peegeeq.db.resilience.CircuitBreakerManager;
import io.cloudevents.jackson.JsonFormat;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;
import io.vertx.core.Context;
import io.vertx.core.eventbus.DeliveryOptions;
import dev.mars.peegeeq.api.tracing.TraceCtx;
import dev.mars.peegeeq.api.tracing.TraceContextUtil;
import dev.mars.peegeeq.api.tracing.AsyncTraceUtils;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

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
    private long depthCacheTimerId = 0;
    private long dlqTimerId = 0;
    private long recoveryTimerId = 0;
    private DeadConsumerDetectionJob deadConsumerDetectionJob;
    private ConsumerGroupRetryJob consumerGroupRetryJob;

    // Consecutive failure counters for background timers escalate to ERROR after threshold
    private static final long TIMER_FAILURE_ESCALATION_THRESHOLD = 3;
    private final java.util.concurrent.atomic.AtomicLong persistMetricsFailures = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong depthCacheFailures = new java.util.concurrent.atomic.AtomicLong(0);

    // Overlap guards prevent a new tick firing while the previous one is still in-flight
    private final java.util.concurrent.atomic.AtomicBoolean persistMetricsRunning = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean depthCacheRunning = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicLong dlqCleanupFailures = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong stuckMessageFailures = new java.util.concurrent.atomic.AtomicLong(0);

    private volatile boolean started = false;
    // Shutdown coordination prevents database operations during closeReactive()
    private volatile boolean closing = false;
    private volatile Future<Void> startFuture = null;
    private volatile Future<Void> closeFuture = null;

    // Cached subscription service created once, reused per request
    private volatile dev.mars.peegeeq.api.subscription.SubscriptionService cachedSubscriptionService;

    // New provider interfaces
    private final PgDatabaseService databaseService;
    private final PgQueueFactoryProvider queueFactoryProvider;
    // Explicitly registered lifecycle hooks (no reflection)
    private final List<dev.mars.peegeeq.api.lifecycle.PeeGeeQCloseHook> closeHooks = new CopyOnWriteArrayList<>();


    public PeeGeeQManager() {
        this(new PeeGeeQConfiguration(PeeGeeQConfiguration.getActiveProfile(), new java.util.Properties()));
    }

    public PeeGeeQManager(String profile) {
        this(new PeeGeeQConfiguration(profile, new java.util.Properties()));
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
        // Initialize system-level trace context for main thread logs (startup sequence)
        if (TraceContextUtil.captureTraceContext() == null) {
            // Set MDC keys directly no scope to leak (C2 remediation)
            TraceCtx startupTrace = TraceContextUtil.parseOrCreate(null);
            MDC.put(TraceContextUtil.MDC_TRACE_ID, startupTrace.traceId());
            MDC.put(TraceContextUtil.MDC_SPAN_ID, startupTrace.spanId());
        }

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
            logger.info(": Creating client with host={}, port={}, db={}, user={}",
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

            this.circuitBreakerManager = new CircuitBreakerManager(
                configuration.getCircuitBreakerConfig(), meterRegistry);

            // Initialize health check manager with configuration
            PeeGeeQConfiguration.HealthCheckConfig healthCheckConfig = configuration.getHealthCheckConfig();
            this.healthCheckManager = new HealthCheckManager(
                pool,
                this.vertx,
                healthCheckConfig.getInterval(),
                healthCheckConfig.getTimeout(),
                healthCheckConfig.isQueueChecksEnabled(),
                configuration.getDatabaseConfig().getSchema(),
                this.circuitBreakerManager
            );

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
     * Starts PeeGeeQ Manager using reactive event-driven lifecycle management.
     * This method orchestrates the startup sequence using Vert.x event bus for
     * proper separation of concerns and reactive patterns.
     */
    public Future<Void> start() {
        if (started) {
            logger.warn("PeeGeeQ Manager is already started");
            return Future.succeededFuture();
        }
        if (closing) {
            logger.warn("PeeGeeQ Manager is closing, cannot start");
            return Future.failedFuture(new IllegalStateException("PeeGeeQ Manager is closing"));
        }

        logger.info("Starting PeeGeeQ Manager with reactive lifecycle events...");
        logger.debug(": PeeGeeQ Manager reactive start initiated with configuration profile: {}", configuration.getProfile());

        Future<Void> future = Future.succeededFuture()
            .compose(v -> publishLifecycleEvent("database.validating"))
            .compose(v -> validateDatabaseConnectivity())
            .compose(v -> validateRequiredTables())
            .compose(v -> publishLifecycleEvent("database.ready"))
            .compose(v -> startAllComponents())
            .compose(v -> publishLifecycleEvent("components.started"))
            .compose(v -> {
                started = true;
                logger.info("PeeGeeQ Manager started successfully");
                logger.debug(": PeeGeeQ Manager reactive startup completed, all components initialized");
                return publishLifecycleEvent("manager.ready");
            })
            .onFailure(throwable -> {
                logger.error("Failed to start PeeGeeQ Manager reactively", throwable);
                logger.debug(": PeeGeeQ Manager reactive startup failed, error: {}", throwable.getMessage());
            })
            // On failure ONLY: stop background tasks and health checks to prevent leaks,
            // publish manager.failed, and propagate a wrapped RuntimeException.
            // .transform() lets us inspect the AsyncResult and branch on failure without
            // using .recover() (which is forbidden per the recover-removal initiative).
            .transform(ar -> {
                if (ar.succeeded()) {
                    return Future.<Void>succeededFuture();
                }
                Throwable original = ar.cause();
                return stopBackgroundTasks()
                    .eventually(() -> healthCheckManager.stop()
                        .onFailure(e -> logger.warn("Error stopping health checks during startup cleanup: {}", e.getMessage())))
                    .eventually(() -> publishLifecycleEvent("manager.failed")
                        .onFailure(e -> logger.debug("Failed to publish manager.failed event: {}", e.getMessage())))
                    .transform(unused -> Future.<Void>failedFuture(
                            new RuntimeException("Failed to start PeeGeeQ Manager", original)));
            })
            .eventually(() -> {
                startFuture = null;
                return Future.succeededFuture();
            });

        startFuture = future;
        return future;
    }

    /**
     * Stops all PeeGeeQ services reactively.
     */
    public Future<Void> stop() {
        if (!started) {
            return Future.succeededFuture();
        }

        logger.info("Stopping PeeGeeQ Manager...");
        logger.debug(": PeeGeeQ Manager shutdown initiated");

        // Stop background tasks first (awaits in-flight operations)
        logger.debug(": Stopping background tasks");
        return stopBackgroundTasks()
            .compose(v -> {
                logger.debug(": Background tasks stopped successfully");
                // Stop health checks asynchronously to avoid blocking event loop
                logger.debug(": Stopping health check manager");
                return healthCheckManager.stop();
            })
            .compose(v -> {
                logger.debug(": Health check manager stopped successfully");
                started = false;
                logger.info("PeeGeeQ Manager stopped successfully");
                logger.debug(": PeeGeeQ Manager shutdown completed");
                return Future.<Void>succeededFuture();
            })
            .onFailure(throwable -> {
                logger.error("Error stopping PeeGeeQ Manager", throwable);
                started = false; // Mark as stopped even if there were errors
            });
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
     * Gets system status information reactively.
     */
    public Future<SystemStatus> getSystemStatus() {
        return deadLetterQueueManager.getStatistics()
            .map(deadLetterStatsInfo -> {
                dev.mars.peegeeq.db.deadletter.DeadLetterQueueStats deadLetterStats =
                    new dev.mars.peegeeq.db.deadletter.DeadLetterQueueStats(
                        deadLetterStatsInfo.totalMessages(),
                        deadLetterStatsInfo.uniqueTopics(),
                        deadLetterStatsInfo.uniqueTables(),
                        deadLetterStatsInfo.oldestFailure(),
                        deadLetterStatsInfo.newestFailure(),
                        deadLetterStatsInfo.averageRetryCount());

                return new SystemStatus(
                    started,
                    configuration.getProfile(),
                    healthCheckManager.getOverallHealthInternal(),
                    metrics.getSummary(),
                    backpressureManager.getMetrics(),
                    deadLetterStats
                );
            });
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
     * Awaits any in-flight start() operation before closing pools to prevent
     * "Pool closed" errors from racing startup futures.
     */
    public Future<Void> closeReactive() {
        // Return cached close future if already closing prevents re-running
        // the shutdown chain on a dead event loop.
        Future<Void> existing = closeFuture;
        if (existing != null) {
            return existing;
        }
        closing = true;
        logger.info("PeeGeeQManager.closeReactive() called - starting shutdown sequence");

        // Step 1: Mark all components as closing to fail-fast any in-flight timer operations
        // This prevents database queries from being attempted after shutdown begins
        if (metrics != null) {
            metrics.markClosing();
        }
        if (deadLetterQueueManager != null) {
            deadLetterQueueManager.markClosing();
        }
        if (stuckMessageRecoveryManager != null) {
            stuckMessageRecoveryManager.markClosing();
        }
        logger.debug("All components marked as closing to prevent in-flight timer operations");

        // Step 2: Await in-flight start() if any, so startup futures resolve before pools close
        Future<Void> pendingStart = startFuture;
        Future<Void> awaitStart;
        if (pendingStart != null) {
            logger.info("Awaiting in-flight start() before closing pools");
            awaitStart = pendingStart;
        } else {
            awaitStart = Future.succeededFuture();
        }

        // .eventually() chains ensure ALL cleanup runs regardless of any failure.
        // The original awaitStart outcome flows through to the caller automatically —
        // if start() was in-flight and failed, that failure propagates after cleanup.
        Future<Void> result = awaitStart
            // Step 3: Stop reactive components (cancel timers, stop background tasks)
            // After this point, no new timer callbacks will fire
            .eventually(() -> stop()
                .onFailure(e -> logger.warn("stop() failed during close, continuing cleanup: {}", e.getMessage())))
            // Step 4: Run registered close hooks
            .eventually(() -> {
                io.vertx.core.Future<Void> chain = io.vertx.core.Future.succeededFuture();
                if (!closeHooks.isEmpty()) {
                    logger.info("Running {} registered close hooks", closeHooks.size());
                    for (dev.mars.peegeeq.api.lifecycle.PeeGeeQCloseHook hook : closeHooks) {
                        chain = chain.eventually(() -> hook.closeReactive()
                            .onSuccess(v2 -> logger.debug("Close hook '{}' completed", hook.name()))
                            .onFailure(e -> logger.warn("Close hook '{}' failed: {}", hook.name(), e.getMessage())));
                    }
                } else {
                    logger.debug("No registered close hooks to run");
                }
                return chain;
            })
            // Note: Timer cancellation handled by Step 3 stop()->stopBackgroundTasks()
            // Step 5: Close worker executor (finish pending tasks)
            .eventually(() -> workerExecutor.close()
                .onFailure(e -> logger.warn("Failed to close worker executor", e)))
            // Step 6: Close client factory (DB pools) - AFTER workers are done
            .eventually(() -> {
                if (clientFactory != null) {
                    logger.info("Closing client factory");
                    return clientFactory.closeAsync()
                        .onSuccess(v2 -> logger.info("Client factory closed successfully"))
                        .onFailure(e -> logger.warn("Error closing client factory: {}", e.getMessage()));
                }
                return Future.succeededFuture();
            })
            // Step 7: Close manager-owned MeterRegistry
            .eventually(() -> {
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
            // 7. Close Vert.x instance (if owned)
            .eventually(() -> {
                logger.info("PeeGeeQManager.closeReactive() cleanup completed");
                if (vertx != null && vertxOwnedByManager) {
                    logger.info("Closing Vert.x instance (manager-owned)");
                    return vertx.close()
                        .onSuccess(v2 -> logger.info("Vert.x instance closed successfully"))
                        .onFailure(e -> {
                            if (e instanceof java.util.concurrent.RejectedExecutionException ||
                                (e.getCause() != null && e.getCause() instanceof java.util.concurrent.RejectedExecutionException)) {
                                logger.debug("Vert.x event executor terminated during close (expected)");
                            } else {
                                logger.warn("Error closing Vert.x instance", e);
                            }
                        });
                } else if (vertx != null) {
                    logger.info("Skipping Vert.x close (external ownership)");
                }
                return Future.succeededFuture();
            });
        closeFuture = result;
        return result;
    }

    @Override
    public void close() {
        closeReactive()
            .onSuccess(v -> logger.info("PeeGeeQManager closed successfully"))
            .onFailure(e -> logger.error("Error closing PeeGeeQManager", e));
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

    /** Returns the dead consumer detection job, or {@code null} if not configured/started. */
    DeadConsumerDetectionJob getDeadConsumerDetectionJob() { return deadConsumerDetectionJob; }

    /** Returns the consumer group retry job, or {@code null} if not configured/started. */
    ConsumerGroupRetryJob getConsumerGroupRetryJob() { return consumerGroupRetryJob; }

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
     * Returns a cached SubscriptionService for managing consumer group subscriptions.
     * The service is created lazily on first call and reused thereafter.
     * Includes BackfillService (with Vertx for rate-limited backfill) and
     * DeadConsumerGroupCleanup (for admin force-remove) wired.
     *
     * @return A shared SubscriptionService instance with BackfillService and cleanup wired
     */
    public dev.mars.peegeeq.api.subscription.SubscriptionService createSubscriptionService() {
        dev.mars.peegeeq.api.subscription.SubscriptionService service = cachedSubscriptionService;
        if (service == null) {
            synchronized (this) {
                service = cachedSubscriptionService;
                if (service == null) {
                    dev.mars.peegeeq.db.subscription.SubscriptionManager manager =
                        new dev.mars.peegeeq.db.subscription.SubscriptionManager(
                            clientFactory.getConnectionManager(),
                            PeeGeeQDefaults.DEFAULT_POOL_ID
                        );
                    manager.setBackfillService(
                        new dev.mars.peegeeq.db.subscription.BackfillService(
                            clientFactory.getConnectionManager(),
                            PeeGeeQDefaults.DEFAULT_POOL_ID,
                            vertx
                        )
                    );
                    manager.setDeadConsumerGroupCleanup(
                        new dev.mars.peegeeq.db.cleanup.DeadConsumerGroupCleanup(
                            clientFactory.getConnectionManager(),
                            PeeGeeQDefaults.DEFAULT_POOL_ID
                        )
                    );
                    manager.setPartitionedConsumptionServices(
                        new dev.mars.peegeeq.db.consumer.PartitionAssignmentService(
                            clientFactory.getConnectionManager(),
                            PeeGeeQDefaults.DEFAULT_POOL_ID
                        ),
                        new dev.mars.peegeeq.db.consumer.PartitionedFetcher(
                            clientFactory.getConnectionManager(),
                            PeeGeeQDefaults.DEFAULT_POOL_ID
                        ),
                        new dev.mars.peegeeq.db.consumer.PartitionedOffsetManager(
                            clientFactory.getConnectionManager(),
                            PeeGeeQDefaults.DEFAULT_POOL_ID
                        )
                    );
                    cachedSubscriptionService = manager;
                    service = manager;
                }
            }
        }
        return service;
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

        mapper.registerModule(JsonFormat.getCloudEventJacksonModule());
        logger.trace("CloudEvents Jackson module registered successfully");

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

        // Instrument with Tracing
        Context ctx = vertx.getOrCreateContext();
        Object traceObj = ctx.get(TraceContextUtil.CONTEXT_TRACE_KEY);
        TraceCtx currentTrace;
        if (traceObj instanceof TraceCtx) {
            currentTrace = (TraceCtx) traceObj;
        } else {
            currentTrace = TraceCtx.createNew();
            ctx.put(TraceContextUtil.CONTEXT_TRACE_KEY, currentTrace);
        }

        TraceCtx childTrace = currentTrace.childSpan("lifecycle");
        DeliveryOptions options = new DeliveryOptions()
            .addHeader("traceparent", childTrace.traceparent());

        vertx.eventBus().publish(EVENT_BUS_ADDR, eventData, options);
        logger.debug("Published lifecycle event: {}", event);
        return Future.succeededFuture();
    }

    /**
     * Validates database connectivity before starting components.
     * This ensures the database is accessible before health checks and other components start.
     */
    private Future<Void> validateDatabaseConnectivity() {
        logger.info("Validating database connectivity...");

        return AsyncTraceUtils.traceAsyncAction(vertx, "database.validate_connectivity", () ->
            pool.withConnection(connection ->
                connection.preparedQuery("SELECT 1").execute()
                    .map(rowSet -> {
                        logger.info("Database connectivity validated successfully");
                        return (Void) null;
                    })
            )
        ).onFailure(throwable ->
            logger.error("Database connectivity validation failed: {}", throwable.getMessage())
        ).transform(ar -> {
            if (ar.failed()) {
                return Future.failedFuture(new RuntimeException("Database startup validation failed", ar.cause()));
            }
            return Future.succeededFuture();
        });
    }

    private static final List<String> REQUIRED_TABLES = List.of(
            "outbox", "queue_messages", "dead_letter_queue", "outbox_topic_subscriptions");

    /**
     * Validates that all required core tables exist in the configured schema.
     * Fails fast with a clear error listing any missing tables.
     */
    private Future<Void> validateRequiredTables() {
        String schema = configuration.getDatabaseConfig().getSchema();
        logger.info("Validating required tables exist in schema '{}'...", schema);

        String sql = """
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = $1
                """;

        return pool.withConnection(connection ->
            connection.preparedQuery(sql)
                .execute(Tuple.of(schema))
                .map(rows -> {
                    java.util.Set<String> found = new java.util.HashSet<>();
                    for (Row row : rows) {
                        found.add(row.getString("table_name"));
                    }

                    List<String> missing = REQUIRED_TABLES.stream()
                            .filter(t -> !found.contains(t))
                            .toList();

                    if (!missing.isEmpty()) {
                        throw new IllegalStateException(
                                "Database required tables missing in schema '" + schema + "': " + missing);
                    }

                    logger.info("All required tables validated successfully");
                    return (Void) null;
                })
        );
    }

    /**
     * Starts all components reactively after database validation.
     * Components can subscribe to lifecycle events for coordinated startup.
     */
    private Future<Void> startAllComponents() {
        logger.info("Starting all PeeGeeQ components...");

        return AsyncTraceUtils.traceAsyncAction(vertx, "manager.start_components", () ->
            Future.all(
                startHealthChecks(),
                startMetricsCollection(),
                startBackgroundTasks()
            ).compose(compositeFuture -> {
                logger.info("All PeeGeeQ components started successfully");
                return Future.succeededFuture();
            })
        );
    }

    /**
     * Starts health checks reactively without blocking operations.
     */
    private Future<Void> startHealthChecks() {
        return AsyncTraceUtils.traceAsyncAction(vertx, "manager.start_health_checks", () -> {
            logger.debug(": Starting health check manager reactively");
            return healthCheckManager.start()
                .onSuccess(v -> logger.debug(": Health check manager started successfully"))
                .onFailure(throwable -> logger.error(": Failed to start health check manager", throwable));
        });
    }

    /**
     * Starts metrics collection reactively using Vert.x timers.
     */
    private Future<Void> startMetricsCollection() {
        return AsyncTraceUtils.traceAsyncAction(vertx, "manager.start_metrics", () -> {
            if (!configuration.getMetricsConfig().isEnabled()) {
                logger.debug(": Metrics collection disabled by configuration");
                return Future.succeededFuture();
            }

            logger.debug(": Starting metrics collection reactively");

            long intervalMs = configuration.getMetricsConfig().getReportingInterval().toMillis();
            metricsTimerId = vertx.setPeriodic(intervalMs, id -> {
                if (closing) return;
                if (!persistMetricsRunning.compareAndSet(false, true)) return;
                metrics.persistMetrics(meterRegistry)
                    .onSuccess(v -> {
                        persistMetricsFailures.set(0);
                        persistMetricsRunning.set(false);
                    })
                    .onFailure(e -> {
                        persistMetricsRunning.set(false);
                        if (closing) return;
                        long failures = persistMetricsFailures.incrementAndGet();
                        if (failures >= TIMER_FAILURE_ESCALATION_THRESHOLD) {
                            logger.error("Failed to persist metrics ({} consecutive failures): {}",
                                failures, e.getMessage(), e);
                        } else {
                            logger.warn("Failed to persist metrics: {}", e.getMessage(), e);
                        }
                    });
            });

            // Separate timer for refreshing cached queue depth values used by gauges
            long depthCacheIntervalMs = configuration.getMetricsConfig().getDepthCacheInterval().toMillis();
            depthCacheTimerId = vertx.setPeriodic(depthCacheIntervalMs, id -> {
                if (closing) return;
                if (!depthCacheRunning.compareAndSet(false, true)) return;
                metrics.refreshDepthCache()
                    .onSuccess(v -> {
                        depthCacheFailures.set(0);
                        depthCacheRunning.set(false);
                    })
                    .onFailure(e -> {
                        depthCacheRunning.set(false);
                        if (closing) return;
                        long failures = depthCacheFailures.incrementAndGet();
                        if (failures >= TIMER_FAILURE_ESCALATION_THRESHOLD) {
                            logger.error("Failed to refresh depth cache ({} consecutive failures): {}",
                                failures, e.getMessage(), e);
                        } else {
                            logger.warn("Failed to refresh depth cache: {}", e.getMessage(), e);
                        }
                    });
            });

            logger.info("Started metrics collection every {}", configuration.getMetricsConfig().getReportingInterval());
            logger.debug(": Metrics collection started successfully");
            return Future.succeededFuture();
        });
    }

    /**
     * Starts background tasks using Vert.x timers.
     */
    private Future<Void> startBackgroundTasks() {
        return AsyncTraceUtils.traceAsyncAction(vertx, "manager.start_background_tasks", () -> {
            logger.debug(": Starting background cleanup tasks");

            // Dead letter queue cleanup every 24 hours
            dlqTimerId = vertx.setPeriodic(TimeUnit.HOURS.toMillis(DLQ_CLEANUP_INTERVAL_HOURS), id -> {
                if (closing) return;
                deadLetterQueueManager.purgeOldDeadLetterMessages(DEFAULT_DLQ_RETENTION_DAYS)
                    .onSuccess(cleaned -> {
                        dlqCleanupFailures.set(0);
                        if (cleaned > 0) {
                            logger.info("Cleaned up {} old dead letter messages (retention: {} days)",
                                cleaned, DEFAULT_DLQ_RETENTION_DAYS);
                        }
                    })
                    .onFailure(e -> {
                        long failures = dlqCleanupFailures.incrementAndGet();
                        if (failures >= TIMER_FAILURE_ESCALATION_THRESHOLD) {
                            logger.error("Failed to cleanup old dead letter messages ({} consecutive failures): {}",
                                failures, e.getMessage(), e);
                        } else {
                            logger.warn("Failed to cleanup old dead letter messages: {}", e.getMessage(), e);
                        }
                    });
            });

            // Stuck message recovery
            if (stuckMessageRecoveryManager != null) {
                long recoveryMs = configuration.getQueueConfig().getRecoveryCheckInterval().toMillis();
                recoveryTimerId = vertx.setPeriodic(recoveryMs, id -> {
                    if (closing) return;
                    stuckMessageRecoveryManager.recoverStuckMessages()
                        .onSuccess(recovered -> {
                            stuckMessageFailures.set(0);
                            if (recovered > 0) {
                                logger.info("Recovered {} stuck messages from PROCESSING state", recovered);
                            }
                        })
                        .onFailure(e -> {
                            long failures = stuckMessageFailures.incrementAndGet();
                            if (failures >= TIMER_FAILURE_ESCALATION_THRESHOLD) {
                                logger.error("Failed to recover stuck messages ({} consecutive failures): {}",
                                    failures, e.getMessage(), e);
                            } else {
                                logger.warn("Failed to recover stuck messages: {}", e.getMessage(), e);
                            }
                        });
                });
            }

            // Dead consumer detection + cleanup
            if (configuration.getQueueConfig().isDeadConsumerDetectionEnabled()) {
                long detectionMs = configuration.getQueueConfig().getDeadConsumerDetectionInterval().toMillis();
                DeadConsumerDetector detector = new DeadConsumerDetector(
                        clientFactory.getConnectionManager(), PeeGeeQDefaults.DEFAULT_POOL_ID);
                DeadConsumerGroupCleanup cleanup = new DeadConsumerGroupCleanup(
                        clientFactory.getConnectionManager(), PeeGeeQDefaults.DEFAULT_POOL_ID);
                deadConsumerDetectionJob = new DeadConsumerDetectionJob(
                        vertx, detector, cleanup, detectionMs);
                deadConsumerDetectionJob.start();
                logger.info("Started dead consumer detection job: interval={}ms", detectionMs);
            } else {
                logger.info("Dead consumer detection disabled by configuration");
            }

            // Consumer group retry + DLQ automation
            if (configuration.getQueueConfig().isConsumerGroupRetryEnabled()) {
                long retryMs = configuration.getQueueConfig().getConsumerGroupRetryInterval().toMillis();
                ConsumerGroupRetryService retryService = new ConsumerGroupRetryService(
                        clientFactory.getConnectionManager(), deadLetterQueueManager, PeeGeeQDefaults.DEFAULT_POOL_ID);
                consumerGroupRetryJob = new ConsumerGroupRetryJob(vertx, retryService, retryMs);
                consumerGroupRetryJob.start();
                logger.info("Started consumer group retry job: interval={}ms", retryMs);
            } else {
                logger.info("Consumer group retry job disabled by configuration");
            }

            logger.debug(": Background cleanup tasks started successfully");
            return Future.succeededFuture();
        });
    }

    /**
     * Stops all background tasks by canceling Vert.x timers.
     * Returns a Future that completes when any in-flight background operations finish.
     */
    private Future<Void> stopBackgroundTasks() {
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
        if (depthCacheTimerId != 0) {
            vertx.cancelTimer(depthCacheTimerId);
            depthCacheTimerId = 0;
        }
        Future<Void> jobStop = Future.succeededFuture();
        if (deadConsumerDetectionJob != null) {
            DeadConsumerDetectionJob job = deadConsumerDetectionJob;
            deadConsumerDetectionJob = null;
            jobStop = jobStop.compose(v -> job.stop()
                .onFailure(e -> logger.warn("Error stopping dead consumer detection job: {}", e.getMessage()))
                .transform(ar -> Future.succeededFuture()));
        }
        if (consumerGroupRetryJob != null) {
            ConsumerGroupRetryJob job = consumerGroupRetryJob;
            consumerGroupRetryJob = null;
            jobStop = jobStop.compose(v -> job.stop()
                .onFailure(e -> logger.warn("Error stopping consumer group retry job: {}", e.getMessage()))
                .transform(ar -> Future.succeededFuture()));
        }
        return jobStop
            .onSuccess(v -> logger.debug(": All background tasks stopped"));
    }

}
