package dev.mars.peegeeq.bitemporal;

import dev.mars.peegeeq.api.EventStore;
import dev.mars.peegeeq.api.EventStoreFactory;
import dev.mars.peegeeq.api.messaging.StartPosition;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.api.subscription.SubscriptionState;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Persistence contracts only; replay and live delivery are separate implementation phases. */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
class DurableBiTemporalSubscriptionIntegrationTest {
    private static final String TABLE = "bitemporal_event_log";
    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private PeeGeeQManager otherManager;
    private DurableBiTemporalSubscriptionCoordinator service;
    private DurableBiTemporalSubscriptionCoordinator otherService;
    private EventStore<String> store;
    private String schema;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext context) {
        schema = "durable_" + UUID.randomUUID().toString().replace("-", "");
        manager = newManager(schema);
        manager.start().map(v -> {
            var factory = new BiTemporalEventStoreFactory(vertx, manager);
            service = factory.createBiTemporalSubscriptionService();
            store = factory.createEventStore(String.class, TABLE);
            return (Void) null;
        }).onComplete(context.succeeding(v -> context.completeNow()));
    }

    private PeeGeeQManager newManager(String tenant) {
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, tenant, SchemaComponent.BITEMPORAL);
        return new PeeGeeQManager(new PeeGeeQConfiguration("default", PeeGeeQTestConfig.builder()
            .from(postgres).schema(tenant)
            .property("peegeeq.health.enabled", "false")
            .property("peegeeq.metrics.enabled", "false").build()));
    }

    @AfterEach
    void tearDown(VertxTestContext context) {
        Future.join(
            service == null ? Future.succeededFuture() : service.close(),
            otherService == null ? Future.succeededFuture() : otherService.close(),
            store == null ? Future.succeededFuture() : store.close())
            .transform(closed -> Future.join(
                manager == null ? Future.succeededFuture() : manager.closeReactive(),
                otherManager == null ? Future.succeededFuture() : otherManager.closeReactive())
                .compose(v -> closed.succeeded() ? Future.succeededFuture() : Future.failedFuture(closed.cause())))
            .onComplete(context.succeeding(v -> context.completeNow()));
    }

    private SubscriptionOptions options(StartPosition position) {
        return SubscriptionOptions.builder().durableEnabled(true).subscriptionName("orders")
            .startPosition(position).heartbeatIntervalSeconds(7).heartbeatTimeoutSeconds(21).build();
    }

    private Future<Void> registerDefinition(SubscriptionOptions options) {
        return service.registerDefinition(TABLE, "orders", "billing", "order.*", "customer-1", options);
    }

    private DurableBiTemporalSubscriptionCoordinator.SubscriptionKey key() {
        return new DurableBiTemporalSubscriptionCoordinator.SubscriptionKey(TABLE, "orders", "billing");
    }

    private Future<Long> append() {
        return store.append("order.created", "payload", Instant.now())
            .compose(v -> manager.getPool().query("SELECT MAX(id) AS id FROM " + schema + "." + TABLE).execute())
            .map(rows -> rows.iterator().next().getLong("id"));
    }

    @Test
    void unavailableDeliveryFailsInsteadOfPretendingToSubscribe(VertxTestContext context) {
        service.subscribe(TABLE, "orders", "billing", "order.*", "customer-1",
            message -> Future.succeededFuture(), options(StartPosition.FROM_BEGINNING))
            .onComplete(context.failing(error -> context.verify(() -> {
                assertInstanceOf(UnsupportedOperationException.class, error);
                context.completeNow();
            })));
    }

    @Test
    void publicFactoryPersistsDefinitionAndInitialCursor(VertxTestContext context, Vertx vertx) {
        EventStoreFactory factory = new BiTemporalEventStoreFactory(vertx, manager);
        otherService = (DurableBiTemporalSubscriptionCoordinator) factory.createBiTemporalSubscriptionService();
        registerDefinition(options(StartPosition.FROM_BEGINNING))
            .compose(v -> otherService.getSubscription(TABLE, "orders", "billing"))
            .onComplete(context.succeeding(info -> context.verify(() -> {
                assertEquals(TABLE, info.tableName());
                assertEquals("orders", info.subscriptionName());
                assertEquals("billing", info.consumerGroup());
                assertEquals("order.*", info.eventType());
                assertEquals("customer-1", info.aggregateId());
                assertEquals(SubscriptionState.ACTIVE, info.state());
                assertEquals(0L, info.startFromEventId());
                assertEquals(0L, info.lastProcessedId());
                assertEquals(7, info.heartbeatIntervalSeconds());
                assertEquals(21, info.heartbeatTimeoutSeconds());
                assertNotNull(info.subscribedAt());
                assertNotNull(info.lastHeartbeatAt());
                context.completeNow();
            })));
    }

    @Test
    void fromNowStartsAtExistingHighWaterMark(VertxTestContext context) {
        append().compose(id -> registerDefinition(options(StartPosition.FROM_NOW))
            .compose(v -> service.getCurrentCursor(key())).map(cursor -> {
                assertEquals(id, cursor);
                return cursor;
            })).onComplete(context.succeeding(v -> context.completeNow()));
    }

    @Test
    void explicitStartPositionIsPersisted(VertxTestContext context) {
        append().compose(id -> registerDefinition(SubscriptionOptions.builder().durableEnabled(true)
            .subscriptionName("orders").startFromMessageId(id).build())
            .compose(v -> service.getCurrentCursor(key())).map(cursor -> {
                assertEquals(id, cursor);
                return cursor;
            })).onComplete(context.succeeding(v -> context.completeNow()));
    }

    @Test
    void cursorAndIdentitySurviveServiceRestartAndReregistration(Vertx vertx, VertxTestContext context) {
        registerDefinition(options(StartPosition.FROM_BEGINNING)).compose(v -> append())
            .compose(id -> service.advanceCursor(key(), id).compose(v -> service.close()).map(v -> {
                service = new BiTemporalEventStoreFactory(vertx, manager).createBiTemporalSubscriptionService();
                return id;
            }))
            .compose(id -> service.loadActiveSubscriptionDefinitions()
                .compose(v -> registerDefinition(options(StartPosition.FROM_BEGINNING)))
                .compose(v -> service.listSubscriptions(TABLE)).map(list -> {
                    assertEquals(1, list.size());
                    assertEquals(id, list.getFirst().lastProcessedId());
                    assertNotNull(list.getFirst().lastProcessedAt());
                    return list;
                })).onComplete(context.succeeding(v -> context.completeNow()));
    }

    @Test
    void lifecycleAndResetPersistWithoutDeletingDefinition(VertxTestContext context) {
        registerDefinition(options(StartPosition.FROM_BEGINNING)).compose(v -> append())
            .compose(id -> service.advanceCursor(key(), id))
            .compose(v -> service.pauseSubscription(key()))
            .compose(v -> service.getSubscription(TABLE, "orders", "billing"))
            .compose(info -> {
                assertEquals(SubscriptionState.PAUSED, info.state());
                return service.resetCursor(key(), 0);
            }).compose(v -> service.getCurrentCursor(key()))
            .compose(cursor -> {
                assertEquals(0L, cursor);
                return service.resumeSubscription(key());
            }).compose(v -> service.updateHeartbeat(TABLE, "orders", "billing"))
            .compose(v -> service.getSubscription(TABLE, "orders", "billing"))
            .compose(info -> {
                assertTrue(info.isActive());
                assertNotNull(info.lastActiveAt());
                return service.cancel(TABLE, "orders", "billing");
            }).compose(v -> service.getSubscription(TABLE, "orders", "billing"))
            .onComplete(context.succeeding(info -> context.verify(() -> {
                assertEquals(SubscriptionState.CANCELLED, info.state());
                assertEquals(0L, info.lastProcessedId());
                context.completeNow();
            })));
    }

    @Test
    void failedCursorTransactionRollsBack(VertxTestContext context) {
        registerDefinition(options(StartPosition.FROM_BEGINNING)).compose(v -> append())
            .compose(id -> manager.getPool().withTransaction(connection ->
                service.advanceCursor(connection, key(), id)
                    .compose(v -> Future.failedFuture(new IllegalStateException("rollback proof")))))
            .transform(result -> {
                assertInstanceOf(IllegalStateException.class, result.cause());
                return service.getCurrentCursor(key());
            }).onComplete(context.succeeding(cursor -> context.verify(() -> {
                assertEquals(0L, cursor);
                context.completeNow();
            })));
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, Long.MAX_VALUE})
    void invalidResetDoesNotChangeCursor(long value, VertxTestContext context) {
        registerDefinition(options(StartPosition.FROM_BEGINNING))
            .compose(v -> service.resetCursor(key(), value))
            .transform(result -> {
                assertInstanceOf(IllegalArgumentException.class, result.cause());
                return service.getCurrentCursor(key());
            }).onComplete(context.succeeding(cursor -> context.verify(() -> {
                assertEquals(0L, cursor);
                context.completeNow();
            })));
    }

    @Test
    void backwardAdvanceFailsAndLeavesProgressUnchanged(VertxTestContext context) {
        registerDefinition(options(StartPosition.FROM_BEGINNING)).compose(v -> append())
            .compose(id -> service.advanceCursor(key(), id)
                .compose(v -> service.advanceCursor(key(), 0)).transform(result -> {
                    assertInstanceOf(IllegalArgumentException.class, result.cause());
                    return service.getCurrentCursor(key()).map(cursor -> {
                        assertEquals(id, cursor);
                        return cursor;
                    });
                })).onComplete(context.succeeding(v -> context.completeNow()));
    }

    @Test
    void cancelledSubscriptionCannotBeResumedOrReregistered(VertxTestContext context) {
        registerDefinition(options(StartPosition.FROM_BEGINNING))
            .compose(v -> service.cancel(TABLE, "orders", "billing"))
            .compose(v -> service.resumeSubscription(key()))
            .transform(result -> {
                assertInstanceOf(IllegalStateException.class, result.cause());
                return registerDefinition(options(StartPosition.FROM_BEGINNING));
            }).onComplete(context.failing(error -> context.verify(() -> {
                assertInstanceOf(IllegalStateException.class, error);
                context.completeNow();
            })));
    }

    @ParameterizedTest
    @ValueSource(strings = {"lookup", "advance", "pause", "resume", "cancel", "heartbeat", "reset"})
    void missingSubscriptionFails(String operation, VertxTestContext context) {
        Future<?> action = switch (operation) {
            case "lookup" -> service.getCurrentCursor(key());
            case "advance" -> service.advanceCursor(key(), 0);
            case "pause" -> service.pauseSubscription(key());
            case "resume" -> service.resumeSubscription(key());
            case "cancel" -> service.cancel(TABLE, "orders", "billing");
            case "heartbeat" -> service.updateHeartbeat(TABLE, "orders", "billing");
            default -> service.resetCursor(key(), 0);
        };
        action.onComplete(context.failing(error -> context.verify(() -> {
            assertInstanceOf(java.util.NoSuchElementException.class, error);
            context.completeNow();
        })));
    }

    @Test
    void rejectsChangedFilterWithoutOverwritingDefinition(VertxTestContext context) {
        registerDefinition(options(StartPosition.FROM_BEGINNING))
            .compose(v -> service.registerDefinition(TABLE, "orders", "billing", "different", null,
                options(StartPosition.FROM_BEGINNING)))
            .transform(result -> {
                assertInstanceOf(IllegalArgumentException.class, result.cause());
                return service.getSubscription(TABLE, "orders", "billing");
            }).onComplete(context.succeeding(info -> context.verify(() -> {
                assertEquals("order.*", info.eventType());
                assertEquals("customer-1", info.aggregateId());
                context.completeNow();
            })));
    }

    @Test
    void schemaScopedReadsAndWritesDoNotTouchOtherTenant(Vertx vertx, VertxTestContext context) {
        otherManager = newManager(schema + "_b");
        otherManager.start().map(v -> {
            otherService = new BiTemporalEventStoreFactory(vertx, otherManager).createBiTemporalSubscriptionService();
            return (Void) null;
        }).compose(v -> registerDefinition(options(StartPosition.FROM_BEGINNING)))
            .compose(v -> otherService.listSubscriptions(TABLE))
            .compose(list -> {
                assertTrue(list.isEmpty());
                return otherService.registerDefinition(TABLE, "orders", "billing", null, null,
                    options(StartPosition.FROM_BEGINNING));
            }).compose(v -> service.cancel(TABLE, "orders", "billing"))
            .compose(v -> otherService.getSubscription(TABLE, "orders", "billing"))
            .onComplete(context.succeeding(info -> context.verify(() -> {
                assertTrue(info.isActive());
                assertNull(info.eventType());
                context.completeNow();
            })));
    }

    @Test
    void missingEventTableFailsWithoutInsertingDefinition(VertxTestContext context) {
        service.registerDefinition("missing_events", "orders", "billing", null, null,
            options(StartPosition.FROM_NOW))
            .transform(result -> {
                assertEquals("42P01", assertInstanceOf(PgException.class, result.cause()).getSqlState());
                return service.listSubscriptions("missing_events");
            }).onComplete(context.succeeding(list -> context.verify(() -> {
                assertTrue(list.isEmpty());
                context.completeNow();
            })));
    }

    @Test
    void concurrentRegistrationsKeepOneDefinition(VertxTestContext context) {
        Future.join(registerDefinition(options(StartPosition.FROM_BEGINNING)),
                registerDefinition(options(StartPosition.FROM_BEGINNING)))
            .compose(v -> service.listSubscriptions(TABLE))
            .onComplete(context.succeeding(list -> context.verify(() -> {
                assertEquals(1, list.size());
                assertEquals(0L, list.getFirst().lastProcessedId());
                context.completeNow();
            })));
    }

    @Test
    void competingCursorUpdatesCannotRegressProgress(VertxTestContext context) {
        registerDefinition(options(StartPosition.FROM_BEGINNING)).compose(v -> append())
            .compose(low -> append().compose(high -> {
                Future<Void> lower = service.advanceCursor(key(), low);
                Future<Void> higher = service.advanceCursor(key(), high);
                return Future.join(lower.transform(result -> {
                    if (result.failed()) {
                        assertInstanceOf(IllegalArgumentException.class, result.cause());
                    }
                    return Future.succeededFuture();
                }), higher).compose(v -> service.getCurrentCursor(key())).map(cursor -> {
                    assertEquals(high, cursor);
                    return cursor;
                });
            })).onComplete(context.succeeding(v -> context.completeNow()));
    }

    @Test
    void activeDefinitionLoadingDetectsCorruptCursor(VertxTestContext context) {
        registerDefinition(options(StartPosition.FROM_BEGINNING))
            .compose(v -> manager.getPool().withTransaction(connection -> connection.query(
                "UPDATE " + schema + ".bitemporal_subscriptions SET last_processed_id=999").execute()))
            .compose(v -> service.loadActiveSubscriptionDefinitions())
            .onComplete(context.failing(error -> context.verify(() -> {
                assertInstanceOf(IllegalArgumentException.class, error);
                context.completeNow();
            })));
    }

    @ParameterizedTest
    @ValueSource(strings = {"disabled", "mismatched-name", "timestamp", "negative-id", "beyond-head", "blank-group", "qualified-table"})
    void invalidDefinitionDoesNotWriteMetadata(String invalid, VertxTestContext context) {
        SubscriptionOptions invalidOptions = switch (invalid) {
            case "disabled" -> SubscriptionOptions.defaults();
            case "mismatched-name" -> SubscriptionOptions.builder().durableEnabled(true).subscriptionName("other").build();
            case "timestamp" -> SubscriptionOptions.builder().durableEnabled(true).subscriptionName("orders")
                .startFromTimestamp(Instant.now()).build();
            case "negative-id" -> SubscriptionOptions.builder().durableEnabled(true).subscriptionName("orders")
                .startFromMessageId(-1).build();
            case "beyond-head" -> SubscriptionOptions.builder().durableEnabled(true).subscriptionName("orders")
                .startFromMessageId(99).build();
            default -> options(StartPosition.FROM_BEGINNING);
        };
        service.registerDefinition(invalid.equals("qualified-table") ? "other." + TABLE : TABLE,
            "orders", invalid.equals("blank-group") ? " " : "billing", null, null, invalidOptions)
            .transform(result -> {
                assertInstanceOf(IllegalArgumentException.class, result.cause());
                return service.listSubscriptions(TABLE);
            }).onComplete(context.succeeding(list -> context.verify(() -> {
                assertTrue(list.isEmpty());
                context.completeNow();
            })));
    }

    @Test
    void pausedDefinitionCanReregisterWithoutResettingCursor(VertxTestContext context) {
        registerDefinition(options(StartPosition.FROM_BEGINNING)).compose(v -> append())
            .compose(id -> service.advanceCursor(key(), id)
                .compose(v -> service.pauseSubscription(key()))
                .compose(v -> registerDefinition(options(StartPosition.FROM_BEGINNING)))
                .compose(v -> service.getSubscription(TABLE, "orders", "billing"))
                .map(info -> {
                    assertTrue(info.isActive());
                    assertEquals(id, info.lastProcessedId());
                    return info;
                })).onComplete(context.succeeding(v -> context.completeNow()));
    }

    @Test
    void cursorTransactionMustBeExplicit(VertxTestContext context) {
        manager.getPool().withConnection(connection -> service.advanceCursor(connection, key(), 0))
            .onComplete(context.failing(error -> context.verify(() -> {
                assertInstanceOf(IllegalStateException.class, error);
                context.completeNow();
            })));
    }

    @Test
    void closedServiceRejectsOperationsButDoesNotCloseSharedPool(VertxTestContext context) {
        service.close().compose(v -> service.listSubscriptions(TABLE)).transform(result -> {
            assertInstanceOf(IllegalStateException.class, result.cause());
            return manager.getPool().query("SELECT 1 AS alive").execute();
        }).onComplete(context.succeeding(rows -> context.verify(() -> {
            assertEquals(1, rows.iterator().next().getInteger("alive"));
            context.completeNow();
        })));
    }
}
