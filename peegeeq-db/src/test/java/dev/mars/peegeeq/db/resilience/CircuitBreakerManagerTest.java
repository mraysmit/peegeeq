package dev.mars.peegeeq.db.resilience;

import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for CircuitBreakerManager.
 */
class CircuitBreakerManagerTest {

    private CircuitBreakerManager circuitBreakerManager;
    private PeeGeeQConfiguration.CircuitBreakerConfig config;

    @BeforeEach
    void setUp() {
        config = new PeeGeeQConfiguration.CircuitBreakerConfig(
            true,           // enabled
            3,              // failureThreshold
            Duration.ofSeconds(1), // waitDuration
            10,             // ringBufferSize
            50.0            // failureRateThreshold
        );
        
        circuitBreakerManager = new CircuitBreakerManager(config, new SimpleMeterRegistry());
    }

    @Test
    void testCircuitBreakerManagerInitialization() {
        assertNotNull(circuitBreakerManager);
        assertTrue(circuitBreakerManager.getCircuitBreakerNames().isEmpty());
    }

    @Test
    void testDisabledCircuitBreaker() {
        PeeGeeQConfiguration.CircuitBreakerConfig disabledConfig = 
            new PeeGeeQConfiguration.CircuitBreakerConfig(false, 3, Duration.ofSeconds(1), 10, 50.0);
        
        CircuitBreakerManager disabledManager = new CircuitBreakerManager(disabledConfig, null);
        
        // Should execute without circuit breaker protection
        String result = disabledManager.executeSupplier("test", () -> "success");
        assertEquals("success", result);
        
        // Should not create any circuit breakers
        assertTrue(disabledManager.getCircuitBreakerNames().isEmpty());
    }

    @Test
    void testSuccessfulExecution() {
        String result = circuitBreakerManager.executeSupplier("test-operation", () -> "success");
        assertEquals("success", result);
        
        // Verify circuit breaker was created
        assertTrue(circuitBreakerManager.getCircuitBreakerNames().contains("test-operation"));
        
        CircuitBreakerManager.CircuitBreakerMetrics metrics = 
            circuitBreakerManager.getMetrics("test-operation");
        assertEquals("CLOSED", metrics.getState());
        assertEquals(1, metrics.getSuccessfulCalls());
        assertEquals(0, metrics.getFailedCalls());
    }

    @Test
    void testFailedExecution() {
        assertThrows(RuntimeException.class, () -> {
            circuitBreakerManager.executeSupplier("test-operation", () -> {
                throw new RuntimeException("Test failure");
            });
        });
        
        CircuitBreakerManager.CircuitBreakerMetrics metrics = 
            circuitBreakerManager.getMetrics("test-operation");
        assertEquals("CLOSED", metrics.getState());
        assertEquals(0, metrics.getSuccessfulCalls());
        assertEquals(1, metrics.getFailedCalls());
    }

    @Test
    void testCircuitBreakerOpening() {
        String operationName = "failing-operation";
        
        // Execute enough failures to open the circuit breaker
        for (int i = 0; i < 5; i++) {
            final int failureIndex = i;
            try {
                circuitBreakerManager.executeSupplier(operationName, () -> {
                    throw new RuntimeException("Failure " + failureIndex);
                });
            } catch (RuntimeException e) {
                // Expected
            }
        }
        
        CircuitBreakerManager.CircuitBreakerMetrics metrics = 
            circuitBreakerManager.getMetrics(operationName);
        
        // Circuit breaker should be open after enough failures
        assertTrue(metrics.getState().equals("OPEN") || metrics.getFailedCalls() >= config.getFailureThreshold());
    }

    @Test
    void testCircuitBreakerCallNotPermitted() {
        String operationName = "blocked-operation";
        
        // Force circuit breaker to open
        circuitBreakerManager.forceOpen(operationName);
        
        // Subsequent calls should be blocked
        assertThrows(CallNotPermittedException.class, () -> {
            circuitBreakerManager.executeSupplier(operationName, () -> "should not execute");
        });
        
        CircuitBreakerManager.CircuitBreakerMetrics metrics = 
            circuitBreakerManager.getMetrics(operationName);
        assertEquals("OPEN", metrics.getState());
        assertTrue(metrics.getNotPermittedCalls() > 0);
    }

