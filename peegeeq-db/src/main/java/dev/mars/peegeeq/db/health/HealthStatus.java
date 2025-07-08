package dev.mars.peegeeq.db.health;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Represents the health status of a component.
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
