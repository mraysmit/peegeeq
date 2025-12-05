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
import dev.mars.peegeeq.api.deadletter.DeadLetterMessageInfo;
import dev.mars.peegeeq.api.deadletter.DeadLetterService;
import dev.mars.peegeeq.api.deadletter.DeadLetterStatsInfo;
import dev.mars.peegeeq.rest.setup.RestDatabaseSetupService;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * Handler for Dead Letter Queue REST API endpoints.
 * 
 * Provides REST endpoints for managing dead letter messages including
 * listing, viewing, reprocessing, and deleting failed messages.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-12-05
 * @version 1.0
 */
public class DeadLetterHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(DeadLetterHandler.class);
    
    private final RestDatabaseSetupService setupService;
    private final ObjectMapper objectMapper;
    
    public DeadLetterHandler(RestDatabaseSetupService setupService, ObjectMapper objectMapper) {
        this.setupService = setupService;
        this.objectMapper = objectMapper;
    }
    
    /**
     * Lists dead letter messages with optional topic filter.
     * GET /api/v1/setups/:setupId/deadletter/messages
     * Query params: topic (optional), limit (default 50), offset (default 0)
     */
    public void listMessages(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        String topic = ctx.request().getParam("topic");
        int limit = parseIntParam(ctx, "limit", 50);
        int offset = parseIntParam(ctx, "offset", 0);
        
        logger.debug("Listing dead letter messages for setup: {}, topic: {}, limit: {}, offset: {}", 
            setupId, topic, limit, offset);
        
        DeadLetterService service = setupService.getDeadLetterServiceForSetup(setupId);
        if (service == null) {
            sendError(ctx, 404, "Setup not found: " + setupId);
            return;
        }
        
        try {
            List<DeadLetterMessageInfo> messages;
            if (topic != null && !topic.isEmpty()) {
                messages = service.getDeadLetterMessages(topic, limit, offset);
            } else {
                messages = service.getAllDeadLetterMessages(limit, offset);
            }
            
            JsonArray result = new JsonArray();
            for (DeadLetterMessageInfo msg : messages) {
                result.add(messageToJson(msg));
            }
            
            ctx.response()
                .putHeader("content-type", "application/json")
                .end(result.encode());
        } catch (Exception e) {
            logger.error("Failed to list dead letter messages", e);
            sendError(ctx, 500, "Failed to list dead letter messages: " + e.getMessage());
        }
    }
    
    /**
     * Gets a specific dead letter message by ID.
     * GET /api/v1/setups/:setupId/deadletter/messages/:messageId
     */
    public void getMessage(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        String messageIdStr = ctx.pathParam("messageId");
        
        logger.debug("Getting dead letter message {} for setup: {}", messageIdStr, setupId);
        
        DeadLetterService service = setupService.getDeadLetterServiceForSetup(setupId);
        if (service == null) {
            sendError(ctx, 404, "Setup not found: " + setupId);
            return;
        }
        
        try {
            long messageId = Long.parseLong(messageIdStr);
            Optional<DeadLetterMessageInfo> message = service.getDeadLetterMessage(messageId);
            
            if (message.isEmpty()) {
                sendError(ctx, 404, "Dead letter message not found: " + messageId);
                return;
            }
            
            ctx.response()
                .putHeader("content-type", "application/json")
                .end(messageToJson(message.get()).encode());
        } catch (NumberFormatException e) {
            sendError(ctx, 400, "Invalid message ID: " + messageIdStr);
        } catch (Exception e) {
            logger.error("Failed to get dead letter message", e);
            sendError(ctx, 500, "Failed to get dead letter message: " + e.getMessage());
        }
    }
    
    /**
     * Reprocesses a dead letter message.
     * POST /api/v1/setups/:setupId/deadletter/messages/:messageId/reprocess
     * Body: { "reason": "optional reason for reprocessing" }
     */
    public void reprocessMessage(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        String messageIdStr = ctx.pathParam("messageId");

        logger.debug("Reprocessing dead letter message {} for setup: {}", messageIdStr, setupId);

        DeadLetterService service = setupService.getDeadLetterServiceForSetup(setupId);
        if (service == null) {
            sendError(ctx, 404, "Setup not found: " + setupId);
            return;
        }

        try {
            long messageId = Long.parseLong(messageIdStr);

            // Parse optional reason from body
            String reason = "Reprocessed via REST API";
            JsonObject body = ctx.body().asJsonObject();
            if (body != null && body.containsKey("reason")) {
                reason = body.getString("reason");
            }

            boolean success = service.reprocessDeadLetterMessage(messageId, reason);

            if (success) {
                ctx.response()
                    .putHeader("content-type", "application/json")
                    .end(new JsonObject()
                        .put("success", true)
                        .put("message", "Message reprocessed successfully")
                        .encode());
            } else {
                sendError(ctx, 404, "Dead letter message not found or already processed: " + messageId);
            }
        } catch (NumberFormatException e) {
            sendError(ctx, 400, "Invalid message ID: " + messageIdStr);
        } catch (Exception e) {
            logger.error("Failed to reprocess dead letter message", e);
            sendError(ctx, 500, "Failed to reprocess dead letter message: " + e.getMessage());
        }
    }

    /**
     * Deletes a dead letter message.
     * DELETE /api/v1/setups/:setupId/deadletter/messages/:messageId
     * Query params: reason (optional)
     */
    public void deleteMessage(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        String messageIdStr = ctx.pathParam("messageId");
        String reason = ctx.request().getParam("reason");
        if (reason == null || reason.isEmpty()) {
            reason = "Deleted via REST API";
        }

        logger.debug("Deleting dead letter message {} for setup: {}", messageIdStr, setupId);

        DeadLetterService service = setupService.getDeadLetterServiceForSetup(setupId);
        if (service == null) {
            sendError(ctx, 404, "Setup not found: " + setupId);
            return;
        }

        try {
            long messageId = Long.parseLong(messageIdStr);
            boolean success = service.deleteDeadLetterMessage(messageId, reason);

            if (success) {
                ctx.response().setStatusCode(204).end();
            } else {
                sendError(ctx, 404, "Dead letter message not found: " + messageId);
            }
        } catch (NumberFormatException e) {
            sendError(ctx, 400, "Invalid message ID: " + messageIdStr);
        } catch (Exception e) {
            logger.error("Failed to delete dead letter message", e);
            sendError(ctx, 500, "Failed to delete dead letter message: " + e.getMessage());
        }
    }

    /**
     * Gets dead letter queue statistics.
     * GET /api/v1/setups/:setupId/deadletter/stats
     */
    public void getStats(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");

        logger.debug("Getting dead letter stats for setup: {}", setupId);

        DeadLetterService service = setupService.getDeadLetterServiceForSetup(setupId);
        if (service == null) {
            sendError(ctx, 404, "Setup not found: " + setupId);
            return;
        }

        try {
            DeadLetterStatsInfo stats = service.getStatistics();

            JsonObject result = new JsonObject()
                .put("totalMessages", stats.totalMessages())
                .put("uniqueTopics", stats.uniqueTopics())
                .put("uniqueTables", stats.uniqueTables())
                .put("averageRetryCount", stats.averageRetryCount());

            if (stats.oldestFailure() != null) {
                result.put("oldestFailure", stats.oldestFailure().toString());
            }
            if (stats.newestFailure() != null) {
                result.put("newestFailure", stats.newestFailure().toString());
            }

            ctx.response()
                .putHeader("content-type", "application/json")
                .end(result.encode());
        } catch (Exception e) {
            logger.error("Failed to get dead letter stats", e);
            sendError(ctx, 500, "Failed to get dead letter stats: " + e.getMessage());
        }
    }

    /**
     * Cleans up old dead letter messages.
     * POST /api/v1/setups/:setupId/deadletter/cleanup
     * Query params: retentionDays (default 30)
     */
    public void cleanup(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        int retentionDays = parseIntParam(ctx, "retentionDays", 30);

        logger.debug("Cleaning up dead letter messages for setup: {}, retention: {} days", setupId, retentionDays);

        DeadLetterService service = setupService.getDeadLetterServiceForSetup(setupId);
        if (service == null) {
            sendError(ctx, 404, "Setup not found: " + setupId);
            return;
        }

        try {
            int cleaned = service.cleanupOldMessages(retentionDays);

            ctx.response()
                .putHeader("content-type", "application/json")
                .end(new JsonObject()
                    .put("success", true)
                    .put("messagesDeleted", cleaned)
                    .put("retentionDays", retentionDays)
                    .encode());
        } catch (Exception e) {
            logger.error("Failed to cleanup dead letter messages", e);
            sendError(ctx, 500, "Failed to cleanup dead letter messages: " + e.getMessage());
        }
    }

    private JsonObject messageToJson(DeadLetterMessageInfo msg) {
        JsonObject json = new JsonObject()
            .put("id", msg.id())
            .put("originalTable", msg.originalTable())
            .put("originalId", msg.originalId())
            .put("topic", msg.topic())
            .put("payload", msg.payload())
            .put("originalCreatedAt", msg.originalCreatedAt().toString())
            .put("failedAt", msg.failedAt().toString())
            .put("failureReason", msg.failureReason())
            .put("retryCount", msg.retryCount());
        
        if (msg.headers() != null) {
            json.put("headers", new JsonObject(new java.util.HashMap<>(msg.headers())));
        }
        if (msg.correlationId() != null) {
            json.put("correlationId", msg.correlationId());
        }
        if (msg.messageGroup() != null) {
            json.put("messageGroup", msg.messageGroup());
        }
        return json;
    }
    
    private int parseIntParam(RoutingContext ctx, String name, int defaultValue) {
        String value = ctx.request().getParam(name);
        if (value == null || value.isEmpty()) return defaultValue;
        try { return Integer.parseInt(value); } 
        catch (NumberFormatException e) { return defaultValue; }
    }
    
    private void sendError(RoutingContext ctx, int statusCode, String message) {
        ctx.response().setStatusCode(statusCode)
            .putHeader("content-type", "application/json")
            .end(new JsonObject().put("error", message).encode());
    }
}

