package dev.mars.peegeeq.db.resilience;

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


import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for BackpressureManager.
 * 
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
class BackpressureManagerTest {

    private BackpressureManager backpressureManager;

    @BeforeEach
    void setUp() {
        backpressureManager = new BackpressureManager(3, Duration.ofSeconds(1));
    }

    @Test
    void testBackpressureManagerInitialization() {
        assertNotNull(backpressureManager);
        
        BackpressureManager.BackpressureMetrics metrics = backpressureManager.getMetrics();
        assertEquals(3, metrics.getMaxConcurrentOperations());
        assertEquals(3, metrics.getAvailablePermits());
        assertEquals(0, metrics.getActiveOperations());
        assertEquals(0, metrics.getTotalRequests());
    }

    @Test
    void testSuccessfulOperation() throws Exception {
        String result = backpressureManager.execute("test-operation", () -> "success");
        assertEquals("success", result);
        
        BackpressureManager.BackpressureMetrics metrics = backpressureManager.getMetrics();
        assertEquals(1, metrics.getTotalRequests());
        assertEquals(1, metrics.getSuccessfulOperations());
        assertEquals(0, metrics.getFailedOperations());
        assertEquals(1.0, metrics.getCurrentSuccessRate());
    }

    @Test
    void testFailedOperation() {
        System.out.println("🧪 ===== RUNNING INTENTIONAL BACKPRESSURE FAILURE TEST ===== 🧪");
        System.out.println("🔥 **INTENTIONAL TEST** 🔥 This test deliberately throws an exception to verify backpressure failure handling");
        System.out.println("🔥 **INTENTIONAL TEST FAILURE** 🔥 Throwing RuntimeException in backpressure operation");

        assertThrows(BackpressureManager.BackpressureException.class, () -> {
            backpressureManager.execute("test-operation", () -> {
                throw new RuntimeException("🧪 INTENTIONAL TEST FAILURE: Test failure");
            });
        });

        BackpressureManager.BackpressureMetrics metrics = backpressureManager.getMetrics();
        assertEquals(1, metrics.getTotalRequests());
        assertEquals(0, metrics.getSuccessfulOperations());
        assertEquals(1, metrics.getFailedOperations());
        assertEquals(0.0, metrics.getCurrentSuccessRate());

        System.out.println("✅ **SUCCESS** ✅ Backpressure manager correctly handled the intentional failure");
        System.out.println("🧪 ===== INTENTIONAL FAILURE TEST COMPLETED ===== 🧪");
    }

    @Test
    void testVoidOperation() throws Exception {
        AtomicInteger counter = new AtomicInteger(0);
        
        backpressureManager.executeVoid("test-void", counter::incrementAndGet);
        
        assertEquals(1, counter.get());
        
        BackpressureManager.BackpressureMetrics metrics = backpressureManager.getMetrics();
        assertEquals(1, metrics.getSuccessfulOperations());
    }

