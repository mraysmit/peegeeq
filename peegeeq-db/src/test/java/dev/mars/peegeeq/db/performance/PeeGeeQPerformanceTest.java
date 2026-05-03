package dev.mars.peegeeq.db.performance;

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


import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.SharedPostgresTestExtension;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.metrics.PeeGeeQMetrics;
import dev.mars.peegeeq.db.resilience.BackpressureManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import dev.mars.peegeeq.test.categories.TestCategories;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Performance tests for PeeGeeQ system.
 *
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 *
 * <p><strong>IMPORTANT:</strong> This test uses SharedPostgresTestExtension for shared container.
 * Schema is initialized once by the extension.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
@Tag(TestCategories.PERFORMANCE)
@ExtendWith({SharedPostgresTestExtension.class, VertxExtension.class})
@EnabledIfSystemProperty(named = "peegeeq.performance.tests", matches = "true")
@Execution(ExecutionMode.SAME_THREAD)
class PeeGeeQPerformanceTest {

    private static final Logger logger = LoggerFactory.getLogger(PeeGeeQPerformanceTest.class);

    private PeeGeeQManager manager;
    private Vertx vertx;

    @BeforeAll
    static void logSystemInfo() {
        logger.info("=== PEEGEEQ PERFORMANCE TEST SUITE ===");
        logger.info("System Information:");
        logger.info(SystemInfoCollector.formatAsSummary());
        logger.info("=== Starting Performance Tests ===");
    }

