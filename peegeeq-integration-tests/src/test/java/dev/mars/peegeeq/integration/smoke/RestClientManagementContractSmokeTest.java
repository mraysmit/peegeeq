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

import dev.mars.peegeeq.client.PeeGeeQClient;
import dev.mars.peegeeq.client.PeeGeeQRestClient;
import dev.mars.peegeeq.client.config.ClientConfig;
import dev.mars.peegeeq.integration.SmokeTestBase;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the peegeeq-rest-client management getters against the REAL endpoint contracts
 * (metrics-stack review backlog, 2026-08-09).
 *
 * <p>The module had zero tests, and all four management getters were written against an
 * imagined contract: {@code getSystemOverview()} Jackson-parsed the whole body into a dto
 * whose fields the endpoint never emits (strict mapper — fails on the first unknown
 * property), and {@code getQueues()}/{@code getConsumerGroups()}/{@code getEventStores()}
 * called {@code bodyAsJsonArray()} on endpoints that wrap their arrays in an object
 * ({@code {queues: [...]}} etc.). Every one of these calls failed against a real server.
 */
@ExtendWith(VertxExtension.class)
@DisplayName("REST client management contract smoke tests")
class RestClientManagementContractSmokeTest extends SmokeTestBase {

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
    @DisplayName("getSystemOverview maps the real /management/overview payload")
    void getSystemOverviewMapsTheRealPayload(VertxTestContext testContext) {
        // Raw payload first, then the typed client call - the mapped values must
        // equal what the endpoint actually said.
        webClient.get("/api/v1/management/overview")
            .send()
            .compose(raw -> {
                JsonObject stats = raw.bodyAsJsonObject().getJsonObject("systemStats");
                return client.getSystemOverview().map(overview -> new Object[]{stats, overview});
            })
            .onComplete(testContext.succeeding(pair -> testContext.verify(() -> {
                JsonObject stats = (JsonObject) pair[0];
                var overview = (dev.mars.peegeeq.client.dto.SystemOverview) pair[1];
                assertEquals(stats.getInteger("totalSetups"), overview.totalSetups());
                assertEquals(stats.getInteger("totalQueues"), overview.totalQueues());
                assertEquals(stats.getInteger("totalConsumerGroups"), overview.totalConsumerGroups());
                assertEquals(stats.getInteger("totalEventStores"), overview.totalEventStores());
                assertEquals(stats.getLong("totalMessages"), overview.totalMessages());
                assertEquals(stats.getInteger("activeConnections"), overview.activeConnections());
                assertEquals(stats.getString("uptime"), overview.uptime());
                testContext.completeNow();
            })));
    }

    @Test
    @DisplayName("getQueues unwraps the object payload and maps a created queue")
    void getQueuesReturnsCreatedQueue(VertxTestContext testContext) {
        String setupId = generateSetupId();
        String queueName = "client-contract-queue";
        JsonObject setupRequest = createDatabaseSetupRequest(setupId, queueName);

        webClient.post("/api/v1/database-setup/create")
            .sendJsonObject(setupRequest)
            .compose(response -> {
                if (response.statusCode() != 201 && response.statusCode() != 200) {
                    return io.vertx.core.Future.failedFuture(new AssertionError(
                        "Setup creation failed: " + response.statusCode() + " " + response.bodyAsString()));
                }
                return client.getQueues();
            })
            .compose(queues -> {
                testContext.verify(() -> {
                    var match = queues.stream()
                        .filter(q -> queueName.equals(q.name()) && setupId.equals(q.setupId()))
                        .findFirst();
                    assertTrue(match.isPresent(),
                        "getQueues must return the created queue with name and setupId mapped; got " + queues);
                });
                return cleanupSetupStrict(setupId);
            })
            .onComplete(testContext.succeeding(v -> testContext.completeNow()));
    }

    @Test
    @DisplayName("getConsumerGroups unwraps the object payload")
    void getConsumerGroupsUnwrapsObjectPayload(VertxTestContext testContext) {
        client.getConsumerGroups()
            .onComplete(testContext.succeeding(groups -> testContext.verify(() -> {
                assertNotNull(groups, "getConsumerGroups must return a list, not fail on the object payload");
                testContext.completeNow();
            })));
    }

    @Test
    @DisplayName("getEventStores unwraps the object payload")
    void getEventStoresUnwrapsObjectPayload(VertxTestContext testContext) {
        client.getEventStores()
            .onComplete(testContext.succeeding(stores -> testContext.verify(() -> {
                assertNotNull(stores, "getEventStores must return a list, not fail on the object payload");
                testContext.completeNow();
            })));
    }
}
