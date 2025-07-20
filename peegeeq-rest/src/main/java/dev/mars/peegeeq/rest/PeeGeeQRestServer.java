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
import dev.mars.peegeeq.db.setup.PeeGeeQDatabaseSetupService;
import dev.mars.peegeeq.rest.handlers.DatabaseSetupHandler;
import dev.mars.peegeeq.rest.handlers.EventStoreHandler;
import dev.mars.peegeeq.rest.handlers.QueueHandler;
import dev.mars.peegeeq.rest.handlers.WebSocketHandler;
import dev.mars.peegeeq.rest.handlers.ServerSentEventsHandler;
import dev.mars.peegeeq.rest.handlers.ConsumerGroupHandler;
import dev.mars.peegeeq.rest.handlers.ManagementApiHandler;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.jackson.DatabindCodec;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CorsHandler;
import io.vertx.ext.web.handler.StaticHandler;
import io.vertx.ext.web.handler.LoggerHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Vert.x-based REST server for PeeGeeQ database setup and management.
 * 
 * Provides HTTP endpoints for creating and managing database setups,
 * including template-based database creation, queue management, and
 * event store configuration without using Spring framework.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-18
 * @version 1.0
 */
public class PeeGeeQRestServer extends AbstractVerticle {
    
    private static final Logger logger = LoggerFactory.getLogger(PeeGeeQRestServer.class);
    
    private final int port;
    private final DatabaseSetupService setupService;
    @SuppressWarnings("unused") // Reserved for future metrics features
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;
    
    private HttpServer server;
    
    public PeeGeeQRestServer() {
        this(8080);
    }
    
    public PeeGeeQRestServer(int port) {
        this.port = port;
        this.setupService = new PeeGeeQDatabaseSetupService();
        this.meterRegistry = new SimpleMeterRegistry();
        this.objectMapper = createObjectMapper();
        
        // Configure Jackson for Vert.x
        DatabindCodec.mapper().registerModule(new JavaTimeModule());
        DatabindCodec.mapper().registerModule(new JavaTimeModule());
    }
    
    @Override
    public void start(Promise<Void> startPromise) {
        Router router = createRouter();
        
        server = vertx.createHttpServer()
                .requestHandler(router)
                .webSocketHandler(webSocket -> {
                    // Handle WebSocket connections for real-time streaming
                    String path = webSocket.path();
                    if (path.startsWith("/ws/queues/")) {
                        WebSocketHandler webSocketHandler = new WebSocketHandler(setupService, objectMapper);
                        webSocketHandler.handleQueueStream(webSocket);
                    } else {
                        webSocket.close();
                    }
                })
                .listen(port, result -> {
                    if (result.succeeded()) {
                        logger.info("PeeGeeQ REST API server started on port {}", port);
                        startPromise.complete();
                    } else {
                        logger.error("Failed to start PeeGeeQ REST API server", result.cause());
                        startPromise.fail(result.cause());
                    }
                });
    }
    
    @Override
    public void stop(Promise<Void> stopPromise) {
        if (server != null) {
            server.close(result -> {
                if (result.succeeded()) {
                    logger.info("PeeGeeQ REST API server stopped");
                    stopPromise.complete();
                } else {
                    logger.error("Failed to stop PeeGeeQ REST API server", result.cause());
                    stopPromise.fail(result.cause());
                }
            });
        } else {
            stopPromise.complete();
        }
    }
    
