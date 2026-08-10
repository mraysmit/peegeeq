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
import dev.mars.peegeeq.api.setup.DatabaseSetupStatus;
import dev.mars.peegeeq.client.PeeGeeQClient;
import dev.mars.peegeeq.client.PeeGeeQRestClient;
import dev.mars.peegeeq.client.config.ClientConfig;
import dev.mars.peegeeq.client.dto.SetupDetailsInfo;
import dev.mars.peegeeq.client.dto.SetupResultInfo;
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
 * Pins the peegeeq-rest-client setups methods against the REAL endpoint contracts
 * (setups group of the module audit, 2026-08-10).
 *
 * <p>All four setups methods were written against an imagined contract:
 * {@code createSetup()}/{@code getSetup()} Jackson-parsed the payload into
 * {@code dev.mars.peegeeq.api.setup.DatabaseSetupResult}, which has no Jackson creator
 * and whose fields ({@code QueueFactory}/{@code EventStore} maps) the endpoints never
 * emit; {@code listSetups()} called {@code bodyAsJsonArray()} on an endpoint that wraps
 * its ids in an object ({@code {count, setupIds: [...]}}); and {@code getSetupStatus()}
 * Jackson-parsed the {@code {setupId, status}} object into the bare
 * {@code DatabaseSetupStatus} enum. Every one of these calls failed against a real server.
 */
@ExtendWith(VertxExtension.class)
@DisplayName("REST client setups contract smoke tests")
class RestClientSetupsContractSmokeTest extends SmokeTestBase {

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
    @DisplayName("createSetup, listSetups, getSetup and getSetupStatus map the real setups payloads")
    void setupsFlowMapsTheRealPayloads(VertxTestContext testContext) {
        String setupId = generateSetupId();
        String queueName = "client-setups-queue";
        JsonObject requestJson = createDatabaseSetupRequest(setupId, queueName);
        JsonObject dbJson = requestJson.getJsonObject("databaseConfig");
        String expectedDatabaseName = dbJson.getString("databaseName");
        String expectedSchema = dbJson.getString("schema");

        DatabaseSetupRequest request = new DatabaseSetupRequest(
                setupId,
                new DatabaseConfig.Builder()
                        .host(dbJson.getString("host"))
                        .port(dbJson.getInteger("port"))
                        .databaseName(expectedDatabaseName)
                        .username(dbJson.getString("username"))
                        .password(dbJson.getString("password"))
                        .schema(expectedSchema)
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
            .compose(result -> {
                testContext.verify(() -> {
                    SetupResultInfo created = result;
                    assertEquals(setupId, created.setupId(),
                        "createSetup must map the created setupId; got " + created);
                    assertEquals("ACTIVE", created.status(),
                        "createSetup must map the ACTIVE status the endpoint emits; got " + created);
                    assertEquals(1, created.queueCount(),
                        "createSetup must map queueCount for the one requested queue; got " + created);
                });
                return client.listSetups();
            })
            .compose(setupIds -> {
                testContext.verify(() -> assertTrue(setupIds.contains(setupId),
                    "listSetups must unwrap the setupIds array and contain the created setup; got " + setupIds));
                return client.getSetup(setupId);
            })
            .compose(details -> {
                testContext.verify(() -> {
                    SetupDetailsInfo info = details;
                    assertEquals(setupId, info.setupId(),
                        "getSetup must map the setupId; got " + info);
                    assertEquals(expectedDatabaseName, info.databaseName(),
                        "getSetup must map the databaseName from the request; got " + info);
                    assertEquals(expectedSchema, info.schema(),
                        "getSetup must map the schema from the request; got " + info);
                    assertTrue(info.queueFactories().contains(queueName),
                        "getSetup must map queueFactories containing the created queue; got " + info);
                });
                return client.getSetupStatus(setupId);
            })
            .compose(status -> {
                testContext.verify(() -> assertEquals(DatabaseSetupStatus.ACTIVE, status,
                    "getSetupStatus must map the status field of the {setupId, status} payload"));
                return cleanupSetupStrict(setupId);
            })
            .onComplete(testContext.succeeding(v -> testContext.completeNow()));
    }
}
