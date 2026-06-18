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

package dev.mars.peegeeq.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.rest.config.RestServerConfig;
import dev.mars.peegeeq.rest.handlers.DatabaseSetupHandler;
import dev.mars.peegeeq.rest.handlers.DeadLetterHandler;
import dev.mars.peegeeq.rest.handlers.EventStoreHandler;
import dev.mars.peegeeq.rest.handlers.HealthHandler;
import dev.mars.peegeeq.rest.handlers.QueueHandler;
import dev.mars.peegeeq.rest.handlers.SubscriptionHandler;
import dev.mars.peegeeq.rest.handlers.WebSocketHandler;
import dev.mars.peegeeq.rest.handlers.ServerSentEventsHandler;
import dev.mars.peegeeq.rest.handlers.ConsumerAlertHandler;
import dev.mars.peegeeq.rest.handlers.ConsumerGroupHandler;
import dev.mars.peegeeq.rest.handlers.SystemMonitoringHandler;
import io.vertx.core.json.JsonObject;
import dev.mars.peegeeq.rest.handlers.ManagementApiHandler;
import dev.mars.peegeeq.rest.handlers.SubscriptionManagerFactory;
import dev.mars.peegeeq.rest.handlers.WebhookSubscriptionHandler;
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.FileDescriptorMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.binder.system.UptimeMetrics;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Tag;
import java.util.List;
import io.vertx.ext.web.RoutingContext;

import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.Future;
import io.vertx.core.Vertx;

import io.vertx.core.http.HttpServer;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.jackson.DatabindCodec;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CorsHandler;
import io.vertx.ext.web.handler.StaticHandler;
import io.vertx.ext.web.handler.LoggerHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.mars.peegeeq.api.tracing.TraceCtx;
import dev.mars.peegeeq.api.tracing.TraceContextUtil;
import io.vertx.core.Context;

/**
 * Vert.x-based REST server for PeeGeeQ database setup and management.
 *
 * Provides HTTP endpoints for creating and managing database setups,
 * including template-based database creation, queue management, and
 * event store configuration without using Spring framework.
 *
 * This server depends ONLY on peegeeq-api interfaces. The DatabaseSetupService
 * implementation must be injected via the constructor by the deploying
 * application.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-18
 * @version 2.0
 */
public class PeeGeeQRestServer extends AbstractVerticle {

    private static final Logger logger = LoggerFactory.getLogger(PeeGeeQRestServer.class);

    private final RestServerConfig config;
    private final DatabaseSetupService setupService;
    private final PrometheusMeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;

    private HttpServer server;
    private volatile long serverStartTime;

    // Handlers that manage consumers and need explicit cleanup on shutdown
    private WebhookSubscriptionHandler webhookHandler;
    private ServerSentEventsHandler sseHandler;
    private SystemMonitoringHandler monitoringHandler;
    private WebSocketHandler webSocketHandler;

    /**
     * Creates a REST server with injected configuration and setup service.
     *
     * @param config       The validated server configuration (required)
     * @param setupService The DatabaseSetupService implementation (required)
     * @throws NullPointerException if config or setupService is null
     */
    public PeeGeeQRestServer(RestServerConfig config, DatabaseSetupService setupService) {
        this.config = java.util.Objects.requireNonNull(config,
                "RestServerConfig must be provided");
        this.setupService = java.util.Objects.requireNonNull(setupService,
                "DatabaseSetupService must be provided - this module depends only on peegeeq-api interfaces");
        this.objectMapper = createObjectMapper();
        this.meterRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

        // Register standard Micrometer binders
        new ClassLoaderMetrics().bindTo(meterRegistry);
        new JvmMemoryMetrics().bindTo(meterRegistry);
        @SuppressWarnings("resource") // JvmGcMetrics lives for application lifetime - managed by meterRegistry
        var gcMetrics = new JvmGcMetrics();
        gcMetrics.bindTo(meterRegistry);
        new ProcessorMetrics().bindTo(meterRegistry);
        new JvmThreadMetrics().bindTo(meterRegistry);
        new FileDescriptorMetrics().bindTo(meterRegistry);
        new UptimeMetrics().bindTo(meterRegistry);

        // Register application-wide metrics
        meterRegistry.gauge("peegeeq.server.status", 1); // 1 = UP

        // Configure Jackson for Vert.x
        DatabindCodec.mapper().registerModule(new JavaTimeModule());

        // Validate CORS configuration at startup
        validateCorsConfiguration();
    }

