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
import java.util.Map;
import java.util.Objects;

/**
 * Represents the overall health status of the PeeGeeQ system.
 * 
 * This record is part of the PeeGeeQ API layer, providing
 * abstraction over implementation-specific health check details.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-12-05
 * @version 1.0
 */
public record OverallHealthInfo(
    String status,
    Map<String, HealthStatusInfo> components,
    Instant timestamp
) {
    /**
     * Status constant for a healthy system.
     */
    public static final String STATUS_UP = "UP";
    
    /**
     * Status constant for an unhealthy system.
     */
    public static final String STATUS_DOWN = "DOWN";
    
    /**
     * Compact constructor with validation.
     */
    public OverallHealthInfo {
        Objects.requireNonNull(status, "Status cannot be null");
        Objects.requireNonNull(components, "Components cannot be null");
        Objects.requireNonNull(timestamp, "Timestamp cannot be null");
        components = Map.copyOf(components);
    }
    
    /**
     * Returns true if the overall system is healthy.
     */
    public boolean isHealthy() {
        return STATUS_UP.equals(status);
    }
    
    /**
     * Returns the count of healthy components.
     */
    public long getHealthyCount() {
        return components.values().stream()
            .filter(HealthStatusInfo::isHealthy)
            .count();
    }
    
    /**
     * Returns the count of degraded components.
     */
    public long getDegradedCount() {
        return components.values().stream()
            .filter(HealthStatusInfo::isDegraded)
            .count();
    }
    
    /**
     * Returns the count of unhealthy components.
     */
    public long getUnhealthyCount() {
        return components.values().stream()
            .filter(HealthStatusInfo::isUnhealthy)
            .count();
    }
    
    /**
     * Returns the total number of components.
     */
    public int getComponentCount() {
        return components.size();
    }
}

