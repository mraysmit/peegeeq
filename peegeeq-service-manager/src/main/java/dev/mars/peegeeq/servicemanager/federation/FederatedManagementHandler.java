package dev.mars.peegeeq.servicemanager.federation;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.api.tracing.TraceContextUtil;
import dev.mars.peegeeq.api.tracing.TraceCtx;
import dev.mars.peegeeq.servicemanager.discovery.ConsulServiceDiscovery;
import dev.mars.peegeeq.servicemanager.model.PeeGeeQInstance;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.WebClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Handles federated management API endpoints that aggregate data from multiple PeeGeeQ instances.
 * 
 * This handler provides endpoints that:
 * - Aggregate data from all registered instances
 * - Route requests to specific instances
 * - Provide unified views across the entire PeeGeeQ cluster
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-24
 * @version 1.0
 */
public class FederatedManagementHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(FederatedManagementHandler.class);
    
    private final ConsulServiceDiscovery serviceDiscovery;
    private final WebClient webClient;
    
    // Request timeout in milliseconds
    private static final int REQUEST_TIMEOUT = 10000;
    
    public FederatedManagementHandler(ConsulServiceDiscovery serviceDiscovery, ObjectMapper objectMapper) {
        this.serviceDiscovery = serviceDiscovery;
        this.webClient = WebClient.create(serviceDiscovery.getVertx());
    }
    
    /**
     * Gets federated overview data from all healthy instances.
     * GET /api/v1/federated/overview
     */
    public void getFederatedOverview(RoutingContext ctx) {
        logger.debug("Federated overview requested");
        
        // Capture trace context for async callbacks
        final TraceCtx traceCtx = getCurrentTraceCtx();
        
        serviceDiscovery.discoverInstances()
                .compose(instances -> {
                    List<PeeGeeQInstance> healthyInstances = instances.stream()
                            .filter(PeeGeeQInstance::isHealthy)
                            .collect(Collectors.toList());
                    
                    if (healthyInstances.isEmpty()) {
                        return Future.succeededFuture(new JsonObject()
                                .put("message", "No healthy instances available")
                                .put("instanceCount", 0)
                                .put("aggregatedData", new JsonObject())
                                .put("timestamp", Instant.now().toString()));
                    }
                    
                    // Fetch overview data from all healthy instances
                    List<Future<JsonObject>> futures = healthyInstances.stream()
                            .map(instance -> fetchInstanceOverview(instance))
                            .collect(Collectors.toList());
                    
                    return Future.all(futures)
                            .map(compositeFuture -> {
                                JsonObject aggregatedOverview = aggregateOverviewData(
                                        healthyInstances, compositeFuture.list());

                                return new JsonObject()
                                        .put("message", "Federated overview retrieved successfully")
                                        .put("instanceCount", healthyInstances.size())
                                        .put("aggregatedData", aggregatedOverview)
                                        .put("instanceDetails", createInstanceSummary(healthyInstances))
                                        .put("timestamp", Instant.now().toString());
                            });
                })
                .onSuccess(response -> {
                    try (var scope = TraceContextUtil.mdcScope(traceCtx)) {
                        ctx.response()
                                .setStatusCode(200)
                                .putHeader("content-type", "application/json")
                                .end(response.encode());
                    }
                })
                .onFailure(throwable -> {
                    try (var scope = TraceContextUtil.mdcScope(traceCtx)) {
                        logger.error("Failed to get federated overview", throwable);
                        sendError(ctx, 500, "Failed to retrieve federated overview: " + throwable.getMessage());
                    }
                });
    }
    
    /**
     * Gets federated queue data from all healthy instances.
     * GET /api/v1/federated/queues
     */
    public void getFederatedQueues(RoutingContext ctx) {
        logger.debug("Federated queues requested");
        
        // Capture trace context for async callbacks
        final TraceCtx traceCtx = getCurrentTraceCtx();
        
        serviceDiscovery.discoverInstances()
                .compose(instances -> {
                    List<PeeGeeQInstance> healthyInstances = instances.stream()
                            .filter(PeeGeeQInstance::isHealthy)
                            .collect(Collectors.toList());
                    
                    if (healthyInstances.isEmpty()) {
                        return Future.succeededFuture(new JsonObject()
                                .put("message", "No healthy instances available")
                                .put("queueCount", 0)
                                .put("queues", new JsonArray())
                                .put("timestamp", Instant.now().toString()));
                    }
                    
                    // Fetch queue data from all healthy instances
                    List<Future<JsonObject>> futures = healthyInstances.stream()
                            .map(instance -> fetchInstanceQueues(instance))
                            .collect(Collectors.toList());
                    
                    return Future.all(futures)
                            .map(compositeFuture -> {
                                JsonArray aggregatedQueues = aggregateQueuesData(
                                        healthyInstances, compositeFuture.list());

                                return new JsonObject()
                                        .put("message", "Federated queues retrieved successfully")
                                        .put("instanceCount", healthyInstances.size())
                                        .put("queueCount", aggregatedQueues.size())
                                        .put("queues", aggregatedQueues)
                                        .put("timestamp", Instant.now().toString());
                            });
                })
                .onSuccess(response -> {
                    try (var scope = TraceContextUtil.mdcScope(traceCtx)) {
                        ctx.response()
                                .setStatusCode(200)
                                .putHeader("content-type", "application/json")
                                .end(response.encode());
                    }
                })
                .onFailure(throwable -> {
                    try (var scope = TraceContextUtil.mdcScope(traceCtx)) {
                        logger.error("Failed to get federated queues", throwable);
                        sendError(ctx, 500, "Failed to retrieve federated queues: " + throwable.getMessage());
                    }
                });
    }
    
    /**
     * Gets federated consumer group data from all healthy instances.
     * GET /api/v1/federated/consumer-groups
     */
    public void getFederatedConsumerGroups(RoutingContext ctx) {
        logger.debug("Federated consumer groups requested");
        
        // Capture trace context for async callbacks
        final TraceCtx traceCtx = getCurrentTraceCtx();
        
        serviceDiscovery.discoverInstances()
                .compose(instances -> {
                    List<PeeGeeQInstance> healthyInstances = instances.stream()
                            .filter(PeeGeeQInstance::isHealthy)
                            .collect(Collectors.toList());
                    
                    if (healthyInstances.isEmpty()) {
                        return Future.succeededFuture(new JsonObject()
                                .put("message", "No healthy instances available")
                                .put("groupCount", 0)
                                .put("consumerGroups", new JsonArray())
                                .put("timestamp", Instant.now().toString()));
                    }
                    
                    // Fetch consumer group data from all healthy instances
                    List<Future<JsonObject>> futures = healthyInstances.stream()
                            .map(instance -> fetchInstanceConsumerGroups(instance))
                            .collect(Collectors.toList());
                    
                    return Future.all(futures)
                            .map(compositeFuture -> {
                                JsonArray aggregatedGroups = aggregateConsumerGroupsData(
                                        healthyInstances, compositeFuture.list());

                                return new JsonObject()
                                        .put("message", "Federated consumer groups retrieved successfully")
                                        .put("instanceCount", healthyInstances.size())
                                        .put("groupCount", aggregatedGroups.size())
                                        .put("consumerGroups", aggregatedGroups)
                                        .put("timestamp", Instant.now().toString());
                            });
                })
                .onSuccess(response -> {
                    try (var scope = TraceContextUtil.mdcScope(traceCtx)) {
                        ctx.response()
                                .setStatusCode(200)
                                .putHeader("content-type", "application/json")
                                .end(response.encode());
                    }
                })
                .onFailure(throwable -> {
                    try (var scope = TraceContextUtil.mdcScope(traceCtx)) {
                        logger.error("Failed to get federated consumer groups", throwable);
                        sendError(ctx, 500, "Failed to retrieve federated consumer groups: " + throwable.getMessage());
                    }
                });
    }
    
    /**
     * Gets federated event store data from all healthy instances.
     * GET /api/v1/federated/event-stores
     */
    public void getFederatedEventStores(RoutingContext ctx) {
        logger.debug("Federated event stores requested");
        
        // Capture trace context for async callbacks
        final TraceCtx traceCtx = getCurrentTraceCtx();
        
        serviceDiscovery.discoverInstances()
                .compose(instances -> {
                    List<PeeGeeQInstance> healthyInstances = instances.stream()
                            .filter(PeeGeeQInstance::isHealthy)
                            .collect(Collectors.toList());
                    
                    if (healthyInstances.isEmpty()) {
                        return Future.succeededFuture(new JsonObject()
                                .put("message", "No healthy instances available")
                                .put("eventStoreCount", 0)
                                .put("eventStores", new JsonArray())
                                .put("timestamp", Instant.now().toString()));
                    }
                    
                    // Fetch event store data from all healthy instances
                    List<Future<JsonObject>> futures = healthyInstances.stream()
                            .map(instance -> fetchInstanceEventStores(instance))
                            .collect(Collectors.toList());
                    
                    return Future.all(futures)
                            .map(compositeFuture -> {
                                JsonArray aggregatedEventStores = aggregateEventStoresData(
                                        healthyInstances, compositeFuture.list());

                                return new JsonObject()
                                        .put("message", "Federated event stores retrieved successfully")
                                        .put("instanceCount", healthyInstances.size())
                                        .put("eventStoreCount", aggregatedEventStores.size())
                                        .put("eventStores", aggregatedEventStores)
                                        .put("timestamp", Instant.now().toString());
                            });
                })
                .onSuccess(response -> {
                    try (var scope = TraceContextUtil.mdcScope(traceCtx)) {
                        ctx.response()
                                .setStatusCode(200)
                                .putHeader("content-type", "application/json")
                                .end(response.encode());
                    }
                })
                .onFailure(throwable -> {
                    try (var scope = TraceContextUtil.mdcScope(traceCtx)) {
                        logger.error("Failed to get federated event stores", throwable);
                        sendError(ctx, 500, "Failed to retrieve federated event stores: " + throwable.getMessage());
                    }
                });
    }
    
    /**
     * Gets federated metrics from all healthy instances.
     * GET /api/v1/federated/metrics
     */
    public void getFederatedMetrics(RoutingContext ctx) {
        logger.debug("Federated metrics requested");
        
        // Capture trace context for async callbacks
        final TraceCtx traceCtx = getCurrentTraceCtx();
        
        serviceDiscovery.discoverInstances()
                .compose(instances -> {
                    List<PeeGeeQInstance> healthyInstances = instances.stream()
                            .filter(PeeGeeQInstance::isHealthy)
                            .collect(Collectors.toList());
                    
                    if (healthyInstances.isEmpty()) {
                        return Future.succeededFuture(new JsonObject()
                                .put("message", "No healthy instances available")
                                .put("metrics", new JsonObject())
                                .put("timestamp", Instant.now().toString()));
                    }
                    
                    // Fetch metrics from all healthy instances
                    List<Future<JsonObject>> futures = healthyInstances.stream()
                            .map(instance -> fetchInstanceMetrics(instance))
                            .collect(Collectors.toList());
                    
                    return Future.all(futures)
                            .map(compositeFuture -> {
                                JsonObject aggregatedMetrics = aggregateMetricsData(
                                        healthyInstances, compositeFuture.list());

                                return new JsonObject()
                                        .put("message", "Federated metrics retrieved successfully")
                                        .put("instanceCount", healthyInstances.size())
                                        .put("metrics", aggregatedMetrics)
                                        .put("timestamp", Instant.now().toString());
                            });
                })
                .onSuccess(response -> {
                    try (var scope = TraceContextUtil.mdcScope(traceCtx)) {
                        ctx.response()
                                .setStatusCode(200)
                                .putHeader("content-type", "application/json")
                                .end(response.encode());
                    }
                })
                .onFailure(throwable -> {
                    try (var scope = TraceContextUtil.mdcScope(traceCtx)) {
                        logger.error("Failed to get federated metrics", throwable);
                        sendError(ctx, 500, "Failed to retrieve federated metrics: " + throwable.getMessage());
                    }
                });
    }

    // Instance-specific routing methods

    /**
     * Gets overview data from a specific instance.
     * GET /api/v1/instances/:instanceId/overview
     */
    public void getInstanceOverview(RoutingContext ctx) {
        String instanceId = ctx.pathParam("instanceId");
        routeToInstance(ctx, instanceId, "/api/v1/management/overview");
    }

    /**
     * Gets queue data from a specific instance.
     * GET /api/v1/instances/:instanceId/queues
     */
    public void getInstanceQueues(RoutingContext ctx) {
        String instanceId = ctx.pathParam("instanceId");
        routeToInstance(ctx, instanceId, "/api/v1/management/queues");
    }

    /**
     * Gets consumer group data from a specific instance.
     * GET /api/v1/instances/:instanceId/consumer-groups
     */
    public void getInstanceConsumerGroups(RoutingContext ctx) {
        String instanceId = ctx.pathParam("instanceId");
        routeToInstance(ctx, instanceId, "/api/v1/management/consumer-groups");
    }

    /**
     * Gets event store data from a specific instance.
     * GET /api/v1/instances/:instanceId/event-stores
     */
    public void getInstanceEventStores(RoutingContext ctx) {
        String instanceId = ctx.pathParam("instanceId");
        routeToInstance(ctx, instanceId, "/api/v1/management/event-stores");
    }

    /**
     * Gets metrics from a specific instance.
     * GET /api/v1/instances/:instanceId/metrics
     */
    public void getInstanceMetrics(RoutingContext ctx) {
        String instanceId = ctx.pathParam("instanceId");
        routeToInstance(ctx, instanceId, "/api/v1/management/metrics");
    }

    // Helper methods for fetching data from instances

    private Future<JsonObject> fetchInstanceOverview(PeeGeeQInstance instance) {
        return makeInstanceRequest(instance, "/api/v1/management/overview")
                .recover(throwable -> {
                    logger.warn("Failed to fetch overview from instance {}: {}",
                            instance.getInstanceId(), throwable.getMessage());
                    return Future.succeededFuture(createErrorResponse(instance, throwable));
                });
    }

    private Future<JsonObject> fetchInstanceQueues(PeeGeeQInstance instance) {
        return makeInstanceRequest(instance, "/api/v1/management/queues")
                .recover(throwable -> {
                    logger.warn("Failed to fetch queues from instance {}: {}",
                            instance.getInstanceId(), throwable.getMessage());
                    return Future.succeededFuture(createErrorResponse(instance, throwable));
                });
    }

    private Future<JsonObject> fetchInstanceConsumerGroups(PeeGeeQInstance instance) {
        return makeInstanceRequest(instance, "/api/v1/management/consumer-groups")
                .recover(throwable -> {
                    logger.warn("Failed to fetch consumer groups from instance {}: {}",
                            instance.getInstanceId(), throwable.getMessage());
                    return Future.succeededFuture(createErrorResponse(instance, throwable));
                });
    }

    private Future<JsonObject> fetchInstanceEventStores(PeeGeeQInstance instance) {
        return makeInstanceRequest(instance, "/api/v1/management/event-stores")
                .recover(throwable -> {
                    logger.warn("Failed to fetch event stores from instance {}: {}",
                            instance.getInstanceId(), throwable.getMessage());
                    return Future.succeededFuture(createErrorResponse(instance, throwable));
                });
    }

    private Future<JsonObject> fetchInstanceMetrics(PeeGeeQInstance instance) {
        return makeInstanceRequest(instance, "/api/v1/management/metrics")
                .recover(throwable -> {
                    logger.warn("Failed to fetch metrics from instance {}: {}",
                            instance.getInstanceId(), throwable.getMessage());
                    return Future.succeededFuture(createErrorResponse(instance, throwable));
                });
    }

    // Core helper methods

    private Future<JsonObject> makeInstanceRequest(PeeGeeQInstance instance, String path) {
        String url = instance.getBaseUrl() + path;

        return webClient.getAbs(url)
                .timeout(REQUEST_TIMEOUT)
                .send()
                .map(response -> response.bodyAsJsonObject())
                .onFailure(throwable ->
                    logger.debug("Request failed to {}: {}", url, throwable.getMessage()));
    }

    private void routeToInstance(RoutingContext ctx, String instanceId, String path) {
        if (instanceId == null || instanceId.trim().isEmpty()) {
            sendError(ctx, 400, "instanceId is required");
            return;
        }
        
        // Capture trace context for async callbacks
        final TraceCtx traceCtx = getCurrentTraceCtx();

        serviceDiscovery.getInstance(instanceId)
                .compose(instance -> {
                    if (!instance.isHealthy()) {
                        return Future.failedFuture("Instance is not healthy: " + instanceId);
                    }
                    return makeInstanceRequest(instance, path);
                })
                .onSuccess(response -> {
                    try (var scope = TraceContextUtil.mdcScope(traceCtx)) {
                        ctx.response()
                                .setStatusCode(200)
                                .putHeader("content-type", "application/json")
                                .end(response.encode());
                    }
                })
                .onFailure(throwable -> {
                    try (var scope = TraceContextUtil.mdcScope(traceCtx)) {
                        logger.error("Failed to route request to instance {}: {}", instanceId, throwable.getMessage());
                        sendError(ctx, 500, "Failed to route request to instance: " + throwable.getMessage());
                    }
                });
    }

    private JsonObject createErrorResponse(PeeGeeQInstance instance, Throwable throwable) {
        return new JsonObject()
                .put("instanceId", instance.getInstanceId())
                .put("error", true)
                .put("message", throwable.getMessage())
                .put("timestamp", Instant.now().toString());
    }

    private JsonArray createInstanceSummary(List<PeeGeeQInstance> instances) {
        JsonArray summary = new JsonArray();
        for (PeeGeeQInstance instance : instances) {
            summary.add(new JsonObject()
                    .put("instanceId", instance.getInstanceId())
                    .put("host", instance.getHost())
                    .put("port", instance.getPort())
                    .put("environment", instance.getEnvironment())
                    .put("region", instance.getRegion())
                    .put("status", instance.getStatus().toString()));
        }
        return summary;
    }

    // Data aggregation methods

    private JsonObject aggregateOverviewData(List<PeeGeeQInstance> instances, List<JsonObject> responses) {
        int totalQueues = 0;
        int totalConsumerGroups = 0;
        int totalEventStores = 0;
        int totalMessages = 0;
        double totalMessagesPerSecond = 0.0;
        int totalActiveConnections = 0;

        for (int i = 0; i < responses.size(); i++) {
            JsonObject response = responses.get(i);
            if (response.getBoolean("error", false)) {
                continue; // Skip error responses
            }

            JsonObject systemStats = response.getJsonObject("systemStats");
            if (systemStats != null) {
                totalQueues += systemStats.getInteger("totalQueues", 0);
                totalConsumerGroups += systemStats.getInteger("totalConsumerGroups", 0);
                totalEventStores += systemStats.getInteger("totalEventStores", 0);
                totalMessages += systemStats.getInteger("totalMessages", 0);
                totalMessagesPerSecond += systemStats.getDouble("messagesPerSecond", 0.0);
                totalActiveConnections += systemStats.getInteger("activeConnections", 0);
            }
        }

        return new JsonObject()
                .put("totalInstances", instances.size())
                .put("totalQueues", totalQueues)
                .put("totalConsumerGroups", totalConsumerGroups)
                .put("totalEventStores", totalEventStores)
                .put("totalMessages", totalMessages)
                .put("messagesPerSecond", totalMessagesPerSecond)
                .put("activeConnections", totalActiveConnections);
    }

    private JsonArray aggregateQueuesData(List<PeeGeeQInstance> instances, List<JsonObject> responses) {
        JsonArray aggregatedQueues = new JsonArray();

        for (int i = 0; i < responses.size(); i++) {
            JsonObject response = responses.get(i);
            PeeGeeQInstance instance = instances.get(i);

            if (response.getBoolean("error", false)) {
                continue; // Skip error responses
            }

            JsonArray queues = response.getJsonArray("queues");
            if (queues != null) {
                for (Object queueObj : queues) {
                    if (queueObj instanceof JsonObject) {
                        JsonObject queue = (JsonObject) queueObj;
                        // Add instance information to each queue
                        queue.put("sourceInstanceId", instance.getInstanceId())
                             .put("sourceHost", instance.getHost())
                             .put("sourcePort", instance.getPort())
                             .put("sourceEnvironment", instance.getEnvironment())
                             .put("sourceRegion", instance.getRegion());

                        aggregatedQueues.add(queue);
                    }
                }
            }
        }

        return aggregatedQueues;
    }

    private JsonArray aggregateConsumerGroupsData(List<PeeGeeQInstance> instances, List<JsonObject> responses) {
        JsonArray aggregatedGroups = new JsonArray();

        for (int i = 0; i < responses.size(); i++) {
            JsonObject response = responses.get(i);
            PeeGeeQInstance instance = instances.get(i);

            if (response.getBoolean("error", false)) {
                continue; // Skip error responses
            }

            JsonArray groups = response.getJsonArray("consumerGroups");
            if (groups != null) {
                for (Object groupObj : groups) {
                    if (groupObj instanceof JsonObject) {
                        JsonObject group = (JsonObject) groupObj;
                        // Add instance information to each consumer group
                        group.put("sourceInstanceId", instance.getInstanceId())
                             .put("sourceHost", instance.getHost())
                             .put("sourcePort", instance.getPort())
                             .put("sourceEnvironment", instance.getEnvironment())
                             .put("sourceRegion", instance.getRegion());

                        aggregatedGroups.add(group);
                    }
                }
            }
        }

        return aggregatedGroups;
    }

    private JsonArray aggregateEventStoresData(List<PeeGeeQInstance> instances, List<JsonObject> responses) {
        JsonArray aggregatedEventStores = new JsonArray();

        for (int i = 0; i < responses.size(); i++) {
            JsonObject response = responses.get(i);
            PeeGeeQInstance instance = instances.get(i);

            if (response.getBoolean("error", false)) {
                continue; // Skip error responses
            }

            JsonArray eventStores = response.getJsonArray("eventStores");
            if (eventStores != null) {
                for (Object storeObj : eventStores) {
                    if (storeObj instanceof JsonObject) {
                        JsonObject store = (JsonObject) storeObj;
                        // Add instance information to each event store
                        store.put("sourceInstanceId", instance.getInstanceId())
                             .put("sourceHost", instance.getHost())
                             .put("sourcePort", instance.getPort())
                             .put("sourceEnvironment", instance.getEnvironment())
                             .put("sourceRegion", instance.getRegion());

                        aggregatedEventStores.add(store);
                    }
                }
            }
        }

        return aggregatedEventStores;
    }

    private JsonObject aggregateMetricsData(List<PeeGeeQInstance> instances, List<JsonObject> responses) {
        long totalMemoryUsed = 0;
        long totalMemoryTotal = 0;
        long totalMemoryMax = 0;
        int totalCpuCores = 0;
        int totalThreadsActive = 0;
        double totalMessagesPerSecond = 0.0;
        int totalActiveConnections = 0;
        int totalMessages = 0;

        JsonArray instanceMetrics = new JsonArray();

        for (int i = 0; i < responses.size(); i++) {
            JsonObject response = responses.get(i);
            PeeGeeQInstance instance = instances.get(i);

            if (response.getBoolean("error", false)) {
                // Add error entry for this instance
                instanceMetrics.add(new JsonObject()
                        .put("instanceId", instance.getInstanceId())
                        .put("error", true)
                        .put("message", response.getString("message", "Unknown error")));
                continue;
            }

            // Extract metrics from response
            long memoryUsed = response.getLong("memoryUsed", 0L);
            long memoryTotal = response.getLong("memoryTotal", 0L);
            long memoryMax = response.getLong("memoryMax", 0L);
            int cpuCores = response.getInteger("cpuCores", 0);
            int threadsActive = response.getInteger("threadsActive", 0);
            double messagesPerSecond = response.getDouble("messagesPerSecond", 0.0);
            int activeConnections = response.getInteger("activeConnections", 0);
            int messages = response.getInteger("totalMessages", 0);

            // Aggregate totals
            totalMemoryUsed += memoryUsed;
            totalMemoryTotal += memoryTotal;
            totalMemoryMax += memoryMax;
            totalCpuCores += cpuCores;
            totalThreadsActive += threadsActive;
            totalMessagesPerSecond += messagesPerSecond;
            totalActiveConnections += activeConnections;
            totalMessages += messages;

            // Add individual instance metrics
            instanceMetrics.add(new JsonObject()
                    .put("instanceId", instance.getInstanceId())
                    .put("host", instance.getHost())
                    .put("port", instance.getPort())
                    .put("memoryUsed", memoryUsed)
                    .put("memoryTotal", memoryTotal)
                    .put("memoryMax", memoryMax)
                    .put("cpuCores", cpuCores)
                    .put("threadsActive", threadsActive)
                    .put("messagesPerSecond", messagesPerSecond)
                    .put("activeConnections", activeConnections)
                    .put("totalMessages", messages));
        }

        return new JsonObject()
                .put("aggregated", new JsonObject()
                        .put("totalMemoryUsed", totalMemoryUsed)
                        .put("totalMemoryTotal", totalMemoryTotal)
                        .put("totalMemoryMax", totalMemoryMax)
                        .put("totalCpuCores", totalCpuCores)
                        .put("totalThreadsActive", totalThreadsActive)
                        .put("totalMessagesPerSecond", totalMessagesPerSecond)
                        .put("totalActiveConnections", totalActiveConnections)
                        .put("totalMessages", totalMessages)
                        .put("averageMemoryUsage", totalMemoryTotal > 0 ?
                                (double) totalMemoryUsed / totalMemoryTotal * 100 : 0.0))
                .put("instances", instanceMetrics);
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
    
    /**
     * Gets the current TraceCtx from Vert.x Context, or null if not present.
     */
    private TraceCtx getCurrentTraceCtx() {
        io.vertx.core.Context ctx = Vertx.currentContext();
        if (ctx == null) {
            return null;
        }
        Object traceObj = ctx.get(TraceContextUtil.CONTEXT_TRACE_KEY);
        return (traceObj instanceof TraceCtx) ? (TraceCtx) traceObj : null;
    }
}
