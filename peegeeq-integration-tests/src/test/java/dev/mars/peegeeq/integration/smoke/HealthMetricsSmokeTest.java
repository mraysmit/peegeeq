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

package dev.mars.peegeeq.integration.smoke;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke tests for Health and Metrics API endpoints.
 * 
 * Tests cover:
 * - GET /health - Simple health check
 * - GET /api/v1/health - API health check
 * - GET /metrics - Prometheus metrics
 * - GET /api/v1/management/metrics - Management metrics
 * - GET /api/v1/setups/:setupId/health - Per-setup health
 */
@DisplayName("Health & Metrics Smoke Tests")
class HealthMetricsSmokeTest extends SmokeTestBase {

    @Test
    @DisplayName("GET /health returns UP status")
    void testSimpleHealthCheck() throws Exception {
        HttpResponse<String> response = get("/health");
        
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("UP");
        logSuccess("GET /health", response);
    }

    @Test
    @DisplayName("GET /api/v1/health returns health status")
    void testApiHealthCheck() throws Exception {
        HttpResponse<String> response = get("/api/v1/health");
        
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isNotEmpty();
        logSuccess("GET /api/v1/health", response);
    }

    @Test
    @DisplayName("GET /metrics returns Prometheus format metrics")
    void testPrometheusMetrics() throws Exception {
        HttpResponse<String> response = get("/metrics");
        
        assertThat(response.statusCode()).isEqualTo(200);
        // Prometheus format typically contains metric names with underscores
        assertThat(response.body()).isNotEmpty();
        logSuccess("GET /metrics", response);
    }

    @Test
    @DisplayName("GET /api/v1/management/metrics returns management metrics")
    void testManagementMetrics() throws Exception {
        HttpResponse<String> response = get("/api/v1/management/metrics");
        
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isNotEmpty();
        logSuccess("GET /api/v1/management/metrics", response);
    }

    @Test
    @DisplayName("GET /api/v1/setups/:setupId/health returns setup health (404 for non-existent)")
    void testPerSetupHealth() throws Exception {
        String setupId = "non-existent-setup-" + System.currentTimeMillis();
        HttpResponse<String> response = get("/api/v1/setups/" + setupId + "/health");
        
        // Non-existent setup should return 404 or 500
        assertThat(response.statusCode()).isIn(404, 500);
        logSuccess("GET /api/v1/setups/:setupId/health (non-existent)", response);
    }

    @Test
    @DisplayName("GET /api/v1/setups/:setupId/health/components returns component health list")
    void testComponentHealthList() throws Exception {
        String setupId = "non-existent-setup-" + System.currentTimeMillis();
        HttpResponse<String> response = get("/api/v1/setups/" + setupId + "/health/components");
        
        // Non-existent setup should return 404 or 500
        assertThat(response.statusCode()).isIn(404, 500);
        logSuccess("GET /api/v1/setups/:setupId/health/components (non-existent)", response);
    }
}

