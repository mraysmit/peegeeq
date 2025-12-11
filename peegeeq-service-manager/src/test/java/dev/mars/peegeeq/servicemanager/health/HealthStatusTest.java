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

package dev.mars.peegeeq.servicemanager.health;

import dev.mars.peegeeq.servicemanager.model.ServiceHealth;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for HealthStatus.
 */
class HealthStatusTest {

    @Test
    @DisplayName("healthy() creates healthy status")
    void healthy_createsHealthyStatus() {
        HealthStatus status = HealthStatus.healthy();
        
        assertTrue(status.isHealthy());
        assertFalse(status.isUnhealthy());
        assertFalse(status.isUnknown());
        assertEquals(ServiceHealth.HEALTHY, status.getHealth());
        assertEquals("Healthy", status.getMessage());
    }

    @Test
    @DisplayName("healthy(message) creates healthy status with custom message")
    void healthyWithMessage_createsHealthyStatusWithCustomMessage() {
        HealthStatus status = HealthStatus.healthy("All systems operational");
        
        assertTrue(status.isHealthy());
        assertEquals("All systems operational", status.getMessage());
    }

    @Test
    @DisplayName("unhealthy() creates unhealthy status")
    void unhealthy_createsUnhealthyStatus() {
        HealthStatus status = HealthStatus.unhealthy("Database connection failed");
        
        assertFalse(status.isHealthy());
        assertTrue(status.isUnhealthy());
        assertFalse(status.isUnknown());
        assertEquals(ServiceHealth.UNHEALTHY, status.getHealth());
        assertEquals("Database connection failed", status.getMessage());
    }

    @Test
    @DisplayName("unknown() creates unknown status")
    void unknown_createsUnknownStatus() {
        HealthStatus status = HealthStatus.unknown("Pending health check");
        
        assertFalse(status.isHealthy());
        assertFalse(status.isUnhealthy());
        assertTrue(status.isUnknown());
        assertEquals(ServiceHealth.UNKNOWN, status.getHealth());
    }

    @Test
    @DisplayName("starting() creates starting status")
    void starting_createsStartingStatus() {
        HealthStatus status = HealthStatus.starting();
        
        assertFalse(status.isHealthy());
        assertFalse(status.isUnhealthy());
        assertTrue(status.isUnknown()); // transitional states are unknown
        assertEquals(ServiceHealth.STARTING, status.getHealth());
        assertEquals("Starting", status.getMessage());
    }

    @Test
    @DisplayName("stopping() creates stopping status")
    void stopping_createsStoppingStatus() {
        HealthStatus status = HealthStatus.stopping();
        
        assertFalse(status.isHealthy());
        assertFalse(status.isUnhealthy());
        assertTrue(status.isUnknown()); // transitional states are unknown
        assertEquals(ServiceHealth.STOPPING, status.getHealth());
        assertEquals("Stopping", status.getMessage());
    }

    @Test
    @DisplayName("getAgeMillis() returns positive value")
    void getAgeMillis_returnsPositiveValue() throws InterruptedException {
        HealthStatus status = HealthStatus.healthy();
        Thread.sleep(10);
        
        assertTrue(status.getAgeMillis() >= 10);
    }

    @Test
    @DisplayName("isStale() returns true for old status")
    void isStale_returnsTrueForOldStatus() {
        Instant oldTime = Instant.now().minusSeconds(60);
        HealthStatus status = new HealthStatus(ServiceHealth.HEALTHY, oldTime, "Old status");
        
        assertTrue(status.isStale(30000)); // 30 seconds threshold
    }

    @Test
    @DisplayName("isStale() returns false for recent status")
    void isStale_returnsFalseForRecentStatus() {
        HealthStatus status = HealthStatus.healthy();
        
        assertFalse(status.isStale(30000)); // 30 seconds threshold
    }

    @Test
    @DisplayName("null health defaults to UNKNOWN")
    void nullHealth_defaultsToUnknown() {
        HealthStatus status = new HealthStatus(null, null, null);
        
        assertEquals(ServiceHealth.UNKNOWN, status.getHealth());
    }

    @Test
    @DisplayName("null lastCheck defaults to now")
    void nullLastCheck_defaultsToNow() {
        Instant before = Instant.now();
        HealthStatus status = new HealthStatus(ServiceHealth.HEALTHY, null, "Test");
        Instant after = Instant.now();
        
        assertNotNull(status.getLastCheck());
        assertFalse(status.getLastCheck().isBefore(before));
        assertFalse(status.getLastCheck().isAfter(after));
    }

    @Test
    @DisplayName("equals and hashCode work correctly")
    void equalsAndHashCode_workCorrectly() {
        Instant time = Instant.now();
        HealthStatus status1 = new HealthStatus(ServiceHealth.HEALTHY, time, "Test");
        HealthStatus status2 = new HealthStatus(ServiceHealth.HEALTHY, time, "Test");
        
        assertEquals(status1, status2);
        assertEquals(status1.hashCode(), status2.hashCode());
    }

    @Test
    @DisplayName("toString contains relevant info")
    void toString_containsRelevantInfo() {
        HealthStatus status = HealthStatus.healthy("All good");
        String str = status.toString();
        
        assertTrue(str.contains("HEALTHY"));
        assertTrue(str.contains("All good"));
        assertTrue(str.contains("ageMillis"));
    }
}

