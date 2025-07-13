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
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Represents the health status of a component.
 * 
 * This enum is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
public class HealthStatus {
    public enum Status {
        HEALTHY, DEGRADED, UNHEALTHY
    }
    
    private final String component;
    private final Status status;
    private final String message;
    private final Map<String, Object> details;
    private final Instant timestamp;
    
    private HealthStatus(String component, Status status, String message, Map<String, Object> details) {
        this.component = Objects.requireNonNull(component, "Component cannot be null");
        this.status = Objects.requireNonNull(status, "Status cannot be null");
        this.message = message;
        this.details = details != null ? Map.copyOf(details) : Collections.emptyMap();
        this.timestamp = Instant.now();
    }
    
    public static HealthStatus healthy(String component) {
        return new HealthStatus(component, Status.HEALTHY, null, null);
    }
    
    public static HealthStatus healthy(String component, Map<String, Object> details) {
        return new HealthStatus(component, Status.HEALTHY, null, details);
    }
    
    public static HealthStatus degraded(String component, String message) {
        return new HealthStatus(component, Status.DEGRADED, message, null);
    }
    
    public static HealthStatus degraded(String component, String message, Map<String, Object> details) {
        return new HealthStatus(component, Status.DEGRADED, message, details);
    }
    
    public static HealthStatus unhealthy(String component, String message) {
        return new HealthStatus(component, Status.UNHEALTHY, message, null);
    }
    
    public static HealthStatus unhealthy(String component, String message, Map<String, Object> details) {
        return new HealthStatus(component, Status.UNHEALTHY, message, details);
    }
    
    public String getComponent() {
        return component;
    }
    
    public Status getStatus() {
        return status;
    }
    
    public String getMessage() {
        return message;
    }
    
    public Map<String, Object> getDetails() {
        return details;
    }
    
    public Instant getTimestamp() {
        return timestamp;
    }
    
    public boolean isHealthy() {
        return status == Status.HEALTHY;
    }
    
    public boolean isDegraded() {
        return status == Status.DEGRADED;
    }
    
    public boolean isUnhealthy() {
        return status == Status.UNHEALTHY;
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("HealthStatus{")
          .append("component='").append(component).append('\'')
          .append(", status=").append(status);
        
        if (message != null) {
            sb.append(", message='").append(message).append('\'');
        }
        
        if (!details.isEmpty()) {
            sb.append(", details=").append(details);
        }
        
        sb.append(", timestamp=").append(timestamp)
          .append('}');
        
        return sb.toString();
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        HealthStatus that = (HealthStatus) o;
        return Objects.equals(component, that.component) &&
               status == that.status &&
               Objects.equals(message, that.message) &&
               Objects.equals(details, that.details);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(component, status, message, details);
    }
}