    @Test
    void testConcurrentOperations() throws InterruptedException {
        int threadCount = 5;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);
        
        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    String result = backpressureManager.execute("concurrent-test", () -> {
                        Thread.sleep(500); // Simulate work
                        return "success";
                    });
                    if ("success".equals(result)) {
                        successCount.incrementAndGet();
                    }
                } catch (BackpressureManager.BackpressureException e) {
                    rejectedCount.incrementAndGet();
                } catch (Exception e) {
                    // Unexpected exception
                } finally {
                    completeLatch.countDown();
                }
            }).start();
        }
        
        startLatch.countDown(); // Start all threads
        assertTrue(completeLatch.await(5, TimeUnit.SECONDS));
        
        // With max 3 concurrent operations and 5 threads, some should succeed and some might be rejected
        assertTrue(successCount.get() > 0);
        assertEquals(threadCount, successCount.get() + rejectedCount.get());
        
        BackpressureManager.BackpressureMetrics metrics = backpressureManager.getMetrics();
        assertEquals(threadCount, metrics.getTotalRequests());
    }

    @Test
    void testOperationTimeout() {
        BackpressureManager shortTimeoutManager = new BackpressureManager(1, Duration.ofMillis(100));
        
        // Start a long-running operation to consume the permit
        CompletableFuture<Void> longOperation = CompletableFuture.runAsync(() -> {
            try {
                shortTimeoutManager.execute("long-operation", () -> {
                    Thread.sleep(1000);
                    return "long result";
                });
            } catch (Exception e) {
                // Expected
            }
        });
        
        // Wait a bit to ensure the first operation has started
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // This operation should timeout waiting for a permit
        assertThrows(BackpressureManager.BackpressureException.class, () -> {
            shortTimeoutManager.execute("timeout-test", () -> "should timeout");
        });
        
        BackpressureManager.BackpressureMetrics metrics = shortTimeoutManager.getMetrics();
        assertTrue(metrics.getTimeoutRequests() > 0);
        assertTrue(metrics.getTimeoutRate() > 0);
        
        longOperation.cancel(true);
    }

    @Test
    void testAdaptiveRateLimiting() throws Exception {
        System.out.println("🧪 ===== RUNNING INTENTIONAL ADAPTIVE RATE LIMITING FAILURE TEST ===== 🧪");
        System.out.println("🔥 **INTENTIONAL TEST** 🔥 This test deliberately generates multiple failures to test adaptive rate limiting");
        System.out.println("🔥 **INTENTIONAL TEST FAILURE** 🔥 Generating 5 failing operations to trigger adaptive limiting");

        // Create a manager that will trigger adaptive limiting
        BackpressureManager adaptiveManager = new BackpressureManager(10, Duration.ofSeconds(1));

        // Generate some failures to lower success rate
        for (int i = 0; i < 5; i++) {
            try {
                adaptiveManager.execute("failing-operation", () -> {
                    throw new RuntimeException("🧪 INTENTIONAL TEST FAILURE: Failure");
                });
            } catch (BackpressureManager.BackpressureException e) {
                // Expected - this is an intentional failure for testing
            }
        }

        // Add some successes
        for (int i = 0; i < 3; i++) {
            adaptiveManager.execute("success-operation", () -> "success");
        }

        BackpressureManager.BackpressureMetrics metrics = adaptiveManager.getMetrics();
        assertTrue(metrics.getCurrentSuccessRate() < 1.0);
        assertTrue(metrics.getAdaptiveLimit() <= metrics.getMaxConcurrentOperations());

        System.out.println("✅ **SUCCESS** ✅ Adaptive rate limiting correctly responded to intentional failures");
        System.out.println("🧪 ===== INTENTIONAL FAILURE TEST COMPLETED ===== 🧪");
    }

    @Test
    void testMetricsReset() throws Exception {
        // Generate some metrics
        backpressureManager.execute("test", () -> "success");
        try {
            backpressureManager.execute("test", () -> {
                throw new RuntimeException("failure");
            });
        } catch (BackpressureManager.BackpressureException e) {
            // Expected
        }
        
        BackpressureManager.BackpressureMetrics beforeReset = backpressureManager.getMetrics();
        assertTrue(beforeReset.getTotalRequests() > 0);
        
        // Reset metrics
        backpressureManager.resetMetrics();
        
        BackpressureManager.BackpressureMetrics afterReset = backpressureManager.getMetrics();
        assertEquals(0, afterReset.getTotalRequests());
        assertEquals(0, afterReset.getSuccessfulOperations());
        assertEquals(0, afterReset.getFailedOperations());
        assertEquals(1.0, afterReset.getCurrentSuccessRate());
    }

    @Test
    void testLimitAdjustment() throws Exception {
        // Test increasing limit
        backpressureManager.adjustLimit(5);
        
        BackpressureManager.BackpressureMetrics metrics = backpressureManager.getMetrics();
        assertEquals(5, metrics.getAvailablePermits());
        
        // Test decreasing limit
        backpressureManager.adjustLimit(2);
        
        metrics = backpressureManager.getMetrics();
        assertEquals(2, metrics.getAvailablePermits());
        
        // Test invalid limit
        assertThrows(IllegalArgumentException.class, () -> {
            backpressureManager.adjustLimit(0);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            backpressureManager.adjustLimit(-1);
        });
    }

    @Test
    void testUtilizationCalculation() throws Exception {
        // Start some long-running operations
        int activeOperations = 2;
        CountDownLatch operationsStarted = new CountDownLatch(activeOperations);
        CountDownLatch operationsCanComplete = new CountDownLatch(1);
        
        for (int i = 0; i < activeOperations; i++) {
            CompletableFuture.runAsync(() -> {
                try {
                    backpressureManager.execute("utilization-test", () -> {
                        operationsStarted.countDown();
                        operationsCanComplete.await();
                        return "success";
                    });
                } catch (Exception e) {
                    // Handle exceptions
                }
            });
        }
        
        // Wait for operations to start
        assertTrue(operationsStarted.await(1, TimeUnit.SECONDS));
        
        BackpressureManager.BackpressureMetrics metrics = backpressureManager.getMetrics();
        double expectedUtilization = (double) activeOperations / 3; // 3 is max concurrent operations
        assertEquals(expectedUtilization, metrics.getUtilization(), 0.01);
        
        // Allow operations to complete
        operationsCanComplete.countDown();
    }

    @Test
    void testRejectionRate() throws Exception {
        // Create a manager with very short timeout for this test
        BackpressureManager quickTimeoutManager = new BackpressureManager(3, Duration.ofMillis(10));

        // Fill up all permits with long-running operations
        int maxOperations = 3;
        CountDownLatch operationsStarted = new CountDownLatch(maxOperations);
        CountDownLatch operationsCanComplete = new CountDownLatch(1);

        for (int i = 0; i < maxOperations; i++) {
            CompletableFuture.runAsync(() -> {
                try {
                    quickTimeoutManager.execute("blocking-operation", () -> {
                        operationsStarted.countDown();
                        operationsCanComplete.await();
                        return "success";
                    });
                } catch (Exception e) {
                    // Handle exceptions
                }
            });
        }

        // Wait for all permits to be consumed
        assertTrue(operationsStarted.await(1, TimeUnit.SECONDS));

        // Try to execute more operations - these should timeout/be rejected
        int rejectedOperations = 0;
        for (int i = 0; i < 5; i++) {
            try {
                quickTimeoutManager.execute("should-be-rejected", () -> "success");
            } catch (BackpressureManager.BackpressureException e) {
                rejectedOperations++;
            }
        }

        assertTrue(rejectedOperations > 0);

        // Allow original operations to complete
        operationsCanComplete.countDown();
    }

    @Test
    void testInterruptedOperation() throws Exception {
        Thread testThread = new Thread(() -> {
            try {
                backpressureManager.execute("interrupted-test", () -> {
                    Thread.currentThread().interrupt();
                    return "should not complete";
                });
                fail("Should have thrown BackpressureException");
            } catch (BackpressureManager.BackpressureException e) {
                assertTrue(e.getMessage().contains("interrupted"));
            }
        });
        
        testThread.start();
        testThread.join(1000);
        assertFalse(testThread.isAlive());
    }

    @Test
    void testMetricsToString() throws Exception {
        backpressureManager.execute("test", () -> "success");
        
        BackpressureManager.BackpressureMetrics metrics = backpressureManager.getMetrics();
        String metricsString = metrics.toString();
        
        assertNotNull(metricsString);
        assertTrue(metricsString.contains("maxConcurrentOperations=3"));
        assertTrue(metricsString.contains("totalRequests=1"));
        assertTrue(metricsString.contains("successfulOperations=1"));
        assertTrue(metricsString.contains("utilization="));
        assertTrue(metricsString.contains("rejectionRate="));
    }

    @Test
    void testSuccessRateAdaptation() throws Exception {
        System.out.println("🧪 ===== RUNNING INTENTIONAL SUCCESS RATE ADAPTATION FAILURE TEST ===== 🧪");
        System.out.println("🔥 **INTENTIONAL TEST** 🔥 This test deliberately generates high failure rate to test success rate adaptation");
        System.out.println("🔥 **INTENTIONAL TEST FAILURE** 🔥 Generating 8 failing operations to test success rate adaptation");

        // Test that success rate affects adaptive limiting
        BackpressureManager adaptiveManager = new BackpressureManager(10, Duration.ofSeconds(1));

        // Start with high failure rate
        for (int i = 0; i < 8; i++) {
            try {
                adaptiveManager.execute("failing", () -> {
                    throw new RuntimeException("🧪 INTENTIONAL TEST FAILURE: failure");
                });
            } catch (BackpressureManager.BackpressureException e) {
                // Expected - this is an intentional failure for testing
            }
        }

        // Add fewer successes
        for (int i = 0; i < 2; i++) {
            adaptiveManager.execute("success", () -> "success");
        }

        BackpressureManager.BackpressureMetrics metrics = adaptiveManager.getMetrics();
        assertEquals(0.2, metrics.getCurrentSuccessRate(), 0.01); // 2 success out of 10 total
        assertTrue(metrics.getAdaptiveLimit() < metrics.getMaxConcurrentOperations());

        System.out.println("SUCCESS: Success rate adaptation correctly responded to intentional failures");
        System.out.println("=== INTENTIONAL FAILURE TEST COMPLETED ===");
    }

    @Test
    void testCounterReset() throws Exception {
        BackpressureManager resetTestManager = new BackpressureManager(10, Duration.ofSeconds(1));
        
        // Generate many operations to trigger counter reset
        for (int i = 0; i < 1001; i++) {
            if (i % 2 == 0) {
                resetTestManager.execute("success", () -> "success");
            } else {
                try {
                    resetTestManager.execute("failure", () -> {
                        throw new RuntimeException("failure");
                    });
                } catch (BackpressureManager.BackpressureException e) {
                    // Expected
                }
            }
        }
        
        BackpressureManager.BackpressureMetrics metrics = resetTestManager.getMetrics();
        // Counters should have been reset, so values should be less than 1001
        assertTrue(metrics.getSuccessfulOperations() < 1001);
        assertTrue(metrics.getFailedOperations() < 1001);
        // But success rate should still be meaningful
        assertTrue(metrics.getCurrentSuccessRate() > 0.4 && metrics.getCurrentSuccessRate() < 0.6);
    }
}
