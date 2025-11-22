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
import dev.mars.peegeeq.api.database.DatabaseConfig;
import dev.mars.peegeeq.api.database.QueueConfig;
import dev.mars.peegeeq.api.database.EventStoreConfig;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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
    
    public DatabaseSetupHandler(DatabaseSetupService setupService, ObjectMapper objectMapper) {
        this.setupService = setupService;
        // objectMapper kept as parameter for potential future use and backward compatibility
    }
    
    /**
     * Creates a complete database setup with queues and event stores.
     * POST /api/v1/setups
     */
    public void createSetup(RoutingContext ctx) {
        try {
            JsonObject body = ctx.body().asJsonObject();
            
            // Parse request with comprehensive parameter support
            DatabaseSetupRequest request = parseSetupRequest(body);
            
            logger.info("Creating database setup: {} with {} queues and {} event stores",
                       request.getSetupId(),
                       request.getQueues().size(),
                       request.getEventStores().size());
            
            setupService.createCompleteSetup(request)
                    .thenAccept(result -> {
                        JsonObject response = new JsonObject()
                            .put("setupId", result.getSetupId())
                            .put("status", result.getStatus().name())
                            .put("queueCount", result.getQueueFactories().size())
                            .put("eventStoreCount", result.getEventStores().size())
                            .put("message", "Database setup created successfully");
                        
                        ctx.response()
                                .setStatusCode(201)
                                .putHeader("Content-Type", "application/json")
                                .end(response.encode());
                        
                        logger.info("Database setup created successfully: {}", request.getSetupId());
                    })
                    .exceptionally(throwable -> {
                        // Check if this is an expected database creation conflict (no stack trace)
                        Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
                        if (isDatabaseCreationConflictError(cause)) {
                            logger.debug("🚫 EXPECTED: Database creation conflict for setup: {} (concurrent test scenario)",
                                       request.getSetupId());
                        } else {
                            logger.error("Error creating database setup: " + request.getSetupId(), throwable);
                        }
                        
                        int statusCode = 500;
                        String errorMessage = "Failed to create setup: " + throwable.getMessage();
                        
                        if (cause.getMessage() != null) {
                            if (cause.getMessage().contains("already exists")) {
                                statusCode = 409; // Conflict
                                errorMessage = "Setup already exists: " + request.getSetupId();
                            } else if (cause.getMessage().contains("invalid")) {
                                statusCode = 400; // Bad Request
                            }
                        }
                        
                        sendError(ctx, statusCode, errorMessage);
                        return null;
                    });
                    
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid request for database setup: {}", e.getMessage());
            sendError(ctx, 400, "Invalid request: " + e.getMessage());
        } catch (Exception e) {
            // Check if this is an intentional test error (invalid JSON with "invalid" field)
            if (e.getMessage() != null && e.getMessage().contains("Unrecognized field \"invalid\"")) {
                logger.info("🧪 EXPECTED TEST ERROR - Error parsing create setup request (invalid field test) - {}", e.getMessage());
            } else {
                logger.error("Error parsing create setup request", e);
            }
            sendError(ctx, 500, "Error processing request: " + e.getMessage());
        }
    }
    
    /**
     * Deletes a database setup and cleans up resources.
     * DELETE /api/v1/setups/:setupId
     */
    public void deleteSetup(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        
        logger.info("Deleting database setup: {}", setupId);
        
        setupService.destroySetup(setupId)
                .thenAccept(v -> {
                    ctx.response()
                            .setStatusCode(204) // No Content
                            .end();
                    
                    logger.info("Database setup deleted successfully: {}", setupId);
                })
                .exceptionally(err -> {
                    logger.error("Failed to delete database setup: {}", setupId, err);
                    sendError(ctx, 500, "Failed to delete setup: " + err.getMessage());
                    return null;
                });
    }
    
    /**
     * Legacy method name for backward compatibility.
     */
    public void destroySetup(RoutingContext ctx) {
        deleteSetup(ctx);
    }
    
    /**
     * Gets the status of a database setup.
     * GET /api/v1/setups/:setupId/status
     */
    public void getSetupStatus(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        
        logger.debug("Getting status for setup: {}", setupId);
        
        setupService.getSetupStatus(setupId)
            .thenAccept(status -> {
                JsonObject response = new JsonObject()
                    .put("setupId", setupId)
                    .put("status", status.name());
                
                ctx.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end(response.encode());
            })
            .exceptionally(err -> {
                logger.debug("Setup not found: {}", setupId);
                sendError(ctx, 404, "Setup not found: " + setupId);
                return null;
            });
    }
    
    /**
     * Gets complete details of a database setup.
     * GET /api/v1/setups/:setupId
     */
    public void getSetupDetails(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        
        logger.debug("Getting details for setup: {}", setupId);
        
        setupService.getSetupResult(setupId)
            .thenAccept(result -> {
                JsonObject response = new JsonObject()
                    .put("setupId", result.getSetupId())
                    .put("status", result.getStatus().name())
                    .put("queueFactories", new JsonArray(new ArrayList<>(result.getQueueFactories().keySet())))
                    .put("eventStores", new JsonArray(new ArrayList<>(result.getEventStores().keySet())));
                
                ctx.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end(response.encode());
            })
            .exceptionally(err -> {
                // Check if this is an expected setup not found error (no stack trace)
                Throwable cause = err.getCause() != null ? err.getCause() : err;
                if (isSetupNotFoundError(cause)) {
                    logger.debug("🚫 EXPECTED: Setup not found: {}", setupId);
                } else if (isTestScenario(setupId, err)) {
                    logger.info("🧪 EXPECTED TEST ERROR - Error getting setup details: {} - {}",
                               setupId, err.getMessage());
                } else {
                    logger.error("Error getting setup details: " + setupId, err);
                }
                sendError(ctx, 404, "Setup not found: " + setupId);
                return null;
            });
    }
    
    /**
     * Adds a new queue to an existing database setup.
     * POST /api/v1/setups/:setupId/queues
     */
    public void addQueue(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        
        try {
            JsonObject body = ctx.body().asJsonObject();
            QueueConfig queueConfig = parseQueueConfig(body);
            
            logger.info("Adding queue {} to setup: {}", queueConfig.getQueueName(), setupId);
            
            setupService.addQueue(setupId, queueConfig)
                    .thenAccept(v -> {
                        JsonObject response = new JsonObject()
                            .put("message", "Queue added successfully")
                            .put("queueName", queueConfig.getQueueName())
                            .put("setupId", setupId);
                        
                        ctx.response()
                                .setStatusCode(201)
                                .putHeader("Content-Type", "application/json")
                                .end(response.encode());
                        
                        logger.info("Queue added successfully: {} to setup {}", queueConfig.getQueueName(), setupId);
                    })
                    .exceptionally(err -> {
                        logger.error("Failed to add queue to setup: {}", setupId, err);
                        
                        int statusCode = 500;
                        String errorMessage = "Failed to add queue: " + err.getMessage();
                        
                        Throwable cause = err.getCause() != null ? err.getCause() : err;
                        if (cause.getMessage() != null && cause.getMessage().contains("Setup not found")) {
                            statusCode = 404;
                            errorMessage = "Setup not found: " + setupId;
                        }
                        
                        sendError(ctx, statusCode, errorMessage);
                        return null;
                    });
                    
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid queue configuration: {}", e.getMessage());
            sendError(ctx, 400, "Invalid request: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Error parsing add queue request", e);
            sendError(ctx, 400, "Invalid request format: " + e.getMessage());
        }
    }
    
    /**
     * Adds a new event store to an existing database setup.
     * POST /api/v1/setups/:setupId/eventstores
     */
    public void addEventStore(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        
        try {
            JsonObject body = ctx.body().asJsonObject();
            EventStoreConfig eventStoreConfig = parseEventStoreConfig(body);
            
            logger.info("Adding event store {} to setup: {}", eventStoreConfig.getEventStoreName(), setupId);
            
            setupService.addEventStore(setupId, eventStoreConfig)
                    .thenAccept(v -> {
                        JsonObject response = new JsonObject()
                            .put("message", "Event store added successfully")
                            .put("eventStoreName", eventStoreConfig.getEventStoreName())
                            .put("setupId", setupId);
                        
                        ctx.response()
                                .setStatusCode(201)
                                .putHeader("Content-Type", "application/json")
                                .end(response.encode());
                        
                        logger.info("Event store added successfully: {} to setup {}", 
                                eventStoreConfig.getEventStoreName(), setupId);
                    })
                    .exceptionally(err -> {
                        logger.error("Failed to add event store to setup: {}", setupId, err);
                        
                        int statusCode = 500;
                        String errorMessage = "Failed to add event store: " + err.getMessage();
                        
                        Throwable cause = err.getCause() != null ? err.getCause() : err;
                        if (cause.getMessage() != null && cause.getMessage().contains("Setup not found")) {
                            statusCode = 404;
                            errorMessage = "Setup not found: " + setupId;
                        }
                        
                        sendError(ctx, statusCode, errorMessage);
                        return null;
                    });
                    
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid event store configuration: {}", e.getMessage());
            sendError(ctx, 400, "Invalid request: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Error parsing add event store request", e);
            sendError(ctx, 400, "Invalid request format: " + e.getMessage());
        }
    }
    
    /**
     * Lists all active setup IDs.
     * GET /api/v1/setups
     */
    public void listSetups(RoutingContext ctx) {
        logger.debug("Listing all active setups");
        
        setupService.getAllActiveSetupIds()
            .thenAccept(setupIds -> {
                JsonObject response = new JsonObject()
                    .put("count", setupIds.size())
                    .put("setupIds", new JsonArray(new ArrayList<>(setupIds)));
                
                ctx.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end(response.encode());
            })
            .exceptionally(err -> {
                logger.error("Failed to list setups", err);
                sendError(ctx, 500, "Failed to list setups: " + err.getMessage());
                return null;
            });
    }
    
    /**
     * Parses a DatabaseSetupRequest from JSON.
     */
    private DatabaseSetupRequest parseSetupRequest(JsonObject json) {
        String setupId = json.getString("setupId");
        if (setupId == null || setupId.trim().isEmpty()) {
            throw new IllegalArgumentException("setupId is required");
        }
        
        // Parse database configuration
        JsonObject dbConfigJson = json.getJsonObject("databaseConfig");
        if (dbConfigJson == null) {
            throw new IllegalArgumentException("databaseConfig is required");
        }
        DatabaseConfig dbConfig = parseDatabaseConfig(dbConfigJson);
        
        // Parse queues
        List<QueueConfig> queues = new ArrayList<>();
        JsonArray queuesArray = json.getJsonArray("queues");
        if (queuesArray != null) {
            for (int i = 0; i < queuesArray.size(); i++) {
                queues.add(parseQueueConfig(queuesArray.getJsonObject(i)));
            }
        }
        
        // Parse event stores
        List<EventStoreConfig> eventStores = new ArrayList<>();
        JsonArray eventStoresArray = json.getJsonArray("eventStores");
        if (eventStoresArray != null) {
            for (int i = 0; i < eventStoresArray.size(); i++) {
                eventStores.add(parseEventStoreConfig(eventStoresArray.getJsonObject(i)));
            }
        }
        
        return new DatabaseSetupRequest(setupId, dbConfig, queues, eventStores, null);
    }
    
    /**
     * Parses database configuration from JSON.
     */
    private DatabaseConfig parseDatabaseConfig(JsonObject json) {
        return new DatabaseConfig.Builder()
            .host(json.getString("host", "localhost"))
            .port(json.getInteger("port", 5432))
            .databaseName(json.getString("databaseName"))
            .username(json.getString("username", "postgres"))
            .password(json.getString("password", "postgres"))
            .schema(json.getString("schema", "peegeeq"))
            .templateDatabase(json.getString("templateDatabase", "template0"))
            .encoding(json.getString("encoding", "UTF8"))
            .build();
    }
    
    /**
     * Parses queue configuration from JSON with all parameters.
     */
    private QueueConfig parseQueueConfig(JsonObject json) {
        String queueName = json.getString("queueName");
        if (queueName == null || queueName.trim().isEmpty()) {
            throw new IllegalArgumentException("queueName is required");
        }
        
        QueueConfig.Builder builder = new QueueConfig.Builder()
            .queueName(queueName);
        
        // Visibility timeout (seconds or ISO-8601 duration)
        if (json.containsKey("visibilityTimeoutSeconds")) {
            builder.visibilityTimeoutSeconds(json.getInteger("visibilityTimeoutSeconds"));
        } else if (json.containsKey("visibilityTimeout")) {
            Object visibilityTimeout = json.getValue("visibilityTimeout");
            if (visibilityTimeout instanceof Number) {
                // Treat as seconds
                builder.visibilityTimeoutSeconds(((Number) visibilityTimeout).intValue());
            } else if (visibilityTimeout instanceof String) {
                // Parse as ISO-8601 duration
                builder.visibilityTimeout(Duration.parse((String) visibilityTimeout));
            }
        }
        
        // Max retries
        if (json.containsKey("maxRetries")) {
            builder.maxRetries(json.getInteger("maxRetries"));
        }
        
        // Dead letter enabled
        if (json.containsKey("deadLetterEnabled")) {
            builder.deadLetterEnabled(json.getBoolean("deadLetterEnabled"));
        }
        
        // Batch size
        if (json.containsKey("batchSize")) {
            int batchSize = json.getInteger("batchSize");
            if (batchSize <= 0) {
                throw new IllegalArgumentException("batchSize must be greater than 0");
            }
            builder.batchSize(batchSize);
        }
        
        // Polling interval (seconds or ISO-8601 duration)
        if (json.containsKey("pollingIntervalSeconds")) {
            builder.pollingInterval(Duration.ofSeconds(json.getInteger("pollingIntervalSeconds")));
        } else if (json.containsKey("pollingInterval")) {
            Object pollingInterval = json.getValue("pollingInterval");
            if (pollingInterval instanceof Number) {
                // Treat as seconds
                builder.pollingInterval(Duration.ofSeconds(((Number) pollingInterval).longValue()));
            } else if (pollingInterval instanceof String) {
                // Parse as ISO-8601 duration
                builder.pollingInterval(Duration.parse((String) pollingInterval));
            }
        }
        
        // FIFO enabled
        if (json.containsKey("fifoEnabled")) {
            builder.fifoEnabled(json.getBoolean("fifoEnabled"));
        }
        
        // Dead letter queue name
        if (json.containsKey("deadLetterQueueName")) {
            builder.deadLetterQueueName(json.getString("deadLetterQueueName"));
        }
        
        return builder.build();
    }
    
    /**
     * Parses event store configuration from JSON with all parameters.
     */
    private EventStoreConfig parseEventStoreConfig(JsonObject json) {
        String eventStoreName = json.getString("eventStoreName");
        if (eventStoreName == null || eventStoreName.trim().isEmpty()) {
            throw new IllegalArgumentException("eventStoreName is required");
        }
        
        String tableName = json.getString("tableName");
        if (tableName == null || tableName.trim().isEmpty()) {
            throw new IllegalArgumentException("tableName is required");
        }
        
        EventStoreConfig.Builder builder = new EventStoreConfig.Builder()
            .eventStoreName(eventStoreName)
            .tableName(tableName);
        
        // Notification prefix
        if (json.containsKey("notificationPrefix")) {
            builder.notificationPrefix(json.getString("notificationPrefix"));
        }
        
        // Query limit
        if (json.containsKey("queryLimit")) {
            int queryLimit = json.getInteger("queryLimit");
            if (queryLimit <= 0) {
                throw new IllegalArgumentException("queryLimit must be greater than 0");
            }
            builder.queryLimit(queryLimit);
        }
        
        // Metrics enabled
        if (json.containsKey("metricsEnabled")) {
            builder.metricsEnabled(json.getBoolean("metricsEnabled"));
        }
        
        // Bi-temporal enabled
        if (json.containsKey("biTemporalEnabled")) {
            builder.biTemporalEnabled(json.getBoolean("biTemporalEnabled"));
        }
        
        // Partition strategy
        if (json.containsKey("partitionStrategy")) {
            String strategy = json.getString("partitionStrategy");
            // Validate strategy
            if (!strategy.matches("none|daily|weekly|monthly|yearly")) {
                throw new IllegalArgumentException("Invalid partitionStrategy. Must be: none, daily, weekly, monthly, or yearly");
            }
            builder.partitionStrategy(strategy);
        }
        
        return builder.build();
    }
    
    private void sendError(RoutingContext ctx, int statusCode, String message) {
        JsonObject error = new JsonObject()
                .put("error", message)
                .put("statusCode", statusCode)
                .put("timestamp", System.currentTimeMillis());
        
        ctx.response()
                .setStatusCode(statusCode)
                .putHeader("Content-Type", "application/json")
                .end(error.encode());
    }

    /**
     * Check if this is a setup not found error (expected, no stack trace needed).
     */
    private boolean isSetupNotFoundError(Throwable throwable) {
        return throwable != null &&
               throwable.getClass().getSimpleName().equals("SetupNotFoundException");
    }

    /**
     * Check if this is a database creation conflict error (expected in concurrent scenarios).
     */
    private boolean isDatabaseCreationConflictError(Throwable throwable) {
        return throwable != null &&
               throwable.getClass().getSimpleName().equals("DatabaseCreationConflictException");
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
