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
import dev.mars.peegeeq.api.tracing.TraceCtx;
import dev.mars.peegeeq.api.tracing.TraceContextUtil;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        // objectMapper kept as parameter for potential future use and backward
        // compatibility
    }

    /**
     * Creates a complete database setup with queues and event stores.
     * POST /api/v1/setups
     */
    public void createSetup(RoutingContext ctx) {
        String traceparent = ctx.request().getHeader("traceparent");
        TraceCtx requestTrace = TraceContextUtil.parseOrCreate(traceparent);

        Context vertxContext = Vertx.currentContext();
        if (vertxContext != null) {
            vertxContext.put(TraceContextUtil.CONTEXT_TRACE_KEY, requestTrace);
        }

        try (var ignored = TraceContextUtil.mdcScope(requestTrace)) {
            try {
                JsonObject body = ctx.body().asJsonObject();
                final TraceCtx finalTraceCtx = requestTrace;

                // Parse request with comprehensive parameter support
                DatabaseSetupRequest request = parseSetupRequest(ctx, body);

                logger.info("Creating database setup: {} with {} queues and {} event stores",
                        request.getSetupId(),
                        request.getQueues().size(),
                        request.getEventStores().size());

                setupService.createCompleteSetup(request)
                        .map(result -> {
                            try (var scope = TraceContextUtil.mdcScope(finalTraceCtx)) {
                                logger.debug("REST HANDLER: Received completion from createCompleteSetup for setupId={}",
                                        request.getSetupId());
                                return new JsonObject()
                                        .put("setupId", result.getSetupId())
                                        .put("status", result.getStatus().name())
                                        .put("queueCount", result.getQueueFactories().size())
                                        .put("eventStoreCount", result.getEventStores().size())
                                        .put("message", "Database setup created successfully");
                            }
                        })
                        .onSuccess(response -> {
                            ctx.response()
                                    .setStatusCode(201)
                                    .putHeader("Content-Type", "application/json")
                                    .end(response.encode());
                            logger.info("Database setup created successfully: {}", request.getSetupId());
                        })
                        .onFailure(throwable -> {
                            try (var scope = TraceContextUtil.mdcScope(finalTraceCtx)) {
                                Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
                                if (isDatabaseCreationConflictError(cause)) {
                                    logger.debug("Database creation conflict for setup: {}", request.getSetupId());
                                } else {
                                    logger.error("Error creating database setup: " + request.getSetupId(), throwable);
                                }

                                int statusCode = 503;
                                String errorMessage = "Failed to create setup '" + request.getSetupId() + "': "
                                        + throwable.getMessage();

                                if (isDatabaseCreationConflictError(cause)
                                        || (cause.getMessage() != null && cause.getMessage().contains("already exists"))) {
                                    // The setup (its database) already exists. Create is non-destructive and will
                                    // NOT overwrite it — attach with connect, or drop the database first (a
                                    // separate, guarded operation) to recreate.
                                    statusCode = 409;
                                    errorMessage = "Setup already exists: " + request.getSetupId()
                                            + ". Use POST /api/v1/database-setup/connect to attach, or drop the "
                                            + "database first (a separate, guarded operation) to recreate.";
                                } else if (cause.getMessage() != null && cause.getMessage().contains("invalid")) {
                                    statusCode = 400;
                                }

                                sendError(ctx, statusCode, errorMessage);
                            }
                        });

            } catch (IllegalArgumentException e) {
                logger.warn("Invalid request for database setup: {}", e.getMessage());
                sendError(ctx, 400, "Invalid request: " + e.getMessage());
            } catch (Exception e) {
                logger.error("Error parsing create setup request", e);
                sendError(ctx, 400, "Error processing request: " + e.getMessage());
            }
        }
    }

    /**
     * Non-destructively connects to a setup whose database and schema already exist.
     * POST /api/v1/database-setup/connect
     *
     * <p>Uses the same request DTO as {@link #createSetup}, but the {@code queues} / {@code eventStores}
     * in the body are ignored — the setup's contents are reconstituted from its self-describing registry
     * tables — and {@code setupId} is treated as the expected id, validated against the value recovered
     * from the schema. Nothing is created; a missing PeeGeeQ schema fails clearly with 400.
     */
    public void connectToExistingSetup(RoutingContext ctx) {
        String traceparent = ctx.request().getHeader("traceparent");
        TraceCtx requestTrace = TraceContextUtil.parseOrCreate(traceparent);

        Context vertxContext = Vertx.currentContext();
        if (vertxContext != null) {
            vertxContext.put(TraceContextUtil.CONTEXT_TRACE_KEY, requestTrace);
        }

        try (var ignored = TraceContextUtil.mdcScope(requestTrace)) {
            try {
                JsonObject body = ctx.body().asJsonObject();
                final TraceCtx finalTraceCtx = requestTrace;

                // Same DTO as create; queues/eventStores are ignored on connect (reconstituted from schema).
                DatabaseSetupRequest request = parseSetupRequest(ctx, body);

                logger.info("Connecting to existing database setup: {}", request.getSetupId());

                setupService.connectToExistingSetup(request)
                        .map(result -> {
                            try (var scope = TraceContextUtil.mdcScope(finalTraceCtx)) {
                                logger.debug("REST HANDLER: Received completion from connectToExistingSetup for setupId={}",
                                        result.getSetupId());
                                return new JsonObject()
                                        .put("setupId", result.getSetupId())
                                        .put("status", result.getStatus().name())
                                        .put("queueCount", result.getQueueFactories().size())
                                        .put("eventStoreCount", result.getEventStores().size())
                                        .put("message", "Database setup connected successfully");
                            }
                        })
                        .onSuccess(response -> {
                            ctx.response()
                                    .setStatusCode(200)
                                    .putHeader("Content-Type", "application/json")
                                    .end(response.encode());
                            logger.info("Connected to existing database setup: {}", request.getSetupId());
                        })
                        .onFailure(throwable -> {
                            try (var scope = TraceContextUtil.mdcScope(finalTraceCtx)) {
                                logger.error("Error connecting to existing database setup: " + request.getSetupId(), throwable);
                                Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;

                                // Schema-absent / not-a-PeeGeeQ-database / setupId mismatch are client errors (400);
                                // connection/auth and other infrastructure failures map to 503.
                                if (cause instanceof IllegalArgumentException || cause instanceof IllegalStateException) {
                                    sendError(ctx, 400, "Cannot connect to setup '" + request.getSetupId() + "': "
                                            + cause.getMessage());
                                } else {
                                    sendError(ctx, 503, "Failed to connect to setup '" + request.getSetupId() + "': "
                                            + throwable.getMessage());
                                }
                            }
                        });

            } catch (IllegalArgumentException e) {
                logger.warn("Invalid request for connect setup: {}", e.getMessage());
                sendError(ctx, 400, "Invalid request: " + e.getMessage());
            } catch (Exception e) {
                logger.error("Error parsing connect setup request", e);
                sendError(ctx, 400, "Error processing request: " + e.getMessage());
            }
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
                .onSuccess(v -> {
                    ctx.response()
                            .setStatusCode(204) // No Content
                            .end();

                    logger.info("Database setup deleted successfully: {}", setupId);
                })
                .onFailure(err -> {
                    logger.error("Failed to delete database setup: {}", setupId, err);
                    sendError(ctx, 503, "Failed to delete setup: " + err.getMessage());
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
                .map(status -> new JsonObject()
                        .put("setupId", setupId)
                        .put("status", status.name()))
                .onSuccess(response -> ctx.response()
                        .setStatusCode(200)
                        .putHeader("Content-Type", "application/json")
                        .end(response.encode()))
                .onFailure(err -> {
                    Throwable cause = err.getCause() != null ? err.getCause() : err;
                    if (isSetupNotFoundError(cause)) {
                        logger.debug("Setup not found: {}", setupId);
                        sendError(ctx, 404, "Setup not found: " + setupId);
                    } else {
                        logger.error("Failed to get setup status: {}", setupId, err);
                        sendError(ctx, 503, "Failed to get setup status: " + err.getMessage());
                    }
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
                .compose(result -> setupService.getDatabaseConfig(setupId)
                        .map(config -> new JsonObject()
                                .put("setupId", result.getSetupId())
                                .put("status", result.getStatus().name())
                                .put("host", config.getHost())
                                .put("port", config.getPort())
                                .put("databaseName", config.getDatabaseName())
                                .put("schema", config.getSchema())
                                .put("queueFactories", new JsonArray(new ArrayList<>(result.getQueueFactories().keySet())))
                                .put("eventStores", new JsonArray(new ArrayList<>(result.getEventStores().keySet()))))
                        .transform(ar -> {
                            if (ar.failed()) {
                                logger.warn("getDatabaseConfig failed for active setup {}: {}", setupId, ar.cause().getMessage());
                                return Future.failedFuture(new IllegalStateException(
                                        "Database config unavailable for active setup: " + setupId));
                            }
                            return Future.succeededFuture(ar.result());
                        }))
                .onSuccess(response -> ctx.response()
                        .setStatusCode(200)
                        .putHeader("Content-Type", "application/json")
                        .end(response.encode()))
                .onFailure(err -> {
                    Throwable cause = err.getCause() != null ? err.getCause() : err;
                    if (isSetupNotFoundError(cause)) {
                        logger.debug("Setup not found: {}", setupId);
                        sendError(ctx, 404, "Setup not found: " + setupId);
                    } else {
                        logger.error("Error getting setup details: " + setupId, err);
                        sendError(ctx, 503, "Failed to get setup details: " + err.getMessage());
                    }
                });
    }

    /**
     * Adds a new queue to an existing database setup.
     * POST /api/v1/setups/:setupId/queues
     */
    public void addQueue(RoutingContext ctx) {
        final JsonObject body;
        final String setupId;
        final QueueConfig queueConfig;
        try {
            body = ctx.body().asJsonObject();
            setupId = ConfigParser.getSetupId(ctx.pathParam("setupId"), body);
            queueConfig = ConfigParser.parseQueueConfig(body);
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid queue configuration: {}", e.getMessage());
            sendError(ctx, 400, "Invalid request: " + e.getMessage());
            return;
        } catch (Exception e) {
            logger.error("Error parsing add queue request", e);
            sendError(ctx, 400, "Invalid request format: " + e.getMessage());
            return;
        }

        logger.info("Adding queue {} to setup: {}", queueConfig.getQueueName(), setupId);

        setupService.addQueue(setupId, queueConfig)
                .compose(v -> setupService.getSetupResult(setupId))
                .map(result -> {
                    var factory = result.getQueueFactories().get(queueConfig.getQueueName());
                    String implementationType = factory != null ? factory.getImplementationType() : null;
                    JsonObject response = new JsonObject()
                            .put("message", "Queue '" + queueConfig.getQueueName()
                                    + "' added successfully to setup '" + setupId + "'")
                            .put("queueName", queueConfig.getQueueName())
                            .put("setupId", setupId);
                    if (implementationType != null) {
                        response.put("implementationType", implementationType);
                    }
                    return response;
                })
                .onSuccess(response -> {
                    ctx.response()
                            .setStatusCode(201)
                            .putHeader("Content-Type", "application/json")
                            .end(response.encode());
                    logger.info("Queue added successfully: {} to setup {}", queueConfig.getQueueName(), setupId);
                })
                .onFailure(err -> {
                    logger.error("Failed to add queue '{}' to setup: {}", queueConfig.getQueueName(), setupId, err);
                    Throwable cause = err.getCause() != null ? err.getCause() : err;
                    if (cause.getMessage() != null && cause.getMessage().contains("Setup not found")) {
                        sendError(ctx, 404, "Setup not found: " + setupId);
                    } else if (cause instanceof IllegalArgumentException) {
                        sendError(ctx, 400, "Invalid request: " + cause.getMessage());
                    } else {
                        sendError(ctx, 503, "Failed to add queue '" + queueConfig.getQueueName() + "': " + err.getMessage());
                    }
                });
    }

    /**
     * Adds a new event store to an existing database setup.
     * POST /api/v1/setups/:setupId/eventstores
     */
    public void addEventStore(RoutingContext ctx) {
        final JsonObject body;
        final String setupId;
        final EventStoreConfig eventStoreConfig;
        try {
            body = ctx.body().asJsonObject();
            setupId = ConfigParser.getSetupId(ctx.pathParam("setupId"), body);
            eventStoreConfig = ConfigParser.parseEventStoreConfig(body);
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid event store configuration: {}", e.getMessage());
            sendError(ctx, 400, "Invalid request: " + e.getMessage());
            return;
        } catch (Exception e) {
            logger.error("Error parsing add event store request", e);
            sendError(ctx, 400, "Invalid request format: " + e.getMessage());
            return;
        }

        logger.info("Adding event store {} to setup: {}", eventStoreConfig.getEventStoreName(), setupId);

        setupService.addEventStore(setupId, eventStoreConfig)
                .map(v -> new JsonObject()
                        .put("message", "Event store '" + eventStoreConfig.getEventStoreName()
                                + "' added successfully to setup '" + setupId + "'")
                        .put("eventStoreName", eventStoreConfig.getEventStoreName())
                        .put("setupId", setupId))
                .onSuccess(response -> {
                    ctx.response()
                            .setStatusCode(201)
                            .putHeader("Content-Type", "application/json")
                            .end(response.encode());
                    logger.info("Event store added successfully: {} to setup {}",
                            eventStoreConfig.getEventStoreName(), setupId);
                })
                .onFailure(err -> {
                    logger.error("Failed to add event store '{}' to setup: {}",
                            eventStoreConfig.getEventStoreName(), setupId, err);
                    Throwable cause = err.getCause() != null ? err.getCause() : err;
                    if (cause.getMessage() != null && cause.getMessage().contains("Setup not found")) {
                        sendError(ctx, 404, "Setup not found: " + setupId);
                    } else {
                        sendError(ctx, 503, "Failed to add event store '" + eventStoreConfig.getEventStoreName() + "': " + err.getMessage());
                    }
                });
    }

    /**
     * Lists all active setup IDs.
     * GET /api/v1/setups
     */
    public void listSetups(RoutingContext ctx) {
        logger.debug("Listing all active setups");

        setupService.getAllActiveSetupIds()
                .map(setupIds -> new JsonObject()
                        .put("count", setupIds.size())
                        .put("setupIds", new JsonArray(new ArrayList<>(setupIds))))
                .onSuccess(response -> ctx.response()
                        .setStatusCode(200)
                        .putHeader("Content-Type", "application/json")
                        .end(response.encode()))
                .onFailure(err -> {
                    logger.error("Failed to list setups", err);
                    sendError(ctx, 503, "Failed to list setups: " + err.getMessage());
                });
    }

    /**
     * Lists all queues for a specific setup.
     * GET /api/v1/setups/:setupId/queues
     */
    public void listQueues(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");

        logger.debug("Listing queues for setup: {}", setupId);

        setupService.getSetupResult(setupId)
                .map(result -> {
                    List<String> queueNames = new ArrayList<>(result.getQueueFactories().keySet());
                    // Additive enrichment: per-queue implementation type. The legacy "count" and
                    // "queues" (names only) fields are preserved for backward compatibility.
                    JsonArray queueDetails = new JsonArray();
                    result.getQueueFactories().forEach((name, factory) -> queueDetails.add(
                            new JsonObject()
                                    .put("name", name)
                                    .put("implementationType",
                                            factory != null ? factory.getImplementationType() : null)));
                    return new JsonObject()
                            .put("count", queueNames.size())
                            .put("queues", new JsonArray(queueNames))
                            .put("queueDetails", queueDetails);
                })
                .onSuccess(response -> ctx.response()
                        .setStatusCode(200)
                        .putHeader("Content-Type", "application/json")
                        .end(response.encode()))
                .onFailure(err -> {
                    Throwable cause = err.getCause() != null ? err.getCause() : err;
                    if (isSetupNotFoundError(cause)) {
                        logger.debug("Setup not found: {}", setupId);
                        sendError(ctx, 404, "Setup not found: " + setupId);
                    } else {
                        logger.error("Failed to list queues for setup: {}", setupId, err);
                        sendError(ctx, 503, "Failed to list queues: " + err.getMessage());
                    }
                });
    }

    /**
     * Lists all event stores for a specific setup.
     * GET /api/v1/setups/:setupId/eventstores
     */
    public void listEventStores(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");

        logger.debug("Listing event stores for setup: {}", setupId);

        setupService.getSetupResult(setupId)
                .map(result -> {
                    List<String> storeNames = new ArrayList<>(result.getEventStores().keySet());
                    return new JsonObject()
                            .put("count", storeNames.size())
                            .put("eventStores", new JsonArray(storeNames));
                })
                .onSuccess(response -> ctx.response()
                        .setStatusCode(200)
                        .putHeader("Content-Type", "application/json")
                        .end(response.encode()))
                .onFailure(err -> {
                    Throwable cause = err.getCause() != null ? err.getCause() : err;
                    if (isSetupNotFoundError(cause)) {
                        logger.debug("Setup not found: {}", setupId);
                        sendError(ctx, 404, "Setup not found: " + setupId);
                    } else {
                        logger.error("Failed to list event stores for setup: {}", setupId, err);
                        sendError(ctx, 503, "Failed to list event stores: " + err.getMessage());
                    }
                });
    }

    /**
     * Parses a DatabaseSetupRequest from JSON.
     */
    private DatabaseSetupRequest parseSetupRequest(RoutingContext ctx, JsonObject json) {
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
                queues.add(ConfigParser.parseQueueConfig(queuesArray.getJsonObject(i)));
            }
        }

        // Parse event stores
        List<EventStoreConfig> eventStores = new ArrayList<>();
        JsonArray eventStoresArray = json.getJsonArray("eventStores");
        if (eventStoresArray != null) {
            for (int i = 0; i < eventStoresArray.size(); i++) {
                eventStores.add(ConfigParser.parseEventStoreConfig(eventStoresArray.getJsonObject(i)));
            }
        }

        return new DatabaseSetupRequest(setupId, dbConfig, queues, eventStores, null);
    }

    /**
     * Parses database configuration from JSON.
     */
    private DatabaseConfig parseDatabaseConfig(JsonObject json) {
        String schema = json.getString("schema");
        if (schema == null || schema.isBlank()) {
            throw new IllegalArgumentException("schema is required");
        }

        return new DatabaseConfig.Builder()
                .host(json.getString("host", "localhost"))
                .port(json.getInteger("port", 5432))
                .databaseName(json.getString("databaseName"))
                .username(json.getString("username", "postgres"))
                .password(json.getString("password", "postgres"))
                .schema(schema)
                .templateDatabase(json.getString("templateDatabase", "template0"))
                .encoding(json.getString("encoding", "UTF8"))
                .build();
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
     * Check if this is a setup not found error (mapped to HTTP 404).
     */
    private boolean isSetupNotFoundError(Throwable throwable) {
        return throwable instanceof SetupNotFoundException;
    }

    /**
     * Check if this is a database creation conflict error from concurrent
     * setup requests racing to create the same database.
     */
    private boolean isDatabaseCreationConflictError(Throwable throwable) {
        return throwable instanceof DatabaseCreationConflictException;
    }
}
