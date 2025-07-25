package dev.mars.peegeeq.servicemanager.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.servicemanager.model.PeeGeeQInstance;
import dev.mars.peegeeq.servicemanager.model.ServiceHealth;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.consul.ConsulClient;
import io.vertx.ext.consul.Service;
import io.vertx.ext.consul.ServiceOptions;
import io.vertx.ext.consul.CheckOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Consul-based service discovery for PeeGeeQ instances.
 * 
 * This class manages the registration, deregistration, and discovery of PeeGeeQ instances
 * using HashiCorp Consul as the service registry.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-24
 * @version 1.0
 */
public class ConsulServiceDiscovery {
    
    private static final Logger logger = LoggerFactory.getLogger(ConsulServiceDiscovery.class);
    
    private final Vertx vertx;
    private final ConsulClient consulClient;
    private final ObjectMapper objectMapper;
    
    // Cache for registered instances
    private final Map<String, PeeGeeQInstance> instanceCache = new ConcurrentHashMap<>();
    
    // Service name for PeeGeeQ instances in Consul
    private static final String PEEGEEQ_SERVICE_NAME = "peegeeq-instance";
    
    public ConsulServiceDiscovery(Vertx vertx, ConsulClient consulClient, ObjectMapper objectMapper) {
        this.vertx = vertx;
        this.consulClient = consulClient;
        this.objectMapper = objectMapper;

        // Start periodic cache refresh
        startCacheRefresh();
    }

    public Vertx getVertx() {
        return vertx;
    }
    
    /**
     * Registers a PeeGeeQ instance with Consul.
     */
    public Future<Void> registerInstance(PeeGeeQInstance instance) {
        Promise<Void> promise = Promise.promise();
        
        logger.info("Registering PeeGeeQ instance: {} at {}:{}", 
                instance.getInstanceId(), instance.getHost(), instance.getPort());
        
        try {
            // Create service options for Consul registration
            ServiceOptions serviceOptions = new ServiceOptions()
                    .setId(instance.getInstanceId())
                    .setName(PEEGEEQ_SERVICE_NAME)
                    .setAddress(instance.getHost())
                    .setPort(instance.getPort())
                    .setTags(createServiceTags(instance));
            
            // Only add health check if not in test environment
            if (!"test".equals(instance.getEnvironment()) && !"test-unhealthy".equals(instance.getEnvironment())) {
                String healthUrl = "http://" + instance.getHost() + ":" + instance.getPort() + "/health";
                CheckOptions healthCheck = new CheckOptions()
                        .setHttp(healthUrl)
                        .setInterval("10s");

                serviceOptions.setCheckOptions(healthCheck);

                logger.info("🔍 Registering service with health check URL: {}", healthUrl);
                logger.info("🔍 Health check interval: 10s");
            } else if ("test-unhealthy".equals(instance.getEnvironment())) {
                // For test-unhealthy environment, add a failing health check
                CheckOptions healthCheck = new CheckOptions()
                        .setHttp("http://localhost:99999/nonexistent")  // This will always fail
                        .setInterval("10s");

                serviceOptions.setCheckOptions(healthCheck);
                logger.info("🔍 Registering service with failing health check (test-unhealthy environment)");
            } else {
                logger.info("🔍 Registering service without health check (test environment)");
            }
            
            // Register with Consul
            consulClient.registerService(serviceOptions, result -> {
                if (result.succeeded()) {
                    // Add to local cache
                    instance.setRegisteredAt(Instant.now());
                    instance.setStatus(ServiceHealth.HEALTHY);
                    instanceCache.put(instance.getInstanceId(), instance);
                    
                    logger.info("Successfully registered PeeGeeQ instance: {}", instance.getInstanceId());
                    promise.complete();
                } else {
                    logger.error("Failed to register PeeGeeQ instance: {}", instance.getInstanceId(), result.cause());
                    promise.fail(result.cause());
                }
            });
            
        } catch (Exception e) {
            logger.error("Error preparing instance registration for: {}", instance.getInstanceId(), e);
            promise.fail(e);
        }
        
        return promise.future();
    }
    
