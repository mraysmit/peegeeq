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

package dev.mars.peegeeq.api.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.Tag;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for HealthStatusInfo record.
 */
@Tag("core")
class HealthStatusInfoTest {

    @Test
    @DisplayName("healthy() creates healthy status")
    void healthy_createsHealthyStatus() {
        HealthStatusInfo status = HealthStatusInfo.healthy("database");
        
        assertEquals("database", status.component());
        assertEquals(ComponentHealthState.HEALTHY, status.state());
        assertNull(status.message());
        assertTrue(status.details().isEmpty());
        assertNotNull(status.timestamp());
        assertTrue(status.isHealthy());
        assertFalse(status.isDegraded());
        assertFalse(status.isUnhealthy());
    }

    @Test
    @DisplayName("healthy() with details creates healthy status with details")
    void healthyWithDetails_createsHealthyStatusWithDetails() {
        Map<String, Object> details = Map.of("connections", 10, "latency", 5.2);
        HealthStatusInfo status = HealthStatusInfo.healthy("database", details);
        
        assertEquals("database", status.component());
        assertEquals(ComponentHealthState.HEALTHY, status.state());
        assertEquals(details, status.details());
        assertTrue(status.isHealthy());
    }

    @Test
    @DisplayName("degraded() creates degraded status")
    void degraded_createsDegradedStatus() {
        HealthStatusInfo status = HealthStatusInfo.degraded("queue", "High latency detected");
        
        assertEquals("queue", status.component());
        assertEquals(ComponentHealthState.DEGRADED, status.state());
        assertEquals("High latency detected", status.message());
        assertFalse(status.isHealthy());
        assertTrue(status.isDegraded());
        assertFalse(status.isUnhealthy());
    }

    @Test
    @DisplayName("degraded() with details creates degraded status with details")
    void degradedWithDetails_createsDegradedStatusWithDetails() {
        Map<String, Object> details = Map.of("latency_ms", 500);
        HealthStatusInfo status = HealthStatusInfo.degraded("queue", "High latency", details);
        
        assertEquals(ComponentHealthState.DEGRADED, status.state());
        assertEquals(details, status.details());
    }

    @Test
    @DisplayName("unhealthy() creates unhealthy status")
    void unhealthy_createsUnhealthyStatus() {
        HealthStatusInfo status = HealthStatusInfo.unhealthy("database", "Connection failed");
        
        assertEquals("database", status.component());
        assertEquals(ComponentHealthState.UNHEALTHY, status.state());
        assertEquals("Connection failed", status.message());
        assertFalse(status.isHealthy());
        assertFalse(status.isDegraded());
        assertTrue(status.isUnhealthy());
    }

    @Test
    @DisplayName("unhealthy() with details creates unhealthy status with details")
    void unhealthyWithDetails_createsUnhealthyStatusWithDetails() {
        Map<String, Object> details = Map.of("error", "ECONNREFUSED", "attempts", 3);
        HealthStatusInfo status = HealthStatusInfo.unhealthy("database", "Connection failed", details);
        
        assertEquals(ComponentHealthState.UNHEALTHY, status.state());
        assertEquals(details, status.details());
    }

    @Test
    @DisplayName("null component throws NullPointerException")
    void nullComponent_throwsNPE() {
        assertThrows(NullPointerException.class, () ->
            HealthStatusInfo.healthy(null)
        );
    }

    @Test
    @DisplayName("details are immutable copy")
    void details_areImmutableCopy() {
        java.util.HashMap<String, Object> mutableDetails = new java.util.HashMap<>();
        mutableDetails.put("key", "value");
        
        HealthStatusInfo status = HealthStatusInfo.healthy("test", mutableDetails);
        
        // Modify original map
        mutableDetails.put("key2", "value2");
        
        // Status details should not be affected
        assertFalse(status.details().containsKey("key2"));
    }

    @Test
    @DisplayName("null details become empty map")
    void nullDetails_becomeEmptyMap() {
        HealthStatusInfo status = HealthStatusInfo.healthy("test");
        assertNotNull(status.details());
        assertTrue(status.details().isEmpty());
    }
}

