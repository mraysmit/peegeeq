package dev.mars.peegeeq.servicemanager.health;

import dev.mars.peegeeq.servicemanager.model.PeeGeeQInstance;
import dev.mars.peegeeq.servicemanager.model.ServiceHealth;
import io.vertx.core.json.JsonObject;

import java.time.Instant;
import java.util.Objects;

/**
 * Represents the result of a health check performed on a PeeGeeQ instance.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-24
 * @version 1.0
 */
public class HealthCheckResult {
    
    private final PeeGeeQInstance instance;
    private final ServiceHealth health;
    private final Instant checkTime;
    private final String errorMessage;
    private final JsonObject healthData;
    
    public HealthCheckResult(PeeGeeQInstance instance, ServiceHealth health, Instant checkTime, 
                           String errorMessage, JsonObject healthData) {
        this.instance = instance;
        this.health = health != null ? health : ServiceHealth.UNKNOWN;
        this.checkTime = checkTime != null ? checkTime : Instant.now();
        this.errorMessage = errorMessage;
        this.healthData = healthData;
    }
    
    public PeeGeeQInstance getInstance() {
        return instance;
    }
    
    public ServiceHealth getHealth() {
        return health;
    }
    
    public Instant getCheckTime() {
        return checkTime;
    }
    
    public String getErrorMessage() {
        return errorMessage;
    }
    
    public JsonObject getHealthData() {
        return healthData;
    }
    
    /**
     * Checks if the health check was successful.
     */
    public boolean isSuccessful() {
        return health == ServiceHealth.HEALTHY;
    }
    
    /**
     * Checks if the health check failed.
     */
    public boolean isFailed() {
        return health == ServiceHealth.UNHEALTHY;
    }
    
    /**
     * Checks if the health check result is unknown.
     */
    public boolean isUnknown() {
        return health == ServiceHealth.UNKNOWN || health.isTransitional();
    }
    
    /**
     * Gets the instance ID.
     */
    public String getInstanceId() {
        return instance != null ? instance.getInstanceId() : null;
    }
    
    /**
     * Gets the response time of the health check in milliseconds.
     */
    public long getResponseTimeMillis() {
        // This would need to be calculated during the actual health check
        // For now, return 0 as a placeholder
        return 0;
    }
    
    /**
     * Converts this result to a JSON object for API responses.
     */
    public JsonObject toJson() {
        JsonObject json = new JsonObject()
                .put("instanceId", getInstanceId())
                .put("health", health.toString())
                .put("checkTime", checkTime.toString())
                .put("successful", isSuccessful());
        
        if (instance != null) {
            json.put("host", instance.getHost())
                .put("port", instance.getPort())
                .put("environment", instance.getEnvironment())
                .put("region", instance.getRegion());
        }
        
        if (errorMessage != null) {
            json.put("errorMessage", errorMessage);
        }
        
        if (healthData != null) {
            json.put("healthData", healthData);
        }
        
        return json;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        HealthCheckResult that = (HealthCheckResult) o;
        return Objects.equals(instance, that.instance) &&
                health == that.health &&
                Objects.equals(checkTime, that.checkTime);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(instance, health, checkTime);
    }
    
    @Override
    public String toString() {
        return "HealthCheckResult{" +
                "instanceId='" + getInstanceId() + '\'' +
                ", health=" + health +
                ", checkTime=" + checkTime +
                ", successful=" + isSuccessful() +
                ", errorMessage='" + errorMessage + '\'' +
                '}';
    }
    
    /**
     * Creates a successful health check result.
     */
    public static HealthCheckResult success(PeeGeeQInstance instance, JsonObject healthData) {
        return new HealthCheckResult(instance, ServiceHealth.HEALTHY, Instant.now(), null, healthData);
    }
    
    /**
     * Creates a failed health check result.
     */
    public static HealthCheckResult failure(PeeGeeQInstance instance, String errorMessage) {
        return new HealthCheckResult(instance, ServiceHealth.UNHEALTHY, Instant.now(), errorMessage, null);
    }
    
    /**
     * Creates an unknown health check result.
     */
    public static HealthCheckResult unknown(PeeGeeQInstance instance, String message) {
        return new HealthCheckResult(instance, ServiceHealth.UNKNOWN, Instant.now(), message, null);
    }
}
