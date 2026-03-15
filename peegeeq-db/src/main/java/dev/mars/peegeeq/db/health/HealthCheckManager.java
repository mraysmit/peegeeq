package dev.mars.peegeeq.db.health;


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


import dev.mars.peegeeq.api.health.ComponentHealthState;
import dev.mars.peegeeq.api.health.HealthService;
import dev.mars.peegeeq.api.health.HealthStatusInfo;
import dev.mars.peegeeq.api.health.OverallHealthInfo;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.mars.peegeeq.db.resilience.CircuitBreakerManager;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Comprehensive health check system for PeeGeeQ.
 *
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
public class HealthCheckManager implements HealthService {
    private static final Logger logger = LoggerFactory.getLogger(HealthCheckManager.class);
    private static final Pattern SQL_IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    private final Pool reactivePool;
    private final Vertx vertx;
    private final Duration checkInterval;
    private final Duration timeout;
    private volatile long periodicTimerId = -1;
    private volatile ExecutorService healthCheckExecutor;
    private final Set<String> inFlightChecks;
    private final AtomicBoolean healthChecksInProgress = new AtomicBoolean(false);
    private final Map<String, HealthCheck> healthChecks;
    private final Map<String, HealthStatus> lastResults;
    private volatile boolean running = false;
    private final boolean enableQueueHealthChecks;
    private final String schema;
    private final CircuitBreakerManager circuitBreakerManager;

    /**
     * Constructor using reactive Pool and externally managed Vertx for Vert.x 5.x patterns.
     *
     * @param reactivePool The reactive pool for database connections
     * @param vertx Externally managed Vertx instance
     * @param checkInterval How often to run health checks
     * @param timeout Timeout for each health check
     */
    public HealthCheckManager(Pool reactivePool, Vertx vertx, Duration checkInterval, Duration timeout) {
        this(reactivePool, vertx, checkInterval, timeout, true, null, null);
    }

    /**
     * Constructor with explicit Vertx instance for fully reactive scheduling.
     */
    public HealthCheckManager(Pool reactivePool, Vertx vertx, Duration checkInterval, Duration timeout, boolean enableQueueHealthChecks) {
        this(reactivePool, vertx, checkInterval, timeout, enableQueueHealthChecks, null, null);
    }

    /**
     * Constructor with configurable queue health checks and schema.
     * Use this constructor to specify the schema for multi-tenant setups.
     *
     * @param reactivePool The reactive pool for database connections
     * @param checkInterval How often to run health checks
     * @param timeout Timeout for each health check
     * @param enableQueueHealthChecks Whether to enable health checks for queue tables (outbox, native-queue, dead-letter-queue)
     * @param schema The schema name to use for table references
     */
    public HealthCheckManager(Pool reactivePool, Vertx vertx, Duration checkInterval, Duration timeout, boolean enableQueueHealthChecks, String schema) {
        this(reactivePool, vertx, checkInterval, timeout, enableQueueHealthChecks, schema, null);
    }

    /**
     * Constructor with configurable queue health checks, schema and circuit breaker manager.
     *
     * @param reactivePool The reactive pool for database connections
     * @param checkInterval How often to run health checks
     * @param timeout Timeout for each health check
     * @param enableQueueHealthChecks Whether to enable health checks for queue tables (outbox, native-queue, dead-letter-queue)
     * @param schema The schema name to use for table references
     * @param circuitBreakerManager The circuit breaker manager to check for open circuits
     */
    public HealthCheckManager(Pool reactivePool, Vertx vertx, Duration checkInterval, Duration timeout, boolean enableQueueHealthChecks, String schema, CircuitBreakerManager circuitBreakerManager) {
        this.reactivePool = reactivePool;
        if (vertx == null) {
            throw new IllegalArgumentException("Vertx must be provided externally");
        }
        this.vertx = vertx;
        this.checkInterval = checkInterval;
        this.timeout = timeout;
        this.enableQueueHealthChecks = enableQueueHealthChecks;
        this.schema = normalizeSchema(schema);
        this.circuitBreakerManager = circuitBreakerManager;
        this.healthCheckExecutor = createHealthCheckExecutor();
        this.inFlightChecks = ConcurrentHashMap.newKeySet();
        this.healthChecks = new ConcurrentHashMap<>();
        this.lastResults = new ConcurrentHashMap<>();

        registerDefaultHealthChecks();
    }

