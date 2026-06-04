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
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
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
 * Handler for Management API endpoints that support the web-based admin
 * console.
 * 
 * Provides REST endpoints for system overview, health checks, and management
 * operations
 * specifically designed to support the PeeGeeQ Management UI.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-19
 * @version 1.0
 */
public class ManagementApiHandler {

    private static final Logger logger = LoggerFactory.getLogger(ManagementApiHandler.class);

    private final DatabaseSetupService setupService;
    private final Vertx vertx;
    // Cache for system metrics (updated periodically)
    private final Map<String, Object> systemMetricsCache = new ConcurrentHashMap<>();
    private long lastMetricsUpdate = 0;
    private static final long METRICS_CACHE_TTL = 30000; // 30 seconds

    public ManagementApiHandler(DatabaseSetupService setupService, ObjectMapper objectMapper, Vertx vertx) {
        this.setupService = setupService;
        this.vertx = vertx;
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

        setupService.getAllActiveSetupIds()
                .compose(activeSetupIds -> {
                    Future<JsonArray> setupsFuture;
                    if (activeSetupIds.isEmpty()) {
                        setupsFuture = Future.succeededFuture(new JsonArray());
                    } else {
                        List<Future<JsonObject>> setupFutures = new ArrayList<>();
                        for (String setupId : activeSetupIds) {
                            setupFutures.add(getSetupSummary(setupId));
                        }
                        setupsFuture = Future.all(setupFutures).map(cf -> {
                            JsonArray setups = new JsonArray();
                            for (int i = 0; i < cf.size(); i++) {
                                setups.add(cf.<JsonObject>resultAt(i));
                            }
                            return setups;
                        });
                    }
                    return Future.all(setupsFuture, getRecentActivity(), getTotalActiveConnections()).map(cf -> {
                        JsonArray setups = cf.resultAt(0);
                        JsonArray recentActivity = cf.resultAt(1);
                        int activeConnections = cf.resultAt(2);
                        return new JsonObject()
                                .put("setups", setups)
                                .put("systemStats", buildSystemTotals(setups, activeConnections))
                                .put("recentActivity", recentActivity)
                                .put("timestamp", System.currentTimeMillis());
                    });
                })
                .onSuccess(overview -> ctx.response()
                        .setStatusCode(200)
                        .putHeader("content-type", "application/json")
                        .end(overview.encode()))
                .onFailure(e -> {
                    logger.error("Error getting system overview: {}", e.getMessage(), e);
                    sendError(ctx, 503, "Failed to get system overview: " + e.getMessage());
                });
    }

    /**
     * Builds a per-setup summary containing its queues, consumer groups, event stores
     * and derived per-setup totals.
     */
    private Future<JsonObject> getSetupSummary(String setupId) {
        return Future.all(
                getQueuesForSetup(setupId),
                getConsumerGroupsForSetup(setupId),
                getEventStoresForSetup(setupId)
        ).map(cf -> {
            JsonArray queues = cf.resultAt(0);
            JsonArray consumerGroups = cf.resultAt(1);
            JsonArray eventStores = cf.resultAt(2);

            int totalMessages = 0;
            double messagesPerSecond = 0.0;
            for (Object obj : queues) {
                if (obj instanceof JsonObject) {
                    JsonObject q = (JsonObject) obj;
                    totalMessages += q.getInteger("messages", 0);
                    messagesPerSecond += q.getDouble("messageRate", 0.0);
                }
            }

            return new JsonObject()
                    .put("setupId", setupId)
                    .put("status", "ACTIVE")
                    .put("totalQueues", queues.size())
                    .put("totalConsumerGroups", consumerGroups.size())
                    .put("totalEventStores", eventStores.size())
                    .put("totalMessages", totalMessages)
                    .put("messagesPerSecond", messagesPerSecond)
                    .put("queues", queues)
                    .put("consumerGroups", consumerGroups)
                    .put("eventStores", eventStores);
        }).transform(ar -> {
            if (ar.failed()) {
                logger.warn("Failed to get summary for setup {}: {}", setupId, ar.cause().getMessage());
                return Future.succeededFuture(new JsonObject()
                        .put("setupId", setupId)
                        .put("status", "ERROR")
                        .put("totalQueues", 0)
                        .put("totalConsumerGroups", 0)
                        .put("totalEventStores", 0)
                        .put("totalMessages", 0)
                        .put("messagesPerSecond", 0.0)
                        .put("queues", new JsonArray())
                        .put("consumerGroups", new JsonArray())
                        .put("eventStores", new JsonArray()));
            }
            return Future.succeededFuture(ar.result());
        });
    }

    /**
     * Builds system-wide totals by summing across all setup summaries.
     */
    private JsonObject buildSystemTotals(JsonArray setups, int activeConnections) {
        int totalSetups = setups.size();
        int totalQueues = 0;
        int totalConsumerGroups = 0;
        int totalEventStores = 0;
        int totalMessages = 0;
        double messagesPerSecond = 0.0;

        for (Object obj : setups) {
            if (obj instanceof JsonObject) {
                JsonObject setup = (JsonObject) obj;
                totalQueues += setup.getInteger("totalQueues", 0);
                totalConsumerGroups += setup.getInteger("totalConsumerGroups", 0);
                totalEventStores += setup.getInteger("totalEventStores", 0);
                totalMessages += setup.getInteger("totalMessages", 0);
                messagesPerSecond += setup.getDouble("messagesPerSecond", 0.0);
            }
        }

        return new JsonObject()
                .put("totalSetups", totalSetups)
                .put("totalQueues", totalQueues)
                .put("totalConsumerGroups", totalConsumerGroups)
                .put("totalEventStores", totalEventStores)
                .put("totalMessages", totalMessages)
                .put("messagesPerSecond", messagesPerSecond)
                .put("activeConnections", activeConnections)
                .put("uptime", getUptimeString());
    }

    /**
     * Queries pg_stat_activity for each active setup to count real database connections.
     */
    private Future<Integer> getTotalActiveConnections() {
        return setupService.getAllActiveSetupIds()
                .compose(activeSetupIds -> {
                    if (activeSetupIds.isEmpty()) {
                        return Future.succeededFuture(0);
                    }
                    List<Future<Integer>> futures = new ArrayList<>();
                    for (String setupId : activeSetupIds) {
                        futures.add(getActiveConnectionsForSetup(setupId));
                    }
                    return Future.all(futures).map(cf -> {
                        int total = 0;
                        for (int i = 0; i < cf.size(); i++) {
                            total += (int) cf.resultAt(i);
                        }
                        return total;
                    });
                })
                .transform(ar -> {
                    if (ar.failed()) {
                        logger.debug("Failed to get total active connections: {}", ar.cause().getMessage());
                        return Future.succeededFuture(0);
                    }
                    return Future.succeededFuture(ar.result());
                });
    }

    /**
     * Counts active PostgreSQL connections for a single setup by querying pg_stat_activity.
     */
    private Future<Integer> getActiveConnectionsForSetup(String setupId) {
        return setupService.getDatabaseConfig(setupId)
                .compose(dbConfig -> {
                    PgConnectOptions connectOptions = new PgConnectOptions()
                            .setHost(dbConfig.getHost())
                            .setPort(dbConfig.getPort())
                            .setDatabase(dbConfig.getDatabaseName())
                            .setUser(dbConfig.getUsername())
                            .setPassword(dbConfig.getPassword());

                    Pool tempPool = PgBuilder.pool()
                            .with(new PoolOptions().setMaxSize(1))
                            .connectingTo(connectOptions)
                            .using(vertx)
                            .build();

                    return tempPool
                            .preparedQuery("SELECT count(*)::int FROM pg_stat_activity WHERE datname = current_database()")
                            .execute()
                            .map(rows -> rows.iterator().next().getInteger(0))
                            .eventually(() -> tempPool.close());
                })
                .transform(ar -> {
                    if (ar.failed()) {
                        logger.debug("Failed to get active connections for setup {}: {}", setupId, ar.cause().getMessage());
                        return Future.succeededFuture(0);
                    }
                    return Future.succeededFuture(ar.result());
                });
    }

    /**
     * Queue list endpoint for the management UI.
     * GET /api/v1/management/queues
     */
    public void getQueues(RoutingContext ctx) {
        logger.debug("Queue list requested");

        getRealQueues()
                .map(queues -> new JsonObject()
                        .put("message", "Queues retrieved successfully")
                        .put("queueCount", queues.size())
                        .put("queues", queues)
                        .put("timestamp", System.currentTimeMillis()))
                .onSuccess(response -> ctx.response()
                        .setStatusCode(200)
                        .putHeader("content-type", "application/json")
                        .end(response.encode()))
                .onFailure(e -> {
                    logger.error("Error retrieving queues", e);
                    sendError(ctx, 503, "Failed to retrieve queues: " + e.getMessage());
                });
    }

    /**
     * Gets real queue data from active setups.
     */
    private Future<JsonArray> getRealQueues() {
        return setupService.getAllActiveSetupIds()
                .compose(activeSetupIds -> {
                    List<Future<JsonArray>> setupFutures = new ArrayList<>();
                    for (String setupId : activeSetupIds) {
                        setupFutures.add(getQueuesForSetup(setupId));
                    }
                    if (setupFutures.isEmpty()) {
                        return Future.succeededFuture(new JsonArray());
                    }
                    return Future.all(setupFutures).map(cf -> {
                        JsonArray queues = new JsonArray();
                        for (int i = 0; i < cf.size(); i++) {
                            JsonArray partial = cf.resultAt(i);
                            partial.forEach(queues::add);
                        }
                        return queues;
                    });
                })
                .transform(ar -> {
                    if (ar.failed()) {
                        logger.warn("Failed to retrieve real queue data", ar.cause());
                        return Future.succeededFuture(new JsonArray());
                    }
                    return Future.succeededFuture(ar.result());
                });
    }

