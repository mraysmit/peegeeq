package dev.mars.peegeeq.integration.bitemporal;

import dev.mars.peegeeq.api.EventStore;
import dev.mars.peegeeq.api.database.DatabaseConfig;
import dev.mars.peegeeq.api.database.EventStoreConfig;
import dev.mars.peegeeq.api.setup.DatabaseSetupRequest;
import dev.mars.peegeeq.api.setup.DatabaseSetupResult;
import dev.mars.peegeeq.integration.SmokeTestBase;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Multi-setup bi-temporal LISTEN/NOTIFY isolation — regression coverage for the Phase F1 shared-Vert.x
 * defect (see {@code docs-design/tasks/SCHEMA-PROCESSING-GAPS-CRITICAL-17-Jun-2026.md}, "Phase F1" and
 * the "multi-setup / Vert.x-sharing test coverage" plan, item P2).
 *
 * <p>P1 ({@code MultiSetupNativeListenIsolationTest}) locks the <em>native queue</em> LISTEN path. The
 * bi-temporal event store has its <strong>own</strong> LISTEN/NOTIFY implementation
 * ({@code ReactiveNotificationHandler} + the {@code eventstore} trigger template), reached through a
 * separate dedicated LISTEN connection. That is a second, independent place the same class of bug —
 * a second setup's LISTEN connection going silently deaf when per-setup managers share one Vert.x —
 * could hide, so this test exercises it.
 *
 * <p><b>Finding (verified by instrumented experiment, 2026-06-27):</b> it does NOT hide there. This test
 * passes even when the per-setup managers share one Vert.x (the reverted Phase F1 defect), whereas P1
 * times out under the exact same condition. The root cause is <em>not</em> LISTEN-side at all — both
 * paths' dedicated LISTEN connections ({@code PgConnection.connect}) correctly reach their own database.
 * F1 is a <em>producer pool</em> misrouting: {@code PgConnectionManager.createReactivePool} builds a
 * Vert.x <em>shared</em> pool ({@code setShared(true)}) with no explicit name, so on a shared Vert.x all
 * setups collapse onto one pool pinned to the first setup's database and later setups' writes land in the
 * wrong DB. This store is immune because {@code PgBiTemporalEventStore.getOrCreateReactivePool} uses
 * {@code setShared(false)} plus a unique per-table pool name ("Disable sharing to ensure correct database
 * connection") — it already fixed the collision the native pool still has. This test's value is therefore
 * (1) positive multi-setup coverage of the setup-service-provisioned bi-temporal notification path —
 * previously untested end-to-end, as existing tests only appended/queried over REST and never subscribed
 * — and (2) documenting that immunity. It is not, and should not be presented as, an F1 regression lock
 * (P1 is that). Full analysis: {@code SCHEMA-PROCESSING-GAPS-CRITICAL-17-Jun-2026.md} ("F1 root cause").
 *
 * <p><b>Why the append is produced via REST, not the store's own append():</b> the subscriber's LISTEN
 * connection and the REST append's connection are physically different connections, so a received
 * notification proves delivery genuinely travelled over NOTIFY across connections — the exact path F1
 * broke for the second setup. (The store's {@code subscribe()} itself establishes the LISTEN via
 * {@code ensureNotificationHandlerStarted()}, so no warm-up append is needed before subscribing.)
 *
 * <p>Both event stores are provisioned through {@code setupService.createCompleteSetup} so the
 * per-setup Vert.x path — the one F1 collapsed — is what is under test; the {@code EventStore}
 * instances are taken from each setup result's {@link DatabaseSetupResult#getEventStores()} map (the
 * setup service creates them as {@code EventStore<Object>}).
 */
@ExtendWith(VertxExtension.class)
@Tag("integration")
@DisplayName("Multi-setup bi-temporal LISTEN/NOTIFY isolation")
public class MultiSetupBitemporalNotificationIsolationTest extends SmokeTestBase {

    private static final Logger log = LoggerFactory.getLogger(MultiSetupBitemporalNotificationIsolationTest.class);

    private static final String STORE_NAME = "multi_setup_es";

    private final List<EventStore<?>> activeStores = Collections.synchronizedList(new ArrayList<>());

    @AfterEach
    void closeStores(VertxTestContext testContext) {
        List<Future<Void>> closes = new ArrayList<>();
        synchronized (activeStores) {
            for (EventStore<?> store : activeStores) {
                closes.add(store.close()
                        .onFailure(e -> log.warn("Failed to close event store during cleanup", e)));
            }
            activeStores.clear();
        }
        // Best-effort teardown: settle all closes (each already logs its own failure), then complete.
        Future.join(closes).onComplete(ignored -> testContext.completeNow());
    }

