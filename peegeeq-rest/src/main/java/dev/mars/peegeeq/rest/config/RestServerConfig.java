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

package dev.mars.peegeeq.rest.config;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, validated configuration for PeeGeeQ REST Server.
 * Parsed once at bootstrap, injected into verticle.
 * 
 * This follows Vert.x 5.x best practices:
 * - Configuration loaded via ConfigRetriever at bootstrap
 * - Parsed and validated once into typed, immutable record
 * - Injected into verticles (not accessed via globals/statics)
 * - Tests can easily create custom configs without file/env dependencies
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-12-30
 * @version 1.0
 */
public record RestServerConfig(
        int port,
        MonitoringConfig monitoring,
        List<String> allowedOrigins) {

    /**
     * Monitoring endpoint configuration for WebSocket and SSE metrics streaming.
     */
    public record MonitoringConfig(
            int maxConnections,
            int maxConnectionsPerIp,
            int defaultIntervalSeconds,
            int minIntervalSeconds,
            int maxIntervalSeconds,
            long idleTimeoutMs,
            long cacheTtlMs,
            long jitterMs) {
        /**
         * Validation in compact constructor.
         * Throws IllegalArgumentException if any values are invalid.
         */
        public MonitoringConfig {
            if (maxConnections <= 0) {
                throw new IllegalArgumentException("maxConnections must be positive");
            }
            if (maxConnectionsPerIp <= 0) {
                throw new IllegalArgumentException("maxConnectionsPerIp must be positive");
            }
            if (defaultIntervalSeconds <= 0) {
                throw new IllegalArgumentException("defaultIntervalSeconds must be positive");
            }
            if (minIntervalSeconds <= 0) {
                throw new IllegalArgumentException("minIntervalSeconds must be positive");
            }
            if (maxIntervalSeconds <= 0) {
                throw new IllegalArgumentException("maxIntervalSeconds must be positive");
            }
            if (minIntervalSeconds > defaultIntervalSeconds) {
                throw new IllegalArgumentException("minIntervalSeconds must be <= defaultIntervalSeconds");
            }
            if (defaultIntervalSeconds > maxIntervalSeconds) {
                throw new IllegalArgumentException("defaultIntervalSeconds must be <= maxIntervalSeconds");
            }
            if (idleTimeoutMs <= 0) {
                throw new IllegalArgumentException("idleTimeoutMs must be positive");
            }
            if (cacheTtlMs <= 0) {
                throw new IllegalArgumentException("cacheTtlMs must be positive");
            }
            if (jitterMs < 0) {
                throw new IllegalArgumentException("jitterMs must be positive");
            }
        }

        /**
         * Production defaults based on research and performance testing.
         * 
         * @return Default monitoring configuration
         */
        public static MonitoringConfig defaults() {
            return new MonitoringConfig(
                    1000, // maxConnections - reasonable for production
                    10, // maxConnectionsPerIp - prevent abuse
                    5, // defaultIntervalSeconds - balance freshness/load
                    1, // minIntervalSeconds - allow high-frequency when needed
                    60, // maxIntervalSeconds - don't let intervals get too stale
                    300000, // idleTimeoutMs - 5 minutes idle timeout
                    5000, // cacheTtlMs - 5 second cache for metrics
                    1000 // jitterMs - 1 second jitter to spread load
            );
        }
    }

    /**
     * Parse and validate configuration from JsonObject.
     * Called once at bootstrap after ConfigRetriever merges all sources.
     * 
     * @param json Merged configuration from ConfigRetriever
     * @return Validated, immutable configuration
     * @throws IllegalArgumentException if validation fails
     */
    public static RestServerConfig from(JsonObject json) {
        int port = json.getInteger("port", 8080);

        JsonObject monJson = json.getJsonObject("monitoring", new JsonObject());
        MonitoringConfig monitoring = new MonitoringConfig(
                monJson.getInteger("maxConnections", 1000),
                monJson.getInteger("maxConnectionsPerIp", 10),
                monJson.getInteger("defaultIntervalSeconds", 5),
                monJson.getInteger("minIntervalSeconds", 1),
                monJson.getInteger("maxIntervalSeconds", 60),
                monJson.getLong("idleTimeoutMs", 300000L),
                monJson.getLong("cacheTtlMs", 5000L),
                monJson.getLong("jitterMs", 1000L));

        JsonArray originsArray = json.getJsonArray("allowedOrigins");
        if (originsArray == null || originsArray.isEmpty()) {
            throw new IllegalArgumentException("allowedOrigins must be provided and non-empty");
        }
        List<String> allowedOrigins = originsArray.stream()
                .map(Object::toString)
                .toList();

        return new RestServerConfig(port, monitoring, allowedOrigins);
    }

    /**
     * Validation in compact constructor.
     * Throws IllegalArgumentException if port is invalid.
     */
    public RestServerConfig {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be 1-65535");
        }
        Objects.requireNonNull(monitoring, "monitoring config must not be null");
        Objects.requireNonNull(allowedOrigins, "allowedOrigins must not be null");
    }
}
