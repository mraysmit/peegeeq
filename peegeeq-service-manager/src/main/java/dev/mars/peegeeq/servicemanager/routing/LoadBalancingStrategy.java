package dev.mars.peegeeq.servicemanager.routing;

/**
 * Load balancing strategies for distributing requests across PeeGeeQ instances.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-24
 * @version 1.0
 */
public enum LoadBalancingStrategy {
    
    /**
     * Round-robin distribution - requests are distributed evenly across instances in order.
     */
    ROUND_ROBIN("round_robin"),
    
    /**
     * Random distribution - requests are distributed randomly across instances.
     */
    RANDOM("random"),
    
    /**
     * First available - always selects the first healthy instance.
     */
    FIRST_AVAILABLE("first_available"),
    
    /**
     * Least connections - selects the instance with the fewest active connections (future).
     */
    LEAST_CONNECTIONS("least_connections"),
    
    /**
     * Weighted round-robin - distributes based on instance weights (future).
     */
    WEIGHTED_ROUND_ROBIN("weighted_round_robin");
    
    private final String strategy;
    
    LoadBalancingStrategy(String strategy) {
        this.strategy = strategy;
    }
    
    public String getStrategy() {
        return strategy;
    }
    
    /**
     * Parses a string strategy into a LoadBalancingStrategy enum.
     */
    public static LoadBalancingStrategy fromString(String strategy) {
        if (strategy == null) {
            return ROUND_ROBIN; // Default
        }
        
        for (LoadBalancingStrategy lbs : values()) {
            if (lbs.strategy.equalsIgnoreCase(strategy)) {
                return lbs;
            }
        }
        
        return ROUND_ROBIN; // Default fallback
    }
    
    /**
     * Checks if the strategy is currently implemented.
     */
    public boolean isImplemented() {
        switch (this) {
            case ROUND_ROBIN:
            case RANDOM:
            case FIRST_AVAILABLE:
                return true;
            case LEAST_CONNECTIONS:
            case WEIGHTED_ROUND_ROBIN:
                return false;
            default:
                return false;
        }
    }
    
    @Override
    public String toString() {
        return strategy;
    }
}