    private String normalizeSchema(String schemaName) {
        if (schemaName == null || schemaName.isBlank()) {
            return null;
        }
        if (!SQL_IDENTIFIER.matcher(schemaName).matches()) {
            throw new IllegalArgumentException("Invalid schema name: " + schemaName);
        }
        return schemaName;
    }

    private String qualifiedTable(String table) {
        return (schema == null || schema.isBlank()) ? table : (schema + "." + table);
    }

    private ExecutorService createHealthCheckExecutor() {
        // Bound execution so repeated timeouts cannot create unbounded in-flight tasks.
        int maxWorkers = Math.max(4, Runtime.getRuntime().availableProcessors());
        ThreadFactory threadFactory = Thread.ofPlatform()
            .name("peegeeq-health-check-worker-", 0)
            .daemon(true)
            .factory();

        return new ThreadPoolExecutor(
            maxWorkers,
            maxWorkers,
            60L,
            TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            threadFactory,
            new ThreadPoolExecutor.AbortPolicy()
        );
    }

    private synchronized ExecutorService ensureHealthCheckExecutor() {
        if (healthCheckExecutor == null || healthCheckExecutor.isShutdown() || healthCheckExecutor.isTerminated()) {
            healthCheckExecutor = createHealthCheckExecutor();
        }
        return healthCheckExecutor;
    }

    private String schemaContext() {
        return (schema == null || schema.isBlank()) ? "connection search_path" : schema;
    }

    private void registerDefaultHealthChecks() {
        // Database connectivity check - always enabled
        registerHealthCheck("database", new DatabaseHealthCheck());

        // Queue health checks - only if enabled
        if (enableQueueHealthChecks) {
            registerHealthCheck("outbox-queue", new OutboxQueueHealthCheck());
            registerHealthCheck("native-queue", new NativeQueueHealthCheck());
            registerHealthCheck("dead-letter-queue", new DeadLetterQueueHealthCheck());
            logger.info("Queue health checks enabled (outbox, native-queue, dead-letter-queue)");
        } else {
            logger.info("Queue health checks disabled - skipping outbox, native-queue, and dead-letter-queue checks");
        }

        // System resource checks - always enabled
        registerHealthCheck("memory", new MemoryHealthCheck());
        registerHealthCheck("disk-space", new DiskSpaceHealthCheck());
    }
    
    public void registerHealthCheck(String name, HealthCheck healthCheck) {
        healthChecks.put(name, healthCheck);
        logger.info("Registered health check: {}", name);
    }
    
    public void start() {
        try {
            awaitVertxFuture(startReactive(), Duration.ofSeconds(5));
        } catch (Exception e) {
            logger.error("Failed to start health checks - database connection validation failed: {}",
                        e.getMessage());
            throw new RuntimeException("Database startup validation failed - ensure database is accessible", e);
        }
    }
    
    public void stop() {
        if (!running) {
            return;
        }

        try {
            awaitVertxFuture(stopReactive(), Duration.ofSeconds(5));
        } catch (Exception e) {
            throw new RuntimeException("Failed to stop health check manager reactively", e);
        }

        logger.info("Health check manager stopped");
    }

    private <T> T awaitVertxFuture(Future<T> future, Duration waitTimeout) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> resultRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        future.onComplete(ar -> {
            if (ar.succeeded()) {
                resultRef.set(ar.result());
            } else {
                errorRef.set(ar.cause());
            }
            latch.countDown();
        });

        if (!latch.await(waitTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new TimeoutException("Timed out waiting for Vert.x future completion");
        }

        Throwable error = errorRef.get();
        if (error == null) {
            return resultRef.get();
        }
        if (error instanceof Exception exception) {
            throw exception;
        }
        if (error instanceof Error unrecoverable) {
            throw unrecoverable;
        }
        throw new RuntimeException(error);
    }

    /**
     * Stops the health check manager reactively.
     * This method shuts down the scheduler without blocking the calling thread.
     */
    public Future<Void> stopReactive() {
        if (!running) {
            return Future.succeededFuture();
        }

        running = false;
        logger.info("Stopping health check manager reactively...");
        if (periodicTimerId >= 0) {
            vertx.cancelTimer(periodicTimerId);
            periodicTimerId = -1;
        }

        ExecutorService executorToStop = healthCheckExecutor;
        healthCheckExecutor = null;
        if (executorToStop != null) {
            executorToStop.shutdownNow();
        }
        inFlightChecks.clear();
        return Future.succeededFuture();
    }