    private Future<JsonArray> getQueuesForSetup(String setupId) {
        return setupService.getSetupResult(setupId)
                .compose(setupResult -> {
                    if (setupResult.getStatus() != DatabaseSetupStatus.ACTIVE) {
                        return Future.succeededFuture(new JsonArray());
                    }
                    Map<String, QueueFactory> queueFactories = setupResult.getQueueFactories();
                    List<Future<JsonObject>> queueFutures = new ArrayList<>();
                    for (Map.Entry<String, QueueFactory> entry : queueFactories.entrySet()) {
                        String queueName = entry.getKey();
                        QueueFactory factory = entry.getValue();
                        queueFutures.add(
                                getRealConsumerCount(setupResult, queueName).compose(consumerCount ->
                                    Future.all(
                                            factory.countMessages(queueName),
                                            getRealMessageRate(setupResult, queueName),
                                            getRealAvgProcessingTime(setupResult, queueName),
                                            factory.isHealthy()
                                    ).map(cf -> {
                                            long messageCount = cf.resultAt(0);
                                            double messageRate = cf.resultAt(1);
                                            double avgProcessingTime = cf.resultAt(2);
                                            boolean healthy = cf.resultAt(3);
                                            JsonObject statistics = new JsonObject()
                                                    .put("totalMessages", messageCount)
                                                    .put("activeConsumers", consumerCount)
                                                    .put("messagesPerSecond", messageRate)
                                                    .put("avgProcessingTimeMs", avgProcessingTime);
                                            return new JsonObject()
                                                    .put("setupId", setupId)
                                                    .put("setup", setupId)
                                                    .put("queueName", queueName)
                                                    .put("name", queueName)
                                                    .put("type", factory.getImplementationType())
                                                    .put("implementationType", factory.getImplementationType())
                                                    .put("status", healthy ? "active" : "error")
                                                    .put("messageCount", messageCount)
                                                    .put("messages", messageCount)
                                                    .put("consumerCount", consumerCount)
                                                    .put("consumers", consumerCount)
                                                    .put("messageRate", messageRate)
                                                    .put("errorRate", 0.0)
                                                    .put("statistics", statistics)
                                                    .put("createdAt", setupResult.getCreatedAt())
                                                    .put("updatedAt", Instant.now().toString());
                                        })));
                    }
                    if (queueFutures.isEmpty()) {
                        return Future.succeededFuture(new JsonArray());
                    }
                    return Future.all(queueFutures).map(cf -> {
                        JsonArray queues = new JsonArray();
                        for (int i = 0; i < cf.size(); i++) {
                            queues.add(cf.<JsonObject>resultAt(i));
                        }
                        return queues;
                    });
                })
                .transform(ar -> {
                    if (ar.failed()) {
                        logger.debug("Setup {} not found or error occurred: {}", setupId, ar.cause().getMessage());
                        return Future.succeededFuture(new JsonArray());
                    }
                    return Future.succeededFuture(ar.result());
                });
    }

    /**
     * Consumer groups endpoint for the management UI.
     * GET /api/v1/management/consumer-groups
     */
    public void getConsumerGroups(RoutingContext ctx) {
        logger.debug("Consumer groups list requested");

        getRealConsumerGroups()
                .map(consumerGroups -> new JsonObject()
                        .put("message", "Consumer groups retrieved successfully")
                        .put("groupCount", consumerGroups.size())
                        .put("consumerGroups", consumerGroups)
                        .put("timestamp", System.currentTimeMillis()))
                .onSuccess(response -> ctx.response()
                        .setStatusCode(200)
                        .putHeader("content-type", "application/json")
                        .end(response.encode()))
                .onFailure(e -> {
                    logger.error("Error retrieving consumer groups", e);
                    sendError(ctx, 503, "Failed to retrieve consumer groups: " + e.getMessage());
                });
    }

    /**
     * Event stores endpoint for the management UI.
     * GET /api/v1/management/event-stores
     */
    public void getEventStores(RoutingContext ctx) {
        logger.debug("Event stores list requested");

        getRealEventStores()
                .map(eventStores -> new JsonObject()
                        .put("message", "Event stores retrieved successfully")
                        .put("eventStoreCount", eventStores.size())
                        .put("eventStores", eventStores)
                        .put("timestamp", System.currentTimeMillis()))
                .onSuccess(response -> ctx.response()
                        .setStatusCode(200)
                        .putHeader("content-type", "application/json")
                        .end(response.encode()))
                .onFailure(e -> {
                    logger.error("Error retrieving event stores", e);
                    sendError(ctx, 503, "Failed to retrieve event stores: " + e.getMessage());
                });
    }