    /**
     * Validates CORS configuration at startup to ensure security requirements are
     * met.
     * Logs warnings for potentially insecure configurations.
     */
    private void validateCorsConfiguration() {
        if (config.allowedOrigins() == null || config.allowedOrigins().isEmpty()) {
            throw new IllegalStateException(
                    "CORS configuration is required. allowedOrigins must not be null or empty. " +
                            "This is a security requirement to prevent unauthorized cross-origin access.");
        }

        if (config.allowedOrigins().size() == 1 && "*".equals(config.allowedOrigins().get(0))) {
            logger.warn("  SECURITY WARNING: CORS is configured to allow ALL origins (wildcard '*'). " +
                    "This is acceptable for development/testing but NOT recommended for production. " +
                    "Consider specifying explicit allowed origins in production environments.");
        } else {
            logger.info(" CORS configuration validated: {} allowed origin(s)", config.allowedOrigins().size());
            config.allowedOrigins().forEach(origin -> logger.debug("  - Allowed origin: {}", origin));
        }
    }

    @Override
    public void start(Promise<Void> startPromise) {
        this.serverStartTime = System.currentTimeMillis();
                // Use a startup-only trace for boot logs without polluting request context.
                TraceCtx startupTrace = TraceCtx.createNew();
                try (var scope = TraceContextUtil.mdcScope(startupTrace)) {
                        logger.info("Initializing PeeGeeQ REST Server with new Trace ID: {}", startupTrace.traceId());
                }

        // Create router and start server with composable Future chain
        Future.succeededFuture()
                .compose(v -> {
                    Router router = createRouter();
                    logger.debug("Router created successfully");
                    return Future.succeededFuture(router);
                })
                .compose(router -> {
                    return vertx.createHttpServer()
                            .requestHandler(router)
                            .webSocketHandler(this::routeWebSocket)
                            .listen(config.port());
                })
                .compose(httpServer -> {
                    server = httpServer;
                    logger.info("PeeGeeQ REST API server started on port {}", httpServer.actualPort());
                    return Future.succeededFuture();
                })
                .onSuccess(v -> startPromise.complete())
                .onFailure(cause -> {
                    logger.error("Failed to start PeeGeeQ REST API server", cause);
                    startPromise.fail(cause);
                });
    }

    /**
     * Routes an incoming WebSocket connection by path. Extracted from the startup compose
     * chain so each chain stage reads at a glance — mirrors how REST routes are organised in
     * {@link #createRouter()}. Behaviour is unchanged: {@code /ws/queues/...} streams a queue,
     * {@code /ws/monitoring} streams system stats, {@code /ws/health} returns a one-shot health
     * frame, and any other path is closed.
     */
    private void routeWebSocket(ServerWebSocket webSocket) {
        String path = webSocket.path();
        if (path.startsWith("/ws/queues/")) {
            if (webSocketHandler != null) {
                webSocketHandler.handleQueueStream(webSocket);
            } else {
                closeWithServerError(webSocket, "WebSocketHandler not initialized");
            }
        } else if (path.equals("/ws/monitoring")) {
            // System monitoring WebSocket endpoint (uses shared singleton)
            if (monitoringHandler != null) {
                monitoringHandler.handleWebSocketMonitoring(webSocket);
            } else {
                closeWithServerError(webSocket, "SystemMonitoringHandler not initialized");
            }
        } else if (path.equals("/ws/health")) {
            // WebSocket health check endpoint
            logger.info("WebSocket health check connection from: {}", webSocket.remoteAddress());
            JsonObject healthResponse = new JsonObject()
                    .put("status", "UP")
                    .put("type", "websocket")
                    .put("timestamp", System.currentTimeMillis());
            webSocket.writeTextMessage(healthResponse.encode());
            webSocket.close();
            logger.info("WebSocket health check response sent and connection closed");
        } else {
            logger.info("WebSocket connection to unknown path: {}, closing", path);
            webSocket.close();
        }
    }

