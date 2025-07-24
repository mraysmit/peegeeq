package dev.mars.peegeeq.servicemanager.registration;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.servicemanager.discovery.ConsulServiceDiscovery;
import dev.mars.peegeeq.servicemanager.model.PeeGeeQInstance;
import dev.mars.peegeeq.servicemanager.model.ServiceHealth;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Handles REST API endpoints for PeeGeeQ instance registration and management.
 * 
 * This handler provides endpoints for:
 * - Registering new PeeGeeQ instances
 * - Deregistering instances
 * - Listing all registered instances
 * - Checking instance health
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-24
 * @version 1.0
 */
public class InstanceRegistrationHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(InstanceRegistrationHandler.class);
    
    private final ConsulServiceDiscovery serviceDiscovery;
    private final ObjectMapper objectMapper;
    
    public InstanceRegistrationHandler(ConsulServiceDiscovery serviceDiscovery, ObjectMapper objectMapper) {
        this.serviceDiscovery = serviceDiscovery;
        this.objectMapper = objectMapper;
    }
    
    /**
     * Registers a new PeeGeeQ instance.
     * POST /api/v1/instances/register
     */
    public void registerInstance(RoutingContext ctx) {
        logger.debug("Instance registration requested");
        
        try {
            String body = ctx.body().asString();
            JsonObject registrationData = new JsonObject(body);
            
            // Validate required fields
            String instanceId = registrationData.getString("instanceId");
            String host = registrationData.getString("host");
            Integer port = registrationData.getInteger("port");
            
            if (instanceId == null || instanceId.trim().isEmpty()) {
                sendError(ctx, 400, "instanceId is required");
                return;
            }
            
            if (host == null || host.trim().isEmpty()) {
                sendError(ctx, 400, "host is required");
                return;
            }
            
            if (port == null || port <= 0 || port > 65535) {
                sendError(ctx, 400, "port must be a valid port number (1-65535)");
                return;
            }
            
            // Create PeeGeeQ instance
            PeeGeeQInstance.Builder builder = PeeGeeQInstance.builder()
                    .instanceId(instanceId)
                    .host(host)
                    .port(port)
                    .version(registrationData.getString("version", "unknown"))
                    .environment(registrationData.getString("environment", "default"))
                    .region(registrationData.getString("region", "default"));
            
            // Add metadata if provided
            JsonObject metadata = registrationData.getJsonObject("metadata");
            if (metadata != null) {
                for (Map.Entry<String, Object> entry : metadata.getMap().entrySet()) {
                    builder.metadata(entry.getKey(), String.valueOf(entry.getValue()));
                }
            }
            
            PeeGeeQInstance instance = builder.build();
            
            // Register with service discovery
            serviceDiscovery.registerInstance(instance)
                    .onSuccess(v -> {
                        logger.info("Successfully registered instance: {}", instanceId);
                        
                        JsonObject response = new JsonObject()
                                .put("message", "Instance registered successfully")
                                .put("instanceId", instanceId)
                                .put("status", "registered")
                                .put("timestamp", Instant.now().toString());
                        
                        ctx.response()
                                .setStatusCode(201)
                                .putHeader("content-type", "application/json")
                                .end(response.encode());
                    })
                    .onFailure(throwable -> {
                        logger.error("Failed to register instance: {}", instanceId, throwable);
                        sendError(ctx, 500, "Failed to register instance: " + throwable.getMessage());
                    });
            
        } catch (Exception e) {
            logger.error("Error processing instance registration", e);
            sendError(ctx, 400, "Invalid registration data: " + e.getMessage());
        }
    }
    
    /**
     * Deregisters a PeeGeeQ instance.
     * DELETE /api/v1/instances/:instanceId/deregister
     */
    public void deregisterInstance(RoutingContext ctx) {
        String instanceId = ctx.pathParam("instanceId");
        
        logger.debug("Instance deregistration requested for: {}", instanceId);
        
        if (instanceId == null || instanceId.trim().isEmpty()) {
            sendError(ctx, 400, "instanceId is required");
            return;
        }
        
        serviceDiscovery.deregisterInstance(instanceId)
                .onSuccess(v -> {
                    logger.info("Successfully deregistered instance: {}", instanceId);
                    
                    JsonObject response = new JsonObject()
                            .put("message", "Instance deregistered successfully")
                            .put("instanceId", instanceId)
                            .put("status", "deregistered")
                            .put("timestamp", Instant.now().toString());
                    
                    ctx.response()
                            .setStatusCode(200)
                            .putHeader("content-type", "application/json")
                            .end(response.encode());
                })
                .onFailure(throwable -> {
                    logger.error("Failed to deregister instance: {}", instanceId, throwable);
                    sendError(ctx, 500, "Failed to deregister instance: " + throwable.getMessage());
                });
    }
    
    /**
     * Lists all registered PeeGeeQ instances.
     * GET /api/v1/instances
     */
    public void listInstances(RoutingContext ctx) {
        logger.debug("Instance list requested");
        
        // Get query parameters for filtering
        String environment = ctx.request().getParam("environment");
        String region = ctx.request().getParam("region");
        String status = ctx.request().getParam("status");
        
        serviceDiscovery.discoverInstances()
                .onSuccess(instances -> {
                    // Apply filters if provided
                    List<PeeGeeQInstance> filteredInstances = instances.stream()
                            .filter(instance -> environment == null || instance.isInEnvironment(environment))
                            .filter(instance -> region == null || instance.isInRegion(region))
                            .filter(instance -> status == null || instance.getStatus().toString().equalsIgnoreCase(status))
                            .toList();
                    
                    JsonArray instanceArray = new JsonArray();
                    for (PeeGeeQInstance instance : filteredInstances) {
                        JsonObject instanceJson = new JsonObject()
                                .put("instanceId", instance.getInstanceId())
                                .put("host", instance.getHost())
                                .put("port", instance.getPort())
                                .put("version", instance.getVersion())
                                .put("environment", instance.getEnvironment())
                                .put("region", instance.getRegion())
                                .put("status", instance.getStatus().toString())
                                .put("baseUrl", instance.getBaseUrl())
                                .put("managementUrl", instance.getManagementUrl())
                                .put("registeredAt", instance.getRegisteredAt() != null ? 
                                        instance.getRegisteredAt().toString() : null)
                                .put("lastHealthCheck", instance.getLastHealthCheck() != null ? 
                                        instance.getLastHealthCheck().toString() : null);
                        
                        if (instance.getMetadata() != null && !instance.getMetadata().isEmpty()) {
                            JsonObject metadataJson = new JsonObject();
                            instance.getMetadata().forEach(metadataJson::put);
                            instanceJson.put("metadata", metadataJson);
                        }
                        
                        instanceArray.add(instanceJson);
                    }
                    
                    JsonObject response = new JsonObject()
                            .put("message", "Instances retrieved successfully")
                            .put("instanceCount", filteredInstances.size())
                            .put("totalInstances", instances.size())
                            .put("instances", instanceArray)
                            .put("timestamp", Instant.now().toString());
                    
                    if (environment != null) response.put("filteredByEnvironment", environment);
                    if (region != null) response.put("filteredByRegion", region);
                    if (status != null) response.put("filteredByStatus", status);
                    
                    ctx.response()
                            .setStatusCode(200)
                            .putHeader("content-type", "application/json")
                            .end(response.encode());
                })
                .onFailure(throwable -> {
                    logger.error("Failed to list instances", throwable);
                    sendError(ctx, 500, "Failed to retrieve instances: " + throwable.getMessage());
                });
    }
    
    /**
     * Checks the health of a specific instance.
     * GET /api/v1/instances/:instanceId/health
     */
    public void checkInstanceHealth(RoutingContext ctx) {
        String instanceId = ctx.pathParam("instanceId");
        
        logger.debug("Health check requested for instance: {}", instanceId);
        
        if (instanceId == null || instanceId.trim().isEmpty()) {
            sendError(ctx, 400, "instanceId is required");
            return;
        }
        
        serviceDiscovery.checkInstanceHealth(instanceId)
                .onSuccess(health -> {
                    JsonObject response = new JsonObject()
                            .put("instanceId", instanceId)
                            .put("status", health.toString())
                            .put("healthy", health.isAvailable())
                            .put("timestamp", Instant.now().toString());
                    
                    ctx.response()
                            .setStatusCode(200)
                            .putHeader("content-type", "application/json")
                            .end(response.encode());
                })
                .onFailure(throwable -> {
                    logger.error("Failed to check health for instance: {}", instanceId, throwable);
                    
                    JsonObject response = new JsonObject()
                            .put("instanceId", instanceId)
                            .put("status", ServiceHealth.UNKNOWN.toString())
                            .put("healthy", false)
                            .put("error", throwable.getMessage())
                            .put("timestamp", Instant.now().toString());
                    
                    ctx.response()
                            .setStatusCode(200)
                            .putHeader("content-type", "application/json")
                            .end(response.encode());
                });
    }
    
    private void sendError(RoutingContext ctx, int statusCode, String message) {
        JsonObject error = new JsonObject()
                .put("error", message)
                .put("timestamp", Instant.now().toString())
                .put("status", statusCode);
        
        ctx.response()
                .setStatusCode(statusCode)
                .putHeader("content-type", "application/json")
                .end(error.encode());
    }
}
