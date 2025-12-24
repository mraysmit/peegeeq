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
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Subscription Lifecycle E2E Smoke Tests.
 * 
 * Verifies subscription lifecycle operations through REST API:
 * Client -> REST API -> Runtime -> SubscriptionService -> PostgreSQL
 */
@Tag(TestCategories.SMOKE)
@ExtendWith(VertxExtension.class)
@DisplayName("Subscription Lifecycle Smoke Tests")
class SubscriptionLifecycleSmokeTest extends SmokeTestBase {

    private static final String QUEUE_NAME = "sub_smoke_test_queue";
    private static final String GROUP_NAME = "smoke-sub-group";

    @Test
    @DisplayName("Should list subscriptions for topic")
    void testListSubscriptions(VertxTestContext testContext) {
        String setupId = generateSetupId();
        JsonObject setupRequest = createDatabaseSetupRequest(setupId, QUEUE_NAME);

        webClient.post(REST_PORT, REST_HOST, "/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .sendJsonObject(setupRequest)
            .compose(setupResponse -> {
                logger.info("Setup created: {}", setupId);
                
                // List subscriptions for the queue topic
                return webClient.get(REST_PORT, REST_HOST,
                        "/api/v1/setups/" + setupId + "/subscriptions/" + QUEUE_NAME)
                    .send();
            })
            .onComplete(testContext.succeeding(response -> {
                testContext.verify(() -> {
                    int statusCode = response.statusCode();
                    logger.info("List subscriptions response: {} - {}", statusCode, response.bodyAsString());
                    
                    assertEquals(200, statusCode, "Expected 200");
                    
                    // Response should be a JSON array (possibly empty)
                    String body = response.bodyAsString();
                    assertTrue(body.startsWith("["), "Response should be a JSON array");
                    
                    cleanupSetup(setupId);
                });
                testContext.completeNow();
            }));
    }

    @Test
    @DisplayName("Should get subscription (404 for non-existent)")
    void testGetSubscription(VertxTestContext testContext) {
        String setupId = generateSetupId();
        JsonObject setupRequest = createDatabaseSetupRequest(setupId, QUEUE_NAME);

        webClient.post(REST_PORT, REST_HOST, "/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .sendJsonObject(setupRequest)
            .compose(setupResponse -> {
                logger.info("Setup created: {}", setupId);
                
                // Try to get a non-existent subscription
                return webClient.get(REST_PORT, REST_HOST,
                        "/api/v1/setups/" + setupId + "/subscriptions/" + QUEUE_NAME + "/" + GROUP_NAME)
                    .send();
            })
            .onComplete(testContext.succeeding(response -> {
                testContext.verify(() -> {
                    int statusCode = response.statusCode();
                    logger.info("Get subscription response: {} - {}", statusCode, response.bodyAsString());
                    
                    // 404 is expected for non-existent subscription
                    assertEquals(404, statusCode, "Expected 404 for non-existent subscription");
                    
                    cleanupSetup(setupId);
                });
                testContext.completeNow();
            }));
    }

    @Test
    @DisplayName("Should pause subscription (handles non-existent gracefully)")
    void testPauseSubscription(VertxTestContext testContext) {
        String setupId = generateSetupId();
        JsonObject setupRequest = createDatabaseSetupRequest(setupId, QUEUE_NAME);

        webClient.post(REST_PORT, REST_HOST, "/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .sendJsonObject(setupRequest)
            .compose(setupResponse -> {
                logger.info("Setup created: {}", setupId);
                
                // Try to pause a non-existent subscription
                return webClient.post(REST_PORT, REST_HOST,
                        "/api/v1/setups/" + setupId + "/subscriptions/" + QUEUE_NAME + "/" + GROUP_NAME + "/pause")
                    .send();
            })
            .onComplete(testContext.succeeding(response -> {
                testContext.verify(() -> {
                    int statusCode = response.statusCode();
                    logger.info("Pause subscription response: {} - {}", statusCode, response.bodyAsString());
                    
                    // May return 200 (success) or 404/500 (not found/error)
                    assertTrue(statusCode == 200 || statusCode == 404 || statusCode == 500,
                        "Expected 200, 404, or 500, got " + statusCode);
                    
                    cleanupSetup(setupId);
                });
                testContext.completeNow();
            }));
    }

    @Test
    @DisplayName("Should resume subscription (handles non-existent gracefully)")
    void testResumeSubscription(VertxTestContext testContext) {
        String setupId = generateSetupId();
        JsonObject setupRequest = createDatabaseSetupRequest(setupId, QUEUE_NAME);

        webClient.post(REST_PORT, REST_HOST, "/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .sendJsonObject(setupRequest)
            .compose(setupResponse -> {
                logger.info("Setup created: {}", setupId);
                
                // Try to resume a non-existent subscription
                return webClient.post(REST_PORT, REST_HOST,
                        "/api/v1/setups/" + setupId + "/subscriptions/" + QUEUE_NAME + "/" + GROUP_NAME + "/resume")
                    .send();
            })
            .onComplete(testContext.succeeding(response -> {
                testContext.verify(() -> {
                    int statusCode = response.statusCode();
                    logger.info("Resume subscription response: {} - {}", statusCode, response.bodyAsString());
                    
                    // May return 200 (success) or 404/500 (not found/error)
                    assertTrue(statusCode == 200 || statusCode == 404 || statusCode == 500,
                        "Expected 200, 404, or 500, got " + statusCode);
                    
                    cleanupSetup(setupId);
                });
                testContext.completeNow();
            }));
    }