    /**
     * Validates the connection pool before starting health checks.
     * This prevents confusing DEBUG messages during startup by ensuring
     * the database is accessible before health monitoring begins.
     */
    private Future<Void> validateConnectionPool(Pool pool) {
        return pool.withConnection(connection ->
            connection.preparedQuery("SELECT 1").execute()
                .map(rowSet -> {
                    logger.info("Database connection pool validated successfully");
                    return (Void) null;
                })
        ).recover(throwable -> {
            logger.error("Database connection pool validation failed: {}", throwable.getMessage());
            return Future.failedFuture(new RuntimeException("Database startup validation failed", throwable));
        });
    }

    /**
     * Starts health checks reactively without blocking operations.
     * This is the preferred method for event-driven startup orchestration.
     */
    public Future<Void> startReactive() {
        if (running) {
            logger.warn("Health check manager is already running");
            return Future.succeededFuture();
        }

        logger.debug("Validating database connection pool before starting health checks (reactive)");
        return validateConnectionPool(reactivePool)
            .map(v -> {
                // Keep a small startup delay to avoid aggressive immediate checks.
                startWithDelay(Duration.ofMillis(100));
                logger.info("Health check manager started reactively with initial delay 100ms, interval: {}", checkInterval);
                return (Void) null;
            });
    }

    /**
     * Starts health checks with a configurable initial delay.
     * This allows starting health checks after successful pool validation.
     */
    private void startWithDelay(Duration initialDelay) {
        if (running) {
            logger.warn("Health check manager is already running");
            return;
        }

        running = true;

        // Execute an initial health-check cycle immediately so callers observe
        // status promptly after successful startup validation.
        performHealthChecks();

        long initialDelayMs = initialDelay.toMillis();
        if (initialDelayMs <= 0) {
            periodicTimerId = vertx.setPeriodic(checkInterval.toMillis(), id -> performHealthChecks());
        } else {
            vertx.setTimer(initialDelayMs, id -> {
                if (!running) {
                    return;
                }
                periodicTimerId = vertx.setPeriodic(checkInterval.toMillis(), periodicId -> performHealthChecks());
            });
        }

        logger.info("Health check manager started with initial delay: {}, interval: {}",
                    initialDelay, checkInterval);
    }

    private void performHealthChecks() {
        if (!running) {
            return;
        }
        if (!healthChecksInProgress.compareAndSet(false, true)) {
            logger.debug("Skipping health check run - previous run still in progress");
            return;
        }

        logger.debug("Performing health checks");

        Future<Void> chain = Future.succeededFuture();
        for (Map.Entry<String, HealthCheck> entry : new HashMap<>(healthChecks).entrySet()) {
            final String name = entry.getKey();
            final HealthCheck check = entry.getValue();
            chain = chain.compose(v -> runHealthCheckWithTimeout(name, check)
                .onSuccess(status -> {
                    lastResults.put(name, status);
                    logUnhealthyStatusIfNeeded(name, status);
                })
                .onFailure(t -> {
                    HealthStatus status;
                    if (t instanceof TimeoutException) {
                        status = HealthStatus.unhealthy(name, "Health check timed out");
                        logger.warn("Health check timed out: {}", name);
                    } else {
                        status = HealthStatus.unhealthy(name, "Health check error: " + t.getMessage());
                        logger.warn("Health check error: {}", name, t);
                    }
                    lastResults.put(name, status);
                })
                .mapEmpty());
        }

        chain.onComplete(ar -> healthChecksInProgress.set(false));
    }

