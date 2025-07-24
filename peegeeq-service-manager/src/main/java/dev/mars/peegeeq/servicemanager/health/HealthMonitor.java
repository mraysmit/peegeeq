package dev.mars.peegeeq.servicemanager.health;

import dev.mars.peegeeq.servicemanager.discovery.ConsulServiceDiscovery;
import dev.mars.peegeeq.servicemanager.model.PeeGeeQInstance;
import dev.mars.peegeeq.servicemanager.model.ServiceHealth;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.predicate.ResponsePredicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Health monitor for PeeGeeQ instances with automatic failover capabilities.
 * 
 * This component:
 * - Performs periodic health checks on registered instances
 * - Updates instance health status
 * - Triggers failover when instances become unhealthy
 * - Provides health metrics and statistics
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-24
 * @version 1.0
 */
public class HealthMonitor {
    
    private static final Logger logger = LoggerFactory.getLogger(HealthMonitor.class);
    
    private final Vertx vertx;
    private final ConsulServiceDiscovery serviceDiscovery;
    private final WebClient webClient;
    
    // Configuration
    private final int healthCheckInterval;
    private final int healthCheckTimeout;
    private final int maxFailures;
    
    // Health tracking
    private final ConcurrentHashMap<String, HealthStatus> instanceHealthStatus = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> failureCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> lastHealthChecks = new ConcurrentHashMap<>();
    
    // Monitoring state
    private Long healthCheckTimerId;
    private boolean monitoring = false;
    
    public HealthMonitor(Vertx vertx, ConsulServiceDiscovery serviceDiscovery) {
        this(vertx, serviceDiscovery, 30000, 5000, 3);
    }
    
    public HealthMonitor(Vertx vertx, ConsulServiceDiscovery serviceDiscovery, 
                        int healthCheckInterval, int healthCheckTimeout, int maxFailures) {
        this.vertx = vertx;
        this.serviceDiscovery = serviceDiscovery;
        this.webClient = WebClient.create(vertx);
        this.healthCheckInterval = healthCheckInterval;
        this.healthCheckTimeout = healthCheckTimeout;
        this.maxFailures = maxFailures;
    }
    
    /**
     * Starts the health monitoring process.
     */
    public void startMonitoring() {
        if (monitoring) {
            logger.warn("Health monitoring is already running");
            return;
        }
        
        logger.info("Starting health monitoring with interval: {}ms, timeout: {}ms, max failures: {}", 
                healthCheckInterval, healthCheckTimeout, maxFailures);
        
        monitoring = true;
        
        // Schedule periodic health checks
        healthCheckTimerId = vertx.setPeriodic(healthCheckInterval, timerId -> {
            performHealthChecks()
                    .onSuccess(results -> logger.debug("Health check completed for {} instances", results.size()))
                    .onFailure(throwable -> logger.error("Health check failed", throwable));
        });
        
        // Perform initial health check
        performHealthChecks();
    }
    
    /**
     * Stops the health monitoring process.
     */
    public void stopMonitoring() {
        if (!monitoring) {
            logger.warn("Health monitoring is not running");
            return;
        }
        
        logger.info("Stopping health monitoring");
        
        monitoring = false;
        
        if (healthCheckTimerId != null) {
            vertx.cancelTimer(healthCheckTimerId);
            healthCheckTimerId = null;
        }
    }
    
    /**
     * Performs health checks on all registered instances.
     */
    public Future<List<HealthCheckResult>> performHealthChecks() {
        return serviceDiscovery.discoverInstances()
                .compose(instances -> {
                    if (instances.isEmpty()) {
                        logger.debug("No instances to health check");
                        return Future.succeededFuture(List.of());
                    }
                    
                    logger.debug("Performing health checks on {} instances", instances.size());
                    
                    List<Future<HealthCheckResult>> healthCheckFutures = instances.stream()
                            .map(this::performHealthCheck)
                            .toList();
                    
                    return Future.all(healthCheckFutures)
                            .map(compositeFuture -> compositeFuture.list());
                });
    }
    
