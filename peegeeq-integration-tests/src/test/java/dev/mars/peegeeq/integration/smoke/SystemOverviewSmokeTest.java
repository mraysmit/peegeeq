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
 * Smoke tests for System Overview and Management Dashboard API endpoints.
 *
 * Tests cover:
 * - GET /api/v1/management/overview - System overview dashboard
 * - GET /api/v1/management/queues - Queue list for management
 * - GET /api/v1/management/consumer-groups - Consumer group list
 * - GET /api/v1/management/event-stores - Event store list
 * - GET /api/v1/management/messages - Recent messages
 */
@Tag(TestCategories.SMOKE)
@ExtendWith(VertxExtension.class)
@DisplayName("System Overview Smoke Tests")
class SystemOverviewSmokeTest extends SmokeTestBase {

    @Test
    @DisplayName("GET /api/v1/management/overview returns system overview")
    void testSystemOverview(VertxTestContext testContext) {
        webClient.get("/api/v1/management/overview")
            .send()
            .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                assertEquals(200, response.statusCode());
                assertTrue(response.bodyAsString().contains("systemStats"));
                assertTrue(response.bodyAsString().contains("timestamp"));
                logger.info("GET /api/v1/management/overview returned: {}", response.bodyAsString());
                testContext.completeNow();
            })));
    }

    @Test
    @DisplayName("GET /api/v1/management/queues returns queue list with proper structure")
    void testManagementQueues(VertxTestContext testContext) {
        webClient.get("/api/v1/management/queues")
            .send()
            .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                assertEquals(200, response.statusCode());

                JsonObject body = response.bodyAsJsonObject();
                assertNotNull(body, "Response body should be a JSON object");
                assertTrue(body.containsKey("queues"), "Response should contain 'queues' array");

                JsonArray queues = body.getJsonArray("queues");
                assertNotNull(queues, "Queues array should not be null");

                logger.info("GET /api/v1/management/queues returned {} queues", queues.size());

                // If there are queues, validate their structure matches frontend expectations
                if (queues.size() > 0) {
                    for (int i = 0; i < queues.size(); i++) {
                        JsonObject queue = queues.getJsonObject(i);
                        validateQueueStructure(queue, "Queue at index " + i);
                    }
                    logger.info("✅ All queue objects have valid structure matching frontend contract");
                }

                testContext.completeNow();
            })));
    }

    @Test
    @DisplayName("GET /api/v1/management/consumer-groups returns consumer group list")
    void testManagementConsumerGroups(VertxTestContext testContext) {
        webClient.get("/api/v1/management/consumer-groups")
            .send()
            .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                assertEquals(200, response.statusCode());
                assertNotNull(response.bodyAsString());
                logger.info("GET /api/v1/management/consumer-groups returned: {}", response.bodyAsString());
                testContext.completeNow();
            })));
    }

    @Test
    @DisplayName("GET /api/v1/management/event-stores returns event store list")
    void testManagementEventStores(VertxTestContext testContext) {
        webClient.get("/api/v1/management/event-stores")
            .send()
            .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                assertEquals(200, response.statusCode());
                assertNotNull(response.bodyAsString());
                logger.info("GET /api/v1/management/event-stores returned: {}", response.bodyAsString());
                testContext.completeNow();
            })));
    }

    @Test
    @DisplayName("GET /api/v1/management/messages returns recent messages")
    void testManagementMessages(VertxTestContext testContext) {
        webClient.get("/api/v1/management/messages")
            .send()
            .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                assertEquals(200, response.statusCode());
                assertNotNull(response.bodyAsString());
                logger.info("GET /api/v1/management/messages returned: {}", response.bodyAsString());
                testContext.completeNow();
            })));
    }

    /**
     * Validates that a queue object has the structure expected by the frontend.
     * This ensures the backend API contract matches what the UI needs.
     *
     * Expected structure (as returned by backend):
     * {
     *   "name": "string",
     *   "setup": "string",
     *   "implementationType": "string",
     *   "status": "string",
     *   "statistics": {
     *     "totalMessages": number,
     *     "activeConsumers": number,
     *     "messagesPerSecond": number,
     *     "avgProcessingTimeMs": number
     *   }
     * }
     */
    private void validateQueueStructure(JsonObject queue, String context) {
        // Validate top-level required fields (as actually returned by backend)
        assertNotNull(queue.getString("name"), context + ": Queue must have 'name' field");
        assertNotNull(queue.getString("setup"), context + ": Queue must have 'setup' field");
        assertNotNull(queue.getString("implementationType"), context + ": Queue must have 'implementationType' field");
        assertNotNull(queue.getString("status"), context + ": Queue must have 'status' field");

        // Validate nested statistics object - THIS IS CRITICAL FOR FRONTEND
        assertTrue(queue.containsKey("statistics"),
            context + ": Queue must have 'statistics' object (not flat fields)");

        JsonObject statistics = queue.getJsonObject("statistics");
        assertNotNull(statistics, context + ": 'statistics' must be a JSON object, not null");

        // Validate all statistics fields that the frontend uses
        assertTrue(statistics.containsKey("totalMessages"),
            context + ": statistics.totalMessages is required by frontend");
        assertTrue(statistics.containsKey("activeConsumers"),
            context + ": statistics.activeConsumers is required by frontend");
        assertTrue(statistics.containsKey("messagesPerSecond"),
            context + ": statistics.messagesPerSecond is required by frontend");
        assertTrue(statistics.containsKey("avgProcessingTimeMs"),
            context + ": statistics.avgProcessingTimeMs is required by frontend");

        // Validate types to ensure frontend won't get runtime errors
        assertNotNull(statistics.getValue("totalMessages"),
            context + ": statistics.totalMessages must not be null");
        assertNotNull(statistics.getValue("activeConsumers"),
            context + ": statistics.activeConsumers must not be null");
        assertNotNull(statistics.getValue("messagesPerSecond"),
            context + ": statistics.messagesPerSecond must not be null");
        assertNotNull(statistics.getValue("avgProcessingTimeMs"),
            context + ": statistics.avgProcessingTimeMs must not be null");

        // Ensure no flat statistics fields exist (common mistake)
        assertFalse(queue.containsKey("totalMessages"),
            context + ": Queue should NOT have flat 'totalMessages' field - must be in statistics object");
        assertFalse(queue.containsKey("activeConsumers"),
            context + ": Queue should NOT have flat 'activeConsumers' field - must be in statistics object");
        assertFalse(queue.containsKey("messagesPerSecond"),
            context + ": Queue should NOT have flat 'messagesPerSecond' field - must be in statistics object");
        assertFalse(queue.containsKey("avgProcessingTimeMs"),
            context + ": Queue should NOT have flat 'avgProcessingTimeMs' field - must be in statistics object");

        logger.debug("{}: Queue structure validation passed - {}", context, queue.getString("name"));
    }
}


