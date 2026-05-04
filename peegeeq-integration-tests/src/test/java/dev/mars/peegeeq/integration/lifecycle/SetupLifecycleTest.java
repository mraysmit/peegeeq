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
package dev.mars.peegeeq.integration.lifecycle;

import dev.mars.peegeeq.integration.SmokeTestBase;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integration tests verifying that setup deletion is complete and permanent.
 *
 * <p>The fire-and-forget {@code cleanupSetup} helper used in other integration tests only
 * issues the DELETE request without asserting any outcome. This class verifies that:
 * <ul>
 *   <li>The DELETE endpoint returns HTTP 204.</li>
 *   <li>A subsequent status query for the deleted setup returns HTTP 404.</li>
 *   <li>A subsequent publish attempt to the deleted setup returns HTTP 404.</li>
 * </ul>
 */
@ExtendWith(VertxExtension.class)
@DisplayName("Setup Lifecycle Tests")
@Tag("integration")
public class SetupLifecycleTest extends SmokeTestBase {

    private static final String QUEUE_NAME = "lifecycle_test_queue";

    /**
     * Verifies that deleting a setup removes it completely from the server.
     *
     * <h3>What is being tested</h3>
     * The {@code DELETE /api/v1/setups/:setupId} endpoint must:
     * <ol>
     *   <li>Return HTTP 204 (No Content) on the delete call itself.</li>
     *   <li>Cause a subsequent {@code GET /api/v1/setups/:setupId/status} to return 404.</li>
     *   <li>Cause a subsequent publish to the deleted setup to return 404.</li>
     * </ol>
     *
     * <h3>Why this matters</h3>
     * Without this test, the cleanup path was only exercised as a best-effort
     * fire-and-forget call. A regression in the delete handler (returning 500, silently
     * failing, or leaving the setup in an active state) would go undetected.
     */
    @Test
    @DisplayName("Deleted setup must return 404 for status and publish")
    void testDeletedSetupIsUnreachable(VertxTestContext testContext) {
        String setupId = generateSetupId();
        JsonObject setupRequest = createDatabaseSetupRequest(setupId, QUEUE_NAME);

        // 1. Create the setup
        webClient.post("/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .sendJsonObject(setupRequest)
            .compose(createResponse -> {
                int status = createResponse.statusCode();
                if (status != 200 && status != 201) {
                    return Future.failedFuture("Setup creation failed with HTTP " + status
                        + ": " + createResponse.bodyAsString());
                }
                logger.info("Setup created: {}", setupId);

                // 2. Confirm the setup is active by publishing a message
                return webClient.post("/api/v1/queues/" + setupId + "/" + QUEUE_NAME + "/messages")
                    .putHeader("content-type", "application/json")
                    .sendJsonObject(new JsonObject()
                        .put("payload", new JsonObject().put("data", "pre-delete-probe")));
            })
            .compose(publishResponse -> {
                int status = publishResponse.statusCode();
                if (status != 200 && status != 201) {
                    return Future.failedFuture("Pre-delete publish failed with HTTP " + status
                        + ": " + publishResponse.bodyAsString());
                }
                logger.info("Pre-delete publish succeeded for setup {}", setupId);

                // 3. DELETE the setup must return 204
                return webClient.delete("/api/v1/setups/" + setupId).send();
            })
            .compose(deleteResponse -> {
                int status = deleteResponse.statusCode();
                if (status != 204 && status != 200) {
                    return Future.failedFuture("DELETE /api/v1/setups/" + setupId
                        + " returned HTTP " + status + " (expected 204):"
                        + " " + deleteResponse.bodyAsString());
                }
                logger.info("Setup deleted (HTTP {}): {}", status, setupId);

                // 4. Status query must return 404
                return webClient.get("/api/v1/setups/" + setupId + "/status").send();
            })
            .compose(statusResponse -> {
                int status = statusResponse.statusCode();
                if (status != 404) {
                    return Future.failedFuture(
                        "GET /api/v1/setups/" + setupId + "/status returned HTTP " + status
                            + " after deletion (expected 404): " + statusResponse.bodyAsString());
                }
                logger.info("Setup status returns 404 after deletion: {}", setupId);

                // 5. Publish attempt must return 404
                return webClient.post("/api/v1/queues/" + setupId + "/" + QUEUE_NAME + "/messages")
                    .putHeader("content-type", "application/json")
                    .sendJsonObject(new JsonObject()
                        .put("payload", new JsonObject().put("data", "post-delete-probe")));
            })
            .onSuccess(publishResponse -> {
                testContext.verify(() -> {
                    int status = publishResponse.statusCode();
                    logger.info("Post-delete publish returned HTTP {}: {}", status, publishResponse.bodyAsString());
                    assertEquals(404, status,
                        "Publish to deleted setup must return 404, got " + status
                            + ": " + publishResponse.bodyAsString());
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }
}
