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


import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Represents the overall health status of the PeeGeeQ system.
 * 
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
public class OverallHealthStatus {
    private final String status;
    private final Map<String, HealthStatus> components;
    private final Instant timestamp;
    
    public OverallHealthStatus(String status, Map<String, HealthStatus> components, Instant timestamp) {
        this.status = Objects.requireNonNull(status, "Status cannot be null");
        this.components = Map.copyOf(Objects.requireNonNull(components, "Components cannot be null"));
        this.timestamp = Objects.requireNonNull(timestamp, "Timestamp cannot be null");
    }
    
    public String getStatus() {
        return status;
    }
    
    public Map<String, HealthStatus> getComponents() {
        return components;
    }
    
    public Instant getTimestamp() {
        return timestamp;
    }
    
    public boolean isHealthy() {
        return "UP".equals(status);
    }
    
    public long getHealthyCount() {
        return components.values().stream()
            .mapToLong(status -> status.isHealthy() ? 1 : 0)
            .sum();
    }
    
    public long getDegradedCount() {
        return components.values().stream()
            .mapToLong(status -> status.isDegraded() ? 1 : 0)
            .sum();
    }
    
    public long getUnhealthyCount() {
        return components.values().stream()
            .mapToLong(status -> status.isUnhealthy() ? 1 : 0)
            .sum();
    }
    
    @Override
    public String toString() {
        return "OverallHealthStatus{" +
                "status='" + status + '\'' +
                ", components=" + components.size() +
                ", healthy=" + getHealthyCount() +
                ", degraded=" + getDegradedCount() +
                ", unhealthy=" + getUnhealthyCount() +
                ", timestamp=" + timestamp +
                '}';
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        OverallHealthStatus that = (OverallHealthStatus) o;
        return Objects.equals(status, that.status) &&
               Objects.equals(components, that.components) &&
               Objects.equals(timestamp, that.timestamp);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(status, components, timestamp);
    }
}