    /**
     * Closes a WebSocket with an internal-server-error status (1011) after logging the cause.
     * Used when a required handler was not initialized.
     */
    private void closeWithServerError(ServerWebSocket webSocket, String reason) {
        logger.error(reason);
        webSocket.close((short) 1011, "Internal Server Error");
    }

    /**
     * Returns the actual port the HTTP server is listening on.
     * This is useful when the server was started with port 0 (OS-assigned port).
     * Returns -1 if the server has not started yet.
     */
    public int actualPort() {
        return server != null ? server.actualPort() : -1;
    }

    @Override
    public void stop(Promise<Void> stopPromise) {
        logger.info("Stopping PeeGeeQ REST API server - closing handlers first");

        // Step 1: Close handlers that manage consumers BEFORE closing the HTTP server
        // This ensures consumers are properly stopped before database connections are
        // closed
        try {
            if (webhookHandler != null) {
                logger.debug("Closing WebhookSubscriptionHandler...");
                webhookHandler.close();
                logger.debug("WebhookSubscriptionHandler closed");
            }
            if (sseHandler != null) {
                logger.debug("Closing ServerSentEventsHandler...");
                sseHandler.close();
                logger.debug("ServerSentEventsHandler closed");
            }
            if (monitoringHandler != null) {
                logger.debug("Closing SystemMonitoringHandler...");
                monitoringHandler.close();
                logger.debug("SystemMonitoringHandler closed");
            }
            if (webSocketHandler != null) {
                logger.debug("Closing WebSocketHandler...");
                webSocketHandler.close();
                logger.debug("WebSocketHandler closed");
            }
        } catch (Exception e) {
            logger.warn("Error closing handlers during shutdown: {}", e.getMessage());
        }

        // Step 2: Close the setup service (cancels background timers on all active
        // PeeGeeQManager instances, preventing "Failed to refresh depth cache" warnings
        // after the database becomes unreachable)
        setupService.close()
                .onFailure(e -> logger.warn("Error closing setup service during shutdown: {}", e.getMessage()))
                // Step 3: Close the HTTP server regardless of setupService outcome
                .eventually(() -> {
                    if (server != null) {
                        return server.close()
                                .onSuccess(v -> logger.info("PeeGeeQ REST API server stopped"))
                                .onFailure(cause -> logger.error("Failed to stop PeeGeeQ REST API server", cause));
                    }
                    return Future.succeededFuture();
                })
                .onSuccess(v -> stopPromise.complete())
                .onFailure(stopPromise::fail);
    }

