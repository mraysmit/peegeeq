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
 * Queue Management E2E Smoke Tests.
 *
 * Verifies queue management operations:
 * - Purge queue
 * - Pause queue
 * - Resume queue
 */
@Tag(TestCategories.SMOKE)
@ExtendWith(VertxExtension.class)
@DisplayName("Queue Management Smoke Tests")
class QueueManagementSmokeTest extends SmokeTestBase {

    private static final String QUEUE_NAME = "mgmt_smoke_test_queue";

    @Test
    @DisplayName("Should purge queue messages")
    void testPurgeQueue(VertxTestContext testContext) {
        String setupId = generateSetupId();
        JsonObject setupRequest = createDatabaseSetupRequest(setupId, QUEUE_NAME);

        // 1. Create Setup
        webClient.post(REST_PORT, REST_HOST, "/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .sendJsonObject(setupRequest)
            .compose(setupResponse -> {
                // 2. Send a message
                JsonObject messagePayload = new JsonObject()
                    .put("payload", new JsonObject().put("data", "test-data"));

                return webClient.post(REST_PORT, REST_HOST,
                        "/api/v1/queues/" + setupId + "/" + QUEUE_NAME + "/messages")
                    .putHeader("content-type", "application/json")
                    .sendJsonObject(messagePayload);
            })
            .compose(msgResponse -> {
                // 3. Purge Queue
                return webClient.post(REST_PORT, REST_HOST,
                        "/api/v1/queues/" + setupId + "/" + QUEUE_NAME + "/purge")
                    .send();
            })
            .onComplete(testContext.succeeding(response -> {
                testContext.verify(() -> {
                    int statusCode = response.statusCode();
                    logger.info("Purge queue response: {} - {}", statusCode, response.bodyAsString());

                    assertEquals(200, statusCode, "Expected 200 OK");

                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body, "Response body should not be null");
                    assertEquals("Queue purged successfully", body.getString("message"));
                    assertEquals(1, body.getInteger("purgedCount"), "Should have purged 1 message");

                    cleanupSetup(setupId);
                });
                testContext.completeNow();
            }));
    }

    @Test
    @DisplayName("Should pause and resume queue")
    void testPauseResumeQueue(VertxTestContext testContext) {
        String setupId = generateSetupId();
        JsonObject setupRequest = createDatabaseSetupRequest(setupId, QUEUE_NAME);

        // 1. Create Setup
        webClient.post(REST_PORT, REST_HOST, "/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .sendJsonObject(setupRequest)
            .compose(setupResponse -> {
                // 2. Create a consumer group (needed for pause/resume to have effect on subscriptions)
                // Pause/Resume works on subscriptions, so we need at least one.
                JsonObject createGroupRequest = new JsonObject()
                    .put("groupName", "test-group")
                    .put("maxMembers", 1);

                return webClient.post(REST_PORT, REST_HOST,
                        "/api/v1/queues/" + setupId + "/" + QUEUE_NAME + "/consumer-groups")
                    .putHeader("content-type", "application/json")
                    .sendJsonObject(createGroupRequest);
            })
            .compose(groupResponse -> {
                // 3. Pause Queue
                return webClient.post(REST_PORT, REST_HOST,
                        "/api/v1/queues/" + setupId + "/" + QUEUE_NAME + "/pause")
                    .send();
            })
            .compose(pauseResponse -> {
                assertEquals(200, pauseResponse.statusCode());
                JsonObject body = pauseResponse.bodyAsJsonObject();
                assertEquals("Queue paused successfully", body.getString("message"));

                // 4. Resume Queue
                return webClient.post(REST_PORT, REST_HOST,
                        "/api/v1/queues/" + setupId + "/" + QUEUE_NAME + "/resume")
                    .send();
            })
            .onComplete(testContext.succeeding(response -> {
                testContext.verify(() -> {
                    int statusCode = response.statusCode();
                    logger.info("Resume queue response: {} - {}", statusCode, response.bodyAsString());

                    assertEquals(200, statusCode, "Expected 200 OK");

                    JsonObject body = response.bodyAsJsonObject();
                    assertEquals("Queue resumed successfully", body.getString("message"));

                    cleanupSetup(setupId);
                });
                testContext.completeNow();
            }));
    }

    @Test
    @DisplayName("Should get queue details")
    void testGetQueueDetails(VertxTestContext testContext) {
        String setupId = generateSetupId();
        JsonObject setupRequest = createDatabaseSetupRequest(setupId, QUEUE_NAME);

        webClient.post(REST_PORT, REST_HOST, "/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .sendJsonObject(setupRequest)
            .compose(setupResponse -> {
                return webClient.get(REST_PORT, REST_HOST,
                        "/api/v1/queues/" + setupId + "/" + QUEUE_NAME)
                    .send();
            })
            .onComplete(testContext.succeeding(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode());
                    JsonObject body = response.bodyAsJsonObject();
                    assertEquals(QUEUE_NAME, body.getString("name"));
                    assertEquals(setupId, body.getString("setup"));
                    assertTrue(body.containsKey("statistics"));

                    cleanupSetup(setupId);
                });
                testContext.completeNow();
            }));
    }

    @Test
    @DisplayName("Should delete queue")
    void testDeleteQueue(VertxTestContext testContext) {
        String setupId = generateSetupId();
        JsonObject setupRequest = createDatabaseSetupRequest(setupId, QUEUE_NAME);

        webClient.post(REST_PORT, REST_HOST, "/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .sendJsonObject(setupRequest)
            .compose(setupResponse -> {
                return webClient.delete(REST_PORT, REST_HOST,
                        "/api/v1/queues/" + setupId + "/" + QUEUE_NAME)
                    .send();
            })
            .onComplete(testContext.succeeding(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode());
                    JsonObject body = response.bodyAsJsonObject();
                    assertEquals("Queue deleted successfully", body.getString("message"));

                    cleanupSetup(setupId);
                });
                testContext.completeNow();
            }));
    }

    @Test
    @DisplayName("Should get queue consumers")
    void testGetQueueConsumers(VertxTestContext testContext) {
        String setupId = generateSetupId();
        JsonObject setupRequest = createDatabaseSetupRequest(setupId, QUEUE_NAME);

        webClient.post(REST_PORT, REST_HOST, "/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .sendJsonObject(setupRequest)
            .compose(setupResponse -> {
                // Create a consumer group
                JsonObject createGroupRequest = new JsonObject()
                    .put("groupName", "test-group")
                    .put("maxMembers", 1);

                return webClient.post(REST_PORT, REST_HOST,
                        "/api/v1/queues/" + setupId + "/" + QUEUE_NAME + "/consumer-groups")
                    .putHeader("content-type", "application/json")
                    .sendJsonObject(createGroupRequest);
            })
            .compose(groupResponse -> {
                // Get consumers
                return webClient.get(REST_PORT, REST_HOST,
                        "/api/v1/queues/" + setupId + "/" + QUEUE_NAME + "/consumers")
                    .send();
            })
            .onComplete(testContext.succeeding(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode());
                    JsonObject body = response.bodyAsJsonObject();
                    assertEquals(QUEUE_NAME, body.getString("queueName"));
                    assertTrue(body.containsKey("consumers"));

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

