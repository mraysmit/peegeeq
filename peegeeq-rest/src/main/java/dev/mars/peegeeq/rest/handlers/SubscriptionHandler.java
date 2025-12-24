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
import dev.mars.peegeeq.api.error.PeeGeeQError;
import dev.mars.peegeeq.api.error.PeeGeeQErrorCodes;
import dev.mars.peegeeq.api.subscription.SubscriptionInfo;
import dev.mars.peegeeq.api.subscription.SubscriptionService;
import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.rest.error.ErrorResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Handler for Subscription Lifecycle REST API endpoints.
 *
 * Provides REST endpoints for managing consumer group subscriptions including
 * pause, resume, heartbeat, and listing operations.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-12-05
 * @version 1.0
 */
public class SubscriptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(SubscriptionHandler.class);

    private final DatabaseSetupService setupService;
    private final ObjectMapper objectMapper;

    public SubscriptionHandler(DatabaseSetupService setupService, ObjectMapper objectMapper) {
        this.setupService = setupService;
        this.objectMapper = objectMapper;
    }
    
    /**
     * Lists all subscriptions for a topic.
     * GET /api/v1/setups/:setupId/subscriptions/:topic
     */
    public void listSubscriptions(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        String topic = ctx.pathParam("topic");

        logger.debug("Listing subscriptions for setup: {}, topic: {}", setupId, topic);

        SubscriptionService service = setupService.getSubscriptionServiceForSetup(setupId);
        if (service == null) {
            sendSetupNotFoundError(ctx, setupId);
            return;
        }

        service.listSubscriptions(topic)
            .onSuccess(subscriptions -> {
                JsonArray result = new JsonArray();
                for (SubscriptionInfo info : subscriptions) {
                    result.add(subscriptionToJson(info));
                }
                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .end(result.encode());
            })
            .onFailure(error -> {
                logger.error("Failed to list subscriptions for topic: {}", topic, error);
                sendError(ctx, 500, PeeGeeQErrorCodes.INTERNAL_ERROR, "Failed to list subscriptions: " + error.getMessage());
            });
    }

    /**
     * Gets a specific subscription.
     * GET /api/v1/setups/:setupId/subscriptions/:topic/:groupName
     */
    public void getSubscription(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        String topic = ctx.pathParam("topic");
        String groupName = ctx.pathParam("groupName");

        logger.debug("Getting subscription for setup: {}, topic: {}, group: {}", setupId, topic, groupName);

        SubscriptionService service = setupService.getSubscriptionServiceForSetup(setupId);
        if (service == null) {
            sendSetupNotFoundError(ctx, setupId);
            return;
        }

        service.getSubscription(topic, groupName)
            .onSuccess(info -> {
                if (info == null) {
                    sendSubscriptionNotFoundError(ctx, topic, groupName);
                } else {
                    ctx.response()
                        .putHeader("Content-Type", "application/json")
                        .end(subscriptionToJson(info).encode());
                }
            })
            .onFailure(error -> {
                logger.error("Failed to get subscription: {}/{}", topic, groupName, error);
                sendError(ctx, 500, PeeGeeQErrorCodes.INTERNAL_ERROR, "Failed to get subscription: " + error.getMessage());
            });
    }
    
    /**
     * Pauses a subscription.
     * POST /api/v1/setups/:setupId/subscriptions/:topic/:groupName/pause
     */
    public void pauseSubscription(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        String topic = ctx.pathParam("topic");
        String groupName = ctx.pathParam("groupName");

        logger.info("Pausing subscription for setup: {}, topic: {}, group: {}", setupId, topic, groupName);

        SubscriptionService service = setupService.getSubscriptionServiceForSetup(setupId);
        if (service == null) {
            sendSetupNotFoundError(ctx, setupId);
            return;
        }

        service.pause(topic, groupName)
            .onSuccess(v -> {
                JsonObject result = new JsonObject()
                    .put("success", true)
                    .put("topic", topic)
                    .put("groupName", groupName)
                    .put("action", "paused");
                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .end(result.encode());
            })
            .onFailure(error -> {
                if (isSubscriptionNotFoundError(error)) {
                    logger.debug("Subscription not found for pause: {}/{}", topic, groupName);
                    sendSubscriptionNotFoundError(ctx, topic, groupName);
                } else {
                    logger.error("Failed to pause subscription: {}/{}", topic, groupName, error);
                    sendError(ctx, 500, PeeGeeQErrorCodes.SUBSCRIPTION_PAUSE_FAILED, "Failed to pause subscription: " + error.getMessage());
                }
            });
    }

    /**
     * Resumes a paused subscription.
     * POST /api/v1/setups/:setupId/subscriptions/:topic/:groupName/resume
     */
    public void resumeSubscription(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        String topic = ctx.pathParam("topic");
        String groupName = ctx.pathParam("groupName");

        logger.info("Resuming subscription for setup: {}, topic: {}, group: {}", setupId, topic, groupName);

        SubscriptionService service = setupService.getSubscriptionServiceForSetup(setupId);
        if (service == null) {
            sendSetupNotFoundError(ctx, setupId);
            return;
        }

        service.resume(topic, groupName)
            .onSuccess(v -> {
                JsonObject result = new JsonObject()
                    .put("success", true)
                    .put("topic", topic)
                    .put("groupName", groupName)
                    .put("action", "resumed");
                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .end(result.encode());
            })
            .onFailure(error -> {
                if (isSubscriptionNotFoundError(error)) {
                    logger.debug("Subscription not found for resume: {}/{}", topic, groupName);
                    sendSubscriptionNotFoundError(ctx, topic, groupName);
                } else {
                    logger.error("Failed to resume subscription: {}/{}", topic, groupName, error);
                    sendError(ctx, 500, PeeGeeQErrorCodes.SUBSCRIPTION_RESUME_FAILED, "Failed to resume subscription: " + error.getMessage());
                }
            });
    }

    /**
     * Updates heartbeat for a subscription.
     * POST /api/v1/setups/:setupId/subscriptions/:topic/:groupName/heartbeat
     */
    public void updateHeartbeat(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        String topic = ctx.pathParam("topic");
        String groupName = ctx.pathParam("groupName");

        logger.debug("Updating heartbeat for setup: {}, topic: {}, group: {}", setupId, topic, groupName);

        SubscriptionService service = setupService.getSubscriptionServiceForSetup(setupId);
        if (service == null) {
            sendSetupNotFoundError(ctx, setupId);
            return;
        }

        service.updateHeartbeat(topic, groupName)
            .onSuccess(v -> {
                JsonObject result = new JsonObject()
                    .put("success", true)
                    .put("topic", topic)
                    .put("groupName", groupName)
                    .put("action", "heartbeat_updated");
                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .end(result.encode());
            })
            .onFailure(error -> {
                if (isSubscriptionNotFoundError(error)) {
                    logger.debug("Subscription not found for heartbeat: {}/{}", topic, groupName);
                    sendSubscriptionNotFoundError(ctx, topic, groupName);
                } else {
                    logger.error("Failed to update heartbeat: {}/{}", topic, groupName, error);
                    sendError(ctx, 500, PeeGeeQErrorCodes.SUBSCRIPTION_HEARTBEAT_FAILED, "Failed to update heartbeat: " + error.getMessage());
                }
            });
    }

    /**
     * Cancels a subscription.
     * DELETE /api/v1/setups/:setupId/subscriptions/:topic/:groupName
     */
    public void cancelSubscription(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        String topic = ctx.pathParam("topic");
        String groupName = ctx.pathParam("groupName");

        logger.info("Cancelling subscription for setup: {}, topic: {}, group: {}", setupId, topic, groupName);

        SubscriptionService service = setupService.getSubscriptionServiceForSetup(setupId);
        if (service == null) {
            sendSetupNotFoundError(ctx, setupId);
            return;
        }

        service.cancel(topic, groupName)
            .onSuccess(v -> {
                JsonObject result = new JsonObject()
                    .put("success", true)
                    .put("topic", topic)
                    .put("groupName", groupName)
                    .put("action", "cancelled");
                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .end(result.encode());
            })
            .onFailure(error -> {
                if (isSubscriptionNotFoundError(error)) {
                    logger.debug("Subscription not found for cancel: {}/{}", topic, groupName);
                    sendSubscriptionNotFoundError(ctx, topic, groupName);
                } else {
                    logger.error("Failed to cancel subscription: {}/{}", topic, groupName, error);
                    sendError(ctx, 500, PeeGeeQErrorCodes.SUBSCRIPTION_CANCEL_FAILED, "Failed to cancel subscription: " + error.getMessage());
                }
            });
    }

    /**
     * Converts SubscriptionInfo to JSON.
     */
    private JsonObject subscriptionToJson(SubscriptionInfo info) {
        JsonObject json = new JsonObject()
            .put("id", info.id())
            .put("topic", info.topic())
            .put("groupName", info.groupName())
            .put("state", info.state().name())
            .put("heartbeatIntervalSeconds", info.heartbeatIntervalSeconds())
            .put("heartbeatTimeoutSeconds", info.heartbeatTimeoutSeconds())
            .put("backfillStatus", info.backfillStatus());

        if (info.subscribedAt() != null) {
            json.put("subscribedAt", info.subscribedAt().toString());
        }
        if (info.lastActiveAt() != null) {
            json.put("lastActiveAt", info.lastActiveAt().toString());
        }
        if (info.lastHeartbeatAt() != null) {
            json.put("lastHeartbeatAt", info.lastHeartbeatAt().toString());
        }
        if (info.startFromMessageId() != null) {
            json.put("startFromMessageId", info.startFromMessageId());
        }
        if (info.startFromTimestamp() != null) {
            json.put("startFromTimestamp", info.startFromTimestamp().toString());
        }
        if (info.backfillCheckpointId() != null) {
            json.put("backfillCheckpointId", info.backfillCheckpointId());
        }
        if (info.backfillProcessedMessages() != null) {
            json.put("backfillProcessedMessages", info.backfillProcessedMessages());
        }
        if (info.backfillTotalMessages() != null) {
            json.put("backfillTotalMessages", info.backfillTotalMessages());
        }
        if (info.backfillStartedAt() != null) {
            json.put("backfillStartedAt", info.backfillStartedAt().toString());
        }
        if (info.backfillCompletedAt() != null) {
            json.put("backfillCompletedAt", info.backfillCompletedAt().toString());
        }

        return json;
    }

    /**
     * Checks if the error indicates a subscription was not found.
     * This is used to return 404 instead of 500 for not-found errors.
     */
    private boolean isSubscriptionNotFoundError(Throwable error) {
        if (error == null) {
            return false;
        }
        String message = error.getMessage();
        if (message != null && message.contains("Subscription not found")) {
            return true;
        }
        // Also check for IllegalStateException which is thrown by SubscriptionManager
        if (error instanceof IllegalStateException && message != null && message.contains("not found")) {
            return true;
        }
        return false;
    }

    /**
     * Sends an error response with standard error code.
     */
    private void sendError(RoutingContext ctx, int statusCode, String errorCode, String message) {
        ErrorResponse.send(ctx, statusCode, PeeGeeQError.of(errorCode, message));
    }

    /**
     * Sends a setup not found error.
     */
    private void sendSetupNotFoundError(RoutingContext ctx, String setupId) {
        ErrorResponse.notFound(ctx, PeeGeeQError.setupNotFound(setupId));
    }

    /**
     * Sends a subscription not found error.
     */
    private void sendSubscriptionNotFoundError(RoutingContext ctx, String topic, String groupName) {
        ErrorResponse.notFound(ctx, PeeGeeQError.subscriptionNotFound(topic + "/" + groupName));
    }
}
