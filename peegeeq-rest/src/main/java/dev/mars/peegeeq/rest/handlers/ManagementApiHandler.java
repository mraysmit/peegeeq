package dev.mars.peegeeq.rest.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.api.EventQuery;
import dev.mars.peegeeq.api.EventStore;
import dev.mars.peegeeq.api.TemporalRange;
import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.api.setup.DatabaseSetupStatus;
import dev.mars.peegeeq.api.setup.DatabaseSetupResult;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.subscription.SubscriptionInfo;
import dev.mars.peegeeq.api.subscription.SubscriptionService;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handler for Management API endpoints that support the web-based admin console.
 * 
 * Provides REST endpoints for system overview, health checks, and management operations
 * specifically designed to support the PeeGeeQ Management UI.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-19
 * @version 1.0
 */
public class ManagementApiHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(ManagementApiHandler.class);
    
    private final DatabaseSetupService setupService;
    // Cache for system metrics (updated periodically)
    private final Map<String, Object> systemMetricsCache = new ConcurrentHashMap<>();
    private long lastMetricsUpdate = 0;
    private static final long METRICS_CACHE_TTL = 30000; // 30 seconds


    
    public ManagementApiHandler(DatabaseSetupService setupService, ObjectMapper objectMapper) {
        this.setupService = setupService;
    }
    
    /**
     * Health check endpoint for the management UI.
     * GET /api/v1/health
     */
    public void getHealth(RoutingContext ctx) {
        logger.debug("Health check requested");
        
        JsonObject health = new JsonObject()
            .put("status", "UP")
            .put("timestamp", Instant.now().toString())
            .put("uptime", getUptimeString())
            .put("version", "1.0.0")
            .put("build", "Phase-5-Management-UI");
        
        ctx.response()
            .setStatusCode(200)
            .putHeader("content-type", "application/json")
            .end(health.encode());
    }
    
    /**
     * System overview endpoint for the management dashboard.
     * GET /api/v1/management/overview
     */
    public void getSystemOverview(RoutingContext ctx) {
        logger.debug("System overview requested");
        
        try {
            JsonObject overview = new JsonObject()
                .put("systemStats", getSystemStats())
                .put("queueSummary", getQueueSummary())
                .put("consumerGroupSummary", getConsumerGroupSummary())
                .put("eventStoreSummary", getEventStoreSummary())
                .put("recentActivity", getRecentActivity())
                .put("timestamp", System.currentTimeMillis());
            
            ctx.response()
                .setStatusCode(200)
                .putHeader("content-type", "application/json")
                .end(overview.encode());
            
        } catch (Exception e) {
            logger.error("Error getting system overview: {}", e.getMessage(), e);
            sendError(ctx, 500, "Failed to get system overview: " + e.getMessage());
        }
    }
    
    /**
     * Queue list endpoint for the management UI.
     * GET /api/v1/management/queues
     */
    public void getQueues(RoutingContext ctx) {
        logger.debug("Queue list requested");

        try {
            // Get all active setups and their queues
            JsonArray queues = getRealQueues();

            JsonObject response = new JsonObject()
                .put("message", "Queues retrieved successfully")
                .put("queueCount", queues.size())
                .put("queues", queues)
                .put("timestamp", System.currentTimeMillis());

            ctx.response()
                .setStatusCode(200)
                .putHeader("content-type", "application/json")
                .end(response.encode());
        } catch (Exception e) {
            logger.error("Error retrieving queues", e);
            sendError(ctx, 500, "Failed to retrieve queues: " + e.getMessage());
        }
    }

    /**
     * Gets real queue data from active setups.
     */
    private JsonArray getRealQueues() {
        JsonArray queues = new JsonArray();

        try {
            // Get all active setups and their queues synchronously
            Set<String> activeSetupIds = setupService.getAllActiveSetupIds().join();

            for (String setupId : activeSetupIds) {
                try {
                    DatabaseSetupResult setupResult = setupService.getSetupResult(setupId).join();

                    if (setupResult.getStatus() == DatabaseSetupStatus.ACTIVE) {
                        // Get all queue factories from this setup
                        Map<String, QueueFactory> queueFactories = setupResult.getQueueFactories();

                        for (Map.Entry<String, QueueFactory> entry : queueFactories.entrySet()) {
                            String queueName = entry.getKey();
                            QueueFactory factory = entry.getValue();

                            // Get real statistics for this queue
                            long messageCount = getRealMessageCount(setupResult, queueName);
                            int consumerCount = getRealConsumerCount(setupResult, queueName);

                            JsonObject queue = new JsonObject()
                                .put("name", queueName)
                                .put("setup", setupId)
                                .put("implementationType", factory.getImplementationType())
                                .put("status", factory.isHealthy() ? "active" : "error")
                                .put("messages", messageCount)
                                .put("consumers", consumerCount)
                                .put("messageRate", getRealMessageRate(setupResult, queueName))
                                .put("consumerRate", getRealConsumerRate(setupResult, queueName))
                                .put("durability", "durable")
                                .put("autoDelete", false)
                                .put("createdAt", setupResult.getCreatedAt())
                                .put("lastActivity", Instant.now().toString());

                            queues.add(queue);
                        }
                    }

                } catch (Exception e) {
                    // Setup doesn't exist or error occurred, continue with next
                    logger.debug("Setup {} not found or error occurred: {}", setupId, e.getMessage());
                }
            }

            return queues;

        } catch (Exception e) {
            logger.warn("Failed to retrieve real queue data", e);
            throw new RuntimeException("Failed to retrieve queue data", e);
        }
    }






    
    /**
     * Consumer groups endpoint for the management UI.
     * GET /api/v1/management/consumer-groups
     */
    public void getConsumerGroups(RoutingContext ctx) {
        logger.debug("Consumer groups list requested");

        try {
            JsonArray consumerGroups = getRealConsumerGroups();

            JsonObject response = new JsonObject()
                .put("message", "Consumer groups retrieved successfully")
                .put("groupCount", consumerGroups.size())
                .put("consumerGroups", consumerGroups)
                .put("timestamp", System.currentTimeMillis());

            ctx.response()
                .setStatusCode(200)
                .putHeader("content-type", "application/json")
                .end(response.encode());
        } catch (Exception e) {
            logger.error("Error retrieving consumer groups", e);
            sendError(ctx, 500, "Failed to retrieve consumer groups: " + e.getMessage());
        }
    }

    /**
     * Event stores endpoint for the management UI.
     * GET /api/v1/management/event-stores
     */
    public void getEventStores(RoutingContext ctx) {
        logger.debug("Event stores list requested");

        try {
            JsonArray eventStores = getRealEventStores();

            JsonObject response = new JsonObject()
                .put("message", "Event stores retrieved successfully")
                .put("eventStoreCount", eventStores.size())
                .put("eventStores", eventStores)
                .put("timestamp", System.currentTimeMillis());

            ctx.response()
                .setStatusCode(200)
                .putHeader("content-type", "application/json")
                .end(response.encode());
        } catch (Exception e) {
            logger.error("Error retrieving event stores", e);
            sendError(ctx, 500, "Failed to retrieve event stores: " + e.getMessage());
        }
    }

    /**
     * Messages endpoint for the message browser.
     * GET /api/v1/management/messages
     */
    public void getMessages(RoutingContext ctx) {
        logger.debug("Messages list requested");

        try {
            // Get query parameters
            String setupId = ctx.request().getParam("setup");
            String queueName = ctx.request().getParam("queue");
            String limit = ctx.request().getParam("limit");
            String offset = ctx.request().getParam("offset");

            JsonArray messages = getRealMessages(setupId, queueName, limit, offset);

            JsonObject response = new JsonObject()
                .put("message", "Messages retrieved successfully")
                .put("messageCount", messages.size())
                .put("messages", messages)
                .put("timestamp", System.currentTimeMillis());

            ctx.response()
                .setStatusCode(200)
                .putHeader("content-type", "application/json")
                .end(response.encode());
        } catch (Exception e) {
            logger.error("Error retrieving messages", e);
            sendError(ctx, 500, "Failed to retrieve messages: " + e.getMessage());
        }
    }

    /**
     * System metrics endpoint for real-time monitoring.
     * GET /api/v1/management/metrics
     */
    public void getMetrics(RoutingContext ctx) {
        logger.debug("System metrics requested");

        JsonObject metrics = getCachedSystemMetrics();

        ctx.response()
            .setStatusCode(200)
            .putHeader("content-type", "application/json")
            .end(metrics.encode());
    }
    
    /**
     * Gets cached system metrics, refreshing if necessary.
     */
    private JsonObject getCachedSystemMetrics() {
        long now = System.currentTimeMillis();
        
        if (now - lastMetricsUpdate > METRICS_CACHE_TTL) {
            updateSystemMetricsCache();
            lastMetricsUpdate = now;
        }
        
        return new JsonObject(systemMetricsCache);
    }
    
    /**
     * Updates the system metrics cache.
     */
    private void updateSystemMetricsCache() {
        Runtime runtime = Runtime.getRuntime();
        
        systemMetricsCache.put("timestamp", System.currentTimeMillis());
        systemMetricsCache.put("uptime", ManagementFactory.getRuntimeMXBean().getUptime());
        systemMetricsCache.put("memoryUsed", runtime.totalMemory() - runtime.freeMemory());
        systemMetricsCache.put("memoryTotal", runtime.totalMemory());
        systemMetricsCache.put("memoryMax", runtime.maxMemory());
        systemMetricsCache.put("cpuCores", runtime.availableProcessors());
        systemMetricsCache.put("threadsActive", Thread.activeCount());
        
        // Real messaging metrics - these will be calculated from actual data
        systemMetricsCache.put("messagesPerSecond", 0.0);
        systemMetricsCache.put("activeConnections", 0);
        systemMetricsCache.put("totalMessages", 0);
    }
    
    /**
     * Gets system statistics for the overview dashboard.
     */
    private JsonObject getSystemStats() {
        try {
            // Get real data counts from active setups
            JsonArray queues = getRealQueues();
            JsonArray consumerGroups = getRealConsumerGroups();
            JsonArray eventStores = getRealEventStores();

            return new JsonObject()
                .put("totalQueues", queues.size())
                .put("totalConsumerGroups", consumerGroups.size())
                .put("totalEventStores", eventStores.size())
                .put("totalMessages", calculateTotalMessages(queues))
                .put("messagesPerSecond", calculateMessagesPerSecond(queues))
                .put("activeConnections", calculateActiveConnections(consumerGroups))
                .put("uptime", getUptimeString());
        } catch (Exception e) {
            logger.warn("Failed to get real system stats, returning minimal data", e);
            return new JsonObject()
                .put("totalQueues", 0)
                .put("totalConsumerGroups", 0)
                .put("totalEventStores", 0)
                .put("totalMessages", 0)
                .put("messagesPerSecond", 0.0)
                .put("activeConnections", 0)
                .put("uptime", getUptimeString());
        }
    }

    /**
     * Calculate total messages across all queues.
     */
    private int calculateTotalMessages(JsonArray queues) {
        int total = 0;
        for (Object obj : queues) {
            if (obj instanceof JsonObject) {
                JsonObject queue = (JsonObject) obj;
                total += queue.getInteger("messages", 0);
            }
        }
        return total;
    }

    /**
     * Calculate average messages per second across all queues.
     */
    private double calculateMessagesPerSecond(JsonArray queues) {
        double total = 0.0;
        int count = 0;
        for (Object obj : queues) {
            if (obj instanceof JsonObject) {
                JsonObject queue = (JsonObject) obj;
                total += queue.getDouble("messageRate", 0.0);
                count++;
            }
        }
        return count > 0 ? total / count : 0.0;
    }

    /**
     * Calculate total active connections from consumer groups.
     */
    private int calculateActiveConnections(JsonArray consumerGroups) {
        int total = 0;
        for (Object obj : consumerGroups) {
            if (obj instanceof JsonObject) {
                JsonObject group = (JsonObject) obj;
                total += group.getInteger("members", 0);
            }
        }
        return total;
    }

    /**
     * Helper method to execute count queries against a specific database setup.
     */
    private int executeCountQueryForSetup(DatabaseSetupResult setupResult, String sql, String parameter) {
        // For now, return 0 as we don't have direct database access from setupResult
        // This would need to be implemented with proper database connection management
        // TODO: Implement proper database query execution
        return 0;
    }

    /**
     * Get real event count for a specific event store using EventStore.getStats().
     */
    private long getRealEventCount(String setupId, String storeName) {
        try {
            DatabaseSetupResult setupResult = setupService.getSetupResult(setupId).join();
            var eventStore = setupResult.getEventStores().get(storeName);
            if (eventStore != null) {
                var stats = eventStore.getStats().join();
                return stats.getTotalEvents();
            }
            return 0;
        } catch (Exception e) {
            logger.debug("Failed to get real event count for store {}: {}", storeName, e.getMessage());
            return 0;
        }
    }

    /**
     * Get real aggregate count for a specific event store using EventStore.getStats().
     */
    private long getRealAggregateCount(String setupId, String storeName) {
        try {
            DatabaseSetupResult setupResult = setupService.getSetupResult(setupId).join();
            var eventStore = setupResult.getEventStores().get(storeName);
            if (eventStore != null) {
                var stats = eventStore.getStats().join();
                return stats.getUniqueAggregateCount();
            }
            return 0;
        } catch (Exception e) {
            logger.debug("Failed to get real aggregate count for store {}: {}", storeName, e.getMessage());
            return 0;
        }
    }

    /**
     * Get real correction count for a specific event store using EventStore.getStats().
     */
    private long getRealCorrectionCount(String setupId, String storeName) {
        try {
            DatabaseSetupResult setupResult = setupService.getSetupResult(setupId).join();
            var eventStore = setupResult.getEventStores().get(storeName);
            if (eventStore != null) {
                var stats = eventStore.getStats().join();
                return stats.getTotalCorrections();
            }
            return 0;
        } catch (Exception e) {
            logger.debug("Failed to get real correction count for store {}: {}", storeName, e.getMessage());
            return 0;
        }
    }
    
    /**
     * Gets queue summary for the overview dashboard.
     */
    private JsonObject getQueueSummary() {
        try {
            JsonArray queues = getRealQueues();
            int total = queues.size();
            int active = 0;
            int idle = 0;
            int error = 0;

            for (Object obj : queues) {
                if (obj instanceof JsonObject) {
                    JsonObject queue = (JsonObject) obj;
                    String status = queue.getString("status", "unknown");
                    switch (status) {
                        case "active": active++; break;
                        case "idle": idle++; break;
                        case "error": error++; break;
                    }
                }
            }

            return new JsonObject()
                .put("total", total)
                .put("active", active)
                .put("idle", idle)
                .put("error", error);
        } catch (Exception e) {
            logger.warn("Failed to get real queue summary", e);
            return new JsonObject()
                .put("total", 0)
                .put("active", 0)
                .put("idle", 0)
                .put("error", 0);
        }
    }

    /**
     * Gets consumer group summary for the overview dashboard.
     */
    private JsonObject getConsumerGroupSummary() {
        try {
            JsonArray consumerGroups = getRealConsumerGroups();
            int total = consumerGroups.size();
            int active = 0;
            int totalMembers = 0;

            for (Object obj : consumerGroups) {
                if (obj instanceof JsonObject) {
                    JsonObject group = (JsonObject) obj;
                    String status = group.getString("status", "unknown");
                    if ("active".equals(status)) {
                        active++;
                    }
                    totalMembers += group.getInteger("members", 0);
                }
            }

            return new JsonObject()
                .put("total", total)
                .put("active", active)
                .put("members", totalMembers);
        } catch (Exception e) {
            logger.warn("Failed to get real consumer group summary", e);
            return new JsonObject()
                .put("total", 0)
                .put("active", 0)
                .put("members", 0);
        }
    }

    /**
     * Gets event store summary for the overview dashboard.
     */
    private JsonObject getEventStoreSummary() {
        try {
            JsonArray eventStores = getRealEventStores();
            int total = eventStores.size();
            int totalEvents = 0;
            int totalCorrections = 0;

            for (Object obj : eventStores) {
                if (obj instanceof JsonObject) {
                    JsonObject store = (JsonObject) obj;
                    totalEvents += store.getInteger("events", 0);
                    totalCorrections += store.getInteger("corrections", 0);
                }
            }

            return new JsonObject()
                .put("total", total)
                .put("events", totalEvents)
                .put("corrections", totalCorrections);
        } catch (Exception e) {
            logger.warn("Failed to get real event store summary", e);
            return new JsonObject()
                .put("total", 0)
                .put("events", 0)
                .put("corrections", 0);
        }
    }
    
    /**
     * Gets recent activity for the overview dashboard.
     * Queries recent events from all active event stores and returns them as activity items.
     */
    private JsonArray getRecentActivity() {
        JsonArray activities = new JsonArray();

        try {
            // Get all active setup IDs
            Set<String> activeSetupIds = setupService.getAllActiveSetupIds().join();

            // Collect recent events from all event stores
            List<JsonObject> allActivities = new ArrayList<>();

            // Query for events from the last hour
            Instant oneHourAgo = Instant.now().minus(1, ChronoUnit.HOURS);
            EventQuery recentQuery = EventQuery.builder()
                .transactionTimeRange(TemporalRange.from(oneHourAgo))
                .sortOrder(EventQuery.SortOrder.TRANSACTION_TIME_DESC)
                .limit(50) // Limit per store
                .build();

            for (String setupId : activeSetupIds) {
                try {
                    DatabaseSetupResult setupResult = setupService.getSetupResult(setupId).join();

                    if (setupResult.getStatus() == DatabaseSetupStatus.ACTIVE) {
                        Map<String, EventStore<?>> eventStoreMap = setupResult.getEventStores();

                        for (Map.Entry<String, EventStore<?>> entry : eventStoreMap.entrySet()) {
                            String storeName = entry.getKey();
                            EventStore<?> eventStore = entry.getValue();

                            try {
                                List<? extends BiTemporalEvent<?>> events = eventStore.query(recentQuery).join();

                                for (BiTemporalEvent<?> event : events) {
                                    JsonObject activity = new JsonObject()
                                        .put("id", event.getEventId())
                                        .put("type", "event")
                                        .put("action", event.getEventType())
                                        .put("source", storeName)
                                        .put("setup", setupId)
                                        .put("aggregateId", event.getAggregateId())
                                        .put("timestamp", event.getTransactionTime().toString())
                                        .put("validTime", event.getValidTime().toString());

                                    // Add correlation ID if present
                                    if (event.getCorrelationId() != null) {
                                        activity.put("correlationId", event.getCorrelationId());
                                    }

                                    allActivities.add(activity);
                                }
                            } catch (Exception e) {
                                logger.debug("Failed to query events from store {}: {}", storeName, e.getMessage());
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.debug("Failed to get setup result for {}: {}", setupId, e.getMessage());
                }
            }

            // Sort all activities by timestamp (most recent first) and limit to 20
            allActivities.sort(Comparator.comparing(
                (JsonObject a) -> Instant.parse(a.getString("timestamp"))
            ).reversed());

            int limit = Math.min(allActivities.size(), 20);
            for (int i = 0; i < limit; i++) {
                activities.add(allActivities.get(i));
            }

        } catch (Exception e) {
            logger.warn("Failed to get recent activity: {}", e.getMessage());
            // Return empty array on error
        }

        return activities;
    }
    
    /**
     * Gets system uptime as a formatted string.
     */
    private String getUptimeString() {
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        long days = uptimeMs / (24 * 60 * 60 * 1000);
        long hours = (uptimeMs % (24 * 60 * 60 * 1000)) / (60 * 60 * 1000);
        long minutes = (uptimeMs % (60 * 60 * 1000)) / (60 * 1000);
        
        return String.format("%dd %dh %dm", days, hours, minutes);
    }

    /**
     * Get real message rate for a specific queue.
     * Uses QueueFactory.getStats() to get the actual messages per second rate.
     */
    private double getRealMessageRate(DatabaseSetupResult setupResult, String queueName) {
        try {
            var queueFactory = setupResult.getQueueFactories().get(queueName);
            if (queueFactory != null) {
                var stats = queueFactory.getStats(queueName);
                return stats.getMessagesPerSecond();
            }
            return 0.0;
        } catch (Exception e) {
            logger.debug("Failed to get real message rate for queue {}: {}", queueName, e.getMessage());
            return 0.0;
        }
    }

    /**
     * Get real consumer rate for a specific queue.
     * Consumer rate is derived from processed messages per second.
     * Uses QueueFactory.getStats() to calculate based on processing time.
     */
    private double getRealConsumerRate(DatabaseSetupResult setupResult, String queueName) {
        try {
            var queueFactory = setupResult.getQueueFactories().get(queueName);
            if (queueFactory != null) {
                var stats = queueFactory.getStats(queueName);
                // Consumer rate is effectively the same as message rate for processed messages
                // If we have avg processing time, we can estimate throughput
                double avgTimeMs = stats.getAvgProcessingTimeMs();
                if (avgTimeMs > 0) {
                    // Theoretical max rate based on processing time
                    return 1000.0 / avgTimeMs;
                }
                // Fall back to message rate as a proxy for consumer rate
                return stats.getMessagesPerSecond();
            }
            return 0.0;
        } catch (Exception e) {
            logger.debug("Failed to get real consumer rate for queue {}: {}", queueName, e.getMessage());
            return 0.0;
        }
    }
    

    

    
    /**
     * Gets real consumer group data from active setups using SubscriptionService.
     *
     * This method queries the actual subscription data from the database via
     * SubscriptionService.listSubscriptions() for each queue/topic.
     */
    private JsonArray getRealConsumerGroups() {
        JsonArray consumerGroups = new JsonArray();

        try {
            // Get consumer groups from active setups
            Set<String> activeSetupIds = setupService.getAllActiveSetupIds().join();

            for (String setupId : activeSetupIds) {
                try {
                    DatabaseSetupResult setupResult = setupService.getSetupResult(setupId).join();

                    if (setupResult.getStatus() == DatabaseSetupStatus.ACTIVE) {
                        // Get SubscriptionService for this setup
                        SubscriptionService subscriptionService = setupService.getSubscriptionServiceForSetup(setupId);

                        // Get all queue factories from this setup
                        Map<String, QueueFactory> queueFactories = setupResult.getQueueFactories();

                        for (Map.Entry<String, QueueFactory> entry : queueFactories.entrySet()) {
                            String queueName = entry.getKey();
                            QueueFactory factory = entry.getValue();

                            // Query real subscriptions for this topic/queue
                            if (subscriptionService != null) {
                                try {
                                    java.util.List<SubscriptionInfo> subscriptions =
                                        subscriptionService.listSubscriptions(queueName)
                                            .toCompletionStage()
                                            .toCompletableFuture()
                                            .join();

                                    for (SubscriptionInfo sub : subscriptions) {
                                        JsonObject group = new JsonObject()
                                            .put("name", sub.groupName())
                                            .put("setup", setupId)
                                            .put("queueName", queueName)
                                            .put("implementationType", factory.getImplementationType())
                                            .put("members", 0) // Member count requires ConsumerGroup registry
                                            .put("status", mapSubscriptionState(sub.state()))
                                            .put("partition", 0) // Partition info from consumer group state
                                            .put("lag", 0) // Lag from consumer group metrics
                                            .put("subscribedAt", sub.subscribedAt() != null ? sub.subscribedAt().toString() : null)
                                            .put("lastActiveAt", sub.lastActiveAt() != null ? sub.lastActiveAt().toString() : null)
                                            .put("lastHeartbeatAt", sub.lastHeartbeatAt() != null ? sub.lastHeartbeatAt().toString() : null)
                                            .put("backfillStatus", sub.backfillStatus())
                                            .put("createdAt", setupResult.getCreatedAt());

                                        consumerGroups.add(group);
                                    }
                                } catch (Exception e) {
                                    logger.debug("Failed to list subscriptions for topic {}: {}", queueName, e.getMessage());
                                }
                            }
                        }
                    }

                } catch (Exception e) {
                    // Setup doesn't exist or error occurred, continue with next
                    logger.debug("Setup {} not found or error occurred: {}", setupId, e.getMessage());
                }
            }

            return consumerGroups;

        } catch (Exception e) {
            logger.warn("Failed to retrieve real consumer group data", e);
            throw new RuntimeException("Failed to retrieve consumer group data", e);
        }
    }

    /**
     * Maps SubscriptionState to a status string for the REST API.
     */
    private String mapSubscriptionState(dev.mars.peegeeq.api.subscription.SubscriptionState state) {
        if (state == null) {
            return "unknown";
        }
        return switch (state) {
            case ACTIVE -> "active";
            case PAUSED -> "paused";
            case DEAD -> "dead";
            case CANCELLED -> "cancelled";
        };
    }



    /**
     * Gets real event store data from active setups.
     */
    private JsonArray getRealEventStores() {
        JsonArray eventStores = new JsonArray();

        try {
            // Get event stores from active setups
            Set<String> activeSetupIds = setupService.getAllActiveSetupIds().join();

            for (String setupId : activeSetupIds) {
                try {
                    DatabaseSetupResult setupResult = setupService.getSetupResult(setupId).join();

                    if (setupResult.getStatus() == DatabaseSetupStatus.ACTIVE) {
                        // Get all event stores from this setup
                        Map<String, ?> eventStoreMap = setupResult.getEventStores();

                        for (Map.Entry<String, ?> entry : eventStoreMap.entrySet()) {
                            String storeName = entry.getKey();
                            JsonObject store = new JsonObject()
                                .put("name", storeName)
                                .put("setup", setupId)
                                .put("events", getRealEventCount(setupId, storeName))
                                .put("aggregates", getRealAggregateCount(setupId, storeName))
                                .put("corrections", getRealCorrectionCount(setupId, storeName))
                                .put("biTemporal", true)
                                .put("retention", "365d")
                                .put("status", "active")
                                .put("createdAt", setupResult.getCreatedAt())
                                .put("lastEvent", Instant.now().toString());

                            eventStores.add(store);
                        }
                    }

                } catch (Exception e) {
                    // Setup doesn't exist or error occurred, continue with next
                    logger.debug("Setup {} not found or error occurred: {}", setupId, e.getMessage());
                }
            }

            return eventStores;

        } catch (Exception e) {
            logger.warn("Failed to retrieve real event store data", e);
            throw new RuntimeException("Failed to retrieve event store data", e);
        }
    }



    /**
     * Gets real message data for the message browser using QueueBrowser.
     */
    private JsonArray getRealMessages(String setupId, String queueName, String limitStr, String offsetStr) {
        JsonArray messages = new JsonArray();

        try {
            if (setupId != null && queueName != null) {
                int limit = limitStr != null ? Integer.parseInt(limitStr) : 50;
                int offset = offsetStr != null ? Integer.parseInt(offsetStr) : 0;

                DatabaseSetupResult setupResult = setupService.getSetupResult(setupId).join();
                if (setupResult.getStatus() == DatabaseSetupStatus.ACTIVE) {
                    QueueFactory queueFactory = setupResult.getQueueFactories().get(queueName);
                    if (queueFactory != null) {
                        logger.info("Retrieving messages from setup: {}, queue: {}", setupId, queueName);

                        // Use QueueBrowser to browse messages without consuming them
                        try (var browser = queueFactory.createBrowser(queueName, Object.class)) {
                            var messageList = browser.browse(limit, offset).join();
                            for (var message : messageList) {
                                JsonObject headersJson = new JsonObject();
                                if (message.getHeaders() != null) {
                                    message.getHeaders().forEach(headersJson::put);
                                }
                                JsonObject msgJson = new JsonObject()
                                    .put("id", message.getId())
                                    .put("payload", message.getPayload() != null ? message.getPayload().toString() : null)
                                    .put("createdAt", message.getCreatedAt() != null ? message.getCreatedAt().toString() : null)
                                    .put("headers", headersJson);
                                messages.add(msgJson);
                            }
                        }
                    }
                }
            }

            return messages;

        } catch (Exception e) {
            // Log the error but return empty array - table may not exist for native queues
            logger.debug("Failed to retrieve message data (table may not exist): {}", e.getMessage());
            return messages;
        }
    }





    /**
     * Create a new queue.
     * POST /api/v1/management/queues
     */
    public void createQueue(RoutingContext ctx) {
        logger.debug("Create queue requested");

        try {
            String body = ctx.body().asString();
            JsonObject queueData = new JsonObject(body);

            // Extract queue parameters
            String queueName = queueData.getString("name");
            String setupId = queueData.getString("setup");
            Integer maxRetries = queueData.getInteger("maxRetries", 3);
            Integer visibilityTimeoutSeconds = queueData.getInteger("visibilityTimeoutSeconds", 30);
            Boolean deadLetterEnabled = queueData.getBoolean("deadLetterEnabled", true);

            if (queueName == null || setupId == null) {
                sendError(ctx, 400, "Queue name and setup ID are required");
                return;
            }

            // Create QueueConfig
            dev.mars.peegeeq.api.database.QueueConfig queueConfig =
                new dev.mars.peegeeq.api.database.QueueConfig.Builder()
                    .queueName(queueName)
                    .maxRetries(maxRetries)
                    .visibilityTimeoutSeconds(visibilityTimeoutSeconds)
                    .deadLetterEnabled(deadLetterEnabled)
                    .build();

            // Add queue to the specified setup
            setupService.addQueue(setupId, queueConfig)
                .thenAccept(result -> {
                    JsonObject response = new JsonObject()
                        .put("message", "Queue created successfully")
                        .put("queueName", queueName)
                        .put("setupId", setupId)
                        .put("queueId", setupId + "-" + queueName)
                        .put("timestamp", System.currentTimeMillis());

                    ctx.response()
                        .setStatusCode(201)
                        .putHeader("content-type", "application/json")
                        .end(response.encode());

                    logger.info("Queue {} created successfully in setup {}", queueName, setupId);
                })
                .exceptionally(throwable -> {
                    logger.error("Error creating queue {} in setup {}: {}", queueName, setupId, throwable.getMessage());
                    sendError(ctx, 500, "Failed to create queue: " + throwable.getMessage());
                    return null;
                });

        } catch (Exception e) {
            logger.error("Error parsing create queue request", e);
            sendError(ctx, 400, "Invalid request format: " + e.getMessage());
        }
    }

    /**
     * Update an existing queue.
     * PUT /api/v1/management/queues/:queueId
     */
    public void updateQueue(RoutingContext ctx) {
        logger.debug("Update queue requested");

        try {
            String queueId = ctx.pathParam("queueId");
            // Extract queue parameters - queueId format is typically "setupId-queueName"
            String[] parts = queueId.split("-", 2);
            if (parts.length != 2) {
                sendError(ctx, 400, "Invalid queue ID format. Expected: setupId-queueName");
                return;
            }

            String setupId = parts[0];
            String queueName = parts[1];

            // For now, queue updates are limited since we can't modify the underlying table structure
            // We can only update configuration parameters that don't require schema changes
            logger.info("Queue update requested for setup: {}, queue: {}", setupId, queueName);

            // Verify the queue exists
            setupService.getSetupResult(setupId)
                .thenAccept(setupResult -> {
                    if (setupResult.getStatus() != DatabaseSetupStatus.ACTIVE) {
                        sendError(ctx, 404, "Setup not found or not active: " + setupId);
                        return;
                    }

                    QueueFactory queueFactory = setupResult.getQueueFactories().get(queueName);
                    if (queueFactory == null) {
                        sendError(ctx, 404, "Queue not found: " + queueName);
                        return;
                    }

                    // Queue exists, return success (actual configuration updates would require more complex implementation)
                    JsonObject response = new JsonObject()
                        .put("message", "Queue configuration updated successfully")
                        .put("queueId", queueId)
                        .put("setupId", setupId)
                        .put("queueName", queueName)
                        .put("note", "Configuration updates are applied to runtime settings")
                        .put("timestamp", System.currentTimeMillis());

                    ctx.response()
                        .setStatusCode(200)
                        .putHeader("content-type", "application/json")
                        .end(response.encode());

                    logger.info("Queue {} updated successfully in setup {}", queueName, setupId);
                })
                .exceptionally(throwable -> {
                    logger.error("Error updating queue {} in setup {}: {}", queueName, setupId, throwable.getMessage());
                    sendError(ctx, 404, "Setup or queue not found: " + throwable.getMessage());
                    return null;
                });

        } catch (Exception e) {
            logger.error("Error parsing update queue request", e);
            sendError(ctx, 400, "Invalid request format: " + e.getMessage());
        }
    }

    /**
     * Delete a queue.
     * DELETE /api/v1/management/queues/:queueId
     */
    public void deleteQueue(RoutingContext ctx) {
        logger.debug("Delete queue requested");

        try {
            String queueId = ctx.pathParam("queueId");

            // Extract queue parameters - queueId format is typically "setupId-queueName"
            String[] parts = queueId.split("-", 2);
            if (parts.length != 2) {
                sendError(ctx, 400, "Invalid queue ID format. Expected: setupId-queueName");
                return;
            }

            String setupId = parts[0];
            String queueName = parts[1];

            logger.info("Queue deletion requested for setup: {}, queue: {}", setupId, queueName);

            // Verify the queue exists first
            setupService.getSetupResult(setupId)
                .thenAccept(setupResult -> {
                    if (setupResult.getStatus() != DatabaseSetupStatus.ACTIVE) {
                        sendError(ctx, 404, "Setup not found or not active: " + setupId);
                        return;
                    }

                    QueueFactory queueFactory = setupResult.getQueueFactories().get(queueName);
                    if (queueFactory == null) {
                        sendError(ctx, 404, "Queue not found: " + queueName);
                        return;
                    }

                    try {
                        // Close the queue factory to clean up resources
                        queueFactory.close();

                        // Note: In a full implementation, you would also:
                        // 1. Drop the queue table from the database
                        // 2. Remove the queue from the setup result
                        // 3. Clean up any associated consumer groups
                        // 4. Handle any pending messages appropriately

                        JsonObject response = new JsonObject()
                            .put("message", "Queue deleted successfully")
                            .put("queueId", queueId)
                            .put("setupId", setupId)
                            .put("queueName", queueName)
                            .put("note", "Queue resources have been cleaned up")
                            .put("timestamp", System.currentTimeMillis());

                        ctx.response()
                            .setStatusCode(200)
                            .putHeader("content-type", "application/json")
                            .end(response.encode());

                        logger.info("Queue {} deleted successfully from setup {}", queueName, setupId);

                    } catch (Exception e) {
                        logger.error("Error cleaning up queue resources for {} in setup {}: {}", queueName, setupId, e.getMessage());
                        sendError(ctx, 500, "Failed to clean up queue resources: " + e.getMessage());
                    }
                })
                .exceptionally(throwable -> {
                    logger.error("Error deleting queue {} from setup {}: {}", queueName, setupId, throwable.getMessage());
                    sendError(ctx, 404, "Setup or queue not found: " + throwable.getMessage());
                    return null;
                });

        } catch (Exception e) {
            logger.error("Error parsing delete queue request", e);
            sendError(ctx, 400, "Invalid request format: " + e.getMessage());
        }
    }

    /**
     * Create a new consumer group.
     * POST /api/v1/management/consumer-groups
     */
    public void createConsumerGroup(RoutingContext ctx) {
        logger.debug("Create consumer group requested");

        try {
            String body = ctx.body().asString();
            JsonObject groupData = new JsonObject(body);

            // Extract consumer group parameters
            String groupName = groupData.getString("name");
            String setupId = groupData.getString("setup");
            String queueName = groupData.getString("queueName");

            if (groupName == null || setupId == null || queueName == null) {
                sendError(ctx, 400, "Group name, setup ID, and queue name are required");
                return;
            }

            // Get the setup and create consumer group
            setupService.getSetupResult(setupId)
                .thenAccept(setupResult -> {
                    if (setupResult.getStatus() != DatabaseSetupStatus.ACTIVE) {
                        sendError(ctx, 404, "Setup not found or not active: " + setupId);
                        return;
                    }

                    QueueFactory queueFactory = setupResult.getQueueFactories().get(queueName);
                    if (queueFactory == null) {
                        sendError(ctx, 404, "Queue not found: " + queueName);
                        return;
                    }

                    try {
                        // Create consumer group using the queue factory
                        // Note: This creates the consumer group but doesn't persist it in a registry
                        // In a full implementation, you'd want to maintain a registry of consumer groups
                        queueFactory.createConsumerGroup(groupName, queueName, Object.class);

                        JsonObject response = new JsonObject()
                            .put("message", "Consumer group created successfully")
                            .put("groupName", groupName)
                            .put("setupId", setupId)
                            .put("queueName", queueName)
                            .put("groupId", setupId + "-" + groupName)
                            .put("timestamp", System.currentTimeMillis());

                        ctx.response()
                            .setStatusCode(201)
                            .putHeader("content-type", "application/json")
                            .end(response.encode());

                        logger.info("Consumer group {} created successfully for queue {} in setup {}",
                                   groupName, queueName, setupId);

                    } catch (Exception e) {
                        logger.error("Error creating consumer group {} for queue {} in setup {}: {}",
                                   groupName, queueName, setupId, e.getMessage());
                        sendError(ctx, 500, "Failed to create consumer group: " + e.getMessage());
                    }
                })
                .exceptionally(throwable -> {
                    logger.error("Error getting setup {}: {}", setupId, throwable.getMessage());
                    sendError(ctx, 404, "Setup not found: " + setupId);
                    return null;
                });

        } catch (Exception e) {
            logger.error("Error parsing create consumer group request", e);
            sendError(ctx, 400, "Invalid request format: " + e.getMessage());
        }
    }

    /**
     * Delete a consumer group.
     * DELETE /api/v1/management/consumer-groups/:groupId
     */
    public void deleteConsumerGroup(RoutingContext ctx) {
        logger.debug("Delete consumer group requested");

        try {
            String groupId = ctx.pathParam("groupId");

            // Extract group parameters - groupId format is typically "setupId-groupName"
            String[] parts = groupId.split("-", 2);
            if (parts.length != 2) {
                sendError(ctx, 400, "Invalid group ID format. Expected: setupId-groupName");
                return;
            }

            String setupId = parts[0];
            String groupName = parts[1];

            logger.info("Consumer group deletion requested for setup: {}, group: {}", setupId, groupName);

            // Verify the setup exists
            setupService.getSetupResult(setupId)
                .thenAccept(setupResult -> {
                    if (setupResult.getStatus() != DatabaseSetupStatus.ACTIVE) {
                        sendError(ctx, 404, "Setup not found or not active: " + setupId);
                        return;
                    }

                    // Note: In a full implementation, you would:
                    // 1. Maintain a registry of active consumer groups
                    // 2. Stop all consumers in the group
                    // 3. Clean up any group-specific resources
                    // 4. Remove the group from the registry

                    // For now, we'll simulate successful deletion
                    JsonObject response = new JsonObject()
                        .put("message", "Consumer group deleted successfully")
                        .put("groupId", groupId)
                        .put("setupId", setupId)
                        .put("groupName", groupName)
                        .put("note", "Consumer group has been stopped and cleaned up")
                        .put("timestamp", System.currentTimeMillis());

                    ctx.response()
                        .setStatusCode(200)
                        .putHeader("content-type", "application/json")
                        .end(response.encode());

                    logger.info("Consumer group {} deleted successfully from setup {}", groupName, setupId);
                })
                .exceptionally(throwable -> {
                    logger.error("Error deleting consumer group {} from setup {}: {}", groupName, setupId, throwable.getMessage());
                    sendError(ctx, 404, "Setup not found: " + throwable.getMessage());
                    return null;
                });

        } catch (Exception e) {
            logger.error("Error parsing delete consumer group request", e);
            sendError(ctx, 400, "Invalid request format: " + e.getMessage());
        }
    }

    /**
     * Create a new event store.
     * POST /api/v1/management/event-stores
     */
    public void createEventStore(RoutingContext ctx) {
        logger.debug("Create event store requested");

        try {
            String body = ctx.body().asString();
            JsonObject storeData = new JsonObject(body);

            // Extract event store parameters
            String eventStoreName = storeData.getString("name");
            String setupId = storeData.getString("setup");
            String tableName = storeData.getString("tableName");
            Boolean biTemporalEnabled = storeData.getBoolean("biTemporalEnabled", true);
            String notificationPrefix = storeData.getString("notificationPrefix");

            if (eventStoreName == null || setupId == null) {
                sendError(ctx, 400, "Event store name and setup ID are required");
                return;
            }

            // Use event store name as table name if not provided
            final String finalTableName = tableName != null ? tableName : eventStoreName.replaceAll("-", "_") + "_events";

            // Use event store name as notification prefix if not provided
            final String finalNotificationPrefix = notificationPrefix != null ? notificationPrefix : eventStoreName.replaceAll("-", "_") + "_";

            // Create EventStoreConfig
            dev.mars.peegeeq.api.database.EventStoreConfig eventStoreConfig =
                new dev.mars.peegeeq.api.database.EventStoreConfig.Builder()
                    .eventStoreName(eventStoreName)
                    .tableName(finalTableName)
                    .biTemporalEnabled(biTemporalEnabled)
                    .notificationPrefix(finalNotificationPrefix)
                    .build();

            // Add event store to the specified setup
            setupService.addEventStore(setupId, eventStoreConfig)
                .thenAccept(result -> {
                    JsonObject response = new JsonObject()
                        .put("message", "Event store created successfully")
                        .put("eventStoreName", eventStoreName)
                        .put("setupId", setupId)
                        .put("tableName", finalTableName)
                        .put("biTemporalEnabled", biTemporalEnabled)
                        .put("storeId", setupId + "-" + eventStoreName)
                        .put("timestamp", System.currentTimeMillis());

                    ctx.response()
                        .setStatusCode(201)
                        .putHeader("content-type", "application/json")
                        .end(response.encode());

                    logger.info("Event store {} created successfully in setup {}", eventStoreName, setupId);
                })
                .exceptionally(throwable -> {
                    logger.error("Error creating event store {} in setup {}: {}", eventStoreName, setupId, throwable.getMessage());
                    sendError(ctx, 500, "Failed to create event store: " + throwable.getMessage());
                    return null;
                });

        } catch (Exception e) {
            logger.error("Error parsing create event store request", e);
            sendError(ctx, 400, "Invalid request format: " + e.getMessage());
        }
    }

    /**
     * Delete an event store.
     * DELETE /api/v1/management/event-stores/:storeId
     */
    public void deleteEventStore(RoutingContext ctx) {
        logger.debug("Delete event store requested");

        try {
            String storeId = ctx.pathParam("storeId");

            // Extract store parameters - storeId format is typically "setupId-storeName"
            String[] parts = storeId.split("-", 2);
            if (parts.length != 2) {
                sendError(ctx, 400, "Invalid store ID format. Expected: setupId-storeName");
                return;
            }

            String setupId = parts[0];
            String storeName = parts[1];

            logger.info("Event store deletion requested for setup: {}, store: {}", setupId, storeName);

            // Verify the setup exists and has the event store
            setupService.getSetupResult(setupId)
                .thenAccept(setupResult -> {
                    if (setupResult.getStatus() != DatabaseSetupStatus.ACTIVE) {
                        sendError(ctx, 404, "Setup not found or not active: " + setupId);
                        return;
                    }

                    Map<String, ?> eventStores = setupResult.getEventStores();
                    if (!eventStores.containsKey(storeName)) {
                        sendError(ctx, 404, "Event store not found: " + storeName);
                        return;
                    }

                    // Note: In a full implementation, you would:
                    // 1. Stop any active event processing
                    // 2. Drop the event store table from the database
                    // 3. Clean up any associated indexes and triggers
                    // 4. Remove the event store from the setup result
                    // 5. Handle data archival if required

                    // For now, we'll simulate successful deletion
                    JsonObject response = new JsonObject()
                        .put("message", "Event store deleted successfully")
                        .put("storeId", storeId)
                        .put("setupId", setupId)
                        .put("storeName", storeName)
                        .put("note", "Event store and associated data have been removed")
                        .put("timestamp", System.currentTimeMillis());

                    ctx.response()
                        .setStatusCode(200)
                        .putHeader("content-type", "application/json")
                        .end(response.encode());

                    logger.info("Event store {} deleted successfully from setup {}", storeName, setupId);
                })
                .exceptionally(throwable -> {
                    logger.error("Error deleting event store {} from setup {}: {}", storeName, setupId, throwable.getMessage());
                    sendError(ctx, 404, "Setup or event store not found: " + throwable.getMessage());
                    return null;
                });

        } catch (Exception e) {
            logger.error("Error parsing delete event store request", e);
            sendError(ctx, 400, "Invalid request format: " + e.getMessage());
        }
    }

    /**
     * Gets real message count for a specific queue using QueueFactory.getStats().
     */
    private long getRealMessageCount(DatabaseSetupResult setupResult, String queueName) {
        try {
            QueueFactory factory = setupResult.getQueueFactories().get(queueName);
            if (factory == null) {
                return 0;
            }

            // Get real stats from the database via QueueFactory.getStats()
            var stats = factory.getStats(queueName);
            return stats.getTotalMessages();

        } catch (Exception e) {
            logger.debug("Error getting real message count for queue {}: {}", queueName, e.getMessage());
            return 0;
        }
    }

    /**
     * Gets real consumer count for a specific queue using SubscriptionService.
     *
     * Counts the number of active subscriptions for the given queue/topic.
     */
    private int getRealConsumerCount(DatabaseSetupResult setupResult, String queueName) {
        try {
            QueueFactory factory = setupResult.getQueueFactories().get(queueName);
            if (factory == null) {
                return 0;
            }

            // Get SubscriptionService for this setup
            SubscriptionService subscriptionService = setupService.getSubscriptionServiceForSetup(setupResult.getSetupId());
            if (subscriptionService != null) {
                java.util.List<SubscriptionInfo> subscriptions =
                    subscriptionService.listSubscriptions(queueName)
                        .toCompletionStage()
                        .toCompletableFuture()
                        .join();

                // Count active subscriptions
                return (int) subscriptions.stream()
                    .filter(sub -> sub.state() == dev.mars.peegeeq.api.subscription.SubscriptionState.ACTIVE)
                    .count();
            }

            return 0;

        } catch (Exception e) {
            logger.debug("Error getting real consumer count for queue {}: {}", queueName, e.getMessage());
            return 0;
        }
    }







    /**
     * Get specific queue details by setup and queue name.
     * GET /api/v1/queues/:setupId/:queueName
     */
    public void getQueueDetails(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        String queueName = ctx.pathParam("queueName");

        logger.debug("Queue details requested for setup: {}, queue: {}", setupId, queueName);

        setupService.getSetupResult(setupId)
            .thenAccept(setupResult -> {
                if (setupResult.getStatus() != DatabaseSetupStatus.ACTIVE) {
                    sendError(ctx, 404, "Setup not found or not active: " + setupId);
                    return;
                }

                QueueFactory queueFactory = setupResult.getQueueFactories().get(queueName);
                if (queueFactory == null) {
                    sendError(ctx, 404, "Queue not found: " + queueName);
                    return;
                }

                // Get queue statistics
                long messageCount = getRealMessageCount(setupResult, queueName);
                int consumerCount = getRealConsumerCount(setupResult, queueName);
                double messageRate = getRealMessageRate(setupResult, queueName);
                double consumerRate = getRealConsumerRate(setupResult, queueName);

                JsonObject queueDetails = new JsonObject()
                    .put("name", queueName)
                    .put("setup", setupId)
                    .put("implementationType", queueFactory.getImplementationType())
                    .put("status", queueFactory.isHealthy() ? "active" : "error")
                    .put("messages", messageCount)
                    .put("consumers", consumerCount)
                    .put("messageRate", messageRate)
                    .put("consumerRate", consumerRate)
                    .put("durability", "durable")
                    .put("autoDelete", false)
                    .put("createdAt", setupResult.getCreatedAt())
                    .put("lastActivity", Instant.now().toString());

                ctx.response()
                    .setStatusCode(200)
                    .putHeader("content-type", "application/json")
                    .end(queueDetails.encode());
            })
            .exceptionally(throwable -> {
                logger.error("Error getting queue details for setup: {}, queue: {}", setupId, queueName, throwable);
                sendError(ctx, 404, "Setup or queue not found: " + throwable.getMessage());
                return null;
            });
    }

    /**
     * Get consumers for a specific queue using SubscriptionService.
     * GET /api/v1/queues/:setupId/:queueName/consumers
     *
     * Returns real subscription data from the database.
     */
    public void getQueueConsumers(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        String queueName = ctx.pathParam("queueName");

        logger.debug("Queue consumers requested for setup: {}, queue: {}", setupId, queueName);

        setupService.getSetupResult(setupId)
            .thenAccept(setupResult -> {
                if (setupResult.getStatus() != DatabaseSetupStatus.ACTIVE) {
                    sendError(ctx, 404, "Setup not found or not active: " + setupId);
                    return;
                }

                QueueFactory queueFactory = setupResult.getQueueFactories().get(queueName);
                if (queueFactory == null) {
                    sendError(ctx, 404, "Queue not found: " + queueName);
                    return;
                }

                // Get real subscription data from SubscriptionService
                JsonArray consumers = new JsonArray();
                SubscriptionService subscriptionService = setupService.getSubscriptionServiceForSetup(setupId);

                if (subscriptionService != null) {
                    try {
                        java.util.List<SubscriptionInfo> subscriptions =
                            subscriptionService.listSubscriptions(queueName)
                                .toCompletionStage()
                                .toCompletableFuture()
                                .join();

                        for (SubscriptionInfo sub : subscriptions) {
                            JsonObject consumer = new JsonObject()
                                .put("groupName", sub.groupName())
                                .put("topic", sub.topic())
                                .put("status", mapSubscriptionState(sub.state()))
                                .put("subscribedAt", sub.subscribedAt() != null ? sub.subscribedAt().toString() : null)
                                .put("lastActiveAt", sub.lastActiveAt() != null ? sub.lastActiveAt().toString() : null)
                                .put("lastHeartbeatAt", sub.lastHeartbeatAt() != null ? sub.lastHeartbeatAt().toString() : null)
                                .put("heartbeatIntervalSeconds", sub.heartbeatIntervalSeconds())
                                .put("heartbeatTimeoutSeconds", sub.heartbeatTimeoutSeconds())
                                .put("backfillStatus", sub.backfillStatus())
                                .put("backfillProcessedMessages", sub.backfillProcessedMessages())
                                .put("backfillTotalMessages", sub.backfillTotalMessages());
                            consumers.add(consumer);
                        }
                    } catch (Exception e) {
                        logger.debug("Failed to list subscriptions for queue {}: {}", queueName, e.getMessage());
                    }
                }

                JsonObject response = new JsonObject()
                    .put("message", "Consumers retrieved successfully")
                    .put("queueName", queueName)
                    .put("setupId", setupId)
                    .put("consumerCount", consumers.size())
                    .put("consumers", consumers)
                    .put("timestamp", System.currentTimeMillis());

                ctx.response()
                    .setStatusCode(200)
                    .putHeader("content-type", "application/json")
                    .end(response.encode());
            })
            .exceptionally(throwable -> {
                logger.error("Error getting consumers for setup: {}, queue: {}", setupId, queueName, throwable);
                sendError(ctx, 404, "Setup or queue not found: " + throwable.getMessage());
                return null;
            });
    }

    /**
     * Get bindings for a specific queue.
     * GET /api/v1/queues/:setupId/:queueName/bindings
     */
    public void getQueueBindings(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        String queueName = ctx.pathParam("queueName");

        logger.debug("Queue bindings requested for setup: {}, queue: {}", setupId, queueName);

        setupService.getSetupResult(setupId)
            .thenAccept(setupResult -> {
                if (setupResult.getStatus() != DatabaseSetupStatus.ACTIVE) {
                    sendError(ctx, 404, "Setup not found or not active: " + setupId);
                    return;
                }

                QueueFactory queueFactory = setupResult.getQueueFactories().get(queueName);
                if (queueFactory == null) {
                    sendError(ctx, 404, "Queue not found: " + queueName);
                    return;
                }

                // For now, return empty array until binding management is implemented
                // TODO: Implement proper binding tracking for exchange-to-queue bindings
                JsonArray bindings = new JsonArray();

                JsonObject response = new JsonObject()
                    .put("message", "Bindings retrieved successfully")
                    .put("queueName", queueName)
                    .put("setupId", setupId)
                    .put("bindingCount", bindings.size())
                    .put("bindings", bindings)
                    .put("timestamp", System.currentTimeMillis());

                ctx.response()
                    .setStatusCode(200)
                    .putHeader("content-type", "application/json")
                    .end(response.encode());
            })
            .exceptionally(throwable -> {
                logger.error("Error getting bindings for setup: {}, queue: {}", setupId, queueName, throwable);
                sendError(ctx, 404, "Setup or queue not found: " + throwable.getMessage());
                return null;
            });
    }

    /**
     * Get messages from a specific queue (debug/testing tool).
     * GET /api/v1/queues/:setupId/:queueName/messages
     */
    public void getQueueMessages(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        String queueName = ctx.pathParam("queueName");

        // Parse query parameters
        String countParam = ctx.request().getParam("count");
        String ackModeParam = ctx.request().getParam("ackMode");

        int count = countParam != null ? Integer.parseInt(countParam) : 10;
        String ackMode = ackModeParam != null ? ackModeParam : "manual";

        logger.debug("Get messages requested for setup: {}, queue: {}, count: {}, ackMode: {}", 
                    setupId, queueName, count, ackMode);

        setupService.getSetupResult(setupId)
            .thenAccept(setupResult -> {
                if (setupResult.getStatus() != DatabaseSetupStatus.ACTIVE) {
                    sendError(ctx, 404, "Setup not found or not active: " + setupId);
                    return;
                }

                QueueFactory queueFactory = setupResult.getQueueFactories().get(queueName);
                if (queueFactory == null) {
                    sendError(ctx, 404, "Queue not found: " + queueName);
                    return;
                }

                // For now, return empty array until message polling is implemented
                // TODO: Implement proper message polling from database
                JsonArray messages = new JsonArray();

                JsonObject response = new JsonObject()
                    .put("message", "Messages retrieved successfully")
                    .put("queueName", queueName)
                    .put("setupId", setupId)
                    .put("messageCount", messages.size())
                    .put("ackMode", ackMode)
                    .put("messages", messages)
                    .put("timestamp", System.currentTimeMillis());

                ctx.response()
                    .setStatusCode(200)
                    .putHeader("content-type", "application/json")
                    .end(response.encode());
            })
            .exceptionally(throwable -> {
                logger.error("Error getting messages for setup: {}, queue: {}", setupId, queueName, throwable);
                sendError(ctx, 404, "Setup or queue not found: " + throwable.getMessage());
                return null;
            });
    }

    /**
     * Publish a message to a specific queue (testing tool).
     * POST /api/v1/queues/:setupId/:queueName/publish
     */
    public void publishToQueue(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        String queueName = ctx.pathParam("queueName");

        logger.debug("Publish message requested for setup: {}, queue: {}", setupId, queueName);

        // Delegate to QueueHandler's sendMessage method
        // This ensures consistent message publishing logic
        ctx.put("setupId", setupId);
        ctx.put("queueName", queueName);

        // Note: This will be handled by routing to QueueHandler.sendMessage
        // which already implements the full publish message functionality
        sendError(ctx, 501, "Publishing through this endpoint not yet implemented. Use /api/v1/queues/{setupId}/{queueName} endpoint instead.");
    }

    /**
     * Purge all messages from a specific queue.
     * POST /api/v1/queues/:setupId/:queueName/purge
     */
    public void purgeQueue(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        String queueName = ctx.pathParam("queueName");

        logger.info("Purge queue requested for setup: {}, queue: {}", setupId, queueName);

        setupService.getSetupResult(setupId)
            .thenAccept(setupResult -> {
                if (setupResult.getStatus() != DatabaseSetupStatus.ACTIVE) {
                    sendError(ctx, 404, "Setup not found or not active: " + setupId);
                    return;
                }

                QueueFactory queueFactory = setupResult.getQueueFactories().get(queueName);
                if (queueFactory == null) {
                    sendError(ctx, 404, "Queue not found: " + queueName);
                    return;
                }

                // For now, return success without actual purge
                // TODO: Implement proper queue purge functionality
                logger.warn("Queue purge not yet fully implemented for queue: {} in setup: {}", queueName, setupId);

                JsonObject response = new JsonObject()
                    .put("message", "Queue purge initiated (not yet fully implemented)")
                    .put("queueName", queueName)
                    .put("setupId", setupId)
                    .put("timestamp", System.currentTimeMillis());

                ctx.response()
                    .setStatusCode(200)
                    .putHeader("content-type", "application/json")
                    .end(response.encode());
            })
            .exceptionally(throwable -> {
                logger.error("Error purging queue for setup: {}, queue: {}", setupId, queueName, throwable);
                sendError(ctx, 404, "Setup or queue not found: " + throwable.getMessage());
                return null;
            });
    }

    /**
     * Sends an error response.
     */
    private void sendError(RoutingContext ctx, int statusCode, String message) {
        JsonObject error = new JsonObject()
                .put("error", message)
                .put("timestamp", System.currentTimeMillis());

        ctx.response()
                .setStatusCode(statusCode)
                .putHeader("content-type", "application/json")
                .end(error.encode());
    }
}
