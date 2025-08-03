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

package dev.mars.peegeeq.rest.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.api.setup.*;
import dev.mars.peegeeq.api.database.QueueConfig;
import dev.mars.peegeeq.api.database.EventStoreConfig;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler for database setup REST endpoints.
 * 
 * Handles HTTP requests for creating and managing PeeGeeQ database setups,
 * including template-based database creation and resource management.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-18
 * @version 1.0
 */
public class DatabaseSetupHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(DatabaseSetupHandler.class);
    
    private final DatabaseSetupService setupService;
    private final ObjectMapper objectMapper;
    
    public DatabaseSetupHandler(DatabaseSetupService setupService, ObjectMapper objectMapper) {
        this.setupService = setupService;
        this.objectMapper = objectMapper;
    }
    
    /**
     * Creates a complete database setup with queues and event stores.
     */
    public void createSetup(RoutingContext ctx) {
        try {
            String body = ctx.body().asString();
            DatabaseSetupRequest request = objectMapper.readValue(body, DatabaseSetupRequest.class);
            
            logger.info("Creating database setup: {}", request.getSetupId());
            
            setupService.createCompleteSetup(request)
                    .thenAccept(result -> {
                        try {
                            String responseJson = objectMapper.writeValueAsString(result);
                            ctx.response()
                                    .setStatusCode(200)
                                    .putHeader("content-type", "application/json")
                                    .end(responseJson);
                            logger.info("Database setup created successfully: {}", request.getSetupId());
                        } catch (Exception e) {
                            logger.error("Error serializing setup result", e);
                            sendError(ctx, 500, "Internal server error");
                        }
                    })
                    .exceptionally(throwable -> {
                        logger.error("Error creating database setup: " + request.getSetupId(), throwable);
                        sendError(ctx, 400, "Failed to create database setup: " + throwable.getMessage());
                        return null;
                    });
                    
        } catch (Exception e) {
            // Check if this is an intentional test error (invalid JSON with "invalid" field)
            if (e.getMessage() != null && e.getMessage().contains("Unrecognized field \"invalid\"")) {
                logger.info("🧪 EXPECTED TEST ERROR - Error parsing create setup request (invalid field test) - {}", e.getMessage());
            } else {
                logger.error("Error parsing create setup request", e);
            }
            sendError(ctx, 400, "Invalid request format");
        }
    }
    
    /**
     * Destroys a database setup and cleans up resources.
     */
    public void destroySetup(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        
        logger.info("Destroying database setup: {}", setupId);
        
        setupService.destroySetup(setupId)
                .thenAccept(result -> {
                    ctx.response()
                            .setStatusCode(204)
                            .end();
                    logger.info("Database setup destroyed successfully: {}", setupId);
                })
                .exceptionally(throwable -> {
                    logger.error("Error destroying database setup: " + setupId, throwable);
                    sendError(ctx, 404, "Setup not found or could not be destroyed");
                    return null;
                });
    }
    
    /**
     * Gets the status of a database setup.
     */
    public void getStatus(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        
        setupService.getSetupStatus(setupId)
                .thenAccept(status -> {
                    try {
                        String responseJson = objectMapper.writeValueAsString(status);
                        ctx.response()
                                .setStatusCode(200)
                                .putHeader("content-type", "application/json")
                                .end(responseJson);
                    } catch (Exception e) {
                        logger.error("Error serializing setup status", e);
                        sendError(ctx, 500, "Internal server error");
                    }
                })
                .exceptionally(throwable -> {
                    // Check if this is an intentional test error
                    if (isTestScenario(setupId, throwable)) {
                        logger.info("🧪 EXPECTED TEST ERROR - Error getting setup status: {} - {}",
                                   setupId, throwable.getMessage());
                    } else {
                        logger.error("Error getting setup status: " + setupId, throwable);
                    }
                    sendError(ctx, 404, "Setup not found");
                    return null;
                });
    }
    
    /**
     * Adds a new queue to an existing database setup.
     */
    public void addQueue(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        
        try {
            String body = ctx.body().asString();
            QueueConfig queueConfig = objectMapper.readValue(body, QueueConfig.class);
            
            logger.info("Adding queue {} to setup: {}", queueConfig.getQueueName(), setupId);
            
            setupService.addQueue(setupId, queueConfig)
                    .thenAccept(result -> {
                        ctx.response()
                                .setStatusCode(201)
                                .putHeader("content-type", "application/json")
                                .end("{\"message\":\"Queue added successfully\"}");
                        logger.info("Queue added successfully: {} to setup {}", queueConfig.getQueueName(), setupId);
                    })
                    .exceptionally(throwable -> {
                        logger.error("Error adding queue to setup: " + setupId, throwable);
                        sendError(ctx, 400, "Failed to add queue: " + throwable.getMessage());
                        return null;
                    });
                    
        } catch (Exception e) {
            logger.error("Error parsing add queue request", e);
            sendError(ctx, 400, "Invalid request format");
        }
    }
    
    /**
     * Adds a new event store to an existing database setup.
     */
    public void addEventStore(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        
        try {
            String body = ctx.body().asString();
            EventStoreConfig eventStoreConfig = objectMapper.readValue(body, EventStoreConfig.class);
            
            logger.info("Adding event store {} to setup: {}", eventStoreConfig.getEventStoreName(), setupId);
            
            setupService.addEventStore(setupId, eventStoreConfig)
                    .thenAccept(result -> {
                        ctx.response()
                                .setStatusCode(201)
                                .putHeader("content-type", "application/json")
                                .end("{\"message\":\"Event store added successfully\"}");
                        logger.info("Event store added successfully: {} to setup {}", 
                                eventStoreConfig.getEventStoreName(), setupId);
                    })
                    .exceptionally(throwable -> {
                        logger.error("Error adding event store to setup: " + setupId, throwable);
                        sendError(ctx, 400, "Failed to add event store: " + throwable.getMessage());
                        return null;
                    });
                    
        } catch (Exception e) {
            logger.error("Error parsing add event store request", e);
            sendError(ctx, 400, "Invalid request format");
        }
    }
    
    private void sendError(RoutingContext ctx, int statusCode, String message) {
        JsonObject error = new JsonObject()
                .put("error", message)
                .put("timestamp", System.currentTimeMillis());
        
        ctx.response()
                .setStatusCode(statusCode)
                .putHeader("content-type", "application/json")
                .end(error.encode());
    }

    /**
     * Determines if an error is part of an intentional test scenario
     */
    private boolean isTestScenario(String setupId, Throwable throwable) {
        // Check for test setup IDs
        if (setupId != null && (setupId.equals("non-existent-setup") || setupId.equals("non-existent") || setupId.startsWith("test-"))) {
            return true;
        }

        // Check for test-related error messages
        String message = throwable.getMessage();
        if (message != null && (message.contains("Setup not found: non-existent") ||
                               message.contains("INTENTIONAL TEST FAILURE"))) {
            return true;
        }

        return false;
    }
}
