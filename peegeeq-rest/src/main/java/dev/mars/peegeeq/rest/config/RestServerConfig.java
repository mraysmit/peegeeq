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

import io.vertx.core.json.JsonObject;

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
    MonitoringConfig monitoring
) {
    
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
        long jitterMs
    ) {
        /**
         * Validation in compact constructor.
         * Throws IllegalArgumentException if any values are invalid.
         */
        public MonitoringConfig {
            if (maxConnections <= 0) {
                throw new IllegalArgumentException("maxConnections must be > 0, got: " + maxConnections);
            }
            if (maxConnectionsPerIp <= 0) {
                throw new IllegalArgumentException("maxConnectionsPerIp must be > 0, got: " + maxConnectionsPerIp);
            }
            if (minIntervalSeconds < 1) {
                throw new IllegalArgumentException("minIntervalSeconds must be >= 1, got: " + minIntervalSeconds);
            }
            if (maxIntervalSeconds < minIntervalSeconds) {
                throw new IllegalArgumentException(
                    "maxIntervalSeconds must be >= minIntervalSeconds, got max=" + maxIntervalSeconds + 
                    " min=" + minIntervalSeconds
                );
            }
            if (defaultIntervalSeconds < minIntervalSeconds || defaultIntervalSeconds > maxIntervalSeconds) {
                throw new IllegalArgumentException(
                    "defaultIntervalSeconds must be between min and max, got default=" + defaultIntervalSeconds +
                    " min=" + minIntervalSeconds + " max=" + maxIntervalSeconds
                );
            }
            if (idleTimeoutMs < 0) {
                throw new IllegalArgumentException("idleTimeoutMs must be >= 0, got: " + idleTimeoutMs);
            }
            if (cacheTtlMs < 0) {
                throw new IllegalArgumentException("cacheTtlMs must be >= 0, got: " + cacheTtlMs);
            }
            if (jitterMs < 0) {
                throw new IllegalArgumentException("jitterMs must be >= 0, got: " + jitterMs);
            }
        }
        
        /**
         * Production defaults based on research and performance testing.
         * 
         * @return Default monitoring configuration
         */
        public static MonitoringConfig defaults() {
            return new MonitoringConfig(
                1000,    // maxConnections - reasonable for production
                10,      // maxConnectionsPerIp - prevent abuse
                5,       // defaultIntervalSeconds - balance freshness/load
                1,       // minIntervalSeconds - allow high-frequency when needed
                60,      // maxIntervalSeconds - don't let intervals get too stale
                300000,  // idleTimeoutMs - 5 minutes idle timeout
                5000,    // cacheTtlMs - 5 second cache for metrics
                1000     // jitterMs - 1 second jitter to spread load
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
            monJson.getLong("jitterMs", 1000L)
        );
        
        return new RestServerConfig(port, monitoring);
    }
    
    /**
     * Validation in compact constructor.
     * Throws IllegalArgumentException if port is invalid.
     */
    public RestServerConfig {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be 1-65535, got: " + port);
        }
    }
}