    @BeforeEach
    void setUp(Vertx injectedVertx, VertxTestContext testContext) {
        this.vertx = injectedVertx;
        PostgreSQLContainer postgres = SharedPostgresTestExtension.getContainer();

        // Configure for performance testing
        Properties testProps = new Properties();
        testProps.setProperty("peegeeq.database.host", postgres.getHost());
        testProps.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        testProps.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        testProps.setProperty("peegeeq.database.username", postgres.getUsername());
        testProps.setProperty("peegeeq.database.password", postgres.getPassword());
        testProps.setProperty("peegeeq.database.ssl.enabled", "false");
        
        // Performance optimized settings
        testProps.setProperty("peegeeq.database.pool.min-size", "10");
        testProps.setProperty("peegeeq.database.pool.max-size", "20");
        testProps.setProperty("peegeeq.database.pool.shared", "false");
        testProps.setProperty("peegeeq.database.pool.idle-timeout-ms", "2000");
        testProps.setProperty("peegeeq.database.pool.connection-timeout-ms", "5000");
        testProps.setProperty("peegeeq.queue.batch-size", "100");
        testProps.setProperty("peegeeq.queue.polling-interval", "PT100MS");
        testProps.setProperty("peegeeq.metrics.enabled", "true");
        testProps.setProperty("peegeeq.metrics.reporting-interval", "PT5S");

        testProps.forEach((key, value) -> System.setProperty(key.toString(), value.toString()));

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("performance");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start().onSuccess(v -> { logger.info("PeeGeeQManager started successfully"); testContext.completeNow(); }).onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        logger.info("Tearing down: closing PeeGeeQManager");
        if (manager != null) {
            manager.closeReactive()
                .onSuccess(v -> {
                    System.getProperties().entrySet().removeIf(entry -> 
                        entry.getKey().toString().startsWith("peegeeq."));
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }
    }

    @Test
    void testHighThroughputMetricsRecording(VertxTestContext testContext) {
        PeeGeeQMetrics metrics = manager.getMetrics();
        metrics.bindTo(manager.getMeterRegistry());

        int threadCount = 10;
        int operationsPerThread = 1000;
        int totalOperations = threadCount * operationsPerThread;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        Checkpoint latch = testContext.checkpoint(threadCount);
        AtomicLong totalTime = new AtomicLong(0);

        Instant startTime = Instant.now();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    Instant threadStart = Instant.now();
                    
                    for (int j = 0; j < operationsPerThread; j++) {
                        metrics.recordMessageSent("perf-topic");
                        metrics.recordMessageReceived("perf-topic");
                        metrics.recordMessageProcessed("perf-topic", Duration.ofMillis(1));
                        
                        if (j % 10 == 0) {
                            metrics.recordMessageFailed("perf-topic", "test-error");
                        }
                    }
                    
                    Instant threadEnd = Instant.now();
                    totalTime.addAndGet(Duration.between(threadStart, threadEnd).toMillis());
                } finally {
                    latch.flag();
                }
            });
        }

    }

    @Test
    void testBackpressureUnderLoad(VertxTestContext testContext) {
        BackpressureManager backpressureManager = manager.getBackpressureManager();
        
        int threadCount = 20;
        int operationsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        Checkpoint latch = testContext.checkpoint(threadCount);
        
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);
        AtomicInteger timeoutCount = new AtomicInteger(0);

        Instant startTime = Instant.now();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        try {
                            String result = backpressureManager.execute("perf-test", () -> "success");
                            if ("success".equals(result)) {
                                successCount.incrementAndGet();
                            }
                        } catch (Exception e) {
                            if (e.getMessage().contains("timed out")) {
                                timeoutCount.incrementAndGet();
                            } else {
                                rejectedCount.incrementAndGet();
                            }
                        }
                    }
                } catch (Exception e) {
                    // Handle unexpected exceptions
                } finally {
                    latch.flag();
                }
            });
        }

    }

    @Test
    void testConcurrentHealthChecks(VertxTestContext testContext) {
        var healthManager = manager.getHealthCheckManager();
        
        int threadCount = 10;
        int checksPerThread = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        Checkpoint latch = testContext.checkpoint(threadCount);
        
        AtomicInteger healthyCount = new AtomicInteger(0);
        AtomicLong totalCheckTime = new AtomicLong(0);

        Instant startTime = Instant.now();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < checksPerThread; j++) {
                        Instant checkStart = Instant.now();
                        boolean healthy = healthManager.isHealthy();
                        Instant checkEnd = Instant.now();
                        
                        totalCheckTime.addAndGet(Duration.between(checkStart, checkEnd).toMillis());
                        
                        if (healthy) {
                            healthyCount.incrementAndGet();
                        }
                    }
                } finally {
                    latch.flag();
                }
            });
        }

    }

    @Test
    void testDatabaseConnectionPoolPerformance(VertxTestContext testContext) {
        int threadCount = 20;
        int queriesPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        Checkpoint latch = testContext.checkpoint(threadCount);

        AtomicInteger successfulQueries = new AtomicInteger(0);
        AtomicLong totalQueryTime = new AtomicLong(0);

        Instant startTime = Instant.now();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < queriesPerThread; j++) {
                        Instant queryStart = Instant.now();

                        // Use reactive patterns without blocking
                        manager.getDatabaseService().getConnectionProvider()
                            .withConnection("peegeeq-main", connection -> {
                                return connection.query("SELECT 1")
                                    .execute()
                                    .map(rowSet -> {
                                        var row = rowSet.iterator().next();
                                        return row.getInteger(0);
                                    });
                            })
                            .onSuccess(result -> {
                                successfulQueries.incrementAndGet();
                                Instant queryEnd = Instant.now();
                                totalQueryTime.addAndGet(Duration.between(queryStart, queryEnd).toMillis());
                            })
                            .onFailure(cause -> logger.warn("Query failed", cause));

                    }
                } catch (Exception e) {
                    logger.warn("Unexpected exception during query execution", e);
                } finally {
                    latch.flag();
                }
            });
        }

    }

    @Test
    void testMemoryUsageUnderLoad(VertxTestContext testContext) {
        Runtime runtime = Runtime.getRuntime();
        
        // Force garbage collection and get baseline
        System.gc();
        
        // Use reactive timer for delay instead of blocking join
        vertx.setTimer(1000, timerId -> {
            long baselineMemory = runtime.totalMemory() - runtime.freeMemory();
            
            PeeGeeQMetrics metrics = manager.getMetrics();
            metrics.bindTo(manager.getMeterRegistry());
            
            // Generate load
            int operations = 10000;
            for (int i = 0; i < operations; i++) {
                metrics.recordMessageSent("memory-test");
                metrics.recordMessageReceived("memory-test");
                metrics.recordMessageProcessed("memory-test", Duration.ofMillis(1));
                
                if (i % 1000 == 0) {
                    // Check memory periodically
                    long currentMemory = runtime.totalMemory() - runtime.freeMemory();
                    long memoryIncrease = currentMemory - baselineMemory;
                    
                    // Memory increase should be reasonable
                    assertTrue(memoryIncrease < 100 * 1024 * 1024, // 100MB
                        "Memory usage should not increase excessively");
                }
            }
            
            // Final memory check - use reactive timer again
            System.gc();
            vertx.setTimer(1000, finalTimerId -> {
                long finalMemory = runtime.totalMemory() - runtime.freeMemory();
                long totalIncrease = finalMemory - baselineMemory;
                
                System.out.printf("Memory Usage Results:%n");
                System.out.printf("  Baseline Memory: %d MB%n", baselineMemory / 1024 / 1024);
                System.out.printf("  Final Memory: %d MB%n", finalMemory / 1024 / 1024);
                System.out.printf("  Memory Increase: %d MB%n", totalIncrease / 1024 / 1024);
                
                // Memory should not increase significantly
                assertTrue(totalIncrease < 50 * 1024 * 1024, // 50MB
                    "Memory increase should be minimal after operations");
                testContext.completeNow();
            });
        });
    }
}
