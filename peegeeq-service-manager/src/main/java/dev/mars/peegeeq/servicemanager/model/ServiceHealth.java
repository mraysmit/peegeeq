package dev.mars.peegeeq.servicemanager.model;

/**
 * Represents the health status of a PeeGeeQ service instance.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-24
 * @version 1.0
 */
public enum ServiceHealth {
    
    /**
     * The service is healthy and available for requests.
     */
    HEALTHY("healthy"),
    
    /**
     * The service is unhealthy and should not receive requests.
     */
    UNHEALTHY("unhealthy"),
    
    /**
     * The service health status is unknown or could not be determined.
     */
    UNKNOWN("unknown"),
    
    /**
     * The service is starting up and not yet ready for requests.
     */
    STARTING("starting"),
    
    /**
     * The service is shutting down and should not receive new requests.
     */
    STOPPING("stopping");
    
    private final String status;
    
    ServiceHealth(String status) {
        this.status = status;
    }
    
    public String getStatus() {
        return status;
    }
    
    /**
     * Checks if the service is available for requests.
     */
    public boolean isAvailable() {
        return this == HEALTHY;
    }
    
    /**
     * Checks if the service is in a transitional state.
     */
    public boolean isTransitional() {
        return this == STARTING || this == STOPPING;
    }
    
    /**
     * Parses a string status into a ServiceHealth enum.
     */
    public static ServiceHealth fromString(String status) {
        if (status == null) {
            return UNKNOWN;
        }
        
        for (ServiceHealth health : values()) {
            if (health.status.equalsIgnoreCase(status)) {
                return health;
            }
        }
        
        return UNKNOWN;
    }
    
    @Override
    public String toString() {
        return status;
    }
}
