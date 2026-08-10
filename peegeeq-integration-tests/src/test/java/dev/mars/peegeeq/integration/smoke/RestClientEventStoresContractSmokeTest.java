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

import dev.mars.peegeeq.api.EventQuery;
import dev.mars.peegeeq.api.database.DatabaseConfig;
import dev.mars.peegeeq.api.database.EventStoreConfig;
import dev.mars.peegeeq.api.setup.DatabaseSetupRequest;
import dev.mars.peegeeq.client.PeeGeeQClient;
import dev.mars.peegeeq.client.PeeGeeQRestClient;
import dev.mars.peegeeq.client.config.ClientConfig;
import dev.mars.peegeeq.client.dto.AppendEventRequest;
import dev.mars.peegeeq.client.dto.CorrectionRequest;
import dev.mars.peegeeq.client.dto.EventInfo;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the peegeeq-rest-client event-store methods against the REAL endpoint
 * contracts (event-stores group of the module audit, 2026-08-10).
 *
 * <p>All eight event-store methods were written against an imagined contract:
 * {@code appendEvent()}/{@code getEvent()}/{@code getEventVersions()}/
 * {@code appendCorrection()}/{@code getEventAsOf()} fed their payloads to
 * Jackson targeting the {@code BiTemporalEvent} INTERFACE, which cannot be
 * instantiated; the request dtos serialized keys the server's strict request
 * mapper rejects with 400 ({@code headers} instead of {@code metadata},
 * {@code correctedPayload} instead of {@code eventData}) and an Instant
 * {@code validTime} the server's String-typed alias silently drops;
 * {@code getEventAsOf()} sent an {@code asOf} query param when the handler
 * requires {@code transactionTime}; {@code queryEvents()}/
 * {@code getEventStoreStats()} strict-parsed wrapper objects ({@code events}/
 * {@code totalCount}/{@code hasMore}, nested {@code stats}) into dtos holding
 * the interface and never-emitted fields; and {@code streamEvents()} fed every
 * SSE frame — control frames included — to the same non-instantiable target.
 * Every one of these calls failed against a real server.</p>
 */
@ExtendWith(VertxExtension.class)
@DisplayName("REST client event-stores contract smoke tests")
class RestClientEventStoresContractSmokeTest extends SmokeTestBase {

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
    @DisplayName("event append, query, get, correction, versions, as-of and stats map the real payloads")
    void eventStoresFlowMapsTheRealPayloads(VertxTestContext testContext) {
        String setupId = generateSetupId();
        String storeName = "client_es_store";
        String aggregateId = "order-" + System.currentTimeMillis();
        String correlationId = "corr-" + setupId;
        String correctionReason = "amount was wrong";
        Instant validTime = Instant.now();
        AtomicReference<String> appendedEventId = new AtomicReference<>();
        JsonObject requestJson = createDatabaseSetupRequest(setupId, "client-es-unused-queue");
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
                List.of(),
                List.of(),
                null);

