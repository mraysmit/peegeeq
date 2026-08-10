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

import dev.mars.peegeeq.api.database.DatabaseConfig;
import dev.mars.peegeeq.api.database.QueueConfig;
import dev.mars.peegeeq.api.setup.DatabaseSetupRequest;
import dev.mars.peegeeq.client.PeeGeeQClient;
import dev.mars.peegeeq.client.PeeGeeQRestClient;
import dev.mars.peegeeq.client.config.ClientConfig;
import dev.mars.peegeeq.client.dto.WebhookSubscriptionRequest;
import dev.mars.peegeeq.integration.SmokeTestBase;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the peegeeq-rest-client dead-letter, subscription-listing and webhook-subscription
 * methods against the REAL endpoint contracts (deadletter/webhooks group of the module
 * audit, 2026-08-10).
 *
 * <p>The audited defects: {@code listDeadLetters()} sent page/pageSize query params the
 * server never reads (it reads limit/offset) and strict-parsed the endpoint's BARE ARRAY
 * payload into an imagined {@code DeadLetterListResponse} wrapper, failing every call;
 * {@code cleanupDeadLetters()} posted {@code {olderThanDays}} in a body the server never
 * reads (retentionDays is a query param) and read a {@code deletedCount} key the payload
 * never carries, the 0L default masking the defect; {@code listSubscriptions()} unwrapped
 * a {@code subscriptions} key from an object payload the server never emits (bare array),
 * failing every call; the webhook-subscription dtos imagined the contract on both sides —
 * the request serialized secret/maxRetries/retryDelayMs/contentType keys the server never
 * reads, and the info dto lacked the {@code consecutiveFailures} key the get payload
 * carries, so {@code getWebhookSubscription()} failed on every call while
 * {@code createWebhookSubscription()} silently default-filled the imagined fields.
 */
@ExtendWith(VertxExtension.class)
@DisplayName("REST client dead-letters and webhooks contract smoke tests")
class RestClientDeadLettersWebhooksContractSmokeTest extends SmokeTestBase {

    private static PeeGeeQClient client;

    @BeforeAll
    static void createClient() {
        client = PeeGeeQRestClient.create(vertx, ClientConfig.builder()
                .baseUrl("http://localhost:" + actualServerPort)
                .build());
    }

    @AfterAll
    static void closeClient() {
        if (client != null) {
            client.close();
            client = null;
        }
    }

    @Test
    @DisplayName("listDeadLetters, cleanupDeadLetters, listSubscriptions and the webhook-subscription round-trip map the real payloads")
    void deadLettersAndWebhooksFlowMapsTheRealPayloads(VertxTestContext testContext) {
        String setupId = generateSetupId();
        String queueName = "client-dlq-webhooks-queue";
        String webhookUrl = "http://localhost:9/hook";
        JsonObject requestJson = createDatabaseSetupRequest(setupId, queueName);
        JsonObject dbJson = requestJson.getJsonObject("databaseConfig");

        DatabaseSetupRequest request = new DatabaseSetupRequest(
                setupId,
                new DatabaseConfig.Builder()
                        .host(dbJson.getString("host"))
                        .port(dbJson.getInteger("port"))
                        .databaseName(dbJson.getString("databaseName"))
                        .username(dbJson.getString("username"))
                        .password(dbJson.getString("password"))
                        .schema(dbJson.getString("schema"))
                        .templateDatabase(dbJson.getString("templateDatabase"))
                        .encoding(dbJson.getString("encoding"))
                        .build(),
                List.of(new QueueConfig.Builder()
                        .queueName(queueName)
                        .maxRetries(3)
                        .visibilityTimeoutSeconds(30)
                        .build()),
                List.of(),
                null);

        client.createSetup(request)
            .compose(created -> {
                testContext.verify(() -> assertEquals(setupId, created.setupId(),
                    "createSetup must map the created setupId; got " + created));
                return client.listDeadLetters(setupId, 0, 10);
            })
            .compose(deadLetters -> {
                testContext.verify(() -> assertTrue(deadLetters.isEmpty(),
                    "listDeadLetters must parse the bare-array payload with accepted params; "
                        + "a fresh setup has no dead letters; got " + deadLetters));
                return client.cleanupDeadLetters(setupId, 30);
            })
            .compose(messagesDeleted -> {
                testContext.verify(() -> assertEquals(0L, messagesDeleted,
                    "cleanupDeadLetters must read the real messagesDeleted count; "
                        + "a fresh setup deletes none"));
                return client.listSubscriptions(setupId, queueName);
            })
            .compose(subscriptions -> {
                testContext.verify(() -> assertTrue(subscriptions.isEmpty(),
                    "listSubscriptions must parse the bare-array payload; "
                        + "no group has subscribed to the topic; got " + subscriptions));
                return client.createWebhookSubscription(setupId, queueName,
                        new WebhookSubscriptionRequest(webhookUrl));
            })
            .compose(createdInfo -> {
                testContext.verify(() -> {
                    assertNotNull(createdInfo.subscriptionId(),
                        "createWebhookSubscription must map subscriptionId; got " + createdInfo);
                    assertEquals(webhookUrl, createdInfo.webhookUrl(),
                        "createWebhookSubscription must map webhookUrl; got " + createdInfo);
                    assertEquals(queueName, createdInfo.queueName(),
                        "createWebhookSubscription must map queueName; got " + createdInfo);
                    assertEquals("ACTIVE", createdInfo.status(),
                        "createWebhookSubscription must map the ACTIVE status; got " + createdInfo);
                    assertNotNull(createdInfo.createdAt(),
                        "createWebhookSubscription must map createdAt; got " + createdInfo);
                });
                return client.getWebhookSubscription(createdInfo.subscriptionId())
                    .compose(fetched -> {
                        testContext.verify(() -> {
                            assertEquals(createdInfo.subscriptionId(), fetched.subscriptionId(),
                                "getWebhookSubscription must round-trip the subscriptionId; got " + fetched);
                            assertEquals(webhookUrl, fetched.webhookUrl(),
                                "getWebhookSubscription must map webhookUrl; got " + fetched);
                            assertEquals(queueName, fetched.queueName(),
                                "getWebhookSubscription must map queueName; got " + fetched);
                            assertEquals("ACTIVE", fetched.status(),
                                "getWebhookSubscription must map the ACTIVE status; got " + fetched);
                            assertEquals(Integer.valueOf(0), fetched.consecutiveFailures(),
                                "getWebhookSubscription must map consecutiveFailures; "
                                    + "a new subscription has none; got " + fetched);
                        });
                        return client.deleteWebhookSubscription(createdInfo.subscriptionId());
                    });
            })
            .compose(v -> cleanupSetupStrict(setupId))
            .onComplete(testContext.succeeding(v -> testContext.completeNow()));
    }
}