    private Router createRouter() {
        Router router = Router.router(vertx);

        // Add Micrometer metrics aggregation handler
        router.route().handler(this::handleHttpRequestMetrics);

        // Initialize per-request trace context before request logging and business handlers.
        router.route().handler(this::handleRequestTracing);

        // Global handlers
        router.route().handler(LoggerHandler.create());
        router.route().handler(BodyHandler.create());
        router.route().handler(createCorsHandler());

        // Create handlers - all use DatabaseSetupService interface (no casts to
        // implementation types)
        DatabaseSetupHandler setupHandler = new DatabaseSetupHandler(setupService, objectMapper);
        QueueHandler queueHandler = new QueueHandler(setupService, objectMapper);
        EventStoreHandler eventStoreHandler = new EventStoreHandler(setupService, objectMapper, vertx);

        // Create SubscriptionManagerFactory for database-backed subscription
        // persistence
        SubscriptionManagerFactory subscriptionManagerFactory = new SubscriptionManagerFactory(setupService);
        ConsumerGroupHandler consumerGroupHandler = new ConsumerGroupHandler(setupService, objectMapper,
                subscriptionManagerFactory);

        // Store handlers that manage consumers in instance fields for proper shutdown
        // cleanup
        this.sseHandler = new ServerSentEventsHandler(vertx, setupService);
        ManagementApiHandler managementHandler = new ManagementApiHandler(setupService, objectMapper, vertx);
        this.webhookHandler = new WebhookSubscriptionHandler(setupService, objectMapper, vertx);
        DeadLetterHandler deadLetterHandler = new DeadLetterHandler(setupService, objectMapper);
        SubscriptionHandler subscriptionHandler = new SubscriptionHandler(setupService, objectMapper);
        HealthHandler healthHandler = new HealthHandler(setupService, objectMapper);
        ConsumerAlertHandler consumerAlertHandler = new ConsumerAlertHandler(setupService);

        // System monitoring handler for real-time metrics streaming
        this.monitoringHandler = new SystemMonitoringHandler(setupService, vertx, config.monitoring(), meterRegistry);

        // Queue streaming handler
        this.webSocketHandler = new WebSocketHandler(setupService, objectMapper);

        // Health check routes
        router.get("/api/v1/health").handler(ctx -> {
            JsonObject health = new JsonObject()
                    .put("status", "UP")
                    .put("timestamp", System.currentTimeMillis())
                    .put("uptime", System.currentTimeMillis() - serverStartTime)
                    .put("version", "1.0.0")
                    .put("build", "Phase-5-Management-UI");
            ctx.response()
                    .putHeader("content-type", "application/json")
                    .end(health.encode());
        });

        // SSE health check endpoint
        router.get("/api/v1/sse/health").handler(ctx -> {
            logger.info("SSE health check request from: {}", ctx.request().remoteAddress());
            ctx.response()
                    .putHeader("Content-Type", "text/event-stream")
                    .putHeader("Cache-Control", "no-cache")
                    .putHeader("Connection", "keep-alive")
                    .setChunked(true);

            // Send a health event (using default 'message' event type for compatibility)
            String healthEvent = "data: {\"status\":\"UP\",\"type\":\"sse\",\"timestamp\":" + System.currentTimeMillis()
                    + "}\n\n";
            ctx.response().write(healthEvent);

            // Close the connection immediately after sending health event
            ctx.response().end();
            logger.info("SSE health check response sent and connection closed");
        });

        // System monitoring SSE metrics endpoint (versioned + legacy unversioned for backward compat)
        router.get("/api/v1/sse/metrics").handler(monitoringHandler::handleSSEMetrics);
        router.get("/sse/metrics").handler(monitoringHandler::handleSSEMetrics);

        // Database setup routes (legacy /database-setup path - kept for backward compatibility)
        router.post("/api/v1/database-setup/create").handler(setupHandler::createSetup);
        router.delete("/api/v1/database-setup/:setupId").handler(setupHandler::destroySetup);
        router.get("/api/v1/database-setup/:setupId/status").handler(setupHandler::getSetupStatus);
        router.post("/api/v1/database-setup/:setupId/queues").handler(setupHandler::addQueue);
        // REMOVED: Duplicate route - use /api/v1/setups/:setupId/eventstores instead
        // router.post("/api/v1/database-setup/:setupId/eventstores").handler(setupHandler::addEventStore);

        // Database setup routes - RESTful endpoints (Phase 3.1)
        router.get("/api/v1/setups").handler(setupHandler::listSetups);
        router.post("/api/v1/setups").handler(setupHandler::createSetup);
        router.get("/api/v1/setups/:setupId").handler(setupHandler::getSetupDetails);
        router.get("/api/v1/setups/:setupId/status").handler(setupHandler::getSetupStatus);
        router.delete("/api/v1/setups/:setupId").handler(setupHandler::deleteSetup);
        router.get("/api/v1/setups/:setupId/queues").handler(setupHandler::listQueues);
        router.post("/api/v1/setups/:setupId/queues").handler(setupHandler::addQueue);
        router.get("/api/v1/setups/:setupId/eventstores").handler(setupHandler::listEventStores);
        router.post("/api/v1/setups/:setupId/eventstores").handler(setupHandler::addEventStore);

        // Queue routes - Phase 1 & 2: Message Sending
        router.post("/api/v1/queues/:setupId/:queueName/messages").handler(queueHandler::sendMessage);
        router.post("/api/v1/queues/:setupId/:queueName/messages/batch").handler(queueHandler::sendMessages);
        router.get("/api/v1/queues/:setupId/:queueName/stats").handler(queueHandler::getQueueStats);

        // Webhook subscription routes - Push-based message delivery (RECOMMENDED
        // approach)
        // Use these webhook endpoints for scalable, push-based message delivery
        router.post("/api/v1/setups/:setupId/queues/:queueName/webhook-subscriptions")
                .handler(webhookHandler::createSubscription);
        router.get("/api/v1/webhook-subscriptions/:subscriptionId").handler(webhookHandler::getSubscription);
        router.delete("/api/v1/webhook-subscriptions/:subscriptionId").handler(webhookHandler::deleteSubscription);

        // NON-DESTRUCTIVE live message stream (replaces the removed consuming /stream): observes new
        // messages via QueueBrowser.tail() (plain SELECT, no consume) and pushes them over SSE.
        router.get("/api/v1/queues/:setupId/:queueName/messages/stream")
                .handler(sseHandler::handleQueueMessageStream);
        router.get("/api/v1/sse/queues/:setupId").handler(sseHandler::handleQueueUpdates);

        // Queue routes - Phase 4: Consumer Group Management
        router.post("/api/v1/queues/:setupId/:queueName/consumer-groups").handler(consumerGroupHandler::createConsumerGroup);
        router.get("/api/v1/queues/:setupId/:queueName/consumer-groups").handler(consumerGroupHandler::listConsumerGroups);
        router.get("/api/v1/queues/:setupId/:queueName/consumer-groups/:groupName").handler(consumerGroupHandler::getConsumerGroup);
        router.delete("/api/v1/queues/:setupId/:queueName/consumer-groups/:groupName").handler(consumerGroupHandler::deleteConsumerGroup);
        router.post("/api/v1/queues/:setupId/:queueName/consumer-groups/:groupName/members").handler(consumerGroupHandler::joinConsumerGroup);
        router.delete("/api/v1/queues/:setupId/:queueName/consumer-groups/:groupName/members/:memberId").handler(consumerGroupHandler::leaveConsumerGroup);

        // Consumer Group Subscription Options - Phase 3.2: Subscription configuration
        router.post("/api/v1/consumer-groups/:setupId/:queueName/:groupName/subscription").handler(consumerGroupHandler::updateSubscriptionOptions);
        router.get("/api/v1/consumer-groups/:setupId/:queueName/:groupName/subscription").handler(consumerGroupHandler::getSubscriptionOptions);
        router.delete("/api/v1/consumer-groups/:setupId/:queueName/:groupName/subscription").handler(consumerGroupHandler::deleteSubscriptionOptions);

        // Management API routes - Phase 5: Management UI Support
        router.get("/api/v1/management/overview").handler(managementHandler::getSystemOverview);
        router.get("/api/v1/management/queues").handler(managementHandler::getQueues);
        router.post("/api/v1/management/queues").handler(managementHandler::createQueue);
        router.put("/api/v1/management/queues/:setupId/:queueName").handler(managementHandler::updateQueue);
        router.delete("/api/v1/management/queues/:setupId/:queueName").handler(managementHandler::deleteQueue);
        router.get("/api/v1/management/consumer-groups").handler(managementHandler::getConsumerGroups);
        router.post("/api/v1/management/consumer-groups").handler(managementHandler::createConsumerGroup);
        router.delete("/api/v1/management/consumer-groups/:setupId/:queueName/:groupName").handler(managementHandler::deleteConsumerGroup);
        router.post("/api/v1/management/consumer-groups/:setupId/:queueName/:groupName/pause").handler(managementHandler::pauseConsumerGroup);
        router.post("/api/v1/management/consumer-groups/:setupId/:queueName/:groupName/resume").handler(managementHandler::resumeConsumerGroup);
        router.post("/api/v1/management/consumer-groups/:setupId/:queueName/:groupName/backfill").handler(managementHandler::backfillConsumerGroup);
        router.get("/api/v1/management/event-stores").handler(managementHandler::getEventStores);
        router.post("/api/v1/management/event-stores").handler(managementHandler::createEventStore);
        router.delete("/api/v1/management/event-stores/:storeId").handler(managementHandler::deleteEventStore);
        router.get("/api/v1/management/messages").handler(managementHandler::getMessages);
        router.get("/api/v1/management/metrics").handler(managementHandler::getMetrics);

        // Queue Details API routes - Phase 1: Queue Details Page
        router.get("/api/v1/queues/:setupId/:queueName").handler(managementHandler::getQueueDetails);
        router.get("/api/v1/queues/:setupId/:queueName/consumers").handler(managementHandler::getQueueConsumers);
        router.get("/api/v1/queues/:setupId/:queueName/bindings").handler(managementHandler::getQueueBindings);
        router.get("/api/v1/queues/:setupId/:queueName/messages").handler(managementHandler::getQueueMessages);
        router.post("/api/v1/queues/:setupId/:queueName/publish").handler(queueHandler::sendMessage);
        router.post("/api/v1/queues/:setupId/:queueName/purge").handler(managementHandler::purgeQueue);
        router.post("/api/v1/queues/:setupId/:queueName/pause").handler(managementHandler::pauseQueue);
        router.post("/api/v1/queues/:setupId/:queueName/resume").handler(managementHandler::resumeQueue);
        router.delete("/api/v1/queues/:setupId/:queueName").handler(managementHandler::deleteQueueByName);

        // Event store routes
        // NOTE: SSE streaming route MUST come before :eventId routes to avoid "stream"
        // being matched as eventId
        router.get("/api/v1/eventstores/:setupId/:eventStoreName/events/stream").handler(eventStoreHandler::handleEventStream);
        router.post("/api/v1/eventstores/:setupId/:eventStoreName/events").handler(eventStoreHandler::storeEvent);
        router.get("/api/v1/eventstores/:setupId/:eventStoreName/events").handler(eventStoreHandler::queryEvents);
        router.get("/api/v1/eventstores/:setupId/:eventStoreName/events/:eventId").handler(eventStoreHandler::getEvent);
        router.get("/api/v1/eventstores/:setupId/:eventStoreName/events/:eventId/versions").handler(eventStoreHandler::getAllVersions);
        router.get("/api/v1/eventstores/:setupId/:eventStoreName/events/:eventId/at").handler(eventStoreHandler::getAsOfTransactionTime);
        router.post("/api/v1/eventstores/:setupId/:eventStoreName/events/:eventId/corrections").handler(eventStoreHandler::appendCorrection);
        router.get("/api/v1/eventstores/:setupId/:eventStoreName/aggregates").handler(eventStoreHandler::getUniqueAggregates);
        router.post("/api/v1/eventstores/:setupId/:eventStoreName/aggregate-summary/reconcile").handler(eventStoreHandler::reconcileAggregateSummary);
        router.get("/api/v1/eventstores/:setupId/:eventStoreName/stats").handler(eventStoreHandler::getStats);

        // Standard REST DELETE for event stores (matches queue deletion pattern)
        router.delete("/api/v1/eventstores/:setupId/:eventStoreName").handler(managementHandler::deleteEventStoreByName);

        // Dead Letter Queue routes
        router.get("/api/v1/setups/:setupId/deadletter/messages").handler(deadLetterHandler::listMessages);
        router.get("/api/v1/setups/:setupId/deadletter/messages/:messageId").handler(deadLetterHandler::getMessage);
        router.post("/api/v1/setups/:setupId/deadletter/messages/:messageId/reprocess").handler(deadLetterHandler::reprocessMessage);
        router.delete("/api/v1/setups/:setupId/deadletter/messages/:messageId").handler(deadLetterHandler::deleteMessage);
        router.get("/api/v1/setups/:setupId/deadletter/stats").handler(deadLetterHandler::getStats);
        router.post("/api/v1/setups/:setupId/deadletter/cleanup").handler(deadLetterHandler::cleanup);

        // Subscription Lifecycle routes
        router.get("/api/v1/setups/:setupId/subscriptions/:topic").handler(subscriptionHandler::listSubscriptions);
        router.post("/api/v1/setups/:setupId/subscriptions/:topic").handler(subscriptionHandler::createSubscription);
        router.get("/api/v1/setups/:setupId/subscriptions/:topic/:groupName").handler(subscriptionHandler::getSubscription);
        router.post("/api/v1/setups/:setupId/subscriptions/:topic/:groupName/pause").handler(subscriptionHandler::pauseSubscription);
        router.post("/api/v1/setups/:setupId/subscriptions/:topic/:groupName/resume").handler(subscriptionHandler::resumeSubscription);
        router.post("/api/v1/setups/:setupId/subscriptions/:topic/:groupName/heartbeat").handler(subscriptionHandler::updateHeartbeat);
        router.delete("/api/v1/setups/:setupId/subscriptions/:topic/:groupName").handler(subscriptionHandler::cancelSubscription);
        router.delete("/api/v1/setups/:setupId/subscriptions/:topic/:groupName/force-remove").handler(subscriptionHandler::forceRemoveConsumerGroup);

        // Backfill Lifecycle routes
        router.post("/api/v1/setups/:setupId/subscriptions/:topic/:groupName/backfill").handler(subscriptionHandler::startBackfill);
        router.get("/api/v1/setups/:setupId/subscriptions/:topic/:groupName/backfill").handler(subscriptionHandler::getBackfillProgress);
        router.delete("/api/v1/setups/:setupId/subscriptions/:topic/:groupName/backfill").handler(subscriptionHandler::cancelBackfill);

        // Partitioned Consumption routes (OFFSET_WATERMARK mode)
        router.post("/api/v1/setups/:setupId/subscriptions/:topic/:groupName/partitions/join").handler(subscriptionHandler::joinPartitionedGroup);
        router.delete("/api/v1/setups/:setupId/subscriptions/:topic/:groupName/partitions/leave").handler(subscriptionHandler::leavePartitionedGroup);
        router.get("/api/v1/setups/:setupId/subscriptions/:topic/:groupName/partitions").handler(subscriptionHandler::getPartitionAssignments);
        router.post("/api/v1/setups/:setupId/subscriptions/:topic/:groupName/partitions/fetch").handler(subscriptionHandler::fetchPartitioned);
        router.post("/api/v1/setups/:setupId/subscriptions/:topic/:groupName/partitions/commit").handler(subscriptionHandler::commitPartitionedOffset);

        // Consumer Alerting routes - dead consumer detection and blocked message stats
        router.get("/api/v1/setups/:setupId/consumer-alerts/dead").handler(consumerAlertHandler::listDeadSubscriptions);
        router.get("/api/v1/setups/:setupId/consumer-alerts/summary").handler(consumerAlertHandler::getHealthSummary);
        router.get("/api/v1/setups/:setupId/consumer-alerts/blocked").handler(consumerAlertHandler::getBlockedStats);

        // Health API routes - per-setup health endpoints
        router.get("/api/v1/setups/:setupId/health").handler(healthHandler::getOverallHealth);
        router.get("/api/v1/setups/:setupId/health/components").handler(healthHandler::listComponentHealth);
        router.get("/api/v1/setups/:setupId/health/components/:name").handler(healthHandler::getComponentHealth);

        // Static file serving for Management UI - Phase 5
        router.route("/ui/*").handler(StaticHandler.create("webroot").setIndexPage("index.html"));
        router.route("/").handler(ctx -> ctx.response().putHeader("location", "/ui/").setStatusCode(302).end());

        // Health check
        router.get("/health").handler(ctx -> {
            ctx.response()
                    .putHeader("content-type", "application/json")
                    .end("{\"status\":\"UP\",\"service\":\"peegeeq-rest-api\"}");
        });

        // Metrics endpoint
        router.get("/metrics").handler(ctx -> {
            ctx.response()
                    .putHeader("content-type", "text/plain; version=0.0.4; charset=utf-8")
                    .end(meterRegistry.scrape());
        });

        // Global failure handler: catches any unhandled exception from any route handler.
        // Without this, Vert.x logs ERROR "Unhandled exception in router" and returns plain-text 500.
        router.route().failureHandler(ctx -> {
            int status = ctx.statusCode() > 0 ? ctx.statusCode() : 500;
            Throwable failure = ctx.failure();
            String message = failure != null ? failure.getMessage() : "Internal Server Error";

            // Throwable + 5xx = handler bug  ERROR with full stack trace.
            // Throwable + 4xx = expected rejection with cause (e.g. not found)  DEBUG, no stack trace.
            // No throwable + 5xx = explicit ctx.fail(5xx) with no cause  WARN.
            // No throwable + 4xx = explicit ctx.fail(4xx)  DEBUG.
            if (failure != null && status >= 500) {
                logger.error("Unhandled exception in route [{} {}] status={}",
                        ctx.request().method(), ctx.request().path(), status, failure);
            } else if (failure != null) {
                logger.debug("Request rejected [{} {}] status={}: {}",
                        ctx.request().method(), ctx.request().path(), status, failure.getMessage());
            } else if (status >= 500) {
                logger.warn("Request failed [{} {}] status={}",
                        ctx.request().method(), ctx.request().path(), status);
            } else {
                logger.debug("Request rejected [{} {}] status={}",
                        ctx.request().method(), ctx.request().path(), status);
            }

            // Guard: if a handler partially wrote the response before throwing,
            // attempting to write again would throw IllegalStateException.
            if (ctx.response().ended()) {
                return;
            }

            ctx.response()
                    .setStatusCode(status)
                    .putHeader("content-type", "application/json")
                    .end(new JsonObject()
                            .put("error", message)
                            .put("timestamp", System.currentTimeMillis())
                            .encode());
        });

        return router;
    }

