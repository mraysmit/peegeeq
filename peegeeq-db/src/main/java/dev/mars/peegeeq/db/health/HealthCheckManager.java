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
import io.vertx.sqlclient.Pool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

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
    
    private final Pool reactivePool;
    private final Duration checkInterval;
    private final Duration timeout;
    private final ScheduledExecutorService scheduler;
    private final Map<String, HealthCheck> healthChecks;
    private final Map<String, HealthStatus> lastResults;
    private volatile boolean running = false;
    private final boolean enableQueueHealthChecks;

    /**
     * Constructor using reactive Pool for Vert.x 5.x patterns.
     * This is the only constructor - pure Vert.x reactive implementation.
     *
     * @param reactivePool The reactive pool for database connections
     * @param checkInterval How often to run health checks
     * @param timeout Timeout for each health check
     */
    public HealthCheckManager(Pool reactivePool, Duration checkInterval, Duration timeout) {
        this(reactivePool, checkInterval, timeout, true);
    }

    /**
     * Constructor with configurable queue health checks.
     * Use this constructor to disable queue health checks when queue tables don't exist.
     *
     * @param reactivePool The reactive pool for database connections
     * @param checkInterval How often to run health checks
     * @param timeout Timeout for each health check
     * @param enableQueueHealthChecks Whether to enable health checks for queue tables (outbox, native-queue, dead-letter-queue)
     */
    public HealthCheckManager(Pool reactivePool, Duration checkInterval, Duration timeout, boolean enableQueueHealthChecks) {
        this.reactivePool = reactivePool;
        this.checkInterval = checkInterval;
        this.timeout = timeout;
        this.enableQueueHealthChecks = enableQueueHealthChecks;
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "peegeeq-health-check");
            t.setDaemon(false); // Changed to false to ensure proper shutdown
            return t;
        });
        this.healthChecks = new ConcurrentHashMap<>();
        this.lastResults = new ConcurrentHashMap<>();

        registerDefaultHealthChecks();
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
        if (running) {
            logger.warn("Health check manager is already running");
            return;
        }

        // NEW: Validate connection pool before starting health checks
        // This prevents confusing DEBUG messages during startup
        logger.debug("Validating database connection pool before starting health checks");

        try {
            validateConnectionPool(reactivePool)
                .compose(v -> {
                    // Start health checks with small delay after successful validation
                    startWithDelay(Duration.ofMillis(100));
                    return Future.succeededFuture();
                })
                .toCompletionStage()
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS); // Wait up to 5 seconds for validation

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
        
        running = false;
        scheduler.shutdown();
        
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        logger.info("Health check manager stopped");
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

        // Shutdown scheduler asynchronously
        return Future.future(promise -> {
            scheduler.shutdown();
            // We don't wait for termination here to avoid blocking
            // The scheduler threads will exit when their tasks complete or are interrupted
            logger.info("Health check manager stopped (scheduler shutdown initiated)");
            promise.complete();
        });
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

        running = true;

        // Start health checks with appropriate delay for reactive startup
        scheduler.scheduleAtFixedRate(this::performHealthChecks,
            100, checkInterval.toMillis(), TimeUnit.MILLISECONDS);

        logger.info("Health check manager started reactively with 100ms initial delay, interval: {}", checkInterval);
        return Future.succeededFuture();
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
        scheduler.scheduleAtFixedRate(this::performHealthChecks,
            initialDelay.toMillis(), checkInterval.toMillis(), TimeUnit.MILLISECONDS);

        logger.info("Health check manager started with initial delay: {}, interval: {}",
                    initialDelay, checkInterval);
    }

    private void performHealthChecks() {
        logger.debug("Performing health checks");
        
        for (Map.Entry<String, HealthCheck> entry : healthChecks.entrySet()) {
            String name = entry.getKey();
            HealthCheck check = entry.getValue();
            
            CompletableFuture<HealthStatus> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return check.check();
                } catch (Exception e) {
                    // Check if this is a connection error during shutdown (expected during cleanup)
                    String errorMsg = e.getMessage();
                    boolean isConnectionError = errorMsg != null &&
                        (errorMsg.contains("Connection refused") ||
                         errorMsg.contains("connection may have been lost") ||
                         errorMsg.contains("underlying connection"));

                    // Check if this is a FATAL schema error (missing tables)
                    boolean isFatalSchemaError = errorMsg != null &&
                        (errorMsg.contains("relation") && errorMsg.contains("does not exist"));

                    if (isConnectionError) {
                        logger.debug("Health check failed due to connection issue (expected during shutdown): {} - {}",
                            name, errorMsg);
                    } else if (isFatalSchemaError) {
                        logger.error("Health check failed with FATAL schema error: {} - {}", name, errorMsg);
                    } else {
                        logger.warn("Health check failed: {} - {}", name, errorMsg, e);
                    }
                    return HealthStatus.unhealthy(name, "Health check threw exception: " + errorMsg);
                }
            }, scheduler);

            try {
                HealthStatus status = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                lastResults.put(name, status);

                if (!status.isHealthy()) {
                    // Check if the failure message indicates a connection issue
                    String statusMsg = status.getMessage();
                    boolean isConnectionError = statusMsg != null &&
                        (statusMsg.contains("Connection refused") ||
                         statusMsg.contains("connection may have been lost") ||
                         statusMsg.contains("underlying connection"));

                    // Check if this is a FATAL schema error (missing tables)
                    boolean isFatalSchemaError = statusMsg != null && statusMsg.contains("FATAL:");

                    if (isConnectionError) {
                        logger.debug("Health check failed due to connection issue (expected during shutdown): {} - {}",
                            name, statusMsg);
                    } else if (isFatalSchemaError) {
                        logger.error("Health check failed with FATAL schema error: {} - {}", name, statusMsg);
                    } else {
                        logger.warn("Health check failed: {} - {}", name, statusMsg);
                    }
                }
            } catch (TimeoutException e) {
                HealthStatus timeoutStatus = HealthStatus.unhealthy(name, "Health check timed out");
                lastResults.put(name, timeoutStatus);
                logger.warn("Health check timed out: {}", name);
            } catch (Exception e) {
                HealthStatus errorStatus = HealthStatus.unhealthy(name, "Health check error: " + e.getMessage());
                lastResults.put(name, errorStatus);
                logger.warn("Health check error: {}", name, e);
            }
        }
    }
    
    /**
     * Gets the overall health status using internal types.
     * For API consumers, use {@link #getOverallHealth()} which returns API types.
     */
    public OverallHealthStatus getOverallHealthInternal() {
        Map<String, HealthStatus> currentResults = new HashMap<>(lastResults);

        boolean allHealthy = currentResults.values().stream().allMatch(HealthStatus::isHealthy);
        String status = allHealthy ? "UP" : "DOWN";

        return new OverallHealthStatus(status, currentResults, Instant.now());
    }

    public HealthStatus getHealthStatus(String checkName) {
        return lastResults.get(checkName);
    }

    @Override
    public boolean isHealthy() {
        return lastResults.values().stream().allMatch(HealthStatus::isHealthy);
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
    public CompletableFuture<OverallHealthInfo> getOverallHealthAsync() {
        return CompletableFuture.supplyAsync(this::getOverallHealth, scheduler);
    }

    @Override
    public HealthStatusInfo getComponentHealth(String componentName) {
        HealthStatus status = getHealthStatus(componentName);
        return toHealthStatusInfo(status);
    }

    @Override
    public CompletableFuture<HealthStatusInfo> getComponentHealthAsync(String componentName) {
        return CompletableFuture.supplyAsync(() -> getComponentHealth(componentName), scheduler);
    }

    // Note: isHealthy() and isRunning() are already implemented above

    // ========================================
    // End HealthService API Interface
    // ========================================

    // Default health check implementations
    private class DatabaseHealthCheck implements HealthCheck {
        @Override
        public HealthStatus check() {
            // Use reactive approach - block on the result for compatibility with synchronous interface
            try {
                return checkDatabaseReactive().toCompletionStage().toCompletableFuture().get();
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
                            return HealthStatus.unhealthy("database", "Database query returned unexpected result");
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
            // Use reactive approach - block on the result for compatibility with synchronous interface
            try {
                return checkOutboxQueueReactive().toCompletionStage().toCompletableFuture().get();
            } catch (Exception e) {
                return HealthStatus.unhealthy("outbox-queue", "Reactive outbox queue health check failed: " + e.getMessage());
            }
        }

        private Future<HealthStatus> checkOutboxQueueReactive() {
            String sql = "SELECT COUNT(*) FROM outbox WHERE status = 'PENDING' AND created_at > NOW() - INTERVAL '1 hour'";
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
                if (errorMsg != null && errorMsg.contains("relation \"outbox\" does not exist")) {
                    return Future.succeededFuture(HealthStatus.unhealthy("outbox-queue",
                        "FATAL: outbox table does not exist - schema not initialized properly"));
                }
                return Future.succeededFuture(HealthStatus.unhealthy("outbox-queue", "Failed to check outbox queue: " + errorMsg));
            });
        }
    }
    
    private class NativeQueueHealthCheck implements HealthCheck {
        @Override
        public HealthStatus check() {
            // Use reactive approach - block on the result for compatibility with synchronous interface
            try {
                return checkNativeQueueReactive().toCompletionStage().toCompletableFuture().get();
            } catch (Exception e) {
                return HealthStatus.unhealthy("native-queue", "Reactive native queue health check failed: " + e.getMessage());
            }
        }

        private Future<HealthStatus> checkNativeQueueReactive() {
            String sql = "SELECT COUNT(*) FROM queue_messages WHERE status = 'AVAILABLE'";
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
                if (errorMsg != null && errorMsg.contains("relation \"queue_messages\" does not exist")) {
                    return Future.succeededFuture(HealthStatus.unhealthy("native-queue",
                        "FATAL: queue_messages table does not exist - schema not initialized properly"));
                }
                return Future.succeededFuture(HealthStatus.unhealthy("native-queue", "Failed to check native queue: " + errorMsg));
            });
        }
    }
    
    private class DeadLetterQueueHealthCheck implements HealthCheck {
        @Override
        public HealthStatus check() {
            // Use reactive approach - block on the result for compatibility with synchronous interface
            try {
                return checkDeadLetterQueueReactive().toCompletionStage().toCompletableFuture().get();
            } catch (Exception e) {
                return HealthStatus.unhealthy("dead-letter-queue", "Reactive dead letter queue health check failed: " + e.getMessage());
            }
        }

        private Future<HealthStatus> checkDeadLetterQueueReactive() {
            String sql = "SELECT COUNT(*) FROM dead_letter_queue WHERE failed_at > NOW() - INTERVAL '1 hour'";
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
                if (errorMsg != null && errorMsg.contains("relation \"dead_letter_queue\" does not exist")) {
                    return Future.succeededFuture(HealthStatus.unhealthy("dead-letter-queue",
                        "FATAL: dead_letter_queue table does not exist - schema not initialized properly"));
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
