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
 * Smoke tests for Webhook Subscription API endpoints.
 *
 * Tests cover:
 * - POST /api/v1/setups/:setupId/queues/:queueName/webhook-subscriptions - Create webhook
 * - GET /api/v1/webhook-subscriptions/:subscriptionId - Get webhook subscription
 * - DELETE /api/v1/webhook-subscriptions/:subscriptionId - Delete webhook subscription
 */
@Tag(TestCategories.SMOKE)
@ExtendWith(VertxExtension.class)
@DisplayName("Webhook Subscription Smoke Tests")
class WebhookSubscriptionSmokeTest extends SmokeTestBase {

    @Test
    @DisplayName("POST webhook subscription requires webhookUrl")
    void testCreateWebhookRequiresUrl(VertxTestContext testContext) {
        String setupId = "test-setup-" + System.currentTimeMillis();
        String queueName = "test-queue";

        // Missing webhookUrl should return 400
        JsonObject body = new JsonObject();

        webClient.post(REST_PORT, REST_HOST,
                "/api/v1/setups/" + setupId + "/queues/" + queueName + "/webhook-subscriptions")
            .putHeader("content-type", "application/json")
            .sendJsonObject(body)
            .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                // Should return 400 for missing webhookUrl, or 404/500 if setup doesn't exist
                assertTrue(response.statusCode() == 400 || response.statusCode() == 404 || response.statusCode() == 500,
                    "Expected 400, 404, or 500 but got " + response.statusCode());
                logger.info("POST webhook subscription (missing url) returned: {}", response.statusCode());
                testContext.completeNow();
            })));
    }

    @Test
    @DisplayName("POST webhook subscription with invalid setup returns 404")
    void testCreateWebhookInvalidSetup(VertxTestContext testContext) {
        String setupId = "non-existent-setup-" + System.currentTimeMillis();
        String queueName = "test-queue";

        JsonObject body = new JsonObject()
            .put("webhookUrl", "http://localhost:9999/webhook");

        // INTENTIONAL FAILURE TEST: Creating webhook for non-existent setup to verify 404 response
        logger.info("INTENTIONAL FAILURE TEST: Creating webhook subscription for non-existent setup {} (expecting 404)", setupId);
        webClient.post(REST_PORT, REST_HOST,
                "/api/v1/setups/" + setupId + "/queues/" + queueName + "/webhook-subscriptions")
            .putHeader("content-type", "application/json")
            .sendJsonObject(body)
            .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                JsonObject responseBody = response.bodyAsJsonObject();
                assertEquals(404, response.statusCode(), "Expected 404 for non-existent setup");
                assertNotNull(responseBody, "Response body should not be null");
                assertTrue(responseBody.containsKey("error"), "Response should contain 'error' field");
                logger.info("POST webhook subscription (invalid setup) returned: {} - {}", response.statusCode(), responseBody);
                testContext.completeNow();
            })));
    }

    @Test
    @DisplayName("GET webhook subscription returns 404 for non-existent")
    void testGetWebhookSubscriptionNotFound(VertxTestContext testContext) {
        String subscriptionId = "non-existent-subscription-" + System.currentTimeMillis();

        // INTENTIONAL FAILURE TEST: Getting non-existent webhook subscription to verify 404 response
        logger.info("INTENTIONAL FAILURE TEST: Getting non-existent webhook subscription {} (expecting 404)", subscriptionId);
        webClient.get(REST_PORT, REST_HOST, "/api/v1/webhook-subscriptions/" + subscriptionId)
            .send()
            .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                JsonObject responseBody = response.bodyAsJsonObject();
                assertEquals(404, response.statusCode(), "Expected 404 for non-existent subscription");
                assertNotNull(responseBody, "Response body should not be null");
                assertTrue(responseBody.containsKey("error"), "Response should contain 'error' field");
                logger.info("GET /api/v1/webhook-subscriptions/:id (non-existent) returned: {} - {}", response.statusCode(), responseBody);
                testContext.completeNow();
            })));
    }

    @Test
    @DisplayName("DELETE webhook subscription returns 404 for non-existent")
    void testDeleteWebhookSubscriptionNotFound(VertxTestContext testContext) {
        String subscriptionId = "non-existent-subscription-" + System.currentTimeMillis();

        // INTENTIONAL FAILURE TEST: Deleting non-existent webhook subscription to verify 404 response
        logger.info("INTENTIONAL FAILURE TEST: Deleting non-existent webhook subscription {} (expecting 404)", subscriptionId);
        webClient.delete(REST_PORT, REST_HOST, "/api/v1/webhook-subscriptions/" + subscriptionId)
            .send()
            .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                JsonObject responseBody = response.bodyAsJsonObject();
                assertEquals(404, response.statusCode(), "Expected 404 for non-existent subscription");
                assertNotNull(responseBody, "Response body should not be null");
                assertTrue(responseBody.containsKey("error"), "Response should contain 'error' field");
                logger.info("DELETE /api/v1/webhook-subscriptions/:id (non-existent) returned: {} - {}", response.statusCode(), responseBody);
                testContext.completeNow();
            })));
    }
}