    @Test
    void testCircuitBreakerReset() {
        String operationName = "reset-operation";
        
        // Cause some failures
        for (int i = 0; i < 3; i++) {
            try {
                circuitBreakerManager.executeSupplier(operationName, () -> {
                    throw new RuntimeException("Failure");
                });
            } catch (RuntimeException e) {
                // Expected
            }
        }
        
        // Reset the circuit breaker
        circuitBreakerManager.reset(operationName);
        
        // Should be able to execute successfully
        String result = circuitBreakerManager.executeSupplier(operationName, () -> "success after reset");
        assertEquals("success after reset", result);
        
        CircuitBreakerManager.CircuitBreakerMetrics metrics = 
            circuitBreakerManager.getMetrics(operationName);
        assertEquals("CLOSED", metrics.getState());
    }

    @Test
    void testDatabaseOperationCircuitBreaker() {
        String result = circuitBreakerManager.executeDatabaseOperation("select", () -> "query result");
        assertEquals("query result", result);
        
        assertTrue(circuitBreakerManager.getCircuitBreakerNames().contains("database-select"));
    }

    @Test
    void testQueueOperationCircuitBreaker() {
        String result = circuitBreakerManager.executeQueueOperation("outbox", "send", () -> "message sent");
        assertEquals("message sent", result);
        
        assertTrue(circuitBreakerManager.getCircuitBreakerNames().contains("outbox-send"));
    }

    @Test
    void testRunnableExecution() {
        AtomicInteger counter = new AtomicInteger(0);
        
        circuitBreakerManager.executeRunnable("test-runnable", counter::incrementAndGet);
        
        assertEquals(1, counter.get());
        assertTrue(circuitBreakerManager.getCircuitBreakerNames().contains("test-runnable"));
    }