        client.createSetup(request)
            .compose(created -> {
                testContext.verify(() -> assertEquals(setupId, created.setupId(),
                    "createSetup must map the created setupId; got " + created));
                return client.addEventStore(setupId, new EventStoreConfig.Builder()
                        .eventStoreName(storeName)
                        .tableName(storeName + "_events")
                        .biTemporalEnabled(true)
                        .build());
            })
            .compose(v -> client.appendEvent(setupId, storeName,
                new AppendEventRequest("OrderCreated", Map.of("orderId", aggregateId, "amount", 199.99))
                    .withValidTime(validTime)
                    .withHeader("channel", "web")
                    .withCorrelationId(correlationId)
                    .withAggregateId(aggregateId)))
            .compose(appended -> {
                testContext.verify(() -> {
                    assertNotNull(appended.eventId(),
                        "appendEvent must map the eventId of the 201 payload; got " + appended);
                    assertEquals(storeName, appended.eventStoreName(),
                        "appendEvent must map eventStoreName; got " + appended);
                    assertEquals(setupId, appended.setupId(),
                        "appendEvent must map setupId; got " + appended);
                    assertEquals("OrderCreated", appended.eventType(),
                        "appendEvent must map eventType; got " + appended);
                    assertTrue(appended.version() >= 1,
                        "appendEvent must map the stored version (>= 1); got " + appended);
                    assertNotNull(appended.transactionTime(),
                        "appendEvent must parse the ISO transactionTime string; got " + appended);
                });
                appendedEventId.set(appended.eventId());
                return client.queryEvents(setupId, storeName, EventQuery.forEventType("OrderCreated"));
            })
            .compose(result -> {
                testContext.verify(() -> {
                    assertEquals(1, result.size(),
                        "queryEvents must unwrap the events array with the one appended event; got " + result);
                    assertEquals(1L, result.total(),
                        "queryEvents must map the payload's totalCount to total; got " + result);
                    assertFalse(result.hasMore(),
                        "queryEvents must map hasMore; got " + result);
                    EventInfo event = result.events().get(0);
                    assertEquals(appendedEventId.get(), event.id(),
                        "each event must map its id from the payload's eventId key; got " + event);
                    assertEquals("OrderCreated", event.eventType(),
                        "each event must map eventType; got " + event);
                    assertInstanceOf(JsonObject.class, event.eventData(),
                        "the appended object payload must arrive as a JsonObject eventData; got " + event);
                    assertEquals(aggregateId, ((JsonObject) event.eventData()).getString("orderId"),
                        "eventData must round-trip the appended payload; got " + event);
                    assertEquals(aggregateId, event.aggregateId(),
                        "each event must map aggregateId; got " + event);
                    assertEquals(correlationId, event.correlationId(),
                        "each event must map correlationId; got " + event);
                    assertNotNull(event.validFrom(),
                        "each event must parse the decimal epoch-seconds validFrom; got " + event);
                    assertEquals(validTime.getEpochSecond(), event.validFrom().getEpochSecond(),
                        "validFrom must round-trip the appended validTime (second precision); got " + event);
                    assertNotNull(event.transactionTime(),
                        "each event must parse the decimal epoch-seconds transactionTime; got " + event);
                    assertNotNull(event.metadata(),
                        "the appended header must arrive in the metadata map; got " + event);
                    assertEquals("web", event.metadata().get("channel"),
                        "metadata must round-trip the appended headers; got " + event);
                });
                return client.getEvent(setupId, storeName, appendedEventId.get());
            })
            .compose(event -> {
                testContext.verify(() -> {
                    assertEquals(appendedEventId.get(), event.id(),
                        "getEvent must unwrap the payload's event object and map its id; got " + event);
                    assertEquals("OrderCreated", event.eventType(),
                        "getEvent must map eventType; got " + event);
                    assertInstanceOf(JsonObject.class, event.eventData(),
                        "getEvent must map the object eventData; got " + event);
                    assertEquals(aggregateId, ((JsonObject) event.eventData()).getString("orderId"),
                        "getEvent must round-trip the appended payload; got " + event);
                });
                return client.appendCorrection(setupId, storeName, appendedEventId.get(),
                    new CorrectionRequest(Map.of("orderId", aggregateId, "amount", 249.99), correctionReason)
                        .withValidTime(validTime));
            })
            .compose(correction -> {
                testContext.verify(() -> {
                    assertNotNull(correction.correctionEventId(),
                        "appendCorrection must map correctionEventId; got " + correction);
                    assertNotEquals(appendedEventId.get(), correction.correctionEventId(),
                        "the correction event gets its own id; got " + correction);
                    assertEquals(appendedEventId.get(), correction.originalEventId(),
                        "appendCorrection must map originalEventId; got " + correction);
                    assertEquals(storeName, correction.eventStoreName(),
                        "appendCorrection must map eventStoreName; got " + correction);
                    assertEquals(setupId, correction.setupId(),
                        "appendCorrection must map setupId; got " + correction);
                    assertTrue(correction.version() >= 2,
                        "the correction version must be >= 2; got " + correction);
                    assertEquals(correctionReason, correction.correctionReason(),
                        "appendCorrection must echo the correctionReason; got " + correction);
                    assertNotNull(correction.transactionTime(),
                        "appendCorrection must parse the ISO transactionTime string; got " + correction);
                });
                return client.getEventVersions(setupId, storeName, appendedEventId.get());
            })
            .compose(versions -> {
                testContext.verify(() -> assertTrue(versions.size() >= 2,
                    "getEventVersions must unwrap the versions array with original + correction; got " + versions));
                return client.getEventAsOf(setupId, storeName, appendedEventId.get(), Instant.now());
            })
            .compose(eventAsOf -> {
                testContext.verify(() -> {
                    assertNotNull(eventAsOf.id(),
                        "getEventAsOf must send the transactionTime param and unwrap the event; got " + eventAsOf);
                    assertEquals("OrderCreated", eventAsOf.eventType(),
                        "getEventAsOf must map eventType; got " + eventAsOf);
                });
                return client.getEventStoreStats(setupId, storeName);
            })
            .compose(stats -> {
                testContext.verify(() -> {
                    assertEquals(storeName, stats.storeName(),
                        "getEventStoreStats must map stats.eventStoreName to storeName; got " + stats);
                    assertEquals(setupId, stats.setupId(),
                        "getEventStoreStats must map the wrapper's setupId; got " + stats);
                    assertTrue(stats.totalEvents() >= 1,
                        "getEventStoreStats must map totalEvents; got " + stats);
                    assertTrue(stats.totalCorrections() >= 1,
                        "getEventStoreStats must map totalCorrections; got " + stats);
                    assertNotNull(stats.eventCountsByType(),
                        "getEventStoreStats must map eventCountsByType; got " + stats);
                    assertTrue(stats.eventCountsByType().containsKey("OrderCreated"),
                        "eventCountsByType must carry the appended event type; got " + stats);
                });
                return cleanupSetupStrict(setupId);
            })
            .onComplete(testContext.succeeding(v -> testContext.completeNow()));
    }
}
