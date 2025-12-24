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
 * Dead Letter Queue E2E Smoke Tests.
 * 
 * Verifies DLQ operations through REST API:
 * Client -> REST API -> Runtime -> DeadLetterService -> PostgreSQL
 */
@Tag(TestCategories.SMOKE)
@ExtendWith(VertxExtension.class)
@DisplayName("Dead Letter Queue Smoke Tests")
class DeadLetterQueueSmokeTest extends SmokeTestBase {

    private static final String QUEUE_NAME = "dlq_smoke_test_queue";

    @Test
    @DisplayName("Should list dead letter messages")
    void testListDeadLetterMessages(VertxTestContext testContext) {
        String setupId = generateSetupId();
        JsonObject setupRequest = createDatabaseSetupRequest(setupId, QUEUE_NAME);

        webClient.post(REST_PORT, REST_HOST, "/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .sendJsonObject(setupRequest)
            .compose(setupResponse -> {
                logger.info("Setup created: {}", setupId);
                
                // List dead letter messages (should be empty initially)
                return webClient.get(REST_PORT, REST_HOST,
                        "/api/v1/setups/" + setupId + "/deadletter/messages")
                    .send();
            })
            .onComplete(testContext.succeeding(response -> {
                testContext.verify(() -> {
                    int statusCode = response.statusCode();
                    logger.info("List DLQ messages response: {} - {}", statusCode, response.bodyAsString());
                    
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
    @DisplayName("Should get dead letter message (404 for non-existent)")
    void testGetDeadLetterMessage(VertxTestContext testContext) {
        String setupId = generateSetupId();
        JsonObject setupRequest = createDatabaseSetupRequest(setupId, QUEUE_NAME);

        webClient.post(REST_PORT, REST_HOST, "/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .sendJsonObject(setupRequest)
            .compose(setupResponse -> {
                logger.info("Setup created: {}", setupId);

                // INTENTIONAL FAILURE TEST: Getting non-existent DLQ message to verify 404 response
                logger.info("INTENTIONAL FAILURE TEST: Getting non-existent DLQ message 999999 (expecting 404)");
                return webClient.get(REST_PORT, REST_HOST,
                        "/api/v1/setups/" + setupId + "/deadletter/messages/999999")
                    .send();
            })
            .onComplete(testContext.succeeding(response -> {
                testContext.verify(() -> {
                    int statusCode = response.statusCode();
                    JsonObject body = response.bodyAsJsonObject();
                    logger.info("Get DLQ message response: {} - {}", statusCode, body);

                    assertEquals(404, statusCode, "Expected 404 for non-existent message");
                    assertNotNull(body, "Response body should not be null");
                    assertTrue(body.containsKey("error"), "Response should contain 'error' field");

                    cleanupSetup(setupId);
                });
                testContext.completeNow();
            }));
    }

    @Test
    @DisplayName("Should reprocess dead letter message (404 for non-existent)")
    void testReprocessDeadLetterMessage(VertxTestContext testContext) {
        String setupId = generateSetupId();
        JsonObject setupRequest = createDatabaseSetupRequest(setupId, QUEUE_NAME);

        webClient.post(REST_PORT, REST_HOST, "/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .sendJsonObject(setupRequest)
            .compose(setupResponse -> {
                logger.info("Setup created: {}", setupId);

                JsonObject reprocessRequest = new JsonObject()
                    .put("reason", "Smoke test reprocess");

                // INTENTIONAL FAILURE TEST: Reprocessing non-existent DLQ message to verify 404 response
                logger.info("INTENTIONAL FAILURE TEST: Reprocessing non-existent DLQ message 999999 (expecting 404)");
                return webClient.post(REST_PORT, REST_HOST,
                        "/api/v1/setups/" + setupId + "/deadletter/messages/999999/reprocess")
                    .putHeader("content-type", "application/json")
                    .sendJsonObject(reprocessRequest);
            })
            .onComplete(testContext.succeeding(response -> {
                testContext.verify(() -> {
                    int statusCode = response.statusCode();
                    JsonObject body = response.bodyAsJsonObject();
                    logger.info("Reprocess DLQ message response: {} - {}", statusCode, body);

                    assertEquals(404, statusCode, "Expected 404 for non-existent message");
                    assertNotNull(body, "Response body should not be null");
                    assertTrue(body.containsKey("error"), "Response should contain 'error' field");

                    cleanupSetup(setupId);
                });
                testContext.completeNow();
            }));
    }

    @Test
    @DisplayName("Should delete dead letter message (404 for non-existent)")
    void testDeleteDeadLetterMessage(VertxTestContext testContext) {
        String setupId = generateSetupId();
        JsonObject setupRequest = createDatabaseSetupRequest(setupId, QUEUE_NAME);

        webClient.post(REST_PORT, REST_HOST, "/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .sendJsonObject(setupRequest)
            .compose(setupResponse -> {
                logger.info("Setup created: {}", setupId);

                // INTENTIONAL FAILURE TEST: Deleting non-existent DLQ message to verify 404 response
                logger.info("INTENTIONAL FAILURE TEST: Deleting non-existent DLQ message 999999 (expecting 404)");
                return webClient.delete(REST_PORT, REST_HOST,
                        "/api/v1/setups/" + setupId + "/deadletter/messages/999999")
                    .send();
            })
            .onComplete(testContext.succeeding(response -> {
                testContext.verify(() -> {
                    int statusCode = response.statusCode();
                    JsonObject body = response.bodyAsJsonObject();
                    logger.info("Delete DLQ message response: {} - {}", statusCode, body);

                    assertEquals(404, statusCode, "Expected 404 for non-existent message");
                    assertNotNull(body, "Response body should not be null");
                    assertTrue(body.containsKey("error"), "Response should contain 'error' field");

                    cleanupSetup(setupId);
                });
                testContext.completeNow();
            }));
    }

    @Test
    @DisplayName("Should get dead letter queue statistics")
    void testGetDeadLetterStats(VertxTestContext testContext) {
        String setupId = generateSetupId();
        JsonObject setupRequest = createDatabaseSetupRequest(setupId, QUEUE_NAME);

        webClient.post(REST_PORT, REST_HOST, "/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .sendJsonObject(setupRequest)
            .compose(setupResponse -> {
                logger.info("Setup created: {}", setupId);

                // Get DLQ statistics
                return webClient.get(REST_PORT, REST_HOST,
                        "/api/v1/setups/" + setupId + "/deadletter/stats")
                    .send();
            })
            .onComplete(testContext.succeeding(response -> {
                testContext.verify(() -> {
                    int statusCode = response.statusCode();
                    logger.info("Get DLQ stats response: {} - {}", statusCode, response.bodyAsString());

                    assertEquals(200, statusCode, "Expected 200");

                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body, "Response body should not be null");
                    assertTrue(body.containsKey("totalMessages"), "Response should contain totalMessages");

                    cleanupSetup(setupId);
                });
                testContext.completeNow();
            }));
    }

    @Test
    @DisplayName("Should cleanup old dead letter messages")
    void testCleanupDeadLetterQueue(VertxTestContext testContext) {
        String setupId = generateSetupId();
        JsonObject setupRequest = createDatabaseSetupRequest(setupId, QUEUE_NAME);

        webClient.post(REST_PORT, REST_HOST, "/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .sendJsonObject(setupRequest)
            .compose(setupResponse -> {
                logger.info("Setup created: {}", setupId);

                // Cleanup old DLQ messages (with 30 day retention)
                return webClient.post(REST_PORT, REST_HOST,
                        "/api/v1/setups/" + setupId + "/deadletter/cleanup?retentionDays=30")
                    .send();
            })
            .onComplete(testContext.succeeding(response -> {
                testContext.verify(() -> {
                    int statusCode = response.statusCode();
                    logger.info("Cleanup DLQ response: {} - {}", statusCode, response.bodyAsString());

                    assertEquals(200, statusCode, "Expected 200");

                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body, "Response body should not be null");
                    assertTrue(body.getBoolean("success", false), "Cleanup should succeed");
                    assertTrue(body.containsKey("messagesDeleted"), "Response should contain messagesDeleted");

                    cleanupSetup(setupId);
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

