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

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CORE unit tests for OverallHealthStatus data model.
 * 
 * <p>Tests the OverallHealthStatus constructor, getters, and business logic
 * without requiring database access.</p>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-27
 * @version 1.0
 */
@Tag(TestCategories.CORE)
class OverallHealthStatusTest {

    @Test
    void testConstructorWithAllHealthyComponents() {
        Instant now = Instant.now();
        Map<String, HealthStatus> components = Map.of(
            "database", HealthStatus.healthy("database"),
            "cache", HealthStatus.healthy("cache")
        );
        
        OverallHealthStatus overall = new OverallHealthStatus("UP", components, now);
        
        assertEquals("UP", overall.getStatus());
        assertEquals(2, overall.getComponents().size());
        assertEquals(now, overall.getTimestamp());
        assertTrue(overall.isHealthy());
        assertEquals(2, overall.getHealthyCount());
        assertEquals(0, overall.getDegradedCount());
        assertEquals(0, overall.getUnhealthyCount());
    }

    @Test
    void testConstructorWithMixedComponents() {
        Instant now = Instant.now();
        Map<String, HealthStatus> components = Map.of(
            "database", HealthStatus.healthy("database"),
            "cache", HealthStatus.degraded("cache", "High latency"),
            "queue", HealthStatus.unhealthy("queue", "Connection failed")
        );
        
        OverallHealthStatus overall = new OverallHealthStatus("DEGRADED", components, now);
        
        assertEquals("DEGRADED", overall.getStatus());
        assertEquals(3, overall.getComponents().size());
        assertFalse(overall.isHealthy());
        assertEquals(1, overall.getHealthyCount());
        assertEquals(1, overall.getDegradedCount());
        assertEquals(1, overall.getUnhealthyCount());
    }

    @Test
    void testConstructorWithEmptyComponents() {
        Instant now = Instant.now();
        Map<String, HealthStatus> components = Map.of();
        
        OverallHealthStatus overall = new OverallHealthStatus("UP", components, now);
        
        assertEquals(0, overall.getComponents().size());
        assertEquals(0, overall.getHealthyCount());
        assertEquals(0, overall.getDegradedCount());
        assertEquals(0, overall.getUnhealthyCount());
    }

    @Test
    void testConstructorRequiresStatus() {
        Instant now = Instant.now();
        Map<String, HealthStatus> components = Map.of();
        
        Exception exception = assertThrows(NullPointerException.class, () -> {
            new OverallHealthStatus(null, components, now);
        });
        
        assertTrue(exception.getMessage().contains("Status"));
    }

    @Test
    void testConstructorRequiresComponents() {
        Instant now = Instant.now();
        
        Exception exception = assertThrows(NullPointerException.class, () -> {
            new OverallHealthStatus("UP", null, now);
        });
        
        assertTrue(exception.getMessage().contains("Components"));
    }

    @Test
    void testConstructorRequiresTimestamp() {
        Map<String, HealthStatus> components = Map.of();
        
        Exception exception = assertThrows(NullPointerException.class, () -> {
            new OverallHealthStatus("UP", components, null);
        });
        
        assertTrue(exception.getMessage().contains("Timestamp"));
    }

    @Test
    void testComponentsAreImmutable() {
        Instant now = Instant.now();
        Map<String, HealthStatus> components = Map.of(
            "database", HealthStatus.healthy("database")
        );
        
        OverallHealthStatus overall = new OverallHealthStatus("UP", components, now);
        
        assertThrows(UnsupportedOperationException.class, () -> {
            overall.getComponents().put("cache", HealthStatus.healthy("cache"));
        });
    }

    @Test
    void testIsHealthyWhenStatusIsUp() {
        Instant now = Instant.now();
        OverallHealthStatus overall = new OverallHealthStatus("UP", Map.of(), now);
        
        assertTrue(overall.isHealthy());
    }

