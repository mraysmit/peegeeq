package dev.mars.peegeeq.rest.handlers;

/**
 * Load balancing strategies for consumer groups.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-19
 * @version 1.0
 */
public enum LoadBalancingStrategy {
    
    /**
     * Round-robin assignment of partitions to members.
     */
    ROUND_ROBIN,
    
    /**
     * Range-based assignment of partitions to members.
     */
    RANGE,
    
    /**
     * Sticky assignment that tries to minimize partition reassignment.
     */
    STICKY,
    
    /**
     * Random assignment of partitions to members.
     */
    RANDOM
}
