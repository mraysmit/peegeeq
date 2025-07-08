package dev.mars.peegeeq.db.health;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Represents the overall health status of the PeeGeeQ system.
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