    private CorsHandler createCorsHandler() {
        CorsHandler corsHandler = CorsHandler.create();

        // Configure allowed origins from config
        config.allowedOrigins().forEach(corsHandler::addOrigin);

        return corsHandler
                .allowedMethod(io.vertx.core.http.HttpMethod.GET)
                .allowedMethod(io.vertx.core.http.HttpMethod.POST)
                .allowedMethod(io.vertx.core.http.HttpMethod.PUT)
                .allowedMethod(io.vertx.core.http.HttpMethod.DELETE)
                .allowedMethod(io.vertx.core.http.HttpMethod.OPTIONS)
                .allowedHeader("Content-Type")
                                .allowedHeader("traceparent")
                .allowedHeader("Authorization");
    }

        /**
         * Initializes and scopes trace context per request.
         */
        private void handleRequestTracing(RoutingContext ctx) {
                String traceparent = ctx.request().getHeader("traceparent");
                TraceCtx requestTrace = TraceContextUtil.parseOrCreate(traceparent);

                Context vertxContext = Vertx.currentContext();
                if (vertxContext != null) {
                        vertxContext.put(TraceContextUtil.CONTEXT_TRACE_KEY, requestTrace);
                }
                TraceContextUtil.setMDCFromTraceparent(requestTrace.traceparent());

                ctx.addBodyEndHandler(v -> TraceContextUtil.clearTraceMDC());
                ctx.next();
        }