    private Future<HealthStatus> runHealthCheckWithTimeout(String name, HealthCheck check) {
        if (!running) {
            return Future.succeededFuture(HealthStatus.unhealthy(name, "Skipped — health check manager stopped"));
        }

        Promise<HealthStatus> promise = Promise.promise();
        AtomicBoolean completed = new AtomicBoolean(false);
        ExecutorService executor = ensureHealthCheckExecutor();

        if (!inFlightChecks.add(name)) {
            return Future.failedFuture(new TimeoutException("Previous health check still running: " + name));
        }

        AtomicLong timeoutIdRef = new AtomicLong(-1);
        AtomicReference<java.util.concurrent.Future<?>> runningTaskRef = new AtomicReference<>();

        Runnable checkRunnable = () -> {
            if (!running) {
                if (completed.compareAndSet(false, true)) {
                    promise.tryComplete(HealthStatus.unhealthy(name, "Skipped — health check manager stopped"));
                }
                inFlightChecks.remove(name);
                return;
            }
            try {
                HealthStatus status = check.check();
                if (completed.compareAndSet(false, true)) {
                    long timeoutId = timeoutIdRef.get();
                    if (timeoutId >= 0) {
                        vertx.cancelTimer(timeoutId);
                    }
                    promise.tryComplete(status);
                }
            } catch (Exception e) {
                // Check if this is a connection error during shutdown (expected during cleanup)
                String errorMsg = e.getMessage();
                boolean isConnectionError = errorMsg != null &&
                    (errorMsg.contains("Connection refused") ||
                     errorMsg.contains("connection may have been lost") ||
                     errorMsg.contains("underlying connection") ||
                     errorMsg.contains("Pool closed"));

                // Check if this is a FATAL schema error (missing tables)
                boolean isFatalSchemaError = errorMsg != null &&
                    (errorMsg.contains("relation") && errorMsg.contains("does not exist"));

                if (isConnectionError || !running) {
                    logger.debug("Health check failed due to connection issue (expected during shutdown): {} - {}",
                        name, errorMsg);
                } else if (isFatalSchemaError) {
                    logger.error("Health check failed with FATAL schema error: {} - {}", name, errorMsg);
                } else {
                    logger.warn("Health check failed: {} - {}", name, errorMsg, e);
                }

                if (completed.compareAndSet(false, true)) {
                    long timeoutId = timeoutIdRef.get();
                    if (timeoutId >= 0) {
                        vertx.cancelTimer(timeoutId);
                    }
                    promise.tryComplete(HealthStatus.unhealthy(name, "Health check threw exception: " + errorMsg));
                }
            } finally {
                inFlightChecks.remove(name);
            }
        };

        java.util.concurrent.Future<?> runningTask;
        try {
            runningTask = executor.submit(checkRunnable);
            runningTaskRef.set(runningTask);
        } catch (RejectedExecutionException e) {
            inFlightChecks.remove(name);
            return Future.failedFuture(new RuntimeException("Health check executor saturated for: " + name, e));
        }

        long timeoutId = vertx.setTimer(timeout.toMillis(), id -> {
            if (completed.compareAndSet(false, true)) {
                java.util.concurrent.Future<?> task = runningTaskRef.get();
                if (task != null) {
                    task.cancel(true);
                }
                promise.tryFail(new TimeoutException("Health check timed out: " + name));
            }
        });
        timeoutIdRef.set(timeoutId);

        return promise.future();
    }

    private void logUnhealthyStatusIfNeeded(String name, HealthStatus status) {
        if (status.isHealthy()) {
            return;
        }

        String statusMsg = status.getMessage();
        boolean isConnectionError = statusMsg != null &&
            (statusMsg.contains("Connection refused") ||
             statusMsg.contains("connection may have been lost") ||
             statusMsg.contains("underlying connection") ||
             statusMsg.contains("Pool closed"));

        boolean isFatalSchemaError = statusMsg != null && statusMsg.contains("FATAL:");

        if (isConnectionError) {
            logger.debug("Health check failed due to connection issue (expected during shutdown): {} - {}", name, statusMsg);
        } else if (isFatalSchemaError) {
            logger.error("Health check failed with FATAL schema error: {} - {}", name, statusMsg);
        } else {
            logger.warn("Health check failed: {} - {}", name, statusMsg);
        }
    }
    
    /**
     * Gets the overall health status using internal types.
     * For API consumers, use {@link #getOverallHealth()} which returns API types.
     */
    public OverallHealthStatus getOverallHealthInternal() {
        Map<String, HealthStatus> currentResults = new HashMap<>(lastResults);

        boolean allHealthy = !currentResults.isEmpty() && currentResults.values().stream().allMatch(HealthStatus::isHealthy);
        String status = allHealthy ? "UP" : "DOWN";

        return new OverallHealthStatus(status, currentResults, Instant.now());
    }

    public HealthStatus getHealthStatus(String checkName) {
        return lastResults.get(checkName);
    }

    @Override
    public boolean isHealthy() {
        return !lastResults.isEmpty() && lastResults.values().stream().allMatch(HealthStatus::isHealthy);
    }

    /**
     * Checks if the health check manager is currently running.
     * Used for testing and monitoring purposes.
     */
    @Override
    public boolean isRunning() {
        return running;
    }

