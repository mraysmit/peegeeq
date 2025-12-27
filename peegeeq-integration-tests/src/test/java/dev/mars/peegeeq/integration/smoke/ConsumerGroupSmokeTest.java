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
 * Consumer Group E2E Smoke Tests.
 * 
 * Verifies consumer group operations through REST API:
 * Client -> REST API -> Runtime -> Consumer Group -> PostgreSQL
 */
@Tag(TestCategories.SMOKE)
@ExtendWith(VertxExtension.class)
@DisplayName("Consumer Group Smoke Tests")
class ConsumerGroupSmokeTest extends SmokeTestBase {

    private static final String QUEUE_NAME = "cg_smoke_test_queue";
    private static final String GROUP_NAME = "smoke-test-group";

    @Test
    @DisplayName("Should create consumer group")
    void testCreateConsumerGroup(VertxTestContext testContext) {
        String setupId = generateSetupId();
        JsonObject setupRequest = createDatabaseSetupRequest(setupId, QUEUE_NAME);

        webClient.post(REST_PORT, REST_HOST, "/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .sendJsonObject(setupRequest)
            .compose(setupResponse -> {
                logger.info("Setup created: {}", setupId);
                
                JsonObject createGroupRequest = new JsonObject()
                    .put("groupName", GROUP_NAME)
                    .put("maxMembers", 5)
                    .put("loadBalancingStrategy", "ROUND_ROBIN")
                    .put("sessionTimeout", 30000);

                return webClient.post(REST_PORT, REST_HOST,
                        "/api/v1/queues/" + setupId + "/" + QUEUE_NAME + "/consumer-groups")
                    .putHeader("content-type", "application/json")
                    .sendJsonObject(createGroupRequest);
            })
            .onComplete(testContext.succeeding(response -> {
                testContext.verify(() -> {
                    int statusCode = response.statusCode();
                    logger.info("Create consumer group response: {} - {}", statusCode, response.bodyAsString());
                    
                    assertTrue(statusCode == 200 || statusCode == 201,
                        "Expected 200 or 201, got " + statusCode);
                    
                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body, "Response body should not be null");
                    assertEquals(GROUP_NAME, body.getString("groupName"), "Group name should match");
                    
                    cleanupSetup(setupId);
                });
                testContext.completeNow();
            }));
    }

    @Test
    @DisplayName("Should list consumer groups")
    void testListConsumerGroups(VertxTestContext testContext) {
        String setupId = generateSetupId();
        JsonObject setupRequest = createDatabaseSetupRequest(setupId, QUEUE_NAME);

        webClient.post(REST_PORT, REST_HOST, "/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .sendJsonObject(setupRequest)
            .compose(setupResponse -> {
                // Create a consumer group first
                JsonObject createGroupRequest = new JsonObject()
                    .put("groupName", GROUP_NAME)
                    .put("maxMembers", 5);

                return webClient.post(REST_PORT, REST_HOST,
                        "/api/v1/queues/" + setupId + "/" + QUEUE_NAME + "/consumer-groups")
                    .putHeader("content-type", "application/json")
                    .sendJsonObject(createGroupRequest);
            })
            .compose(createResponse -> {
                // List consumer groups
                return webClient.get(REST_PORT, REST_HOST,
                        "/api/v1/queues/" + setupId + "/" + QUEUE_NAME + "/consumer-groups")
                    .send();
            })
            .onComplete(testContext.succeeding(response -> {
                testContext.verify(() -> {
                    int statusCode = response.statusCode();
                    logger.info("List consumer groups response: {} - {}", statusCode, response.bodyAsString());
                    
                    assertEquals(200, statusCode, "Expected 200");
                    
                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body, "Response body should not be null");
                    assertTrue(body.containsKey("groups") || body.containsKey("consumerGroups"),
                        "Response should contain groups array");
                    
                    cleanupSetup(setupId);
                });
                testContext.completeNow();
            }));
    }

    @Test
    @DisplayName("Should get consumer group details")
    void testGetConsumerGroup(VertxTestContext testContext) {
        String setupId = generateSetupId();
        JsonObject setupRequest = createDatabaseSetupRequest(setupId, QUEUE_NAME);

        webClient.post(REST_PORT, REST_HOST, "/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .sendJsonObject(setupRequest)
            .compose(setupResponse -> {
                JsonObject createGroupRequest = new JsonObject()
                    .put("groupName", GROUP_NAME)
                    .put("maxMembers", 5);

                return webClient.post(REST_PORT, REST_HOST,
                        "/api/v1/queues/" + setupId + "/" + QUEUE_NAME + "/consumer-groups")
                    .putHeader("content-type", "application/json")
                    .sendJsonObject(createGroupRequest);
            })
            .compose(createResponse -> {
                // Get consumer group details
                return webClient.get(REST_PORT, REST_HOST,
                        "/api/v1/queues/" + setupId + "/" + QUEUE_NAME + "/consumer-groups/" + GROUP_NAME)
                    .send();
            })
            .onComplete(testContext.succeeding(response -> {
                testContext.verify(() -> {
                    int statusCode = response.statusCode();
                    logger.info("Get consumer group response: {} - {}", statusCode, response.bodyAsString());

                    assertEquals(200, statusCode, "Expected 200");

                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body, "Response body should not be null");
                    assertEquals(GROUP_NAME, body.getString("groupName"), "Group name should match");

                    cleanupSetup(setupId);
                });
                testContext.completeNow();
            }));
    }

