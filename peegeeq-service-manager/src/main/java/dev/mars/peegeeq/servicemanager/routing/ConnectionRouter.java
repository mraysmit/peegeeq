package dev.mars.peegeeq.servicemanager.routing;

import dev.mars.peegeeq.servicemanager.discovery.ConsulServiceDiscovery;
import dev.mars.peegeeq.servicemanager.model.PeeGeeQInstance;
import io.vertx.core.Future;
import io.vertx.core.Promise;
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
 * Connection router that intelligently routes requests to healthy PeeGeeQ instances
 * using load balancing and failover strategies.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-24
 * @version 1.0
 */
public class ConnectionRouter {
    
    private static final Logger logger = LoggerFactory.getLogger(ConnectionRouter.class);
    
    private final ConsulServiceDiscovery serviceDiscovery;
    private final WebClient webClient;
    private final LoadBalancer loadBalancer;
    private final int requestTimeout;
    private final int maxRetries;
    
    // Connection tracking for monitoring
    private final ConcurrentHashMap<String, AtomicInteger> connectionCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> lastRequestTimes = new ConcurrentHashMap<>();
    
    public ConnectionRouter(ConsulServiceDiscovery serviceDiscovery, WebClient webClient) {
        this(serviceDiscovery, webClient, LoadBalancingStrategy.ROUND_ROBIN, 10000, 3);
    }
    
    public ConnectionRouter(ConsulServiceDiscovery serviceDiscovery, WebClient webClient, 
                          LoadBalancingStrategy strategy, int requestTimeout, int maxRetries) {
        this.serviceDiscovery = serviceDiscovery;
        this.webClient = webClient;
        this.loadBalancer = new LoadBalancer(strategy);
        this.requestTimeout = requestTimeout;
        this.maxRetries = maxRetries;
    }
    
    /**
     * Routes a GET request to a healthy instance using load balancing.
     */
    public Future<JsonObject> routeGetRequest(String path) {
        return routeGetRequest(path, null, null);
    }
    
    /**
     * Routes a GET request to a healthy instance in a specific environment.
     */
    public Future<JsonObject> routeGetRequest(String path, String environment) {
        return routeGetRequest(path, environment, null);
    }
    
    /**
     * Routes a GET request to a healthy instance in a specific environment and region.
     */
    public Future<JsonObject> routeGetRequest(String path, String environment, String region) {
        return serviceDiscovery.discoverInstances()
                .compose(instances -> {
                    PeeGeeQInstance selectedInstance = loadBalancer.selectInstance(instances, environment, region);
                    
                    if (selectedInstance == null) {
                        return Future.failedFuture("No healthy instances available for routing");
                    }
                    
                    return routeRequestWithRetry(selectedInstance, path, instances, environment, region, 0);
                });
    }
    
    /**
     * Routes a request to a specific instance by ID.
     */
    public Future<JsonObject> routeToInstance(String instanceId, String path) {
        return serviceDiscovery.getInstance(instanceId)
                .compose(instance -> {
                    if (!instance.isHealthy()) {
                        return Future.failedFuture("Instance is not healthy: " + instanceId);
                    }
                    
                    return makeRequest(instance, path);
                });
    }
    
    /**
     * Routes a request with automatic retry and failover.
     */
    private Future<JsonObject> routeRequestWithRetry(PeeGeeQInstance instance, String path, 
                                                   List<PeeGeeQInstance> allInstances, 
                                                   String environment, String region, int retryCount) {
        
        return makeRequest(instance, path)
                .recover(throwable -> {
                    logger.warn("Request failed to instance {}: {}", instance.getInstanceId(), throwable.getMessage());
                    
                    if (retryCount >= maxRetries) {
                        return Future.failedFuture("Max retries exceeded: " + throwable.getMessage());
                    }
                    
                    // Try to select another instance for retry
                    List<PeeGeeQInstance> remainingInstances = allInstances.stream()
                            .filter(inst -> !inst.getInstanceId().equals(instance.getInstanceId()))
                            .toList();
                    
                    PeeGeeQInstance nextInstance = loadBalancer.selectInstance(remainingInstances, environment, region);
                    
                    if (nextInstance == null) {
                        return Future.failedFuture("No more healthy instances available for retry");
                    }
                    
                    logger.info("Retrying request with instance {} (attempt {})", 
                            nextInstance.getInstanceId(), retryCount + 1);
                    
                    return routeRequestWithRetry(nextInstance, path, remainingInstances, 
                                               environment, region, retryCount + 1);
                });
    }
    