    /**
     * Two bi-temporal setups on one service, each with a subscriber on its own event store. An event
     * appended (via REST) to each setup's store must reach THAT setup's subscriber over LISTEN/NOTIFY.
     * Both subscribers must receive. (Unlike the native path in P1, this holds even under the F1 shared
     * Vert.x — see the class javadoc; the bi-temporal path is not susceptible to that defect.)
     */
    @Test
    @Timeout(value = 60, timeUnit = TimeUnit.SECONDS)
    @DisplayName("Each of two bi-temporal setups' subscribers receives its own append notification")
    void twoBitemporalSetups_eachSubscriberReceivesItsAppend(VertxTestContext testContext) {
        String setupA = generateSetupId();
        String setupB = generateSetupId();

        Checkpoint receivedA = testContext.checkpoint();
        Checkpoint receivedB = testContext.checkpoint();

        // Create both setups (each provisions its own database + per-setup Vert.x), then subscribe on
        // each event store while both are live — the multi-setup scenario F1 broke.
        setupService.createCompleteSetup(createEventStoreSetupRequest(setupA))
                .compose(resultA -> setupService.createCompleteSetup(createEventStoreSetupRequest(setupB))
                        .map(resultB -> new DatabaseSetupResult[]{resultA, resultB}))
                .onSuccess(results -> {
                    subscribeThenAppend(results[0], setupA, "MarkerA", receivedA, testContext);
                    subscribeThenAppend(results[1], setupB, "MarkerB", receivedB, testContext);
                })
                .onFailure(testContext::failNow);
    }

    /**
     * Subscribes to {@code setup}'s event store for {@code eventType} and flags {@code received} when a
     * notification for that type arrives, then — only after the subscription's LISTEN is established
     * ({@code subscribe()} completes) — appends exactly one event of that type via the REST API. A
     * received notification therefore proves delivery travelled over NOTIFY.
     */
    @SuppressWarnings("unchecked")
    private void subscribeThenAppend(DatabaseSetupResult setup, String setupId, String eventType,
            Checkpoint received, VertxTestContext ctx) {
        EventStore<Object> store = (EventStore<Object>) setup.getEventStores().get(STORE_NAME);
        if (store == null) {
            ctx.failNow(new AssertionError("Event store missing for setup " + setupId));
            return;
        }
        activeStores.add(store);
        store.subscribe(eventType, message -> {
                    var event = message.getPayload();
                    if (eventType.equals(event.getEventType())) {
                        log.info("setup {}: subscriber received its append over LISTEN/NOTIFY (eventId={})",
                                setupId, event.getEventId());
                        received.flag();
                    }
                    return Future.succeededFuture();
                })
                .onSuccess(v -> webClient.post("/api/v1/eventstores/" + setupId + "/" + STORE_NAME + "/events")
                        .putHeader("content-type", "application/json")
                        .sendJsonObject(new JsonObject()
                                .put("aggregateId", "agg-" + eventType)
                                .put("eventType", eventType)
                                .put("payload", new JsonObject().put("marker", eventType + "-" + System.nanoTime()))
                                .put("validTime", Instant.now().toString()))
                        .onSuccess(resp -> {
                            if (resp.statusCode() != 200 && resp.statusCode() != 201) {
                                ctx.failNow(new AssertionError("Append to setup " + setupId
                                        + " failed: HTTP " + resp.statusCode() + " body=" + resp.bodyAsString()));
                            }
                        })
                        .onFailure(ctx::failNow))
                .onFailure(ctx::failNow);
    }

    private DatabaseSetupRequest createEventStoreSetupRequest(String setupId) {
        DatabaseConfig dbConfig = new DatabaseConfig.Builder()
                .host(getPostgresHost())
                .port(getPostgresPort())
                .databaseName("multisetup_es_db_" + setupId.replace("-", "_"))
                .username(getPostgresUsername())
                .password(getPostgresPassword())
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .templateDatabase("template0")
                .encoding("UTF8")
                .build();
        EventStoreConfig eventStoreConfig = new EventStoreConfig.Builder()
                .eventStoreName(STORE_NAME)
                .tableName(STORE_NAME)
                .biTemporalEnabled(true)
                .build();
        return new DatabaseSetupRequest(setupId, dbConfig, List.of(), List.of(eventStoreConfig), Map.of());
    }
}