    /**
     * Deregisters a PeeGeeQ instance from Consul.
     */
    public Future<Void> deregisterInstance(String instanceId) {
        Promise<Void> promise = Promise.promise();
        
        logger.info("Deregistering PeeGeeQ instance: {}", instanceId);
        
        consulClient.deregisterService(instanceId, result -> {
            if (result.succeeded()) {
                // Remove from local cache
                instanceCache.remove(instanceId);
                
                logger.info("Successfully deregistered PeeGeeQ instance: {}", instanceId);
                promise.complete();
            } else {
                logger.error("Failed to deregister PeeGeeQ instance: {}", instanceId, result.cause());
                promise.fail(result.cause());
            }
        });
        
        return promise.future();
    }
    
    /**
     * Discovers all healthy PeeGeeQ instances from Consul.
     */
    public Future<List<PeeGeeQInstance>> discoverInstances() {
        Promise<List<PeeGeeQInstance>> promise = Promise.promise();
        
        logger.debug("Discovering PeeGeeQ instances from Consul");
        
        logger.info("🔍 Querying Consul for healthy services with name: {}", PEEGEEQ_SERVICE_NAME);

        // First, let's see ALL services (healthy and unhealthy) for debugging
        consulClient.healthServiceNodes(PEEGEEQ_SERVICE_NAME, false, allResult -> {
            if (allResult.succeeded()) {
                logger.info("🔍 DEBUG: Total services (healthy + unhealthy): {}", allResult.result().getList().size());
                allResult.result().getList().forEach(entry -> {
                    logger.info("🔍 DEBUG: Service {} has {} health checks",
                            entry.getService().getId(), entry.getChecks().size());
                    entry.getChecks().forEach(check -> {
                        logger.info("🔍 DEBUG: Check status: {}, output: {}",
                                check.getStatus(), check.getOutput());
                    });
                });
            }
        });

        // Now query for only healthy services
        consulClient.healthServiceNodes(PEEGEEQ_SERVICE_NAME, true, result -> {
            if (result.succeeded()) {
                try {
                    List<PeeGeeQInstance> instances = new ArrayList<>();

                    logger.info("🔍 Consul returned {} service entries", result.result().getList().size());

                    for (io.vertx.ext.consul.ServiceEntry serviceEntry : result.result().getList()) {
                        logger.info("🔍 Processing service entry: {}", serviceEntry.getService().getId());
                        logger.info("🔍 Service health checks: {}", serviceEntry.getChecks().size());

                        // Log health check details
                        serviceEntry.getChecks().forEach(check -> {
                            logger.info("🔍 Health check - Status: {}, Output: {}",
                                    check.getStatus(), check.getOutput());
                        });

                        PeeGeeQInstance instance = convertServiceEntryToInstance(serviceEntry);
                        if (instance != null) {
                            instances.add(instance);
                            // Update cache
                            instanceCache.put(instance.getInstanceId(), instance);
                        }
                    }

                    logger.info("🔍 Discovered {} healthy PeeGeeQ instances", instances.size());
                    promise.complete(instances);
                    
                } catch (Exception e) {
                    logger.error("Error processing discovered services", e);
                    promise.fail(e);
                }
            } else {
                logger.error("Failed to discover PeeGeeQ instances from Consul", result.cause());
                promise.fail(result.cause());
            }
        });
        
        return promise.future();
    }
    
