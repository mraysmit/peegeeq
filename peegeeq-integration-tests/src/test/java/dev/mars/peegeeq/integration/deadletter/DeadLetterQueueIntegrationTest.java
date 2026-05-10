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
package dev.mars.peegeeq.integration.deadletter;

import dev.mars.peegeeq.integration.SmokeTestBase;
import io.vertx.core.Future;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dead Letter Queue (DLQ) integration tests.
 *
 * <p>Covers two complementary scenarios:
 * <ol>
 *   <li><b>API read path</b> a message inserted directly into the
 *       {@code dead_letter_queue} table must be returned by the DLQ REST API
 *       with all fields preserved. This tests the API's connection to the
 *       correct setup database and its response serialisation.</li>
 *   <li><b>End-to-end retry exhaustion</b> a message published to an outbox
 *       queue whose webhook always returns 5xx must exhaust its retry budget
 *       (maxRetries=0 → single attempt) and then appear in the DLQ. This
 *       tests the full retry → DLQ promotion pipeline.</li>
 * </ol>
 */
@ExtendWith(VertxExtension.class)
@DisplayName("Dead Letter Queue Integration Tests")
@Tag("integration")
public class DeadLetterQueueIntegrationTest extends SmokeTestBase {

    // -------------------------------------------------------------------------
    // Test 1 API read path (deterministic, no webhook required)
    // -------------------------------------------------------------------------

    /**
     * Verifies that the DLQ REST API returns messages that are present in the
     * setup's {@code dead_letter_queue} table.
     *
     * <h3>What is being tested</h3>
     * The DLQ REST API ({@code GET /api/v1/setups/:setupId/deadletter/messages})
     * must query the setup's own isolated database. This test inserts a row
     * directly via SQL (bypassing the delivery mechanism) and then confirms
     * the API returns it with the correct fields. It proves:
     * <ul>
     *   <li>The API routes to the correct setup's database.</li>
     *   <li>The response JSON is correctly serialised including {@code correlationId}.</li>
     * </ul>
     *
     * <h3>Test flow</h3>
     * <ol>
     *   <li>Create a PeeGeeQ setup via the REST API.</li>
     *   <li>Insert a row directly into the setup's {@code dead_letter_queue} table
     *       via a raw PostgreSQL connection, using a unique {@code correlation_id}.</li>
     *   <li>Call the DLQ list API and assert the response contains the inserted
     *       message with the expected {@code correlationId}.</li>
     * </ol>
     */
    @Test
    @DisplayName("DLQ API returns messages present in the setup database")
    void testDlqApiReturnsRealMessages(VertxTestContext testContext) {
        String setupId = generateSetupId();
        String queueName = "dlq_api_read_queue";
        String correlationId = "dlq-read-" + UUID.randomUUID().toString().substring(0, 8);

        JsonObject setupRequest = createDatabaseSetupRequest(setupId, queueName);

        webClient.post("/api/v1/database-setup/create").sendJsonObject(setupRequest)
            .compose(r -> {
                // Insert a row directly into the dead_letter_queue of this setup's database
                JsonObject dbConfig = setupRequest.getJsonObject("databaseConfig");
                PgConnectOptions opts = new PgConnectOptions()
                    .setHost(dbConfig.getString("host"))
                    .setPort(dbConfig.getInteger("port"))
                    .setDatabase(dbConfig.getString("databaseName"))
                    .setUser(dbConfig.getString("username"))
                    .setPassword(dbConfig.getString("password"));

                Pool pool = Pool.pool(vertx, opts, new PoolOptions().setMaxSize(1));

                String insertSql = """
                    INSERT INTO dead_letter_queue
                        (original_table, original_id, topic, payload, original_created_at, failure_reason, retry_count, correlation_id)
                    VALUES ($1, $2, $3, $4::jsonb, NOW(), $5, $6, $7)
                    """;

                return pool.preparedQuery(insertSql)
                    .execute(Tuple.of(
                        "outbox",
                        1L,
                        queueName,
                        new JsonObject().put("data", "dlq-direct-insert"),
                        "Simulated test failure",
                        0,
                        correlationId))
                    .compose(res -> {
                        logger.info("Inserted row into dead_letter_queue (correlationId={})", correlationId);
                        return Future.<Void>succeededFuture();
                    })
                    .eventually(() -> pool.close());
            })
            // Query the DLQ REST API
            .compose(v -> webClient.get("/api/v1/setups/" + setupId + "/deadletter/messages").send())
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode(),
                        "DLQ list API must return 200");