    // ========================================
    // HealthService API Interface Implementation
    // ========================================

    /**
     * Converts internal HealthStatus to API HealthStatusInfo.
     */
    private HealthStatusInfo toHealthStatusInfo(HealthStatus status) {
        if (status == null) return null;

        ComponentHealthState state = switch (status.getStatus()) {
            case HEALTHY -> ComponentHealthState.HEALTHY;
            case DEGRADED -> ComponentHealthState.DEGRADED;
            case UNHEALTHY -> ComponentHealthState.UNHEALTHY;
        };

        return new HealthStatusInfo(
            status.getComponent(),
            state,
            status.getMessage(),
            status.getDetails(),
            status.getTimestamp()
        );
    }

    /**
     * Converts internal OverallHealthStatus to API OverallHealthInfo.
     */
    private OverallHealthInfo toOverallHealthInfo(OverallHealthStatus status) {
        Map<String, HealthStatusInfo> components = new HashMap<>();
        for (Map.Entry<String, HealthStatus> entry : status.getComponents().entrySet()) {
            components.put(entry.getKey(), toHealthStatusInfo(entry.getValue()));
        }
        return new OverallHealthInfo(status.getStatus(), components, status.getTimestamp());
    }

    @Override
    public OverallHealthInfo getOverallHealth() {
        return toOverallHealthInfo(getOverallHealthInternal());
    }

    @Override
    public Future<OverallHealthInfo> getOverallHealthAsync() {
        return Future.succeededFuture(getOverallHealth());
    }

    @Override
    public HealthStatusInfo getComponentHealth(String componentName) {
        HealthStatus status = getHealthStatus(componentName);
        return toHealthStatusInfo(status);
    }

    @Override
    public Future<HealthStatusInfo> getComponentHealthAsync(String componentName) {
        return Future.succeededFuture(getComponentHealth(componentName));
    }

    // Note: isHealthy() and isRunning() are already implemented above

    // ========================================
    // End HealthService API Interface
    // ========================================