    private Router createRouter() {
        Router router = Router.router(vertx);
        
        // Global handlers
        router.route().handler(LoggerHandler.create());
        router.route().handler(BodyHandler.create());
        router.route().handler(createCorsHandler());
        
        // Create handlers
        DatabaseSetupHandler setupHandler = new DatabaseSetupHandler(setupService, objectMapper);
        QueueHandler queueHandler = new QueueHandler(setupService, objectMapper);
        EventStoreHandler eventStoreHandler = new EventStoreHandler(setupService, objectMapper);
        ServerSentEventsHandler sseHandler = new ServerSentEventsHandler(setupService, objectMapper, vertx);
        ConsumerGroupHandler consumerGroupHandler = new ConsumerGroupHandler(setupService, objectMapper);
        ManagementApiHandler managementHandler = new ManagementApiHandler(setupService, objectMapper);
        
        // Database setup routes
        router.post("/api/v1/database-setup/create").handler(setupHandler::createSetup);
        router.delete("/api/v1/database-setup/:setupId").handler(setupHandler::destroySetup);
        router.get("/api/v1/database-setup/:setupId/status").handler(setupHandler::getStatus);
        router.post("/api/v1/database-setup/:setupId/queues").handler(setupHandler::addQueue);
        router.post("/api/v1/database-setup/:setupId/eventstores").handler(setupHandler::addEventStore);
        
        // Queue routes - Phase 1 & 2: Message Sending
        router.post("/api/v1/queues/:setupId/:queueName/messages").handler(queueHandler::sendMessage);
        router.post("/api/v1/queues/:setupId/:queueName/messages/batch").handler(queueHandler::sendMessages);
        router.get("/api/v1/queues/:setupId/:queueName/stats").handler(queueHandler::getQueueStats);

        // Queue routes - Phase 3: Message Consumption
        router.get("/api/v1/queues/:setupId/:queueName/messages/next").handler(queueHandler::getNextMessage);
        router.get("/api/v1/queues/:setupId/:queueName/messages").handler(queueHandler::getMessages);
        router.delete("/api/v1/queues/:setupId/:queueName/messages/:messageId").handler(queueHandler::acknowledgeMessage);

        // Queue routes - Phase 4: Real-time Streaming
        router.get("/api/v1/queues/:setupId/:queueName/stream").handler(sseHandler::handleQueueStream);

        // Queue routes - Phase 4: Consumer Group Management
        router.post("/api/v1/queues/:setupId/:queueName/consumer-groups").handler(consumerGroupHandler::createConsumerGroup);
        router.get("/api/v1/queues/:setupId/:queueName/consumer-groups").handler(consumerGroupHandler::listConsumerGroups);
        router.get("/api/v1/queues/:setupId/:queueName/consumer-groups/:groupName").handler(consumerGroupHandler::getConsumerGroup);
        router.delete("/api/v1/queues/:setupId/:queueName/consumer-groups/:groupName").handler(consumerGroupHandler::deleteConsumerGroup);
        router.post("/api/v1/queues/:setupId/:queueName/consumer-groups/:groupName/members").handler(consumerGroupHandler::joinConsumerGroup);
        router.delete("/api/v1/queues/:setupId/:queueName/consumer-groups/:groupName/members/:memberId").handler(consumerGroupHandler::leaveConsumerGroup);

        // Management API routes - Phase 5: Management UI Support
        router.get("/api/v1/health").handler(managementHandler::getHealth);
        router.get("/api/v1/management/overview").handler(managementHandler::getSystemOverview);
        router.get("/api/v1/management/queues").handler(managementHandler::getQueues);
        router.post("/api/v1/management/queues").handler(managementHandler::createQueue);
        router.put("/api/v1/management/queues/:queueId").handler(managementHandler::updateQueue);
        router.delete("/api/v1/management/queues/:queueId").handler(managementHandler::deleteQueue);
        router.get("/api/v1/management/consumer-groups").handler(managementHandler::getConsumerGroups);
        router.post("/api/v1/management/consumer-groups").handler(managementHandler::createConsumerGroup);
        router.delete("/api/v1/management/consumer-groups/:groupId").handler(managementHandler::deleteConsumerGroup);
        router.get("/api/v1/management/event-stores").handler(managementHandler::getEventStores);
        router.post("/api/v1/management/event-stores").handler(managementHandler::createEventStore);
        router.delete("/api/v1/management/event-stores/:storeId").handler(managementHandler::deleteEventStore);
        router.get("/api/v1/management/messages").handler(managementHandler::getMessages);
        router.get("/api/v1/management/metrics").handler(managementHandler::getMetrics);

        // Event store routes
        router.post("/api/v1/eventstores/:setupId/:eventStoreName/events").handler(eventStoreHandler::storeEvent);
        router.get("/api/v1/eventstores/:setupId/:eventStoreName/events").handler(eventStoreHandler::queryEvents);
        router.get("/api/v1/eventstores/:setupId/:eventStoreName/events/:eventId").handler(eventStoreHandler::getEvent);
        router.get("/api/v1/eventstores/:setupId/:eventStoreName/stats").handler(eventStoreHandler::getStats);

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
            // Basic metrics endpoint in Prometheus format
            // In a production implementation, this would:
            // 1. Integrate with Micrometer or Prometheus Java client
            // 2. Collect metrics from all handlers and services
            // 3. Include JVM metrics, HTTP metrics, and business metrics
            // 4. Provide proper Prometheus exposition format

            StringBuilder metrics = new StringBuilder();
            metrics.append("# HELP peegeeq_http_requests_total Total HTTP requests\n");
            metrics.append("# TYPE peegeeq_http_requests_total counter\n");
            metrics.append("peegeeq_http_requests_total 0\n");
            metrics.append("# HELP peegeeq_active_connections Active connections\n");
            metrics.append("# TYPE peegeeq_active_connections gauge\n");
            metrics.append("peegeeq_active_connections 0\n");
            metrics.append("# HELP peegeeq_messages_sent_total Total messages sent\n");
            metrics.append("# TYPE peegeeq_messages_sent_total counter\n");
            metrics.append("peegeeq_messages_sent_total 0\n");

            ctx.response()
                    .putHeader("content-type", "text/plain; version=0.0.4; charset=utf-8")
                    .end(metrics.toString());
        });
        
        return router;
    }
    
    private CorsHandler createCorsHandler() {
        return CorsHandler.create()
                .allowedMethod(io.vertx.core.http.HttpMethod.GET)
                .allowedMethod(io.vertx.core.http.HttpMethod.POST)
                .allowedMethod(io.vertx.core.http.HttpMethod.PUT)
                .allowedMethod(io.vertx.core.http.HttpMethod.DELETE)
                .allowedMethod(io.vertx.core.http.HttpMethod.OPTIONS)
                .allowedHeader("Content-Type")
                .allowedHeader("Authorization");
    }
    
    private ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }
    
    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        
        Vertx vertx = Vertx.vertx();
        vertx.deployVerticle(new PeeGeeQRestServer(port), result -> {
            if (result.succeeded()) {
                logger.info("PeeGeeQ REST API deployed successfully");
            } else {
                logger.error("Failed to deploy PeeGeeQ REST API", result.cause());
                System.exit(1);
            }
        });
    }
}
