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

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.api.health.HealthService;
import dev.mars.peegeeq.api.health.HealthStatusInfo;
import dev.mars.peegeeq.api.health.OverallHealthInfo;
import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * REST handler for health check endpoints.
 *
 * Provides endpoints for:
 * - GET /api/v1/setups/:setupId/health - Overall health status
 * - GET /api/v1/setups/:setupId/health/components - List all component health
 * - GET /api/v1/setups/:setupId/health/components/:name - Get specific component health
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-12-05
 * @version 1.0
 */
public class HealthHandler {

    private static final Logger logger = LoggerFactory.getLogger(HealthHandler.class);

    private final DatabaseSetupService setupService;
    private final ObjectMapper objectMapper;

    public HealthHandler(DatabaseSetupService setupService, ObjectMapper objectMapper) {
        this.setupService = setupService;
        this.objectMapper = objectMapper;
    }

    /**
     * GET /api/v1/setups/:setupId/health
     * Returns overall health status for a setup.
     */
    public void getOverallHealth(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        logger.info("Getting overall health for setup: {}", setupId);

        HealthService healthService = setupService.getHealthServiceForSetup(setupId);
        if (healthService == null) {
            ctx.response()
                .setStatusCode(404)
                .putHeader("content-type", "application/json")
                .end(new JsonObject().put("error", "Setup not found: " + setupId).encode());
            return;
        }

        healthService.getOverallHealthAsync()
            .thenAccept(health -> {
                JsonObject response = overallHealthToJson(health);
                int statusCode = health.isHealthy() ? 200 : 503;
                ctx.response()
                    .setStatusCode(statusCode)
                    .putHeader("content-type", "application/json")
                    .end(response.encode());
            })
            .exceptionally(ex -> {
                logger.error("Failed to get overall health for setup: {}", setupId, ex);
                ctx.response()
                    .setStatusCode(500)
                    .putHeader("content-type", "application/json")
                    .end(new JsonObject().put("error", "Failed to get health: " + ex.getMessage()).encode());
                return null;
            });
    }

    /**
     * GET /api/v1/setups/:setupId/health/components
     * Returns health status of all components.
     */
    public void listComponentHealth(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        logger.info("Listing component health for setup: {}", setupId);

        HealthService healthService = setupService.getHealthServiceForSetup(setupId);
        if (healthService == null) {
            ctx.response()
                .setStatusCode(404)
                .putHeader("content-type", "application/json")
                .end(new JsonObject().put("error", "Setup not found: " + setupId).encode());
            return;
        }

        healthService.getOverallHealthAsync()
            .thenAccept(health -> {
                JsonArray components = new JsonArray();
                health.components().forEach((name, status) -> {
                    components.add(componentHealthToJson(status));
                });
                ctx.response()
                    .setStatusCode(200)
                    .putHeader("content-type", "application/json")
                    .end(components.encode());
            })
            .exceptionally(ex -> {
                logger.error("Failed to list component health for setup: {}", setupId, ex);
                ctx.response()
                    .setStatusCode(500)
                    .putHeader("content-type", "application/json")
                    .end(new JsonObject().put("error", "Failed to list components: " + ex.getMessage()).encode());
                return null;
            });
    }

    /**
     * GET /api/v1/setups/:setupId/health/components/:name
     * Returns health status of a specific component.
     */
    public void getComponentHealth(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        String componentName = ctx.pathParam("name");
        logger.info("Getting component health for setup: {}, component: {}", setupId, componentName);

        HealthService healthService = setupService.getHealthServiceForSetup(setupId);
        if (healthService == null) {
            ctx.response()
                .setStatusCode(404)
                .putHeader("content-type", "application/json")
                .end(new JsonObject().put("error", "Setup not found: " + setupId).encode());
            return;
        }

        healthService.getComponentHealthAsync(componentName)
            .thenAccept(status -> {
                if (status == null) {
                    ctx.response()
                        .setStatusCode(404)
                        .putHeader("content-type", "application/json")
                        .end(new JsonObject().put("error", "Component not found: " + componentName).encode());
                } else {
                    JsonObject response = componentHealthToJson(status);
                    int statusCode = status.isHealthy() ? 200 : (status.isDegraded() ? 200 : 503);
                    ctx.response()
                        .setStatusCode(statusCode)
                        .putHeader("content-type", "application/json")
                        .end(response.encode());
                }
            })
            .exceptionally(ex -> {
                logger.error("Failed to get component health: {}/{}", setupId, componentName, ex);
                ctx.response()
                    .setStatusCode(500)
                    .putHeader("content-type", "application/json")
                    .end(new JsonObject().put("error", "Failed to get component: " + ex.getMessage()).encode());
                return null;
            });
    }

    // Helper methods for JSON conversion

    private JsonObject overallHealthToJson(OverallHealthInfo health) {
        JsonObject json = new JsonObject()
            .put("healthy", health.isHealthy())
            .put("status", health.status())
            .put("timestamp", health.timestamp().toString())
            .put("componentCount", health.getComponentCount())
            .put("healthyCount", health.getHealthyCount())
            .put("degradedCount", health.getDegradedCount())
            .put("unhealthyCount", health.getUnhealthyCount());

        JsonObject components = new JsonObject();
        health.components().forEach((name, componentStatus) -> {
            components.put(name, componentHealthToJson(componentStatus));
        });
        json.put("components", components);

        return json;
    }

    private JsonObject componentHealthToJson(HealthStatusInfo status) {
        JsonObject json = new JsonObject()
            .put("component", status.component())
            .put("state", status.state().name())
            .put("timestamp", status.timestamp().toString());

        if (status.message() != null) {
            json.put("message", status.message());
        }

        if (status.details() != null && !status.details().isEmpty()) {
            JsonObject details = new JsonObject();
            status.details().forEach((key, value) -> {
                if (value != null) {
                    details.put(key, value.toString());
                }
            });
            json.put("details", details);
        }

        return json;
    }
}
