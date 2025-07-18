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
        DatabindCodec.prettyMapper().registerModule(new JavaTimeModule());
    }
    
    @Override
    public void start(Promise<Void> startPromise) {
        Router router = createRouter();
        
        server = vertx.createHttpServer()
                .requestHandler(router)
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
        
        // Database setup routes
        router.post("/api/v1/database-setup/create").handler(setupHandler::createSetup);
        router.delete("/api/v1/database-setup/:setupId").handler(setupHandler::destroySetup);
        router.get("/api/v1/database-setup/:setupId/status").handler(setupHandler::getStatus);
        router.post("/api/v1/database-setup/:setupId/queues").handler(setupHandler::addQueue);
        router.post("/api/v1/database-setup/:setupId/eventstores").handler(setupHandler::addEventStore);
        
        // Queue routes
        router.post("/api/v1/queues/:setupId/:queueName/messages").handler(queueHandler::sendMessage);
        router.get("/api/v1/queues/:setupId/:queueName/stats").handler(queueHandler::getQueueStats);
        
        // Event store routes
        router.post("/api/v1/eventstores/:setupId/:eventStoreName/events").handler(eventStoreHandler::storeEvent);
        router.get("/api/v1/eventstores/:setupId/:eventStoreName/events").handler(eventStoreHandler::queryEvents);
        router.get("/api/v1/eventstores/:setupId/:eventStoreName/events/:eventId").handler(eventStoreHandler::getEvent);
        router.get("/api/v1/eventstores/:setupId/:eventStoreName/stats").handler(eventStoreHandler::getStats);
        
        // Health check
        router.get("/health").handler(ctx -> {
            ctx.response()
                    .putHeader("content-type", "application/json")
                    .end("{\"status\":\"UP\",\"service\":\"peegeeq-rest-api\"}");
        });
        
        // Metrics endpoint
        router.get("/metrics").handler(ctx -> {
            // Simple metrics endpoint - could be enhanced with Prometheus format
            ctx.response()
                    .putHeader("content-type", "text/plain")
                    .end("# PeeGeeQ REST API Metrics\n# TODO: Implement Prometheus metrics\n");
        });
        
        return router;
    }
    
    private CorsHandler createCorsHandler() {
        return CorsHandler.create("*")
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
