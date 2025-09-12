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


import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
public class HealthCheckManager {
    private static final Logger logger = LoggerFactory.getLogger(HealthCheckManager.class);
    
    private final DataSource dataSource;
    private final Pool reactivePool;
    private final Duration checkInterval;
    private final Duration timeout;
    private final ScheduledExecutorService scheduler;
    private final Map<String, HealthCheck> healthChecks;
    private final Map<String, HealthStatus> lastResults;
    private volatile boolean running = false;
    
    /**
     * Legacy constructor using DataSource.
     * @deprecated Use HealthCheckManager(Pool, Duration, Duration) for reactive patterns
     */
    @Deprecated
    public HealthCheckManager(DataSource dataSource, Duration checkInterval, Duration timeout) {
        this.dataSource = dataSource;
        this.reactivePool = null;
        this.checkInterval = checkInterval;
        this.timeout = timeout;
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "peegeeq-health-check");
            t.setDaemon(false); // Changed to false to ensure proper shutdown
            return t;
        });
        this.healthChecks = new ConcurrentHashMap<>();
        this.lastResults = new ConcurrentHashMap<>();
        
        registerDefaultHealthChecks();
    }

    /**
     * Modern reactive constructor using Vert.x Pool.
     * This is the preferred constructor for Vert.x 5.x reactive patterns.
     */
    public HealthCheckManager(Pool reactivePool, Duration checkInterval, Duration timeout) {
        this.dataSource = null;
        this.reactivePool = reactivePool;
        this.checkInterval = checkInterval;
        this.timeout = timeout;
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
        // Database connectivity check
        registerHealthCheck("database", new DatabaseHealthCheck());
        
        // Queue health checks
        registerHealthCheck("outbox-queue", new OutboxQueueHealthCheck());
        registerHealthCheck("native-queue", new NativeQueueHealthCheck());
        registerHealthCheck("dead-letter-queue", new DeadLetterQueueHealthCheck());
        
        // System resource checks
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
        
        running = true;
        scheduler.scheduleAtFixedRate(this::performHealthChecks, 0, 
            checkInterval.toMillis(), TimeUnit.MILLISECONDS);
        
        logger.info("Health check manager started with interval: {}", checkInterval);
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
    
    private void performHealthChecks() {
        logger.debug("Performing health checks");
        
        for (Map.Entry<String, HealthCheck> entry : healthChecks.entrySet()) {
            String name = entry.getKey();
            HealthCheck check = entry.getValue();
            
            CompletableFuture<HealthStatus> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return check.check();
                } catch (Exception e) {
                    logger.warn("Health check failed: {}", name, e);
                    return HealthStatus.unhealthy(name, "Health check threw exception: " + e.getMessage());
                }
            }, scheduler);
            
            try {
                HealthStatus status = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                lastResults.put(name, status);
                
                if (!status.isHealthy()) {
                    logger.warn("Health check failed: {} - {}", name, status.getMessage());
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
    
    public OverallHealthStatus getOverallHealth() {
        Map<String, HealthStatus> currentResults = new HashMap<>(lastResults);
        
        boolean allHealthy = currentResults.values().stream().allMatch(HealthStatus::isHealthy);
        String status = allHealthy ? "UP" : "DOWN";
        
        return new OverallHealthStatus(status, currentResults, Instant.now());
    }
    
    public HealthStatus getHealthStatus(String checkName) {
        return lastResults.get(checkName);
    }
    
    public boolean isHealthy() {
        return lastResults.values().stream().allMatch(HealthStatus::isHealthy);
    }
    
    // Default health check implementations
    private class DatabaseHealthCheck implements HealthCheck {
        @Override
        public HealthStatus check() {
            if (reactivePool != null) {
                // Use reactive approach - block on the result for compatibility with synchronous interface
                try {
                    return checkDatabaseReactive().toCompletionStage().toCompletableFuture().get();
                } catch (Exception e) {
                    return HealthStatus.unhealthy("database", "Reactive database health check failed: " + e.getMessage());
                }
            } else {
                // Use legacy JDBC approach
                try (Connection conn = dataSource.getConnection()) {
                    if (!conn.isValid(5)) {
                        return HealthStatus.unhealthy("database", "Database connection is not valid");
                    }

                    // Test basic query
                    try (PreparedStatement stmt = conn.prepareStatement("SELECT 1");
                         ResultSet rs = stmt.executeQuery()) {

                        if (rs.next() && rs.getInt(1) == 1) {
                            return HealthStatus.healthy("database");
                        } else {
                            return HealthStatus.unhealthy("database", "Database query returned unexpected result");
                        }
                    }
                } catch (SQLException e) {
                    return HealthStatus.unhealthy("database", "Database connection failed: " + e.getMessage());
                }
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
            if (reactivePool != null) {
                // Use reactive approach - block on the result for compatibility with synchronous interface
                try {
                    return checkOutboxQueueReactive().toCompletionStage().toCompletableFuture().get();
                } catch (Exception e) {
                    return HealthStatus.unhealthy("outbox-queue", "Reactive outbox queue health check failed: " + e.getMessage());
                }
            } else {
                // Use legacy JDBC approach
                try (Connection conn = dataSource.getConnection()) {
                    // Check if outbox table exists and is accessible
                    String sql = "SELECT COUNT(*) FROM outbox WHERE status = 'PENDING' AND created_at > NOW() - INTERVAL '1 hour'";
                    try (PreparedStatement stmt = conn.prepareStatement(sql);
                         ResultSet rs = stmt.executeQuery()) {

                        if (rs.next()) {
                            long pendingCount = rs.getLong(1);
                            Map<String, Object> details = new HashMap<>();
                            details.put("pending_messages", pendingCount);

                            if (pendingCount > 10000) {
                                return HealthStatus.unhealthy("outbox-queue", "Too many pending messages: " + pendingCount, details);
                            }

                            return HealthStatus.healthy("outbox-queue", details);
                        }
                    }
                } catch (SQLException e) {
                    return HealthStatus.unhealthy("outbox-queue", "Failed to check outbox queue: " + e.getMessage());
                }

                return HealthStatus.unhealthy("outbox-queue", "Unable to verify outbox queue status");
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
                return Future.succeededFuture(HealthStatus.unhealthy("outbox-queue", "Failed to check outbox queue: " + throwable.getMessage()));
            });
        }
    }
    
    private class NativeQueueHealthCheck implements HealthCheck {
        @Override
        public HealthStatus check() {
            if (reactivePool != null) {
                // Use reactive approach - block on the result for compatibility with synchronous interface
                try {
                    return checkNativeQueueReactive().toCompletionStage().toCompletableFuture().get();
                } catch (Exception e) {
                    return HealthStatus.unhealthy("native-queue", "Reactive native queue health check failed: " + e.getMessage());
                }
            } else {
                // Use legacy JDBC approach
                try (Connection conn = dataSource.getConnection()) {
                    String sql = "SELECT COUNT(*) FROM queue_messages WHERE status = 'AVAILABLE'";
                    try (PreparedStatement stmt = conn.prepareStatement(sql);
                         ResultSet rs = stmt.executeQuery()) {

                        if (rs.next()) {
                            long availableCount = rs.getLong(1);
                            Map<String, Object> details = new HashMap<>();
                            details.put("available_messages", availableCount);

                            return HealthStatus.healthy("native-queue", details);
                        }
                    }
                } catch (SQLException e) {
                    return HealthStatus.unhealthy("native-queue", "Failed to check native queue: " + e.getMessage());
                }

                return HealthStatus.unhealthy("native-queue", "Unable to verify native queue status");
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
                return Future.succeededFuture(HealthStatus.unhealthy("native-queue", "Failed to check native queue: " + throwable.getMessage()));
            });
        }
    }
    
    private class DeadLetterQueueHealthCheck implements HealthCheck {
        @Override
        public HealthStatus check() {
            if (reactivePool != null) {
                // Use reactive approach - block on the result for compatibility with synchronous interface
                try {
                    return checkDeadLetterQueueReactive().toCompletionStage().toCompletableFuture().get();
                } catch (Exception e) {
                    return HealthStatus.unhealthy("dead-letter-queue", "Reactive dead letter queue health check failed: " + e.getMessage());
                }
            } else {
                // Use legacy JDBC approach
                try (Connection conn = dataSource.getConnection()) {
                    String sql = "SELECT COUNT(*) FROM dead_letter_queue WHERE failed_at > NOW() - INTERVAL '1 hour'";
                    try (PreparedStatement stmt = conn.prepareStatement(sql);
                         ResultSet rs = stmt.executeQuery()) {

                        if (rs.next()) {
                            long recentFailures = rs.getLong(1);
                            Map<String, Object> details = new HashMap<>();
                            details.put("recent_failures", recentFailures);

                            if (recentFailures > 100) {
                                return HealthStatus.unhealthy("dead-letter-queue",
                                    "High number of recent failures: " + recentFailures, details);
                            }

                            return HealthStatus.healthy("dead-letter-queue", details);
                        }
                    }
                } catch (SQLException e) {
                    return HealthStatus.unhealthy("dead-letter-queue", "Failed to check dead letter queue: " + e.getMessage());
                }

                return HealthStatus.unhealthy("dead-letter-queue", "Unable to verify dead letter queue status");
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
                return Future.succeededFuture(HealthStatus.unhealthy("dead-letter-queue", "Failed to check dead letter queue: " + throwable.getMessage()));
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