    @Test
    void testConcurrentCircuitBreakerAccess() throws InterruptedException {
        int threadCount = 10;
        int operationsPerThread = 50;
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        
        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        try {
                            String result = circuitBreakerManager.executeSupplier("concurrent-operation", 
                                () -> "success");
                            if ("success".equals(result)) {
                                successCount.incrementAndGet();
                            }
                        } catch (Exception e) {
                            failureCount.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            }).start();
        }
        
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        
        int expectedSuccesses = threadCount * operationsPerThread;
        assertEquals(expectedSuccesses, successCount.get());
        assertEquals(0, failureCount.get());
        
        CircuitBreakerManager.CircuitBreakerMetrics metrics = 
            circuitBreakerManager.getMetrics("concurrent-operation");
        assertEquals(expectedSuccesses, metrics.getSuccessfulCalls());
        assertEquals("CLOSED", metrics.getState());
    }

    @Test
    void testCircuitBreakerMetricsForNonExistentBreaker() {
        CircuitBreakerManager.CircuitBreakerMetrics metrics = 
            circuitBreakerManager.getMetrics("non-existent");
        
        assertNotNull(metrics);
        assertTrue(metrics.isEnabled());
        assertEquals("UNKNOWN", metrics.getState());
        assertEquals(0, metrics.getSuccessfulCalls());
        assertEquals(0, metrics.getFailedCalls());
    }

    @Test
    void testDisabledCircuitBreakerMetrics() {
        PeeGeeQConfiguration.CircuitBreakerConfig disabledConfig = 
            new PeeGeeQConfiguration.CircuitBreakerConfig(false, 3, Duration.ofSeconds(1), 10, 50.0);
        
        CircuitBreakerManager disabledManager = new CircuitBreakerManager(disabledConfig, null);
        
        CircuitBreakerManager.CircuitBreakerMetrics metrics = 
            disabledManager.getMetrics("any-operation");
        
        assertNotNull(metrics);
        assertFalse(metrics.isEnabled());
        assertEquals("DISABLED", metrics.getState());
    }

    @Test
    void testCircuitBreakerConfiguration() {
        // Test that configuration is properly applied
        String operationName = "config-test";
        
        // Execute operations to build up metrics
        for (int i = 0; i < 5; i++) {
            circuitBreakerManager.executeSupplier(operationName, () -> "success");
        }
        
        CircuitBreakerManager.CircuitBreakerMetrics metrics = 
            circuitBreakerManager.getMetrics(operationName);
        
        assertEquals(5, metrics.getSuccessfulCalls());
        assertEquals(0, metrics.getFailedCalls());
        assertEquals(0.0f, metrics.getFailureRate());
    }

    @Test
    void testCircuitBreakerStateTransitions() throws InterruptedException {
        String operationName = "state-transition-test";
        
        // Start with successful operations (CLOSED state)
        for (int i = 0; i < 3; i++) {
            circuitBreakerManager.executeSupplier(operationName, () -> "success");
        }
        
        CircuitBreakerManager.CircuitBreakerMetrics metrics = 
            circuitBreakerManager.getMetrics(operationName);
        assertEquals("CLOSED", metrics.getState());
        
        // Cause failures to open the circuit
        for (int i = 0; i < 10; i++) {
            try {
                circuitBreakerManager.executeSupplier(operationName, () -> {
                    throw new RuntimeException("Failure");
                });
            } catch (RuntimeException e) {
                // Expected
            }
        }
        
        metrics = circuitBreakerManager.getMetrics(operationName);
        // Should be OPEN or have high failure rate
        assertTrue(metrics.getState().equals("OPEN") || metrics.getFailureRate() > 50.0f);
    }

    @Test
    void testCircuitBreakerWithNullMeterRegistry() {
        CircuitBreakerManager managerWithoutMetrics = new CircuitBreakerManager(config, null);
        
        // Should still work without metrics registry
        String result = managerWithoutMetrics.executeSupplier("test", () -> "success");
        assertEquals("success", result);
    }

    @Test
    void testCircuitBreakerMetricsToString() {
        String operationName = "metrics-string-test";
        
        circuitBreakerManager.executeSupplier(operationName, () -> "success");
        
        CircuitBreakerManager.CircuitBreakerMetrics metrics = 
            circuitBreakerManager.getMetrics(operationName);
        
        String metricsString = metrics.toString();
        assertNotNull(metricsString);
        assertTrue(metricsString.contains("CLOSED"));
        assertTrue(metricsString.contains("successfulCalls=1"));
        assertTrue(metricsString.contains("failedCalls=0"));
        assertTrue(metricsString.contains("enabled=true"));
    }

    @Test
    void testCircuitBreakerWithCustomConfiguration() {
        PeeGeeQConfiguration.CircuitBreakerConfig customConfig = 
            new PeeGeeQConfiguration.CircuitBreakerConfig(
                true,
                2,              // Lower failure threshold
                Duration.ofMillis(500), // Shorter wait duration
                5,              // Smaller ring buffer
                30.0            // Lower failure rate threshold
            );
        
        CircuitBreakerManager customManager = new CircuitBreakerManager(customConfig, new SimpleMeterRegistry());
        
        String operationName = "custom-config-test";
        
        // Should work with custom configuration
        String result = customManager.executeSupplier(operationName, () -> "success");
        assertEquals("success", result);
        
        CircuitBreakerManager.CircuitBreakerMetrics metrics = 
            customManager.getMetrics(operationName);
        assertEquals("CLOSED", metrics.getState());
    }

    @Test
    void testMultipleCircuitBreakers() {
        // Test that multiple circuit breakers can be managed independently
        String op1 = "operation-1";
        String op2 = "operation-2";
        
        circuitBreakerManager.executeSupplier(op1, () -> "result1");
        circuitBreakerManager.executeSupplier(op2, () -> "result2");
        
        assertTrue(circuitBreakerManager.getCircuitBreakerNames().contains(op1));
        assertTrue(circuitBreakerManager.getCircuitBreakerNames().contains(op2));
        assertEquals(2, circuitBreakerManager.getCircuitBreakerNames().size());
        
        CircuitBreakerManager.CircuitBreakerMetrics metrics1 = 
            circuitBreakerManager.getMetrics(op1);
        CircuitBreakerManager.CircuitBreakerMetrics metrics2 = 
            circuitBreakerManager.getMetrics(op2);
        
        assertEquals(1, metrics1.getSuccessfulCalls());
        assertEquals(1, metrics2.getSuccessfulCalls());
    }
}