    /**
     * Handler to track HTTP request metrics using Micrometer.
     */
    private void handleHttpRequestMetrics(RoutingContext ctx) {
        long startTime = System.nanoTime();

        ctx.addBodyEndHandler(v -> {
            long duration = System.nanoTime() - startTime;
            String method = ctx.request().method().name();
            String path = ctx.normalizedPath();
            int statusCode = ctx.response().getStatusCode();

            // Record timer for request duration
            Timer.builder("peegeeq.http.requests.duration")
                    .description("Time taken to process HTTP requests")
                    .tags(List.of(
                            Tag.of("method", method),
                            Tag.of("path", path),
                            Tag.of("status", String.valueOf(statusCode))))
                    .register(meterRegistry)
                    .record(duration, java.util.concurrent.TimeUnit.NANOSECONDS);

            // Record counter for total requests
            Counter.builder("peegeeq.http.requests.total")
                    .description("Total number of HTTP requests processed")
                    .tags(List.of(
                            Tag.of("method", method),
                            Tag.of("path", path),
                            Tag.of("status", String.valueOf(statusCode))))
                    .register(meterRegistry)
                    .increment();
        });

        ctx.next();
    }

    private ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }

    /**
     * Gets the port this server is configured to listen on.
     *
     * @return the configured port
     */
    public int getPort() {
        return config.port();
    }
}
