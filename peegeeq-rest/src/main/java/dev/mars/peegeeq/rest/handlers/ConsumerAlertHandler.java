package dev.mars.peegeeq.rest.handlers;

import dev.mars.peegeeq.api.error.PeeGeeQError;
import dev.mars.peegeeq.api.error.PeeGeeQErrorCodes;
import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.api.subscription.SubscriptionInfo;
import dev.mars.peegeeq.api.subscription.SubscriptionService;
import dev.mars.peegeeq.rest.error.ErrorResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler for Dead Consumer Alerting REST API endpoints.
 *
 * Provides REST endpoints for programmatic access to dead consumer detection data:
 * - GET /api/v1/setups/:setupId/consumer-alerts/dead - List dead subscriptions
 * - GET /api/v1/setups/:setupId/consumer-alerts/summary - Subscription health summary
 * - GET /api/v1/setups/:setupId/consumer-alerts/blocked - Blocked message stats
 */
public class ConsumerAlertHandler {

    private static final Logger logger = LoggerFactory.getLogger(ConsumerAlertHandler.class);

    private final DatabaseSetupService setupService;

    public ConsumerAlertHandler(DatabaseSetupService setupService) {
        this.setupService = setupService;
    }

    /**
     * Lists all dead subscriptions.
     * GET /api/v1/setups/:setupId/consumer-alerts/dead
     */
    public void listDeadSubscriptions(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");

        SubscriptionService service = setupService.getSubscriptionServiceForSetup(setupId);
        if (service == null) {
            ErrorResponse.notFound(ctx, PeeGeeQError.setupNotFound(setupId));
            return;
        }

        service.listDeadSubscriptions()
            .onSuccess(deadSubscriptions -> {
                JsonArray deadArray = new JsonArray();
                for (SubscriptionInfo info : deadSubscriptions) {
                    JsonObject sub = new JsonObject()
                        .put("topic", info.topic())
                        .put("groupName", info.groupName())
                        .put("state", info.state().name())
                        .put("heartbeatTimeoutSeconds", info.heartbeatTimeoutSeconds());
                    if (info.lastHeartbeatAt() != null) {
                        sub.put("lastHeartbeatAt", info.lastHeartbeatAt().toString());
                    }
                    if (info.lastActiveAt() != null) {
                        sub.put("lastActiveAt", info.lastActiveAt().toString());
                    }
                    deadArray.add(sub);
                }
                JsonObject result = new JsonObject()
                    .put("deadSubscriptions", deadArray)
                    .put("totalDead", deadArray.size());
                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .end(result.encode());
            })
            .onFailure(error -> {
                logger.error("Failed to list dead subscriptions for setup: {}", setupId, error);
                ErrorResponse.send(ctx, 500,
                    PeeGeeQError.of(PeeGeeQErrorCodes.SUBSCRIPTION_ALERTS_FAILED,
                        "Failed to list dead subscriptions: " + error.getMessage()));
            });
    }

    /**
     * Returns subscription health summary.
     * GET /api/v1/setups/:setupId/consumer-alerts/summary
     */
    public void getHealthSummary(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");

        SubscriptionService service = setupService.getSubscriptionServiceForSetup(setupId);
        if (service == null) {
            ErrorResponse.notFound(ctx, PeeGeeQError.setupNotFound(setupId));
            return;
        }

        service.getSubscriptionHealthSummary()
            .onSuccess(summary -> {
                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .end(summary.encode());
            })
            .onFailure(error -> {
                logger.error("Failed to get health summary for setup: {}", setupId, error);
                ErrorResponse.send(ctx, 500,
                    PeeGeeQError.of(PeeGeeQErrorCodes.SUBSCRIPTION_ALERTS_FAILED,
                        "Failed to get subscription health summary: " + error.getMessage()));
            });
    }

    /**
     * Returns blocked message statistics.
     * GET /api/v1/setups/:setupId/consumer-alerts/blocked
     */
    public void getBlockedStats(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");

        SubscriptionService service = setupService.getSubscriptionServiceForSetup(setupId);
        if (service == null) {
            ErrorResponse.notFound(ctx, PeeGeeQError.setupNotFound(setupId));
            return;
        }

        service.getBlockedMessageStats()
            .onSuccess(stats -> {
                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .end(stats.encode());
            })
            .onFailure(error -> {
                logger.error("Failed to get blocked message stats for setup: {}", setupId, error);
                ErrorResponse.send(ctx, 500,
                    PeeGeeQError.of(PeeGeeQErrorCodes.SUBSCRIPTION_ALERTS_FAILED,
                        "Failed to get blocked message stats: " + error.getMessage()));
            });
    }
}