    // Helper method to wrap health checks with circuit breaker logic
    private Future<HealthStatus> executeWithCircuitBreaker(String cbName, java.util.function.Supplier<Future<HealthStatus>> operation) {
        if (circuitBreakerManager != null) {
            CircuitBreaker cb = circuitBreakerManager.getCircuitBreaker(cbName);
            if (cb != null) {
                if (!cb.tryAcquirePermission()) {
                    return Future.succeededFuture(HealthStatus.unhealthy(cbName, "Circuit breaker open"));
                }
                
                long start = System.nanoTime();
                return operation.get()
                    .onSuccess(status -> {
                        long duration = System.nanoTime() - start;
                        if (status.isHealthy()) {
                            cb.onSuccess(duration, TimeUnit.NANOSECONDS);
                        } else {
                            // Treat unhealthy status as a failure for the circuit breaker
                            cb.onError(duration, TimeUnit.NANOSECONDS, new RuntimeException(status.getMessage()));
                        }
                    })
                    .onFailure(t -> cb.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, t));
            }
        }
        return operation.get();
    }

    // Default health check implementations
    private class DatabaseHealthCheck implements HealthCheck {
        @Override
        public HealthStatus check() {
            try {
                return awaitVertxFuture(executeWithCircuitBreaker("database", this::checkDatabaseReactive), timeout);
            } catch (TimeoutException e) {
                return HealthStatus.unhealthy("database", "Reactive database health check timed out");
            } catch (Exception e) {
                return HealthStatus.unhealthy("database", "Reactive database health check failed: " + e.getMessage());
            }
        }

        private Future<HealthStatus> checkDatabaseReactive() {
            return reactivePool.withConnection(connection -> {
                // Simple query to test connection health
                return connection.preparedQuery("SELECT 1").execute()
                    .map(rowSet -> {
                        if (rowSet.iterator().hasNext() && rowSet.iterator().next().getInteger(0) == 1) {
                            return HealthStatus.healthy("database");
                        } else {
                            throw new RuntimeException("Database query returned unexpected result");
                        }
                    });
            }).recover(throwable -> {
                return Future.succeededFuture(HealthStatus.unhealthy("database", "Database connection failed: " + throwable.getMessage()));
            });
        }
    }
    
    private class OutboxQueueHealthCheck implements HealthCheck {
        @Override
        public HealthStatus check() {
            try {
                // Use "database" circuit breaker for queue checks too, as they depend on the DB
                return awaitVertxFuture(executeWithCircuitBreaker("database", this::checkOutboxQueueReactive), timeout);
            } catch (TimeoutException e) {
                return HealthStatus.unhealthy("outbox-queue", "Reactive outbox queue health check timed out");
            } catch (Exception e) {
                return HealthStatus.unhealthy("outbox-queue", "Reactive outbox queue health check failed: " + e.getMessage());
            }
        }

        private Future<HealthStatus> checkOutboxQueueReactive() {
            String sql = String.format("SELECT COUNT(*) FROM %s WHERE status = 'PENDING' AND created_at > NOW() - INTERVAL '1 hour'", qualifiedTable("outbox"));
            return reactivePool.withConnection(connection -> {
                return connection.preparedQuery(sql).execute()
                    .map(rowSet -> {
                        if (rowSet.iterator().hasNext()) {
                            long pendingCount = rowSet.iterator().next().getLong(0);
                            Map<String, Object> details = new HashMap<>();
                            details.put("pending_messages", pendingCount);

                            if (pendingCount > 10000) {
                                return HealthStatus.unhealthy("outbox-queue", "Too many pending messages: " + pendingCount, details);
                            }

                            return HealthStatus.healthy("outbox-queue", details);
                        } else {
                            return HealthStatus.unhealthy("outbox-queue", "Unable to verify outbox queue status");
                        }
                    });
            }).recover(throwable -> {
                String errorMsg = throwable.getMessage();
                // Check for missing table - this is a FATAL configuration error
                if (errorMsg != null && (errorMsg.contains("relation \"outbox\" does not exist") || errorMsg.contains("relation \"" + qualifiedTable("outbox") + "\" does not exist"))) {
                    return Future.succeededFuture(HealthStatus.unhealthy("outbox-queue",
                        "FATAL: outbox table does not exist in schema context " + schemaContext() + " - schema not initialized properly"));
                }
                return Future.succeededFuture(HealthStatus.unhealthy("outbox-queue", "Failed to check outbox queue: " + errorMsg));
            });
        }
    }
    
    private class NativeQueueHealthCheck implements HealthCheck {
        @Override
        public HealthStatus check() {
            try {
                return awaitVertxFuture(executeWithCircuitBreaker("database", this::checkNativeQueueReactive), timeout);
            } catch (TimeoutException e) {
                return HealthStatus.unhealthy("native-queue", "Reactive native queue health check timed out");
            } catch (Exception e) {
                return HealthStatus.unhealthy("native-queue", "Reactive native queue health check failed: " + e.getMessage());
            }
        }

        private Future<HealthStatus> checkNativeQueueReactive() {
            String sql = String.format("SELECT COUNT(*) FROM %s WHERE status = 'AVAILABLE'", qualifiedTable("queue_messages"));
            return reactivePool.withConnection(connection -> {
                return connection.preparedQuery(sql).execute()
                    .map(rowSet -> {
                        if (rowSet.iterator().hasNext()) {
                            long availableCount = rowSet.iterator().next().getLong(0);
                            Map<String, Object> details = new HashMap<>();
                            details.put("available_messages", availableCount);

                            return HealthStatus.healthy("native-queue", details);
                        } else {
                            return HealthStatus.unhealthy("native-queue", "Unable to verify native queue status");
                        }
                    });
            }).recover(throwable -> {
                String errorMsg = throwable.getMessage();
                // Check for missing table - this is a FATAL configuration error
                if (errorMsg != null && (errorMsg.contains("relation \"queue_messages\" does not exist") || errorMsg.contains("relation \"" + qualifiedTable("queue_messages") + "\" does not exist"))) {
                    return Future.succeededFuture(HealthStatus.unhealthy("native-queue",
                        "FATAL: queue_messages table does not exist in schema context " + schemaContext() + " - schema not initialized properly"));
                }
                return Future.succeededFuture(HealthStatus.unhealthy("native-queue", "Failed to check native queue: " + errorMsg));
            });
        }
    }
    
    private class DeadLetterQueueHealthCheck implements HealthCheck {
        @Override
        public HealthStatus check() {
            try {
                return awaitVertxFuture(executeWithCircuitBreaker("database", this::checkDeadLetterQueueReactive), timeout);
            } catch (TimeoutException e) {
                return HealthStatus.unhealthy("dead-letter-queue", "Reactive dead letter queue health check timed out");
            } catch (Exception e) {
                return HealthStatus.unhealthy("dead-letter-queue", "Reactive dead letter queue health check failed: " + e.getMessage());
            }
        }

        private Future<HealthStatus> checkDeadLetterQueueReactive() {
            String sql = String.format("SELECT COUNT(*) FROM %s WHERE failed_at > NOW() - INTERVAL '1 hour'", qualifiedTable("dead_letter_queue"));
            return reactivePool.withConnection(connection -> {
                return connection.preparedQuery(sql).execute()
                    .map(rowSet -> {
                        if (rowSet.iterator().hasNext()) {
                            long recentFailures = rowSet.iterator().next().getLong(0);
                            Map<String, Object> details = new HashMap<>();
                            details.put("recent_failures", recentFailures);

                            if (recentFailures > 100) {
                                return HealthStatus.unhealthy("dead-letter-queue",
                                    "High number of recent failures: " + recentFailures, details);
                            }

                            return HealthStatus.healthy("dead-letter-queue", details);
                        } else {
                            return HealthStatus.unhealthy("dead-letter-queue", "Unable to verify dead letter queue status");
                        }
                    });
            }).recover(throwable -> {
                String errorMsg = throwable.getMessage();
                // Check for missing table - this is a FATAL configuration error
                if (errorMsg != null && (errorMsg.contains("relation \"dead_letter_queue\" does not exist") || errorMsg.contains("relation \"" + qualifiedTable("dead_letter_queue") + "\" does not exist"))) {
                    return Future.succeededFuture(HealthStatus.unhealthy("dead-letter-queue",
                        "FATAL: dead_letter_queue table does not exist in schema context " + schemaContext() + " - schema not initialized properly"));
                }
                return Future.succeededFuture(HealthStatus.unhealthy("dead-letter-queue", "Failed to check dead letter queue: " + errorMsg));
            });
        }
    }
    
    private class MemoryHealthCheck implements HealthCheck {
        @Override
        public HealthStatus check() {
            Runtime runtime = Runtime.getRuntime();
            long maxMemory = runtime.maxMemory();
            long totalMemory = runtime.totalMemory();
            long freeMemory = runtime.freeMemory();
            long usedMemory = totalMemory - freeMemory;
            
            double memoryUsagePercent = (double) usedMemory / maxMemory * 100;
            
            Map<String, Object> details = new HashMap<>();
            details.put("max_memory_mb", maxMemory / 1024 / 1024);
            details.put("used_memory_mb", usedMemory / 1024 / 1024);
            details.put("memory_usage_percent", Math.round(memoryUsagePercent * 100.0) / 100.0);
            
            if (memoryUsagePercent > 90) {
                return HealthStatus.unhealthy("memory", "Memory usage is critically high: " + 
                    Math.round(memoryUsagePercent) + "%", details);
            } else if (memoryUsagePercent > 80) {
                return HealthStatus.degraded("memory", "Memory usage is high: " + 
                    Math.round(memoryUsagePercent) + "%", details);
            }
            
            return HealthStatus.healthy("memory", details);
        }
    }
    
    private class DiskSpaceHealthCheck implements HealthCheck {
        @Override
        public HealthStatus check() {
            try {
                java.io.File root = new java.io.File("/");
                long totalSpace = root.getTotalSpace();
                long freeSpace = root.getFreeSpace();
                long usedSpace = totalSpace - freeSpace;
                
                double diskUsagePercent = (double) usedSpace / totalSpace * 100;
                
                Map<String, Object> details = new HashMap<>();
                details.put("total_space_gb", totalSpace / 1024 / 1024 / 1024);
                details.put("free_space_gb", freeSpace / 1024 / 1024 / 1024);
                details.put("disk_usage_percent", Math.round(diskUsagePercent * 100.0) / 100.0);
                
                if (diskUsagePercent > 95) {
                    return HealthStatus.unhealthy("disk-space", "Disk usage is critically high: " + 
                        Math.round(diskUsagePercent) + "%", details);
                } else if (diskUsagePercent > 85) {
                    return HealthStatus.degraded("disk-space", "Disk usage is high: " + 
                        Math.round(diskUsagePercent) + "%", details);
                }
                
                return HealthStatus.healthy("disk-space", details);
            } catch (Exception e) {
                return HealthStatus.unhealthy("disk-space", "Failed to check disk space: " + e.getMessage());
            }
        }
    }


}