                    String body = response.bodyAsString();
                    assertTrue(body.startsWith("["),
                        "DLQ list response must be a JSON array");

                    JsonArray messages = new JsonArray(body);
                    assertFalse(messages.isEmpty(),
                        "DLQ list must contain the inserted message");

                    JsonObject first = messages.getJsonObject(0);
                    assertEquals(correlationId, first.getString("correlationId"),
                        "DLQ message correlationId must match the inserted row");
                    assertEquals(queueName, first.getString("topic"),
                        "DLQ message topic must match the inserted row");
                    assertEquals("Simulated test failure", first.getString("failureReason"),
                        "DLQ message failureReason must be preserved");

                    logger.info("DLQ API correctly returned inserted message (correlationId={})", correlationId);
                    cleanupSetup(setupId);
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    // -------------------------------------------------------------------------
    // Test 2 End-to-end retry exhaustion → DLQ promotion
    // -------------------------------------------------------------------------

    /**
     * Verifies that a message whose webhook endpoint always returns 5xx exhausts
     * its retry budget and is promoted to the dead letter queue.
     *
     * <h3>What is being tested</h3>
     * The full pipeline: publish → delivery attempt → webhook returns 500 →
     * retry_count(0) >= maxRetries(0) → moved to {@code dead_letter_queue}.
     *
     * <h3>Prerequisites</h3>
     * This test requires the webhook subscription mechanism to be operational.
     * If {@code WebhookSubscriptionHandler} cannot create a consumer subscription
     * (e.g. due to a stale bytecode issue in the installed {@code peegeeq-rest}
     * artifact), the delivery attempt never happens, the message stays PENDING,
     * and this test will time out with a RED result.
     *
     * <h3>Test flow</h3>
     * <ol>
     *   <li>Create a setup with {@code maxRetries=0} (first failure → DLQ immediately).</li>
     *   <li>Register a webhook server that always returns HTTP 500.</li>
     *   <li>Publish a message via the REST API with a unique {@code correlationId}.</li>
     *   <li>Poll the DLQ REST API at 500ms intervals until the message appears there,
     *       with a 30-second failure-only timeout guard.</li>
     *   <li>Assert the DLQ entry has the expected {@code correlationId}.</li>
     * </ol>
     */
    @Test
    @DisplayName("Exhausted retries promote message to dead letter queue")
    void testExhaustedRetriesPromoteMessageToDlq(VertxTestContext testContext) {
        String setupId = generateSetupId();
        String queueName = "dlq_retry_exhaustion_queue";
        String correlationId = "dlq-exhaust-" + UUID.randomUUID().toString().substring(0, 8);

        // maxRetries=0: first delivery failure immediately moves message to DLQ
        JsonObject setupRequest = createDatabaseSetupRequest(setupId, queueName);
        setupRequest.getJsonArray("queues").getJsonObject(0)
            .put("type", "outbox")
            .put("maxRetries", 0);

        // Webhook server that always returns 500 to trigger immediate DLQ promotion
        ConcurrentLinkedQueue<String> webhookInvocations = new ConcurrentLinkedQueue<>();
        int webhookPort = 9190 + (int) (Math.random() * 100);
        String webhookPath = "/webhook-fail-" + setupId;

        HttpServer failingWebhookServer = vertx.createHttpServer()
            .requestHandler(req -> {
                if (req.path().equals(webhookPath)) {
                    req.bodyHandler(body -> webhookInvocations.add(body.toString()));
                    // Always return 500 to simulate a permanently failing consumer
                    req.response().setStatusCode(500).end("{\"error\":\"simulated failure\"}");
                }
            });

        long[] timeoutTimerId = {-1};

        failingWebhookServer.listen(webhookPort)
            .compose(server -> webClient.post("/api/v1/database-setup/create").sendJsonObject(setupRequest))
            .compose(r -> webClient.post(
                    "/api/v1/setups/" + setupId + "/queues/" + queueName + "/webhook-subscriptions")
                .sendJsonObject(new JsonObject()
                    .put("webhookUrl", "http://localhost:" + webhookPort + webhookPath)))
            .compose(r -> webClient.post("/api/v1/queues/" + setupId + "/" + queueName + "/messages")
                .sendJsonObject(new JsonObject()
                    .put("payload", new JsonObject().put("data", "retry-exhaustion-test"))
                    .put("correlationId", correlationId)))
            .onSuccess(publishResponse -> {
                testContext.verify(() ->
                    assertEquals(200, publishResponse.statusCode(), "Message publish must succeed"));
                logger.info("Message published (correlationId={}), polling DLQ...", correlationId);

                // Poll DLQ until the message appears (delivery failure should trigger DLQ quickly)
                timeoutTimerId[0] = vertx.setTimer(30_000, ignored -> {
                    testContext.failNow(new AssertionError(
                        "Message was not promoted to DLQ within 30 seconds. "
                            + "Webhook invocations: " + webhookInvocations.size()
                            + ". Check that WebhookSubscriptionHandler is correctly compiled "
                            + "against the current MessageConsumer API (Future<Void> subscribe)."));
                    failingWebhookServer.close();
                    cleanupSetup(setupId);
                });

                vertx.setPeriodic(500, timerId ->
                    webClient.get("/api/v1/setups/" + setupId + "/deadletter/messages")
                        .send()
                        .onSuccess(dlqResponse -> {
                            String body = dlqResponse.bodyAsString();
                            if (body.startsWith("[")) {
                                JsonArray messages = new JsonArray(body);
                                if (!messages.isEmpty()) {
                                    vertx.cancelTimer(timerId);
                                    vertx.cancelTimer(timeoutTimerId[0]);
                                    testContext.verify(() -> {
                                        assertFalse(messages.isEmpty(),
                                            "DLQ must contain the exhausted message");
                                        // Find the entry with our correlationId
                                        boolean found = false;
                                        for (int i = 0; i < messages.size(); i++) {
                                            JsonObject entry = messages.getJsonObject(i);
                                            if (correlationId.equals(entry.getString("correlationId"))) {
                                                found = true;
                                                assertEquals(queueName, entry.getString("topic"),
                                                    "DLQ entry topic must match queue name");
                                                logger.info(
                                                    "DLQ correctly received exhausted message "
                                                        + "(correlationId={}, retryCount={})",
                                                    correlationId, entry.getInteger("retryCount"));
                                                break;
                                            }
                                        }
                                        assertTrue(found,
                                            "DLQ must contain entry with correlationId=" + correlationId
                                                + ". Actual DLQ: " + messages.encode());
                                        failingWebhookServer.close();
                                        cleanupSetup(setupId);
                                    });
                                    testContext.completeNow();
                                }
                            }
                        })
                        .onFailure(err -> logger.warn("DLQ poll error: {}", err.getMessage())));
            })
            .onFailure(testContext::failNow);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void cleanupSetup(String setupId) {
        webClient.delete("/api/v1/setups/" + setupId)
            .send()
            .onFailure(err -> logger.warn("Failed to cleanup setup {}", setupId, err));
    }
}