    @Test
    void testIsHealthyWhenStatusIsNotUp() {
        Instant now = Instant.now();
        OverallHealthStatus degraded = new OverallHealthStatus("DEGRADED", Map.of(), now);
        OverallHealthStatus down = new OverallHealthStatus("DOWN", Map.of(), now);

        assertFalse(degraded.isHealthy());
        assertFalse(down.isHealthy());
    }

    @Test
    void testEqualsAndHashCode() {
        Instant now = Instant.now();
        Map<String, HealthStatus> components = Map.of(
            "database", HealthStatus.healthy("database")
        );

        OverallHealthStatus status1 = new OverallHealthStatus("UP", components, now);
        OverallHealthStatus status2 = new OverallHealthStatus("UP", components, now);
        OverallHealthStatus status3 = new OverallHealthStatus("DOWN", components, now);

        assertEquals(status1, status2);
        assertEquals(status1.hashCode(), status2.hashCode());
        assertNotEquals(status1, status3);
    }

    @Test
    void testEqualsSameObject() {
        Instant now = Instant.now();
        OverallHealthStatus status = new OverallHealthStatus("UP", Map.of(), now);

        assertEquals(status, status);
    }

    @Test
    void testEqualsNull() {
        Instant now = Instant.now();
        OverallHealthStatus status = new OverallHealthStatus("UP", Map.of(), now);

        assertNotEquals(status, null);
    }

    @Test
    void testEqualsDifferentClass() {
        Instant now = Instant.now();
        OverallHealthStatus status = new OverallHealthStatus("UP", Map.of(), now);

        assertNotEquals(status, "not a status");
    }

    @Test
    void testToString() {
        Instant now = Instant.now();
        Map<String, HealthStatus> components = Map.of(
            "database", HealthStatus.healthy("database"),
            "cache", HealthStatus.degraded("cache", "High latency"),
            "queue", HealthStatus.unhealthy("queue", "Connection failed")
        );

        OverallHealthStatus overall = new OverallHealthStatus("DEGRADED", components, now);
        String str = overall.toString();

        assertTrue(str.contains("DEGRADED"));
        assertTrue(str.contains("3")); // component count
        assertTrue(str.contains("healthy=1"));
        assertTrue(str.contains("degraded=1"));
        assertTrue(str.contains("unhealthy=1"));
    }

    @Test
    void testGetHealthyCountWithAllHealthy() {
        Instant now = Instant.now();
        Map<String, HealthStatus> components = Map.of(
            "db1", HealthStatus.healthy("db1"),
            "db2", HealthStatus.healthy("db2"),
            "db3", HealthStatus.healthy("db3")
        );

        OverallHealthStatus overall = new OverallHealthStatus("UP", components, now);

        assertEquals(3, overall.getHealthyCount());
        assertEquals(0, overall.getDegradedCount());
        assertEquals(0, overall.getUnhealthyCount());
    }

    @Test
    void testGetDegradedCountWithAllDegraded() {
        Instant now = Instant.now();
        Map<String, HealthStatus> components = Map.of(
            "db1", HealthStatus.degraded("db1", "slow"),
            "db2", HealthStatus.degraded("db2", "slow")
        );

        OverallHealthStatus overall = new OverallHealthStatus("DEGRADED", components, now);

        assertEquals(0, overall.getHealthyCount());
        assertEquals(2, overall.getDegradedCount());
        assertEquals(0, overall.getUnhealthyCount());
    }

    @Test
    void testGetUnhealthyCountWithAllUnhealthy() {
        Instant now = Instant.now();
        Map<String, HealthStatus> components = Map.of(
            "db1", HealthStatus.unhealthy("db1", "down"),
            "db2", HealthStatus.unhealthy("db2", "down")
        );

        OverallHealthStatus overall = new OverallHealthStatus("DOWN", components, now);

        assertEquals(0, overall.getHealthyCount());
        assertEquals(0, overall.getDegradedCount());
        assertEquals(2, overall.getUnhealthyCount());
    }
}


