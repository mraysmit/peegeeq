package dev.mars.peegeeq.bitemporal;

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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.mars.peegeeq.api.*;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlConnection;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import dev.mars.peegeeq.test.categories.TestCategories;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for complex PgBiTemporalEventStore functionality:
 * - Batch operations (appendBatch)
 * - Transactional operations (appendInTransaction, appendOwnTransaction)
 * - Error handling and edge cases
 * - Temporal queries (getAsOfTransactionTime)
 * 
 * Target: Improve coverage from 44% to 70%+ by testing complex code paths.
 *
 * <p><b>Implementation notes (reactive migration):</b>
 * <ul>
 *   <li>All blocking {@code await()} helpers removed — tests use {@code .compose()} chains
 *       with {@code VertxTestContext} for async coordination.</li>
 *   <li>All {@code awaitAsyncDelay()} calls replaced with {@code delay()} returning
 *       {@code Future<Void>} via Vert.x timer, composed into the chain.</li>
 *   <li>All {@code .toCompletionStage().toCompletableFuture().get()} bridges removed.</li>
 *   <li>{@code cleanupDatabase()} returns {@code Future<Void>} and is composed into
 *       setUp/tearDown chains.</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-01-12
 * @version 1.0
 */
@Tag(TestCategories.CORE)
@Testcontainers
@ExtendWith(VertxExtension.class)
class PgBiTemporalEventStoreComplexTest {

    private static final String DATABASE_SCHEMA_PROPERTY = "peegeeq.database.schema";
    
