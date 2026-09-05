package dev.mars.peegeeq.bitemporal;

import dev.mars.peegeeq.api.EventStore;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.test.logging.ExpectedErrorLog;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
class DurableBiTemporalReplayIntegrationTest {
    private static final String TABLE = "bitemporal_event_log";
    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();
    private PeeGeeQManager manager;
    private DurableBiTemporalSubscriptionCoordinator service;
    private EventStore<String> store;
    private String schema;
    private Promise<Void> releaseWriter;
    private Future<Void> writer;
    private DurableBiTemporalSubscriptionCoordinator competitor;
    private PeeGeeQManager otherManager;
    private EventStore<String> otherStore;
    private EventStore<Payload> typedStore;

    public record Payload(String name, int quantity) {}

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext context) {
        schema = "replay_" + UUID.randomUUID().toString().replace("-", "");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, schema, SchemaComponent.BITEMPORAL);
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("default", PeeGeeQTestConfig.builder()
            .from(postgres).schema(schema).property("peegeeq.health.enabled", "false")
            .property("peegeeq.metrics.enabled", "false").build()));
        manager.start().map(v -> {
            var factory = new BiTemporalEventStoreFactory(vertx, manager);
            service = factory.createBiTemporalSubscriptionService();
            store = factory.createEventStore(String.class, TABLE);
            return (Void) null;
        }).onComplete(context.succeeding(v -> context.completeNow()));
    }

    @AfterEach
    void tearDown(VertxTestContext context) {
        if (releaseWriter != null) releaseWriter.tryComplete();
        (writer == null ? Future.<Void>succeededFuture() : writer)
            .transform(written -> Future.join(service == null ? Future.succeededFuture() : service.close(),
            store == null ? Future.succeededFuture() : store.close(),
            otherStore == null ? Future.succeededFuture() : otherStore.close(),
            typedStore == null ? Future.succeededFuture() : typedStore.close(),
            competitor == null ? Future.succeededFuture() : competitor.close())
            .transform(closed -> Future.join(manager == null ? Future.succeededFuture() : manager.closeReactive(),
                otherManager == null ? Future.succeededFuture() : otherManager.closeReactive())
                .compose(v -> closed.failed() ? Future.failedFuture(closed.cause())
                    : written.failed() ? Future.failedFuture(written.cause()) : Future.succeededFuture())))
            .onComplete(context.succeeding(v -> context.completeNow()));
    }

    private Future<Void> register(String filter, String aggregate) {
        return service.registerDefinition(TABLE, "orders", "billing", filter, aggregate,
            SubscriptionOptions.builder().durableEnabled(true).subscriptionName("orders")
                .startPosition(dev.mars.peegeeq.api.messaging.StartPosition.FROM_BEGINNING).build());
    }

    private DurableBiTemporalSubscriptionCoordinator.SubscriptionKey key() {
        return new DurableBiTemporalSubscriptionCoordinator.SubscriptionKey(TABLE, "orders", "billing");
    }

    private Future<Long> append(String type, String payload, String aggregate) {
        return store.append(type, payload, Instant.now(), Map.of("source", "test"), "correlation", null, aggregate)
            .compose(event -> manager.getPool().preparedQuery("SELECT id FROM " + schema + "." + TABLE
                + " WHERE event_id=$1").execute(io.vertx.sqlclient.Tuple.of(event.getEventId())))
            .map(rows -> rows.iterator().next().getLong("id"));
    }

    @Test
    void failedRegistrationDoesNotPoisonTheLocalHandlerKey(VertxTestContext context) {
        var wrong = SubscriptionOptions.builder().durableEnabled(true).subscriptionName("wrong").build();
        service.subscribe(TABLE, "orders", "billing", null, null, String.class,
            message -> Future.succeededFuture(), wrong).transform(result -> {
                assertInstanceOf(IllegalArgumentException.class, result.cause());
                return service.subscribe(TABLE, "orders", "billing", null, null, String.class,
                    message -> Future.succeededFuture(), liveOptions());
            }).onComplete(context.succeeding(v -> context.completeNow()));
    }

    private SubscriptionOptions liveOptions() {
        return SubscriptionOptions.builder().durableEnabled(true).subscriptionName("orders")
            .startPosition(dev.mars.peegeeq.api.messaging.StartPosition.FROM_BEGINNING).replayBatchSize(1).build();
    }

    @Test
    @ExpectedErrorLog(logger = "dev.mars.peegeeq.bitemporal.DurableBiTemporalDelivery",
        message = "Durable delivery failed for SubscriptionKey[tableName=bitemporal_event_log, subscriptionName=orders, consumerGroup=billing]",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = IllegalStateException.class)
    void liveFailureIsObservableAndRecreatedServiceCanRetry(Vertx vertx, VertxTestContext context) {
        IllegalStateException failure = new IllegalStateException("live handler failed");
        service.subscribe(TABLE, "orders", "billing", null, null, String.class,
            message -> Future.failedFuture(failure), liveOptions())
            .compose(v -> append("order.created", "retry", "a"))
            .compose(last -> service.deliveryCompletion(TABLE, "orders", "billing")
                .timeout(10, java.util.concurrent.TimeUnit.SECONDS).transform(result -> {
                    assertSame(failure, result.cause());
                    return service.getCurrentCursor(key());
                }).compose(cursor -> {
                    assertEquals(0L, cursor);
                    return service.close();
                }).compose(v -> {
                    competitor = new BiTemporalEventStoreFactory(vertx, manager).createBiTemporalSubscriptionService();
                    return competitor.catchUp(TABLE, "orders", "billing", String.class, message -> {
                        assertEquals("retry", message.getPayload().getPayload());
                        return Future.succeededFuture();
                    }, 1);
                }).compose(v -> competitor.getCurrentCursor(key())).map(cursor -> {
                    assertEquals(last, cursor);
                    return cursor;
                })).onComplete(context.succeeding(v -> context.completeNow()));
    }

    @Test
    void pauseResumeAndCancelRespectCommittedProgress(VertxTestContext context) {
        List<String> received = new ArrayList<>();
        service.subscribe(TABLE, "orders", "billing", null, null, String.class, message -> {
            received.add(message.getPayload().getPayload());
            return Future.succeededFuture();
        }, liveOptions()).compose(v -> service.pause(TABLE, "orders", "billing"))
            .compose(v -> append("order.created", "paused", "a"))
            .compose(last -> service.getCurrentCursor(key()).compose(cursor -> {
                assertEquals(0L, cursor);
                assertTrue(received.isEmpty());
                return service.resume(TABLE, "orders", "billing");
            }).compose(v -> service.cancel(TABLE, "orders", "billing"))
                .compose(v -> append("order.created", "cancelled", "a"))
                .compose(v -> service.getCurrentCursor(key())).map(cursor -> {
                    assertEquals(last, cursor);
                    assertEquals(List.of("paused"), received);
                    return cursor;
                })).onComplete(context.succeeding(v -> context.completeNow()));
    }

    @Test
    void periodicReconciliationDeliversACommitWithoutNotify(VertxTestContext context) {
        Promise<Void> received = Promise.promise();
        service.subscribe(TABLE, "orders", "billing", null, null, String.class, message -> {
            assertEquals("silent", message.getPayload().getPayload());
            received.tryComplete();
            return Future.succeededFuture();
        }, liveOptions()).compose(v -> manager.getPool().withTransaction(connection ->
            connection.query("SET LOCAL session_replication_role='replica'").execute()
                .compose(ignored -> store.appendBuilder().eventType("order.created").payload("silent")
                    .validTime(Instant.now()).inTransaction(connection).execute())))
            .compose(v -> received.future().timeout(5, java.util.concurrent.TimeUnit.SECONDS))
            .compose(v -> service.pause(TABLE, "orders", "billing"))
            .onComplete(context.succeeding(v -> context.completeNow()));
    }

    @Test
    void restartRecreatesManagerAndOnlyDeliversUnacknowledgedHistory(Vertx vertx, VertxTestContext context) {
        List<String> received = new ArrayList<>();
        register(null, null).compose(v -> append("order.created", "old", "a"))
            .compose(v -> service.catchUp(TABLE, "orders", "billing", String.class,
                message -> Future.succeededFuture(), 1))
            .compose(v -> Future.join(service.close(), store.close()))
            .compose(v -> manager.closeReactive()).compose(v -> {
                manager = new PeeGeeQManager(new PeeGeeQConfiguration("default", PeeGeeQTestConfig.builder()
                    .from(postgres).schema(schema).property("peegeeq.health.enabled", "false")
                    .property("peegeeq.metrics.enabled", "false").build()));
                return manager.start();
            }).compose(v -> {
                var factory = new BiTemporalEventStoreFactory(vertx, manager);
                service = factory.createBiTemporalSubscriptionService();
                store = factory.createEventStore(String.class, TABLE);
                return append("order.created", "new", "a");
            }).compose(last -> service.subscribe(TABLE, "orders", "billing", null, null, String.class, message -> {
                received.add(message.getPayload().getPayload());
                return Future.succeededFuture();
            }, liveOptions()).compose(v -> service.pause(TABLE, "orders", "billing"))
                .compose(v -> service.getCurrentCursor(key())).map(cursor -> {
                    assertEquals(last, cursor);
                    assertEquals(List.of("new"), received);
                    return cursor;
                })).onComplete(context.succeeding(v -> context.completeNow()));
    }

    @Test
    void typedObjectPayloadUsesTheEventStoreSerializer(Vertx vertx, VertxTestContext context) {
        typedStore = new BiTemporalEventStoreFactory(vertx, manager).createEventStore(Payload.class, TABLE);
        Payload payload = new Payload("order", 42);
        List<Payload> received = new ArrayList<>();
        register(null, null).compose(v -> typedStore.append("order.created", payload, Instant.now()))
            .compose(v -> service.catchUp(TABLE, "orders", "billing", Payload.class, message -> {
                received.add(message.getPayload().getPayload());
                return Future.succeededFuture();
            }, 1)).map(v -> {
                assertEquals(List.of(payload), received);
                return v;
            }).onComplete(context.succeeding(v -> context.completeNow()));
    }

    @Test
    void sameIdentityInTwoSchemasDeliversOnlyItsOwnPayload(Vertx vertx, VertxTestContext context) {
        String otherSchema = "replay_" + UUID.randomUUID().toString().replace("-", "");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, otherSchema, SchemaComponent.BITEMPORAL);
        otherManager = new PeeGeeQManager(new PeeGeeQConfiguration("default", PeeGeeQTestConfig.builder()
            .from(postgres).schema(otherSchema).property("peegeeq.health.enabled", "false")
            .property("peegeeq.metrics.enabled", "false").build()));
        List<String> own = new ArrayList<>();
        List<String> other = new ArrayList<>();
        otherManager.start().compose(v -> {
            var factory = new BiTemporalEventStoreFactory(vertx, otherManager);
            competitor = factory.createBiTemporalSubscriptionService();
            otherStore = factory.createEventStore(String.class, TABLE);
            return Future.join(append("order.created", "tenant-a", "a"),
                otherStore.append("order.created", "tenant-b", Instant.now()));
        }).compose(v -> Future.join(service.subscribe(TABLE, "orders", "billing", null, null, String.class, message -> {
            own.add(message.getPayload().getPayload());
            return Future.succeededFuture();
        }, liveOptions()), competitor.subscribe(TABLE, "orders", "billing", null, null, String.class, message -> {
            other.add(message.getPayload().getPayload());
            return Future.succeededFuture();
        }, liveOptions()))).compose(v -> Future.join(service.close(), competitor.close())).map(v -> {
            assertEquals(List.of("tenant-a"), own);
            assertEquals(List.of("tenant-b"), other);
            return v;
        }).onComplete(context.succeeding(v -> context.completeNow()));
    }

    @Test
    void expiredLeaseAllowsTakeoverButFencesTheOldAcknowledgement(Vertx vertx, VertxTestContext context) {
        competitor = new BiTemporalEventStoreFactory(vertx, manager).createBiTemporalSubscriptionService();
        List<String> received = new ArrayList<>();
        register(null, null).compose(v -> append("order.created", "one", "a")).compose(last ->
            service.catchUp(TABLE, "orders", "billing", String.class, message -> {
                received.add("old");
                return manager.getPool().withTransaction(connection -> connection.query("UPDATE " + schema
                    + ".bitemporal_subscriptions SET lease_until=clock_timestamp()-interval '1 second'")
                    .execute().mapEmpty()).compose(v -> competitor.catchUp(TABLE, "orders", "billing", String.class,
                        replayed -> {
                            received.add("new");
                            return Future.succeededFuture();
                        }, 1));
            }, 1).transform(result -> {
                assertInstanceOf(IllegalStateException.class, result.cause());
                assertTrue(result.cause().getMessage().contains("stale owner"));
                assertEquals(List.of("old", "new"), received);
                return service.getCurrentCursor(key());
            }).map(cursor -> {
                assertEquals(last, cursor);
                return cursor;
            })).onComplete(context.succeeding(v -> context.completeNow()));
    }

    @Test
    void renewsLeaseWhileWaitingForHandlerAcknowledgement(Vertx vertx, VertxTestContext context) {
        var options = SubscriptionOptions.builder().durableEnabled(true).subscriptionName("orders")
            .startPosition(dev.mars.peegeeq.api.messaging.StartPosition.FROM_BEGINNING)
            .heartbeatIntervalSeconds(1).heartbeatTimeoutSeconds(3).build();
        service.registerDefinition(TABLE, "orders", "billing", null, null, options)
            .compose(v -> append("order.created", "one", "a"))
            .compose(last -> service.catchUp(TABLE, "orders", "billing", String.class, message ->
                manager.getPool().query("SELECT lease_until FROM " + schema + ".bitemporal_subscriptions")
                    .execute().compose(rows -> waitForRenewal(vertx,
                        rows.iterator().next().getOffsetDateTime("lease_until"), System.nanoTime() + 4_000_000_000L)), 1)
                .compose(v -> service.getCurrentCursor(key())).map(cursor -> {
                    assertEquals(last, cursor);
                    return cursor;
                })).onComplete(context.succeeding(v -> context.completeNow()));
    }

    private Future<Void> waitForRenewal(Vertx vertx, java.time.OffsetDateTime initial, long deadline) {
        return manager.getPool().query("SELECT lease_until FROM " + schema + ".bitemporal_subscriptions")
            .execute().compose(rows -> {
                if (rows.iterator().next().getOffsetDateTime("lease_until").isAfter(initial)) return Future.succeededFuture();
                if (System.nanoTime() >= deadline) return Future.failedFuture(new AssertionError("Lease was not renewed"));
                return vertx.timer(10).compose(v -> waitForRenewal(vertx, initial, deadline));
            });
    }

    @Test
    void competingReplayCannotDispatchUntilOwnerReleases(Vertx vertx, VertxTestContext context) {
        competitor = new BiTemporalEventStoreFactory(vertx, manager).createBiTemporalSubscriptionService();
        Promise<Void> entered = Promise.promise();
        releaseWriter = Promise.promise();
        register(null, null).compose(v -> append("order.created", "one", "a")).compose(last -> {
            writer = service.catchUp(TABLE, "orders", "billing", String.class, message -> {
                entered.tryComplete();
                return releaseWriter.future();
            }, 1).onFailure(entered::tryFail);
            return entered.future().compose(v -> competitor.catchUp(TABLE, "orders", "billing", String.class,
                message -> Future.failedFuture(new AssertionError("Competing owner dispatched")), 1))
                .transform(result -> {
                    assertInstanceOf(IllegalStateException.class, result.cause());
                    assertTrue(result.cause().getMessage().contains("owned"));
                    releaseWriter.complete();
                    return writer;
                }).compose(v -> competitor.catchUp(TABLE, "orders", "billing", String.class,
                    message -> Future.failedFuture(new AssertionError("Acknowledged event redelivered")), 1))
                .compose(v -> competitor.getCurrentCursor(key())).map(cursor -> {
                    assertEquals(last, cursor);
                    return cursor;
                });
        }).onComplete(context.succeeding(v -> context.completeNow()));
    }

    @Test
    void handsOffWritesDuringCatchUpAndContinuesLiveWithoutDuplicates(Vertx vertx, VertxTestContext context) {
        List<String> received = new ArrayList<>();
        Promise<Void> live = Promise.promise();
        var options = SubscriptionOptions.builder().durableEnabled(true).subscriptionName("orders")
            .startPosition(dev.mars.peegeeq.api.messaging.StartPosition.FROM_BEGINNING)
            .replayBatchSize(1).build();
        append("order.created", "history", "a").compose(v -> service.subscribe(TABLE, "orders", "billing",
            null, null, String.class, message -> {
                String payload = message.getPayload().getPayload();
                received.add(payload);
                if (payload.equals("history")) return append("order.created", "handoff", "a").mapEmpty();
                if (payload.equals("handoff")) return append("order.created", "live", "a").mapEmpty();
                if (payload.equals("live")) live.tryComplete();
                return Future.succeededFuture();
            }, options)).compose(v -> live.future().timeout(10, java.util.concurrent.TimeUnit.SECONDS))
            .compose(v -> service.pause(TABLE, "orders", "billing"))
            .map(v -> {
                assertEquals(List.of("history", "handoff", "live"), received);
                return v;
            }).onComplete(context.succeeding(v -> context.completeNow()));
    }

    @Test
    void delayedLowerIdCommitCannotBeSkipped(Vertx vertx, VertxTestContext context) {
        List<String> received = new ArrayList<>();
        Promise<Void> inserted = Promise.promise();
        releaseWriter = Promise.promise();
        register(null, null).compose(v -> {
            writer = manager.getPool().withTransaction(connection -> store.appendBuilder()
                .eventType("order.created").payload("low").validTime(Instant.now())
                .inTransaction(connection).execute().map(event -> {
                    inserted.complete();
                    return (Void) null;
                }).compose(ignored -> releaseWriter.future())).onFailure(inserted::tryFail);
            return inserted.future();
        }).compose(v -> append("order.created", "high", "a"))
            .compose(high -> {
                Future<Void> replay = service.catchUp(TABLE, "orders", "billing", String.class, message -> {
                    received.add(message.getPayload().getPayload());
                    return Future.succeededFuture();
                }, 1);
                return waitForWriterBarrier(vertx, replay, System.nanoTime() + 4_000_000_000L)
                    .compose(v -> {
                        releaseWriter.complete();
                        return Future.join(writer, replay);
                    }).compose(v -> service.getCurrentCursor(key())).map(cursor -> {
                        assertEquals(high, cursor);
                        assertEquals(List.of("low", "high"), received);
                        return cursor;
                    });
            }).onComplete(context.succeeding(v -> context.completeNow()));
    }

    private Future<Void> waitForWriterBarrier(Vertx vertx, Future<Void> replay, long deadline) {
        if (replay.isComplete()) return Future.failedFuture(new AssertionError("Replay bypassed the uncommitted lower ID"));
        return manager.getPool().preparedQuery("SELECT COUNT(*) AS waiting FROM pg_locks "
            + "WHERE relation=$1::regclass AND mode='ShareLock' AND NOT granted")
            .execute(io.vertx.sqlclient.Tuple.of(schema + "." + TABLE)).compose(rows -> {
                if (rows.iterator().next().getLong("waiting") > 0) return Future.succeededFuture();
                if (System.nanoTime() >= deadline) return Future.failedFuture(new AssertionError("No replay writer barrier observed"));
                return vertx.timer(10).compose(v -> waitForWriterBarrier(vertx, replay, deadline));
            });
    }

    @Test
    void replaysMultipleBoundedBatchesAndDoesNotRedeliverAcknowledgedEvents(VertxTestContext context) {
        List<String> received = new ArrayList<>();
        register(null, null).compose(v -> append("order.created", "one", "a"))
            .compose(v -> append("order.created", "two", "a"))
            .compose(v -> append("order.created", "three", "a"))
            .compose(last -> service.catchUp(TABLE, "orders", "billing", String.class, message -> {
                assertEquals("test", message.getHeaders().get("source"));
                assertEquals("correlation", message.getPayload().getCorrelationId());
                received.add(message.getPayload().getPayload());
                return Future.succeededFuture();
            }, 2).compose(v -> service.getCurrentCursor(key())).compose(cursor -> {
                assertEquals(last, cursor);
                assertEquals(List.of("one", "two", "three"), received);
                return service.catchUp(TABLE, "orders", "billing", String.class, message ->
                    Future.failedFuture(new AssertionError("Acknowledged event redelivered")), 2);
            })).onComplete(context.succeeding(v -> context.completeNow()));
    }

    @Test
    void wildcardAndAggregateFiltersAdvancePastNonmatchingRows(VertxTestContext context) {
        List<String> received = new ArrayList<>();
        register("order.*", "a").compose(v -> append("order.created", "match", "a"))
            .compose(v -> append("order.created.extra", "wrong-depth", "a"))
            .compose(v -> append("order.created", "wrong-aggregate", "b"))
            .compose(last -> service.catchUp(TABLE, "orders", "billing", String.class, message -> {
                received.add(message.getPayload().getPayload());
                return Future.succeededFuture();
            }, 1).compose(v -> service.getCurrentCursor(key())).map(cursor -> {
                assertEquals(last, cursor);
                assertEquals(List.of("match"), received);
                return cursor;
            })).onComplete(context.succeeding(v -> context.completeNow()));
    }

    @Test
    void handlerFailurePreservesLastAcknowledgementAndCanResume(VertxTestContext context) {
        IllegalStateException failure = new IllegalStateException("handler failure");
        register(null, null).compose(v -> append("order.created", "one", "a"))
            .compose(first -> append("order.created", "two", "a").compose(last ->
                service.catchUp(TABLE, "orders", "billing", String.class, message ->
                    "two".equals(message.getPayload().getPayload()) ? Future.failedFuture(failure)
                        : Future.succeededFuture(), 2).transform(result -> {
                    assertSame(failure, result.cause());
                    return service.getCurrentCursor(key());
                }).compose(cursor -> {
                    assertEquals(first, cursor);
                    return service.catchUp(TABLE, "orders", "billing", String.class, message -> {
                        assertEquals("two", message.getPayload().getPayload());
                        return Future.succeededFuture();
                    }, 2);
                }).compose(v -> service.getCurrentCursor(key())).map(cursor -> {
                    assertEquals(last, cursor);
                    return cursor;
                }))).onComplete(context.succeeding(v -> context.completeNow()));
    }
}
