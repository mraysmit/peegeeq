package dev.mars.peegeeq.servicemanager.health;

import dev.mars.peegeeq.servicemanager.model.ServiceHealth;

import java.time.Instant;
import java.util.Objects;

/**
 * Represents the health status of a PeeGeeQ instance at a specific point in time.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-24
 * @version 1.0
 */
public class HealthStatus {
    
    private final ServiceHealth health;
    private final Instant lastCheck;
    private final String message;
    
    public HealthStatus(ServiceHealth health, Instant lastCheck, String message) {
        this.health = health != null ? health : ServiceHealth.UNKNOWN;
        this.lastCheck = lastCheck != null ? lastCheck : Instant.now();
        this.message = message;
    }
    
    public ServiceHealth getHealth() {
        return health;
    }
    
    public Instant getLastCheck() {
        return lastCheck;
    }
    
    public String getMessage() {
        return message;
    }
    
    /**
     * Checks if this health status indicates the instance is available.
     */
    public boolean isHealthy() {
        return health == ServiceHealth.HEALTHY;
    }
    
    /**
     * Checks if this health status indicates the instance is unavailable.
     */
    public boolean isUnhealthy() {
        return health == ServiceHealth.UNHEALTHY;
    }
    
    /**
     * Checks if this health status is unknown or transitional.
     */
    public boolean isUnknown() {
        return health == ServiceHealth.UNKNOWN || health.isTransitional();
    }
    
    /**
     * Gets the age of this health status in milliseconds.
     */
    public long getAgeMillis() {
        return Instant.now().toEpochMilli() - lastCheck.toEpochMilli();
    }
    
    /**
     * Checks if this health status is stale (older than the specified threshold).
     */
    public boolean isStale(long thresholdMillis) {
        return getAgeMillis() > thresholdMillis;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        HealthStatus that = (HealthStatus) o;
        return health == that.health &&
                Objects.equals(lastCheck, that.lastCheck) &&
                Objects.equals(message, that.message);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(health, lastCheck, message);
    }
    
    @Override
    public String toString() {
        return "HealthStatus{" +
                "health=" + health +
                ", lastCheck=" + lastCheck +
                ", message='" + message + '\'' +
                ", ageMillis=" + getAgeMillis() +
                '}';
    }
    
    /**
     * Creates a healthy status.
     */
    public static HealthStatus healthy() {
        return new HealthStatus(ServiceHealth.HEALTHY, Instant.now(), "Healthy");
    }
    
    /**
     * Creates a healthy status with a custom message.
     */
    public static HealthStatus healthy(String message) {
        return new HealthStatus(ServiceHealth.HEALTHY, Instant.now(), message);
    }
    
    /**
     * Creates an unhealthy status.
     */
    public static HealthStatus unhealthy(String message) {
        return new HealthStatus(ServiceHealth.UNHEALTHY, Instant.now(), message);
    }
    
    /**
     * Creates an unknown status.
     */
    public static HealthStatus unknown(String message) {
        return new HealthStatus(ServiceHealth.UNKNOWN, Instant.now(), message);
    }
    
    /**
     * Creates a starting status.
     */
    public static HealthStatus starting() {
        return new HealthStatus(ServiceHealth.STARTING, Instant.now(), "Starting");
    }
    
    /**
     * Creates a stopping status.
     */
    public static HealthStatus stopping() {
        return new HealthStatus(ServiceHealth.STOPPING, Instant.now(), "Stopping");
    }
}
