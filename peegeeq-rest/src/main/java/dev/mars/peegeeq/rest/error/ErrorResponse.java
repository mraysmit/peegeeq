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
package dev.mars.peegeeq.rest.error;

import dev.mars.peegeeq.api.error.PeeGeeQError;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;

/**
 * Utility class for sending standardized error responses in REST handlers.
 */
public final class ErrorResponse {

    private ErrorResponse() {
        // Utility class - no instantiation
    }

    /**
     * Sends an error response with the given PeeGeeQError.
     *
     * @param ctx        The routing context
     * @param statusCode HTTP status code
     * @param error      The PeeGeeQ error
     */
    public static void send(RoutingContext ctx, int statusCode, PeeGeeQError error) {
        JsonObject json = new JsonObject()
            .put("code", error.code())
            .put("error", error.message())
            .put("timestamp", error.timestamp().toEpochMilli());

        if (error.details() != null) {
            json.put("details", error.details());
        }

        ctx.response()
            .setStatusCode(statusCode)
            .putHeader("Content-Type", "application/json")
            .end(json.encode());
    }

    /**
     * Sends a 400 Bad Request error.
     */
    public static void badRequest(RoutingContext ctx, PeeGeeQError error) {
        send(ctx, 400, error);
    }

    /**
     * Sends a 404 Not Found error.
     */
    public static void notFound(RoutingContext ctx, PeeGeeQError error) {
        send(ctx, 404, error);
    }

    /**
     * Sends a 500 Internal Server Error.
     */
    public static void internalError(RoutingContext ctx, PeeGeeQError error) {
        send(ctx, 500, error);
    }

    /**
     * Sends a 409 Conflict error.
     */
    public static void conflict(RoutingContext ctx, PeeGeeQError error) {
        send(ctx, 409, error);
    }

    /**
     * Sends a 503 Service Unavailable error.
     */
    public static void serviceUnavailable(RoutingContext ctx, PeeGeeQError error) {
        send(ctx, 503, error);
    }

    /**
     * Converts a PeeGeeQError to a JsonObject for embedding in other responses.
     */
    public static JsonObject toJson(PeeGeeQError error) {
        JsonObject json = new JsonObject()
            .put("code", error.code())
            .put("error", error.message())
            .put("timestamp", error.timestamp().toEpochMilli());

        if (error.details() != null) {
            json.put("details", error.details());
        }

        return json;
    }
}