    /**
     * Gets a specific instance by ID.
     */
    public Future<PeeGeeQInstance> getInstance(String instanceId) {
        Promise<PeeGeeQInstance> promise = Promise.promise();
        
        // Check cache first
        PeeGeeQInstance cachedInstance = instanceCache.get(instanceId);
        if (cachedInstance != null) {
            promise.complete(cachedInstance);
            return promise.future();
        }
        
        // If not in cache, discover from Consul
        discoverInstances()
                .onSuccess(instances -> {
                    PeeGeeQInstance found = instances.stream()
                            .filter(instance -> instance.getInstanceId().equals(instanceId))
                            .findFirst()
                            .orElse(null);
                    
                    if (found != null) {
                        promise.complete(found);
                    } else {
                        promise.fail(new RuntimeException("Instance not found: " + instanceId));
                    }
                })
                .onFailure(promise::fail);
        
        return promise.future();
    }
    
    /**
     * Gets all cached instances (for performance).
     */
    public List<PeeGeeQInstance> getCachedInstances() {
        return new ArrayList<>(instanceCache.values());
    }
    
    /**
     * Checks the health of a specific instance.
     */
    public Future<ServiceHealth> checkInstanceHealth(String instanceId) {
        Promise<ServiceHealth> promise = Promise.promise();
        
        consulClient.healthServiceNodes(PEEGEEQ_SERVICE_NAME, true, result -> {
            if (result.succeeded()) {
                // Find the specific instance and check its health
                boolean found = false;
                for (io.vertx.ext.consul.ServiceEntry serviceEntry : result.result().getList()) {
                    if (serviceEntry.getService().getId().equals(instanceId)) {
                        found = true;
                        // In a real implementation, you'd check the health status from Consul
                        // For now, assume healthy if found
                        promise.complete(ServiceHealth.HEALTHY);
                        break;
                    }
                }

                if (!found) {
                    promise.complete(ServiceHealth.UNHEALTHY);
                }
            } else {
                logger.error("Failed to check health for instance: {}", instanceId, result.cause());
                promise.complete(ServiceHealth.UNKNOWN);
            }
        });
        
        return promise.future();
    }
    
    private List<String> createServiceTags(PeeGeeQInstance instance) {
        List<String> tags = new ArrayList<>();
        tags.add("version=" + instance.getVersion());
        tags.add("environment=" + instance.getEnvironment());
        tags.add("region=" + instance.getRegion());
        
        if (instance.getMetadata() != null) {
            instance.getMetadata().forEach((key, value) -> 
                    tags.add(key + "=" + value));
        }
        
        return tags;
    }
    
    private PeeGeeQInstance convertServiceEntryToInstance(io.vertx.ext.consul.ServiceEntry serviceEntry) {
        try {
            io.vertx.ext.consul.Service service = serviceEntry.getService();
            PeeGeeQInstance instance = new PeeGeeQInstance();
            instance.setInstanceId(service.getId());
            instance.setHost(service.getAddress());
            instance.setPort(service.getPort());
            instance.setStatus(ServiceHealth.HEALTHY); // Assuming healthy since we queried for healthy services

            // Parse tags to extract metadata
            if (service.getTags() != null) {
                for (String tag : service.getTags()) {
                    String[] parts = tag.split("=", 2);
                    if (parts.length == 2) {
                        switch (parts[0]) {
                            case "version":
                                instance.setVersion(parts[1]);
                                break;
                            case "environment":
                                instance.setEnvironment(parts[1]);
                                break;
                            case "region":
                                instance.setRegion(parts[1]);
                                break;
                            default:
                                instance.addMetadata(parts[0], parts[1]);
                                break;
                        }
                    }
                }
            }

            return instance;

        } catch (Exception e) {
            logger.error("Error converting Consul service entry to PeeGeeQ instance", e);
            return null;
        }
    }
    
    private void startCacheRefresh() {
        // Refresh cache every 30 seconds
        vertx.setPeriodic(30000, timerId -> {
            discoverInstances()
                    .onSuccess(instances -> logger.debug("Cache refreshed with {} instances", instances.size()))
                    .onFailure(throwable -> logger.warn("Failed to refresh instance cache", throwable));
        });
    }
}