    /**
     * Performs a health check on a specific instance.
     */
    public Future<HealthCheckResult> performHealthCheck(PeeGeeQInstance instance) {
        String instanceId = instance.getInstanceId();
        String healthUrl = instance.getHealthUrl();
        
        logger.debug("Health checking instance: {} at {}", instanceId, healthUrl);
        
        Instant checkTime = Instant.now();
        lastHealthChecks.put(instanceId, checkTime);
        
        return webClient.getAbs(healthUrl)
                .timeout(healthCheckTimeout)
                .expect(ResponsePredicate.SC_OK)
                .send()
                .map(response -> {
                    // Health check successful
                    JsonObject healthData = response.bodyAsJsonObject();
                    
                    // Reset failure count
                    failureCounts.put(instanceId, new AtomicInteger(0));
                    
                    // Update health status
                    HealthStatus status = new HealthStatus(ServiceHealth.HEALTHY, checkTime, 
                            healthData.getString("status", "UP"));
                    instanceHealthStatus.put(instanceId, status);
                    
                    logger.debug("Health check successful for instance: {}", instanceId);
                    
                    return new HealthCheckResult(instance, ServiceHealth.HEALTHY, checkTime, null, healthData);
                })
                .recover(throwable -> {
                    // Health check failed
                    logger.warn("Health check failed for instance {}: {}", instanceId, throwable.getMessage());
                    
                    // Increment failure count
                    AtomicInteger failures = failureCounts.computeIfAbsent(instanceId, k -> new AtomicInteger(0));
                    int failureCount = failures.incrementAndGet();
                    
                    // Determine health status based on failure count
                    ServiceHealth health = failureCount >= maxFailures ? ServiceHealth.UNHEALTHY : ServiceHealth.UNKNOWN;
                    
                    // Update health status
                    HealthStatus status = new HealthStatus(health, checkTime, throwable.getMessage());
                    instanceHealthStatus.put(instanceId, status);
                    
                    if (health == ServiceHealth.UNHEALTHY) {
                        logger.error("Instance {} marked as unhealthy after {} failures", instanceId, failureCount);
                        // Could trigger additional failover actions here
                    }
                    
                    return Future.succeededFuture(new HealthCheckResult(instance, health, checkTime, 
                            throwable.getMessage(), null));
                });
    }
    
    /**
     * Gets the current health status of an instance.
     */
    public HealthStatus getInstanceHealth(String instanceId) {
        return instanceHealthStatus.get(instanceId);
    }
    
    /**
     * Gets the health status of all monitored instances.
     */
    public JsonObject getAllInstanceHealth() {
        JsonObject healthReport = new JsonObject();
        
        instanceHealthStatus.forEach((instanceId, status) -> {
            JsonObject instanceHealth = new JsonObject()
                    .put("status", status.getHealth().toString())
                    .put("lastCheck", status.getLastCheck().toString())
                    .put("message", status.getMessage())
                    .put("failureCount", getFailureCount(instanceId));
            
            healthReport.put(instanceId, instanceHealth);
        });
        
        return healthReport;
    }
    
    /**
     * Gets the failure count for a specific instance.
     */
    public int getFailureCount(String instanceId) {
        AtomicInteger count = failureCounts.get(instanceId);
        return count != null ? count.get() : 0;
    }
    
    /**
     * Gets the last health check time for a specific instance.
     */
    public Instant getLastHealthCheck(String instanceId) {
        return lastHealthChecks.get(instanceId);
    }
    
    /**
     * Checks if an instance is considered healthy.
     */
    public boolean isInstanceHealthy(String instanceId) {
        HealthStatus status = instanceHealthStatus.get(instanceId);
        return status != null && status.getHealth() == ServiceHealth.HEALTHY;
    }
    
    /**
     * Gets health monitoring statistics.
     */
    public JsonObject getHealthStats() {
        int totalInstances = instanceHealthStatus.size();
        long healthyInstances = instanceHealthStatus.values().stream()
                .filter(status -> status.getHealth() == ServiceHealth.HEALTHY)
                .count();
        long unhealthyInstances = instanceHealthStatus.values().stream()
                .filter(status -> status.getHealth() == ServiceHealth.UNHEALTHY)
                .count();
        long unknownInstances = totalInstances - healthyInstances - unhealthyInstances;
        
        return new JsonObject()
                .put("totalInstances", totalInstances)
                .put("healthyInstances", healthyInstances)
                .put("unhealthyInstances", unhealthyInstances)
                .put("unknownInstances", unknownInstances)
                .put("healthCheckInterval", healthCheckInterval)
                .put("healthCheckTimeout", healthCheckTimeout)
                .put("maxFailures", maxFailures)
                .put("monitoring", monitoring)
                .put("timestamp", Instant.now().toString());
    }
    
    /**
     * Resets health status for a specific instance (useful for testing).
     */
    public void resetInstanceHealth(String instanceId) {
        instanceHealthStatus.remove(instanceId);
        failureCounts.remove(instanceId);
        lastHealthChecks.remove(instanceId);
    }
    
    /**
     * Resets all health status data (useful for testing).
     */
    public void resetAllHealth() {
        instanceHealthStatus.clear();
        failureCounts.clear();
        lastHealthChecks.clear();
    }
    
    public boolean isMonitoring() {
        return monitoring;
    }
    
    @Override
    public String toString() {
        return "HealthMonitor{" +
                "healthCheckInterval=" + healthCheckInterval +
                ", healthCheckTimeout=" + healthCheckTimeout +
                ", maxFailures=" + maxFailures +
                ", monitoring=" + monitoring +
                ", instanceCount=" + instanceHealthStatus.size() +
                '}';
    }
}