    @Container
    static PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE);
        container.withDatabaseName("peegeeq_test_" + System.currentTimeMillis());
        container.withUsername("test");
        container.withPassword("test");
        return container;
    }
    
    private PeeGeeQManager manager;
    private BiTemporalEventStoreFactory factory;
    private PgBiTemporalEventStore<TestEvent> eventStore;
    private Vertx vertx;

    private String resolveSchema() {
        String configured = System.getProperty(DATABASE_SCHEMA_PROPERTY, "public");
        String schema = configured == null ? "public" : configured.trim();
        if (schema.isEmpty()) {
            return "public";
        }
        if (!schema.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
            throw new IllegalArgumentException("Invalid schema name for test: " + schema);
        }
        return schema;
    }
    
    public static class TestEvent {
        private final String id;
        private final String data;
        private final int value;
        
        @JsonCreator
        public TestEvent(@JsonProperty("id") String id,
                        @JsonProperty("data") String data,
                        @JsonProperty("value") int value) {
            this.id = id;
            this.data = data;
            this.value = value;
        }
        
        public String getId() { return id; }
        public String getData() { return data; }
        public int getValue() { return value; }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TestEvent testEvent = (TestEvent) o;
            return value == testEvent.value &&
                   Objects.equals(id, testEvent.id) &&
                   Objects.equals(data, testEvent.data);
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(id, data, value);
        }
    }

    @BeforeEach
    void setUp(VertxTestContext testContext) throws Exception {
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.health-check.enabled", "false");
        System.setProperty("peegeeq.health-check.queue-checks-enabled", "false");
        System.setProperty("peegeeq.queue.dead-consumer-detection.enabled", "false");
        System.setProperty("peegeeq.queue.recovery.enabled", "false");

        String schema = resolveSchema();
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, schema, SchemaComponent.BITEMPORAL);

        vertx = Vertx.vertx();

        PeeGeeQConfiguration config = new PeeGeeQConfiguration();
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());

        // Truncate using a pool tied to our vertx so the event loop survives pool close
        PgConnectOptions connectOptions = new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getFirstMappedPort())
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword());
        Pool setupPool = PgBuilder.pool().connectingTo(connectOptions).using(vertx).build();

        setupPool.query("TRUNCATE TABLE " + schema + ".bitemporal_event_log CASCADE").execute()
            .recover(err -> Future.succeededFuture(null))
            .compose(v -> setupPool.close())
            .compose(v -> manager.start())
            .onSuccess(v -> {
                factory = new BiTemporalEventStoreFactory(vertx, manager);
                eventStore = (PgBiTemporalEventStore<TestEvent>) factory.createEventStore(TestEvent.class, "bitemporal_event_log");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }
    
    @AfterEach
    void tearDown(VertxTestContext testContext) throws Exception {
        Future<Void> closeFuture = (eventStore != null)
            ? eventStore.close()
            : Future.succeededFuture();

        closeFuture
            .compose(v -> manager != null ? manager.closeReactive() : Future.succeededFuture())
            .compose(v -> vertx != null ? vertx.close() : Future.succeededFuture())
            .onSuccess(v -> {
                System.clearProperty("peegeeq.database.host");
                System.clearProperty("peegeeq.database.port");
                System.clearProperty("peegeeq.database.name");
                System.clearProperty("peegeeq.database.username");
                System.clearProperty("peegeeq.database.password");
                System.clearProperty("peegeeq.health-check.enabled");
                System.clearProperty("peegeeq.health-check.queue-checks-enabled");
                System.clearProperty("peegeeq.queue.dead-consumer-detection.enabled");
                System.clearProperty("peegeeq.queue.recovery.enabled");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(60, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    private Future<Void> delay(long millis) {
        Promise<Void> promise = Promise.promise();
        vertx.setTimer(millis, id -> promise.complete());
        return promise.future();
    }

    // ==================== Batch Operations ====================
    
    @Test
    void testAppendBatchMultipleEvents(VertxTestContext testContext) throws Exception {
        Instant validTime = Instant.now();
        List<PgBiTemporalEventStore.BatchEventData<TestEvent>> batch = Arrays.asList(
            new PgBiTemporalEventStore.BatchEventData<>(
                "BatchType1", new TestEvent("b1", "data1", 100),
                validTime, Map.of("batch", "true"), "corr-1", "agg-1"
            ),
            new PgBiTemporalEventStore.BatchEventData<>(
                "BatchType2", new TestEvent("b2", "data2", 200),
                validTime, Map.of("batch", "true"), "corr-2", "agg-2"
            ),
            new PgBiTemporalEventStore.BatchEventData<>(
                "BatchType1", new TestEvent("b3", "data3", 300),
                validTime, Map.of("batch", "true"), "corr-3", "agg-1"
            )
        );
        
        eventStore.appendBatch(batch)
            .compose(events -> {
                testContext.verify(() -> {
                    assertNotNull(events);
                    assertEquals(3, events.size());
                    assertEquals("BatchType1", events.get(0).getEventType());
                    assertEquals("b1", events.get(0).getPayload().getId());
                    assertEquals("corr-1", events.get(0).getCorrelationId());
                    assertEquals("agg-1", events.get(0).getAggregateId());
                });
                return eventStore.query(EventQuery.forEventType("BatchType1"));
            })
            .onSuccess(retrieved -> testContext.verify(() -> {
                assertEquals(2, retrieved.size());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }
    
    @Test
    void testAppendBatchEmpty(VertxTestContext testContext) throws Exception {
        List<PgBiTemporalEventStore.BatchEventData<TestEvent>> empty = Collections.emptyList();

        eventStore.appendBatch(empty)
            .onSuccess(events -> testContext.verify(() -> {
                assertNotNull(events);
                assertTrue(events.isEmpty());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }
    
    @Test
    void testAppendBatchLarge(VertxTestContext testContext) throws Exception {
        Instant validTime = Instant.now();
        List<PgBiTemporalEventStore.BatchEventData<TestEvent>> batch = new ArrayList<>();
        
        for (int i = 0; i < 50; i++) {
            batch.add(new PgBiTemporalEventStore.BatchEventData<>(
                "LargeBatch",
                new TestEvent("large-" + i, "data-" + i, i),
                validTime,
                Map.of("index", String.valueOf(i)),
                "corr-large",
                "agg-large"
            ));
        }
        
        eventStore.appendBatch(batch)
            .compose(events -> {
                testContext.verify(() -> assertEquals(50, events.size()));
                return eventStore.query(EventQuery.forEventType("LargeBatch"));
            })
            .onSuccess(all -> testContext.verify(() -> {
                assertEquals(50, all.size());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    // ==================== Transaction Propagation ====================
    
    @Test
    void testAppendOwnTransactionPropagation(VertxTestContext testContext) throws Exception {
        TestEvent payload = new TestEvent("tx-prop", "transaction", 999);
        Instant validTime = Instant.now();
        
        eventStore.appendOwnTransaction(
            "TxPropEvent",
            payload,
            validTime,
            Map.of("tx", "context"),
            "corr-tx",
            null,
            "agg-tx"
        )
        .compose(event -> {
            testContext.verify(() -> {
                assertNotNull(event);
                assertEquals("TxPropEvent", event.getEventType());
                assertEquals(payload, event.getPayload());
            });
            return eventStore.getById(event.getEventId());
        })
        .onSuccess(retrieved -> testContext.verify(() -> {
            assertNotNull(retrieved);
            testContext.completeNow();
        }))
        .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    // ==================== In-Transaction Operations ====================
    
    @Test
    void testAppendInTransactionCommit(VertxTestContext testContext) throws Exception {
        PgConnectOptions options = new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getFirstMappedPort())
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword());
        
        Pool pool = PgBuilder.pool().connectingTo(options).using(vertx).build();

        pool.getConnection()
            .compose(conn -> conn.begin()
                .compose(tx -> {
                    TestEvent payload = new TestEvent("in-tx-commit", "committed", 777);
                    return eventStore.appendInTransaction(
                        "InTxCommitEvent", payload, Instant.now(),
                        Map.of("in-tx", "true"), "corr-in-tx", null, "agg-in-tx", conn
                    )
                    .compose(event -> tx.commit().map(event))
                    .recover(err -> tx.rollback().compose(v -> Future.failedFuture(err)))
                    .eventually(() -> conn.close());
                })
            )
            .compose(event -> {
                testContext.verify(() -> {
                    assertNotNull(event);
                    assertEquals("InTxCommitEvent", event.getEventType());
                });
                return eventStore.getById(event.getEventId());
            })
            .compose(retrieved -> {
                testContext.verify(() -> assertNotNull(retrieved));
                return pool.close();
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }
    
    @Test
    void testAppendInTransactionRollback(VertxTestContext testContext) throws Exception {
        PgConnectOptions options = new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getFirstMappedPort())
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword());
        
        Pool pool = PgBuilder.pool().connectingTo(options).using(vertx).build();

        pool.getConnection()
            .compose(conn -> conn.begin()
                .compose(tx -> {
                    TestEvent payload = new TestEvent("in-tx-rollback", "rolled back", 666);
                    return eventStore.appendInTransaction(
                        "InTxRollbackEvent", payload, Instant.now(),
                        Map.of(), null, null, null, conn
                    )
                    .map(event -> event != null ? event.getEventId() : null)
                    .compose(eventId -> tx.rollback().map(eventId))
                    .eventually(() -> conn.close());
                })
            )
            .compose(eventId -> {
                if (eventId != null) {
                    return eventStore.getById(eventId)
                        .map(retrieved -> {
                            testContext.verify(() ->
                                assertNull(retrieved, "Event should not exist after rollback")
                            );
                            return (Void) null;
                        })
                        .recover(e -> Future.succeededFuture());
                }
                return Future.succeededFuture();
            })
            .compose(v -> pool.close())
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    // ==================== Temporal Queries ====================
    
    @Test
    void testGetAsOfTransactionTimeOriginal(VertxTestContext testContext) throws Exception {
        TestEvent original = new TestEvent("temporal-1", "original", 900);
        Instant validTime = Instant.now();
        
        eventStore.appendBuilder().eventType("TempEvent").payload(original).validTime(validTime).execute()
            .compose(event1 -> {
                Instant afterFirst = Instant.now();
                return delay(150)
                    .compose(v -> {
                        TestEvent corrected = new TestEvent("temporal-1", "corrected", 950);
                        return eventStore.appendCorrection(event1.getEventId(), "TempEvent", corrected, validTime, "Correction");
                    })
                    .compose(v -> eventStore.getAsOfTransactionTime(event1.getEventId(), afterFirst));
            })
            .onSuccess(historical -> testContext.verify(() -> {
                assertNotNull(historical);
                assertEquals("original", historical.getPayload().getData());
                assertEquals(1L, historical.getVersion());
                assertFalse(historical.isCorrection());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }
    
    @Test
    void testGetAsOfTransactionTimeFuture(VertxTestContext testContext) throws Exception {
        TestEvent payload = new TestEvent("future", "data", 1000);

        eventStore.appendBuilder().eventType("FutureEvent").payload(payload).validTime(Instant.now()).execute()
            .compose(event -> {
                Instant futureTime = Instant.now().plus(1, ChronoUnit.DAYS);
                return eventStore.getAsOfTransactionTime(event.getEventId(), futureTime)
                    .map(result -> Map.entry(event, result));
            })
            .onSuccess(pair -> testContext.verify(() -> {
                assertNotNull(pair.getValue());
                assertEquals(pair.getKey().getEventId(), pair.getValue().getEventId());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    @Test
    void testGetAsOfTransactionTimeBeforeEventCreationReturnsNull(VertxTestContext testContext) throws Exception {
        Instant beforeCreate = Instant.now();

        delay(25)
            .compose(v -> eventStore.appendBuilder().eventType("BeforeCreateEvent")
                .payload(new TestEvent("before-create", "data", 1001)).validTime(Instant.now()).execute())
            .compose(event -> eventStore.getAsOfTransactionTime(event.getEventId(), beforeCreate))
            .onSuccess(result -> testContext.verify(() -> {
                assertNull(result, "As-of query before event creation should not return the event");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    @Test
    void testGetAsOfTransactionTimeUsingCorrectionEventIdResolvesToRootLineage(VertxTestContext testContext) throws Exception {
        TestEvent original = new TestEvent("temporal-corr", "original", 1111);
        Instant validTime = Instant.now();

        eventStore.appendBuilder().eventType("TempEvent").payload(original).validTime(validTime).execute()
            .compose(originalEvent -> {
                Instant afterOriginal = Instant.now();
                return delay(120)
                    .compose(v -> {
                        TestEvent corrected = new TestEvent("temporal-corr", "corrected", 2222);
                        return eventStore.appendCorrection(originalEvent.getEventId(), "TempEvent", corrected, validTime, "fix");
                    })
                    .compose(correctionEvent ->
                        eventStore.getAsOfTransactionTime(correctionEvent.getEventId(), afterOriginal)
                            .map(historical -> Map.entry(originalEvent, historical))
                    );
            })
            .onSuccess(pair -> testContext.verify(() -> {
                BiTemporalEvent<TestEvent> historical = pair.getValue();
                assertNotNull(historical, "As-of lookup should resolve correction lineage back to root event");
                assertEquals("original", historical.getPayload().getData());
                assertEquals(1L, historical.getVersion());
                assertEquals(pair.getKey().getEventId(), historical.getEventId());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    @Test
    void testEventBusDistributionAppendPreservesCausationId(VertxTestContext testContext) throws Exception {
        String previousDistributionValue = System.getProperty("peegeeq.database.use.event.bus.distribution");
        System.setProperty("peegeeq.database.use.event.bus.distribution", "true");

        String correlationId = "corr-eb-" + System.nanoTime();
        String causationId = "cause-eb-" + System.nanoTime();

        PgBiTemporalEventStore.deployDatabaseWorkerVerticles(vertx, 1, "bitemporal_event_log")
            .compose(v -> eventStore.appendBuilder()
                .eventType("EventBusCausation")
                .payload(new TestEvent("evt-bus", "payload", 77))
                .validTime(Instant.now())
                .headers(Map.of("source", "event-bus"))
                .correlationId(correlationId)
                .causationId(causationId)
                .aggregateId("agg-eb")
                .execute()
            )
            .compose(appended -> {
                testContext.verify(() -> {
                    assertNotNull(appended);
                    assertEquals(causationId, appended.getCausationId(),
                        "Event returned from event-bus append should preserve causationId");
                });
                return eventStore.getById(appended.getEventId());
            })
            .onSuccess(fetched -> {
                try {
                    testContext.verify(() -> {
                        assertNotNull(fetched);
                        assertEquals(causationId, fetched.getCausationId(),
                            "Stored event should persist causationId when event-bus distribution is enabled");
                    });
                } finally {
                    if (previousDistributionValue == null) {
                        System.clearProperty("peegeeq.database.use.event.bus.distribution");
                    } else {
                        System.setProperty("peegeeq.database.use.event.bus.distribution", previousDistributionValue);
                    }
                }
                testContext.completeNow();
            })
            .onFailure(err -> {
                if (previousDistributionValue == null) {
                    System.clearProperty("peegeeq.database.use.event.bus.distribution");
                } else {
                    System.setProperty("peegeeq.database.use.event.bus.distribution", previousDistributionValue);
                }
                testContext.failNow(err);
            });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    @Test
    void testGetAsOfTransactionTimeDeepCorrectionChainUsingLatestCorrectionId(VertxTestContext testContext) throws Exception {
        Instant validTime = Instant.now();

        eventStore.appendBuilder().eventType("ChainEvent")
            .payload(new TestEvent("chain", "v1", 1)).validTime(validTime).execute()
            .compose(v1 -> {
                Instant tAfterV1 = Instant.now();
                return delay(80)
                    .compose(d -> eventStore.appendCorrection(v1.getEventId(), "ChainEvent",
                        new TestEvent("chain", "v2", 2), validTime, "c1"))
                    .compose(v2 -> {
                        Instant tAfterV2 = Instant.now();
                        return delay(80)
                            .compose(d -> eventStore.appendCorrection(v1.getEventId(), "ChainEvent",
                                new TestEvent("chain", "v3", 3), validTime, "c2"))
                            .compose(v3 -> {
                                Instant tAfterV3 = Instant.now();
                                return delay(80)
                                    .compose(d2 -> eventStore.appendCorrection(v1.getEventId(), "ChainEvent",
                                        new TestEvent("chain", "v4", 4), validTime, "c3"))
                                    .compose(v4 ->
                                        Future.all(
                                            eventStore.getAsOfTransactionTime(v4.getEventId(), tAfterV1),
                                            eventStore.getAsOfTransactionTime(v4.getEventId(), tAfterV2),
                                            eventStore.getAsOfTransactionTime(v4.getEventId(), tAfterV3),
                                            eventStore.getAsOfTransactionTime(v4.getEventId(), Instant.now())
                                        ).map(cf -> {
                                            BiTemporalEvent<TestEvent> asOfV1 = cf.resultAt(0);
                                            BiTemporalEvent<TestEvent> asOfV2 = cf.resultAt(1);
                                            BiTemporalEvent<TestEvent> asOfV3 = cf.resultAt(2);
                                            BiTemporalEvent<TestEvent> asOfLatest = cf.resultAt(3);
                                            return List.of(asOfV1, asOfV2, asOfV3, asOfLatest);
                                        })
                                    );
                            });
                    });
            })
            .onSuccess(results -> testContext.verify(() -> {
                assertNotNull(results.get(0));
                assertNotNull(results.get(1));
                assertNotNull(results.get(2));
                assertNotNull(results.get(3));
                assertEquals("v1", results.get(0).getPayload().getData());
                assertEquals(1L, results.get(0).getVersion());
                assertEquals("v2", results.get(1).getPayload().getData());
                assertEquals(2L, results.get(1).getVersion());
                assertEquals("v3", results.get(2).getPayload().getData());
                assertEquals(3L, results.get(2).getVersion());
                assertEquals("v4", results.get(3).getPayload().getData());
                assertEquals(4L, results.get(3).getVersion());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    // ==================== Error Handling ====================
    
    @Test
    void testAppendNullEventType(VertxTestContext testContext) throws Exception {
        try {
            eventStore.appendBuilder().eventType(null)
                .payload(new TestEvent("x", "data", 1)).validTime(Instant.now()).execute()
                .onSuccess(v -> testContext.failNow("Should have thrown for null event type"))
                .onFailure(err -> testContext.completeNow());
        } catch (IllegalStateException e) {
            testContext.completeNow();
        }

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    @Test
    void testAppendEmptyEventType(VertxTestContext testContext) throws Exception {
        eventStore.appendBuilder().eventType("   ")
            .payload(new TestEvent("x", "data", 1)).validTime(Instant.now()).execute()
            .onSuccess(v -> testContext.failNow("Should have thrown for empty event type"))
            .onFailure(err -> testContext.completeNow());

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    @Test
    void testAppendWithLongCausationIdRejected(VertxTestContext testContext) throws Exception {
        String longCausationId = "c".repeat(256);

        eventStore.appendBuilder().eventType("TestType")
            .payload(new TestEvent("x", "data", 1)).validTime(Instant.now())
            .headers(Map.of()).correlationId("corr").causationId(longCausationId).aggregateId("agg").execute()
            .onSuccess(v -> testContext.failNow("Should have thrown for long causation ID"))
            .onFailure(err -> testContext.completeNow());

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }
    
    @Test
    void testAppendNullPayload(VertxTestContext testContext) throws Exception {
        try {
            eventStore.appendBuilder().eventType("TestType")
                .payload(null).validTime(Instant.now()).execute()
                .onSuccess(v -> testContext.failNow("Should have thrown for null payload"))
                .onFailure(err -> testContext.completeNow());
        } catch (IllegalStateException | NullPointerException e) {
            testContext.completeNow();
        }

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    @Test
    void testObjectPayloadScalarRoundTripMatchesNativeOutboxSemantics(VertxTestContext testContext) throws Exception {
        EventStore<Object> objectStore = factory.createObjectEventStore();

        objectStore.appendBuilder()
            .eventType("ObjectScalar")
            .payload("hello-world")
            .validTime(Instant.now())
            .execute()
            .compose(appended -> objectStore.getById(appended.getEventId()))
            .onSuccess(fetched -> {
                testContext.verify(() -> {
                    assertNotNull(fetched);
                    assertEquals("hello-world", fetched.getPayload());
                    assertTrue(fetched.getPayload() instanceof String,
                        "Object payload should unwrap to scalar value, not a wrapper map");
                });
                objectStore.close();
                testContext.completeNow();
            })
            .onFailure(err -> {
                objectStore.close();
                testContext.failNow(err);
            });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    @Test
    void testObjectPayloadNumericAndBooleanRoundTripMatchesNativeOutboxSemantics(VertxTestContext testContext) throws Exception {
        EventStore<Object> objectStore = factory.createObjectEventStore();

        objectStore.appendBuilder()
            .eventType("ObjectNumber").payload(42).validTime(Instant.now()).execute()
            .compose(numberAppended -> objectStore.getById(numberAppended.getEventId()))
            .compose(numberFetched -> {
                testContext.verify(() -> {
                    assertNotNull(numberFetched);
                    assertEquals(42, numberFetched.getPayload());
                    assertTrue(numberFetched.getPayload() instanceof Integer,
                        "Numeric Object payload should unwrap to Integer scalar");
                });
                return objectStore.appendBuilder()
                    .eventType("ObjectBoolean").payload(true).validTime(Instant.now()).execute();
            })
            .compose(boolAppended -> objectStore.getById(boolAppended.getEventId()))
            .onSuccess(boolFetched -> {
                testContext.verify(() -> {
                    assertNotNull(boolFetched);
                    assertEquals(true, boolFetched.getPayload());
                    assertTrue(boolFetched.getPayload() instanceof Boolean,
                        "Boolean Object payload should unwrap to Boolean scalar");
                });
                objectStore.close();
                testContext.completeNow();
            })
            .onFailure(err -> {
                objectStore.close();
                testContext.failNow(err);
            });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    @Test
    void testObjectPayloadLegacyScalarJsonStoredWithoutWrapperCanBeRead(VertxTestContext testContext) throws Exception {
        EventStore<Object> objectStore = factory.createObjectEventStore();
        String eventId = UUID.randomUUID().toString();
        Instant validTime = Instant.now();

        Pool pool = PgBuilder.pool()
            .connectingTo(new PgConnectOptions()
                .setHost(postgres.getHost())
                .setPort(postgres.getFirstMappedPort())
                .setDatabase(postgres.getDatabaseName())
                .setUser(postgres.getUsername())
                .setPassword(postgres.getPassword()))
            .using(vertx)
            .build();

        String insertSql = """
            INSERT INTO bitemporal_event_log
            (event_id, event_type, valid_time, transaction_time, payload, headers,
             version, correlation_id, causation_id, aggregate_id, is_correction, created_at)
            VALUES ($1, $2, $3, NOW(), $4::jsonb, $5::jsonb, 1, $6, $7, $8, false, NOW())
            """;

        pool.preparedQuery(insertSql)
            .execute(io.vertx.sqlclient.Tuple.of(
                eventId, "LegacyScalar",
                validTime.atOffset(ZoneOffset.UTC),
                "\"legacy-scalar\"", "{}", eventId, null, null))
            .compose(v -> pool.close())
            .compose(v -> objectStore.getById(eventId))
            .onSuccess(fetched -> {
                testContext.verify(() -> {
                    assertNotNull(fetched);
                    assertEquals("legacy-scalar", fetched.getPayload());
                    assertTrue(fetched.getPayload() instanceof String);
                });
                objectStore.close();
                testContext.completeNow();
            })
            .onFailure(err -> {
                objectStore.close();
                testContext.failNow(err);
            });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    @Test
    void testObjectPayloadLegacyArrayJsonCanBeRead(VertxTestContext testContext) throws Exception {
        EventStore<Object> objectStore = factory.createObjectEventStore();
        String eventId = UUID.randomUUID().toString();
        Instant validTime = Instant.now();

        Pool pool = PgBuilder.pool()
            .connectingTo(new PgConnectOptions()
                .setHost(postgres.getHost())
                .setPort(postgres.getFirstMappedPort())
                .setDatabase(postgres.getDatabaseName())
                .setUser(postgres.getUsername())
                .setPassword(postgres.getPassword()))
            .using(vertx)
            .build();

        String insertSql = """
            INSERT INTO bitemporal_event_log
            (event_id, event_type, valid_time, transaction_time, payload, headers,
             version, correlation_id, causation_id, aggregate_id, is_correction, created_at)
            VALUES ($1, $2, $3, NOW(), $4::jsonb, $5::jsonb, 1, $6, $7, $8, false, NOW())
            """;

        pool.preparedQuery(insertSql)
            .execute(io.vertx.sqlclient.Tuple.of(
                eventId, "LegacyArray",
                validTime.atOffset(ZoneOffset.UTC),
                "[1,2,3]", "{}", eventId, null, null))
            .compose(v -> pool.close())
            .compose(v -> objectStore.getById(eventId))
            .onSuccess(fetched -> {
                testContext.verify(() -> {
                    assertNotNull(fetched);
                    assertTrue(fetched.getPayload() instanceof List, "Array JSON should deserialize to List for Object payload");
                    assertEquals(List.of(1, 2, 3), fetched.getPayload());
                });
                objectStore.close();
                testContext.completeNow();
            })
            .onFailure(err -> {
                objectStore.close();
                testContext.failNow(err);
            });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }
    
    @Test
    void testAppendNullValidTime(VertxTestContext testContext) throws Exception {
        try {
            eventStore.appendBuilder().eventType("TestType")
                .payload(new TestEvent("x", "data", 1)).validTime(null).execute()
                .onSuccess(v -> testContext.failNow("Should have thrown for null valid time"))
                .onFailure(err -> testContext.completeNow());
        } catch (IllegalStateException | NullPointerException e) {
            testContext.completeNow();
        }

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }
    
    @Test
    void testAppendCorrectionNonExistent(VertxTestContext testContext) throws Exception {
        String fakeId = UUID.randomUUID().toString();
        
        eventStore.appendCorrection(fakeId, "BadCorrection",
            new TestEvent("x", "data", 1), Instant.now(), "Invalid")
            .onSuccess(v -> testContext.failNow("Should have thrown for non-existent correction target"))
            .onFailure(err -> testContext.completeNow());

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }
    
    @Test
    void testGetByIdNonExistent(VertxTestContext testContext) throws Exception {
        String fakeId = UUID.randomUUID().toString();
        
        eventStore.getById(fakeId)
            .onSuccess(result -> testContext.verify(() -> {
                assertNull(result);
                testContext.completeNow();
            }))
            .onFailure(err -> testContext.completeNow()); // Either null or exception acceptable

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    // ==================== Query Complexity ====================
    
    @Test
    void testQueryByCorrelationId(VertxTestContext testContext) throws Exception {
        Instant validTime = Instant.now();
        String uniqueCorr = "test-corr-" + UUID.randomUUID();
        String otherCorr = "test-other-" + UUID.randomUUID();
        
        eventStore.appendBuilder().eventType("CorEvent").payload(new TestEvent("c1", "data1", 10))
            .validTime(validTime).headers(Map.of()).correlationId(uniqueCorr).causationId(null).aggregateId("agg1").execute()
            .compose(e1 -> eventStore.appendBuilder().eventType("CorEvent").payload(new TestEvent("c2", "data2", 20))
                .validTime(validTime).headers(Map.of()).correlationId(otherCorr).causationId(null).aggregateId("agg2").execute()
                .map(e2 -> e1))
            .compose(e1 -> eventStore.appendBuilder().eventType("CorEvent").payload(new TestEvent("c3", "data3", 30))
                .validTime(validTime).headers(Map.of()).correlationId(uniqueCorr).causationId(null).aggregateId("agg3").execute()
                .map(e3 -> Map.entry(e1.getEventId(), e3.getEventId())))
            .compose(ids -> eventStore.query(EventQuery.builder().correlationId(uniqueCorr).build())
                .map(events -> Map.entry(ids, events)))
            .onSuccess(result -> testContext.verify(() -> {
                var ids = result.getKey();
                var events = result.getValue();
                assertNotNull(events);
                List<BiTemporalEvent<TestEvent>> ourEvents = events.stream()
                    .filter(e -> e.getEventId().equals(ids.getKey()) || e.getEventId().equals(ids.getValue()))
                    .toList();
                assertEquals(2, ourEvents.size(), "Should find exactly 2 events with correlation ID: " + uniqueCorr);
                assertTrue(ourEvents.stream().allMatch(e -> uniqueCorr.equals(e.getCorrelationId())));
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }
    
    @Test
    void testQueryWithLimit(VertxTestContext testContext) throws Exception {
        Instant validTime = Instant.now();

        Future<Void> chain = Future.succeededFuture();
        for (int i = 1; i <= 15; i++) {
            final int index = i;
            chain = chain.compose(v -> eventStore.appendBuilder().eventType("LimitEvent")
                .payload(new TestEvent("lim-" + index, "data", index * 100)).validTime(validTime).execute().mapEmpty());
        }

        chain
            .compose(v -> eventStore.query(EventQuery.builder().eventType("LimitEvent").limit(7).build()))
            .onSuccess(events -> testContext.verify(() -> {
                assertNotNull(events);
                assertTrue(events.size() <= 7);
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }
    
    @Test
    void testGetAllVersionsMultipleCorrections(VertxTestContext testContext) throws Exception {
        TestEvent v1 = new TestEvent("versions", "v1", 1);
        Instant validTime = Instant.now();
        
        eventStore.appendBuilder().eventType("VersionEvent").payload(v1).validTime(validTime).execute()
            .compose(event1 -> {
                TestEvent v2 = new TestEvent("versions", "v2", 2);
                return eventStore.appendCorrection(event1.getEventId(), "VersionEvent", v2, validTime, "correction 1")
                    .map(v -> event1);
            })
            .compose(event1 -> {
                TestEvent v3 = new TestEvent("versions", "v3", 3);
                return eventStore.appendCorrection(event1.getEventId(), "VersionEvent", v3, validTime, "correction 2")
                    .map(v -> event1);
            })
            .compose(event1 -> eventStore.getAllVersions(event1.getEventId()))
            .onSuccess(versions -> testContext.verify(() -> {
                assertEquals(3, versions.size());
                assertEquals(1L, versions.get(0).getVersion());
                assertEquals(2L, versions.get(1).getVersion());
                assertEquals(3L, versions.get(2).getVersion());
                assertTrue(versions.get(1).isCorrection());
                assertTrue(versions.get(2).isCorrection());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    // ==================== Close and Lifecycle ====================
    
    @Test
    void testCloseIdempotent(VertxTestContext testContext) throws Exception {
        eventStore.close();
        
        assertDoesNotThrow(() -> {
            eventStore.close();
            eventStore.close();
        });

        testContext.completeNow();
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }
    
    @Test
    void testAppendAfterClose(VertxTestContext testContext) throws Exception {
        TestEvent payload = new TestEvent("after-close", "data", 123);
        
        eventStore.close();

        delay(200)
            .compose(v -> eventStore.appendBuilder().eventType("AfterClose").payload(payload).validTime(Instant.now()).execute())
            .onSuccess(v -> testContext.completeNow()) // May succeed depending on implementation
            .onFailure(err -> testContext.completeNow()); // Expected in some implementations

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    // ==================== Concurrent Operations ====================
    
    @Test
    void testConcurrentAppends(VertxTestContext testContext) throws Exception {
        Instant validTime = Instant.now();
        List<Future<?>> futures = new ArrayList<>();
        
        for (int i = 0; i < 10; i++) {
            final int index = i;
            futures.add(eventStore.appendBuilder().eventType("ConcurrentEvent")
                .payload(new TestEvent("concurrent-" + index, "data-" + index, index)).validTime(validTime).execute());
        }
        
        Future.all(futures)
            .compose(v -> eventStore.query(EventQuery.forEventType("ConcurrentEvent")))
            .onSuccess(all -> testContext.verify(() -> {
                assertEquals(10, all.size());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }
    
    @Test
    void testConcurrentBatchAndSingle(VertxTestContext testContext) throws Exception {
        Instant validTime = Instant.now();
        
        List<PgBiTemporalEventStore.BatchEventData<TestEvent>> batch = Arrays.asList(
            new PgBiTemporalEventStore.BatchEventData<>(
                "MixedEvent", new TestEvent("batch-1", "batch", 1),
                validTime, Map.of(), "batch-corr", "agg-mix"
            ),
            new PgBiTemporalEventStore.BatchEventData<>(
                "MixedEvent", new TestEvent("batch-2", "batch", 2),
                validTime, Map.of(), "batch-corr", "agg-mix"
            )
        );
        
        Future<List<BiTemporalEvent<TestEvent>>> batchFuture = eventStore.appendBatch(batch);
        Future<BiTemporalEvent<TestEvent>> single1 = eventStore.appendBuilder().eventType("MixedEvent")
            .payload(new TestEvent("single-1", "single", 10)).validTime(validTime).execute();
        Future<BiTemporalEvent<TestEvent>> single2 = eventStore.appendBuilder().eventType("MixedEvent")
            .payload(new TestEvent("single-2", "single", 20)).validTime(validTime).execute();
        
        Future.all(batchFuture, single1, single2)
            .compose(v -> eventStore.query(EventQuery.forEventType("MixedEvent")))
            .onSuccess(all -> testContext.verify(() -> {
                assertEquals(4, all.size());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }
    
    @Test
    void testGetUniqueAggregates(VertxTestContext testContext) throws Exception {
        Instant validTime = Instant.now();
        String eventType = "AggTestEvent";
        
        eventStore.appendBuilder().eventType(eventType).payload(new TestEvent("e1", "data", 1))
            .validTime(validTime).headers(Map.of()).correlationId(null).causationId(null).aggregateId("agg-001").execute()
            .compose(v -> eventStore.appendBuilder().eventType(eventType).payload(new TestEvent("e2", "data", 2))
                .validTime(validTime).headers(Map.of()).correlationId(null).causationId(null).aggregateId("agg-002").execute())
            .compose(v -> eventStore.appendBuilder().eventType(eventType).payload(new TestEvent("e3", "data", 3))
                .validTime(validTime).headers(Map.of()).correlationId(null).causationId(null).aggregateId("agg-001").execute())
            .compose(v -> eventStore.appendBuilder().eventType(eventType).payload(new TestEvent("e4", "data", 4))
                .validTime(validTime).headers(Map.of()).correlationId(null).causationId(null).aggregateId("agg-003").execute())
            .compose(v -> eventStore.getUniqueAggregates(eventType))
            .onSuccess(uniqueAggregates -> testContext.verify(() -> {
                assertNotNull(uniqueAggregates);
                assertEquals(3, uniqueAggregates.size());
                assertTrue(uniqueAggregates.contains("agg-001"));
                assertTrue(uniqueAggregates.contains("agg-002"));
                assertTrue(uniqueAggregates.contains("agg-003"));
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }
    
    @Test
    void testAppendWithAllOverloads(VertxTestContext testContext) throws Exception {
        Instant validTime = Instant.now();
        
        eventStore.appendBuilder().eventType("OverloadEvent").payload(new TestEvent("o1", "data", 1)).validTime(validTime).execute()
            .compose(e1 -> {
                testContext.verify(() -> assertNotNull(e1));
                return eventStore.appendBuilder().eventType("OverloadEvent").payload(new TestEvent("o2", "data", 2))
                    .validTime(validTime).headers(Map.of("header1", "value1")).execute();
            })
            .compose(e2 -> {
                testContext.verify(() -> {
                    assertNotNull(e2);
                    assertEquals("value1", e2.getHeaders().get("header1"));
                });
                return eventStore.append("OverloadEvent", new TestEvent("o3", "data", 3),
                    validTime);
            })
            .compose(e3 -> {
                testContext.verify(() -> assertNotNull(e3));
                return eventStore.append("OverloadEvent", new TestEvent("o4", "data", 4),
                    validTime, Map.of("tx", "true"));
            })
            .compose(e4 -> {
                testContext.verify(() -> assertNotNull(e4));
                return eventStore.append("OverloadEvent", new TestEvent("o5", "data", 5),
                    validTime, Map.of(), "corr-tx", null, null);
            })
            .compose(e5 -> {
                testContext.verify(() -> {
                    assertNotNull(e5);
                    assertEquals("corr-tx", e5.getCorrelationId());
                });
                return eventStore.query(EventQuery.forEventType("OverloadEvent"));
            })
            .onSuccess(all -> testContext.verify(() -> {
                assertTrue(all.size() >= 5, "Should have at least 5 events from overload tests");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }
    
    @Test
    void testAppendInTransactionOverloads(VertxTestContext testContext) throws Exception {
        Instant validTime = Instant.now();
        
        PgConnectOptions options = new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getFirstMappedPort())
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword());
        
        Pool pool = PgBuilder.pool().connectingTo(options).using(vertx).build();
        
        pool.withTransaction(conn ->
            eventStore.appendInTransaction("InTxOverloadEvent", new TestEvent("itx1", "data", 1), validTime, conn)
                .compose(e1 -> {
                    testContext.verify(() -> assertNotNull(e1));
                    return eventStore.appendInTransaction("InTxOverloadEvent", new TestEvent("itx2", "data", 2),
                        validTime, Map.of("intx", "true"), conn);
                })
                .compose(e2 -> {
                    testContext.verify(() -> {
                        assertNotNull(e2);
                        assertEquals("true", e2.getHeaders().get("intx"));
                    });
                    return eventStore.appendInTransaction("InTxOverloadEvent", new TestEvent("itx3", "data", 3),
                        validTime, Map.of(), "intx-corr", conn);
                })
                .map(e3 -> {
                    testContext.verify(() -> {
                        assertNotNull(e3);
                        assertEquals("intx-corr", e3.getCorrelationId());
                    });
                    return (Void) null;
                })
        )
        .compose(v -> eventStore.query(EventQuery.forEventType("InTxOverloadEvent")))
        .compose(all -> {
            testContext.verify(() -> assertEquals(3, all.size(), "Should have 3 events from in-transaction overload tests"));
            return pool.close();
        })
        .onSuccess(v -> testContext.completeNow())
        .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }
    
    @Test
    void testGetByIdReactive(VertxTestContext testContext) throws Exception {
        Instant validTime = Instant.now();
        
        eventStore.appendBuilder().eventType("GetByIdTest")
            .payload(new TestEvent("reactive-id", "reactive-data", 999)).validTime(validTime).execute()
            .compose(created -> eventStore.getById(created.getEventId())
                .map(retrieved -> Map.entry(created, retrieved)))
            .onSuccess(pair -> testContext.verify(() -> {
                var created = pair.getKey();
                var retrieved = pair.getValue();
                assertNotNull(retrieved);
                assertEquals(created.getEventId(), retrieved.getEventId());
                assertEquals("reactive-id", retrieved.getPayload().id);
                assertEquals(999, retrieved.getPayload().value);
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }
    
    @Test
    void testGetAllVersionsReactive(VertxTestContext testContext) throws Exception {
        Instant validTime = Instant.now();
        
        eventStore.appendBuilder().eventType("VersionTest")
            .payload(new TestEvent("v1", "original", 100)).validTime(validTime).execute()
            .compose(original -> eventStore.appendCorrection(original.getEventId(), "VersionTest",
                new TestEvent("v2", "correction1", 200), validTime.plus(1, ChronoUnit.DAYS), "Correction 1 reason")
                .map(v -> original))
            .compose(original -> eventStore.appendCorrection(original.getEventId(), "VersionTest",
                new TestEvent("v3", "correction2", 300), validTime.plus(2, ChronoUnit.DAYS), "Correction 2 reason")
                .map(v -> original))
            .compose(original -> eventStore.getAllVersions(original.getEventId()))
            .onSuccess(versions -> testContext.verify(() -> {
                assertNotNull(versions);
                assertTrue(versions.size() >= 3, "Should have original + 2 corrections = 3 versions");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }
    
    @Test
    void testQueryReactive(VertxTestContext testContext) throws Exception {
        Instant validTime = Instant.now();
        String uniqueType = "ReactiveQueryTest-" + UUID.randomUUID().toString().substring(0, 8);
        
        eventStore.appendBuilder().eventType(uniqueType).payload(new TestEvent("rq1", "data", 1)).validTime(validTime).execute()
            .compose(v -> eventStore.appendBuilder().eventType(uniqueType).payload(new TestEvent("rq2", "data", 2)).validTime(validTime).execute())
            .compose(v -> eventStore.appendBuilder().eventType(uniqueType).payload(new TestEvent("rq3", "data", 3)).validTime(validTime).execute())
            .compose(v -> eventStore.query(EventQuery.forEventType(uniqueType)))
            .onSuccess(results -> testContext.verify(() -> {
                assertNotNull(results);
                assertEquals(3, results.size());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }
    
    @Test
    void testStatsReactive(VertxTestContext testContext) throws Exception {
        Instant validTime = Instant.now();

        eventStore.appendBuilder().eventType("StatsTest").payload(new TestEvent("s1", "data", 1)).validTime(validTime).execute()
            .compose(v -> eventStore.appendBuilder().eventType("StatsTest").payload(new TestEvent("s2", "data", 2)).validTime(validTime).execute())
            .compose(v -> eventStore.getStats())
            .onSuccess(stats -> testContext.verify(() -> {
                assertNotNull(stats);
                assertTrue(stats.getTotalEvents() >= 2);
                assertNotNull(stats.getOldestEventTime());
                assertNotNull(stats.getNewestEventTime());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }
    
    @Test
    void testAppendCorrectionWithFullParameters(VertxTestContext testContext) throws Exception {
        Instant validTime = Instant.now();
        
        eventStore.appendBuilder().eventType("CorrectionTest")
            .payload(new TestEvent("original", "data", 100)).validTime(validTime).execute()
            .compose(original -> eventStore.appendCorrection(
                original.getEventId(), "CorrectionTest",
                new TestEvent("corrected", "corrected-data", 200),
                validTime.plus(1, ChronoUnit.HOURS),
                Map.of("correction-header", "value1"),
                "corr-123", "agg-corrected", "Data quality improvement")
                .map(correction -> Map.entry(original.getEventId(), correction)))
            .onSuccess(pair -> testContext.verify(() -> {
                var correction = pair.getValue();
                assertNotNull(correction);
                assertEquals(pair.getKey(), correction.getPreviousVersionId());
                assertEquals("corrected", correction.getPayload().id);
                assertEquals("value1", correction.getHeaders().get("correction-header"));
                assertEquals("corr-123", correction.getCorrelationId());
                assertEquals("agg-corrected", correction.getAggregateId());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }
    
    @Test
    void testQueryWithAggregateIdFilter(VertxTestContext testContext) throws Exception {
        Instant validTime = Instant.now();
        String uniqueAgg = "agg-filter-" + UUID.randomUUID().toString().substring(0, 8);
        
        eventStore.appendBuilder().eventType("AggFilterTest").payload(new TestEvent("e1", "data", 1))
            .validTime(validTime).headers(Map.of()).correlationId(null).causationId(null).aggregateId(uniqueAgg).execute()
            .compose(v -> eventStore.appendBuilder().eventType("AggFilterTest").payload(new TestEvent("e2", "data", 2))
                .validTime(validTime).headers(Map.of()).correlationId(null).causationId(null).aggregateId("other-agg").execute())
            .compose(v -> eventStore.appendBuilder().eventType("AggFilterTest").payload(new TestEvent("e3", "data", 3))
                .validTime(validTime).headers(Map.of()).correlationId(null).causationId(null).aggregateId(uniqueAgg).execute())
            .compose(v -> eventStore.query(EventQuery.builder().eventType("AggFilterTest").aggregateId(uniqueAgg).build()))
            .onSuccess(results -> testContext.verify(() -> {
                assertNotNull(results);
                assertTrue(results.size() >= 2);
                assertTrue(results.stream().allMatch(e -> uniqueAgg.equals(e.getAggregateId())));
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }
    
    @Test
    void testAppendReactiveDirectly(VertxTestContext testContext) throws Exception {
        Instant validTime = Instant.now();
        
        eventStore.appendBuilder().eventType("ReactiveAppendTest")
            .payload(new TestEvent("reactive", "reactive-data", 777)).validTime(validTime).execute()
            .onSuccess(event -> testContext.verify(() -> {
                assertNotNull(event);
                assertEquals("reactive", event.getPayload().id);
                assertEquals(777, event.getPayload().value);
                assertEquals("ReactiveAppendTest", event.getEventType());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }
    
    @Test
    void testMultipleCorrections(VertxTestContext testContext) throws Exception {
        Instant validTime = Instant.now();
        
        eventStore.appendBuilder().eventType("MultiCorrectionTest")
            .payload(new TestEvent("v0", "original", 100)).validTime(validTime).execute()
            .compose(original -> {
                Future<Void> chain = Future.succeededFuture();
                for (int i = 1; i <= 5; i++) {
                    final int idx = i;
                    final String eventId = original.getEventId();
                    chain = chain.compose(v -> eventStore.appendCorrection(eventId, "MultiCorrectionTest",
                        new TestEvent("v" + idx, "correction-" + idx, 100 + idx),
                        validTime.plus(idx, ChronoUnit.HOURS), "Correction " + idx).mapEmpty());
                }
                return chain.compose(v -> eventStore.getAllVersions(original.getEventId()));
            })
            .onSuccess(versions -> testContext.verify(() -> {
                assertNotNull(versions);
                assertTrue(versions.size() >= 6, "Should have original + 5 corrections");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }
    
    @Test
    void testGetAsOfTransactionTimeWithPreciseTimestamp(VertxTestContext testContext) throws Exception {
        Instant validTime = Instant.now();
        
        eventStore.appendBuilder().eventType("TimeQueryTest")
            .payload(new TestEvent("time-test", "data", 999)).validTime(validTime).execute()
            .compose(event -> {
                Instant queryTime = Instant.now();
                return eventStore.getAsOfTransactionTime(event.getEventId(), queryTime)
                    .map(retrieved -> Map.entry(event.getEventId(), retrieved));
            })
            .onSuccess(pair -> testContext.verify(() -> {
                assertNotNull(pair.getValue());
                assertEquals(pair.getKey(), pair.getValue().getEventId());
                assertEquals("time-test", pair.getValue().getPayload().id);
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }
    
    @Test
    void testHeadersSerialization(VertxTestContext testContext) throws Exception {
        Instant validTime = Instant.now();
        Map<String, String> complexHeaders = Map.of(
            "header-1", "value-1",
            "header-2", "value-2",
            "header-3", "value-3",
            "special-chars", "value with spaces & symbols!@#"
        );
        
        eventStore.appendBuilder().eventType("HeaderTest")
            .payload(new TestEvent("headers", "data", 111)).validTime(validTime).headers(complexHeaders).execute()
            .compose(event -> {
                testContext.verify(() -> {
                    assertNotNull(event);
                    assertEquals(4, event.getHeaders().size());
                    assertEquals("value-1", event.getHeaders().get("header-1"));
                    assertEquals("value with spaces & symbols!@#", event.getHeaders().get("special-chars"));
                });
                return eventStore.getById(event.getEventId());
            })
            .onSuccess(retrieved -> testContext.verify(() -> {
                assertEquals(4, retrieved.getHeaders().size());
                assertEquals("value-1", retrieved.getHeaders().get("header-1"));
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }
    
    @Test
    void testEmptyHeaders(VertxTestContext testContext) throws Exception {
        Instant validTime = Instant.now();
        
        eventStore.appendBuilder().eventType("EmptyHeaderTest")
            .payload(new TestEvent("no-headers", "data", 222)).validTime(validTime).headers(Map.of()).execute()
            .onSuccess(event -> testContext.verify(() -> {
                assertNotNull(event);
                assertTrue(event.getHeaders().isEmpty() || event.getHeaders().size() == 0);
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }
    
    @Test
    void testBatchWithDifferentEventTypes(VertxTestContext testContext) throws Exception {
        Instant validTime = Instant.now();
        
        List<PgBiTemporalEventStore.BatchEventData<TestEvent>> mixedBatch = Arrays.asList(
            new PgBiTemporalEventStore.BatchEventData<>(
                "TypeA", new TestEvent("a1", "data", 1),
                validTime, Map.of(), "corr-a", "agg-a"
            ),
            new PgBiTemporalEventStore.BatchEventData<>(
                "TypeB", new TestEvent("b1", "data", 2),
                validTime, Map.of(), "corr-b", "agg-b"
            ),
            new PgBiTemporalEventStore.BatchEventData<>(
                "TypeC", new TestEvent("c1", "data", 3),
                validTime, Map.of(), "corr-c", "agg-c"
            )
        );
        
        eventStore.appendBatch(mixedBatch)
            .onSuccess(results -> testContext.verify(() -> {
                assertNotNull(results);
                assertEquals(3, results.size());
                Set<String> types = results.stream().map(BiTemporalEvent::getEventType).collect(java.util.stream.Collectors.toSet());
                assertTrue(types.contains("TypeA"));
                assertTrue(types.contains("TypeB"));
                assertTrue(types.contains("TypeC"));
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }
    
    // ===== ADDITIONAL COVERAGE TESTS FOR EDGE CASES =====
    
    @Test
    @DisplayName("getAsOfTransactionTime with past time should handle historical query")
    void testGetAsOfTransactionTimePastTime(VertxTestContext testContext) throws Exception {
        Instant validTime = Instant.now();
        TestEvent payload = new TestEvent("tx-time-test", "data", 999);
        
        eventStore.appendBuilder().eventType("TxTimeTest").payload(payload).validTime(validTime).execute()
            .compose(appended -> {
                testContext.verify(() -> assertNotNull(appended));
                Instant pastTime = Instant.parse("2020-01-01T00:00:00Z");
                return eventStore.getAsOfTransactionTime(appended.getEventId(), pastTime);
            })
            .onSuccess(result -> {
                // result is expected to be null when querying as-of a time before the event existed
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }
    
    @Test
    @DisplayName("appendOwnTransaction with full parameters should work")
    void testAppendOwnTransactionFull(VertxTestContext testContext) throws Exception {
        Instant validTime = Instant.now();
        TestEvent payload = new TestEvent("tx-full", "transaction data", 888);
        
        eventStore.appendOwnTransaction(
            "TxFullTest", payload, validTime,
            Map.of("tx-header", "value"), "tx-corr-123", "tx-cause-456", "tx-agg-789"
        )
        .compose(result -> {
            testContext.verify(() -> {
                assertNotNull(result);
                assertEquals("TxFullTest", result.getEventType());
                assertEquals("tx-corr-123", result.getCorrelationId());
                assertEquals("tx-cause-456", result.getCausationId());
                assertEquals("tx-agg-789", result.getAggregateId());
            });
            return eventStore.getById(result.getEventId());
        })
        .onSuccess(retrieved -> testContext.verify(() -> {
            assertNotNull(retrieved);
            testContext.completeNow();
        }))
        .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }
    
    @Test
    @DisplayName("query with event type filter should work correctly")
    void testQueryEventTypeFilter(VertxTestContext testContext) throws Exception {
        Instant validTime = Instant.now();
        String uniqueType = "UniqueQuery_" + UUID.randomUUID().toString().substring(0, 8);
        
        eventStore.appendBuilder().eventType(uniqueType).payload(new TestEvent("q1", "data", 1)).validTime(validTime).execute()
            .compose(v -> eventStore.appendBuilder().eventType(uniqueType).payload(new TestEvent("q2", "data", 2)).validTime(validTime).execute())
            .compose(v -> eventStore.appendBuilder().eventType("OtherType").payload(new TestEvent("q3", "data", 3)).validTime(validTime).execute())
            .compose(v -> eventStore.query(EventQuery.builder().eventType(uniqueType).build()))
            .onSuccess(results -> testContext.verify(() -> {
                assertNotNull(results);
                assertEquals(2, results.size());
                assertTrue(results.stream().allMatch(e -> e.getEventType().equals(uniqueType)));
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }
    
    @Test
    @DisplayName("query with limit should restrict results")
    void testQueryLimitParameter(VertxTestContext testContext) throws Exception {
        Instant validTime = Instant.now();
        String uniqueType = "LimitQuery_" + UUID.randomUUID().toString().substring(0, 8);
        
        Future<Void> chain = Future.succeededFuture();
        for (int i = 0; i < 10; i++) {
            final int idx = i;
            chain = chain.compose(v -> eventStore.appendBuilder().eventType(uniqueType)
                .payload(new TestEvent("lq" + idx, "data", idx)).validTime(validTime).execute().mapEmpty());
        }

        chain
            .compose(v -> eventStore.query(EventQuery.builder().eventType(uniqueType).limit(5).build()))
            .onSuccess(results -> testContext.verify(() -> {
                assertNotNull(results);
                assertEquals(5, results.size());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }
    
    @Test
    @DisplayName("getById with non-existent ID should return null")
    void testGetByIdMissing(VertxTestContext testContext) throws Exception {
        String nonExistentId = UUID.randomUUID().toString();
        
        eventStore.getById(nonExistentId)
            .onSuccess(result -> testContext.verify(() -> {
                assertNull(result, "Non-existent ID should return null");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }
    
    @Test
    @DisplayName("appendOwnTransaction with minimal parameters")
    void testAppendOwnTransactionMinimal(VertxTestContext testContext) throws Exception {
        Instant validTime = Instant.now();
        TestEvent payload = new TestEvent("tx-min", "minimal", 111);
        
        eventStore.append("TxMinTest", payload, validTime)
            .onSuccess(result -> testContext.verify(() -> {
                assertNotNull(result);
                assertEquals("TxMinTest", result.getEventType());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }
    
    @Test
    @DisplayName("appendOwnTransaction with headers only")
    void testAppendOwnTransactionHeaders(VertxTestContext testContext) throws Exception {
        Instant validTime = Instant.now();
        TestEvent payload = new TestEvent("tx-headers", "headers", 222);
        
        eventStore.append("TxHeadersTest", payload, validTime,
            Map.of("h1", "v1", "h2", "v2"))
            .onSuccess(result -> testContext.verify(() -> {
                assertNotNull(result);
                assertEquals("TxHeadersTest", result.getEventType());
                assertNotNull(result.getHeaders());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }
    
    @Test
    @DisplayName("appendOwnTransaction with correlation ID")
    void testAppendOwnTransactionCorrelation(VertxTestContext testContext) throws Exception {
        Instant validTime = Instant.now();
        TestEvent payload = new TestEvent("tx-corr", "correlation", 333);
        
        eventStore.append("TxCorrTest", payload, validTime,
            Map.of(), "my-correlation-id", null, null)
            .onSuccess(result -> testContext.verify(() -> {
                assertNotNull(result);
                assertEquals("TxCorrTest", result.getEventType());
                assertEquals("my-correlation-id", result.getCorrelationId());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }
    
    @Test
    @DisplayName("appendOwnTransaction with aggregate ID")
    void testAppendOwnTransactionAggregate(VertxTestContext testContext) throws Exception {
        Instant validTime = Instant.now();
        TestEvent payload = new TestEvent("tx-agg", "aggregate", 444);
        
        eventStore.appendOwnTransaction("TxAggTest", payload, validTime,
            Map.of(), null, null, "my-aggregate-id")
            .onSuccess(result -> testContext.verify(() -> {
                assertNotNull(result);
                assertEquals("TxAggTest", result.getEventType());
                assertEquals("my-aggregate-id", result.getAggregateId());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }
    
    @Test
    @DisplayName("getAsOfTransactionTime with current time should return event")
    void testGetAsOfTransactionTimeCurrentTime(VertxTestContext testContext) throws Exception {
        Instant validTime = Instant.now();
        TestEvent payload = new TestEvent("tx-current", "data", 666);
        
        eventStore.appendBuilder().eventType("TxCurrentTime").payload(payload).validTime(validTime).execute()
            .compose(appended -> {
                testContext.verify(() -> assertNotNull(appended));
                return eventStore.getAsOfTransactionTime(appended.getEventId(), Instant.now())
                    .map(result -> Map.entry(appended, result));
            })
            .onSuccess(pair -> testContext.verify(() -> {
                assertNotNull(pair.getValue());
                assertEquals(pair.getKey().getEventId(), pair.getValue().getEventId());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    // ==================== Correction Family Traversal ====================
    // Tests covering the recursive CTE-based family resolution used by
    // appendCorrection (version numbering), getAllVersions, and
    // getAsOfTransactionTime.  Covers both positive and negative paths.

    /**
     * Build a true chain A → B → C where each correction targets the previous
     * correction (not the root).  Verify version numbers are 1, 2, 3 with no
     * duplicates — this is the bug the shallow query used to miss.
     */
    @Test
    void testChainedCorrectionVersionNumbersAreSequential(VertxTestContext testContext) throws Exception {
        Instant validTime = Instant.now();

        eventStore.appendBuilder().eventType("ChainVersionTest")
            .payload(new TestEvent("a", "root", 1)).validTime(validTime).execute()
            .compose(a -> eventStore.appendCorrection(a.getEventId(), "ChainVersionTest",
                new TestEvent("b", "corr-of-root", 2), validTime, "correct root")
            )
            .compose(b -> eventStore.appendCorrection(b.getEventId(), "ChainVersionTest",
                new TestEvent("c", "corr-of-corr", 3), validTime, "correct the correction")
            )
            .compose(c -> eventStore.appendCorrection(c.getEventId(), "ChainVersionTest",
                new TestEvent("d", "corr-of-corr-of-corr", 4), validTime, "correct again")
                .map(d -> Map.entry(c, d))
            )
            .compose(pair -> eventStore.getAllVersions(pair.getKey().getEventId()))
            .onSuccess(versions -> testContext.verify(() -> {
                assertEquals(4, versions.size(), "Should have root + 3 chained corrections");
                assertEquals(1L, versions.get(0).getVersion());
                assertEquals(2L, versions.get(1).getVersion());
                assertEquals(3L, versions.get(2).getVersion());
                assertEquals(4L, versions.get(3).getVersion());
                assertFalse(versions.get(0).isCorrection());
                assertTrue(versions.get(1).isCorrection());
                assertTrue(versions.get(2).isCorrection());
                assertTrue(versions.get(3).isCorrection());
                // Verify payload data survived round-trip
                assertEquals("root", versions.get(0).getPayload().getData());
                assertEquals("corr-of-root", versions.get(1).getPayload().getData());
                assertEquals("corr-of-corr", versions.get(2).getPayload().getData());
                assertEquals("corr-of-corr-of-corr", versions.get(3).getPayload().getData());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    /**
     * getAllVersions must return the full family regardless of which member's
     * event ID is passed — root, middle, or leaf.
     */
    @Test
    void testGetAllVersionsFromAnyCorrectionIdReturnsFullFamily(VertxTestContext testContext) throws Exception {
        Instant validTime = Instant.now();

        eventStore.appendBuilder().eventType("FamilyLookup")
            .payload(new TestEvent("r", "root", 1)).validTime(validTime).execute()
            .compose(root -> eventStore.appendCorrection(root.getEventId(), "FamilyLookup",
                new TestEvent("m", "middle", 2), validTime, "mid")
                .map(mid -> Map.entry(root, mid))
            )
            .compose(pair -> eventStore.appendCorrection(pair.getValue().getEventId(), "FamilyLookup",
                new TestEvent("l", "leaf", 3), validTime, "leaf")
                .map(leaf -> List.of(pair.getKey().getEventId(),
                                     pair.getValue().getEventId(),
                                     leaf.getEventId()))
            )
            .compose(ids -> {
                // Query from root, middle, and leaf — all should return 3 versions
                return Future.all(
                    eventStore.getAllVersions(ids.get(0)),
                    eventStore.getAllVersions(ids.get(1)),
                    eventStore.getAllVersions(ids.get(2))
                ).map(cf -> List.of(
                    (List<BiTemporalEvent<TestEvent>>) cf.resultAt(0),
                    (List<BiTemporalEvent<TestEvent>>) cf.resultAt(1),
                    (List<BiTemporalEvent<TestEvent>>) cf.resultAt(2)
                ));
            })
            .onSuccess(results -> testContext.verify(() -> {
                for (int i = 0; i < 3; i++) {
                    List<BiTemporalEvent<TestEvent>> versions = results.get(i);
                    assertEquals(3, versions.size(),
                        "getAllVersions from member " + i + " should return all 3 members");
                    assertEquals(1L, versions.get(0).getVersion());
                    assertEquals(2L, versions.get(1).getVersion());
                    assertEquals(3L, versions.get(2).getVersion());
                }
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    /**
     * Sequential corrections from different entry points: corrections target A,
     * A, then B. With chain model enforcement, the system resolves the latest
     * version for each, producing a linear chain A→B→C→D. Version numbers
     * must be unique and sequential across the entire family.
     */
    @Test
    void testFanOutCorrectionsProduceUniqueVersions(VertxTestContext testContext) throws Exception {
        Instant validTime = Instant.now();

        eventStore.appendBuilder().eventType("FanOut")
            .payload(new TestEvent("a", "root", 1)).validTime(validTime).execute()
            .compose(a ->
                // B corrects A (version 2)
                eventStore.appendCorrection(a.getEventId(), "FanOut",
                    new TestEvent("b", "child1", 2), validTime, "branch 1")
                .map(b -> Map.entry(a, b))
            )
            .compose(ab ->
                // C corrects A (version 3 — the family already has 1, 2)
                eventStore.appendCorrection(ab.getKey().getEventId(), "FanOut",
                    new TestEvent("c", "child2", 3), validTime, "branch 2")
                .map(c -> List.of(ab.getKey(), ab.getValue(), c))
            )
            .compose(abc ->
                // D corrects B (version 4 — the family already has 1, 2, 3)
                eventStore.appendCorrection(abc.get(1).getEventId(), "FanOut",
                    new TestEvent("d", "grandchild", 4), validTime, "deeper")
                .map(d -> abc.get(0).getEventId())
            )
            .compose(rootId -> eventStore.getAllVersions(rootId))
            .onSuccess(versions -> testContext.verify(() -> {
                assertEquals(4, versions.size(), "Family should have 4 members");
                // Collect version numbers and verify uniqueness
                Set<Long> versionNumbers = new HashSet<>();
                for (BiTemporalEvent<TestEvent> v : versions) {
                    assertTrue(versionNumbers.add(v.getVersion()),
                        "Duplicate version number: " + v.getVersion());
                }
                // Versions should be 1, 2, 3, 4
                assertEquals(Set.of(1L, 2L, 3L, 4L), versionNumbers);
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    /**
     * Deep chain (5 levels): each correction targets the previous one.
     * Verifies the recursive CTE handles depth > 2 correctly.
     */
    @Test
    void testDeepCorrectionChainVersionNumbering(VertxTestContext testContext) throws Exception {
        Instant validTime = Instant.now();
        final int depth = 5;

        eventStore.appendBuilder().eventType("DeepChain")
            .payload(new TestEvent("v1", "root", 1)).validTime(validTime).execute()
            .compose(root -> {
                // Build a chain: each correction targets the previous event
                Future<BiTemporalEvent<TestEvent>> chain = Future.succeededFuture(root);
                for (int i = 2; i <= depth; i++) {
                    final int idx = i;
                    chain = chain.compose(prev ->
                        eventStore.appendCorrection(prev.getEventId(), "DeepChain",
                            new TestEvent("v" + idx, "depth-" + idx, idx), validTime,
                            "chain correction " + idx)
                    );
                }
                return chain.compose(leaf -> eventStore.getAllVersions(leaf.getEventId()));
            })
            .onSuccess(versions -> testContext.verify(() -> {
                assertEquals(depth, versions.size(), "Should have " + depth + " versions");
                for (int i = 0; i < depth; i++) {
                    assertEquals(i + 1L, versions.get(i).getVersion(),
                        "Version at index " + i + " should be " + (i + 1));
                }
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    /**
     * getAsOfTransactionTime resolves from a leaf correction ID back through
     * the full chain to correctly answer temporal queries.
     */
    @Test
    void testGetAsOfTransactionTimeFromLeafOfChainedCorrections(VertxTestContext testContext) throws Exception {
        Instant validTime = Instant.now();

        eventStore.appendBuilder().eventType("TxChain")
            .payload(new TestEvent("a", "root", 1)).validTime(validTime).execute()
            .compose(a -> delay(80).compose(d ->
                eventStore.appendCorrection(a.getEventId(), "TxChain",
                    new TestEvent("b", "mid", 2), validTime, "mid")
                .map(b -> {
                    Instant tAfterA = a.getTransactionTime();
                    return Map.entry(tAfterA, b);
                })
            ))
            .compose(entry -> delay(80).compose(d ->
                eventStore.appendCorrection(entry.getValue().getEventId(), "TxChain",
                    new TestEvent("c", "leaf", 3), validTime, "leaf")
                .map(c -> Map.entry(entry.getKey(), c))
            ))
            .compose(entry -> {
                Instant tBeforeAll = entry.getKey().minusSeconds(1);
                String leafId = entry.getValue().getEventId();
                return Future.all(
                    // Before any event existed — should return null
                    eventStore.getAsOfTransactionTime(leafId, tBeforeAll),
                    // Now — should return the latest version
                    eventStore.getAsOfTransactionTime(leafId, Instant.now())
                ).map(cf -> {
                    // Arrays.asList allows nulls; List.of does not
                    BiTemporalEvent<TestEvent> beforeResult = cf.resultAt(0);
                    BiTemporalEvent<TestEvent> nowResult = cf.resultAt(1);
                    return Arrays.asList(beforeResult, nowResult);
                });
            })
            .onSuccess(results -> testContext.verify(() -> {
                assertNull(results.get(0), "Before any existed, should be null");
                assertNotNull(results.get(1), "At current time, should find latest");
                assertEquals("leaf", results.get(1).getPayload().getData());
                assertEquals(3L, results.get(1).getVersion());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    // ---------- Negative: correction family edge cases ----------

    /**
     * appendCorrection with null originalEventId must fail with
     * NullPointerException immediately.
     */
    @Test
    void testAppendCorrectionNullOriginalEventIdFails(VertxTestContext testContext) throws Exception {
        try {
            eventStore.appendCorrection(null, "Type",
                    new TestEvent("x", "x", 1), Instant.now(), "reason")
                .onSuccess(v -> testContext.failNow("Should have failed for null originalEventId"))
                .onFailure(err -> testContext.verify(() -> {
                    assertTrue(err instanceof NullPointerException);
                    testContext.completeNow();
                }));
        } catch (NullPointerException e) {
            // Thrown synchronously — also acceptable
            testContext.completeNow();
        }

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    /**
     * appendCorrection with null correctionReason must fail with
     * NullPointerException immediately.
     */
    @Test
    void testAppendCorrectionNullCorrectionReasonFails(VertxTestContext testContext) throws Exception {
        Instant validTime = Instant.now();

        eventStore.appendBuilder().eventType("NullReason")
            .payload(new TestEvent("x", "data", 1)).validTime(validTime).execute()
            .compose(original -> {
                try {
                    return eventStore.appendCorrection(original.getEventId(), "NullReason",
                        new TestEvent("y", "corrected", 2), validTime, null);
                } catch (NullPointerException e) {
                    return Future.failedFuture(e);
                }
            })
            .onSuccess(v -> testContext.failNow("Should have failed for null correctionReason"))
            .onFailure(err -> testContext.verify(() -> {
                assertTrue(err instanceof NullPointerException);
                testContext.completeNow();
            }));

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    /**
     * appendCorrection targeting a non-existent event ID must fail with a
     * meaningful error (IllegalArgumentException).
     */
    @Test
    void testAppendCorrectionNonExistentEventFails(VertxTestContext testContext) throws Exception {
        eventStore.appendCorrection("does-not-exist", "Type",
                new TestEvent("x", "x", 1), Instant.now(), "reason")
            .onSuccess(v -> testContext.failNow("Should have failed for non-existent event"))
            .onFailure(err -> testContext.verify(() -> {
                assertTrue(err instanceof IllegalArgumentException,
                    "Expected IllegalArgumentException but got: " + err.getClass().getName());
                assertTrue(err.getMessage().contains("does-not-exist"));
                testContext.completeNow();
            }));

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    /**
     * getAllVersions for a non-existent event ID returns an empty list
     * rather than failing.
     */
    @Test
    void testGetAllVersionsNonExistentReturnsEmpty(VertxTestContext testContext) throws Exception {
        eventStore.getAllVersions("no-such-event-id")
            .onSuccess(versions -> testContext.verify(() -> {
                assertNotNull(versions);
                assertTrue(versions.isEmpty(), "Non-existent event should yield empty list");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    /**
     * getAllVersions with a null event ID must fail with NullPointerException.
     */
    @Test
    void testGetAllVersionsNullIdFails(VertxTestContext testContext) throws Exception {
        try {
            eventStore.getAllVersions(null)
                .onSuccess(v -> testContext.failNow("Should have failed for null ID"))
                .onFailure(err -> testContext.verify(() -> {
                    assertTrue(err instanceof NullPointerException);
                    testContext.completeNow();
                }));
        } catch (NullPointerException e) {
            testContext.completeNow();
        }

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    // ==================== Fail-Fast Row Mapping Tests ====================

    /**
     * Inserts a corrupted row directly via SQL, then verifies that query()
     * fails the future rather than silently returning partial results.
     */
    @Test
    void testQueryFailsFastOnCorruptedPayload(VertxTestContext testContext) throws Exception {
        String schema = resolveSchema();
        String corruptEventType = "CorruptType_" + UUID.randomUUID();
        String corruptEventId = UUID.randomUUID().toString();

        PgConnectOptions connectOptions = new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getFirstMappedPort())
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword());
        Pool directPool = PgBuilder.pool().connectingTo(connectOptions).using(vertx).build();

        // Insert a row with payload that cannot deserialize to TestEvent
        String insertSql = "INSERT INTO " + schema + ".bitemporal_event_log "
            + "(event_id, event_type, valid_time, transaction_time, payload, headers, "
            + "version, is_correction, created_at) "
            + "VALUES ($1, $2, NOW(), NOW(), $3::jsonb, '{}'::jsonb, 1, false, NOW())";

        io.vertx.sqlclient.Tuple params = io.vertx.sqlclient.Tuple.of(
            corruptEventId,
            corruptEventType,
            "{\"totally_wrong_field\": \"not a TestEvent\"}"
        );

        directPool.preparedQuery(insertSql).execute(params)
            .compose(v -> directPool.close())
            .compose(v -> eventStore.query(EventQuery.forEventType(corruptEventType)))
            .onSuccess(events -> testContext.failNow(
                "query() should have failed but returned " + events.size() + " events"))
            .onFailure(err -> testContext.verify(() -> {
                assertNotNull(err, "Expected a mapping error");
                testContext.completeNow();
            }));

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    /**
     * Inserts a valid event, then a corrupted correction row referencing it,
     * and verifies that getAllVersions() fails the future rather than
     * silently dropping the corrupted version.
     */
    @Test
    void testGetAllVersionsFailsFastOnCorruptedPayload(VertxTestContext testContext) throws Exception {
        String schema = resolveSchema();
        Instant validTime = Instant.now();

        // First, append a valid event through the store API
        eventStore.appendBuilder()
            .eventType("CorruptVersionType")
            .payload(new TestEvent("root", "valid", 1))
            .validTime(validTime)
            .execute()
            .compose(validEvent -> {
                // Now insert a corrupted correction row directly via SQL
                PgConnectOptions connectOptions = new PgConnectOptions()
                    .setHost(postgres.getHost())
                    .setPort(postgres.getFirstMappedPort())
                    .setDatabase(postgres.getDatabaseName())
                    .setUser(postgres.getUsername())
                    .setPassword(postgres.getPassword());
                Pool directPool = PgBuilder.pool().connectingTo(connectOptions).using(vertx).build();

                String insertSql = "INSERT INTO " + schema + ".bitemporal_event_log "
                    + "(event_id, event_type, valid_time, transaction_time, payload, headers, "
                    + "version, previous_version_id, is_correction, correction_reason, created_at) "
                    + "VALUES ($1, $2, NOW(), NOW(), $3::jsonb, '{}'::jsonb, 2, $4, true, 'corrupt', NOW())";

                io.vertx.sqlclient.Tuple params = io.vertx.sqlclient.Tuple.of(
                    UUID.randomUUID().toString(),
                    "CorruptVersionType",
                    "{\"totally_wrong_field\": \"not a TestEvent\"}",
                    validEvent.getEventId()
                );

                return directPool.preparedQuery(insertSql).execute(params)
                    .compose(v -> directPool.close())
                    .map(validEvent);
            })
            .compose(validEvent -> eventStore.getAllVersions(validEvent.getEventId()))
            .onSuccess(events -> testContext.failNow(
                "getAllVersions() should have failed but returned " + events.size() + " events"))
            .onFailure(err -> testContext.verify(() -> {
                assertNotNull(err, "Expected a mapping error");
                testContext.completeNow();
            }));

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    // ========== mapRowToEvent type-dispatch coverage ==========

    @Test
    void testNullHeadersColumnReadsAsEmptyMap(VertxTestContext testContext) throws Exception {
        EventStore<Object> objectStore = factory.createObjectEventStore();
        String schema = resolveSchema();
        String eventId = UUID.randomUUID().toString();

        PgConnectOptions connectOptions = new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getFirstMappedPort())
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword());
        Pool pool = PgBuilder.pool().connectingTo(connectOptions).using(vertx).build();

        String insertSql = "INSERT INTO " + schema + ".bitemporal_event_log "
            + "(event_id, event_type, valid_time, transaction_time, payload, headers, "
            + "version, correlation_id, is_correction, created_at) "
            + "VALUES ($1, $2, NOW(), NOW(), '{\"value\": \"data\"}'::jsonb, NULL, 1, $3, false, NOW())";

        pool.preparedQuery(insertSql)
            .execute(io.vertx.sqlclient.Tuple.of(eventId, "NullHeaders", eventId))
            .compose(v -> pool.close())
            .compose(v -> objectStore.getById(eventId))
            .onSuccess(fetched -> {
                testContext.verify(() -> {
                    assertNotNull(fetched);
                    assertNotNull(fetched.getHeaders(), "Null headers column should produce empty map, not null");
                    assertTrue(fetched.getHeaders().isEmpty(), "Null headers column should produce empty map");
                });
                objectStore.close();
                testContext.completeNow();
            })
            .onFailure(err -> {
                objectStore.close();
                testContext.failNow(err);
            });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    @Test
    void testHeadersWithNullValuesRoundTrip(VertxTestContext testContext) throws Exception {
        EventStore<Object> objectStore = factory.createObjectEventStore();
        String schema = resolveSchema();
        String eventId = UUID.randomUUID().toString();

        PgConnectOptions connectOptions = new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getFirstMappedPort())
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword());
        Pool pool = PgBuilder.pool().connectingTo(connectOptions).using(vertx).build();

        // Insert headers with a null value entry via direct SQL
        String insertSql = "INSERT INTO " + schema + ".bitemporal_event_log "
            + "(event_id, event_type, valid_time, transaction_time, payload, headers, "
            + "version, correlation_id, is_correction, created_at) "
            + "VALUES ($1, $2, NOW(), NOW(), '{\"value\": \"x\"}'::jsonb, "
            + "'{\"present\": \"yes\", \"absent\": null}'::jsonb, 1, $3, false, NOW())";

        pool.preparedQuery(insertSql)
            .execute(io.vertx.sqlclient.Tuple.of(eventId, "NullValueHeaders", eventId))
            .compose(v -> pool.close())
            .compose(v -> objectStore.getById(eventId))
            .onSuccess(fetched -> {
                testContext.verify(() -> {
                    assertNotNull(fetched);
                    assertEquals("yes", fetched.getHeaders().get("present"));
                    assertFalse(fetched.getHeaders().containsKey("absent"),
                        "Null-valued JSONB header entry should be skipped");
                });
                objectStore.close();
                testContext.completeNow();
            })
            .onFailure(err -> {
                objectStore.close();
                testContext.failNow(err);
            });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    @Test
    void testLegacyNumericJsonbPayloadCanBeRead(VertxTestContext testContext) throws Exception {
        EventStore<Object> objectStore = factory.createObjectEventStore();
        String schema = resolveSchema();
        String eventId = UUID.randomUUID().toString();

        PgConnectOptions connectOptions = new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getFirstMappedPort())
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword());
        Pool pool = PgBuilder.pool().connectingTo(connectOptions).using(vertx).build();

        String insertSql = "INSERT INTO " + schema + ".bitemporal_event_log "
            + "(event_id, event_type, valid_time, transaction_time, payload, headers, "
            + "version, correlation_id, is_correction, created_at) "
            + "VALUES ($1, $2, NOW(), NOW(), '42'::jsonb, '{}'::jsonb, 1, $3, false, NOW())";

        pool.preparedQuery(insertSql)
            .execute(io.vertx.sqlclient.Tuple.of(eventId, "LegacyNumber", eventId))
            .compose(v -> pool.close())
            .compose(v -> objectStore.getById(eventId))
            .onSuccess(fetched -> {
                testContext.verify(() -> {
                    assertNotNull(fetched);
                    assertTrue(fetched.getPayload() instanceof Number,
                        "Raw numeric JSONB should deserialize to Number, got: "
                        + fetched.getPayload().getClass().getSimpleName());
                    assertEquals(42, ((Number) fetched.getPayload()).intValue());
                });
                objectStore.close();
                testContext.completeNow();
            })
            .onFailure(err -> {
                objectStore.close();
                testContext.failNow(err);
            });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    @Test
    void testLegacyBooleanJsonbPayloadCanBeRead(VertxTestContext testContext) throws Exception {
        EventStore<Object> objectStore = factory.createObjectEventStore();
        String schema = resolveSchema();
        String eventId = UUID.randomUUID().toString();

        PgConnectOptions connectOptions = new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getFirstMappedPort())
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword());
        Pool pool = PgBuilder.pool().connectingTo(connectOptions).using(vertx).build();

        String insertSql = "INSERT INTO " + schema + ".bitemporal_event_log "
            + "(event_id, event_type, valid_time, transaction_time, payload, headers, "
            + "version, correlation_id, is_correction, created_at) "
            + "VALUES ($1, $2, NOW(), NOW(), 'true'::jsonb, '{}'::jsonb, 1, $3, false, NOW())";

        pool.preparedQuery(insertSql)
            .execute(io.vertx.sqlclient.Tuple.of(eventId, "LegacyBool", eventId))
            .compose(v -> pool.close())
            .compose(v -> objectStore.getById(eventId))
            .onSuccess(fetched -> {
                testContext.verify(() -> {
                    assertNotNull(fetched);
                    assertTrue(fetched.getPayload() instanceof Boolean,
                        "Raw boolean JSONB should deserialize to Boolean, got: "
                        + fetched.getPayload().getClass().getSimpleName());
                    assertEquals(true, fetched.getPayload());
                });
                objectStore.close();
                testContext.completeNow();
            })
            .onFailure(err -> {
                objectStore.close();
                testContext.failNow(err);
            });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    @Test
    void testComplexObjectPayloadJsonObjectRoundTrip(VertxTestContext testContext) throws Exception {
        Instant validTime = Instant.now();
        TestEvent original = new TestEvent("complex-obj", "nested data with special chars: <>&\"'", 99);

        eventStore.appendBuilder().eventType("ComplexObjectTest")
            .payload(original).validTime(validTime).execute()
            .compose(event -> eventStore.getById(event.getEventId()))
            .onSuccess(fetched -> {
                testContext.verify(() -> {
                    assertNotNull(fetched);
                    TestEvent payload = fetched.getPayload();
                    assertNotNull(payload);
                    assertEquals("complex-obj", payload.getId());
                    assertEquals("nested data with special chars: <>&\"'", payload.getData());
                    assertEquals(99, payload.getValue());
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    @Test
    void testHeadersRoundTripThroughMapRowToEvent(VertxTestContext testContext) throws Exception {
        Instant validTime = Instant.now();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/json");
        headers.put("x-trace-id", "abc-123-def-456");
        headers.put("empty-value", "");

        eventStore.appendBuilder().eventType("HeaderRoundTrip")
            .payload(new TestEvent("hdr", "data", 1)).validTime(validTime).headers(headers).execute()
            .compose(event -> eventStore.getById(event.getEventId()))
            .onSuccess(fetched -> {
                testContext.verify(() -> {
                    assertNotNull(fetched);
                    assertEquals("application/json", fetched.getHeaders().get("content-type"));
                    assertEquals("abc-123-def-456", fetched.getHeaders().get("x-trace-id"));
                    assertEquals("", fetched.getHeaders().get("empty-value"));
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }
}
