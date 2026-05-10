package dev.mars.peegeeq.rest.handlers;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.api.setup.DatabaseSetupStatus;
import dev.mars.peegeeq.rest.config.RestServerConfig;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Counter;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Handles real-time system monitoring via WebSocket and SSE endpoints.
 * 
 * <p>
 * <strong>Hexagonal Architecture Compliance:</strong>
 * - Only depends on peegeeq-api interfaces (DatabaseSetupService, QueueFactory)
 * - Does NOT depend on ManagementApiHandler (sibling REST handler)
 * - Follows same data access patterns as other REST handlers
 * - All business logic resides in service layer (peegeeq-runtime)
 * 
 * <p>
 * <strong>Design Pattern:</strong> Per-connection streaming with jitter
 * - Each WebSocket/SSE connection gets its own timer
 * - Random jitter prevents synchronized load spikes
 * - Individual cleanup on disconnect (no resource leaks)
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-12-30
 * @version 1.0
 */
public class SystemMonitoringHandler {

    private static final Logger log = LoggerFactory.getLogger(SystemMonitoringHandler.class);

    // Dependencies (ONLY from peegeeq-api and peegeeq-runtime)
    private final DatabaseSetupService setupService;
    private final Vertx vertx;
    private final RestServerConfig.MonitoringConfig config;
    private final MeterRegistry meterRegistry;
    private final Random random;

    // Connection tracking
    private final Map<String, WebSocketConnection> wsConnections = new ConcurrentHashMap<>();
    private final Map<String, SSEConnection> sseConnections = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> connectionsByIp = new ConcurrentHashMap<>();
    private final AtomicInteger totalConnections = new AtomicInteger(0);
    private final AtomicLong connectionIdCounter = new AtomicLong(0);

    // Metrics caching
    private final AtomicReference<CachedMetrics> cachedMetrics = new AtomicReference<>();

    /**
     * Cached metrics with TTL to reduce GC pressure
     */
    private class CachedMetrics {
        final JsonObject json;
        final long timestamp;

        CachedMetrics(JsonObject json, long timestamp) {
            this.json = json;
            this.timestamp = timestamp;
        }

        boolean isExpired(long now, long cacheTtlMs) {
            return (now - timestamp) > cacheTtlMs;
        }
    }

    /**
     * Constructor with explicit configuration injection.
     * 
     * @param setupService DatabaseSetupService for accessing metrics
     * @param vertx        Vert.x instance for timers
     * @param config       Monitoring configuration (injected, not loaded from
     *                     env/sys)
     */
    public SystemMonitoringHandler(
            DatabaseSetupService setupService,
            Vertx vertx,
            RestServerConfig.MonitoringConfig config,
            MeterRegistry meterRegistry) {
        this.setupService = setupService;
        this.vertx = vertx;
        this.config = config;
        this.meterRegistry = meterRegistry;
        this.random = new Random();

        // Register tagged gauges for connections
        Gauge.builder("peegeeq.monitoring.connections", wsConnections, Map::size)
                .tag("type", "ws")
                .description("Active WebSocket monitoring connections")
                .register(this.meterRegistry);

        Gauge.builder("peegeeq.monitoring.connections", sseConnections, Map::size)
                .tag("type", "sse")
                .description("Active SSE monitoring connections")
                .register(this.meterRegistry);

        Gauge.builder("peegeeq.monitoring.connections.total", totalConnections, AtomicInteger::get)
                .description("Total active monitoring connections (WS + SSE)")
                .register(this.meterRegistry);
    }