    @Test
    @DisplayName("Should join consumer group")
    void testJoinConsumerGroup(VertxTestContext testContext) {
        String setupId = generateSetupId();
        String memberId = "member-" + System.currentTimeMillis();
        JsonObject setupRequest = createDatabaseSetupRequest(setupId, QUEUE_NAME);

        webClient.post(REST_PORT, REST_HOST, "/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .sendJsonObject(setupRequest)
            .compose(setupResponse -> {
                JsonObject createGroupRequest = new JsonObject()
                    .put("groupName", GROUP_NAME)
                    .put("maxMembers", 5);

                return webClient.post(REST_PORT, REST_HOST,
                        "/api/v1/queues/" + setupId + "/" + QUEUE_NAME + "/consumer-groups")
                    .putHeader("content-type", "application/json")
                    .sendJsonObject(createGroupRequest);
            })
            .compose(createResponse -> {
                // Join consumer group
                JsonObject joinRequest = new JsonObject()
                    .put("memberName", memberId);

                return webClient.post(REST_PORT, REST_HOST,
                        "/api/v1/queues/" + setupId + "/" + QUEUE_NAME + "/consumer-groups/" + GROUP_NAME + "/members")
                    .putHeader("content-type", "application/json")
                    .sendJsonObject(joinRequest);
            })
            .onComplete(testContext.succeeding(response -> {
                testContext.verify(() -> {
                    int statusCode = response.statusCode();
                    logger.info("Join consumer group response: {} - {}", statusCode, response.bodyAsString());

                    assertTrue(statusCode == 200 || statusCode == 201,
                        "Expected 200 or 201, got " + statusCode);

                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body, "Response body should not be null");
                    assertEquals(GROUP_NAME, body.getString("groupName"), "Group name should match");

                    cleanupSetup(setupId);
                });
                testContext.completeNow();
            }));
    }

    @Test
    @DisplayName("Should leave consumer group")
    void testLeaveConsumerGroup(VertxTestContext testContext) {
        String setupId = generateSetupId();
        String memberId = "member-" + System.currentTimeMillis();
        final String[] consumerId = new String[1];
        JsonObject setupRequest = createDatabaseSetupRequest(setupId, QUEUE_NAME);

        webClient.post(REST_PORT, REST_HOST, "/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .sendJsonObject(setupRequest)
            .compose(setupResponse -> {
                JsonObject createGroupRequest = new JsonObject()
                    .put("groupName", GROUP_NAME)
                    .put("maxMembers", 5);

                return webClient.post(REST_PORT, REST_HOST,
                        "/api/v1/queues/" + setupId + "/" + QUEUE_NAME + "/consumer-groups")
                    .putHeader("content-type", "application/json")
                    .sendJsonObject(createGroupRequest);
            })
            .compose(createResponse -> {
                // Join consumer group first
                JsonObject joinRequest = new JsonObject()
                    .put("memberName", memberId);

                return webClient.post(REST_PORT, REST_HOST,
                        "/api/v1/queues/" + setupId + "/" + QUEUE_NAME + "/consumer-groups/" + GROUP_NAME + "/members")
                    .putHeader("content-type", "application/json")
                    .sendJsonObject(joinRequest);
            })
            .compose(joinResponse -> {
                // Get the consumer ID from join response
                JsonObject body = joinResponse.bodyAsJsonObject();
                consumerId[0] = body.getString("consumerId", memberId);

                // Leave consumer group
                return webClient.delete(REST_PORT, REST_HOST,
                        "/api/v1/queues/" + setupId + "/" + QUEUE_NAME + "/consumer-groups/" + GROUP_NAME + "/members/" + consumerId[0])
                    .send();
            })
            .onComplete(testContext.succeeding(response -> {
                testContext.verify(() -> {
                    int statusCode = response.statusCode();
                    logger.info("Leave consumer group response: {} - {}", statusCode, response.bodyAsString());

                    assertTrue(statusCode == 200 || statusCode == 204,
                        "Expected 200 or 204, got " + statusCode);

                    cleanupSetup(setupId);
                });
                testContext.completeNow();
            }));
    }

    @Test
    @DisplayName("Should delete consumer group")
    void testDeleteConsumerGroup(VertxTestContext testContext) {
        String setupId = generateSetupId();
        JsonObject setupRequest = createDatabaseSetupRequest(setupId, QUEUE_NAME);

        webClient.post(REST_PORT, REST_HOST, "/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .sendJsonObject(setupRequest)
            .compose(setupResponse -> {
                JsonObject createGroupRequest = new JsonObject()
                    .put("groupName", GROUP_NAME)
                    .put("maxMembers", 5);

                return webClient.post(REST_PORT, REST_HOST,
                        "/api/v1/queues/" + setupId + "/" + QUEUE_NAME + "/consumer-groups")
                    .putHeader("content-type", "application/json")
                    .sendJsonObject(createGroupRequest);
            })
            .compose(createResponse -> {
                // Delete consumer group
                return webClient.delete(REST_PORT, REST_HOST,
                        "/api/v1/queues/" + setupId + "/" + QUEUE_NAME + "/consumer-groups/" + GROUP_NAME)
                    .send();
            })
            .onComplete(testContext.succeeding(response -> {
                testContext.verify(() -> {
                    int statusCode = response.statusCode();
                    logger.info("Delete consumer group response: {} - {}", statusCode, response.bodyAsString());

                    assertTrue(statusCode == 200 || statusCode == 204,
                        "Expected 200 or 204, got " + statusCode);

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

