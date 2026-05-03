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


import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CircuitBreakerManager.
 *
 * CircuitBreakerManager is a pure registry and factory. Tests verify creation,
 * caching, state inspection, forceOpen, and reset using the CircuitBreaker
 * directly — the same pattern used by production callers (see HealthCheckManager).
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
@Tag(TestCategories.CORE)
class CircuitBreakerManagerTest {

    private CircuitBreakerManager circuitBreakerManager;
    private PeeGeeQConfiguration.CircuitBreakerConfig config;

    @BeforeEach
    void setUp() {
        config = new PeeGeeQConfiguration.CircuitBreakerConfig(
            true,
            3,
            Duration.ofSeconds(1),
            10,
            50.0
        );
        circuitBreakerManager = new CircuitBreakerManager(config, new SimpleMeterRegistry());
    }

    // -------------------------------------------------------------------------
    // Kept tests — these covered the API that survives unchanged
    // -------------------------------------------------------------------------

    @Test
    void testCircuitBreakerManagerInitialization() {
        assertNotNull(circuitBreakerManager);
        assertTrue(circuitBreakerManager.getCircuitBreakerNames().isEmpty());
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

    // -------------------------------------------------------------------------
    // New tests — verify the factory API using CircuitBreaker directly
    // -------------------------------------------------------------------------

    @Test
    void testGetCircuitBreaker_createsAndCaches() {
        CircuitBreaker first = circuitBreakerManager.getCircuitBreaker("cb-cache-test");
        CircuitBreaker second = circuitBreakerManager.getCircuitBreaker("cb-cache-test");

        assertNotNull(first);
        assertSame(first, second, "Same instance must be returned on second call");
        assertTrue(circuitBreakerManager.getCircuitBreakerNames().contains("cb-cache-test"));
    }

    @Test
    void testGetCircuitBreaker_disabledReturnsNull() {
        PeeGeeQConfiguration.CircuitBreakerConfig disabledConfig =
            new PeeGeeQConfiguration.CircuitBreakerConfig(false, 3, Duration.ofSeconds(1), 10, 50.0);
        CircuitBreakerManager disabledManager = new CircuitBreakerManager(disabledConfig, null);

        assertNull(disabledManager.getCircuitBreaker("any-name"));
        assertTrue(disabledManager.getCircuitBreakerNames().isEmpty());
    }

    @Test
    void testForceOpen_circuitBreakerRefusesPermission() {
        circuitBreakerManager.forceOpen("forced-open");

        CircuitBreaker cb = circuitBreakerManager.getCircuitBreaker("forced-open");
        assertFalse(cb.tryAcquirePermission(), "OPEN circuit breaker must refuse permission");

        CircuitBreakerManager.CircuitBreakerMetrics metrics =
            circuitBreakerManager.getMetrics("forced-open");
        assertEquals("OPEN", metrics.getState());
    }

    @Test
    void testReset_returnsCircuitBreakerToClosed() {
        circuitBreakerManager.forceOpen("reset-test");

        circuitBreakerManager.reset("reset-test");

        CircuitBreaker cb = circuitBreakerManager.getCircuitBreaker("reset-test");
        assertTrue(cb.tryAcquirePermission(), "CLOSED circuit breaker must grant permission after reset");
        // Release the acquired permission since we are not executing a real call
        cb.onSuccess(0, TimeUnit.NANOSECONDS);

        assertEquals("CLOSED", circuitBreakerManager.getMetrics("reset-test").getState());
    }

    @Test
    void testMetrics_recordedViaDirectCircuitBreakerCalls() {
        CircuitBreaker cb = circuitBreakerManager.getCircuitBreaker("metrics-test");

        // Bracket pattern required by Resilience4j for metrics to be recorded:
        // tryAcquirePermission() → execute → onSuccess() / onError()
        cb.tryAcquirePermission();
        cb.onSuccess(1, TimeUnit.NANOSECONDS);

        cb.tryAcquirePermission();
        cb.onSuccess(1, TimeUnit.NANOSECONDS);

        cb.tryAcquirePermission();
        cb.onError(1, TimeUnit.NANOSECONDS, new RuntimeException("simulated"));

        CircuitBreakerManager.CircuitBreakerMetrics metrics =
            circuitBreakerManager.getMetrics("metrics-test");

        assertEquals(2, metrics.getSuccessfulCalls());
        assertEquals(1, metrics.getFailedCalls());
    }

    @Test
    void testMultipleCircuitBreakers_independentState() {
        circuitBreakerManager.getCircuitBreaker("cb-a");
        circuitBreakerManager.getCircuitBreaker("cb-b");

        circuitBreakerManager.forceOpen("cb-a");

        assertEquals("OPEN",   circuitBreakerManager.getMetrics("cb-a").getState());
        assertEquals("CLOSED", circuitBreakerManager.getMetrics("cb-b").getState());
        assertTrue(circuitBreakerManager.getCircuitBreaker("cb-b").tryAcquirePermission());
        // Release the acquired permission
        circuitBreakerManager.getCircuitBreaker("cb-b").onSuccess(0, TimeUnit.NANOSECONDS);
    }
}
