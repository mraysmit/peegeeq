package dev.mars.peegeeq.api.health;

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

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Represents the health status of a single component.
 * 
 * This record is part of the PeeGeeQ API layer, providing
 * abstraction over implementation-specific health check details.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-12-05
 * @version 1.0
 */
public record HealthStatusInfo(
    String component,
    ComponentHealthState state,
    String message,
    Map<String, Object> details,
    Instant timestamp
) {
    /**
     * Compact constructor with validation.
     */
    public HealthStatusInfo {
        Objects.requireNonNull(component, "Component cannot be null");
        Objects.requireNonNull(state, "State cannot be null");
        Objects.requireNonNull(timestamp, "Timestamp cannot be null");
        details = details != null ? Map.copyOf(details) : Collections.emptyMap();
    }
    
    /**
     * Creates a healthy status for a component.
     */
    public static HealthStatusInfo healthy(String component) {
        return new HealthStatusInfo(component, ComponentHealthState.HEALTHY, null, null, Instant.now());
    }
    
    /**
     * Creates a healthy status for a component with details.
     */
    public static HealthStatusInfo healthy(String component, Map<String, Object> details) {
        return new HealthStatusInfo(component, ComponentHealthState.HEALTHY, null, details, Instant.now());
    }
    
    /**
     * Creates a degraded status for a component.
     */
    public static HealthStatusInfo degraded(String component, String message) {
        return new HealthStatusInfo(component, ComponentHealthState.DEGRADED, message, null, Instant.now());
    }
    
    /**
     * Creates a degraded status for a component with details.
     */
    public static HealthStatusInfo degraded(String component, String message, Map<String, Object> details) {
        return new HealthStatusInfo(component, ComponentHealthState.DEGRADED, message, details, Instant.now());
    }
    
    /**
     * Creates an unhealthy status for a component.
     */
    public static HealthStatusInfo unhealthy(String component, String message) {
        return new HealthStatusInfo(component, ComponentHealthState.UNHEALTHY, message, null, Instant.now());
    }
    
    /**
     * Creates an unhealthy status for a component with details.
     */
    public static HealthStatusInfo unhealthy(String component, String message, Map<String, Object> details) {
        return new HealthStatusInfo(component, ComponentHealthState.UNHEALTHY, message, details, Instant.now());
    }
    
    /**
     * Returns true if the component is healthy.
     */
    public boolean isHealthy() {
        return state == ComponentHealthState.HEALTHY;
    }
    
    /**
     * Returns true if the component is degraded.
     */
    public boolean isDegraded() {
        return state == ComponentHealthState.DEGRADED;
    }
    
    /**
     * Returns true if the component is unhealthy.
     */
    public boolean isUnhealthy() {
        return state == ComponentHealthState.UNHEALTHY;
    }
}

