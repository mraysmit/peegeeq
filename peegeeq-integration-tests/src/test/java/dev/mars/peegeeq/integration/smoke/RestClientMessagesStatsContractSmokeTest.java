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
import dev.mars.peegeeq.client.dto.MessageRequest;
import dev.mars.peegeeq.client.dto.MessageSendResult;
import dev.mars.peegeeq.client.dto.QueueDetailsInfo;
import dev.mars.peegeeq.client.dto.QueueStats;
import dev.mars.peegeeq.integration.SmokeTestBase;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the peegeeq-rest-client messages-stats methods against the REAL endpoint contracts
 * (messages-stats group of the module audit, 2026-08-10).
 *
 * <p>All four methods were written against an imagined contract: {@code sendBatch()} posted
 * a bare message array to an endpoint that expects a {@code {messages: [...]}} wrapper and
 * then called {@code parseListResponse()} on an object payload carrying {@code messageIds}
 * and {@code failures} arrays; {@code getQueueStats()} Jackson-parsed a payload that carries
 * {@code setupId}/{@code implementationType}/{@code healthy}/{@code successRatePercent}/
 * {@code timestamp} plus conditionally-present percentile keys into the strict client dto;
 * {@code getQueueDetails()} Jackson-parsed a payload whose keys are {@code name}/{@code setup}/
 * {@code status}/{@code messages}/{@code consumers}; and {@code getQueueConsumers()} read the
 * {@code consumers} array elements as strings when the endpoint emits objects. Every one of
 * these calls failed against a real server (the consumers read only survives while the array
 * is empty).
 */
@ExtendWith(VertxExtension.class)
@DisplayName("REST client messages-stats contract smoke tests")
class RestClientMessagesStatsContractSmokeTest extends SmokeTestBase {

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
    @DisplayName("sendBatch, getQueueStats, getQueueDetails and getQueueConsumers map the real queue payloads")
    void messagesStatsFlowMapsTheRealPayloads(VertxTestContext testContext) {
        String setupId = generateSetupId();
        String queueName = "client-msgstats-queue";
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
                return client.sendMessage(setupId, queueName,
                    new MessageRequest(Map.of("seq", 0, "source", "messages-stats-contract")));
            })
            .compose(single -> {
                testContext.verify(() -> {
                    assertNotNull(single.messageId(),
                        "sendMessage must map the messageId; got " + single);
                    assertEquals(queueName, single.queueName(),
                        "sendMessage must map the queueName; got " + single);
                });
                return client.sendBatch(setupId, queueName, List.of(
                    new MessageRequest(Map.of("seq", 1, "source", "messages-stats-contract")),
                    new MessageRequest(Map.of("seq", 2, "source", "messages-stats-contract")),
                    new MessageRequest(Map.of("seq", 3, "source", "messages-stats-contract"))));
            })
            .compose(batchResults -> {
                testContext.verify(() -> {
                    assertEquals(3, batchResults.size(),
                        "sendBatch must return one result per accepted message; got " + batchResults);
                    for (MessageSendResult result : batchResults) {
                        assertNotNull(result.messageId(),
                            "each batch result must carry a real message id; got " + result);
                        assertEquals(queueName, result.queueName(),
                            "each batch result must carry the queueName; got " + result);
                        assertEquals(setupId, result.setupId(),
                            "each batch result must carry the setupId; got " + result);
                    }
                });
                // Raw payload first, then the typed client call - the mapped values must
                // equal what the endpoint actually said.
                return webClient.get("/api/v1/queues/" + setupId + "/" + queueName + "/stats").send();
            })
            .compose(rawStatsResponse -> {
                JsonObject rawStats = rawStatsResponse.bodyAsJsonObject();
                return client.getQueueStats(setupId, queueName)
                    .map(stats -> new Object[]{rawStats, stats});
            })
            .compose(pair -> {
                JsonObject raw = (JsonObject) pair[0];
                QueueStats stats = (QueueStats) pair[1];
                testContext.verify(() -> {
                    assertEquals(queueName, stats.queueName(),
                        "getQueueStats must map queueName; got " + stats);
                    assertTrue(stats.totalMessages() >= 4,
                        "totalMessages must reflect the 4 sent messages; got " + stats);
                    assertEquals(raw.getLong("totalMessages"), stats.totalMessages(),
                        "getQueueStats must map totalMessages; raw=" + raw + " mapped=" + stats);
                    assertEquals(raw.getLong("pendingMessages"), stats.pendingMessages(),
                        "getQueueStats must map pendingMessages; raw=" + raw + " mapped=" + stats);
                    assertEquals(raw.getLong("processedMessages"), stats.processedMessages(),
                        "getQueueStats must map processedMessages; raw=" + raw + " mapped=" + stats);
                    assertEquals(raw.getLong("inFlightMessages"), stats.inFlightMessages(),
                        "getQueueStats must map inFlightMessages; raw=" + raw + " mapped=" + stats);
                    assertEquals(raw.getLong("deadLetteredMessages"), stats.deadLetteredMessages(),
                        "getQueueStats must map deadLetteredMessages; raw=" + raw + " mapped=" + stats);
                });
                return webClient.get("/api/v1/queues/" + setupId + "/" + queueName).send();
            })
            .compose(rawDetailsResponse -> {
                JsonObject rawDetails = rawDetailsResponse.bodyAsJsonObject();
                return client.getQueueDetails(setupId, queueName)
                    .map(details -> new Object[]{rawDetails, details});
            })
            .compose(pair -> {
                JsonObject raw = (JsonObject) pair[0];
                QueueDetailsInfo details = (QueueDetailsInfo) pair[1];
                testContext.verify(() -> {
                    assertEquals(queueName, details.queueName(),
                        "getQueueDetails must map queueName from 'name'; got " + details);
                    assertEquals(setupId, details.setupId(),
                        "getQueueDetails must map setupId from 'setup'; got " + details);
                    assertEquals(raw.getString("implementationType"), details.implementationType(),
                        "getQueueDetails must map implementationType; raw=" + raw + " mapped=" + details);
                    assertEquals(!"error".equals(raw.getString("status")), details.healthy(),
                        "getQueueDetails must derive healthy from the status field; raw=" + raw + " mapped=" + details);
                    assertEquals(raw.getLong("messages"), details.totalMessages(),
                        "getQueueDetails must map totalMessages from 'messages'; raw=" + raw + " mapped=" + details);
                    assertEquals(raw.getInteger("consumers"), details.consumerCount(),
                        "getQueueDetails must map consumerCount from 'consumers'; raw=" + raw + " mapped=" + details);
                    assertEquals(Instant.ofEpochMilli(raw.getLong("createdAt")), details.createdAt(),
                        "getQueueDetails must map createdAt from the epoch-millis payload; raw=" + raw + " mapped=" + details);
                });
                return client.getQueueConsumers(setupId, queueName);
            })
            .compose(consumers -> {
                testContext.verify(() -> assertNotNull(consumers,
                    "getQueueConsumers must map the consumers array without error; got " + consumers));
                return cleanupSetupStrict(setupId);
            })
            .onComplete(testContext.succeeding(v -> testContext.completeNow()));
    }
}
