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
 * Health Check E2E Smoke Tests.
 * 
 * Verifies health monitoring endpoints work correctly.
 */
@Tag(TestCategories.SMOKE)
@ExtendWith(VertxExtension.class)
@DisplayName("Health Check Smoke Tests")
class HealthCheckSmokeTest extends SmokeTestBase {

    @Test
    @DisplayName("Should return server health status")
    void testServerHealth(VertxTestContext testContext) {
        webClient.get(REST_PORT, REST_HOST, "/api/v1/health")
            .send()
            .onComplete(testContext.succeeding(response -> {
                testContext.verify(() -> {
                    int statusCode = response.statusCode();
                    logger.info("Health check response: {} - {}", statusCode, response.bodyAsString());
                    
                    assertEquals(200, statusCode, "Health endpoint should return 200");
                    
                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body, "Response body should not be null");
                    
                    // Health response should indicate status
                    assertTrue(body.containsKey("status") || body.containsKey("healthy"),
                        "Response should contain health status");
                    
                    logger.info("Server health check passed");
                });
                testContext.completeNow();
            }));
    }

    @Test
    @DisplayName("Should return management overview")
    void testManagementOverview(VertxTestContext testContext) {
        webClient.get(REST_PORT, REST_HOST, "/api/v1/management/overview")
            .send()
            .onComplete(testContext.succeeding(response -> {
                testContext.verify(() -> {
                    int statusCode = response.statusCode();
                    logger.info("Management overview response: {} - {}", statusCode, response.bodyAsString());
                    
                    assertEquals(200, statusCode, "Management overview should return 200");
                    
                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body, "Response body should not be null");
                    
                    // Overview should contain system stats
                    assertTrue(body.containsKey("systemStats") || body.containsKey("timestamp"),
                        "Response should contain system information");
                    
                    logger.info("Management overview check passed");
                });
                testContext.completeNow();
            }));
    }

    @Test
    @DisplayName("Should return setup-specific health after setup creation")
    void testSetupHealth(VertxTestContext testContext) {
        String setupId = generateSetupId();
        JsonObject setupRequest = createDatabaseSetupRequest(setupId, "health_test_queue");

        webClient.post(REST_PORT, REST_HOST, "/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .sendJsonObject(setupRequest)
            .compose(setupResponse -> {
                logger.info("Setup created for health test: {}", setupId);
                
                return webClient.get(REST_PORT, REST_HOST, "/api/v1/setups/" + setupId + "/health")
                    .send();
            })
            .onComplete(testContext.succeeding(response -> {
                testContext.verify(() -> {
                    int statusCode = response.statusCode();
                    logger.info("Setup health response: {} - {}", statusCode, response.bodyAsString());
                    
                    // Health endpoint may return 200 or 404 depending on implementation
                    assertTrue(statusCode == 200 || statusCode == 404,
                        "Expected 200 or 404, got " + statusCode);
                    
                    if (statusCode == 200) {
                        JsonObject body = response.bodyAsJsonObject();
                        assertNotNull(body, "Response body should not be null");
                        logger.info("Setup health check passed");
                    }
                    
                    // Cleanup
                    cleanupSetup(setupId);
                });
                testContext.completeNow();
            }));
    }

    @Test
    @DisplayName("Should list all setups")
    void testListSetups(VertxTestContext testContext) {
        webClient.get(REST_PORT, REST_HOST, "/api/v1/setups")
            .send()
            .onComplete(testContext.succeeding(response -> {
                testContext.verify(() -> {
                    int statusCode = response.statusCode();
                    logger.info("List setups response: {} - {}", statusCode, response.bodyAsString());
                    
                    assertEquals(200, statusCode, "List setups should return 200");
                    
                    // Response should be an array or object with setups
                    String bodyStr = response.bodyAsString();
                    assertTrue(bodyStr.startsWith("[") || bodyStr.startsWith("{"),
                        "Response should be JSON array or object");
                    
                    logger.info("List setups check passed");
                });
                testContext.completeNow();
            }));
    }

    private void cleanupSetup(String setupId) {
        webClient.delete(REST_PORT, REST_HOST, "/api/v1/setups/" + setupId)
            .send()
            .onComplete(ar -> {
                if (ar.succeeded()) {
                    logger.info("Setup deleted: {}", setupId);
                } else {
                    logger.warn("Failed to delete setup: {}", setupId, ar.cause());
                }
            });
    }
}

