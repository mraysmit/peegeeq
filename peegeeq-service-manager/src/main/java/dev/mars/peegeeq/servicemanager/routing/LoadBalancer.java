package dev.mars.peegeeq.servicemanager.routing;

import dev.mars.peegeeq.servicemanager.model.PeeGeeQInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Load balancer for distributing requests across healthy PeeGeeQ instances.
 * 
 * Supports multiple load balancing strategies:
 * - Round Robin
 * - Least Connections (future)
 * - Weighted Round Robin (future)
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-24
 * @version 1.0
 */
public class LoadBalancer {
    
    private static final Logger logger = LoggerFactory.getLogger(LoadBalancer.class);
    
    private final LoadBalancingStrategy strategy;
    private final AtomicInteger roundRobinCounter = new AtomicInteger(0);
    
    public LoadBalancer() {
        this(LoadBalancingStrategy.ROUND_ROBIN);
    }
    
    public LoadBalancer(LoadBalancingStrategy strategy) {
        this.strategy = strategy;
    }
    
    /**
     * Selects the best instance from the available healthy instances.
     */
    public PeeGeeQInstance selectInstance(List<PeeGeeQInstance> instances) {
        if (instances == null || instances.isEmpty()) {
            logger.warn("No instances available for load balancing");
            return null;
        }
        
        // Filter to only healthy instances
        List<PeeGeeQInstance> healthyInstances = instances.stream()
                .filter(PeeGeeQInstance::isHealthy)
                .collect(Collectors.toList());
        
        if (healthyInstances.isEmpty()) {
            logger.warn("No healthy instances available for load balancing");
            return null;
        }
        
        return switch (strategy) {
            case ROUND_ROBIN -> selectRoundRobin(healthyInstances);
            case RANDOM -> selectRandom(healthyInstances);
            case FIRST_AVAILABLE -> selectFirstAvailable(healthyInstances);
            case LEAST_CONNECTIONS, WEIGHTED_ROUND_ROBIN -> {
                logger.warn("Strategy {} not yet implemented, falling back to ROUND_ROBIN", strategy);
                yield selectRoundRobin(healthyInstances);
            }
        };
    }
    
    /**
     * Selects instances based on environment filter.
     */
    public PeeGeeQInstance selectInstance(List<PeeGeeQInstance> instances, String environment) {
        if (environment == null) {
            return selectInstance(instances);
        }
        
        List<PeeGeeQInstance> filteredInstances = instances.stream()
                .filter(instance -> instance.isInEnvironment(environment))
                .collect(Collectors.toList());
        
        return selectInstance(filteredInstances);
    }
    
    /**
     * Selects instances based on environment and region filters.
     */
    public PeeGeeQInstance selectInstance(List<PeeGeeQInstance> instances, String environment, String region) {
        if (environment == null && region == null) {
            return selectInstance(instances);
        }
        
        List<PeeGeeQInstance> filteredInstances = instances.stream()
                .filter(instance -> environment == null || instance.isInEnvironment(environment))
                .filter(instance -> region == null || instance.isInRegion(region))
                .collect(Collectors.toList());
        
        return selectInstance(filteredInstances);
    }
    
    /**
     * Gets all healthy instances from the list.
     */
    public List<PeeGeeQInstance> getHealthyInstances(List<PeeGeeQInstance> instances) {
        return instances.stream()
                .filter(PeeGeeQInstance::isHealthy)
                .collect(Collectors.toList());
    }
    
    /**
     * Gets healthy instances filtered by environment.
     */
    public List<PeeGeeQInstance> getHealthyInstances(List<PeeGeeQInstance> instances, String environment) {
        return instances.stream()
                .filter(PeeGeeQInstance::isHealthy)
                .filter(instance -> environment == null || instance.isInEnvironment(environment))
                .collect(Collectors.toList());
    }
    
    /**
     * Gets healthy instances filtered by environment and region.
     */
    public List<PeeGeeQInstance> getHealthyInstances(List<PeeGeeQInstance> instances, String environment, String region) {
        return instances.stream()
                .filter(PeeGeeQInstance::isHealthy)
                .filter(instance -> environment == null || instance.isInEnvironment(environment))
                .filter(instance -> region == null || instance.isInRegion(region))
                .collect(Collectors.toList());
    }
    
    /**
     * Checks if there are any healthy instances available.
     */
    public boolean hasHealthyInstances(List<PeeGeeQInstance> instances) {
        return instances.stream().anyMatch(PeeGeeQInstance::isHealthy);
    }
    
    /**
     * Gets the count of healthy instances.
     */
    public int getHealthyInstanceCount(List<PeeGeeQInstance> instances) {
        return (int) instances.stream().filter(PeeGeeQInstance::isHealthy).count();
    }
    
    // Load balancing strategy implementations
    
    private PeeGeeQInstance selectRoundRobin(List<PeeGeeQInstance> healthyInstances) {
        if (healthyInstances.isEmpty()) {
            return null;
        }
        
        int index = roundRobinCounter.getAndIncrement() % healthyInstances.size();
        PeeGeeQInstance selected = healthyInstances.get(index);
        
        logger.debug("Round robin selected instance: {} (index: {})", 
                selected.getInstanceId(), index);
        
        return selected;
    }
    
    private PeeGeeQInstance selectRandom(List<PeeGeeQInstance> healthyInstances) {
        if (healthyInstances.isEmpty()) {
            return null;
        }
        
        int index = (int) (Math.random() * healthyInstances.size());
        PeeGeeQInstance selected = healthyInstances.get(index);
        
        logger.debug("Random selected instance: {} (index: {})", 
                selected.getInstanceId(), index);
        
        return selected;
    }
    
    private PeeGeeQInstance selectFirstAvailable(List<PeeGeeQInstance> healthyInstances) {
        if (healthyInstances.isEmpty()) {
            return null;
        }
        
        PeeGeeQInstance selected = healthyInstances.get(0);
        
        logger.debug("First available selected instance: {}", selected.getInstanceId());
        
        return selected;
    }
    
    public LoadBalancingStrategy getStrategy() {
        return strategy;
    }
    
    /**
     * Creates a load balancer with the specified strategy.
     */
    public static LoadBalancer create(LoadBalancingStrategy strategy) {
        return new LoadBalancer(strategy);
    }
    
    /**
     * Creates a round-robin load balancer.
     */
    public static LoadBalancer roundRobin() {
        return new LoadBalancer(LoadBalancingStrategy.ROUND_ROBIN);
    }
    
    /**
     * Creates a random load balancer.
     */
    public static LoadBalancer random() {
        return new LoadBalancer(LoadBalancingStrategy.RANDOM);
    }
    
    /**
     * Creates a first-available load balancer.
     */
    public static LoadBalancer firstAvailable() {
        return new LoadBalancer(LoadBalancingStrategy.FIRST_AVAILABLE);
    }
    
    /**
     * Resets the round-robin counter (useful for testing).
     */
    public void resetCounter() {
        roundRobinCounter.set(0);
    }
    
    @Override
    public String toString() {
        return "LoadBalancer{" +
                "strategy=" + strategy +
                ", roundRobinCounter=" + roundRobinCounter.get() +
                '}';
    }
}
