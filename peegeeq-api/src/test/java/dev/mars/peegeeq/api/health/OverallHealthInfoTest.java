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
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for OverallHealthInfo record.
 */
@Tag("core")
class OverallHealthInfoTest {

    @Test
    @DisplayName("isHealthy() returns true for UP status")
    void isHealthy_returnsTrueForUp() {
        OverallHealthInfo info = new OverallHealthInfo(
            OverallHealthInfo.STATUS_UP,
            Map.of("db", HealthStatusInfo.healthy("db")),
            Instant.now()
        );
        
        assertTrue(info.isHealthy());
    }

    @Test
    @DisplayName("isHealthy() returns false for DOWN status")
    void isHealthy_returnsFalseForDown() {
        OverallHealthInfo info = new OverallHealthInfo(
            OverallHealthInfo.STATUS_DOWN,
            Map.of("db", HealthStatusInfo.unhealthy("db", "Connection failed")),
            Instant.now()
        );
        
        assertFalse(info.isHealthy());
    }

    @Test
    @DisplayName("getHealthyCount() counts healthy components")
    void getHealthyCount_countsHealthyComponents() {
        OverallHealthInfo info = new OverallHealthInfo(
            OverallHealthInfo.STATUS_UP,
            Map.of(
                "db", HealthStatusInfo.healthy("db"),
                "queue", HealthStatusInfo.healthy("queue"),
                "cache", HealthStatusInfo.degraded("cache", "Slow")
            ),
            Instant.now()
        );
        
        assertEquals(2, info.getHealthyCount());
    }

    @Test
    @DisplayName("getDegradedCount() counts degraded components")
    void getDegradedCount_countsDegradedComponents() {
        OverallHealthInfo info = new OverallHealthInfo(
            OverallHealthInfo.STATUS_UP,
            Map.of(
                "db", HealthStatusInfo.healthy("db"),
                "queue", HealthStatusInfo.degraded("queue", "High latency"),
                "cache", HealthStatusInfo.degraded("cache", "Slow")
            ),
            Instant.now()
        );
        
        assertEquals(2, info.getDegradedCount());
    }

    @Test
    @DisplayName("getUnhealthyCount() counts unhealthy components")
    void getUnhealthyCount_countsUnhealthyComponents() {
        OverallHealthInfo info = new OverallHealthInfo(
            OverallHealthInfo.STATUS_DOWN,
            Map.of(
                "db", HealthStatusInfo.unhealthy("db", "Connection failed"),
                "queue", HealthStatusInfo.healthy("queue"),
                "cache", HealthStatusInfo.unhealthy("cache", "Timeout")
            ),
            Instant.now()
        );
        
        assertEquals(2, info.getUnhealthyCount());
    }

    @Test
    @DisplayName("getComponentCount() returns total components")
    void getComponentCount_returnsTotalComponents() {
        OverallHealthInfo info = new OverallHealthInfo(
            OverallHealthInfo.STATUS_UP,
            Map.of(
                "db", HealthStatusInfo.healthy("db"),
                "queue", HealthStatusInfo.healthy("queue"),
                "cache", HealthStatusInfo.healthy("cache")
            ),
            Instant.now()
        );
        
        assertEquals(3, info.getComponentCount());
    }

    @Test
    @DisplayName("null status throws NullPointerException")
    void nullStatus_throwsNPE() {
        assertThrows(NullPointerException.class, () ->
            new OverallHealthInfo(null, Map.of(), Instant.now())
        );
    }

    @Test
    @DisplayName("null components throws NullPointerException")
    void nullComponents_throwsNPE() {
        assertThrows(NullPointerException.class, () ->
            new OverallHealthInfo(OverallHealthInfo.STATUS_UP, null, Instant.now())
        );
    }

    @Test
    @DisplayName("components are immutable copy")
    void components_areImmutableCopy() {
        java.util.HashMap<String, HealthStatusInfo> mutableComponents = new java.util.HashMap<>();
        mutableComponents.put("db", HealthStatusInfo.healthy("db"));
        
        OverallHealthInfo info = new OverallHealthInfo(
            OverallHealthInfo.STATUS_UP,
            mutableComponents,
            Instant.now()
        );
        
        // Modify original map
        mutableComponents.put("queue", HealthStatusInfo.healthy("queue"));
        
        // Info components should not be affected
        assertEquals(1, info.getComponentCount());
    }

    @Test
    @DisplayName("STATUS constants are correct")
    void statusConstants_areCorrect() {
        assertEquals("UP", OverallHealthInfo.STATUS_UP);
        assertEquals("DOWN", OverallHealthInfo.STATUS_DOWN);
    }
}

