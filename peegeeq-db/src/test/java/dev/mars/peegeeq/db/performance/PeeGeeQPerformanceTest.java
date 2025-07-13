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
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.metrics.PeeGeeQMetrics;
import dev.mars.peegeeq.db.resilience.BackpressureManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance tests for PeeGeeQ system.
 * 
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
@Testcontainers
@EnabledIfSystemProperty(named = "peegeeq.performance.tests", matches = "true")
class PeeGeeQPerformanceTest {

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("perf_test")
            .withUsername("test_user")
            .withPassword("test_pass")
            .withSharedMemorySize(256 * 1024 * 1024L); // 256MB shared memory for better performance

    private PeeGeeQManager manager;

    @BeforeEach
    void setUp() {
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
        testProps.setProperty("peegeeq.database.pool.max-size", "50");
        testProps.setProperty("peegeeq.queue.batch-size", "100");
        testProps.setProperty("peegeeq.queue.polling-interval", "PT100MS");
        testProps.setProperty("peegeeq.metrics.enabled", "true");
        testProps.setProperty("peegeeq.metrics.reporting-interval", "PT5S");

        testProps.forEach((key, value) -> System.setProperty(key.toString(), value.toString()));

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("performance");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close();
        }
        System.getProperties().entrySet().removeIf(entry -> 
            entry.getKey().toString().startsWith("peegeeq."));
    }

    @Test
    void testHighThroughputMetricsRecording() throws Exception {
        PeeGeeQMetrics metrics = manager.getMetrics();
        metrics.bindTo(manager.getMeterRegistry());

        int threadCount = 10;
        int operationsPerThread = 1000;
        int totalOperations = threadCount * operationsPerThread;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
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
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS));
        executor.shutdown();

        Instant endTime = Instant.now();
        Duration totalDuration = Duration.between(startTime, endTime);

        // Performance assertions
        assertTrue(totalDuration.toMillis() < 10000, "Should complete within 10 seconds");
        
        PeeGeeQMetrics.MetricsSummary summary = metrics.getSummary();
        assertEquals(totalOperations, summary.getMessagesSent());
        assertEquals(totalOperations, summary.getMessagesReceived());
        assertEquals(totalOperations, summary.getMessagesProcessed());
        
        double throughput = totalOperations / (totalDuration.toMillis() / 1000.0);
        System.out.printf("Metrics throughput: %.2f operations/second%n", throughput);
        assertTrue(throughput > 1000, "Should achieve at least 1000 ops/sec");
    }

    @Test
    void testBackpressureUnderLoad() throws Exception {
        BackpressureManager backpressureManager = manager.getBackpressureManager();
        
        int threadCount = 20;
        int operationsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);
        AtomicInteger timeoutCount = new AtomicInteger(0);

        Instant startTime = Instant.now();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        try {
                            String result = backpressureManager.execute("perf-test", () -> {
                                // Simulate work
                                Thread.sleep(10);
                                return "success";
                            });
                            if ("success".equals(result)) {
                                successCount.incrementAndGet();
                            }
                        } catch (BackpressureManager.BackpressureException e) {
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
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(60, TimeUnit.SECONDS));
        executor.shutdown();

        Instant endTime = Instant.now();
        Duration totalDuration = Duration.between(startTime, endTime);

        BackpressureManager.BackpressureMetrics metrics = backpressureManager.getMetrics();
        
        System.out.printf("Backpressure Performance Results:%n");
        System.out.printf("  Total Duration: %d ms%n", totalDuration.toMillis());
        System.out.printf("  Successful Operations: %d%n", successCount.get());
        System.out.printf("  Rejected Operations: %d%n", rejectedCount.get());
        System.out.printf("  Timeout Operations: %d%n", timeoutCount.get());
        System.out.printf("  Success Rate: %.2f%%%n", metrics.getCurrentSuccessRate() * 100);
        System.out.printf("  Utilization: %.2f%%%n", metrics.getUtilization() * 100);

        // Performance assertions
        assertTrue(successCount.get() > 0, "Should have some successful operations");
        assertTrue(metrics.getCurrentSuccessRate() > 0.5, "Success rate should be reasonable under load");
    }

    @Test
    void testConcurrentHealthChecks() throws Exception {
        var healthManager = manager.getHealthCheckManager();
        
        int threadCount = 10;
        int checksPerThread = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        
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
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS));
        executor.shutdown();

        Instant endTime = Instant.now();
        Duration totalDuration = Duration.between(startTime, endTime);
        
        int totalChecks = threadCount * checksPerThread;
        double avgCheckTime = totalCheckTime.get() / (double) totalChecks;
        double throughput = totalChecks / (totalDuration.toMillis() / 1000.0);

        System.out.printf("Health Check Performance Results:%n");
        System.out.printf("  Total Checks: %d%n", totalChecks);
        System.out.printf("  Healthy Results: %d%n", healthyCount.get());
        System.out.printf("  Average Check Time: %.2f ms%n", avgCheckTime);
        System.out.printf("  Throughput: %.2f checks/second%n", throughput);

        // Performance assertions
        assertTrue(avgCheckTime < 100, "Average health check should be under 100ms");
        assertTrue(throughput > 100, "Should achieve at least 100 health checks/sec");
        assertTrue(healthyCount.get() > totalChecks * 0.9, "Most health checks should be healthy");
    }

    @Test
    void testDatabaseConnectionPoolPerformance() throws Exception {
        var dataSource = manager.getDataSource();
        
        int threadCount = 20;
        int queriesPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        
        AtomicInteger successfulQueries = new AtomicInteger(0);
        AtomicLong totalQueryTime = new AtomicLong(0);

        Instant startTime = Instant.now();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < queriesPerThread; j++) {
                        Instant queryStart = Instant.now();
                        
                        try (var conn = dataSource.getConnection();
                             var stmt = conn.prepareStatement("SELECT 1");
                             var rs = stmt.executeQuery()) {
                            
                            if (rs.next() && rs.getInt(1) == 1) {
                                successfulQueries.incrementAndGet();
                            }
                        }
                        
                        Instant queryEnd = Instant.now();
                        totalQueryTime.addAndGet(Duration.between(queryStart, queryEnd).toMillis());
                    }
                } catch (Exception e) {
                    // Handle database errors
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(60, TimeUnit.SECONDS));
        executor.shutdown();

        Instant endTime = Instant.now();
        Duration totalDuration = Duration.between(startTime, endTime);
        
        int totalQueries = threadCount * queriesPerThread;
        double avgQueryTime = totalQueryTime.get() / (double) totalQueries;
        double throughput = totalQueries / (totalDuration.toMillis() / 1000.0);

        System.out.printf("Database Performance Results:%n");
        System.out.printf("  Total Queries: %d%n", totalQueries);
        System.out.printf("  Successful Queries: %d%n", successfulQueries.get());
        System.out.printf("  Average Query Time: %.2f ms%n", avgQueryTime);
        System.out.printf("  Throughput: %.2f queries/second%n", throughput);

        // Performance assertions
        assertTrue(avgQueryTime < 50, "Average query time should be under 50ms");
        assertTrue(throughput > 500, "Should achieve at least 500 queries/sec");
        assertEquals(totalQueries, successfulQueries.get(), "All queries should succeed");
    }

    @Test
    void testMemoryUsageUnderLoad() throws Exception {
        Runtime runtime = Runtime.getRuntime();
        
        // Force garbage collection and get baseline
        System.gc();
        Thread.sleep(1000);
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
        
        // Final memory check
        System.gc();
        Thread.sleep(1000);
        long finalMemory = runtime.totalMemory() - runtime.freeMemory();
        long totalIncrease = finalMemory - baselineMemory;
        
        System.out.printf("Memory Usage Results:%n");
        System.out.printf("  Baseline Memory: %d MB%n", baselineMemory / 1024 / 1024);
        System.out.printf("  Final Memory: %d MB%n", finalMemory / 1024 / 1024);
        System.out.printf("  Memory Increase: %d MB%n", totalIncrease / 1024 / 1024);
        
        // Memory should not increase significantly
        assertTrue(totalIncrease < 50 * 1024 * 1024, // 50MB
            "Memory increase should be minimal after operations");
    }
}