    /**
     * Backward-compatible constructor using default configuration
     */
    /**
     * Backward-compatible constructor using default configuration and simple
     * registry
     */
    public SystemMonitoringHandler(DatabaseSetupService setupService, Vertx vertx) {
        this(setupService, vertx, RestServerConfig.MonitoringConfig.defaults(),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    /**
     * Closes the handler and all active connections.
     * Prevents resource leaks on server shutdown.
     */
    public void close() {
        log.info("Closing SystemMonitoringHandler: {} WS, {} SSE connections",
                wsConnections.size(), sseConnections.size());

        // Close all WebSocket connections
        wsConnections.values().forEach(conn -> {
            if (conn.timerId > 0)
                vertx.cancelTimer(conn.timerId);
            if (conn.idleCheckerId > 0)
                vertx.cancelTimer(conn.idleCheckerId);
            try {
                conn.webSocket.close((short) 1001, "Server shutting down");
            } catch (Exception e) {
                // Ignore close errors
            }
        });
        wsConnections.clear();

        // Close all SSE connections
        sseConnections.values().forEach(conn -> {
            if (conn.metricsTimerId > 0)
                vertx.cancelTimer(conn.metricsTimerId);
            if (conn.heartbeatTimerId > 0)
                vertx.cancelTimer(conn.heartbeatTimerId);
            if (conn.idleCheckerId > 0)
                vertx.cancelTimer(conn.idleCheckerId);
            try {
                conn.response.end();
            } catch (Exception e) {
                // Ignore close errors
            }
        });
        sseConnections.clear();

        connectionsByIp.clear();
        totalConnections.set(0);
        log.info("SystemMonitoringHandler closed successfully");
    }

    /**
     * Handle WebSocket monitoring connection at /ws/monitoring
     */
    public void handleWebSocketMonitoring(ServerWebSocket ws) {
        String clientIp = getClientIp(ws.remoteAddress().toString());

        // Check global connection limit
        if (totalConnections.get() >= config.maxConnections()) {
            log.warn("Rejecting WebSocket connection from {}: max connections reached", clientIp);
            ws.close();
            return;
        }

        // Check per-IP connection limit
        AtomicInteger ipConnections = connectionsByIp.computeIfAbsent(clientIp, k -> new AtomicInteger(0));
        if (ipConnections.get() >= config.maxConnectionsPerIp()) {
            log.warn("Rejecting WebSocket connection from {}: per-IP limit reached", clientIp);
            ws.close();
            return;
        }

        // Accept connection
        String connectionId = generateConnectionId();
        WebSocketConnection connection = new WebSocketConnection(connectionId, ws, clientIp);

        log.info("WebSocket monitoring connected: {} from {}", connectionId, clientIp);

        // Track connection
        wsConnections.put(connectionId, connection);
        totalConnections.incrementAndGet();
        ipConnections.incrementAndGet();

        // Send welcome message
        JsonObject welcome = new JsonObject()
                .put("type", "welcome")
                .put("connectionId", connectionId)
                .put("timestamp", System.currentTimeMillis())
                .put("message", "Connected to PeeGeeQ system monitoring");
        ws.writeTextMessage(welcome.encode());

        // Send initial stats update immediately (off event loop)
        sendMetricsToWebSocket(connection);

        // Start per-connection streaming with jitter
        long jitter = config.jitterMs() > 0 ? random.nextInt((int) config.jitterMs()) : 0;
        long intervalMs = connection.updateInterval * 1000L + jitter;

        long timerId = vertx.setPeriodic(intervalMs, id -> {
            sendMetricsToWebSocket(connection);
        });

        connection.timerId = timerId;

        // Handle incoming messages (ping, configure, refresh)
        ws.textMessageHandler(text -> {
            try {
                JsonObject command = new JsonObject(text);
                handleWebSocketCommand(connection, command);
                connection.lastActivity = System.currentTimeMillis();
            } catch (Exception e) {
                log.error("Error handling WebSocket command from {}", connectionId, e);
                JsonObject error = new JsonObject()
                        .put("type", "error")
                        .put("message", "Invalid command format");
                ws.writeTextMessage(error.encode());
            }
        });

        // Handle disconnect
        ws.closeHandler(v -> {
            log.info("WebSocket monitoring disconnected: {}", connectionId);
            cleanupWebSocketConnection(connectionId, clientIp);
        });

        ws.exceptionHandler(err -> {
            log.error("WebSocket error for {}", connectionId, err);
            cleanupWebSocketConnection(connectionId, clientIp);
        });

        // Idle timeout check
        long idleCheckerId = vertx.setPeriodic(60000, id -> {
            long now = System.currentTimeMillis();
            if ((now - connection.lastActivity) > config.idleTimeoutMs()) {
                log.info("Closing idle WebSocket connection: {}", connectionId);
                ws.close();
            }
        });

        connection.idleCheckerId = idleCheckerId;
    }

    /**
     * Handle SSE monitoring connection at /sse/metrics
     */
    public void handleSSEMetrics(RoutingContext ctx) {
        HttpServerRequest request = ctx.request();
        HttpServerResponse response = ctx.response();
        String clientIp = getClientIp(request.remoteAddress().toString());

        // Check global connection limit
        if (totalConnections.get() >= config.maxConnections()) {
            log.warn("Rejecting SSE connection from {}: max connections reached", clientIp);
            ctx.response().setStatusCode(503).end("Max connections reached");
            return;
        }

        // Check per-IP connection limit
        AtomicInteger ipConnections = connectionsByIp.computeIfAbsent(clientIp, k -> new AtomicInteger(0));
        if (ipConnections.get() >= config.maxConnectionsPerIp()) {
            log.warn("Rejecting SSE connection from {}: per-IP limit reached", clientIp);
            ctx.response().setStatusCode(429).end("Per-IP connection limit reached");
            return;
        }

        // Parse query parameters
        int interval = parseInterval(request.getParam("interval"));
        int heartbeat = parseHeartbeat(request.getParam("heartbeat"));

        // Setup SSE response headers
        response.putHeader("Content-Type", "text/event-stream")
                .putHeader("Cache-Control", "no-cache")
                .putHeader("Connection", "keep-alive")
                .setChunked(true);

        // Create connection
        String connectionId = generateConnectionId();
        SSEConnection connection = new SSEConnection(connectionId, response, clientIp, interval);

        log.info("SSE monitoring connected: {} from {} (interval={}s, heartbeat={}s)",
                connectionId, clientIp, interval, heartbeat);

        // Track connection
        sseConnections.put(connectionId, connection);
        totalConnections.incrementAndGet();
        ipConnections.incrementAndGet();

        // Send connection event
        StringBuilder connEvent = new StringBuilder();
        connEvent.append("event: connected\n");
        connEvent.append("data: {\"connectionId\":\"").append(connectionId).append("\"");
        connEvent.append(",\"timestamp\":").append(System.currentTimeMillis()).append("}\n\n");
        response.write(connEvent.toString());

        // Send initial stats update immediately (off event loop)
        sendMetricsToSse(connection, () -> cleanupSSEConnection(connectionId, clientIp));

        // Start per-connection metrics streaming with jitter
        long jitter = config.jitterMs() > 0 ? random.nextInt((int) config.jitterMs()) : 0;
        long intervalMs = interval * 1000L + jitter;

        long metricsTimerId = vertx.setPeriodic(intervalMs, id -> {
            sendMetricsToSse(connection, () -> cleanupSSEConnection(connectionId, clientIp));
        });

        connection.metricsTimerId = metricsTimerId;

        // Start heartbeat timer
        long heartbeatTimerId = vertx.setPeriodic(heartbeat * 1000L, id -> {
            try {
                connection.sendHeartbeat();
            } catch (Exception e) {
                log.error("Error sending SSE heartbeat to {}", connectionId, e);
                cleanupSSEConnection(connectionId, clientIp);
            }
        });

        connection.heartbeatTimerId = heartbeatTimerId;

        // Handle client disconnect
        response.closeHandler(v -> {
            log.info("SSE monitoring disconnected: {}", connectionId);
            cleanupSSEConnection(connectionId, clientIp);
        });

        response.exceptionHandler(err -> {
            log.error("SSE error for {}", connectionId, err);
            cleanupSSEConnection(connectionId, clientIp);
        });

        // Idle timeout check
        long idleCheckerId = vertx.setPeriodic(60000, id -> {
            long now = System.currentTimeMillis();
            if ((now - connection.lastActivity) > config.idleTimeoutMs()) {
                log.info("Closing idle SSE connection: {}", connectionId);
                response.end();
            }
        });

        connection.idleCheckerId = idleCheckerId;
    }

    /**
     * Helper methods
     */

    private Future<JsonObject> getOrUpdateCachedMetrics() {
        long now = System.currentTimeMillis();
        CachedMetrics current = cachedMetrics.get();

        if (current == null || current.isExpired(now, config.cacheTtlMs())) {
            // Start timer for metrics collection overhead
            Timer.Sample sample = Timer.start(meterRegistry);

            return collectMetricsFromServices()
                    .map(metrics -> {
                        CachedMetrics newCache = new CachedMetrics(metrics, now);
                        // Atomic update attempt - if it fails, another thread succeeded, which is fine
                        cachedMetrics.compareAndSet(current, newCache);

                        sample.stop(Timer.builder("peegeeq.monitoring.collection.duration")
                                .description("Time taken to collect and aggregate system metrics")
                                .register(meterRegistry));

                        return newCache.json;
                    })
                    .transform(ar -> {
                        if (ar.failed()) {
                            log.error("Failed to collect monitoring metrics", ar.cause());
                            Counter.builder("peegeeq.monitoring.errors")
                                    .tag("operation", "collection")
                                    .description("Total errors during monitoring operations")
                                    .register(meterRegistry)
                                    .increment();
                            return Future.succeededFuture(
                                    current != null ? current.json : collectMinimalRuntimeMetrics());
                        }
                        return Future.succeededFuture(ar.result());
                    });
        }

        return Future.succeededFuture(current.json);
    }

    private JsonObject collectMinimalRuntimeMetrics() {
        Runtime runtime = Runtime.getRuntime();
        return new JsonObject()
                .put("timestamp", System.currentTimeMillis())
                .put("uptime", ManagementFactory.getRuntimeMXBean().getUptime())
                .put("cpuCores", runtime.availableProcessors())
                .put("memoryUsed", runtime.totalMemory() - runtime.freeMemory())
                .put("memoryTotal", runtime.totalMemory())
                .put("memoryMax", runtime.maxMemory());
    }

    /**
     * Collect metrics from service layer (peegeeq-runtime services).
     * 
     * <p>
     * <strong>Architecture Note:</strong>
     * This method replicates data collection logic from ManagementApiHandler
     * but does NOT call ManagementApiHandler directly. Both handlers independently
     * access the same DatabaseSetupService facade, maintaining clean separation.
     */
    private Future<JsonObject> collectMetricsFromServices() {
        long now = System.currentTimeMillis();
        Runtime runtime = Runtime.getRuntime();
        long uptime = ManagementFactory.getRuntimeMXBean().getUptime();

        return setupService.getAllActiveSetupIds()
                .compose(activeSetupIds -> {
                    // Collect metrics from each setup sequentially to avoid overwhelming the pool
                    Future<JsonObject> accumulator = Future.succeededFuture(new JsonObject()
                            .put("totalQueues", 0)
                            .put("totalConsumerGroups", 0)
                            .put("totalEventStores", 0)
                            .put("totalMessages", 0L)
                            .put("activeConsumerConnections", 0)
                            .put("activeSubscriptions", 0)
                            .put("pausedSubscriptions", 0)
                            .put("deadSubscriptions", 0)
                            .put("cancelledSubscriptions", 0)
                            .put("subscribedTopics", new JsonArray())
                            .put("activeBackfills", new JsonArray())
                            .put("totalSetups", activeSetupIds.size()));

                    for (String setupId : activeSetupIds) {
                        accumulator = accumulator.compose(agg -> collectSetupMetrics(setupId, agg));
                    }

                    return accumulator;
                })
                .map(agg -> {
                    int totalMessages_i = agg.getInteger("totalMessages", 0);
                    long totalMessages = agg.getLong("totalMessages", (long) totalMessages_i);
                    int totalConsumerGroups = agg.getInteger("totalConsumerGroups", 0);
                    double messagesPerSecond = totalMessages > 0 && uptime > 0
                            ? totalMessages / (uptime / 1000.0) : 0.0;
                    int activeConnectionsTotal = totalConnections.get()
                            + agg.getInteger("activeConsumerConnections", 0);
                    JsonArray topicsArray = agg.getJsonArray("subscribedTopics", new JsonArray());

                    return new JsonObject()
                            .put("type", "system_stats")
                            .put("timestamp", now)
                            .put("uptime", getUptimeString(uptime))
                            .put("memoryUsed", runtime.totalMemory() - runtime.freeMemory())
                            .put("memoryTotal", runtime.totalMemory())
                            .put("memoryMax", runtime.maxMemory())
                            .put("cpuCores", runtime.availableProcessors())
                            .put("threadsActive", Thread.activeCount())
                            .put("messagesPerSecond", messagesPerSecond)
                            .put("activeConnections", activeConnectionsTotal)
                            .put("totalMessages", totalMessages)
                            .put("totalQueues", agg.getInteger("totalQueues", 0))
                            .put("totalConsumerGroups", totalConsumerGroups)
                            .put("totalEventStores", agg.getInteger("totalEventStores", 0))
                            .put("totalSetups", agg.getInteger("totalSetups", 0))
                            .put("subscriptionHealth", new JsonObject()
                                    .put("active", agg.getInteger("activeSubscriptions", 0))
                                    .put("paused", agg.getInteger("pausedSubscriptions", 0))
                                    .put("dead", agg.getInteger("deadSubscriptions", 0))
                                    .put("cancelled", agg.getInteger("cancelledSubscriptions", 0))
                                    .put("total", totalConsumerGroups)
                                    .put("topics", topicsArray.size()))
                            .put("activeBackfills", agg.getJsonArray("activeBackfills", new JsonArray()));
                })
                .transform(ar -> {
                    if (ar.failed()) {
                        log.error("Error collecting metrics from services", ar.cause());
                        return Future.succeededFuture(new JsonObject()
                                .put("timestamp", now)
                                .put("uptime", ManagementFactory.getRuntimeMXBean().getUptime())
                                .put("cpuCores", runtime.availableProcessors())
                                .put("memoryUsed", runtime.totalMemory() - runtime.freeMemory())
                                .put("memoryTotal", runtime.totalMemory())
                                .put("memoryMax", runtime.maxMemory())
                                .put("error", "Could not collect full metrics: " + ar.cause().getMessage()));
                    }
                    return Future.succeededFuture(ar.result());
                });
    }

    /**
     * Collect metrics for a single setup and merge into the accumulator.
     */
    private Future<JsonObject> collectSetupMetrics(String setupId, JsonObject agg) {
        return setupService.getSetupResult(setupId)
                .compose(setupResult -> {
                    if (setupResult.getStatus() != DatabaseSetupStatus.ACTIVE) {
                        return Future.succeededFuture(agg);
                    }

                    // Queues and Messages (synchronous no DB call)
                    Map<String, QueueFactory> queueFactories = setupResult.getQueueFactories();
                    int setupQueues = queueFactories.size();
                    long setupMessages = 0;
                    for (Map.Entry<String, QueueFactory> entry : queueFactories.entrySet()) {
                        try {
                            var stats = entry.getValue().getStats(entry.getKey());
                            setupMessages += stats.getPendingMessages();
                        } catch (Exception e) {
                            log.debug("Could not get stats for queue {}", entry.getKey(), e);
                        }
                    }

                    // Event Stores (synchronous)
                    int setupEventStores = setupResult.getEventStores().size();

                    // Update accumulator with synchronous metrics
                    long totalMessagesNow = agg.getLong("totalMessages", 0L) + setupMessages;
                    agg.put("totalQueues", agg.getInteger("totalQueues", 0) + setupQueues);
                    agg.put("totalMessages", totalMessagesNow);
                    agg.put("totalEventStores", agg.getInteger("totalEventStores", 0) + setupEventStores);

                    // Consumer Groups and Connections (async listSubscriptions returns Future)
                    dev.mars.peegeeq.api.subscription.SubscriptionService subService = setupService
                            .getSubscriptionServiceForSetup(setupId);
                    if (subService == null) {
                        return Future.succeededFuture(agg);
                    }

                    // Collect subscription metrics for each topic sequentially
                    Future<JsonObject> topicAccumulator = Future.succeededFuture(agg);
                    for (String topic : queueFactories.keySet()) {
                        topicAccumulator = topicAccumulator.compose(
                                acc -> collectTopicSubscriptionMetrics(subService, topic, acc));
                    }
                    return topicAccumulator;
                })
                .transform(ar -> {
                    if (ar.failed()) {
                        log.debug("Could not process setup {}", setupId, ar.cause());
                        return Future.succeededFuture(agg);
                    }
                    return Future.succeededFuture(ar.result());
                });
    }

    /**
     * Collect subscription metrics for a single topic and merge into the accumulator.
     */
    private Future<JsonObject> collectTopicSubscriptionMetrics(
            dev.mars.peegeeq.api.subscription.SubscriptionService subService,
            String topic, JsonObject agg) {
        return subService.listSubscriptions(topic)
                .map(subs -> {
                    agg.put("totalConsumerGroups",
                            agg.getInteger("totalConsumerGroups", 0) + subs.size());
                    agg.put("activeConsumerConnections",
                            agg.getInteger("activeConsumerConnections", 0) + subs.size());

                    JsonArray subscribedTopics = agg.getJsonArray("subscribedTopics", new JsonArray());
                    JsonArray activeBackfills = agg.getJsonArray("activeBackfills", new JsonArray());

                    for (dev.mars.peegeeq.api.subscription.SubscriptionInfo sub : subs) {
                        if (!subscribedTopics.contains(topic)) {
                            subscribedTopics.add(topic);
                        }
                        switch (sub.state()) {
                            case ACTIVE -> agg.put("activeSubscriptions",
                                    agg.getInteger("activeSubscriptions", 0) + 1);
                            case PAUSED -> agg.put("pausedSubscriptions",
                                    agg.getInteger("pausedSubscriptions", 0) + 1);
                            case DEAD -> agg.put("deadSubscriptions",
                                    agg.getInteger("deadSubscriptions", 0) + 1);
                            case CANCELLED -> agg.put("cancelledSubscriptions",
                                    agg.getInteger("cancelledSubscriptions", 0) + 1);
                        }
                        if ("IN_PROGRESS".equals(sub.backfillStatus())) {
                            long processed = sub.backfillProcessedMessages() != null
                                    ? sub.backfillProcessedMessages() : 0;
                            long total = sub.backfillTotalMessages() != null
                                    ? sub.backfillTotalMessages() : 0;
                            double percentComplete = total > 0
                                    ? (processed * 100.0) / total : 0.0;
                            activeBackfills.add(new JsonObject()
                                    .put("topic", sub.topic())
                                    .put("groupName", sub.groupName())
                                    .put("processedMessages", processed)
                                    .put("totalMessages", total)
                                    .put("percentComplete",
                                            Math.round(percentComplete * 10.0) / 10.0));
                        }
                    }
                    agg.put("subscribedTopics", subscribedTopics);
                    agg.put("activeBackfills", activeBackfills);
                    return agg;
                })
                .transform(ar -> {
                    if (ar.failed()) {
                        log.debug("Could not list subscriptions for topic {}", topic, ar.cause());
                        return Future.succeededFuture(agg);
                    }
                    return Future.succeededFuture(ar.result());
                });
    }

    private void handleWebSocketCommand(WebSocketConnection connection, JsonObject command) {
        String type = command.getString("type");

        switch (type) {
            case "ping":
                handlePing(connection, command);
                break;
            case "configure":
                handleConfigure(connection, command);
                break;
            case "refresh":
                sendMetricsToWebSocket(connection);
                break;
            default:
                JsonObject error = new JsonObject()
                        .put("type", "error")
                        .put("message", "Unknown command type: " + type);
                connection.webSocket.writeTextMessage(error.encode());
        }
    }

    private void handlePing(WebSocketConnection connection, JsonObject command) {
        JsonObject pong = new JsonObject()
                .put("type", "pong")
                .put("timestamp", System.currentTimeMillis());

        if (command.containsKey("id")) {
            pong.put("id", command.getValue("id"));
        }

        connection.webSocket.writeTextMessage(pong.encode());
    }

    private void handleConfigure(WebSocketConnection connection, JsonObject command) {
        int interval = command.getInteger("interval", config.defaultIntervalSeconds());

        if (interval < config.minIntervalSeconds() || interval > config.maxIntervalSeconds()) {
            JsonObject error = new JsonObject()
                    .put("type", "error")
                    .put("message", "Invalid interval: must be between " + config.minIntervalSeconds() + " and "
                            + config.maxIntervalSeconds());
            connection.webSocket.writeTextMessage(error.encode());
            return;
        }

        // Cancel old timer
        if (connection.timerId > 0) {
            vertx.cancelTimer(connection.timerId);
        }

        // Start new timer with jitter
        long jitter = config.jitterMs() > 0 ? random.nextInt((int) config.jitterMs()) : 0;
        long intervalMs = interval * 1000L + jitter;

        long timerId = vertx.setPeriodic(intervalMs, id -> {
            sendMetricsToWebSocket(connection);
        });

        connection.timerId = timerId;
        connection.updateInterval = interval;

        // Send confirmation
        JsonObject confirmation = new JsonObject()
                .put("type", "configured")
                .put("interval", interval)
                .put("timestamp", System.currentTimeMillis());
        connection.webSocket.writeTextMessage(confirmation.encode());
    }

    private void cleanupWebSocketConnection(String connectionId, String clientIp) {
        WebSocketConnection connection = wsConnections.remove(connectionId);
        if (connection != null) {
            if (connection.timerId > 0)
                vertx.cancelTimer(connection.timerId);
            if (connection.idleCheckerId > 0)
                vertx.cancelTimer(connection.idleCheckerId);
        }

        totalConnections.decrementAndGet();

        AtomicInteger ipCount = connectionsByIp.get(clientIp);
        if (ipCount != null) {
            ipCount.decrementAndGet();
            if (ipCount.get() <= 0) {
                connectionsByIp.remove(clientIp);
            }
        }
    }

    private void cleanupSSEConnection(String connectionId, String clientIp) {
        SSEConnection connection = sseConnections.remove(connectionId);
        if (connection != null) {
            if (connection.metricsTimerId > 0)
                vertx.cancelTimer(connection.metricsTimerId);
            if (connection.heartbeatTimerId > 0)
                vertx.cancelTimer(connection.heartbeatTimerId);
            if (connection.idleCheckerId > 0)
                vertx.cancelTimer(connection.idleCheckerId);
        }

        totalConnections.decrementAndGet();

        AtomicInteger ipCount = connectionsByIp.get(clientIp);
        if (ipCount != null) {
            ipCount.decrementAndGet();
            if (ipCount.get() <= 0) {
                connectionsByIp.remove(clientIp);
            }
        }
    }

    private void sendMetricsToWebSocket(WebSocketConnection connection) {
        collectMetricsOnWorker(
                metrics -> {
                    connection.sendMetrics(metrics);
                    connection.lastActivity = System.currentTimeMillis();
                },
                error -> log.error("Error sending WebSocket metrics to {}", connection.connectionId, error));
    }

    private void sendMetricsToSse(SSEConnection connection, Runnable onError) {
        collectMetricsOnWorker(
                metrics -> {
                    connection.sendMetricsEvent(metrics);
                    connection.lastActivity = System.currentTimeMillis();
                },
                error -> {
                    log.error("Error sending SSE metrics to {}", connection.connectionId, error);
                    if (onError != null) {
                        onError.run();
                    }
                });
    }

    private void collectMetricsOnWorker(java.util.function.Consumer<JsonObject> onSuccess,
            java.util.function.Consumer<Throwable> onFailure) {
        getOrUpdateCachedMetrics()
            .onSuccess(result -> onSuccess.accept(result))
            .onFailure(error -> onFailure.accept(error));
    }

    private int parseInterval(String param) {
        if (param == null)
            return config.defaultIntervalSeconds();
        try {
            int interval = Integer.parseInt(param);
            return Math.max(config.minIntervalSeconds(), Math.min(config.maxIntervalSeconds(), interval));
        } catch (NumberFormatException e) {
            return config.defaultIntervalSeconds();
        }
    }

    private int parseHeartbeat(String param) {
        if (param == null)
            return 30;
        try {
            return Math.max(10, Integer.parseInt(param));
        } catch (NumberFormatException e) {
            return 30;
        }
    }

    private String generateConnectionId() {
        return "monitoring-" + connectionIdCounter.incrementAndGet();
    }

    private String getUptimeString(long uptimeMs) {
        long days = uptimeMs / (24 * 60 * 60 * 1000);
        long hours = (uptimeMs % (24 * 60 * 60 * 1000)) / (60 * 60 * 1000);
        long minutes = (uptimeMs % (60 * 60 * 1000)) / (60 * 1000);
        long seconds = (uptimeMs % (60 * 1000)) / 1000;

        if (days > 0) {
            return String.format("%dd %dh %dm", days, hours, minutes);
        } else if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes, seconds);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds);
        } else {
            return String.format("%ds", seconds);
        }
    }

    private String getClientIp(String remoteAddress) {
        // Extract IP from "host:port" format
        int colonIndex = remoteAddress.lastIndexOf(':');
        return colonIndex > 0 ? remoteAddress.substring(0, colonIndex) : remoteAddress;
    }

    /**
     * Connection wrapper classes (non-static to access config)
     */

    private class WebSocketConnection {
        final String connectionId;
        final ServerWebSocket webSocket;
        final String clientIp;
        int updateInterval = config.defaultIntervalSeconds();
        long timerId = -1;
        long idleCheckerId = -1;
        long lastActivity = System.currentTimeMillis();

        WebSocketConnection(String connectionId, ServerWebSocket webSocket, String clientIp) {
            this.connectionId = connectionId;
            this.webSocket = webSocket;
            this.clientIp = clientIp;
        }

        void sendMetrics(JsonObject metrics) {
            JsonObject message = new JsonObject()
                    .put("type", "system_stats")
                    .put("data", metrics)
                    .put("timestamp", System.currentTimeMillis());
            webSocket.writeTextMessage(message.encode());
        }
    }

    private class SSEConnection {
        final String connectionId;
        final HttpServerResponse response;
        final String clientIp;
        int updateInterval;
        long metricsTimerId = -1;
        long heartbeatTimerId = -1;
        long idleCheckerId = -1;
        long lastActivity = System.currentTimeMillis();
        long eventId = 0;

        SSEConnection(String connectionId, HttpServerResponse response, String clientIp, int interval) {
            this.connectionId = connectionId;
            this.response = response;
            this.clientIp = clientIp;
            this.updateInterval = interval;
        }

        void sendMetricsEvent(JsonObject metrics) {
            eventId++;
            StringBuilder sse = new StringBuilder();
            sse.append("event: metrics\n");
            sse.append("id: ").append(eventId).append("\n");
            sse.append("data: ").append(metrics.encode()).append("\n\n");
            response.write(sse.toString());
        }

        void sendHeartbeat() {
            StringBuilder sse = new StringBuilder();
            sse.append("event: heartbeat\n");
            sse.append("data: {\"timestamp\":").append(System.currentTimeMillis()).append("}\n\n");
            response.write(sse.toString());
        }
    }
}

