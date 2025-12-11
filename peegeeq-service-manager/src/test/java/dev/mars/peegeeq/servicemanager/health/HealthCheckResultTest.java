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

import dev.mars.peegeeq.servicemanager.model.PeeGeeQInstance;
import dev.mars.peegeeq.servicemanager.model.ServiceHealth;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for HealthCheckResult.
 */
class HealthCheckResultTest {

    private PeeGeeQInstance createTestInstance() {
        return PeeGeeQInstance.builder()
            .instanceId("test-instance-1")
            .host("localhost")
            .port(8080)
            .version("1.0.0")
            .environment("test")
            .region("local")
            .build();
    }

    @Test
    @DisplayName("success() creates healthy result")
    void success_createsHealthyResult() {
        PeeGeeQInstance instance = createTestInstance();
        JsonObject healthData = new JsonObject().put("uptime", 3600);
        
        HealthCheckResult result = HealthCheckResult.success(instance, healthData);
        
        assertTrue(result.isSuccessful());
        assertFalse(result.isFailed());
        assertFalse(result.isUnknown());
        assertEquals(ServiceHealth.HEALTHY, result.getHealth());
        assertEquals(instance, result.getInstance());
        assertEquals(healthData, result.getHealthData());
        assertNull(result.getErrorMessage());
    }

    @Test
    @DisplayName("failure() creates unhealthy result")
    void failure_createsUnhealthyResult() {
        PeeGeeQInstance instance = createTestInstance();
        
        HealthCheckResult result = HealthCheckResult.failure(instance, "Connection refused");
        
        assertFalse(result.isSuccessful());
        assertTrue(result.isFailed());
        assertFalse(result.isUnknown());
        assertEquals(ServiceHealth.UNHEALTHY, result.getHealth());
        assertEquals("Connection refused", result.getErrorMessage());
        assertNull(result.getHealthData());
    }

    @Test
    @DisplayName("unknown() creates unknown result")
    void unknown_createsUnknownResult() {
        PeeGeeQInstance instance = createTestInstance();
        
        HealthCheckResult result = HealthCheckResult.unknown(instance, "Timeout");
        
        assertFalse(result.isSuccessful());
        assertFalse(result.isFailed());
        assertTrue(result.isUnknown());
        assertEquals(ServiceHealth.UNKNOWN, result.getHealth());
        assertEquals("Timeout", result.getErrorMessage());
    }

    @Test
    @DisplayName("getInstanceId() returns instance ID")
    void getInstanceId_returnsInstanceId() {
        PeeGeeQInstance instance = createTestInstance();
        HealthCheckResult result = HealthCheckResult.success(instance, null);
        
        assertEquals("test-instance-1", result.getInstanceId());
    }

    @Test
    @DisplayName("getInstanceId() returns null for null instance")
    void getInstanceId_returnsNullForNullInstance() {
        HealthCheckResult result = new HealthCheckResult(null, ServiceHealth.UNKNOWN, Instant.now(), null, null);
        
        assertNull(result.getInstanceId());
    }

    @Test
    @DisplayName("toJson() includes all fields")
    void toJson_includesAllFields() {
        PeeGeeQInstance instance = createTestInstance();
        JsonObject healthData = new JsonObject().put("connections", 10);
        
        HealthCheckResult result = HealthCheckResult.success(instance, healthData);
        JsonObject json = result.toJson();
        
        assertEquals("test-instance-1", json.getString("instanceId"));
        assertEquals("HEALTHY", json.getString("health"));
        assertTrue(json.getBoolean("successful"));
        assertEquals("localhost", json.getString("host"));
        assertEquals(8080, json.getInteger("port"));
        assertNotNull(json.getJsonObject("healthData"));
    }

    @Test
    @DisplayName("toJson() includes error message when present")
    void toJson_includesErrorMessageWhenPresent() {
        PeeGeeQInstance instance = createTestInstance();
        HealthCheckResult result = HealthCheckResult.failure(instance, "Connection failed");
        
        JsonObject json = result.toJson();
        
        assertEquals("Connection failed", json.getString("errorMessage"));
    }

    @Test
    @DisplayName("null health defaults to UNKNOWN")
    void nullHealth_defaultsToUnknown() {
        HealthCheckResult result = new HealthCheckResult(null, null, null, null, null);
        
        assertEquals(ServiceHealth.UNKNOWN, result.getHealth());
    }

    @Test
    @DisplayName("null checkTime defaults to now")
    void nullCheckTime_defaultsToNow() {
        Instant before = Instant.now();
        HealthCheckResult result = new HealthCheckResult(null, ServiceHealth.HEALTHY, null, null, null);
        Instant after = Instant.now();
        
        assertNotNull(result.getCheckTime());
        assertFalse(result.getCheckTime().isBefore(before));
        assertFalse(result.getCheckTime().isAfter(after));
    }

    @Test
    @DisplayName("equals and hashCode work correctly")
    void equalsAndHashCode_workCorrectly() {
        PeeGeeQInstance instance = createTestInstance();
        Instant checkTime = Instant.now();
        
        HealthCheckResult result1 = new HealthCheckResult(instance, ServiceHealth.HEALTHY, checkTime, null, null);
        HealthCheckResult result2 = new HealthCheckResult(instance, ServiceHealth.HEALTHY, checkTime, null, null);
        
        assertEquals(result1, result2);
        assertEquals(result1.hashCode(), result2.hashCode());
    }
}