    /**
     * Makes an HTTP request to a specific instance.
     */
    private Future<JsonObject> makeRequest(PeeGeeQInstance instance, String path) {
        String url = instance.getBaseUrl() + path;
        String instanceId = instance.getInstanceId();
        
        // Track connection
        connectionCounts.computeIfAbsent(instanceId, k -> new AtomicInteger(0)).incrementAndGet();
        lastRequestTimes.put(instanceId, Instant.now());
        
        logger.debug("Making request to {}: {}", instanceId, url);
        
        Promise<JsonObject> promise = Promise.promise();
        
        webClient.getAbs(url)
                .timeout(requestTimeout)
                .expect(ResponsePredicate.SC_OK)
                .send()
                .onSuccess(response -> {
                    // Decrement connection count
                    AtomicInteger count = connectionCounts.get(instanceId);
                    if (count != null) {
                        count.decrementAndGet();
                    }
                    
                    try {
                        JsonObject result = response.bodyAsJsonObject();
                        promise.complete(result);
                    } catch (Exception e) {
                        logger.error("Failed to parse response from {}: {}", instanceId, e.getMessage());
                        promise.fail("Invalid JSON response from instance");
                    }
                })
                .onFailure(throwable -> {
                    // Decrement connection count
                    AtomicInteger count = connectionCounts.get(instanceId);
                    if (count != null) {
                        count.decrementAndGet();
                    }
                    
                    logger.debug("Request failed to {}: {}", instanceId, throwable.getMessage());
                    promise.fail(throwable);
                });
        
        return promise.future();
    }
    
    /**
     * Gets connection statistics for monitoring.
     */
    public JsonObject getConnectionStats() {
        JsonObject stats = new JsonObject();
        
        connectionCounts.forEach((instanceId, count) -> {
            JsonObject instanceStats = new JsonObject()
                    .put("activeConnections", count.get())
                    .put("lastRequestTime", lastRequestTimes.get(instanceId) != null ? 
                            lastRequestTimes.get(instanceId).toString() : null);
            
            stats.put(instanceId, instanceStats);
        });
        
        return stats;
    }
    
    /**
     * Gets the current load balancing strategy.
     */
    public LoadBalancingStrategy getLoadBalancingStrategy() {
        return loadBalancer.getStrategy();
    }
    
    /**
     * Gets the number of active connections to a specific instance.
     */
    public int getActiveConnections(String instanceId) {
        AtomicInteger count = connectionCounts.get(instanceId);
        return count != null ? count.get() : 0;
    }
    
    /**
     * Gets the last request time for a specific instance.
     */
    public Instant getLastRequestTime(String instanceId) {
        return lastRequestTimes.get(instanceId);
    }
    
    /**
     * Clears connection statistics (useful for testing).
     */
    public void clearStats() {
        connectionCounts.clear();
        lastRequestTimes.clear();
    }
    
    /**
     * Gets the total number of active connections across all instances.
     */
    public int getTotalActiveConnections() {
        return connectionCounts.values().stream()
                .mapToInt(AtomicInteger::get)
                .sum();
    }
    
    @Override
    public String toString() {
        return "ConnectionRouter{" +
                "loadBalancingStrategy=" + loadBalancer.getStrategy() +
                ", requestTimeout=" + requestTimeout +
                ", maxRetries=" + maxRetries +
                ", totalActiveConnections=" + getTotalActiveConnections() +
                '}';
    }
}
