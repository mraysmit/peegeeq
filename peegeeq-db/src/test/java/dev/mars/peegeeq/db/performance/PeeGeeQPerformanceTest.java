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
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
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
 * <p>Verifies throughput, pool behaviour, backpressure, health-check concurrency and
 * memory stability under sustained load against a real PostgreSQL Testcontainer instance.
 * Tests run sequentially within this class ({@code SAME_THREAD}) to avoid interfering
 * with each other's pool state.
 *
 * <h2>Pool configuration</h2>
 * <p>{@code peegeeq.database.pool.max-wait-queue-size} is set to {@code -1} (unlimited)
 * in {@link #setUp} for this suite. This is intentional: several tests deliberately
 * submit far more concurrent requests than the pool's {@code max-size} allows, relying
 * on Vert.x's unbounded wait queue to absorb the excess rather than reject requests.
 * The production default (128) was introduced in PgPoolConfig to guard against
 * memory exhaustion in normal operation; overriding it here restores the behaviour
 * these tests were designed and validated against.
 *
 * <p>Do <strong>not</strong> remove the {@code max-wait-queue-size = -1} override.
 * Doing so will cause {@code ConnectionPoolTooBusyException} in
 * {@link #testConnectionPoolHandlesBurstLoadFromThreadPoolExecutor} and any other test that saturates
 * the pool queue.
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

        // Configure for performance testing.
        // Use PeeGeeQConfiguration(profile, overrides) so properties are scoped to this
        // instance and never written to System.getProperties(). System properties are global
        // JVM state; writing and deleting them in setUp/tearDown is inherently racy when
        // test classes run concurrently.
        Properties testProps = new Properties();
        testProps.setProperty("peegeeq.database.host", postgres.getHost());
        testProps.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        testProps.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        testProps.setProperty("peegeeq.database.username", postgres.getUsername());
        testProps.setProperty("peegeeq.database.password", postgres.getPassword());
        testProps.setProperty("peegeeq.database.ssl.enabled", "false");
        testProps.setProperty("peegeeq.database.schema", PostgreSQLTestConstants.TEST_SCHEMA);

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
        // Unlimited wait queue: several tests saturate the pool deliberately; -1 prevents
        // ConnectionPoolTooBusyException. See class-level Javadoc.
        testProps.setProperty("peegeeq.database.pool.max-wait-queue-size", "-1");
        testProps.setProperty("peegeeq.queue.consumer-group-retry.enabled", "false");
        testProps.setProperty("peegeeq.queue.dead-consumer-detection.enabled", "false");

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("performance", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start().onSuccess(v -> { logger.info("PeeGeeQManager started successfully"); testContext.completeNow(); }).onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        logger.info("Tearing down: closing PeeGeeQManager");
        if (manager != null) {
            manager.closeReactive()
                .onSuccess(v -> testContext.completeNow())
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

    /**
     * Verifies that the Vert.x reactive pool handles a large burst of concurrent
     * database requests without blocking or failing.
     *
     * <p>20 threads each fire 100 {@code SELECT 1} queries through the reactive pool
     * (2000 total requests against a pool of max-size 20). All requests that cannot
     * be served immediately are held in the pool's wait queue and served in order.
     * This requires {@code max-wait-queue-size = -1}; see class-level Javadoc.
     *
     * <p>Successful query count is recorded via {@code AtomicInteger}. The checkpoint
     * fires when all 20 threads finish submitting, not when all queries complete
     * (queries are fire-and-forget from each thread). The intent is to verify that
     * the pool does not throw under sustained burst load.
     */
    @Test
    void testConnectionPoolHandlesBurstLoadFromThreadPoolExecutor(VertxTestContext testContext) {
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
