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

import dev.mars.peegeeq.integration.SmokeTestBase;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.*;

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
@Tag(TestCategories.SMOKE)
@ExtendWith(VertxExtension.class)
@DisplayName("Health & Metrics Smoke Tests")
class HealthMetricsSmokeTest extends SmokeTestBase {

    @Test
    @DisplayName("GET /health returns UP status")
    void testSimpleHealthCheck(VertxTestContext testContext) {
        webClient.get(REST_PORT, REST_HOST, "/health")
            .send()
            .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                assertEquals(200, response.statusCode());
                assertTrue(response.bodyAsString().contains("UP"));
                logger.info("GET /health returned: {}", response.bodyAsString());
                testContext.completeNow();
            })));
    }

    @Test
    @DisplayName("GET /api/v1/health returns health status")
    void testApiHealthCheck(VertxTestContext testContext) {
        webClient.get(REST_PORT, REST_HOST, "/api/v1/health")
            .send()
            .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                assertEquals(200, response.statusCode());
                assertNotNull(response.bodyAsString());
                logger.info("GET /api/v1/health returned: {}", response.bodyAsString());
                testContext.completeNow();
            })));
    }

    @Test
    @DisplayName("GET /metrics returns Prometheus format metrics")
    void testPrometheusMetrics(VertxTestContext testContext) {
        webClient.get(REST_PORT, REST_HOST, "/metrics")
            .send()
            .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                assertEquals(200, response.statusCode());
                assertNotNull(response.bodyAsString());
                logger.info("GET /metrics returned {} bytes", response.bodyAsString().length());
                testContext.completeNow();
            })));
    }

    @Test
    @DisplayName("GET /api/v1/management/metrics returns management metrics")
    void testManagementMetrics(VertxTestContext testContext) {
        webClient.get(REST_PORT, REST_HOST, "/api/v1/management/metrics")
            .send()
            .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                assertEquals(200, response.statusCode());
                assertNotNull(response.bodyAsString());
                logger.info("GET /api/v1/management/metrics returned: {}", response.bodyAsString());
                testContext.completeNow();
            })));
    }

    @Test
    @DisplayName("GET /api/v1/setups/:setupId/health returns 404 for non-existent setup")
    void testPerSetupHealth(VertxTestContext testContext) {
        String setupId = "non-existent-setup-" + System.currentTimeMillis();

        // INTENTIONAL FAILURE TEST: Getting health for non-existent setup to verify 404 response
        logger.info("INTENTIONAL FAILURE TEST: Getting health for non-existent setup {} (expecting 404)", setupId);
        webClient.get(REST_PORT, REST_HOST, "/api/v1/setups/" + setupId + "/health")
            .send()
            .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                JsonObject body = response.bodyAsJsonObject();
                assertEquals(404, response.statusCode(), "Expected 404 for non-existent setup");
                assertNotNull(body, "Response body should not be null");
                assertTrue(body.containsKey("error"), "Response should contain 'error' field");
                logger.info("GET /api/v1/setups/:setupId/health (non-existent) returned: {} - {}", response.statusCode(), body);
                testContext.completeNow();
            })));
    }

    @Test
    @DisplayName("GET /api/v1/setups/:setupId/health/components returns 404 for non-existent setup")
    void testComponentHealthList(VertxTestContext testContext) {
        String setupId = "non-existent-setup-" + System.currentTimeMillis();

        // INTENTIONAL FAILURE TEST: Getting component health for non-existent setup to verify 404 response
        logger.info("INTENTIONAL FAILURE TEST: Getting component health for non-existent setup {} (expecting 404)", setupId);
        webClient.get(REST_PORT, REST_HOST, "/api/v1/setups/" + setupId + "/health/components")
            .send()
            .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                JsonObject body = response.bodyAsJsonObject();
                assertEquals(404, response.statusCode(), "Expected 404 for non-existent setup");
                assertNotNull(body, "Response body should not be null");
                assertTrue(body.containsKey("error"), "Response should contain 'error' field");
                logger.info("GET /api/v1/setups/:setupId/health/components (non-existent) returned: {} - {}", response.statusCode(), body);
                testContext.completeNow();
            })));
    }
}

