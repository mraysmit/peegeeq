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
 * Smoke tests for Webhook Subscription API endpoints.
 * 
 * Tests cover:
 * - POST /api/v1/setups/:setupId/queues/:queueName/webhook-subscriptions - Create webhook
 * - GET /api/v1/webhook-subscriptions/:subscriptionId - Get webhook subscription
 * - DELETE /api/v1/webhook-subscriptions/:subscriptionId - Delete webhook subscription
 */
@DisplayName("Webhook Subscription Smoke Tests")
class WebhookSubscriptionSmokeTest extends SmokeTestBase {

    @Test
    @DisplayName("POST webhook subscription requires webhookUrl")
    void testCreateWebhookRequiresUrl() throws Exception {
        String setupId = "test-setup-" + System.currentTimeMillis();
        String queueName = "test-queue";
        
        // Missing webhookUrl should return 400
        String body = "{}";
        HttpResponse<String> response = post(
            "/api/v1/setups/" + setupId + "/queues/" + queueName + "/webhook-subscriptions",
            body
        );
        
        // Should return 400 for missing webhookUrl, or 404/500 if setup doesn't exist
        assertThat(response.statusCode()).isIn(400, 404, 500);
        logSuccess("POST webhook subscription (missing url)", response);
    }

    @Test
    @DisplayName("POST webhook subscription with invalid setup returns error")
    void testCreateWebhookInvalidSetup() throws Exception {
        String setupId = "non-existent-setup-" + System.currentTimeMillis();
        String queueName = "test-queue";
        
        String body = "{\"webhookUrl\": \"http://localhost:9999/webhook\"}";
        HttpResponse<String> response = post(
            "/api/v1/setups/" + setupId + "/queues/" + queueName + "/webhook-subscriptions",
            body
        );
        
        // Non-existent setup should return 404 or 500
        assertThat(response.statusCode()).isIn(404, 500);
        logSuccess("POST webhook subscription (invalid setup)", response);
    }

    @Test
    @DisplayName("GET webhook subscription returns 404 for non-existent")
    void testGetWebhookSubscriptionNotFound() throws Exception {
        String subscriptionId = "non-existent-subscription-" + System.currentTimeMillis();
        HttpResponse<String> response = get("/api/v1/webhook-subscriptions/" + subscriptionId);
        
        assertThat(response.statusCode()).isEqualTo(404);
        logSuccess("GET /api/v1/webhook-subscriptions/:id (non-existent)", response);
    }

    @Test
    @DisplayName("DELETE webhook subscription returns 404 for non-existent")
    void testDeleteWebhookSubscriptionNotFound() throws Exception {
        String subscriptionId = "non-existent-subscription-" + System.currentTimeMillis();
        HttpResponse<String> response = delete("/api/v1/webhook-subscriptions/" + subscriptionId);
        
        assertThat(response.statusCode()).isEqualTo(404);
        logSuccess("DELETE /api/v1/webhook-subscriptions/:id (non-existent)", response);
    }
}