    /**
     * Messages endpoint for the message browser.
     * GET /api/v1/management/messages
     */
    public void getMessages(RoutingContext ctx) {
        logger.debug("Messages list requested");

        String setupId = ctx.request().getParam("setup");
        String queueName = ctx.request().getParam("queue");
        String limit = ctx.request().getParam("limit");
        String offset = ctx.request().getParam("offset");

        getRealMessages(setupId, queueName, limit, offset)
                .map(messages -> new JsonObject()
                        .put("message", "Messages retrieved successfully")
                        .put("messageCount", messages.size())
                        .put("messages", messages)
                        .put("timestamp", System.currentTimeMillis()))
                .onSuccess(response -> ctx.response()
                        .setStatusCode(200)
                        .putHeader("content-type", "application/json")
                        .end(response.encode()))
                .onFailure(e -> {
                    logger.error("Error retrieving messages", e);
                    sendError(ctx, 503, "Failed to retrieve messages: " + e.getMessage());
                });
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
     * Gets recent activity for the overview dashboard.
     * Queries recent events from all active event stores and returns them as
     * activity items.
     */
    private Future<JsonArray> getRecentActivity() {
        Instant oneHourAgo = Instant.now().minus(1, ChronoUnit.HOURS);
        EventQuery recentQuery = EventQuery.builder()
                .transactionTimeRange(TemporalRange.from(oneHourAgo))
                .sortOrder(EventQuery.SortOrder.TRANSACTION_TIME_DESC)
                .limit(50)
                .build();

        return setupService.getAllActiveSetupIds()
                .compose(activeSetupIds -> {
                    List<Future<List<JsonObject>>> setupFutures = new ArrayList<>();
                    for (String setupId : activeSetupIds) {
                        setupFutures.add(getRecentActivityForSetup(setupId, recentQuery));
                    }
                    if (setupFutures.isEmpty()) {
                        return Future.succeededFuture(new JsonArray());
                    }
                    return Future.all(setupFutures).map(cf -> {
                        List<JsonObject> allActivities = new ArrayList<>();
                        for (int i = 0; i < cf.size(); i++) {
                            allActivities.addAll(cf.resultAt(i));
                        }
                        allActivities.sort(Comparator.comparing(
                                (JsonObject a) -> Instant.parse(a.getString("timestamp"))).reversed());
                        JsonArray activities = new JsonArray();
                        int limit = Math.min(allActivities.size(), 20);
                        for (int i = 0; i < limit; i++) {
                            activities.add(allActivities.get(i));
                        }
                        return activities;
                    });
                })
                .transform(ar -> {
                    if (ar.failed()) {
                        logger.warn("Failed to get recent activity: {}", ar.cause().getMessage());
                        return Future.succeededFuture(new JsonArray());
                    }
                    return Future.succeededFuture(ar.result());
                });
    }

    private Future<List<JsonObject>> getRecentActivityForSetup(String setupId, EventQuery recentQuery) {
        return setupService.getSetupResult(setupId)
                .compose(setupResult -> {
                    if (setupResult.getStatus() != DatabaseSetupStatus.ACTIVE) {
                        return Future.succeededFuture(List.<JsonObject>of());
                    }
                    Map<String, EventStore<?>> eventStoreMap = setupResult.getEventStores();
                    List<Future<List<JsonObject>>> storeFutures = new ArrayList<>();
                    for (Map.Entry<String, EventStore<?>> entry : eventStoreMap.entrySet()) {
                        String storeName = entry.getKey();
                        EventStore<?> eventStore = entry.getValue();
                        storeFutures.add(
                                eventStore.query(recentQuery)
                                        .map(events -> {
                                            List<JsonObject> activities = new ArrayList<>();
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
                                                if (event.getCorrelationId() != null) {
                                                    activity.put("correlationId", event.getCorrelationId());
                                                }
                                                activities.add(activity);
                                            }
                                            return activities;
                                        })
                                        .transform(ar2 -> {
                                            if (ar2.failed()) {
                                                logger.debug("Failed to query events from store {}: {}", storeName, ar2.cause().getMessage());
                                                return Future.succeededFuture(List.<JsonObject>of());
                                            }
                                            return Future.succeededFuture(ar2.result());
                                        }));
                    }
                    if (storeFutures.isEmpty()) {
                        return Future.succeededFuture(List.<JsonObject>of());
                    }
                    return Future.all(storeFutures).map(cf -> {
                        List<JsonObject> results = new ArrayList<>();
                        for (int i = 0; i < cf.size(); i++) {
                            results.addAll(cf.resultAt(i));
                        }
                        return results;
                    });
                })
                .transform(ar -> {
                    if (ar.failed()) {
                        logger.debug("Failed to get setup result for {}: {}", setupId, ar.cause().getMessage());
                        return Future.succeededFuture(List.<JsonObject>of());
                    }
                    return Future.succeededFuture(ar.result());
                });
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
    private Future<Double> getRealMessageRate(DatabaseSetupResult setupResult, String queueName) {
        var queueFactory = setupResult.getQueueFactories().get(queueName);
        if (queueFactory == null) {
            return Future.succeededFuture(0.0);
        }
        return queueFactory.getStats(queueName)
                .map(stats -> stats.getMessagesPerSecond());
    }

    /**
     * Get real average processing time for a specific queue.
     * Uses QueueFactory.getStats() to get the actual average processing time in
     * milliseconds.
     */
    private Future<Double> getRealAvgProcessingTime(DatabaseSetupResult setupResult, String queueName) {
        var queueFactory = setupResult.getQueueFactories().get(queueName);
        if (queueFactory == null) {
            return Future.succeededFuture(0.0);
        }
        return queueFactory.getStats(queueName)
                .map(stats -> stats.getAvgProcessingTimeMs());
    }

    /**
     * Gets real consumer group data from active setups using SubscriptionService.
     *
     * This method queries the actual subscription data from the database via
     * SubscriptionService.listSubscriptions() for each queue/topic.
     */
    private Future<JsonArray> getRealConsumerGroups() {
        return setupService.getAllActiveSetupIds()
                .compose(activeSetupIds -> {
                    List<Future<JsonArray>> setupFutures = new ArrayList<>();
                    for (String setupId : activeSetupIds) {
                        setupFutures.add(getConsumerGroupsForSetup(setupId));
                    }
                    if (setupFutures.isEmpty()) {
                        return Future.succeededFuture(new JsonArray());
                    }
                    return Future.all(setupFutures).map(cf -> {
                        JsonArray consumerGroups = new JsonArray();
                        for (int i = 0; i < cf.size(); i++) {
                            JsonArray partial = cf.resultAt(i);
                            partial.forEach(consumerGroups::add);
                        }
                        return consumerGroups;
                    });
                })
                .transform(ar -> {
                    if (ar.failed()) {
                        logger.warn("Failed to retrieve real consumer group data", ar.cause());
                        return Future.succeededFuture(new JsonArray());
                    }
                    return Future.succeededFuture(ar.result());
                });
    }

    private Future<JsonArray> getConsumerGroupsForSetup(String setupId) {
        return setupService.getSetupResult(setupId)
                .compose(setupResult -> {
                    if (setupResult.getStatus() != DatabaseSetupStatus.ACTIVE) {
                        return Future.succeededFuture(new JsonArray());
                    }
                    SubscriptionService subscriptionService = setupService.getSubscriptionServiceForSetup(setupId);
                    Map<String, QueueFactory> queueFactories = setupResult.getQueueFactories();
                    List<Future<JsonArray>> queueFutures = new ArrayList<>();
                    for (Map.Entry<String, QueueFactory> entry : queueFactories.entrySet()) {
                        String queueName = entry.getKey();
                        QueueFactory factory = entry.getValue();
                        if (subscriptionService != null) {
                            queueFutures.add(
                                    subscriptionService.listSubscriptions(queueName)
                                            .map(subscriptions -> {
                                                JsonArray groups = new JsonArray();
                                                for (SubscriptionInfo sub : subscriptions) {
                                                    JsonObject group = new JsonObject()
                                                            .put("name", sub.groupName())
                                                            .put("setup", setupId)
                                                            .put("queueName", queueName)
                                                            .put("implementationType", factory.getImplementationType())
                                                            .put("members", 0)
                                                            .put("status", mapSubscriptionState(sub.state()))
                                                            .put("partition", 0)
                                                            .put("lag", 0)
                                                            .put("subscribedAt",
                                                                    sub.subscribedAt() != null ? sub.subscribedAt().toString() : null)
                                                            .put("lastActiveAt",
                                                                    sub.lastActiveAt() != null ? sub.lastActiveAt().toString() : null)
                                                            .put("lastHeartbeatAt",
                                                                    sub.lastHeartbeatAt() != null ? sub.lastHeartbeatAt().toString() : null)
                                                            .put("backfillStatus", sub.backfillStatus())
                                                            .put("createdAt", setupResult.getCreatedAt());
                                                    groups.add(group);
                                                }
                                                return groups;
                                            })
                                            .transform(ar2 -> {
                                                if (ar2.failed()) {
                                                    logger.debug("Failed to list subscriptions for topic {}: {}", queueName, ar2.cause().getMessage());
                                                    return Future.succeededFuture(new JsonArray());
                                                }
                                                return Future.succeededFuture(ar2.result());
                                            }));
                        }
                    }
                    if (queueFutures.isEmpty()) {
                        return Future.succeededFuture(new JsonArray());
                    }
                    return Future.all(queueFutures).map(cf -> {
                        JsonArray consumerGroups = new JsonArray();
                        for (int i = 0; i < cf.size(); i++) {
                            JsonArray partial = cf.resultAt(i);
                            partial.forEach(consumerGroups::add);
                        }
                        return consumerGroups;
                    });
                })
                .transform(ar -> {
                    if (ar.failed()) {
                        logger.debug("Setup {} not found or error occurred: {}", setupId, ar.cause().getMessage());
                        return Future.succeededFuture(new JsonArray());
                    }
                    return Future.succeededFuture(ar.result());
                });
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
    private Future<JsonArray> getRealEventStores() {
        return setupService.getAllActiveSetupIds()
                .compose(activeSetupIds -> {
                    List<Future<JsonArray>> setupFutures = new ArrayList<>();
                    for (String setupId : activeSetupIds) {
                        setupFutures.add(getEventStoresForSetup(setupId));
                    }
                    if (setupFutures.isEmpty()) {
                        return Future.succeededFuture(new JsonArray());
                    }
                    return Future.all(setupFutures).map(cf -> {
                        JsonArray eventStores = new JsonArray();
                        for (int i = 0; i < cf.size(); i++) {
                            JsonArray partial = cf.resultAt(i);
                            partial.forEach(eventStores::add);
                        }
                        return eventStores;
                    });
                })
                .transform(ar -> {
                    if (ar.failed()) {
                        logger.warn("Failed to retrieve real event store data", ar.cause());
                        return Future.succeededFuture(new JsonArray());
                    }
                    return Future.succeededFuture(ar.result());
                });
    }

    private Future<JsonArray> getEventStoresForSetup(String setupId) {
        return setupService.getSetupResult(setupId)
                .compose(setupResult -> {
                    if (setupResult.getStatus() != DatabaseSetupStatus.ACTIVE) {
                        return Future.succeededFuture(new JsonArray());
                    }
                    Map<String, ?> eventStoreMap = setupResult.getEventStores();
                    List<Future<JsonObject>> storeFutures = new ArrayList<>();
                    for (Map.Entry<String, ?> entry : eventStoreMap.entrySet()) {
                        String storeName = entry.getKey();
                        storeFutures.add(
                                Future.all(
                                        getRealEventCount(setupId, storeName),
                                        getRealAggregateCount(setupId, storeName),
                                        getRealCorrectionCount(setupId, storeName)
                                ).map(cf -> new JsonObject()
                                        .put("name", storeName)
                                        .put("setup", setupId)
                                        .put("events", cf.<Long>resultAt(0))
                                        .put("aggregates", cf.<Long>resultAt(1))
                                        .put("corrections", cf.<Long>resultAt(2))
                                        .put("biTemporal", true)
                                        .put("retention", "365d")
                                        .put("status", "active")
                                        .put("createdAt", setupResult.getCreatedAt())
                                        .put("lastEvent", Instant.now().toString())));
                    }
                    if (storeFutures.isEmpty()) {
                        return Future.succeededFuture(new JsonArray());
                    }
                    return Future.all(storeFutures).map(cf -> {
                        JsonArray eventStores = new JsonArray();
                        for (int i = 0; i < cf.size(); i++) {
                            eventStores.add(cf.<JsonObject>resultAt(i));
                        }
                        return eventStores;
                    });
                })
                .transform(ar -> {
                    if (ar.failed()) {
                        logger.debug("Setup {} not found or error occurred: {}", setupId, ar.cause().getMessage());
                        return Future.succeededFuture(new JsonArray());
                    }
                    return Future.succeededFuture(ar.result());
                });
    }

    private Future<Long> getRealEventCount(String setupId, String storeName) {
        return setupService.getSetupResult(setupId)
                .compose(setupResult -> {
                    dev.mars.peegeeq.api.EventStore<?> store = setupResult.getEventStores().get(storeName);
                    if (store == null) {
                        return Future.succeededFuture(0L);
                    }
                    return store.getStats().map(dev.mars.peegeeq.api.EventStore.EventStoreStats::getTotalEvents);
                })
                .transform(ar -> {
                    if (ar.failed()) {
                        logger.debug("Failed to get event count for store {}/{}: {}", setupId, storeName, ar.cause().getMessage());
                        return Future.succeededFuture(0L);
                    }
                    return Future.succeededFuture(ar.result());
                });
    }

    private Future<Long> getRealAggregateCount(String setupId, String storeName) {
        return setupService.getSetupResult(setupId)
                .compose(setupResult -> {
                    dev.mars.peegeeq.api.EventStore<?> store = setupResult.getEventStores().get(storeName);
                    if (store == null) {
                        return Future.succeededFuture(0L);
                    }
                    return store.getStats().map(dev.mars.peegeeq.api.EventStore.EventStoreStats::getUniqueAggregateCount);
                })
                .transform(ar -> {
                    if (ar.failed()) {
                        logger.debug("Failed to get aggregate count for store {}/{}: {}", setupId, storeName, ar.cause().getMessage());
                        return Future.succeededFuture(0L);
                    }
                    return Future.succeededFuture(ar.result());
                });
    }

    private Future<Long> getRealCorrectionCount(String setupId, String storeName) {
        return setupService.getSetupResult(setupId)
                .compose(setupResult -> {
                    dev.mars.peegeeq.api.EventStore<?> store = setupResult.getEventStores().get(storeName);
                    if (store == null) {
                        return Future.succeededFuture(0L);
                    }
                    return store.getStats().map(dev.mars.peegeeq.api.EventStore.EventStoreStats::getTotalCorrections);
                })
                .transform(ar -> {
                    if (ar.failed()) {
                        logger.debug("Failed to get correction count for store {}/{}: {}", setupId, storeName, ar.cause().getMessage());
                        return Future.succeededFuture(0L);
                    }
                    return Future.succeededFuture(ar.result());
                });
    }

    /**
     * Gets real message data for the message browser using QueueBrowser.
     */
    private Future<JsonArray> getRealMessages(String setupId, String queueName, String limitStr, String offsetStr) {
        if (setupId == null || queueName == null) {
            return Future.succeededFuture(new JsonArray());
        }
        int limit = limitStr != null ? Integer.parseInt(limitStr) : 50;
        int offset = offsetStr != null ? Integer.parseInt(offsetStr) : 0;

        return setupService.getSetupResult(setupId)
                .compose(setupResult -> {
                    if (setupResult.getStatus() != DatabaseSetupStatus.ACTIVE) {
                        return Future.succeededFuture(new JsonArray());
                    }
                    QueueFactory queueFactory = setupResult.getQueueFactories().get(queueName);
                    if (queueFactory == null) {
                        return Future.succeededFuture(new JsonArray());
                    }
                    logger.info("Retrieving messages from setup: {}, queue: {}", setupId, queueName);
                    return queueFactory.<Object>createBrowser(queueName, Object.class).compose(browser ->
                        browser.browse(limit, offset)
                            .map(messageList -> {
                                JsonArray messages = new JsonArray();
                                for (var message : messageList) {
                                    JsonObject headersJson = new JsonObject();
                                    if (message.getHeaders() != null) {
                                        message.getHeaders().forEach(headersJson::put);
                                    }
                                    JsonObject msgJson = new JsonObject()
                                            .put("id", message.getId())
                                            .put("payload",
                                                    message.getPayload() != null ? message.getPayload().toString() : null)
                                            .put("createdAt",
                                                    message.getCreatedAt() != null ? message.getCreatedAt().toString() : null)
                                            .put("headers", headersJson);
                                    messages.add(msgJson);
                                }
                                return messages;
                            })
                            .eventually(() -> {
                                try { browser.close(); } catch (Exception ignored) { }
                                return Future.succeededFuture();
                            }));
                })
                .transform(ar -> {
                    if (ar.failed()) {
                        logger.debug("Failed to retrieve message data (table may not exist): {}", ar.cause().getMessage());
                        return Future.succeededFuture(new JsonArray());
                    }
                    return Future.succeededFuture(ar.result());
                });
    }

    /**
     * Create a new queue.
     * POST /api/v1/management/queues
     */
    public void createQueue(RoutingContext ctx) {
        logger.debug("Create queue requested");

        try {
            JsonObject queueData = ctx.body().asJsonObject();
            String setupId = ConfigParser.getSetupId(ctx.pathParam("setupId"), queueData);

            // Parse queue configuration using the unified parser
            dev.mars.peegeeq.api.database.QueueConfig queueConfig = ConfigParser.parseQueueConfig(queueData);
            String queueName = queueConfig.getQueueName();

            logger.info("Creating queue '{}' in setup '{}' using unified ConfigParser", queueName, setupId);

            // Add queue to the specified setup
            setupService.addQueue(setupId, queueConfig)
                    .compose(v -> setupService.getSetupResult(setupId))
                    .map(result -> {
                        logger.info("Queue {} created successfully in setup {} with all parameters", queueName, setupId);
                        var factory = result.getQueueFactories().get(queueName);
                        String implementationType = factory != null ? factory.getImplementationType() : null;
                        JsonObject response = new JsonObject()
                                .put("message", "Queue '" + queueName
                                        + "' created successfully in setup '" + setupId + "'")
                                .put("queueName", queueName)
                                .put("setupId", setupId)
                                .put("queueId", setupId + "-" + queueName)
                                .put("id", setupId + "-" + queueName)
                                .put("maxRetries", queueConfig.getMaxRetries())
                                .put("visibilityTimeoutSeconds", queueConfig.getVisibilityTimeout().getSeconds())
                                .put("deadLetterEnabled", queueConfig.isDeadLetterEnabled())
                                .put("batchSize", queueConfig.getBatchSize())
                                .put("pollingIntervalSeconds", queueConfig.getPollingInterval().getSeconds())
                                .put("fifoEnabled", queueConfig.isFifoEnabled())
                                .put("deadLetterQueueName", queueConfig.getDeadLetterQueueName())
                                .put("timestamp", System.currentTimeMillis());
                        if (implementationType != null) {
                            response.put("implementationType", implementationType);
                        }
                        return response;
                    })
                    .onSuccess(response -> ctx.response()
                            .setStatusCode(201)
                            .putHeader("content-type", "application/json")
                            .end(response.encode()))
                    .onFailure(throwable -> {
                        logger.error("Error creating queue '{}' in setup '{}': {}", queueName, setupId,
                                throwable.getMessage());
                        int statusCode = 503;
                        String errorMessage = "Failed to create queue '" + queueName + "': " + throwable.getMessage();
                        Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
                        if (cause.getMessage() != null && cause.getMessage().contains("Setup not found")) {
                            statusCode = 404;
                            errorMessage = "Setup not found: " + setupId;
                        } else if (cause instanceof IllegalArgumentException) {
                            statusCode = 400;
                            errorMessage = "Invalid queue configuration: " + cause.getMessage();
                        }
                        sendError(ctx, statusCode, errorMessage);
                    });

        } catch (IllegalArgumentException e) {
            logger.error("Invalid queue configuration: {}", e.getMessage());
            sendError(ctx, 400, "Invalid queue configuration: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Error parsing create queue request", e);
            sendError(ctx, 400, "Invalid request format: " + e.getMessage());
        }
    }

    /**
     * Update an existing queue.
     * PUT /api/v1/management/queues/:setupId/:queueName
     */
    public void updateQueue(RoutingContext ctx) {
        logger.debug("Update queue requested");

        try {
            String setupId = ctx.pathParam("setupId");
            String queueName = ctx.pathParam("queueName");
            if (setupId == null || setupId.isBlank() || queueName == null || queueName.isBlank()) {
                sendError(ctx, 400, "Both setupId and queueName path parameters are required");
                return;
            }

            // For now, queue updates are limited since we can't modify the underlying table
            // structure. We can only update configuration parameters that don't require schema changes
            logger.info("Queue update requested for setup: {}, queue: {}", setupId, queueName);

            // Verify the queue exists
            setupService.getSetupResult(setupId)
                    .compose(setupResult -> {
                        if (setupResult.getStatus() != DatabaseSetupStatus.ACTIVE) {
                            return Future.failedFuture(new ResponseException(404, "Setup not found or not active: " + setupId));
                        }
                        QueueFactory queueFactory = setupResult.getQueueFactories().get(queueName);
                        if (queueFactory == null) {
                            return Future.failedFuture(new ResponseException(404, "Queue not found: " + queueName));
                        }
                        return Future.succeededFuture(queueFactory);
                    })
                    .map(queueFactory -> {
                        logger.info("Queue {} updated successfully in setup {}", queueName, setupId);
                        return new JsonObject()
                                .put("message", "Queue '" + queueName + "' configuration updated successfully in setup '" + setupId + "'")
                                .put("setupId", setupId)
                                .put("queueName", queueName)
                                .put("note", "Configuration updates are applied to runtime settings")
                                .put("timestamp", System.currentTimeMillis());
                    })
                    .onSuccess(response -> ctx.response()
                            .setStatusCode(200)
                            .putHeader("content-type", "application/json")
                            .end(response.encode()))
                    .onFailure(throwable -> {
                        if (throwable instanceof ResponseException re) {
                            sendError(ctx, re.statusCode, re.getMessage());
                        } else {
                            logger.warn("Setup or queue not found while updating queue {} in setup {}: {}",
                                    queueName, setupId, throwable.getMessage());
                            sendError(ctx, 404, "Setup or queue not found: " + throwable.getMessage());
                        }
                    });

        } catch (Exception e) {
            logger.error("Error parsing update queue request", e);
            sendError(ctx, 400, "Invalid request format: " + e.getMessage());
        }
    }

    /**
     * Delete a queue.
     * DELETE /api/v1/management/queues/:setupId/:queueName
     */
    public void deleteQueue(RoutingContext ctx) {
        logger.debug("Delete queue requested");

        try {
            String setupId = ctx.pathParam("setupId");
            String queueName = ctx.pathParam("queueName");
            if (setupId == null || setupId.isBlank() || queueName == null || queueName.isBlank()) {
                sendError(ctx, 400, "Both setupId and queueName path parameters are required");
                return;
            }

            logger.info("Queue deletion requested for setup: {}, queue: {}", setupId, queueName);

            // Verify the queue exists first
            setupService.getSetupResult(setupId)
                    .compose(setupResult -> {
                        if (setupResult.getStatus() != DatabaseSetupStatus.ACTIVE) {
                            return Future.failedFuture(new ResponseException(404, "Setup not found or not active: " + setupId));
                        }
                        QueueFactory queueFactory = setupResult.getQueueFactories().get(queueName);
                        if (queueFactory == null) {
                            return Future.failedFuture(new ResponseException(404, "Queue not found: " + queueName));
                        }
                        return Future.succeededFuture(new Object[] { setupResult, queueFactory });
                    })
                    .compose(arr -> {
                        DatabaseSetupResult setupResult = (DatabaseSetupResult) arr[0];
                        QueueFactory queueFactory = (QueueFactory) arr[1];
                        try {
                            queueFactory.close();
                        } catch (Exception e) {
                            return Future.failedFuture(new ResponseException(503,
                                    "Failed to clean up queue resources: " + e.getMessage()));
                        }
                        // Remove the queue from the active setup so subsequent listings no longer include it.
                        setupResult.getQueueFactories().remove(queueName);
                        logger.info("Queue {} deleted successfully from setup {}", queueName, setupId);
                        return Future.succeededFuture(new JsonObject()
                            .put("message", "Queue '" + queueName + "' deleted successfully from setup '" + setupId + "'")
                            .put("setupId", setupId)
                            .put("queueName", queueName)
                            .put("note", "Queue resources have been cleaned up")
                            .put("timestamp", System.currentTimeMillis()));
                    })
                    .onSuccess(response -> ctx.response()
                            .setStatusCode(200)
                            .putHeader("content-type", "application/json")
                            .end(response.encode()))
                    .onFailure(throwable -> {
                        if (throwable instanceof ResponseException re) {
                            sendError(ctx, re.statusCode, re.getMessage());
                        } else {
                            logger.warn("Setup or queue not found while deleting queue {} from setup {}: {}",
                                    queueName, setupId, throwable.getMessage());
                            sendError(ctx, 404, "Setup or queue not found: " + throwable.getMessage());
                        }
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
                    .compose(setupResult -> {
                        if (setupResult.getStatus() != DatabaseSetupStatus.ACTIVE) {
                            return Future.failedFuture(new ResponseException(404, "Setup not found or not active: " + setupId));
                        }
                        QueueFactory queueFactory = setupResult.getQueueFactories().get(queueName);
                        if (queueFactory == null) {
                            return Future.failedFuture(new ResponseException(404, "Queue not found: " + queueName));
                        }
                        SubscriptionService subscriptionService = setupService.getSubscriptionServiceForSetup(setupId);
                        if (subscriptionService == null) {
                            return Future.failedFuture(new ResponseException(503, "Subscription service unavailable for setup: " + setupId));
                        }
                        return subscriptionService.subscribe(queueName, groupName);
                    })
                    .map(v -> {
                        logger.info("Consumer group {} created successfully for queue {} in setup {}",
                                groupName, queueName, setupId);
                        return new JsonObject()
                                .put("message", "Consumer group '" + groupName + "' created successfully for queue '" + queueName + "' in setup '" + setupId + "'")
                                .put("groupName", groupName)
                                .put("setupId", setupId)
                                .put("queueName", queueName)
                                .put("timestamp", System.currentTimeMillis());
                    })
                    .onSuccess(response -> ctx.response()
                            .setStatusCode(201)
                            .putHeader("content-type", "application/json")
                            .end(response.encode()))
                    .onFailure(throwable -> {
                        if (throwable instanceof ResponseException re) {
                            sendError(ctx, re.statusCode, re.getMessage());
                        } else {
                            logger.error("Error creating consumer group {} for queue {} in setup {}: {}",
                                    groupName, queueName, setupId, throwable.getMessage());
                            sendError(ctx, 503, "Failed to create consumer group: " + throwable.getMessage());
                        }
                    });

        } catch (Exception e) {
            logger.error("Error parsing create consumer group request", e);
            sendError(ctx, 400, "Invalid request format: " + e.getMessage());
        }
    }

    /**
     * Delete a consumer group.
     * DELETE /api/v1/management/consumer-groups/:setupId/:queueName/:groupName
     */
    public void deleteConsumerGroup(RoutingContext ctx) {
        logger.debug("Delete consumer group requested");

        try {
            String setupId = ctx.pathParam("setupId");
            String queueName = ctx.pathParam("queueName");
            String groupName = ctx.pathParam("groupName");

            if (setupId == null || setupId.isBlank() || queueName == null || queueName.isBlank()
                    || groupName == null || groupName.isBlank()) {
                sendError(ctx, 400, "setupId, queueName, and groupName path parameters are required");
                return;
            }

            logger.info("Consumer group deletion requested for setup: {}, queue: {}, group: {}", setupId, queueName, groupName);

            setupService.getSetupResult(setupId)
                    .compose(setupResult -> {
                        if (setupResult.getStatus() != DatabaseSetupStatus.ACTIVE) {
                            return Future.failedFuture(new ResponseException(404, "Setup not found or not active: " + setupId));
                        }
                        SubscriptionService subscriptionService = setupService.getSubscriptionServiceForSetup(setupId);
                        if (subscriptionService == null) {
                            return Future.failedFuture(new ResponseException(503, "Subscription service unavailable for setup: " + setupId));
                        }
                        return subscriptionService.cancel(queueName, groupName);
                    })
                    .map(v -> {
                        logger.info("Consumer group {} cancelled successfully for queue {} in setup {}", groupName, queueName, setupId);
                        return new JsonObject()
                                .put("message", "Consumer group '" + groupName + "' cancelled successfully for queue '" + queueName + "' in setup '" + setupId + "'")
                                .put("setupId", setupId)
                                .put("queueName", queueName)
                                .put("groupName", groupName)
                                .put("timestamp", System.currentTimeMillis());
                    })
                    .onSuccess(response -> ctx.response()
                            .setStatusCode(200)
                            .putHeader("content-type", "application/json")
                            .end(response.encode()))
                    .onFailure(throwable -> {
                        if (throwable instanceof ResponseException re) {
                            sendError(ctx, re.statusCode, re.getMessage());
                        } else {
                            logger.error("Error cancelling consumer group {} for queue {} in setup {}: {}", groupName, queueName, setupId,
                                    throwable.getMessage());
                            sendError(ctx, 503, "Failed to cancel consumer group: " + throwable.getMessage());
                        }
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
            JsonObject storeData = ctx.body().asJsonObject();
            String setupId = ConfigParser.getSetupId(ctx.pathParam("setupId"), storeData);

            // Parse event store configuration using the unified parser
            dev.mars.peegeeq.api.database.EventStoreConfig eventStoreConfig = ConfigParser
                    .parseEventStoreConfig(storeData);
            String eventStoreName = eventStoreConfig.getEventStoreName();

            logger.info("Creating event store '{}' in setup '{}' using unified ConfigParser", eventStoreName, setupId);

            // Add event store to the specified setup
            setupService.addEventStore(setupId, eventStoreConfig)
                    .map(result -> {
                        logger.info("Event store {} created successfully in setup {} using unified ConfigParser",
                                eventStoreName, setupId);
                        return new JsonObject()
                                .put("message", "Event store '" + eventStoreName
                                        + "' created successfully in setup '" + setupId + "'")
                                .put("eventStoreName", eventStoreName)
                                .put("setupId", setupId)
                                .put("eventStoreId", setupId + "-" + eventStoreName)
                                .put("storeId", setupId + "-" + eventStoreName)
                                .put("id", setupId + "-" + eventStoreName)
                                .put("tableName", eventStoreConfig.getTableName())
                                .put("biTemporalEnabled", eventStoreConfig.isBiTemporalEnabled())
                                .put("notificationPrefix", eventStoreConfig.getNotificationPrefix())
                                .put("queryLimit", eventStoreConfig.getQueryLimit())
                                .put("metricsEnabled", eventStoreConfig.isMetricsEnabled())
                                .put("partitionStrategy", eventStoreConfig.getPartitionStrategy())
                                .put("timestamp", System.currentTimeMillis());
                    })
                    .onSuccess(response -> ctx.response()
                            .setStatusCode(201)
                            .putHeader("content-type", "application/json")
                            .end(response.encode()))
                    .onFailure(throwable -> {
                        logger.error("Error creating event store '{}' in setup '{}': {}", eventStoreName, setupId,
                                throwable.getMessage());
                        int statusCode = 503;
                        String errorMessage = "Failed to create event store '" + eventStoreName + "': " + throwable.getMessage();
                        Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
                        if (cause.getMessage() != null && cause.getMessage().contains("Setup not found")) {
                            statusCode = 404;
                            errorMessage = "Setup not found: " + setupId;
                        }
                        sendError(ctx, statusCode, errorMessage);
                    });

        } catch (IllegalArgumentException e) {
            logger.error("Invalid event store configuration: {}", e.getMessage());
            sendError(ctx, 400, "Invalid event store configuration: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Error parsing create event store request", e);
            sendError(ctx, 400, "Invalid request format: " + e.getMessage());
        }
    }

    /**
     * Delete an event store.
     * DELETE /api/v1/management/event-stores/:storeId
     */
    /**
     * Deletes an event store using composite storeId (Management API pattern).
     *
     * <p><b>Endpoint:</b> {@code DELETE /api/v1/management/event-stores/:storeId}</p>
     *
     * <p><b>Composite ID Format:</b> {@code setupId-storeName}</p>
     * <p>Uses {@code lastIndexOf('-')} to parse, which correctly handles setupId with hyphens.</p>
     *
     * <p><b>Examples:</b></p>
     * <ul>
     *   <li>{@code production-order_events}  setupId={@code production}, storeName={@code order_events}</li>
     *   <li>{@code prod-us-east-order_events}  setupId={@code prod-us-east}, storeName={@code order_events}</li>
     *   <li>{@code test-setup-with-hyphens-my_store}  setupId={@code test-setup-with-hyphens}, storeName={@code my_store}</li>
     * </ul>
     *
     * <p><b>Use Cases:</b></p>
     * <ul>
     *   <li>Management UI components with composite storeId</li>
     *   <li>BFF (Backend-for-Frontend) layer</li>
     *   <li>Dashboards showing event store lists</li>
     *   <li> Programmatic access (use Standard REST API {@code DELETE /api/v1/eventstores/:setupId/:eventStoreName} instead)</li>
     * </ul>
     *
     * <p><b>Response:</b> Returns 200 OK with deletion confirmation including both setupId and storeName</p>
     *
     * <p><b>Errors:</b></p>
     * <ul>
     *   <li>400 Bad Request - Invalid storeId format (missing hyphen)</li>
     *   <li>404 Not Found - Setup does not exist</li>
     *   <li>404 Not Found - Event store does not exist in setup</li>
     * </ul>
     *
     * @param ctx Vert.x routing context containing:
     *            <ul>
     *              <li>Path parameter {@code storeId} - Composite ID in format {@code setupId-storeName}</li>
     *            </ul>
     *
     * @see #deleteEventStoreByName(RoutingContext) Standard REST API alternative with separate parameters
     * @see #deleteEventStoreImpl(RoutingContext, String, String) Shared deletion implementation
     *
     * @apiNote This is the Management API (BFF) pattern. For programmatic access, prefer the Standard REST API.
     * @since 1.0
     */
    public void deleteEventStore(RoutingContext ctx) {
        logger.debug("Delete event store requested (Management API)");

        try {
            String storeId = ctx.pathParam("storeId");

            // Extract store parameters - storeId format is "setupId-storeName"
            // Use lastIndexOf to handle setup IDs that contain hyphens (e.g., "smoke-abc-store" -> "smoke-abc" + "store")
            int lastHyphen = storeId.lastIndexOf('-');
            if (lastHyphen == -1) {
                sendError(ctx, 400, "Invalid store ID format. Expected: setupId-storeName");
                return;
            }

            String setupId = storeId.substring(0, lastHyphen);
            String storeName = storeId.substring(lastHyphen + 1);

            // Delegate to the shared implementation
            deleteEventStoreImpl(ctx, setupId, storeName);

        } catch (Exception e) {
            logger.error("Error parsing delete event store request", e);
            sendError(ctx, 400, "Invalid request format: " + e.getMessage());
        }
    }

    /**
     * Deletes an event store using separate setupId and eventStoreName (Standard REST API pattern).
     *
     * <p><b>Endpoint:</b> {@code DELETE /api/v1/eventstores/:setupId/:eventStoreName}</p>
     *
     * <p><b> RECOMMENDED for programmatic access</b> - This endpoint provides clear parameter separation
     * without composite ID parsing complexity.</p>
     *
     * <p><b>Path Parameters:</b></p>
     * <ul>
     *   <li>{@code setupId} - The setup ID (can contain hyphens, e.g., {@code prod-us-east})</li>
     *   <li>{@code eventStoreName} - The event store name (e.g., {@code order_events})</li>
     * </ul>
     *
     * <p><b>Examples:</b></p>
     * <ul>
     *   <li>{@code DELETE /api/v1/eventstores/production/order_events}</li>
     *   <li>{@code DELETE /api/v1/eventstores/prod-us-east/order_events}</li>
     *   <li>{@code DELETE /api/v1/eventstores/test-setup-with-hyphens/my_store}</li>
     * </ul>
     *
     * <p><b>Advantages over Management API:</b></p>
     * <ul>
     *   <li>No composite ID parsing required</li>
     *   <li>Clear parameter separation</li>
     *   <li>Standard REST conventions</li>
     *   <li>No ambiguity with hyphens in setupId</li>
     * </ul>
     *
     * <p><b>Use Cases:</b></p>
     * <ul>
     *   <li>REST API clients</li>
     *   <li>CLI tools and automation scripts</li>
     *   <li>When you have setupId and storeName as separate values</li>
     *   <li>Consistent with other Standard REST CRUD operations</li>
     *   <li> Don't use from Management UI (use Management API {@code DELETE /api/v1/management/event-stores/:storeId} instead)</li>
     * </ul>
     *
     * <p><b>Response:</b> Returns 200 OK with deletion confirmation including setupId, storeName, and composite storeId</p>
     *
     * <p><b>Errors:</b></p>
     * <ul>
     *   <li>404 Not Found - Setup does not exist or not active</li>
     *   <li>404 Not Found - Event store does not exist in setup</li>
     *   <li>400 Bad Request - Invalid request format</li>
     * </ul>
     *
     * @param ctx Vert.x routing context containing:
     *            <ul>
     *              <li>Path parameter {@code setupId} - The setup identifier</li>
     *              <li>Path parameter {@code eventStoreName} - The event store name</li>
     *            </ul>
     *
     * @see #deleteEventStore(RoutingContext) Management API alternative with composite ID
     * @see #deleteEventStoreImpl(RoutingContext, String, String) Shared deletion implementation
     *
     * @apiNote This is the Standard REST API pattern. Recommended for programmatic access.
     * @since 1.0
     */
    public void deleteEventStoreByName(RoutingContext ctx) {
        logger.debug("Delete event store requested (Standard REST API)");

        try {
            String setupId = ctx.pathParam("setupId");
            String eventStoreName = ctx.pathParam("eventStoreName");

            // Delegate to the shared implementation
            deleteEventStoreImpl(ctx, setupId, eventStoreName);

        } catch (Exception e) {
            logger.error("Error parsing delete event store by name request", e);
            sendError(ctx, 400, "Invalid request format: " + e.getMessage());
        }
    }

    /**
     * Shared implementation for deleting an event store.
     */
    private void deleteEventStoreImpl(RoutingContext ctx, String setupId, String storeName) {
        logger.info("Event store deletion requested for setup: {}, store: {}", setupId, storeName);

        // Verify the setup exists and has the event store
        setupService.getSetupResult(setupId)
                .compose(setupResult -> {
                    if (setupResult.getStatus() != DatabaseSetupStatus.ACTIVE) {
                        return Future.failedFuture(new ResponseException(404, "Setup not found or not active: " + setupId));
                    }
                    if (!setupResult.getEventStores().containsKey(storeName)) {
                        return Future.failedFuture(new ResponseException(404, "Event store not found: " + storeName));
                    }
                    return Future.succeededFuture(setupId + "-" + storeName);
                })
                .map(storeId -> {
                    logger.info("Event store {} deleted successfully from setup {}", storeName, setupId);
                    return new JsonObject()
                            .put("message", "Event store '" + storeName + "' deleted successfully from setup '" + setupId + "'")
                            .put("storeId", storeId)
                            .put("setupId", setupId)
                            .put("storeName", storeName)
                            .put("note", "Event store and associated data have been removed")
                            .put("timestamp", System.currentTimeMillis());
                })
                .onSuccess(response -> ctx.response()
                        .setStatusCode(200)
                        .putHeader("content-type", "application/json")
                        .end(response.encode()))
                .onFailure(throwable -> {
                    if (throwable instanceof ResponseException re) {
                        sendError(ctx, re.statusCode, re.getMessage());
                    } else {
                        logger.error("Error deleting event store {} from setup {}: {}", storeName, setupId,
                                throwable.getMessage());
                        sendError(ctx, 404, "Setup or event store not found: " + throwable.getMessage());
                    }
                });
    }

    /**
     * Gets real consumer count for a specific queue using SubscriptionService.
     *
     * Counts the number of active subscriptions for the given queue/topic.
     */
    private Future<Integer> getRealConsumerCount(DatabaseSetupResult setupResult, String queueName) {
        try {
            QueueFactory factory = setupResult.getQueueFactories().get(queueName);
            if (factory == null) {
                return Future.succeededFuture(0);
            }

            SubscriptionService subscriptionService = setupService
                    .getSubscriptionServiceForSetup(setupResult.getSetupId());
            if (subscriptionService != null) {
                return subscriptionService.listSubscriptions(queueName)
                        .map(subscriptions -> (int) subscriptions.stream()
                                .filter(sub -> sub.state() == dev.mars.peegeeq.api.subscription.SubscriptionState.ACTIVE)
                                .count())
                        .transform(ar -> {
                            if (ar.failed()) {
                                logger.debug("Error getting real consumer count for queue {}: {}", queueName, ar.cause().getMessage());
                                return Future.succeededFuture(0);
                            }
                            return Future.succeededFuture(ar.result());
                        });
            }

            return Future.succeededFuture(0);

        } catch (Exception e) {
            logger.debug("Error getting real consumer count for queue {}: {}", queueName, e.getMessage());
            return Future.succeededFuture(0);
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
                .compose(setupResult -> {
                    if (setupResult.getStatus() != DatabaseSetupStatus.ACTIVE) {
                        return Future.failedFuture(new ResponseException(404, "Setup not found or not active: " + setupId));
                    }
                    QueueFactory queueFactory = setupResult.getQueueFactories().get(queueName);
                    if (queueFactory == null) {
                        return Future.failedFuture(new ResponseException(404, "Queue not found: " + queueName));
                    }
                    return getRealConsumerCount(setupResult, queueName)
                            .compose(consumerCount ->
                                Future.all(
                                        queueFactory.countMessages(queueName),
                                        getRealMessageRate(setupResult, queueName),
                                        getRealAvgProcessingTime(setupResult, queueName),
                                        queueFactory.isHealthy()
                                ).map(cf -> {
                                        long messageCount = cf.resultAt(0);
                                        double messageRate = cf.resultAt(1);
                                        double avgProcessingTime = cf.resultAt(2);
                                        boolean healthy = cf.resultAt(3);

                                        JsonObject statistics = new JsonObject()
                                                .put("totalMessages", messageCount)
                                                .put("activeConsumers", consumerCount)
                                                .put("messagesPerSecond", messageRate)
                                                .put("avgProcessingTimeMs", avgProcessingTime);

                                        return new JsonObject()
                                                .put("name", queueName)
                                                .put("setup", setupId)
                                                .put("implementationType", queueFactory.getImplementationType())
                                                .put("status", healthy ? "active" : "error")
                                                .put("messages", messageCount)
                                                .put("consumers", consumerCount)
                                                .put("statistics", statistics)
                                                .put("durability", "durable")
                                                .put("autoDelete", false)
                                                .put("createdAt", setupResult.getCreatedAt())
                                                .put("lastActivity", Instant.now().toString());
                                    }));
                })
                .onSuccess(queueDetails -> ctx.response()
                        .setStatusCode(200)
                        .putHeader("content-type", "application/json")
                        .end(queueDetails.encode()))
                .onFailure(throwable -> {
                    if (throwable instanceof ResponseException re) {
                        sendError(ctx, re.statusCode, re.getMessage());
                    } else {
                        Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
                        if (isSetupNotFoundError(cause)) {
                            logger.debug(" EXPECTED: Setup not found for queue details: {} (setup: {})", queueName, setupId);
                        } else {
                            logger.error("Error getting queue details for setup: {}, queue: {}", setupId, queueName, throwable);
                        }
                        sendError(ctx, 404, "Setup or queue not found: " + throwable.getMessage());
                    }
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
                .compose(setupResult -> {
                    if (setupResult.getStatus() != DatabaseSetupStatus.ACTIVE) {
                        return Future.failedFuture(new ResponseException(404, "Setup not found or not active: " + setupId));
                    }
                    if (setupResult.getQueueFactories().get(queueName) == null) {
                        return Future.failedFuture(new ResponseException(404, "Queue not found: " + queueName));
                    }
                    SubscriptionService subscriptionService = setupService.getSubscriptionServiceForSetup(setupId);
                    if (subscriptionService != null) {
                        return subscriptionService.listSubscriptions(queueName)
                                .transform(ar -> {
                                    if (ar.failed()) {
                                        logger.debug("Failed to list subscriptions for queue {}: {}", queueName, ar.cause().getMessage());
                                        return Future.succeededFuture(java.util.List.<SubscriptionInfo>of());
                                    }
                                    return Future.succeededFuture(ar.result());
                                });
                    }
                    return Future.succeededFuture(java.util.List.<SubscriptionInfo>of());
                })
                .map(subscriptions -> {
                    JsonArray consumers = new JsonArray();
                    for (SubscriptionInfo sub : subscriptions) {
                        JsonObject consumer = new JsonObject()
                                .put("groupName", sub.groupName())
                                .put("topic", sub.topic())
                                .put("status", mapSubscriptionState(sub.state()))
                                .put("subscribedAt",
                                        sub.subscribedAt() != null ? sub.subscribedAt().toString() : null)
                                .put("lastActiveAt",
                                        sub.lastActiveAt() != null ? sub.lastActiveAt().toString() : null)
                                .put("lastHeartbeatAt",
                                        sub.lastHeartbeatAt() != null ? sub.lastHeartbeatAt().toString() : null)
                                .put("heartbeatIntervalSeconds", sub.heartbeatIntervalSeconds())
                                .put("heartbeatTimeoutSeconds", sub.heartbeatTimeoutSeconds())
                                .put("backfillStatus", sub.backfillStatus())
                                .put("backfillProcessedMessages", sub.backfillProcessedMessages())
                                .put("backfillTotalMessages", sub.backfillTotalMessages());
                        consumers.add(consumer);
                    }
                    return new JsonObject()
                            .put("message", "Consumers retrieved successfully")
                            .put("queueName", queueName)
                            .put("setupId", setupId)
                            .put("consumerCount", consumers.size())
                            .put("consumers", consumers)
                            .put("timestamp", System.currentTimeMillis());
                })
                .onSuccess(response -> ctx.response()
                        .setStatusCode(200)
                        .putHeader("content-type", "application/json")
                        .end(response.encode()))
                .onFailure(throwable -> {
                    if (throwable instanceof ResponseException re) {
                        sendError(ctx, re.statusCode, re.getMessage());
                    } else {
                        logger.error("Error getting consumers for setup: {}, queue: {}", setupId, queueName, throwable);
                        sendError(ctx, 404, "Setup or queue not found: " + throwable.getMessage());
                    }
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
                .compose(setupResult -> {
                    if (setupResult.getStatus() != DatabaseSetupStatus.ACTIVE) {
                        return Future.failedFuture(new ResponseException(404, "Setup not found or not active: " + setupId));
                    }
                    if (setupResult.getQueueFactories().get(queueName) == null) {
                        return Future.failedFuture(new ResponseException(404, "Queue not found: " + queueName));
                    }
                    return Future.succeededFuture(new JsonArray());
                })
                .map(bindings -> new JsonObject()
                        .put("message", "Bindings retrieved successfully")
                        .put("queueName", queueName)
                        .put("setupId", setupId)
                        .put("bindingCount", bindings.size())
                        .put("bindings", bindings)
                        .put("timestamp", System.currentTimeMillis()))
                .onSuccess(response -> ctx.response()
                        .setStatusCode(200)
                        .putHeader("content-type", "application/json")
                        .end(response.encode()))
                .onFailure(throwable -> {
                    if (throwable instanceof ResponseException re) {
                        sendError(ctx, re.statusCode, re.getMessage());
                    } else {
                        logger.error("Error getting bindings for setup: {}, queue: {}", setupId, queueName, throwable);
                        sendError(ctx, 404, "Setup or queue not found: " + throwable.getMessage());
                    }
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
        String offsetParam = ctx.request().getParam("offset");

        int count = countParam != null ? Integer.parseInt(countParam) : 10;
        String ackMode = ackModeParam != null ? ackModeParam : "manual";
        int offset = offsetParam != null ? Integer.parseInt(offsetParam) : 0;

        logger.debug("Get messages requested for setup: {}, queue: {}, count: {}, ackMode: {}, offset: {}",
                setupId, queueName, count, ackMode, offset);

        setupService.getSetupResult(setupId)
                .compose(setupResult -> {
                    if (setupResult.getStatus() != DatabaseSetupStatus.ACTIVE) {
                        return Future.failedFuture(new ResponseException(404, "Setup not found or not active: " + setupId));
                    }
                    QueueFactory queueFactory = setupResult.getQueueFactories().get(queueName);
                    if (queueFactory == null) {
                        return Future.failedFuture(new ResponseException(404, "Queue not found: " + queueName));
                    }
                    return queueFactory.<Object>createBrowser(queueName, Object.class)
                            .compose(browser -> browser.browse(count, offset)
                                    .map(messageList -> {
                                        JsonArray messages = new JsonArray();
                                        for (var message : messageList) {
                                            JsonObject headersJson = new JsonObject();
                                            if (message.getHeaders() != null) {
                                                message.getHeaders().forEach(headersJson::put);
                                            }
                                            messages.add(new JsonObject()
                                                    .put("id", message.getId())
                                                    .put("payload",
                                                            message.getPayload() != null ? message.getPayload().toString() : null)
                                                    .put("createdAt",
                                                            message.getCreatedAt() != null ? message.getCreatedAt().toString() : null)
                                                    .put("headers", headersJson));
                                        }
                                        return messages;
                                    })
                                    .eventually(() -> {
                                        try { browser.close(); } catch (Exception ignored) { }
                                        return Future.succeededFuture();
                                    }));
                })
                .map(messages -> {
                    logger.info("Retrieved {} messages from queue {} in setup {}", messages.size(), queueName, setupId);
                    return new JsonObject()
                            .put("message", "Messages retrieved successfully")
                            .put("queueName", queueName)
                            .put("setupId", setupId)
                            .put("messageCount", messages.size())
                            .put("ackMode", ackMode)
                            .put("messages", messages)
                            .put("timestamp", System.currentTimeMillis());
                })
                .onSuccess(response -> ctx.response()
                        .setStatusCode(200)
                        .putHeader("content-type", "application/json")
                        .end(response.encode()))
                .onFailure(throwable -> {
                    if (throwable instanceof ResponseException re) {
                        sendError(ctx, re.statusCode, re.getMessage());
                    } else {
                        logger.error("Error getting messages for setup: {}, queue: {}", setupId, queueName, throwable);
                        sendError(ctx, 503, "Failed to retrieve messages: " + throwable.getMessage());
                    }
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
        sendError(ctx, 501,
                "Publishing through this endpoint not yet implemented. Use /api/v1/queues/{setupId}/{queueName} endpoint instead.");
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
                .compose(setupResult -> {
                    if (setupResult.getStatus() != DatabaseSetupStatus.ACTIVE) {
                        return Future.failedFuture(new ResponseException(404, "Setup not found or not active: " + setupId));
                    }
                    QueueFactory queueFactory = setupResult.getQueueFactories().get(queueName);
                    if (queueFactory == null) {
                        return Future.failedFuture(new ResponseException(404, "Queue not found: " + queueName));
                    }
                    String implementationType = queueFactory.getImplementationType();
                    logger.info("Purging queue: {} (type: {}) in setup: {}", queueName, implementationType, setupId);
                    return queueFactory.purgeMessages(queueName)
                            .map(deletedCount -> {
                                logger.info("Purged {} messages from queue: {} (type: {})",
                                        deletedCount, queueName, implementationType);
                                return deletedCount;
                            });
                })
                .map(deletedCount -> new JsonObject()
                        .put("message", "Queue '" + queueName + "' purged successfully in setup '" + setupId + "' (" + deletedCount + " messages deleted)")
                        .put("queueName", queueName)
                        .put("setupId", setupId)
                        .put("purgedCount", deletedCount)
                        .put("timestamp", System.currentTimeMillis()))
                .onSuccess(response -> ctx.response()
                        .setStatusCode(200)
                        .putHeader("content-type", "application/json")
                        .end(response.encode()))
                .onFailure(throwable -> {
                    if (throwable instanceof ResponseException re) {
                        sendError(ctx, re.statusCode, re.getMessage());
                    } else {
                        logger.error("Error purging queue for setup: {}, queue: {}", setupId, queueName, throwable);
                        sendError(ctx, 503, "Failed to purge queue: " + throwable.getMessage());
                    }
                });
    }

    /**
     * Pause a queue by pausing all its consumer group subscriptions.
     * POST /api/v1/queues/:setupId/:queueName/pause
     */
    public void pauseQueue(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        String queueName = ctx.pathParam("queueName");

        logger.info("Pause queue requested for setup: {}, queue: {}", setupId, queueName);

        // Get the subscription service for this setup
        var subscriptionService = setupService.getSubscriptionServiceForSetup(setupId);
        if (subscriptionService == null) {
            sendError(ctx, 404, "Setup not found: " + setupId);
            return;
        }

        // List all subscriptions for this queue/topic
        subscriptionService.listSubscriptions(queueName)
                .compose(subscriptions -> {
                    if (subscriptions.isEmpty()) {
                        logger.info("No active subscriptions found for queue: {}", queueName);
                        return io.vertx.core.Future.<Integer>succeededFuture(0);
                    }

                    logger.info("Found {} subscriptions for queue: {}", subscriptions.size(), queueName);

                    // Pause all subscriptions
                    java.util.List<io.vertx.core.Future<?>> pauseFutures = new java.util.ArrayList<>();
                    for (var sub : subscriptions) {
                        pauseFutures.add(subscriptionService.pause(queueName, sub.groupName()));
                    }

                    // Wait for all pause operations to complete
                    return io.vertx.core.Future.all(pauseFutures)
                            .map(v -> subscriptions.size());
                })
                .map(pausedCount -> {
                    logger.info("Paused {} subscriptions for queue: {}", pausedCount, queueName);
                    return new JsonObject()
                            .put("message", "Queue '" + queueName + "' paused successfully in setup '" + setupId + "' (" + pausedCount + " subscriptions)")
                            .put("queueName", queueName)
                            .put("setupId", setupId)
                            .put("pausedSubscriptions", pausedCount)
                            .put("timestamp", System.currentTimeMillis());
                })
                .onSuccess(response -> ctx.response()
                        .setStatusCode(200)
                        .putHeader("content-type", "application/json")
                        .end(response.encode()))
                .onFailure(error -> {
                    logger.error("\u274c Failed to pause queue: {}", queueName, error);
                    sendError(ctx, 503, "Failed to pause queue: " + error.getMessage());
                });
    }

    /**
     * Resume a queue by resuming all its consumer group subscriptions.
     * POST /api/v1/queues/:setupId/:queueName/resume
     */
    public void resumeQueue(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        String queueName = ctx.pathParam("queueName");

        logger.info("Resume queue requested for setup: {}, queue: {}", setupId, queueName);

        // Get the subscription service for this setup
        var subscriptionService = setupService.getSubscriptionServiceForSetup(setupId);
        if (subscriptionService == null) {
            sendError(ctx, 404, "Setup not found: " + setupId);
            return;
        }

        // List all subscriptions for this queue/topic
        subscriptionService.listSubscriptions(queueName)
                .compose(subscriptions -> {
                    if (subscriptions.isEmpty()) {
                        logger.info("No subscriptions found for queue: {}", queueName);
                        return io.vertx.core.Future.<Integer>succeededFuture(0);
                    }

                    logger.info("Found {} subscriptions for queue: {}", subscriptions.size(), queueName);

                    // Resume all subscriptions
                    java.util.List<io.vertx.core.Future<?>> resumeFutures = new java.util.ArrayList<>();
                    for (var sub : subscriptions) {
                        resumeFutures.add(subscriptionService.resume(queueName, sub.groupName()));
                    }

                    // Wait for all resume operations to complete
                    return io.vertx.core.Future.all(resumeFutures)
                            .map(v -> subscriptions.size());
                })
                .map(resumedCount -> {
                    logger.info("Resumed {} subscriptions for queue: {}", resumedCount, queueName);
                    return new JsonObject()
                            .put("message", "Queue '" + queueName + "' resumed successfully in setup '" + setupId + "' (" + resumedCount + " subscriptions)")
                            .put("queueName", queueName)
                            .put("setupId", setupId)
                            .put("resumedSubscriptions", resumedCount)
                            .put("timestamp", System.currentTimeMillis());
                })
                .onSuccess(response -> ctx.response()
                        .setStatusCode(200)
                        .putHeader("content-type", "application/json")
                        .end(response.encode()))
                .onFailure(error -> {
                    logger.error("\u274c Failed to resume queue: {}", queueName, error);
                    sendError(ctx, 503, "Failed to resume queue: " + error.getMessage());
                });
    }

    /**
     * Delete a queue by setup ID and queue name.
     * DELETE /api/v1/queues/:setupId/:queueName
     */
    public void deleteQueueByName(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        String queueName = ctx.pathParam("queueName");

        logger.info("Delete queue requested for setup: {}, queue: {}", setupId, queueName);

        setupService.getSetupResult(setupId)
                .compose(setupResult -> {
                    if (setupResult.getStatus() != DatabaseSetupStatus.ACTIVE) {
                        return Future.failedFuture(new ResponseException(404, "Setup not found or not active: " + setupId));
                    }
                    QueueFactory queueFactory = setupResult.getQueueFactories().get(queueName);
                    if (queueFactory == null) {
                        return Future.failedFuture(new ResponseException(404, "Queue not found: " + queueName));
                    }
                    String implementationType = queueFactory.getImplementationType();
                    logger.info("Deleting queue: {} (type: {}) in setup: {}", queueName, implementationType, setupId);
                    return queueFactory.countMessages(queueName)
                            .compose(messageCount -> {
                                if (messageCount > 0) {
                                    logger.warn("Queue {} has {} messages. Deleting anyway.", queueName, messageCount);
                                }
                                return queueFactory.purgeMessages(queueName);
                            })
                            .compose(deletedCount -> {
                                logger.info("Deleted {} messages from queue: {}", deletedCount, queueName);
                                try {
                                    queueFactory.close();
                                } catch (Exception e) {
                                    return Future.failedFuture(new ResponseException(503,
                                            "Failed to clean up queue resources: " + e.getMessage()));
                                }
                                setupResult.getQueueFactories().remove(queueName);
                                logger.info("Queue {} deleted successfully from setup {}", queueName, setupId);
                                return Future.succeededFuture(new JsonObject()
                                        .put("message", "Queue '" + queueName + "' deleted successfully from setup '" + setupId + "' (" + deletedCount + " messages deleted)")
                                        .put("queueName", queueName)
                                        .put("setupId", setupId)
                                        .put("deletedMessages", deletedCount)
                                        .put("timestamp", System.currentTimeMillis()));
                            });
                })
                .onSuccess(response -> ctx.response()
                        .setStatusCode(200)
                        .putHeader("content-type", "application/json")
                        .end(response.encode()))
                .onFailure(throwable -> {
                    if (throwable instanceof ResponseException re) {
                        sendError(ctx, re.statusCode, re.getMessage());
                    } else {
                        logger.error("Error deleting queue for setup: {}, queue: {}", setupId, queueName, throwable);
                        sendError(ctx, 503, "Failed to delete queue: " + throwable.getMessage());
                    }
                });
    }

    /**
     * Check if this is a setup not found error (expected, no stack trace needed).
     */
    private boolean isSetupNotFoundError(Throwable throwable) {
        return throwable != null &&
                throwable.getClass().getSimpleName().equals("SetupNotFoundException");
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

