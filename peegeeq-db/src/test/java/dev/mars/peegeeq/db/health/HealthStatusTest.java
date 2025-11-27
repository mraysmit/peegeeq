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

import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CORE unit tests for HealthStatus data model.
 * 
 * <p>Tests the HealthStatus factory methods, getters, and business logic
 * without requiring database access.</p>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-27
 * @version 1.0
 */
@Tag(TestCategories.CORE)
class HealthStatusTest {

    @Test
    void testHealthyWithoutDetails() {
        HealthStatus status = HealthStatus.healthy("database");
        
        assertEquals("database", status.getComponent());
        assertEquals(HealthStatus.Status.HEALTHY, status.getStatus());
        assertNull(status.getMessage());
        assertTrue(status.getDetails().isEmpty());
        assertNotNull(status.getTimestamp());
        assertTrue(status.isHealthy());
        assertFalse(status.isDegraded());
        assertFalse(status.isUnhealthy());
    }

    @Test
    void testHealthyWithDetails() {
        Map<String, Object> details = Map.of("connections", 10, "latency", 5.2);
        HealthStatus status = HealthStatus.healthy("database", details);
        
        assertEquals("database", status.getComponent());
        assertEquals(HealthStatus.Status.HEALTHY, status.getStatus());
        assertNull(status.getMessage());
        assertEquals(details, status.getDetails());
        assertTrue(status.isHealthy());
    }

    @Test
    void testDegradedWithoutDetails() {
        HealthStatus status = HealthStatus.degraded("database", "High latency");
        
        assertEquals("database", status.getComponent());
        assertEquals(HealthStatus.Status.DEGRADED, status.getStatus());
        assertEquals("High latency", status.getMessage());
        assertTrue(status.getDetails().isEmpty());
        assertFalse(status.isHealthy());
        assertTrue(status.isDegraded());
        assertFalse(status.isUnhealthy());
    }

    @Test
    void testDegradedWithDetails() {
        Map<String, Object> details = Map.of("latency", 150.5);
        HealthStatus status = HealthStatus.degraded("database", "High latency", details);
        
        assertEquals("database", status.getComponent());
        assertEquals(HealthStatus.Status.DEGRADED, status.getStatus());
        assertEquals("High latency", status.getMessage());
        assertEquals(details, status.getDetails());
        assertTrue(status.isDegraded());
    }

    @Test
    void testUnhealthyWithoutDetails() {
        HealthStatus status = HealthStatus.unhealthy("database", "Connection failed");
        
        assertEquals("database", status.getComponent());
        assertEquals(HealthStatus.Status.UNHEALTHY, status.getStatus());
        assertEquals("Connection failed", status.getMessage());
        assertTrue(status.getDetails().isEmpty());
        assertFalse(status.isHealthy());
        assertFalse(status.isDegraded());
        assertTrue(status.isUnhealthy());
    }

    @Test
    void testUnhealthyWithDetails() {
        Map<String, Object> details = Map.of("error", "Timeout", "attempts", 3);
        HealthStatus status = HealthStatus.unhealthy("database", "Connection failed", details);
        
        assertEquals("database", status.getComponent());
        assertEquals(HealthStatus.Status.UNHEALTHY, status.getStatus());
        assertEquals("Connection failed", status.getMessage());
        assertEquals(details, status.getDetails());
        assertTrue(status.isUnhealthy());
    }

    @Test
    void testDetailsAreImmutable() {
        Map<String, Object> details = Map.of("key", "value");
        HealthStatus status = HealthStatus.healthy("component", details);
        
        // Should return immutable copy
        assertThrows(UnsupportedOperationException.class, () -> {
            status.getDetails().put("new", "value");
        });
    }

    @Test
    void testNullDetailsBecomesEmptyMap() {
        HealthStatus status = HealthStatus.healthy("component", null);
        
        assertNotNull(status.getDetails());
        assertTrue(status.getDetails().isEmpty());
    }

    @Test
    void testStatusEnumValues() {
        HealthStatus.Status[] values = HealthStatus.Status.values();
        
        assertEquals(3, values.length);
        assertEquals(HealthStatus.Status.HEALTHY, values[0]);
        assertEquals(HealthStatus.Status.DEGRADED, values[1]);
        assertEquals(HealthStatus.Status.UNHEALTHY, values[2]);
    }

    @Test
    void testStatusEnumValueOf() {
        assertEquals(HealthStatus.Status.HEALTHY, HealthStatus.Status.valueOf("HEALTHY"));
        assertEquals(HealthStatus.Status.DEGRADED, HealthStatus.Status.valueOf("DEGRADED"));
        assertEquals(HealthStatus.Status.UNHEALTHY, HealthStatus.Status.valueOf("UNHEALTHY"));
    }

    @Test
    void testEqualsAndHashCode() {
        Map<String, Object> details = Map.of("key", "value");

        HealthStatus status1 = HealthStatus.degraded("database", "High latency", details);
        HealthStatus status2 = HealthStatus.degraded("database", "High latency", details);
        HealthStatus status3 = HealthStatus.healthy("database");

        // Note: equals doesn't include timestamp
        assertEquals(status1, status2);
        assertEquals(status1.hashCode(), status2.hashCode());
        assertNotEquals(status1, status3);
    }

    @Test
    void testEqualsSameObject() {
        HealthStatus status = HealthStatus.healthy("component");
        assertEquals(status, status);
    }

    @Test
    void testEqualsNull() {
        HealthStatus status = HealthStatus.healthy("component");
        assertNotEquals(status, null);
    }

    @Test
    void testEqualsDifferentClass() {
        HealthStatus status = HealthStatus.healthy("component");
        assertNotEquals(status, "not a health status");
    }

    @Test
    void testToStringHealthy() {
        HealthStatus status = HealthStatus.healthy("database");
        String str = status.toString();

        assertTrue(str.contains("database"));
        assertTrue(str.contains("HEALTHY"));
    }

    @Test
    void testToStringDegradedWithMessage() {
        HealthStatus status = HealthStatus.degraded("database", "High latency");
        String str = status.toString();

        assertTrue(str.contains("database"));
        assertTrue(str.contains("DEGRADED"));
        assertTrue(str.contains("High latency"));
    }

    @Test
    void testToStringWithDetails() {
        Map<String, Object> details = Map.of("connections", 10);
        HealthStatus status = HealthStatus.healthy("database", details);
        String str = status.toString();

        assertTrue(str.contains("database"));
        assertTrue(str.contains("HEALTHY"));
        assertTrue(str.contains("connections"));
    }
}


